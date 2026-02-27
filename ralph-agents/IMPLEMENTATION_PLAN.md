# Implementation Plan – NAS Indexer & RAG Pipeline

## Orchestration notes
<!-- Orchestrator ide írja amit tanul az iterációk közt -->
- Subagents invoked via Task tool only
- pst-extractor és embedder skeleton párhuzamosan futhat (független)
- dedup csak PST extraction után futhat (depends on TASK-02)

---

## Phase 1 – PST Extractor

### TASK-01 – pst-extractor + embedder skeleton (PÁRHUZAMOS)
**Status**: IN PROGRESS
**Agents**: `pst-extractor` + `embedder` (run_in_background=true, parallel)
**Orchestrator note**: These are independent – dispatch both simultaneously

pst-extractor agent scope: Dockerfile, requirements.txt, main.py, activity_guard copy
embedder agent scope: Dockerfile, requirements.txt, main.py, activity_guard copy, extractors/__init__.py

Validation agent: `validator`
```bash
docker compose build pst-extractor   # exits 0
docker compose build embedder         # exits 0
```

---

### TASK-02 – PST email + attachment extraction
**Status**: PENDING
**Depends on**: TASK-01
**Agent**: `pst-extractor`

Scope: pst_reader.py, attachment_saver.py, db.py (emails+attachments tables)

Validation agent: `validator`
```bash
sqlite3 data/index.db "SELECT COUNT(*), COUNT(message_id) FROM emails;"
# Both > 0 after test PST processing
ls /Volumes/nas/_extracted/   # directory exists
```

---

### TASK-03 – Dedup engine
**Status**: PENDING
**Depends on**: TASK-02
**Agent**: `dedup-engine` (haiku – simple SQL task)

Scope: pst-extractor/dedup.py, called from main.py after each PST

Validation agent: `validator`
```bash
sqlite3 data/index.db "SELECT match_type, COUNT(*) FROM dup_groups GROUP BY match_type;"
```

---

## Phase 2 – Embedder

### TASK-04 – Text extractors (PÁRHUZAMOS)
**Status**: PENDING
**Depends on**: TASK-01
**Agents**: `embedder` handles all – but orchestrator can split into parallel Tasks:
  - Task A: pdf.py + docx.py + pptx.py
  - Task B: xlsx.py + txt.py + image.py (DeepSeek-OCR)

Validation agent: `validator`
```bash
docker compose run --rm embedder python -c "
from extractors import pdf, docx, pptx, xlsx, image; print('ALL OK')"
```

---

### TASK-05 – Chunker + Qdrant uploader
**Status**: PENDING
**Depends on**: TASK-04
**Agent**: `embedder`

Scope: chunker.py, qdrant_uploader.py, wire into main.py

Validation agent: `validator`
```bash
curl -s http://localhost:6333/collections/nas_documents | jq .result.vectors_count
sqlite3 data/index.db "SELECT COUNT(*) FROM chunks WHERE qdrant_id IS NOT NULL;"
```

---

## Phase 3 – Dashboard

### TASK-06 – Dedup review UI
**Status**: PENDING
**Depends on**: TASK-03
**Agent**: `dashboard-builder`

Scope: 3 new API endpoints + Duplikátumok tab in index.html

Validation agent: `validator`
```bash
curl -s http://localhost:8080/api/duplicates | jq type   # "array"
curl -s -X POST http://localhost:8080/api/duplicates/1/decision \
  -H 'Content-Type: application/json' -d '{"action":"keep_all"}' | jq .ok
```

---

### TASK-07 – RAG search UI
**Status**: PENDING
**Depends on**: TASK-05
**Agent**: `dashboard-builder`

Scope: /api/search endpoint + Keresés tab

Validation agent: `validator`
```bash
curl -s -X POST http://localhost:8080/api/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"test","limit":3}' | jq type   # "array"
curl -s http://localhost:8080/api/stats | jq .total_files   # still works
```

---

### TASK-08 – End-to-end smoke test
**Status**: PENDING
**Depends on**: TASK-05, TASK-06, TASK-07
**Agent**: `validator` (only – no code changes)

```bash
docker compose up -d                          # all 6 services start
docker compose ps | grep -v "Up" | wc -l     # 0 unhealthy
curl -s http://localhost:8080/api/stats | jq .total_files
curl -s http://localhost:6333/collections/nas_documents | jq .result.vectors_count
curl -s -X POST http://localhost:8080/api/search \
  -d '{"query":"email","limit":3}' | jq length
```
All must return non-zero / non-error values.

---

<!-- RALPH_DONE -->
