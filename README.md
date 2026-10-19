# FM-SWITCH

An app for iPhone, iPad and Mac for swapping M-VAVE FM-1 firmwares over USB. Firmwares come
from the firmware hub's catalogue merged with fm1-editor.com's list, and are saved on the device
for offline flashing. M-VAVE's official V15 comes with the app (from M-VAVE's open-source
[fm1_open](https://github.com/m-vave/fm1_open), Apache-2.0), so it's always there to go back to.
See `PLAN.md` for the research and the phased plan.

## Layout

```
project.yml              XcodeGen spec (the .xcodeproj is generated, not committed)
App/                     SwiftUI app (iOS 17+)
  Theme/                 8-bit look: accent colour, pixel fonts, pixel boxes, optional CRT effects
  Catalogue/             merged firmware list: thefirmwarehub.com feed + fm1-editor.com page,
                         package discovery (GitHub releases, publisher manifests, project pages,
                         M-VAVE's downloads), live checks for newer releases and their builds
  Model/                 settings, catalogue, saved firmwares, flash history, device controller
  Views/                 Device, Firmware (catalogue), Saved, Setup, Flash; the wide layout
                         (sidebar and two panes) for iPad and Mac
  Platform/iOS, macOS/   the few things done differently on each: staying awake, the clipboard
  Resources/Fonts/       Departure Mono, Silkscreen, VT323 (SIL OFL)
  Resources/Firmware/    M-VAVE's FM-1 V15, unmodified, and its Apache-2.0 notice
  Assets.xcassets/       the app icon, made by scripts/make-icon.py
AppTests/                firmware list and flashing tests (Swift Testing)
scripts/check.sh         every check CI runs
scripts/make-icon.py     draws the app icon from its 32 x 32 pixel map (standard and tinted)
```

Talking to the FM-1 (the update protocol, `.fwsc` packages, CoreMIDI and a simulated FM-1)
is [FM1Kit](https://github.com/ocean/FM1Kit), an open-source Swift package. `project.yml`
uses its tagged release from GitHub, taking 0.2.x fixes but not 0.3.0, which may change the API.

### Working on FM1Kit and the app together

To build against a local FM1Kit checkout at `../FM1Kit` (that is, `~/code/FM1Kit`), generate
the project from `project.local.yml`. It's `project.yml` with FM1Kit's source swapped for that
path:

```bash
xcodegen generate --spec project.local.yml   # use ../FM1Kit
FM1KIT=local scripts/check.sh                # run the checks against ../FM1Kit
xcodegen generate                            # back to the tagged release
```

Before pushing app changes that need unreleased FM1Kit changes, tag a new FM1Kit release and
raise `minorVersion` in `project.yml`. CI always uses the release.

## Running it

```bash
xcodegen generate
open FMSwitch.xcodeproj
```

Pick an iPhone or iPad simulator, or **My Mac**, and run. iPad (landscape, or a 13-inch in
either orientation) and the Mac get the wide layout: a sidebar, and lists with the picked item
beside them. The Mac app is native SwiftUI, sandboxed, and talks to the FM-1 over USB MIDI like
the iPhone. In the Simulator the app uses the simulated FM-1 (set under
**SETUP ▸ CONNECTION**). It runs the whole protocol: install, the reboot into the loader, and
the identity check. It can also start in USB rescue mode or "pull the cable" at 45% to test
**RESUME**.

If Xcode says *"CoreSimulator is out of date"*, the Simulator components behind Xcode 27 need
updating. Open Xcode once and let it install them (or run `xcodebuild -runFirstLaunch`), then
reboot if it still complains.

On a real iPhone, set your team under Signing & Capabilities. A free Apple ID works for
personal installs.

## Phase 0 with real hardware

1. **Mac, no risk:** plug the FM-1 into the Mac and run
   `swift run --package-path ../FM1Kit fm1probe ports`, then `... fm1probe identify`.
   It should print something like `FM-1_916`. `fm1probe inspect file.fwsc` shows a package's
   identity and hash.
2. **iPhone, no risk:** on the DEVICE tab, check that the FM-1 appears and reports its
   identity. The handshake is the only thing sent.
3. **iPhone, first flash:** flash a firmware you can easily recover from (e.g. Felucca), with a
   Mac and Chrome installer ready as a fallback. Note the duration. Then deliberately unplug
   mid-write and check that **RESUME** finishes it.
4. Tune FM1Kit's `UpdaterTiming` if the real device wants different pacing.

## Tests and checks

```bash
scripts/check.sh
```

This runs everything CI runs, and it must pass with no warnings. It needs
`brew install xcodegen swiftlint periphery`, and fetches FM1Kit's release (set
`FM1KIT=local` to use `../FM1Kit`). In order, it:

- regenerates the project;
- checks formatting (`swift format`, `.swift-format`);
- lints (`swiftlint --strict`, `.swiftlint.yml`);
- builds with warnings as errors, then runs the tests on the first available iPhone simulator
  (set `SIMULATOR` to choose one);
- does an unsigned iPhone and iPad device build;
- builds the Mac app with warnings as errors and runs the tests on macOS;
- builds the DocC documentation with warnings as errors;
- looks for unused code and imports (`swiftlint analyze` and Periphery).

To run only some of these, name their groups: `lint`, `build`, `test`, `device`, `mac`,
`docs` and `unused`. For example, `scripts/check.sh lint` or `scripts/check.sh build test`.
`test` and `unused` use the results of `build`. CI runs three parallel jobs:
`lint device mac docs`, `build test` and `build unused`.

To fix formatting, run `swift format format --in-place --recursive App AppTests`. Some lint
findings can be fixed with `swiftlint --fix`, but check what it changes: it can rewrite a
closure inside `#require` into a key path, which doesn't compile.

The app's tests cover the firmware list (reading both lists, merging, finding packages,
version ordering and build names) and flashing against the simulated FM-1 (install, resume after
a pulled cable, confirming the same identity and refusing V15 over V15). FM1Kit has its own tests
and checks in its repository.

CI (`.github/workflows/ci.yml`) runs the same checks against FM1Kit's tagged release.

## Staying alive during a flash

Touching the screen doesn't keep an iOS app running. iOS suspends an app when you leave it or
lock the phone, not when you stop touching it. So the app:

- pauses auto-lock for the whole flash;
- asks iOS for the short background grace period if you do leave;
- shows a "keep this screen open" notice;
- relies on the loader's resume when a write is interrupted anyway.
