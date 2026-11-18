#!/usr/bin/env bash
# Shared Android 13 launcher visibility predicate used by smoke_test.sh and its regression test.
launcher_is_resumed_and_focused() {
  if [[ "$#" -ne 2 ]]; then
    echo "launcher_is_resumed_and_focused requires ActivityManager and WindowManager dump paths" >&2
    return 2
  fi
  local activity_dump="$1"
  local window_dump="$2"

  # Avoid grep -q on the right side of a pipeline under pipefail:
  # early exit can SIGPIPE the upstream grep even when a match exists.
  grep -E 'topResumedActivity=|ResumedActivity:' "$activity_dump" |
    grep -F 'com.openjarvis/.ui.MainActivity' >/dev/null &&
  grep -F 'mCurrentFocus=Window' "$window_dump" |
    grep -F 'com.openjarvis/com.openjarvis.ui.MainActivity' >/dev/null
}
