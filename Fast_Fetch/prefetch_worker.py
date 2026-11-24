#!/usr/bin/env python3
"""
Fast_Fetch/prefetch_worker.py — background prefetch pool for Tier-1 stream links.
═════════════════════════════════════════════════════════════════════════════
`resolved_cache.py` says the expensive resolver should run "on a miss (or in
the prefetch pool, later, with source='pool')". This is that pool worker.

The JIT resolver (`fast_fetch.resolve_live_source`) turns a stored bridge URL
into a playable token when a user asks for it. That is correct but cold: the
first viewer of a popular title pays the full unwrap latency (measured ~42 s
solo on this box), and a token that quietly died mid-evening fails in the
player instead of at the desk. This daemon keeps the hottest end of the
catalog warm so the stream route answers from cache:

* It selects the top N (default 200) priority titles. No play-count column
  exists in the ledger (`viewer.py` says the same and refuses to invent one),
  so priority uses the only popularity-adjacent signal we carry: how many
  prints and mirrors the catalog holds for the work, tie-broken by recency —
  identical to the "trending" sort already published by the browse API.
* For each title it finds the Tier-1 link: the single best *playable* winner
  across the title's releases, ranked by `tiers.for_release` (band, then
  playback kind, then live-ness, then measured server preference) — the exact
  link the "Auto" player would start at.
* Warmth is judged by `resolved_cache.lookup_cache` itself, so the pool and
  the request path share one freshness rule: a row counts when its horizon
  (`reverify_after`, already capped and margin-adjusted from the observed
  TTL) still lies at least one hour ahead. Anything else — missing, inside
  the hour, failure-circuited, IP-bound — is re-resolved through the
  production JIT path and written back with `record_resolved_cache(...,
  source='pool')`, which stores the *observed* expiry and only the heuristics
  when the host declared nothing. A row is never written for a failed
  resolve: the table holds accepted URLs only. Failed links are backed off
  in-process (the schema deliberately has no failure row), so a dead Tier-1
  candidate is retried every `--fail-backoff` seconds rather than every
  sweep.

Politeness is part of the contract: one resolve at a time, a fixed delay
between resolves, and a `/proc/loadavg` gate before every single one — this
box also serves the API, and a warm-up that slows down live playback has
failed at its own job. The gate waits at most `--max-wait-seconds` (300 s)
for a quiet slot, then yields the sweep instead of hanging a scheduled run.

RUN MODE (gap A7, 2026-11-11): a systemd timer + oneshot service, shipped
beside this file as `radd-prefetch.timer` / `radd-prefetch.service` and
installed by the landing (see `PREFETCH_RUNBOOK.md`). The timer fires a
`--once` sweep every 6 hours (00:30, 06:30, 12:30, 18:30 PKT) — the shortest
cache horizon — so rows are re-warmed before they go cold. The ceiling is
4.0: measured 2026-11-11 the box idles at load ~0.1 (the 6–8 idle of
2026-11-04 was the five qoder/agy agent lanes, retired since), so 4.0 parks
the worker only under genuine saturation on this 1-vCPU box, and the timer
re-fires a yielded sweep six hours later. The service also runs at Nice=19
with idle IO class, the same deprioritisation as the backup unit. Cron is
not installed on this box; systemd timers are the scheduler here. Until the
units are installed the pool does not run and the request path is the only
resolver — the live `resolved_cache` proves it: 1 JIT row, 0 pool rows.

PRE-PEAK FILL (play-fast §2 (j), 2026-11-16): the same machinery, timed to
the evening instead of the clock. `radd-prefetch-fill.timer` fires a
`--fill` run at 18:00 PKT and `radd-prefetch-topup.timer` a top-100 top-up
at 21:00 — so the titles pressed in the 19:00–23:00 peak were re-resolved
an hour or two earlier instead of paying the 8–14 s cold resolve live.
A fill warms, per title, the START rung the player presses first (480p
when the title has one, else the auto rung) plus the AUTO rung when it
differs — at most two links per title, one per band, so a top-200 fill
cannot quietly become a top-2000 resolve. Hubcloud-family links resolve
LAST (their FSL/FSLv2 URLs live ~5 h, the shortest banked lives). The
pick is the trending/recency order with operator pins (`--pins`, post
ids, at most 20) first. Overlap guard: the fill, the top-up and the
ordinary sweeps share one flock (`/tmp/radd-prefetch.lock`); a run that
finds it held yields at once, and every run waits for the autocrawl
merge's own lock before resolving (the top-up waits up to 45 min for it,
because the 20:01 merge runs until ~21:30). See `PREFETCH_RUNBOOK.md`.

Adult law applies here before anywhere else: the priority query excludes
`is_18_plus=1` posts *and* any post sharing a `canonical_key` with a flagged
one, so this worker never even resolves a link for a flagged work, let alone
caches a playable token for it. The JIT path would refuse too (and answers
`not_found` under v2); refusing earlier is cheaper and leaves nothing to leak.

Stdlib only, like everything else in Fast_Fetch. Run it under systemd or
`nohup`; SIGINT/SIGTERM finish the current wait and exit cleanly.

Usage:
    python3 -S Fast_Fetch/prefetch_worker.py                 # daemon, forever
    python3 -S Fast_Fetch/prefetch_worker.py --once          # single pass
    python3 -S Fast_Fetch/prefetch_worker.py --once --fill   # pre-peak fill, top 200
    python3 -S Fast_Fetch/prefetch_worker.py --once --topup  # evening top-up, top 100
    python3 -S Fast_Fetch/prefetch_worker.py --dry-run       # show plan only
    python3 -S Fast_Fetch/prefetch_worker.py --limit 3 --db /tmp/scratch.db
"""

