#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Out-of-process link-health sweep runner (2026-11-15, Phase 1 sweep rate).

The in-process maintenance tick probes 400 rows every 6 hours (2 batches of
200) — at that rate the 4.43M unchecked rows take years, while the live
cursor proves it: ``serve`` sat at id 17,605 on 2026-11-14 and 18,005 a day
later. Raising the in-process slice is not the answer: a 200-link warm once
took ~17 minutes at ~46% sustained CPU and starved a real Play resolve for
35 seconds (Robo 501 re-test, 2026-11-14). So the rate fix lives OUT of the
serve process, in this runner, behind the same guards the prefetch worker
uses: a load gate, a memory floor, Nice=19 + idle IO (from the systemd
unit), and a yield — never a fight — when the prefetch sweep or a backup is
running.

Each run does two phases, both through :func:`link_state.propagate`:

1. **Demand-first** (scope ``sweep-demand``): the rows users actually
   pressed, read from ``resolved_cache`` (1,505 of 1,508 pressed rows
   carried no fresh verdict when measured 2026-11-15). Those are the
   verdicts that change what the next press serves. Bounded by
   ``--demand-limit`` (default 200) per run.
2. **Background** (scope ``sweep``): the id-ordered pending scan, resumed
   from its own cursor — it never fights the serve tick's ``serve``
   cursor. Bounded by ``--max-rows`` (default 500) per run.

Honest arithmetic, measured 2026-11-15: a probe costs ~1–2 s of worker
time (host-dependent; presigned hosts ~4.4 s/row), so 4 runs a day at 500
rows is ~2,000 rows/day from this runner plus ~1,600/day from the tick —
full coverage of 4.43M rows still takes years. What this runner fixes is
the ORDER: pressed rows get verdicts within a day, and the background
cursor grinds steadily instead of never moving. Raising ``--max-rows``
needs a quiet-window measurement first, not optimism.

Interruption is safe by construction: ``propagate`` commits per batch and
parks its cursor at the last committed batch, so a SIGTERM, a load-gate
yield, or a systemd timeout resumes cleanly on the next run. The runner
never restarts, stops, or touches the API service or the admin panel.

Name note: ``link_sweep.py`` already exists and is a different tool — a
stratified probability sample of the projected links for a population
estimate, merged by operator action. This runner drives
``link_state.propagate`` and writes ledger verdicts directly, so it is
``link_state_runner.py``, not a second ``link_sweep``.

