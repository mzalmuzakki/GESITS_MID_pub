"""Gesits G1 MID serial protocol (Bluetooth Classic SPP, plain ASCII "AT" commands).

Sources: the Gesits Android app (com.gesits.app.mobile v1.5.2) and the community
"GESITS Dashboard" app (com.gesits.dashboard v1.0.1.17), whose code comments record
behaviour checked on real bikes. See docs/PROTOCOL.md for details and confidence.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Dict, Iterable, List, Optional, Tuple

# Standard Serial Port Profile UUID used by the app (createRfcommSocketToServiceRecord).
SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

CRLF = "\r\n"

# Keys read with "AT+<KEY>?\r\n".
HOME_KEYS = ["SPD", "SOC", "MODE", "PARK", "ODO"]
BATTERY_KEYS = ["SOC", "SOH", "VBAT", "IBAT", "CBAT", "TBAT"]
DIAG_KEYS = ["SPD", "TRT", "TMOT", "TINV", "ERR", "MODE", "PARK"]
ALL_KEYS = ["SPD", "SOC", "ODO", "TINV", "TMOT", "TBAT", "SOH", "VBAT", "IBAT", "CBAT",
            "ERR", "MODE", "PARK", "TRT"]   # GESITS Dashboard's list (works on the bike)

# GESITS Dashboard poll schedule (ms between reads of each key). Every 100-200 ms it sends
# the ONE key that is most overdue. AT+PWR? was dropped there: the MID does not know it.
POLL_PERIOD_MS = {"SPD": 500, "IBAT": 500, "VBAT": 1000, "TRT": 2000, "ODO": 2000,
                  "SOC": 4000, "ERR": 8000, "MODE": 8000, "PARK": 8000, "CBAT": 8000,
                  "TINV": 15000, "TMOT": 15000, "TBAT": 15000, "SOH": 30000}

KEY_DESCRIPTIONS = {
    "SPD": "Speed (km/h)",
    "SOC": "State of charge (%)",
    "SOC2": "State of charge, battery 2 (%) [old app only]",
    "SOH": "Battery state of health (%)",
    "NUMBAT": "Number of batteries [old app only]",
    "ODO": "Odometer (km)",
    "MODE": "Drive mode: 0=ECO 1=URBAN 2=SPORT (confirmed on bike)",
    "PARK": "1=PARK, 0=DRIVE (confirmed on bike)",
    "VBAT": "Battery voltage (V)",
    "IBAT": "Battery current (A, negative = regen)",
    "CBAT": "Battery value 'CBAT' (meaning unclear)",
    "TBAT": "Battery temperature (deg C)",
    "TMOT": "Motor temperature (deg C)",
    "TINV": "Inverter / controller temperature (deg C)",
    "TRT": "Throttle",
    "ERR": "Error bitmask (0 = no error)",
    "FWD": "Unclear; stays 1 whatever the mode [old app only]",
}

# AT+ERR bitmask as used by GESITS Dashboard ("adjust to the motor firmware" - not official).
ERROR_BITS = {
    0: "Inverter over temperature", 1: "Motor over temperature", 2: "Motor fault",
    3: "Hall sensor fault", 4: "Throttle fault", 5: "Controller fault",
    6: "Over voltage", 7: "Under voltage",
    11: "Throttle signal abnormal (seen as ERR=0800, unofficial)",
}

DRIVE_MODES = {0: "ECO", 1: "URBAN", 2: "SPORT"}

NAV_MAX_CHARS = 20      # the MID shows at most 20 characters per AT+NAV message
MAPS_MAX_CHARS = 7000   # base64 JPEG limit used by GESITS Dashboard (bigger frames freeze the MID)

# Fixed commands (exact bytes the app sends). MAPON/MAPEND come from the old app only;
# GESITS Dashboard never sends them and still gets pictures on the MID.
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


def nav_ascii(text: str) -> str:
    """Strip accents/non-ASCII like GESITS Dashboard does before sending to the MID."""
    import unicodedata
    text = unicodedata.normalize("NFD", text or "")
    text = "".join(c for c in text if not unicodedata.combining(c))
    text = "".join(c if 0x20 <= ord(c) <= 0x7E else " " for c in text)
    return " ".join(text.replace('"', "'").split())


def split_nav_text(text: str, width: int = NAV_MAX_CHARS) -> list:
    """Split text into at most two <=20-char parts at the space nearest the middle."""
    text = nav_ascii(text)
    if len(text) <= width:
        return [text] if text else []
    best, cut = None, -1
    for i, ch in enumerate(text):
        if ch == " " and i <= width and len(text) - i - 1 <= width:
            d = abs(i - (len(text) + 1) // 2)
            if best is None or d < best:
                best, cut = d, i
    if cut < 0:  # no balanced split: wrap word by word, max 2 parts
        parts, line = [], ""
        for word in text.split(" "):
            cand = f"{line} {word}".strip()
            if len(cand) <= width:
                line = cand
            else:
                parts.append(line or word[:width])
                line = word[:width] if line else ""
        parts.append(line)
        return [p for p in parts if p][:2]
    return [text[:cut].strip(), text[cut:].strip()[:width]]


def nav_text(text: str, pad: bool = True) -> bytes:
    """AT+NAV="<text>": one message of up to 20 chars on the MID.

    GESITS Dashboard pads to exactly 20 chars, because the MID only redraws changed
    characters and leftovers from a longer previous text stay on screen otherwise.
    """
    text = _quoted_arg(text)
    if len(text) > NAV_MAX_CHARS:
        raise ValueError(f"MID shows max {NAV_MAX_CHARS} chars; use split_nav_text()")
    if pad:
        text = text.ljust(NAV_MAX_CHARS)
    return f'AT+NAV="{text}"{CRLF}'.encode("ascii")


def trip_range(km: float) -> bytes:
    """AT+TRIP="12.3": the remaining-range figure shown on the MID (sent every 3 s)."""
    return f'AT+TRIP="{km:.1f}"{CRLF}'.encode("ascii")


def map_image(payload: str) -> bytes:
    """AT+MAPS="<payload>"\\r\\n.

    payload is a base64 JPEG (gesits.mapimage.jpeg_to_base64, used on current bikes) or
    the old app's 2-bit hex bitmap (gesits.mapimage.encode_codes, legacy).
    """
    import re
    if not re.fullmatch(r"[A-Za-z0-9+/=]+", payload or ""):
        raise ValueError("map payload must be base64 (JPEG) or hex")
    return f'AT+MAPS="{payload}"{CRLF}'.encode("ascii")


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

    def get_float(self, key: str) -> Optional[float]:
        try:
            return float(self.raw[key].strip('"'))
        except (KeyError, ValueError):
            return None

    @property
    def park_state(self) -> Optional[str]:
        v = self.raw.get("PARK", "").strip('"').upper()
        return {"1": "PARK", "P": "PARK", "PARK": "PARK", "0": "DRIVE", "D": "DRIVE",
                "DRIVE": "DRIVE"}.get(v, v or None)

    @property
    def power_w(self) -> Optional[float]:
        v, i = self.get_float("VBAT"), self.get_float("IBAT")
        return None if v is None or i is None else round(v * i, 1)

    @property
    def error_mask(self) -> Optional[int]:
        return parse_error_mask(self.raw["ERR"]) if "ERR" in self.raw else None

    @property
    def errors(self) -> List[str]:
        return decode_errors(self.error_mask or 0)

    @property
    def range_km(self) -> Optional[float]:
        """The old app's range estimate: 1 km per % of SOC."""
        soc = self.get_int("SOC")
        return None if soc is None else soc * 1.0

    @property
    def energy_kwh(self) -> Optional[float]:
        """The app's remaining-energy estimate: (SOC + SOC) * 0.014 kWh."""
        soc = self.get_int("SOC")
        return None if soc is None else round(2 * soc * 0.014, 3)

    @property
    def has_error(self) -> Optional[bool]:
        mask = self.error_mask
        return None if mask is None else mask != 0


def parse_error_mask(value: str) -> int:
    """'0800' / '0x0800' -> 0x800 (hex, as reported), '0' / '0000' -> 0."""
    v = value.strip().strip('"')
    try:
        return int(v[2:] if v.lower().startswith("0x") else v, 16)
    except ValueError:
        return 0


def decode_errors(mask: int) -> List[str]:
    return [ERROR_BITS.get(b, f"Unknown bit {b}") for b in range(32) if mask >> b & 1]
