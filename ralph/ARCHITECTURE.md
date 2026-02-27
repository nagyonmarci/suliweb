# NAS Indexer – Teljes Architektúra

> Ez a dokumentum a rendszer teljes leírása.
> Claude Code vagy más AI agent számára ez az egyetlen referencia.
> Minden implementációs döntés itt van indokolva.

---

## Célok

1. **16 TB-os Synology NAS (DS418, DSM 6.2.4) teljes bejárása** – minden fájl indexelése
2. **PST fájlok feldolgozása** – emailek + csatolmányok kinyerése
3. **Duplikátum felismerés** – 3 rétegben, felhasználói döntéssel
4. **RAG alap felépítése** – csak a "winner" (nem duplikált) dokumentumok kerülnek be
5. **Képek OCR-je** – DeepSeek-OCR vision modellel, lokálisan

---

## Hardver & Környezet

| Komponens | Részlet |
|-----------|---------|
| NAS | Synology DS418, ARM Realtek RTD1296, 2 GB RAM |
| NAS OS | DSM 6.2.4-25556 Update 7 – **Docker NEM fut rajta** |
| NAS kapacitás | 16 TB, egy partíció (/volume1) |
| NAS elérés | SMB mount: `smb://NAS_HOST/volume1` → `/Volumes/nas` Mac-en |
| Feldolgozó gép | MacBook Pro M4 Max, 128 GB RAM |
| Mac OS | macOS – Docker Desktop fut rajta |
| Ollama | **Natívan fut Mac-en** (nem Dockerben) – Metal GPU használathoz |

---

## Szolgáltatások áttekintése

```
┌─────────────────────────────────────────────────────────────────┐
│  MacBook M4 Max                                                  │
│                                                                  │
│  Ollama (natív, Metal GPU)                                       │
│    ├── nomic-embed-text   (embedding, 768 dim)                   │
│    └── deepseek-ocr       (vision OCR, ~3s/kép)                  │
│                                                                  │
│  Docker Compose                                                  │
│    ├── nas-monitor     → Synology API polling (15s)              │
│    ├── indexer         → fájl bejárás, SHA256, duplikátum DB     │
│    ├── pst-extractor   → PST → emailek + csatolmányok            │
│    ├── embedder        → szöveg kinyerés + chunking + Qdrant     │
│    ├── qdrant          → vektortároló                            │
│    └── dashboard       → webes UI (duplikátum döntés + státusz)  │
│                                                                  │
│  SMB mount: /Volumes/nas → NAS /volume1                          │
│    └── /Volumes/nas/_extracted  (kinyert csatolmányok)           │
└─────────────────────────────────────────────────────────────────┘
```

---

## Feldolgozási pipeline – lépésről lépésre

```
NAS fájlrendszer
      │
      ▼
┌─────────────┐
│   indexer   │  Bejárja a teljes NAS-t. Minden fájlhoz:
│             │  - path, name, extension, size, mtime
│             │  - SHA256 hash (bájt-szintű duplikátum kulcs)
│             │  - files táblába írja
│             │  - duplikátum csoportokat képez (dup_groups + dup_members)
└──────┬──────┘
       │ ha .pst fájl jelenik meg
       ▼
┌──────────────────┐
│  pst-extractor   │  Minden PST fájlhoz:
│                  │  - Emaileket kinyeri → emails tábla
│                  │    · message_id (RFC header) = duplikátum kulcs
│                  │    · subject, sender, recipients, sent_at, body_text
│                  │    · body_hash (normalizált törzs hash)
│                  │  - Csatolmányokat kinyeri:
│                  │    · NAS-ra menti: /volume1/_extracted/{pst_name}/{msg_id}/{filename}
│                  │    · attachments táblába írja
│                  │    · SHA256 + content_hash számít
│                  │  - Duplikátumokat csoportosítja (message_id egyezés)
└──────┬───────────┘
       │ ha embed_status = 'pending' ÉS rag_winner = 1
       ▼
┌──────────────┐
│   embedder   │  Minden "winner" dokumentumhoz:
│              │
│  Szöveg kinyerés típusonként:
│    .pdf      → PyMuPDF (fitz)
│    .docx     → python-docx
│    .pptx     → python-pptx
│    .xlsx     → openpyxl
│    .txt .md  → közvetlen olvasás
│    .eml .msg → email parser
│    .jpg .png │
│    .tiff .bmp│→ DeepSeek-OCR @ Ollama
│    .gif .webp│   prompt: típus alapján (document/table/figure/general)
│              │
│  Chunking:
│    - 512 token, 64 token overlap
│    - Fejléc-tudatos (Markdown h1/h2 határán nem vág)
│    - Minimum 50 token (rövid chunk-ot elveti)
│              │
│  Embedding:
│    - nomic-embed-text @ Ollama → 768 dim vektor
│    - Qdrant-ba tölti fel metadatával együtt
│    - chunks táblába írja a chunk szövegét + qdrant_id-t
│              │
│  Státusz frissítés:
│    - files/emails/attachments.embed_status = 'done'
│    - files/emails/attachments.embedded_at = now()
└──────┬───────┘
       │
       ▼
┌──────────────┐
│    qdrant    │  Collection: "documents"
│              │  Minden pont metaadatai:
│              │    - source_type: file|email|attachment
│              │    - source_id: path vagy id
│              │    - chunk_index
│              │    - extension
│              │    - name
│              │    - mtime / sent_at
└──────────────┘
```

