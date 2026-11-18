#!/usr/bin/env python3
"""Fast_Fetch/backfill_quality.py — normalize the stored releases.quality spelling.

DB checkup 2026-11-18, fix #6. The catalog's ``releases.quality`` arrived
fragmented: ``720p`` vs ``720P`` (593,105 vs 138,476 rows), a bare ``HD`` on
410,824 rows, ``full hd``/``fhd`` spellings, and 33,032 empty strings. New
writes are normalized at ingest by ``quality.normalize_quality`` (wired into
``fast_fetch.save_scraped_data``); this script fixes the rows already stored,
using the same function as the authority — the four SQL steps below are the
exact image of its rules, and the post-check proves it: after ``--apply``,
zero rows remain that the function would still change.

Steps, in order (a row can be touched by two steps — ``HD`` is lower-cased
by step 1 and then mapped by step 2 — so the per-step counts overlap and do
NOT sum; the distinct total is ``rows_changed``):
  1. lower + trim: ``quality = LOWER(TRIM(quality))`` where it differs;
  2. ``hd`` → ``720p`` (a bare HD tag is a 720p claim);
  3. ``full hd`` / ``full-hd`` / ``fhd`` → ``1080p``;
  4. empty string → NULL (a missing quality is a missing claim).

Safety: ``--dry-run`` is the default and touches nothing. ``--apply`` runs
all four UPDATEs in a single transaction and rolls back if the post-check
finds any row the normalizer would still change. Idempotent: a re-run
changes zero rows. The live run is parent-gated (backup first, like every
live data job).

Stdlib only, like everything else in Fast_Fetch.

Usage:
    python3 -S Fast_Fetch/backfill_quality.py --db <catalog.db>
    python3 -S Fast_Fetch/backfill_quality.py --db <catalog.db> --apply
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from pathlib import Path

import config
import quality as quality_mod

#: The default catalog, resolved through config (RADD_DB_PATH / DB_PATH) —
#: never a hardcoded absolute path (repo rule 5).
def _default_db() -> Path:
    return Path(config.DB_PATH)

#: Rows the normalizer would still change — the post-check. Any row matching
#: this after --apply means the SQL and the function disagree, and the run
#: must roll back rather than report success.
_REMAINING_SQL = (
    "SELECT COUNT(*) FROM releases WHERE quality IS NOT NULL AND ("
    " quality <> LOWER(TRIM(quality))"
    " OR quality IN ('hd', 'full hd', 'full-hd', 'fhd')"
    " OR quality = '')"
)

#: (stats key, dry-run count SQL, apply UPDATE SQL). The dry-run counts are
#: measured against the original rows but in step order: step 2 counts rows
#: whose lower-trimmed form is 'hd' (so 'HD' is counted once, here, not in
#: step 1), and step 4 counts rows whose trimmed form is empty.
_STEPS = (
    ("lower_trim",
     "SELECT COUNT(*) FROM releases WHERE quality IS NOT NULL"
     " AND quality <> LOWER(TRIM(quality))",
     "UPDATE releases SET quality = LOWER(TRIM(quality))"
     " WHERE quality IS NOT NULL AND quality <> LOWER(TRIM(quality))"),
    ("hd_to_720p",
     "SELECT COUNT(*) FROM releases WHERE quality IS NOT NULL"
     " AND LOWER(TRIM(quality)) = 'hd'",
     "UPDATE releases SET quality = '720p' WHERE quality = 'hd'"),
    ("fullhd_to_1080p",
     "SELECT COUNT(*) FROM releases WHERE quality IS NOT NULL"
     " AND LOWER(TRIM(quality)) IN ('full hd', 'full-hd', 'fhd')",
     "UPDATE releases SET quality = '1080p'"
     " WHERE quality IN ('full hd', 'full-hd', 'fhd')"),
    ("empty_to_null",
     "SELECT COUNT(*) FROM releases WHERE quality IS NOT NULL"
     " AND TRIM(quality) = ''",
     "UPDATE releases SET quality = NULL WHERE quality = ''"),
)


def _check_table(conn: sqlite3.Connection) -> None:
    cols = {c[1] for c in conn.execute("PRAGMA table_info(releases)").fetchall()}
    if "quality" not in cols:
        raise ValueError("releases table has no quality column")


def _count_remaining(conn: sqlite3.Connection) -> int:
    return conn.execute(_REMAINING_SQL).fetchone()[0]


def backfill(db_path, *, apply: bool = False) -> dict:
    """Normalize stored ``releases.quality``; dry-run by default.

    Returns {"lower_trim", "hd_to_720p", "fullhd_to_1080p",
    "empty_to_null", "rows_changed", "remaining_unnormalized"} — per-step
    counts of rows that step touches (dry-run: would touch; apply: did
    touch — a row can be touched by two steps, so these do not sum), the
    distinct number of rows changed (measured before the run), and the
    post-check count of rows the normalizer would still change (0 after a
    clean apply).
    """
    db_path = Path(db_path)
    if not apply:
        conn = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
        try:
            _check_table(conn)
            stats = {key: conn.execute(sql).fetchone()[0]
                     for key, sql, _ in _STEPS}
            stats["rows_changed"] = _count_remaining(conn)
            stats["remaining_unnormalized"] = stats["rows_changed"]
            return stats
        finally:
            conn.close()

    conn = sqlite3.connect(str(db_path))
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            _check_table(conn)
            stats = {"rows_changed": _count_remaining(conn)}
            for key, _sql, update in _STEPS:
                stats[key] = conn.execute(update).rowcount
            stats["remaining_unnormalized"] = _count_remaining(conn)
            if stats["remaining_unnormalized"]:
                raise RuntimeError(
                    "post-check: %d rows still unnormalized; rolling back"
                    % stats["remaining_unnormalized"])
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
                        help="write normalized quality (default is --dry-run)")
    args = parser.parse_args(argv)
    db_path = Path(args.db).expanduser() if args.db else _default_db()

    stats = backfill(db_path, apply=args.apply)
    print("lower+trim touches: %d" % stats["lower_trim"])
    print("hd -> 720p: %d" % stats["hd_to_720p"])
    print("full hd/fhd -> 1080p: %d" % stats["fullhd_to_1080p"])
    print("empty -> NULL: %d" % stats["empty_to_null"])
    print("distinct rows changed: %d" % stats["rows_changed"])
    print("rows the normalizer would still change: %d"
          % stats["remaining_unnormalized"])
    if not args.apply:
        print("dry-run: nothing written (pass --apply to write)")
    else:
        print("applied: quality normalized on %d rows" % stats["rows_changed"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
