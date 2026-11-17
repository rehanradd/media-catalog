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
#
# FOREGROUND / ANR GUARDS (added 2026-11-17, branch
# muse/emulator-anr-guard-20261117): runs 44014294998 (main) and 44013690073
# reported SUCCESS but were FALSE-GREEN — every screenshot showed the
# Android launcher with a "Pixel Launcher isn't responding" ANR dialog
# ("Process system isn't responding"); the app never came to the
# foreground on the software emulator (no KVM) and the script happily
# screenshotted the launcher. This file now FAILS (non-zero exit) instead
# of screenshotting the launcher:
#   * wait_for_app_focus: after every `am start`, poll dumpsys until
#     the installed package is the focused package, dismissing ANR dialogs
#     ("Wait") as they appear; bounded wait, then fail loudly.
#   * screenshot(): refuses to capture unless the app is focused (one
#     ANR-dismiss + relaunch retry first), and fails if the new frame is
#     byte-identical to the previous one — every step between shots is a
#     tap/swipe/relaunch that must change the screen, so identical frames
#     mean a frozen launcher/ANR, not the app.
# Run 44020506293 (first run with the guards) FAILED HONESTLY — and
# proved the guards work AND found the real cause of the earlier
# false-greens: the debug build carries applicationIdSuffix ".debug"
# (brand.properties), so the installed package is
# com.raddstream.app.debug, but this script hardcoded
# com.raddstream.app — `am start` failed with "Error type 3 ... does
# not exist" and the app never launched at all. The old script
# swallowed that failure (|| true) and screenshotted the launcher.
# The package is now derived from the device after install (below).
# The workflow uploads emu-shots/ with `if: always()`, so a failed run
# still leaves its (launcher) frames as evidence of what went wrong.
set -euo pipefail

SECONDS="${1:-60}"
APK="App/app/build/outputs/apk/debug/app-debug.apk"
# $GITHUB_WORKSPACE is empty inside the runner's script context (run
# 44002960788); $PWD is the checkout when this file runs.
OUT="${GITHUB_WORKSPACE:-$PWD}/emu-shots"
mkdir -p "$OUT"
# Defaults; overwritten after install from the real package on the
# device (debug builds carry the ".debug" suffix — see below).
APP_PKG="com.raddstream.app"
MAIN_ACTIVITY="com.raddstream.app/.MainActivity"
# Set by screenshot(): name + md5 of the previous frame, for the
# byte-identical frozen-screen check.
PREV_SHOT=""
PREV_MD5=""

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

# Package currently in the foreground, or empty if it cannot be read.
focused_pkg() {
  local focus
  focus=$(adb shell dumpsys window 2>/dev/null | grep -i -m1 -E 'mCurrentFocus|mFocusedApp' || true)
  if [ -z "$focus" ]; then
    focus=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|ResumedActivity' || true)
  fi
  # `grep -o` prints EVERY match on the line (the package appears twice
  # in "pkg/pkg.Activity") and -m1 only limits lines — take the first.
  echo "$focus" | grep -o -E '[a-z][a-z0-9_]*(\.[a-z0-9_]+)+' | head -n 1 || true
}

is_app_focused() {
  [ "$(focused_pkg)" = "$APP_PKG" ]
}

# True if an "isn't responding" / "not responding" dialog is on screen.
anr_present() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  adb pull -q /sdcard/ui.xml /tmp/ui.xml 2>/dev/null || return 1
  grep -qi "isn't responding\|not responding" /tmp/ui.xml
}

# Tap "Wait" on an ANR dialog, up to 3 attempts. Returns 0 if no ANR
# remains (or none was present), 1 if the dialog is still there.
dismiss_anr() {
  local tries=0
  while [ "$tries" -lt 3 ]; do
    if ! anr_present; then
      return 0
    fi
    echo "ANR dialog detected (attempt $((tries + 1))/3) — tapping Wait" >&2
    tap_text "Wait" || true
    sleep 5
    tries=$((tries + 1))
  done
  if anr_present; then
    echo "FAIL: ANR dialog still present after 3 dismiss attempts" >&2
    return 1
  fi
  return 0
}

