# Implementation Plan

## Discovered patterns
<!-- Orchestrator writes here between iterations -->

---

## Phase 0 – Shared infrastructure

### TASK-00 – docker-compose.yml + go.mod-ok + DB séma
**Status**: PENDING
**Depends on**: –
**Agent**: `go-service` (haiku elég)
**Parallel**: No

Scope:
- `docker-compose.yml` – mind a 6 service
- `nas-monitor/go.mod`, `indexer/go.mod`, `dashboard/go.mod`
- `schema.sql` – teljes SQLite séma (lásd @docs/SCHEMA.md)
- `.env.example`

READ FIRST: @CLAUDE.md @docs/SCHEMA.md @docs/GO_PATTERNS.md

Done when:
```bash
docker compose config   # valid, no errors
cat nas-monitor/go.mod | grep module
cat schema.sql | grep "CREATE TABLE files"
```

---

## Phase 1 – Go core services (PARALLEL)

### TASK-01a – nas-monitor Go service
**Status**: PENDING
**Depends on**: TASK-00
**Agent**: `go-service`
**Parallel**: YES (with TASK-01b)

Scope: `nas-monitor/main.go`, `nas-monitor/synology.go`, `nas-monitor/metrics.go`

READ FIRST: @docs/GO_PATTERNS.md @docs/SCHEMA.md @CLAUDE.md

Implement:
- `SynologyClient` – login, GetUtilization, auto-relogin on session expire
- `MetricsWriter` – write to metrics.db every 15s, retain 7 days
- `main()` – config from env, continuous loop, graceful shutdown on SIGTERM

Done when:
```bash
docker compose build nas-monitor   # exits 0
docker compose run --rm nas-monitor /service --help 2>&1 | head -3
```

---

### TASK-01b – indexer Go service skeleton
**Status**: PENDING
**Depends on**: TASK-00
**Agent**: `go-service`
**Parallel**: YES (with TASK-01a)

Scope: `indexer/main.go`, `indexer/guard.go`, `indexer/synology.go`, `indexer/db.go`

READ FIRST: @docs/GO_PATTERNS.md @docs/SCHEMA.md @CLAUDE.md

Implement:
- `ActivityGuard` struct + goroutine (copy from @docs/GO_PATTERNS.md exactly)
- `SynologyClient` (identical to nas-monitor – copy, do not abstract yet)
- `DB` struct – openDB with WAL DSN, CreateTables, SaveState, LoadState
- `main()` – config, guard, scan loop, rescan delay

Done when:
```bash
docker compose build indexer   # exits 0
docker compose run --rm indexer /service --version 2>&1
```

---

### TASK-02 – indexer scanner + hasher
**Status**: PENDING
**Depends on**: TASK-01b
**Agent**: `go-service`

Scope: `indexer/scanner.go`, `indexer/hasher.go`

READ FIRST: @docs/GO_PATTERNS.md @indexer/db.go @indexer/guard.go

Implement:
- `Scanner.Scan()` – filepath.WalkDir + worker pool (NumCPU goroutines)
- `sha256File()` – streaming hash
- `contentHash()` – normalize + sha256
- Crash-safe: save last path to scan_state, resume on restart
- Throttle: check guard every 100 files
- Batch insert every 500 files

Done when:
```bash
# Place 10 test files in /tmp/test-nas/ then:
SCAN_PATH=/tmp/test-nas DB_PATH=/tmp/test.db \
  docker compose run --rm indexer /service
sqlite3 /tmp/test.db "SELECT COUNT(*) FROM files;"
# Must be 10
```

---

## Phase 2 – Python services (PARALLEL)

### TASK-03a – pst-extractor Python service
**Status**: PENDING
**Depends on**: TASK-00
**Agent**: `pst-extractor`
**Parallel**: YES (with TASK-03b)

READ FIRST: @docs/SCHEMA.md @.claude/skills/pst-extractor/SKILL.md @.claude/rules/activity-guard.md

Scope: full `pst-extractor/` service

Done when:
```bash
docker compose build pst-extractor   # exits 0
docker compose run --rm pst-extractor python -c "import pypff; print('OK')"
sqlite3 data/index.db "SELECT COUNT(*) FROM emails;"   # no error
```

---

### TASK-03b – embedder Python service skeleton + extractors
**Status**: PENDING
**Depends on**: TASK-00
**Agent**: `embedder`
**Parallel**: YES (with TASK-03a)

