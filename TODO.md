# To do

In order. Each item waits until it's asked for.

1. ~~**Code quality:** bring FM1Kit's checks to the app and fix what they find.~~ Done
   2026-10-16: `scripts/check.sh` passes, and CI is set up in `.github/workflows/ci.yml`.
2. **Hardware testing:** run stage A of FM1Kit's `HARDWARE_TESTING.md` against the real FM-1
   (and stage B if wanted), then use the output to tune `UpdaterTiming` and check the port-name
   filter (`probeablePortNameFragments`).

Then, only when asked:

3. Create github.com/ocean/FM1Kit and push FM1Kit to it, then check that its CI (`macos-26`)
   runs.
4. Give FM-SWITCH its own GitHub repository and push it. Its CI needs `ocean/FM1Kit` to exist
   first (step 3).
5. Once FM1Kit is published, point `project.yml` at the GitHub URL and a version instead of
   `../FM1Kit`.
6. Write `fm1probe install` (hardware stage D).
