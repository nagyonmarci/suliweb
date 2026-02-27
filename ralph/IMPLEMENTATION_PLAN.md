# Implementation Plan – NAS Indexer & RAG Pipeline

## Codebase Patterns
<!-- Ralph ide írja a felfedezett mintákat iterációk között -->
- SQLite connections: always WAL + check_same_thread=False + row_factory=sqlite3.Row
- Activity guard: copy from indexer/activity_guard.py, adapt env vars only
- Docker services: never use localhost for inter-container comms, use service name
- Ollama from Docker: http://host.docker.internal:11434

---

## Phase 1 – PST Extractor

### TASK-01 – pst-extractor service skeleton
**Status**: COMPLETED
**Spec**: @specs/pst-extractor.md
**Read first**: @indexer/activity_guard.py @indexer/nas_api.py @indexer/db.py @docs/SCHEMA.md @.claude/skills/pst-extractor/SKILL.md

Create `pst-extractor/` with:
- `Dockerfile` – Python 3.12, install libpff/pypff
- `requirements.txt` – pypff, httpx, loguru
- `main.py` – continuous loop with activity_guard pattern, watches for unprocessed PST files
- `pst_reader.py` – PST processing logic with message and attachment extraction

Validation:
```bash
docker compose build pst-extractor   # exits 0
docker compose run --rm pst-extractor python -c "import pypff; print('pypff OK')"
```

---

### TASK-02 – PST email extraction
**Status**: COMPLETED
**Spec**: @specs/pst-extractor.md
**Read first**: @.claude/skills/pst-extractor/SKILL.md @docs/SCHEMA.md

Implement `pst-extractor/pst_reader.py`:
- Open PST with pypff, walk all folders recursively
- Extract: message_id (from transport headers), subject, sender, recipients (JSON), sent_at, body_text
- `body_hash` = sha256 of normalized body text
- Insert into `emails` table with `INSERT OR IGNORE` (message_id UNIQUE)
- Track already-processed PSTs in `scan_state` table: key=`pst_processed:{path}`, value=`1`

Validation:
```bash
# Place a test .pst in data/test/ and run:
docker compose run --rm pst-extractor python -c "
from pst_reader import process_pst
process_pst('/data/test/sample.pst', 'data/index.db', '/mnt/extracted')
"
sqlite3 data/index.db "SELECT COUNT(*), COUNT(message_id) FROM emails;"
# Both counts must be > 0
```

---

### TASK-03 – PST attachment extraction
**Status**: COMPLETED
**Spec**: @specs/pst-extractor.md
**Read first**: @.claude/skills/pst-extractor/SKILL.md

Implement `pst-extractor/attachment_saver.py`:
- For each attachment: save to `/mnt/extracted/{pst_stem}/{safe_msg_id}/{filename}`
- Compute sha256 of saved file
- Insert into `attachments` table
- Update `emails.has_attachments` and `emails.attachment_count`

Validation:
```bash
sqlite3 data/index.db "SELECT COUNT(*) FROM attachments WHERE extracted_path IS NOT NULL;"
# Must be > 0 if test PST has attachments
ls /Volumes/nas/_extracted/   # directory structure exists
```

---

### TASK-04 – Dedup engine (all 3 levels)
**Status**: COMPLETED
**Spec**: @specs/dedup-engine.md
**Read first**: @.claude/skills/dedup-engine/SKILL.md @docs/SCHEMA.md

Implement `pst-extractor/dedup.py`:
- Level 1: SHA256 duplication across `files` table
- Level 2: `content_hash` duplication (where SHA256 differs)
- Level 3: `message_id` duplication across `emails` table
- Fill `dup_groups` + `dup_members` tables
- Auto-suggest winner: penalize /temp/ /backup/ /old/ paths, prefer newest mtime

Called automatically after each PST is fully processed.

Validation:
```bash
sqlite3 data/index.db "SELECT match_type, COUNT(*) FROM dup_groups GROUP BY match_type;"
# message_id groups appear if duplicate emails exist in test PSTs
```

---

## Phase 2 – Embedder

### TASK-05 – Embedder service skeleton + text extractors
**Status**: PENDING
**Spec**: @specs/embedder.md
**Read first**: @indexer/activity_guard.py @docs/SCHEMA.md @.claude/skills/embedder/SKILL.md

Create `embedder/` with:
- `Dockerfile` – Python 3.12 + PyMuPDF + python-docx + python-pptx + openpyxl + qdrant-client + Pillow
- `requirements.txt`
- `main.py` – loop: SELECT files WHERE rag_winner=1 AND embed_status='pending', process each
- `extractors/pdf.py` – PyMuPDF
- `extractors/docx.py` – python-docx
- `extractors/pptx.py` – python-pptx (slides + speaker notes)
- `extractors/xlsx.py` – openpyxl, cells as pipe-separated text
- `extractors/txt.py` – direct read, detect encoding

