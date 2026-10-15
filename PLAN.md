# FM-SWITCH (iOS FM-1 firmware switcher): viability and plan

Research date: 2026-10-15.

## Verdict: viable

The FM-1 is flashed entirely over **class-compliant USB-MIDI SysEx**, not DFU, WebUSB or a
vendor USB driver. That matters because iOS apps cannot talk to arbitrary USB devices, but
CoreMIDI works with any class-compliant USB-MIDI device on an iPhone over USB-C (or Lightning
with the Camera Adapter). Everything the browser installers do with Web MIDI can be done with
CoreMIDI. Safari has no Web MIDI, which is why none of the existing web installers work on a
phone. A native app fills that gap.

## How flashing works (from Felucca `web/fm1ota.js`, FM-1-RE `docs/io/11-ota-protocol.md`)

- Device: JieLi AC791N. Normal mode enumerates as "FM-1 Midi" (`4c4a:c755`). The update loader
  enumerates as `ota-FM-1` (`4d4a:4155`), with the same MIDI port name.
- **Device-driven ("the device asks, we answer").** The host never pushes data. The device
  sends read requests, each giving an address and length (≤ 512 bytes), and the host replies
  with that slice of the image.
- Handshake: send `F0 00 32 45 00 00 00 40 7F F7`. The reply unpacks to 34 bytes giving the
  identity text `MODEL_VERSION`, e.g. `FM-1_916`, or `ota-FM-1_…` when the loader is running.
- Upgrade command: `F0 22 24 35 7F F7`.
- Request and response body: `00 59 30 | len:u24le | flash_type | addr:u32le | req_len:u24le |
  data | checksum`, where checksum = `~sum(flash_type..data) & 0xFF`. The whole message is
  7-bit packed LSB-first between `F0` and `F7`.
- Flow:
  1. Find the port and handshake.
  2. Send UPGRADE and wait 2 s. Serve requests (about 74) until the device asks for
     `0xE0000000`, then reply `"success\0"`. This step is the **verify/stage** phase:
     nothing is written yet.
  3. The device reboots into `ota-FM-1`. Re-scan for up to 30 s, handshake, send UPGRADE
     again.
  4. Serve about 1,167 requests until the device asks for `0xF0000000`, then reply
     `"success\0"`. This step is the **write** phase.
  5. The device reboots. Re-scan and handshake, then confirm the identity equals the
     package's product string.
- **Resumable:** if the write is interrupted, the device stays in the loader. Finding an
  `ota-` device then means "resume with the same image".
- `.fwsc`: the product ID is encoded in marker bytes after each of the first 20 × 0x30 blocks.
  The logical image is the file with those 20 marker bytes stripped. Both are pure functions
  and simple to port.
- **USB rescue:** hold OCT− while powering on. This works for some firmwares (SLOOP family,
  zp12) and uses the same MIDI protocol, so the app supports it.
- **Not possible on iOS:** ROM bootloader recovery (`UBOOT1.00` USB mass storage with SCSI
  pass-through via jl-uboot-tool). That needs a computer. The app must say so clearly.

## Firmware sources

| Source | Machine-readable? | Use |
|---|---|---|
| `https://thefirmwarehub.com/catalog.json` | **Yes**: 37 entries, nightly from GitHub releases | Primary feed |
| fm1-editor.com/firmware | No (HTML cards linking to installers) | Optional links and descriptions |
| aroum/fm1-custom-fw README | Markdown table | Optional "more info" links |

`catalog.json` fields include `id, name, author, kind, what` (description), `version, date,
beta, pkg` (relative URL of the mirrored `.fwsc`), `product, size, sha256, source, site,
editor, loader`. `loader: "mvave"` (currently only Bubba Machine) appears to mean the official
firmware must be running first. This is unconfirmed: the hub's JS bundle needs checking.

The hub is run by a third party (designburg / zvenson). Ask them before shipping an app that
consumes the feed and downloads from their mirror, and cache responsibly.

## Official firmware

- Do not bundle it. The user either imports `FM-1.fwsc` from Files (from M-UPGRADE or the
  m-vave.com download) or pastes a URL.
- Validate it the way Felucca does: V15 is 699,956 bytes with SHA-256
  `db1642b2b6fa5c2cccb11ffd13878068bb28601678d3644049f99dc40e7edb8a`. Keep a small table of
  known official hashes so it can be updated later.
