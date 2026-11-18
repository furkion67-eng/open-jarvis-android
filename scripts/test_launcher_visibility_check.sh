#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/launcher_visibility_check.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/activity" <<'EOF'
topResumedActivity=ActivityRecord{one u0 com.openjarvis/.ui.MainActivity} t13}
ResumedActivity: ActivityRecord{two u0 com.openjarvis/.ui.MainActivity} t14}
EOF
cat > "$tmp/window" <<'EOF'
mCurrentFocus=Window{one u0 com.openjarvis/com.openjarvis.ui.MainActivity}
EOF
launcher_is_resumed_and_focused "$tmp/activity" "$tmp/window"

# A single resumed Activity is accepted.
printf '%s\n' 'ResumedActivity: ActivityRecord{one u0 com.openjarvis/.ui.MainActivity} t13}' > "$tmp/activity"
launcher_is_resumed_and_focused "$tmp/activity" "$tmp/window"

# The keyboard can own current focus while JARVIS remains the IME input target.
cat > "$tmp/window" <<'EOF'
mCurrentFocus=Window{ime u0 InputMethod}
imeInputTarget in display# 0 Window{one u0 com.openjarvis/com.openjarvis.ui.MainActivity}
EOF
launcher_is_resumed_and_focused "$tmp/activity" "$tmp/window"

# A different Activity must never pass the launcher visibility gate.
printf '%s\n' 'ResumedActivity: ActivityRecord{one u0 com.other/.MainActivity} t13}' > "$tmp/activity"
if launcher_is_resumed_and_focused "$tmp/activity" "$tmp/window"; then
  echo "FAIL: non-JARVIS Activity was accepted" >&2
  exit 1
fi

# A resumed JARVIS Activity without focus or IME input target must fail.
printf '%s\n' 'ResumedActivity: ActivityRecord{one u0 com.openjarvis/.ui.MainActivity} t13}' > "$tmp/activity"
printf '%s\n' 'mCurrentFocus=Window{one u0 com.other/.MainActivity}' > "$tmp/window"
if launcher_is_resumed_and_focused "$tmp/activity" "$tmp/window"; then
  echo "FAIL: unrelated focused window was accepted" >&2
  exit 1
fi

echo "LAUNCHER-MATCHER-OK"
