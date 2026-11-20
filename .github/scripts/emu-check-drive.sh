#!/usr/bin/env bash
# Emulator check driver: install the debug APK, tap through Home -> Search
# -> Detail -> Live TV -> Downloads -> Settings, one screenshot per step,
# three video segments, full logcat. Invoked by
# .github/workflows/emulator-check.yml as ONE line
# (bash .github/scripts/emu-check-drive.sh <segment_seconds>) — it MUST stay
# a single invocation: android-emulator-runner executes the workflow's
# `script:` input line by line, each in a fresh `sh -c`, so shell variables
# (and functions) set on one line do NOT survive to the next. Run 44009742313
# proved it on the sibling script. Inside this file the whole flow runs in
# ONE shell, so state persists.
#
# SHAPE: the vars + helpers below (tap_text, focused_pkg, wait_for_app_focus,
# screenshot + the ANR/frozen-screen guards) are copied VERBATIM from
# .github/scripts/emu-drive.sh — same code, same run history, see that
# file's header for why each guard exists. The two scripts are intentionally
# separate files: emu-drive.sh serves the screenshots workflow (Disclaimer
# -> Home only); this one serves the check workflow (the full tap-through).
# If a helper is fixed in one, mirror it in the other — they drift otherwise.
#
# HONEST LIMITS: an emulator is not a real device (Robo v10 proved it:
# Discover Pakistan played from the server but never played on the virtual
# device) and video has no sound. This replaces Robo for UI proofs, not
# for playback proof.
#
set -euo pipefail

SECONDS="${1:-60}"
APK="App/app/build/outputs/apk/debug/app-debug.apk"
# $GITHUB_WORKSPACE is empty inside the runner's script context (run
# 44002960788); $PWD is the checkout when this file runs.
OUT="${GITHUB_WORKSPACE:-$PWD}/emu-check"
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
  # Keep the last dump as run evidence (uploaded with emu-check/).
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

# --- Flow: install, then tap through Home -> Search -> Detail -> Live TV
# -> Downloads -> Settings, one screenshot per step, three video segments,
# full logcat streamed to the artifact dir. ---
#
# Per-segment video length. $1 is the workflow input (default 120s);
# screenrecord itself caps at 180s, and a segment that runs long simply
# ends its recording early — the screenshots still cover every step.
# NOTE: the base vars above set SECONDS="$1", but bash treats SECONDS as a
# live timer (reading it returns 60 + elapsed), so it is NOT used here.
SEG_SECONDS="${1:-120}"

# --- Install (same proven sequence as emu-drive.sh) ---
adb shell settings put global verifier_verify_adb_installs 0 >/dev/null 2>&1 || true
adb shell settings put global package_verifier_enable 0 >/dev/null 2>&1 || true
if ! adb install -r --no-streaming "$APK"; then
  echo "WARN: --no-streaming install failed, retrying plain install" >&2
  adb install -r "$APK"
fi
# The debug build carries applicationIdSuffix ".debug" (brand.properties),
# so the installed package is com.raddstream.app.debug — derive it from
# the device instead of guessing (emu-drive.sh run 44020506293).
APP_PKG=$(adb shell pm list packages 2>/dev/null | tr -d '\r' | grep -o 'com\.raddstream\.app[^ ]*' | head -n 1 || true)
if [ -z "$APP_PKG" ]; then
  echo "FAIL: no com.raddstream.app* package found after install" >&2
  exit 1
fi
# The launcher ACTIVITY class is NOT "$APP_PKG.MainActivity": the manifest
# declares ".MainActivity" against namespace com.raddstream.app while the
# debug suffix only changes the PACKAGE (run 44027036003). Resolve it.
MAIN_ACTIVITY=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$APP_PKG" 2>/dev/null | tr -d '\r' | grep '/' | tail -n 1 || true)
if [ -z "$MAIN_ACTIVITY" ]; then
  echo "WARN: resolve-activity found no launcher for $APP_PKG, falling back to $APP_PKG/.MainActivity" >&2
  MAIN_ACTIVITY="$APP_PKG/.MainActivity"
fi
echo "Installed package: $APP_PKG"
echo "Launcher activity: $MAIN_ACTIVITY"

