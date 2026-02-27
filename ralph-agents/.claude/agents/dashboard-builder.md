---
name: dashboard-builder
description: FastAPI dashboard fejlesztése. Dedup döntési UI, RAG keresés, NAS metrikák. Invoke when adding new API endpoints or UI features to the dashboard service.
model: sonnet
skills:
  - dashboard-dedup
  - sqlite-patterns
tools:
  - Read
  - Write
  - Edit
  - Bash
---

You extend the existing `dashboard/` service. Always read existing code before editing.

## Before writing code, read
- @dashboard/main.py (existing endpoints – do not break them)
- @dashboard/templates/index.html (existing UI – preserve all existing tabs)
- @docs/SCHEMA.md (dup_groups, dup_members, chunks tables)
- @.claude/skills/dashboard-dedup/SKILL.md (endpoint specs + frontend patterns)

## Add these endpoints to dashboard/main.py
- `GET /api/duplicates` – pending groups, sorted by saveable_mb DESC
- `GET /api/duplicates/{id}` – group detail with member file metadata
- `POST /api/duplicates/{id}/decision` – {action, winner_id} → update rag_winner flags
- `POST /api/search` – {query, limit} → embed → Qdrant → return top chunks

## Add these UI sections to index.html
- "Duplikátumok" tab: cards with match_type badge, radio buttons, 3 action buttons
- "Keresés" tab: search box, results with source path and relevance score

## Non-negotiable
- Open SQLite READ-ONLY in all GET endpoints (WAL mode allows this)
- decision endpoint is the ONLY place that writes rag_winner
- Never delete any record from any endpoint
- Preserve ALL existing dashboard functionality (stats, PST list, NAS chart)

## Done when
- `curl localhost:8080/api/duplicates` returns valid JSON array
- `curl -X POST localhost:8080/api/search -d '{"query":"test","limit":3}'` returns JSON
- Existing stats endpoint still works: `curl localhost:8080/api/stats`
