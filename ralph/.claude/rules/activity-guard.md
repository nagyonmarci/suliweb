---
description: Minden új Docker service-ben kötelező időzítési és terhelés-figyelő minta
---

# Activity Guard szabály

Minden új service-ben az `activity_guard.py` mintát KÖTELEZŐ követni.
Másold az `indexer/activity_guard.py` fájlt az új service-be, és igazítsd az env változókat.

## Kötelező env változók minden service-ben

```yaml
environment:
  - ACTIVE_HOURS_START=22
  - ACTIVE_HOURS_END=6
  - WEEKEND_ALWAYS_ACTIVE=true
  - MAX_NAS_CPU=50          # embedder-nél 40
  - MAX_NAS_TX_MBPS=40      # embedder-nél 30
  - POLL_INTERVAL_SECONDS=60
```

## Főciklus minta

```python
guard = ActivityGuard(api)
while True:
    guard.wait_until_ready()      # blokkolva vár, amíg szabad
    do_work(guard)                # munka közben guard.should_pause() hívva
    time.sleep(RESCAN_DELAY)
```

## Futás közben throttling

Hosszú műveleteknél (pl. fájlonkénti hash, embedding) minden N elemnél:
```python
if counter % CHECK_INTERVAL == 0:
    pause, reason = guard.should_pause()
    if pause:
        save_progress()
        guard.wait_until_ready()
```
