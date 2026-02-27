---
name: embedder-agent
description: Embedder service implementálása. Szöveg kinyerés minden fájltípusból, DeepSeek-OCR képekhez, chunking, Qdrant feltöltés.
tools: Read, Write, Edit, Bash
model: sonnet
skills:
  - embedder
---

Te az `embedder` Docker service-t implementálod.

## Kötelező lépések sorrendben

1. Olvasd el a `.claude/skills/embedder/SKILL.md` fájlt
2. Olvasd el az `indexer/activity_guard.py` fájlt – kötelező minta
3. Olvasd el a `docs/SCHEMA.md` chunks tábla részét
4. Implementáld az `embedder/` könyvtárban:
   - `Dockerfile` (Python 3.12 + PyMuPDF + python-docx + python-pptx + openpyxl + qdrant-client + Pillow)
   - `requirements.txt`
   - `main.py` – főciklus, csak rag_winner=1 elemeken fut
   - `extractors/pdf.py`, `docx.py`, `pptx.py`, `xlsx.py`, `image.py`
   - `chunker.py` – 512 token, 64 overlap
   - `qdrant_uploader.py` – collection létrehozás + upsert

## Kritikus megszorítások
- **Csak `rag_winner=1` fájlokon fut** – mindig ellenőrizd az embed_status és rag_winner mezőket
- Ollama: `http://host.docker.internal:11434` (NEM localhost)
- DeepSeek-OCR képtípus detekció: arány alapján document/table/figure/general prompt
- embed_status frissítése: 'pending' → 'done' | 'error' | 'skipped'

## Elfogadási kritériumok
- [ ] PDF, DOCX, PPTX szöveg kinyerve és chunk-olva
- [ ] Kép OCR fut DeepSeek-OCR-rel, Markdown kimenet
- [ ] chunks tábla feltöltve, qdrant_id mezők kitöltve
- [ ] Qdrant collection `nas_documents` létezik és lekérdezhető
- [ ] embed_status='done' azon fájloknál ahol sikeres volt
- [ ] Már feldolgozott fájlok (embed_status='done') nem dolgozódnak fel újra
