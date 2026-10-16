#!/usr/bin/env bash
# Runs every check CI runs: formatting, lint, a warnings-as-errors build, tests, a device build,
# documentation and unused-code analysis. Needs Xcode, XcodeGen, SwiftLint and Periphery
# (`brew install xcodegen swiftlint periphery`), and FM1Kit checked out at ../FM1Kit.
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

simulator="${SIMULATOR:-$(xcrun simctl list devices available | grep -m1 -oE 'iPhone[^(]*' | sed 's/ *$//')}"
if [[ -z "$simulator" ]]; then
    echo "No iPhone simulator is installed. Add one in Xcode, or set SIMULATOR."
    exit 1
fi
destination="platform=iOS Simulator,name=$simulator"
mkdir -p .build/logs

step "Project (XcodeGen)"
xcodegen generate --quiet

step "Formatting (swift format)"
swift format lint --strict --recursive App AppTests

step "Lint (SwiftLint)"
swiftlint lint --strict --quiet

step "Build with warnings as errors ($simulator)"
xcodebuild clean build-for-testing -project FMSwitch.xcodeproj -scheme FMSwitch -destination "$destination" \
    -derivedDataPath .build/xcode COMPILER_INDEX_STORE_ENABLE=YES \
    SWIFT_TREAT_WARNINGS_AS_ERRORS=YES GCC_TREAT_WARNINGS_AS_ERRORS=YES \
    > .build/logs/xcodebuild.log 2>&1 || fail_with_log .build/logs/xcodebuild.log

step "Tests"
xcodebuild test-without-building -project FMSwitch.xcodeproj -scheme FMSwitch -destination "$destination" \
    -derivedDataPath .build/xcode -quiet

step "Device build (unsigned)"
xcodebuild build -project FMSwitch.xcodeproj -scheme FMSwitch -destination 'generic/platform=iOS' \
    -derivedDataPath .build/device CODE_SIGNING_ALLOWED=NO SWIFT_TREAT_WARNINGS_AS_ERRORS=YES -quiet

step "Documentation (DocC, warnings as errors)"
xcodebuild docbuild -project FMSwitch.xcodeproj -scheme FMSwitch -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath .build/docs OTHER_DOCC_FLAGS="--warnings-as-errors" -quiet

step "Unused code (SwiftLint analyze, Periphery)"
swiftlint analyze --strict --quiet --compiler-log-path .build/logs/xcodebuild.log
# FM1Kit is in the same index but has its own Periphery run, so only the app's files are reported.
periphery scan --project FMSwitch.xcodeproj --schemes FMSwitch --strict --quiet --skip-build \
    --index-store-path .build/xcode/Index.noindex/DataStore \
    --report-include 'App/**/*.swift' --report-include 'AppTests/**/*.swift'

printf '\nAll checks passed.\n'
