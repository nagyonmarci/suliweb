# Adatbázis séma – index.db

SQLite, WAL mód, foreign_keys ON. Helye: `./data/index.db`

## files – minden fájl a NAS-on

```sql
CREATE TABLE files (
    path            TEXT    PRIMARY KEY,   -- /mnt/nas/docs/report.pdf
    name            TEXT    NOT NULL,
    extension       TEXT    NOT NULL,      -- .pdf, .pst, .docx ...
    size            INTEGER NOT NULL,
    mtime           REAL    NOT NULL,
    sha256          TEXT,                  -- dedup 1. szint
    content_hash    TEXT,                  -- dedup 2. szint (normalizált szöveg)
    text_length     INTEGER,
    has_text        BOOLEAN DEFAULT 0,
    language        TEXT,                  -- hu / en / ...
    scanned_at      REAL    NOT NULL,
    text_extracted_at REAL,
    embedded_at     REAL,
    embed_status    TEXT DEFAULT 'pending', -- pending|done|error|skipped
    rag_winner      BOOLEAN DEFAULT 1,     -- 0 = kihagyva a RAG-ból
    source_type     TEXT DEFAULT 'file',   -- file|email|attachment
    scan_run_id     INTEGER REFERENCES scan_runs(id)
);
```

## emails – PST-ből kinyert emailek

```sql
CREATE TABLE emails (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id      TEXT    UNIQUE,        -- RFC Message-ID – dedup 3. szint!
    pst_path        TEXT    NOT NULL REFERENCES files(path),
    subject         TEXT,
    sender          TEXT,
    recipients      TEXT,                  -- JSON tömb
    sent_at         REAL,
    body_text       TEXT,
    body_hash       TEXT,                  -- normalizált törzs hash
    has_attachments BOOLEAN DEFAULT 0,
    attachment_count INTEGER DEFAULT 0,
    extracted_at    REAL,
    embed_status    TEXT DEFAULT 'pending',
    embedded_at     REAL,
    rag_winner      BOOLEAN DEFAULT 1
);
```

## attachments – csatolmányok

```sql
CREATE TABLE attachments (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    email_id        INTEGER NOT NULL REFERENCES emails(id),
    original_name   TEXT    NOT NULL,
    extension       TEXT    NOT NULL,
    size            INTEGER,
    sha256          TEXT,
    content_hash    TEXT,
    extracted_path  TEXT,   -- /mnt/extracted/{pst_stem}/{message_id}/{filename}
    extracted_at    REAL,
    embed_status    TEXT DEFAULT 'pending',
    embedded_at     REAL,
    rag_winner      BOOLEAN DEFAULT 1
);
```

## dup_groups + dup_members – döntési felület

```sql
CREATE TABLE dup_groups (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    match_type   TEXT NOT NULL,   -- sha256 | content_hash | message_id
    hash_value   TEXT NOT NULL,
    item_count   INTEGER NOT NULL,
    total_size_mb REAL,
    saveable_mb  REAL,
    status       TEXT DEFAULT 'pending',  -- pending | reviewed | dismissed
    reviewed_at  REAL,
    created_at   REAL NOT NULL DEFAULT (unixepoch()),
    UNIQUE(match_type, hash_value)
);

CREATE TABLE dup_members (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id    INTEGER NOT NULL REFERENCES dup_groups(id),
    item_type   TEXT NOT NULL,   -- file | email | attachment
    item_id     TEXT NOT NULL,   -- files.path vagy emails.id vagy attachments.id
    is_winner   BOOLEAN DEFAULT 0,
    decision    TEXT DEFAULT 'pending',  -- keep | archive | pending
    decided_at  REAL
);
```

## chunks – RAG darabok

```sql
CREATE TABLE chunks (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    source_type  TEXT NOT NULL,   -- file | email | attachment
    source_id    TEXT NOT NULL,   -- path vagy id
    chunk_index  INTEGER NOT NULL,
    text         TEXT NOT NULL,   -- 512 token, 64 overlap
    token_count  INTEGER,
    qdrant_id    TEXT UNIQUE,
    embedded_at  REAL,
    UNIQUE(source_type, source_id, chunk_index)
);
```

## scan_runs + scan_state

```sql
CREATE TABLE scan_runs (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at   REAL NOT NULL,
    finished_at  REAL,
    files_new    INTEGER DEFAULT 0,
    files_updated INTEGER DEFAULT 0,
    files_skipped INTEGER DEFAULT 0,
    files_deleted INTEGER DEFAULT 0,
    status       TEXT DEFAULT 'running'  -- running | done | interrupted
);

CREATE TABLE scan_state (
    key   TEXT PRIMARY KEY,
    value TEXT
    -- 'last_scanned_path' → crash-safe folytatáshoz
);
```

## Hasznos lekérdezések

```sql
-- PST fájlok méret szerint
SELECT name, path, size/1024/1024 as mb FROM files
WHERE extension='.pst' ORDER BY size DESC;

-- SHA256 duplikátumok
SELECT sha256, COUNT(*) as db, SUM(size)/1024/1024 as mb,
       GROUP_CONCAT(path, ' | ') as paths
FROM files WHERE sha256 IS NOT NULL
GROUP BY sha256 HAVING COUNT(*) > 1 ORDER BY mb DESC;

-- Email duplikátumok (Message-ID)
SELECT message_id, COUNT(*) as db, GROUP_CONCAT(pst_path, ' | ')
FROM emails GROUP BY message_id HAVING COUNT(*) > 1;

-- RAG-ba kerülő elemek
SELECT source_type, COUNT(*) FROM files
WHERE rag_winner=1 AND embed_status='done' GROUP BY source_type;

-- Scan előzmények
SELECT id, datetime(started_at,'unixepoch','localtime') as start,
       round((finished_at-started_at)/3600,2) as h,
       files_new, files_updated, status
FROM scan_runs ORDER BY id DESC LIMIT 10;
```
