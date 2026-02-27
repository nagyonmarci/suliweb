---
name: pst-extractor
description: PST fájlok feldolgozása. Emailek + csatolmányok kinyerése pypff-fel, Message-ID dedup, NAS-ra mentés. Invoke when implementing or debugging the pst-extractor Docker service.
model: sonnet
skills:
  - pst-extractor
  - sqlite-patterns
  - activity-guard
tools:
  - Read
  - Write
  - Edit
  - Bash
---

You implement the `pst-extractor/` Docker service. Read your preloaded skills before writing any code.

## Before writing code, read these files
- @indexer/activity_guard.py (copy this pattern exactly)
- @indexer/nas_api.py (NAS API client)
- @indexer/db.py (SQLite patterns)
- @docs/SCHEMA.md (emails + attachments table structure)

## Implement in this order
1. `pst-extractor/Dockerfile` – Python 3.12, libpff build from source
2. `pst-extractor/requirements.txt`
3. `pst-extractor/nas_api.py` – copy from indexer, do not modify
4. `pst-extractor/activity_guard.py` – copy from indexer, do not modify
5. `pst-extractor/db.py` – PST-specific DB operations (emails, attachments tables)
6. `pst-extractor/pst_reader.py` – pypff wrapper, walk folders, extract emails
7. `pst-extractor/attachment_saver.py` – save to /mnt/extracted/{pst_stem}/{safe_mid}/
8. `pst-extractor/dedup.py` – fill dup_groups + dup_members (all 3 levels)
9. `pst-extractor/main.py` – continuous loop, activity_guard, process new PSTs only

## Non-negotiable constraints
- `INSERT OR IGNORE INTO emails` – message_id is UNIQUE, never upsert
- Track processed PSTs: `scan_state` key=`pst_done:{path}` value=`1`
- Attachment path: `/mnt/extracted/{pst_stem}/{re.sub(r'[^\w@.-]','_',mid)[:80]}/{filename}`
- Never set rag_winner flag – that's the dashboard's job
- Never delete any record

## Done when
- `docker compose build pst-extractor` exits 0
- `docker compose run --rm pst-extractor python -c "import pypff; print('OK')"` prints OK
- All 9 files exist and are syntactically valid Python
