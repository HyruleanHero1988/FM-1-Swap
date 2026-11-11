#!/usr/bin/env python3
"""Rewrite app/src/main/assets/catalogue/newchip.json from The Firmware Hub's catalog.json.

    scripts/update-newchip.py                 # fetch https://thefirmwarehub.com/catalog.json
    scripts/update-newchip.py catalog.json    # or read a copy you already have

The hub asks apps to read catalog.json at most once a day with a User-Agent that says who you
are (https://thefirmwarehub.com/kb/#catalog), so keep a copy and pass it in when re-running.
The list is CC BY 4.0: "Firmware list: The Firmware Hub - https://thefirmwarehub.com".

The hub reads a `chip` value out of every package it lists:
    new  knows the newer flash chip 0xB36014          -> "works"
    old  knows only the first units' chip 0x856014    -> "bricks" (saves lost, updates often refused)
    any  does not check the chip, hub has not tried   -> "unknown" (never counted as safe)
Marks are per package (sha256). Lines with "origin": "manual" are kept as they are, so a
maintainer can add a firmware someone reported by hand and a refresh does not drop it.
"""
import json
import pathlib
import sys
import urllib.request
from datetime import date

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/catalogue/newchip.json"
FEED = "https://thefirmwarehub.com/catalog.json"
STATUS = {"new": "works", "old": "bricks"}
NOTES = {
    "any": "The package does not check the flash chip and the hub has not tried it on the newer chip 0xB36014.",
}


def load_feed(arg):
    if arg:
        return json.loads(pathlib.Path(arg).read_text())
    request = urllib.request.Request(FEED, headers={"User-Agent": "FM-1-Swap-newchip-update (github.com/HyruleanHero1988/FM-1-Swap)"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def main():
    feed = load_feed(sys.argv[1] if len(sys.argv) > 1 else None)
    manual = []
    if OUT.exists():
        manual = [e for e in json.loads(OUT.read_text()).get("firmwares", []) if e.get("origin") == "manual"]
    entries = []
    for fw in sorted(feed["firmwares"], key=lambda f: f["id"]):
        chip = fw.get("chip")
        entry = {
            "id": fw["id"],
            "name": fw["name"],
            "version": fw["version"],
            "sha256": fw["sha256"].lower(),
            "status": STATUS.get(chip, "unknown"),
            "origin": "hub",
        }
        if chip in NOTES:
            entry["note"] = NOTES[chip]
        entries.append(entry)
    doc = {
        "about": "Per-package compatibility with newer FM-1s (flash chip 0xB36014). Anything not listed, or marked unknown, is untested. Rewritten by scripts/update-newchip.py.",
        "source": {
            "name": "The Firmware Hub",
            "url": "https://thefirmwarehub.com/",
            "feed": FEED,
            "license": "CC-BY-4.0",
            "attribution": feed.get("license", {}).get("attribution", "Firmware list: The Firmware Hub \u2013 https://thefirmwarehub.com"),
            "hubUpdated": feed.get("updated"),
            "retrieved": str(date.today()),
            "meaning": {
                "works": "hub chip=new: the package knows the newer flash chip, so it saves and can update there",
                "bricks": "hub chip=old: the package knows only the first units' chip; on a newer FM-1 it keeps saves in RAM and often refuses updates",
                "unknown": "hub chip=any (or no data): not checked or not tried on the newer chip. Never treated as safe",
            },
        },
        "firmwares": manual + entries,
    }
    OUT.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n")
    counts = {s: sum(1 for e in doc["firmwares"] if e["status"] == s) for s in ("works", "bricks", "unknown")}
    print(f"Wrote {OUT.relative_to(ROOT)}: {counts}")


if __name__ == "__main__":
    main()
