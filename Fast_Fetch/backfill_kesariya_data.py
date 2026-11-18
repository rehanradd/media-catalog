#!/usr/bin/env python3
"""
backfill_kesariya_data.py — A4-F6: NULL out-of-range ratings and correct the
two Kesariya rows.

Measured live 2026-11-18 on the merged catalog (read-only):

* ``cat_works`` holds exactly 2 rows with ``imdb_rating > 10`` — a percent
  or vote count filed as a rating, which the projector then minted as a
  fake work rating:
  - ``series:kesariya 100:2025`` — 100.0 (renders ★ 10.0)
  - ``series:dupahiya:2026`` — 25.0 (renders ★ 2.5)
* ``posts`` holds exactly 3 rows with ``imdb_rating > 10`` — the staging
  values the merge copied verbatim (moviecok / filmiyin ``RATING_RE``
  grabbed a "100" / "25" from the page info block):
  - 131710 (moviecok) — "100"
  - 131992 (moviecok) — "25"
  - 145112 (filmiyin) — "25"
* Post 8837 (hdtvb, "Kesariya@100 S01 (2025) Hindi Completed Web Series")
  was filed industry Hollywood / country USA because the hdtvb importer
  never set them and the schema default is Hollywood/USA. It is a Hindi
  Zee5 series: industry Bollywood, country India.
* Post 4136 (desicinemas, same title, filed as a movie, year NULL,
  industry/country/language "Unknown") is the same title misclassified.
  Per the dead-links policy it is never one-strike hard-deleted — its one
  release is a playable 720p source. Decision: KEEP the row, correct it
  in place (type movie -> series, year NULL -> 2025, industry/country/
  language Unknown -> Bollywood/India/Hindi). No release is moved; the
  7-row work merge is C1 territory, not this script.

What the script does (dry-run by default, ``--apply`` writes):

1. NULLs ``cat_works.imdb_rating`` where it is > 10.
2. NULLs ``posts.imdb_rating`` where it is > 10.
3. Fixes post 8837 industry/country (only while they still read the
   wrong Hollywood/USA defaults).
4. Fixes post 4136 type/year/industry/country/language (only while it
   is still filed as a movie).

A percent is not a rating and dividing by 10 is a guess, not data, so
the values are NULLed, not converted. The write-time guard that stops
new ones is ``title_clean.valid_rating`` (merge + projector). Re-running
after ``--apply`` changes 0 rows (idempotent): every UPDATE is guarded
by the condition it fixes.

Usage (operator / gate only — never run against the live DB directly):
    python3 -S Fast_Fetch/backfill_kesariya_data.py --db <catalog.db>
    python3 -S Fast_Fetch/backfill_kesariya_data.py --db <catalog.db> --apply
Python standard library only.
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from pathlib import Path

#: The two Kesariya rows the brief verified live, with their corrections.
#: The industry/country fix for 8837 only applies while the row still
#: carries the wrong schema defaults; the 4136 fix only applies while the
#: row is still filed as a movie — both guards make a re-run a no-op.
POST_8837_FIX = {"industry": "Bollywood", "country": "India"}
POST_4136_FIX = {
    "content_type": "series",
    "year": 2025,
    "industry": "Bollywood",
    "country": "India",
    "language": "Hindi",
}

RATING_SQL = "imdb_rating IS NOT NULL AND CAST(imdb_rating AS REAL) > 10"


def _has_table(conn: sqlite3.Connection, name: str) -> bool:
    return (
        conn.execute(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name = ?",
            (name,),
        ).fetchone()[0]
        == 1
    )


def plan_backfill(conn: sqlite3.Connection) -> dict:
    """Find the rows the backfill would touch. No writes happen here."""
    plan: dict = {"works": [], "posts": [], "post_8837": None, "post_4136": None}
    if _has_table(conn, "cat_works"):
        plan["works"] = [
            {"work_key": row[0], "imdb_rating": row[1]}
            for row in conn.execute(
                f"SELECT work_key, imdb_rating FROM cat_works WHERE {RATING_SQL}"
            ).fetchall()
        ]
    if _has_table(conn, "posts"):
        plan["posts"] = [
            {"id": row[0], "source_site": row[1], "imdb_rating": row[2]}
            for row in conn.execute(
                f"SELECT id, source_site, imdb_rating FROM posts WHERE {RATING_SQL}"
            ).fetchall()
        ]
        row = conn.execute(
            "SELECT industry, country FROM posts WHERE id = 8837"
        ).fetchone()
        if row is not None:
            plan["post_8837"] = {"industry": row[0], "country": row[1]}
        row = conn.execute(
            "SELECT content_type, year, industry, country, language "
            "FROM posts WHERE id = 4136"
        ).fetchone()
        if row is not None:
            plan["post_4136"] = {
                "content_type": row[0],
                "year": row[1],
                "industry": row[2],
                "country": row[3],
                "language": row[4],
            }
    return plan


def apply_backfill(conn: sqlite3.Connection, plan: dict) -> dict:
    """Apply the planned fixes. Every UPDATE is guarded by the condition
    it fixes, so a re-run changes 0 rows. Returns the applied counts.
    The caller commits."""
    applied = {"works": 0, "posts": 0, "post_8837": 0, "post_4136": 0}
    if plan["works"]:
        cur = conn.execute(
            f"UPDATE cat_works SET imdb_rating = NULL WHERE {RATING_SQL}"
        )
        applied["works"] = cur.rowcount
    if plan["posts"]:
        cur = conn.execute(
            f"UPDATE posts SET imdb_rating = NULL WHERE {RATING_SQL}"
        )
        applied["posts"] = cur.rowcount
    current = plan["post_8837"]
    if current is not None and (
        current["industry"] == "Hollywood" and current["country"] == "USA"
    ):
        cur = conn.execute(
            "UPDATE posts SET industry = ?, country = ? "
            "WHERE id = 8837 AND industry = 'Hollywood' AND country = 'USA'",
            (POST_8837_FIX["industry"], POST_8837_FIX["country"]),
        )
        applied["post_8837"] = cur.rowcount
    current = plan["post_4136"]
    if current is not None and current["content_type"] == "movie":
        cur = conn.execute(
            "UPDATE posts SET content_type = ?, year = ?, industry = ?, "
            "country = ?, language = ? WHERE id = 4136 AND content_type = 'movie'",
            (
                POST_4136_FIX["content_type"],
                POST_4136_FIX["year"],
                POST_4136_FIX["industry"],
                POST_4136_FIX["country"],
                POST_4136_FIX["language"],
            ),
        )
        applied["post_4136"] = cur.rowcount
    return applied


def _remaining(conn: sqlite3.Connection) -> dict:
    remaining = {"works": 0, "posts": 0}
    if _has_table(conn, "cat_works"):
        remaining["works"] = conn.execute(
            f"SELECT COUNT(*) FROM cat_works WHERE {RATING_SQL}"
        ).fetchone()[0]
    if _has_table(conn, "posts"):
        remaining["posts"] = conn.execute(
            f"SELECT COUNT(*) FROM posts WHERE {RATING_SQL}"
        ).fetchone()[0]
    return remaining


def print_report(plan: dict, applied: dict | None = None) -> None:
    print(f"cat_works rows with imdb_rating > 10: {len(plan['works'])}")
    for row in plan["works"]:
        print(f"  NULL rating: {row['work_key']} (was {row['imdb_rating']})")
    print(f"posts rows with imdb_rating > 10: {len(plan['posts'])}")
    for row in plan["posts"]:
        print(
            f"  NULL rating: {row['id']} | {row['source_site']} "
            f"(was {row['imdb_rating']!r})"
        )
    if plan["post_8837"] is not None:
        cur = plan["post_8837"]
        fix = (
            "Hollywood/USA -> Bollywood/India"
            if cur["industry"] == "Hollywood" and cur["country"] == "USA"
            else "already correct, no change"
        )
        print(f"post 8837 industry/country: {cur['industry']}/{cur['country']} — {fix}")
    else:
        print("post 8837: row not found, no change")
    if plan["post_4136"] is not None:
        cur = plan["post_4136"]
        fix = (
            "movie/NULL -> series/2025 + Bollywood/India/Hindi"
            if cur["content_type"] == "movie"
            else "already corrected, no change"
        )
        print(
            f"post 4136: {cur['content_type']}/{cur['year']}/"
            f"{cur['industry']}/{cur['country']}/{cur['language']} — {fix}"
        )
    else:
        print("post 4136: row not found, no change")
    if applied is not None:
        total = sum(applied.values())
        print(
            f"applied: works={applied['works']} posts={applied['posts']} "
            f"post_8837={applied['post_8837']} post_4136={applied['post_4136']} "
            f"(total {total})"
        )


def backfill(db: Path, apply: bool = False) -> dict:
    """Plan (and optionally apply) the F6 fixes on ``db``.

    Returns stats: works/posts rating NULLs, whether the two Kesariya
    rows were fixed, the distinct rows changed, and what out-of-range
    ratings remain afterwards (0 after a successful apply).
    """
    conn = sqlite3.connect(str(db))
    try:
        plan = plan_backfill(conn)
        applied = apply_backfill(conn, plan) if apply else {
            "works": 0, "posts": 0, "post_8837": 0, "post_4136": 0,
        }
        if apply:
            conn.commit()
        remaining = _remaining(conn)
        return {
            "works_nulled": applied["works"],
            "posts_nulled": applied["posts"],
            "post_8837_fixed": bool(applied["post_8837"]),
            "post_4136_fixed": bool(applied["post_4136"]),
            "rows_changed": sum(applied.values()),
            "remaining_works": remaining["works"],
            "remaining_posts": remaining["posts"],
            "plan": plan,
        }
    finally:
        conn.close()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="A4-F6 Kesariya data backfill (dry-run by default)"
    )
    parser.add_argument("--db", type=Path, required=True, help="Catalog DB to fix")
    parser.add_argument("--apply", action="store_true", help="Write the fixes (default: report only)")
    args = parser.parse_args()

    if not args.db.exists():
        print(f"[!] Database {args.db} does not exist.", file=sys.stderr)
        return 2

    conn = sqlite3.connect(str(args.db))
    try:
        plan = plan_backfill(conn)
        if args.apply:
            applied = apply_backfill(conn, plan)
            conn.commit()
            print_report(plan, applied)
            remaining = _remaining(conn)
            print(
                f"remaining rows with imdb_rating > 10: "
                f"works={remaining['works']} posts={remaining['posts']}"
            )
            print(
                f"BACKFILL-APPLIED works={applied['works']} "
                f"posts={applied['posts']} post_8837={applied['post_8837']} "
                f"post_4136={applied['post_4136']}"
            )
            if remaining["works"] or remaining["posts"]:
                print("[!] rows with imdb_rating > 10 remain after apply.",
                      file=sys.stderr)
                return 3
        else:
            print_report(plan)
            print("DRY-RUN — nothing changed (pass --apply to write).")
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
