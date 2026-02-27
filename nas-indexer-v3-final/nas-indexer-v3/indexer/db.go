package db

import (
	"database/sql"
	"log/slog"
	"path/filepath"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

// DB represents a database connection
type DB struct {
	*sql.DB
	log *slog.Logger
}

// OpenDB opens a connection to the SQLite database with proper settings
func OpenDB(path string) (*DB, error) {
	dsn := path + "?_busy_timeout=5000&_journal_mode=WAL&_foreign_keys=on&_synchronous=NORMAL"
	db, err := sql.Open("sqlite3", dsn)
	if err != nil {
		return nil, err
	}

	// Set connection limits
	db.SetMaxOpenConns(1) // SQLite: only 1 writer
	db.SetMaxIdleConns(1)

	// Initialize the database schema
	if err := initSchema(db); err != nil {
		return nil, err
	}

	return &DB{DB: db, log: slog.Default()}, nil
}

// initSchema initializes the database schema
func initSchema(db *sql.DB) error {
	schema := `
	CREATE TABLE IF NOT EXISTS files (
		path            TEXT    PRIMARY KEY,
		name            TEXT    NOT NULL,
		extension       TEXT    NOT NULL,
		size            INTEGER NOT NULL,
		mtime           REAL    NOT NULL,
		sha256          TEXT,
		content_hash    TEXT,
		text_length     INTEGER,
		has_text        BOOLEAN DEFAULT 0,
		language        TEXT,
		scanned_at      REAL    NOT NULL,
		text_extracted_at REAL,
		embedded_at     REAL,
		embed_status    TEXT DEFAULT 'pending',
		rag_winner      BOOLEAN DEFAULT 1,
		source_type     TEXT DEFAULT 'file',
		scan_run_id     INTEGER REFERENCES scan_runs(id)
	);

	CREATE TABLE IF NOT EXISTS emails (
		id              INTEGER PRIMARY KEY AUTOINCREMENT,
		message_id      TEXT    UNIQUE,
		pst_path        TEXT    NOT NULL REFERENCES files(path),
		subject         TEXT,
		sender          TEXT,
		recipients      TEXT,
		sent_at         REAL,
		body_text       TEXT,
		body_hash       TEXT,
		has_attachments BOOLEAN DEFAULT 0,
		attachment_count INTEGER DEFAULT 0,
		extracted_at    REAL,
		embed_status    TEXT DEFAULT 'pending',
		embedded_at     REAL,
		rag_winner      BOOLEAN DEFAULT 1
	);

	CREATE TABLE IF NOT EXISTS attachments (
		id              INTEGER PRIMARY KEY AUTOINCREMENT,
		email_id        INTEGER NOT NULL REFERENCES emails(id),
		original_name   TEXT    NOT NULL,
		extension       TEXT    NOT NULL,
		size            INTEGER,
		sha256          TEXT,
		content_hash    TEXT,
		extracted_path  TEXT,
		extracted_at    REAL,
		embed_status    TEXT DEFAULT 'pending',
		embedded_at     REAL,
		rag_winner      BOOLEAN DEFAULT 1
	);

	CREATE TABLE IF NOT EXISTS dup_groups (
		id           INTEGER PRIMARY KEY AUTOINCREMENT,
		match_type   TEXT NOT NULL,
		hash_value   TEXT NOT NULL,
		item_count   INTEGER NOT NULL,
		total_size_mb REAL,
		saveable_mb  REAL,
		status       TEXT DEFAULT 'pending',
		reviewed_at  REAL,
		created_at   REAL NOT NULL DEFAULT (unixepoch()),
		UNIQUE(match_type, hash_value)
	);

	CREATE TABLE IF NOT EXISTS dup_members (
		id          INTEGER PRIMARY KEY AUTOINCREMENT,
		group_id    INTEGER NOT NULL REFERENCES dup_groups(id),
		item_type   TEXT NOT NULL,
		item_id     TEXT NOT NULL,
		is_winner   BOOLEAN DEFAULT 0,
		decision    TEXT DEFAULT 'pending',
		decided_at  REAL
	);

	CREATE TABLE IF NOT EXISTS chunks (
		id           INTEGER PRIMARY KEY AUTOINCREMENT,
		source_type  TEXT NOT NULL,
		source_id    TEXT NOT NULL,
		chunk_index  INTEGER NOT NULL,
		text         TEXT NOT NULL,
		token_count  INTEGER,
		qdrant_id    TEXT UNIQUE,
		embedded_at  REAL,
		UNIQUE(source_type, source_id, chunk_index)
	);

	CREATE TABLE IF NOT EXISTS scan_runs (
		id           INTEGER PRIMARY KEY AUTOINCREMENT,
		started_at   REAL NOT NULL,
		finished_at  REAL,
		files_new    INTEGER DEFAULT 0,
		files_updated INTEGER DEFAULT 0,
		files_skipped INTEGER DEFAULT 0,
		files_deleted INTEGER DEFAULT 0,
		status       TEXT DEFAULT 'running'
	);

	CREATE TABLE IF NOT EXISTS scan_state (
		key   TEXT PRIMARY KEY,
		value TEXT
	);

	CREATE INDEX IF NOT EXISTS idx_files_sha256 ON files(sha256);
	CREATE INDEX IF NOT EXISTS idx_files_content_hash ON files(content_hash);
	CREATE INDEX IF NOT EXISTS idx_emails_message_id ON emails(message_id);
	CREATE INDEX IF NOT EXISTS idx_files_scanned_at ON files(scanned_at);
	CREATE INDEX IF NOT EXISTS idx_scan_runs_started_at ON scan_runs(started_at);
	`

	_, err := db.Exec(schema)
	return err
}

// InsertFile inserts or updates a file record
func (db *DB) InsertFile(file FileRecord) error {
	query := `
		INSERT OR REPLACE INTO files (
			path, name, extension, size, mtime, sha256, content_hash,
			text_length, has_text, language, scanned_at, source_type, scan_run_id
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`

	_, err := db.Exec(query,
		file.Path, file.Name, file.Extension, file.Size, file.Mtime,
		file.Sha256, file.ContentHash, file.TextLength, file.HasText,
		file.Language, file.ScannedAt, file.SourceType, file.ScanRunID)

	return err
}

// FileRecord represents a file record in the database
type FileRecord struct {
	Path         string
	Name         string
	Extension    string
	Size         int64
	Mtime        float64
	Sha256       string
	ContentHash  string
	TextLength   int
	HasText      bool
	Language     string
	ScannedAt    float64
	SourceType   string
	ScanRunID    int
}

// InsertScanRun inserts a new scan run record
func (db *DB) InsertScanRun() (int, error) {
	query := `
		INSERT INTO scan_runs (started_at, status)
		VALUES (?, ?)
	`

	result, err := db.Exec(query, time.Now().Unix(), "running")
	if err != nil {
		return 0, err
	}

	id, err := result.LastInsertId()
	if err != nil {
		return 0, err
	}

	return int(id), nil
}

// UpdateScanRun updates a scan run record
func (db *DB) UpdateScanRun(id int, finishedAt float64, filesNew, filesUpdated, filesSkipped, filesDeleted int) error {
	query := `
		UPDATE scan_runs
		SET finished_at = ?, files_new = ?, files_updated = ?, files_skipped = ?, files_deleted = ?, status = ?
		WHERE id = ?
	`

	_, err := db.Exec(query, finishedAt, filesNew, filesUpdated, filesSkipped, filesDeleted, "done", id)
	return err
}

// SaveProgress saves the last scanned path for crash recovery
func (db *DB) SaveProgress(key, value string) error {
	query := `
		INSERT OR REPLACE INTO scan_state (key, value)
		VALUES (?, ?)
	`

	_, err := db.Exec(query, key, value)
	return err
}

// LoadProgress loads the last scanned path for crash recovery
func (db *DB) LoadProgress(key string) (string, error) {
	query := `
		SELECT value FROM scan_state WHERE key = ?
	`

	var value string
	err := db.QueryRow(query, key).Scan(&value)
	if err != nil {
		return "", err
	}

	return value, nil
}