# /implement-pst

Implement the complete `pst-extractor` service.

Read these files first (in order):
1. @CLAUDE.md
2. @docs/SCHEMA.md
3. @indexer/activity_guard.py
4. @indexer/nas_api.py
5. @indexer/db.py
6. @.claude/skills/pst-extractor/SKILL.md
7. @.claude/skills/dedup-engine/SKILL.md

Then implement `pst-extractor/` with:
- `Dockerfile` – Python 3.12, install pypff from source (libpff)
- `requirements.txt` – pypff, httpx, loguru
- `main.py` – continuous loop with activity_guard pattern
- `pst_reader.py` – pypff wrapper, yields email dicts with message_id
- `attachment_saver.py` – saves to /mnt/extracted/{pst_stem}/{safe_msg_id}/
- `dedup.py` – fills dup_groups + dup_members after processing each PST

Follow exactly the same patterns as `indexer/` for activity_guard, logging, and SQLite.