---

## Duplikátum felismerés – 3 réteg

### Réteg 1 – SHA256 (bájt-szintű)
- **Mikor:** indexer futáskor, minden fájlnál
- **Mit fed le:** teljesen azonos fájlok (még ha más nevük/helyük is van)
- **Bizonyosság:** 100%
- **Érintett táblák:** `files`, `attachments`

### Réteg 2 – content_hash (tartalom-szintű)
- **Mikor:** embedder szövegkinyerés után
- **Mit fed le:** "ugyanaz a dokumentum, más formátumban vagy fájlnévvel"
- **Hogyan:** kinyert szöveg → lowercase → whitespace normalizálás → SHA256
- **Bizonyosság:** ~99% (eltérő szóközök, fejlécek nem zavarják)
- **Érintett táblák:** `files`, `emails` (body_hash), `attachments`

### Réteg 3 – message_id (email-specifikus)
- **Mikor:** pst-extractor futáskor
- **Mit fed le:** ugyanaz az email több PST-ben
- **Hogyan:** RFC 2822 Message-ID header – globálisan egyedi azonosító
- **Bizonyosság:** 100%
- **Érintett táblák:** `emails`

### Ami szándékosan NINCS benne
- **Szemantikai duplikátum (cosine hasonlóság)** – ez RAG tartalom, nem duplikátum.
  Két különböző de hasonló témájú dokumentum mindkettő értékes a RAG számára.

---

## Adatbázis séma (SQLite, WAL mód)

```sql
-- Fájlok
files (
    path TEXT PK,           -- teljes útvonal
    name TEXT,              -- fájlnév
    extension TEXT,         -- .pdf, .docx stb.
    size INTEGER,           -- bájtban
    mtime REAL,             -- Unix timestamp, változás detektáláshoz
    sha256 TEXT,            -- bájt-szintű hash → dup réteg 1
    content_hash TEXT,      -- szöveg hash → dup réteg 2
    text_length INTEGER,    -- kinyert szöveg hossza
    has_text BOOLEAN,       -- kinyerhető-e szöveg
    language TEXT,          -- hu/en/de stb.
    scanned_at REAL,
    text_extracted_at REAL,
    embedded_at REAL,
    embed_status TEXT,      -- pending|done|error|skipped
    rag_winner BOOLEAN,     -- 0 = duplikátum, nem kerül RAG-ba
    source_type TEXT,       -- file|email|attachment
    scan_run_id INTEGER
)

-- Emailek (PST-ből)
emails (
    id INTEGER PK,
    message_id TEXT UNIQUE, -- RFC header → dup réteg 3
    pst_path TEXT,          -- melyik PST-ből jött
    subject TEXT,
    sender TEXT,
    recipients TEXT,        -- JSON ["a@b.com", ...]
    sent_at REAL,
    body_text TEXT,
    body_hash TEXT,         -- normalizált törzs → dup réteg 2
    has_attachments BOOLEAN,
    attachment_count INTEGER,
    extracted_at REAL,
    embed_status TEXT,
    embedded_at REAL,
    rag_winner BOOLEAN
)

-- Csatolmányok
attachments (
    id INTEGER PK,
    email_id INTEGER FK,
    original_name TEXT,
    extension TEXT,
    size INTEGER,
    sha256 TEXT,            -- dup réteg 1
    content_hash TEXT,      -- dup réteg 2
    extracted_path TEXT,    -- NAS-on: /volume1/_extracted/...
    extracted_at REAL,
    embed_status TEXT,
    embedded_at REAL,
    rag_winner BOOLEAN
)

-- Duplikátum csoportok
dup_groups (
    id INTEGER PK,
    match_type TEXT,        -- sha256|content_hash|message_id
    hash_value TEXT,        -- az egyező hash
    item_count INTEGER,
    total_size_mb REAL,
    saveable_mb REAL,       -- felszabadítható, ha 1 marad
    status TEXT,            -- pending|reviewed|dismissed
    reviewed_at REAL,
    created_at REAL
)

-- Duplikátum csoport tagjai
dup_members (
    id INTEGER PK,
    group_id INTEGER FK,
    item_type TEXT,         -- file|email|attachment
    item_id TEXT,           -- files.path vagy emails.id
    is_winner BOOLEAN,      -- ezt tartja meg a felhasználó
    decision TEXT,          -- keep|archive|pending
    decided_at REAL
)

-- RAG chunk-ok
chunks (
    id INTEGER PK,
    source_type TEXT,       -- file|email|attachment
    source_id TEXT,         -- path vagy id
    chunk_index INTEGER,
    text TEXT,
    token_count INTEGER,
    qdrant_id TEXT UNIQUE   -- Qdrant pont ID
)

-- Scan futások
scan_runs (
    id INTEGER PK,
    started_at REAL,
    finished_at REAL,
    files_new INTEGER,
    files_updated INTEGER,
    files_skipped INTEGER,
    files_deleted INTEGER,
    status TEXT             -- running|done|interrupted
)

-- Kulcs-érték állapot (pl. utolsó scan pozíció)
scan_state (
    key TEXT PK,
    value TEXT
)
```

