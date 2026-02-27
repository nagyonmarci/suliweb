# NAS Indexer

MacBook M4 Max-on futó Docker Compose alapú NAS indexelő rendszer.

## Architektúra

```
docker-compose.yml
├── indexer       – bejárja a NAS-t, SHA256 hash, SQLite adatbázis
├── nas-monitor   – 15 másodpercenként Synology API-n lekérdezi a terhelést
└── dashboard     – http://localhost:8080 – PST fájlok, duplikátumok, grafikon
```

## Telepítés

### 1. NAS SMB mount (macOS)

```bash
# Finder → Go → Connect to Server → smb://192.168.1.100
# vagy parancssorból:
mkdir -p /Volumes/nas
mount -t smbfs //admin@192.168.1.100/volume1 /Volumes/nas
```

Tartós mount (automatikus újraindítás után):
```
# /etc/fstab – NEM ajánlott macOS-en SMB-hez
# Inkább: System Settings → General → Login Items → add mount script
```

Login item szkript (`~/nas-mount.sh`):
```bash
#!/bin/bash
sleep 10  # hálózat felállására vár
osascript -e 'mount volume "smb://admin@192.168.1.100/volume1"'
```

### 2. Konfiguráció

```bash
cp .env.example .env
# .env szerkesztése: NAS_HOST, NAS_USER, NAS_PASS
```

### 3. Indítás

```bash
docker compose up -d
```

A konténerek mindig futnak, az indexer maga dönti el mikor dolgozik.

### 4. Dashboard

```
http://localhost:8080
```

---

## Időzítési logika

Az indexer **folyamatosan fut**, de csak akkor dolgozik, ha:

| Feltétel | Alapértelmezett |
|----------|----------------|
| Hétköznap aktív időszak | 22:00 – 06:00 |
| Hétvégén | mindig aktív |
| NAS CPU limit | < 50% |
| NAS hálózati TX limit | < 40 MB/s |

Ha bármelyik feltétel nem teljesül, az indexer alszik és 60 másodpercenként újraellenőrzi.

Beállítások a `docker-compose.yml`-ben:
```yaml
ACTIVE_HOURS_START=22
ACTIVE_HOURS_END=6
WEEKEND_ALWAYS_ACTIVE=true
MAX_NAS_CPU=50
MAX_NAS_TX_MBPS=40
POLL_INTERVAL_SECONDS=60
RESCAN_DELAY_SECONDS=21600   # 6 óra – scan után mikor indul a következő
```

---

## Hasznos SQL lekérdezések

```sql
-- Összes PST fájl méret szerint
SELECT name, path, size/1024/1024 as mb
FROM files WHERE extension='.pst'
ORDER BY size DESC;

-- Duplikátumok (azonos SHA256)
SELECT sha256, COUNT(*) as db,
       SUM(size)/1024/1024 as ossz_mb,
       GROUP_CONCAT(path, '  |  ') as utvonalak
FROM files
WHERE sha256 IS NOT NULL
GROUP BY sha256 HAVING COUNT(*) > 1
ORDER BY ossz_mb DESC;

-- Scan előzmények
SELECT id, datetime(started_at,'unixepoch','localtime') as start,
       round((finished_at-started_at)/3600.0, 2) as ido_h,
       files_new, files_updated, files_skipped, status
FROM scan_runs ORDER BY id DESC;

-- Fájltípus eloszlás
SELECT extension, COUNT(*) as db, SUM(size)/1024/1024/1024 as gb
FROM files GROUP BY extension ORDER BY gb DESC LIMIT 20;
```

---

## Folytatható scan

Ha az indexer megszakad (újraindítás, áramszünet), az utolsó feldolgozott
fájl útvonalát menti a `scan_state` táblába. Következő indításkor onnan
folytatja, nem kezdi elölről a 16 TB-os bejárást.

---

## Adatbázis helye

```
./data/index.db      – fájl index (SQLite WAL mód, olvasható dashboard-ból is)
./data/metrics.db    – NAS terhelés előzmények (7 nap)
./data/indexer.log   – részletes napló (10 MB rotáció, 30 nap megőrzés)
```
