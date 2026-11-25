"""Live-store adapter for Admin Panel v2 (Phase 2: make everything real).

Reads the REAL production stores the coordinator verified read-only on
the Oracle VM — never the fixture DBs — through injectable paths, so
workspace tests run the exact same code against TEMP COPIES built from
the real DDL. Stdlib only.

Real sources (verified 2026-11-05, read-only SSH):
- Catalog: /home/ubuntu/radd-stream-data/merged_catalog_20261025.db
  posts(id, url, slug, title, clean_title, year, content_type, seasons,
  poster_url, backdrop_urls, imdb_rating, genres, synopsis, is_18_plus,
  is_animated, lastmod, created_at, updated_at, source_site, industry,
  country, language, canonical_key, tmdb_id, imdb_id),
  releases(...), download_sources(...),
  cat_works/cat_releases/cat_links/cat_link_evidence/cat_review_queue/
  cat_projection_meta(key, value), app_remote_config(id=1, 22 fields).
- Ledger: /home/ubuntu/radd-stream-data/user_state.db
  user_accounts(id, key_hash UNIQUE, created_at, last_seen_at,
  revoked_at, vip_until REAL), user_favorites, user_progress,
  payment_transactions(id, account_id, plan, amount_pkr, method, tid,
  status pending|approved|rejected, submitted_at, reviewed_at,
  reviewer_note), payment_review_audit(id, payment_id, account_id,
  action, prior_vip_until, new_vip_until, reviewer, note, acted_at).
- Domains file: /opt/radd-stream/Fast_Fetch/domain_registry.json
  (18 sites, per site active_base_url, known_mirrors[], api_type,
  api_endpoint|sitemap_endpoint, status).
- Fleet status: Fast_Fetch/<site>_status.json + admin_fleet_audit
  in the catalog DB.

Rules:
- Reads open the real DBs read-only (sqlite URI mode=ro). NEVER write
  to a live DB from the workspace; writes here are only ever called
  against temp copies in tests, and at the live gate only after a
  backup. Since PNL-08 that backup is ENFORCED in code, not just
  noted: every write goes through _rw, which refuses (ApiError
  "backup_required", 409) unless a fresh verified backup marker
  exists for that store — see admin_v2/backup_guard.py. A LiveStores
  with backup_guard=None is a fixture/test instance and is never
  live; the live entry point always passes a real guard.
- No credential, token, key hash, or transaction id is ever returned
  or logged by this module. Operator display is account_hint only:
  "acct-" + first 8 hex of sha256("radd-payment-hint:v1:" + key_hash),
  exactly as v1 payments.account_hint() computes it.
- Adult flags are READ ONLY: posts.is_18_plus / cat_works.is_18_plus.
  No setter exists anywhere in this module (OD-18).
- Blocked tables (writers not in trunk code) are NEVER touched:
  verified_incoming_tids, device_trials, user_accounts extra columns.
  cat_review_queue stays read-only; client_link_reports is never
  created. The usage tables (usage_device, usage_metric,
  usage_dim_metric) are READ-ONLY operator aggregates here: their writer
  is `usage_stats.py` in trunk (Tracking A, Session 96), and this module
  only ever reads them through `LiveStores.usage_summary()` — no write
  method for them exists anywhere in this module.
"""
import glob
import hashlib
import ipaddress
import json
import os
import re
import shutil
import sqlite3
import ssl
import subprocess
import sys
import threading
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

from .errors import ApiError, bad, conflict, not_found
from .security import valid_domain
from .store import FLEET_SITES

HINT_SALT = "radd-payment-hint:v1:"
HINT_CHARS = 8

PLAN_DAYS = {"vip_monthly": 30, "vip_yearly": 365}
PLAN_DEFAULT_PKR = {"vip_monthly": 200, "vip_yearly": 1500}
PAYMENT_METHODS = ("jazzcash", "easypaisa", "raast")

# The 19 real app_remote_config fields the Android app actually reads
# (viewer.py GET /api/v1/app/remote-config + VipRemoteConfig.kt).
# Coins are gone (removal 2026-11-17, PNL-03): the six coin pass
# prices and the `coins_enabled` switch are NOT carried here — the
# live row keeps its stored 0 for the flag and its dormant prices,
# and no panel write ever touches them again.
#
# PNL-01 redesign §0A N1 (canonical field→route map): this is the
# v2-writable live-row set — exactly store.MONETIZATION_FIELDS, and
# every draft field on the live-row route must be in BOTH lists. The
# one N1 addition is free_quality_cap: editable in the draft and
# served from the live row, yet no writer could ever put it there
# (save_remote_config rejected it as unknown). PNL-05 (Wave 3) adds
# the two ads switches to the v2 set. 19 fields: the 18 above + the
# cap. The monetization publish and rollback carry
# exactly this set; the generic publish carries the draft's live-row
# subset (maintenance_mode, apk_update_url, free_quality_cap).
REMOTE_CONFIG_FIELDS = [
    "pak_till_id", "pak_jazzcash_number", "pak_easypaisa_number",
    "pak_raast_id", "vip_monthly_pkr", "vip_yearly_pkr",
    "monetag_zone_id", "adsterra_tag_id", "ads_enabled", "clean_ads_only",
    "shrinkme_api_token",
    "droplink_api_token", "maintenance_mode", "apk_update_url",
    "feature_downloads", "feature_pure_cinema", "feature_chromecast",
    "feature_quality_gate",
    "free_quality_cap",
]

# N1 decision for the APK trio + the link-shorteners flag (second
# opinion §4.1: writer or removal from the served body). The trio
# STAYS served: the app reads apk_update_version_code / size_bytes /
# sha256 for its update check (VipRemoteConfig.kt), so removal would
# break it — and the writer already exists: the v1 /admin card has
# text rows for all three, and _save_monetization writes them to the
# live row. feature_link_shorteners is a retired flag (default 0),
# likewise v1-edited. None of the four is a v2 draft field: they are
# deliberately NOT in REMOTE_CONFIG_FIELDS, so no v2 writer can touch
# them — the map test pins that.
RELEASE_OWNED_LIVE_FIELDS = [
    "apk_update_sha256", "apk_update_size_bytes",
    "apk_update_version_code", "feature_link_shorteners",
]

# The other 22 live-row fields (admin_panel.MONETIZATION_FIELDS minus
# the sets above): ad cadence, plus the coin/streak/pass prices and the
# `coins_enabled` flag. The 3 ad fields stay v1-only BY EXPLICIT
# DECISION (PNL-01 redesign §4): no v2 UI, no v2 writer, no silent
# claim — they are edited in v1 /admin, which stamps the same version
# allocator (admin_panel._save_monetization). The 19 coin fields are
# v1-only by the PNL-03 removal: no v2 writer ever touches them, the
# columns stay dormant on the live row (flag 0), and v1 /admin still
# lists them — a v1 save is the only write path left.
V1_ONLY_LIVE_FIELDS = [
    "ad_preroll_min_gap_minutes", "ad_native_card_every_posters",
    "ad_app_open_min_gap_minutes", "coins_enabled",
    "coin_per_rewarded_ad", "rewarded_daily_cap",
    "coin_share", "coin_finish_watch",
    "pass_day_pkr", "pass_weekend_pkr", "pass_monthly_vip_pkr",
    "streak_day1_coins", "streak_day3_coins", "streak_day7_pass_hours",
    "streak_day_boundary_hour", "streak_tz",
    "coin_redeem_day", "coin_redeem_weekend", "coin_redeem_monthly",
    "coin_redeem_download", "coin_redeem_request", "coin_redeem_theme",
]

# Every field on the live app_remote_config row (19 v2-writable + 4
# release-owned + 22 v1-only = 45). The startup migration adds all of
# them (the v1 read path SELECTs all 45); the coverage test pins the
# 45 against admin_panel.MONETIZATION_FIELDS.
ALL_LIVE_REMOTE_CONFIG_FIELDS = (REMOTE_CONFIG_FIELDS
                                 + RELEASE_OWNED_LIVE_FIELDS
                                 + V1_ONLY_LIVE_FIELDS)

# Draft-only fields: served from the published snapshot via panel_feed,
# never from the live row (force-update block, brand, region order).
V2_ONLY_SNAPSHOT_FIELDS = [
    "min_version_code", "latest_version_code", "latest_version_name",
    "force_update", "force_update_message", "maintenance_message",
    "brand_primary", "brand_accent", "region_order",
]
REMOTE_CONFIG_SECRETS = ("shrinkme_api_token", "droplink_api_token")

# Columns stored as INTEGER (everything else in the live-row field
# sets is TEXT). Covers all 45 live-row fields: the startup migration
# adds every live-row column, and the only TEXT v1-only field is
# streak_tz.
# True defaults for added live-row columns (must agree with the v1
# field spec admin_panel.MONETIZATION_FIELDS - the migration seeds
# the SERVED value, not just the column: a catalog predating
# clean_ads_only must serve 1 (standing order), not 0. Fields not
# listed here default to 0 (INTEGER) or '' (TEXT). Pinned by
# test_publish_redesign.TestCoverageMap against the v1 spec.
_LIVE_COLUMN_DEFAULTS = {
    "ad_app_open_min_gap_minutes": 240,
    "ad_native_card_every_posters": 12,
    "ad_preroll_min_gap_minutes": 25,
    "clean_ads_only": 1,
    "coin_finish_watch": 15,
    "coin_per_rewarded_ad": 5,
    "coin_redeem_day": 500,
    "coin_redeem_download": 100,
    "coin_redeem_monthly": 5000,
    "coin_redeem_request": 300,
    "coin_redeem_theme": 250,
    "coin_redeem_weekend": 1200,
    "coin_share": 25,
    "feature_chromecast": 1,
    "feature_downloads": 1,
    "feature_pure_cinema": 1,
    "free_quality_cap": "480p",
    "pass_day_pkr": 20,
    "pass_monthly_vip_pkr": 250,
    "pass_weekend_pkr": 50,
    "rewarded_daily_cap": 10,
    "streak_day1_coins": 10,
    "streak_day3_coins": 30,
    "streak_day7_pass_hours": 24,
    "streak_day_boundary_hour": 4,
    "streak_tz": "Asia/Karachi",
    "vip_monthly_pkr": 200,
    "vip_yearly_pkr": 1500,
}

_REMOTE_CONFIG_INT_FIELDS = frozenset({
    "vip_monthly_pkr", "vip_yearly_pkr", "maintenance_mode",
    "feature_downloads", "feature_pure_cinema", "feature_chromecast",
    "feature_quality_gate", "coins_enabled",
    "ads_enabled", "clean_ads_only",
    "apk_update_size_bytes", "apk_update_version_code",
    "feature_link_shorteners",
    "ad_preroll_min_gap_minutes", "ad_native_card_every_posters",
    "ad_app_open_min_gap_minutes", "coin_per_rewarded_ad",
    "rewarded_daily_cap", "coin_share", "coin_finish_watch",
    "pass_day_pkr", "pass_weekend_pkr", "pass_monthly_vip_pkr",
    "streak_day1_coins", "streak_day3_coins", "streak_day7_pass_hours",
    "streak_day_boundary_hour",
    "coin_redeem_day", "coin_redeem_weekend", "coin_redeem_monthly",
    "coin_redeem_download", "coin_redeem_request", "coin_redeem_theme",
})
ENGINE_SYNC_SITES = frozenset({
    "vegamovies", "rogmovies", "ssrmovies", "7starhd", "katmoviehd",
    "multimovies", "10xflix", "hdhub4u", "filmyzilla",
})
SCRIPT_SYNC_FLAGS = {
    "9xmovies": "--sync-api", "bolly4u": "--sync-api",
    "desicinemas": "--sync-sitemaps", "hdtvb": "--sync-sitemap",
    "hindilinks4u": "--sync-sitemap", "moviezguru": "--sync-api",
    "rareanimes": "--sync-api", "toonworld4all": "--sync-api",
    "world4ufree": "--sync-api",
    "harpalgeo": "--sync-programs", "movienestbd": "--sync-search",
    "moviesdrive": "--sync-posts", "xprimehub": "--sync-sitemap",
}

# New-sites importers live in importers/ (not Fast_Fetch/): their only
# operation is `sync --limit N`, used for both fleet modes.
IMPORTER_SITES = frozenset({"castle", "dramadog", "megacartoons"})
FLEET_MODES = ("sync", "crawl")

CERT_PATH_DEFAULT = "/etc/letsencrypt/live/raddstream.duckdns.org/fullchain.pem"


class LiveUnavailable(ApiError):
    """A live store is not configured or its file is missing."""

    def __init__(self, message="That live store is not available here."):
        super().__init__("live_unavailable", message, 503)


def account_hint(key_hash):
    """Operator display label only. Identical to v1 payments.account_hint."""
    text = str(key_hash or "")
    if not text:
        return ""
    return "acct-" + hashlib.sha256(
        (HINT_SALT + text).encode("utf-8")).hexdigest()[:HINT_CHARS]


def fleet_command(site, mode, limit, python="python3",
                  base_dir="/opt/radd-stream/Fast_Fetch"):
    """The argv for one validated fleet run — exactly v1 _fleet_command.

    Every piece is a literal or an integer; the site must be in
    FLEET_SITES, so a request body can never add an argument, a path,
    or a shell word. No shell is ever used to run it.
    """
    if site not in FLEET_SITES:
        raise ValueError("unknown crawler")
    if mode not in FLEET_MODES:
        raise ValueError("unknown mode")
    if site in IMPORTER_SITES:
        # importers/ modules take `sync --limit N` for both modes.
        return [python, os.path.join(base_dir, "importers", "import_%s.py" % site),
                "sync", "--limit", str(int(limit))]
    if mode == "sync":
        if site in ENGINE_SYNC_SITES:
            return [python, os.path.join(base_dir, "fast_fetch.py"),
                    "sync", "--site", site]
        return [python, os.path.join(base_dir, "import_%s.py" % site),
                SCRIPT_SYNC_FLAGS[site]]
    limit = int(limit)
    if site == "multimovies":
        return [python, os.path.join(base_dir, "import_%s.py" % site),
                "--limit-roots", str(limit)]
    return [python, os.path.join(base_dir, "import_%s.py" % site),
            "--limit", str(limit)]


