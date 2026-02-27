import os
import time
from datetime import datetime
from loguru import logger
from nas_api import SynologyAPI


class ActivityGuard:
    """
    Dönti el, hogy az indexer aktívan futhat-e.

    Szabályok:
      - Hétköznap: ACTIVE_HOURS_START (pl. 22) – ACTIVE_HOURS_END (pl. 6) között fut
      - Hétvégén (szombat=5, vasárnap=6): mindig fut, ha WEEKEND_ALWAYS_ACTIVE=true
      - NAS CPU > MAX_NAS_CPU %  → vár
      - NAS TX  > MAX_NAS_TX_MBPS MB/s → vár
    """

    def __init__(self, api: SynologyAPI):
        self.api = api
        self.start_hour = int(os.getenv("ACTIVE_HOURS_START", "22"))
        self.end_hour = int(os.getenv("ACTIVE_HOURS_END", "6"))
        self.weekend_always = os.getenv("WEEKEND_ALWAYS_ACTIVE", "true").lower() == "true"
        self.max_cpu = float(os.getenv("MAX_NAS_CPU", "50"))
        self.max_tx = float(os.getenv("MAX_NAS_TX_MBPS", "40"))
        self.poll_interval = int(os.getenv("POLL_INTERVAL_SECONDS", "60"))

    def is_active_time(self) -> bool:
        now = datetime.now()
        weekday = now.weekday()  # 0=hétfő, 5=szombat, 6=vasárnap

        # Hétvége
        if weekday >= 5 and self.weekend_always:
            return True

        # Éjszakai ablak (pl. 22:00 → 06:00, átnyúlik éjfélen)
        hour = now.hour
        if self.start_hour > self.end_hour:
            # átnyúlik éjfélen: pl. 22–6
            return hour >= self.start_hour or hour < self.end_hour
        else:
            # pl. 1–5
            return self.start_hour <= hour < self.end_hour

    def is_nas_idle(self) -> tuple[bool, str]:
        """True ha a NAS nem túlterhelt. Visszaad egy státusz üzenetet is."""
        cpu, tx = self.api.get_cpu_and_network()
        if cpu > self.max_cpu:
            return False, f"NAS CPU magas: {cpu:.1f}% (limit: {self.max_cpu}%)"
        if tx > self.max_tx:
            return False, f"NAS hálózat magas: {tx:.2f} MB/s (limit: {self.max_tx} MB/s)"
        return True, f"NAS OK – CPU: {cpu:.1f}%, TX: {tx:.2f} MB/s"

    def wait_until_ready(self):
        """
        Blokkoló várakozás, amíg mindkét feltétel teljesül:
        helyes időablak ÉS NAS nem túlterhelt.
        Minden körben logolja az okot.
        """
        while True:
            if not self.is_active_time():
                now = datetime.now()
                logger.info(
                    f"Nem aktív időszak ({now.strftime('%H:%M %A')}). "
                    f"Aktív: {self.start_hour}:00–{self.end_hour}:00, "
                    f"hétvégén {'mindig' if self.weekend_always else 'szintén korlátozott'}. "
                    f"Következő ellenőrzés {self.poll_interval}s múlva."
                )
                time.sleep(self.poll_interval)
                continue

            nas_ok, nas_msg = self.is_nas_idle()
            if not nas_ok:
                logger.info(f"NAS terhelés miatt várakozás: {nas_msg}. {self.poll_interval}s múlva újra.")
                time.sleep(self.poll_interval)
                continue

            logger.debug(f"Futás engedélyezett. {nas_msg}")
            return

    def should_pause(self) -> tuple[bool, str]:
        """
        Futás közben periodikusan hívható.
        True ha meg kell állni (időn kívül vagy NAS túlterhelt).
        """
        if not self.is_active_time():
            now = datetime.now()
            return True, f"Időablakon kívül ({now.strftime('%H:%M')})"
        nas_ok, msg = self.is_nas_idle()
        if not nas_ok:
            return True, msg
        return False, "OK"
