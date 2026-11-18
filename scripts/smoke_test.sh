#!/usr/bin/env bash
# Open Jarvis launch smoke test (issue #3).
#
# Runs on an Android 13 (API 33) emulator inside CI:
#   A) cold launch on a fresh install, process must stay alive 20s with no
#      FATAL EXCEPTION in the logcat crash buffer;
#   B) launch with a legacy graphify.db (schema user_version=1) planted in the
#      app data dir — the exact upgraded-device state that crashed Open Jarvis
#      with "A migration from 1 to 3 was required but not found" — the app must
#      stay alive and the database must be backed up as graphify.db.unmigratable-*;
#   C) instrumented tests: full Room recovery end-to-end + analysis engine.
# Post-instrumentation screen, crash buffer, ActivityManager and WindowManager
# state are always captured before interpreting test failures.
set -euo pipefail

APP_PKG="com.openjarvis"
LAUNCHER="com.openjarvis/.ui.MainActivity"

fail() { echo "SMOKE-FAIL: $1"; exit 1; }

adb wait-for-device
adb root >/dev/null 2>&1 || true

adb install -r "$APP_APK" >/dev/null || fail "app APK install failed"
adb shell pm clear "$APP_PKG" >/dev/null

logcat_scan() {
  local label="$1"
  adb logcat -d -b crash > crashlog.txt 2>/dev/null || true
  if grep -q "FATAL EXCEPTION" crashlog.txt; then
    echo "---- crash buffer ----"; cat crashlog.txt
    fail "$label: FATAL EXCEPTION found in logcat crash buffer"
  fi
  adb logcat -d > logcat-full.txt 2>/dev/null || true
}

capture_post_instrumentation() {
  # Capture the state after the instrumented launcher assertion, including
  # failure cases. These files are uploaded by the workflow's always() step.
  adb exec-out screencap -p > screen-after-instrumented.png 2>/dev/null || true
  adb logcat -d -b crash > crashlog-after-instrumented.txt 2>/dev/null || true
  adb logcat -d -v threadtime > logcat-after-instrumented.txt 2>/dev/null || true
  adb shell dumpsys activity activities > activity-after-instrumented.txt 2>/dev/null || true
  adb shell dumpsys activity top > activity-top-after-instrumented.txt 2>/dev/null || true
  adb shell dumpsys window windows > windows-after-instrumented.txt 2>/dev/null || true
  echo "Captured post-instrumentation screenshot, crash buffer, ActivityManager and WindowManager state."
}

launch_and_verify() {
  local label="$1"
  adb shell am start -n "$LAUNCHER" >/dev/null
  sleep 20
  local pid
  pid="$(adb shell pidof "$APP_PKG" | tr -d '\r' || true)"
  if [ -z "$pid" ]; then
    adb logcat -d > logcat-full.txt 2>/dev/null || true
    fail "$label: launcher process died within 20s"
  fi
  echo "$label: launcher process alive (pid=$pid) after 20s"
  adb exec-out screencap -p > "screen-${label}.png" 2>/dev/null || true
  logcat_scan "$label"
}

echo "=== Scenario A: cold launch on a fresh install ==="
adb logcat -c >/dev/null 2>&1 || true
launch_and_verify "fresh-launch"

echo "=== Scenario B: launch with legacy graphify.db (issue #3 device state) ==="
adb shell am force-stop "$APP_PKG"
adb logcat -c >/dev/null 2>&1 || true
sqlite3 legacy.db "CREATE TABLE legacy_marker (id INTEGER PRIMARY KEY); PRAGMA user_version=1;"
adb push legacy.db /data/local/tmp/legacy.db >/dev/null
adb shell chmod 644 /data/local/tmp/legacy.db
adb shell run-as "$APP_PKG" mkdir -p databases
adb shell run-as "$APP_PKG" cp /data/local/tmp/legacy.db databases/graphify.db
adb shell am start -n "$LAUNCHER" >/dev/null
sleep 20
pid="$(adb shell pidof "$APP_PKG" | tr -d '\r' || true)"
if [ -z "$pid" ]; then
  fail "legacy-db: launcher process died within 20s"
fi
echo "legacy-db: launcher process alive (pid=$pid) after 20s"
adb exec-out screencap -p > "screen-legacy-db.png" 2>/dev/null || true
logcat_scan "legacy-db"
adb shell run-as "$APP_PKG" ls databases | tr -d '\r' > databases.txt
cat databases.txt
if ! grep -q "graphify.db.unmigratable-" databases.txt; then
  fail "legacy-db: un-migratable database was not backed up"
fi
echo "legacy-db: recovery backup created, app stayed alive"

echo "=== Scenario C: instrumented tests (Room recovery end-to-end + analysis engine) ==="
adb install -r "$TEST_APK" >/dev/null || fail "test APK install failed"
set +e
adb shell am instrument -w "$APP_PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee instrumented.log
instrumented_exit=${PIPESTATUS[0]}
set -e
capture_post_instrumentation
if [ "$instrumented_exit" -ne 0 ]; then
  fail "instrumented command exited with status $instrumented_exit (see post-instrumentation evidence)"
fi
if grep -q "FATAL EXCEPTION" crashlog-after-instrumented.txt; then
  echo "---- post-instrumentation crash buffer ----"
  cat crashlog-after-instrumented.txt
  fail "instrumented tests produced a FATAL EXCEPTION"
fi
if ! grep -q "OK (" instrumented.log; then
  fail "instrumented tests did not report OK (see post-instrumentation evidence)"
fi
if grep -q "FAILURES" instrumented.log; then
  fail "instrumented tests reported FAILURES (see post-instrumentation evidence)"
fi

echo "SMOKE-OK: fresh launch, legacy-database launch and instrumented tests all passed"
