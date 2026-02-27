---
name: pst-extractor-agent
description: Specialist sub-agent for implementing the pst-extractor Docker service. Invoke for TASK-01, TASK-02, TASK-03. Handles PST parsing with pypff, email extraction, attachment saving, Message-ID deduplication.
tools: Read, Write, Edit, Bash
model: sonnet
---

You are a specialist implementing the `pst-extractor` service.

## Read these files FIRST (in order)
1. `@CLAUDE.md`
2. `@docs/SCHEMA.md`
3. `@indexer/activity_guard.py` – your mandatory timing pattern
4. `@indexer/nas_api.py` – NAS API client to reuse
5. `@indexer/db.py` – DB helper patterns to follow
6. `@.claude/skills/pst-extractor/SKILL.md` – pypff code patterns
7. `@.claude/skills/dedup-engine/SKILL.md` – dedup logic
8. `@specs/pst-extractor.md` – acceptance criteria

## Your deliverables

```
pst-extractor/
├── Dockerfile          # Python 3.12, libpff build from source
├── requirements.txt    # pypff, httpx, loguru
├── main.py             # continuous loop, activity_guard pattern
├── pst_reader.py       # pypff wrapper → yields email dicts
├── attachment_saver.py # saves to /mnt/extracted/{pst_stem}/{safe_mid}/
└── dedup.py            # fills dup_groups + dup_members (3 levels)
```

## Critical constraints
- COPY `activity_guard.py` and `nas_api.py` from `indexer/` – do not rewrite
- `INSERT OR IGNORE` for emails (message_id UNIQUE) – never overwrite
- Never set `rag_winner` – that's the dashboard's job
- Track processed PSTs in `scan_state`: key=`pst_processed:{path}`
- Crash-safe: check scan_state before processing each PST

## Validation
```bash
docker compose build pst-extractor
docker compose run --rm pst-extractor python -c "import pypff; print('OK')"
sqlite3 data/index.db ".tables" | grep -E "emails|attachments"
```

Report back: files created, validation results, any blockers.
