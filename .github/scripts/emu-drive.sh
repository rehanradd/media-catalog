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
# Run 44059589013 (head 51440f80) failed the FOCUS wait — but the app
# WAS up: focus-timeout.png shows the real Disclaimer card with "I
# understand", and logcat shows MainActivity displayed at +2m16s. The
# guard lied. focused_pkg() took the FIRST mCurrentFocus line, which is
# a stale `mCurrentFocus=null` (no package, no "Not Responding"), so the
# mFocusedApp fallback never fired; and the fallback itself would have
# taken the FIRST mFocusedApp line, which is a stale googlesdksetup
# entry — the live one (the app) comes later in the dump. Fix: ignore
# null mCurrentFocus lines and take the LAST one; take the LAST
# mFocusedApp line. The dumpsys also shows an app ANR window
# ("Application Not Responding: com.raddstream.app.debug") and logcat
# shows a DNS failure (firebase-settings.crashlytics.com unresolvable)
# — the same failure shape as the Welcome catalog-unreachable in run
# 44048613561. The workflow now passes -dns-server 8.8.8.8 so the AVD
# does not depend on the host-provided 10.0.2.3 resolver.
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
# On-device scratch paths. Run 44030951147 proved /sdcard is NOT
# writable by shell on this AVD: `screenrecord /sdcard/radd-demo.mp4`
# died with "Permission denied", and every `uiautomator dump
# /sdcard/ui.xml` in tap_text silently failed the same way — the
# disclaimer WAS on screen (01-disclaimer.png shows the "I understand"
# button) yet tap_text never saw it in 6 tries. /data/local/tmp is
# world-writable on the emulator and has no FUSE/sdcard quirks.
UI_DUMP="/data/local/tmp/ui.xml"
VIDEO_PATH="/data/local/tmp/radd-demo.mp4"
# Set by screenshot(): name + md5 of the previous frame, for the
# byte-identical frozen-screen check.
PREV_SHOT=""
PREV_MD5=""

# Tap a UI node by its exact visible text (uiautomator dump -> centre tap).
tap_text() {
  local want="$1" tries=0
  while [ "$tries" -lt 10 ]; do
    # Never reuse a stale dump: a failed dump/pull must read as "no
    # ui.xml pulled" for THIS try, not silently match the previous
    # screen's nodes.
    rm -f /tmp/ui.xml
    # timeout 20: with system_server frozen (run 44043626083) the dump
    # hangs and the wait loop stalls for the whole budget; a killed dump
    # reads as "no ui.xml pulled", same as a failed one.
    timeout 20 adb shell uiautomator dump "$UI_DUMP" >/dev/null 2>&1
    adb pull -q "$UI_DUMP" /tmp/ui.xml 2>/dev/null || true
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
    # Diagnostics, not silence: if the dump is missing/empty the next
    # failure is diagnosable from the log instead of a bare WARN.
    # Run 44034109759: the app reached Welcome, then the app/System UI
    # ANR'd ("RADD Stream isn't responding" / "System UI isn't
    # responding") and the dialog covered the screen for all 6
    # 'Continue' tries — tap_text never saw 'Continue' because the
    # dialog was on top. Dismiss it inline (non-recursive: parse the
    # already-pulled dump for the "Wait" button) and retry, instead of
    # burning all 6 tries against a covered screen.
    if [ -f /tmp/ui.xml ] && grep -qi "isn't responding\|not responding" /tmp/ui.xml; then
      WXY=$(python3 - <<'PY'
import sys, xml.etree.ElementTree as ET
try:
    root = ET.parse('/tmp/ui.xml').getroot()
except Exception:
    sys.exit(0)
for n in root.iter('node'):
    if (n.get('text') or '') == 'Wait':
        b = n.get('bounds', '')
        nums = b.replace('[', ' ').replace(']', ' ').replace(',', ' ').split()
        if len(nums) == 4:
            x = (int(nums[0]) + int(nums[2])) // 2
            y = (int(nums[1]) + int(nums[3])) // 2
            print(f"{x} {y}")
            break
PY
)
      if [ -n "$WXY" ]; then
        echo "tap_text '$want': ANR dialog present — tapping Wait ($WXY)" >&2
        adb shell input tap $WXY
        # Run 44053140525: the Wait tap doesn't clear the dialog — only
        # system_server recovering does. Give it 15s before re-dumping
        # instead of 5s, or the retries burn out while it is still busy.
        sleep 15
      fi
    fi
    if [ -f /tmp/ui.xml ]; then
      echo "tap_text '$want' try $((tries + 1))/10: not found (ui.xml $(wc -c < /tmp/ui.xml) bytes, $(grep -o '<node' /tmp/ui.xml | wc -l) nodes)" >&2
    else
      echo "tap_text '$want' try $((tries + 1))/10: not found (no ui.xml pulled — dump to $UI_DUMP failed)" >&2
    fi
    tries=$((tries + 1))
    sleep 3
  done
  echo "WARN: text '$want' not found after retries" >&2
  # Keep the last dump as run evidence (uploaded with emu-shots/).
  if [ -f /tmp/ui.xml ]; then cp /tmp/ui.xml "$OUT/ui-dump-$(echo "$want" | tr ' ' '_').xml" 2>/dev/null || true; fi
  return 1
}

