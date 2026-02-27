import os
import sqlite3
import pypff
import re
from pathlib import Path
from loguru import logger
from datetime import datetime

def process_pst_files(db_path: str, extracted_base: str, guard):
    """Process all PST files that haven't been processed yet."""
    con = sqlite3.connect(db_path, check_same_thread=False)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("PRAGMA synchronous=NORMAL")
    con.execute("PRAGMA foreign_keys=ON")
    con.row_factory = sqlite3.Row

    try:
        # Find PST files that haven't been processed yet
        cursor = con.execute("""
            SELECT path FROM files
            WHERE extension = '.pst'
            AND path NOT IN (
                SELECT value FROM scan_state WHERE key LIKE 'pst_processed:%'
            )
            ORDER BY name
        """)

        pst_files = cursor.fetchall()

        for row in pst_files:
            pst_path = row['path']
            logger.info(f"Feldolgozás: {pst_path}")

            try:
                process_single_pst(pst_path, db_path, extracted_base, guard)

                # Mark as processed
                con.execute("INSERT OR REPLACE INTO scan_state VALUES (?,?)",
                          (f"pst_processed:{pst_path}", "1"))
                con.commit()

            except Exception as e:
                logger.error(f"Hiba a {pst_path} feldolgozása közben: {e}")
                con.rollback()
                # Continue with other files instead of stopping completely

    finally:
        con.close()

def process_single_pst(pst_path: str, db_path: str, extracted_base: str, guard):
    """Process a single PST file."""
    con = sqlite3.connect(db_path, check_same_thread=False)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("PRAGMA synchronous=NORMAL")
    con.execute("PRAGMA foreign_keys=ON")
    con.row_factory = sqlite3.Row

    try:
        logger.info(f"PST fájl feldolgozása: {pst_path}")

        # Open PST file
        pf = pypff.file()
        pf.open(pst_path)
        root = pf.get_root_folder()

        # Process all messages
        walk_folder(root, pst_path, db_path, extracted_base, guard)

        pf.close()

        # Run deduplication after processing
        logger.info("Duplikátum keresés indítása...")
        # Import and run deduplication
        from dedup import run_deduplication
        run_deduplication(db_path)

    except Exception as e:
        logger.error(f"Hiba a PST feldolgozása közben: {e}")
        raise
    finally:
        con.close()

def walk_folder(folder, pst_path: str, db_path: str, extracted_base: str, guard):
    """Recursively walk through PST folders."""
    # Process messages in current folder
    for i in range(folder.number_of_sub_messages):
        msg = folder.get_sub_message(i)
        process_message(msg, pst_path, db_path, extracted_base, guard)

    # Recursively process subfolders
    for i in range(folder.number_of_sub_folders):
        walk_folder(folder.get_sub_folder(i), pst_path, db_path, extracted_base, guard)

def process_message(msg, pst_path: str, db_path: str, extracted_base: str, guard):
    """Process a single email message."""
    con = sqlite3.connect(db_path, check_same_thread=False)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("PRAGMA synchronous=NORMAL")
    con.execute("PRAGMA foreign_keys=ON")
    con.row_factory = sqlite3.Row

    try:
        # Extract message data
        message_id = extract_message_id(msg)
        subject = msg.subject or ""
        sender = msg.sender_name or ""
        sent_at = msg.delivery_time.timestamp() if msg.delivery_time else None
        body_text = msg.plain_text_body or ""
        body_hash = None

        # Calculate body hash
        if body_text:
            import hashlib
            body_hash = hashlib.sha256(body_text.encode('utf-8')).hexdigest()

        # Extract recipients (if available)
        recipients = None
        if hasattr(msg, 'recipient_names') and msg.recipient_names:
            recipients = msg.recipient_names

        # Insert email
        con.execute("""
            INSERT OR IGNORE INTO emails (message_id, pst_path, subject, sender, sent_at, body_text, body_hash, recipients)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, (message_id, pst_path, subject, sender, sent_at, body_text, body_hash, recipients))

        email_id = con.execute("SELECT last_insert_rowid()").fetchone()[0]

        # Process attachments
        if msg.number_of_attachments > 0:
            logger.info(f"Csatolmányok feldolgozása: {msg.number_of_attachments}")
            from attachment_saver import save_attachments
            save_attachments(msg, email_id, message_id, pst_path, extracted_base, db_path)

        con.commit()

        # Check if we should pause periodically
        if email_id % 10 == 0:  # Check every 10 emails
            pause, reason = guard.should_pause()
            if pause:
                logger.info(f"Felhasználói szünet: {reason}")
                guard.wait_until_ready()

    except Exception as e:
        logger.error(f"Hiba az email feldolgozása közben: {e}")
        con.rollback()
        raise
    finally:
        con.close()

def extract_message_id(msg):
    """Extract Message-ID from transport headers."""
    headers = msg.get_transport_headers()
    if headers and "Message-ID:" in headers:
        try:
            return headers.split("Message-ID:")[1].split("\n")[0].strip()
        except:
            pass
    return None

def process_attachments(msg, email_id: int, message_id: str, pst_path: str, extracted_base: str, db_path: str):
    """Process attachments for a message."""
    con = sqlite3.connect(db_path, check_same_thread=False)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("PRAGMA synchronous=NORMAL")
    con.execute("PRAGMA foreign_keys=ON")
    con.row_factory = sqlite3.Row

    try:
        pst_stem = Path(pst_path).stem
        safe_mid = re.sub(r'[^\w@.-]', '_', message_id or str(email_id))[:80]

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

        con.commit()

    except Exception as e:
        logger.error(f"Hiba a csatolmány feldolgozása közben: {e}")
        con.rollback()
        raise
    finally:
        con.close()