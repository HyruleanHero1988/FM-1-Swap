# FM-SWITCH for Android

An Android port of [ocean](https://github.com/ocean)'s [FM-SWITCH](https://github.com/ocean/FM-SWITCH)
for iPhone, iPad, and Mac. Same job: swap M-VAVE FM-1 firmwares over USB MIDI. Firmwares come from
[the firmware hub](https://thefirmwarehub.com/catalog.json) and are saved on the phone for offline
flashing. M-VAVE's official V15 ships with the app (from
[fm1_open](https://github.com/m-vave/fm1_open), Apache-2.0), so it's always there to go back to.

This is a Kotlin / Jetpack Compose rewrite, not a wrapper around the iOS app. Behaviour, protocol,
catalogue, phosphor UI, and bundled V15 follow ocean's original. Use that project if you want the
iPhone, iPad, or Mac build.

Not affiliated with M-VAVE. Flashing custom firmware is at your own risk.

## Download

The debug APK is on [Releases](https://github.com/HyruleanHero1988/FM-SWITCH/releases)
(`FM-SWITCH-debug.apk`). Sideload it on a phone; USB MIDI to the FM-1 does not work on an emulator.

GitHub Actions also attaches `app-debug` on each green [CI run](https://github.com/HyruleanHero1988/FM-SWITCH/actions),
but that zip is easy to miss and is not the repo homepage.

## What it does

- Lists the firmware hub catalogue merged with [fm1-editor.com](https://fm1-editor.com/firmware/). Official V15 is first; the rest are ranked by GitHub stars (and hub installs), so SLOOP, Felucca, X0X, JIANT and Melodee sit at the top instead of drowning alphabetically. Each row shows a representative shot from fm1-editor.com when they have one. Firmwares the hub doesn't carry still show up, including Baud Girl's FM-1+VA, isod89's Clipper 1.0 (from the makers of SLOOP), and M-VAVE Official Open. Their `.fwsc` is found from GitHub releases or the publisher's own page. Clipper is marked experimental, so it sits with the other beta listings (shown unless **SETUP** hides them).
- Downloads `.fwsc` packages, checks SHA-256, and keeps them offline
- Talks to a real FM-1 over class-compliant USB MIDI (Android MIDI API)
- Stages, writes, and resumes interrupted flashes using the same protocol as FM1Kit
- Offers a simulated FM-1 under **SETUP ▸ CONNECTION**, including cable-pull at 45% to test **RESUME**
- Before a flash, automatically dumps the running firmware the same way Baud Girl's Device Manager does: 128 presets and 16 patterns as `.syx` files, plus a copy of that `.fwsc` if this phone already has it. The write does not start if that dump fails. Other firmwares (Felucca, SLOOP, V15, the loader) still ask you to confirm you've backed up, because they have no FM-1+VA librarian.
- Restores those backups later from **SAVED**, and restores V15 factory voices after installing official firmware
- Keeps the 8-bit phosphor look: Silkscreen, Departure Mono, VT323, CRT overlay, colour presets
- System / hardware BACK pops firmware detail, clears search, dismisses dialogs, and returns to DEVICE before leaving the app. It does not abort a write in progress.

## Talking to the FM-1

The FM-1 is flashed entirely over USB-MIDI SysEx, not DFU or a vendor driver. Android's MIDI
service enumerates class-compliant USB-MIDI devices, which is what the FM-1 is. The app does
not claim the USB device itself (that fights the MIDI service and makes the FM-1 appear and
vanish in a loop). Plug it in, grant USB if Android asks, and leave the DEVICE screen: once
the identity sticks, the MIDI port is held open.

The protocol library in `fm1kit/` is a Kotlin port of ocean's [FM1Kit](https://github.com/ocean/FM1Kit)
(MIT), written from the [FM-1-RE](https://github.com/AL-255/FM-1-RE) notes (WTFPL). It contains
no GPL installer code.

## Building

You need JDK 17+ and the Android SDK (compile SDK 35).

```bash
./gradlew :fm1kit:test          # protocol + simulated-device tests (no Android SDK UI)
./gradlew :app:assembleDebug    # APK at app/build/outputs/apk/debug/
```

Open the folder in Android Studio (Ladybug / Koala or later), let Gradle sync, and run on a
phone or emulator.

On the emulator there is no USB MIDI, so use **SETUP ▸ CONNECTION ▸ SIMULATED FM-1**. On a
phone, plug the FM-1 into USB-C (OTG). Grant USB permission if Android asks.

`local.properties` should point at your SDK (copy `local.properties.example`):

```
sdk.dir=/path/to/Android/sdk
```

## GitHub Actions

`.github/workflows/ci.yml` runs on every push and pull request: protocol tests, then
`assembleDebug`. On `main` it publishes `FM-SWITCH-debug.apk` to
[Releases](https://github.com/HyruleanHero1988/FM-SWITCH/releases) and also uploads the
`app-debug` Actions artifact. You can retrigger it from the Actions tab with **Run workflow**.

CI vendors the Gradle wrapper, bundled V15, and fonts (from the original
[FM-SWITCH](https://github.com/ocean/FM-SWITCH) tree) before it runs Gradle, then uploads
`app-debug`. On `main` it also strips leftover iOS sources from a replaced fork and commits
those Android assets. Enable Actions on the GitHub repo if the workflow is waiting for approval.

## First flash on hardware

1. Acknowledge the safety notice.
2. On **DEVICE**, check the FM-1 appears and reports an identity. Only a handshake is sent.
3. Flash something you can recover from (Felucca, or bundled V15), with a computer installer
   ready as a fallback.
4. Unplug mid-write on purpose and confirm **RESUME** finishes it.

If the FM-1 won't start, some firmwares boot USB recovery with **OCT−** held at power-on.
That is a firmware feature, not a ROM one. Official V15 and Felucca 1.6.1 do not offer it.
A bricked chip that shows up as `WL80UBOOT` needs a computer and jl-uboot-tool;
FM-SWITCH cannot help there.

## Layout

```
fm1kit/     Kotlin port of the FM-1 protocol, packages, updater, simulated device
app/        Jetpack Compose UI (API 26+)
  assets/firmware/   bundled M-VAVE V15 + Apache-2.0 notice
  assets/licenses/   font OFL notices
  java/com/fmswitch  screens, catalogue, USB MIDI transport
```

## Credits

This Android app exists because of [ocean](https://github.com/ocean)'s original
[FM-SWITCH](https://github.com/ocean/FM-SWITCH) (MIT) for iOS and macOS, and the
[FM1Kit](https://github.com/ocean/FM1Kit) protocol library that talks to the FM-1.

Also:

- [FM-1-RE](https://github.com/AL-255/FM-1-RE) (WTFPL) for the protocol notes
- [the firmware hub](https://thefirmwarehub.com) catalogue
- [fm1_open](https://github.com/m-vave/fm1_open) (Apache-2.0) for official V15

## Licences

- App and `fm1kit`: MIT
- Original iOS/macOS FM-SWITCH and FM1Kit: MIT, ocean
- Bundled `FM-1_015.fwsc`: Apache-2.0, M-VAVE, unmodified from fm1_open
- Fonts: SIL Open Font License
