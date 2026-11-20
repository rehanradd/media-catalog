#!/usr/bin/env python3
"""Fast_Fetch/backfill_slices_s1s2s3_genres.py — gate-time backfill for the
list-shaped placeholder fields (server slices S2/S3, 2026-11-20).

The serve-time code (redact.null_placeholders / public_list / curate_genres)
already hides placeholders and junk genres from every new response, and
the write-time code (fast_fetch._normalize_scraped_data) already files
NULL instead of the literal "Unknown" for new rows. This script cleans
the rows that were filed BEFORE those fixes, so the stored data stops
carrying placeholders at all.

Zero Data Loss — only the ABSENCE of a value is filed as NULL:
  * A whole-value placeholder ("Unknown", "N/A", "", "[]") becomes NULL.
  * A list loses its placeholder ITEMS ("Hindi, Unknown" -> "Hindi",
    '["Action", "Unknown"]' -> '["Action"]'); the stored shape is kept
    (JSON stays JSON, a comma list stays a comma list).
  * Junk genre tokens ("NETFLIX", "Hindi Dub", "COMPLETED") are NEVER
    deleted from stored rows. They are curated at serve time only
    (redact.curate_genres / GENRE_DENYLIST); the stored data stays so a
    wrong denylist entry hides a chip, never deletes data.
All cleaning goes through redact.null_placeholders, the same helper the
write path uses, so the backfill and new writes agree exactly.

Columns cleaned (table.column):
  posts.language, posts.genres,
  cat_works.languages, cat_works.genres,
  releases.languages,
  cat_releases.audio_languages, cat_releases.subtitle_languages
The pure-scalar columns (posts/cat_works country/industry) and the year
rule live in backfill_slices_s1s2s3.sql, which runs in the same gate.

Safety: `--dry-run` is the default and touches nothing (read-only
connection). `--apply` writes in a single transaction and reports the
count per column. The live run is parent-gated (backup first, like every
live data job). Missing tables/columns are skipped with a note, so the
script also runs against staging DBs that lack the v2 projection.

Stdlib only, like everything else in Fast_Fetch.

Usage:
    python3 -S Fast_Fetch/backfill_slices_s1s2s3_genres.py --db <catalog.db>
    python3 -S Fast_Fetch/backfill_slices_s1s2s3_genres.py --db <catalog.db> --apply
    python3 -S Fast_Fetch/backfill_slices_s1s2s3_genres.py --db <catalog.db> --limit 100
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import config  # noqa: E402
import redact  # noqa: E402

#: (table, column) pairs whose list-shaped values get placeholder-nulled.
#: Keyed by rowid, so no table needs an `id` column.
TARGETS = (
    ("posts", "language"),
    ("posts", "genres"),
    ("cat_works", "languages"),
    ("cat_works", "genres"),
    ("releases", "languages"),
    ("cat_releases", "audio_languages"),
    ("cat_releases", "subtitle_languages"),
)

#: Samples shown per column in a dry run.
SHOW_SAMPLES = 5


def _default_db() -> Path:
    return Path(config.DB_PATH)


def _table_columns(conn, table: str) -> set:
    rows = conn.execute("PRAGMA table_info(%s)" % table).fetchall()
    return {row[1] for row in rows}


def _short(value, limit: int = 60) -> str:
    text = "NULL" if value is None else str(value)
    return text if len(text) <= limit else text[: limit - 3] + "..."


def scan(conn, limit: int | None = None):
    """Yield (table, column, changed, samples) without writing anything.

    `changed` is a list of (rowid, stored, cleaned); `samples` is the
    first SHOW_SAMPLES of them, for the dry-run report.
    """
    for table, column in TARGETS:
        if not conn.execute(
            "SELECT COUNT(*) FROM sqlite_master"
            " WHERE type = 'table' AND name = ?",
            (table,),
        ).fetchone()[0]:
            print("%s.%s: table missing, skipped" % (table, column))
            continue
        if column not in _table_columns(conn, table):
            print("%s.%s: column missing, skipped" % (table, column))
            continue
        sql = "SELECT rowid, %s FROM %s WHERE %s IS NOT NULL" % (
            column,
            table,
            column,
        )
        if limit is not None:
            sql += " LIMIT %d" % limit
        changed = []
        for rowid, stored in conn.execute(sql):
            cleaned = redact.null_placeholders(stored)
            if cleaned != stored:
                changed.append((rowid, stored, cleaned))
        yield table, column, changed


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=None,
                        help="catalog database path (default: config.DB_PATH)")
    parser.add_argument("--apply", action="store_true",
                        help="write the cleaned values (default is --dry-run)")
    parser.add_argument("--limit", type=int, default=None,
                        help="only scan the first N non-NULL rows per column")
    args = parser.parse_args(argv)
    db_path = Path(args.db).expanduser() if args.db else _default_db()

    if not args.apply:
        conn = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
        try:
            total = 0
            for table, column, changed in scan(conn, limit=args.limit):
                total += len(changed)
                print("%s.%s: %d rows would change" % (table, column, len(changed)))
                for rowid, stored, cleaned in changed[:SHOW_SAMPLES]:
                    print("  rowid %d: %r -> %r"
                          % (rowid, _short(stored), _short(cleaned)))
                if len(changed) > SHOW_SAMPLES:
                    print("  ... and %d more" % (len(changed) - SHOW_SAMPLES))
        finally:
            conn.close()
        print("total rows that would change: %d" % total)
        print("dry-run: nothing written (pass --apply to write)")
        print("note: junk genre tokens (NETFLIX, Hindi Dub, COMPLETED) are")
        print("  never deleted by this script — serve-time curation hides them.")
        return 0

    conn = sqlite3.connect(str(db_path))
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            total = 0
            for table, column, changed in scan(conn, limit=args.limit):
                for rowid, _stored, cleaned in changed:
                    conn.execute(
                        "UPDATE %s SET %s = ? WHERE rowid = ?" % (table, column),
                        (cleaned, rowid),
                    )
                total += len(changed)
                print("%s.%s: %d rows cleaned" % (table, column, len(changed)))
            conn.execute("COMMIT")
        except Exception:
            conn.execute("ROLLBACK")
            raise
    finally:
        conn.close()
    print("total rows cleaned: %d" % total)
    print("remember: run backfill_slices_s1s2s3.sql in the same gate for")
    print("  the scalar columns (country/industry) and the year rule.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