# --- Logcat: stream the whole run to the artifact dir from the start ---
# (background adb client; killed at the end). A crash anywhere in the flow
# is captured even if a later step fails the run outright.
adb logcat -v threadtime > "$OUT/logcat.txt" 2>/dev/null &
LOG_PID=$!

# --- Video segments: one screenrecord per section, pulled + validated ---
REC_PID=""
REC_NAME=""
start_video() {
  local name="$1"
  REC_NAME="$name"
  adb shell screenrecord --time-limit "$SEG_SECONDS" "/data/local/tmp/$name.mp4" &
  REC_PID=$!
}
# Stop the running recorder, pull the file, and check it actually has a
# moov atom (oranblock lesson: a recorder cut off mid-write leaves media
# data with no moov — plausible size, plays nowhere). An invalid file is
# kept as evidence and reported, not hidden; the screenshots are the gate.
stop_video() {
  if [ -n "${REC_PID:-}" ]; then
    kill "$REC_PID" 2>/dev/null || true
    sleep 2
    REC_PID=""
  fi
  if [ -n "${REC_NAME:-}" ]; then
    adb pull -q "/data/local/tmp/$REC_NAME.mp4" "$OUT/$REC_NAME.mp4" 2>/dev/null || echo "WARN: no video pulled for $REC_NAME" >&2
    if [ -f "$OUT/$REC_NAME.mp4" ]; then
      if python3 - "$OUT/$REC_NAME.mp4" <<'PY'
import sys
with open(sys.argv[1], 'rb') as f:
    data = f.read()
sys.exit(0 if b'moov' in data else 1)
PY
      then
        echo "VIDEO-OK: $REC_NAME.mp4 ($(du -h "$OUT/$REC_NAME.mp4" | cut -f1))"
      else
        echo "VIDEO-INVALID: $REC_NAME.mp4 has no moov atom (recorder cut off?) — file kept as evidence" >&2
      fi
    fi
    REC_NAME=""
  fi
}
# If the script dies mid-segment (set -e), pull whatever is recording so
# the failure still leaves its video behind.
cleanup() {
  if [ -n "${REC_PID:-}" ]; then kill "$REC_PID" 2>/dev/null || true; sleep 2; fi
  if [ -n "${REC_NAME:-}" ]; then adb pull -q "/data/local/tmp/$REC_NAME.mp4" "$OUT/$REC_NAME.mp4" 2>/dev/null || true; fi
  if [ -n "${LOG_PID:-}" ]; then kill "$LOG_PID" 2>/dev/null || true; fi
}
trap cleanup EXIT

# Tap a search-result row by its title text. The search field itself holds
# the same query text and sits higher in the dump, so the FIRST match would
# tap the field, not the result — take the LAST match, which is a result
# row (suggestions are gone once the query is submitted).
tap_result() {
  local want="$1" tries=0
  while [ "$tries" -lt 10 ]; do
    rm -f /tmp/ui.xml
    timeout 20 adb shell uiautomator dump "$UI_DUMP" >/dev/null 2>&1
    adb pull -q "$UI_DUMP" /tmp/ui.xml 2>/dev/null || true
    XY=$(python3 - "$want" <<'PY'
import sys, xml.etree.ElementTree as ET
want = sys.argv[1]
try:
    root = ET.parse('/tmp/ui.xml').getroot()
except Exception:
    sys.exit(0)
last = None
for n in root.iter('node'):
    if (n.get('text') or '') == want or (n.get('content-desc') or '') == want:
        b = n.get('bounds', '')
        nums = b.replace('[', ' ').replace(']', ' ').replace(',', ' ').split()
        if len(nums) == 4:
            x = (int(nums[0]) + int(nums[2])) // 2
            y = (int(nums[1]) + int(nums[3])) // 2
            last = f"{x} {y}"
if last:
    print(last)
PY
)
    if [ -n "$XY" ]; then
      adb shell input tap $XY
      return 0
    fi
    echo "tap_result '$want' try $((tries + 1))/10: not found" >&2
    tries=$((tries + 1))
    sleep 3
  done
  echo "WARN: result '$want' not found after retries" >&2
  return 1
}

