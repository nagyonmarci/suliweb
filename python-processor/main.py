import logging
import os
import re
import tempfile
from pathlib import Path

from fastapi import FastAPI, UploadFile, File, Form
from mcp.server.fastmcp import FastMCP
from markitdown import MarkItDown
from email_reply_parser import EmailReplyParser
from pydantic import BaseModel

logging.getLogger("pdfminer").setLevel(logging.ERROR)

app = FastAPI()
md = MarkItDown()
mcp_server = FastMCP("suliweb-python")

ATTACHMENTS_DIR = Path("/app/attachments").resolve()
HASHES_DIR = (ATTACHMENTS_DIR / "hashes").resolve()

FALLBACK_BODY_CHARS = 2_000

# ---------------------------------------------------------------------------
# Pydantic models
# ---------------------------------------------------------------------------

class StripReplyRequest(BaseModel):
    body: str


class StripReplyResponse(BaseModel):
    stripped: str


class ConvertAttachmentResponse(BaseModel):
    markdown: str
    truncated: bool
    error: str | None = None


class Entity(BaseModel):
    name: str
    type: str  # PERSON | ORG | TOPIC | LOCATION | CLAIM | MECHANISM


class MultimodalEvidence(BaseModel):
    evidence_type: str  # FIGURE | TABLE | CHART | FORMULA | IMAGE
    reference: str      # alt text, caption, or src


class Relation(BaseModel):
    subject: str
    predicate: str  # PROVES | CONTRADICTS | EXTENDS | INVOLVES | MENTIONS
    object: str


class K1ParsedDocument(BaseModel):
    text_blocks: list[str]
    tables: list[dict]
    entities: list[Entity]
    multimodal_evidence: list[MultimodalEvidence]
    citations: list[str]
    relations: list[Relation]
    error: str | None = None


# ---------------------------------------------------------------------------
# K1 parsing helpers
# ---------------------------------------------------------------------------

_IMG_RE      = re.compile(r'!\[([^\]]*)\]\(([^)]+)\)')
_CITATION_RE = re.compile(r'(?<!!)\[(?:\d[\d,\s\-]*|\w[^[\]]{0,40}(?:,\s*\d{4})?)\]')
_TABLE_SEP_RE = re.compile(r'^\|[\s\-|:]+\|$')


def _extract_text_blocks(text: str) -> list[str]:
    blocks = [b.strip() for b in re.split(r'\n{2,}', text)]
    return [b for b in blocks if b and not b.startswith('|')]


def _extract_tables(text: str) -> list[dict]:
    tables: list[dict] = []
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith('|') and i + 1 < len(lines) and _TABLE_SEP_RE.match(lines[i + 1]):
            headers = [h.strip() for h in line.strip('|').split('|')]
            rows: list[dict] = []
            j = i + 2
            while j < len(lines) and lines[j].startswith('|'):
                cells = [c.strip() for c in lines[j].strip('|').split('|')]
                rows.append(dict(zip(headers, cells)))
                j += 1
            tables.append({"headers": headers, "rows": rows})
            i = j
        else:
            i += 1
    return tables


def _extract_multimodal_evidence(text: str) -> list[MultimodalEvidence]:
    return [
        MultimodalEvidence(evidence_type="FIGURE", reference=alt.strip() or src.strip())
        for alt, src in _IMG_RE.findall(text)
    ]


def _extract_citations(text: str) -> list[str]:
    # de-duplicate, preserve order
    seen: set[str] = set()
    result: list[str] = []
    for m in _CITATION_RE.findall(text):
        if m not in seen:
            seen.add(m)
            result.append(m)
    return result


def parse_to_k1(markdown: str) -> K1ParsedDocument:
    """
    Structural parse of a MarkItDown-produced Markdown string into K1 schema.
    entities and relations are left empty — the K1 4B model (Phase 5) fills them.
    """
    return K1ParsedDocument(
        text_blocks=_extract_text_blocks(markdown),
        tables=_extract_tables(markdown),
        entities=[],
        multimodal_evidence=_extract_multimodal_evidence(markdown),
        citations=_extract_citations(markdown),
        relations=[],
    )


