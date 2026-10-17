#!/usr/bin/env bash
# Runs every check CI runs: formatting, lint, a warnings-as-errors build, tests, a device build,
# documentation and unused-code analysis. Needs Xcode, XcodeGen, SwiftLint and Periphery
# (`brew install xcodegen swiftlint periphery`).
#
# FM1Kit comes from its tagged release on GitHub. Set FM1KIT=local to build against a checkout
# at ../FM1Kit instead (project.local.yml).
#
# With no arguments it runs every group. Name groups to run only those, in the order given:
#   lint    formatting and SwiftLint
#   build   the warnings-as-errors simulator build that `test` and `unused` use
#   test    the tests, on the simulator build
#   device  an unsigned device build
#   docs    the DocC documentation, with warnings as errors
#   unused  SwiftLint analyze and Periphery, on the simulator build
# CI runs them as parallel jobs: `lint device docs`, `build test` and `build unused`.
#
# Tests run on the first available iPhone simulator; set SIMULATOR to pick one by name.
set -euo pipefail
cd "$(dirname "$0")/.."

step() { printf '\n== %s\n' "$1"; }

# Shows the end of a log that xcodebuild wrote, then fails.
fail_with_log() {
    tail -40 "$1"
    printf '\nFull log: %s\n' "$1"
    exit 1
}

simulator_destination() {
    local simulator="${SIMULATOR:-$(xcrun simctl list devices available | grep -m1 -oE 'iPhone[^(]*' | sed 's/ *$//')}"
    if [[ -z "$simulator" ]]; then
        echo "No iPhone simulator is installed. Add one in Xcode, or set SIMULATOR." >&2
        exit 1
    fi
    echo "platform=iOS Simulator,name=$simulator"
}

# The simulator build's results, which `test` and `unused` need.
require_build() {
    if [[ ! -f .build/logs/xcodebuild.log || ! -d .build/xcode/Index.noindex/DataStore ]]; then
        echo "Run the build group first: scripts/check.sh build $1" >&2
        exit 1
    fi
}

check_lint() {
    step "Formatting (swift format)"
    swift format lint --strict --recursive App AppTests

    step "Lint (SwiftLint)"
    swiftlint lint --strict --quiet
}

check_build() {
    local destination
    destination="$(simulator_destination)"
    step "Build with warnings as errors (${destination##*name=})"
    xcodebuild clean build-for-testing -project FMSwitch.xcodeproj -scheme FMSwitch -destination "$destination" \
        -derivedDataPath .build/xcode COMPILER_INDEX_STORE_ENABLE=YES \
        FMSWITCH_STRICT=YES \
        > .build/logs/xcodebuild.log 2>&1 || fail_with_log .build/logs/xcodebuild.log
}

check_test() {
    require_build test
    step "Tests"
    xcodebuild test-without-building -project FMSwitch.xcodeproj -scheme FMSwitch \
        -destination "$(simulator_destination)" -derivedDataPath .build/xcode -quiet
}

check_device() {
    step "Device build (unsigned)"
    xcodebuild build -project FMSwitch.xcodeproj -scheme FMSwitch -destination 'generic/platform=iOS' \
        -derivedDataPath .build/device CODE_SIGNING_ALLOWED=NO FMSWITCH_STRICT=YES -quiet
}

check_docs() {
    step "Documentation (DocC, warnings as errors)"
    xcodebuild docbuild -project FMSwitch.xcodeproj -scheme FMSwitch -destination 'generic/platform=iOS Simulator' \
        -derivedDataPath .build/docs OTHER_DOCC_FLAGS="--warnings-as-errors" -quiet
}

check_unused() {
    require_build unused
    step "Unused code (SwiftLint analyze, Periphery)"
    swiftlint analyze --strict --quiet --compiler-log-path .build/logs/xcodebuild.log
    # FM1Kit is in the same index but has its own Periphery run, so only the app's files are reported.
    periphery scan --project FMSwitch.xcodeproj --schemes FMSwitch --strict --quiet --skip-build \
        --index-store-path .build/xcode/Index.noindex/DataStore \
        --report-include 'App/**/*.swift' --report-include 'AppTests/**/*.swift'
}

groups=("$@")
if [[ ${#groups[@]} -eq 0 ]]; then
    groups=(lint build test device docs unused)
fi
for group in "${groups[@]}"; do
    case "$group" in
        lint | build | test | device | docs | unused) ;;
        *)
            echo "Unknown check group: $group (use lint, build, test, device, docs or unused)." >&2
            exit 1
            ;;
    esac
done

case "${FM1KIT:-release}" in
    release) spec=project.yml ;;
    local) spec=project.local.yml ;;
    *)
        echo "FM1KIT must be release or local, not ${FM1KIT}." >&2
        exit 1
        ;;
esac

mkdir -p .build/logs
step "Project (XcodeGen, FM1Kit from ${spec})"
xcodegen generate --quiet --spec "$spec"

for group in "${groups[@]}"; do
    "check_$group"
done

printf '\nChecks passed: %s.\n' "${groups[*]}"
