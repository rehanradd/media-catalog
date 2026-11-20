#!/bin/bash
# run_lane.sh <site> — drive one per-site sweep lane to completion.
# Restarts the lane until its scope cursor reads 'complete'. A death on
# "database is locked" (many writers, 5s busy timeout) waits 30s and retries
# (max 3 consecutive lock failures, then it stops and logs). Any other death
# stops immediately so a real bug is visible instead of looped.
# Cursor resume makes every restart cheap: only uncommitted batch probes redo.
set -u
site="$1"
db="/home/ubuntu/radd-stream-data/merged_catalog_20261025.db"
log="/tmp/sweep-lane-$site-20261118.out"
lock_tries=0
while true; do
  python3 link_state.py --db "$db" --scope "sweep-$site" --site "$site" \
    --apply --max-rows 20000 --workers 8 >> "$log" 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then
    lock_tries=0
    state=$(sqlite3 "$db" "SELECT state FROM link_state_cursor WHERE scope='sweep-$site';" 2>/dev/null)
    if [ "$state" = "complete" ]; then
      echo "[lane $site] scope complete, exiting" >> "$log"
      exit 0
    fi
    echo "[lane $site] slice done (state=$state), continuing" >> "$log"
    sleep 5
    continue
  fi
  if tail -5 "$log" | grep -q "database is locked"; then
    lock_tries=$((lock_tries + 1))
    if [ $lock_tries -gt 3 ]; then
      echo "[lane $site] gave up after 3 lock retries" >> "$log"
      exit 1
    fi
    echo "[lane $site] locked, retry $lock_tries/3 in 30s" >> "$log"
    sleep 30
    continue
  fi
  echo "[lane $site] died rc=$rc (not a lock), stopping" >> "$log"
  exit $rc
done