# Poll until the app is in the foreground ($1 = timeout seconds).
# Dismisses ANR dialogs as they appear. Returns 1 on timeout or on a
# stuck ANR — the caller fails the run.
wait_for_app_focus() {
  local timeout="${1:-120}" start now
  start=$(date +%s)
  while true; do
    dismiss_anr || return 1
    if is_app_focused; then
      echo "App is in foreground (focus: $(focused_pkg))"
      return 0
    fi
    now=$(date +%s)
    if [ $((now - start)) -ge "$timeout" ]; then
      echo "FAIL: app never came to foreground within ${timeout}s (focus now: '$(focused_pkg)')" >&2
      return 1
    fi
    sleep 5
  done
}

# Guard before a screenshot: the app must be focused. One ANR-dismiss +
# relaunch retry, then fail the run rather than screenshot the launcher.
assert_app_focused() {
  local step="$1"
  if is_app_focused; then return 0; fi
  echo "App not focused before '$step' (focus: '$(focused_pkg)') — dismissing ANR and relaunching" >&2
  dismiss_anr || true
  adb shell am start -n "$MAIN_ACTIVITY" >/dev/null 2>&1 || true
  if wait_for_app_focus 60; then return 0; fi
  echo "FAIL: app not in foreground before '$step'; refusing to screenshot the launcher" >&2
  return 1
}

# Capture one frame, guarded: app must be focused, and the frame must
# differ from the previous one (a tap/swipe/relaunch sits between every
# two shots, so a byte-identical frame means a frozen launcher/ANR).
screenshot() {
  local name="$1" md5
  assert_app_focused "$name" || exit 1
  adb exec-out screencap -p > "$OUT/$name"
  md5=$(md5sum "$OUT/$name" | cut -d' ' -f1)
  if [ -n "$PREV_MD5" ] && [ "$md5" = "$PREV_MD5" ]; then
    echo "FAIL: $name is byte-identical to $PREV_SHOT — screen is frozen (launcher/ANR), not the app" >&2
    exit 1
  fi
  PREV_SHOT="$name"
  PREV_MD5="$md5"
}

adb install -r "$APK"
# The debug build carries applicationIdSuffix ".debug"
# (brand.properties identity.application.id.debug.suffix), so the
# installed package is com.raddstream.app.debug — NOT com.raddstream.app.
# Run 44020506293 proved it: `am start -n com.raddstream.app/.MainActivity`
# failed with "Error type 3 ... does not exist" and the app never
# launched. Derive the real package from the device (adb output is
# CRLF, so strip \r before comparing), and fail if it is not there.
APP_PKG=$(adb shell pm list packages 2>/dev/null | tr -d '\r' | grep -o 'com\.raddstream\.app[^ ]*' | head -n 1 || true)
if [ -z "$APP_PKG" ]; then
  echo "FAIL: no com.raddstream.app* package found after install" >&2
  exit 1
fi
MAIN_ACTIVITY="$APP_PKG/.MainActivity"
echo "Installed package: $APP_PKG"
# Start video capture in the background (killed after the flow).
adb shell screenrecord --time-limit "$SECONDS" /sdcard/radd-demo.mp4 &
REC_PID=$!
adb shell am start -n "$MAIN_ACTIVITY"
# The app must actually reach the foreground — on the software emulator
# the launcher can ANR here and the app never draws a frame (runs
# 44014294998 / 44013690073). Fail instead of screenshotting the launcher.
wait_for_app_focus 120 || exit 1
sleep 4
screenshot "01-disclaimer.png"

# The two onboarding taps are the known flow (DisclaimerDialog
# confirmLabel "I understand", SetupScreen Button "Continue"). If either
# text never appears the flow is stuck — fail honestly instead of
# walking a dead screen and screenshotting it.
tap_text "I understand" || { echo "FAIL: disclaimer 'I understand' never appeared" >&2; exit 1; }
sleep 4
screenshot "02-welcome.png"
tap_text "Continue" || { echo "FAIL: setup 'Continue' never appeared" >&2; exit 1; }
sleep 10
screenshot "03-home-top.png"
# Long-home capture: scroll down in steps, one shot per step.
adb shell input swipe 540 1600 540 600 400
sleep 3
screenshot "04-home-scrolled-1.png"
adb shell input swipe 540 1600 540 600 400
sleep 3
screenshot "05-home-scrolled-2.png"
# Back to top, settle, final home shot.
adb shell input keyevent 3
sleep 2
adb shell am start -n "$MAIN_ACTIVITY"
sleep 6
screenshot "06-home-final.png"
sleep 2
kill $REC_PID 2>/dev/null || true
sleep 2
adb pull -q /sdcard/radd-demo.mp4 "$OUT/radd-demo.mp4" 2>/dev/null || echo "no video pulled"
ls -la "$OUT"
