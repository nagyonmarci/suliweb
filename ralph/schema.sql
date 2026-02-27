-- ============================================================
--  NAS Indexer – SQLite séma
--  PRAGMA WAL mód – párhuzamos olvasás több Docker service-ből
-- ============================================================

PRAGMA journal_mode = WAL;
PRAGMA synchronous  = NORMAL;
PRAGMA foreign_keys = ON;
PRAGMA cache_size   = -32000;  -- 32 MB

-- ── Fájlok ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS files (
    path                TEXT    PRIMARY KEY,
    name                TEXT    NOT NULL,
    extension           TEXT    NOT NULL,
    size                INTEGER NOT NULL,
    mtime               REAL    NOT NULL,
    sha256              TEXT,
    content_hash        TEXT,
    text_length         INTEGER,
    has_text            INTEGER DEFAULT 0,
    language            TEXT,
    scanned_at          REAL    NOT NULL,
    text_extracted_at   REAL,
    embedded_at         REAL,
    embed_status        TEXT    NOT NULL DEFAULT 'pending',
    rag_winner          INTEGER NOT NULL DEFAULT 1,
    source_type         TEXT    NOT NULL DEFAULT 'file',
    scan_run_id         INTEGER,
    FOREIGN KEY (scan_run_id) REFERENCES scan_runs(id)
);

CREATE INDEX IF NOT EXISTS idx_files_sha256       ON files(sha256);
CREATE INDEX IF NOT EXISTS idx_files_content_hash ON files(content_hash);
CREATE INDEX IF NOT EXISTS idx_files_extension    ON files(extension);
CREATE INDEX IF NOT EXISTS idx_files_embed_status ON files(embed_status);
CREATE INDEX IF NOT EXISTS idx_files_rag_winner   ON files(rag_winner);
CREATE INDEX IF NOT EXISTS idx_files_mtime        ON files(mtime);

-- ── Emailek (PST-ből kinyerve) ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS emails (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id          TEXT    UNIQUE,
    pst_path            TEXT    NOT NULL,
    subject             TEXT,
    sender              TEXT,
    recipients          TEXT,           -- JSON: ["a@b.com"]
    sent_at             REAL,
    body_text           TEXT,
    body_hash           TEXT,
    has_attachments     INTEGER NOT NULL DEFAULT 0,
    attachment_count    INTEGER NOT NULL DEFAULT 0,
    extracted_at        REAL    NOT NULL,
    embed_status        TEXT    NOT NULL DEFAULT 'pending',
    embedded_at         REAL,
    rag_winner          INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (pst_path) REFERENCES files(path)
);

CREATE INDEX IF NOT EXISTS idx_emails_message_id  ON emails(message_id);
CREATE INDEX IF NOT EXISTS idx_emails_body_hash   ON emails(body_hash);
CREATE INDEX IF NOT EXISTS idx_emails_pst_path    ON emails(pst_path);
CREATE INDEX IF NOT EXISTS idx_emails_sent_at     ON emails(sent_at);
CREATE INDEX IF NOT EXISTS idx_emails_embed       ON emails(embed_status);
CREATE INDEX IF NOT EXISTS idx_emails_rag_winner  ON emails(rag_winner);

-- ── Csatolmányok ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS attachments (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    email_id            INTEGER NOT NULL,
    original_name       TEXT    NOT NULL,
    extension           TEXT    NOT NULL,
    size                INTEGER,
    sha256              TEXT,
    content_hash        TEXT,
    extracted_path      TEXT,           -- /volume1/_extracted/...
    extracted_at        REAL,
    embed_status        TEXT    NOT NULL DEFAULT 'pending',
    embedded_at         REAL,
    rag_winner          INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (email_id) REFERENCES emails(id)
);

CREATE INDEX IF NOT EXISTS idx_att_email_id     ON attachments(email_id);
CREATE INDEX IF NOT EXISTS idx_att_sha256       ON attachments(sha256);
CREATE INDEX IF NOT EXISTS idx_att_content_hash ON attachments(content_hash);
CREATE INDEX IF NOT EXISTS idx_att_embed        ON attachments(embed_status);
CREATE INDEX IF NOT EXISTS idx_att_rag_winner   ON attachments(rag_winner);

-- ── Duplikátum csoportok ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS dup_groups (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    match_type      TEXT    NOT NULL,   -- sha256 | content_hash | message_id
    hash_value      TEXT    NOT NULL,
    item_count      INTEGER NOT NULL,
    total_size_mb   REAL    NOT NULL DEFAULT 0,
    saveable_mb     REAL    NOT NULL DEFAULT 0,
    status          TEXT    NOT NULL DEFAULT 'pending',  -- pending|reviewed|dismissed
    reviewed_at     REAL,
    created_at      REAL    NOT NULL DEFAULT (unixepoch()),
    UNIQUE(match_type, hash_value)
);

