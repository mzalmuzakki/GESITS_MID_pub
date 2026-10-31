"""Custom MID screen: draws live data as a 350x220 picture for AT+MAPS.

Layout (320x128 = the MID's visible map area; GRID Mobile's MID pictures use this size.
350x220 pictures - GESITS Dashboard's size - spill under the MID's own widgets):

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

W, H = 320, 128   # GRID Mobile's MID pictures are 320x128 = the visible map area

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


DW, DH = 320, 128          # dashboard design size = the MID's visible map area
DEFAULT_MARGINS = (0, 0, 0, 0)    # left, top, right, bottom (pixels) if the MID covers an edge


def render(tel: Telemetry, now: Optional[time.struct_time] = None, range_km: Optional[float] = None,
           margins=DEFAULT_MARGINS):
    """Return the 320x128 picture for AT+MAPS (same layout as the Android app's ScreenRenderer.kt).
    Text positions are baselines (anchor "ls"), exactly like Android's Canvas.drawText."""
    from PIL import Image, ImageDraw

    now = now or time.localtime()
    rng = range_km if range_km is not None else tel.range_km
    dash = Image.new("RGB", (DW, DH), BG)
    d = ImageDraw.Draw(dash)
    f12, f14, f18, f38 = _font(12), _font(14), _font(18), _font(38)

    def txt(x, y, s, f, fill):
        d.text((x, y), s, font=f, fill=fill, anchor="ls")

    # row 1: clock, range, gear, mode
    txt(6, 17, time.strftime("%H:%M", now), f18, TEXT)
    if rng is not None:
        txt(72, 16, f"{_fmt(rng)} km", f14, MUTED)
    mode = tel.drive_mode or "--"
    mc = MODE_COLOURS.get(mode, MUTED)
    mw = d.textlength(mode, font=f12) + 14
    d.rounded_rectangle((DW - 6 - mw, 2, DW - 6, 20), radius=5, fill=mc)
    txt(DW - 6 - mw + 7, 16, mode, f12, BG)
    gear = {"PARK": "P", "DRIVE": "D"}.get(tel.park_state or "", "-")
    gc = AMBER if gear == "P" else GREEN if gear == "D" else MUTED
    d.rounded_rectangle((DW - 12 - mw - 20, 2, DW - 12 - mw, 20), radius=5, outline=gc, width=2)
    txt(DW - 12 - mw - 14, 16, gear, f12, gc)

    # row 2: battery, power
    soc = tel.get_float("SOC")
    socc = RED if soc is not None and soc <= 20 else AMBER if soc is not None and soc <= 35 else GREEN
    txt(6, 60, _fmt(soc) + "%", f38, TEXT)
    d.rounded_rectangle((6, 66, 150, 76), radius=4, fill=PANEL)
    if soc:
        d.rounded_rectangle((6, 66, 6 + 144 * max(0.0, min(soc, 100)) / 100, 76), radius=4, fill=socc)
    d.rectangle((159, 26, 160, 76), fill=PANEL)
    p = tel.power_w
    txt(170, 44, (_fmt(p / 1000, ".1f") if p is not None else "--") + " kW", f18, TEXT)
    txt(170, 60, "REGEN" if p is not None and p < 0 else "POWER", f12, MUTED)
    d.rounded_rectangle((170, 66, 314, 76), radius=4, fill=PANEL)
    if p:
        frac = max(0.0, min(abs(p) / MAX_POWER_W, 1.0))
        d.rounded_rectangle((170, 66, 170 + 144 * frac, 76), radius=4, fill=GREEN if p < 0 else BLUE)

    # row 3: temperatures or error banner
    errs = tel.errors if tel.error_mask else []
    if errs:
        d.rounded_rectangle((4, 82, DW - 4, DH - 2), radius=6, fill=RED)
        txt(12, 101, "ERROR", f18, TEXT)
        txt(12, 119, errs[0][:40], f12, TEXT)
    else:
        tw = (DW - 8 - 12) / 3
        for i, (label, key) in enumerate([("BAT", "TBAT"), ("MOT", "TMOT"), ("INV", "TINV")]):
            x0 = 4 + i * (tw + 6)
            v = tel.get_float(key)
            d.rounded_rectangle((x0, 82, x0 + tw, DH - 2), radius=6, fill=PANEL)
            txt(x0 + 7, 97, label, f12, MUTED)
            txt(x0 + 7, 120, _fmt(v) + "\u00b0C", f18, _temp_colour(key, v))

    left, top, right, bottom = margins
    if margins == (0, 0, 0, 0):
        return dash
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
    d.rectangle((10, 10, W - 10, H - 10), outline=(0, 255, 0), width=3)
    d.text((W // 2 - 14, 12), "TOP", font=_font(14), fill=(0, 255, 255))
    d.text((W // 2 - 30, H // 2 - 8), f"{W}x{H}", font=_font(16), fill=(255, 255, 255))
    return img


def sample_telemetry() -> Telemetry:
    t = Telemetry()
    t.update([("SOC", "82"), ("MODE", "0"), ("PARK", "0"), ("VBAT", "71.8"), ("IBAT", "16.5"),
              ("ODO", "1234.5"), ("TBAT", "31"), ("TMOT", "47"), ("TINV", "39"), ("ERR", "0")])
    return t
