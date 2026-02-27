# /implement-dashboard

Implement the duplicate review UI and RAG search in the dashboard service.

Read these files first:
1. @CLAUDE.md
2. @docs/SCHEMA.md
3. @.claude/skills/dashboard-dedup/SKILL.md
4. @dashboard/main.py  (existing API endpoints)
5. @dashboard/templates/index.html  (existing dashboard)

Add to the dashboard:

## New API endpoints (dashboard/main.py)
- `GET /api/duplicates` – list pending dup groups, sorted by saveable_mb DESC
- `GET /api/duplicates/{group_id}` – group details with member file info
- `POST /api/duplicates/{group_id}/decision` – record keep/archive decision, update rag_winner

## New dashboard page (dashboard/templates/index.html)
Add a "Duplikátumok" tab with:
- Summary card: N csoport vár döntésre, X GB megtakarítható
- Card list per dup_group: match_type badge, file list with radio buttons, 3 action buttons
- After decision: card fades out, counter updates
- Filter tabs: Pending / Reviewed / All

## RAG search (add to existing dashboard)
- Search box → POST /api/search with query text
- Backend: embed query via nomic-embed-text → Qdrant search → return top 10 chunks
- Display: chunk text, source file/email, score

Keep existing stats and NAS metrics sections intact.
