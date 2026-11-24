#!/usr/bin/env bash
# Android 13/14 launch, feature instrumentation and reviewable narrated-video evidence.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
source "$SCRIPT_DIR/launcher_visibility_check.sh"
APP_PKG="com.openjarvis"
LAUNCHER="com.openjarvis/.ui.MainActivity"
: "${APP_APK:?APP_APK is required}"
: "${TEST_APK:?TEST_APK is required}"
fail() { echo "SMOKE-FAIL: $1" >&2; exit 1; }

adb wait-for-device
boot=""
for attempt in $(seq 1 60); do
  boot="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  [ "$boot" = "1" ] && break
  sleep 1
done
[ "$boot" = "1" ] || fail "emulator did not finish booting"
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
case "$api" in 33|34) echo "Android API $api" ;; *) fail "unexpected API $api" ;; esac

adb install -r "$APP_APK" >/dev/null || fail "Debug APK install failed"
adb shell pm clear "$APP_PKG" >/dev/null || fail "app data clear failed"

capture() {
  local label="$1"
  local pid
  adb shell dumpsys activity activities > "activity-$label.txt" 2>/dev/null || true
  adb shell dumpsys activity processes > "processes-$label.txt" 2>/dev/null || true
  adb shell dumpsys activity lastanr > "lastanr-$label.txt" 2>/dev/null || true
  adb shell dumpsys window > "windows-$label.txt" 2>/dev/null || true
  adb shell dumpsys input > "input-$label.txt" 2>/dev/null || true
  adb shell dumpsys cpuinfo > "cpuinfo-$label.txt" 2>/dev/null || true
  adb shell dumpsys meminfo "$APP_PKG" > "meminfo-$label.txt" 2>/dev/null || true
  adb shell dumpsys gfxinfo "$APP_PKG" framestats > "gfxinfo-$label.txt" 2>/dev/null || true
  adb shell dumpsys dropbox --print data_app_anr > "dropbox-$label.txt" 2>/dev/null || true
  adb shell ps -A > "process-list-$label.txt" 2>/dev/null || true
  adb logcat -d -b crash > "crashlog-$label.txt" 2>/dev/null || true
  adb logcat -d -b all -v threadtime > "logcat-$label.txt" 2>/dev/null || true
  grep -E 'topResumedActivity|ResumedActivity|reportedDrawn|mCurrentFocus|imeInputTarget|mFocusedApp' "activity-$label.txt" "windows-$label.txt" > "focus-summary-$label.txt" 2>/dev/null || true
  pid="$(adb shell pidof "$APP_PKG" | tr -d '\r' || true)"
  if [ -n "$pid" ]; then
    printf '%s\n' "$pid" > "pid-$label.txt"
    # SIGQUIT asks ART for a thread dump; it is diagnostic and does not grant
    # additional privileges or alter app data.
    adb shell kill -3 "$pid" >/dev/null 2>&1 || true
    sleep 1
    adb logcat -d -b all -v threadtime > "thread-dump-$label.txt" 2>/dev/null || true
  else
    echo "PROCESS-NOT-RUNNING" > "pid-$label.txt"
  fi
  adb exec-out screencap -p > "screen-$label.png" 2>/dev/null || true
}
visible() {
  local label="$1"
  local deadline="$2"
  while (( SECONDS < deadline )); do
    adb shell dumpsys activity activities > "activity-$label.txt" 2>/dev/null || true
    adb shell dumpsys window > "windows-$label.txt" 2>/dev/null || true

    # Distinguish a drawn JARVIS Activity from a system-owned ANR dialog. The latter
    # is an emulator/system health failure, not evidence that JARVIS itself ANR'd.
    if grep -E 'topResumedActivity=|ResumedActivity:' "activity-$label.txt" |
         grep -F -e 'com.openjarvis/.ui.MainActivity' -e 'com.openjarvis/com.openjarvis.ui.MainActivity' >/dev/null &&
       grep -Fq 'reportedDrawn=true' "activity-$label.txt" &&
       grep -Fq 'Application Not Responding: system' "windows-$label.txt"; then
      capture "$label-system-anr"
      echo "SYSTEM-ANR-OVERLAY: MainActivity is drawn but a system ANR dialog owns focus" >&2
      return 2
    fi

    if launcher_is_resumed_and_focused "activity-$label.txt" "windows-$label.txt"; then
      adb exec-out screencap -p > "screen-$label.png" 2>/dev/null || return 1
      test -s "screen-$label.png" || return 1
      echo "MAIN-ACTIVITY-VISIBLE: $label"
      return 0
    fi
    sleep 1
  done
  capture "$label-failed"
  return 1
}

