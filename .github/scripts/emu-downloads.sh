#!/usr/bin/env bash
# Robo v21 — uiautomator hierarchy dump + coordinate-tap proof for the
# Settings > Downloads > "Open Downloads" button.
#
# Why this exists: Robo v16-v20 all failed at the same tap. v16-v19 used a
# Button+text descriptor and the click itself errored; v20 used a text-only
# descriptor and the tap was a no-op (Robo does an accessibility ACTION_CLICK
# on the matched node, and the inner Text node has no click action). The
# button is proven wired correctly from main (NavGraph.kt:579 navigates to
# Screen.Downloads.route, registered at line 629; the card is a plain
# non-clickable Column; the button is a standard always-enabled
# OutlinedButton). So the question is what the button's accessibility node
# actually looks like — its class, bounds, clickable/enabled flags, and
# whether any ancestor merges semantics or sits on top of it — and whether
# a REAL coordinate tap (adb input tap at the node's centre) navigates.
#
# What it does: installs the debug APK, taps through Disclaimer -> Welcome,
# opens Settings, scrolls until "Open Downloads" is on screen, dumps the
# uiautomator hierarchy, prints the button node's full attributes plus its
# ancestor chain, taps the button by coordinates, and dumps the hierarchy
# again to prove whether the Downloads screen opened. All outputs land in
# emu-downloads/ for artifact upload. No video (this is a diagnostic, not
# a demo); the run is short on purpose.
#
# Invoked by .github/workflows/emulator-downloads.yml as ONE line
# (bash .github/scripts/emu-downloads.sh) — it MUST stay a single
# invocation: android-emulator-runner executes the workflow's `script:`
# input line by line, each in a fresh `sh -c`, so shell variables (and
# functions) set on one line do NOT survive to the next. Inside this file
# the whole flow runs in ONE shell, so state persists. (Same lesson as
# emu-drive.sh, run 44009742313.)
#
# PACKAGE FIX (2026-11-22, run 44990207302): this script originally
# hardcoded the base package in its launch command
# (`adb shell am start -n <base-pkg>/.MainActivity`),
# but the debug build carries applicationIdSuffix ".debug", so the
# installed package is com.raddstream.app.debug and the launch failed
# with "Error type 3 ... does not exist" — the app never launched and
# the run burned its whole budget tapping the launcher (the 4 artifact
# screenshots were the launcher with an ANR dialog). The 45-min timeout
# was only the proximate kill. The guards below are ported verbatim
# from emu-drive.sh, which solved this exact trap (runs 44020506293,
# 44059589013): derive the real package from the device after install,
# resolve the launcher activity from the device, wait for app focus
# (dismissing ANR dialogs), and refuse to screenshot the launcher.
# The 120-min run dispatched on the unfixed script (44994309471) was
# cancelled before it could burn 2 hours the same way.
set -euo pipefail

APK="App/app/build/outputs/apk/debug/app-debug.apk"
# $GITHUB_WORKSPACE is empty inside the runner's script context (run
# 44002960788); $PWD is the checkout when this file runs.
OUT="${GITHUB_WORKSPACE:-$PWD}/emu-downloads"
mkdir -p "$OUT"
# Defaults; overwritten after install from the real package on the
# device (debug builds carry the ".debug" suffix — see below).
APP_PKG="com.raddstream.app"
MAIN_ACTIVITY="$APP_PKG/.MainActivity"
# On-device scratch path. Run 44030951147 proved the sdcard dump path is NOT
# writable by shell on this AVD: dumps there silently failed, so tap_text
# never saw the node it was looking for even when it was on screen. /data/local/tmp is world-writable on
# the emulator and has no FUSE/sdcard quirks. The hierarchy dumps
# (ui-settings-downloads.xml, ui-after-tap.xml) use it too.
UI_DUMP="/data/local/tmp/ui.xml"
UI_SETTINGS="/data/local/tmp/ui-settings-downloads.xml"
UI_AFTER="/data/local/tmp/ui-after-tap.xml"
# Set by screenshot(): name + md5 of the previous frame, for the
# byte-identical frozen-screen check.
PREV_SHOT=""
PREV_MD5=""

