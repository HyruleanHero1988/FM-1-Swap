Install **FM-1-Swap.apk** from this release. It is the release build, package `com.fmswitch`, and it is not debuggable. It installs over the copy already on the phone. Do not install the debug APK on the `android-debug` tag, and do not install an APK from anywhere else. The signing key lives in this repository so that a later file from this same release can install over this one. Anyone else with that key could sign an update Android would accept.

If you already installed a debug build, uninstall it first. Its package is `com.fmswitch.debug`, and it will not update into this one.

Not affiliated with M-VAVE. Flashing custom firmware is at your own risk.

The PATCHES screen sends a hosted DX7 patch, or a bank of 32, to a connected FM-1. Official V15, FM-1+VA, and Official Open take a single patch. A bank replaces 32 patches, and you choose that bank on the FM-1. The same banks are on the [patches page](https://hyruleanhero1988.github.io/FM-1-Swap/patches.html).

## What has been tried on a real FM-1

FM-1 Swap has been used to swap firmwares on a real FM-1, including SLOOP, Felucca Rainbow, and Clipper. Those swaps completed. The pictures here are from a write that started on Clipper, which reported `FM-1_900`. FM-1 Swap wrote Rainbow Mode 1.1.5-rb, and the FM-1 came back reporting `FM-1_911`.

## If a flash goes wrong

Keep the screen open and the cable in until the app says it finished. An install takes about a minute. If the write is interrupted after the update loader has taken over, leave the FM-1 plugged in and press **RESUME** with the same package. The FM-1 waits in that loader until a write finishes.

Some firmwares can be started in USB recovery by holding **OCT−** while switching on, then flashing a saved package. SLOOP and Clipper do this. Melodee does from 1.3.5, ChoralRoot from 0.16. HYP-1, FuMi-1, FiMba-1, LOF-1, ECO-1 and GRAV-1 use **OCT−** and **OCT+** held for 5 seconds while the firmware is already running.

Official V15 and Felucca 1.6.1 do not. Holding OCT− while switching those on starts them normally. There is no recovery gesture.

If the FM-1 will not start at all, not even in USB recovery, FM-1 Swap cannot repair it. A computer that sees a USB device with UBOOT in the name, such as `WL80UBOOT`, is seeing the chip's own download mode. Tools built on [jl-uboot-tool](https://github.com/kagaimiq/jl-uboot-tool) can put the official firmware back. Some firmwares, Melodee, FuMi-1 and Hortator among them, publish one with instructions. If the chip never gets that far, recovery needs an [FM-1 Transporter](https://github.com/kurogedelic/FM-1-transporter). This app has not tried either.

## Pictures

These shots are from a write that started on Clipper. The status line on the firmware list is the FM-1 reporting `FM-1_900` before Rainbow Mode was written.

![Firmware list, with the FM-1 reporting FM-1_900](https://github.com/HyruleanHero1988/FM-1-Swap/releases/download/v0.2.0/firmware-list.jpg)

![The flash finished and the FM-1 reported FM-1_911](https://github.com/HyruleanHero1988/FM-1-Swap/releases/download/v0.2.0/flashed.jpg)

![The safety notice on SETUP, the same text shown the first time the app opens](https://github.com/HyruleanHero1988/FM-1-Swap/releases/download/v0.2.0/before-you-start.jpg)
