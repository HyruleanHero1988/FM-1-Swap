#!/usr/bin/env python3
"""Draw the FM-1 Swap launcher icons from the 32 x 32 pixel map."""

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

PALETTE = {
    ".": (0x14, 0x19, 0x17, 255),
    "S": (0x08, 0x0A, 0x09, 255),
    "G": (0x7B, 0xE3, 0x9A, 255),
    "g": (0x2E, 0x5A, 0x3D, 255),
    "W": (0xE8, 0xEF, 0xE9, 255),
    "K": (0x0A, 0x0C, 0x0B, 255),
}

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def read_map():
    rows = [row for row in ICON_MAP.strip().splitlines()]
    if len(rows) != 32 or any(len(row) != 32 for row in rows):
        raise SystemExit("The icon map must be 32 rows of 32 cells.")
    return [list(row) for row in rows]


def with_shadow(cells):
    shaded = [row[:] for row in cells]
    for y in range(31):
        for x in range(31):
            if cells[y][x] != "." and cells[y + 1][x + 1] == ".":
                shaded[y + 1][x + 1] = "S"
    return shaded


def png(pixels):
    raw = b"".join(b"\x00" + b"".join(struct.pack("4B", *pixel) for pixel in row) for row in pixels)

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    size = len(pixels)
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def render(cells, size):
    rows = []
    for y in range(size):
        cell_row = cells[y * 32 // size]
        rows.append([PALETTE[cell_row[x * 32 // size]] for x in range(size)])
    return rows


def main():
    root = Path(__file__).resolve().parent.parent
    cells = with_shadow(read_map())
    res = root / "app" / "src" / "main" / "res"
    for folder, size in DENSITIES.items():
        dest = res / folder
        dest.mkdir(parents=True, exist_ok=True)
        data = png(render(cells, size))
        (dest / "ic_launcher.png").write_bytes(data)
        (dest / "ic_launcher_round.png").write_bytes(data)
    play = res / "mipmap-xxxhdpi" / "ic_launcher_play.png"
    play.write_bytes(png(render(cells, 512)))
    print("Wrote launcher icons")


if __name__ == "__main__":
    main()
