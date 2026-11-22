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
# DUMP FIX (2026-11-22, run 44995080198): `uiautomator dump` CANNOT
# complete in 20s under TCG software emulation — all 10 tap_text tries
# for 'I understand' failed with "no ui.xml pulled", and each try took
# ~20s (the `timeout 20` killing the dump mid-flight, not a fast
# failure: 07:01:50, 07:02:10, 07:02:32, ...). The dump's own stderr
# was discarded (>/dev/null), so the reason was invisible, and the app
# was still on its splash (01-disclaimer.png = logo only, no text) the
# whole time — the script went straight from focus-confirm to tap_text
# with no wait for the UI to settle. Fix: a 60s dump budget (3x, still
# bounded so a frozen system_server cannot stall the run), dump
# stdout+stderr captured and logged on failure (timeout vs error is
# distinguishable), and wait_for_ui_ready() below, which proves dumps
# work AND the UI has rendered text before any tap loop starts.
# V22 FIX (2026-11-22, run 44998141138): the dump fix was only partial —
# dumps pull sometimes (12/22 nodes) but the run still failed, on TWO
# blockers the screenshots prove:
# (A) "System UI isn't responding" ANR over the Disclaimer. The Wait tap
#     at the hierarchy-derived centre (540,1399) DOES eventually work —
#     'I understand' succeeded on try 7 after 6 Wait taps (~4 min) —
#     but the old inline handling burned the tap_text 10-try budget on
#     it and never verified the dialog was gone. Fix: wait_for_anr_clear()
#     taps Wait, re-dumps, and verifies the dialog is actually gone,
#     retrying until it clears (300s budget) instead of assuming one tap
#     worked; tap_text hands off to it and fails honestly if it never
#     clears. dismiss_anr() delegates to it too.
# (B) The Welcome screen dead-ends when the catalog is unreachable.
#     Default catalog is https://raddstream.duckdns.org
#     (brand.properties network.default_server_url; live and reachable
#     from the Oracle VM and the public internet). The runner emulator
#     could not reach it in run 5 — most likely the probe timing out
#     under slow TCG, not a wrong address (the CI emulator reached the
#     catalog before, run 13 on ac128da8, so the address is right and the
#     runner can reach it in principle). The screen greys out "Continue"
#     (enabled=false) so the script's Continue -> Done path can never
#     proceed — and tap_text matched the greyed button by text alone,
#     tapped its coordinates, and reported success for a no-op. Fix:
#     (1) tap_text now refuses to tap a disabled node (own or any
#     ancestor enabled=false) and reports it as "present but DISABLED";
#     (2) wait_for_welcome_settle() waits for the probe to settle, then
#     branches — "Continue offline — saved titles only" when the catalog
#     is unreachable (onConnected(false) skips Region/"Done" and opens
#     Home directly), else the normal Continue -> Done path.
DUMP_TIMEOUT=60
# Set by screenshot(): name + md5 of the previous frame, for the
# byte-identical frozen-screen check.
PREV_SHOT=""
PREV_MD5=""

# Dump the current UI hierarchy to /tmp/ui.xml (fresh each call — never
# reuse a stale dump). Returns 0 iff a non-empty, XML-parseable dump was
# pulled; 1 otherwise. Unlike the old inline `timeout 20 ... >/dev/null
# 2>&1`, the dump's stdout+stderr are captured to /tmp/dump.err and the
# failure mode is logged: a `timeout` kill (exit 124) reads as "timed
# out after ${DUMP_TIMEOUT}s", any other nonzero exit logs the device's
# own error text, and a pull failure is logged separately. Callers must
# NOT silence this function's stderr — the whole point is that the next
# dump failure is diagnosable from the run log.
dump_ui() {
  rm -f /tmp/ui.xml /tmp/dump.err
  timeout "$DUMP_TIMEOUT" adb shell uiautomator dump "$UI_DUMP" > /tmp/dump.err 2>&1
  local rc=$?
  if [ "$rc" -eq 124 ]; then
    echo "dump_ui: uiautomator dump TIMED OUT after ${DUMP_TIMEOUT}s (system_server slow under TCG?)" >&2
    return 1
  fi
  if [ "$rc" -ne 0 ]; then
    echo "dump_ui: uiautomator dump failed (exit $rc): $(head -c 300 /tmp/dump.err | tr '\n' ' ')" >&2
    return 1
  fi
  adb pull -q "$UI_DUMP" /tmp/ui.xml 2>/dev/null || { echo "dump_ui: dump wrote $UI_DUMP but pull failed" >&2; return 1; }
  if [ ! -s /tmp/ui.xml ]; then
    echo "dump_ui: pulled ui.xml is empty" >&2
    return 1
  fi
  python3 -c "import xml.etree.ElementTree as ET; ET.parse('/tmp/ui.xml')" 2>/dev/null || { echo "dump_ui: pulled ui.xml does not parse as XML" >&2; return 1; }
  return 0
}

