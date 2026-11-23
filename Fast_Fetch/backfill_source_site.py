#!/usr/bin/env python3
"""Backfill ``posts.source_site`` from ``posts.url``.

Why this file exists: ``posts.source_site`` defaults to ``'vegamovies'``
and most importers never set it, so ~105,000 live rows (measured
2026-11-23, read-only) are labelled ``vegamovies`` while their page lives
on another site — 90,732 on registry-site hosts plus 14,377 on hosts the
audit had not attributed (desicinemas 5,290, mp4moviez 4,125,
moviesflix 1,432, themoviebox 1,327, filmyhit 935, pagalmovies 750, and
smaller hosts). The true vegamovies set is only the 8,530 rows whose URL
host actually contains ``vegamovies``. Until this is fixed, every
per-site count, freshness check and site filter in the catalog is ~80%
wrong for the vegamovies label, and every future merge repeats the
mislabeling.

The rule is one line per row: the site is the site of the page URL
(``source_site.site_for_url`` — host match, never a raw URL substring,
so ``castle`` does not match castlevania titles). A row is rewritten
only when the URL host maps to a known site AND the stored label
differs. Rows whose host is unknown are left alone and reported.

What this deliberately does NOT touch:

* ``releases.source_site`` — that is per-release scrape provenance, not
  the post's site: one hindilinks4u post legitimately carries releases
  filed by hdhub4u, katmoviehd and world4ufree scrapes (live: 141,551
  hindilinks4u + 22,288 hdhub4u + 21,341 katmoviehd + 16,780
  world4ufree releases on hindilinks4u-URL posts). Rewriting it to the
  post's site would destroy that provenance.
* ``cat_links`` / ``post_sources`` / ``cat_works`` — projection-derived;
  a re-projection owns them, not this ledger fix.
* ``raw_meta`` — the ``{"site": "kuttymovies"}`` note on ~186 rows
  records the discovery sitemap feed, while the page itself lives on
  mp4moviez.deal (148 of the 186) or kuttymovies.wiki (38). The URL
  wins: the post IS its page.

Dry run by default; ``--apply`` writes. Idempotent: a second run plans
zero rows, because every rewritten row already carries its URL's site.
Batched (``--batch``, default 5000) with a commit per batch, and a
verification scan after ``--apply`` that must find zero rows left to
fix. Prints the before/after ``source_site`` distribution and asserts
the post count is unchanged.

    python3 -S Fast_Fetch/backfill_source_site.py <db.path>             # dry run
    python3 -S Fast_Fetch/backfill_source_site.py <db.path> --apply    # write

NEVER run this against the live catalog while the service serves
presses: the gate owns live execution (backup first with
``sqlite3 .backup``, stop the service, apply, verify counts, restart,
live-test). The script refuses the live database filename unless
``--i-understand-live`` is passed, so a stray invocation cannot be the
gate — the gate passes the flag deliberately.

Stdlib only.
"""
from __future__ import annotations

import argparse
import sqlite3
import sys
import time
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import source_site

BATCH = 5000

#: The live catalog filename. Refused without --i-understand-live.
LIVE_DB_NAME = "merged_catalog_20261025.db"

SELECT_SQL = "SELECT id, url, source_site FROM posts"

UPDATE_SQL = "UPDATE posts SET source_site = ? WHERE id = ? AND source_site = ?"

DISTRIBUTION_SQL = "SELECT source_site, COUNT(*) FROM posts GROUP BY source_site"


