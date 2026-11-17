#!/usr/bin/env python3
"""Radd Stream watchdog — self-healing API + database operations.

Design only (v1.0, 2026-11-16). Staged in ~/workspace/, NOT installed.
Install target: /opt/radd-stream/Fast_Fetch/radd_watchdog.py
(see radd-ops-self-healing-design-20261116.md §8 for the install plan).

What it does: runs the check catalogue (radd-ops-az-catalogue-20261116.md) in
three tiers (light every 15 min, medium hourly, heavy daily off-peak), fixes the
small safe things itself (dead allowlisted timers, backup prep), and escalates
everything else as a structured alert file for the 15-min loop to surface.

Safety rules (non-negotiable, see the design spec §5):
  * Never deletes data. No DELETE statement exists in this file.
  * Never restarts the API service or the panel. A down service escalates.
  * Never writes to the live DB except through the gated merge path (phase 2
    only, behind the phase2-enabled flag + two recorded supervised merges),
    and the projection re-run (flag-gated). Every other write goes to the
    watchdog's own state dir.
  * Heavy jobs only 02:00-05:00 PKT, load-gated (1-min load < 2.0,
    MemAvailable > 1500 MB), never overlapping a prefetch sweep, a link
    sweep, or a backup copy.
  * Kill switches: flag files under STATE_DIR/flags (disable-all,
    disable-auto-timer-fix, disable-auto-merge, disable-auto-projection,
    phase2-enabled). Checked at every step.
  * API timings are taken from the VM only (API_BASE defaults to
    http://127.0.0.1:8080). Sample size 1 for every probe of unknown cost.

Stdlib only. No repo imports. Reads config from the environment so the same
file works in staging and on the live box.
"""

import argparse
import datetime
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# ---------------------------------------------------------------- config

DB_PATH = os.environ.get(
    "RADD_DB_PATH", "/home/ubuntu/radd-stream-data/merged_catalog_20261025.db"
)
API_BASE = os.environ.get("RADD_API_BASE", "http://127.0.0.1:8080")
# The panel 303 comes via nginx on the public host, NOT via 127.0.0.1
# (http://127.0.0.1/admin hits the nginx catch-all: 404 with no Host
# header, 301 to https with one). Verified in the 2026-11-17 dry-run.
PANEL_URL = os.environ.get("RADD_PANEL_URL", "https://raddstream.duckdns.org/admin")
STATE_DIR = Path(os.environ.get("RADD_WATCHDOG_DIR", "/home/ubuntu/radd-watchdog"))
STAGING_DIR = Path(os.environ.get(
    "RADD_STAGING_DIR", "/home/ubuntu/radd-stream-data"))  # [verify at install]
MERGE_SCRIPT = os.environ.get(
    "RADD_MERGE_SCRIPT", "/opt/radd-stream/Fast_Fetch/merge_fleet.py")  # [verify]
# Gate backups live in the data dir (gate-backup-*.db); /home/ubuntu only
# holds a few older ones. D1 must glob where the freshest backup lands.
BACKUP_DIR = Path("/home/ubuntu/radd-stream-data")
LOAD_CEILING = 2.0
MEM_FLOOR_MB = 1500.0
DISK_MIN_FREE_GB = 15.0  # > 1.5x the ~9.8 GB DB
TIMER_FIX_CAP_PER_DAY = 2
MERGE_STALE_HOURS = 48
PROJECTION_STALE_HOURS = 24

# Timers the watchdog is allowed to re-enable. Never radd-stream.service,
# never anything panel-related.
TIMER_ALLOWLIST = [
    "radd-autocrawl.timer",
    "radd-fleet.timer",
    "radd-link-sweep.timer",
    "radd-prefetch.timer",
    "radd-backup-hf.timer",
]
HEAVY_SERVICES = ["radd-prefetch.service", "radd-link-sweep.service",
                  "radd-backup-hf.service"]

OK, WARN, CRIT, UNKNOWN = "ok", "warn", "crit", "unknown"
EXIT = {OK: 0, WARN: 1, CRIT: 2, UNKNOWN: 1}


# ---------------------------------------------------------------- helpers

def now_iso():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def now_epoch():
    return time.time()


