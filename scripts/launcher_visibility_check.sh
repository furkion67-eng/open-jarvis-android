#!/usr/bin/env bash
# Shared Android 13/14 launcher acceptance predicate.
launcher_is_resumed_and_focused() {
  if [[ "$#" -ne 2 ]]; then
    echo "launcher_is_resumed_and_focused requires ActivityManager and WindowManager dump paths" >&2
    return 2
  fi
  local activity_dump="$1"
  local window_dump="$2"
  local component="com.openjarvis/com.openjarvis.ui.MainActivity"
  local short_component="com.openjarvis/.ui.MainActivity"

  if ! grep -E 'topResumedActivity=|ResumedActivity:' "$activity_dump" |
       grep -F -e "$component" -e "$short_component" >/dev/null; then
    return 1
  fi

  # RESUMED is not equivalent to a drawn UI. Android's ActivityRecord exposes
  # reportedDrawn; require an actual first draw before treating the launcher
  # as visible (this catches the prior API 34 ANR-dialog false-positive case).
  if ! grep -Eq 'reportedDrawn=true' "$activity_dump"; then
    return 1
  fi

  # Android may focus the keyboard while the app remains its IME input target.
  awk -v component="$component" '
    /mCurrentFocus=Window/ && index($0, component) { focused=1 }
    /imeInputTarget in display#/ && index($0, component) { input_target=1 }
    END { exit !(focused || input_target) }
  ' "$window_dump"
}