from __future__ import annotations

import argparse
import os
import signal
import sqlite3
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

try:
    import fcntl  # Unix-only; the overlap guard fails open without it
except ImportError:  # pragma: no cover - the worker runs on Linux
    fcntl = None

BASE_DIR = Path(__file__).resolve().parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

#: panel_feed is imported lazily (see _load_panel_feed), never at module
#: top: it imports config, and config freezes DB_PATH at import time, so
#: an eager import here would freeze the default DB before main() honours
#: --db (the dry-run CLI test caught it: "no such table: posts" on a
#: scratch DB, because get_db opened the frozen default instead).
_panel_feed = None
_panel_feed_tried = False


def _load_panel_feed():
    """The operator's tuning-knob/pause reader, or None. Never raises.

    Imported on first use — by then main() has already set RADD_DB_PATH
    from --db, so config freezes the right database.
    """
    global _panel_feed, _panel_feed_tried
    if not _panel_feed_tried:
        _panel_feed_tried = True
        try:
            import panel_feed as _pf
            _panel_feed = _pf
        except Exception:  # pragma: no cover - panel_feed is importable in-repo
            _panel_feed = None
    return _panel_feed

#: The pool this worker keeps warm, and the rhythm it keeps it warm with.
#: The shipped pool size (200) and the shipped resolve concurrency (1,
#: serial) are the answers when the panel is unwired; when the panel IS
#: wired, the operator's `prefetch_queue` knob sizes the pool and
#: `prefetch_concurrency` sizes the resolve fan-out (panel bounds 0..500
#: and 1..2). `warmup_count` caps how many links one sweep resolves
#: (panel bound 0..200; 0 means "no cap" in the panel, so an unwired panel
#: answers no cap at all).
TOP_N = 200
RESOLVE_DELAY_SECONDS = 2.0      # between two resolves, never between skips
LOAD_CEILING = 4.0               # 1-minute /proc/loadavg above which we wait
                                 # (gap A7, measured 2026-11-11: the box idles
                                 # at ~0.1 since the agent lanes retired, so
                                 # 4.0 parks the worker only under genuine
                                 # saturation of this 1-vCPU box; the 2.0 it
                                 # replaces parked it permanently while the
                                 # lanes held idle load at 6-8)
LOAD_POLL_SECONDS = 15.0         # how often we re-read loadavg while gated
MAX_WAIT_SECONDS = 300.0         # longest a sweep waits for a quiet slot,
                                 # then it yields and the next timer firing
                                 # retries (a timer-driven oneshot must never
                                 # hang until systemd kills it)
MIN_FRESH_WINDOW_SECONDS = 3600.0  # "warm" = horizon at least this far ahead
SCAN_INTERVAL_SECONDS = 900.0    # pause between full sweeps
FAILURE_BACKOFF_SECONDS = 15 * 60.0  # in-process retry delay for cold links

#: Pre-peak fill (play-fast §2 (j), 2026-11-16): the evening run that warms
#: the top titles before the 19:00–23:00 peak. One fill warms, per title,
#: the START rung the player presses first (480p when the title has one,
#: else the auto rung) plus the AUTO rung (highest playable band) when it
#: differs — at most two links per title, one per band. Two is the cap that
#: keeps a top-200 fill from quietly becoming a top-2000 resolve; the
#: same-band backups stay cold — they only matter when the winner fails,
#: and warming them would triple the fill.
FILL_LINKS_PER_TITLE = 2
FILL_START_BAND = "480p"
#: Evening top-up size (2026-11-17, measured live): 100 titles, up from
#: 50. At 20:25 PKT — 2.4 h after the 18:00 fill, mid-peak — only 66 of
#: the top 200 titles were still warm and 223 of 308 pool rows had already
#: passed reverify_after, so the 18:00 rows for ranks 51–100 are largely
#: cold by 21:00 and the old top-50 top-up left them to rot until the
#: 00:30 sweep. The top-up only resolves cold rows, so the added cost is
#: bounded by its warmup cap (2 links x 100 titles = 200) under the run's
#: own load gate; ranks 201+ stay cold — the next step after this one is
#: measured, not this one. The top-up service passes no --limit, so this
#: constant is what the live 21:00 run actually warms.
FILL_TOPUP_LIMIT = 100
#: Services whose unwrap can mint the short-lived hubcloud-family URLs
#: (FSL ~5 h, FSLv2 ~5 h — the shortest banked lives in the catalog;
#: corrected 2026-11-24 DOC-01: fresh mints measure 18000s, not 28800s).
#: The fill resolves these LAST so their clock starts as late as possible.
#: The variant (FSL vs FSLv2) is only known AFTER the unwrap — the stored
#: service label cannot distinguish them — so the whole family goes last.
#: Same set the resolver itself dispatches on (fast_fetch.py, the hubcloud
#: branch). A link counts as family when its service is in this set or its
#: stored URL names hubcloud.
HUBCLOUD_FAMILY_SERVICES = ("fsl_r2", "hubcloud_10g", "hubcloud")
#: Overlap guard (1-vCPU box: two heavy jobs share one core, and the
#: 2026-11-11 outage was a backup overlapping a sweep). The fill, the
#: top-up and the ordinary sweeps all take the same lock file with flock;
#: whoever holds it runs, whoever finds it held yields. The file is never
#: deleted — a stale file is harmless, the lock dies with the process
#: (same pattern as the autocrawl's /tmp/radd_autocrawl.lock).
PREFETCH_LOCK_PATH = "/tmp/radd-prefetch.lock"
AUTOCRAWL_LOCK_PATH = "/tmp/radd_autocrawl.lock"


