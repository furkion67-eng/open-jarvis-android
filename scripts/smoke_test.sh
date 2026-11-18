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
#   C) instrumented tests: full Room recovery end-to-end + analysis engine;
#   D) regardless of the instrumented suite result, relaunch MainActivity and
#      preserve screenshot, crash buffer, ActivityManager and WindowManager
#      evidence. This separates an instrumentation assertion failure from a
#      real launcher crash.
# Instrumented tests may intentionally open an external app (SMS fallback), so
# their last screenshot is not necessarily the JARVIS launcher screen.
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
  # Preserve the immediate state after the test suite before changing focus.
  adb exec-out screencap -p > screen-after-instrumented.png 2>/dev/null || true
  adb logcat -d -b crash > crashlog-after-instrumented.txt 2>/dev/null || true
  adb logcat -d -v threadtime > logcat-after-instrumented.txt 2>/dev/null || true
  adb shell dumpsys activity activities > activity-after-instrumented.txt 2>/dev/null || true
  adb shell dumpsys activity top > activity-top-after-instrumented.txt 2>/dev/null || true
  adb shell dumpsys window windows > windows-after-instrumented.txt 2>/dev/null || true
  echo "Captured immediate post-instrumentation screenshot, crash buffer, ActivityManager and WindowManager state."
}

verify_launcher_visible_after_tests() {
  echo "=== Scenario D: relaunch and verify visible MainActivity after instrumented tests ==="
  adb shell am force-stop "$APP_PKG" >/dev/null 2>&1 || true
  if ! adb shell am start -W -n "$LAUNCHER" > launcher-start-after-instrumented.txt 2>&1; then
    echo "MainActivity could not be relaunched after instrumented tests."
    adb shell dumpsys activity activities > activity-main-activity-visible.txt 2>/dev/null || true
    adb shell dumpsys window windows > windows-main-activity-visible.txt 2>/dev/null || true
    adb logcat -d -b crash > crashlog-after-visible-launch.txt 2>/dev/null || true
    return 1
  fi

  # Match the current resumed Activity and focused window on the same sample.
  # grep -F avoids assumptions about Android's Window{token ...} formatting.
  launcher_is_resumed_and_focused() {
    local resumed_line focused_line
    resumed_line="$(
      grep -E 'topResumedActivity=|ResumedActivity:' activity-main-activity-visible.txt |
        grep -F 'com.openjarvis/.ui.MainActivity' | tail -n 1 || true
    )"
    focused_line="$(
      grep -F 'mCurrentFocus=Window' windows-main-activity-visible.txt |
        grep -F 'com.openjarvis/com.openjarvis.ui.MainActivity' | tail -n 1 || true
    )"
    [[ -n "$resumed_line" && -n "$focused_line" ]]
  }

  local deadline=$((SECONDS + 20))
  local visible=0
  while (( SECONDS < deadline )); do
    adb shell dumpsys activity activities > activity-main-activity-visible.txt 2>/dev/null || true
    adb shell dumpsys window windows > windows-main-activity-visible.txt 2>/dev/null || true
    if launcher_is_resumed_and_focused; then
      visible=1
      break
    fi
    sleep 1
  done

  # Take one fresh sample after the bounded wait to avoid a timeout race.
  if [ "$visible" -ne 1 ]; then
    adb shell dumpsys activity activities > activity-main-activity-visible.txt 2>/dev/null || true
    adb shell dumpsys window windows > windows-main-activity-visible.txt 2>/dev/null || true
    if launcher_is_resumed_and_focused; then
      visible=1
      echo "MAIN-ACTIVITY-VISIBLE: final bounded recheck found the resumed launcher and focused window"
    fi
  fi

  if [ "$visible" -ne 1 ]; then
    echo "---- ActivityManager current-resumed lines ----"
    grep -E 'topResumedActivity=|ResumedActivity:' activity-main-activity-visible.txt || true
    echo "---- WindowManager current-focus line ----"
    grep -F 'mCurrentFocus=Window' windows-main-activity-visible.txt || true
    echo "---- Full ActivityManager state ----"
    cat activity-main-activity-visible.txt || true
    echo "---- Full WindowManager state ----"
    cat windows-main-activity-visible.txt || true
    adb exec-out screencap -p > screen-main-activity-visible.png 2>/dev/null || true
    adb logcat -d -b crash > crashlog-after-visible-launch.txt 2>/dev/null || true
    echo "MainActivity did not become the resumed Activity with the focused JARVIS window within 20s."
    return 1
  fi

  adb exec-out screencap -p > screen-main-activity-visible.png 2>/dev/null || {
    echo "Could not capture the visible MainActivity screenshot."
    return 1
  }
  test -s screen-main-activity-visible.png || {
    echo "Visible MainActivity screenshot is empty."
    return 1
  }
  adb logcat -d -b crash > crashlog-after-visible-launch.txt 2>/dev/null || true
  if grep -q "FATAL EXCEPTION" crashlog-after-visible-launch.txt; then
    cat crashlog-after-visible-launch.txt
    echo "MainActivity relaunch produced a FATAL EXCEPTION."
    return 1
  fi
  echo "MAIN-ACTIVITY-VISIBLE: resumed MainActivity and focused JARVIS window verified after instrumented tests"
  return 0
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
  adb exec-out screencap -p > "screen-$label.png" 2>/dev/null || true
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
instrumented_exit=\${PIPESTATUS[0]}
set -e
capture_post_instrumentation

# Always perform the independent launcher check, even when an instrumented
# assertion failed. Preserve its evidence before reporting the suite failure.
set +e
verify_launcher_visible_after_tests
launcher_visible_exit=$?
set -e

if [ "$instrumented_exit" -ne 0 ] || ! grep -q "OK (" instrumented.log || grep -q "FAILURES" instrumented.log; then
  if [ "$launcher_visible_exit" -eq 0 ]; then
    echo "Launcher visibility passed independently; the instrumented assertion suite still failed."
  else
    echo "Launcher visibility check also failed; inspect the preserved Activity/Window/crash evidence."
  fi
  fail "instrumented tests did not pass (see instrumented.log and post-instrumentation evidence)"
fi
if [ "$launcher_visible_exit" -ne 0 ]; then
  fail "MainActivity was not visibly resumed after instrumented tests"
fi

echo "SMOKE-OK: fresh launch, legacy-database recovery, instrumented tests and post-test MainActivity visibility all passed"