def _row(d):
    return dict(d) if d is not None else None


def _has_table(conn, name):
    row = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
        (name,)).fetchone()
    return row is not None


def ensure_live_remote_config_columns(catalog_path):
    """Startup migration for the live app_remote_config row (PNL-01 D7).

    Adds every column in ALL_LIVE_REMOTE_CONFIG_FIELDS (all 45 live-row
    fields — the 19 v2-writable plus the 4 release-owned plus the 22
    v1-only) plus config_version
    that the catalog predates, idempotently. The v1-only columns must
    be here too: the v1 read path (`admin_panel.remote_config`) SELECTs
    all 45, so a catalog that only ever saw the v2 migration would
    answer settings_unavailable on every poll. Runs at panel startup —
    Service construction calls it when a live catalog is wired — never
    in the request path, so a publish never takes an exclusive schema
    lock mid-request. A missing file or a missing table is a no-op (a
    catalog that does not exist yet has nothing to migrate); anything
    else propagates, so a half-migrated catalog fails loudly at boot
    instead of mid-publish. Returns the list of columns added."""
    if not catalog_path or not os.path.exists(catalog_path):
        return []
    conn = sqlite3.connect(catalog_path, timeout=10.0,
                           check_same_thread=False)
    try:
        if not _has_table(conn, "app_remote_config"):
            return []
        cols = {r[1] for r in conn.execute(
            "PRAGMA table_info(app_remote_config)")}
        added = []
        for key in sorted(set(ALL_LIVE_REMOTE_CONFIG_FIELDS) | {"config_version"}):
            if key in cols:
                continue
            # Seed the TRUE default (v1 spec via _LIVE_COLUMN_DEFAULTS),
            # not 0/"": a predated column must serve what a catalog
            # that had it from the start serves (clean_ads_only=1,
            # vip_monthly_pkr=200, free_quality_cap="480p", ...).
            if key in _REMOTE_CONFIG_INT_FIELDS or key == "config_version":
                default = _LIVE_COLUMN_DEFAULTS.get(key, 0)
                coltype = "INTEGER NOT NULL DEFAULT %d" % default
            else:
                default = _LIVE_COLUMN_DEFAULTS.get(key, "")
                coltype = "TEXT NOT NULL DEFAULT '%s'" % default
            conn.execute("ALTER TABLE app_remote_config ADD COLUMN %s %s"
                         % (key, coltype))
            added.append(key)
        if added:
            conn.commit()
        return added
    finally:
        conn.close()