# --- Onboarding: launch, Disclaimer -> Welcome -> Home ---
adb shell am start -n "$MAIN_ACTIVITY"
# The app must actually reach the foreground — on the software emulator
# the launcher can ANR here and the app never draws a frame (emu-drive.sh
# runs 44014294998 / 44013690073). Fail instead of screenshotting the launcher.
wait_for_app_focus 180 || exit 1
sleep 4
# Video starts here, not at launch: the launch wait is dead time (the app
# took 2m16s to foreground on run 44059589013) and would eat the whole
# first segment's time limit before a single app frame appears.
start_video "01-onboarding-home"
screenshot "01-disclaimer.png"
# The two onboarding taps are the known flow (DisclaimerDialog
# confirmLabel "I understand", SetupScreen Button "Continue"). If either
# text never appears the flow is stuck — fail honestly instead of walking
# a dead screen and screenshotting it.
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
screenshot "03-home.png"
adb shell input swipe 540 1600 540 600 400
sleep 3
screenshot "04-home-scrolled.png"
stop_video

# --- Search -> Detail ---
start_video "02-search-detail"
tap_text "Search" || { echo "FAIL: Search tab not found" >&2; exit 1; }
sleep 4
screenshot "05-search.png"
# The search field's prompt is "Search titles or cast" (placeholder + the
# field's content description). Type a title the catalog is known to hold
# (Robo v10 proved the One Piece detail page), submit with Enter, and
# open the first result row.
tap_text "Search titles or cast" || { echo "FAIL: search field not found" >&2; exit 1; }
sleep 2
adb shell input text "One%sPiece" # %s is adb's space escape; %20 stays literal ("One%20Piece", zero results — run 44701819731)
sleep 3
adb shell input keyevent 66
sleep 6
screenshot "06-search-results.png"
tap_result "One Piece" || { echo "FAIL: no 'One Piece' result to open" >&2; exit 1; }
sleep 6
screenshot "07-detail.png"
adb shell input swipe 540 1600 540 600 400
sleep 3
screenshot "08-detail-scrolled.png"
stop_video

# --- Live TV -> Downloads -> Settings ---
start_video "03-livetv-downloads-settings"
# Detail is not a bottom-nav route (no bar on it) — go Back first, then
# the Live TV tab is reachable.
adb shell input keyevent 4
sleep 3
tap_text "Live TV" || { echo "FAIL: Live TV tab not found" >&2; exit 1; }
sleep 8
screenshot "09-livetv.png"
adb shell input swipe 540 1600 540 600 400
sleep 3
screenshot "10-livetv-scrolled.png"
# Downloads has no bottom tab: it lives behind the DOWNLOADS tile in the
# "Your Library" strip at the bottom of the Home feed. Go Home, scroll
# until the tile is on screen, then tap it.
tap_text "Home" || { echo "FAIL: Home tab not found" >&2; exit 1; }
sleep 4
found=0
for i in 1 2 3 4 5 6 7 8; do
  if ui_has_text "DOWNLOADS"; then found=1; break; fi
  adb shell input swipe 540 1600 540 600 400
  sleep 2
done
if [ "$found" -ne 1 ]; then echo "FAIL: DOWNLOADS tile never appeared on Home" >&2; exit 1; fi
tap_text "DOWNLOADS" || { echo "FAIL: DOWNLOADS tile tap failed" >&2; exit 1; }
sleep 5
screenshot "11-downloads.png"
# Downloads is not a bottom-nav route either — Back, then Settings tab.
adb shell input keyevent 4
sleep 3
tap_text "Settings" || { echo "FAIL: Settings tab not found" >&2; exit 1; }
sleep 4
screenshot "12-settings.png"
adb shell input swipe 540 1600 540 600 400
sleep 3
screenshot "13-settings-scrolled.png"
stop_video

# --- Wrap up: finish the logcat stream, crash excerpt, inventory ---
if [ -n "${LOG_PID:-}" ]; then kill "$LOG_PID" 2>/dev/null || true; sleep 1; fi
grep -i -E 'AndroidRuntime|FATAL|crash' "$OUT/logcat.txt" | tail -100 > "$OUT/logcat-crashes.txt" || true
ls -la "$OUT"
echo "CHECK DONE: $(ls "$OUT"/*.png 2>/dev/null | wc -l) screenshots, $(ls "$OUT"/*.mp4 2>/dev/null | wc -l) videos, logcat $(wc -c < "$OUT/logcat.txt") bytes"
