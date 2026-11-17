Run 3 — fully independent re-execution of the issue #3 launch validation,
2026-11-17T18:16Z, on a fresh GitHub-hosted ubuntu-latest runner (this is CI
hardware, not a developer workstation), separate from the runs that produced
the parent-directory evidence.

Methodology (nothing trusted from prior runs):
1. Checkout of PR head 3d93447 into a clean worktree; unzipped the PR-head
   `open-jarvis-apk-build-ready(2).zip` from scratch.
2. Applied the EXACT CI patch phase from `.github/workflows/build-open-jarvis-apk.yml`
   (launcher manifest fix, repair_sources.py, MainActivity/dashboard guards,
   add_startup_diagnostics.py) with no modifications.
3. Built with JDK 17 + Gradle 8.2 pulled fresh:
   - `testDebugUnitTest` → BUILD SUCCESSFUL, 26/26 tests pass, 0 failures
     (JUnit XML audit across all result files).
   - `assembleDebug assembleDebugAndroidTest` → BUILD SUCCESSFUL; both APKs produced.
4. Installed system image `system-images;android-33;google_apis;x86_64`,
   created a Pixel 4 AVD, granted KVM access (`setfacl /dev/kvm`), booted the
   emulator (37.2.13, -accel on, gfxstream), `sys.boot_completed=1` on API 33.
5. Ran `scripts/smoke_test.sh` unmodified against the freshly built APKs.

Results (transcript in smoke-output.txt):
- Scenario A fresh cold launch: process alive after 20 s; crash buffer has 0
  "FATAL EXCEPTION" (crashlog.txt in this directory is the post-run `-b crash`
  dump — it is empty of FATALs).
- Scenario B legacy `user_version=1` graphify.db planted (issue #3 device
  state): launcher alive after 20 s; DB backed up as
  `graphify.db.unmigratable-1794939352117`; fresh schema created
  (databases.txt listing included).
- Scenario C instrumented suite: OK (4 tests), 0.335 s (instrumented.log).
- Screenshot screen-fresh-launch.png shows the "Permission Required" screen
  rendered — the app reaches real UI on Android 13.

Verify on GitHub: every file in this directory was produced during this run;
compare file mtime/pixel data with the run transcript in the issue thread.
