# /implement-embedder

Implement the complete `embedder` service.

Read these files first (in order):
1. @CLAUDE.md
2. @docs/SCHEMA.md
3. @indexer/activity_guard.py
4. @.claude/skills/embedder/SKILL.md

Then implement `embedder/` with:
- `Dockerfile` – Python 3.12 + PyMuPDF + python-docx + python-pptx + openpyxl + qdrant-client + Pillow
- `requirements.txt`
- `main.py` – loops over files/emails/attachments WHERE rag_winner=1 AND embed_status='pending'
- `extractors/pdf.py` – PyMuPDF text extraction
- `extractors/docx.py` – python-docx
- `extractors/pptx.py` – python-pptx (slides + notes)
- `extractors/xlsx.py` – openpyxl cells as text
- `extractors/image.py` – DeepSeek-OCR @ host.docker.internal:11434, type detection
- `chunker.py` – 512 token, 64 overlap, skip chunks < 50 chars
- `qdrant_uploader.py` – create collection if not exists, upsert points

Critical: Only process files where rag_winner=1 AND embed_status='pending'.
Update embed_status to 'done'/'error'/'skipped' after each file.