READ FIRST: @docs/SCHEMA.md @.claude/skills/embedder/SKILL.md @.claude/rules/activity-guard.md

Scope: full `embedder/` service including all extractors and DeepSeek-OCR

Done when:
```bash
docker compose build embedder   # exits 0
docker compose run --rm embedder python -c "
from extractors import pdf, docx, pptx, xlsx, image; print('ALL OK')"
```

---

### TASK-04 – dedup engine
**Status**: PENDING
**Depends on**: TASK-03a
**Agent**: `dedup-engine`

Scope: `pst-extractor/dedup.py`

READ FIRST: @docs/SCHEMA.md @.claude/skills/dedup-engine/SKILL.md

Done when:
```bash
sqlite3 data/index.db "SELECT match_type, COUNT(*) FROM dup_groups GROUP BY match_type;"
# No error (may be empty if no dupes yet)
```

---

### TASK-05 – embedder chunker + Qdrant
**Status**: PENDING
**Depends on**: TASK-03b
**Agent**: `embedder`

Scope: `embedder/chunker.py`, `embedder/qdrant_uploader.py`, wire into `embedder/main.py`

READ FIRST: @.claude/skills/embedder/SKILL.md @docs/SCHEMA.md

Done when:
```bash
curl -s http://localhost:6333/collections | jq .result.collections[].name
# "nas_documents" appears after processing 1 test file
sqlite3 data/index.db "SELECT COUNT(*) FROM chunks WHERE qdrant_id IS NOT NULL;"
```

---

## Phase 3 – Dashboard

### TASK-06 – dashboard Go API
**Status**: PENDING
**Depends on**: TASK-02
**Agent**: `go-service`

Scope: `dashboard/main.go`, `dashboard/api/stats.go`, `dashboard/api/duplicates.go`, `dashboard/api/search.go`, `dashboard/api/pst.go`

READ FIRST: @docs/GO_PATTERNS.md @docs/SCHEMA.md @.claude/skills/dashboard-dedup/SKILL.md

Implement:
- `GET /api/stats` – total files, PST count, dup count, last scan
- `GET /api/metrics/recent?minutes=60` – from metrics.db
- `GET /api/duplicates` + `GET /api/duplicates/{id}` + `POST /api/duplicates/{id}/decision`
- `POST /api/search` – embed query (nomic-embed-text @ Ollama) → Qdrant → return chunks
- `GET /*` – serve `./frontend/dist` static files

Done when:
```bash
docker compose build dashboard   # exits 0
curl -s http://localhost:8080/api/stats | jq .total_files
curl -s http://localhost:8080/api/duplicates | jq type   # "array"
```

---

### TASK-07 – Astro frontend
**Status**: PENDING
**Depends on**: TASK-06
**Agent**: `astro-frontend`

Scope: full `dashboard/frontend/` Astro project

READ FIRST: @docs/ASTRO_PATTERNS.md @CLAUDE.md

Implement:
- `src/layouts/Base.astro` – nav, dark theme tokens
- `src/pages/index.astro` – stats cards + NASChart island + top extensions table
- `src/pages/duplicates.astro` – DupCard islands, filter tabs, saveable MB summary
- `src/pages/search.astro` – SearchBox island, results
- `src/components/NASChart.tsx` – Recharts, 30s auto-refresh
- `src/components/DupCard.tsx` – radio buttons, 3 action buttons, suggested winner
- `src/components/SearchBox.tsx` – query input, results with source + score
- `src/lib/api.ts` – typed fetch wrappers
- `astro.config.mjs` – static output, React + Tailwind, dev proxy → localhost:8080

Done when:
```bash
cd dashboard/frontend && npm run build   # exits 0, dist/ created
ls dashboard/frontend/dist/index.html    # exists
curl -s http://localhost:8080/ | grep "NAS Indexer"   # after go service restart
```

---

### TASK-08 – End-to-end smoke test
**Status**: PENDING
**Depends on**: TASK-05, TASK-06, TASK-07
**Agent**: `validator` (only – no code changes)

```bash
docker compose up -d
docker compose ps --format json | jq '[.[].State] | all(. == "running")'   # true
curl -s http://localhost:8080/ | grep "NAS Indexer"
curl -s http://localhost:8080/api/stats | jq .total_files
curl -s http://localhost:6333/collections/nas_documents | jq .result.vectors_count
curl -s -X POST http://localhost:8080/api/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"test","limit":3}' | jq type   # "array"
```
All must succeed without errors.

---

<!-- RALPH_DONE -->