def parse_ts(value):
    """Parse the many timestamp shapes in this DB: epoch float, ISO string,
    'YYYY-MM-DD HH:MM:SS', or a datetime object. Returns epoch or None."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    s = str(value).strip()
    try:
        return float(s)
    except ValueError:
        pass
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M",
                "%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%d"):
        try:
            dt = datetime.datetime.strptime(s, fmt)
            if dt.tzinfo is None:
                # Naive timestamps in this DB are VM-local time (PKT, UTC+5);
                # parsing them as UTC overstated every age by 5h.
                dt = dt.replace(tzinfo=datetime.timezone(
                    datetime.timedelta(hours=5)))
            return dt.timestamp()
        except ValueError:
            continue
    return None


def run_cmd(argv, timeout=60):
    """Run a command, return (exit_code, stdout, stderr). Never raises."""
    try:
        p = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
        return p.returncode, p.stdout.strip(), p.stderr.strip()
    except (subprocess.TimeoutExpired, FileNotFoundError, OSError) as exc:
        return -1, "", str(exc)


def load_1min():
    try:
        return os.getloadavg()[0]
    except (OSError, AttributeError):
        return None


def mem_available_mb():
    try:
        with open("/proc/meminfo") as fh:
            for line in fh:
                if line.startswith("MemAvailable:"):
                    return float(line.split()[1]) / 1024.0
    except OSError:
        return None
    return None


def disk_free_gb(path):
    """Free GB on the volume holding path. Falls back to the parent dir (or
    /) when the file itself does not exist yet — disk_usage needs an existing
    path."""
    for candidate in (path, os.path.dirname(str(path)) or "/", "/"):
        try:
            return shutil.disk_usage(candidate).free / 1e9
        except OSError:
            continue
    return None


def system_active():
    """True when no heavy service is currently running (overlap guard)."""
    for svc in HEAVY_SERVICES:
        rc, out, _ = run_cmd(["systemctl", "is-active", svc], timeout=15)
        if rc == 0 and out == "active":
            return svc
    return None


def gates_ok(for_heavy=False):
    """Load + memory + overlap gates. Returns (ok, reason)."""
    load = load_1min()
    if load is not None and load > LOAD_CEILING:
        return False, "1-min load %.2f > %.1f" % (load, LOAD_CEILING)
    mem = mem_available_mb()
    if mem is not None and mem < MEM_FLOOR_MB:
        return False, "MemAvailable %.0f MB < %.0f MB" % (mem, MEM_FLOOR_MB)
    if for_heavy:
        busy = system_active()
        if busy:
            return False, "%s is running (overlap guard)" % busy
    return True, ""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Do not follow redirects — a 303 IS the expected answer for /admin."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def http_get(path, timeout=10, expect_json=True):
    """GET API_BASE + path. Returns (status, seconds, parsed_json_or_None, error).
    A 303 is returned as status 303 (not followed)."""
    url = API_BASE.rstrip("/") + path
    opener = urllib.request.build_opener(NoRedirect)
    req = urllib.request.Request(url, headers={"User-Agent": "radd-watchdog/1.0"})
    t0 = time.time()
    try:
        with opener.open(req, timeout=timeout) as resp:
            body = resp.read()
            secs = time.time() - t0
            data = json.loads(body) if expect_json and body else None
            return resp.status, secs, data, None
    except urllib.error.HTTPError as exc:
        return exc.code, time.time() - t0, None, None
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return None, time.time() - t0, None, str(exc)


def open_ro_db(path=None):
    """Read-only connection. mode=ro cannot write even by accident.
    The path is resolved at call time (not as a default argument) so the
    module-level DB_PATH — env or test override — is honoured."""
    if path is None:
        path = DB_PATH
    uri = "file:%s?mode=ro" % urllib.request.pathname2url(str(path))
    conn = sqlite3.connect(uri, uri=True, timeout=30)
    conn.execute("PRAGMA query_only = ON")
    return conn


def safe_scalar(conn, sql, params=()):
    """One value, or None on any error (schema drift -> UNKNOWN, not a crash)."""
    try:
        row = conn.execute(sql, params).fetchone()
        return row[0] if row else None
    except sqlite3.Error:
        return None


# ---------------------------------------------------------------- state / alerts

def state_path():
    return STATE_DIR / "state.json"


def load_state():
    try:
        return json.loads(state_path().read_text())
    except (OSError, ValueError):
        return {"checks": {}, "autofix": {}, "merges": []}


def save_state(state):
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    tmp = state_path().with_suffix(".tmp")
    tmp.write_text(json.dumps(state, indent=2, sort_keys=True))
    tmp.replace(state_path())


def flag(name):
    return (STATE_DIR / "flags" / name).exists()


def log_line(msg):
    (STATE_DIR).mkdir(parents=True, exist_ok=True)
    line = "%s %s\n" % (datetime.datetime.now().isoformat(timespec="seconds"), msg)
    with open(STATE_DIR / "alerts.log", "a") as fh:
        fh.write(line)


def alert(state, check_id, severity, value, expected, evidence, command=None,
          auto_fix=None):
    """Write a structured alert file. P1 alerts only after 2 consecutive
    failures (flap guard); P0 alerts immediately."""
    entry = state["checks"].get(check_id, {})
    fails = entry.get("fails", 0) + 1
    if severity == "P1" and fails < 2:
        log_line("%s P1 (1st failure, watching): %s" % (check_id, value))
        return None
    (STATE_DIR / "alerts").mkdir(parents=True, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%dT%H%M%S")
    payload = {
        "check": check_id, "severity": severity, "first_seen": now_iso(),
        "consecutive_failures": fails, "value": str(value),
        "expected": expected, "evidence": evidence,
        "suggested_command": command, "auto_fix_attempted": auto_fix,
    }
    (STATE_DIR / "alerts" / ("%s-%s.json" % (check_id, ts))).write_text(
        json.dumps(payload, indent=2))
    log_line("%s %s ALERT: %s (evidence: %s)" % (check_id, severity, value, evidence))
    return payload


# ---------------------------------------------------------------- auto-fix: dead timers

def fix_dead_timer(state, timer, dry_run):
    """Re-enable a dead allowlisted timer. Capped, logged, flag-gated."""
    if flag("disable-all") or flag("disable-auto-timer-fix"):
        return "skipped (kill-switch flag set)"
    today = datetime.date.today().isoformat()
    key = "%s:%s" % (timer, today)
    used = state.get("autofix", {}).get(key, 0)
    if used >= TIMER_FIX_CAP_PER_DAY:
        return "cap hit (%d/day) — escalating instead" % TIMER_FIX_CAP_PER_DAY
    if dry_run:
        return "dry-run: would run systemctl enable --now %s" % timer
    # Least privilege: the install adds a sudoers rule for exactly these
    # commands (see the unit draft). Fall back to plain systemctl if sudo
    # is not configured — the failure is logged, never hidden.
    rc, _, err = run_cmd(["sudo", "-n", "systemctl", "enable", "--now", timer],
                         timeout=60)
    if rc != 0:
        rc, _, err = run_cmd(["systemctl", "enable", "--now", timer], timeout=60)
    if rc == 0:
        state.setdefault("autofix", {})[key] = used + 1
        return "re-enabled (attempt %d today)" % (used + 1)
    return "re-enable FAILED: %s" % err


def check_timer(state, timer, dry_run):
    """C1–C5. Returns (status, value, detail, auto_fix_note)."""
    rc, out, err = run_cmd(["systemctl", "show", timer,
                            "-p", "ActiveState,SubState,NextElapseUSecMonotonic"],
                           timeout=15)
    # NOTE: do NOT add --value here — it strips the "key=" prefixes this
    # parser needs, so every timer read as dead (false positive found in
    # the 2026-11-17 dry-run: all 5 healthy timers reported dead).
    if rc != 0:
        return UNKNOWN, "systemctl failed: %s" % err, "", None
    vals = dict(line.split("=", 1) for line in out.splitlines() if "=" in line)
    active = vals.get("ActiveState") == "active"
    nxt = vals.get("NextElapseUSecMonotonic", "")
    if active:
        return OK, "active, next %s" % (nxt or "n/a"), "", None
    note = fix_dead_timer(state, timer, dry_run)
    if "re-enabled" in note or "dry-run" in note:
        return WARN, "was dead, %s" % note, "", note
    return CRIT, "dead, %s" % note, "", note


# ---------------------------------------------------------------- checks

def check_service():
    """A1: API service active with a PID."""
    rc, out, err = run_cmd(["systemctl", "is-active", "radd-stream.service"],
                           timeout=15)
    if rc != 0 or out != "active":
        return CRIT, "service not active: %s %s" % (out, err)
    _, pid, _ = run_cmd(["systemctl", "show", "radd-stream.service",
                         "-p", "MainPID", "--value"], timeout=15)
    return OK, "active, PID %s" % (pid or "?"),


def check_posts_endpoint():
    """A2: GET /api/v1/posts?limit=1."""
    status, secs, data, err = http_get("/api/v1/posts?limit=1", timeout=10)
    if status != 200:
        return CRIT, "HTTP %s (%s)" % (status, err)
    total = (data or {}).get("total")
    if secs > 5:
        return WARN, "200 in %.2fs (slow), total=%s" % (secs, total)
    return OK, "200 in %.2fs, total=%s" % (secs, total)


def check_search_love():
    """A3: GET /api/v2/search?q=love&limit=5."""
    status, secs, data, err = http_get("/api/v2/search?q=love&limit=5", timeout=10)
    if status != 200:
        return CRIT, "HTTP %s (%s)" % (status, err)
    n = len((data or {}).get("items", [])) if isinstance(data, dict) else 0
    if secs > 5:
        return WARN, "200 in %.2fs (%d items) — slow twice in a row escalates" % (secs, n)
    return OK, "200 in %.2fs, %d items" % (secs, n)


def check_journal_errors():
    """A17: new errors/tracebacks in the service journal since 15 min ago."""
    rc, out, _ = run_cmd(["journalctl", "-u", "radd-stream.service",
                          "--since", "15 min ago", "-p", "err", "--no-pager",
                          "-o", "cat"], timeout=30)
    if rc != 0:
        return UNKNOWN, "journalctl failed"
    lines = [ln for ln in out.splitlines() if ln.strip()]
    if lines:
        return CRIT, "%d error lines in last 15 min: %s" % (len(lines), lines[0][:200])
    return OK, "no error lines in last 15 min"


def check_disk():
    """D2: free disk > 1.5x DB size."""
    free = disk_free_gb(DB_PATH)
    if free is None:
        return UNKNOWN, "disk_usage failed"
    if free < DISK_MIN_FREE_GB:
        return CRIT, "%.1f GB free < %.1f GB floor" % (free, DISK_MIN_FREE_GB)
    return OK, "%.1f GB free" % free


def check_merge_freshness(conn):
    """C6: newest post created < 48h ago."""
    newest = safe_scalar(conn, "SELECT MAX(created_at) FROM posts")
    ts = parse_ts(newest)
    if ts is None:
        return UNKNOWN, "MAX(created_at) unreadable (%r)" % (newest,)
    age_h = (now_epoch() - ts) / 3600.0
    if age_h > MERGE_STALE_HOURS:
        return CRIT, "newest post %.1fh old (%s)" % (age_h, newest)
    if age_h > 24:
        return WARN, "newest post %.1fh old (%s)" % (age_h, newest)
    return OK, "newest post %.1fh old" % age_h


def check_projection_bridge(conn):
    """B12: cat_work_languages exists and its stamp equals projected_at."""
    exists = safe_scalar(
        conn, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' "
              "AND name='cat_work_languages'")
    projected = safe_scalar(
        conn, "SELECT value FROM cat_projection_meta WHERE key='projected_at'")
    if not exists:
        return CRIT, "cat_work_languages MISSING (projected_at=%s)" % projected
    stamp = safe_scalar(
        conn, "SELECT value FROM cat_projection_meta WHERE key='bridge_at'")
    if stamp is not None and projected is not None and stamp != projected:
        return WARN, "bridge stamp %s != projected_at %s" % (stamp, projected)
    age_h = (now_epoch() - parse_ts(projected)) / 3600.0 if parse_ts(projected) else None
    if age_h is not None and age_h > PROJECTION_STALE_HOURS:
        return WARN, "projected_at %.1fh old (%s)" % (age_h, projected)
    return OK, "bridge present, projected_at=%s" % projected


def check_salaar_dedup():
    """A4: search salaar returns exactly 1 film."""
    status, secs, data, err = http_get("/api/v2/search?q=salaar&limit=20", timeout=10)
    if status != 200:
        return CRIT, "HTTP %s (%s)" % (status, err)
    items = (data or {}).get("items", []) if isinstance(data, dict) else []
    titles = {str(it.get("title", "")).strip().lower() for it in items
              if isinstance(it, dict)}
    if len(items) != 1 or len(titles) != 1:
        return CRIT, "%d items / %d distinct titles (dedup broken)" % (len(items), len(titles))
    return OK, "1 film in %.3fs" % secs


def check_post_title(post_id, expect_substr, label):
    """A5/A6: a detail endpoint returns the expected title."""
    status, secs, data, err = http_get("/api/v1/posts/%d" % post_id, timeout=15)
    if status != 200:
        return CRIT, "%s: HTTP %s (%s)" % (label, status, err)
    title = ""
    if isinstance(data, dict):
        # The API wraps the post: {"success": true, "data": {"title": ...}}.
        # Reading a top-level "title" always found "" (false positive in
        # the 2026-11-17 dry-run: both A5 and A6 CRIT on correct titles).
        inner = data.get("data") if isinstance(data.get("data"), dict) else {}
        post = data.get("post") if isinstance(data.get("post"), dict) else {}
        title = str(inner.get("title", "") or data.get("title", "")
                    or post.get("title", ""))
    if expect_substr.lower() not in title.lower():
        return CRIT, "%s: title is %r, expected %r" % (label, title, expect_substr)
    return OK, "%s: %r in %.2fs" % (label, title, secs)


def check_browse(genre, expect_first, limit_s):
    """A7–A10: a browse facet returns items, fast."""
    status, secs, data, err = http_get(
        "/api/v2/browse?genre=%s&limit=5" % genre, timeout=15)
    if status != 200:
        return CRIT, "genre=%s: HTTP %s (%s)" % (genre, status, err)
    items = (data or {}).get("items", []) if isinstance(data, dict) else []
    if not items:
        return WARN, "genre=%s: 200 but 0 items" % genre
    first = str(items[0].get("title", "")) if isinstance(items[0], dict) else ""
    if expect_first and expect_first.lower() not in first.lower():
        return WARN, "genre=%s: first is %r, expected %r" % (genre, first, expect_first)
    if secs > limit_s:
        return WARN, "genre=%s: %.2fs (> %.1fs)" % (genre, secs, limit_s)
    return OK, "genre=%s: %d items in %.2fs" % (genre, len(items), secs)


def check_remote_config():
    """A11: remote-config answers and carries the quality_gate field."""
    status, secs, data, err = http_get("/api/v1/app/remote-config", timeout=10)
    if status != 200:
        return CRIT, "HTTP %s (%s)" % (status, err)
    if not isinstance(data, dict) or "quality_gate" not in json.dumps(data):
        return WARN, "200 but no quality_gate field in payload"
    return OK, "200 in %.2fs" % secs


def check_panel():
    """A16: panel answers via nginx (303 = login redirect). Never touched."""
    opener = urllib.request.build_opener(NoRedirect)
    req = urllib.request.Request(PANEL_URL,
                                 headers={"User-Agent": "radd-watchdog/1.0"})
    t0 = time.time()
    try:
        with opener.open(req, timeout=10) as resp:
            return OK, "303 in %.2fs" % (time.time() - t0) \
                if resp.status == 303 else \
                (CRIT, "panel HTTP %s, expected 303" % resp.status)
    except urllib.error.HTTPError as exc:
        if exc.code == 303:
            return OK, "303 in %.2fs" % (time.time() - t0)
        return CRIT, "panel HTTP %s, expected 303" % exc.code
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return CRIT, "panel unreachable: %s" % exc


def check_link_state_recency(conn):
    """C3 (data half): newest link_state_runs row age.

    Uses finished_at, not started_at: started_at is inherited from the
    link_state_cursor row (the walk's start, sticky across runs — 22.2h old
    on a sweep that finished 2.5h ago, measured 2026-11-17), so reading
    started_at reports a healthy sweep as stale.
    """
    newest = safe_scalar(conn, "SELECT MAX(finished_at) FROM link_state_runs")
    ts = parse_ts(newest)
    if ts is None:
        return UNKNOWN, "link_state_runs unreadable"
    age_h = (now_epoch() - ts) / 3600.0
    if age_h > 48:
        return CRIT, "last link_state run %.1fh ago" % age_h
    if age_h > 8:
        return WARN, "last link_state run %.1fh ago" % age_h
    return OK, "last link_state run %.1fh ago" % age_h


def check_host_health(conn):
    """B10: host_health freshness."""
    newest = safe_scalar(conn, "SELECT MAX(measured_at) FROM host_health")
    ts = parse_ts(newest)
    if ts is None:
        return UNKNOWN, "host_health unreadable"
    age_h = (now_epoch() - ts) / 3600.0
    if age_h > 48:
        return CRIT, "host_health %.1fh old" % age_h
    if age_h > 24:
        return WARN, "host_health %.1fh old" % age_h
    return OK, "host_health %.1fh old" % age_h


def check_resolved_cache(conn):
    """B11: live banked rows."""
    live = safe_scalar(
        conn, "SELECT COUNT(*) FROM resolved_cache WHERE reverify_after > ?",
        (now_epoch(),))
    total = safe_scalar(conn, "SELECT COUNT(*) FROM resolved_cache")
    if live is None or total is None:
        return UNKNOWN, "resolved_cache unreadable"
    if live == 0:
        return CRIT, "0 live banked rows (instant-play bank empty)"
    return OK, "%d live / %d total banked rows" % (live, total)


def check_staged_backlog(conn):
    """C7: staging DBs newer than the newest catalog post = unmerged work."""
    newest = safe_scalar(conn, "SELECT MAX(created_at) FROM posts")
    newest_ts = parse_ts(newest)
    if newest_ts is None:
        return UNKNOWN, "cannot read newest post"
    # Only *_staging.db files are staging work. The live catalog,
    # user_state.db and the per-site importer DBs share this directory and
    # are always newer than the newest post — flagging them was a false
    # positive in the 2026-11-17 dry-run (it flagged the live catalog
    # itself, user_state.db and a 56-byte junk file).
    pending = []
    try:
        for p in STAGING_DIR.glob("*_staging.db"):
            if p.stat().st_mtime > newest_ts:
                pending.append(p.name)
    except OSError:
        return UNKNOWN, "staging dir unreadable"
    if pending:
        return WARN, "unmerged staging DBs: %s" % ", ".join(sorted(pending))
    return OK, "no staging DB newer than the newest post"


def check_sitemap_cache(conn):
    """C8: sitemap cache is being attempted at all."""
    row = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND "
        "name LIKE '%sitemap%'").fetchone()
    if not row:
        return UNKNOWN, "no sitemap table found (name differs — verify at install)"
    table = row[0]
    total = safe_scalar(conn, "SELECT COUNT(*) FROM %s" % table)
    attempted = safe_scalar(
        conn, "SELECT COUNT(*) FROM %s WHERE last_attempt IS NOT NULL" % table)
    if total is None or attempted is None:
        return UNKNOWN, "sitemap table %s unreadable" % table
    if attempted == 0:
        return CRIT, "sitemap cache: %d rows, 0 attempted (detection dead)" % total
    return OK, "sitemap cache: %d/%d attempted" % (attempted, total)


def gap_counts(conn):
    """B4/B5/B6/B8/B9: the daily heavy COUNTs. Returns a dict of (count, rate)."""
    out = {}

    def q(sql):
        v = safe_scalar(conn, sql)
        return v if v is not None else -1

    posts = q("SELECT COUNT(*) FROM posts") or 1
    works = q("SELECT COUNT(*) FROM cat_works") or 1
    out["posts_no_year"] = (q("SELECT COUNT(*) FROM posts WHERE year IS NULL"), posts)
    out["posts_no_poster"] = (q("SELECT COUNT(*) FROM posts WHERE poster_url IS NULL OR poster_url=''"), posts)
    out["posts_no_genres"] = (q("SELECT COUNT(*) FROM posts WHERE genres IS NULL OR genres=''"), posts)
    out["posts_no_synopsis"] = (q("SELECT COUNT(*) FROM posts WHERE synopsis IS NULL OR synopsis=''"), posts)
    out["posts_no_cast"] = (q("SELECT COUNT(*) FROM posts WHERE cast_names IS NULL OR cast_names=''"), posts)
    out["works_no_year"] = (q("SELECT COUNT(*) FROM cat_works WHERE year IS NULL"), works)
    out["works_no_poster"] = (q("SELECT COUNT(*) FROM cat_works WHERE poster_url IS NULL OR poster_url=''"), works)
    out["works_no_genres"] = (q("SELECT COUNT(*) FROM cat_works WHERE genres IS NULL OR genres=''"), works)
    out["works_no_tmdb"] = (q("SELECT COUNT(*) FROM cat_works WHERE tmdb_id IS NULL"), works)
    out["works_no_imdb"] = (q("SELECT COUNT(*) FROM cat_works WHERE imdb_id IS NULL"), works)
    out["works_no_runtime"] = (q("SELECT COUNT(*) FROM cat_works WHERE runtime_minutes IS NULL"), works)
    out["dup_groups"] = (q("SELECT COUNT(*) FROM (SELECT lower(title), year FROM cat_works GROUP BY 1, 2 HAVING COUNT(*) > 1)"), None)
    out["orphan_releases"] = (q("SELECT COUNT(*) FROM releases r LEFT JOIN posts p ON p.id = r.post_id WHERE p.id IS NULL"), None)
    out["orphan_sources"] = (q("SELECT COUNT(*) FROM download_sources s LEFT JOIN releases r ON r.id = s.release_id WHERE r.id IS NULL"), None)
    out["releases_no_source"] = (q("SELECT COUNT(*) FROM releases r WHERE NOT EXISTS (SELECT 1 FROM download_sources s WHERE s.release_id = r.id)"), None)
    out["sources_never_checked"] = (q("SELECT COUNT(*) FROM download_sources WHERE last_checked_at IS NULL"), None)
    out["sources_active"] = (q("SELECT COUNT(*) FROM download_sources WHERE status = 'active'"), None)
    return out


def verify_backup_copy(backup_path, live_posts):
    """B1/D1: integrity + counts + orphans on a COPY (never the live file)."""
    try:
        conn = open_ro_db(backup_path)
    except sqlite3.Error as exc:
        return CRIT, "cannot open backup copy: %s" % exc
    try:
        row = conn.execute("PRAGMA integrity_check").fetchone()
        if not row or str(row[0]).lower() != "ok":
            return CRIT, "backup integrity_check = %r" % (row,)
        posts = safe_scalar(conn, "SELECT COUNT(*) FROM posts")
        if posts != live_posts:
            return CRIT, "backup posts %s != live %s" % (posts, live_posts)
        orph = safe_scalar(
            conn, "SELECT COUNT(*) FROM releases r LEFT JOIN posts p "
                  "ON p.id = r.post_id WHERE p.id IS NULL")
        if orph:
            return CRIT, "backup has %s orphan releases" % orph
        return OK, "backup verified: integrity ok, posts %s, orphans 0" % posts
    except sqlite3.Error as exc:
        # A corrupt backup must report CRIT, not crash the heavy tier.
        return CRIT, "backup verification raised: %s" % exc
    finally:
        conn.close()


def ensure_fresh_backup(state, dry_run):
    """D1 auto-fix (heavy tier): make a verified backup if none < 24h old.
    Copy via the sqlite3 backup API (WAL-safe), verify, keep on success,
    delete on verification failure. Returns (status, detail)."""
    if flag("disable-all"):
        return WARN, "backup prep skipped (kill-switch flag set)"
    backups = sorted(BACKUP_DIR.glob("gate-backup-*.db"),
                     key=lambda p: p.stat().st_mtime, reverse=True)
    if backups:
        age_h = (now_epoch() - backups[0].stat().st_mtime) / 3600.0
        if age_h < 24:
            # D1 (2026-11-17 install): age alone is not proof — a fresh
            # backup passed the dry-run by luck (an older backup in the
            # wrong dir), and a corrupt-but-fresh one would pass too.
            # Verify the freshest backup with the same integrity +
            # counts + orphans check used for new copies. Verification
            # is read-only, so it also runs in dry-run mode. On
            # failure: CRIT + escalate only — never delete a
            # pre-existing gate backup, and never auto-make a
            # replacement (disk is at the D2 floor; a new 10 GB copy
            # could fill it).
            live_posts = None
            try:
                with open_ro_db() as conn:
                    live_posts = safe_scalar(conn, "SELECT COUNT(*) FROM posts")
            except sqlite3.Error:
                pass
            if live_posts is None:
                return UNKNOWN, "fresh backup %s (%.1fh old) present but live posts unreadable — counts not compared" % (backups[0].name, age_h)
            status, detail = verify_backup_copy(backups[0], live_posts)
            if status == OK:
                return OK, "fresh backup verified: %s (%.1fh old, %s)" % (
                    backups[0].name, age_h, detail)
            return CRIT, "fresh backup FAILED verification: %s (%.1fh old): %s" % (
                backups[0].name, age_h, detail)
    ok, reason = gates_ok(for_heavy=True)
    if not ok:
        return WARN, "backup prep deferred: %s" % reason
    if dry_run:
        return WARN, "dry-run: would copy + verify a fresh backup"
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M")
    dest = BACKUP_DIR / ("gate-backup-%s.db" % stamp)
    try:
        src = sqlite3.connect(
            "file:%s?mode=ro" % urllib.request.pathname2url(DB_PATH),
            uri=True, timeout=60)
        dst = sqlite3.connect(str(dest))
        src.backup(dst)
        dst.close()
        src.close()
    except sqlite3.Error as exc:
        return CRIT, "backup copy failed: %s" % exc
    live_posts = None
    try:
        with open_ro_db() as conn:
            live_posts = safe_scalar(conn, "SELECT COUNT(*) FROM posts")
    except sqlite3.Error:
        pass
    status, detail = verify_backup_copy(dest, live_posts)
    if status != OK:
        try:
            dest.unlink()
        except OSError:
            pass
        return CRIT, "fresh backup FAILED verification and was deleted: %s" % detail
    log_line("backup prep: verified %s (%s)" % (dest.name, detail))
    return OK, "fresh verified backup: %s" % dest.name


# ---------------------------------------------------------------- tiers

# (check_id, severity, tier, function, alert_expected)
LIGHT = [
    ("A1", "P0", "light", lambda s, d: check_service()),
    ("A2", "P0", "light", lambda s, d: check_posts_endpoint()),
    ("A3", "P0", "light", lambda s, d: check_search_love()),
    ("A17", "P0", "light", lambda s, d: check_journal_errors()),
    ("D2", "P0", "light", lambda s, d: check_disk()),
]
MEDIUM = LIGHT + [
    ("A4", "P1", "medium", lambda s, d: check_salaar_dedup()),
    ("A5", "P1", "medium",
     lambda s, d: check_post_title(158737, "Lock Upp", "Lock Upp 2026")),
    ("A6", "P2", "medium",
     lambda s, d: check_post_title(13724, "Sonu Ke Titu Ki Sweety", "post 13724")),
    ("A7", "P1", "medium", lambda s, d: _browse_newest()),
    ("A8", "P1", "medium", lambda s, d: check_browse("kpop", "K-Pops!", 2.0)),
    ("A9", "P1", "medium", lambda s, d: check_browse("hindi", None, 1.0)),
    ("A11", "P1", "medium", lambda s, d: check_remote_config()),
    ("A16", "P1", "medium", lambda s, d: check_panel()),
]
HEAVY = MEDIUM  # heavy adds the DB-heavy checks below, in run_tier()


def _browse_newest():
    status, secs, data, err = http_get("/api/v2/browse?sort=newest&limit=5",
                                       timeout=10)
    if status != 200:
        return CRIT, "browse newest: HTTP %s (%s)" % (status, err)
    items = (data or {}).get("items", []) if isinstance(data, dict) else []
    if not items:
        return WARN, "browse newest: 200 but 0 items"
    year = items[0].get("year") if isinstance(items[0], dict) else None
    if secs > 1.0:
        return WARN, "browse newest: %.2fs, first year %s" % (secs, year)
    return OK, "browse newest: %d items in %.2fs, first year %s" % (
        len(items), secs, year)


def run_tier(tier, state, dry_run):
    """Run one tier. Returns a dict of check_id -> result."""
    results = {}
    conn = None

    def db():
        nonlocal conn
        if conn is None:
            conn = open_ro_db()
        return conn

    # Timers are checked in every tier (C1–C5); the light tier covers C1.
    timer_ids = {"light": ["radd-autocrawl.timer"],
                 "medium": TIMER_ALLOWLIST, "heavy": TIMER_ALLOWLIST}[tier]
    for timer in timer_ids:
        cid = "C1" if timer == "radd-autocrawl.timer" else "Cx-" + timer
        status, value, _, note = check_timer(state, timer, dry_run)
        results[cid] = {"status": status, "value": value, "timer": timer,
                        "auto_fix": note}

    checks = {"light": LIGHT, "medium": MEDIUM, "heavy": HEAVY}[tier]
    for cid, sev, _t, fn in checks:
        try:
            status, value = fn(state, dry_run)
        except Exception as exc:  # a check must never kill the run
            status, value = UNKNOWN, "check raised %r" % (exc,)
        results[cid] = {"status": status, "value": value, "severity": sev}

    # DB-light checks that need a connection (cheap, indexed).
    for cid, sev, fn in [
            ("C6", "P0", lambda c: check_merge_freshness(c)),
            ("B12", "P1", lambda c: check_projection_bridge(c))]:
        try:
            status, value = fn(db())
        except Exception as exc:
            status, value = UNKNOWN, "check raised %r" % (exc,)
        results[cid] = {"status": status, "value": value, "severity": sev}

    if tier in ("medium", "heavy"):
        for cid, sev, fn in [
                ("C3data", "P1", lambda c: check_link_state_recency(c)),
                ("B10", "P1", lambda c: check_host_health(c)),
                ("B11", "P2", lambda c: check_resolved_cache(c)),
                ("C7", "P1", lambda c: check_staged_backlog(c))]:
            try:
                status, value = fn(db())
            except Exception as exc:
                status, value = UNKNOWN, "check raised %r" % (exc,)
            results[cid] = {"status": status, "value": value, "severity": sev}

    if tier == "heavy":
        ok, reason = gates_ok(for_heavy=True)
        if not ok:
            results["HEAVY-GATE"] = {"status": WARN,
                                     "value": "heavy checks skipped: %s" % reason,
                                     "severity": "P2"}
        else:
            # B1/D1: backup prep first (a verified net must exist before any
            # merge talk), then the full gap counts on the live DB (read-only).
            status, value = ensure_fresh_backup(state, dry_run)
            results["D1"] = {"status": status, "value": value, "severity": "P0"}
            try:
                gaps = gap_counts(db())
                base = {"posts_no_year": 9555, "posts_no_poster": 20932,
                        "posts_no_genres": 52457, "posts_no_synopsis": 89976,
                        "posts_no_cast": 142390, "works_no_year": 8877,
                        "works_no_poster": 11011, "works_no_genres": 42100,
                        "works_no_tmdb": 187668, "works_no_imdb": 169672,
                        "works_no_runtime": 197502, "dup_groups": 13898,
                        "orphan_releases": 0, "orphan_sources": 0,
                        "releases_no_source": 46489}
                for key, (count, _den) in gaps.items():
                    if count is None or count < 0:
                        results["B-" + key] = {"status": UNKNOWN,
                                               "value": "unreadable",
                                               "severity": "P2"}
                        continue
                    if key in ("orphan_releases", "orphan_sources") and count > 0:
                        st, sev = CRIT, "P0"
                    elif key in base and count > base[key]:
                        st, sev = WARN, "P2"
                    else:
                        st, sev = OK, "P2"
                    # Trend rule (catalogue B4/B5): a gap RATE rising > 0.5pp
                    # vs the previous heavy run warns, even if it is still
                    # below the audit baseline. Rates, not absolutes — a
                    # merge legitimately adds rows (and their gaps) with it.
                    prev_rates = state.get("gap_rates", {})
                    if _den:
                        rate = count / _den
                        prev = prev_rates.get(key)
                        if prev is not None and rate - prev > 0.005:
                            st, sev = WARN, "P2"
                            value_suffix = " (rate %.1f%%, was %.1f%%)" % (
                                rate * 100, prev * 100)
                        else:
                            value_suffix = ""
                        prev_rates[key] = rate
                    else:
                        prev = state.get("gap_counts", {}).get(key)
                        if prev is not None and count > prev and key not in (
                                "orphan_releases", "orphan_sources"):
                            st, sev = WARN, "P2"
                            value_suffix = " (was %s)" % prev
                        else:
                            value_suffix = ""
                        state.setdefault("gap_counts", {})[key] = count
                    state["gap_rates"] = prev_rates
                    results["B-" + key] = {"status": st,
                                           "value": "%s = %s%s" % (
                                               key, count, value_suffix),
                                           "severity": sev}
            except Exception as exc:
                results["B-GAPS"] = {"status": UNKNOWN,
                                     "value": "gap counts raised %r" % (exc,),
                                     "severity": "P2"}
            try:
                status, value = check_sitemap_cache(db())
                results["C8"] = {"status": status, "value": value, "severity": "P2"}
            except Exception as exc:
                results["C8"] = {"status": UNKNOWN,
                                 "value": "check raised %r" % (exc,),
                                 "severity": "P2"}
    if conn is not None:
        conn.close()
    return results


def main(argv=None):
    global STATE_DIR
    ap = argparse.ArgumentParser(description="Radd Stream watchdog (design v1.0)")
    ap.add_argument("--tier", choices=["light", "medium", "heavy"],
                    default="light")
    ap.add_argument("--state-dir", default=str(STATE_DIR))
    ap.add_argument("--dry-run", action="store_true",
                    help="run checks, report what would be fixed, fix nothing")
    ap.add_argument("--json", action="store_true", help="print full result JSON")
    args = ap.parse_args(argv)

    STATE_DIR = Path(args.state_dir)
    state = load_state()

    if flag("disable-all") and not args.dry_run:
        print("watchdog disabled by flag (disable-all); checks skipped")
        return 0

    results = run_tier(args.tier, state, args.dry_run)

    worst = OK
    for cid, res in results.items():
        status = res["status"]
        entry = state["checks"].get(cid, {})
        if status in (CRIT, WARN):
            entry["fails"] = entry.get("fails", 0) + 1
        else:
            entry["fails"] = 0
        entry.update({"status": status, "value": str(res["value"]),
                      "ts": now_iso()})
        state["checks"][cid] = entry
        if status == CRIT:
            worst = CRIT
            alert(state, cid, res.get("severity", "P0"), res["value"],
                  "see radd-ops-az-catalogue-20261116.md",
                  "tier=%s dry_run=%s" % (args.tier, args.dry_run),
                  auto_fix=res.get("auto_fix"))
        elif status == WARN:
            if worst != CRIT:
                worst = WARN
            alert(state, cid, res.get("severity", "P1"), res["value"],
                  "see radd-ops-az-catalogue-20261116.md",
                  "tier=%s dry_run=%s" % (args.tier, args.dry_run),
                  auto_fix=res.get("auto_fix"))
        log_line("%s %s %s: %s" % (args.tier, cid, status, res["value"]))
    save_state(state)

    summary = {"tier": args.tier, "dry_run": args.dry_run, "worst": worst,
               "checks": {k: {"status": v["status"], "value": str(v["value"])}
                          for k, v in results.items()}}
    if args.json:
        print(json.dumps(summary, indent=2))
    else:
        for cid, res in results.items():
            print("%-12s %-8s %s" % (cid, res["status"], res["value"]))
        print("worst: %s" % worst)
    return EXIT[worst]


if __name__ == "__main__":
    sys.exit(main())