def _connect(db_path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(str(db_path), timeout=60)
    conn.execute("PRAGMA busy_timeout=60000")
    return conn


def _has_source_site(conn: sqlite3.Connection) -> bool:
    return "source_site" in {r[1] for r in conn.execute("PRAGMA table_info(posts)")}


def scan(conn: sqlite3.Connection) -> dict:
    """Plan the backfill without writing anything.

    Returns ``total`` posts read, ``planned`` as a list of
    ``(post_id, stored_site, target_site)``, ``by_route`` counts of
    ``(stored, target)`` pairs, and ``unknown`` — rows whose URL host
    maps to no known site and are left alone.
    """
    planned: list[tuple[int, str, str]] = []
    by_route: Counter = Counter()
    unknown = 0
    total = 0
    for post_id, url, stored in conn.execute(SELECT_SQL):
        total += 1
        target = source_site.site_for_url(url)
        if target is None:
            unknown += 1
            continue
        if (stored or "") != target:
            planned.append((int(post_id), stored or "", target))
            by_route[(stored or "", target)] += 1
    return {"total": total, "planned": planned, "by_route": by_route,
            "unknown": unknown}


def apply_plan(conn: sqlite3.Connection, planned: list[tuple[int, str, str]],
               *, batch: int = BATCH, progress: bool = True) -> int:
    """Write the planned rows in batches; returns rows actually updated.

    The ``AND source_site = ?`` guard makes each batch conditional: a
    row a concurrent writer already fixed is skipped, never double-set.
    """
    changed = 0
    for start in range(0, len(planned), batch):
        chunk = planned[start:start + batch]
        cur = conn.executemany(
            UPDATE_SQL, [(target, post_id, stored) for post_id, stored, target in chunk])
        conn.commit()
        changed += cur.rowcount
        if progress:
            print(f"  ... {min(start + batch, len(planned))}/{len(planned)} planned, "
                  f"{changed} updated", flush=True)
    return changed


def distribution(conn: sqlite3.Connection) -> dict[str, int]:
    return {str(site): int(n) for site, n in conn.execute(DISTRIBUTION_SQL)}


def run(db_path: Path, *, apply: bool, batch: int = BATCH,
        progress: bool = True) -> dict:
    """Dry-run or apply the backfill on ``db_path``; returns the report."""
    started = time.time()
    conn = _connect(db_path)
    try:
        if not _has_source_site(conn):
            raise SystemExit(f"{db_path}: posts has no source_site column")
        before_total = conn.execute("SELECT COUNT(*) FROM posts").fetchone()[0]
        before_dist = distribution(conn)
        plan = scan(conn)
        report = {
            "db": str(db_path),
            "total_posts": plan["total"],
            "planned": len(plan["planned"]),
            "unknown_host_rows": plan["unknown"],
            "by_route": {f"{s or '(blank)'} -> {t}": n
                         for (s, t), n in sorted(plan["by_route"].items(),
                                                 key=lambda kv: -kv[1])},
            "before": before_dist,
        }
        if not apply:
            report["applied"] = 0
            report["verified_remaining"] = len(plan["planned"])
            return report
        changed = apply_plan(conn, plan["planned"], batch=batch, progress=progress)
        after_total = conn.execute("SELECT COUNT(*) FROM posts").fetchone()[0]
        if after_total != before_total:
            raise SystemExit(
                f"post count changed during apply: {before_total} -> {after_total}")
        remaining = scan(conn)
        if remaining["planned"]:
            raise SystemExit(
                f"verification failed: {len(remaining['planned'])} rows still mislabelled")
        report["applied"] = changed
        report["verified_remaining"] = 0
        report["after"] = distribution(conn)
        report["seconds"] = round(time.time() - started, 1)
        return report
    finally:
        conn.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("db", type=Path, help="SQLite database path")
    parser.add_argument("--apply", action="store_true",
                        help="write the backfill (default is a dry run)")
    parser.add_argument("--batch", type=int, default=BATCH,
                        help="rows per commit (default 5000)")
    parser.add_argument("--i-understand-live", action="store_true",
                        help="allow the live catalog filename (gate use only)")
    args = parser.parse_args(argv)
    if args.db.resolve().name == LIVE_DB_NAME and not args.i_understand_live:
        print(f"REFUSED: {args.db} is the live catalog filename. Live execution "
              f"needs owner/gate approval — re-run with --i-understand-live "
              f"from the gate procedure (backup first, service stopped).",
              file=sys.stderr)
        return 2
    report = run(args.db, apply=args.apply, batch=args.batch)
    print(f"db: {report['db']}")
    print(f"posts: {report['total_posts']}  planned: {report['planned']}  "
          f"unknown-host rows left alone: {report['unknown_host_rows']}")
    for route, count in report["by_route"].items():
        print(f"  {route}: {count}")
    if args.apply:
        print(f"applied: {report['applied']}  verified remaining: "
              f"{report['verified_remaining']}  ({report['seconds']}s)")
        moved = {site: report["after"].get(site, 0) - report["before"].get(site, 0)
                 for site in set(report["before"]) | set(report["after"])}
        print("distribution change (top movers):")
        for site, delta in sorted(moved.items(), key=lambda kv: -abs(kv[1]))[:12]:
            print(f"  {site}: {report['before'].get(site, 0)} -> "
                  f"{report['after'].get(site, 0)} ({delta:+d})")
    else:
        print("dry run — nothing written (re-run with --apply to write)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