adb logcat -c >/dev/null 2>&1 || true
launch_started="$(date +%s)"
launch_deadline=$((SECONDS + 20))
# Do not use am start -W here: its own wait can consume most of the first-draw
# budget before the harness starts polling. Measure launch-to-draw from one clock.
if ! adb shell am start -n "$LAUNCHER" > launcher-start.txt 2>&1; then
  capture "launcher-start-failed"
  fail "MainActivity start command failed; diagnostic bundle captured"
fi
pid=""
while (( SECONDS < launch_deadline )); do
  pid="$(adb shell pidof "$APP_PKG" | tr -d '\r' || true)"
  [ -n "$pid" ] && break
  sleep 1
done
if [ -z "$pid" ]; then
  capture "launcher-process-died"
  fail "launcher process did not start within the 20-second cold-start budget"
fi
if visible "fresh-launch" "$launch_deadline"; then
  launch_elapsed=$(( $(date +%s) - launch_started ))
  echo "LAUNCH-TO-VISIBLE-SECONDS:$launch_elapsed"
else
  visible_status=$?
  if [ "$visible_status" -eq 2 ]; then
    fail "system ANR dialog obscured a drawn JARVIS Activity; emulator/system health gate failed"
  fi
  fail "MainActivity did not become drawn and focused within the 20-second cold-start budget"
fi

adb install -r "$TEST_APK" >/dev/null || fail "instrumented APK install failed"
set +e
adb shell am instrument -w "$APP_PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee instrumented.log
instrumented_exit=$?
set -e
[ "$instrumented_exit" -eq 0 ] || fail "instrumentation command failed: $instrumented_exit"
grep -q "OK (" instrumented.log || fail "instrumentation did not report OK"
if grep -q "FAILURES" instrumented.log; then fail "instrumentation reported failures"; fi

for class in MainActivityLaunchInstrumentedTest JarvisTaskPersistenceInstrumentedTest VideoTaskExecutionInstrumentedTest ContactResolutionInstrumentedTest AndroidContactsResolverProviderInstrumentedTest SmsConfirmationInstrumentedTest SafStorageGatewayInstrumentedTest StorageUriGrantRegistryInstrumentedTest StorageAccessPanelInstrumentedTest ContactChoicePagingCiOverlayTest VideoArtifactPlayerInstrumentedTest VideoArtifactPlayerMainActivityInstrumentedTest VideoNarratedExportInstrumentedTest VideoArtifactEvidenceCiOverlayTest; do
  grep -Fq "$class" instrumented.log || fail "required instrumentation class not discovered: $class"
done

python3 - instrumented.log <<'PY'
import pathlib, sys
lines = pathlib.Path(sys.argv[1]).read_text(errors="replace").splitlines()
klass = test = ""
skipped = []
for line in lines:
    if line.startswith("INSTRUMENTATION_STATUS: class="):
        klass = line.split("=", 1)[1].strip()
    elif line.startswith("INSTRUMENTATION_STATUS: test="):
        test = line.split("=", 1)[1].strip()
    elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
        code = line.split(":", 1)[1].strip()
        if code in {"-3", "-4"}:
            skipped.append((klass, test, code))
if skipped:
    for item in skipped: print("INSTRUMENTATION-SKIPPED:", item)
    raise SystemExit(f"zero skipped tests required; found {len(skipped)}")
