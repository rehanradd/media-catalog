#!/usr/bin/env python3
"""Backfill bridge-fallback sources for sourceless releases (DB-08).

Diagnosis (2026-11-24, live): 46,586 releases have zero ``download_sources``
rows because their importer's bridge unwrap returned ``[]`` silently and the
release was saved anyway. 45,606 of them still carry the pointer in
``releases.intermediate_url``; 980 have a NULL/empty intermediate URL and
need a re-scrape, not a fabricated row.

This script inserts ONE bridge-fallback source per sourceless release that
has a non-empty intermediate URL, keeping the bridge itself as an
``unverified`` source (same shape as the bbupload gateway fallback in
``import_katmoviehd.resolve_file_sources`` and the scrape-time fallbacks this
branch adds to import_bolly4u / import_katmoviehd / import_desicinemas).
Only hosts in ``BRIDGE_HOSTS`` are minted; anything else is reported as
unmapped and left alone. The script is idempotent: it only touches
releases that currently have zero sources, so a re-run is a no-op once the
rows exist.

Usage (gate-only for --apply; the gate backup comes first):

    python3 -S Fast_Fetch/backfill_sourceless_bridges.py --db catalog.db
    python3 -S Fast_Fetch/backfill_sourceless_bridges.py --db catalog.db --apply
    python3 -S Fast_Fetch/backfill_sourceless_bridges.py --db catalog.db --apply --site bolly4u --limit 5000

Dry-run is the default and opens the DB read-only (mode=ro), so it cannot
write even by accident. --apply writes in a single transaction.
"""

import argparse
import json
import sqlite3
import sys
import urllib.parse
from collections import Counter
from pathlib import Path

# host fragment -> (service, source_name). Only these bridges are minted.
# "desicinemas.pk" is deliberately ABSENT: for those rows intermediate_url is
# the post page itself, not a bridge — minting it would fabricate a source.
BRIDGE_HOSTS = (
    ("linkskeep", "linkskeep", "🔗 LinksKeep Bridge"),
    ("photojin.", "photojin", "🔗 PhotoJin Bridge"),
    ("kmhd", "kmhd", "🔗 KatMovieHD Gateway"),
    ("trembed", "trembed", "🌐 Trembed Bridge"),
)


def bridge_for(intermediate_url: str):
    """(service, source_name) for a bridge URL, or None when unmapped."""
    try:
        host = (urllib.parse.urlparse(intermediate_url or "").hostname or "").lower()
    except Exception:
        return None
    if not host:
        return None
    for fragment, service, name in BRIDGE_HOSTS:
        if fragment in host:
            return service, name
    return None


def resource_id_of(url: str) -> str:
    return (url or "").rstrip("/").split("/")[-1]


def census(conn, site=None):
    """Counts only — no writes. Returns (candidates, bridgeless)."""
    args = []
    site_clause = ""
    if site:
        site_clause = " AND r.source_site = ?"
        args.append(site)
    bridgeless = conn.execute(
        "SELECT COUNT(*) FROM releases r"
        " WHERE NOT EXISTS (SELECT 1 FROM download_sources s WHERE s.release_id = r.id)"
        " AND (r.intermediate_url IS NULL OR TRIM(r.intermediate_url) = ?)"
        + site_clause,
        [""] + args,
    ).fetchone()[0]
    candidates = conn.execute(
        "SELECT r.id, r.source_site, r.intermediate_url FROM releases r"
        " WHERE NOT EXISTS (SELECT 1 FROM download_sources s WHERE s.release_id = r.id)"
        " AND r.intermediate_url IS NOT NULL AND TRIM(r.intermediate_url) <> ?"
        + site_clause
        + " ORDER BY r.id",
        [""] + args,
    ).fetchall()
    return candidates, bridgeless


def run(db_path, *, apply=False, limit=None, site=None):
    if not Path(db_path).exists():
        raise SystemExit(f"no such database: {db_path}")
    uri = f"file:{db_path}?mode=ro" if not apply else db_path
    conn = sqlite3.connect(uri, uri=not apply)
    conn.row_factory = sqlite3.Row
    try:
        tables = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type = 'table'")}
        for needed in ("releases", "download_sources"):
            if needed not in tables:
                raise SystemExit(f"database {db_path} has no {needed} table")
        candidates, bridgeless = census(conn, site=site)
        total_candidates = len(candidates)
        if limit is not None:
            candidates = candidates[:limit]
        by_site = Counter()
        by_service = Counter()
        unmapped = Counter()
        insertable = []
        for row in candidates:
            mapped = bridge_for(row["intermediate_url"])
            if mapped is None:
                try:
                    host = (urllib.parse.urlparse(
                        row["intermediate_url"]).hostname or "").lower()
                except Exception:
                    host = ""
                unmapped[host or "(unparseable)"] += 1
                continue
            service, _name = mapped
            by_site[row["source_site"]] += 1
            by_service[service] += 1
            insertable.append(row)
        inserted = 0
        if apply and insertable:
            for row in insertable:
                service, name = bridge_for(row["intermediate_url"])
                conn.execute(
                    "INSERT INTO download_sources"
                    " (release_id, source_name, service, resource_id,"
                    "  source_url, final_url, is_temporary, status)"
                    " SELECT ?, ?, ?, ?, ?, ?, 0, 'unverified'"
                    " WHERE NOT EXISTS (SELECT 1 FROM download_sources"
                    "                     WHERE release_id = ?)",
                    (row["id"], name, service,
                     resource_id_of(row["intermediate_url"]),
                     row["intermediate_url"], row["intermediate_url"],
                     row["id"]),
                )
                inserted += conn.execute("SELECT changes()").fetchone()[0]
            conn.commit()
        summary = {
            "db": str(db_path),
            "apply": bool(apply),
            "site": site,
            "limit": limit,
            "candidates_with_bridge": total_candidates,
            "mapped": len(insertable),
            "inserted": inserted if apply else 0,
            "would_insert": 0 if apply else len(insertable),
            "bridgeless_no_pointer": bridgeless,
            "unmapped_hosts": dict(unmapped),
            "by_source_site": dict(by_site),
            "by_service": dict(by_service),
        }
        print(json.dumps(summary, indent=2, sort_keys=True))
        return summary
    finally:
        conn.close()


def parse_args(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--db", required=True, help="unified catalog SQLite DB")
    p.add_argument("--apply", action="store_true",
                   help="write the rows (default is a read-only dry run)")
    p.add_argument("--limit", type=int, default=None,
                   help="cap the number of rows inserted")
    p.add_argument("--site", default=None,
                   help="only backfill releases from this source_site")
    return p.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    run(args.db, apply=args.apply, limit=args.limit, site=args.site)
    return 0


if __name__ == "__main__":
    sys.exit(main())