# Wait until a UI dump succeeds AND the hierarchy contains at least one
# node with non-empty text — i.e. the app has rendered a real screen,
# not just its splash (the splash is a bare logo: 0 text nodes, run
# 44995080198). Bounded to $1 seconds (default 180). Logs each attempt
# so the run log shows whether dumps were failing or the app was simply
# still loading. Returns 1 on timeout, after saving a screenshot and
# the last dump diagnostics into $OUT.
wait_for_ui_ready() {
  local budget="${1:-180}" start now tries=0
  start=$(date +%s)
  while true; do
    tries=$((tries + 1))
    if dump_ui 2>/tmp/wait-dump.err; then
      local text_nodes
      text_nodes=$(python3 - <<'PY' 2>/dev/null
import sys, xml.etree.ElementTree as ET
try:
    root = ET.parse('/tmp/ui.xml').getroot()
except Exception:
    sys.exit(0)
print(sum(1 for n in root.iter('node') if (n.get('text') or '').strip()))
PY
)
      if [ "${text_nodes:-0}" -gt 0 ]; then
        echo "UI ready after ${tries} dump(s): $text_nodes text node(s) on screen"
        return 0
      fi
      echo "wait_for_ui_ready try $tries: dump ok but 0 text nodes (splash still showing?)" >&2
    else
      echo "wait_for_ui_ready try $tries: $(cat /tmp/wait-dump.err | head -c 200 | tr '\n' ' ')" >&2
    fi
    now=$(date +%s)
    if [ $((now - start)) -ge "$budget" ]; then
      echo "FAIL: UI never became ready within ${budget}s (no dump with text nodes)" >&2
      adb exec-out screencap -p > "$OUT/ui-ready-timeout.png" 2>/dev/null || true
      cp /tmp/wait-dump.err "$OUT/ui-ready-timeout-dump.err" 2>/dev/null || true
      cp /tmp/ui.xml "$OUT/ui-ready-timeout-ui.xml" 2>/dev/null || true
      return 1
    fi
    sleep 5
  done
}

