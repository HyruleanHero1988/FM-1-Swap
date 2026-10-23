Download **FM-1-Swap.apk** from this GitHub release and install it on the phone. It replaces an earlier release of FM-1 Swap already there.

Install that file from this release and nowhere else. If Android refuses the install because a different copy of the app is already on the phone, uninstall that first, then install this file.

Not affiliated with M-VAVE. Flashing custom firmware is at your own risk.

## New in 0.5.0

When a newer release of FM-1 Swap is published, a line under the top bar says so. **UPDATE** downloads it. Android asks you to confirm before it installs. **LATER** hides that line. SETUP still shows **UPDATE**.

The first screen can be scrolled. That is the notice that appears the first time the app opens. On a short phone the text was taller than the display, so **OK** sat off the bottom and you could not reach it. The notice now scrolls, and **OK** stays on the screen.

FIRMWARE now has the same groups as the firmware hub. You can show All, Synths, Grooveboxes, Drums, Chords, Trackers, For devs, Other, Vintage, In browser, or From source.

How it works is on the [guide](https://hyruleanhero1988.github.io/FM-1-Swap/).

## What has been tried on a real FM-1

FM-1 Swap has been used to swap firmwares on a real FM-1, including SLOOP, Felucca Rainbow, and Clipper. Those swaps completed. The pictures here are from a write that started on Clipper, which reported `FM-1_900`. FM-1 Swap wrote Rainbow Mode 1.1.5-rb, and the FM-1 came back reporting `FM-1_911`.

## If a flash goes wrong

Keep the screen open and the cable in until the app says it finished. An install takes about a minute. If the write is interrupted after the update loader has taken over, leave the FM-1 plugged in and press **RESUME** with the same package. The FM-1 waits in that loader until a write finishes.

Some firmwares can be started in USB recovery by holding **OCT−** while switching on, then flashing a saved package. SLOOP and Clipper do this. Melodee does from 1.3.5, ChoralRoot from 0.16. HYP-1, FuMi-1, FiMba-1, LOF-1, ECO-1 and GRAV-1 use **OCT−** and **OCT+** held for 5 seconds while the firmware is already running.

Official V15 and Felucca 1.6.1 do not. Holding OCT− while switching those on starts them normally. There is no recovery gesture.

If the FM-1 will not start at all, not even in USB recovery, FM-1 Swap cannot repair it. A computer that sees a USB device with UBOOT in the name, such as `WL80UBOOT`, is seeing the chip's own download mode. Tools built on [jl-uboot-tool](https://github.com/kagaimiq/jl-uboot-tool) can put the official firmware back. Some firmwares, Melodee, FuMi-1 and Hortator among them, publish one with instructions. If the chip never gets that far, recovery needs an [FM-1 Transporter](https://github.com/kurogedelic/FM-1-transporter). This app has not tried either.

## Pictures

These shots are from a write that started on Clipper. The status line on the firmware list is the FM-1 reporting `FM-1_900` before Rainbow Mode was written.

![Firmware list, with the FM-1 reporting FM-1_900](https://github.com/HyruleanHero1988/FM-1-Swap/releases/download/v0.5.0/firmware-list.jpg)

![The flash finished and the FM-1 reported FM-1_911](https://github.com/HyruleanHero1988/FM-1-Swap/releases/download/v0.5.0/flashed.jpg)

![The safety notice on SETUP, the same text shown the first time the app opens](https://github.com/HyruleanHero1988/FM-1-Swap/releases/download/v0.5.0/before-you-start.jpg)
