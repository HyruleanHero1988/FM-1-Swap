#!/usr/bin/env bash
# Rebuild app/src/main/assets/catalogue/snapshot.json from the live hub,
# fm1-editor.com, and the RedtailWorks pack.
set -euo pipefail
cd "$(dirname "$0")/.."
BAKE_CATALOGUE=true ./gradlew :app:testDebugUnitTest --tests com.fmswitch.catalogue.BakeCatalogue
