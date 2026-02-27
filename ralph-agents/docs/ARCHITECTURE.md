# Architektúra – NAS Indexer & RAG Pipeline

## Áttekintés

```
MacBook M4 Max (128 GB RAM)
├── Docker Compose (restart: always)
│   ├── nas-monitor      → Synology API, terhelés mérés
│   ├── indexer          → SMB mount, SHA256, SQLite
│   ├── pst-extractor    → PST → emailek + csatolmányok
│   ├── embedder         → szöveg kinyerés + Qdrant
│   ├── qdrant           → vektortároló (port 6333)
│   └── dashboard        → web UI (port 8080)
├── Ollama (natív, Metal GPU)
│   ├── deepseek-ocr     → képek, szkennelt doksik
│   └── nomic-embed-text → embedding
└── SMB mount: /Volumes/nas → Synology DS418 /volume1
```

## Adatfolyam

```
1. SCAN      indexer bejárja /Volumes/nas
             → SHA256 + metadata → files tábla

2. PST       pst-extractor figyeli az új .pst fájlokat
             → emails + attachments táblák
             → csatolmányok fizikailag /nas/_extracted/-be

3. DEDUP     scan után: dup_groups + dup_members feltöltés
             → sha256 | content_hash | message_id alapján
             → status = 'pending'

4. REVIEW    dashboard dedup UI
             → felhasználó dönt: keep / archive
             → rag_winner flag frissül

5. EMBED     embedder csak rag_winner=1 elemeken fut
             → szöveg kinyerés típusonként
             → 512 token chunk, 64 overlap
             → nomic-embed-text → Qdrant

6. RAG KÉSZ  http://localhost:8080 – keresési felület
```

## Időzítési logika (activity_guard.py)

Minden service **folyamatosan fut** (`restart: always`), de aktív munkát csak akkor végez:

| Feltétel | Érték |
|---|---|
| Hétköznap | 22:00 – 06:00 |
| Hétvégén | egész nap |
| NAS CPU max | 50% (embedder: 40%) |
| NAS TX max | 40 MB/s (embedder: 30 MB/s) |
| Poll interval | 60 másodperc |
| Rescan delay | 6 óra |

Ha feltétel nem teljesül → service alszik, 60s-ként újraellenőrzi. **Nem kell cron.**

## Crash-safe scan

Az indexer `scan_state` táblába menti az utolsó feldolgozott fájl útvonalát.
16 TB-os scan megszakadás esetén onnan folytatja, nem kezdi elölről.

## Vision / OCR pipeline

```
Kép fájl
  ↓
Típus detekció (arány + EXIF + fájlnév)
  ├── document → "Convert the document to markdown."
  ├── table    → "Given the layout of the image."
  ├── figure   → "Parse the figure."
  └── general  → "Free OCR."
  ↓
deepseek-ocr @ Ollama (Metal GPU, ~3s/kép, 6.7 GB)
  ↓
Markdown szöveg → chunker → nomic-embed-text → Qdrant
```

**Telepítés (Mac, natívan!):**
```bash
brew install ollama
ollama pull deepseek-ocr
ollama pull nomic-embed-text
```

## Szövegkinyerés típusonként

| Fájltípus | Eszköz | Megjegyzés |
|---|---|---|
| .pdf | PyMuPDF (fitz) | szöveg + képek külön |
| .docx | python-docx | |
| .pptx | python-pptx | slide-ok + notes |
| .xlsx | openpyxl | cellák szövegként |
| .jpg/.png/.tiff | deepseek-ocr @ Ollama | prompt típusonként |
| .msg/.eml | email stdlib | |
| .txt/.md | közvetlen | |
| .pst | pypff / libpff | → emails tábla |

## Duplikátum döntési felület

A dashboard `/duplicates` oldalán kártyás nézet:

```
┌─────────────────────────────────────────────────────┐
│ CSOPORT #47  │  sha256  │  3 fájl  │  248 MB össz  │
├──────────────┬──────────┬───────────────────────────┤
│ ● report.pdf │ 248 MB   │ /archive/2023/            │ ← keep
│   report.pdf │ 248 MB   │ /backup/old/              │ ← archive
│   Report.PDF │ 248 MB   │ /temp/                    │ ← archive
└──────────────┴──────────┴───────────────────────────┘
  [Ezt tartom meg]  [Mindet megtartom]  [Kihagyom]
```

POST `/api/duplicates/{group_id}/decision` → `rag_winner` flag frissítés.

## Projekt struktúra

```
nas-indexer/
├── CLAUDE.md                    ← Claude Code fő memória (ez a fájl)
├── docker-compose.yml
├── .env                         ← NAS_HOST, NAS_USER, NAS_PASS (gitignore!)
├── .gitignore
├── docs/
│   ├── ARCHITECTURE.md          ← ez a fájl
│   └── SCHEMA.md                ← SQLite séma részletesen
├── .claude/
│   ├── agents/
│   │   ├── pst-extractor-agent.md
│   │   └── embedder-agent.md
│   ├── skills/
│   │   ├── pst-extractor/SKILL.md
│   │   ├── embedder/SKILL.md
│   │   ├── dedup-engine/SKILL.md
│   │   └── dashboard-dedup/SKILL.md
│   ├── rules/
│   │   ├── activity-guard.md    ← időzítési minta
│   │   └── sqlite-patterns.md   ← DB konvenciók
│   └── commands/
│       ├── implement-pst.md
│       ├── implement-embedder.md
│       └── implement-dashboard.md
├── data/                        ← gitignore!
│   ├── index.db
│   ├── metrics.db
│   └── *.log
├── nas-monitor/    ✅ KÉSZ
├── indexer/        ✅ KÉSZ
│   ├── activity_guard.py        ← MINTA – minden új service ezt követi
│   ├── nas_api.py
│   ├── scanner.py
│   ├── db.py
│   └── main.py
├── pst-extractor/  🔲 TODO
├── embedder/       🔲 TODO
└── dashboard/      🔲 TODO (részben kész)
```
