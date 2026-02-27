import httpx
from loguru import logger


class SynologyAPI:
    def __init__(self, host: str, user: str, password: str):
        self.base = f"http://{host}:5000/webapi"
        self.user = user
        self.password = password
        self.sid: str | None = None

    def login(self) -> bool:
        try:
            r = httpx.get(f"{self.base}/auth.cgi", params={
                "api": "SYNO.API.Auth",
                "version": "3",
                "method": "login",
                "account": self.user,
                "passwd": self.password,
                "session": "nas_indexer",
                "format": "sid"
            }, timeout=10)
            data = r.json()
            if data.get("success"):
                self.sid = data["data"]["sid"]
                logger.info("Synology API bejelentkezés sikeres")
                return True
            logger.warning(f"Bejelentkezés sikertelen: {data}")
            return False
        except Exception as e:
            logger.error(f"Synology API elérési hiba: {e}")
            return False

    def logout(self):
        if not self.sid:
            return
        try:
            httpx.get(f"{self.base}/auth.cgi", params={
                "api": "SYNO.API.Auth",
                "version": "3",
                "method": "logout",
                "session": "nas_indexer",
                "_sid": self.sid
            }, timeout=5)
        except Exception:
            pass
        self.sid = None

    def get_utilization(self) -> dict | None:
        """CPU, memória, hálózati terhelés lekérdezése."""
        if not self.sid and not self.login():
            return None
        try:
            r = httpx.get(f"{self.base}/entry.cgi", params={
                "api": "SYNO.Core.System.Utilization",
                "version": "1",
                "method": "get",
                "_sid": self.sid
            }, timeout=10)
            data = r.json()
            if not data.get("success"):
                # sid lejárt, újra bejelentkezünk
                self.sid = None
                if self.login():
                    return self.get_utilization()
                return None
            return data["data"]
        except Exception as e:
            logger.error(f"Utilization lekérdezési hiba: {e}")
            return None

    def get_cpu_and_network(self) -> tuple[float, float]:
        """
        Visszaadja: (cpu_percent, tx_mbps)
        Hiba esetén (0.0, 0.0) – így nem blokkolja az indexert.
        """
        util = self.get_utilization()
        if not util:
            return 0.0, 0.0
        try:
            cpu = float(util["cpu"]["user_load"])
            # network lista, első interfész TX bytes/s → MB/s
            tx_bytes = float(util["network"][0]["tx"])
            tx_mbps = tx_bytes / 1024 / 1024
            return cpu, tx_mbps
        except (KeyError, IndexError, TypeError) as e:
            logger.warning(f"Utilization adat parse hiba: {e}")
            return 0.0, 0.0