def _tuning_knob(name, default):
    """One operator tuning knob, or `default` when the panel cannot answer.

    A consumer's shipped behaviour is its own default, not the panel's
    seed, so an unwired panel changes nothing. Never raises.
    """
    feed = _load_panel_feed()
    if feed is None:
        return default
    try:
        return feed.tuning_knob(name=name, default=default)
    except Exception:
        return default


def _prefetch_paused():
    """True when the operator paused all prefetching. Never raises."""
    feed = _load_panel_feed()
    if feed is None:
        return False
    try:
        return feed.is_paused(
            target_type="prefetch_global", target_id="all")
    except Exception:
        return False

_STOP = False


def _handle_stop(signum, frame):
    global _STOP
    _STOP = True


def log(msg: str) -> None:
    print(f"[prefetch {time.strftime('%Y-%m-%d %H:%M:%S')}] {msg}", flush=True)


def sleep_chunks(seconds: float) -> None:
    """Sleep that reacts to SIGINT/SIGTERM within a fifth of a second.

    `time.sleep` is restarted after a handled signal since PEP 475, so a
    plain 15-minute sleep would ignore the stop flag for its whole duration.
    """
    deadline = time.time() + seconds
    while not _STOP:
        remaining = deadline - time.time()
        if remaining <= 0:
            return
        time.sleep(min(0.2, remaining))


def current_load() -> float:
    """1-minute load average, or 0.0 where /proc/loadavg does not exist.

    On a non-Linux box there is no measured number to gate on and inventing
    one would gate us to a fiction; the resolve delay alone still throttles.
    """
    try:
        with open("/proc/loadavg") as handle:
            return float(handle.read().split()[0])
    except (OSError, ValueError, IndexError):
        return 0.0


def _try_flock(path):
    """Non-blocking exclusive flock on `path`, or None when held/unavailable.

    Opens (creating) the lock file and tries LOCK_EX|LOCK_NB. Returns the
    open file object — the caller holds it for as long as the lock must
    last and closes it to release. None means another process holds the
    lock, the file cannot be opened, or flock is unavailable on this box
    (fail-open: the load gate still throttles the run). Never raises.
    """
    if fcntl is None:
        return None
    try:
        handle = open(path, "a+b")
    except OSError:
        return None
    try:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        handle.close()
        return None
    return handle


def wait_for_autocrawl_lock(lock_path, max_wait_seconds):
    """True when no autocrawl merge holds its lock (or it freed in time).

    Probes the merge's own lock file the way the merge's own guard does: a
    held lock means the merge is writing to the live DB, and a fill that
    resolves against it would both slow the merge and bank rows the merge
    may rewrite. Polls every LOAD_POLL_SECONDS up to `max_wait_seconds`,
    then answers False and the caller yields the run. A box without flock
    answers True at once (cannot detect the merge; the load gate still
    applies). The probe takes the lock and releases it immediately — the
    race between release and the first resolve is the same any check has,
    and the per-resolve load gate is the backstop. Never raises.
    """
    if fcntl is None:
        return True
    started = time.time()
    logged = False
    while not _STOP:
        handle = _try_flock(lock_path)
        if handle is not None:
            handle.close()
            return True
        if not logged:
            log("autocrawl merge is running; waiting for its lock")
            logged = True
        if max_wait_seconds is not None:
            remaining = max_wait_seconds - (time.time() - started)
            if remaining <= 0:
                return False
            sleep_chunks(min(LOAD_POLL_SECONDS, remaining))
        else:
            sleep_chunks(LOAD_POLL_SECONDS)
    return False


def wait_for_quiet_machine(load_ceiling: float,
                           max_wait_seconds: float | None = None) -> tuple[bool, bool, bool]:
    """Returns (did_block, stopping, timed_out).

    Blocks until 1-minute loadavg drops to the ceiling. With max_wait_seconds
    set, gives up after that long and reports timed_out — a timer-driven sweep
    must yield and let the next firing retry, not hang the oneshot service
    until systemd kills it. With max_wait_seconds None it waits forever (the
    old behaviour, kept for a hand-run daemon). The sleep never overshoots
    the deadline: it sleeps min(poll, remaining) each round.
    """
    blocked = False
    started = time.time()
    while not _STOP:
        load = current_load()
        if load <= load_ceiling:
            break
        if not blocked:
            log(f"load {load:.2f} > {load_ceiling:.1f}; waiting")
            blocked = True
        if max_wait_seconds is not None:
            remaining = max_wait_seconds - (time.time() - started)
            if remaining <= 0:
                return blocked, False, True
            sleep_chunks(min(LOAD_POLL_SECONDS, remaining))
        else:
            sleep_chunks(LOAD_POLL_SECONDS)
    return blocked, _STOP, False


