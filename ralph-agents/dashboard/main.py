import os
import sqlite3
import time
from datetime import datetime
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse
from fastapi.templating import Jinja2Templates
import uvicorn

DB_PATH      = os.getenv("DB_PATH",      "/data/index.db")
METRICS_PATH = os.getenv("METRICS_PATH", "/data/metrics.db")

app = FastAPI(title="NAS Indexer Dashboard")
templates = Jinja2Templates(directory="templates")


def db(path: str) -> sqlite3.Connection:
    con = sqlite3.connect(path, check_same_thread=False)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA journal_mode=WAL")
    return con


@app.get("/", response_class=HTMLResponse)
def index(request: Request):
    return templates.TemplateResponse("index.html", {"request": request})


@app.get("/api/stats")
def stats():
    con = db(DB_PATH)
    total     = con.execute("SELECT COUNT(*) FROM files").fetchone()[0]
    total_gb  = con.execute("SELECT SUM(size)/1024/1024/1024 FROM files").fetchone()[0] or 0
    pst_count = con.execute("SELECT COUNT(*) FROM files WHERE extension='.pst'").fetchone()[0]
    pst_gb    = con.execute("SELECT SUM(size)/1024/1024/1024 FROM files WHERE extension='.pst'").fetchone()[0] or 0

    # Duplikátumok
    dup_row = con.execute("""
        SELECT COUNT(*)-COUNT(DISTINCT sha256) as dup_files,
               SUM(size)/1024/1024/1024 as dup_gb
        FROM files
        WHERE sha256 IN (
            SELECT sha256 FROM files
            WHERE sha256 IS NOT NULL
            GROUP BY sha256 HAVING COUNT(*)>1
        ) AND sha256 IS NOT NULL
    """).fetchone()

    # Utolsó scan
    last_run = con.execute("""
        SELECT id, started_at, finished_at, files_new, files_updated,
               files_skipped, files_deleted, status
        FROM scan_runs ORDER BY id DESC LIMIT 1
    """).fetchone()

    # Top 10 legnagyobb fájl típusonként
    top_ext = con.execute("""
        SELECT extension, COUNT(*) as cnt, SUM(size)/1024/1024/1024 as gb
        FROM files GROUP BY extension ORDER BY gb DESC LIMIT 10
    """).fetchall()

    con.close()

    return {
        "total_files": total,
        "total_gb": round(total_gb, 2),
        "pst_count": pst_count,
        "pst_gb": round(pst_gb, 2),
        "dup_files": dup_row["dup_files"] if dup_row else 0,
        "dup_gb": round(dup_row["dup_gb"] or 0, 2) if dup_row else 0,
        "last_run": dict(last_run) if last_run else None,
        "top_extensions": [dict(r) for r in top_ext],
    }


@app.get("/api/pst")
def pst_files(limit: int = 100, offset: int = 0):
    con = db(DB_PATH)
    rows = con.execute("""
        SELECT path, name, size/1024/1024 as size_mb, mtime, sha256
        FROM files WHERE extension='.pst'
        ORDER BY size DESC LIMIT ? OFFSET ?
    """, (limit, offset)).fetchall()
    total = con.execute("SELECT COUNT(*) FROM files WHERE extension='.pst'").fetchone()[0]
    con.close()
    return {"total": total, "files": [dict(r) for r in rows]}


@app.get("/api/duplicates")
def duplicates(limit: int = 50):
    con = db(DB_PATH)
    rows = con.execute("""
        SELECT sha256,
               COUNT(*) as count,
               SUM(size)/1024/1024 as total_mb,
               MAX(size)/1024/1024 as size_mb,
               GROUP_CONCAT(path, '|||') as paths
        FROM files
        WHERE sha256 IS NOT NULL
        GROUP BY sha256 HAVING COUNT(*) > 1
        ORDER BY total_mb DESC
        LIMIT ?
    """, (limit,)).fetchall()
    con.close()
    result = []
    for r in rows:
        result.append({
            "sha256": r["sha256"][:16] + "...",
            "count": r["count"],
            "size_mb": round(r["size_mb"], 1),
            "total_mb": round(r["total_mb"], 1),
            "paths": r["paths"].split("|||")
        })
    return result


@app.get("/api/metrics/recent")
def metrics_recent(minutes: int = 60):
    try:
        con = db(METRICS_PATH)
        cutoff = time.time() - minutes * 60
        rows = con.execute("""
            SELECT ts, cpu_user, mem_used_mb, tx_mbps, rx_mbps
            FROM metrics WHERE ts > ? ORDER BY ts ASC
        """, (cutoff,)).fetchall()
        con.close()
        return [dict(r) for r in rows]
    except Exception:
        return []


if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=8080, reload=False)
