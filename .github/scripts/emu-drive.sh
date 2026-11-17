#!/usr/bin/env bash
# Drive the emulator: install the debug APK, tap Disclaimer -> Welcome,
# screenshot Home (top + scrolled steps + final), record a short video.
# Invoked by .github/workflows/emulator-screenshots.yml as ONE line
# (bash .github/scripts/emu-drive.sh <screenrecord_seconds>) — it MUST stay
# a single invocation: android-emulator-runner executes the workflow's
# `script:` input line by line, each in a fresh `sh -c`, so shell variables
# (and functions) set on one line do NOT survive to the next. Run 44009742313
# proved it: OUT was set on one line and `mkdir -p "$OUT"` on the next saw
# '' ("cannot create directory ''", exit 1) even after the $PWD fallback fix.
# Inside this file the whole flow runs in ONE shell, so state persists.
set -euo pipefail

SECONDS="${1:-60}"
APK="App/app/build/outputs/apk/debug/app-debug.apk"
# $GITHUB_WORKSPACE is empty inside the runner's script context (run
# 44002960788); $PWD is the checkout when this file runs.
OUT="${GITHUB_WORKSPACE:-$PWD}/emu-shots"
mkdir -p "$OUT"
adb install -r "$APK"
# Start video capture in the background (killed after the flow).
adb shell screenrecord --time-limit "$SECONDS" /sdcard/radd-demo.mp4 &
REC_PID=$!
adb shell am start -n com.raddstream.app/.MainActivity
sleep 12
adb exec-out screencap -p > "$OUT/01-disclaimer.png"

# Tap a UI node by its exact visible text (uiautomator dump -> centre tap).
tap_text() {
  local want="$1" tries=0
  while [ "$tries" -lt 6 ]; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull -q /sdcard/ui.xml /tmp/ui.xml 2>/dev/null || true
    XY=$(python3 - "$want" <<'PY'
import sys, xml.etree.ElementTree as ET
want = sys.argv[1]
try:
    root = ET.parse('/tmp/ui.xml').getroot()
except Exception:
    sys.exit(0)
for n in root.iter('node'):
    if (n.get('text') or '') == want or (n.get('content-desc') or '') == want:
        b = n.get('bounds', '')
        nums = b.replace('[', ' ').replace(']', ' ').replace(',', ' ').split()
        if len(nums) == 4:
            x = (int(nums[0]) + int(nums[2])) // 2
            y = (int(nums[1]) + int(nums[3])) // 2
            print(f"{x} {y}")
            break
PY
)
    if [ -n "$XY" ]; then
      adb shell input tap $XY
      return 0
    fi
    tries=$((tries + 1))
    sleep 3
  done
  echo "WARN: text '$want' not found after retries" >&2
  return 1
}

tap_text "I understand" || true
sleep 4
adb exec-out screencap -p > "$OUT/02-welcome.png"
tap_text "Continue" || true
sleep 10
adb exec-out screencap -p > "$OUT/03-home-top.png"
# Long-home capture: scroll down in steps, one shot per step.
adb shell input swipe 540 1600 540 600 400
sleep 3
adb exec-out screencap -p > "$OUT/04-home-scrolled-1.png"
adb shell input swipe 540 1600 540 600 400
sleep 3
adb exec-out screencap -p > "$OUT/05-home-scrolled-2.png"
# Back to top, settle, final home shot.
adb shell input keyevent 3
sleep 2
adb shell am start -n com.raddstream.app/.MainActivity
sleep 6
adb exec-out screencap -p > "$OUT/06-home-final.png"
sleep 2
kill $REC_PID 2>/dev/null || true
sleep 2
adb pull -q /sdcard/radd-demo.mp4 "$OUT/radd-demo.mp4" 2>/dev/null || echo "no video pulled"
ls -la "$OUT"