---

## Időzítési logika (minden szolgáltatásban közös)

Az `ActivityGuard` osztály minden service-ben ugyanúgy működik:

```python
# Aktív időszak:
#   Hétköznap:  22:00 – 06:00
#   Hétvégén:   egész nap (WEEKEND_ALWAYS_ACTIVE=true)
#
# NAS terhelés limit:
#   CPU  < 50%     (nas-monitor méri, metrics.db-ből olvassa)
#   TX   < 40 MB/s
#
# Ha bármelyik feltétel nem teljesül:
#   → 60 másodpercet vár, újraellenőriz
#   → futás közben is ellenőriz (minden 100. fájlnál)
#   → megszakítás esetén menti az utolsó pozíciót (scan_state tábla)
#   → folytatható – nem kezdi elölről
```

---

## Ollama – natív telepítés Mac-en

```bash
brew install ollama

# Modellek letöltése
ollama pull nomic-embed-text   # embedding, 274 MB
ollama pull deepseek-ocr       # vision OCR, 6.7 GB

# Automatikus indítás
ollama serve   # vagy: System Settings → Login Items
```

Docker konténerek ezen a címen érik el: `http://host.docker.internal:11434`

---

## DeepSeek-OCR prompt stratégia

```python
# Képtípus → prompt mód
VISION_PROMPTS = {
    "document": "<image>\n<|grounding|>Convert the document to markdown.",
    "table":    "<image>\n<|grounding|>Given the layout of the image.",
    "figure":   "<image>\nParse the figure.",
    "general":  "<image>\nFree OCR.",
}

# Automatikus típus detekció
def detect_image_type(path, img) -> str:
    w, h = img.size
    ratio = h / w
    exif  = get_exif(path)

    if exif.get("is_photo"):        return "general"
    if ratio > 1.2:                 return "document"   # álló, A4-szerű
    if ratio < 0.7:                 return "figure"     # fekvő, diagram
    return "table"                                       # négyzetes → tábla
```

---

## Dashboard – oldalak és funkciók

### `/` – Főoldal / Összesítő
- Indexelt fájlok száma, teljes méret
- PST fájlok száma / mérete
- Duplikátumok száma / megtakarítható GB
- Embedding státusz (pending / done / error)
- NAS terhelés grafikon (utolsó 60 perc)
- Scan futás előzmények

### `/duplicates` – Duplikátum döntési felület
- Csoportonként listázva (match_type szerint szűrhető)
- Minden csoportban: fájlok neve, útvonala, mérete, dátuma, forrása
- Auto-javaslat: legújabb vagy legnagyobb fájl = winner jelölés
- Gombok: **[Ezt tartom meg]** / **[Mindet megtartom]** / **[Kihagyom]**
- Bulk akciók: "Mindent jóváhagyok ahol 1 winner van"
- Szűrők: csak PST emailek / csak fájlok / csak csatolmányok

### `/pst` – PST tartalom böngésző
- PST fájlonként bontva
- Emailek listája (feladó, tárgy, dátum, mellékletek száma)
- Csatolmányok listája és letöltési link

### `/rag` – RAG státusz
- Chunk-ok száma Qdrant-ban
- Feldolgozatlan dokumentumok listája
- Keresési teszt: prompt beírása → top 5 releváns chunk visszamutatva

---

