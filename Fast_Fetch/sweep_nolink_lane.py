#!/usr/bin/env python3
"""Demand-first sweep lane for the 90,261 no-active-source posts (H2 #1/#2).

Verifies, highest-demand first, every unchecked source backing a post that
has NO status='active' source anywhere — the posts that cannot play without
a live re-resolve. Writes ONLY through link_state.propagate, so the verdict
policy is unchanged: 401/403/429 -> expired (never dead), transport errors
earn no verdict, only real HTTP error answers mark dead. Resumable: the
driver pages posts newest-first by keyset, and propagate skips rows that
already carry a fresh verdict, so a kill/restart redoes almost nothing.

Order: (A) sources whose links were actually pressed (resolved_cache, most
recent press first), then (B) all remaining no-active-source posts, newest
post first. The per-site lanes grind id-ordered; this lane works
newest-first, so overlap between lanes stays small.

Usage (on the Oracle VM, detached):
  nohup nice -n 19 python3 sweep_nolink_lane.py > /tmp/sweep-lane-nolink-20261118.out 2>&1 &
"""
import os
import sqlite3
import sys
import time

sys.path.insert(0, "/opt/radd-stream/Fast_Fetch")

import link_state  # noqa: E402

DB = "/home/ubuntu/radd-stream-data/merged_catalog_20261025.db"
SCOPE = "sweep-nolink"
WORKERS = 12
POSTS_PER_PAGE = 2000
CHUNK_ROWS = 5000


def log(msg):
    print(f"[nolink-lane {time.strftime('%H:%M:%S')}] {msg}", flush=True)


NO_ACTIVE_CLAUSE = """
NOT EXISTS (
    SELECT 1 FROM releases r2
    JOIN download_sources ds2 ON ds2.release_id = r2.id
    WHERE r2.post_id = p.id AND ds2.status = 'active'
)
"""


def main():
    conn = sqlite3.connect(DB, timeout=30.0)
    conn.row_factory = sqlite3.Row
    total_checked = 0
    total_updated = 0
    started = time.time()

    # Phase A — proven demand: links users actually pressed.
    log("phase A: pressed links of no-active-source posts")
    rows = conn.execute(
        f"""
        SELECT DISTINCT ds.id FROM download_sources ds
        JOIN releases r ON r.id = ds.release_id
        JOIN posts p ON p.id = r.post_id
        JOIN resolved_cache rc ON rc.link_id = ds.id
        WHERE ds.checked_at IS NULL AND {NO_ACTIVE_CLAUSE}
        ORDER BY rc.resolved_at DESC
        """
    ).fetchall()
    ids = [int(r[0]) for r in rows]
    log(f"phase A: {len(ids)} pressed source rows to verify")
    for i in range(0, len(ids), CHUNK_ROWS):
        chunk = ids[i:i + CHUNK_ROWS]
        summary = link_state.propagate(
            conn, scope=SCOPE, ids=chunk, workers=WORKERS)
        total_checked += summary["rows_checked"]
        total_updated += summary["rows_updated"]
        log(f"phase A chunk {i // CHUNK_ROWS + 1}: checked={summary['rows_checked']} "
            f"updated={summary['rows_updated']} {summary['by_status']} "
            f"total_checked={total_checked} elapsed={time.time() - started:.0f}s")
    log(f"phase A done: checked={total_checked} updated={total_updated}")

    # Phase B — newest no-active-source posts first, keyset pages.
    log("phase B: newest no-active-source posts first")
    last_post_id = None
    page = 0
    while True:
        if last_post_id is None:
            post_rows = conn.execute(
                f"SELECT id FROM posts p WHERE {NO_ACTIVE_CLAUSE} "
                "ORDER BY p.id DESC LIMIT ?",
                (POSTS_PER_PAGE,)).fetchall()
        else:
            post_rows = conn.execute(
                f"SELECT id FROM posts p WHERE p.id < ? AND {NO_ACTIVE_CLAUSE} "
                "ORDER BY p.id DESC LIMIT ?",
                (last_post_id, POSTS_PER_PAGE)).fetchall()
        if not post_rows:
            break
        post_ids = [int(r[0]) for r in post_rows]
        last_post_id = post_ids[-1]
        page += 1
        placeholders = ",".join("?" for _ in post_ids)
        src_rows = conn.execute(
            f"""SELECT ds.id FROM download_sources ds
                JOIN releases r ON r.id = ds.release_id
                WHERE r.post_id IN ({placeholders}) AND ds.checked_at IS NULL""",
            post_ids).fetchall()
        ids = [int(r[0]) for r in src_rows]
        if not ids:
            log(f"phase B page {page}: {len(post_ids)} posts, 0 unchecked sources "
                f"(last_post={last_post_id})")
            continue
        summary = link_state.propagate(
            conn, scope=SCOPE, ids=ids, workers=WORKERS)
        total_checked += summary["rows_checked"]
        total_updated += summary["rows_updated"]
        log(f"phase B page {page}: {len(post_ids)} posts, checked={summary['rows_checked']} "
            f"updated={summary['rows_updated']} {summary['by_status']} "
            f"total_checked={total_checked} elapsed={time.time() - started:.0f}s")
    log(f"ALL DONE: total_checked={total_checked} total_updated={total_updated} "
        f"elapsed={time.time() - started:.0f}s")
    conn.close()


if __name__ == "__main__":
    main()
