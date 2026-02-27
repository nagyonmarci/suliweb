import sqlite3
import os
from loguru import logger
from datetime import datetime


class Database:
    def __init__(self, db_path):
        self.db_path = db_path
        self.init_db()

    def get_connection(self):
        """Create and return a database connection with proper settings."""
        con = sqlite3.connect(self.db_path, check_same_thread=False)
        con.execute("PRAGMA journal_mode=WAL")
        con.execute("PRAGMA synchronous=NORMAL")
        con.execute("PRAGMA foreign_keys=ON")
        con.execute("PRAGMA cache_size=-32000")
        con.row_factory = sqlite3.Row
        return con

    def init_db(self):
        """Initialize the database with required tables."""
        con = self.get_connection()
        try:
            # Create emails table
            con.execute("""
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
                )
            """)

            # Create attachments table
            con.execute("""
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
                )
            """)

            # Create a view for easy access to email + attachment info
            con.execute("""
                CREATE VIEW IF NOT EXISTS email_attachments AS
                SELECT e.id as email_id, e.message_id, e.pst_path, e.subject, e.sender,
                       e.sent_at, e.attachment_count, e.has_attachments,
                       a.id as attachment_id, a.original_name, a.extension, a.size, a.sha256
                FROM emails e
                LEFT JOIN attachments a ON e.id = a.email_id
            """)

            con.commit()
            logger.info("Adatbázis inicializálva")
        except Exception as e:
            logger.error(f"Adatbázis inicializálási hiba: {e}")
            raise
        finally:
            con.close()

    def insert_email(self, pst_path, email_data):
        """Insert email data into the database."""
        con = self.get_connection()
        try:
            # Calculate body hash for deduplication
            body_hash = None
            if email_data['body_text']:
                # Simple hash of body text (in production, use more robust method)
                body_hash = hash(email_data['body_text']) % (10 ** 18)

            con.execute("""
                INSERT OR IGNORE INTO emails
                (message_id, pst_path, subject, sender, recipients, sent_at,
                 body_text, body_hash, has_attachments, attachment_count, extracted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                email_data['message_id'],
                pst_path,
                email_data['subject'],
                email_data['sender'],
                email_data['recipients'],
                email_data['sent_at'],
                email_data['body_text'],
                body_hash,
                email_data['has_attachments'],
                email_data['attachment_count'],
                datetime.now().timestamp()
            ))
            con.commit()
        except Exception as e:
            logger.error(f"Email beszúrása hiba: {e}")
            raise
        finally:
            con.close()

    def insert_attachment(self, pst_path, message_id, filename, extracted_path, sha256):
        """Insert attachment data into the database."""
        con = self.get_connection()
        try:
            # Get the email ID for this message
            email_row = con.execute("""
                SELECT id FROM emails
                WHERE pst_path = ? AND message_id = ?
            """, (pst_path, message_id)).fetchone()

            if not email_row:
                logger.warning(f"Nem található email a következőhöz: {pst_path}, {message_id}")
                return

            email_id = email_row['id']

            # Extract file extension
            _, ext = os.path.splitext(filename)

            # Get file size
            size = os.path.getsize(extracted_path) if os.path.exists(extracted_path) else 0

            con.execute("""
                INSERT OR IGNORE INTO attachments
                (email_id, original_name, extension, size, sha256, extracted_path, extracted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """, (
                email_id,
                filename,
                ext,
                size,
                sha256,
                extracted_path,
                datetime.now().timestamp()
            ))
            con.commit()
        except Exception as e:
            logger.error(f"Attachment beszúrása hiba: {e}")
            raise
        finally:
            con.close()

    def get_unprocessed_pst_files(self):
        """Get PST files that haven't been processed yet."""
        con = self.get_connection()
        try:
            rows = con.execute("""
                SELECT path, name, size, mtime, sha256, scanned_at
                FROM files
                WHERE extension = '.pst' AND
                      (text_extracted_at IS NULL OR text_extracted_at < scanned_at)
                ORDER BY mtime DESC
            """).fetchall()
            return [dict(row) for row in rows]
        except Exception as e:
            logger.error(f"PST fájlok lekérdezése hiba: {e}")
            return []
        finally:
            con.close()

    def mark_pst_as_processed(self, pst_path, email_count, attachment_count):
        """Mark a PST file as processed."""
        con = self.get_connection()
        try:
            con.execute("""
                UPDATE files
                SET text_extracted_at = ?
                WHERE path = ?
            """, (datetime.now().timestamp(), pst_path))
            con.commit()
        except Exception as e:
            logger.error(f"PST fájl megjelölése hiba: {e}")
            raise
        finally:
            con.close()

    def mark_pst_as_error(self, pst_path, error_message):
        """Mark a PST file as having an error during processing."""
        con = self.get_connection()
        try:
            con.execute("""
                UPDATE files
                SET text_extracted_at = ?, embed_status = 'error'
                WHERE path = ?
            """, (datetime.now().timestamp(), pst_path))
            con.commit()
        except Exception as e:
            logger.error(f"PST fájl hiba megjelölése hiba: {e}")
            raise
        finally:
            con.close()