# Tap a UI node by its exact visible text (uiautomator dump -> centre tap).
# A REAL coordinate tap, not an accessibility click, so it is the closest
# thing to a user's finger. Ported from emu-drive.sh (10 tries, bounded
# dumps, stale-dump removal, inline ANR dismissal, per-try diagnostics).
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
    # burning all 10 tries against a covered screen.
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
  # Keep the last dump as run evidence (uploaded with emu-downloads/).
  if [ -f /tmp/ui.xml ]; then cp /tmp/ui.xml "$OUT/ui-dump-$(echo "$want" | tr ' ' '_').xml" 2>/dev/null || true; fi
  return 1
}

# Package currently in the foreground, or empty if it cannot be read.
# Ported from emu-drive.sh (run 44059589013 fix: ignore null
# mCurrentFocus lines and take the LAST one; take the LAST mFocusedApp
# line — the FIRST lines are stale entries that hide the live window).
focused_pkg() {
  local focus app
  focus=$(adb shell dumpsys window 2>/dev/null | grep -i 'mCurrentFocus' | grep -v '=null' | tail -n 1 || true)
  # Run 44043626083: mCurrentFocus was "Application Not Responding:
  # system" (no package in it) while mFocusedApp was the app — the
  # guard's job is "don't screenshot the launcher", and the app being
  # the focused app satisfies it, so the ANR window must not mask it.
  if echo "$focus" | grep -qi 'Not Responding'; then
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
# NOTE: use this only for shots that MUST follow a screen transition
# (01-04). The post-scroll (05) and post-tap (06) shots use a plain
# screencap after assert_app_focused instead: if the scroll found the
# button already on screen, or the tap did NOT navigate (the very
# outcome this run is testing), the frame is legitimately identical to
# the previous one — and the byte-identical check would kill the run
# before the verdict is written.
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

# Capture one frame with the focus guard only (no byte-identical
# check): for shots where an unchanged frame is a legitimate test
# outcome, not a frozen screen (05 after scrolling, 06 after the tap).
screenshot_plain() {
  local name="$1"
  assert_app_focused "$name" || exit 1
  adb exec-out screencap -p > "$OUT/$name"
  PREV_SHOT="$name"
  PREV_MD5=$(md5sum "$OUT/$name" | cut -d' ' -f1)
}

# Scroll the Settings list until the wanted text's centre is inside the
# viewport (Pixel 7 is 1080x2400). Presence alone is not enough: a
# vertically-scrolling Column composes all its children, so an off-screen
# node is in the hierarchy but its tap would miss.
scroll_to_text() {
  local want="$1" i=0
  while [ "$i" -lt 12 ]; do
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
for n in root.iter('node'):
    if (n.get('text') or '') == want:
        b = n.get('bounds', '')
        nums = b.replace('[', ' ').replace(']', ' ').replace(',', ' ').split()
        if len(nums) == 4:
            x = (int(nums[0]) + int(nums[2])) // 2
            y = (int(nums[1]) + int(nums[3])) // 2
            if 0 <= x < 1080 and 0 <= y < 2400:
                print(f"{x} {y}")
                break
PY
)
    if [ -n "$XY" ]; then
      echo "on screen: $want at $XY"
      return 0
    fi
    # Same ANR trap as tap_text: a dialog on top hides the node.
    if [ -f /tmp/ui.xml ] && grep -qi "isn't responding\|not responding" /tmp/ui.xml; then
      dismiss_anr || true
    fi
    adb shell input swipe 540 1600 540 700 400
    sleep 2
    i=$((i + 1))
  done
  echo "WARN: '$want' not on screen after scrolling" >&2
  return 1
}

