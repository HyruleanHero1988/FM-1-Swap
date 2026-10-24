# FM-1 Swap

FM-1 Swap installs firmware on an M-VAVE FM-1 from an Android phone, over USB MIDI.
The firmware list ships in the app (the FM-1 Firmware Hub by zvenson, Benny Sparra's fm1-editor.com, and RedtailWorks, captured when this build was made). REFRESH
pulls a newer copy. Firmwares you save stay on the phone for offline flashing. M-VAVE's official V15 ships with the app (from
[fm1_open](https://github.com/m-vave/fm1_open), Apache-2.0), so it's always there to go back to.

FM-1 Swap is a Kotlin / Jetpack Compose app. It plays a firmware on the phone before a write, keeps a backup when the running firmware can give one, and sends DX7 patches to a connected FM-1. The idea of swapping FM-1 firmware from a phone started with [ocean](https://github.com/ocean)'s [FM-SWITCH](https://github.com/ocean/FM-SWITCH).

Not affiliated with M-VAVE. Flashing custom firmware is at your own risk.

If you found this useful, consider [tipping me on Ko-fi](https://ko-fi.com/hyruleanhero1988).

## Download

How it works, what can go wrong, and the install link are on the site:
[FM-1 Swap](https://hyruleanhero1988.github.io/FM-1-Swap/).
The same site hosts [DX7 patches](https://hyruleanhero1988.github.io/FM-1-Swap/patches.html)
that the app's PATCHES screen, and a desktop browser, can send to a connected FM-1.

Download [FM-1 Swap 0.6.1](https://github.com/HyruleanHero1988/FM-1-Swap/releases/tag/v0.6.1)
(`FM-1-Swap.apk`) from this GitHub release and install it on the phone. It
replaces an earlier release of FM-1 Swap already there. Sideload it on a phone.
USB MIDI to the FM-1 does not work on an emulator.

Install that file from this release and nowhere else. If Android refuses the
install because a different copy of the app is already on the phone, uninstall
that first, then install this file.

## What it does

- Opens on a firmware list baked into the app (no network required to see it). Official V15 is first; the rest are ranked by GitHub stars (and hub installs), so SLOOP, Felucca, X0X, JIANT and Melodee sit at the top instead of drowning alphabetically. Each row shows a shot from the project's GitHub page when the README has one (the first image under Screenshots, otherwise the first picture that isn't a logo), and otherwise from fm1-editor.com or the publisher. The snapshot includes Baud Girl's FM-1+VA, isod89's Clipper 1.0, M-VAVE's Official Open (the eight-track groovebox they ship as [FM1 Open](https://github.com/m-vave/fm1_open) — currently V14, whose `.fwsc` is inside the Workbench HTML), and RedtailWorks' LOF-1, ECO-1 and GRAV-1 (HYP-1 is already on the hub). **REFRESH** re-pulls the hub, fm1-editor.com, and the RedtailWorks pack. Clipper and the experimental RedtailWorks machines are marked beta (shown unless **SETUP** hides them). OPEN-1 is a different synth, not an FM-1 firmware, so it is not listed.
- **FAVORITE** marks a firmware. **SORT** orders the catalogue by **FAVORITES** (marked ones first, in the order you set with EARLIER and LATER), **POPULAR**, **NAME**, **AUTHOR**, or **NEWEST**. **FAVORITES** hides everything you have not marked. Chips under that row filter the same groups as the firmware hub: synths, grooveboxes, drums, chords, trackers, for devs, other, vintage, in browser, and from source. With none marked, the list opens on Baud Girl, SLOOP, Clipper, HYP-1, Felucca, and Rainbow Mode. The marks are stored on the phone.
- Downloads `.fwsc` packages, checks SHA-256, and keeps them offline. A missing file says the firmware list may be out of date and offers **REFRESH**. On TRY, that refresh downloads the file when the listing has moved.
- When a newer release of the app is published, a line under the top bar says so. **UPDATE** downloads that APK and hands it to Android, which asks you to confirm before it installs. **LATER** hides the line for that version. SETUP still offers **UPDATE**.
- After a flash finishes, that firmware's row says when it was last installed, as day-month-year. Forks that report the same device name stay separate.
- **SETUP**, next to the version, has **TIP ON KO-FI**.
- Talks to a real FM-1 over class-compliant USB MIDI (Android MIDI API)
- On **DEVICE**, **PLAY TEST NOTES** sends a C–E–G–C arpeggio on channels 1 and 2, then handshakes again so you can hear whether notes arrive and whether the USB-MIDI port stayed up. Hold C–B to send live notes. Stock firmware listens on channel 2; Felucca on 1–4. Grooveboxes such as SLOOP may stay quiet on a plain note-on — the handshake after is the cable test.
- **TRY** boots the exact `.fwsc` you would flash on a virtual FM-1 built into the app: [Joakim Eriksson's fm1-emu](https://github.com/joakimeriksson/fm1-emu) (MIT), which emulates the FM-1's JieLi AC791N, compiled to native code for Android (`native/fm1-jni`). Sound comes out of the phone, the screen and LEDs are the firmware's own, and the panel is drawn in Compose: tap keys and buttons (multi-touch), drag knobs up or down. By default the panel is traced as glowing vector lines in your phosphor colour, like a scope's electron beam, with afterglow on keys, LEDs and knob sweeps; **SETUP ▸ PHOSPHOR EMULATOR PANEL** switches back to the realistic panel. The firmware's own screen is never altered in either style. What you save on the emulated FM-1 (patches, patterns, settings) is kept per package. TRY runs the emulated chip at 240 MHz, the same speed as a real FM-1. **SETUP ▸ CHOOSE EMULATOR CLOCK** is off until you turn it on; then the TRY screen offers 100, 160 or 240, remembered per firmware, for a phone whose sound gaps or whose load goes over 100%. **SETUP ▸ RUN EMULATOR IN BACKGROUND** is off too. Off, TRY stops when you switch away or the screen turns off. On, it keeps sounding until you leave the TRY screen, which always stops it. Turning the phone does not stop it: the same session moves between the portrait and landscape frames. Sideways, the panel sits against one edge and the controls use the whole leftover margin, clipped so they cannot cover the knobs or the screen. A running TRY does not print its load. Each audio block may run at most 20 ms of emulated time, so a firmware that stalls costs bounded work. A firmware that has made no sound and drawn nothing after 6 emulated seconds is stopped with an explanation. M-VAVE's Official Open is one of these: its open SDK starts the FM-1's second core in a way fm1-emu doesn't model yet. It does play in M-VAVE's own [FM1 Workbench](https://open.m-vave.com/fm1-workbench), which is its synth engine compiled to WebAssembly rather than an emulated chip; that URL is listed on the firmware's page. By default the package TRY downloads goes straight into SAVED, ready to flash, so trying a firmware and then flashing it costs one download (**SETUP ▸ SAVE WHAT YOU TRY**). With that off, TRY keeps it only in a cache Android may clear, and SAVE, on the firmware page and on the emulator, puts it under SAVED. Either way a package already on the phone is never downloaded again, and a saved one plays offline. A USB MIDI keyboard plugged into the phone plays the virtual FM-1 while TRY is open. Every port the keyboard sends on is listened to, and notes are played on channel 2, so a controller left on channel 1 is heard. Official V15 and Felucca both hear channel 2. The FM-1 already open for flashing is not used as that keyboard. RedtailWorks' HYP-1, LOF-1, ECO-1 and GRAV-1 play in this same TRY emulator. Other firmwares whose authors publish a browser emulator (X0X, AMB-1, hub `/play/` pages) list those URLs on the firmware's page. **SETUP ▸ SIMULATED FM-1** remains a protocol dummy for flash tests and makes no sound.
- Before flashing a package TRY has never settled, FM-1 Swap boots it silently at 240 MHz. If that boot fails to start, or if a TRY you already ran stopped, the sheet shows a red warning and CONTINUE stays off until I UNDERSTAND is checked. A later TRY that does start clears the flag. **SKIP CHECK** writes without waiting for that boot and does not record a result for the check it stopped. Cancelling the sheet does not record a result either, and the probe does not touch the save a real TRY keeps. **FLASH** on a firmware page saves the package first when it is not already under SAVED, then opens that sheet. SAVE and FLASH do not open TRY.
- Stages, writes, and resumes interrupted flashes using the same protocol as FM1Kit
- Before a flash, automatically dumps the running firmware when it knows how. FM-1+VA (Baud Girl's range, `FM-1_079` through `FM-1_799`) is saved the same way Baud Girl's Device Manager does: 128 presets and 16 patterns as `.syx` files. Felucca, SLOOP, and anything that still answers their editor backup — Felucca commands 65–67, or SLOOP commands 34–36 on the same FL SysEx header — is saved as the same full backup those web editors write (projects, presets, settings, samples; SLOOP includes the FM6 bank, Felucca's own backup leaves that voice bank out). A copy of the `.fwsc` is kept too when this phone already has it. The write does not start if a dump that the device started then fails. Official V15, the loader, and firmware that never answers those requests still ask you to confirm you've backed up.
- Restores those backups later from **SAVED**, and restores V15 factory voices after installing official firmware
- Keeps the 8-bit phosphor look: Silkscreen, Departure Mono, VT323, CRT overlay, colour presets
- System / hardware BACK closes the emulator, pops firmware detail, clears search, dismisses dialogs, and returns to DEVICE before leaving the app. It does not abort a write in progress.

## Talking to the FM-1

The FM-1 is flashed entirely over USB-MIDI SysEx, not DFU or a vendor driver. Android's MIDI
service enumerates class-compliant USB-MIDI devices, which is what the FM-1 is. `MainActivity`
registers for `USB_DEVICE_ATTACHED` with `res/xml/device_filter.xml` (USB class 1, subclass 3).
That is the system prompt offering to open the app when an FM-1 is plugged in, including while
the app is closed. Leave the `UsbDevice` on that intent closed. `UsbManager.openDevice` or
`requestPermission` takes the port from the MIDI service, the FM-1 drops, and it enumerates
again, which brings the prompt back. Plug it in, accept the prompt if Android shows it, and
leave the DEVICE screen: once the identity sticks, the MIDI port is held open.

The protocol library in `fm1kit/` speaks the FM-1's USB MIDI protocol, following [ocean's FM1Kit](https://github.com/ocean/FM1Kit)
(MIT) and the [FM-1-RE](https://github.com/AL-255/FM-1-RE) notes (WTFPL). It contains
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
runs its tests and the protocol tests, then `assembleDebug` and `assembleRelease`. On `main` it publishes `FM-1-Swap-debug.apk` to the
[android-debug](https://github.com/HyruleanHero1988/FM-1-Swap/releases/tag/android-debug) tag and `FM-1-Swap.apk` (versionName 0.6.1) to
[v0.6.1](https://github.com/HyruleanHero1988/FM-1-Swap/releases/tag/v0.6.1). It also uploads the
`app-debug` Actions artifact. You can retrigger it from the Actions tab with **Run workflow**.

CI vendors the Gradle wrapper, bundled V15, and fonts (fetched from
[ocean's FM-SWITCH](https://github.com/ocean/FM-SWITCH), where the idea started) before it runs Gradle, then uploads
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
FM-1 Swap cannot help there.

## Layout

```
fm1kit/     FM-1 protocol, packages, updater, simulated device
app/        Jetpack Compose UI (API 26+)
  assets/firmware/   bundled M-VAVE V15 + Apache-2.0 notice
  assets/licenses/   font OFL, fm1-emu MIT, and patch-bank notices
  java/com/fmswitch  screens, catalogue, USB MIDI transport, emu/ (TRY's engine and panel)
native/fm1-jni/      JNI wrapper around fm1-emu's machine (Rust), built into libfm1emu.so
```

## Credits

FM-1 Swap is for the M-VAVE FM-1. Not affiliated with M-VAVE.

The firmware list is assembled from [the FM-1 Firmware Hub](https://thefirmwarehub.com) by zvenson, [Benny Sparra's fm1-editor.com](https://fm1-editor.com/firmware/), and [RedtailWorks](https://rustypawz.github.io/fm1-HYP1/) (HYP-1, LOF-1, ECO-1, and GRAV-1). Each firmware is its author's. A row names them and links their page. Pictures come from that project's own page, from the hub, or from fm1-editor.com.

Patch banks 1–4 are the FM-1's own presets, captured by [KingParamount](https://github.com/KingParamount/fm1-factory-presets) and shared as DX7 banks under CC0. The voices come from Yamaha ROM and VRC cartridges and from BlackWinny's Dexed_cart 1.0 collection, as M-VAVE selected and renamed them. [Aquanode's](https://github.com/aquanodemusic/aquanode-soundscapes) banks are CC0, from aquanode-soundscapes.

The idea of swapping FM-1 firmware from a phone started with [ocean](https://github.com/ocean)'s [FM-SWITCH](https://github.com/ocean/FM-SWITCH) (MIT). The protocol in `fm1kit/` follows ocean's [FM1Kit](https://github.com/ocean/FM1Kit) (MIT) and the [FM-1-RE](https://github.com/AL-255/FM-1-RE) notes (WTFPL). TRY runs [Joakim Eriksson's fm1-emu](https://github.com/joakimeriksson/fm1-emu) (MIT).

Type in the app is Silkscreen (the [Silkscreen Project](https://github.com/googlefonts/silkscreen)), [Departure Mono by Helena Zhang](https://helenazhang.com), and [VT323 by Peter Hull](https://fonts.google.com/specimen/VT323). All three are under the SIL Open Font License.

## Licences

- App and `fm1kit`: MIT
- ocean's FM-SWITCH and FM1Kit: MIT, ocean
- Bundled `FM-1_015.fwsc`: Apache-2.0, M-VAVE, unmodified from [fm1_open](https://github.com/m-vave/fm1_open)
- FM-1 factory banks: the SysEx capture is CC0, KingParamount. The voices are Yamaha's and the Dexed_cart collection's, as M-VAVE selected them. See `app/src/main/assets/licenses/PATCHES.txt`
- Aquanode banks: CC0, Aquanode, from aquanode-soundscapes
- Fonts: SIL Open Font License
- fm1-emu (compiled into `libfm1emu.so`): MIT, Joakim Eriksson
