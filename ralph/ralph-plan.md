# NAS Indexer & RAG Pipeline – Ralph Plan

## Project
16 TB-os NAS indexelő, duplikátum felismerő és RAG pipeline.
MacBook M4 Max fut mindent, Synology DS418 csak tároló.

## Tasks

- [ ] TASK-01: pst-extractor service skeleton (Dockerfile, main.py, activity_guard minta)
- [ ] TASK-02: PST email extraction (pypff, message_id, emails tábla)
- [ ] TASK-03: PST attachment extraction (fájlok NAS-ra, attachments tábla)
- [ ] TASK-04: Dedup engine (sha256 + content_hash + message_id, dup_groups/members)
- [ ] TASK-05: Embedder service skeleton + text extractors (PDF, DOCX, PPTX, XLSX, TXT)
- [ ] TASK-06: DeepSeek-OCR image extractor (típus detekció, 4 prompt variant)
- [ ] TASK-07: Chunker + Qdrant uploader (512/64, nomic-embed-text, nas_documents collection)
- [ ] TASK-08: Dashboard dedup review UI (kártyák, döntés API, rag_winner frissítés)
- [ ] TASK-09: RAG search UI (query embed, Qdrant search, találatok)
- [ ] TASK-10: End-to-end smoke test (teljes pipeline)

## Instructions for Ralph

Read `PROMPT_build.md` for your operating instructions.
Read `IMPLEMENTATION_PLAN.md` for detailed task specifications and validation commands.
Read `AGENTS.md` for project commands.
