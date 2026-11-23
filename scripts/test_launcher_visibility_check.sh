#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/launcher_visibility_check.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/activity" <<'EOF'
topResumedActivity=ActivityRecord{one u0 com.openjarvis/.ui.MainActivity} t13}
ResumedActivity: ActivityRecord{two u0 com.openjarvis/.ui.MainActivity} t14}
* Hist #0: ActivityRecord{one u0 com.openjarvis/.ui.MainActivity t13}
  mVisibleRequested=true mVisible=true mClientVisible=true reportedDrawn=true reportedVisible=true
EOF
cat > "$tmp/window" <<'EOF'
mCurrentFocus=Window{one u0 com.openjarvis/com.openjarvis.ui.MainActivity}
EOF
launcher_is_resumed_and_focused "$tmp/activity" "$tmp/window"

# A single resumed and actually drawn Activity is accepted.
printf '%s\n' 'ResumedActivity: ActivityRecord{one u0 com.openjarvis/.ui.MainActivity} t13}' '* Hist #0: ActivityRecord{one u0 com.openjarvis/.ui.MainActivity t13}' 'mVisibleRequested=true reportedDrawn=true reportedVisible=true' > "$tmp/activity"
launcher_is_resumed_and_focused "$tmp/activity" "$tmp/window"

# RESUMED without a first draw must fail even if the app window has focus.
printf '%s\n' 'ResumedActivity: ActivityRecord{one u0 com.openjarvis/.ui.MainActivity} t13}' '* Hist #0: ActivityRecord{one u0 com.openjarvis/.ui.MainActivity t13}' 'mVisibleRequested=true reportedDrawn=false reportedVisible=false' > "$tmp/activity"
if launcher_is_resumed_and_focused "$tmp/activity" "$tmp/window"; then
  echo "FAIL: undrawn Activity was accepted" >&2
  exit 1
fi

# The keyboard can own current focus while JARVIS remains the IME input target.
printf '%s\n' 'ResumedActivity: ActivityRecord{one u0 com.openjarvis/.ui.MainActivity} t13}' '* Hist #0: ActivityRecord{one u0 com.openjarvis/.ui.MainActivity t13}' 'mVisibleRequested=true reportedDrawn=true reportedVisible=true' > "$tmp/activity"
cat > "$tmp/window" <<'EOF'
mCurrentFocus=Window{ime u0 InputMethod}
imeInputTarget in display# 0 Window{one u0 com.openjarvis/com.openjarvis.ui.MainActivity}
EOF
launcher_is_resumed_and_focused "$tmp/activity" "$tmp/window"

# A different Activity must never pass the launcher visibility gate.
printf '%s\n' 'ResumedActivity: ActivityRecord{one u0 com.other/.MainActivity} t13}' '* Hist #0: ActivityRecord{one u0 com.other/.MainActivity t13}' 'mVisibleRequested=true reportedDrawn=true reportedVisible=true' > "$tmp/activity"
if launcher_is_resumed_and_focused "$tmp/activity" "$tmp/window"; then
  echo "FAIL: non-JARVIS Activity was accepted" >&2
  exit 1
fi

# A resumed and drawn JARVIS Activity without focus or IME input target must fail.
printf '%s\n' 'ResumedActivity: ActivityRecord{one u0 com.openjarvis/.ui.MainActivity} t13}' '* Hist #0: ActivityRecord{one u0 com.openjarvis/.ui.MainActivity t13}' 'mVisibleRequested=true reportedDrawn=true reportedVisible=true' > "$tmp/activity"
printf '%s\n' 'mCurrentFocus=Window{one u0 com.other/.MainActivity}' > "$tmp/window"
if launcher_is_resumed_and_focused "$tmp/activity" "$tmp/window"; then
  echo "FAIL: unrelated focused window was accepted" >&2
  exit 1
fi

echo "LAUNCHER-MATCHER-OK"
