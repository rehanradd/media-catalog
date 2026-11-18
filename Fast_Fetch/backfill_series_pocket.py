#!/usr/bin/env python3
"""
backfill_series_pocket.py — B5: reclassify the series pocket that the
filmyzilla importer filed as movies.

The pocket, measured live 2026-11-18 on the merged catalog:

* LOOSE pocket (the 17 Nov self-check's definition): 243 movie-classed rows
  whose clean_title says "season" or "web series"
  (``clean_title LIKE '%season%' OR clean_title LIKE '%web series%'``).
* STRICT pocket (what this script flips): 150 rows carrying real series
  evidence under ``import_filmyzilla.detect_series_markers`` — a season
  number, the "Seaosn" typo, a "Web Series" suffix, an S-token, or a
  "Complete Season" pack. 143 of them are servable (``is_18_plus = 0``),
  so the servable series count (``content_type = 'series' AND
  is_18_plus = 0``) moves 43,302 -> 43,445.

The 93-row gap between the two pockets is NOT flipped, and that is the
whole point: a bare "season" word with no number is a movie title as often
as a series marker — "Open Season", "Season of the Witch", "Killing
Season", "Wedding Season", "Happiest Season" (125 such rows live). Flipping
the loose 243 would misfile real movies as series — the same error in
reverse. The report below prints the loose-minus-strict rows so the
operator can see exactly what stays a movie and why.

Usage (operator / gate only — never run against the live DB directly):
    python3 -S Fast_Fetch/backfill_series_pocket.py --db <catalog.db>
    python3 -S Fast_Fetch/backfill_series_pocket.py --db <catalog.db> --apply
    python3 -S Fast_Fetch/backfill_series_pocket.py --db <catalog.db> --site filmyzilla --apply

Dry-run is the default: it prints the plan and changes nothing. --apply
flips only rows still classed 'movie' (idempotent — a second run flips 0)
and never touches a row's existing `seasons` value (COALESCE keeps it).
Python standard library only.
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from collections import Counter
from pathlib import Path

BASE_DIR = Path(__file__).parent.resolve()
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

from import_filmyzilla import detect_series_markers  # noqa: E402

#: The servable-series gauge the 17 Nov check measured (43,302 live).
SERIES_COUNT_SQL = (
    "SELECT COUNT(*) FROM posts WHERE content_type = 'series' AND is_18_plus = 0"
)

#: The loose pocket definition (243 live) — reported, never flipped as-is.
LOOSE_POCKET_SQL = (
    "SELECT COUNT(*) FROM posts WHERE content_type = 'movie' "
    "AND (clean_title LIKE '%season%' OR clean_title LIKE '%web series%')"
)

#: Rows the plan reads: the title is the evidence, clean_title the fallback.
SCAN_SQL = (
    "SELECT id, title, clean_title, seasons, source_site, is_18_plus "
    "FROM posts WHERE content_type = 'movie'"
)


def series_count(conn: sqlite3.Connection) -> int:
    return conn.execute(SERIES_COUNT_SQL).fetchone()[0]


def loose_pocket_count(conn: sqlite3.Connection, site: str | None = None) -> int:
    sql = LOOSE_POCKET_SQL
    args: tuple = ()
    if site is not None:
        sql += " AND source_site = ?"
        args = (site,)
    return conn.execute(sql, args).fetchone()[0]


def plan_backfill(conn: sqlite3.Connection, site: str | None = None) -> dict:
    """Plan the flip: which movie-classed rows carry series evidence.

    The rule is `import_filmyzilla.detect_series_markers` — the same rule
    the importer now applies to new rows, so the backfill and the importer
    can never drift apart. No writes happen here.
    """
    rows = conn.execute(SCAN_SQL).fetchall()
    hits = []
    loose_minus_strict = []
    for row in rows:
        post_id, title, clean_title, seasons, source_site, is_18_plus = row
        if site is not None and source_site != site:
            continue
        is_series, season_number = detect_series_markers(title, clean_title)
        if is_series:
            hits.append({
                "id": post_id,
                "title": title,
                "clean_title": clean_title,
                "source_site": source_site,
                "is_18_plus": is_18_plus,
                "seasons": f"Season {season_number}" if season_number is not None else None,
            })
        elif (clean_title or "").lower().find("season") >= 0 or \
                (clean_title or "").lower().find("web series") >= 0:
            loose_minus_strict.append({
                "id": post_id,
                "clean_title": clean_title,
                "source_site": source_site,
            })
    return {
        "scanned": len(rows),
        "hits": hits,
        "servable_flips": sum(1 for h in hits if not h["is_18_plus"]),
        "by_site": dict(Counter(h["source_site"] for h in hits)),
        "loose_pocket": loose_pocket_count(conn, site),
        "loose_minus_strict": loose_minus_strict,
    }


def apply_backfill(conn: sqlite3.Connection, plan: dict) -> int:
    """Flip the planned rows to content_type='series'.

    Only rows still classed 'movie' move (the WHERE clause is the guard,
    so a re-run flips 0). `seasons` is filled only where the row has none —
    an existing value is never clobbered. Returns the flipped count. The
    caller commits.
    """
    flipped = 0
    for hit in plan["hits"]:
        cur = conn.execute(
            "UPDATE posts SET content_type = 'series', "
            "seasons = COALESCE(seasons, ?) "
            "WHERE id = ? AND content_type = 'movie'",
            (hit["seasons"], hit["id"]),
        )
        flipped += cur.rowcount
    return flipped


def print_report(plan: dict, before: int, after: int | None = None) -> None:
    print(f"movie rows scanned: {plan['scanned']:,}")
    print(f"loose pocket (season/web-series in clean_title): {plan['loose_pocket']:,}")
    print(f"strict flips (series evidence): {len(plan['hits']):,} "
          f"({plan['servable_flips']:,} servable)")
    print(f"flips by site: {plan['by_site']}")
    print(f"servable series count before: {before:,}")
    if after is not None:
        print(f"servable series count after: {after:,} (+{after - before:,})")
    else:
        print(f"servable series count after (projected): {before + plan['servable_flips']:,} "
              f"(+{plan['servable_flips']:,})")
    print(f"loose-minus-strict rows staying movies: {len(plan['loose_minus_strict']):,}")
    for row in plan["loose_minus_strict"][:10]:
        print(f"  stays movie: {row['id']} | {row['clean_title']!r} | {row['source_site']}")


def main() -> int:
    parser = argparse.ArgumentParser(description="B5 series-pocket backfill (dry-run by default)")
    parser.add_argument("--db", type=Path, required=True, help="Catalog DB to reclassify")
    parser.add_argument("--site", default=None, help="Only reclassify this source_site")
    parser.add_argument("--apply", action="store_true", help="Write the flip (default: report only)")
    args = parser.parse_args()

    if not args.db.exists():
        print(f"[!] Database {args.db} does not exist.", file=sys.stderr)
        return 2

    conn = sqlite3.connect(str(args.db))
    try:
        before = series_count(conn)
        plan = plan_backfill(conn, site=args.site)
        if args.apply:
            flipped = apply_backfill(conn, plan)
            conn.commit()
            after = series_count(conn)
            print_report(plan, before, after)
            print(f"BACKFILL-APPLIED flipped={flipped}")
            if after - before != plan["servable_flips"]:
                print("[!] series-count delta != planned servable flips; "
                      "check for concurrent writes.", file=sys.stderr)
                return 3
        else:
            print_report(plan, before)
            print("DRY-RUN — nothing changed (pass --apply to write).")
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