## Projekt fájlstruktúra

```
nas-indexer/
│
├── docker-compose.yml
├── .env                        # NAS_HOST, NAS_USER, NAS_PASS
├── .env.example
├── ARCHITECTURE.md             # ← ez a fájl
│
├── data/                       # runtime adatok (gitignore-ba)
│   ├── index.db                # SQLite főadatbázis
│   ├── metrics.db              # NAS terhelés előzmények
│   ├── indexer.log
│   ├── pst-extractor.log
│   └── embedder.log
│
├── nas-monitor/
│   ├── Dockerfile
│   ├── requirements.txt
│   └── main.py                 # Synology API polling → metrics.db
│
├── indexer/
│   ├── Dockerfile
│   ├── requirements.txt
│   ├── main.py                 # belépési pont, végtelen ciklus
│   ├── activity_guard.py       # időzítés + NAS terhelés figyelő
│   ├── nas_api.py              # Synology REST API wrapper
│   ├── scanner.py              # os.walk + hash + db írás
│   └── db.py                  # SQLite helper függvények
│
├── pst-extractor/
│   ├── Dockerfile
│   ├── requirements.txt        # pypff, extract-msg
│   ├── main.py                 # belépési pont
│   ├── activity_guard.py       # (ugyanaz mint indexer/activity_guard.py)
│   ├── pst_parser.py           # pypff wrapper, email + csatolmány kinyerés
│   ├── attachment_saver.py     # NAS-ra mentés, útvonal generálás
│   ├── dedup.py               # message_id + body_hash duplikátum logika
│   └── db.py                  # emails + attachments tábla műveletek
│
├── embedder/
│   ├── Dockerfile
│   ├── requirements.txt        # pymupdf, python-docx, python-pptx,
│   │                           # openpyxl, httpx, qdrant-client
│   ├── main.py                 # belépési pont
│   ├── activity_guard.py       # (ugyanaz)
│   ├── extractors/
│   │   ├── __init__.py
│   │   ├── pdf.py              # PyMuPDF
│   │   ├── docx.py             # python-docx
│   │   ├── pptx.py             # python-pptx
│   │   ├── xlsx.py             # openpyxl
│   │   ├── text.py             # .txt, .md, .csv
│   │   ├── email.py            # .eml, .msg
│   │   └── image.py            # DeepSeek-OCR @ Ollama
│   ├── chunker.py              # 512 token, 64 overlap, header-aware
│   ├── embedder.py             # nomic-embed-text @ Ollama
│   ├── qdrant_client.py        # Qdrant feltöltés + collection init
│   └── db.py                  # embed_status frissítés, chunks tábla
│
└── dashboard/
    ├── Dockerfile
    ├── requirements.txt        # fastapi, uvicorn, jinja2
    ├── main.py                 # FastAPI app, route-ok
    ├── routers/
    │   ├── stats.py            # / főoldal API
    │   ├── duplicates.py       # /duplicates GET + POST (döntés)
    │   ├── pst.py              # /pst böngésző
    │   └── rag.py              # /rag státusz + teszt keresés
    └── templates/
        ├── base.html           # közös layout, navigáció
        ├── index.html          # főoldal
        ├── duplicates.html     # duplikátum döntési felület
        ├── pst.html            # PST böngésző
        └── rag.html            # RAG státusz
```

---

## Környezeti változók – teljes lista

```bash
# .env
NAS_HOST=192.168.1.100
NAS_USER=admin
NAS_PASS=your_password

# Minden service-ben közös (docker-compose.yml-ben)
ACTIVE_HOURS_START=22
ACTIVE_HOURS_END=6
WEEKEND_ALWAYS_ACTIVE=true
POLL_INTERVAL_SECONDS=60

# indexer
SCAN_PATH=/mnt/nas
DB_PATH=/data/index.db
MAX_NAS_CPU=50
MAX_NAS_TX_MBPS=40
RESCAN_DELAY_SECONDS=21600    # 6 óra

# pst-extractor
EXTRACTED_BASE=/mnt/extracted  # → NAS /volume1/_extracted

# embedder
QDRANT_HOST=qdrant
QDRANT_PORT=6333
OLLAMA_HOST=http://host.docker.internal:11434
EMBEDDING_MODEL=nomic-embed-text
VISION_MODEL=deepseek-ocr
CHUNK_SIZE=512
CHUNK_OVERLAP=64
MAX_NAS_CPU=40                 # embedder engedékenyebb – kevésbé NAS-igényes
MAX_NAS_TX_MBPS=30
```

---

## NAS SMB mount – tartós beállítás Mac-en

