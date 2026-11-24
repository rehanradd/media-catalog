#!/usr/bin/env python3
"""Read-only projection-gap audit (DB-08).

Prints the posts→works gap census from ``catalog_v2.projection_gap()``
against a catalog DB opened read-only, and exits non-zero when the number
of unprojected works exceeds the threshold — the staleness gate that tells
the operator a full ``catalog_v2.project()`` rebuild is owed.

    python3 -S Fast_Fetch/projection_audit.py --db catalog.db
    python3 -S Fast_Fetch/projection_audit.py --db catalog.db --fail-if-new-works 100

Exit 0: gap within threshold. Exit 2: re-projection owed. Exit 1: usage/DB
error. Never writes to the DB.
"""

import argparse
import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.resolve()))

import catalog_v2  # noqa: E402


def audit(db_path, *, fail_if_new_works=0):
    if not Path(db_path).exists():
        raise SystemExit(f"no such database: {db_path}")
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        return catalog_v2.projection_gap(conn)
    finally:
        conn.close()


def parse_args(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--db", required=True, help="catalog SQLite DB (opened read-only)")
    p.add_argument("--fail-if-new-works", type=int, default=0,
                   help="exit 2 when new_works exceeds this (default 0: any "
                        "unprojected work means a rebuild is owed)")
    return p.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    gap = audit(args.db, fail_if_new_works=args.fail_if_new_works)
    print(json.dumps(gap, indent=2, sort_keys=True))
    return 2 if gap["new_works"] > args.fail_if_new_works else 0


if __name__ == "__main__":
    sys.exit(main())
