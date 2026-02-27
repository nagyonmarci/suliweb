# NAS Indexer – Ralph Build Prompt

You are the agent. Do the work autonomously.

## Your role
Implement the NAS Indexer & RAG Pipeline project step by step.
After each task: validate it works, then commit with a descriptive message.

## How to proceed each iteration

1. Read `IMPLEMENTATION_PLAN.md` – find the first task not marked PASSED
2. Read the relevant spec in `specs/` for that task
3. Read referenced files in `@` notation before writing any code
4. Implement the task completely
5. Run the validation commands listed in the task
6. If validation passes: mark task PASSED in `IMPLEMENTATION_PLAN.md`, commit
7. If validation fails: fix and retry (max 3 attempts), then mark BLOCKED with reason
8. Output status block (see below) and exit

## Critical rules

- **Never skip reading specs and referenced files** – they contain required patterns
- **activity_guard.py pattern is mandatory** in every new service – copy from `indexer/`
- **Only `rag_winner=1` files get embedded** – always check this before processing
- **Ollama URL in Docker**: `http://host.docker.internal:11434` (never localhost)
- **SQLite**: WAL mode, batch commit every 500 items, `check_same_thread=False`
- **Never modify** already-PASSED tasks or existing `indexer/` and `nas-monitor/` code
- Task status words: use **PASSED** / **PENDING** / **BLOCKED** – never "done" or "complete"

## Status block (output at end of EVERY iteration)

```
---RALPH_STATUS---
TASK: [task id and name]
STATUS: IN_PROGRESS | PASSED | BLOCKED
EXIT_SIGNAL: false | true
REASON: [brief note]
---END_RALPH_STATUS---
```

Set `EXIT_SIGNAL: true` only when all tasks in `IMPLEMENTATION_PLAN.md` are PASSED.
When all tasks PASSED, write `RALPH_DONE` on its own line in `IMPLEMENTATION_PLAN.md`.