```bash
# Egyszeri mount
open "smb://admin@192.168.1.100/volume1"

# Automatikus mount login után – létrehozz egy Login Item szkriptet:
# ~/Library/Scripts/mount-nas.sh
#!/bin/bash
sleep 15   # hálózat felállására vár
/usr/bin/osascript -e 'mount volume "smb://admin@192.168.1.100/volume1"'

# _extracted mappa a NAS-on (egyszer, SSH-ból vagy DSM-ből)
mkdir /volume1/_extracted
```

---

## Indítás – első alkalommal

```bash
# 1. Ollama modellek (Mac, natív)
ollama pull nomic-embed-text
ollama pull deepseek-ocr

# 2. NAS mount
open "smb://admin@NAS_IP/volume1"

# 3. Konfiguráció
cd nas-indexer
cp .env.example .env
nano .env   # NAS_HOST, NAS_USER, NAS_PASS

# 4. Adatmappa
mkdir -p data

# 5. Indítás
docker compose up -d

# 6. Logok figyelése
docker compose logs -f indexer
docker compose logs -f pst-extractor
docker compose logs -f embedder

# 7. Dashboard
open http://localhost:8080
```

---

## Mi van kész, mi van hátra

### ✅ Kész (ebben a sessionben megírva)
- `docker-compose.yml` – teljes service definíció
- `nas-monitor/` – teljes implementáció
- `indexer/` – teljes implementáció (scanner, activity_guard, nas_api, db)
- `dashboard/` – alap implementáció (stats, PST lista, duplikátum lista, NAS grafikon)

### 🔲 Még implementálandó
- `pst-extractor/` – teljes implementáció
  - `pst_parser.py` – pypff wrapper
  - `attachment_saver.py` – NAS-ra mentés
  - `dedup.py` – message_id + body_hash logika
- `embedder/` – teljes implementáció
  - `extractors/` – minden fájltípus
  - `extractors/image.py` – DeepSeek-OCR integráció
  - `chunker.py` – header-aware chunking
  - `qdrant_client.py` – collection init + feltöltés
- `dashboard/routers/duplicates.py` – döntési logika POST endpoint
- `dashboard/templates/duplicates.html` – interaktív döntési felület
- `dashboard/routers/rag.py` – RAG teszt keresés
- `schema.sql` – teljes adatbázis séma fájlban

### 💡 Javasolt sorrend Claude Code-dal
1. `schema.sql` – először az adatbázis, minden más erre épül
2. `pst-extractor/` – PST kinyerés a legsajátosabb feladat
3. `embedder/extractors/` – fájltípusonként haladva
4. `embedder/` fő pipeline – chunker + qdrant
5. `dashboard/` duplikátum döntési felület
6. `dashboard/` RAG teszt keresés

---

## Függőségek összefoglalva

```
nas-monitor:    httpx, loguru
indexer:        httpx, loguru
pst-extractor:  pypff, extract-msg, httpx, loguru
embedder:       pymupdf, python-docx, python-pptx, openpyxl,
                httpx, qdrant-client, tiktoken, loguru, Pillow
dashboard:      fastapi, uvicorn, jinja2, loguru
```

---

## Fontos implementációs megjegyzések

1. **SQLite WAL mód** – minden service ugyanazt az `index.db`-t írja/olvassa.
   WAL módban ez biztonságos, de csak egy service írjon egy táblába egyszerre.
   Az indexer írja a `files`-t, a pst-extractor az `emails`/`attachments`-t,
   az embedder csak az `embed_status`-t frissíti és a `chunks`-t írja.

2. **activity_guard.py másolás** – minden service-ben ugyanaz a fájl.
   Refaktorálható közös Python package-be, de a Docker egyszerűség kedvéért
   másolatként is működik.

3. **NAS írás** – csak a pst-extractor ír a NAS-ra (`_extracted` mappába).
   Minden más service read-only mountot használ.

4. **Qdrant collection neve** – `"documents"` – egységesen minden service-ben.
   Payload mezők: `source_type`, `source_id`, `chunk_index`, `extension`,
   `name`, `mtime`, `sent_at`, `language`.

5. **PST kinyerés útvonal konvenció**:
   `/volume1/_extracted/{pst_filename_stem}/{message_id_hash}/{attachment_name}`
   A `message_id_hash` az RFC Message-ID első 16 karaktere (URL-safe).

6. **Duplikátum döntés hatása**:
   - `dup_members.decision = 'keep'` → `files/emails/attachments.rag_winner = 1`
   - `dup_members.decision = 'archive'` → `rag_winner = 0`, embedder kihagyja
   - A fizikai fájlokat a rendszer **soha nem törli** – csak az indexben jelöli
