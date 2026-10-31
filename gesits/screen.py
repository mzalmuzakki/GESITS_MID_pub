"""Custom MID screen: draws live data as a 350x220 picture for AT+MAPS.

Layout (designed at 350x160, scaled into the area not covered by the MID's own widgets;
on a real G1 the MID shows the picture's top ~164 px inside its red map frame):

  +------------------------------------------------+
  | 12:34   82 km              [D]  [ ECO ]        |  clock, adaptive range, gear, mode
  |  82%                   |   1.2 kW   POWER      |  battery / power (green = regen)
  |  [#########---]        |   [====         ]     |
  |  BAT 31C   |   MOT 44C   |   INV 39C           |  temps: amber >= warn, red >= crit
  +------------------------------------------------+
  When ERR != 0 the temperature row becomes a red error banner.
  (The ESP32 firmware still draws the earlier full-height layout.)
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


DW, DH = 350, 160          # dashboard design size, scaled into the free area of the 350x220 picture
DEFAULT_MARGINS = (0, 0, 0, 56)   # left, top, right, bottom: MID widgets cover the bottom ~56 px


def render(tel: Telemetry, now: Optional[time.struct_time] = None, range_km: Optional[float] = None,
           margins=DEFAULT_MARGINS):
    """Return the 350x220 picture for AT+MAPS: the dashboard drawn in the free area left
    by `margins` (same layout as the Android app's ScreenRenderer.kt)."""
    from PIL import Image, ImageDraw

    now = now or time.localtime()
    rng = range_km if range_km is not None else tel.range_km
    dash = Image.new("RGB", (DW, DH), BG)
    d = ImageDraw.Draw(dash)
    f_small, f_med, f_big = _font(13), _font(20), _font(44)

    # row 1: clock, range, gear, mode
    d.text((8, 3), time.strftime("%H:%M", now), font=f_med, fill=TEXT)
    if rng is not None:
        d.text((92, 6), f"{_fmt(rng)} km", font=_font(16), fill=MUTED)
    mode = tel.drive_mode or "--"
    mc = MODE_COLOURS.get(mode, MUTED)
    mw = d.textlength(mode, font=f_small) + 16
    d.rounded_rectangle((DW - 8 - mw, 4, DW - 8, 25), radius=6, fill=mc)
    d.text((DW - 8 - mw + 8, 7), mode, font=f_small, fill=BG)
    gear = {"PARK": "P", "DRIVE": "D"}.get(tel.park_state or "", "-")
    gc = AMBER if gear == "P" else GREEN if gear == "D" else MUTED
    d.rounded_rectangle((DW - 16 - mw - 24, 4, DW - 16 - mw, 25), radius=6, outline=gc, width=2)
    d.text((DW - 16 - mw - 16, 7), gear, font=f_small, fill=gc)

    # row 2: battery, power
    soc = tel.get_float("SOC")
    socc = RED if soc is not None and soc <= 20 else AMBER if soc is not None and soc <= 35 else GREEN
    d.text((8, 26), _fmt(soc) + "%", font=f_big, fill=TEXT)
    d.rounded_rectangle((8, 80, 162, 92), radius=4, fill=PANEL)
    if soc:
        d.rounded_rectangle((8, 80, 8 + 154 * max(0.0, min(soc, 100)) / 100, 92), radius=4, fill=socc)
    d.line((175, 34, 175, 94), fill=PANEL, width=2)
    p = tel.power_w
    d.text((188, 36), (_fmt(p / 1000, ".1f") if p is not None else "--") + " kW", font=f_med, fill=TEXT)
    d.text((188, 60), "REGEN" if p is not None and p < 0 else "POWER", font=f_small, fill=MUTED)
    d.rounded_rectangle((188, 80, 342, 92), radius=4, fill=PANEL)
    if p:
        frac = max(0.0, min(abs(p) / MAX_POWER_W, 1.0))
        d.rounded_rectangle((188, 80, 188 + 154 * frac, 92), radius=4, fill=GREEN if p < 0 else BLUE)

    # row 3: temperatures or error banner
    errs = tel.errors if tel.error_mask else []
    if errs:
        d.rounded_rectangle((4, 102, DW - 4, DH - 2), radius=8, fill=RED)
        d.text((14, 106), "ERROR", font=f_med, fill=TEXT)
        d.text((14, 134), errs[0][:40], font=f_small, fill=TEXT)
    else:
        tw = (DW - 8 - 16) / 3
        for i, (label, key) in enumerate([("BAT", "TBAT"), ("MOT", "TMOT"), ("INV", "TINV")]):
            x0 = 4 + i * (tw + 8)
            v = tel.get_float(key)
            d.rounded_rectangle((x0, 102, x0 + tw, DH - 2), radius=8, fill=PANEL)
            d.text((x0 + 8, 106), label, font=f_small, fill=MUTED)
            d.text((x0 + 8, 126), _fmt(v) + "\u00b0C", font=f_med, fill=_temp_colour(key, v))

    left, top, right, bottom = margins
    cw, ch = max(40, W - left - right), max(30, H - top - bottom)
    sc = min(cw / DW, ch / DH)
    img = Image.new("RGB", (W, H), BG)
    img.paste(dash.resize((max(1, round(DW * sc)), max(1, round(DH * sc))), Image.LANCZOS), (left, top))
    return img


def calibration_grid():
    """Same grid as the Android app: lines every 10 px (brighter every 50) with labels."""
    from PIL import Image, ImageDraw

    img = Image.new("RGB", (W, H), (0, 0, 0))
    d = ImageDraw.Draw(img)
    for x in range(0, W + 1, 10):
        d.line((x, 0, x, H), fill=(150, 150, 150) if x % 50 == 0 else (50, 50, 50))
    for y in range(0, H + 1, 10):
        d.line((0, y, W, y), fill=(150, 150, 150) if y % 50 == 0 else (50, 50, 50))
    f = _font(12)
    for x in range(50, W, 50):
        d.text((x + 2, 1), str(x), font=f, fill=(255, 255, 0))
    for y in range(50, H, 50):
        d.text((2, y - 14), str(y), font=f, fill=(255, 255, 0))
    d.rectangle((1, 1, W - 2, H - 2), outline=(255, 0, 0), width=3)
    d.rectangle((20, 20, W - 20, H - 20), outline=(0, 255, 0), width=3)
    d.text((W // 2 - 14, 22), "TOP", font=_font(14), fill=(0, 255, 255))
    d.text((W // 2 - 30, H // 2 - 8), "350x220", font=_font(16), fill=(255, 255, 255))
    return img


def sample_telemetry() -> Telemetry:
    t = Telemetry()
    t.update([("SOC", "82"), ("MODE", "0"), ("PARK", "0"), ("VBAT", "71.8"), ("IBAT", "16.5"),
              ("ODO", "1234.5"), ("TBAT", "31"), ("TMOT", "47"), ("TINV", "39"), ("ERR", "0")])
    return t