def select_priority_posts(conn: sqlite3.Connection, limit: int,
                          only_ids: tuple[int, ...] | None = None) -> list[sqlite3.Row]:
    """Top `limit` works by the ledger's only honest popularity proxy.

    The adult exclusion is deliberately two clauses: a flagged row is out, and
    so is every row whose `canonical_key` names a flagged work — 407
    unflagged twin rows across 394 mixed-flag keys (measured on production
    `posts`; the same figure `catalog_exporter.py` pins, and the older
    "442 / 455" pair is stale) are still reachable under an unflagged twin,
    and a prefetcher must not warm the twin of a hidden title. `only_ids`
    restricts the pick to those posts (the fill's pins) under the SAME
    exclusion — a pin is an operator's wish, never a bypass of the adult
    law — returned in priority order; the caller reorders pins itself.
    """
    sql = """
        SELECT p.id AS post_id,
               COALESCE(NULLIF(TRIM(p.clean_title), ''), p.title) AS title,
               COUNT(DISTINCT r.id) + COUNT(DISTINCT s.id) AS priority
        FROM posts p
        LEFT JOIN releases r ON r.post_id = p.id
        LEFT JOIN download_sources s ON s.release_id = r.id
        WHERE p.is_18_plus = 0
          AND (p.canonical_key IS NULL OR NOT EXISTS (
                   SELECT 1 FROM posts f
                   WHERE f.is_18_plus = 1 AND f.canonical_key = p.canonical_key))
        """
    params: list = []
    if only_ids:
        sql += " AND p.id IN (%s)" % ", ".join("?" for _ in only_ids)
        params.extend(int(i) for i in only_ids)
    sql += """
        GROUP BY p.id
        ORDER BY priority DESC, p.id DESC
        LIMIT ?
        """
    params.append(int(limit))
    return conn.execute(sql, params).fetchall()


def tier1_source_id(conn: sqlite3.Connection, tiers, post_id: int) -> int | None:
    """The download_sources id the "Auto" player would pick first for this work.

    Reuses `tiers.for_release` rather than re-ranking, so there is exactly one
    definition of "best copy" in the repo. Winners are compared by band:
    2160p beats 1080p beats … beats unspecified, and only play-mode tiers
    qualify — a download-only title has nothing to warm.
    """
    best: tuple[int, int] | None = None  # (band_index, source_id); lower index wins
    for release in conn.execute("SELECT * FROM releases WHERE post_id = ?", (post_id,)):
        links = [dict(row) for row in conn.execute(
            "SELECT * FROM download_sources WHERE release_id = ?", (release["id"],))]
        if not links:
            continue
        ladder = tiers.for_release(dict(release), links)
        for tier in ladder.get("tiers", []):
            if tier.get("mode") != "play" or not tier.get("link"):
                continue
            band_index = tiers.BANDS.index(tier["band"]) if tier["band"] in tiers.BANDS \
                else len(tiers.BANDS)
            source_id = tier["link"].get("id")
            if source_id is None:
                continue
            if best is None or band_index < best[0]:
                best = (band_index, int(source_id))
    return best[1] if best else None


def fill_links_for_post(conn: sqlite3.Connection, tiers, post_id: int) -> list[int]:
    """The 1–2 links a pre-peak fill warms for one title (fill mode only).

    The first press resolves the START rung — 480p when the title has a
    playable 480p tier (the shipped default start), else the auto rung
    (the highest playable band, which is also the fallback start rung) —
    and the Detail pre-resolve warms the AUTO rung (the best allowed
    print). Warming the single Tier-1 winner, as the ordinary sweep does,
    would leave the rung the player actually presses first cold. At most
    two links, one per band; the same-band backups stay cold (they only
    matter when the winner fails). Download-only titles warm nothing.
    Reuses `tiers.for_release` + `tiers.merge`, so there is still exactly
    one definition of "best copy" in the repo.
    """
    ladders = []
    for release in conn.execute("SELECT * FROM releases WHERE post_id = ?", (post_id,)):
        links = [dict(row) for row in conn.execute(
            "SELECT * FROM download_sources WHERE release_id = ?", (release["id"],))]
        if not links:
            continue
        ladders.append(tiers.for_release(dict(release), links))
    if not ladders:
        return []
    merged = tiers.merge(ladders)
    playable = [t for t in merged.get("tiers", [])
                if t.get("playable") and t.get("link")]
    if not playable:
        return []
    auto = merged.get("auto") or {}
    auto_tier = next((t for t in playable if t["band"] == auto.get("band")), None)
    start_tier = next(
        (t for t in playable if t["band"] == FILL_START_BAND), None) or auto_tier
    links: list[int] = []
    for tier in (start_tier, auto_tier):
        if tier is None:
            continue
        source_id = tier["link"].get("id")
        if source_id is not None and int(source_id) not in links:
            links.append(int(source_id))
    return links