CREATE INDEX IF NOT EXISTS idx_dup_groups_status ON dup_groups(status);
CREATE INDEX IF NOT EXISTS idx_dup_groups_type   ON dup_groups(match_type);

-- ── Duplikátum csoport tagjai ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS dup_members (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id    INTEGER NOT NULL,
    item_type   TEXT    NOT NULL,   -- file | email | attachment
    item_id     TEXT    NOT NULL,   -- files.path | emails.id | attachments.id
    is_winner   INTEGER NOT NULL DEFAULT 0,
    decision    TEXT    NOT NULL DEFAULT 'pending',  -- keep | archive | pending
    decided_at  REAL,
    FOREIGN KEY (group_id) REFERENCES dup_groups(id)
);

CREATE INDEX IF NOT EXISTS idx_dup_members_group    ON dup_members(group_id);
CREATE INDEX IF NOT EXISTS idx_dup_members_item     ON dup_members(item_type, item_id);
CREATE INDEX IF NOT EXISTS idx_dup_members_decision ON dup_members(decision);

-- ── RAG chunk-ok ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chunks (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    source_type     TEXT    NOT NULL,   -- file | email | attachment
    source_id       TEXT    NOT NULL,
    chunk_index     INTEGER NOT NULL,
    text            TEXT    NOT NULL,
    token_count     INTEGER,
    qdrant_id       TEXT    UNIQUE,
    embedded_at     REAL,
    UNIQUE(source_type, source_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_chunks_source   ON chunks(source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_chunks_qdrant   ON chunks(qdrant_id);

-- ── Scan futások ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS scan_runs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at      REAL    NOT NULL,
    finished_at     REAL,
    files_new       INTEGER NOT NULL DEFAULT 0,
    files_updated   INTEGER NOT NULL DEFAULT 0,
    files_skipped   INTEGER NOT NULL DEFAULT 0,
    files_deleted   INTEGER NOT NULL DEFAULT 0,
    status          TEXT    NOT NULL DEFAULT 'running'  -- running|done|interrupted
);

-- ── Általános állapot (folytatható scan, stb.) ────────────────────────────
CREATE TABLE IF NOT EXISTS scan_state (
    key     TEXT PRIMARY KEY,
    value   TEXT
);

-- ── Hasznos VIEW-ok ───────────────────────────────────────────────────────

-- Duplikátum csoportok részletesen (dashboard-hoz)
CREATE VIEW IF NOT EXISTS v_dup_groups_detail AS
SELECT
    g.id,
    g.match_type,
    g.item_count,
    g.total_size_mb,
    g.saveable_mb,
    g.status,
    g.created_at,
    COUNT(CASE WHEN m.decision = 'keep'    THEN 1 END) AS decided_keep,
    COUNT(CASE WHEN m.decision = 'archive' THEN 1 END) AS decided_archive,
    COUNT(CASE WHEN m.decision = 'pending' THEN 1 END) AS decided_pending
FROM dup_groups g
LEFT JOIN dup_members m ON m.group_id = g.id
GROUP BY g.id;

-- Embedding progress (dashboard-hoz)
CREATE VIEW IF NOT EXISTS v_embed_progress AS
SELECT
    'file'       AS source_type,
    COUNT(*)     AS total,
    SUM(CASE WHEN embed_status = 'done'    THEN 1 ELSE 0 END) AS done,
    SUM(CASE WHEN embed_status = 'pending' THEN 1 ELSE 0 END) AS pending,
    SUM(CASE WHEN embed_status = 'error'   THEN 1 ELSE 0 END) AS error,
    SUM(CASE WHEN embed_status = 'skipped' THEN 1 ELSE 0 END) AS skipped
FROM files WHERE rag_winner = 1
UNION ALL
SELECT 'email', COUNT(*),
    SUM(CASE WHEN embed_status='done'    THEN 1 ELSE 0 END),
    SUM(CASE WHEN embed_status='pending' THEN 1 ELSE 0 END),
    SUM(CASE WHEN embed_status='error'   THEN 1 ELSE 0 END),
    SUM(CASE WHEN embed_status='skipped' THEN 1 ELSE 0 END)
FROM emails WHERE rag_winner = 1
UNION ALL
SELECT 'attachment', COUNT(*),
    SUM(CASE WHEN embed_status='done'    THEN 1 ELSE 0 END),
    SUM(CASE WHEN embed_status='pending' THEN 1 ELSE 0 END),
    SUM(CASE WHEN embed_status='error'   THEN 1 ELSE 0 END),
    SUM(CASE WHEN embed_status='skipped' THEN 1 ELSE 0 END)
FROM attachments WHERE rag_winner = 1;
