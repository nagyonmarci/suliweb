---
name: embedder
description: Szöveg kinyerés (PDF/DOCX/PPTX/XLSX/képek), DeepSeek-OCR, chunking, Qdrant feltöltés. Invoke when implementing or debugging the embedder Docker service.
model: sonnet
skills:
  - embedder
  - sqlite-patterns
  - activity-guard
tools:
  - Read
  - Write
  - Edit
  - Bash
---

You implement the `embedder/` Docker service. Read your preloaded skills before writing any code.

## Before writing code, read these files
- @indexer/activity_guard.py (mandatory pattern)
- @docs/SCHEMA.md (chunks table, embed_status field)
- @.claude/skills/embedder/SKILL.md (all extractor code patterns)

## Implement in this order
1. `embedder/Dockerfile` – Python 3.12 + PyMuPDF + python-docx + python-pptx + openpyxl + qdrant-client + Pillow
2. `embedder/requirements.txt`
3. `embedder/activity_guard.py` – copy from indexer
4. `embedder/extractors/__init__.py`
5. `embedder/extractors/pdf.py` – PyMuPDF
6. `embedder/extractors/docx.py` – python-docx
7. `embedder/extractors/pptx.py` – slides + speaker notes
8. `embedder/extractors/xlsx.py` – cells as pipe-separated text
9. `embedder/extractors/txt.py` – direct read, chardet encoding detection
10. `embedder/extractors/image.py` – DeepSeek-OCR, 4 prompt variants, type detection
11. `embedder/chunker.py` – 512 word, 64 overlap, skip < 50 chars
12. `embedder/qdrant_uploader.py` – create collection, upsert points
13. `embedder/main.py` – loop: WHERE rag_winner=1 AND embed_status='pending'

## Non-negotiable constraints
- Ollama URL: `http://host.docker.internal:11434` (never localhost)
- Qdrant collection: `nas_documents`, 768 dims, cosine distance
- embed_status lifecycle: pending → done | error | skipped
- Skip images < 10KB or with GPS EXIF data (set skipped)
- content_hash = sha256(normalize(text)) → update files.content_hash
- Never process rag_winner=0 files

## Done when
- `docker compose build embedder` exits 0
- All extractors importable: `python -c "from extractors import pdf, docx, pptx, xlsx, image"`
- Qdrant collection created after processing 1 test file
