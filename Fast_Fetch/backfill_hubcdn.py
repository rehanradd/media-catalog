#!/usr/bin/env python3
"""Crack the stored hubcdn.io/cloud bridges into their direct R2 assets.

Why this file exists (Phase C lane E, 2026-11-20): the hubcdn unwrapper
(:func:`fast_fetch.unwrap_hubcdn`) already works, is already wired into
:func:`fast_fetch.resolve_live_source`, and already proved itself live — two
stored gateway URLs unwrapped to ``pub-*.r2.dev`` assets from the Oracle VM,
and 9 ledger rows already carry an R2 ``final_url`` a press wrote there on
2026-11-18/19. The 16,855 ``service='hubcdn'`` rows are still "uncracked"
only as *stored*: the hdtvb importer files every cloud link as
``source_url == final_url == https://hubcdn.io/cloud/<id>`` with
``status='active'`` and ``is_temporary=0`` (``--resolve-direct`` defaults off),
and nothing ever batch-unwraps them afterwards — 16,725 of them have never
been checked at all (``last_checked_at IS NULL``) and none has ever recorded
a resolve failure. A press already plays them; this script does the same
unwrap in bulk so the catalog stops depending on a first press to crack each
row.

The ``<id>`` in the stored URL is a base64 JSON blob (``iv`` + ``tag`` +
``data`` — AES-GCM shape). No client-side decryption is needed or attempted:
the gateway decrypts it server-side and renders ``dl-trigger`` buttons, and
the unwrapper follows those buttons to the assets. Python's stdlib has no
AES-GCM, and inventing a pure-python one to open a blob the server already
opens would be the wrong tool for this job.

What one row costs: exactly what a press costs — the gateway page, one hop
per button, and the Stage-2 gate probe of the winner. The write mirrors
``resolve_live_source``'s own persist shape (same columns, same values):
``final_url`` becomes the R2 asset, ``is_temporary`` stays 0, ``status``
``'active'``, ``last_checked_at``/``updated_at`` stamped, the failure record
cleared, ``mime_type``/``http_status``/``is_resumable`` from the gate probe,
``expires_at`` NULL (an R2 object declares no freshness window, which is the
``record_link_expiry(clear=True)`` outcome the resolver records for it).
Only the R2 asset is stored: when the gateway offers no R2 copy the row is
left alone for the JIT press, which can still serve its Google front. A
"Preparing Download" page (the gateway routing the asset to filesdl behind
a JS challenge) is counted separately as ``preparing`` and likewise left
alone — it needs a real browser, not a re-run. A failure records nothing —
a backfill must not poison the negative cache for rows it could not prove,
and re-running simply retries them.

Read-only by default (report mode counts the bridges). ``--apply`` writes.
Re-runs are safe: already-direct rows are never selected, so each run only
touches what is still a bridge.

    python3 -S Fast_Fetch/backfill_hubcdn.py <db.path>                  # report
    python3 -S Fast_Fetch/backfill_hubcdn.py <db.path> --apply          # crack
    python3 -S Fast_Fetch/backfill_hubcdn.py <db.path> --apply --limit 100
    python3 -S Fast_Fetch/backfill_hubcdn.py <db.path> --apply --delay 0.1

Stdlib only. Run it against a staging copy first; the live catalog moves
only through the gate, never by running this here.
"""
from __future__ import annotations

import argparse
import sqlite3
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.resolve()))

import fast_fetch  # noqa: E402

#: A stored hubcdn bridge: the gateway page as both addresses, never yet
#: unwrapped. The ``service`` term is what the resolver's own branch matches
#: on; the ``LIKE`` term is what makes an already-cracked row (R2
#: ``final_url``) fall out of the set on a re-run.
BRIDGE_SELECT = """
    SELECT id, source_url, final_url FROM download_sources
    WHERE service = 'hubcdn' AND final_url LIKE '%hubcdn.io/cloud/%'
    ORDER BY id
"""

#: The persist shape ``resolve_live_source`` writes for a hubcdn press,
#: minus the expiry call (folded into the ``expires_at = NULL`` below —
#: see the module docstring).
REWRITE_SQL = """
    UPDATE download_sources
    SET final_url = ?, is_temporary = 0, status = 'active',
        last_checked_at = CURRENT_TIMESTAMP,
        updated_at = CURRENT_TIMESTAMP,
        last_failed_at = NULL, failure_class = NULL,
        mime_type = ?, http_status = ?, is_resumable = ?,
        expires_at = NULL
    WHERE id = ?
"""


def _default_probe(url: str) -> dict | None:
    """The resolver's own gate verdict for one candidate, or None.

    One gate definition, shared with the press path: a candidate is served
    only when :func:`fast_fetch._stage2_probe` proves it a file (or a
    playlist for a ``watch`` caller — this backfill only ever stores files,
    so ``"either"`` is the honest want).
    """
    verdict = fast_fetch._stage2_probe(url)
    if not fast_fetch._stage2_accept(verdict, "either"):
        return None
    return verdict


