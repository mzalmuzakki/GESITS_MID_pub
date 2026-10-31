"""High-level client: polling loop, commands and MID map display."""
from __future__ import annotations

import time
from datetime import datetime
from typing import Iterable, List, Optional, Sequence, Tuple

from . import mapimage, protocol
from .protocol import LineParser, Telemetry
from .transport import Transport


class GesitsClient:
    def __init__(self, transport: Transport, reply_timeout: float = 0.5) -> None:
        self.t = transport
        self.parser = LineParser()
        self.telemetry = Telemetry()
        self.reply_timeout = reply_timeout
        self.map_mode = False
        self._last_send = 0.0

    MIN_GAP_S = 0.03  # GESITS Dashboard's native writer keeps >= 30 ms between commands

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.t.close()

    # -- low level ------------------------------------------------------------
    def send(self, data: bytes) -> None:
        wait = self._last_send + self.MIN_GAP_S - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        self.t.write(data)
        self._last_send = time.monotonic()

    def receive(self, timeout: float) -> List[Tuple[str, str]]:
        pairs = self.parser.feed(self.t.read(timeout))
        self.telemetry.update(pairs)
        return pairs

    def request(self, key: str) -> Optional[str]:
        """Send AT+KEY? and wait (briefly) for the KEY=value reply."""
        self.send(protocol.query(key))
        deadline = time.monotonic() + self.reply_timeout
        while time.monotonic() < deadline:
            for k, v in self.receive(max(0.01, deadline - time.monotonic())):
                if k == key:
                    return v
        return None

    def poll(self, keys: Iterable[str]) -> Telemetry:
        """One round-robin pass over keys, one command in flight at a time like the app."""
        for key in keys:
            self.request(key)
        return self.telemetry

    # -- MID features -----------------------------------------------------------
    def sync_time(self, when: Optional[datetime] = None) -> None:
        self.send(protocol.set_time(when))

    def show_text(self, text: str) -> None:
        """Show up to 20 chars on the MID (AT+NAV); longer text is split and only
        the first part is sent - rotate parts yourself with show_text() calls."""
        parts = protocol.split_nav_text(text) or [""]
        self.send(protocol.nav_text(parts[0]))

    def show_range(self, km: float) -> None:
        self.send(protocol.trip_range(km))

    def show_jpeg(self, b64: str) -> None:
        """Send a base64 JPEG (from mapimage.jpeg_to_base64) to the MID map area."""
        self.send(protocol.map_image(b64))

    def show_map(self, codes: Sequence[Sequence[int]], text: str = "") -> None:
        """LEGACY: 164x88 4-colour bitmap the way the old official app did it
        (MAPON once, then NAV text, then MAPS hex)."""
        if not self.map_mode:
            self.send(protocol.MAP_ON)
            self.map_mode = True
        self.send(protocol.nav_text(text))
        self.send(protocol.map_image(mapimage.encode_codes(codes)))

    def hide_map(self) -> None:
        self.send(protocol.MAP_OFF)
        self.send(protocol.MAP_END)
        self.map_mode = False

    def end_session(self) -> None:
        self.send(protocol.END_SESSION)
