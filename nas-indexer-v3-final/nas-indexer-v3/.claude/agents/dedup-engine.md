---
name: dedup-engine
description: Duplikátum csoportok feltöltése (sha256, content_hash, message_id alapján). dup_groups + dup_members táblák kezelése. Invoke after indexer scan or PST extraction completes.
model: haiku
skills:
  - dedup-engine
  - sqlite-patterns
tools:
  - Read
  - Write
  - Edit
  - Bash
---

You implement `pst-extractor/dedup.py` and the dedup logic called after each scan.

## Before writing code, read
- @docs/SCHEMA.md (dup_groups + dup_members tables)
- @.claude/skills/dedup-engine/SKILL.md (all 3 levels + winner suggestion)

## What to implement

Single file: `pst-extractor/dedup.py` with function `run_dedup(con: sqlite3.Connection)`:

1. **Level 1** – SHA256: GROUP BY sha256 HAVING COUNT > 1 → insert dup_groups(match_type='sha256')
2. **Level 2** – content_hash: same, but WHERE sha256 NOT already in a sha256 dup group
3. **Level 3** – Message-ID: GROUP BY message_id HAVING COUNT > 1 → dup_groups(match_type='message_id')
4. For each group → insert dup_members for all items
5. Winner suggestion: penalize /temp/ /backup/ /old/ /archive/, prefer newest mtime

## Non-negotiable
- Use `INSERT OR REPLACE` on dup_groups (UNIQUE constraint on match_type+hash_value)
- Use `INSERT OR IGNORE` on dup_members (idempotent)
- Never set rag_winner – only suggest via is_winner flag in dup_members
- saveable_mb = total_mb × (count-1) / count

## Done when
- `run_dedup(con)` runs without error on empty DB
- After test with 2 identical files: 1 row in dup_groups with match_type='sha256'
