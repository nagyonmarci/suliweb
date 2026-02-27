# NAS Indexer & RAG Pipeline

## Orchestration model
Main session = orchestrator. Subagents = specialists. Ralph loop drives iteration.
**Subagents are invoked via Task tool only – never bash commands.**
**Subagents cannot spawn other subagents.**

## Hardver
- NAS: Synology DS418, DSM 6.2.4, ARM, csak SMB mount. Sem Docker sem Entware.
- Mac: M4 Max, 128 GB RAM, Metal GPU. Docker Desktop + Ollama natívan.
- NAS mount: `/Volumes/nas` → SMB. Csatolmányok: `/Volumes/nas/_extracted/`

## Routing table – mikor melyik subagent
| Task típus | Subagent | Model |
|---|---|---|
| PST fájl feldolgozás | `pst-extractor` | sonnet |
| Szöveg kinyerés + embedding | `embedder` | sonnet |
| Duplikátum csoportosítás | `dedup-engine` | haiku |
| Dashboard API endpoint | `dashboard-builder` | sonnet |
| Fájlrendszer olvasás/kutatás | `explore` (beépített) | haiku |
| Validáció, tesztelés | `validator` | haiku |

## Párhuzamosítás szabályok
- PÁRHUZAMOS: egymástól független fájlok feldolgozása, különböző service-ek skeletonja
- SZEKVENCIÁLIS: ha B kimenet függ A kimenetétől (pl. dedup csak indexelés után)
- MAX 5 párhuzamos Task egyszerre (token budget)

## Szolgáltatások
| Service | Státusz |
|---|---|
| nas-monitor | ✅ KÉSZ |
| indexer | ✅ KÉSZ |
| pst-extractor | 🔲 TODO |
| embedder | 🔲 TODO |
| qdrant | ✅ infra |
| dashboard | 🔲 TODO |

## Kötelező minták
- `activity_guard.py` – minden új service-ben, copy from `indexer/`
- SQLite WAL + `check_same_thread=False` + batch commit 500-anként
- Ollama Docker-ből: `http://host.docker.internal:11434`
- Csak `rag_winner=1` fájlok kerülnek Qdrant-ba

## Duplikátum szintek
1. SHA256 – bájt-azonos
2. content_hash – szöveg hash (normalizált)
3. Message-ID – email dedup több PST közt

Séma: @docs/SCHEMA.md | Architektúra: @docs/ARCHITECTURE.md