# Tap a UI node by its exact visible text (uiautomator dump -> centre tap).
# A REAL coordinate tap, not an accessibility click, so it is the closest
# thing to a user's finger. Ported from emu-drive.sh (10 tries, bounded
# dumps, stale-dump removal, inline ANR dismissal, per-try diagnostics).
tap_text() {
  local want="$1" tries=0
  while [ "$tries" -lt 10 ]; do
    # dump_ui() removes the stale dump and logs the failure mode; a
    # failed dump reads as "no ui.xml pulled" for THIS try, not a
    # silent match against the previous screen's nodes.
    if ! dump_ui 2>/tmp/tap-dump.err; then
      echo "tap_text '$want' try $((tries + 1))/10: not found ($(cat /tmp/tap-dump.err | head -c 200 | tr '\n' ' '))" >&2
      tries=$((tries + 1))
      sleep 3
      continue
    fi
    XY=$(python3 - "$want" <<'PY'
import sys, xml.etree.ElementTree as ET
want = sys.argv[1]
try:
    root = ET.parse('/tmp/ui.xml').getroot()
except Exception:
    sys.exit(0)
parent = {c: p for p in root.iter() for c in p}
disabled_seen = False
for n in root.iter('node'):
    if (n.get('text') or '') == want or (n.get('content-desc') or '') == want:
        # V22 (run 44998141138): a disabled control must never be
        # "tapped" — a real user's tap on it does nothing, and tap_text
        # would report success for a no-op ('Continue' was greyed on the
        # Welcome screen because the catalog was unreachable, yet
        # tap_text tapped its coordinates and returned 0). Skip a node
        # whose own enabled flag or any ancestor's is "false"; a missing
        # flag counts as enabled. A Text inside an enabled Button still
        # matches (the Text and its Button ancestor are both enabled).
        m, p = n, parent.get(n)
        disabled = (m.get('enabled') == 'false')
        while p is not None and p.tag == 'node' and not disabled:
            disabled = (p.get('enabled') == 'false')
            p = parent.get(p)
        if disabled:
            disabled_seen = True
            continue
        b = n.get('bounds', '')
        nums = b.replace('[', ' ').replace(']', ' ').replace(',', ' ').split()
        if len(nums) == 4:
            x = (int(nums[0]) + int(nums[2])) // 2
            y = (int(nums[1]) + int(nums[3])) // 2
            print(f"{x} {y}")
            break
else:
    if disabled_seen:
        print("DISABLED")
PY
)
    if [ "$XY" = "DISABLED" ]; then
      echo "tap_text '$want' try $((tries + 1))/10: not found (present but DISABLED — e.g. Continue greyed while catalog unreachable)" >&2
      tries=$((tries + 1))
      sleep 3
      continue
    fi
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
    # dialog was on top.
    # V22 (run 44998141138): the old inline handling tapped Wait once
    # and slept 15s, never verifying the dialog was gone — the Wait tap
    # only works once system_server recovers, which took 6 tries
    # (~4 min) for 'I understand', burning the tap budget. Hand off to
    # wait_for_anr_clear(), which taps Wait, re-dumps, and verifies the
    # dialog is actually gone, retrying until it clears (300s budget).
    # If it never clears, fail honestly instead of burning all 10 tries.
    if [ -f /tmp/ui.xml ] && grep -qi "isn't responding\|not responding" /tmp/ui.xml; then
      if wait_for_anr_clear 300; then
        echo "tap_text '$want': ANR cleared — re-dumping for '$want'" >&2
      else
        echo "FAIL: tap_text '$want': ANR dialog never cleared within 300s" >&2
        return 1
      fi
    fi
    # dump_ui() succeeded here, so /tmp/ui.xml exists and parses — the
    # text is genuinely not on this screen (not a dump failure; those
    # `continue` above with their own reason logged).
    echo "tap_text '$want' try $((tries + 1))/10: not found (ui.xml $(wc -c < /tmp/ui.xml) bytes, $(grep -o '<node' /tmp/ui.xml | wc -l) nodes)" >&2
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
  # dump_ui() bounds the dump (a frozen system_server must not stall
  # the caller); a failed dump reads as "no dialog seen" and the
  # caller retries.
  dump_ui 2>/dev/null || return 1
  grep -qi "isn't responding\|not responding" /tmp/ui.xml
}

