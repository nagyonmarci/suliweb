# NAS Indexer & RAG Pipeline – Ralph Plan

## DO_NOT_COMMIT

## Project
16 TB NAS indexelő, duplikátum felismerő és RAG pipeline.
MacBook M4 Max orchestrálja a subagenteket, Synology DS418 csak tároló.

## How you work
Read PROMPT_build.md for orchestration rules.
You dispatch subagents via Task tool. You do NOT write code yourself.

## Tasks

- [ ] TASK-01: pst-extractor + embedder skeleton (PARALLEL subagents)
- [ ] TASK-02: PST email + attachment extraction
- [ ] TASK-03: Dedup engine (sha256 + content_hash + message_id)
- [ ] TASK-04: Text extractors – PDF, DOCX, PPTX, XLSX, images/OCR (PARALLEL)
- [ ] TASK-05: Chunker + Qdrant uploader
- [ ] TASK-06: Dashboard dedup review UI
- [ ] TASK-07: Dashboard RAG search UI
- [ ] TASK-08: End-to-end smoke test (validator only)