def _hubcloud_family_ids(conn: sqlite3.Connection, source_ids) -> set[int]:
    """Source ids whose stored link belongs to the hubcloud family.

    The FSLv2 variant is only known AFTER the unwrap — the stored service
    label cannot distinguish FSL from FSLv2 — so the whole family goes
    last in a fill. Same membership the resolver itself dispatches on.
    """
    if not source_ids:
        return set()
    placeholders = ", ".join("?" for _ in source_ids)
    rows = conn.execute(
        "SELECT id, service, source_url FROM download_sources "
        "WHERE id IN (%s)" % placeholders,
        tuple(int(i) for i in source_ids)).fetchall()
    return {int(row["id"]) for row in rows
            if str(row["service"] or "") in HUBCLOUD_FAMILY_SERVICES
            or "hubcloud" in str(row["source_url"] or "").lower()}


def parse_pins(text) -> tuple[int, ...]:
    """Comma-separated post ids from --pins, or (). At most 20, in order,
    deduped; a non-integer or non-positive entry is dropped with a log
    line. A pin is an operator's wish, never a bypass: the adult
    exclusion still applies when the pins are selected.
    """
    if not text:
        return ()
    pins: list[int] = []
    for part in str(text).split(","):
        part = part.strip()
        if not part:
            continue
        try:
            pid = int(part)
        except ValueError:
            log(f"ignoring unparseable pin {part!r}")
            continue
        if pid <= 0 or pid in pins:
            continue
        pins.append(pid)
    if len(pins) > 20:
        log(f"keeping the first 20 pins of {len(pins)}")
        pins = pins[:20]
    return tuple(pins)


def horizon_seconds_left(row: dict, now: float) -> float:
    """How long a cache hit stays servable — the same column the request path obeys.

    `lookup_cache` already refused rows past `reverify_after` (and open failure
    circuits / IP-bound tokens), so any row reaching here is servable; the pool
    asks the stricter question: is it servable a full hour from now?
    """
    return row["reverify_after"] - now


def _resolve_one(*, fast_fetch, resolved_cache, source_id, title,
                   load_ceiling, max_wait_seconds: float | None = None,
                   fail_backoff, verbose, _failure_backoff,
                   delay=0.0):
    """Resolve one source and bank it.

    Returns one of "resolved", "failed", "resolved_gated",
    "failed_gated", "stopping", "timed_out".

    One unit of the pool's work, written so the serial loop and the
    parallel fan-out call the same code: wait for a quiet machine, resolve
    (v2, the honest contract — a failed unwrap answers no_playable_source
    instead of success+stale, and the cache never stores a URL we believe
    is dead), and bank the answer on the caller's own connection. A broken
    resolve never kills the daemon; it is a cold link with a backoff.
    `delay` staggers parallel starts so two workers do not hit the same
    host in the same instant.
    """
    if delay:
        sleep_chunks(delay)
    blocked, stopping, timed_out = wait_for_quiet_machine(
        load_ceiling, max_wait_seconds)
    if stopping:
        return "stopping"
    if timed_out:
        # The box stayed busy: yield this sweep rather than resolving
        # under load. The timer re-fires in 6h (A7 behaviour, kept).
        wait = (f"{max_wait_seconds:.0f}s" if max_wait_seconds is not None
                else "unbounded")
        log(f"load still above {load_ceiling:.1f} after {wait}; "
            "yielding sweep")
        return "timed_out"
    try:
        # background=True: this is a sweep warm, not a press — slow
        # unwraps (the FilePress prepare poll, ~2 min) may use their
        # long budget here, and the banked answer serves the next press.
        # API-06: this is a background worker, not the request path, so it
        # may mint the filemoon PoW token (allow_pow_solve=True). The mint
        # runs here, off the press path, and the session-scoped token is
        # banked on disk for presses to reuse — a press never waits on a
        # solve. (load-gated above: the sweep yields under load first.)
        result = fast_fetch.resolve_live_source(source_id, v2=True,
                                                background=True,
                                                allow_pow_solve=True)
    except Exception as exc:  # a broken resolve must not kill the daemon
        result = {"success": False, "error": f"{type(exc).__name__}: {exc}"}
    if result.get("success"):
        _failure_backoff.pop(source_id, None)
        # Each worker banks on its own connection: the shared sweep
        # connection is not shared across threads.
        with fast_fetch.get_db() as task_conn:
            resolved_cache.init_resolved_cache(task_conn)
            stored = resolved_cache.record_resolved_cache(
                task_conn, source_id, result, source="pool")
            task_conn.commit()
        if verbose:
            remaining = (horizon_seconds_left(stored, time.time())
                         if stored else 0.0)
            log(f"warm #{source_id} '{title}' "
                f"horizon {remaining / 3600.0:.1f}h "
                f"({stored['category']}/{stored['kind']})" if stored else
                f"resolve for #{source_id} returned no cacheable url")
        return "resolved" if not blocked else "resolved_gated"
    _failure_backoff[source_id] = time.time() + fail_backoff
    log(f"cold #{source_id} '{title}': "
        f"{result.get('error') or result.get('code')}")
    return "failed" if not blocked else "failed_gated"


def _demand_pin_ids(conn, *, limit: int = 20) -> tuple[int, ...]:
    """Post ids the shared demand ledger says are hot, hottest first.

    The pool's demand consumer: a fill/top-up with --demand warms these
    first (after operator pins). Capped at the --pins cap (20) so demand
    can never crowd out the priority pick the way an unbounded list could.
    Never raises: an unreadable ledger answers (), and `hot_works` itself
    already excludes flagged works and their unflagged twins.
    """
    try:
        import resolved_cache
        return tuple(w["work_id"] for w in resolved_cache.hot_works(
            conn, hours=resolved_cache.DEMAND_WINDOW_HOURS,
            min_presses=resolved_cache.DEMAND_MIN_PRESSES,
            limit=int(limit)))
    except Exception:      # noqa: BLE001 — an unreadable ledger is no demand
        return ()