# Package currently in the foreground, or empty if it cannot be read.
focused_pkg() {
  local focus app
  # Run 44059589013: dumpsys window repeats these keys across sections
  # and the FIRST mCurrentFocus line is a stale `mCurrentFocus=null`
  # (no package, no "Not Responding"), which hid the live ANR window
  # line below it and kept the guard blind for the whole 180 s while
  # the app sat at the Disclaimer. Ignore null lines, take the LAST.
  focus=$(adb shell dumpsys window 2>/dev/null | grep -i 'mCurrentFocus' | grep -v '=null' | tail -n 1 || true)
  # Run 44043626083: mCurrentFocus was "Application Not Responding:
  # system" (no package in it) while mFocusedApp was the app — the
  # guard's job is "don't screenshot the launcher", and the app being
  # the focused app satisfies it, so the ANR window must not mask it.
  if echo "$focus" | grep -qi 'Not Responding'; then
    # Same stale-first trap: the first mFocusedApp line can be a
    # googlesdksetup entry while the live one (the app) comes later.
    app=$(adb shell dumpsys window 2>/dev/null | grep -i 'mFocusedApp' | tail -n 1 || true)
    [ -n "$app" ] && focus="$app"
  fi
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
  # timeout 20: with system_server frozen the dump hangs; a killed dump
  # reads as "no dialog seen", same as a failed one — the caller retries.
  timeout 20 adb shell uiautomator dump "$UI_DUMP" >/dev/null 2>&1 || return 1
  adb pull -q "$UI_DUMP" /tmp/ui.xml 2>/dev/null || return 1
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

# True if the current UI dump contains the given (case-insensitive)
# text. Timeout-bounded like the other dumps — a frozen system_server
# must not stall the caller.
ui_has_text() {
  rm -f /tmp/ui.xml
  timeout 20 adb shell uiautomator dump "$UI_DUMP" >/dev/null 2>&1
  adb pull -q "$UI_DUMP" /tmp/ui.xml 2>/dev/null || return 1
  grep -qi "$1" /tmp/ui.xml
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
      # Run 44040083866: the app launched (am start OK) but never
      # foregrounded, and left NO evidence — no screenshot, no logcat,
      # empty artifact (the upload step found nothing in $OUT). Dump
      # diagnostics into $OUT before failing so the next such failure
      # is diagnosable: current frame, window state, crash lines.
      adb exec-out screencap -p > "$OUT/focus-timeout.png" 2>/dev/null || true
      adb shell dumpsys window > "$OUT/focus-timeout-dumpsys-window.txt" 2>/dev/null || true
      adb logcat -d -t 300 2>/dev/null | grep -i -E 'raddstream|AndroidRuntime|FATAL|crash' > "$OUT/focus-timeout-logcat.txt" || true
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

# Run 44023968113 failed HERE, before any launch: the streamed install
# died with INSTALL_FAILED_VERIFICATION_FAILURE ("Integrity verification
# timed out") on the slow software emulator (boot alone took 634s).
# Disable the on-device package verifier for adb installs, then install
# non-streaming (push the APK file first, then install — avoids the
# streamed-verification timeout); fall back to a plain install if the
# adb on the runner predates --no-streaming, and retry plain once.
adb shell settings put global verifier_verify_adb_installs 0 >/dev/null 2>&1 || true
adb shell settings put global package_verifier_enable 0 >/dev/null 2>&1 || true
if ! adb install -r --no-streaming "$APK"; then
  echo "WARN: --no-streaming install failed, retrying plain install" >&2
  adb install -r "$APK"
fi
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
# The launcher ACTIVITY class is NOT "$APP_PKG.MainActivity": the
# manifest declares android:name=".MainActivity" against namespace
# com.raddstream.app, while the debug suffix only changes the PACKAGE.
# Run 44027036003 proved it: the plain install SUCCEEDED
# ("Installed package: com.raddstream.app.debug") but `am start -n
# com.raddstream.app.debug/.MainActivity` failed with "Error type 3 ...
# does not exist" — the real component is
# com.raddstream.app.debug/com.raddstream.app.MainActivity. Resolve
# the launcher from the device instead of guessing the class name;
# fall back to the old "$APP_PKG/.MainActivity" form if resolution
# finds nothing (other flavors may keep package == namespace).
MAIN_ACTIVITY=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$APP_PKG" 2>/dev/null | tr -d '\r' | grep '/' | tail -n 1 || true)
if [ -z "$MAIN_ACTIVITY" ]; then
  echo "WARN: resolve-activity found no launcher for $APP_PKG, falling back to $APP_PKG/.MainActivity" >&2
  MAIN_ACTIVITY="$APP_PKG/.MainActivity"
fi
echo "Installed package: $APP_PKG"
echo "Launcher activity: $MAIN_ACTIVITY"
# Start video capture in the background (killed after the flow).
adb shell screenrecord --time-limit "$SECONDS" "$VIDEO_PATH" &
REC_PID=$!
adb shell am start -n "$MAIN_ACTIVITY"
# The app must actually reach the foreground — on the software emulator
# the launcher can ANR here and the app never draws a frame (runs
# 44014294998 / 44013690073). Fail instead of screenshotting the launcher.
# 180s, not 120s: run 44040083866's emulator was slow throughout (boot
# 596s, install ~79s) and the app never foregrounded within 120s — the
# wait may simply have been too short for this runner, and the timeout
# path now dumps diagnostics (above) so a real crash is distinguishable
# from slowness.
wait_for_app_focus 180 || exit 1
sleep 4
screenshot "01-disclaimer.png"

# The two onboarding taps are the known flow (DisclaimerDialog
# confirmLabel "I understand", SetupScreen Button "Continue"). If either
# text never appears the flow is stuck — fail honestly instead of
# walking a dead screen and screenshotting it.
tap_text "I understand" || { echo "FAIL: disclaimer 'I understand' never appeared" >&2; exit 1; }
sleep 4
screenshot "02-welcome.png"
# Welcome probes the default catalog on arrival: if the probe fails the
# screen shows "We couldn't find the catalog at that address" and
# Continue stays DISABLED — tapping it is a no-op (run 44048613561).
# Retry the probe via Connect; if it still fails, capture diagnostics
# (logcat + in-emulator network probe) and fail clearly instead of
# letting the frozen-screen guard misreport a stuck Welcome as Home.
if ui_has_text "couldn't find the catalog"; then
  echo "Welcome: catalog unreachable — retrying via Connect" >&2
  for attempt in 1 2; do
    tap_text "Connect" || true
    sleep 20
    if ! ui_has_text "couldn't find the catalog"; then
      echo "Welcome: catalog reachable after Connect (attempt $attempt)" >&2
      break
    fi
    echo "Welcome: catalog still unreachable after Connect (attempt $attempt)" >&2
  done
  if ui_has_text "couldn't find the catalog"; then
    echo "FAIL: catalog unreachable from the emulator after Connect retries" >&2
    adb logcat -d -t 500 2>/dev/null | grep -i -E 'raddstream|UnknownHost|ConnectException|SocketTimeout|SSL|Handshake|AndroidRuntime|FATAL' > "$OUT/welcome-unreachable-logcat.txt" || true
    {
      echo "--- ping raddstream.duckdns.org ---"
      adb shell ping -c 1 -W 5 raddstream.duckdns.org 2>&1 || true
      echo "--- curl posts from the emulator ---"
      adb shell curl -s -o /dev/null -w "posts:%{http_code}\n" --max-time 15 "https://raddstream.duckdns.org/api/v1/posts?limit=1" 2>&1 || true
    } > "$OUT/welcome-network-probe.txt" 2>&1 || true
    adb exec-out screencap -p > "$OUT/welcome-unreachable.png" 2>/dev/null || true
    cp /tmp/ui.xml "$OUT/welcome-unreachable-ui.xml" 2>/dev/null || true
    exit 1
  fi
fi
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
adb pull -q "$VIDEO_PATH" "$OUT/radd-demo.mp4" 2>/dev/null || echo "no video pulled"
ls -la "$OUT"
