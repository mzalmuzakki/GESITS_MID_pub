"""A fake Gesits bike that speaks the MID protocol over TCP.

Lets you develop dashboards without the motorcycle. Its replies follow what the
app expects (KEY=value\\r\\n); real-bike timing and exact value ranges are not
known yet, so treat it as a stand-in, not a reference.
"""
from __future__ import annotations

import math
import re
import socket
import threading
import time
from pathlib import Path
from typing import Optional

from . import mapimage

_CMD = re.compile(r'^AT\+(\+?[A-Z0-9]+)(\?|=(.*))?$')


class FakeBike:
    def __init__(self, preview_dir: Optional[str] = None, verbose: bool = True) -> None:
        self.t0 = time.monotonic()
        self.preview_dir = Path(preview_dir) if preview_dir else None
        self.verbose = verbose
        self.clock = None
        self.nav_text = None
        self.range_km = None
        self.map_on = False
        self.frames = 0

    def value(self, key: str) -> Optional[str]:
        t = time.monotonic() - self.t0
        speed = int(35 + 25 * math.sin(t / 6))
        values = {
            "SPD": speed, "SOC": 82, "SOH": 97, "ODO": round(1234.5 + t * speed / 3600, 1),
            "MODE": int(t / 20) % 3, "PARK": 0, "VBAT": 71.8, "IBAT": round(speed * 0.4, 1),
            "CBAT": 18, "TBAT": 31, "TMOT": 44, "TINV": 39, "ERR": 0, "TRT": speed * 2,
            "SOC2": 0, "NUMBAT": 1, "FWD": 1,
        }
        v = values.get(key)
        return None if v is None else str(v)

    def handle_line(self, line: str) -> Optional[str]:
        m = _CMD.match(line.strip())
        if not m:
            return None
        key, op, arg = m.group(1), m.group(2), m.group(3)
        if op == "?":
            v = self.value(key)
            return f"{key}={v}\r\n" if v is not None else None
        arg = (arg or "").strip('"')
        if key == "TIME":
            self.clock = arg
        elif key == "NAV":
            self.nav_text = arg
        elif key == "MAPON":
            self.map_on = arg == "1"
        elif key == "TRIP":
            self.range_km = arg
        elif key == "MAPS":
            self.frames += 1
            kind = "base64 JPEG" if arg.startswith("/9j/") else "legacy hex"
            if self.preview_dir:
                self.preview_dir.mkdir(parents=True, exist_ok=True)
                stem = self.preview_dir / f"mid_frame_{self.frames:04d}"
                if kind == "base64 JPEG":
                    import base64
                    stem.with_suffix(".jpg").write_bytes(base64.b64decode(arg))
                elif len(arg) == mapimage.HEX_LENGTH:
                    mapimage.codes_to_image(mapimage.decode_hex(arg)).save(stem.with_suffix(".png"))
            arg = f"<{len(arg)} chars, {kind}>"
        if self.verbose:
            print(f"[bike] {key} {arg}", flush=True)
        return None

    def serve_connection(self, conn: socket.socket) -> None:
        buf = b""
        with conn:
            while True:
                data = conn.recv(65536)
                if not data:
                    return
                buf += data
                # Most commands end with CR/LF; AT+END and AT+MAPEND may arrive bare.
                while b"\r\n" in buf:
                    line, buf = buf.split(b"\r\n", 1)
                    reply = self.handle_line(line.decode("utf-8", "replace"))
                    if reply:
                        conn.sendall(reply.encode())
                if buf.strip() in (b"AT+END", b"AT+MAPEND"):
                    self.handle_line(buf.decode())
                    buf = b""


def serve(host: str = "127.0.0.1", port: int = 3333, preview_dir: Optional[str] = None,
          ready: Optional[threading.Event] = None, verbose: bool = True) -> None:
    bike = FakeBike(preview_dir, verbose)
    with socket.create_server((host, port)) as srv:
        if verbose:
            print(f"Fake Gesits bike listening on tcp://{host}:{port}", flush=True)
        if ready:
            ready.set()
        while True:
            conn, _ = srv.accept()
            threading.Thread(target=bike.serve_connection, args=(conn,), daemon=True).start()
