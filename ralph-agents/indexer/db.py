import sqlite3
import time
from pathlib import Path


def get_connection(db_path: str) -> sqlite3.Connection:
    con = sqlite3.connect(db_path, check_same_thread=False)
    con.execute("PRAGMA journal_mode=WAL")   # párhuzamos olvasás (dashboard)
    con.execute("PRAGMA synchronous=NORMAL")
    con.execute("PRAGMA cache_size=-32000")  # 32 MB cache
    _create_tables(con)
    return con


def _create_tables(con: sqlite3.Connection):
    con.executescript("""
        CREATE TABLE IF NOT EXISTS files (
            path        TEXT PRIMARY KEY,
            name        TEXT NOT NULL,
            extension   TEXT NOT NULL,
            size        INTEGER NOT NULL,
            mtime       REAL NOT NULL,
            sha256      TEXT,
            scanned_at  REAL NOT NULL,
            scan_run_id INTEGER
        );

        CREATE INDEX IF NOT EXISTS idx_extension ON files(extension);
        CREATE INDEX IF NOT EXISTS idx_sha256    ON files(sha256);
        CREATE INDEX IF NOT EXISTS idx_size      ON files(size);

        CREATE TABLE IF NOT EXISTS scan_runs (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            started_at  REAL NOT NULL,
            finished_at REAL,
            files_new   INTEGER DEFAULT 0,
            files_updated INTEGER DEFAULT 0,
            files_skipped INTEGER DEFAULT 0,
            files_deleted INTEGER DEFAULT 0,
            status      TEXT DEFAULT 'running'   -- running | done | interrupted
        );

        CREATE TABLE IF NOT EXISTS scan_state (
            key   TEXT PRIMARY KEY,
            value TEXT
        );
    """)
    con.commit()


def upsert_file(con: sqlite3.Connection, path: str, name: str,
                ext: str, size: int, mtime: float,
                sha256: str, run_id: int):
    con.execute("""
        INSERT INTO files (path, name, extension, size, mtime, sha256, scanned_at, scan_run_id)
        VALUES (?,?,?,?,?,?,?,?)
        ON CONFLICT(path) DO UPDATE SET
            name=excluded.name,
            extension=excluded.extension,
            size=excluded.size,
            mtime=excluded.mtime,
            sha256=excluded.sha256,
            scanned_at=excluded.scanned_at,
            scan_run_id=excluded.scan_run_id
    """, (path, name, ext, size, mtime, sha256, time.time(), run_id))


def get_existing_mtime(con: sqlite3.Connection, path: str) -> float | None:
    row = con.execute("SELECT mtime FROM files WHERE path=?", (path,)).fetchone()
    return row[0] if row else None


def start_run(con: sqlite3.Connection) -> int:
    cur = con.execute(
        "INSERT INTO scan_runs (started_at) VALUES (?)", (time.time(),))
    con.commit()
    return cur.lastrowid


def finish_run(con: sqlite3.Connection, run_id: int,
               new: int, updated: int, skipped: int, deleted: int,
               status: str = "done"):
    con.execute("""
        UPDATE scan_runs SET finished_at=?, files_new=?, files_updated=?,
        files_skipped=?, files_deleted=?, status=?
        WHERE id=?
    """, (time.time(), new, updated, skipped, deleted, status, run_id))
    con.commit()


def save_state(con: sqlite3.Connection, key: str, value: str):
    con.execute("INSERT OR REPLACE INTO scan_state VALUES (?,?)", (key, value))
    con.commit()


def load_state(con: sqlite3.Connection, key: str) -> str | None:
    row = con.execute("SELECT value FROM scan_state WHERE key=?", (key,)).fetchone()
    return row[0] if row else None


def remove_deleted_files(con: sqlite3.Connection, scan_run_id: int) -> int:
    """Törli az adatbázisból azokat a fájlokat, amelyek már nem szerepelnek az aktuális futásban."""
    cur = con.execute(
        "DELETE FROM files WHERE scan_run_id != ? AND scan_run_id IS NOT NULL",
        (scan_run_id,))
    con.commit()
    return cur.rowcount
