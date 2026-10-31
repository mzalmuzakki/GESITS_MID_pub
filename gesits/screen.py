"""Custom MID screen: draws live data as a 350x220 picture for AT+MAPS.

Layout (the ESP32 firmware in firmware/esp32_mid_screen draws the same thing):

  +------------------------------------------------+
  | 12:34                      [D]  [ ECO ]        |  top bar: clock, gear, drive mode
  |  82%  [#########---]   |   1.2 kW  [====    ]  |  battery / power (green = regen)
  |  ~ 82 km range         |   ODO 1234.5 km       |
  |  BAT 31C   |   MOT 44C   |   INV 39C           |  temps: amber >= warn, red >= crit
  +------------------------------------------------+
  When ERR != 0 the temperature row becomes a red error banner.
"""
from __future__ import annotations

import time
from typing import Optional

from .protocol import Telemetry

W, H = 350, 220

BG = (12, 14, 18)
PANEL = (30, 34, 42)
TEXT = (235, 238, 242)
MUTED = (140, 148, 160)
GREEN = (60, 200, 110)
AMBER = (255, 176, 32)
RED = (235, 60, 60)
BLUE = (60, 140, 255)
MODE_COLOURS = {"ECO": GREEN, "URBAN": BLUE, "SPORT": RED}

# Same thresholds as GESITS Dashboard.
TEMP_LIMITS = {"TBAT": (45, 55), "TMOT": (70, 85), "TINV": (70, 85)}
MAX_POWER_W = 5000.0   # bar full scale


def _font(size: int, bold: bool = True):
    from PIL import ImageFont

    names = (["DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
              "arialbd.ttf", "Arial Bold.ttf"] if bold else
             ["DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "arial.ttf"])
    for name in names:
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    try:
        return ImageFont.load_default(size)
    except TypeError:  # very old Pillow
        return ImageFont.load_default()


def _temp_colour(key: str, value: Optional[float]):
    if value is None:
        return MUTED
    warn, crit = TEMP_LIMITS[key]
    return RED if value >= crit else AMBER if value >= warn else TEXT


def _fmt(v: Optional[float], spec: str = ".0f", none: str = "--") -> str:
    return none if v is None else format(v, spec)


def render(tel: Telemetry, now: Optional[time.struct_time] = None, range_km: Optional[float] = None):
    """Return a PIL image (350x220) of the custom screen. range_km: adaptive range
    (gesits.rangeest); defaults to the simple 1 km per % estimate."""
    from PIL import Image, ImageDraw

    now = now or time.localtime()
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    f_small, f_med, f_big = _font(14), _font(20), _font(44)

    # Top bar
    d.text((10, 6), time.strftime("%H:%M", now), font=f_med, fill=TEXT)
    mode = tel.drive_mode or "--"
    mc = MODE_COLOURS.get(mode, MUTED)
    mw = d.textlength(mode, font=f_small) + 16
    d.rounded_rectangle((W - 10 - mw, 8, W - 10, 30), radius=6, fill=mc)
    d.text((W - 10 - mw + 8, 11), mode, font=f_small, fill=BG)
    gear = {"PARK": "P", "DRIVE": "D"}.get(tel.park_state or "", "-")
    gc = AMBER if gear == "P" else GREEN if gear == "D" else MUTED
    d.rounded_rectangle((W - 20 - mw - 26, 8, W - 20 - mw, 30), radius=6, outline=gc, width=2)
    d.text((W - 20 - mw - 18, 11), gear, font=f_small, fill=gc)

    # Battery (left)
    soc = tel.get_float("SOC")
    socc = RED if soc is not None and soc <= 20 else AMBER if soc is not None and soc <= 35 else GREEN
    d.text((12, 40), _fmt(soc) + "%", font=f_big, fill=TEXT)
    d.rounded_rectangle((12, 96, 162, 112), radius=4, fill=PANEL)
    if soc:
        d.rounded_rectangle((12, 96, 12 + 150 * max(0.0, min(soc, 100)) / 100, 112), radius=4, fill=socc)
    rng = range_km if range_km is not None else tel.range_km
    d.text((12, 120), f"~{_fmt(rng)} km range", font=f_small, fill=MUTED)

    # Power (right)
    d.line((175, 44, 175, 136), fill=PANEL, width=2)
    p = tel.power_w
    d.text((188, 44), (_fmt(p / 1000, ".1f") if p is not None else "--") + " kW", font=f_med, fill=TEXT)
    d.text((188, 70), "REGEN" if p is not None and p < 0 else "POWER", font=f_small, fill=MUTED)
    d.rounded_rectangle((188, 96, 338, 112), radius=4, fill=PANEL)
    if p:
        frac = max(0.0, min(abs(p) / MAX_POWER_W, 1.0))
        d.rounded_rectangle((188, 96, 188 + 150 * frac, 112), radius=4, fill=GREEN if p < 0 else BLUE)
    d.text((188, 120), f"ODO {_fmt(tel.get_float('ODO'), '.1f')} km", font=f_small, fill=MUTED)

    # Bottom row: temperatures, or the error banner
    errs = tel.errors if tel.error_mask else []
    if errs:
        d.rounded_rectangle((8, 150, W - 8, H - 8), radius=8, fill=RED)
        d.text((18, 158), "ERROR", font=f_med, fill=TEXT)
        d.text((18, 186), errs[0][:38], font=f_small, fill=TEXT)
    else:
        tiles = [("BAT", "TBAT"), ("MOT", "TMOT"), ("INV", "TINV")]
        tw = (W - 16 - 2 * 8) / 3
        for i, (label, key) in enumerate(tiles):
            x0 = 8 + i * (tw + 8)
            v = tel.get_float(key)
            d.rounded_rectangle((x0, 150, x0 + tw, H - 8), radius=8, fill=PANEL)
            d.text((x0 + 10, 156), label, font=f_small, fill=MUTED)
            d.text((x0 + 10, 176), _fmt(v) + "°C", font=f_med, fill=_temp_colour(key, v))
    return img


def sample_telemetry() -> Telemetry:
    t = Telemetry()
    t.update([("SOC", "82"), ("MODE", "0"), ("PARK", "0"), ("VBAT", "71.8"), ("IBAT", "16.5"),
              ("ODO", "1234.5"), ("TBAT", "31"), ("TMOT", "47"), ("TINV", "39"), ("ERR", "0")])
    return t