# ---------------------------------------------------------------------------
# Existing REST endpoints (unchanged — AttachmentProcessingService depends on these)
# ---------------------------------------------------------------------------

@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/strip-reply", response_model=StripReplyResponse)
def strip_reply(req: StripReplyRequest):
    try:
        stripped = EmailReplyParser.parse_reply(req.body)
        if not stripped or not stripped.strip():
            stripped = req.body[:FALLBACK_BODY_CHARS]
    except Exception:
        stripped = req.body[:FALLBACK_BODY_CHARS]
    return StripReplyResponse(stripped=stripped.strip())


@app.post("/convert-attachment", response_model=ConvertAttachmentResponse)
async def convert_attachment(file: UploadFile = File(...), filename: str = Form("")):
    suffix = os.path.splitext(filename or file.filename or "")[1] or ".bin"
    tmp_path = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
            tmp_path = tmp.name
            content = await file.read()
            tmp.write(content)

        result = md.convert(tmp_path)
        text = result.text_content or ""
        return ConvertAttachmentResponse(markdown=text, truncated=False)
    except Exception as e:
        return ConvertAttachmentResponse(markdown="", truncated=False, error=str(e))
    finally:
        if tmp_path and os.path.exists(tmp_path):
            os.unlink(tmp_path)


# ---------------------------------------------------------------------------
# New K1 endpoint — used by EntityExtractionService (Phase 5)
# ---------------------------------------------------------------------------

@app.post("/parse-document", response_model=K1ParsedDocument)
async def parse_document(file: UploadFile = File(...), filename: str = Form("")):
    """Convert a file upload to a K1-structured document (text_blocks, tables, entities, etc.)."""
    suffix = os.path.splitext(filename or file.filename or "")[1] or ".bin"
    tmp_path = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
            tmp_path = tmp.name
            content = await file.read()
            tmp.write(content)

        result = md.convert(tmp_path)
        return parse_to_k1(result.text_content or "")
    except Exception as e:
        return K1ParsedDocument(
            text_blocks=[], tables=[], entities=[],
            multimodal_evidence=[], citations=[], relations=[],
            error=str(e),
        )
    finally:
        if tmp_path and os.path.exists(tmp_path):
            os.unlink(tmp_path)


# ---------------------------------------------------------------------------
# MCP Tool: convert a volume-mounted file to K1-structured JSON by path
# ---------------------------------------------------------------------------

@mcp_server.tool()
def convert_file_to_markdown(file_path: str) -> str:
    """Convert a file in the attachments volume to a K1-structured JSON document.

    file_path must be an absolute path under /app/attachments/.
    Returns a JSON object matching the K1ParsedDocument schema
    (text_blocks, tables, entities, multimodal_evidence, citations, relations),
    or a string starting with 'ERROR:' on failure.
    """
    resolved = Path(file_path).resolve()
    # ponytail: path traversal guard — reject anything outside the attachments mount
    if not str(resolved).startswith(str(ATTACHMENTS_DIR)):
        return "ERROR: path outside attachments directory"
    if not resolved.exists():
        return f"ERROR: file not found: {file_path}"
    try:
        result = md.convert(str(resolved))
        doc = parse_to_k1(result.text_content or "")
        return doc.model_dump_json()
    except Exception as e:
        return f"ERROR: {e}"


# ---------------------------------------------------------------------------
# MCP Resource: read a deduplicated file from the hashes directory by hash ID
# ---------------------------------------------------------------------------

@mcp_server.resource("file://attachments/hashes/{hash_id}")
def get_hash_file(hash_id: str) -> str:
    """Read a deduplicated attachment from the hashes directory by its SHA hash ID."""
    resolved = (HASHES_DIR / hash_id).resolve()
    # ponytail: prevent traversal out of hashes dir
    if not str(resolved).startswith(str(HASHES_DIR)):
        return "ERROR: invalid hash id"
    if not resolved.exists():
        return f"ERROR: hash file not found: {hash_id}"
    try:
        return resolved.read_text(errors="replace")
    except Exception as e:
        return f"ERROR: {e}"


# Mount MCP SSE transport at /mcp  (SSE stream: /mcp/sse, messages: /mcp/messages)
app.mount("/mcp", mcp_server.sse_app())
