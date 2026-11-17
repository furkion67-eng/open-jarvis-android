Issue #3 launch validation — captured 2026-11-17 on an Android 13 (API 33,
x86_64, google_apis) emulator booted locally on the CI runner (KVM).

Environment: emulator 37.2.13, JDK 17, Gradle 8.2, app built from
`open-jarvis-apk-build-ready(2).zip` at this commit after applying the exact CI
patch phase (repair_sources.py, launcher manifest fix, MainActivity/dash guard,
add_startup_diagnostics.py).

Run: `scripts/smoke_test.sh` — output in smoke-test-output.txt.
- Scenario A (fresh install cold launch): process alive after 20 s, 0 FATAL
  EXCEPTION in logcat crash buffer. Screenshot: screen-fresh-launch.png.
- Scenario B (legacy `user_version=1` graphify.db planted — the exact TECNO
  upgraded-device state): launcher alive after 20 s; DB renamed to
  `graphify.db.unmigratable-1794885421836` (preserved, not deleted); fresh
  current-schema DB created. Screenshot: screen-legacy-db.png.
- Scenario C: instrumented suite `am instrument` -> OK (4 tests), includes
  `legacyDatabaseIsBackedUpAndRoomCreatesFreshSchema` end-to-end.
- JVM unit tests: GraphifyDbRecoveryTest 11/11, SocketProtocolTest 10/10,
  FuzzyMatcherTest 5/5.

Limitation: x86_64 emulator image; final confirmation on the physical TECNO
Spark 20 Pro (ARM / HiOS) still requires the user to install the rebuilt APK.
