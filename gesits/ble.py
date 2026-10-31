"""Bluetooth LE transport to the MID (device "GESITS", characteristic FFF2).

Behaviour copied from the community GESITS Dashboard, which uses this path on real bikes:
* FFF2 does NOTIFY + READ + WRITE; replies arrive as notifications on the same characteristic;
* always write WITH response (FFF2 has no write-without-response; such writes are ignored);
* the MID joins consecutive writes until "\\r\\n", so long commands go in pieces:
  AT+MAPS=" -> 50 ms -> base64 in 100-char pieces, 30 ms apart -> "\\r\\n.

Needs:  pip install bleak   (Windows, macOS, Linux/Raspberry Pi)
URI:    ble://GESITS            (device name, the default)
        ble://AA:BB:CC:DD:EE:FF (address; on macOS a CoreBluetooth UUID)
"""
from __future__ import annotations

import asyncio
import re
import threading
import time
from typing import Callable, List, Optional

from .transport import Transport

FFF2 = "0000fff2-0000-1000-8000-00805f9b34fb"
MAPS_HEADER = b'AT+MAPS="'
_ADDR = re.compile(r"^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$|^[0-9A-Fa-f-]{36}$")


def split_for_ble(data: bytes, piece: int = 100) -> List[tuple]:
    """Plan the writes for one command as (bytes, pause_after_seconds) pairs.

    Kept separate from the radio code so it can be unit-tested.
    """
    if data.startswith(MAPS_HEADER) and len(data) > 200:
        body = data[len(MAPS_HEADER):]
        tail = b'"\r\n' if body.endswith(b'"\r\n') else b""
        body = body[:len(body) - len(tail)]
        plan = [(MAPS_HEADER, 0.05)]
        plan += [(body[i:i + piece], 0.03) for i in range(0, len(body), piece)]
        return plan + ([(tail, 0.0)] if tail else [])
    return [(data[i:i + piece], 0.01) for i in range(0, len(data), piece)] or [(b"", 0.0)]


class BleTransport(Transport):
    def __init__(self, target: str = "GESITS", char_uuid: str = FFF2, timeout: float = 20.0,
                 piece: int = 100, log: Optional[Callable[[str], None]] = None) -> None:
        try:
            import bleak  # noqa: F401
        except ImportError as e:
            raise SystemExit("ble:// needs bleak: pip install bleak") from e
        self.char_uuid = char_uuid.lower()
        self.piece = piece
        self.log = log or (lambda msg: None)
        self._rx = bytearray()
        self._cv = threading.Condition()
        self._loop = asyncio.new_event_loop()
        threading.Thread(target=self._loop.run_forever, daemon=True).start()
        self._run(self._connect(target or "GESITS", timeout), timeout + 10)

    # -- asyncio plumbing -------------------------------------------------------
    def _run(self, coro, timeout: Optional[float] = 30):
        return asyncio.run_coroutine_threadsafe(coro, self._loop).result(timeout)

    async def _connect(self, target: str, timeout: float) -> None:
        from bleak import BleakClient, BleakScanner

        if _ADDR.match(target):
            device = await BleakScanner.find_device_by_address(target, timeout=timeout)
        else:
            device = await BleakScanner.find_device_by_name(target, timeout=timeout)
        if device is None:
            raise ConnectionError(f"BLE device {target!r} not found. Ignition on? Bluetooth enabled on the MID?")
        self.client = BleakClient(device)
        await self.client.connect()
        self.char = None
        for service in self.client.services:
            for ch in service.characteristics:
                if ch.uuid.lower() == self.char_uuid:
                    self.char = ch
                    self.log(f"using {ch.uuid} in service {service.uuid} ({', '.join(ch.properties)})")
        if self.char is None:
            await self.client.disconnect()
            raise ConnectionError(f"characteristic {self.char_uuid} not found on {target!r}")
        # Keep pieces inside the negotiated MTU (ATT header is 3 bytes).
        mtu = getattr(self.client, "mtu_size", 0) or 23
        self.piece = max(20, min(self.piece, mtu - 3))
        if "notify" in self.char.properties or "indicate" in self.char.properties:
            await self.client.start_notify(self.char, self._on_notify)

    def _on_notify(self, _char, data: bytearray) -> None:
        with self._cv:
            self._rx += data
            self._cv.notify_all()

    async def _write_plan(self, plan) -> None:
        for chunk, pause in plan:
            if chunk:
                await self.client.write_gatt_char(self.char, chunk, response=True)
            if pause:
                await asyncio.sleep(pause)

    # -- Transport API ------------------------------------------------------------
    def write(self, data: bytes) -> None:
        self._run(self._write_plan(split_for_ble(data, self.piece)), timeout=60)

    def read(self, timeout: float) -> bytes:
        end = time.monotonic() + timeout
        with self._cv:
            while not self._rx:
                left = end - time.monotonic()
                if left <= 0:
                    return b""
                self._cv.wait(left)
            data, self._rx = bytes(self._rx), bytearray()
            return data

    def close(self) -> None:
        try:
            self._run(self.client.disconnect(), timeout=10)
        except Exception:
            pass
        self._loop.call_soon_threadsafe(self._loop.stop)
