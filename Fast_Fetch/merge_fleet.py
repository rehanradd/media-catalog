#!/usr/bin/env python3
"""merge_fleet.py — merge every per-site staging database into one fresh catalog.

Keeps the live database untouched: the target is a new file the operator swaps in
only after the counts, adult filtering and redaction have been checked.
"""

from __future__ import annotations

import json
import sqlite3
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import merge_catalogs

DATA_DIR = Path("/home/ubuntu/radd-stream-data")
DEFAULT_TARGET = DATA_DIR / "merged_catalog_20261025.db"

SITES = [
    "rareanimes", "toonworld4all", "desicinemas", "hdtvb", "moviezguru",
    "hindilinks4u", "hdhub4u", "7starhd", "katmoviehd", "vegamovies",
    "multimovies", "rogmovies", "ssrmovies", "10xflix", "bolly4u",
    "filmyzilla", "9xmovies", "world4ufree", "mp4moviez", "humtv",
    # 2026-11-23 catalog drought: these six importers write staging DBs
    # daily but were never registered here, so autocrawl merged 0 new
    # posts for ~26h while their staging files grew. All six use the
    # default `<site>.db` name (no SOURCES override): xprimehub is the
    # "unified" family (download_sources), the other five are the older
    # "staging" family (sources table) — both merge via merge_site_db.
    "xprimehub", "themoviebox", "javmost", "animeworld", "dramadog",
    "megacartoons",
]

#: A site whose staging database was re-derived after it was first scraped. Merging
#: the stale `katmoviehd.db` would put 2,118 known media-type collisions back into
#: the catalog, so the fleet reads the output of `rederive_katmoviehd.py` instead.
#:
#: mp4moviez and humtv stage under `*_staging.db` names, not the `<site>.db`
#: default: `mp4moviez.db` carries posts+releases but NO sources table (the
#: merge would raise "neither download_sources nor sources"), and `humtv.db`
#: does not exist at all. `mp4moviez_staging2.db` is a byte-identical-content
#: duplicate of `mp4moviez_staging.db` (same 11,404 canonical keys both ways,
#: verified 2026-11-17) — the fleet reads the first, never both.
SOURCES = {
    "katmoviehd": DATA_DIR / "katmoviehd_refreshed.db",
    "mp4moviez": DATA_DIR / "mp4moviez_staging.db",
    "humtv": DATA_DIR / "humtv_staging.db",
}

#: Tables the merge reads from a staging database. Which of them exist depends on
#: the source family — "unified" staging carries `download_sources`, older "staging"
#: carries `sources` — so the fingerprint probes `sqlite_master` and fingerprints
#: only the tables that are there.
FINGERPRINT_TABLES = ("posts", "releases", "download_sources", "sources", "post_sources")


def staging_fingerprint(src: Path) -> dict | None:
    """Cheap, stable fingerprint of a staging database's merged content.

    Per table present: row count, max id, and max `updated_at` / `created_at`
    where those columns exist — so it catches appended rows (count / max id
    move) and in-place edits that flow through the upsert path (which stamps
    `updated_at = CURRENT_TIMESTAMP` on posts and download_sources). File size
    is included so a wholesale re-derive with identical counts still differs.
    No mtime: a sync that rewrites rows without new data would bump it and
    force a full re-merge every cycle on a 1-vCPU box.

    Returns None when the file cannot be read. Callers treat a missing
    fingerprint as "changed" and merge, so a fingerprint failure can only cost
    a merge pass, never a skipped site. Opens the database read-only.
    """
    try:
        size = src.stat().st_size
    except OSError:
        return None
    try:
        conn = sqlite3.connect(f"file:{src}?mode=ro", uri=True)
    except sqlite3.Error:
        return None
    try:
        tables = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        fp: dict = {"size": size}
        for table in FINGERPRINT_TABLES:
            if table not in tables:
                continue
            cols = {r[1] for r in conn.execute(f"PRAGMA table_info({table})")}
            parts = ["COUNT(*)"]
            parts.append("MAX(id)" if "id" in cols else "MAX(rowid)")
            if "updated_at" in cols:
                parts.append("MAX(updated_at)")
            if "created_at" in cols:
                parts.append("MAX(created_at)")
            fp[table] = list(conn.execute(
                f"SELECT {', '.join(parts)} FROM {table}").fetchone())
        return fp
    except sqlite3.Error:
        return None
    finally:
        conn.close()


def _needs_merge(prev: dict, fingerprint: dict | None) -> bool:
    """True when a site must be (re-)merged this cycle.

    A site merges when it has no ledger entry, when its last run errored, when
    it was merged before fingerprints existed (legacy entry: `posts_merged`
    but no `staging_fingerprint` — one catch-up merge, then it gains a
    fingerprint), or when its staging fingerprint changed since the recorded
    one. An unchanged fingerprint skips.
    """
    if not prev.get("posts_merged"):
        return True
    return prev.get("staging_fingerprint") != fingerprint


def main() -> int:
    target = Path(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_TARGET).resolve()
    report = Path(str(target) + ".report.json")
    results = {}
    if report.exists():
        try:
            results = json.loads(report.read_text())
        except json.JSONDecodeError:
            results = {}

    total_started = time.time()
    for site in SITES:
        src = SOURCES.get(site, DATA_DIR / f"{site}.db")
        if not src.exists():
            print(f"[!] missing {src}", flush=True)
            results[site] = {"skipped": "missing"}
            continue
        # Fingerprint BEFORE the merge: if staging changes mid-merge, the stored
        # (pre-merge) fingerprint differs next cycle and the stragglers merge then.
        fingerprint = staging_fingerprint(src)
        if not _needs_merge(results.get(site, {}), fingerprint):
            print(f"[=] {site} unchanged since last merge, skipping", flush=True)
            continue
        started = time.time()
        try:
            stats = merge_catalogs.merge_site_db(src, target, verbose=False)
        except Exception as exc:  # noqa: BLE001 — one site must not abandon the fleet
            stats = {"error": f"{type(exc).__name__}: {str(exc)[:300]}"}
        stats["elapsed_seconds"] = round(time.time() - started, 1)
        # Store the fingerprint on success only: an error entry carries none,
        # so the next cycle retries the site instead of skipping it forever.
        if "error" not in stats and fingerprint is not None:
            stats["staging_fingerprint"] = fingerprint
        results[site] = stats
        print(f"[{site}] {stats}", flush=True)
        report.write_text(json.dumps(results, indent=2))

    print(
        f"[done] fleet merged in {round(time.time() - total_started, 1)}s -> {target}",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
