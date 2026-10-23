#!/usr/bin/env python3
"""Recolor the shipped FM-1 launcher art into hardware body colours.

Reads the committed default launcher zips, keeps the black/dark icon
byte-for-byte, and writes the other plastic colours plus adaptive
foregrounds. Canvas stays black; keys and pads are chosen per body so a
cream or white unit still has contrast. The product photo is not shipped —
colours below were sampled from it.
"""

from __future__ import annotations

import base64
import io
import tempfile
import zipfile
from collections import deque
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app"
PREVIEW_DIR = Path(tempfile.gettempdir()) / "fm1-launcher-preview"

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# Adaptive foreground is 108dp. Content from the 48dp art is scaled to the
# 72dp safe zone and centred, so Pixel/Samsung masks do not crop the FM-1.
ADAPTIVE = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

# Sampled from the M-VAVE colour-variant photo. Pads/keys are not a hue-shift
# of the whole PNG: light bodies get dark controls, darker bodies keep light
# key ticks like the current black icon.
COLOURS = {
    "white": {
        "body": (0xE6, 0xE4, 0xDE),
        "pad": (0x2A, 0x2C, 0x2E),
        "key": (0x24, 0x24, 0x26),
        "outline": (0x3A, 0x3A, 0x3C),
        "light": True,
    },
    "cream": {
        "body": (0xD5, 0xC9, 0xB4),
        "pad": (0x2C, 0x28, 0x24),
        "key": (0x2A, 0x26, 0x22),
        "outline": (0x5C, 0x50, 0x40),
        "light": True,
    },
    "rust": {
        "body": (0x7A, 0x43, 0x36),
        "pad": (0x2E, 0x20, 0x1C),
        "key": (0xEE, 0xE4, 0xDC),
        "outline": (0x3A, 0x20, 0x1A),
        "light": False,
    },
    "mint": {
        "body": (0x6B, 0xC8, 0xB6),
        "pad": (0x2A, 0x58, 0x52),
        "key": (0x1A, 0x32, 0x30),
        "outline": (0x2A, 0x58, 0x50),
        "light": True,
    },
    "orange": {
        "body": (0xC0, 0x56, 0x34),
        "pad": (0x3A, 0x22, 0x18),
        "key": (0xF2, 0xE6, 0xD8),
        "outline": (0x6A, 0x28, 0x18),
        "light": False,
    },
    "purple": {
        "body": (0xA5, 0x7A, 0xB8),
        "pad": (0x58, 0x3E, 0x74),
        "key": (0xF2, 0xEA, 0xF6),
        "outline": (0x4A, 0x30, 0x5A),
        "light": False,
    },
    "slate": {
        "body": (0x56, 0x72, 0x8C),
        "pad": (0x2A, 0x3A, 0x4C),
        "key": (0xE8, 0xEC, 0xF0),
        "outline": (0x2A, 0x3A, 0x4A),
        "light": False,
    },
}

# 192px source. Pads sit to the right of the screen; keys are the bottom row.
PAD_BOX = (100, 55, 176, 108)
KEY_BOX = (16, 112, 176, 146)
SCREEN_BOX = (52, 56, 98, 92)
OUTLINE_PX = 4


def zip_bytes(name: str) -> bytes:
    encoded = (APP / name).read_text(encoding="ascii")
    return base64.b64decode(encoded)


def png_from_default_zips(entry: str) -> Image.Image:
    for name in ("launcher-icons-a.zip.b64", "launcher-icons-b.zip.b64"):
        with zipfile.ZipFile(io.BytesIO(zip_bytes(name))) as zf:
            if entry in zf.namelist():
                return Image.open(io.BytesIO(zf.read(entry))).convert("RGBA")
    raise SystemExit(f"{entry} is not in the default launcher zips.")


def load_source() -> Image.Image:
    return png_from_default_zips("mipmap-xxxhdpi/ic_launcher.png")


def is_near_black(p, limit=4):
    return p[3] > 0 and p[0] <= limit and p[1] <= limit and p[2] <= limit


def luminance(p):
    return (0.2126 * p[0] + 0.7152 * p[1] + 0.0722 * p[2]) / 255.0


def saturation(p):
    mx, mn = max(p[0], p[1], p[2]), min(p[0], p[1], p[2])
    if mx == 0:
        return 0.0
    return (mx - mn) / mx


def flood_canvas(im: Image.Image) -> list[list[bool]]:
    w, h = im.size
    px = im.load()
    canvas = [[False] * w for _ in range(h)]
    q = deque()
    for x, y in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        q.append((x, y))
    while q:
        x, y = q.popleft()
        if x < 0 or y < 0 or x >= w or y >= h or canvas[y][x]:
            continue
        if not is_near_black(px[x, y], 3):
            continue
        canvas[y][x] = True
        q.extend(((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)))
    return canvas


def in_box(x, y, box):
    return box[0] <= x < box[2] and box[1] <= y < box[3]


