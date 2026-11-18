#!/usr/bin/env python3
"""Fast_Fetch/backfill_resolved_cache.py — prune the resolved_cache once.

DB checkup 2026-11-18, fix #8. On the live catalog 1,802 of 3,237
``resolved_cache`` rows (56%) are orphans — their ``link_id`` points at a
``download_sources`` row that no longer exists — and 765 (24%) are past
their measured ``expires_at``. Neither can ever serve; the orphans are
press-banking noise. This script deletes exactly those two sets, using the
same two statements as ``resolved_cache.prune_resolved_cache`` (the single
source of truth — the script calls it, it does not restate it).

The sweep keeps the table clean afterwards: ``link_state_runner.run_once``
prunes at the end of every sweep, so this one-time script is the catch-up,
not the maintenance.

Safety: ``--dry-run`` is the default and touches nothing. ``--apply`` runs
the prune in a single transaction. Idempotent: a re-run deletes zero rows.
The live run is parent-gated (backup first, like every live data job).

Stdlib only, like everything else in Fast_Fetch.

Usage:
    python3 -S Fast_Fetch/backfill_resolved_cache.py --db <catalog.db>
    python3 -S Fast_Fetch/backfill_resolved_cache.py --db <catalog.db> --apply
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
import time
from pathlib import Path

import config
import resolved_cache


#: The default catalog, resolved through config (RADD_DB_PATH / DB_PATH) —
#: never a hardcoded absolute path (repo rule 5).
def _default_db() -> Path:
    return Path(config.DB_PATH)


def _tables(conn: sqlite3.Connection) -> set:
    return {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table'").fetchall()}


def backfill(db_path, *, apply: bool = False) -> dict:
    """Prune orphan + expired ``resolved_cache`` rows; dry-run by default.

    Returns {"orphans_deleted", "expired_deleted", "rows_remaining"} —
    rows that would be deleted (dry-run) or were deleted (apply), plus the
    rows left in the table afterwards. Dry-run bucket counts overlap — a
    row that is both orphan and expired is counted in both — while apply
    deletes orphans first, so each row is deleted once.
    """
    db_path = Path(db_path)
    now = time.time()
    if not apply:
        conn = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
        try:
            tables = _tables(conn)
            orphans = expired = 0
            if "resolved_cache" in tables:
                if "download_sources" in tables:
                    orphans = conn.execute(
                        "SELECT COUNT(*) FROM resolved_cache WHERE link_id"
                        " NOT IN (SELECT id FROM download_sources)"
                    ).fetchone()[0]
                expired = conn.execute(
                    "SELECT COUNT(*) FROM resolved_cache WHERE expires_at"
                    " IS NOT NULL AND expires_at < ?",
                    (now,)).fetchone()[0]
                remaining = conn.execute(
                    "SELECT COUNT(*) FROM resolved_cache").fetchone()[0]
            else:
                remaining = 0
            return {"orphans_deleted": orphans,
                    "expired_deleted": expired,
                    "rows_remaining": remaining}
        finally:
            conn.close()

    conn = sqlite3.connect(str(db_path))
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            stats = resolved_cache.prune_resolved_cache(conn, now=now)
            remaining = conn.execute(
                "SELECT COUNT(*) FROM resolved_cache").fetchone()[0]
            stats["rows_remaining"] = remaining
            conn.execute("COMMIT")
        except Exception:
            conn.execute("ROLLBACK")
            raise
        return stats
    finally:
        conn.close()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=None,
                        help="catalog database path (default: config.DB_PATH)")
    parser.add_argument("--apply", action="store_true",
                        help="prune the cache (default is --dry-run)")
    args = parser.parse_args(argv)
    db_path = Path(args.db).expanduser() if args.db else _default_db()

    stats = backfill(db_path, apply=args.apply)
    print("orphan rows (link gone): %d" % stats["orphans_deleted"])
    print("expired rows: %d" % stats["expired_deleted"])
    print("rows remaining in resolved_cache: %d" % stats["rows_remaining"])
    if not args.apply:
        print("dry-run: nothing written (pass --apply to prune)")
    else:
        print("applied: pruned %d rows"
              % (stats["orphans_deleted"] + stats["expired_deleted"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