# Print a node's full attributes plus its ancestor chain (nearest first).
# The ancestors are what would hide or merge the button: a clickable parent,
# a semantics merge, or an overlapping node on top of it.
dump_node() {
  local want="$1" xml="$2"
  python3 - "$want" "$xml" <<'PY'
import sys, xml.etree.ElementTree as ET
want, path = sys.argv[1], sys.argv[2]
root = ET.parse(path).getroot()
parent = {c: p for p in root.iter() for c in p}
matches = [n for n in root.iter('node') if (n.get('text') or '') == want]
print(f"nodes with text {want!r}: {len(matches)}")
for idx, n in enumerate(matches):
    print(f"--- match {idx} ---")
    for k in ('class', 'text', 'content-desc', 'bounds', 'clickable',
              'enabled', 'focusable', 'checkable', 'checked', 'scrollable',
              'long-clickable', 'selected', 'visible-to-user'):
        print(f"  {k}={n.get(k)}")
    print("  ancestor chain (nearest first):")
    p = parent.get(n)
    depth = 0
    while p is not None and p.tag == 'node' and depth < 12:
        attrs = {k: p.get(k) for k in
                 ('class', 'bounds', 'clickable', 'enabled', 'scrollable')
                 if p.get(k) is not None}
        print(f"    {attrs}")
        p = parent.get(p)
        depth += 1
print("--- all clickable Button-class nodes ---")
for n in root.iter('node'):
    cls = n.get('class') or ''
    if 'Button' in cls and n.get('clickable') == 'true':
        print(f"  {cls} text={n.get('text')!r} bounds={n.get('bounds')} enabled={n.get('enabled')}")
PY
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
# Run 44020506293 proved it: launching with the base package
# (`am start -n <base-pkg>/.MainActivity`) failed with "Error type 3 ...
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

# The onboarding taps are the known flow (DisclaimerDialog confirmLabel
# "I understand", SetupScreen Button "Continue", then "Done"). If any
# text never appears the flow is stuck — fail honestly instead of
# walking a dead screen and screenshotting it.
tap_text "I understand" || { echo "FAIL: disclaimer 'I understand' never appeared" >&2; exit 1; }
sleep 4
screenshot "02-welcome.png"
tap_text "Continue" || { echo "FAIL: setup 'Continue' never appeared" >&2; exit 1; }
sleep 8
tap_text "Done" || { echo "FAIL: setup 'Done' never appeared" >&2; exit 1; }
sleep 3
screenshot "03-home.png"
tap_text "Settings" || { echo "FAIL: 'Settings' never appeared" >&2; exit 1; }
sleep 4
screenshot "04-settings-top.png"

scroll_to_text "Open Downloads" || true
sleep 2
screenshot_plain "05-settings-downloads.png"

# The evidence: hierarchy with the button on screen, plus its node report.
timeout 20 adb shell uiautomator dump "$UI_SETTINGS" >/dev/null 2>&1
adb pull -q "$UI_SETTINGS" "$OUT/ui-settings-downloads.xml" || echo "no hierarchy pulled"
dump_node "Open Downloads" "$OUT/ui-settings-downloads.xml" > "$OUT/open-downloads-node.txt" 2>&1 || echo "node report failed"
cat "$OUT/open-downloads-node.txt"

# The proof: a real coordinate tap on the button, then check where we land.
# Tolerant on purpose: if the tap does not navigate, the verdict below
# says NO — that is the answer this run exists to produce, not a failure.
tap_text "Open Downloads" && echo "tap returned 0" || echo "tap returned non-zero"
sleep 5
screenshot_plain "06-after-tap.png"
timeout 20 adb shell uiautomator dump "$UI_AFTER" >/dev/null 2>&1
adb pull -q "$UI_AFTER" "$OUT/ui-after-tap.xml" || echo "no post-tap hierarchy pulled"

# Verdict: the Downloads screen's signature is its TopAppBar title
# "Downloads" plus the empty-state "Nothing downloaded yet" and the
# "Find something to download" button (DownloadsScreen.kt:83,168,183).
# The Settings button "Open Downloads" must be gone — we left Settings.
python3 - "$OUT/ui-after-tap.xml" > "$OUT/verdict.txt" 2>&1 <<'PY'
import sys, xml.etree.ElementTree as ET
path = sys.argv[1]
try:
    root = ET.parse(path).getroot()
except Exception as e:
    print(f"VERDICT: UNKNOWN — could not parse {path}: {e}")
    sys.exit(0)
texts = [(n.get('text') or '') for n in root.iter('node')]
has_title = 'Downloads' in texts
has_empty = 'Nothing downloaded yet' in texts
has_find = 'Find something to download' in texts
has_storage = 'Device storage' in texts
still_settings = 'Open Downloads' in texts
print(f"TopAppBar title 'Downloads': {has_title}")
print(f"Empty state 'Nothing downloaded yet': {has_empty}")
print(f"Button 'Find something to download': {has_find}")
print(f"Section 'Device storage': {has_storage}")
print(f"Settings button 'Open Downloads' still present: {still_settings}")
if has_empty and has_find and not still_settings:
    print("VERDICT: YES — coordinate tap reached the Downloads screen.")
elif has_title and not still_settings:
    print("VERDICT: LIKELY — on a Downloads-titled screen, Settings button gone.")
elif still_settings:
    print("VERDICT: NO — still on Settings, the tap did not navigate.")
else:
    print("VERDICT: UNCLEAR — neither screen's signature matched; check 06-after-tap.png.")
PY
cat "$OUT/verdict.txt"
ls -la "$OUT"
