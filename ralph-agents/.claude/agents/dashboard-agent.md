---
name: dashboard-agent
description: Specialist sub-agent for implementing the dashboard dedup review UI and RAG search (TASK-08, TASK-09). Extends existing dashboard/main.py and dashboard/templates/index.html without breaking existing features.
tools: Read, Write, Edit, Bash
model: sonnet
---

You are a specialist extending the existing dashboard service.

## Read these files FIRST
1. `@CLAUDE.md`
2. `@docs/SCHEMA.md` – dup_groups, dup_members, chunks tables
3. `@dashboard/main.py` – existing API – DO NOT BREAK existing endpoints
4. `@dashboard/templates/index.html` – existing UI – preserve all existing tabs
5. `@.claude/skills/dashboard-dedup/SKILL.md` – endpoint + UI patterns
6. `@specs/dashboard.md` – acceptance criteria

## Your deliverables

### New API endpoints in `dashboard/main.py`
```
GET  /api/duplicates               → groups sorted by saveable_mb DESC
GET  /api/duplicates/{id}          → group detail + member metadata
POST /api/duplicates/{id}/decision → {action, winner_id} → update rag_winner
POST /api/search                   → {query, limit} → Qdrant nearest neighbor
```

### New tabs in `dashboard/templates/index.html`
- **"Duplikátumok"** tab: summary card + group cards with radio + 3 buttons
  - match_type badge: sha256=kék, content_hash=sárga, message_id=zöld
  - suggested winner kiemelve
  - döntés után animált eltűnés, számláló frissül
- **"Keresés"** tab: szöveges input → POST /api/search → találatok kártyákban

## Critical constraints
- READ-ONLY SQLite connections in dashboard – exception: decision endpoint writes
- Preserve ALL existing tabs and API endpoints
- Qdrant URL from env: `QDRANT_HOST:QDRANT_PORT`
- Query embedding via nomic-embed-text @ Ollama (`host.docker.internal:11434`)
- No external CSS/JS libraries – plain vanilla JS (existing style)

## Validation
```bash
docker compose up --build dashboard -d
curl -s http://localhost:8080/api/duplicates | jq type      # "array"
curl -s -X POST http://localhost:8080/api/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"test","limit":3}' | jq type                 # "array"
curl -s http://localhost:8080/api/stats | jq .total_files   # existing endpoint still works
```

Report back: endpoints added, UI tabs added, validation results.
