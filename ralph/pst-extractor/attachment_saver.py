import os
import sqlite3
import re
from pathlib import Path
from loguru import logger

def save_attachments(msg, email_id: int, message_id: str, pst_path: str, extracted_base: str, db_path: str):
    """Save attachments for a message and update the database."""
    con = sqlite3.connect(db_path, check_same_thread=False)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("PRAGMA synchronous=NORMAL")
    con.execute("PRAGMA foreign_keys=ON")
    con.row_factory = sqlite3.Row

    try:
        pst_stem = Path(pst_path).stem
        safe_mid = re.sub(r'[^\w@.-]', '_', message_id or str(email_id))[:80]

        # Process each attachment
        for i in range(msg.number_of_attachments):
            att = msg.get_attachment(i)
            filename = att.name or f"attachment_{i}"

            # Create output directory
            out_dir = Path(extracted_base) / pst_stem / safe_mid
            out_dir.mkdir(parents=True, exist_ok=True)
            out_path = out_dir / filename

            # Save attachment
            with open(out_path, 'wb') as f:
                f.write(att.read_buffer(att.size))

            # Calculate SHA256
            import hashlib
            with open(out_path, 'rb') as f:
                sha256 = hashlib.sha256(f.read()).hexdigest()

            # Insert into attachments table
            con.execute("""
                INSERT INTO attachments (email_id, original_name, extension, size, sha256, extracted_path)
                VALUES (?, ?, ?, ?, ?, ?)
            """, (email_id, filename, Path(filename).suffix.lower(), att.size, sha256, str(out_path)))

        # Update email with attachment information
        con.execute("""
            UPDATE emails
            SET has_attachments = 1, attachment_count = ?
            WHERE id = ?
        """, (msg.number_of_attachments, email_id))

        con.commit()

    except Exception as e:
        logger.error(f"Hiba a csatolmány feldolgozása közben: {e}")
        con.rollback()
        raise
    finally:
        con.close()