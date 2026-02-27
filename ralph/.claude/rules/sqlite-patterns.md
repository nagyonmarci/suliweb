---
description: SQLite konvenciók és kötelező beállítások
---

# SQLite minták

## Kapcsolat megnyitása (mindig így)

```python
con = sqlite3.connect(db_path, check_same_thread=False)
con.execute("PRAGMA journal_mode=WAL")
con.execute("PRAGMA synchronous=NORMAL")
con.execute("PRAGMA foreign_keys=ON")
con.execute("PRAGMA cache_size=-32000")
con.row_factory = sqlite3.Row
```

## Commit stratégia

- Batch commit minden **500** elemnél – ne commit-olj elemként
- Végén mindig `con.commit()` + `con.close()`
- Hiba esetén: ne rollback, csak log + folytatás következő elemmel

## Párhuzamos hozzáférés

A dashboard READ-ONLY nyitja meg az adatbázist. WAL mód engedi ezt.
Soha ne nyiss WRITE kapcsolatot a dashboardból.

## Crash-safe állapot mentés

```python
# Mentés
con.execute("INSERT OR REPLACE INTO scan_state VALUES (?,?)", (key, value))
con.commit()

# Olvasás
row = con.execute("SELECT value FROM scan_state WHERE key=?", (key,)).fetchone()
```
