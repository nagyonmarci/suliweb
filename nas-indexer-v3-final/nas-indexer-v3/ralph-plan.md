# NAS Indexer – Ralph Plan

## DO_NOT_COMMIT

## Stack
- Go: nas-monitor, indexer, dashboard API
- Python: pst-extractor (pypff), embedder (PyMuPDF, DeepSeek-OCR)
- Astro: frontend (statikus, Go serválja)
- SQLite WAL: shared DB
- Qdrant: vektortároló
- Ollama: natív Mac Metal GPU

## Tasks

- [ ] TASK-00: docker-compose + go.mod-ok + schema.sql
- [ ] TASK-01a: nas-monitor Go service (PARALLEL)
- [ ] TASK-01b: indexer Go skeleton + ActivityGuard (PARALLEL)
- [ ] TASK-02: indexer scanner + worker pool + hasher
- [ ] TASK-03a: pst-extractor Python service (PARALLEL)
- [ ] TASK-03b: embedder Python service + extractors (PARALLEL)
- [ ] TASK-04: dedup engine (sha256 + content_hash + message_id)
- [ ] TASK-05: embedder chunker + Qdrant uploader
- [ ] TASK-06: dashboard Go API (stats, dupes, search, static serve)
- [ ] TASK-07: Astro frontend (dark UI, islands)
- [ ] TASK-08: end-to-end smoke test

## Instructions
Read PROMPT_build.md for orchestration rules.
Read IMPLEMENTATION_PLAN.md for task details, agent routing, validation commands.
Dispatch subagents via Task tool. Do not write implementation code yourself.
