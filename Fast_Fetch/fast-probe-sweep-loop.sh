#!/bin/bash
# Fast-prober sweep loop — REPLACES the old link_state lanes (swap approved by user 18:13 PKT 18 Nov).
# Runs fast_probe.py --apply in 5,000-row batches until no probeable rows remain.
# Stop: touch /tmp/fast-probe-sweep.stop (checked between batches). The domain-remap gate
# touches it before a backup/deploy, waits for the current batch, then relaunches this loop.
# Log: /tmp/fast-probe-sweep.log (one JSON summary per batch, appended).
# Never set -x here (no secrets in this script, but keep the rule).
#
# REPO COPY (INF-05, 2026-11-24). The live file is /home/ubuntu/fast-probe-sweep-loop.sh.
# INSTALL (gate owner, at the next natural relaunch — the running loop already
# read the old copy, so copying now changes nothing until the next start):
#   cp Fast_Fetch/fast-probe-sweep-loop.sh /home/ubuntu/fast-probe-sweep-loop.sh
#   chmod +x /home/ubuntu/fast-probe-sweep-loop.sh
#   # verify the installed copy carries the trap: grep -c forward_term /home/ubuntu/fast-probe-sweep-loop.sh  # must be >= 2
#   # then relaunch ONLY via: nohup /home/ubuntu/fast-probe-sweep-loop.sh >/dev/null 2>&1 &
#   # (never fast_probe.py directly — it runs one batch and exits).
#   # DO NOT install while a gate is running; do NOT restart a running loop to pick this up.
#
# WHAT CHANGED vs the live copy (and why):
#   1. The child is launched in the background with `wait`, and a TERM/INT
#      trap forwards the signal to the child. The old script ran the child
#      in the foreground with no trap, so killing the WRAPPER orphaned the
#      child: it never reads the STOP file (only the wrapper does) and kept
#      writing to the live DB through the backup window. Proven locally
#      (SIGTERM to a trap-less wrapper leaves the foreground child alive,
#      reparented to PID 1) and on the live box — greenmotors, 23 Nov: the
#      wrapper exited 18:54:17 yet a fast_probe.py child still matched
#      pgrep until 19:09:22, after the duplicate loop was killed 18:25.
#   2. A TERM/INT to the wrapper now also sets stop_requested, so the loop
#      exits after the child stops instead of launching another batch.
#      (Killing the wrapper used to mean "sweep stops"; without this flag
#      the forward alone would restart the batch when no STOP file exists.)
#   3. After a batch exits, a present STOP file breaks the loop at once
#      instead of counting a failure and sleeping 30 s first. A gate stop
#      (SIGTERM to the child, or the stop file) used to cost an extra 30 s
#      and one failure count; three such stops in a row exited the loop
#      for good ("3 consecutive failures", 22 Nov 02:27). With fast_probe.py
#      now exiting 0 with "stopped": true on a graceful stop, a stop no
#      longer counts as a failure at all.
#   The batch itself now also watches the STOP file (fast_probe.py
#   --stop-file), so even a child orphaned by a SIGKILLed wrapper exits
#   on its own when the gate touches STOP. These two layers are independent:
#   either one alone closes the stuck-child hole.
DB=/home/ubuntu/radd-stream-data/merged_catalog_20261025.db
STOP=/tmp/fast-probe-sweep.stop
LOG=/tmp/fast-probe-sweep.log
FAIL=0
child=0
stop_requested=0
forward_term() {
  stop_requested=1
  if [ "$child" -ne 0 ]; then kill -TERM "$child" 2>/dev/null; fi
}
trap forward_term TERM INT
echo "$(date -u +%FT%TZ) sweep loop started (fast_probe --apply, 5000 rows/batch, 48 workers)" >> "$LOG"
while true; do
  if [ "$stop_requested" -eq 1 ]; then echo "$(date -u +%FT%TZ) stop signal received, exiting" >> "$LOG"; break; fi
  if [ -f "$STOP" ]; then echo "$(date -u +%FT%TZ) STOP file present, exiting" >> "$LOG"; break; fi
  cd /opt/radd-stream || exit 1
  python3 -S Fast_Fetch/fast_probe.py --db "$DB" --apply --max-rows 5000 --workers 48 >> "$LOG" 2>&1 &
  child=$!
  wait "$child"
  RC=$?
  child=0
  if [ -f "$STOP" ]; then echo "$(date -u +%FT%TZ) STOP file present, exiting" >> "$LOG"; break; fi
  if [ "$stop_requested" -eq 1 ]; then echo "$(date -u +%FT%TZ) stop signal received, exiting" >> "$LOG"; break; fi
  if [ $RC -ne 0 ]; then
    FAIL=$((FAIL+1))
    echo "$(date -u +%FT%TZ) batch exit $RC (consecutive failures $FAIL)" >> "$LOG"
    if [ $FAIL -ge 3 ]; then echo "$(date -u +%FT%TZ) 3 consecutive failures, stopping loop" >> "$LOG"; break; fi
    sleep 30
    continue
  fi
  FAIL=0
  if tail -20 "$LOG" | grep -q '"probed": 0'; then
    echo "$(date -u +%FT%TZ) batch probed 0 rows — sweep complete" >> "$LOG"
    break
  fi
  sleep 5
done
trap - TERM INT
echo "$(date -u +%FT%TZ) sweep loop exited" >> "$LOG"
