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
set -euo pipefail

APK="App/app/build/outputs/apk/debug/app-debug.apk"
# $GITHUB_WORKSPACE is empty inside the runner's script context (run
# 44002960788); $PWD is the checkout when this file runs.
OUT="${GITHUB_WORKSPACE:-$PWD}/emu-downloads"
mkdir -p "$OUT"
adb install -r "$APK"
adb shell am start -n com.raddstream.app/.MainActivity
sleep 12
adb exec-out screencap -p > "$OUT/01-disclaimer.png"

# Tap a UI node by its exact visible text (uiautomator dump -> centre tap).
# Copied verbatim from emu-drive.sh: a REAL coordinate tap, not an
# accessibility click, so it is the closest thing to a user's finger.
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

# Scroll the Settings list until the wanted text's centre is inside the
# viewport (Pixel 7 is 1080x2400). Presence alone is not enough: a
# vertically-scrolling Column composes all its children, so an off-screen
# node is in the hierarchy but its tap would miss.
scroll_to_text() {
  local want="$1" i=0
  while [ "$i" -lt 12 ]; do
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

tap_text "I understand" || true
sleep 4
adb exec-out screencap -p > "$OUT/02-welcome.png"
tap_text "Continue" || true
sleep 8
tap_text "Done" || true
sleep 3
adb exec-out screencap -p > "$OUT/03-home.png"
tap_text "Settings" || true
sleep 4
adb exec-out screencap -p > "$OUT/04-settings-top.png"

scroll_to_text "Open Downloads" || true
sleep 2
adb exec-out screencap -p > "$OUT/05-settings-downloads.png"

# The evidence: hierarchy with the button on screen, plus its node report.
adb shell uiautomator dump /sdcard/ui-settings-downloads.xml >/dev/null 2>&1
adb pull -q /sdcard/ui-settings-downloads.xml "$OUT/ui-settings-downloads.xml" || echo "no hierarchy pulled"
dump_node "Open Downloads" "$OUT/ui-settings-downloads.xml" > "$OUT/open-downloads-node.txt" 2>&1 || echo "node report failed"
cat "$OUT/open-downloads-node.txt"

# The proof: a real coordinate tap on the button, then check where we land.
tap_text "Open Downloads" && echo "tap returned 0" || echo "tap returned non-zero"
sleep 5
adb exec-out screencap -p > "$OUT/06-after-tap.png"
adb shell uiautomator dump /sdcard/ui-after-tap.xml >/dev/null 2>&1
adb pull -q /sdcard/ui-after-tap.xml "$OUT/ui-after-tap.xml" || echo "no post-tap hierarchy pulled"

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