def prefetch_once(*, limit: int, min_fresh: float, load_ceiling: float,
                  fail_backoff: float, dry_run: bool, verbose: bool,
                  max_wait_seconds: float = MAX_WAIT_SECONDS,
                  _failure_backoff: dict[int, float],
                  workers: int = 1, warmup_count: int | None = None,
                  mode: str = "sweep", pins: tuple[int, ...] = (),
                  demand: bool = False) -> dict:
    """One sweep: select the priority titles, resolve the cold ones.

    `workers` is the resolve fan-out — the operator's `prefetch_concurrency`
    knob (panel, clamped 1..2); 1 is the shipped serial loop, and anything
    above it fans the resolves out across a ThreadPoolExecutor, each worker
    banking on its own connection. `warmup_count` caps how many links one
    sweep resolves — the operator's `warmup_count` knob; None means no cap
    (the shipped behaviour: resolve everything selected). A sweep the
    operator paused (`prefetch_global`) resolves nothing and answers
    zeroed stats.

    `mode="fill"` is the pre-peak fill: pins first (operator's post ids,
    under the same adult exclusion), then the priority pick; up to two
    links per title (start rung + auto rung) instead of the one Tier-1
    winner; hubcloud-family links resolve last. `stats` gains "pins"
    (pinned titles selected) and "links" (distinct links to resolve).

    `demand=True` (fill only) promotes the shared-warm demand ledger's hot
    works to pins — after the operator's own pins, before the priority
    pick — so a title that went hot between runs is warm by the next fill
    instead of cold for every press until the next run. `stats` gains
    "demand" (hot works that survived selection). A sweep ignores it: the
    sweeps exist to keep the pool fresh, not to chase the evening peak.
    """
    import fast_fetch
    import resolved_cache
    import tiers

    stats = {"posts": 0, "pins": 0, "tier1": 0, "links": 0, "warm": 0,
             "resolved": 0, "failed": 0, "skipped_no_link": 0, "gated": 0,
             "demand": 0}
    if _prefetch_paused():
        log("prefetching is paused by the operator (panel pause "
            "prefetch_global); sweep skipped.")
        return stats
    workers = max(1, int(workers or 1))
    now = time.time()
    with fast_fetch.get_db() as conn:
        resolved_cache.init_resolved_cache(conn)
        conn.commit()
        if mode == "fill" and demand:
            demand_ids = _demand_pin_ids(conn)
            pins = tuple(dict.fromkeys((*pins, *demand_ids)))
        if mode == "fill" and pins:
            pinned = select_priority_posts(conn, len(pins), only_ids=pins)
            order = {pid: idx for idx, pid in enumerate(pins)}
            pinned = sorted(pinned,
                            key=lambda r: order.get(r["post_id"], len(order)))
            stats["pins"] = len(pinned)
            if demand:
                stats["demand"] = len(
                    [p for p in pinned if p["post_id"] in set(demand_ids)])
            pinned_ids = {r["post_id"] for r in pinned}
            rest = [p for p in
                    select_priority_posts(conn, limit + len(pinned))
                    if p["post_id"] not in pinned_ids]
            posts = pinned + rest[:max(0, limit - len(pinned))]
        else:
            posts = select_priority_posts(conn, limit)
        stats["posts"] = len(posts)
        if verbose or dry_run:
            log(f"selected {len(posts)} priority titles")
        # Phase 1 (serial, cheap): which posts need a resolve at all. A
        # fill warms up to two links per title (start rung + auto rung);
        # a sweep warms the one Tier-1 winner.
        todo = []
        for post in posts:
            if _STOP:
                break
            if mode == "fill":
                source_ids = fill_links_for_post(conn, tiers, post["post_id"])
            else:
                one = tier1_source_id(conn, tiers, post["post_id"])
                source_ids = [one] if one is not None else []
            if not source_ids:
                stats["skipped_no_link"] += 1
                continue
            stats["tier1"] += 1
            for source_id in source_ids:
                hit = resolved_cache.lookup_cache(conn, source_id, now=now)
                if hit is not None and horizon_seconds_left(hit, now) >= min_fresh:
                    stats["warm"] += 1
                    continue
                if now < _failure_backoff.get(source_id, 0.0):
                    continue
                todo.append((source_id, post["title"]))
        # A fill resolves hubcloud-family links last: their FSL/FSLv2 URLs
        # live ~5 h, the shortest banked lives, so their clock must start
        # as late as possible. Stable sort keeps pick order inside each group.
        if mode == "fill" and todo:
            family = _hubcloud_family_ids(conn, [sid for sid, _ in todo])
            todo = sorted(todo, key=lambda item: 1 if item[0] in family else 0)
        # The warmup cap bounds one sweep's resolves; the rest wait for
        # the next sweep. None (unwired panel) means no cap.
        if warmup_count is not None:
            todo = todo[:max(0, int(warmup_count))]
        stats["links"] = len(todo)
        if dry_run:
            for source_id, title in todo:
                log(f"would resolve source #{source_id} for '{title}'")
                stats["resolved"] += 1
            return stats
        # Phase 2: resolve. Serial keeps the shipped delay between
        # resolves; parallel staggers starts across the workers.
        if workers <= 1:
            resolved_any = False
            for source_id, title in todo:
                if _STOP:
                    break
                if resolved_any:
                    sleep_chunks(RESOLVE_DELAY_SECONDS)
                if _STOP:
                    break
                resolved_any = True
                outcome = _resolve_one(
                    fast_fetch=fast_fetch, resolved_cache=resolved_cache,
                    source_id=source_id, title=title,
                    load_ceiling=load_ceiling,
                    max_wait_seconds=max_wait_seconds,
                    fail_backoff=fail_backoff,
                    verbose=verbose, _failure_backoff=_failure_backoff)
                if outcome == "stopping":
                    break
                if outcome == "timed_out":
                    stats["gated"] += 1
                    break
                # "resolved" counts every attempted resolve (A7's pinned
                # meaning: a failed attempt is resolved=1 AND failed=1);
                # "failed" counts the failures among them.
                if outcome in ("resolved", "resolved_gated",
                               "failed", "failed_gated"):
                    stats["resolved"] += 1
                if outcome in ("failed", "failed_gated"):
                    stats["failed"] += 1
                if outcome.endswith("_gated"):
                    stats["gated"] += 1
        else:
            with ThreadPoolExecutor(max_workers=workers) as pool:
                futures = {
                    pool.submit(
                        _resolve_one, fast_fetch=fast_fetch,
                        resolved_cache=resolved_cache,
                        source_id=source_id, title=title,
                        load_ceiling=load_ceiling,
                        max_wait_seconds=max_wait_seconds,
                        fail_backoff=fail_backoff, verbose=verbose,
                        _failure_backoff=_failure_backoff,
                        delay=RESOLVE_DELAY_SECONDS * (i % workers)): None
                    for i, (source_id, title) in enumerate(todo)}
                for fut in as_completed(futures):
                    if _STOP:
                        break
                    outcome = fut.result()
                    if outcome == "stopping":
                        break
                    if outcome == "timed_out":
                        stats["gated"] += 1
                        break
                    if outcome in ("resolved", "resolved_gated",
                                   "failed", "failed_gated"):
                        stats["resolved"] += 1
                    if outcome in ("failed", "failed_gated"):
                        stats["failed"] += 1
                    if outcome.endswith("_gated"):
                        stats["gated"] += 1
    return stats


