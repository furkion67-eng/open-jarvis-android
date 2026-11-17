Issue #3 launch validation — independently re-executed 2026-11-17 on an
Android 13 (API 33, x86_64, google_apis) emulator booted locally on the CI
runner (KVM), against the app built from `open-jarvis-apk-build-ready(2).zip`
at the PR head after applying the exact CI patch phase (repair_sources.py,
launcher manifest fix, MainActivity/dash guard, add_startup_diagnostics.py).

Environment: emulator 37.2.13, JDK 17, Gradle 8.2.

A defect in the previously committed evidence was found and fixed: the
smoke script did not remove a stale `legacy.db` before re-planting it, so
`sqlite3` aborted with "table legacy_marker already exists" and the script
died mid-run — the earlier committed `smoke-test-output.txt` actually showed
that failure while the README claimed success. `scripts/smoke_test.sh` now
deletes the stale file first, and all evidence below comes from a fresh,
complete run of the corrected script (`smoke-output.txt`, exit 0).

Run: `scripts/smoke_test.sh` — full output in smoke-output.txt.
- Scenario A (fresh install cold launch): launcher process alive 20 s, 0
  FATAL EXCEPTION in the logcat crash buffer. Screenshot: screen-fresh-launch.png.
- Scenario B (legacy `user_version=1` graphify.db planted — the exact TECNO
  upgraded-device state): launcher alive after 20 s; DB renamed to
  `graphify.db.unmigratable-1794917709898` (preserved, not deleted; see
  `GraphifyDB: Backed up un-migratable graphify.db` in logcat-after-smoke.txt);
  fresh current-schema DB created. Screenshot: screen-legacy-db.png.
- Scenario C: instrumented suite `am instrument` -> OK (4 tests), includes
  `legacyDatabaseIsBackedUpAndRoomCreatesFreshSchema` end-to-end
  (instrumented.log).
- JVM unit tests at the same head: GraphifyDbRecoveryTest 11/11,
  SocketProtocolTest 10/10, FuzzyMatcherTest 5/5.

Limitations:
- x86_64 emulator image; final confirmation on the physical TECNO Spark 20
  Pro (ARM / HiOS) still requires the user to install the rebuilt APK.
- CI-level emulator evidence (PR-triggered "Build Open Jarvis APK" runs for
  this branch and PR #6) is queued but blocked in `action_required` until
  the repository owner approves those runs; the automation token cannot
  approve runs or dispatch workflows (HTTP 403).
