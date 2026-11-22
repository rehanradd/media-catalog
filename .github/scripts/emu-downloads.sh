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
# V23 FIX (2026-11-22, run 45002249121): v22 cleared an APP ANR ("RADD
# Stream isn't responding") but a SYSTEM UI ANR ("System UI isn't
# responding") then recurred and 11 Wait taps never cleared it in 300s.
# CORRECTION to the v22 diagnosis: (540,1399) was NOT a fixed-coords
# fallback — it is the hierarchy-derived centre of the Wait button's
# bounds [70,1336][1010,1462] in the saved timeout dump, and there is no
# hardcoded tap coordinate anywhere in this script (the only "540" in
# code is the scroll swipe). The taps were correct; the dialog is
# undismissable by input, because the tap must be processed by System UI
# itself, which is the starved process. Fix: wait_for_anr_clear() now
# tells the two ANR types apart (System UI = "System UI isn't
# responding" in the dump) and, for a System UI ANR that survives 3 Wait
# taps, restarts System UI (am force-stop com.android.systemui — it is
# a persistent app and the system restarts it), which bypasses the
# starved input path entirely (binder, not input). An APP ANR keeps the
# Wait-tap path (proven to work: cleared after 4 checks in run 6).
# There is NO setting to disable the foreground ANR dialog:
# anr_show_background (Settings.Global.ANR_SHOW_BACKGROUND) only
# governs BACKGROUND-process ANR dialogs ("If nonzero, ANRs in invisible
# background processes bring up a dialog"), so the dialog cannot be
# switched off — it must be dismissed or its process restarted.
# Also fixed in v23: (a) wait_for_ui_ready() passed with 3 text nodes
# that were the ANR dialog's own ("System UI isn't responding" /
# "Close app" / "Wait", all package=android) — a false "ready" while the
# app had rendered nothing; it now requires text nodes from the APP
# package and dismisses an ANR while waiting. (b) The timeout evidence
# is re-dumped FRESH so the saved dump matches the screenshot moment —
# run 6's anr-timeout.png showed no dialog while the saved ui.xml (up to
# ~45s stale: dump time + tap + sleep 15) still had one.
# V24 FIX (2026-11-22, run 45005940365): v23 never got to act — the run
# died in wait_for_ui_ready, all 7 dump tries failing in 180s
# (uiautomator dump killed, exit 137, x4; dump wrote the file but pull
# failed x3). The timeout screenshot proves a System UI ANR was already
# up over the Disclaimer (which HAD rendered, "I understand" visible):
# the ANR struck during UI-ready, and the dump failures were its
# symptom. The v23 handling only lives in wait_for_anr_clear, called
# from the disclaimer-tap phase — and wait_for_ui_ready only dismissed
# an ANR when a dump SUCCEEDED, so with dumps failing it burned the
# whole budget without ever escalating. Fix: the dump-failure path is
# now ANR-aware. After 2 consecutive dump failures, detect_anr_light()
# asks `dumpsys window` (which does not need uiautomator) whether an
# ANR is up: a System UI ANR restarts System UI once; an app ANR is
# logged and the loop keeps retrying dumps (a Wait tap needs dump
# coordinates, so the normal path takes over as soon as a dump
# succeeds); after 4 consecutive failures with no ANR visible via
# dumpsys, System UI is restarted once as a last resort (dumpsys may
# itself be wedged). wait_for_anr_clear gets the same treatment for its
# dump-failure branch (last-known System UI kind → restart once).
# Timeout evidence in wait_for_ui_ready is now a fresh re-dump plus the
# dumpsys window state, so a run dying here still proves the ANR.
# Known limitation (not fixed here): an ANR striking mid-flow — e.g.
# inside tap_text's or wait_for_welcome_settle's dump loops — with dumps
# failing has no escalation yet; those loops retry until their own
# budget. If a future run dies there, apply the same pattern.
# V25 FIX (2026-11-22, run 45010249089): v24's detection WORKED
# (dumpsys found the System UI ANR twice) but the restart did NOT work:
# both attempts ended "System UI did NOT come back within 90s (pid now:
# '1109')" — `am force-stop` never killed the process. The timeout
# dumpsys proved why: THREE ANR windows were up at once (app +
# systemui + "system"), i.e. system_server itself was ANR'd. `am` is a
# Binder client of ActivityManagerService, which lives in system_server
# — a wedged system_server never processes the force-stop, so the
# process never dies. Fix: restart_systemui() now kills by PID with a
# direct SIGKILL (`adb shell kill -9`), which needs no system_server at
# all — via `adb root` first (the workflow's google_apis image is
# userdebug, which allows it) and `adb unroot` after, falling back to
# `am force-stop` if root is unavailable. Two more fixes from the same
# run: (a) a FAILED restart is never retried in the same
# wait_for_ui_ready call — v24 only set its flag on success, so two
# 90s waits ate the whole 180s budget; (b) if the kill does not respawn
# System UI, the call escalates to an emulator reboot + app relaunch
# (reboot_emulator_and_relaunch), which is the only recovery when
# system_server will not restart its own persistent app — the budget
# resets after a reboot, since the reboot alone takes ~10 min under
# TCG. (c) An APP ANR while dumps are failing now restarts the app
# (restart_app: kill by PID + relaunch + wait for focus), instead of
# only logging and retrying dumps — the v24 run's app had also ANR'd
# (input dispatch timed out on MainActivity focus, 5028ms), and
# restarting System UI alone cannot unstick a wedged app process.
# V26 FIX (2026-11-22, run 45014241234): v25's SIGKILL restart WORKED
# (System UI pid 1134 -> 3321 in 30s) but dumps STILL failed 5/5 after
# it — 4x "dump wrote /data/local/tmp/ui.xml but pull failed" + 1x exit
# 137 — and the run died "UI never became ready within 180s". No second
# ANR detection or restart fired after the respawn, and the reboot
# escalation never triggered, because it only runs when the restart
# FAILS and the restart was counted a success. The 'system' ANR window
# was up alongside systemui at 11:07:07Z — system_server itself wedged,
# which fits pulls failing even after System UI respawned. Fix, two
# parts: (1) after a successful System UI restart, 3+ consecutive dump
# failures re-check via detect_anr_light and reboot the emulator once
# (the v25 reboot path already exists; it is now wired to persistent
# post-restart dump failure, not only to restart failure) — a
# "successful" restart does NOT mean the system is healthy, and only a
# reboot fixes a wedged system_server; the budget resets after a reboot.
# The reboot fires at most once per wait_for_ui_ready call (a `rebooted`
# flag, plus the `esc` return code: a reboot is only attempted when the
# last escalation was a successful restart, never after a reboot already
# happened or a reboot already failed). (2) dump_ui() falls back to
# `adb exec-out cat` when the pull fails — the dump file WAS on the
# device in 4 of 5 failures, only the file-sync pull failed.
# Known limitation (still): wait_for_anr_clear's dump-failure branch has
# the same shape — after its one System UI restart, persistent dump
# failures have no reboot escalation (a nested reboot would also need
# the caller's budget reset, which is not wired); if a run dies there,
# apply the same pattern with a reboot return code. Also unchanged: an
# ANR striking mid-flow inside tap_text's or wait_for_welcome_settle's
# dump loops has no escalation yet.
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
  if ! adb pull -q "$UI_DUMP" /tmp/ui.xml 2>/dev/null; then
    # V26 (run 45014241234): 4 of 5 dump failures after a successful
    # System UI restart were "dump wrote but pull failed" — the file WAS
    # on the device, only the file-sync pull failed. Retry the read via
    # `adb exec-out cat`, which goes through the shell channel instead
    # of the file-sync service (exec-out avoids the shell's \r\n
    # translation, so the XML stays parseable). Bounded to 30s so a
    # wedged adbd cannot stall the caller; a few-KB ui.xml reads in
    # seconds when the channel works.
    echo "dump_ui: dump wrote $UI_DUMP but pull failed — retrying via exec-out cat" >&2
    if ! timeout 30 adb exec-out cat "$UI_DUMP" > /tmp/ui.xml 2>/dev/null || [ ! -s /tmp/ui.xml ]; then
      echo "dump_ui: dump wrote $UI_DUMP but pull AND cat both failed" >&2
      return 1
    fi
    echo "dump_ui: dump recovered via exec-out cat" >&2
  fi
  if [ ! -s /tmp/ui.xml ]; then
    echo "dump_ui: pulled ui.xml is empty" >&2
    return 1
  fi
  python3 -c "import xml.etree.ElementTree as ET; ET.parse('/tmp/ui.xml')" 2>/dev/null || { echo "dump_ui: pulled ui.xml does not parse as XML" >&2; return 1; }
  return 0
}