# Wait until no "isn't responding" dialog is on screen, tapping "Wait"
# (at the hierarchy-derived centre, never fixed coords) and VERIFYING
# the dialog is actually gone by re-dumping after each tap. Bounded to
# $1 seconds (default 300). Returns 0 when the dialog is gone, 1 on
# timeout — and saves a screenshot + the last dump into $OUT so a stuck
# ANR is diagnosable. V22 (run 44998141138): the old "tap Wait once and
# sleep" never verified anything — the Wait tap only works once
# system_server recovers, which took ~4 min for 'I understand'. This
# loop is the patience the dialog needs, with proof at the end.
wait_for_anr_clear() {
  local budget="${1:-300}" start now tries=0
  start=$(date +%s)
  while true; do
    tries=$((tries + 1))
    if ! dump_ui 2>/tmp/anr-dump.err; then
      echo "wait_for_anr_clear try $tries: dump failed ($(cat /tmp/anr-dump.err | head -c 150 | tr '\n' ' ')) — retrying" >&2
    elif ! grep -qi "isn't responding\|not responding" /tmp/ui.xml; then
      echo "ANR dialog cleared after $tries check(s)"
      return 0
    else
      local wxy
      wxy=$(python3 - <<'PY'
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
      if [ -n "$wxy" ]; then
        echo "wait_for_anr_clear try $tries: ANR present — tapping Wait ($wxy)" >&2
        adb shell input tap $wxy
      else
        echo "wait_for_anr_clear try $tries: ANR present but no 'Wait' node in dump — waiting" >&2
      fi
    fi
    now=$(date +%s)
    if [ $((now - start)) -ge "$budget" ]; then
      echo "FAIL: ANR dialog still present after ${budget}s ($tries checks)" >&2
      adb exec-out screencap -p > "$OUT/anr-timeout.png" 2>/dev/null || true
      cp /tmp/ui.xml "$OUT/anr-timeout-ui.xml" 2>/dev/null || true
      return 1
    fi
    sleep 15
  done
}

# Dismiss an ANR dialog if one is present. Returns 0 if no ANR remains
# (or none was present), 1 if the dialog is still there. Delegates to
# wait_for_anr_clear(), which verifies the dialog is actually gone.
dismiss_anr() {
  if ! anr_present; then
    return 0
  fi
  wait_for_anr_clear 180
}

# True if the current UI dump contains the given (case-insensitive)
# text. Dump-bounded like the other dumps — a frozen system_server
# must not stall the caller.
ui_has_text() {
  dump_ui 2>/dev/null || return 1
  grep -qi "$1" /tmp/ui.xml
}

# True (0) if /tmp/ui.xml — already pulled by the caller, never dumped
# here — contains a node with the given exact text that is enabled (the
# node itself and every ancestor have enabled != "false"; a missing
# flag counts as enabled). A disabled Button's Text child counts as
# disabled, so a greyed "Continue" reads as not-enabled.
continue_enabled() {
  local want="$1"
  python3 - "$want" <<'PY'
import sys, xml.etree.ElementTree as ET
want = sys.argv[1]
try:
    root = ET.parse('/tmp/ui.xml').getroot()
except Exception:
    sys.exit(1)
parent = {c: p for p in root.iter() for c in p}
for n in root.iter('node'):
    if (n.get('text') or '') == want or (n.get('content-desc') or '') == want:
        m, p = n, parent.get(n)
        disabled = (m.get('enabled') == 'false')
        while p is not None and p.tag == 'node' and not disabled:
            disabled = (p.get('enabled') == 'false')
            p = parent.get(p)
        if not disabled:
            sys.exit(0)
sys.exit(1)
PY
}

# Wait for the Welcome screen's catalog probe to settle, then print the
# branch the flow must take: "offline" if the probe failed
# (catalog-unreachable error text on screen) or "online" if it
# succeeded ("Continue" node enabled). Diagnostics go to stderr; only
# the one word goes to stdout. Bounded to $1 seconds (default 300).
# On timeout, falls back to "offline" if the "Continue offline" button
# is on screen (the probe likely failed and the error text was missed),
# else fails. V22 (run 44998141138): the old flow tapped "Continue"
# blindly — on a greyed button that tap is a no-op, and "Done" (which
# only exists past Continue) never appeared.
wait_for_welcome_settle() {
  local budget="${1:-300}" start now
  start=$(date +%s)
  while true; do
    if dump_ui 2>/tmp/welcome-dump.err; then
      if grep -qi "couldn't find the catalog" /tmp/ui.xml; then
        echo "Welcome: catalog-unreachable error on screen" >&2
        echo "offline"
        return 0
      fi
      if continue_enabled "Continue"; then
        echo "Welcome: Continue is enabled (catalog reachable)" >&2
        echo "online"
        return 0
      fi
      echo "Welcome: probe still running (no error, Continue not enabled yet)" >&2
    else
      echo "Welcome: dump failed ($(cat /tmp/welcome-dump.err | head -c 150 | tr '\n' ' ')) — retrying" >&2
    fi
    now=$(date +%s)
    if [ $((now - start)) -ge "$budget" ]; then
      if [ -f /tmp/ui.xml ] && grep -qi "Continue offline" /tmp/ui.xml; then
        echo "Welcome: settle timed out after ${budget}s — 'Continue offline' present, taking offline branch" >&2
        echo "offline"
        return 0
      fi
      echo "FAIL: Welcome screen never settled within ${budget}s" >&2
      return 1
    fi
    sleep 5
  done
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
    if ! dump_ui 2>/tmp/scroll-dump.err; then
      echo "scroll_to_text '$want': dump failed ($(cat /tmp/scroll-dump.err | head -c 200 | tr '\n' ' '))" >&2
      i=$((i + 1))
      sleep 2
      continue
    fi
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
# DUMP FIX (run 44995080198): focus only means the app is foregrounded —
# under TCG it can sit on its splash (logo, 0 text nodes) for minutes
# while cold-starting. Prove a dump works AND the UI has rendered text
# before the first tap loop, or the taps burn out against a splash.
wait_for_ui_ready 180 || exit 1
sleep 4
screenshot "01-disclaimer.png"

# The onboarding taps are the known flow (DisclaimerDialog confirmLabel
# "I understand", SetupScreen Button "Continue", then "Done"). If any
# text never appears the flow is stuck — fail honestly instead of
# walking a dead screen and screenshotting it.
tap_text "I understand" || { echo "FAIL: disclaimer 'I understand' never appeared" >&2; exit 1; }
sleep 4
screenshot "02-welcome.png"
# V22 (run 44998141138): the Welcome screen dead-ends when the catalog is
# unreachable — "Continue" is greyed (enabled=false) and "Done" never
# appears, because the Continue -> Done path assumes a reachable catalog.
# The screen itself offers the way out: "Continue offline — saved titles
# only" (shown exactly when !isConnected; SetupScreen.kt). Wait for the
# probe to settle, then branch: offline goes straight to Home
# (onConnected(false) skips the Region/"Done" screen), online takes the
# normal Continue -> Done path.
WELCOME_MODE=$(wait_for_welcome_settle 300) || { echo "FAIL: Welcome screen never settled" >&2; exit 1; }
if [ "$WELCOME_MODE" = "offline" ]; then
  echo "Welcome branch: catalog unreachable — tapping 'Continue offline'" >&2
  tap_text "Continue offline — saved titles only" || { echo "FAIL: 'Continue offline' never appeared" >&2; exit 1; }
  # Offline path skips Region/"Done" — Home opens directly.
elif [ "$WELCOME_MODE" = "online" ]; then
  echo "Welcome branch: catalog reachable — tapping 'Continue' then 'Done'" >&2
  tap_text "Continue" || { echo "FAIL: setup 'Continue' never appeared" >&2; exit 1; }
  sleep 8
  tap_text "Done" || { echo "FAIL: setup 'Done' never appeared" >&2; exit 1; }
else
  echo "FAIL: unknown Welcome branch '$WELCOME_MODE'" >&2; exit 1
fi
sleep 3
screenshot "03-home.png"
tap_text "Settings" || { echo "FAIL: 'Settings' never appeared" >&2; exit 1; }
sleep 4
screenshot "04-settings-top.png"

scroll_to_text "Open Downloads" || true
sleep 2
screenshot_plain "05-settings-downloads.png"

# The evidence: hierarchy with the button on screen, plus its node report.
# dump_ui() bounds the dump to DUMP_TIMEOUT and logs the failure mode.
rm -f /tmp/ui.xml
timeout "$DUMP_TIMEOUT" adb shell uiautomator dump "$UI_SETTINGS" > /tmp/dump.err 2>&1 || echo "settings hierarchy dump failed: $(head -c 200 /tmp/dump.err | tr '\n' ' ')" >&2
adb pull -q "$UI_SETTINGS" "$OUT/ui-settings-downloads.xml" || echo "no hierarchy pulled"
dump_node "Open Downloads" "$OUT/ui-settings-downloads.xml" > "$OUT/open-downloads-node.txt" 2>&1 || echo "node report failed"
cat "$OUT/open-downloads-node.txt"

# The proof: a real coordinate tap on the button, then check where we land.
# Tolerant on purpose: if the tap does not navigate, the verdict below
# says NO — that is the answer this run exists to produce, not a failure.
tap_text "Open Downloads" && echo "tap returned 0" || echo "tap returned non-zero"
sleep 5
screenshot_plain "06-after-tap.png"
rm -f /tmp/ui.xml
timeout "$DUMP_TIMEOUT" adb shell uiautomator dump "$UI_AFTER" > /tmp/dump.err 2>&1 || echo "post-tap hierarchy dump failed: $(head -c 200 /tmp/dump.err | tr '\n' ' ')" >&2
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
