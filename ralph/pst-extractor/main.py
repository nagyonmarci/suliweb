import os
import time
from loguru import logger

from nas_api import SynologyAPI
from activity_guard import ActivityGuard
from pst_reader import process_pst_files

# Log konfiguráció
log_path = os.getenv("LOG_PATH", "/data/pst-extractor.log")
logger.add(log_path, rotation="10 MB", retention="30 days", level="DEBUG")
logger.add(lambda msg: print(msg, end=""), level="INFO")  # stdout is

DB_PATH   = os.getenv("DB_PATH",   "/data/index.db")
EXTRACTED_BASE = os.getenv("EXTRACTED_BASE", "/mnt/extracted")

# Scan befejezése után ennyit vár az újraindítás előtt (másodpercben)
# Default: 6 óra – így egy éjszakai futás csak egyszer fut le
RESCAN_DELAY = int(os.getenv("RESCAN_DELAY_SECONDS", str(6 * 3600)))


def main():
    logger.info("PST Extractor szolgáltatás indult.")
    logger.info(f"Adatbázis: {DB_PATH}")
    logger.info(f"Kimentett fájlok: {EXTRACTED_BASE}")

    api   = SynologyAPI(os.getenv("NAS_HOST", "192.168.1.100"),
                       os.getenv("NAS_USER", "admin"),
                       os.getenv("NAS_PASS", ""))
    guard = ActivityGuard(api)

    while True:
        logger.info("Várakozás az aktív időablakra és szabad NAS-ra...")
        guard.wait_until_ready()

        logger.info("Feltételek teljesültek – PST feldolgozás indul.")
        process_pst_files(DB_PATH, EXTRACTED_BASE, guard)

        # Run global deduplication after processing all PST files
        logger.info("Globális duplikátum keresés indítása...")
        from dedup import run_deduplication
        run_deduplication(DB_PATH)

        logger.info(
            f"PST feldolgozás befejezve. Következő feldolgozás {RESCAN_DELAY // 3600:.1f} óra múlva. "
            f"(vagy hamarabb, ha az időablak újra megnyílik)"
        )
        time.sleep(RESCAN_DELAY)


if __name__ == "__main__":
    main()