# Wait until a UI dump succeeds AND the hierarchy contains at least one
# node with non-empty text FROM THE APP PACKAGE — i.e. the app has
# rendered a real screen, not just its splash (the splash is a bare logo:
# 0 text nodes, run 44995080198). Bounded to $1 seconds (default 180).
# Logs each attempt so the run log shows whether dumps were failing or
# the app was simply still loading. Returns 1 on timeout, after saving
# a screenshot and the last dump diagnostics into $OUT.
# V23 (run 45002249121): the old check counted ANY text node, and passed
# with 3 text nodes that were the ANR dialog's own ("System UI isn't
# responding" / "Close app" / "Wait", all package=android) — a false
# "ready" while the app had rendered nothing. An ANR dialog alone must
# not count as ready. While waiting, an ANR on screen is dismissed
# (dismiss_anr) so the wait can make progress instead of timing out on
# a dialog.
wait_for_ui_ready() {
  local budget="${1:-180}" start now tries=0 dump_failures=0 sysui_restarted=0 app_restarted=0 esc=0 rebooted=0
  start=$(date +%s)
  while true; do
    tries=$((tries + 1))
    if dump_ui 2>/tmp/wait-dump.err; then
      dump_failures=0
      local text_nodes
      text_nodes=$(python3 - "$APP_PKG" <<'PY' 2>/dev/null
import sys, xml.etree.ElementTree as ET
app_pkg = sys.argv[1] if len(sys.argv) > 1 else ''
try:
    root = ET.parse('/tmp/ui.xml').getroot()
except Exception:
    sys.exit(0)
def is_app(n):
    pkg = n.get('package') or ''
    return (not app_pkg) or pkg.startswith(app_pkg)
print(sum(1 for n in root.iter('node') if (n.get('text') or '').strip() and is_app(n)))
PY
)
      if [ "${text_nodes:-0}" -gt 0 ]; then
        echo "UI ready after ${tries} dump(s): $text_nodes app text node(s) on screen"
        return 0
      fi
      if grep -qi "isn't responding\|not responding" /tmp/ui.xml; then
        echo "wait_for_ui_ready try $tries: ANR dialog on screen, dismissing it so the app can render" >&2
        dismiss_anr || echo "wait_for_ui_ready try $tries: ANR dismiss failed — will retry within budget" >&2
      else
        echo "wait_for_ui_ready try $tries: dump ok but 0 app text nodes (splash still showing?)" >&2
      fi
    else
      dump_failures=$((dump_failures + 1))
      echo "wait_for_ui_ready try $tries: $(cat /tmp/wait-dump.err | head -c 200 | tr '\n' ' ') (consecutive dump failures: $dump_failures)" >&2
      # V24 (run 45005940365): dumps failing is itself the symptom — a
      # System UI ANR was already up, but this wait only dismissed an
      # ANR when a dump SUCCEEDED, so it burned the whole budget without
      # escalating. After 2 consecutive dump failures, ask dumpsys
      # (detect_anr_light, no uiautomator needed) whether an ANR is up.
      # System UI ANR → restart System UI once (the v23 remedy, applied
      # here instead of only in the tap phase). App ANR → log it and keep
      # retrying dumps: a Wait tap needs dump coordinates, so dismiss_anr
      # cannot work while dumps fail, and the normal path takes over as
      # soon as a dump succeeds. No ANR visible via dumpsys after 4
      # consecutive failures → restart System UI once as a last resort
      # (dumpsys may itself be wedged; every observed dump wedge was a
      # System UI ANR). Once per call, like the v23 rule.
      # V25 (run 45010249089): a FAILED System UI restart must never be
      # retried in the same call — v24 only set sysui_restarted on
      # success, so two 90s waits ate the whole 180s budget. The flag
      # is set BEFORE the escalation now. On a failed restart the call
      # escalates to an emulator reboot (escalate_systemui), and the
      # budget resets after a reboot since the reboot alone takes
      # ~10 min under TCG. An app ANR now restarts the app
      # (restart_app) once per call instead of only being logged — the
      # v24 run's app had ANR'd too (input dispatch timed out on
      # MainActivity focus, 5028ms), and a Wait tap needs dump
      # coordinates, so the normal path cannot take over while dumps
      # fail.
      if [ "$dump_failures" -ge 2 ] && [ "$sysui_restarted" -eq 0 ]; then
        local anr_kind=""
        anr_kind=$(detect_anr_light) || anr_kind=""
        if [ "$anr_kind" = "systemui" ]; then
          echo "wait_for_ui_ready: dumps failing x$dump_failures and System UI ANR detected via dumpsys — restarting System UI" >&2
          sysui_restarted=1
          escalate_systemui
          esc=$?
          if [ "$esc" -eq 0 ]; then
            dump_failures=0
          elif [ "$esc" -eq 2 ]; then
            echo "wait_for_ui_ready: recovered via emulator reboot — resetting the ${budget}s budget" >&2
            dump_failures=0
            start=$(date +%s)
          else
            echo "wait_for_ui_ready: System UI restart AND emulator reboot both failed — continuing dump retries" >&2
          fi
        elif [ "$anr_kind" = "app" ]; then
          if [ "$app_restarted" -eq 0 ]; then
            echo "wait_for_ui_ready: dumps failing x$dump_failures and app ANR detected via dumpsys — restarting the app" >&2
            app_restarted=1
            if restart_app; then
              dump_failures=0
            else
              echo "wait_for_ui_ready: app restart failed — retrying dumps so the normal ANR path can take over" >&2
            fi
          else
            echo "wait_for_ui_ready: dumps failing x$dump_failures and app ANR detected via dumpsys — app already restarted once, retrying dumps" >&2
          fi
        elif [ "$dump_failures" -ge 4 ]; then
          echo "wait_for_ui_ready: dumps failing x$dump_failures with no ANR visible via dumpsys (dumpsys may itself be wedged) — restarting System UI once as a last resort" >&2
          sysui_restarted=1
          escalate_systemui
          esc=$?
          if [ "$esc" -eq 0 ]; then
            dump_failures=0
          elif [ "$esc" -eq 2 ]; then
            echo "wait_for_ui_ready: recovered via emulator reboot — resetting the ${budget}s budget" >&2
            dump_failures=0
            start=$(date +%s)
          else
            echo "wait_for_ui_ready: System UI restart AND emulator reboot both failed — continuing dump retries" >&2
          fi
        fi
      elif [ "$dump_failures" -ge 3 ] && [ "$sysui_restarted" -eq 1 ] && [ "$esc" -eq 0 ] && [ "$rebooted" -eq 0 ]; then
        # V26 (run 45014241234): the System UI restart SUCCEEDED (pid
        # 1134 -> 3321 in 30s) but dumps STILL failed 5/5 after it —
        # 4x pull failed + 1x exit 137 — and the reboot escalation never
        # fired, because it only runs when the restart FAILS and the
        # restart was counted a success. A "successful" restart does NOT
        # mean the system is healthy: the 'system' ANR window was up
        # alongside systemui, i.e. system_server itself is wedged, and
        # only a reboot fixes that. After 3 consecutive dump failures
        # post-restart, re-check with detect_anr_light (for the log
        # evidence — a 'system' ANR still up confirms the wedge; no ANR
        # visible means dumpsys may itself be wedged) and reboot once.
        # The `esc -eq 0` guard is the key: it is only true when the
        # last escalation was a successful restart — never after a reboot
        # already happened (esc 2) or a reboot already failed (esc 1) —
        # so this fires at most once per call, alongside `rebooted`.
        local anr_kind2=""
        anr_kind2=$(detect_anr_light) || anr_kind2=""
        echo "wait_for_ui_ready: dumps still failing x$dump_failures after a successful System UI restart (ANR now: '${anr_kind2:-none}') — rebooting the emulator" >&2
        rebooted=1
        if reboot_emulator_and_relaunch; then
          echo "wait_for_ui_ready: recovered via emulator reboot — resetting the ${budget}s budget" >&2
          dump_failures=0
          start=$(date +%s)
        else
          echo "wait_for_ui_ready: emulator reboot failed — continuing dump retries" >&2
        fi
      fi
    fi
    now=$(date +%s)
    if [ $((now - start)) -ge "$budget" ]; then
      echo "FAIL: UI never became ready within ${budget}s (no dump with text nodes)" >&2
      # V24: fresh evidence at timeout — a re-dump (v23 pattern) plus the
      # dumpsys window state, so a run dying here still proves whether
      # an ANR was up even though dumps were failing.
      if dump_ui 2>/tmp/wait-timeout-dump.err; then
        if grep -qi "isn't responding\|not responding" /tmp/ui.xml; then
          echo "timeout evidence: fresh dump taken — ANR still present" >&2
        else
          echo "timeout evidence: fresh dump taken — no ANR in fresh dump" >&2
        fi
      else
        echo "timeout evidence: fresh re-dump failed ($(cat /tmp/wait-timeout-dump.err | head -c 150 | tr '\n' ' '))" >&2
      fi
      timeout 20 adb shell dumpsys window > "$OUT/ui-ready-timeout-dumpsys-window.txt" 2>/dev/null || echo "timeout evidence: dumpsys window also failed" >&2
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

# Detect an ANR dialog WITHOUT uiautomator. `dumpsys window` reports the
# ANR window as mCurrentFocus "Application Not Responding: <pkg>" (this
# script's focused_pkg() already relies on that line: a System UI ANR
# shows "Application Not Responding: system"). Prints "systemui" or
# "app" and returns 0 if an ANR is present, 1 otherwise.
# V24 (run 45005940365): the run died in wait_for_ui_ready because
# uiautomator dumps kept failing (exit 137 / pull failed) while a
# System UI ANR was already up — and the only ANR checks went through
# a dump, so the ANR was invisible to the script. `dumpsys window`
# talks to WindowManager, not the accessibility/dump path, so it can
# still answer when uiautomator cannot. Bounded to 20s: if
# system_server is too wedged even for dumpsys, the call is killed and
# this returns 1 (no ANR seen) instead of stalling the caller.
# Kind: "systemui" if any "Not Responding" line names system/systemui,
# else "app". A System UI ANR is checked first because it is the one
# that wedges the dump path — if both are up, systemui wins.
detect_anr_light() {
  local wins
  wins=$(timeout 20 adb shell dumpsys window 2>/dev/null | grep -i 'not responding' | head -n 5 || true)
  if [ -z "$wins" ]; then
    return 1
  fi
  echo "detect_anr_light: dumpsys window reports ANR: $(echo "$wins" | head -c 200 | tr '\n' ' ')" >&2
  if echo "$wins" | grep -qiE 'systemui|not responding: system([^a-z0-9._]|$)'; then
    echo "systemui"
  else
    echo "app"
  fi
  return 0
}

# Restart System UI (com.android.systemui) and wait for it to come
# back. Used when a System UI ANR will not clear with Wait taps (see
# wait_for_anr_clear) or when dumps keep failing under a System UI ANR
# (see wait_for_ui_ready). V25 (run 45010249089): the v23/v24
# `am force-stop` NEVER killed the process — pid stayed 1109 through
# two 90s waits. The timeout dumpsys proved why: THREE ANR windows
# were up at once (app + systemui + "system"), i.e. system_server
# itself was ANR'd. `am` is a Binder client of ActivityManagerService,
# which lives in system_server — a wedged system_server never
# processes the force-stop, so the process never dies. A SIGKILL is a
# direct kernel signal: it needs no system_server at all. But the shell
# user (uid 2000) cannot signal a system-uid (1000) process, so this
# runs `adb root` first (the workflow's google_apis image is userdebug,
# which allows it — Play Store images do not) and `adb unroot` after,
# so later commands behave exactly as before. If root is unavailable
# the kill fails and this falls back to `am force-stop`. System UI is
# a persistent app, so system_server restarts it once it recovers;
# poll pidof until a NEW pid appears, bounded to 60s (a healthy
# restart takes seconds; 90s was too long — two failed waits ate the
# whole 180s wait_for_ui_ready budget in run 45010249089). Returns 0
# if a System UI process is running afterwards with a new pid, 1
# otherwise. Logs the old and new PIDs so a failed restart is
# diagnosable. If this returns 1 the caller escalates to a reboot
# (escalate_systemui) — nothing in-place can recover a system_server
# that will not restart its own persistent app.
restart_systemui() {
  local old_pid new_pid waited=0
  old_pid=$(adb shell pidof com.android.systemui 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)
  echo "restart_systemui: System UI pid before restart: '${old_pid:-none}'" >&2
  if [ -n "$old_pid" ]; then
    adb root >/dev/null 2>&1 || true
    if adb shell kill -9 "$old_pid" 2>/dev/null; then
      echo "restart_systemui: SIGKILL sent to pid $old_pid" >&2
    else
      echo "restart_systemui: kill -9 failed (no root?) — falling back to am force-stop" >&2
      adb shell am force-stop com.android.systemui 2>/dev/null || { echo "restart_systemui: am force-stop failed" >&2; adb unroot >/dev/null 2>&1 || true; return 1; }
    fi
    adb unroot >/dev/null 2>&1 || true
  else
    echo "restart_systemui: no System UI pid found — it may already be dead, waiting for it to respawn" >&2
  fi
  while [ "$waited" -lt 60 ]; do
    sleep 5
    waited=$((waited + 5))
    new_pid=$(adb shell pidof com.android.systemui 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)
    if [ -n "$new_pid" ] && [ "$new_pid" != "$old_pid" ]; then
      echo "restart_systemui: System UI restarted (pid ${old_pid:-none} -> $new_pid) after ${waited}s" >&2
      return 0
    fi
    if [ -n "$new_pid" ] && [ -z "$old_pid" ]; then
      echo "restart_systemui: System UI running again (pid $new_pid) after ${waited}s" >&2
      return 0
    fi
  done
  new_pid=$(adb shell pidof com.android.systemui 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)
  echo "restart_systemui: System UI did NOT come back within 60s (pid now: '${new_pid:-none}')" >&2
  return 1
}

# Restart the app itself ($APP_PKG) — for an APP ANR ("RADD Stream
# isn't responding") while dumps are failing, so no Wait tap is
# possible (a tap needs dump coordinates). Same kill-by-PID approach
# as restart_systemui (force-stop goes through the possibly wedged
# system_server): adb root + kill -9 the app pid, fall back to
# am force-stop, then relaunch via am start and wait for focus.
# Returns 0 if the app is in the foreground afterwards, 1 otherwise.
restart_app() {
  local app_pid=""
  app_pid=$(adb shell pidof "$APP_PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)
  echo "restart_app: app pid before restart: '${app_pid:-none}'" >&2
  if [ -n "$app_pid" ]; then
    adb root >/dev/null 2>&1 || true
    if adb shell kill -9 "$app_pid" 2>/dev/null; then
      echo "restart_app: SIGKILL sent to app pid $app_pid" >&2
    else
      echo "restart_app: kill -9 failed (no root?) — falling back to am force-stop" >&2
      adb shell am force-stop "$APP_PKG" 2>/dev/null || true
    fi
    adb unroot >/dev/null 2>&1 || true
  else
    adb shell am force-stop "$APP_PKG" 2>/dev/null || true
  fi
  sleep 2
  adb shell am start -n "$MAIN_ACTIVITY" >/dev/null 2>&1 || true
  if wait_for_app_focus 60; then
    echo "restart_app: app is in foreground after restart" >&2
    return 0
  fi
  echo "restart_app: app did not reach the foreground after restart" >&2
  return 1
}

# Try to recover a wedged System UI: kill it by PID first
# (restart_systemui), and if that does not respawn it, reboot the
# emulator and relaunch the app (reboot_emulator_and_relaunch).
# Returns 0 if System UI came back without a reboot, 2 if it came
# back via a reboot, 1 if neither worked. The caller resets its
# budget on 2 — a reboot takes ~10 min under TCG, longer than any
# dump budget.
escalate_systemui() {
  if restart_systemui; then
    return 0
  fi
  echo "escalate_systemui: System UI restart failed — rebooting the emulator" >&2
  if reboot_emulator_and_relaunch; then
    return 2
  fi
  return 1
}

# Reboot the emulator and relaunch the app — the last resort when
# System UI will not respawn after a kill (system_server itself is
# ANR'd, so no in-place recovery can work: the persistent-app restart
# is system_server's job). adb reboot does not need system_server
# (adbd triggers it directly), so it works even when the system is
# wedged. Boot takes ~9 min under TCG (v24: 535s), so the boot wait
# is bounded to 600s; the app is then relaunched and must reach the
# foreground. Returns 0 on success, 1 otherwise. The caller resets
# its budget after a reboot — the reboot alone took longer than any
# dump budget.
reboot_emulator_and_relaunch() {
  local waited=0 booted=""
  echo "reboot_emulator_and_relaunch: rebooting the emulator" >&2
  adb reboot 2>/dev/null || adb shell reboot 2>/dev/null || { echo "reboot_emulator_and_relaunch: adb reboot failed" >&2; return 1; }
  echo "reboot_emulator_and_relaunch: waiting for the device to come back" >&2
  timeout 300 adb wait-for-device 2>/dev/null || { echo "reboot_emulator_and_relaunch: device did not come back within 300s" >&2; return 1; }
  while [ "$waited" -lt 600 ]; do
    sleep 10
    waited=$((waited + 10))
    booted=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    if [ "$booted" = "1" ]; then
      break
    fi
  done
  if [ "$booted" != "1" ]; then
    echo "reboot_emulator_and_relaunch: boot did not complete within 600s" >&2
    return 1
  fi
  echo "reboot_emulator_and_relaunch: boot completed in ~${waited}s — relaunching the app" >&2
  adb shell am start -n "$MAIN_ACTIVITY" >/dev/null 2>&1 || true
  if wait_for_app_focus 180; then
    echo "reboot_emulator_and_relaunch: app is in foreground after reboot" >&2
    return 0
  fi
  echo "reboot_emulator_and_relaunch: app did not reach the foreground after reboot" >&2
  return 1
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
# V23 (run 45002249121): the v22 loop cleared an APP ANR ("RADD Stream
# isn't responding") but a SYSTEM UI ANR ("System UI isn't responding")
# then recurred and 11 Wait taps at the correct hierarchy-derived
# centre (540,1399) never cleared it in 300s. A System UI ANR cannot be
# dismissed by tapping Wait: the tap must be processed by System UI
# itself, which is the starved process. So a System UI ANR that survives
# 3 Wait taps escalates to restart_systemui() (once per call) — the
# loop then re-dumps to verify. An APP ANR keeps the Wait-tap path
# (proven to work: cleared after 4 checks in run 45002249121).
wait_for_anr_clear() {
  local budget="${1:-300}" start now tries=0 wait_taps=0 sysui_restarted=0 dump_failures=0 anr_kind="app"
  start=$(date +%s)
  while true; do
    tries=$((tries + 1))
    if ! dump_ui 2>/tmp/anr-dump.err; then
      dump_failures=$((dump_failures + 1))
      echo "wait_for_anr_clear try $tries: dump failed ($(cat /tmp/anr-dump.err | head -c 150 | tr '\n' ' ')) — retrying (consecutive dump failures: $dump_failures)" >&2
      # V24: an ANR was already confirmed on the last successful dump,
      # and dumps failing now is the same wedge run 45005940365 died on.
      # If the last-known kind was a System UI ANR, restart System UI
      # once instead of waiting out the budget on dumps that cannot
      # succeed. An app ANR keeps retrying (its Wait-tap path needs a
      # dump). Once per call, like the v23 rule.
      if [ "$dump_failures" -ge 2 ] && [ "$anr_kind" = "systemui" ] && [ "$sysui_restarted" -eq 0 ]; then
        echo "wait_for_anr_clear: dumps failing (x$dump_failures) with a System UI ANR outstanding — restarting System UI" >&2
        sysui_restarted=1
        dump_failures=0
        restart_systemui || echo "wait_for_anr_clear: System UI restart failed — continuing to retry dumps" >&2
      fi
    elif ! grep -qi "isn't responding\|not responding" /tmp/ui.xml; then
      echo "ANR dialog cleared after $tries check(s)"
      return 0
    else
      dump_failures=0
      local wxy
      anr_kind="app"
      if grep -qi "System UI isn't responding" /tmp/ui.xml; then
        anr_kind="systemui"
      fi
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
        echo "wait_for_anr_clear try $tries: $anr_kind ANR present — tapping Wait ($wxy)" >&2
        adb shell input tap $wxy
        wait_taps=$((wait_taps + 1))
      else
        echo "wait_for_anr_clear try $tries: $anr_kind ANR present but no 'Wait' node in dump — waiting" >&2
      fi
      # V23 escalation (see header): a System UI ANR that survives 3
      # Wait taps is undismissable by input — restart System UI once,
      # then re-dump to verify. The outer budget still bounds the call.
      if [ "$anr_kind" = "systemui" ] && [ "$wait_taps" -ge 3 ] && [ "$sysui_restarted" -eq 0 ]; then
        sysui_restarted=1
        echo "wait_for_anr_clear: System UI ANR survived $wait_taps Wait taps — restarting System UI" >&2
        restart_systemui || echo "wait_for_anr_clear: System UI restart failed — continuing to tap Wait" >&2
      fi
    fi
    now=$(date +%s)
    if [ $((now - start)) -ge "$budget" ]; then
      echo "FAIL: ANR dialog still present after ${budget}s ($tries checks)" >&2
      # V23: re-dump FRESH at timeout so the saved dump matches the
      # screenshot moment. Run 45002249121's anr-timeout.png showed no
      # dialog while the saved ui.xml (copied from /tmp/ui.xml, up to
      # ~45s stale: dump time + tap + sleep 15) still had one.
      if dump_ui 2>/tmp/anr-timeout-dump.err; then
        if grep -qi "isn't responding\|not responding" /tmp/ui.xml; then
          echo "timeout evidence: fresh dump taken — ANR still present" >&2
        else
          echo "timeout evidence: fresh dump taken — ANR GONE in fresh dump (dialog cleared between last check and timeout)" >&2
        fi
      else
        echo "timeout evidence: fresh re-dump failed ($(cat /tmp/anr-timeout-dump.err | head -c 150 | tr '\n' ' ')) — saving last dump instead" >&2
      fi
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
