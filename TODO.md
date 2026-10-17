# To do

In order. Each item waits until it's asked for.

1. ~~**Code quality:** bring FM1Kit's checks to the app and fix what they find.~~ Done
   2026-10-16: `scripts/check.sh` passes, and CI is set up in `.github/workflows/ci.yml`.
2. **Hardware testing:** stage A of FM1Kit's `HARDWARE_TESTING.md` passed on 2026-10-16 on
   Felucca 1.6.1 (`FM-1_916`): the "Felucca" port matched the filter, 50 of 50 handshakes were
   answered in 2–81 ms, and the port came back within 2–3 s of switching on. Stage B isn't
   possible on that firmware, as it doesn't start in USB recovery mode with OCT− held, and nor
   does official V15. The first real install (Felucca to V15, with `fm1probe install`) passed in
   34.7 s: staging 4.2 s over 75 requests, writing 24.4 s over 1167 requests, at most 350 ms
   between requests. Still to do: FM1Kit's write progress jumps from 0% to 80% and sits there
   (the loader reads out of order), so the app's progress bar needs FM1Kit's fix; tune
   `UpdaterTiming`; and optionally stage C (the DEVICE tab on a phone).

Then, only when asked:

3. Create github.com/ocean/FM1Kit and push FM1Kit to it, then check that its CI (`macos-26`)
   runs.
4. ~~Give FM-SWITCH its own GitHub repository and push it.~~ Done 2026-10-16:
   github.com/ocean/FM-SWITCH (public).
5. Once FM1Kit is published, point `project.yml` at the GitHub URL and a version instead of
   `../FM1Kit`. FM1Kit is pushed but not tagged yet: Drew has more of it to test first
   (maybe over the weekend of 2026-10-17).
6. Write `fm1probe install` (hardware stage D). In progress in the FM1Kit session since
   2026-10-16.
7. **fm1_open follow-ups** (see PLAN.md). Done 2026-10-17, against FM1Kit c3bebbf:
   - bundle V15;
   - list the packages that can finish a loader's update;
   - recognise Open identities and keep Open V12 apart from the official V15;
   - check before flashing, with SWITCH FIRMWARE? and a backup tick;
   - move the tests to `FwscPackage.sampleFile`.
   On hold (2026-10-17): restoring V15's factory voices. V15 brought them back by itself
   after custom firmwares; it's revisited only if leaving Open (FM1Kit stage E4) doesn't.
