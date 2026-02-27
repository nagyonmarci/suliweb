# Dedup Engine Skill

## Cél
Scan után dup_groups + dup_members táblák feltöltése mindhárom szinten.

## Mikor fut?
Az indexer scan befejezése után, automatikusan. Nem külön service – az indexer `main.py`-ban hívódik.

## 3 duplikátum szint

### 1. SHA256 – bájt-azonos fájlok

```python
def find_sha256_dups(con: sqlite3.Connection):
    rows = con.execute("""
        SELECT sha256, COUNT(*) as cnt, SUM(size)/1024.0/1024 as total_mb
        FROM files WHERE sha256 IS NOT NULL
        GROUP BY sha256 HAVING cnt > 1
    """).fetchall()
    
    for row in rows:
        group_id = upsert_dup_group(con, "sha256", row["sha256"],
                                    row["cnt"], row["total_mb"])
        members = con.execute(
            "SELECT path, size FROM files WHERE sha256=?", (row["sha256"],)
        ).fetchall()
        for m in members:
            upsert_dup_member(con, group_id, "file", m["path"])
```

### 2. content_hash – normalizált szöveg egyezés

```python
def find_content_hash_dups(con: sqlite3.Connection):
    # Csak ott ahol a sha256 KÜLÖNBÖZŐ (a sha256 dup-ok már kezelve vannak)
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
    # ... ugyanaz mint fent
```

### 3. Message-ID – email duplikátumok

```python
def find_message_id_dups(con: sqlite3.Connection):
    rows = con.execute("""
        SELECT message_id, COUNT(*) as cnt
        FROM emails WHERE message_id IS NOT NULL
        GROUP BY message_id HAVING cnt > 1
    """).fetchall()
    
    for row in rows:
        group_id = upsert_dup_group(con, "message_id", row["message_id"],
                                    row["cnt"], total_mb=0)
        members = con.execute(
            "SELECT id, pst_path FROM emails WHERE message_id=?",
            (row["message_id"],)
        ).fetchall()
        for m in members:
            upsert_dup_member(con, group_id, "email", str(m["id"]))
```

## Helper függvények

```python
def upsert_dup_group(con, match_type, hash_value, item_count, total_mb) -> int:
    # saveable_mb = total - egyszer megtartva
    saveable_mb = total_mb * (item_count - 1) / item_count
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

def upsert_dup_member(con, group_id, item_type, item_id):
    con.execute("""
        INSERT OR IGNORE INTO dup_members (group_id, item_type, item_id)
        VALUES (?,?,?)
    """, (group_id, item_type, item_id))
```

## Automatikus winner javaslat

A rendszer javasolja a "legvalószínűbb megtartandót" (felhasználó felülírhatja):
- Legjobb útvonal: nem `/temp/`, nem `/backup/`, nem `/old/` mappában van
- Legújabb módosítási dátum
- Leghosszabb fájlnév (általában a leginkább megnevezett verzió)

```python
def suggest_winner(members: list[dict]) -> str:
    def score(m):
        path = m["item_id"].lower()
        penalty = sum(1 for bad in ["/temp", "/backup", "/old", "/archive"]
                      if bad in path)
        return (-penalty, m.get("mtime", 0))
    return max(members, key=score)["item_id"]
```
