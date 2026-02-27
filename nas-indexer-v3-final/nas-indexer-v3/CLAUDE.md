# NAS Indexer & RAG Pipeline

## Stack
- **Go 1.22+** – nas-monitor, indexer, dashboard API (egybináris, CGO for SQLite)
- **Python 3.12** – pst-extractor, embedder (pypff, PyMuPDF nincs Go alternatíva)
- **Astro 4** – frontend, statikus build, Go serválja `/public`-ból
- **Qdrant** – vektortároló (Docker)
- **Ollama** – natívan Mac-en, Metal GPU (nem Dockerben)
- **SQLite WAL** – shared DB, Go és Python egyszerre olvassa

## Hardver
- NAS: Synology DS418, DSM 6.2.4, ARM, csak SMB mount → `/Volumes/nas`
- Mac: M4 Max, 128 GB RAM. Csatolmányok: `/Volumes/nas/_extracted/`
- Ollama: `localhost:11434` (Mac natív), Dockerből: `host.docker.internal:11434`

## Projekt struktúra
```
nas-indexer/
├── CLAUDE.md
├── AGENTS.md
├── IMPLEMENTATION_PLAN.md
├── PROMPT_build.md
├── ralph-plan.md
├── docker-compose.yml
├── .env
├── data/                      ← SQLite, logok (gitignore)
├── docs/
│   ├── SCHEMA.md
│   ├── ARCHITECTURE.md
│   └── GO_PATTERNS.md
├── specs/                     ← acceptance criteria per service
├── .claude/agents/            ← subagent definíciók
├── .claude/skills/            ← kódsablonok
├── .claude/rules/             ← kötelező minták
│
├── nas-monitor/               ← Go service
│   ├── main.go
│   ├── synology.go            ← Synology REST API kliens
│   ├── metrics.go             ← metrics.db írás
│   └── go.mod
│
├── indexer/                   ← Go service
│   ├── main.go
│   ├── scanner.go             ← os.Walk, worker pool
│   ├── hasher.go              ← SHA256, content_hash
│   ├── guard.go               ← ActivityGuard goroutine
│   ├── db.go                  ← SQLite műveletek
│   ├── synology.go            ← shared Synology kliens
│   └── go.mod
│
├── dashboard/                 ← Go API + Astro frontend
│   ├── main.go                ← HTTP szerver, statikus fájlok
│   ├── api/
│   │   ├── stats.go
│   │   ├── duplicates.go
│   │   ├── search.go
│   │   └── pst.go
│   ├── frontend/              ← Astro projekt
│   │   ├── astro.config.mjs
│   │   ├── package.json
│   │   ├── src/
│   │   │   ├── pages/
│   │   │   │   ├── index.astro
│   │   │   │   ├── duplicates.astro
│   │   │   │   └── search.astro
│   │   │   ├── components/
│   │   │   │   ├── NASChart.tsx   ← Island
│   │   │   │   ├── DupCard.tsx    ← Island
│   │   │   │   └── SearchBox.tsx  ← Island
│   │   │   └── layouts/
│   │   │       └── Base.astro
│   │   └── dist/              ← astro build output (gitignore)
│   └── go.mod
│
├── pst-extractor/             ← Python service (pypff)
│   ├── Dockerfile
│   ├── main.py
│   ├── pst_reader.py
│   ├── attachment_saver.py
│   ├── dedup.py
│   └── requirements.txt
│
└── embedder/                  ← Python service (PyMuPDF, Ollama)
    ├── Dockerfile
    ├── main.py
    ├── extractors/
    │   ├── pdf.py
    │   ├── docx.py
    │   ├── pptx.py
    │   ├── xlsx.py
    │   ├── image.py           ← deepseek-ocr
    │   └── txt.py
    ├── chunker.py
    ├── qdrant_uploader.py
    └── requirements.txt
```

## Service státuszok
| Service | Lang | Státusz |
|---|---|---|
| nas-monitor | Go | 🔲 TODO |
| indexer | Go | 🔲 TODO |
| dashboard API | Go | 🔲 TODO |
| dashboard frontend | Astro | 🔲 TODO |
| pst-extractor | Python | 🔲 TODO |
| embedder | Python | 🔲 TODO |

## Agent routing
| Task | Agent | Model |
|---|---|---|
| Go service implementáció | `go-service` | sonnet |
| Astro frontend | `astro-frontend` | sonnet |
| Python PST service | `pst-extractor` | sonnet |
| Python embedder | `embedder` | sonnet |
| Dedup logika (SQL) | `dedup-engine` | haiku |
| Validáció | `validator` | haiku |

## Párhuzamosítás
- PÁRHUZAMOS: nas-monitor + indexer skeleton (mindkettő Go, független)
- PÁRHUZAMOS: pst-extractor + embedder skeleton (mindkettő Python, független)
- SZEKVENCIÁLIS: dashboard API csak indexer DB sémája után
- SZEKVENCIÁLIS: Astro frontend csak dashboard API után
- SZEKVENCIÁLIS: dedup csak PST extraction után

## Kötelező minták
- Go: `ActivityGuard` struct goroutine-alapú (lásd @docs/GO_PATTERNS.md)
- Go: SQLite `_busy_timeout=5000&_journal_mode=WAL&_foreign_keys=on` DSN
- Go: worker pool `runtime.NumCPU()` goroutine-nal a fájlhasheléshez
- Python: `activity_guard.py` copy minta (pst-extractor, embedder)
- Csak `rag_winner=1` fájlok kerülnek Qdrant-ba
- Ollama Dockerből: `http://host.docker.internal:11434`

## Séma: @docs/SCHEMA.md
## Architektúra: @docs/ARCHITECTURE.md
## Go minták: @docs/GO_PATTERNS.md
