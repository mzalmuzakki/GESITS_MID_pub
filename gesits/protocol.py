"""Gesits G1 MID serial protocol (Bluetooth Classic SPP, plain ASCII "AT" commands).

Reconstructed from the Gesits Android app (package com.gesits.app.mobile, v1.5.2).
See docs/PROTOCOL.md for the full write-up and confidence notes.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Dict, Iterable, List, Optional, Tuple

# Standard Serial Port Profile UUID used by the app (createRfcommSocketToServiceRecord).
SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

CRLF = "\r\n"

# Keys the app polls with "AT+<KEY>?\r\n", grouped by the screen that polls them.
HOME_KEYS = ["SOC2", "SOC", "NUMBAT", "SOH", "MODE"]
BATTERY_KEYS = ["SOC", "SOH", "TBAT", "SOC2", "VBAT", "CBAT", "NUMBAT", "TRIP"]
DIAG_KEYS = ["TMOT", "TINV", "ERR", "PARK", "FWD", "TRT", "SPD"]
ALL_KEYS = list(dict.fromkeys(HOME_KEYS + BATTERY_KEYS + DIAG_KEYS))

KEY_DESCRIPTIONS = {
    "SOC": "State of charge, battery 1 (%)",
    "SOC2": "State of charge, battery 2 (%)",
    "SOH": "State of health (%)",
    "NUMBAT": "Number of batteries installed",
    "MODE": "Drive mode (0=ECO, 1=URBAN, 2=SPORT)",
    "TBAT": "Battery temperature (raw integer)",
    "VBAT": "Battery voltage (raw integer)",
    "CBAT": "Battery current (raw integer)",
    "TRIP": "Trip value (polled but not shown by the app)",
    "TMOT": "Motor temperature (raw integer)",
    "TINV": "Inverter / controller temperature (raw integer)",
    "ERR": "Error code (\"0000\" = no error)",
    "PARK": "Parking (side-stand / park) status",
    "FWD": "Forward / reverse status",
    "TRT": "Throttle (raw integer)",
    "SPD": "Speed (raw integer, presumably km/h)",
}

DRIVE_MODES = {0: "ECO", 1: "URBAN", 2: "SPORT"}

# Fixed commands (exact bytes the app sends).
MAP_ON = b'AT+MAPON="1"\r\n'
MAP_OFF = b'AT+MAPON="0"\r\n'
MAP_END = b"AT+MAPEND\r\n"
END_SESSION = b"AT+END"          # sent on disconnect; the app sends it WITHOUT CR/LF
LOGOUT_END = b"AT++END\r\n"      # sent on account logout (sic, double '+')
ERROR_QUERY = b"AT+ERR?\r\n"


def query(key: str) -> bytes:
    """Build a read command, e.g. query("SPD") -> b"AT+SPD?\\r\\n"."""
    key = key.strip().upper()
    if not key or not key.isalnum():
        raise ValueError(f"invalid key: {key!r}")
    return f"AT+{key}?{CRLF}".encode("ascii")


def set_time(when: Optional[datetime] = None) -> bytes:
    """Set the MID clock: AT+TIME="HHmm"\\r\\n (the app sends this every second)."""
    when = when or datetime.now()
    return f'AT+TIME="{when:%H%M}"{CRLF}'.encode("ascii")


def _quoted_arg(text: str) -> str:
    if '"' in text or "\r" in text or "\n" in text:
        raise ValueError('text must not contain double quotes or line breaks')
    return text


def nav_text(text: str) -> bytes:
    """Navigation instruction line shown with the map, e.g. '120.0 m turn-left'."""
    return f'AT+NAV="{_quoted_arg(text)}"{CRLF}'.encode("utf-8")


def map_image(hex_payload: str) -> bytes:
    """AT+MAPS="<hex>"\\r\\n where <hex> comes from gesits.mapimage.encode_*()."""
    if any(c not in "0123456789ABCDEF" for c in hex_payload):
        raise ValueError("map payload must be upper-case hex (see gesits.mapimage)")
    return f'AT+MAPS="{hex_payload}"{CRLF}'.encode("ascii")


class LineParser:
    """Accumulates raw bytes from the socket and yields complete response lines.

    The bike answers with lines like ``SPD=42\\r\\n``. Reads can split a line in
    the middle, so partial data is buffered until the CR/LF arrives.
    """

    def __init__(self) -> None:
        self._buf = ""

    def feed(self, data: bytes) -> List[Tuple[str, str]]:
        self._buf += data.decode("utf-8", errors="replace")
        *lines, self._buf = self._buf.split(CRLF)
        out = []
        for line in lines:
            line = line.strip()
            if not line:
                continue
            key, sep, value = line.partition("=")
            out.append((key.strip(), value.strip()) if sep else (line, ""))
        return out


def to_int(value: str) -> Optional[int]:
    """Same leniency as the app's toIntValue(): non-numbers become None."""
    try:
        return int(value.strip().strip('"'))
    except (ValueError, AttributeError):
        return None


@dataclass
class Telemetry:
    """Latest value of every key received so far, plus the app's derived figures."""

    raw: Dict[str, str] = field(default_factory=dict)

    def update(self, pairs: Iterable[Tuple[str, str]]) -> None:
        for key, value in pairs:
            if key:
                self.raw[key] = value

    def get_int(self, key: str) -> Optional[int]:
        return to_int(self.raw[key]) if key in self.raw else None

    @property
    def drive_mode(self) -> Optional[str]:
        mode = self.get_int("MODE")
        return None if mode is None else DRIVE_MODES.get(mode, f"UNKNOWN({mode})")

    @property
    def range_km(self) -> Optional[float]:
        """The app's range estimate: 1 km per % of SOC (battery 1)."""
        soc = self.get_int("SOC")
        return None if soc is None else soc * 1.0

    @property
    def energy_kwh(self) -> Optional[float]:
        """The app's remaining-energy estimate: (SOC + SOC) * 0.014 kWh."""
        soc = self.get_int("SOC")
        return None if soc is None else round(2 * soc * 0.014, 3)

    @property
    def has_error(self) -> Optional[bool]:
        err = self.raw.get("ERR")
        return None if err is None else err.strip('"') != "0000"