Validation:
```bash
docker compose build embedder   # exits 0
docker compose run --rm embedder python -c "
import fitz; from docx import Document; from pptx import Presentation
print('extractors OK')
"
```

---

### TASK-06 – DeepSeek-OCR image extractor
**Status**: PENDING
**Spec**: @specs/embedder.md
**Read first**: @.claude/skills/embedder/SKILL.md

Implement `embedder/extractors/image.py`:
- Detect image type: document / table / figure / general (aspect ratio + EXIF)
- Select prompt per type (4 prompt variants in SKILL.md)
- Call `deepseek-ocr` @ `http://host.docker.internal:11434/api/generate`
- Return Markdown text
- Skip if image < 10KB (likely icon)
- Set `embed_status='skipped'` for non-text photos (EXIF GPS data present)

Validation:
```bash
docker compose run --rm embedder python -c "
from extractors.image import extract_image
text = extract_image('/mnt/nas/_test/scan.jpg', 'http://host.docker.internal:11434')
print(text[:200])
assert len(text) > 10, 'No text extracted'
print('image extractor OK')
"
```

---

### TASK-07 – Chunker + Qdrant uploader
**Status**: PENDING
**Spec**: @specs/embedder.md
**Read first**: @.claude/skills/embedder/SKILL.md @docs/SCHEMA.md

Implement:
- `embedder/chunker.py` – 512 word chunks, 64 word overlap, skip < 50 chars
- `embedder/qdrant_uploader.py` – create collection `nas_documents` if not exists (768 dims, cosine), upsert points with payload: source_type, source_id, chunk_index, text
- Wire into `main.py`: extract → chunk → embed (nomic-embed-text) → upsert → update `embed_status='done'` + `chunks` table

Validation:
```bash
curl -s http://localhost:6333/collections/nas_documents | jq .result.vectors_count
# Must be > 0 after processing at least one test file
sqlite3 data/index.db "SELECT COUNT(*) FROM chunks WHERE qdrant_id IS NOT NULL;"
```

---

## Phase 3 – Dashboard

### TASK-08 – Dedup review UI
**Status**: PENDING
**Spec**: @specs/dashboard.md
**Read first**: @dashboard/main.py @dashboard/templates/index.html @.claude/skills/dashboard-dedup/SKILL.md

Add to dashboard:
- `GET /api/duplicates` – pending groups sorted by saveable_mb DESC
- `GET /api/duplicates/{id}` – group detail with file metadata
- `POST /api/duplicates/{id}/decision` – body: `{winner_id, action}` → update rag_winner flags
- New "Duplikátumok" tab in `index.html`:
  - Summary: N csoport, X GB megtakarítható
  - Card per group: match_type badge, member list with radio buttons, 3 action buttons
  - Auto-suggest winner highlighted (suggested by dedup.py)

Validation:
```bash
curl -s http://localhost:8080/api/duplicates | jq length
# Returns array (may be empty if no dupes yet)
curl -s -X POST http://localhost:8080/api/duplicates/1/decision \
  -H 'Content-Type: application/json' \
  -d '{"action":"keep_all"}' | jq .ok
# Returns true
```

---

### TASK-09 – RAG search UI
**Status**: PENDING
**Spec**: @specs/dashboard.md
**Read first**: @dashboard/main.py @.claude/skills/embedder/SKILL.md

Add to dashboard:
- `POST /api/search` – body: `{query: str, limit: 5}` → embed query with nomic-embed-text → Qdrant search → return top results with chunk text, source path, score
- "Keresés" tab in `index.html` – search box, results with source info and relevance score
- Results link to file path on NAS

Validation:
```bash
curl -s -X POST http://localhost:8080/api/search \
  -H 'Content-Type: application/json' \
  -d '{"query": "test document", "limit": 3}' | jq length
# Returns array (may be 0 if embedder hasn't run yet, but endpoint must exist)
```

---

## Phase 4 – Integration

### TASK-10 – End-to-end smoke test
**Status**: PENDING

Run full stack and verify pipeline flows:
1. `docker compose up -d` – all 6 services start
2. Place 3 test files in `/Volumes/nas/_test/`: one PDF, one DOCX, one JPG
3. Manually trigger indexer scan via `docker exec nas-indexer python -c "from scanner import scan; ..."`
4. Verify `files` table has 3 rows
5. Verify embedder processes them (wait up to 60s): `embed_status='done'`
6. Verify Qdrant has vectors: `curl localhost:6333/collections/nas_documents`
7. Verify RAG search returns results: `curl -X POST localhost:8080/api/search -d '{"query":"test"}'`

Validation: all 7 checks above pass.

---

RALPH_DONE placeholder – Ralph writes this when all tasks are PASSED:
<!-- RALPH_DONE -->