def _run_one_sweep(*, args, limit, workers, warmup_count, failure_backoff,
                   pins, fill, demand=False):
    """One sweep under the overlap guard, or None when this run yields.

    The guard has two halves: our own family's lock (fail fast — a second
    prefetch job never queues behind the first; the timer re-fires) and
    the autocrawl merge's lock (wait up to --max-autocrawl-wait-seconds,
    then yield — the top-up waits up to 45 min for it, because the 20:01
    merge runs until ~21:30). Dry runs skip both: they touch nothing
    remote. A box without flock fails open — the load gate still
    throttles the run. The lock is held only for this sweep, so a daemon's
    next sweep re-checks instead of starving the timer.
    """
    lock_handle = None
    if not args.dry_run and fcntl is not None:
        lock_handle = _try_flock(args.lock_path)
        if lock_handle is None:
            log("another prefetch job holds the lock; yielding this run")
            return None
    try:
        if not args.dry_run and not wait_for_autocrawl_lock(
                args.autocrawl_lock_path, args.max_autocrawl_wait_seconds):
            log("autocrawl merge still running after "
                f"{args.max_autocrawl_wait_seconds:.0f}s; yielding this run")
            return None
        return prefetch_once(limit=limit, min_fresh=args.min_fresh,
                             load_ceiling=args.load_ceiling,
                             fail_backoff=args.fail_backoff,
                             dry_run=args.dry_run, verbose=args.verbose,
                             max_wait_seconds=args.max_wait_seconds,
                             _failure_backoff=failure_backoff,
                             workers=workers, warmup_count=warmup_count,
                             mode="fill" if fill else "sweep", pins=pins,
                             demand=demand)
    finally:
        if lock_handle is not None:
            lock_handle.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Background prefetch pool keeping Tier-1 links warm in resolved_cache.")
    # The pool size is the operator's `prefetch_queue` knob (panel,
    # clamped 0..500); an unwired panel answers the shipped TOP_N. An
    # explicit --limit wins over the knob.
    parser.add_argument("--limit", type=int, default=None,
                        help="priority titles per sweep (default: the "
                             "operator's prefetch_queue knob, else %d)" % TOP_N)
    parser.add_argument("--workers", type=int, default=None,
                        help="parallel resolve workers (default: the "
                             "operator's prefetch_concurrency knob, else 1)")
    parser.add_argument("--warmup-count", type=int, default=None,
                        help="max links resolved per sweep (default: the "
                             "operator's warmup_count knob; no cap unwired)")
    parser.add_argument("--db", default=None,
                        help="ledger path (default: config DB_PATH / RADD_DB_PATH)")
    parser.add_argument("--interval", type=float, default=SCAN_INTERVAL_SECONDS,
                        help="seconds between sweeps (default 900)")
    parser.add_argument("--min-fresh", type=float, default=MIN_FRESH_WINDOW_SECONDS,
                        help="cache horizon must outlive this many seconds (3600)")
    parser.add_argument("--load-ceiling", type=float, default=LOAD_CEILING,
                        help="max 1-minute loadavg before resolving (4.0)")
    parser.add_argument("--max-wait-seconds", type=float, default=MAX_WAIT_SECONDS,
                        help="longest a sweep waits for a quiet slot, then yields (300)")
    parser.add_argument("--fail-backoff", type=float, default=FAILURE_BACKOFF_SECONDS,
                        help="seconds before retrying a failed link (900)")
    parser.add_argument("--once", action="store_true", help="single sweep, then exit")
    parser.add_argument("--fill", action="store_true",
                        help="pre-peak fill: start rung + auto rung per title, "
                             "pins first, hubcloud-family links last")
    parser.add_argument("--topup", action="store_true",
                        help="evening top-up: a --fill of the top 100 titles")
    parser.add_argument("--pins", default=None,
                        help="comma-separated post ids warmed first in a fill "
                             "(at most 20; a flagged or missing pin is dropped)")
    parser.add_argument("--demand", action="store_true",
                        help="fill/topup only: warm the titles with the most "
                             "recent cold presses first (shared demand ledger, "
                             "H4 R5), after --pins and before the priority pick")
    parser.add_argument("--lock-path", default=PREFETCH_LOCK_PATH,
                        help="shared flock between fill, top-up and sweeps "
                             "(default /tmp/radd-prefetch.lock)")
    parser.add_argument("--autocrawl-lock-path", default=AUTOCRAWL_LOCK_PATH,
                        help="the autocrawl merge's lock file "
                             "(default /tmp/radd_autocrawl.lock)")
    parser.add_argument("--max-autocrawl-wait-seconds", type=float,
                        default=MAX_WAIT_SECONDS,
                        help="longest a run waits for the autocrawl merge to "
                             "finish, then yields (300; the top-up passes 2700)")
    parser.add_argument("--dry-run", action="store_true",
                        help="print what would be resolved; touch nothing remote")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args(argv)

    if args.db:
        # Must precede the fast_fetch import: config.py freezes DB_PATH at
        # import time, and fast_fetch.resolve_live_source writes through it.
        os.environ["RADD_DB_PATH"] = str(Path(args.db).resolve())

    if _prefetch_paused():
        log("prefetching is paused by the operator (panel pause "
            "prefetch_global); not starting.")
        return 0

    # A fill is its own scale, not the panel's sweep knobs: the panel's
    # `prefetch_queue` seed is 100 and its `warmup_count` bound is 200,
    # and a fill warms up to two links per title — consulting them here
    # would silently halve the fill. So a fill takes TOP_N (200, or 50
    # for a top-up) and a warmup cap of two links per title; an explicit
    # --limit / --warmup-count still wins over both. The sweep keeps the
    # knob behaviour exactly as shipped.
    fill = bool(args.fill or args.topup)
    if args.topup:
        limit = (args.limit if args.limit is not None else FILL_TOPUP_LIMIT)
    elif fill:
        limit = (args.limit if args.limit is not None else TOP_N)
    else:
        limit = (args.limit if args.limit is not None
                 else _tuning_knob("prefetch_queue", TOP_N))
    workers = (args.workers if args.workers is not None
               else _tuning_knob("prefetch_concurrency", 1))
    if args.warmup_count is not None:
        warmup_count = args.warmup_count
    elif fill:
        warmup_count = FILL_LINKS_PER_TITLE * limit
    else:
        warmup_count = _tuning_knob("warmup_count", None)
    # None (unwired panel) means no cap; a stored 0 means "warm up
    # nothing" and is honoured as an empty todo list by prefetch_once.
    pins = parse_pins(args.pins) if fill else ()
    # --demand is a fill/top-up knob only: the sweeps keep the pool fresh,
    # they do not chase the evening peak.
    demand = bool(args.demand and fill)

    signal.signal(signal.SIGINT, _handle_stop)
    signal.signal(signal.SIGTERM, _handle_stop)

    log(f"starting ({'fill' if fill else 'sweep'}): top {limit}, "
        f"workers {workers}, "
        f"warmup cap {warmup_count if warmup_count is not None else 'none'}, "
        f"pins {len(pins)}, "
        f"demand {'on' if demand else 'off'}, "
        f"fresh window {args.min_fresh:.0f}s, "
        f"load ceiling {args.load_ceiling:.1f}, "
        f"db {os.environ.get('RADD_DB_PATH', 'default')}")
    failure_backoff: dict[int, float] = {}
    while True:
        started = time.time()
        try:
            stats = _run_one_sweep(
                args=args, limit=limit, workers=workers,
                warmup_count=warmup_count, failure_backoff=failure_backoff,
                pins=pins, fill=fill, demand=demand)
            if stats is not None:
                log("sweep: " + " ".join(f"{k}={v}" for k, v in stats.items()) +
                    f" in {time.time() - started:.1f}s")
        except Exception as exc:
            log(f"sweep failed: {type(exc).__name__}: {exc}")
            if args.dry_run:
                return 1
        if args.once or _STOP:
            return 0
        sleep_chunks(max(1.0, args.interval - (time.time() - started)))
        if _STOP:
            return 0


if __name__ == "__main__":
    sys.exit(main())
