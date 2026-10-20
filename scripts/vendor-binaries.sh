#!/usr/bin/env bash
# Fetch committed-binary stand-ins that GitHub's text file API cannot carry,
# then optionally drop leftover iOS sources from an overwritten fork.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

OCEAN_RAW="https://raw.githubusercontent.com/ocean/FM-SWITCH/main"
FWSC_SHA="db1642b2b6fa5c2cccb11ffd13878068bb28601678d3644049f99dc40e7edb8a"

fetch() {
  local url="$1"
  local dest="$2"
  mkdir -p "$(dirname "$dest")"
  if [[ -f "$dest" ]]; then
    return 0
  fi
  echo "Fetching $dest"
  curl -fsSL "$url" -o "$dest"
}

sha256() {
  sha256sum "$1" | awk '{print $1}'
}

copy_or_fetch_fwsc() {
  local dest="$1"
  mkdir -p "$(dirname "$dest")"
  if [[ -f "$dest" ]]; then
    return 0
  fi
  if [[ -f App/Resources/Firmware/FM-1_015.fwsc ]]; then
    cp App/Resources/Firmware/FM-1_015.fwsc "$dest"
  else
    fetch "$OCEAN_RAW/App/Resources/Firmware/FM-1_015.fwsc" "$dest"
  fi
  local got
  got="$(sha256 "$dest")"
  if [[ "$got" != "$FWSC_SHA" ]]; then
    echo "Firmware SHA-256 mismatch for $dest: $got" >&2
    exit 1
  fi
}

copy_or_fetch_fwsc app/src/main/assets/firmware/FM-1_015.fwsc
copy_or_fetch_fwsc fm1kit/src/test/resources/FM-1_015.fwsc

if [[ -d App/Resources/Fonts ]]; then
  mkdir -p app/src/main/res/font
  [[ -f app/src/main/res/font/vt323_regular.ttf ]] || cp App/Resources/Fonts/VT323-Regular.ttf app/src/main/res/font/vt323_regular.ttf
  [[ -f app/src/main/res/font/silkscreen_bold.ttf ]] || cp App/Resources/Fonts/Silkscreen-Bold.ttf app/src/main/res/font/silkscreen_bold.ttf
  [[ -f app/src/main/res/font/silkscreen_regular.ttf ]] || cp App/Resources/Fonts/Silkscreen-Regular.ttf app/src/main/res/font/silkscreen_regular.ttf
  [[ -f app/src/main/res/font/departuremono_regular.otf ]] || cp App/Resources/Fonts/DepartureMono-Regular.otf app/src/main/res/font/departuremono_regular.otf
else
  fetch "$OCEAN_RAW/App/Resources/Fonts/VT323-Regular.ttf" app/src/main/res/font/vt323_regular.ttf
  fetch "$OCEAN_RAW/App/Resources/Fonts/Silkscreen-Bold.ttf" app/src/main/res/font/silkscreen_bold.ttf
  fetch "$OCEAN_RAW/App/Resources/Fonts/Silkscreen-Regular.ttf" app/src/main/res/font/silkscreen_regular.ttf
  fetch "$OCEAN_RAW/App/Resources/Fonts/DepartureMono-Regular.otf" app/src/main/res/font/departuremono_regular.otf
fi

if [[ ! -f gradle/wrapper/gradle-wrapper.jar ]]; then
  fetch "https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar" \
    gradle/wrapper/gradle-wrapper.jar
fi

mkdir -p app/src/main/assets/licenses
if [[ -f App/Resources/Firmware/FM-1_015-LICENCE.txt ]]; then
  cp App/Resources/Firmware/FM-1_015-LICENCE.txt app/src/main/assets/licenses/FM-1_015-LICENCE.txt
fi
fetch "$OCEAN_RAW/App/Resources/Firmware/FM-1_015-LICENCE.txt" app/src/main/assets/licenses/FM-1_015-LICENCE.txt
fetch "$OCEAN_RAW/App/Resources/Fonts/OFL-DepartureMono.txt" app/src/main/assets/licenses/OFL-DepartureMono.txt
fetch "$OCEAN_RAW/App/Resources/Fonts/OFL-Silkscreen.txt" app/src/main/assets/licenses/OFL-Silkscreen.txt
fetch "$OCEAN_RAW/App/Resources/Fonts/OFL-VT323.txt" app/src/main/assets/licenses/OFL-VT323.txt

if [[ ! -f app/src/main/res/mipmap-mdpi/ic_launcher.png ]]; then
  python3 scripts/make-icon.py
fi

if [[ "${STRIP_IOS:-}" == "1" ]]; then
  echo "Removing leftover iOS sources"
  rm -rf App AppTests
  rm -f .swift-format .swiftlint.yml PLAN.md TODO.md Signing.xcconfig \
    project.local.yml project.yml scripts/check.sh
fi
