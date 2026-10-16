# To do

In order. Each item waits until it's asked for.

1. ~~**Code quality:** bring FM1Kit's checks to the app and fix what they find.~~ Done
   2026-10-16: `scripts/check.sh` passes, and CI is set up in `.github/workflows/ci.yml`.
2. **Hardware testing:** stage A of FM1Kit's `HARDWARE_TESTING.md` passed on 2026-10-16 on
   Felucca 1.6.1 (`FM-1_916`): the "Felucca" port matched the filter, 50 of 50 handshakes were
   answered in 2–81 ms, and the port came back within 2–3 s of switching on. Stage B isn't
   possible on that firmware, as it doesn't start in USB recovery mode with OCT− held. Still to
   do: tune `UpdaterTiming` from those results, and optionally stage C (the DEVICE tab on a
   phone).

Then, only when asked:

3. Create github.com/ocean/FM1Kit and push FM1Kit to it, then check that its CI (`macos-26`)
   runs.
4. Give FM-SWITCH its own GitHub repository and push it. Its CI needs `ocean/FM1Kit` to exist
   first (step 3).
5. Once FM1Kit is published, point `project.yml` at the GitHub URL and a version instead of
   `../FM1Kit`.
6. Write `fm1probe install` (hardware stage D). In progress in the FM1Kit session since
   2026-10-16.
