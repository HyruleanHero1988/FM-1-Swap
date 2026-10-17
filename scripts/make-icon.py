#!/usr/bin/env python3
"""Draws the app icon from its pixel map and writes it into the asset catalogue.

The icon is a cartridge with a lightning bolt on its label, going into a synth: swapping the
synth's software by flashing it. It's drawn on a 32 x 32 grid, so each cell is exactly 32 pixels
of the 1024-pixel icon, with a one-cell block shadow like the app's buttons. The Mac's sizes are
drawn from the same grid.

Run it after changing the map or the colours: `scripts/make-icon.py`. It needs only Python 3.
"""

import json
import struct
import zlib
from pathlib import Path

ICON_MAP = """
................................
.........GGGGGGGGGGGGGG.........
........GGGGGGGGGGGGGGGG........
........GWWWWWWWWWWWWWWG........
........GWWWWWWWKKKKWWWG........
........GWWWWWWKKKKWWWWG........
........GWWWWWKKKKWWWWWG........
........GWWWWKKKKWWWWWWG........
........GWWWKKKKKKKKWWWG........
........GWWWWWWKKKKWWWWG........
........GWWWWWKKKKWWWWWG........
........GWWWWWKKKWWWWWWG........
........GWWWWKKKWWWWWWWG........
........GWWWWKKWWWWWWWWG........
........GWWWKKWWWWWWWWWG........
........GWWWWWWWWWWWWWWG........
..GGGGKKGGGGGGGGGGGGGGGGKKGGGG..
..GgggKKGGGGGGGGGGGGGGGGKKgggG..
..GgggKKKKKKKKKKKKKKKKKKKKgggG..
..GggWWggggggggggggggggggWWggG..
..GggWWggggggggggggggggggWWggG..
..GggggggggggggggggggggggggggG..
..GgWWWWKKKWWWKKKWWWWKWWWWWggG..
..GgWWWWKKKWWWKKKWWWWKWWWWWggG..
..GgWWWWKKKWWWKKKWWWWKWWWWWggG..
..GgWWWWWKWWWWWKWWWWWKWWWWWggG..
..GgWWWWWKWWWWWKWWWWWKWWWWWggG..
..GgWWWWWKWWWWWKWWWWWKWWWWWggG..
..GggggggggggggggggggggggggggG..
..GGGGGGGGGGGGGGGGGGGGGGGGGGGG..
................................
................................
"""

# G: phosphor green, g: the synth's dark green face, W: label and keys, K: the bolt, slot and
# black keys. "." is the background, and "S" the shadow, worked out from the map.
PALETTES = {
    # The home screen in light and dark mode: the app's green on its near-black background.
    "AppIcon.png": {
        ".": (0x14, 0x19, 0x17, 255),
        "S": (0x08, 0x0A, 0x09, 255),  # The background darkened, as app icons must be opaque.
        "G": (0x7B, 0xE3, 0x9A, 255),
        "g": (0x2E, 0x5A, 0x3D, 255),
        "W": (0xE8, 0xEF, 0xE9, 255),
        "K": (0x0A, 0x0C, 0x0B, 255),
    },
    # Tinted mode: iOS keeps only the brightness and supplies the colour, on a clear background.
    "AppIcon-Tinted.png": {
        ".": (0, 0, 0, 0),
        "S": (0, 0, 0, 0),
        "G": (0xC8, 0xC8, 0xC8, 255),
        "g": (0x4A, 0x4A, 0x4A, 255),
        "W": (0xFF, 0xFF, 0xFF, 255),
        "K": (0x10, 0x10, 0x10, 255),
    },
}

SIZE = 1024
CATALOGUE = Path(__file__).resolve().parent.parent / "App" / "Assets.xcassets"


def read_map():
    rows = [row for row in ICON_MAP.strip().splitlines()]
    if len(rows) != 32 or any(len(row) != 32 for row in rows):
        raise SystemExit("The icon map must be 32 rows of 32 cells.")
    return [list(row) for row in rows]


def with_shadow(cells):
    """Adds a block shadow one cell down and right of every shape, where the background shows."""
    shaded = [row[:] for row in cells]
    for y in range(31):
        for x in range(31):
            if cells[y][x] != "." and cells[y + 1][x + 1] == ".":
                shaded[y + 1][x + 1] = "S"
    return shaded


def png(pixels):
    """Encodes square rows of RGBA tuples as a PNG."""
    raw = b"".join(b"\x00" + b"".join(struct.pack("4B", *pixel) for pixel in row) for row in pixels)

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    size = len(pixels)
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def render(cells, palette, size=SIZE):
    """Draws the cells at `size` pixels square, taking each pixel from the cell it falls in."""
    rows = []
    for y in range(size):
        cell_row = cells[y * 32 // size]
        rows.append([palette[cell_row[x * 32 // size]] for x in range(size)])
    return rows


# The Mac's icon sizes, in points, each at 1x and 2x.
MAC_SIZES = [16, 32, 128, 256, 512]


def mac_file(points, scale):
    return f"AppIcon-mac-{points}@{scale}x.png"


def contents():
    icon = {"idiom": "universal", "platform": "ios", "size": "1024x1024"}
    mac = [
        {"idiom": "mac", "size": f"{points}x{points}", "scale": f"{scale}x", "filename": mac_file(points, scale)}
        for points in MAC_SIZES
        for scale in (1, 2)
    ]
    return {
        "images": [
            {**icon, "filename": "AppIcon.png"},
            {**icon, "filename": "AppIcon.png", "appearances": [{"appearance": "luminosity", "value": "dark"}]},
            {
                **icon,
                "filename": "AppIcon-Tinted.png",
                "appearances": [{"appearance": "luminosity", "value": "tinted"}],
            },
        ]
        + mac,
        "info": {"author": "xcode", "version": 1},
    }


def main():
    cells = with_shadow(read_map())
    icon_set = CATALOGUE / "AppIcon.appiconset"
    icon_set.mkdir(parents=True, exist_ok=True)
    for name, palette in PALETTES.items():
        (icon_set / name).write_bytes(png(render(cells, palette)))
    # The Mac draws its icon at fixed sizes; below 32 pixels a cell is less than a pixel, so the
    # smallest ones lose some detail.
    for points in MAC_SIZES:
        for scale in (1, 2):
            pixels = render(cells, PALETTES["AppIcon.png"], points * scale)
            (icon_set / mac_file(points, scale)).write_bytes(png(pixels))
    (icon_set / "Contents.json").write_text(json.dumps(contents(), indent=2) + "\n")
    (CATALOGUE / "Contents.json").write_text(json.dumps({"info": {"author": "xcode", "version": 1}}, indent=2) + "\n")
    print(f"Wrote {icon_set.relative_to(CATALOGUE.parent.parent)}")


if __name__ == "__main__":
    main()
