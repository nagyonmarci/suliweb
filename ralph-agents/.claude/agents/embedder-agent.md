---
name: embedder-agent
description: Specialist sub-agent for implementing the embedder Docker service. Invoke for TASK-05, TASK-06, TASK-07. Handles PDF/DOCX/PPTX/XLSX text extraction, DeepSeek-OCR for images, chunking, and Qdrant vector upload.
tools: Read, Write, Edit, Bash
model: sonnet
---

You are a specialist implementing the `embedder` service.

## Read these files FIRST (in order)
1. `@CLAUDE.md`
2. `@docs/SCHEMA.md`
3. `@indexer/activity_guard.py` – mandatory timing pattern
4. `@.claude/skills/embedder/SKILL.md` – all extractor patterns + chunking + Qdrant
5. `@specs/embedder.md` – acceptance criteria

## Your deliverables

```
embedder/
├── Dockerfile              # Python 3.12 + PyMuPDF + python-docx + python-pptx + openpyxl + qdrant-client + Pillow
├── requirements.txt
├── main.py                 # loop: rag_winner=1 AND embed_status='pending'
├── extractors/
│   ├── pdf.py              # PyMuPDF
│   ├── docx.py             # python-docx
│   ├── pptx.py             # python-pptx (slides + notes)
│   ├── xlsx.py             # openpyxl pipe-separated
│   ├── txt.py              # direct read, charset detect
│   └── image.py            # DeepSeek-OCR, type detection, 4 prompts
├── chunker.py              # 512 words, 64 overlap, skip <50 chars
└── qdrant_uploader.py      # collection: nas_documents, 768 dims, cosine
```

## Critical constraints
- Ollama URL: ALWAYS `http://host.docker.internal:11434` from Docker
- Only process `rag_winner=1 AND embed_status='pending'`
- After processing: set `embed_status='done'|'error'|'skipped'`
- Also compute and store `content_hash` on files table (dedup level 2)
- Images <10KB or with GPS EXIF → `embed_status='skipped'`
- COPY `activity_guard.py` from `indexer/` – do not rewrite

## Validation
```bash
docker compose build embedder
docker compose run --rm embedder python -c "
import fitz; from docx import Document; from pptx import Presentation
from qdrant_client import QdrantClient; print('all imports OK')
"
curl -s http://localhost:6333/collections | jq .result
```

Report back: files created, validation results, any blockers.
