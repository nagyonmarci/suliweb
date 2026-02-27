import hashlib
import os
import time
from pathlib import Path
from loguru import logger

from db import (get_connection, upsert_file, get_existing_mtime,
                start_run, finish_run, save_state, load_state,
                remove_deleted_files)
from activity_guard import ActivityGuard

# Terhelés-ellenőrzés ennyiként fájlonként
CHECK_INTERVAL = 100


def hash_file(path: Path, chunk_size: int = 65536) -> str | None:
    h = hashlib.sha256()
    try:
        with open(path, "rb") as f:
            while data := f.read(chunk_size):
                h.update(data)
        return h.hexdigest()
    except (PermissionError, OSError) as e:
        logger.warning(f"Hash hiba [{path}]: {e}")
        return None


def scan(scan_path: str, db_path: str, guard: ActivityGuard):
    con = get_connection(db_path)
    run_id = start_run(con)
    logger.info(f"Scan futás #{run_id} indítva – útvonal: {scan_path}")

    counters = {"new": 0, "updated": 0, "skipped": 0, "errors": 0}
    file_counter = 0

    # Folytatható scan: utolsó feldolgozott útvonal mentése
    resume_path = load_state(con, "last_scanned_path")
    resume_active = resume_path is not None
    if resume_active:
        logger.info(f"Folytatás innen: {resume_path}")

    try:
        for root, dirs, files in os.walk(scan_path, followlinks=False):
            # Rejtett mappák kihagyása
            dirs[:] = [d for d in dirs if not d.startswith(".")]

            for fname in files:
                fpath = Path(root) / fname

                # Folytatás esetén ugorjuk át a már feldolgozottakat
                if resume_active:
                    if str(fpath) == resume_path:
                        resume_active = False
                    continue

                file_counter += 1

                # Periodikus terhelés-ellenőrzés
                if file_counter % CHECK_INTERVAL == 0:
                    pause, reason = guard.should_pause()
                    if pause:
                        logger.info(f"Szünet: {reason} – állapot mentve ({file_counter} fájl feldolgozva)")
                        save_state(con, "last_scanned_path", str(fpath))
                        con.commit()
                        guard.wait_until_ready()
                        logger.info("Folytatás...")

                try:
                    stat = fpath.stat()
                    existing_mtime = get_existing_mtime(con, str(fpath))

                    if existing_mtime is not None and existing_mtime == stat.st_mtime:
                        counters["skipped"] += 1
                        continue

                    sha = hash_file(fpath)
                    upsert_file(
                        con=con,
                        path=str(fpath),
                        name=fname,
                        ext=fpath.suffix.lower(),
                        size=stat.st_size,
                        mtime=stat.st_mtime,
                        sha256=sha,
                        run_id=run_id
                    )

                    if existing_mtime is None:
                        counters["new"] += 1
                    else:
                        counters["updated"] += 1

                    # Batch commit minden 500. fájlnál
                    if file_counter % 500 == 0:
                        con.commit()
                        logger.debug(
                            f"{file_counter} fájl feldolgozva | "
                            f"új: {counters['new']} | "
                            f"frissített: {counters['updated']} | "
                            f"kihagyott: {counters['skipped']}"
                        )

                except (PermissionError, OSError) as e:
                    counters["errors"] += 1
                    logger.warning(f"Fájl hiba [{fpath}]: {e}")

        # Teljes scan után töröljük az eltűnt fájlokat
        deleted = remove_deleted_files(con, run_id)
        con.commit()

        # Sikeres befejezés – töröljük a resume állapotot
        save_state(con, "last_scanned_path", "")

        finish_run(con, run_id,
                   new=counters["new"],
                   updated=counters["updated"],
                   skipped=counters["skipped"],
                   deleted=deleted,
                   status="done")

        logger.success(
            f"Scan #{run_id} kész | "
            f"új: {counters['new']} | "
            f"frissített: {counters['updated']} | "
            f"kihagyott: {counters['skipped']} | "
            f"törölt: {deleted} | "
            f"hiba: {counters['errors']}"
        )

    except Exception as e:
        logger.exception(f"Váratlan hiba scan közben: {e}")
        save_state(con, "last_scanned_path", "")
        finish_run(con, run_id,
                   new=counters["new"],
                   updated=counters["updated"],
                   skipped=counters["skipped"],
                   deleted=0,
                   status="interrupted")
    finally:
        con.close()