def classify(im: Image.Image, canvas: list[list[bool]]) -> list[list[str]]:
    w, h = im.size
    px = im.load()
    dist = [[999] * w for _ in range(h)]
    q = deque()
    for y in range(h):
        for x in range(w):
            if canvas[y][x]:
                dist[y][x] = 0
                q.append((x, y))
    while q:
        x, y = q.popleft()
        d = dist[y][x]
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if 0 <= nx < w and 0 <= ny < h and dist[ny][nx] > d + 1:
                dist[ny][nx] = d + 1
                q.append((nx, ny))

    labels = [["canvas"] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            if canvas[y][x]:
                continue
            p = px[x, y]
            r, g, b, a = p
            sat = saturation(p)
            lum = luminance(p)
            if g > r + 22 and g > b + 12 and g > 28:
                labels[y][x] = "green"
            elif dist[y][x] <= OUTLINE_PX:
                labels[y][x] = "outline"
            elif in_box(x, y, SCREEN_BOX) and lum < 0.18:
                labels[y][x] = "screen"
            elif in_box(x, y, PAD_BOX) and (
                (b > 40 and g > 35 and (g + b) > r * 1.35) or lum > 0.28
            ):
                labels[y][x] = "pad"
            elif in_box(x, y, KEY_BOX) and lum > 0.45 and sat < 0.25:
                labels[y][x] = "key"
            elif lum > 0.55 and sat < 0.22:
                labels[y][x] = "key"
            elif lum < 0.045:
                labels[y][x] = "screen" if in_box(x, y, SCREEN_BOX) else "hole"
            else:
                labels[y][x] = "body"
    return labels


def shade(dest, factor: float) -> tuple[int, int, int]:
    factor = max(0.0, factor)
    if factor <= 1:
        return tuple(max(0, min(255, int(c * factor))) for c in dest)
    t = min((factor - 1) / 1.8, 1.0)
    return tuple(max(0, min(255, int(c + (255 - c) * t))) for c in dest)


def colorize_pixel(p, src_mean_lum: float, dest) -> tuple[int, int, int, int]:
    lum = luminance(p)
    factor = 1.0 if src_mean_lum < 1e-6 else lum / src_mean_lum
    r, g, b = shade(dest, factor)
    return (r, g, b, 255)


def recolor(im: Image.Image, canvas, labels, spec) -> Image.Image:
    w, h = im.size
    px = im.load()
    body_samples = []
    for y in range(h):
        for x in range(w):
            if labels[y][x] == "body":
                body_samples.append(px[x, y])
    src_mean_lum = sum(luminance(p) for p in body_samples) / max(1, len(body_samples))
    out = Image.new("RGBA", (w, h))
    dest = out.load()
    body, pad, key, outline = spec["body"], spec["pad"], spec["key"], spec["outline"]
    light = spec["light"]
    for y in range(h):
        for x in range(w):
            p = px[x, y]
            lab = labels[y][x]
            if lab == "canvas":
                dest[x, y] = (0, 0, 0, 255)
            elif lab == "green":
                dest[x, y] = p
            elif lab == "screen" or lab == "hole":
                dest[x, y] = (0, 0, 0, 255)
            elif lab == "outline":
                dest[x, y] = colorize_pixel(p, src_mean_lum, outline)
            elif lab == "pad":
                # Pads in the source are bright cyan. Keep only a little of that
                # shading so they stay the pad colour, not washed-out white.
                dest[x, y] = (*shade(pad, 0.72 + 0.28 * luminance(p)), 255)
            elif lab == "key":
                if light:
                    dest[x, y] = (*key, 255)
                else:
                    dest[x, y] = p
            else:
                dest[x, y] = colorize_pixel(p, src_mean_lum, body)
    return out


def with_transparent_canvas(im: Image.Image, canvas) -> Image.Image:
    out = im.copy()
    px = out.load()
    w, h = out.size
    for y in range(h):
        for x in range(w):
            if canvas[y][x]:
                px[x, y] = (0, 0, 0, 0)
    return out


def scale(im: Image.Image, size: int) -> Image.Image:
    if im.size == (size, size):
        return im
    return im.resize((size, size), Image.Resampling.LANCZOS)


def adaptive_foreground(device: Image.Image, canvas_size: int) -> Image.Image:
    """Place the 48dp-art device in the 72/108 adaptive safe zone."""
    content = int(round(canvas_size * 72 / 108))
    glyph = scale(device, content)
    out = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    origin = (canvas_size - content) // 2
    out.paste(glyph, (origin, origin), glyph)
    return out


def monochrome(device: Image.Image, canvas, canvas_size: int) -> Image.Image:
    """Alpha from the FM-1's details so a themed icon still reads as the unit."""
    w, h = device.size
    px = device.load()
    mono = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    mp = mono.load()
    for y in range(h):
        for x in range(w):
            if canvas[y][x]:
                continue
            p = px[x, y]
            lum = luminance(p)
            # Soft body plus strong details (keys, pads, outline, waveform).
            alpha = int(max(lum * 255, 70 if lum > 0.04 else 0))
            if saturation(p) > 0.25:
                alpha = min(255, alpha + 80)
            mp[x, y] = (255, 255, 255, max(0, min(255, alpha)))
    return adaptive_foreground(mono, canvas_size)


def png_bytes(im: Image.Image) -> bytes:
    buf = io.BytesIO()
    im.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def write_png(path: Path, im: Image.Image):
    path.parent.mkdir(parents=True, exist_ok=True)
    data = png_bytes(im)
    if not path.exists() or path.read_bytes() != data:
        path.write_bytes(data)


def mime_b64(data: bytes) -> str:
    return base64.encodebytes(data).decode("ascii")


def pack_zip(entries: dict[str, bytes]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for name in sorted(entries):
            zf.writestr(name, entries[name])
    return buf.getvalue()


def write_b64_zip(path: Path, entries: dict[str, bytes]):
    path.write_text(mime_b64(pack_zip(entries)), encoding="ascii")
    print(f"Wrote {path.name} ({path.stat().st_size} bytes, {len(entries)} files)")


def contact_sheet(images: list[tuple[str, Image.Image]], cell=192) -> Image.Image:
    n = len(images)
    sheet = Image.new("RGB", (cell * n, cell + 24), (12, 14, 13))
    for i, (name, im) in enumerate(images):
        sheet.paste(scale(im.convert("RGB"), cell), (i * cell, 0))
    return sheet


def main():
    source = load_source()
    canvas = flood_canvas(source)
    labels = classify(source, canvas)
    transparent_source = with_transparent_canvas(source, canvas)

    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    variants = [("black", source)]
    recolored = {}
    for name, spec in COLOURS.items():
        im = recolor(source, canvas, labels, spec)
        recolored[name] = im
        variants.append((name, im))
        write_png(PREVIEW_DIR / f"{name}.png", im)
        write_png(PREVIEW_DIR / f"{name}_fg.png", with_transparent_canvas(im, canvas))
    write_png(PREVIEW_DIR / "black.png", source)
    sheet = contact_sheet(variants)
    write_png(PREVIEW_DIR / "sheet.png", sheet)
    print("Preview", PREVIEW_DIR / "sheet.png")

    # Adaptive / monochrome assets. Default black foreground is the original
    # device with a punched canvas; colour variants use the recolored device.
    fg_entries: dict[str, bytes] = {}
    colour_low: dict[str, bytes] = {}
    colour_high: dict[str, bytes] = {}

    for folder, size in DENSITIES.items():
        bucket = colour_low if size <= 96 else colour_high
        for name, im in recolored.items():
            data = png_bytes(scale(im, size))
            bucket[f"{folder}/ic_launcher_{name}.png"] = data
            bucket[f"{folder}/ic_launcher_{name}_round.png"] = data

    # xxhdpi and xxxhdpi are enough; Android scales the adaptive layers down.
    for folder, size in ADAPTIVE.items():
        if size < 324:
            continue
        black_fg = png_bytes(adaptive_foreground(transparent_source, size))
        fg_entries[f"{folder}/ic_launcher_foreground.png"] = black_fg
        fg_entries[f"{folder}/ic_launcher_monochrome.png"] = png_bytes(
            monochrome(source, canvas, size)
        )
        for name, im in recolored.items():
            fg = with_transparent_canvas(im, canvas)
            data = png_bytes(adaptive_foreground(fg, size))
            fg_entries[f"{folder}/ic_launcher_{name}_foreground.png"] = data

    write_b64_zip(APP / "launcher-icons-colours-a.zip.b64", colour_low)
    write_b64_zip(APP / "launcher-icons-colours-b.zip.b64", colour_high)
    write_b64_zip(APP / "launcher-icons-colours-c.zip.b64", fg_entries)
    write_adaptive_xml()
    write_png(PREVIEW_DIR / "mono.png", monochrome(source, canvas, 192))
    print("Colours:", ", ".join(["black"] + list(COLOURS)))


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/{foreground}" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""


def write_adaptive_xml():
    dest = APP / "src" / "main" / "res" / "mipmap-anydpi-v26"
    dest.mkdir(parents=True, exist_ok=True)
    files = {
        "ic_launcher": "ic_launcher_foreground",
        "ic_launcher_round": "ic_launcher_foreground",
    }
    for name in COLOURS:
        files[f"ic_launcher_{name}"] = f"ic_launcher_{name}_foreground"
        files[f"ic_launcher_{name}_round"] = f"ic_launcher_{name}_foreground"
    for filename, foreground in files.items():
        path = dest / f"{filename}.xml"
        text = ADAPTIVE_XML.format(foreground=foreground)
        if not path.exists() or path.read_text(encoding="utf-8") != text:
            path.write_text(text, encoding="utf-8")
    print(f"Wrote {len(files)} adaptive icon XML files")


if __name__ == "__main__":
    main()
