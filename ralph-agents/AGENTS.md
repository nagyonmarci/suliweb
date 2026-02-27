# AGENTS.md – NAS Indexer

## Commands

```bash
# Docker
docker compose up -d                    # indítás
docker compose logs -f [service]        # logok
docker compose down                     # leállítás

# Validate service starts
docker compose up --build [service] 2>&1 | tail -20

# SQLite check
sqlite3 data/index.db ".tables"
sqlite3 data/index.db "SELECT COUNT(*) FROM files;"

# Qdrant health
curl -s http://localhost:6333/collections | jq .

# Ollama models (fut Mac-en natívan, nem Dockerben)
ollama list
curl -s http://localhost:11434/api/tags | jq .models[].name
```

## Key files

| Fájl | Mire való |
|---|---|
| `IMPLEMENTATION_PLAN.md` | feladatlista, Ralph ezt frissíti |
| `CLAUDE.md` | projekt memória |
| `docs/SCHEMA.md` | SQLite séma részletesen |
| `docs/ARCHITECTURE.md` | adatfolyam, service-ek |
| `indexer/activity_guard.py` | KÖTELEZŐ minta minden új service-hez |
| `.claude/skills/*/SKILL.md` | implementációs minták kóddal |

## Environment
```
.env → NAS_HOST, NAS_USER, NAS_PASS
Ollama fut: localhost:11434 (Mac natív)
Docker → host.docker.internal:11434
```

## Backpressure (validation)
Minden task után ezek futnak (részletek a task-ban):
- `docker compose up --build [service]` indul hibák nélkül
- SQLite tábla sorok száma > 0 egy teszt után
- Qdrant collection létezik és lekérdezhető
