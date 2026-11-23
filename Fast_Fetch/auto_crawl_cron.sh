#!/bin/bash
# ==============================================================================
# auto_crawl_cron.sh — 6-hour autonomous sitemap-sync + crawl + fleet-merge cycle.
# ==============================================================================
# Executed automatically via radd-autocrawl.timer every 6 hours.
# Zero human intervention required.
#
# OFF-VM BACKUP IS NOT THIS SCRIPT'S JOB ANY MORE (2026-11-20, audit W6):
# The backup step used to run after the merge and call /usr/local/bin/backup-hf,
# the full 61-database backup. It failed every cycle — backup_manager refuses
# to start because staging + output need ~82 GiB and the disk holds ~38 GiB —
# while the cycle still printed "AUTONOMOUS PIPELINE CYCLE COMPLETED
# SUCCESSFULLY". The daily radd-backup-hf timer (backup-hf-slim: 3 databases,
# ~19 GiB staging) already owns the off-VM copy, so the backup step is gone.
# Do not re-add a backup call here without a staging guard that fits the disk.
#
# WHY THERE IS A CRAWL STEP (freshness, 2026-11-23): the sync only fills
# sitemap_cache — 62,207 rows sat unscraped because NO scheduled job ever ran
# `fast_fetch.py crawl`, the only consumer that turns queued URLs into live
# posts. The merge step folds staging DBs, not the cache, so without this
# step a firing timer still merges 0 new posts (every cycle 2026-11-18..23
# merged 0 sites). Crawl picks newest-first per site
# (select_crawl_candidates: scraped DESC, lastmod DESC) and is bounded per
# site (AUTOCRAWL_CRAWL_LIMIT, default 25) so one cycle cannot hog the
# 1-vCPU box. The fleet timer owns the per-site importer syncs; this cycle
# owns the sitemap-queue drain.
#
# FAILURE CONTRACT: a step that fails — or runs without producing its
# summary line — fails the whole cycle (non-zero exit, no success line).
# A silent failure must never read as success again.
#
# The merge step's summary is what tells "found nothing to merge" from
# "did nothing": merge_fleet.py fingerprints every staging database and
# prints "[=] <site> unchanged since last merge, skipping" per skipped
# site, "[<site>] {...}" per merged site, and "[done] fleet merged in Ns".
# This script counts those lines; a run with no "[done]" line is a failure,
# a run with all sites unchanged is a success that merged nothing.
# The crawl step's summary is the same shape: every per-site crawl prints
# "Crawl Complete! Indexed X/Y posts" (even when it indexes 0), so a run
# without that line is a failure, and a run that indexes 0 is a success
# that crawled nothing new.
#
# LOCKED DATABASE: the fast-probe sweep can hold the live DB write lock for
# minutes, longer than init_db's own bounded retry — 2026-11-20 08:02 and
# 2026-11-22 02:01 both aborted the cycle this way. A step that fails with
# "database is locked" is retried ONCE after AUTOCRAWL_LOCK_RETRY_SLEEP
# (default 180s); any other failure fails at once, and a second lock
# failure fails too — the failure contract is unchanged, the cycle still
# fails loud.
#
# TESTING: every path and command below honours an AUTOCRAWL_* override
# (defaults are the live values), so the suite Fast_Fetch/test_auto_crawl_cron.py
# can drive the script with a stub python3. No live behaviour changes.
# AUTOCRAWL_SKIP_CRAWLER_CHECK=1 skips only the active-crawler yield (the
# check needs a /proc scan, which restricted sandboxes cannot do reliably);
# it never skips a pipeline step.
# ==============================================================================

set -u

LOCKFILE="${AUTOCRAWL_LOCKFILE:-/tmp/radd_autocrawl.lock}"
LOGFILE="${AUTOCRAWL_LOGFILE:-/var/log/radd-autocrawl.log}"
BASE_DIR="${AUTOCRAWL_BASE_DIR:-/opt/radd-stream}"
DATA_DIR="${AUTOCRAWL_DATA_DIR:-/home/ubuntu/radd-stream-data}"
PYTHON_BIN="${AUTOCRAWL_PYTHON_BIN:-python3}"
CRAWL_SITES="${AUTOCRAWL_CRAWL_SITES:-vegamovies rogmovies ssrmovies 7starhd katmoviehd multimovies 10xflix hdhub4u filmyzilla}"
CRAWL_LIMIT="${AUTOCRAWL_CRAWL_LIMIT:-25}"
LOCK_RETRY_SLEEP="${AUTOCRAWL_LOCK_RETRY_SLEEP:-180}"
export PATH="/home/ubuntu/.local/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S PKT')] $1" | tee -a "$LOGFILE"
}

fail() {
    log "[✗] $1"
    exit 1
}

# Runs "$@" with stdout+stderr captured to $STEP_TMP, retrying ONCE when the
# step fails with a locked database (see the header note). Returns 0 on
# success, 1 on failure; the caller appends $STEP_TMP to the log and fails
# the cycle. A step that exits 0 is never retried, whatever it printed.
run_with_lock_retry() {
    if "$@" >"$STEP_TMP" 2>&1; then
        return 0
    fi
    if grep -q "database is locked" "$STEP_TMP"; then
        log "[i] Step hit a locked database — retrying once after ${LOCK_RETRY_SLEEP}s."
        sleep "$LOCK_RETRY_SLEEP"
        if "$@" >"$STEP_TMP" 2>&1; then
            return 0
        fi
    fi
    return 1
}

# 1. Prevent concurrent runs
exec 200>"$LOCKFILE"
if ! flock -n 200; then
    log "[!] Another auto-crawl cycle is already in progress. Exiting cleanly."
    exit 0
fi