print("INSTRUMENTATION-SKIP-SUMMARY: 0 skipped")
PY

copy_evidence() {
  adb exec-out run-as "$APP_PKG" cat "files/$1" > "$2" 2>/dev/null || fail "could not extract app evidence $1"
  test -s "$2" || fail "empty evidence file $2"
}
copy_evidence ci-video-artifact.mp4 video-narrated.mp4
copy_evidence ci-video-artifact.srt video-narrated.srt
copy_evidence ci-video-thumbnail.jpg video-narrated-thumbnail.jpg
copy_evidence ci-video-attribution.json video-narrated-attribution.json
copy_evidence ci-video-audio-status.txt video-audio-status.txt
grep -q "VIDEO-NARRATED-PIPELINE-OK" video-audio-status.txt || fail "narrated evidence status missing"
python3 scripts/validate_video_artifact.py --mode narrated video-narrated.mp4 video-narrated.srt
ffmpeg -v error -i video-narrated.mp4 -f null - || fail "MP4 decode failed"

python3 - video-narrated-attribution.json <<'PY'
import json, pathlib, re, sys
data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assets = data.get("attributionManifest", [])
claims = data.get("claimSources", [])
count = data.get("sceneCount")
assert data.get("attributionManifestComplete") is True
assert count == 5 and len(assets) == count
assert len({x.get("assetId") for x in assets}) == count
assert len({x.get("sceneId") for x in assets}) == count
for x in assets:
    assert re.fullmatch(r"[a-f0-9]{64}", x.get("sha256", ""))
    assert "commons.wikimedia.org" in x.get("sourceUrl", "") and "oldid=" in x.get("sourceUrl", "")
    assert x.get("creator") and x.get("licenseId") and x.get("licenseVersion") and x.get("licenseUrl")
    assert x.get("commercialUseAllowed") is True and x.get("derivativesAllowed") is True
    assert x.get("sourceUrl") in x.get("attributionText", "")
assert len(claims) == count
assert len({x.get("claimId") for x in claims}) == count
for x in claims:
    assert x.get("publisher") in {"Türk Tarih Kurumu", "TDV İslâm Ansiklopedisi"}
    assert x.get("sourceUrl", "").startswith("https://")
assert data.get("narrationLocale") == "tr-TR" and data.get("captionLocale") == "tr-TR"
assert 1 <= int(data.get("hookDurationMs", 0)) <= 2000
print(f"ATTRIBUTION-MANIFEST-OK: {len(assets)} licensed scene assets; {len(claims)} sourced claims")
PY
sha256sum video-narrated.mp4 video-narrated.srt video-narrated-thumbnail.jpg video-narrated-attribution.json > video-narrated.sha256
cat video-narrated.sha256

adb shell am force-stop "$APP_PKG" >/dev/null 2>&1 || true
adb shell am start -W -n "$LAUNCHER" > launcher-start-after-tests.txt 2>&1 || fail "post-test launcher relaunch failed"
visible "post-instrumentation" || fail "MainActivity not visible after tests"
adb logcat -d -b crash > crashlog.txt 2>/dev/null || true
adb logcat -d -v threadtime > logcat-full.txt 2>/dev/null || true
if grep -q "FATAL EXCEPTION" crashlog.txt; then cat crashlog.txt; fail "FATAL EXCEPTION detected"; fi
adb shell dumpsys activity activities > activity-final.txt 2>/dev/null || true
adb shell dumpsys window > windows-final.txt 2>/dev/null || true
adb shell run-as "$APP_PKG" rm -f files/ci-video-artifact.mp4 files/ci-video-artifact.srt files/ci-video-thumbnail.jpg files/ci-video-attribution.json files/ci-video-audio-status.txt >/dev/null 2>&1 || true
echo "SMOKE-OK: launcher, contacts/SMS/SAF, embedded player, narrated export, attribution and zero-skip instrumentation"
