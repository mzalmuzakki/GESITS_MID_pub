"""Encode/decode the 164x88, 4-colour bitmap the Gesits MID shows in map mode.

Reconstructed from NavigationGoogleFragment.generateBitmapString() in the app:

* the snapshot is scaled to 164 x 88 px (nearest neighbour, filter=false);
* every pixel becomes a 2-bit colour code (see classify());
* pixels are taken row by row; 4 pixels per byte, first pixel in the LOWEST
  two bits (pixel n of a byte -> bits 2n..2n+1);
* bytes are written as upper-case hex: 164*88/4 = 3608 bytes = 7216 hex chars.

The app builds this through 64-bit words; app_reference_encode() reproduces that
exact algorithm so the tests can prove encode_codes() gives identical output.
"""
from __future__ import annotations

from typing import List, Sequence

WIDTH = 164
HEIGHT = 88
PIXELS = WIDTH * HEIGHT
HEX_LENGTH = PIXELS // 2  # 2 bits per pixel = 2 pixels per hex digit -> 7216 chars

# Colour codes as the app assigns them. What the MID physically renders for each
# code is not confirmed; these preview colours match the source colours.
BLACK, WHITE, BLUE, RED = 0, 1, 2, 3
PREVIEW_PALETTE = {BLACK: (0, 0, 0), WHITE: (255, 255, 255), BLUE: (0, 90, 255), RED: (230, 0, 0)}


def classify(r: int, g: int, b: int) -> int:
    """Map an RGB pixel to a 2-bit code, in the same order of checks as the app."""
    if r >= 210 and g >= 210 and b >= 210:
        return WHITE
    if r <= 40 and g <= 40 and b <= 40:
        return BLACK
    if b >= 210:
        return BLUE
    if r >= 210:
        return RED
    return WHITE


def _check(codes: Sequence[Sequence[int]]) -> None:
    if len(codes) != HEIGHT or any(len(row) != WIDTH for row in codes):
        raise ValueError(f"expected {HEIGHT} rows of {WIDTH} codes")


def encode_codes(codes: Sequence[Sequence[int]]) -> str:
    """codes[y][x] in 0..3 -> hex payload for AT+MAPS."""
    _check(codes)
    out = bytearray(PIXELS // 4)
    i = 0
    for row in codes:
        for c in row:
            if not 0 <= c <= 3:
                raise ValueError(f"colour code out of range: {c}")
            out[i >> 2] |= c << ((i & 3) * 2)
            i += 1
    return out.hex().upper()


def decode_hex(payload: str) -> List[List[int]]:
    """Inverse of encode_codes(): hex payload -> codes[y][x]."""
    data = bytes.fromhex(payload)
    if len(data) != PIXELS // 4:
        raise ValueError(f"expected {PIXELS // 2} hex chars, got {len(payload)}")
    flat = [(data[i >> 2] >> ((i & 3) * 2)) & 3 for i in range(PIXELS)]
    return [flat[y * WIDTH:(y + 1) * WIDTH] for y in range(HEIGHT)]


def app_reference_encode(codes: Sequence[Sequence[int]]) -> str:
    """Line-for-line port of the app's 64-bit-word routine (used by the tests)."""
    _check(codes)
    words = [0] * 451
    w = 0
    hexstr = ""
    for y in range(HEIGHT):
        for x in range(WIDTH):
            r = (x + 1) % 4
            adj = {1: 8, 2: 4, 3: 0, 0: -4}[r]
            i = y * 164 + x
            shift = 64 - (i * 2) % 64 - adj
            words[w] |= codes[y][x] << shift
            if (i + 1) % 32 == 0:
                hexstr += format(words[w], "016x")
                w += 1
    return hexstr.upper()


# --- Pillow helpers (optional dependency) -----------------------------------

def image_to_codes(path_or_image) -> List[List[int]]:
    """Load any image, scale it to 164x88 like the app does, and classify pixels."""
    from PIL import Image

    img = path_or_image if isinstance(path_or_image, Image.Image) else Image.open(path_or_image)
    img = img.convert("RGB").resize((WIDTH, HEIGHT), Image.NEAREST)
    px = img.load()
    return [[classify(*px[x, y]) for x in range(WIDTH)] for y in range(HEIGHT)]


def codes_to_image(codes: Sequence[Sequence[int]], scale: int = 4):
    """Render codes as a preview image (what you should roughly see on the MID)."""
    from PIL import Image

    _check(codes)
    img = Image.new("RGB", (WIDTH, HEIGHT))
    img.putdata([PREVIEW_PALETTE[c] for row in codes for c in row])
    return img.resize((WIDTH * scale, HEIGHT * scale), Image.NEAREST) if scale > 1 else img
