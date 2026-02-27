# NAS Indexer & RAG Pipeline

## Projekt célja
16 TB-os Synology DS418 NAS teljes indexelése, duplikátum felismerés és RAG alapozás.
**A NAS csak tároló. Minden feldolgozás a MacBook Pro M4 Max-on fut (Docker Compose).**

## Hardver
- **NAS**: Synology DS418, DSM 6.2.4, ARM, 2GB RAM. Sem Docker, sem Entware. Csak SMB/NFS mount.
- **Mac**: M4 Max, 128 GB RAM. Ollama natívan (Metal GPU). Docker Desktop.
- **NAS elérés**: SMB mount → `/Volumes/nas`. Csatolmányok → `/Volumes/nas/_extracted/`

## Szolgáltatások (docker compose, restart: always)
| Szolgáltatás | Státusz | Leírás |
|---|---|---|
| nas-monitor | ✅ KÉSZ | Synology API polling 15s, metrics.db |
| indexer | ✅ KÉSZ | SHA256 scan, crash-safe, activity_guard, SQLite |
| pst-extractor | 🔲 TODO | PST → emails + attachments, Message-ID dedup |
| embedder | 🔲 TODO | PDF/DOCX/PPTX/kép szöveg, DeepSeek-OCR, Qdrant |
| qdrant | ✅ infra | Vektortároló, port 6333 |
| dashboard | 🔲 TODO | Dedup UI + RAG tesztelő, port 8080 |

## Kulcs architektúra döntések
- **Időzítés**: minden service `activity_guard.py` mintát követ
  - Hétköznap: 22:00–06:00, hétvégén: egész nap
  - NAS CPU < 50%, TX < 40 MB/s (embedder: < 40% / < 30 MB/s)
- **Crash-safe**: `scan_state` tábla menti az utolsó feldolgozott útvonalat
- **Ollama**: natívan fut Mac-en → `host.docker.internal:11434`
- **Modellek**: `nomic-embed-text` + `deepseek-ocr`

## Duplikátum rétegek
1. **SHA256** – bájt-azonos (100% biztos)
2. **content_hash** – normalizált szöveg hash
3. **Message-ID** – email dedup több PST közt (100% biztos)
> Szemantikai hasonlóság (cosine > 0.95) **NEM duplikátum** – mindkettő RAG-ba kerül

## RAG szabály
Csak `rag_winner = 1` fájlok kerülnek Qdrant-ba. A felhasználó dönt a dashboard UI-ban.

## Adatbázis (SQLite WAL): ./data/index.db
Táblák: `files`, `emails`, `attachments`, `dup_groups`, `dup_members`, `chunks`, `scan_runs`
→ Részletes séma: @docs/SCHEMA.md

## Kód konvenciók
- Python 3.12, type hints, `loguru`, `httpx`
- Minden új service-ben kötelező: `activity_guard.py` minta
- SQLite WAL, batch commit 500-anként