log "================================================================="
log "🚀 RADD-STREAM 6-HOUR AUTONOMOUS PIPELINE CYCLE STARTED"
log "================================================================="

# 2. Rule #2 Check: Do not disrupt active manual crawlers.
# The [i]mport_ bracket keeps pgrep from matching the shell that typed the
# pattern (its own cmdline holds "[i]mport_", which the regex does not match).
if [ -z "${AUTOCRAWL_SKIP_CRAWLER_CHECK:-}" ]; then
    ACTIVE_CRAWLERS=$(pgrep -fc "[i]mport_" || true)
    if [ "$ACTIVE_CRAWLERS" -gt 0 ]; then
        log "[i] Active background crawler detected ($ACTIVE_CRAWLERS running). Yielding to protect ongoing scraper."
        exit 0
    fi
fi

cd "$BASE_DIR"

STEP_TMP="$(mktemp)"
trap 'rm -f "$STEP_TMP"' EXIT

# 3. Step 1: Sitemap Delta Sync across all sites
log "📡 Step 1/3: Checking sitemaps for newly published titles..."
if ! run_with_lock_retry "$PYTHON_BIN" "$BASE_DIR/Fast_Fetch/fast_fetch.py" sync --site all; then
    cat "$STEP_TMP" >> "$LOGFILE"
    fail "Step 1 FAILED (sitemap sync exited non-zero) — cycle aborted."
fi
cat "$STEP_TMP" >> "$LOGFILE"
SYNC_SUMMARY="$(grep -E "Global sync complete!" "$STEP_TMP" | tail -n 1 || true)"
if [ -z "$SYNC_SUMMARY" ]; then
    fail "Step 1 produced no sync summary — cannot tell 'found nothing' from 'did nothing'; cycle aborted."
fi
SYNC_TOTAL="$(echo "$SYNC_SUMMARY" | grep -oE "Total [0-9]+ posts cached" || true)"
log "[i] Step 1 done: sitemap sync ran — ${SYNC_TOTAL:-no total reported}."

# 4. Step 2: Crawl the newest queued sitemap rows into the live catalog.
# One bounded crawl per sitemap site, newest-first (see the header note).
# A site that indexes 0 still prints its summary line; a site that fails
# aborts the cycle before the merge, so a half-crawled queue never merges
# as if it were complete.
log "🕷️ Step 2/3: Crawling newest queued posts per site (limit $CRAWL_LIMIT/site)..."
CRAWLED_TOTAL=0
CRAWL_NSITES="$(echo "$CRAWL_SITES" | wc -w | tr -d ' ')"
CRAWL_FAILED=""
for CRAWL_SITE in $CRAWL_SITES; do
    if ! run_with_lock_retry "$PYTHON_BIN" "$BASE_DIR/Fast_Fetch/fast_fetch.py" crawl --site "$CRAWL_SITE" --limit "$CRAWL_LIMIT"; then
        cat "$STEP_TMP" >> "$LOGFILE"
        CRAWL_FAILED="$CRAWL_SITE"
        break
    fi
    cat "$STEP_TMP" >> "$LOGFILE"
    CRAWL_SUMMARY="$(grep -E "Crawl Complete! Indexed" "$STEP_TMP" | tail -n 1 || true)"
    if [ -z "$CRAWL_SUMMARY" ]; then
        fail "Step 2 produced no crawl summary for $CRAWL_SITE — cannot tell 'found nothing' from 'did nothing'; cycle aborted."
    fi
    CRAWLED_N="$(echo "$CRAWL_SUMMARY" | grep -oE "Indexed [0-9]+/" | grep -oE "[0-9]+" || true)"
    CRAWLED_TOTAL=$((CRAWLED_TOTAL + ${CRAWLED_N:-0}))
done
if [ -n "$CRAWL_FAILED" ]; then
    fail "Step 2 FAILED (crawl $CRAWL_FAILED exited non-zero) — cycle aborted."
fi
log "[i] Step 2 done: crawled $CRAWLED_TOTAL new post(s) across $CRAWL_NSITES site(s)."

# 5. Step 3: Merge new delta into the unified catalog
log "🔄 Step 3/3: Merging newly discovered titles into unified catalog..."
if ! "$PYTHON_BIN" "$BASE_DIR/Fast_Fetch/merge_fleet.py" >"$STEP_TMP" 2>&1; then
    cat "$STEP_TMP" >> "$LOGFILE"
    fail "Step 3 FAILED (merge fleet exited non-zero) — cycle aborted."
fi
cat "$STEP_TMP" >> "$LOGFILE"
MERGE_DONE="$(grep -E "^\[done\] fleet merged in" "$STEP_TMP" | tail -n 1 || true)"
if [ -z "$MERGE_DONE" ]; then
    fail "Step 3 produced no merge summary — cannot tell 'found nothing' from 'did nothing'; cycle aborted."
fi
MERGE_TIME="$(echo "$MERGE_DONE" | grep -oE "merged in [0-9.]+s" || true)"
UNCHANGED="$(grep -c "unchanged since last merge, skipping" "$STEP_TMP" || true)"
MERGED="$(grep -cE "^\[[a-z0-9_]+\] \{" "$STEP_TMP" || true)"
MISSING="$(grep -c "^\[!] missing" "$STEP_TMP" || true)"
ERRORED="$(grep -c "'error':" "$STEP_TMP" || true)"
log "[i] Step 3 done: fleet ${MERGE_TIME:-merged} — $MERGED site(s) merged, $UNCHANGED unchanged (skipped), $MISSING missing, $ERRORED errored."

log "[i] No backup step in this cycle: off-VM backup is owned by the daily radd-backup-hf timer (backup-hf-slim)."
log "✅ AUTONOMOUS PIPELINE CYCLE COMPLETED SUCCESSFULLY"
log "================================================================="
