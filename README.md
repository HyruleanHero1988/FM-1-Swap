# FM-SWITCH for Android

An Android port of [FM-SWITCH](https://github.com/HyruleanHero1988/FM-SWITCH): swap M-VAVE FM-1
firmwares over USB MIDI. Firmwares come from
[the firmware hub](https://thefirmwarehub.com/catalog.json) and are saved on the phone for offline
flashing. M-VAVE's official V15 ships with the app (from
[fm1_open](https://github.com/m-vave/fm1_open), Apache-2.0), so it's always there to go back to.

Not affiliated with M-VAVE. Flashing custom firmware is at your own risk.

## What it does

- Lists the firmware hub catalogue, with official V15 first
- Downloads `.fwsc` packages, checks SHA-256, and keeps them offline
- Talks to a real FM-1 over class-compliant USB MIDI (Android MIDI API)
- Stages, writes, and resumes interrupted flashes using the same protocol as FM1Kit
- Offers a simulated FM-1 under **SETUP ▸ CONNECTION**, including cable-pull at 45% to test **RESUME**
- Restores V15 factory voices after installing official firmware
- Keeps the 8-bit phosphor look: Silkscreen, Departure Mono, VT323, CRT overlay, colour presets

## Talking to the FM-1

The FM-1 is flashed entirely over USB-MIDI SysEx, not DFU or a vendor driver. Android's MIDI
service enumerates class-compliant USB-MIDI devices, which is what the FM-1 is.

The protocol library in `fm1kit/` is a Kotlin port of [FM1Kit](https://github.com/ocean/FM1Kit)
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
`assembleDebug`. The debug APK is uploaded as the `app-debug` artifact (Actions → the run →
Artifacts). You can also trigger it from the Actions tab with **Run workflow**.

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

## Licences

- App and `fm1kit`: MIT
- Bundled `FM-1_015.fwsc`: Apache-2.0, M-VAVE, unmodified from fm1_open
- Fonts: SIL Open Font License
