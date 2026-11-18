#!/usr/bin/env bash
# Shared Android 13 launcher visibility predicate used by smoke_test.sh and its regression test.
launcher_is_resumed_and_focused() {
  if [[ "$#" -ne 2 ]]; then
    echo "launcher_is_resumed_and_focused requires ActivityManager and WindowManager dump paths" >&2
    return 2
  fi
  local activity_dump="$1"
  local window_dump="$2"
  local component="com.openjarvis/com.openjarvis.ui.MainActivity"
  local short_component="com.openjarvis/.ui.MainActivity"

  # dumpsys activity commonly prints the package-relative component while
  # dumpsys window prints the fully-qualified window component. Accept both
  # Android representations for the same MainActivity.
  if ! grep -E 'topResumedActivity=|ResumedActivity:' "$activity_dump" |
       grep -F -e "$component" -e "$short_component" >/dev/null; then
    return 1
  fi

  # Android may give mCurrentFocus to the soft keyboard while the app remains
  # the IME input target. That is a visible, interactive app state, not a crash
  # or a launcher loss. Accept either the app window focus or its IME target.
  awk -v component="$component" '
    /mCurrentFocus=Window/ && index($0, component) { focused=1 }
    /imeInputTarget in display#/ && index($0, component) { input_target=1 }
    END { exit !(focused || input_target) }
  ' "$window_dump"
}
