import os
import time
from loguru import logger

from nas_api import SynologyAPI
from activity_guard import ActivityGuard
from scanner import scan

# Log konfiguráció
log_path = os.getenv("LOG_PATH", "/data/indexer.log")
logger.add(log_path, rotation="10 MB", retention="30 days", level="DEBUG")
logger.add(lambda msg: print(msg, end=""), level="INFO")  # stdout is

SCAN_PATH = os.getenv("SCAN_PATH", "/mnt/nas")
DB_PATH   = os.getenv("DB_PATH",   "/data/index.db")
NAS_HOST  = os.getenv("NAS_HOST",  "192.168.1.100")
NAS_USER  = os.getenv("NAS_USER",  "admin")
NAS_PASS  = os.getenv("NAS_PASS",  "")

# Scan befejezése után ennyit vár az újraindítás előtt (másodpercben)
# Default: 6 óra – így egy éjszaka csak egyszer fut le
RESCAN_DELAY = int(os.getenv("RESCAN_DELAY_SECONDS", str(6 * 3600)))


def main():
    logger.info("NAS Indexer szolgáltatás indult.")
    logger.info(f"Scan útvonal: {SCAN_PATH}")
    logger.info(f"Adatbázis: {DB_PATH}")

    api   = SynologyAPI(NAS_HOST, NAS_USER, NAS_PASS)
    guard = ActivityGuard(api)

    while True:
        logger.info("Várakozás az aktív időablakra és szabad NAS-ra...")
        guard.wait_until_ready()

        logger.info("Feltételek teljesültek – scan indul.")
        scan(SCAN_PATH, DB_PATH, guard)

        logger.info(
            f"Scan befejezve. Következő scan {RESCAN_DELAY // 3600:.1f} óra múlva. "
            f"(vagy hamarabb, ha az időablak újra megnyílik)"
        )
        time.sleep(RESCAN_DELAY)


if __name__ == "__main__":
    main()
