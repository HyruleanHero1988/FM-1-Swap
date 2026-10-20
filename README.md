# FM-SWITCH for Android

An Android port of [ocean](https://github.com/ocean)'s [FM-SWITCH](https://github.com/ocean/FM-SWITCH).
Same job: swap M-VAVE FM-1 firmwares over USB MIDI. The firmware list ships in the
app (hub, fm1-editor.com, and RedtailWorks, captured when this build was made). REFRESH
pulls a newer copy. Firmwares you save stay on the phone for offline flashing. M-VAVE's official V15 ships with the app (from
[fm1_open](https://github.com/m-vave/fm1_open), Apache-2.0), so it's always there to go back to.

This is a Kotlin / Jetpack Compose rewrite, not a wrapper around ocean's original. Behaviour, protocol,
catalogue, phosphor UI, and bundled V15 follow that project.

Not affiliated with M-VAVE. Flashing custom firmware is at your own risk.

## Download

The debug APK is on [Releases](https://github.com/HyruleanHero1988/FM-SWITCH/releases)
(`FM-SWITCH-debug.apk`). Sideload it on a phone; USB MIDI to the FM-1 does not work on an emulator.

Tapping a new APK used to fail with **App not installed** because every CI run signed with a throwaway debug key and `versionCode` never left 1. Builds now share one committed debug key and a rising versionCode. **Uninstall FM-SWITCH once**, then install this APK; after that, the next download updates in place. GitHub Actions also attaches `app-debug` on each green [CI run](https://github.com/HyruleanHero1988/FM-SWITCH/actions).

## What it does

- Opens on a firmware list baked into the app (no network required to see it). Official V15 is first; the rest are ranked by GitHub stars (and hub installs), so SLOOP, Felucca, X0X, JIANT and Melodee sit at the top instead of drowning alphabetically. Each row shows a shot from the project's GitHub page when the README has one (the first image under Screenshots, otherwise the first picture that isn't a logo), and otherwise from fm1-editor.com or the publisher. The snapshot includes Baud Girl's FM-1+VA, isod89's Clipper 1.0, M-VAVE's Official Open (the eight-track groovebox they ship as [FM1 Open](https://github.com/m-vave/fm1_open) — currently V14, whose `.fwsc` is inside the Workbench HTML), and RedtailWorks' LOF-1, ECO-1 and GRAV-1 (HYP-1 is already on the hub). **REFRESH** re-pulls the hub, fm1-editor.com, and the RedtailWorks pack. Clipper and the experimental RedtailWorks machines are marked beta (shown unless **SETUP** hides them). OPEN-1 is a different synth, not an FM-1 firmware, so it is not listed.
- **KEEP** on a firmware adds it to your own list. **SORT** orders the catalogue by **MY CURATION** (that list first, in the order you set with EARLIER and LATER), **POPULAR**, **NAME**, **AUTHOR**, or **NEWEST**. **CURATED** hides everything you have not kept. The list is stored on the phone.
- Downloads `.fwsc` packages, checks SHA-256, and keeps them offline
- Talks to a real FM-1 over class-compliant USB MIDI (Android MIDI API)
- On **DEVICE**, **PLAY TEST NOTES** sends a C–E–G–C arpeggio on channels 1 and 2, then handshakes again so you can hear whether notes arrive and whether the USB-MIDI port stayed up. Hold C–B to send live notes. Stock firmware listens on channel 2; Felucca on 1–4. Grooveboxes such as SLOOP may stay quiet on a plain note-on — the handshake after is the cable test.
- **TRY** boots the exact `.fwsc` you would flash on a virtual FM-1 built into the app: [Joakim Eriksson's fm1-emu](https://github.com/joakimeriksson/fm1-emu) (MIT), which emulates the FM-1's JieLi AC791N, compiled to native code for Android (`native/fm1-jni`). Sound comes out of the phone, the screen and LEDs are the firmware's own, and the panel is drawn in Compose: tap keys and buttons (multi-touch), drag knobs up or down. By default the panel is traced as glowing vector lines in your phosphor colour, like a scope's electron beam, with afterglow on keys, LEDs and knob sweeps; **SETUP ▸ PHOSPHOR EMULATOR PANEL** switches back to the realistic panel. The firmware's own screen is never altered in either style. What you save on the emulated FM-1 (patches, patterns, settings) is kept per package. TRY runs the emulated chip at 240 MHz, the same speed as a real FM-1. **SETUP ▸ CHOOSE EMULATOR CLOCK** is off until you turn it on; then the TRY screen offers 100, 160 or 240, remembered per firmware, for a phone whose sound gaps or whose load goes over 100%. **SETUP ▸ RUN EMULATOR IN BACKGROUND** is off too. Off, TRY stops when you switch away or the screen turns off. On, it keeps sounding until you leave the TRY screen, which always stops it. Turning the phone does not stop it: the same session moves between the portrait and landscape frames. Each audio block may run at most 20 ms of emulated time, so a firmware that stalls costs bounded work. A firmware that has made no sound and drawn nothing after 6 emulated seconds is stopped with an explanation. M-VAVE's Official Open is one of these: its open SDK starts the FM-1's second core in a way fm1-emu doesn't model yet. It does play in M-VAVE's own [FM1 Workbench](https://open.m-vave.com/fm1-workbench), which is its synth engine compiled to WebAssembly rather than an emulated chip; its TRY screen opens that with **AUTHOR'S EMU**. By default the package TRY downloads goes straight into SAVED, ready to flash, so trying a firmware and then flashing it costs one download (**SETUP ▸ SAVE WHAT YOU TRY**). With that off, TRY keeps it only in a cache Android may clear, and SAVE, on the firmware page and on the emulator, puts it under SAVED. Either way a package already on the phone is never downloaded again, and a saved one plays offline. RedtailWorks' HYP-1, LOF-1, ECO-1 and GRAV-1 play in this same TRY emulator. Other firmwares whose authors publish a browser emulator (X0X, AMB-1, hub `/play/` pages) still get an **AUTHOR'S EMU** button. **SETUP ▸ SIMULATED FM-1** remains a protocol dummy for flash tests and makes no sound.
- Stages, writes, and resumes interrupted flashes using the same protocol as FM1Kit
- Before a flash, automatically dumps the running firmware the same way Baud Girl's Device Manager does: 128 presets and 16 patterns as `.syx` files, plus a copy of that `.fwsc` if this phone already has it. The write does not start if that dump fails. Other firmwares (Felucca, SLOOP, V15, the loader) still ask you to confirm you've backed up, because they have no FM-1+VA librarian.
- Restores those backups later from **SAVED**, and restores V15 factory voices after installing official firmware
- Keeps the 8-bit phosphor look: Silkscreen, Departure Mono, VT323, CRT overlay, colour presets
- System / hardware BACK closes the emulator, pops firmware detail, clears search, dismisses dialogs, and returns to DEVICE before leaving the app. It does not abort a write in progress.

## Talking to the FM-1

The FM-1 is flashed entirely over USB-MIDI SysEx, not DFU or a vendor driver. Android's MIDI
service enumerates class-compliant USB-MIDI devices, which is what the FM-1 is. The app does
not claim the USB device itself (that fights the MIDI service and makes the FM-1 appear and
vanish in a loop). Plug it in, grant USB if Android asks, and leave the DEVICE screen: once
the identity sticks, the MIDI port is held open.

The protocol library in `fm1kit/` is a Kotlin port of ocean's [FM1Kit](https://github.com/ocean/FM1Kit)
(MIT), written from the [FM-1-RE](https://github.com/AL-255/FM-1-RE) notes (WTFPL). It contains
no GPL installer code.

Once the identity sticks, **DEVICE ▸ PLAY TEST NOTES** is the check that USB MIDI is
actually carrying channel-voice messages, not only SysEx handshakes.

## Building

You need JDK 17+ and the Android SDK (compile SDK 35). TRY's emulator also needs Rust
(1.87 or newer) with the Android targets, and the Android NDK:

```bash
rustup target add aarch64-linux-android x86_64-linux-android
scripts/build-fm1-native.sh     # fm1-emu at a pinned commit -> app/src/main/jniLibs/*/libfm1emu.so
./gradlew :fm1kit:test          # protocol + simulated-device tests (no Android SDK UI)
./gradlew :app:assembleDebug    # APK at app/build/outputs/apk/debug/
BAKE_CATALOGUE=true ./gradlew :app:testDebugUnitTest --tests com.fmswitch.catalogue.BakeCatalogue
                                # rebuild the shipped firmware list from the live hub
```

Open the folder in Android Studio (Ladybug / Koala or later), let Gradle sync, and run on a
phone or emulator.

On the Android emulator there is no USB MIDI, so use **SETUP ▸ CONNECTION ▸ SIMULATED FM-1**
for flashing tests, or **FIRMWARE ▸ TRY** to play a firmware in the virtual device. On a
phone, plug the FM-1 into USB-C (OTG). Grant USB permission if Android asks.

`local.properties` should point at your SDK (copy `local.properties.example`):

```
sdk.dir=/path/to/Android/sdk
```

## GitHub Actions

`.github/workflows/ci.yml` runs on every push and pull request: it builds the native emulator,
runs its tests and the protocol tests, then `assembleDebug`. On `main` it publishes `FM-SWITCH-debug.apk` to
[Releases](https://github.com/HyruleanHero1988/FM-SWITCH/releases) and also uploads the
`app-debug` Actions artifact. You can retrigger it from the Actions tab with **Run workflow**.

CI vendors the Gradle wrapper, bundled V15, and fonts (from the original
[FM-SWITCH](https://github.com/ocean/FM-SWITCH) tree) before it runs Gradle, then uploads
`app-debug`. The firmware list is the committed snapshot at
`app/src/main/assets/catalogue/snapshot.json`; CI only rebakes it for that job if the
file is missing. Enable Actions on the GitHub repo if the workflow is waiting for approval.

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
  assets/licenses/   font OFL and fm1-emu MIT notices
  java/com/fmswitch  screens, catalogue, USB MIDI transport, emu/ (TRY's engine and panel)
native/fm1-jni/      JNI wrapper around fm1-emu's machine (Rust), built into libfm1emu.so
```

## Credits

This Android app exists because of [ocean](https://github.com/ocean)'s original
[FM-SWITCH](https://github.com/ocean/FM-SWITCH) (MIT), and the
[FM1Kit](https://github.com/ocean/FM1Kit) protocol library that talks to the FM-1.

Also:

- [FM-1-RE](https://github.com/AL-255/FM-1-RE) (WTFPL) for the protocol notes
- [the firmware hub](https://thefirmwarehub.com) catalogue
- [fm1-editor.com](https://fm1-editor.com/firmware/) listings and screenshots
- [RedtailWorks](https://rustypawz.github.io/fm1-HYP1/) for HYP-1, LOF-1, ECO-1 and GRAV-1
- [Joakim Eriksson / fm1-emu](https://github.com/joakimeriksson/fm1-emu) for the FM-1 emulator that **TRY** runs natively (MIT)
- [fm1_open](https://github.com/m-vave/fm1_open) (Apache-2.0) for official V15

## Licences

- App and `fm1kit`: MIT
- Original FM-SWITCH and FM1Kit: MIT, ocean
- Bundled `FM-1_015.fwsc`: Apache-2.0, M-VAVE, unmodified from fm1_open
- Fonts: SIL Open Font License
- fm1-emu (compiled into `libfm1emu.so`): MIT, Joakim Eriksson