Stdlib only. No network except the probes themselves (via
``link_state.default_prober``, SSRF-guarded).
"""

from __future__ import annotations

import argparse
import os
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Defaults, mirroring the prefetch worker's guardrails where they exist.
DEFAULT_SCOPE = "sweep"
DEMAND_SCOPE = "sweep-demand"
DEFAULT_MAX_ROWS = 500
DEFAULT_DEMAND_LIMIT = 200
DEFAULT_WORKERS = 4
LOAD_CEILING = 4.0
MAX_WAIT_SECONDS = 300.0
MEMORY_FLOOR_MB = 1500
STALE_AFTER_SECONDS = 30 * 24 * 3600  # link_state's own default, restated

PREFETCH_SERVICE = "radd-prefetch.service"
BACKUP_SERVICE = "radd-backup-hf.service"


def log(message: str) -> None:
    print(f"[link-state-runner] {message}", flush=True)


def mem_available_mb() -> float | None:
    """MemAvailable in MB from /proc/meminfo, or None where unreadable."""
    try:
        with open("/proc/meminfo", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) / 1024.0
    except (OSError, ValueError):
        return None
    return None


#: States that mean the service is running (or starting/stopping) — for
#: guard purposes all of them mean "yield". ``activating`` exits 3, the
#: same code as ``inactive``, so an exit-code check alone misses a sweep
#: that is still starting up; the state word has to be read from stdout.
_ACTIVE_SERVICE_STATES = frozenset(
    ("active", "activating", "reloading", "deactivating"))


def service_active(name: str) -> bool:
    """True when a systemd service is running; False anywhere else.

    The state word is read from ``systemctl is-active`` stdout
    (stripped, case-insensitive). Only a genuinely stopped service —
    ``inactive``, ``failed``, ``unknown`` — counts as inactive. A box
    without systemctl (a dev machine, a container) answers False —
    the runner simply has no neighbour to yield to there.
    """
    try:
        result = subprocess.run(
            ["systemctl", "is-active", name],
            check=False, timeout=10, capture_output=True, text=True)
    except (OSError, subprocess.SubprocessError):
        return False
    state = (result.stdout or "").strip().lower()
    if state:
        return state in _ACTIVE_SERVICE_STATES
    return result.returncode == 0


def demand_ids(conn: sqlite3.Connection, *, limit: int,
               stale_after_seconds: float = STALE_AFTER_SECONDS) -> list[int]:
    """Pressed rows that need a verdict, most-recent press first.

    A row qualifies when its ledger verdict is missing or older than
    ``stale_after_seconds``. Rows whose ledger row is gone are skipped.
    When ``resolved_cache`` is absent (a fresh DB) the answer is empty,
    and the background phase still runs.
    """
    import link_state

    tables = {row[0] for row in conn.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table'")}
    if "resolved_cache" not in tables:
        return []
    evidence = link_state._available_evidence(conn)
    if "checked_at" in evidence:
        cutoff = time.time() - float(stale_after_seconds)
        rows = conn.execute(
            "SELECT rc.link_id FROM resolved_cache rc"
            " LEFT JOIN download_sources ds ON ds.id = rc.link_id"
            " WHERE ds.id IS NOT NULL"
            " AND (ds.checked_at IS NULL OR ds.checked_at < ?)"
            " ORDER BY rc.resolved_at DESC LIMIT ?",
            (cutoff, int(limit))).fetchall()
    else:
        rows = conn.execute(
            "SELECT rc.link_id FROM resolved_cache rc"
            " ORDER BY rc.resolved_at DESC LIMIT ?",
            (int(limit),)).fetchall()
    seen: list[int] = []
    for (link_id,) in rows:
        try:
            value = int(link_id)
        except (TypeError, ValueError):
            continue
        if value > 0 and value not in seen:
            seen.append(value)
    return seen


def run_once(*, db_path=None, max_rows: int = DEFAULT_MAX_ROWS,
             demand_limit: int = DEFAULT_DEMAND_LIMIT,
             scope: str = DEFAULT_SCOPE,
             workers: int = DEFAULT_WORKERS,
             load_ceiling: float = LOAD_CEILING,
             max_wait_seconds: float = MAX_WAIT_SECONDS,
             dry_run: bool = False, verbose: bool = False,
             wait_quiet=None, check_service=None, mem_mb=None) -> dict:
    """One sweep: demand phase, then background phase. Returns a summary.

    ``wait_quiet``, ``check_service`` and ``mem_mb`` are injectable for
    tests; when omitted they are the real load gate, the real systemd check
    and the real /proc/meminfo reading. A yield (busy box, low memory, a
    neighbour running) returns a summary with ``yielded`` set and exit
    code 0 — the timer retries next firing.
    """
    import link_state

    summary: dict = {"yielded": None, "demand": None, "background": None,
                     "cache_prune": None}
    available = mem_mb if mem_mb is not None else mem_available_mb()
    if available is not None and available < MEMORY_FLOOR_MB:
        summary["yielded"] = f"memory floor: MemAvailable {available:.0f} MB"
        log(f"yielding: {summary['yielded']}")
        return summary
    if wait_quiet is None:
        from prefetch_worker import wait_for_quiet_machine

        def wait_quiet(ceiling, max_wait):  # noqa: ANN001 - injected shape
            _blocked, _stopping, timed_out = wait_for_quiet_machine(
                ceiling, max_wait)
            return timed_out
    if check_service is None:
        check_service = service_active
    for neighbour in (PREFETCH_SERVICE, BACKUP_SERVICE):
        if check_service(neighbour):
            summary["yielded"] = f"{neighbour} is running"
            log(f"yielding: {summary['yielded']}")
            return summary
    if wait_quiet(load_ceiling, max_wait_seconds):
        summary["yielded"] = (f"load stayed above {load_ceiling:.1f} for "
                              f"{max_wait_seconds:.0f}s")
        log(f"yielding: {summary['yielded']}")
        return summary

    import fast_fetch

    def open_conn():
        # One helper for every catalog connection: the explicit-path
        # branch used to open a raw sqlite3.connect and silently skip
        # the serve pragmas (64 MiB cache, mmap, WAL checkpoint), so
        # the production sweep ran on sqlite's defaults. get_db(None)
        # falls back to the live catalog path, exactly as before.
        return fast_fetch.get_db(db_path)

    # Phase 1 — demand-first: the pressed rows.
    with open_conn() as conn:
        ids = demand_ids(conn, limit=demand_limit)
    if ids:
        log(f"demand phase: {len(ids)} pressed rows need a verdict")
        with open_conn() as conn:
            summary["demand"] = link_state.propagate(
                conn, scope=DEMAND_SCOPE, ids=ids, workers=workers,
                dry_run=dry_run)
        log("demand phase: %(rows_checked)s checked, %(rows_updated)s updated, "
            "by_status %(by_status)s" % summary["demand"])
    else:
        summary["demand"] = {"rows_checked": 0, "rows_updated": 0,
                             "by_status": {}, "state": "complete"}
        log("demand phase: no pressed rows need a verdict")

    # Phase 2 — background: the id-ordered pending scan, own cursor.
    if wait_quiet(load_ceiling, max_wait_seconds):
        summary["yielded"] = (f"load stayed above {load_ceiling:.1f} before "
                              "the background phase")
        log(f"yielding: {summary['yielded']}")
        return summary
    with open_conn() as conn:
        summary["background"] = link_state.propagate(
            conn, scope=scope, max_rows=max_rows, workers=workers,
            dry_run=dry_run)
    log("background phase: %(rows_checked)s checked, %(rows_updated)s updated, "
        "by_status %(by_status)s, last_id %(last_id)s, state %(state)s"
        % summary["background"])

    # Cache hygiene (DB checkup fix #8): the resolved_cache orphans whenever
    # a source row is deleted — serve-verify deletes one row at a time,
    # importers delete in bulk — and nothing cascades, so a bounded prune at
    # the end of each sweep keeps press-banking honest. The table is ~3k
    # rows; the prune is two DELETEs. It never fails the sweep and never
    # runs on a dry-run.
    if not dry_run:
        try:
            import resolved_cache
            with open_conn() as conn:
                summary["cache_prune"] = resolved_cache.prune_resolved_cache(conn)
            log("cache prune: %(orphans_deleted)s orphans, "
                "%(expired_deleted)s expired" % summary["cache_prune"])
        except Exception as exc:  # noqa: BLE001 — hygiene must never fail a sweep
            summary["cache_prune"] = {"orphans_deleted": 0,
                                      "expired_deleted": 0,
                                      "error": str(exc)[:200]}
            log("cache prune skipped: %s" % exc)
    if verbose:
        log(f"summary: {summary}")
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Out-of-process link-health sweep: demand-first verdicts "
                    "for pressed rows, then the background pending scan.")
    parser.add_argument("--once", action="store_true",
                        help="single sweep, then exit (the timer is the loop)")
    parser.add_argument("--max-rows", type=int, default=DEFAULT_MAX_ROWS,
                        help="background rows per sweep (default %d)"
                        % DEFAULT_MAX_ROWS)
    parser.add_argument("--demand-limit", type=int, default=DEFAULT_DEMAND_LIMIT,
                        help="pressed rows per sweep (default %d)"
                        % DEFAULT_DEMAND_LIMIT)
    parser.add_argument("--scope", default=DEFAULT_SCOPE,
                        help="cursor scope for the background phase (default "
                             "%s; demand always uses %s)"
                        % (DEFAULT_SCOPE, DEMAND_SCOPE))
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS,
                        help="parallel probers (default %d)" % DEFAULT_WORKERS)
    parser.add_argument("--load-ceiling", type=float, default=LOAD_CEILING,
                        help="max 1-minute loadavg before probing (default %.1f)"
                        % LOAD_CEILING)
    parser.add_argument("--max-wait-seconds", type=float, default=MAX_WAIT_SECONDS,
                        help="longest a phase waits for a quiet slot, then "
                             "yields (default %.0f)" % MAX_WAIT_SECONDS)
    parser.add_argument("--db", default=None,
                        help="ledger path (default: config DB_PATH / RADD_DB_PATH)")
    parser.add_argument("--dry-run", action="store_true",
                        help="plan only: count what would be probed, probe "
                             "nothing, write nothing")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args(argv)

    if args.db:
        # Must precede the fast_fetch import: config.py freezes DB_PATH at
        # import time, and the sweep writes through it.
        os.environ["RADD_DB_PATH"] = str(Path(args.db).resolve())
    if not args.once:
        parser.error("--once is the only mode: the systemd timer is the loop")
    log(f"starting: background scope {args.scope}, max_rows {args.max_rows}, "
        f"demand_limit {args.demand_limit}, workers {args.workers}, "
        f"load ceiling {args.load_ceiling:.1f}, "
        f"db {os.environ.get('RADD_DB_PATH', 'default')}")
    run_once(db_path=args.db, max_rows=args.max_rows,
             demand_limit=args.demand_limit, scope=args.scope,
             workers=args.workers, load_ceiling=args.load_ceiling,
             max_wait_seconds=args.max_wait_seconds, dry_run=args.dry_run,
             verbose=args.verbose)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
