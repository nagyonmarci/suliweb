import os
import time
from loguru import logger

from nas_api import SynologyAPI
from activity_guard import ActivityGuard
from db import Database
from pst_reader import read_pst_file
from attachment_saver import save_attachments

# Log konfiguráció
log_path = os.getenv("LOG_PATH", "/data/pst-extractor.log")
logger.add(log_path, rotation="10 MB", retention="30 days", level="DEBUG")
logger.add(lambda msg: print(msg, end=""), level="INFO")  # stdout is

# Konfiguráció
DB_PATH   = os.getenv("DB_PATH",   "/data/index.db")
NAS_HOST  = os.getenv("NAS_HOST",  "192.168.1.100")
NAS_USER  = os.getenv("NAS_USER",  "admin")
NAS_PASS  = os.getenv("NAS_PASS",  "")
EXTRACTED_PATH = os.getenv("EXTRACTED_PATH", "/mnt/extracted")
PST_SCAN_PATH = os.getenv("PST_SCAN_PATH", "/mnt/nas")

# Scan befejezése után ennyit vár az újraindítás előtt (másodpercben)
# Default: 6 óra – így egy éjszaka csak egyszer fut le
RESCAN_DELAY = int(os.getenv("RESCAN_DELAY_SECONDS", str(6 * 3600)))


def process_pst_files(db: Database, guard: ActivityGuard):
    """Process PST files that have been added to the database."""
    logger.info("PST fájlok feldolgozása indul.")

    # Find PST files that haven't been processed yet
    pst_files = db.get_unprocessed_pst_files()

    for pst_file in pst_files:
        try:
            logger.info(f"PST fájl feldolgozása: {pst_file['path']}")

            # Check if we should pause due to activity guard
            pause, reason = guard.should_pause()
            if pause:
                logger.info(f"Feldolgozás szüneteltetve: {reason}")
                guard.wait_until_ready()
                continue

            # Process the PST file
            email_count, attachment_count = read_pst_file(pst_file['path'], db)

            # Save attachments
            if attachment_count > 0:
                save_attachments(pst_file['path'], EXTRACTED_PATH, db)

            # Mark as processed
            db.mark_pst_as_processed(pst_file['path'], email_count, attachment_count)

            logger.info(f"PST fájl feldolgozva: {pst_file['path']} ({email_count} email, {attachment_count} attachment)")

        except Exception as e:
            logger.error(f"Hiba a PST fájl feldolgozása közben: {pst_file['path']} - {str(e)}")
            db.mark_pst_as_error(pst_file['path'], str(e))
            continue


def main():
    logger.info("PST Extractor szolgáltatás indult.")
    logger.info(f"Adatbázis: {DB_PATH}")
    logger.info(f"Kiterjesztett fájlok helye: {EXTRACTED_PATH}")

    api   = SynologyAPI(NAS_HOST, NAS_USER, NAS_PASS)
    guard = ActivityGuard(api)
    db    = Database(DB_PATH)

    while True:
        logger.info("Várakozás az aktív időablakra és szabad NAS-ra...")
        guard.wait_until_ready()

        logger.info("Feltételek teljesültek – PST feldolgozás indul.")
        process_pst_files(db, guard)

        logger.info(
            f"PST feldolgozás befejezve. Következő ellenőrzés {RESCAN_DELAY // 3600:.1f} óra múlva. "
            f"(vagy hamarabb, ha az időablak újra megnyílik)"
        )
        time.sleep(RESCAN_DELAY)


if __name__ == "__main__":
    main()