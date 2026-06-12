import logging
import os
import tempfile
from fastapi import FastAPI, UploadFile, File, Form
from pydantic import BaseModel
from markitdown import MarkItDown
from email_reply_parser import EmailReplyParser

logging.getLogger("pdfminer").setLevel(logging.ERROR)

app = FastAPI()
md = MarkItDown()


class StripReplyRequest(BaseModel):
    body: str


class StripReplyResponse(BaseModel):
    stripped: str


class ConvertAttachmentResponse(BaseModel):
    markdown: str
    truncated: bool
    error: str | None = None


FALLBACK_BODY_CHARS = 2_000


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
        return ConvertAttachmentResponse(
            markdown=text,
            truncated=False,
        )
    except Exception as e:
        return ConvertAttachmentResponse(markdown="", truncated=False, error=str(e))
    finally:
        if tmp_path and os.path.exists(tmp_path):
            os.unlink(tmp_path)
