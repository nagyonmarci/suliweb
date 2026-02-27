import os
import sqlite3
import time
import httpx
from loguru import logger

NAS_HOST       = os.getenv("NAS_HOST", "192.168.1.100")
NAS_USER       = os.getenv("NAS_USER", "admin")
NAS_PASS       = os.getenv("NAS_PASS", "")
POLL_INTERVAL  = int(os.getenv("POLL_INTERVAL", "15"))
METRICS_PATH   = os.getenv("METRICS_PATH", "/data/metrics.db")
RETAIN_DAYS    = int(os.getenv("RETAIN_DAYS", "7"))

BASE = f"http://{NAS_HOST}:5000/webapi"
sid: str | None = None


def init_db(con: sqlite3.Connection):
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("""
        CREATE TABLE IF NOT EXISTS metrics (
            ts          REAL PRIMARY KEY,
            cpu_user    REAL,
            cpu_system  REAL,
            mem_used_mb REAL,
            mem_total_mb REAL,
            tx_mbps     REAL,
            rx_mbps     REAL,
            disk_read_mbps  REAL,
            disk_write_mbps REAL
        )
    """)
    con.commit()


def login() -> str | None:
    try:
        r = httpx.get(f"{BASE}/auth.cgi", params={
            "api": "SYNO.API.Auth", "version": "3", "method": "login",
            "account": NAS_USER, "passwd": NAS_PASS,
            "session": "nas_monitor", "format": "sid"
        }, timeout=10)
        d = r.json()
        if d.get("success"):
            return d["data"]["sid"]
    except Exception as e:
        logger.error(f"Login hiba: {e}")
    return None


def get_metrics(current_sid: str) -> dict | None:
    try:
        r = httpx.get(f"{BASE}/entry.cgi", params={
            "api": "SYNO.Core.System.Utilization",
            "version": "1", "method": "get",
            "_sid": current_sid
        }, timeout=10)
        d = r.json()
        if d.get("success"):
            return d["data"]
    except Exception as e:
        logger.warning(f"Metrics lekérdezési hiba: {e}")
    return None


def parse_and_store(con: sqlite3.Connection, data: dict):
    ts = time.time()
    cpu   = data.get("cpu", {})
    mem   = data.get("memory", {})
    net   = data.get("network", [{}])[0]
    disk  = data.get("disk", {})

    cpu_user   = float(cpu.get("user_load", 0))
    cpu_system = float(cpu.get("system_load", 0))
    mem_used   = float(mem.get("real_usage", 0)) / 1024   # KB → MB
    mem_total  = float(mem.get("total", 0)) / 1024
    tx_mbps    = float(net.get("tx", 0)) / 1024 / 1024
    rx_mbps    = float(net.get("rx", 0)) / 1024 / 1024

    # Lemez I/O – néhány DSM verzión elérhető
    disk_read  = float(disk.get("read",  0)) / 1024 / 1024 if disk else 0.0
    disk_write = float(disk.get("write", 0)) / 1024 / 1024 if disk else 0.0

    con.execute("""
        INSERT OR REPLACE INTO metrics VALUES (?,?,?,?,?,?,?,?,?)
    """, (ts, cpu_user, cpu_system, mem_used, mem_total,
          tx_mbps, rx_mbps, disk_read, disk_write))
    con.commit()

    logger.debug(
        f"CPU: {cpu_user:.1f}% | MEM: {mem_used:.0f}/{mem_total:.0f} MB | "
        f"TX: {tx_mbps:.2f} MB/s | RX: {rx_mbps:.2f} MB/s"
    )


def cleanup_old(con: sqlite3.Connection):
    cutoff = time.time() - RETAIN_DAYS * 86400
    con.execute("DELETE FROM metrics WHERE ts < ?", (cutoff,))
    con.commit()


def main():
    logger.info(f"NAS Monitor indult – NAS: {NAS_HOST}, poll: {POLL_INTERVAL}s")
    con = sqlite3.connect(METRICS_PATH, check_same_thread=False)
    init_db(con)

    global sid
    sid = login()
    cleanup_counter = 0

    while True:
        if not sid:
            logger.warning("Nincs session, újra bejelentkezés...")
            sid = login()
            if not sid:
                time.sleep(30)
                continue

        data = get_metrics(sid)
        if data is None:
            # Session lejárt
            sid = None
            continue

        parse_and_store(con, data)

        cleanup_counter += 1
        if cleanup_counter >= 240:   # ~1 óránként takarítás
            cleanup_old(con)
            cleanup_counter = 0

        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    main()
