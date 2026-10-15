# FM-SWITCH

An iOS app for swapping M-VAVE FM-1 firmwares from a phone over USB-C. Firmwares come from the
firmware hub's catalogue merged with fm1-editor.com's list, and are saved on the phone for
offline flashing. The official firmware is never bundled: the user downloads it from M-VAVE's
site (or imports it), and it is checked against the known hash. See `PLAN.md` for the research and the phased plan.

## Layout

```
project.yml              XcodeGen spec (the .xcodeproj is generated, not committed)
App/                     SwiftUI app (iOS 17+)
  Theme/                 8-bit look: accent colour, pixel fonts, pixel boxes, optional CRT effects
  Catalogue/             merged firmware list: thefirmwarehub.com feed + fm1-editor.com page,
                         package discovery (GitHub releases, publisher manifests, project pages,
                         M-VAVE's downloads), live checks for newer releases and their builds
  Model/                 settings, catalogue, saved firmwares, flash history, device controller
  Views/                 Device, Firmware (catalogue), Saved, Setup, Flash
  Resources/Fonts/       Departure Mono, Silkscreen, VT323 (SIL OFL)
AppTests/                firmware list tests (Swift Testing)
```

Talking to the FM-1 (the update protocol, `.fwsc` packages, CoreMIDI and a simulated FM-1)
is [FM1Kit](https://github.com/ocean/FM1Kit), an open-source Swift package. `project.yml`
uses a local checkout at `../FM1Kit` (that is, `~/code/FM1Kit`); switch it to the GitHub URL and a version once
it's published.

## Running it

```bash
xcodegen generate
open FMSwitch.xcodeproj
```

Pick an iPhone simulator and run. In the Simulator the app uses the simulated FM-1 (set under
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

## Tests

```bash
xcodebuild test -project FMSwitch.xcodeproj -scheme FMSwitch -destination 'platform=iOS Simulator,name=iPhone 18 Pro'
```

The app's tests cover the firmware list: reading both lists, merging, finding packages,
version ordering and build names. FM1Kit has its own tests (`swift test` in its repository).

## Staying alive during a flash

Touching the screen doesn't keep an iOS app running. iOS suspends an app when you leave it or
lock the phone, not when you stop touching it. So the app:

- pauses auto-lock for the whole flash;
- asks iOS for the short background grace period if you do leave;
- shows a "keep this screen open" notice;
- relies on the loader's resume when a write is interrupted anyway.