def _mirror_projection_expiry(conn: sqlite3.Connection, source_id: int) -> bool:
    """Clear the projected twin's expiry, like ``record_link_expiry`` does.

    The v2 projector re-derives ``expires_at`` from the ledger on a rebuild,
    so the ledger NULL above is the durable half; this clears the already
    published twin so a stale window cannot outlive the new asset. Guarded:
    a catalog without the projection table simply has nothing to mirror.
    """
    try:
        has_table = conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type = 'table'"
            " AND name = 'cat_links'").fetchone()
        if not has_table:
            return False
        has_col = conn.execute(
            "PRAGMA table_info(cat_links)").fetchall()
        if not any(row[1] == "ledger_link_id" for row in has_col):
            return False
        cur = conn.execute(
            "UPDATE cat_links SET expires_at = NULL WHERE ledger_link_id = ?",
            (source_id,))
        return cur.rowcount > 0
    except sqlite3.Error:
        return False


def backfill(db_path, *, apply: bool = False, limit: int | None = None,
             delay: float = 0.3, timeout: int = 8,
             unwrap=None, probe=None) -> dict:
    """Crack stored hubcdn bridges in ``db_path``; returns the counts.

    ``unwrap`` / ``probe`` are the two network edges, injectable so the
    counting, the skip rules and the persist shape are testable offline.
    Defaults are the live ones: :func:`fast_fetch.unwrap_hubcdn` and the
    resolver's Stage-2 gate.
    """
    unwrap = unwrap or fast_fetch.unwrap_hubcdn
    probe = probe or _default_probe
    stats = {"scanned": 0, "rewritten": 0, "google_only": 0,
             "unwrap_failed": 0, "probe_failed": 0, "preparing": 0,
             "skipped_direct": 0, "projection_mirrored": 0}
    with sqlite3.connect(db_path) as conn:
        rows = conn.execute(
            BRIDGE_SELECT + (" LIMIT ?" if limit is not None else ""),
            (() if limit is None else (limit,))).fetchall()
        # Rows the selector cannot see are still worth naming: an
        # already-cracked hubcdn row must not be re-proved, and a
        # foreign-service row carrying a cloud page is another lane's row.
        stats["skipped_direct"] = conn.execute(
            "SELECT COUNT(*) FROM download_sources WHERE service = 'hubcdn'"
            " AND final_url NOT LIKE '%hubcdn.io/cloud/%'"
            " AND final_url IS NOT NULL").fetchone()[0]
        if not apply:
            # Report mode counts the bridges and touches nothing — no
            # network, no writes. Whether a bridge unwraps is a live
            # question only --apply may ask.
            stats["scanned"] = len(rows)
            return stats
        for row_id, source_url, _final_url in rows:
            stats["scanned"] += 1
            try:
                res = unwrap(source_url, timeout=timeout)
            except Exception:
                stats["unwrap_failed"] += 1
                continue
            if not isinstance(res, dict) or not res.get("ok"):
                if isinstance(res, dict) and res.get("error") == "preparing_download":
                    stats["preparing"] += 1
                else:
                    stats["unwrap_failed"] += 1
                continue
            r2_url = res.get("r2_url")
            if not r2_url:
                # Google-only: playable by a press, but the backfill stores
                # the durable, token-free asset only.
                stats["google_only"] += 1
                continue
            verdict = probe(r2_url)
            if verdict is None:
                stats["probe_failed"] += 1
                continue
            http_status = (206 if (verdict.get("resumable")
                                   and verdict.get("kind") == "file")
                           else 200)
            conn.execute(REWRITE_SQL, (
                r2_url, verdict.get("mime"), http_status,
                1 if verdict.get("resumable") else 0, row_id))
            if _mirror_projection_expiry(conn, row_id):
                stats["projection_mirrored"] += 1
            stats["rewritten"] += 1
            if stats["rewritten"] % 100 == 0:
                conn.commit()
            if delay:
                time.sleep(delay)
        conn.commit()
    return stats


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Crack stored hubcdn.io/cloud bridges to R2 assets.")
    parser.add_argument("db", help="catalog SQLite file (staging copy first)")
    parser.add_argument("--apply", action="store_true",
                        help="write the rewrites (default: report only)")
    parser.add_argument("--limit", type=int, default=None,
                        help="max bridge rows to process")
    parser.add_argument("--delay", type=float, default=0.3,
                        help="seconds between rows (default 0.3)")
    parser.add_argument("--timeout", type=int, default=15,
                        help="seconds per unwrap (default 15: an 8 s unwrap"
                        " timed out once in 25 staging rows and succeeded"
                        " on retry)")
    args = parser.parse_args(argv)
    db_path = Path(args.db)
    if not db_path.is_file():
        print(f"no such database: {db_path}", file=sys.stderr)
        return 2
    stats = backfill(db_path, apply=args.apply, limit=args.limit,
                     delay=args.delay, timeout=args.timeout)
    mode = "APPLY" if args.apply else "REPORT (no writes)"
    print(f"[{mode}] scanned={stats['scanned']} "
          f"rewritten={stats['rewritten']} "
          f"google_only={stats['google_only']} "
          f"unwrap_failed={stats['unwrap_failed']} "
          f"probe_failed={stats['probe_failed']} "
          f"preparing={stats['preparing']} "
          f"skipped_direct={stats['skipped_direct']} "
          f"projection_mirrored={stats['projection_mirrored']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
