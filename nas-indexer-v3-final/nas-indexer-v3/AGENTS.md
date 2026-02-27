# AGENTS.md

## Build commands
```bash
# Go service build (minden service-ben)
CGO_ENABLED=1 go build ./...

# Astro build
cd dashboard/frontend && npm run build

# Docker
docker compose build [service]
docker compose up -d
docker compose logs -f [service]

# Validáció
sqlite3 data/index.db ".tables"
sqlite3 data/index.db "SELECT COUNT(*) FROM files;"
curl -s http://localhost:8080/api/stats | jq .
curl -s http://localhost:6333/collections | jq .
ollama list   # Mac natív – deepseek-ocr + nomic-embed-text kell
```

## Agent routing (gyors referencia)
| Feladat | Agent |
|---|---|
| Go service | `go-service` |
| Astro frontend | `astro-frontend` |
| PST feldolgozás | `pst-extractor` |
| Embedding + OCR | `embedder` |
| Dedup SQL logika | `dedup-engine` |
| Bármilyen validáció | `validator` |

## Key files
| Fájl | Tartalom |
|---|---|
| `IMPLEMENTATION_PLAN.md` | feladatlista, Ralph frissíti |
| `docs/GO_PATTERNS.md` | Go kódminták, kötelező követni |
| `docs/ASTRO_PATTERNS.md` | Astro/React minták + design tokens |
| `docs/SCHEMA.md` | SQLite teljes séma |
| `.claude/skills/*/SKILL.md` | Python service minták |

## Environment
```
.env szükséges: NAS_HOST, NAS_USER, NAS_PASS
Ollama Mac natív: localhost:11434
Docker services → host.docker.internal:11434
NAS SMB mount: /Volumes/nas
```