def _to_int(value):
    """cat_projection_meta stores numbers as text; None when unparsable."""
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _to_float(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _parse_openssl_not_after(text):
    """'notAfter=Jan 31 11:04:00 2027 GMT' -> the cert_expiry dict."""
    line = (text or "").strip()
    if not line.lower().startswith("notafter="):
        return None
    raw = line.split("=", 1)[1].strip()
    try:
        dt = datetime.strptime(raw, "%b %d %H:%M:%S %Y %Z")
    except ValueError:
        return None
    dt = dt.replace(tzinfo=timezone.utc)
    return {"date": dt.strftime("%d %b %Y"),
            "iso": dt.strftime("%Y-%m-%d"),
            "unix": int(dt.timestamp())}


def _read_cert_via_sudo(path):
    """Minimal-privilege cert-expiry fallback (admin-panel audit §Cert).

    The panel runs as ubuntu and /etc/letsencrypt/live/ is root-only,
    so the direct read fails with permission denied. This asks root
    for the expiry date ONLY — one exact command, allowed in sudoers
    at deploy (root-approved, no password, no shell):

      ubuntu ALL=(root) NOPASSWD: /usr/bin/openssl x509 -enddate -noout \\
          -in /etc/letsencrypt/live/raddstream.duckdns.org/fullchain.pem

    -n makes sudo fail fast instead of prompting; the path travels as
    one argv item, never through a shell, so it cannot inject. Any
    failure (no sudoers line, sudo missing, bad output) returns None
    and the panel shows "Unknown" as before — never an error page.
    """
    if not path or "\n" in path or "\r" in path:
        return None
    try:
        proc = subprocess.run(
            ["sudo", "-n", "openssl", "x509", "-enddate", "-noout",
             "-in", path],
            capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return None
    if proc.returncode != 0:
        return None
    return _parse_openssl_not_after(proc.stdout)


def _columns(conn, table):
    return {r[1] for r in conn.execute("PRAGMA table_info(%s)" % table)}


def _clean_reviewer(reviewer):
    text = re.sub(r"[^\x20-\x7e]", "", str(reviewer or ""))[:40].strip()
    return text or "operator"


def _clean_note(note):
    return re.sub(r"[^\x20-\x7e]", "", str(note or ""))[:200].strip()


# -- usage aggregates (Tracking A, Session 97) -------------------------
# A read-only mirror of Fast_Fetch/usage_stats.summary(). The panel cannot
# import that module — it lives in the live tree, not in this workspace —
# so the bounded reads are re-implemented here, one for one, against the
# same three tables. If the server changes a window or a rate, change it
# here too and say so in the session log; a silent drift between the two
# is how an operator ends up believing two different DAUs.
USAGE_DIMS = ("geo", "locale", "app_version", "device_class", "region",
              "audio_lang", "subtitle_lang")
USAGE_UNKNOWN = "unknown"
USAGE_METRICS = frozenset({
    "session", "account_request", "playback_start", "playback_attempt",
    "playback_buffer_failure", "playback_error", "crash", "error",
    "ad_request", "ad_fill", "ad_impression", "ad_click",
    "rewarded_completion", "coin_granted",
})
#: Metrics only the phone can tell us about; the server cannot see them.
USAGE_REPORTED_ONLY = frozenset({
    "playback_attempt", "playback_buffer_failure", "playback_error",
    "crash", "error", "ad_request", "ad_fill", "ad_impression",
    "ad_click", "rewarded_completion", "coin_granted",
})
USAGE_LIVE_WINDOWS = (("5m", 300), ("30m", 1800), ("60m", 3600))
USAGE_ACTIVITY_WINDOWS = (("dau", 86400), ("wau", 7 * 86400),
                          ("mau", 30 * 86400))
USAGE_RETENTION_DAYS = (1, 7, 30)


def _usage_day(ts):
    """UTC day number — the cohort bucket, same definition as usage_stats."""
    return int(float(ts) // 86400)


def _usage_scalar(conn, sql, *args, default=0):
    try:
        row = conn.execute(sql, args).fetchone()
    except sqlite3.Error:
        return default
    if row is None or row[0] is None:
        return default
    return row[0]


def _usage_retention(conn, *, today):
    out = {}
    for horizon in USAGE_RETENTION_DAYS:
        cutoff = today - horizon
        installs = int(_usage_scalar(
            conn, "SELECT COUNT(*) FROM usage_device WHERE install_day <= ?",
            cutoff))
        returned = int(_usage_scalar(
            conn, "SELECT COUNT(*) FROM usage_device"
            " WHERE install_day <= ? AND retained_%d = 1" % horizon, cutoff))
        out["d%d" % horizon] = {
            "cohort_installs": installs,
            "returned": returned,
            "rate": (round(returned / installs, 4) if installs else None),
        }
    return out


def _usage_dimension_counts(conn, *, since=None):
    out = {}
    for dim in USAGE_DIMS:
        where, args = ("", ())
        if since is not None:
            where, args = " WHERE last_seen_at >= ?", (since,)
        try:
            rows = conn.execute(
                "SELECT %s, COUNT(*) FROM usage_device%s GROUP BY %s"
                " ORDER BY 2 DESC LIMIT 24" % (dim, where, dim), args).fetchall()
        except sqlite3.Error:
            rows = []
        out[dim] = {str(row[0] or USAGE_UNKNOWN): int(row[1]) for row in rows}
    return out


def _usage_metric_totals(conn, *, from_day, to_day):
    try:
        rows = conn.execute(
            "SELECT metric, SUM(total) FROM usage_metric"
            " WHERE day >= ? AND day <= ? GROUP BY metric",
            (from_day, to_day)).fetchall()
    except sqlite3.Error:
        rows = []
    return {str(row[0]): float(row[1] or 0) for row in rows}


def _usage_by_class(conn, *, from_day, to_day, metric):
    try:
        rows = conn.execute(
            "SELECT value, SUM(total) FROM usage_dim_metric"
            " WHERE day >= ? AND day <= ? AND dim = 'device_class' AND metric = ?"
            " GROUP BY value ORDER BY 2 DESC",
            (from_day, to_day, metric)).fetchall()
    except sqlite3.Error:
        rows = []
    return {str(row[0] or USAGE_UNKNOWN): float(row[1] or 0) for row in rows}


def _usage_payments(conn):
    out = {"by_tier": {}, "total_pkr": 0, "purchases": 0, "pending": 0}
    try:
        rows = conn.execute(
            "SELECT plan, COUNT(*), COALESCE(SUM(amount_pkr), 0)"
            " FROM payment_transactions WHERE status = 'approved' GROUP BY plan"
            " ORDER BY 2 DESC").fetchall()
    except sqlite3.Error:
        rows = []
    tiers = {}
    for plan, count, amount in rows:
        tiers[str(plan or USAGE_UNKNOWN)] = {"purchases": int(count),
                                             "amount_pkr": int(amount or 0)}
    out["by_tier"] = tiers
    out["total_pkr"] = sum(row["amount_pkr"] for row in tiers.values())
    out["purchases"] = sum(row["purchases"] for row in tiers.values())
    out["pending"] = int(_usage_scalar(
        conn, "SELECT COUNT(*) FROM payment_transactions WHERE status = 'pending'"))
    return out


def _usage_env_float(name, default):
    try:
        return float(os.environ.get(name, "") or default)
    except (TypeError, ValueError):
        return default


def _usage_revenue_estimate(*, metrics, users, days):
    ecpm = _usage_env_float("RADD_AD_ECPM_USD", 0.9)
    fx = _usage_env_float("RADD_PKR_PER_USD", 278.0)
    impressions = float(metrics.get("ad_impression", 0))
    clicks = float(metrics.get("ad_click", 0))
    rewarded = float(metrics.get("rewarded_completion", 0))
    population = max(float(users), 1.0)
    window_days = max(float(days), 1.0)
    ad_usd = impressions / 1000.0 * ecpm
    per_1000 = ad_usd / population * 1000.0
    return {
        "assumptions": {"ecpm_usd": ecpm, "pkr_per_usd": fx,
                        "window_days": window_days,
                        "basis": "impressions x eCPM — not a network report"},
        "ad_usd_in_window": round(ad_usd, 4),
        "ad_usd_per_1000_users": round(per_1000, 4),
        "ad_usd_per_1000_users_per_day": round(per_1000 / window_days, 6),
        "ctr": round(clicks / impressions, 4) if impressions else None,
        "rewarded_per_1000_users": round(rewarded / population * 1000.0, 2),
        "estimate": True,
    }


STREAM_CHECK_TIMEOUT = 10


def check_host_move(old_host, new_host, fleet_base_dir=None):
    """Validate a download-host move the audit way, by reusing
    Fast_Fetch/host_registry.py (old-root redirect, then a rewritten
    file URL 200, then the new root 200; a WAF refusal is blocked,
    never dead). Returns its {"ok", "method", "detail"} dict.
    Raises LiveUnavailable when the fleet module cannot be imported
    (a workspace build with no fleet dir)."""
    base = fleet_base_dir or "/opt/radd-stream/Fast_Fetch"
    if base not in sys.path:
        sys.path.insert(0, base)
    try:
        import host_registry
    except ImportError:
        raise LiveUnavailable(
            "host_registry.py is not importable from %s" % base)
    return host_registry.validate_host_move(old_host, new_host)


def _is_public_url(url):
    """True only when every address `url`'s host resolves to is a public
    (global) IP. A hostname that resolves to a private, loopback, link-local,
    reserved, multicast or unspecified address — or that does not resolve at
    all — is not public. Pure except for the DNS lookup; an IP literal never
    touches DNS."""
    try:
        host = (urllib.parse.urlparse(url).hostname or "").strip()
    except (ValueError, AttributeError):
        return False
    if not host:
        return False
    try:
        return ipaddress.ip_address(host).is_global
    except ValueError:
        pass
    try:
        infos = socket.getaddrinfo(host, None)
    except (socket.gaierror, OSError, ValueError):
        return False
    if not infos:
        return False
    for info in infos:
        try:
            address = ipaddress.ip_address(info[4][0])
        except (ValueError, IndexError):
            return False
        if not address.is_global:
            return False
    return True


class _PublicOnlyRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Follows a redirect only to a public target. A redirect to a
    private/reserved address is not followed: returning None hands the
    redirect response back to the caller, so the probe sees the 3xx and
    reports the stream as not alive — the probe never fetches an internal
    target (the panel itself, the VM's metadata address, a loopback
    service)."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not _is_public_url(newurl):
            return None
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _refused_redirect_error(resp):
    """The error for a 3xx the probe did not follow: a redirect is only left
    unfollowed when its target is not a public address, so name that. A 3xx
    with no Location, or a public Location the probe stopped at for another
    reason, keeps a null error."""
    location = ""
    try:
        location = (resp.headers.get("Location") or "").strip()
    except (AttributeError, KeyError):
        location = ""
    if location and not _is_public_url(location):
        return "redirect target is not a public address"
    return None


def check_stream_url(url, timeout=STREAM_CHECK_TIMEOUT):
    """Probe one stream URL without downloading it.

    HEAD first; if the host rejects HEAD (405/501), retry with a
    1-byte Range GET. Alive = HTTP 200/206. Never reads a body larger
    than 1 byte. Returns {"alive": bool, "status": int|None,
    "error": str|None}.

    SSRF guard (2026-11-24): the URL comes from an admin-typed field, so the
    probe refuses a target that is not a public address — private, loopback,
    link-local, reserved or unresolvable — before fetching, and follows a
    redirect only to another public address. A refused redirect answers as a
    3xx with a "redirect target is not a public address" error, never as a
    fetch of the internal target. The 1-byte cap and the admin-only route
    around this probe are unchanged."""
    if not isinstance(url, str) or not url.strip():
        return {"alive": False, "status": None, "error": "empty URL"}
    url = url.strip()
    if not re.match(r"^https?://", url, re.I):
        return {"alive": False, "status": None, "error": "URL must be http(s)"}
    if not _is_public_url(url):
        return {"alive": False, "status": None,
                "error": "URL does not resolve to a public address"}
    opener = urllib.request.build_opener(_PublicOnlyRedirectHandler())
    for method, headers in (("HEAD", {}), ("GET", {"Range": "bytes=0-0"})):
        req = urllib.request.Request(url, headers=headers, method=method)
        try:
            with opener.open(req, timeout=timeout) as resp:
                status = getattr(resp, "status", 200)
                if status in (200, 206):
                    return {"alive": True, "status": status, "error": None}
                if status in (301, 302, 303, 307, 308):
                    return {"alive": False, "status": status,
                            "error": _refused_redirect_error(resp)}
                return {"alive": False, "status": status, "error": None}
        except urllib.error.HTTPError as exc:
            # 405/501 = HEAD not allowed; fall through to the Range GET.
            if method == "HEAD" and exc.code in (405, 501):
                continue
            if exc.code in (301, 302, 303, 307, 308):
                return {"alive": False, "status": exc.code,
                        "error": _refused_redirect_error(exc)}
            return {"alive": exc.code in (200, 206), "status": exc.code, "error": None}
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as exc:
            return {"alive": False, "status": None, "error": str(exc)[:200]}
    return {"alive": False, "status": None, "error": "stream did not answer"}


def _live_tv_bool(value, default=True):
    """Coerce a form/JSON flag to bool. "0"/"false"/"off"/"" -> False.

    (A bare bool("0") is True in Python, so the HTML toggle form's
    hidden enabled="0" used to never disable a channel.)"""
    if isinstance(value, bool):
        return value
    if value is None:
        return default
    if isinstance(value, (int, float)):
        return bool(value)
    return str(value).strip().lower() in ("1", "true", "on", "yes")


class LiveStores:
    """Injectable handle on the real stores. All paths optional: a
    missing path means that store is unavailable (fixture fallback in
    the Service), never a guess."""

    def __init__(self, catalog_path=None, ledger_path=None,
                 registry_path=None, status_dir=None, cert_path=None,
                 fleet_base_dir="/opt/radd-stream/Fast_Fetch",
                 fleet_log_dir=None, fleet_runner=None,
                 host_registry_path=None, backup_guard=None):
        self.catalog_path = catalog_path
        self.ledger_path = ledger_path
        self.registry_path = registry_path
        self.status_dir = status_dir
        self.cert_path = cert_path or CERT_PATH_DEFAULT
        self.fleet_base_dir = fleet_base_dir
        self.fleet_log_dir = fleet_log_dir or fleet_base_dir
        # Explicit host-registry path (RADD_HOST_REGISTRY); None means
        # the property below derives it from the domain registry's
        # directory, else the fleet dir.
        self._host_registry_path = host_registry_path
        # Backup precondition (PNL-08): a BackupGuard, or None for
        # fixture/test instances (never live). The guard is consulted
        # by _rw before ANY live SQLite write opens.
        self.backup_guard = backup_guard
        # fleet_runner(cmd, cwd, log_path) -> pid-like int. Injected in
        # tests so no real crawler is ever spawned there.
        self.fleet_runner = fleet_runner or self._default_runner

    # -- connections ----------------------------------------------------
    def _ro(self, path, what):
        if not path or not os.path.exists(path):
            raise LiveUnavailable("No %s store is configured here." % what)
        conn = sqlite3.connect("file:%s?mode=ro" % path, uri=True,
                               timeout=10.0, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        return conn

    def _rw(self, path, what):
        if not path or not os.path.exists(path):
            raise LiveUnavailable("No %s store is configured here." % what)
        # PNL-08 backup precondition: the single funnel for every
        # live SQLite write. A guard-less instance is a fixture/test
        # instance (never live); a guarded one refuses the write
        # unless a fresh verified backup marker covers this store.
        # Reads (_ro) are never gated — refusing a read would hide
        # the very state an operator needs to see.
        if self.backup_guard is not None:
            self.backup_guard.require(what, path)
        conn = sqlite3.connect(path, timeout=10.0, check_same_thread=False,
                               isolation_level=None)
        conn.row_factory = sqlite3.Row
        return conn

    def backup_status(self):
        """Read-only freshness report for each configured live store:
        {"catalog": {...}, "ledger": {...}} from BackupGuard.status
        (ok / reason / marker / age_s). Stores with no path configured
        are omitted. Never raises for a missing dir or bad marker —
        a missing backup is a report, not a crash."""
        out = {}
        for store, path in (("catalog", self.catalog_path),
                            ("ledger", self.ledger_path)):
            if not path or not os.path.exists(path):
                continue
            if self.backup_guard is None:
                out[store] = {
                    "ok": False,
                    "reason": ("no backup guard is configured on this "
                               "LiveStores instance (fixture/test instance, "
                               "never live)"),
                    "marker": None, "age_s": None}
            else:
                out[store] = self.backup_guard.status(store, path)
        return out

    @property
    def has_catalog(self):
        return bool(self.catalog_path and os.path.exists(self.catalog_path))

    @property
    def has_ledger(self):
        return bool(self.ledger_path and os.path.exists(self.ledger_path))

    # -- catalog reads ----------------------------------------------------
    def catalog_counts(self):
        """Real counts over posts/releases/download_sources + the real
        projection age from cat_projection_meta (never hardcoded 0).

        posts = rows in posts (150,799 live); works = rows in
        cat_works (116,922 live) — the two are NOT the same, and the
        old Analytics code reported posts as "catalog works" (audit
        §Catalog). Adult counts and the projection extras ride along
        so the dashboard never has to guess them.
        """
        conn = self._ro(self.catalog_path, "catalog")
        try:
            out = {"posts": 0, "releases": 0, "sources": 0, "works": 0,
                   "adult_posts": 0, "adult_works": 0,
                   "active_sources": 0, "resumable_sources": 0,
                   "projection_age_h": None, "projection_rev": None,
                   "projection_posts": None,
                   "link_coverage": None, "identity_suspect": None,
                   "release_dups": None, "id_map_rows": None,
                   "links_by_state": {}}
            if _has_table(conn, "cat_works"):
                out["works"] = conn.execute(
                    "SELECT COUNT(*) AS n FROM cat_works").fetchone()["n"]
                out["adult_works"] = conn.execute(
                    "SELECT COUNT(*) AS n FROM cat_works WHERE is_18_plus=1"
                ).fetchone()["n"]
            if _has_table(conn, "posts"):
                out["posts"] = conn.execute(
                    "SELECT COUNT(*) AS n FROM posts").fetchone()["n"]
                out["adult_posts"] = conn.execute(
                    "SELECT COUNT(*) AS n FROM posts WHERE is_18_plus=1"
                ).fetchone()["n"]
            if _has_table(conn, "releases"):
                out["releases"] = conn.execute(
                    "SELECT COUNT(*) AS n FROM releases").fetchone()["n"]
            if _has_table(conn, "download_sources"):
                out["sources"] = conn.execute(
                    "SELECT COUNT(*) AS n FROM download_sources").fetchone()["n"]
                out["active_sources"] = conn.execute(
                    "SELECT COUNT(*) AS n FROM download_sources WHERE status='active'"
                ).fetchone()["n"]
                out["resumable_sources"] = conn.execute(
                    "SELECT COUNT(*) AS n FROM download_sources WHERE is_resumable=1"
                ).fetchone()["n"]
            if _has_table(conn, "cat_links"):
                for r in conn.execute(
                        "SELECT state, COUNT(*) AS n FROM cat_links GROUP BY state"):
                    out["links_by_state"][r["state"]] = r["n"]
            if _has_table(conn, "cat_projection_meta"):
                meta = {r["key"]: r["value"] for r in conn.execute(
                    "SELECT key, value FROM cat_projection_meta")}
                out["projection_rev"] = meta.get("catalog_rev")
                out["projection_posts"] = _to_int(meta.get("posts"))
                out["link_coverage"] = _to_float(meta.get("link_coverage"))
                out["identity_suspect"] = _to_int(meta.get("identity_suspect"))
                out["release_dups"] = _to_int(meta.get("release_dups"))
                out["id_map_rows"] = _to_int(meta.get("id_map_rows"))
                projected = meta.get("projected_at")
                if projected:
                    try:
                        age = (time.time() - float(projected)) / 3600.0
                        out["projection_age_h"] = round(age, 1)
                    except (TypeError, ValueError):
                        pass
                if meta.get("links_by_state"):
                    try:
                        out["links_by_state"] = json.loads(meta["links_by_state"])
                    except ValueError:
                        pass
            return out
        finally:
            conn.close()

    # Session 107: how far the v2 projection has drifted from the ledger,
    # so the Data-health page can flag staleness instead of showing the two
    # numbers side by side with nothing reconciling them (admin-panel audit
    # §2.4 blind spot). A projection is stale when it is older than
    # `_DRIFT_MAX_AGE_H`, or when more than `_DRIFT_MAX_POSTS` posts have
    # arrived — or left — since it was built, or when no projection exists
    # at all. Link coverage is reported for context but never sets stale:
    # 0.0 is the steady state (no probe has ever run), and flagging it
    # would leave the alert permanently on. Read-only; a missing meta
    # table yields "no projection", never an error.
    _DRIFT_MAX_AGE_H = 48
    _DRIFT_MAX_POSTS = 1000

    def projection_drift(self):
        counts = self.catalog_counts()
        age = counts.get("projection_age_h")
        rev = counts.get("projection_rev")
        ledger_posts = counts.get("posts") or 0
        projected_posts = counts.get("projection_posts")
        drift_posts = (None if projected_posts is None
                       else ledger_posts - projected_posts)
        drift_pct = (None if not projected_posts else
                     round(100.0 * drift_posts / projected_posts, 1))
        reasons = []
        if rev is None:
            reasons.append("no projection has ever been built")
        if age is not None and age > self._DRIFT_MAX_AGE_H:
            reasons.append("projection is %.1f h old (over %d h)"
                           % (age, self._DRIFT_MAX_AGE_H))
        if drift_posts is not None and drift_posts > self._DRIFT_MAX_POSTS:
            reasons.append("%s posts arrived since the projection was built"
                           % f"{drift_posts:,}")
        if drift_posts is not None and drift_posts < -self._DRIFT_MAX_POSTS:
            reasons.append("%s posts left the ledger since the projection"
                           " was built" % f"{-drift_posts:,}")
        return {"rev": rev, "age_h": age,
                "ledger_posts": ledger_posts,
                "projected_posts": projected_posts,
                "drift_posts": drift_posts, "drift_pct": drift_pct,
                "link_coverage": counts.get("link_coverage"),
                "unchecked_links": (counts.get("links_by_state") or {}).get("unchecked"),
                "stale": bool(reasons), "reasons": reasons}

    def link_health(self, limit=10):
        """Negative cache: failed download_sources rows by failure
        class, plus the worst services. Read-only; the resolver's
        health signal, so the panel shows what the app already knows.
        Returns all zeros when the table or its failure columns are
        missing (older catalogs), never an error."""
        conn = self._ro(self.catalog_path, "catalog")
        try:
            empty = {"failed_total": 0, "by_failure_class": {},
                     "by_service": []}
            if not _has_table(conn, "download_sources"):
                return empty
            cols = {r[1] for r in conn.execute(
                "PRAGMA table_info(download_sources)")}
            if "last_failed_at" not in cols or "failure_class" not in cols:
                return empty
            total = conn.execute(
                "SELECT COUNT(*) AS n FROM download_sources "
                "WHERE last_failed_at IS NOT NULL").fetchone()["n"]
            by_class = {r["failure_class"]: r["n"] for r in conn.execute(
                "SELECT failure_class, COUNT(*) AS n FROM download_sources "
                "WHERE last_failed_at IS NOT NULL GROUP BY failure_class"
            ).fetchall()}
            by_service = [{"service": r["service"], "failed": r["n"]}
                          for r in conn.execute(
                "SELECT service, COUNT(*) AS n FROM download_sources "
                "WHERE last_failed_at IS NOT NULL GROUP BY service "
                "ORDER BY n DESC LIMIT ?", (limit,)).fetchall()]
            return {"failed_total": total, "by_failure_class": by_class,
                    "by_service": by_service}
        finally:
            conn.close()

    def review_queue_count(self):
        """Total rows waiting in cat_review_queue (the page shows 50)."""
        conn = self._ro(self.catalog_path, "catalog")
        try:
            if not _has_table(conn, "cat_review_queue"):
                return 0
            return conn.execute(
                "SELECT COUNT(*) AS n FROM cat_review_queue"
            ).fetchone()["n"]
        finally:
            conn.close()

    def _work_from_post(self, r):
        d = _row(r)
        return {
            "id": d["id"], "title": d.get("title") or d.get("clean_title") or "",
            "type": (d.get("content_type") or "").lower() or "movie",
            "year": d.get("year"),
            "adult_flag": (1 if d.get("is_18_plus") else 0),
            "language": d.get("language") or "",
            "dub_label": "",
            "poster_state": "real" if d.get("poster_url") else "missing",
            "poster_url": d.get("poster_url"),
            "genres": d.get("genres") or "",
            "imdb_rating": d.get("imdb_rating"),
            "work_key": d.get("canonical_key") or d.get("slug") or str(d["id"]),
            "source_site": d.get("source_site") or "",
            "synopsis": d.get("synopsis") or "",
            "is_animated": bool(d.get("is_animated")),
        }

    def search_works(self, q="", wtype=None, after_id=None, limit=20):
        conn = self._ro(self.catalog_path, "catalog")
        try:
            if not _has_table(conn, "posts"):
                return {"items": [], "count": 0}
            limit = max(1, min(int(limit or 20), 100))
            sql = ("SELECT id, title, clean_title, year, content_type, "
                   "poster_url, genres, imdb_rating, is_18_plus, is_animated, "
                   "language, synopsis, source_site, canonical_key, slug "
                   "FROM posts WHERE 1=1")
            params = []
            if q:
                sql += " AND (title LIKE ? OR clean_title LIKE ?)"
                params += ["%" + q + "%", "%" + q + "%"]
            if wtype:
                sql += " AND content_type=?"; params.append(wtype)
            if after_id:
                sql += " AND id < ?"; params.append(int(after_id))
            sql += " ORDER BY id DESC LIMIT ?"; params.append(limit)
            rows = conn.execute(sql, params).fetchall()
            items = [self._work_from_post(r) for r in rows]
            nxt = items[-1]["id"] if items and len(items) >= limit else None
            return {"items": items, "next_after_id": nxt, "count": len(items)}
        finally:
            conn.close()

    def get_work(self, work_id):
        conn = self._ro(self.catalog_path, "catalog")
        try:
            if not _has_table(conn, "posts"):
                raise not_found()
            r = conn.execute(
                "SELECT id, title, clean_title, year, content_type, poster_url, "
                "genres, imdb_rating, is_18_plus, is_animated, language, "
                "synopsis, source_site, canonical_key, slug FROM posts WHERE id=?",
                (work_id,)).fetchone()
            if not r:
                raise not_found()
            return self._work_from_post(r)
        finally:
            conn.close()

    def resolve_section(self, rule, category_key=None, limit=20):
        """Execute a section source_rule against the real catalog.

        latest: newest posts first. trending: highest
        best_quality_score in cat_works (falls back to newest posts).
        category: genre/content-type match on the category key.
        curated: nothing is resolved (explicit cards only).
        Returns unified work dicts, adult/unknown-flag works included
        here — the caller still applies the adult fail-closed rule.
        """
        if rule == "curated":
            return []
        conn = self._ro(self.catalog_path, "catalog")
        try:
            limit = max(1, min(int(limit or 20), 50))
            if rule == "trending" and _has_table(conn, "cat_works"):
                cols = _columns(conn, "cat_works")
                order = "best_quality_score DESC, release_count DESC" \
                    if "best_quality_score" in cols else "id DESC"
                rows = conn.execute(
                    "SELECT id, title, year, work_type, poster_url, genres, "
                    "imdb_rating, is_18_plus, is_animated, languages, synopsis, "
                    "work_key FROM cat_works ORDER BY %s LIMIT ?" % order,
                    (limit,)).fetchall()
                out = []
                for r in rows:
                    d = _row(r)
                    out.append({
                        "id": d["id"], "title": d.get("title") or "",
                        "type": (d.get("work_type") or "").lower() or "movie",
                        "year": d.get("year"),
                        "adult_flag": 1 if d.get("is_18_plus") else 0,
                        "language": d.get("languages") or "",
                        "dub_label": "", "poster_state": "real" if d.get("poster_url") else "missing",
                        "poster_url": d.get("poster_url"), "genres": d.get("genres") or "",
                        "imdb_rating": d.get("imdb_rating"),
                        "work_key": d.get("work_key") or str(d["id"]),
                        "source_site": "", "synopsis": d.get("synopsis") or "",
                        "is_animated": bool(d.get("is_animated")),
                    })
                return out
            if not _has_table(conn, "posts"):
                return []
            sql = ("SELECT id, title, clean_title, year, content_type, poster_url, "
                   "genres, imdb_rating, is_18_plus, is_animated, language, "
                   "synopsis, source_site, canonical_key, slug FROM posts WHERE 1=1")
            params = []
            if rule == "category" and category_key:
                sql += " AND (genres LIKE ? OR content_type=?)"
                params += ["%" + category_key + "%", category_key]
            sql += " ORDER BY id DESC LIMIT ?"; params.append(limit)
            return [self._work_from_post(r)
                    for r in conn.execute(sql, params).fetchall()]
        finally:
            conn.close()

    def review_queue(self, limit=50):
        """cat_review_queue, READ-ONLY by design (no resolve setter)."""
        conn = self._ro(self.catalog_path, "catalog")
        try:
            if not _has_table(conn, "cat_review_queue"):
                return {"items": [], "count": 0}
            rows = conn.execute(
                "SELECT * FROM cat_review_queue ORDER BY raised_at DESC LIMIT ?",
                (max(1, min(int(limit or 50), 200)),)).fetchall()
            return {"items": [_row(r) for r in rows], "count": len(rows)}
        finally:
            conn.close()

    # -- evidence re-fold ---------------------------------------------------
    def refold_evidence(self, link_id):
        """Replicate v1 catalog_v2.apply_evidence semantics, simplified
        and honest: re-derive the cat_links row from the WHOLE
        cat_link_evidence journal for that link (idempotent, never
        incremented, rows only UPDATED, never deleted).

        WRITE path: only ever run against a COPY in tests; at the live
        gate _rw enforces the backup precondition (PNL-08) before the
        write opens. Returns counters.
        """
        conn = self._rw(self.catalog_path, "catalog")
        try:
            if not _has_table(conn, "cat_links") or not _has_table(conn, "cat_link_evidence"):
                raise bad("That catalog has no projection to re-fold.")
            conn.execute("BEGIN IMMEDIATE")
            try:
                rows = conn.execute(
                    "SELECT * FROM cat_link_evidence WHERE link_id=? ORDER BY probed_at DESC",
                    (int(link_id),)).fetchall()
                if not rows:
                    conn.execute("ROLLBACK")
                    return {"link_id": int(link_id), "refolded": False,
                            "samples": 0, "note": "no evidence journalled for that link"}
                latest = _row(rows[0])
                ok_count = sum(1 for r in rows if r["ok"])
                state = "active" if latest["ok"] else "dead"
                cols = _columns(conn, "cat_links")
                sets, vals = [], []
                if "state" in cols:
                    sets.append("state=?"); vals.append(state)
                if "resumable" in cols and "accepts_ranges" in cols:
                    sets.append("resumable=?")
                    vals.append(1 if latest.get("accepts_ranges") else 0)
                if "accepts_ranges" in cols:
                    sets.append("accepts_ranges=?")
                    vals.append(1 if latest.get("accepts_ranges") else 0)
                if "protocol" in cols and latest.get("protocol"):
                    sets.append("protocol=?"); vals.append(latest["protocol"])
                if "content_type" in cols and latest.get("content_type"):
                    sets.append("content_type=?"); vals.append(latest["content_type"])
                cur = conn.execute(
                    "UPDATE cat_links SET %s WHERE id=?" % ", ".join(sets),
                    vals + [int(link_id)])
                if cur.rowcount != 1:
                    conn.execute("ROLLBACK")
                    raise not_found("That link was not found in the projection.")
                if _has_table(conn, "cat_projection_meta"):
                    row = conn.execute(
                        "SELECT value FROM cat_projection_meta WHERE key='health_rev'"
                    ).fetchone()
                    try:
                        nxt = int(row["value"]) + 1 if row else 1
                    except (TypeError, ValueError):
                        nxt = 1
                    conn.execute(
                        "INSERT INTO cat_projection_meta (key, value) VALUES ('health_rev', ?) "
                        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                        (str(nxt),))
                conn.execute("COMMIT")
            except Exception:
                try:
                    conn.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                raise
            return {"link_id": int(link_id), "refolded": True, "state": state,
                    "samples": len(rows), "ok_samples": ok_count}
        finally:
            conn.close()

    # -- ledger reads -------------------------------------------------------
    def _ledger_accounts(self, conn):
        return conn.execute(
            "SELECT id, key_hash, created_at, last_seen_at, revoked_at, "
            "vip_until FROM user_accounts ORDER BY id ASC LIMIT 5000").fetchall()

    def search_users(self, q="", tier=None, limit=100):
        """Search by account_hint only. The key hash is never exposed;
        the hint is computed per row, exactly as v1 does.

        `device_count` is None — unknown, not zero. A device bucket is the
        salted digest of the raw sync key (or the install UUID the app
        sends); the panel holds only `key_hash`, a different one-way hash
        of the key, and the raw key is never stored anywhere, so no join
        from an account to its devices exists to compute. The global
        install count lives on the Usage page instead; the Users page
        renders None as "unknown".
        """
        conn = self._ro(self.ledger_path, "ledger")
        try:
            now = time.time()
            rows = list(self._ledger_accounts(conn))
            items = []
            for r in rows:
                hint = account_hint(r["key_hash"])
                if q and q not in hint:
                    continue
                is_vip = bool(r["vip_until"] and float(r["vip_until"]) > now)
                if tier == "vip" and not is_vip:
                    continue
                if tier == "free" and is_vip:
                    continue
                items.append({
                    "id": r["id"], "account_hint": hint,
                    "tier": "vip" if is_vip else "free",
                    "vip_until": int(r["vip_until"]) if r["vip_until"] else 0,
                    "device_count": None, "devices": "unknown",
                    "created_at": r["created_at"], "last_seen_at": r["last_seen_at"],
                    "revoked": bool(r["revoked_at"]),
                })
                if len(items) >= max(1, min(int(limit or 100), 500)):
                    break
            return {"items": items, "count": len(items)}
        finally:
            conn.close()

    def get_account_by_hint(self, hint):
        conn = self._ro(self.ledger_path, "ledger")
        try:
            for r in self._ledger_accounts(conn):
                if account_hint(r["key_hash"]) == hint:
                    return _row(r)
            raise not_found("That account was not found.")
        finally:
            conn.close()

    def usage_summary(self, window_days=30, now=None):
        """The whole operator usage answer, in one bounded read of four small
        tables — a read-only mirror of `usage_stats.summary()` in the live
        tree (Tracking A, Session 96/97).

        `available` is False until any usage row exists, and also when the
        `usage_device` table itself is missing: the panel must never create
        tables — its connection is mode=ro, and the server installs the
        schema on the first account call after deploy. A missing table is
        "nothing counted yet", not an error. `sources` labels every event
        family as observed, reported or derived, because a count the client
        has not been sending is an absence, not a zero.
        """
        conn = self._ro(self.ledger_path, "ledger")
        try:
            window_days = max(1, int(window_days or 30))
            if not _has_table(conn, "usage_device"):
                return {"available": False, "detail": "no_usage_data_yet",
                        "window_days": window_days}
            stamp = float(now if now is not None else time.time())
            today = _usage_day(stamp)
            from_day = today - window_days + 1
            installs = int(_usage_scalar(
                conn, "SELECT COUNT(*) FROM usage_device"))
            live = {}
            for name, seconds in USAGE_LIVE_WINDOWS:
                live[name] = int(_usage_scalar(
                    conn, "SELECT COUNT(*) FROM usage_device"
                    " WHERE last_seen_at >= ?", stamp - seconds))
            active = {}
            for name, seconds in USAGE_ACTIVITY_WINDOWS:
                active[name] = int(_usage_scalar(
                    conn, "SELECT COUNT(*) FROM usage_device"
                    " WHERE last_seen_at >= ?", stamp - seconds))
            payload = {
                "available": installs > 0,
                "generated_at": stamp,
                "window_days": window_days,
                "installs": {
                    "distinct_devices": installs,
                    "new_today": int(_usage_scalar(
                        conn, "SELECT COUNT(*) FROM usage_device"
                        " WHERE install_day = ?", today)),
                    "new_this_window": int(_usage_scalar(
                        conn, "SELECT COUNT(*) FROM usage_device"
                        " WHERE install_day >= ?", from_day)),
                },
                "accounts": {
                    "total": int(_usage_scalar(
                        conn, "SELECT COUNT(*) FROM user_accounts")),
                    "revoked": int(_usage_scalar(
                        conn, "SELECT COUNT(*) FROM user_accounts"
                        " WHERE revoked_at IS NOT NULL")),
                    "vip_active": int(_usage_scalar(
                        conn, "SELECT COUNT(*) FROM user_accounts"
                        " WHERE vip_until >= ? AND revoked_at IS NULL", stamp)),
                },
                "retention": _usage_retention(conn, today=today),
                "per_dimension_installed": _usage_dimension_counts(conn),
                "per_dimension_active": _usage_dimension_counts(
                    conn, since=stamp - 86400),
                "metrics": _usage_metric_totals(
                    conn, from_day=from_day, to_day=today),
                "crash_by_class": _usage_by_class(
                    conn, from_day=from_day, to_day=today, metric="crash"),
                "error_by_class": _usage_by_class(
                    conn, from_day=from_day, to_day=today, metric="error"),
                "buffer_failure_by_class": _usage_by_class(
                    conn, from_day=from_day, to_day=today,
                    metric="playback_buffer_failure"),
                "payments": _usage_payments(conn),
                "live_sessions": live,
                "active_users": active,
            }
            metrics = payload["metrics"]
            server_starts = float(metrics.get("playback_start", 0))
            client_attempts = float(metrics.get("playback_attempt", 0))
            stalls = float(metrics.get("playback_buffer_failure", 0))
            denominator = client_attempts or server_starts
            which = "client_attempts" if client_attempts else "server_starts"
            payload["playback"] = {
                "server_stream_starts": server_starts,
                "client_playback_attempts": client_attempts,
                "buffer_failures": stalls,
                "buffer_failure_rate": (
                    round(stalls / denominator, 4) if denominator else None),
                "rate_denominator": which,
                "starts_per_device": (
                    round(denominator / installs, 2) if installs else None),
            }
            payload["revenue_estimate"] = _usage_revenue_estimate(
                metrics=metrics,
                users=payload["active_users"]["mau"] or installs,
                days=window_days)
            payload["sources"] = {
                "observed_by_server": sorted(USAGE_METRICS - USAGE_REPORTED_ONLY),
                "reported_by_client": sorted(USAGE_REPORTED_ONLY),
                "reported_by_client_pending": sorted(
                    name for name in USAGE_REPORTED_ONLY if not metrics.get(name)),
            }
            payload["identity"] = {
                "device_key": "salted sha256, truncated to 16 chars",
                "ip_stored": False,
                "per_person_timeline": False,
                "note": ("a device bucket is X-Install-Id when the app sends it and the"
                         " salted credential otherwise; until the app lane sends the"
                         " header, distinct_devices and accounts can legitimately read"
                         " equal, and that is the fallback rather than a defect"),
            }
            if not payload["available"]:
                payload["no_usage_data_yet"] = True
            return payload
        finally:
            conn.close()

    def list_payments(self, status=None, method=None, after_id=None,
                      limit=20, min_amount=None, max_amount=None,
                      from_ts=None, to_ts=None):
        conn = self._ro(self.ledger_path, "ledger")
        try:
            if not _has_table(conn, "payment_transactions"):
                return {"items": [], "count": 0}
            limit = max(1, min(int(limit or 20), 100))
            sql = ("SELECT p.id, p.account_id, p.plan, p.amount_pkr, p.method, "
                   "p.status, p.submitted_at, p.reviewed_at, p.reviewer_note, "
                   "CASE WHEN p.tid IS NOT NULL AND p.tid <> '' THEN 1 ELSE 0 END AS tid_present, "
                   "a.key_hash FROM payment_transactions p "
                   "LEFT JOIN user_accounts a ON a.id = p.account_id WHERE 1=1")
            params = []
            if status:
                sql += " AND p.status=?"; params.append(status)
            if method:
                sql += " AND p.method=?"; params.append(method)
            if min_amount is not None:
                sql += " AND p.amount_pkr>=?"; params.append(int(min_amount))
            if max_amount is not None:
                sql += " AND p.amount_pkr<=?"; params.append(int(max_amount))
            if from_ts is not None:
                sql += " AND p.submitted_at>=?"; params.append(float(from_ts))
            if to_ts is not None:
                sql += " AND p.submitted_at<=?"; params.append(float(to_ts))
            if after_id:
                sql += " AND p.id < ?"; params.append(int(after_id))
            sql += " ORDER BY p.id DESC LIMIT ?"; params.append(limit)
            items = []
            for r in conn.execute(sql, params).fetchall():
                d = _row(r)
                items.append({
                    "id": d["id"], "account_id": d["account_id"],
                    "account_hint": account_hint(d.get("key_hash")),
                    "plan": d["plan"], "amount_pkr": d["amount_pkr"],
                    "method": d["method"], "status": d["status"],
                    "submitted_at": d["submitted_at"],
                    "created_at": d["submitted_at"],
                    "reviewed_at": d["reviewed_at"],
                    "reviewer_note": d["reviewer_note"],
                    "tid_present": bool(d["tid_present"]),
                })
            nxt = items[-1]["id"] if items and len(items) >= limit else None
            return {"items": items, "next_after_id": nxt, "count": len(items)}
        finally:
            conn.close()

    def get_payment(self, payment_id):
        conn = self._ro(self.ledger_path, "ledger")
        try:
            r = conn.execute(
                "SELECT p.id, p.account_id, p.plan, p.amount_pkr, p.method, "
                "p.status, p.submitted_at, p.reviewed_at, p.reviewer_note, "
                "CASE WHEN p.tid IS NOT NULL AND p.tid <> '' THEN 1 ELSE 0 END AS tid_present, "
                "a.key_hash FROM payment_transactions p "
                "LEFT JOIN user_accounts a ON a.id = p.account_id WHERE p.id=?",
                (int(payment_id),)).fetchone()
            if not r:
                raise not_found()
            d = _row(r)
            return {"id": d["id"], "account_id": d["account_id"],
                    "account_hint": account_hint(d.get("key_hash")),
                    "plan": d["plan"], "amount_pkr": d["amount_pkr"],
                    "method": d["method"], "status": d["status"],
                    "submitted_at": d["submitted_at"],
                    "created_at": d["submitted_at"],
                    "reviewed_at": d["reviewed_at"],
                    "reviewer_note": d["reviewer_note"],
                    "tid_present": bool(d["tid_present"])}
        finally:
            conn.close()

    # -- movie requests (v1 /admin/requests, Session 78) -------------------
    REQUEST_TERMINAL_STATUSES = ("indexed", "declined")
    _REQUEST_STATUSES = ("open",) + REQUEST_TERMINAL_STATUSES

    def list_movie_requests(self, status=None, limit=100, oldest_first=False):
        """The operator's queue, newest first, every row a plain dict.

        Mirrors v1 engagement.list_movie_requests: `status` filters to
        one workflow state (an unknown one is a caller bug and fails
        loudly); `oldest_first` is the queue order — the operator works
        the oldest request first — while the recent list stays newest
        first. A ledger without the table yet returns an honest empty
        list, like list_payments does.
        """
        if status is not None and status not in self._REQUEST_STATUSES:
            raise ValueError("unknown request status: %r" % (status,))
        conn = self._ro(self.ledger_path, "ledger")
        try:
            if not _has_table(conn, "movie_requests"):
                return []
            direction = "ASC" if oldest_first else "DESC"
            if status is None:
                rows = conn.execute(
                    "SELECT id, account_id, title, notes, status, created_at"
                    " FROM movie_requests ORDER BY created_at"
                    " %s, id %s LIMIT ?" % (direction, direction),
                    (int(limit),)).fetchall()
            else:
                rows = conn.execute(
                    "SELECT id, account_id, title, notes, status, created_at"
                    " FROM movie_requests WHERE status = ?"
                    " ORDER BY created_at %s, id %s LIMIT ?" % (direction, direction),
                    (str(status), int(limit))).fetchall()
            return [{
                "id": int(r["id"]), "account_id": int(r["account_id"]),
                "title": str(r["title"]), "notes": str(r["notes"] or ""),
                "status": str(r["status"]), "created_at": float(r["created_at"]),
            } for r in rows]
        finally:
            conn.close()

    def request_account_handles(self, account_ids):
        """{account_id: {hint, profile}} for the queue's accounts.

        One query. profile_name is read only when the column exists —
        the real DDL grew it after the v2 module docstring was written
        and v1 already selects it — so a ledger without it still renders
        hints instead of failing the page.
        """
        ids = sorted({int(a) for a in account_ids})
        if not ids:
            return {}
        conn = self._ro(self.ledger_path, "ledger")
        try:
            cols = {r[1] for r in conn.execute(
                "PRAGMA table_info(user_accounts)").fetchall()}
            sel = "id, key_hash" + (
                ", profile_name" if "profile_name" in cols else "")
            marks = ",".join("?" for _ in ids)
            out = {}
            for r in conn.execute(
                    "SELECT %s FROM user_accounts WHERE id IN (%s)" % (sel, marks),
                    tuple(ids)).fetchall():
                out[int(r["id"])] = {
                    "hint": account_hint(r["key_hash"]),
                    "profile": (str(r["profile_name"] or "")
                                if "profile_name" in cols else ""),
                }
            return out
        finally:
            conn.close()

    def search_payment_by_tid(self, tid_value):
        """Operator-only match confirmation. The TID is never rendered,
        never logged; only the matching claim (without the TID) returns."""
        if not tid_value:
            raise bad("A transaction id is required to search.")
        conn = self._ro(self.ledger_path, "ledger")
        try:
            rows = conn.execute(
                "SELECT p.id, p.account_id, p.plan, p.amount_pkr, p.method, "
                "p.status, p.submitted_at, a.key_hash FROM payment_transactions p "
                "LEFT JOIN user_accounts a ON a.id = p.account_id WHERE p.tid=?",
                (str(tid_value),)).fetchall()
            return {"items": [{
                "id": r["id"], "account_id": r["account_id"],
                "account_hint": account_hint(r["key_hash"]),
                "plan": r["plan"], "amount_pkr": r["amount_pkr"],
                "method": r["method"], "status": r["status"],
                "submitted_at": r["submitted_at"],
                "tid_present": True} for r in rows], "count": len(rows)}
        finally:
            conn.close()

    def user_payment_history(self, account_hint_value):
        acct = self.get_account_by_hint(account_hint_value)
        return self._payments_for_account(acct["id"])

    def _payments_for_account(self, account_id):
        conn = self._ro(self.ledger_path, "ledger")
        try:
            rows = conn.execute(
                "SELECT id, account_id, plan, amount_pkr, method, status, "
                "submitted_at, reviewed_at, reviewer_note, "
                "CASE WHEN tid IS NOT NULL AND tid <> '' THEN 1 ELSE 0 END AS tid_present "
                "FROM payment_transactions WHERE account_id=? ORDER BY id DESC LIMIT 100",
                (int(account_id),)).fetchall()
            return {"items": [{**_row(r), "tid_present": bool(r["tid_present"])}
                              for r in rows], "count": len(rows)}
        finally:
            conn.close()

    # -- ledger writes (v1 semantics, tests run these on a COPY only) ------
    def set_request_status(self, request_id, status):
        """Move one open request to 'indexed' or 'declined' (v1 engagement).

        Returns {"id", "status", "changed"}; None when the row does not
        exist. A re-mark writes nothing and reports the status the row
        already holds — the operator's double-click closes nobody twice.
        Writes run against temp copies in tests; at the live gate _rw
        enforces the backup precondition (PNL-08), like every other
        ledger write here.
        """
        if status not in self.REQUEST_TERMINAL_STATUSES:
            raise ValueError("unknown request status: %r" % (status,))
        conn = self._rw(self.ledger_path, "ledger")
        try:
            if not _has_table(conn, "movie_requests"):
                return None
            conn.execute("BEGIN IMMEDIATE")
            try:
                row = conn.execute(
                    "SELECT status FROM movie_requests WHERE id = ?",
                    (int(request_id),)).fetchone()
                if row is None:
                    conn.execute("ROLLBACK")
                    return None
                current = str(row["status"])
                if current != "open":
                    conn.execute("ROLLBACK")
                    return {"id": int(request_id), "status": current,
                            "changed": False}
                conn.execute(
                    "UPDATE movie_requests SET status = ? WHERE id = ?",
                    (str(status), int(request_id)))
                conn.execute("COMMIT")
                return {"id": int(request_id), "status": str(status),
                        "changed": True}
            except Exception:
                try:
                    conn.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                raise
        finally:
            conn.close()

    def _tid_conflict(self, conn, tid, payment_id):
        """NEW LOGIC (user decision 2026-11-01): the same TID on ANY
        other payment row with status pending or approved refuses the
        approval. Returns the conflicting row or None."""
        if not tid:
            return None
        return conn.execute(
            "SELECT id, account_id, status FROM payment_transactions "
            "WHERE tid=? AND id<>? AND status IN ('pending','approved') "
            "ORDER BY id ASC LIMIT 1", (tid, int(payment_id))).fetchone()

    def approve_payment(self, payment_id, reviewer="operator", note="",
                        now=None):
        """v1 payments.approve_payment, plus the cross-account TID rule.

        Single BEGIN IMMEDIATE txn: rowcount-guarded status update
        (replay returns changed: False, no double credit), vip_until =
        max(now, vip_until) + plan days, payment_review_audit row.
        """
        moment = time.time() if now is None else float(now)
        who, why = _clean_reviewer(reviewer), _clean_note(note)
        conn = self._rw(self.ledger_path, "ledger")
        try:
            conn.execute("BEGIN IMMEDIATE")
            try:
                row = conn.execute(
                    "SELECT * FROM payment_transactions WHERE id=?",
                    (int(payment_id),)).fetchone()
                if not row:
                    conn.execute("ROLLBACK")
                    raise not_found()
                status = str(row["status"] or "")
                acct = conn.execute(
                    "SELECT vip_until, revoked_at FROM user_accounts WHERE id=?",
                    (int(row["account_id"]),)).fetchone()
                stored = float(acct["vip_until"] or 0) if acct else 0.0
                if status != "pending":
                    conn.execute("ROLLBACK")
                    return {"payment_id": int(payment_id), "status": status,
                            "changed": False, "account_id": int(row["account_id"]),
                            "vip_until": stored, "prior_vip_until": stored,
                            "days": 0, "reviewed_at": float(row["reviewed_at"] or 0)}
                plan = str(row["plan"] or "")
                if plan not in PLAN_DAYS:
                    conn.execute("ROLLBACK")
                    raise bad("That plan has no credit length.")
                clash = self._tid_conflict(conn, row["tid"], payment_id)
                if clash is not None:
                    conn.execute("ROLLBACK")
                    raise conflict(
                        "That transaction id is already used by payment %s "
                        "(account %s, status %s). Approval refused."
                        % (clash["id"], clash["account_id"], clash["status"]))
                if acct is None or acct["revoked_at"] is not None:
                    conn.execute("ROLLBACK")
                    raise not_found("That account is gone or revoked.")
                days = PLAN_DAYS[plan]
                new = max(moment, stored) + days * 86400.0
                conn.execute("UPDATE user_accounts SET vip_until=? WHERE id=?",
                             (new, int(row["account_id"])))
                marked = conn.execute(
                    "UPDATE payment_transactions SET status='approved', "
                    "reviewed_at=?, reviewer_note=? WHERE id=? AND status='pending'",
                    (moment, why or None, int(payment_id)))
                if marked.rowcount != 1:
                    conn.execute("ROLLBACK")
                    raise not_found()
                conn.execute(
                    "INSERT INTO payment_review_audit (payment_id, account_id, action, "
                    "prior_vip_until, new_vip_until, reviewer, note, acted_at) "
                    "VALUES (?,?,?,?,?,?,?,?)",
                    (int(payment_id), int(row["account_id"]), "approved",
                     stored, new, who, why or None, moment))
                conn.execute("COMMIT")
            except Exception:
                try:
                    conn.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                raise
            return {"payment_id": int(payment_id), "status": "approved",
                    "changed": True, "account_id": int(row["account_id"]),
                    "vip_until": new, "prior_vip_until": stored,
                    "days": days, "reviewed_at": moment}
        finally:
            conn.close()

    def reject_payment(self, payment_id, reviewer="operator", note="",
                       now=None):
        """v1 reject: same single txn, vip_until never read for writing
        and never written."""
        moment = time.time() if now is None else float(now)
        who, why = _clean_reviewer(reviewer), _clean_note(note)
        conn = self._rw(self.ledger_path, "ledger")
        try:
            conn.execute("BEGIN IMMEDIATE")
            try:
                row = conn.execute(
                    "SELECT * FROM payment_transactions WHERE id=?",
                    (int(payment_id),)).fetchone()
                if not row:
                    conn.execute("ROLLBACK")
                    raise not_found()
                status = str(row["status"] or "")
                acct = conn.execute(
                    "SELECT vip_until FROM user_accounts WHERE id=?",
                    (int(row["account_id"]),)).fetchone()
                stored = float(acct["vip_until"] or 0) if acct else 0.0
                if status != "pending":
                    conn.execute("ROLLBACK")
                    return {"payment_id": int(payment_id), "status": status,
                            "changed": False, "account_id": int(row["account_id"]),
                            "vip_until": stored, "prior_vip_until": stored,
                            "days": 0, "reviewed_at": float(row["reviewed_at"] or 0)}
                marked = conn.execute(
                    "UPDATE payment_transactions SET status='rejected', "
                    "reviewed_at=?, reviewer_note=? WHERE id=? AND status='pending'",
                    (moment, why or None, int(payment_id)))
                if marked.rowcount != 1:
                    conn.execute("ROLLBACK")
                    raise not_found()
                conn.execute(
                    "INSERT INTO payment_review_audit (payment_id, account_id, action, "
                    "prior_vip_until, new_vip_until, reviewer, note, acted_at) "
                    "VALUES (?,?,?,?,?,?,?,?)",
                    (int(payment_id), int(row["account_id"]), "rejected",
                     stored, stored, who, why or None, moment))
                conn.execute("COMMIT")
            except Exception:
                try:
                    conn.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                raise
            return {"payment_id": int(payment_id), "status": "rejected",
                    "changed": True, "account_id": int(row["account_id"]),
                    "vip_until": stored, "prior_vip_until": stored,
                    "days": 0, "reviewed_at": moment}
        finally:
            conn.close()

    def vip_grant_manual(self, account_id, months, reviewer="operator",
                         note="", now=None):
        """Manual VIP grant, mirroring v1 approve semantics: single
        BEGIN IMMEDIATE txn, UPDATE user_accounts SET vip_until +
        INSERT payment_review_audit (action 'manual_grant'). NO fake
        payment row is created. Returns (result, audit_id) so the
        caller can compensate-roll-back if its own record fails."""
        if not (1 <= int(months) <= 24):
            raise bad("VIP grant must be 1 to 24 months.")
        moment = time.time() if now is None else float(now)
        who, why = _clean_reviewer(reviewer), _clean_note(note)
        conn = self._rw(self.ledger_path, "ledger")
        try:
            conn.execute("BEGIN IMMEDIATE")
            try:
                acct = conn.execute(
                    "SELECT vip_until, revoked_at FROM user_accounts WHERE id=?",
                    (int(account_id),)).fetchone()
                if acct is None or acct["revoked_at"] is not None:
                    conn.execute("ROLLBACK")
                    raise not_found("That account is gone or revoked.")
                prior = float(acct["vip_until"] or 0)
                new = max(moment, prior) + int(months) * 30 * 86400.0
                conn.execute("UPDATE user_accounts SET vip_until=? WHERE id=?",
                             (new, int(account_id)))
                cur = conn.execute(
                    "INSERT INTO payment_review_audit (payment_id, account_id, action, "
                    "prior_vip_until, new_vip_until, reviewer, note, acted_at) "
                    "VALUES (NULL,?,?,?,?,?,?,?)",
                    (int(account_id), "manual_grant", prior, new, who,
                     why or None, moment))
                conn.execute("COMMIT")
            except Exception:
                try:
                    conn.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                raise
            return ({"account_id": int(account_id), "vip_until": new,
                     "prior_vip_until": prior, "months": int(months)},
                    cur.lastrowid)
        finally:
            conn.close()

    def rollback_manual_grant(self, account_id, prior_vip_until, audit_id):
        """Compensating rollback for a manual grant whose panel-side
        record failed: restore vip_until and remove the audit row."""
        conn = self._rw(self.ledger_path, "ledger")
        try:
            conn.execute("BEGIN IMMEDIATE")
            try:
                conn.execute("UPDATE user_accounts SET vip_until=? WHERE id=?",
                             (float(prior_vip_until), int(account_id)))
                conn.execute("DELETE FROM payment_review_audit WHERE id=?",
                             (int(audit_id),))
                conn.execute("COMMIT")
            except Exception:
                try:
                    conn.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                raise
        finally:
            conn.close()

    def add_refund_note(self, payment_id, reviewer="operator", note="",
                        now=None):
        """Refund/adjustment NOTE only: an audited note, no status or
        VIP change, no money movement. Action 'adjustment_note'."""
        moment = time.time() if now is None else float(now)
        who, why = _clean_reviewer(reviewer), _clean_note(note)
        if not why:
            raise bad("A refund/adjustment note needs note text.")
        conn = self._rw(self.ledger_path, "ledger")
        try:
            conn.execute("BEGIN IMMEDIATE")
            try:
                row = conn.execute(
                    "SELECT * FROM payment_transactions WHERE id=?",
                    (int(payment_id),)).fetchone()
                if not row:
                    conn.execute("ROLLBACK")
                    raise not_found()
                acct = conn.execute(
                    "SELECT vip_until FROM user_accounts WHERE id=?",
                    (int(row["account_id"]),)).fetchone()
                stored = float(acct["vip_until"] or 0) if acct else 0.0
                cur = conn.execute(
                    "INSERT INTO payment_review_audit (payment_id, account_id, action, "
                    "prior_vip_until, new_vip_until, reviewer, note, acted_at) "
                    "VALUES (?,?,?,?,?,?,?,?)",
                    (int(payment_id), int(row["account_id"]), "adjustment_note",
                     stored, stored, who, why, moment))
                conn.execute("COMMIT")
            except Exception:
                try:
                    conn.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                raise
            return {"payment_id": int(payment_id), "audit_id": cur.lastrowid,
                    "status": str(row["status"]), "vip_until": stored,
                    "note": why}
        finally:
            conn.close()

    def ledger_counts(self):
        conn = self._ro(self.ledger_path, "ledger")
        try:
            out = {"accounts": 0, "vip_accounts": 0, "pending_payments": 0,
                   "approved_payments": 0, "rejected_payments": 0}
            now = time.time()
            if _has_table(conn, "user_accounts"):
                out["accounts"] = conn.execute(
                    "SELECT COUNT(*) AS n FROM user_accounts").fetchone()["n"]
                out["vip_accounts"] = conn.execute(
                    "SELECT COUNT(*) AS n FROM user_accounts WHERE vip_until>?",
                    (now,)).fetchone()["n"]
            if _has_table(conn, "payment_transactions"):
                for r in conn.execute(
                        "SELECT status, COUNT(*) AS n FROM payment_transactions GROUP BY status"):
                    out["%s_payments" % r["status"]] = r["n"]
            return out
        finally:
            conn.close()

    # -- remote config (real app_remote_config, catalog DB) --------------
    def read_remote_config(self):
        """The 17 real fields, id=1. Tokens are never
        returned here; the caller masks them (see read_remote_config_masked)."""
        conn = self._ro(self.catalog_path, "catalog")
        try:
            if not _has_table(conn, "app_remote_config"):
                raise LiveUnavailable("No app_remote_config table here.")
            r = conn.execute(
                "SELECT * FROM app_remote_config WHERE id=1").fetchone()
            if not r:
                raise LiveUnavailable("No app_remote_config row here.")
            out = {k: r[k] for k in REMOTE_CONFIG_FIELDS if k in r.keys()}
            # The published version the app is serving: not a setting the
            # operator edits, so it is never in REMOTE_CONFIG_FIELDS, but
            # every reader of this row wants to know it.
            if "config_version" in r.keys():
                out["config_version"] = r["config_version"]
            return out
        finally:
            conn.close()

    def read_remote_config_masked(self):
        row = self.read_remote_config()
        out = {}
        for k, v in row.items():
            if k in REMOTE_CONFIG_SECRETS:
                continue
            out[k] = v
        out["shrinkme_configured"] = bool(row.get("shrinkme_api_token"))
        out["droplink_configured"] = bool(row.get("droplink_api_token"))
        return out

    def save_remote_config(self, fields, config_version=None):
        """v1 save-monetization pattern: partial save, validated,
        all-or-nothing, updated_at=time.time(). WRITE path: tests run
        this on a COPY only; live use goes through _rw, which enforces
        the backup precondition (PNL-08) before the write opens.

        config_version stamps the published version onto the served row
        in the same UPDATE, so a flag change is never live without a
        version the panel (and the app) can see. It is a separate
        argument, never a member of `fields`: callers must not be able
        to stamp an arbitrary version by posting it.

        PNL-01 redesign (D7): schema changes happen at startup, never
        in the request path. This method no longer ALTERs the live
        schema: a known field whose column is missing raises
        `schema_drift` ("restart the panel to migrate") instead of
        taking an exclusive schema lock mid-publish. Run
        `ensure_live_remote_config_columns` at panel startup (Service
        construction does it when a live catalog is wired)."""
        unknown = sorted(set(fields) - set(REMOTE_CONFIG_FIELDS))
        if unknown:
            raise bad("Unknown field: %s" % ",".join(unknown))
        if not fields:
            raise bad("Empty body clears nothing. Send at least one field.")
        if config_version is not None:
            try:
                config_version = int(config_version)
            except (TypeError, ValueError):
                raise bad("config_version must be a whole number.")
        for k in ("vip_monthly_pkr", "vip_yearly_pkr"):
            if k in fields and not (0 <= int(fields[k]) <= 10_000_000):
                raise bad("VIP price must be between 0 and 10,000,000.")
        for k in ("maintenance_mode", "feature_downloads", "feature_pure_cinema",
                  "feature_chromecast", "feature_quality_gate",
                  "ads_enabled", "clean_ads_only"):
            if k in fields and int(fields[k]) not in (0, 1):
                raise bad("That flag must be 0 or 1.")
        if "free_quality_cap" in fields and fields["free_quality_cap"] not in ("480p", "720p"):
            raise bad("Free quality cap is 480p or 720p only in this version.")
        conn = self._rw(self.catalog_path, "catalog")
        try:
            # D7: no ALTER here. A missing column for a known field is
            # schema drift, not something to fix mid-request.
            cols = {r[1] for r in
                    conn.execute("PRAGMA table_info(app_remote_config)")}
            ensure = set(fields)
            if config_version is not None:
                ensure.add("config_version")
            missing = sorted(ensure - cols)
            if missing:
                raise ApiError(
                    "schema_drift",
                    "The live catalog is missing column(s) %s — restart "
                    "the panel to migrate, then retry." % ", ".join(missing),
                    500)
            write = dict(fields)
            if config_version is not None:
                write["config_version"] = config_version
            conn.execute("BEGIN IMMEDIATE")
            try:
                sets = ", ".join("%s=?" % k for k in write)
                conn.execute(
                    "UPDATE app_remote_config SET %s, updated_at=? WHERE id=1"
                    % sets, list(write.values()) + [time.time()])
                conn.execute("COMMIT")
            except Exception:
                try:
                    conn.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                raise
            return self.read_remote_config_masked()
        finally:
            conn.close()

    def save_pause_flag(self, column, value, config_version):
        """Internal pause-path write (PNL-11): set one live flag
        column + stamp the version in the same UPDATE, exactly
        like save_remote_config. The pause machinery writes
        columns the v2 save path does NOT serve (coins_enabled
        is RELEASE_OWNED — the app reads it from the release,
        the panel only ever holds it at 0 while paused) — so
        this validates against ALL_LIVE_REMOTE_CONFIG_FIELDS,
        not REMOTE_CONFIG_FIELDS. Only _write_feature_pause_live
        calls it, with a column from FEATURE_PAUSE_COLUMNS.
        """
        if column not in ALL_LIVE_REMOTE_CONFIG_FIELDS:
            raise bad("Unknown live column: %s" % column)
        if int(value) not in (0, 1):
            raise bad("That flag must be 0 or 1.")
        try:
            config_version = int(config_version)
        except (TypeError, ValueError):
            raise bad("config_version must be a whole number.")
        conn = self._rw(self.catalog_path, "catalog")
        try:
            cols = {r[1] for r in
                    conn.execute("PRAGMA table_info(app_remote_config)")}
            if column not in cols or "config_version" not in cols:
                raise ApiError(
                    "schema_drift",
                    "The live catalog is missing column(s) %s — restart "
                    "the panel to migrate, then retry." % column, 500)
            conn.execute("BEGIN IMMEDIATE")
            try:
                conn.execute(
                    "UPDATE app_remote_config SET %s=?, config_version=?, "
                    "updated_at=? WHERE id=1" % column,
                    [int(value), config_version, time.time()])
                conn.execute("COMMIT")
            except Exception:
                try:
                    conn.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                raise
            return self.read_remote_config_masked()
        finally:
            conn.close()

    # -- domains file -------------------------------------------------------
    def load_registry(self):
        """Parse domain_registry.json. Returns {site: {...}} or {}."""
        if not self.registry_path or not os.path.exists(self.registry_path):
            return {}
        with open(self.registry_path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        sites = data.get("sites") if isinstance(data, dict) else None
        return sites if isinstance(sites, dict) else {}

    def registry_backup_path(self):
        """Backup of the registry file before the first live write.

        Creates <path>.bak-<timestamp> only if no backup exists yet;
        otherwise reuses the existing backup path. Returns the path."""
        if not self.registry_path or not os.path.exists(self.registry_path):
            return None
        existing = sorted(glob.glob(self.registry_path + ".bak-*"))
        if existing:
            return existing[0]
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        backup = "%s.bak-%s" % (self.registry_path, stamp)
        shutil.copy2(self.registry_path, backup)
        return backup

    def write_registry_domain(self, site_key, domain):
        """Apply one domain to domain_registry.json, atomically, after
        taking the backup (see registry_backup_path). WRITE path: live
        use only after that backup exists; tests use a temp copy."""
        if site_key not in FLEET_SITES:
            raise bad("Unknown site.")
        if not self.registry_path or not os.path.exists(self.registry_path):
            raise LiveUnavailable("No domain registry file is configured here.")
        backup = self.registry_backup_path()
        with open(self.registry_path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        sites = data.setdefault("sites", {})
        entry = sites.setdefault(site_key, {})
        entry["active_base_url"] = "https://%s" % domain
        mirrors = entry.setdefault("known_mirrors", [])
        if "https://%s" % domain not in mirrors:
            mirrors.append("https://%s" % domain)
        entry["status"] = entry.get("status") or "active"
        data["updated_at"] = datetime.now(timezone.utc).isoformat() + "Z"
        tmp = self.registry_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(data, fh, indent=2)
            fh.write("\n")
        os.replace(tmp, self.registry_path)
        return {"site_key": site_key, "domain": domain, "backup": backup}

    # -- host registry (download hosts) -------------------------------------
    @property
    def host_registry_path(self):
        """host_registry.json: the explicit path (RADD_HOST_REGISTRY),
        else next to domain_registry.json, else next to the fleet
        scripts. The panel and the resolver read the same file."""
        if self._host_registry_path:
            return self._host_registry_path
        if self.registry_path:
            return os.path.join(os.path.dirname(self.registry_path),
                                "host_registry.json")
        return os.path.join(self.fleet_base_dir, "host_registry.json")

    def _host_registry_backup_path(self):
        """Backup of the host registry before the first live write.

        Creates <path>.bak-<timestamp> only if no backup exists yet;
        otherwise reuses the existing backup path. Returns the path
        (None when the file does not exist yet — a first write seeds
        it)."""
        path = self.host_registry_path
        if not os.path.exists(path):
            return None
        existing = sorted(glob.glob(path + ".bak-*"))
        if existing:
            return existing[0]
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        backup = "%s.bak-%s" % (path, stamp)
        shutil.copy2(path, backup)
        return backup

    def load_host_registry(self):
        """Parse host_registry.json. Returns the full data dict; a
        missing file reads as an empty registry (the panel can seed
        it), while a malformed one raises LiveUnavailable — a
        half-loaded mapping list must never be served."""
        path = self.host_registry_path
        if not os.path.exists(path):
            return {"version": "2.0", "updated_at": None, "hosts": {}}
        try:
            with open(path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
        except (ValueError, OSError) as exc:
            raise LiveUnavailable("Host registry is not readable: %s" % exc)
        if not isinstance(data, dict) or not isinstance(data.get("hosts"), dict):
            raise LiveUnavailable("Host registry has no host map.")
        return data

    def list_host_mappings(self):
        """Download-host mappings, sorted by old host (read-only)."""
        data = self.load_host_registry()
        out = []
        for old_host in sorted(data["hosts"]):
            entry = data["hosts"][old_host] or {}
            out.append({
                "old_host": old_host,
                "current_domain": entry.get("current_domain") or "",
                "status": entry.get("status") or "active",
                "evidence": entry.get("evidence") or "",
                "last_verified": entry.get("last_verified") or "",
                "verified_by": entry.get("verified_by") or "",
                "rows_affected": entry.get("rows_affected") or 0,
                "dead_rows": entry.get("dead_rows") or 0,
            })
        return {"hosts": out, "count": len(out),
                "version": data.get("version")}

    def apply_host_mapping(self, old_host, new_host, *, checker=None,
                           force=False):
        """Write one host mapping to host_registry.json, atomically,
        after taking the backup (see _host_registry_backup_path).
        When a checker is given it must return {"ok": ...} the audit
        way; a failed check refuses unless force is set, in which
        case the entry is recorded as unverified — and an unverified
        entry is NEVER applied at serve time (the resolver only
        rewrites status == active). Without a checker and without
        force the write refuses: an unvalidated move must be an
        explicit operator choice, not a silent default. WRITE path:
        live use only after that backup exists; tests use a temp
        copy."""
        old_host = (old_host or "").strip().lower().rstrip(".")
        new_host = (new_host or "").strip().lower().rstrip(".")
        if not valid_domain(old_host) or not valid_domain(new_host):
            raise bad("Host only: no scheme, path, query, or token.")
        if old_host == new_host:
            raise bad("The new host is the same as the old host.")
        validation = None
        if checker is not None:
            validation = checker(old_host, new_host)
            if not validation.get("ok") and not force:
                raise bad("Host move did not validate: %s"
                          % validation.get("detail"))
        elif not force:
            raise bad("No host checker is wired here, so the move "
                      "cannot be validated; tick force to record it "
                      "unverified.")
        now = datetime.now(timezone.utc).isoformat() + "Z"
        if validation is not None and not validation.get("ok"):
            status, evidence = "unverified", "manual-unverified"
        else:
            status, evidence = "active", "manual"
        backup = self._host_registry_backup_path()
        data = self.load_host_registry()
        hosts = data.setdefault("hosts", {})
        entry = hosts.setdefault(old_host, {})
        previous = entry.get("current_domain") or ""
        entry["current_domain"] = new_host
        entry["evidence"] = evidence
        entry["status"] = status
        entry["last_verified"] = now
        entry["verified_by"] = "panel-force" if status == "unverified" else "panel"
        mirrors = entry.setdefault("known_mirrors", [])
        if "https://%s" % new_host not in mirrors:
            mirrors.append("https://%s" % new_host)
        history = entry.setdefault("rotation_history", [])
        history.append({"domain": new_host, "first_seen": now,
                        "last_seen": now, "status": status,
                        "evidence": evidence})
        data["updated_at"] = now
        path = self.host_registry_path
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(data, fh, indent=2)
            fh.write("\n")
        os.replace(tmp, path)
        return {"old_host": old_host, "current_domain": new_host,
                "previous_domain": previous, "status": status,
                "evidence": evidence, "validation": validation,
                "backup": backup}

    # -- live tv ------------------------------------------------------------
    @property
    def live_tv_registry_path(self):
        """live_tv_registry.json lives next to the fleet scripts."""
        return os.path.join(self.fleet_base_dir, "live_tv_registry.json")

    def _live_tv_backup_path(self):
        """Backup of the Live TV registry before the first live write."""
        path = self.live_tv_registry_path
        if not os.path.exists(path):
            return None
        existing = sorted(glob.glob(path + ".bak-*"))
        if existing:
            return existing[0]
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        backup = "%s.bak-%s" % (path, stamp)
        shutil.copy2(path, backup)
        return backup

    def list_live_tv_channels(self):
        """Live TV channels from live_tv_registry.json, read-only.

        Returns the channel list (id, name, category, country, language,
        enabled, disabled_reason). A missing or malformed registry
        raises LiveUnavailable — a half-loaded channel list must never
        be served to the operator."""
        path = self.live_tv_registry_path
        if not os.path.exists(path):
            raise LiveUnavailable("No Live TV registry file is configured here.")
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        channels = data.get("channels") if isinstance(data, dict) else None
        if not isinstance(channels, list):
            raise LiveUnavailable("Live TV registry has no channel list.")
        out = []
        for ch in channels:
            if not isinstance(ch, dict) or "id" not in ch:
                continue
            out.append({
                "id": ch["id"], "name": str(ch.get("name") or ""),
                "category": str(ch.get("category") or ""),
                "country": str(ch.get("country") or ""),
                "language": str(ch.get("language") or ""),
                "stream_url": str(ch.get("stream_url") or ""),
                "logo_url": str(ch.get("logo_url") or ""),
                "source": str(ch.get("source") or ""),
                "enabled": bool(ch.get("enabled")),
                "disabled_reason": str(ch.get("disabled_reason") or ""),
            })
        return {"channels": out, "count": len(out)}

    def _read_live_tv_registry(self):
        """Parse live_tv_registry.json; LiveUnavailable if missing/malformed."""
        path = self.live_tv_registry_path
        if not os.path.exists(path):
            raise LiveUnavailable("No Live TV registry file is configured here.")
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        channels = data.get("channels") if isinstance(data, dict) else None
        if not isinstance(channels, list):
            raise LiveUnavailable("Live TV registry has no channel list.")
        return data

    def _write_live_tv_registry(self, data):
        """Atomic registry write (tmp + replace) with an updated_at stamp."""
        path = self.live_tv_registry_path
        data["updated_at"] = datetime.now(timezone.utc).isoformat() + "Z"
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(data, fh, indent=2)
            fh.write("\n")
        os.replace(tmp, path)

    def add_live_tv_channel(self, fields, stream_checker=None):
        """Add one Live TV channel. Writes only after a backup.

        fields: name (required), stream_url (required), category,
        country, language, logo_url, source ("official"|"aggregator"),
        enabled. The stream URL is probed first (checker injectable
        for tests); a dead stream refuses unless fields["force"] is
        truthy. A duplicate name or stream URL is a warning, not a
        refusal. Returns {"channel": ..., "warnings": [...],
        "backup": ...}."""
        name = str(fields.get("name") or "").strip()
        stream_url = str(fields.get("stream_url") or "").strip()
        if not name:
            raise bad("Channel name is required.")
        if len(name) > 120:
            raise bad("Channel name is too long (120 characters).")
        if not re.match(r"^https?://", stream_url, re.I):
            raise bad("Stream URL must start with http:// or https://.")
        if len(stream_url) > 500:
            raise bad("Stream URL is too long (500 characters).")
        category = str(fields.get("category") or "").strip()[:40]
        country = str(fields.get("country") or "").strip()[:40]
        language = str(fields.get("language") or "").strip()[:40]
        logo_url = str(fields.get("logo_url") or "").strip()[:500]
        if logo_url and not re.match(r"^https?://", logo_url, re.I):
            raise bad("Logo URL must start with http:// or https://.")
        source = str(fields.get("source") or "official").strip().lower()
        if source not in ("official", "aggregator"):
            raise bad("Source must be official or aggregator.")
        enabled = _live_tv_bool(fields.get("enabled", True), True)
        force = _live_tv_bool(fields.get("force", False), False)

        data = self._read_live_tv_registry()
        channels = data["channels"]
        warnings = []
        for ch in channels:
            if not isinstance(ch, dict):
                continue
            if str(ch.get("name") or "").strip().lower() == name.lower():
                warnings.append("A channel named %s already exists." % name)
            if str(ch.get("stream_url") or "").strip() == stream_url:
                warnings.append("This stream URL is already used by %s." % (
                    ch.get("name") or "another channel"))
        checker = stream_checker or check_stream_url
        probe = checker(stream_url)
        verified_at = ""
        if probe.get("alive"):
            verified_at = datetime.now(timezone.utc).isoformat() + "Z"
        elif not force:
            status = probe.get("status")
            err = probe.get("error") or ""
            raise bad("Stream URL did not answer%s — check the link, or tick force to add it anyway." % (
                (" (HTTP %s)" % status) if status else ((" (%s)" % err[:80]) if err else "")))
        else:
            warnings.append("Stream URL did not answer; added anyway (forced).")

        backup = self._live_tv_backup_path()
        new_id = 1
        for ch in channels:
            if isinstance(ch, dict) and isinstance(ch.get("id"), int):
                new_id = max(new_id, ch["id"] + 1)
        record = {
            "id": new_id, "name": name, "category": category,
            "country": country, "language": language,
            "logo_url": logo_url, "stream_url": stream_url,
            "source": source, "verified_at": verified_at,
            "enabled": enabled,
            "disabled_reason": "" if enabled else "disabled from panel",
        }
        channels.append(record)
        self._write_live_tv_registry(data)
        return {"channel": record, "warnings": sorted(set(warnings)), "backup": backup}

    def update_live_tv_channel(self, channel_id, fields, stream_checker=None):
        """Edit one Live TV channel. Writes only after a backup.

        fields: any of name, category, country, language, stream_url,
        logo_url, source, enabled, disabled_reason. A changed stream
        URL is probed first (force overrides a dead stream). A
        duplicate name or stream URL (on another channel) is a warning,
        not a refusal. Returns {"channel": ..., "warnings": [...],
        "backup": ...}."""
        data = self._read_live_tv_registry()
        channels = data["channels"]
        found = None
        for ch in channels:
            if isinstance(ch, dict) and ch.get("id") == channel_id:
                found = ch
                break
        if found is None:
            raise not_found("That Live TV channel was not found.")
        warnings = []
        if fields.get("name") is not None:
            name = str(fields["name"] or "").strip()
            if not name:
                raise bad("Channel name is required.")
            if len(name) > 120:
                raise bad("Channel name is too long (120 characters).")
            for ch in channels:
                if ch is not found and isinstance(ch, dict) and str(ch.get("name") or "").strip().lower() == name.lower():
                    warnings.append("A channel named %s already exists." % name)
            found["name"] = name
        if fields.get("category") is not None:
            found["category"] = str(fields["category"] or "").strip()[:40]
        if fields.get("country") is not None:
            found["country"] = str(fields["country"] or "").strip()[:40]
        if fields.get("language") is not None:
            found["language"] = str(fields["language"] or "").strip()[:40]
        if fields.get("logo_url") is not None:
            logo_url = str(fields["logo_url"] or "").strip()[:500]
            if logo_url and not re.match(r"^https?://", logo_url, re.I):
                raise bad("Logo URL must start with http:// or https://.")
            found["logo_url"] = logo_url
        if fields.get("source") is not None:
            source = str(fields["source"] or "").strip().lower()
            if source not in ("official", "aggregator"):
                raise bad("Source must be official or aggregator.")
            found["source"] = source
        if fields.get("stream_url") is not None:
            stream_url = str(fields["stream_url"] or "").strip()
            if not re.match(r"^https?://", stream_url, re.I):
                raise bad("Stream URL must start with http:// or https://.")
            if len(stream_url) > 500:
                raise bad("Stream URL is too long (500 characters).")
            for ch in channels:
                if ch is not found and isinstance(ch, dict) and str(ch.get("stream_url") or "").strip() == stream_url:
                    warnings.append("This stream URL is already used by %s." % (
                        ch.get("name") or "another channel"))
            force = _live_tv_bool(fields.get("force", False), False)
            checker = stream_checker or check_stream_url
            probe = checker(stream_url)
            if probe.get("alive"):
                found["verified_at"] = datetime.now(timezone.utc).isoformat() + "Z"
            elif not force:
                status = probe.get("status")
                err = probe.get("error") or ""
                raise bad("Stream URL did not answer%s — check the link, or tick force to save it anyway." % (
                    (" (HTTP %s)" % status) if status else ((" (%s)" % err[:80]) if err else "")))
            else:
                warnings.append("Stream URL did not answer; saved anyway (forced).")
            found["stream_url"] = stream_url
        if fields.get("disabled_reason") is not None:
            found["disabled_reason"] = str(fields["disabled_reason"] or "")[:200]
        if fields.get("enabled") is not None:
            enabled = _live_tv_bool(fields["enabled"], True)
            found["enabled"] = enabled
            if enabled:
                found["disabled_reason"] = ""
        backup = self._live_tv_backup_path()
        self._write_live_tv_registry(data)
        return {"channel": dict(found), "warnings": sorted(set(warnings)), "backup": backup}

    def delete_live_tv_channel(self, channel_id):
        """Delete one Live TV channel. Writes only after a backup."""
        data = self._read_live_tv_registry()
        channels = data["channels"]
        found = None
        for ch in channels:
            if isinstance(ch, dict) and ch.get("id") == channel_id:
                found = ch
                break
        if found is None:
            raise not_found("That Live TV channel was not found.")
        backup = self._live_tv_backup_path()
        data["channels"] = [c for c in channels
                            if not (isinstance(c, dict) and c.get("id") == channel_id)]
        self._write_live_tv_registry(data)
        return {"id": channel_id, "deleted": True,
                "name": str(found.get("name") or ""), "backup": backup}

    def set_live_tv_channel(self, channel_id, enabled, reason=""):
        """Enable/disable one Live TV channel, atomically, after backup.

        Flips only the channel's `enabled` flag (+ `disabled_reason`);
        everything else in the registry is left untouched. The backend
        re-reads the registry per call, so the change takes effect on
        the next app refresh — no service restart needed."""
        path = self.live_tv_registry_path
        if not os.path.exists(path):
            raise LiveUnavailable("No Live TV registry file is configured here.")
        backup = self._live_tv_backup_path()
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        channels = data.get("channels") if isinstance(data, dict) else None
        if not isinstance(channels, list):
            raise LiveUnavailable("Live TV registry has no channel list.")
        found = None
        for ch in channels:
            if isinstance(ch, dict) and ch.get("id") == channel_id:
                found = ch
                break
        if found is None:
            raise not_found("That Live TV channel was not found.")
        found["enabled"] = bool(enabled)
        found["disabled_reason"] = "" if enabled else (str(reason or "")[:200] or "disabled from panel")
        data["updated_at"] = datetime.now(timezone.utc).isoformat() + "Z"
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(data, fh, indent=2)
            fh.write("\n")
        os.replace(tmp, path)
        return {"id": channel_id, "enabled": bool(enabled), "backup": backup}

    # -- fleet --------------------------------------------------------------
    def fleet_status(self, site):
        """Real per-crawler state: <site>_status.json + latest
        admin_fleet_audit row + last-good domain from the registry."""
        out = {"site_key": site, "status": "idle", "progress": 0,
               "failure_reason": None, "last_good_domain": None,
               "last_sync_at": None, "detail": None, "ever_ran": False}
        if self.status_dir:
            path = os.path.join(self.status_dir, "%s_status.json" % site)
            if os.path.exists(path):
                out["ever_ran"] = True
                try:
                    with open(path, "r", encoding="utf-8") as fh:
                        st = json.load(fh)
                    if isinstance(st, dict):
                        total = st.get("total") or st.get("total_roots") or 0
                        done = st.get("completed") or st.get("completed_roots") or 0
                        if total:
                            out["progress"] = int(100 * done / total)
                        elif st.get("percent") is not None:
                            out["progress"] = int(st["percent"])
                        if st.get("failed"):
                            out["failure_reason"] = "failed: %s" % st["failed"]
                        out["last_sync_at"] = st.get("updated_at") or st.get("timestamp")
                        out["detail"] = {k: st.get(k) for k in
                                         ("completed", "total", "releases", "sources",
                                          "pending", "failed", "skipped", "percent")
                                         if k in st}
                        # A crawl that skipped almost everything is not a
                        # pass: live vegamovies read completed 2, failed 5,
                        # skipped 324 — the import found almost nothing.
                        skipped = st.get("skipped")
                        skipped = skipped if isinstance(skipped, int) else 0
                        done_n = done if isinstance(done, int) else 0
                        failed_n = st.get("failed")
                        failed_n = failed_n if isinstance(failed_n, int) else 0
                        if skipped and skipped > done_n + failed_n:
                            all_n = total if isinstance(total, int) and total else skipped + done_n + failed_n
                            out["failure_reason"] = (
                                "mostly skipped: %d of %d "
                                "(completed %d, failed %d)"
                                % (skipped, all_n, done_n, failed_n))
                except (ValueError, OSError):
                    out["failure_reason"] = "status file unreadable"
        registry = self.load_registry()
        if site in registry:
            url = registry[site].get("active_base_url") or ""
            out["last_good_domain"] = url.replace("https://", "").replace("http://", "").rstrip("/") or None
        if self.has_catalog:
            conn = self._ro(self.catalog_path, "catalog")
            try:
                if _has_table(conn, "admin_fleet_audit"):
                    row = conn.execute(
                        "SELECT * FROM admin_fleet_audit WHERE crawler_id=? "
                        "ORDER BY id DESC LIMIT 1", (site,)).fetchone()
                    if row:
                        d = _row(row)
                        if d["status"] == "running":
                            out["status"] = "running"
                        elif d["status"] in ("failed", "error"):
                            out["status"] = "failed"
                            out["failure_reason"] = d.get("detail") or out["failure_reason"]
                        out["last_sync_at"] = d.get("finished_at") or d.get("started_at") or out["last_sync_at"]
                        out["ever_ran"] = True
            finally:
                conn.close()
        return out

    def list_fleet(self):
        return [self.fleet_status(site) for site in FLEET_SITES]

    def fleet_audit(self, site=None, limit=20):
        conn = self._ro(self.catalog_path, "catalog")
        try:
            if not _has_table(conn, "admin_fleet_audit"):
                return {"items": [], "count": 0}
            if site:
                rows = conn.execute(
                    "SELECT * FROM admin_fleet_audit WHERE crawler_id=? "
                    "ORDER BY id DESC LIMIT ?", (site, int(limit))).fetchall()
            else:
                rows = conn.execute(
                    "SELECT * FROM admin_fleet_audit ORDER BY id DESC LIMIT ?",
                    (int(limit),)).fetchall()
            return {"items": [_row(r) for r in rows], "count": len(rows)}
        finally:
            conn.close()

    def _default_runner(self, cmd, cwd, log_path):
        """Spawn a detached crawler subprocess, v1 style (own session,
        output to a per-run log). Returns the child pid."""
        os.makedirs(os.path.dirname(log_path) or ".", exist_ok=True)
        with open(log_path, "ab") as sink:
            proc = subprocess.Popen(
                cmd, cwd=cwd, stdin=subprocess.DEVNULL,
                stdout=sink, stderr=subprocess.STDOUT, start_new_session=True)
        return int(proc.pid)

    def run_fleet(self, site, mode, limit, actor_id=0):
        """v1 run-scraper: INSERT admin_fleet_audit 'running' + spawn
        the SAME bounded command v1 uses + background watcher updating
        the audit row. WRITE + spawn path: tests inject a fake runner
        and use a COPY of the catalog; live use goes through _rw, which
        enforces the backup precondition (PNL-08) before the audit row
        is written — the crawler is never spawned without a fresh
        verified backup."""
        if site not in FLEET_SITES:
            raise bad("Unknown crawler.")
        if mode not in FLEET_MODES:
            raise bad("Unknown fleet mode.")
        limit = int(limit)
        if limit < 10 or limit > 1000:
            raise bad("Fleet run limit must be 10 to 1000.")
        cmd = fleet_command(site, mode, limit, base_dir=self.fleet_base_dir)
        conn = self._rw(self.catalog_path, "catalog")
        try:
            conn.execute("BEGIN IMMEDIATE")
            try:
                cur = conn.execute(
                    "INSERT INTO admin_fleet_audit (crawler_id, action, status, "
                    "started_at, detail) VALUES (?,?,?,?,?)",
                    (site, "run", "running", int(time.time()),
                     "mode %s limit %d" % (mode, limit)))
                audit_id = cur.lastrowid
                conn.execute("COMMIT")
            except Exception:
                try:
                    conn.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                raise
        finally:
            conn.close()
        log_path = os.path.join(self.fleet_log_dir, "fleet-%s-%d.log" % (site, audit_id))
        pid = self.fleet_runner(cmd, self.fleet_base_dir, log_path)
        conn = self._rw(self.catalog_path, "catalog")
        try:
            conn.execute("UPDATE admin_fleet_audit SET pid=? WHERE id=?",
                         (int(pid), audit_id))
            conn.commit()
        finally:
            conn.close()

        def _watch():
            # The injected fake runner returns a pid with no process to
            # wait on; only watch when the runner is the real one.
            if self.fleet_runner is not self._default_runner:
                return
            try:
                _, status, _ru = os.wait4(int(pid), 0)
                code = os.waitstatus_to_exitcode(status)
            except (ChildProcessError, OSError):
                code = -1
            conn2 = self._rw(self.catalog_path, "catalog")
            try:
                conn2.execute(
                    "UPDATE admin_fleet_audit SET status=?, finished_at=?, "
                    "exit_code=? WHERE id=?",
                    ("done" if code == 0 else "failed", int(time.time()),
                     code, audit_id))
                conn2.commit()
            finally:
                conn2.close()

        threading.Thread(target=_watch, daemon=True).start()
        return {"audit_id": audit_id, "site_key": site, "mode": mode,
                "limit": limit, "pid": int(pid), "command": cmd,
                "status": "running"}

    # -- system health --------------------------------------------------------
    def cert_expiry(self):
        """Real cert-file read. Returns {"date": "31 Jan 2027",
        "iso": "2027-01-31", "unix": ...} or None when unreadable —
        never a hardcoded date.

        The panel runs as ubuntu and /etc/letsencrypt/live/ is root-only,
        so the direct read usually fails with permission denied (audit
        §Cert: the panel showed "Unknown" while the cert is valid to
        2027-01-31). The fallback asks root for the expiry date only,
        via a minimal passwordless-sudo helper — see _read_cert_via_sudo
        for the exact sudoers line the deploy must add.
        """
        path = self.cert_path
        if not path:
            return None
        # No os.path.exists gate here: the parent directory is
        # root-only, so exists() is False for the panel user even when
        # the file is there — and that False used to return None before
        # the sudo fallback ever ran. Try the direct read, then sudo.
        try:
            info = ssl._ssl._test_decode_cert(path)
            not_after = info.get("notAfter")
            if not_after:
                dt = datetime.strptime(not_after, "%b %d %H:%M:%S %Y %Z")
                dt = dt.replace(tzinfo=timezone.utc)
                return {"date": dt.strftime("%d %b %Y"),
                        "iso": dt.strftime("%Y-%m-%d"),
                        "unix": int(dt.timestamp())}
        except Exception:
            pass
        return _read_cert_via_sudo(path)

    def system_health(self, base_dir=".", service_port=None):
        """Read-only disk/load. Service state is 'unknown' unless the
        caller passes a port to probe (workspace tests do not): a TCP
        connect to 127.0.0.1:port says running, a refusal says down.
        The dashboard passes the live service port (8080) explicitly."""
        try:
            total, used, free = shutil.disk_usage(base_dir)
        except OSError:
            total = used = free = 0
        try:
            load1, _load5, _load15 = os.getloadavg()
        except OSError:
            load1 = None
        state = "unknown"
        if service_port is not None:
            try:
                with socket.create_connection(
                        ("127.0.0.1", int(service_port)), timeout=1.0):
                    state = "running"
            except OSError:
                state = "down"
        return {"disk_total_gb": round(total / 1e9, 1),
                "disk_used_gb": round(used / 1e9, 1),
                "disk_free_gb": round(free / 1e9, 1),
                "load_1m": round(load1, 2) if load1 is not None else None,
                "service_state": state}