- **"Swap" = chained flash:** when the target needs stock (or the user opts in to "always go
  via stock"), run stock → target as one guided operation.

## Risks and open questions

1. **App backgrounding mid-flash (main risk).** If iOS suspends the app, the write stalls.
   - Mitigations: disable idle sleep during a flash, use a `beginBackgroundTask` grace
     period, block navigation, and rely on loader resume.
   - Do not use the audio background mode just to stay alive; App Review rejects it.
2. **Licensing.** `fm1ota.js` and `fm1pkg.js` are GPL-3.0, and GPL code conflicts with App
   Store terms.
   - Option 1: implement from the FM-1-RE protocol docs (check that repo's licence).
   - Option 2: get the Felucca author's (Leo Kuroshita) permission.
   - Option 3: keep it personal and distribute via TestFlight or sideloading.
3. **App Store review.** Downloading firmware for an external accessory is fine: it is not
   executable code for the app. Risk is moderate, because reviewers can be uneasy about
   "flashing tools".
4. **Product IDs are not unique.** Many firmwares report `FM-1_900`, so the app cannot always
   tell exactly what is installed. Keep a per-device "last flashed" record and show the
   reported ID.
5. **User data loss.** Firmwares store presets and projects differently. Felucca has a
   SysEx backup protocol (`fm1backup.js`); other firmwares vary. Phase 3 at the earliest.
6. **Untested on iOS:** CoreMIDI behaviour while the device re-enumerates, and real
   throughput. Spike these first (Phase 0).
7. **Power:** the iPhone powers the FM-1 over USB-C. That should be fine, but confirm the FM-1
   does not brown out during a flash write.

## Architecture (SwiftUI, iOS 17+; same code runs on macOS for bench testing)

```
FM1Kit (Swift package, no UI, unit-tested)
  Package/      FwscPackage: productOf, logicalImage, sha256, validation
  Protocol/     Pack7, Message encode/decode, Identity parsing
  Transport/    MidiTransport protocol + CoreMidiTransport (sysex queue, endpoint
                add/remove notifications) + MockTransport (scripted device for tests)
  Updater/      Updater actor: find → stage → loader → write → verify, resume,
                progress as AsyncStream<UpdateEvent>, typed errors
Catalogue/      CatalogueClient (catalog.json, ETag, offline cache), FirmwareStore
                (downloaded .fwsc files in Application Support, sha256-checked)
App/            Browse list + detail, Library (downloaded/offline), Official firmware
                import, Flash screen (big progress, do-not-unplug, resume), Device status
```

Design points:
- **Offline first.** Downloaded firmwares are cached and pinned, so swapping at a gig works
  with no signal. Add a "download all my favourites" action.
- **Pre-flight checks:** the package's product model matches the device, the hash matches
  the catalogue, a device was found, and the battery is not low.
- **Port probing:** only probe ports whose names match `fm-1|felucca|ota|composite|…`, the
  same as Felucca, so the app never sends SysEx to other gear.

## Phases

- **Phase 0 (spike, about 1 day). Do this before anything else.**
  - Build a bare iOS app that lists CoreMIDI endpoints, sends the handshake and shows the
    identity. No risk to the device.
  - Then flash a known-good firmware (Felucca) from the phone, with a Mac and Chrome
    installer ready as a fallback.
  - Measure the duration. Test unplugging during the write, then resume.
  - Test locking the screen and switching apps.
- **Phase 1 (MVP):** FM1Kit with tests against recorded sessions and a mock device;
  `catalog.json` browse and download; official firmware import and validation; flash and
  resume; offline library.
- **Phase 2:** chained "swap via stock"; favourites; flash history; beta toggle; extra
  metadata from fm1-editor.com; notifications when new versions appear (background app
  refresh on the catalogue).
- **Phase 3:** per-firmware backup and restore where a protocol exists (Felucca family);
  importing your own `.fwsc` from Files or the share sheet; Mac Catalyst build.

## References

- https://github.com/AL-255/FM-1-RE (protocol docs, `tools/fm1_ota.py`)
- https://github.com/hugelton/Felucca (`web/fm1ota.js`, `web/fm1pkg.js`, `web/fm1backup.js`)
- https://thefirmwarehub.com/catalog.json
- https://github.com/aroum/fm1-custom-fw, https://github.com/cicloid/awesome-fm-1,
  https://fm1-editor.com/firmware/
