import sqlite3
import re
from loguru import logger

def run_deduplication(db_path: str):
    """
    Run deduplication across all three levels:
    1. SHA256 - byte-identical files
    2. content_hash - normalized text matches
    3. message_id - email duplicates across PST files
    """
    con = sqlite3.connect(db_path, check_same_thread=False)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("PRAGMA synchronous=NORMAL")
    con.execute("PRAGMA foreign_keys=ON")
    con.row_factory = sqlite3.Row

    try:
        logger.info("Duplikátum keresés indítása...")

        # 1. SHA256 duplikátumok
        find_sha256_dups(con)

        # 2. content_hash duplikátumok
        find_content_hash_dups(con)

        # 3. Message-ID duplikátumok
        find_message_id_dups(con)

        # 4. Suggest winners for all groups
        suggest_group_winners(con)

        logger.info("Duplikátum keresés befejezve.")

    except Exception as e:
        logger.error(f"Hiba a duplikátum keresés közben: {e}")
        raise
    finally:
        con.close()

def find_sha256_dups(con: sqlite3.Connection):
    """Find SHA256 duplicate files."""
    logger.info("SHA256 duplikátumok keresése...")

    rows = con.execute("""
        SELECT sha256, COUNT(*) as cnt, SUM(size)/1024.0/1024 as total_mb
        FROM files WHERE sha256 IS NOT NULL
        GROUP BY sha256 HAVING cnt > 1
    """).fetchall()

    for row in rows:
        group_id = upsert_dup_group(con, "sha256", row["sha256"],
                                    row["cnt"], row["total_mb"])
        members = con.execute(
            "SELECT path, size, mtime FROM files WHERE sha256=?", (row["sha256"],)
        ).fetchall()
        for m in members:
            upsert_dup_member(con, group_id, "file", m["path"], m["mtime"])

def find_content_hash_dups(con: sqlite3.Connection):
    """Find content_hash duplicate files (where SHA256 differs)."""
    logger.info("content_hash duplikátumok keresése...")

    # Only where SHA256 differs (SHA256 duplicates already handled)
    rows = con.execute("""
        SELECT content_hash, COUNT(*) as cnt, SUM(size)/1024.0/1024 as total_mb
        FROM files
        WHERE content_hash IS NOT NULL
          AND sha256 NOT IN (
              SELECT sha256 FROM files
              GROUP BY sha256 HAVING COUNT(*) > 1
          )
        GROUP BY content_hash HAVING cnt > 1
    """).fetchall()

    for row in rows:
        group_id = upsert_dup_group(con, "content_hash", row["content_hash"],
                                    row["cnt"], row["total_mb"])
        members = con.execute(
            "SELECT path, size, mtime FROM files WHERE content_hash=?", (row["content_hash"],)
        ).fetchall()
        for m in members:
            upsert_dup_member(con, group_id, "file", m["path"], m["mtime"])

def find_message_id_dups(con: sqlite3.Connection):
    """Find message_id duplicate emails across PST files."""
    logger.info("Message-ID duplikátumok keresése...")

    rows = con.execute("""
        SELECT message_id, COUNT(*) as cnt
        FROM emails WHERE message_id IS NOT NULL
        GROUP BY message_id HAVING cnt > 1
    """).fetchall()

    for row in rows:
        group_id = upsert_dup_group(con, "message_id", row["message_id"],
                                    row["cnt"], total_mb=0)
        members = con.execute(
            "SELECT id, pst_path, sent_at FROM emails WHERE message_id=?",
            (row["message_id"],)
        ).fetchall()
        for m in members:
            upsert_dup_member(con, group_id, "email", str(m["id"]), m["sent_at"])

def upsert_dup_group(con, match_type, hash_value, item_count, total_mb) -> int:
    """Insert or update a duplicate group."""
    # saveable_mb = total - egyszer megtartva
    saveable_mb = total_mb * (item_count - 1) / item_count if item_count > 0 else 0

    con.execute("""
        INSERT INTO dup_groups (match_type, hash_value, item_count, total_size_mb, saveable_mb)
        VALUES (?,?,?,?,?)
        ON CONFLICT(match_type, hash_value) DO UPDATE SET
            item_count=excluded.item_count,
            total_size_mb=excluded.total_size_mb,
            saveable_mb=excluded.saveable_mb
    """, (match_type, hash_value, item_count, total_mb, saveable_mb))
    con.commit()

    return con.execute(
        "SELECT id FROM dup_groups WHERE match_type=? AND hash_value=?",
        (match_type, hash_value)
    ).fetchone()[0]

def upsert_dup_member(con, group_id, item_type, item_id, mtime=None):
    """Insert or ignore a duplicate member."""
    con.execute("""
        INSERT OR IGNORE INTO dup_members (group_id, item_type, item_id)
        VALUES (?,?,?)
    """, (group_id, item_type, item_id))
    con.commit()

def suggest_group_winners(con):
    """Suggest winners for all duplicate groups."""
    logger.info("Gyűjtemény nyerők keresése...")

    # Get all groups that don't have winners yet
    groups = con.execute("""
        SELECT id, match_type FROM dup_groups
        WHERE id NOT IN (SELECT DISTINCT group_id FROM dup_members WHERE is_winner = 1)
    """).fetchall()

    for group in groups:
        group_id = group["id"]
        match_type = group["match_type"]

        # Get all members of this group
        members = con.execute("""
            SELECT dm.id as member_id, dm.item_type, dm.item_id, f.mtime, f.path
            FROM dup_members dm
            LEFT JOIN files f ON dm.item_type = 'file' AND dm.item_id = f.path
            WHERE dm.group_id = ?
        """, (group_id,)).fetchall()

        if not members:
            continue

        # Convert to dict for easier processing
        member_list = []
        for m in members:
            member_list.append({
                "id": m["member_id"],
                "item_type": m["item_type"],
                "item_id": m["item_id"],
                "mtime": m["mtime"]
            })

        # Suggest winner based on match type
        winner = None
        if match_type == "message_id":
            # For emails, we can use the email ID directly
            winner = suggest_email_winner(member_list)
        else:
            # For files, use the path scoring
            winner = suggest_file_winner(member_list)

        if winner:
            # Update the winner flag
            con.execute("""
                UPDATE dup_members
                SET is_winner = 1
                WHERE id = ?
            """, (winner,))
            con.commit()

def suggest_file_winner(members: list) -> str:
    """Suggest the best winner for file duplicates based on path, mtime, and filename length."""
    def score(m):
        path = m["item_id"].lower() if m["item_id"] else ""
        penalty = sum(1 for bad in ["/temp", "/backup", "/old", "/archive"]
                      if bad in path)
        return (-penalty, m.get("mtime", 0), len(path))

    if members:
        winner = max(members, key=score)
        return winner["id"]  # Return the member id
    return None

def suggest_email_winner(members: list) -> str:
    """Suggest the best winner for email duplicates based on sent time."""
    # For emails, we'll pick the one with the latest sent time
    def score(m):
        sent_at = m.get("mtime", 0)  # sent_at is stored in mtime for emails
        return sent_at

    if members:
        winner = max(members, key=score)
        return winner["id"]  # Return the member id
    return None