#!/usr/bin/env python3
"""
recheck_queue.py — the domain recheck queue (self-healing Branch 4).

The sweep (fast_probe.py) counts per-site dead/parked signals per batch;
when a site crosses SITE_ROT_THRESHOLD it lands here, and the recheck job
(domain_recheck.py) drains the queue and runs the discovery chain for
each entry. The queue is a tiny SQLite table in its own sidecar file
next to the catalog DB (``domain_recheck_queue.db``) — a table, not a
JSON file, so it survives reboots; /tmp does not. It never touches the
catalog schema.

Row lifecycle: ``pending`` (needs a recheck attempt) -> ``done`` (the
chain verified a new base). A failed attempt (site still down) leaves
the row ``pending`` with ``last_attempt_at`` stamped, and
:func:`pending` only returns rows past the cooldown, so a down site is
retried at most once per cooldown window, not once per batch. A later
sweep batch that sees fresh dead rows re-activates a ``done`` row
(status back to ``pending``) — a healed site can rot again.

100% Python Standard Library. Zero external dependencies.
"""

import sqlite3
import time
from pathlib import Path

#: The table name inside the sidecar file.
QUEUE_TABLE = "domain_recheck_queue"

#: Seconds a failed attempt suppresses the next one for the same site.
#: The daily recheck job drains once a day; the post-batch hook drains
#: after every sweep batch, so without a cooldown a down site would earn
#: a full discovery chain every few minutes.
DEFAULT_COOLDOWN_SECONDS = 6 * 3600

#: Sidecar file name, always next to the catalog DB it watches.
QUEUE_FILENAME = "domain_recheck_queue.db"

_SCHEMA = f"""
CREATE TABLE IF NOT EXISTS {QUEUE_TABLE} (
    site TEXT PRIMARY KEY,
    queued_at REAL NOT NULL,
    reason TEXT NOT NULL DEFAULT '',
    dead_rows INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'pending',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_attempt_at REAL,
    last_result TEXT
)
"""


def queue_path_for_db(db_path) -> Path:
    """Sidecar queue path for a catalog DB path.

    ``/home/ubuntu/radd-stream-data/merged_catalog_20261025.db`` ->
    ``/home/ubuntu/radd-stream-data/domain_recheck_queue.db``.
    """
    return Path(db_path).with_name(QUEUE_FILENAME)


def _connect(path) -> sqlite3.Connection:
    conn = sqlite3.connect(str(path), timeout=30.0)
    conn.execute(_SCHEMA)
    conn.commit()
    return conn


def ensure_queue(path) -> None:
    """Create the queue table if it does not exist yet. Idempotent."""
    conn = _connect(path)
    try:
        conn.execute(_SCHEMA)
        conn.commit()
    finally:
        conn.close()


def enqueue(path, site: str, dead_rows: int, reason: str) -> None:
    """Append ``site`` to the queue (or top up an existing entry).

    ``dead_rows`` accumulates across batches (a site that keeps dying
    keeps counting); ``reason`` is replaced by the latest batch's reason;
    the status returns to ``pending`` so a ``done`` row re-activates when
    fresh dead rows arrive. The original ``queued_at`` is kept — the
    first-seen time is the honest one.
    """
    if not site:
        return
    conn = _connect(path)
    try:
        now = time.time()
        conn.execute(
            f"""INSERT INTO {QUEUE_TABLE}
                (site, queued_at, reason, dead_rows, status)
                VALUES (?, ?, ?, ?, 'pending')
                ON CONFLICT(site) DO UPDATE SET
                    dead_rows = {QUEUE_TABLE}.dead_rows + excluded.dead_rows,
                    reason = excluded.reason,
                    status = 'pending'""",
            (str(site), now, str(reason or ""), max(0, int(dead_rows))))
        conn.commit()
    finally:
        conn.close()


def pending(path, limit=None, cooldown_s: float = DEFAULT_COOLDOWN_SECONDS) -> list:
    """Due pending entries, oldest first.

    An entry is due when it has never been attempted, or its last attempt
    is older than ``cooldown_s``. A failed attempt therefore suppresses
    the next one for the cooldown window instead of spinning the chain
    once per batch. Returns a list of dicts.
    """
    conn = _connect(path)
    try:
        cutoff = time.time() - float(cooldown_s)
        sql = (f"SELECT site, queued_at, reason, dead_rows, status,"
               f" attempts, last_attempt_at, last_result"
               f" FROM {QUEUE_TABLE}"
               f" WHERE status = 'pending'"
               f" AND (last_attempt_at IS NULL OR last_attempt_at <= ?)"
               f" ORDER BY queued_at ASC")
        params: tuple = (cutoff,)
        if limit is not None:
            sql += " LIMIT ?"
            params = (cutoff, max(0, int(limit)))
        rows = conn.execute(sql, params).fetchall()
        return [{"site": r[0], "queued_at": r[1], "reason": r[2],
                 "dead_rows": r[3], "status": r[4], "attempts": r[5],
                 "last_attempt_at": r[6], "last_result": r[7]} for r in rows]
    finally:
        conn.close()


def get_entry(path, site: str):
    """One queue row as a dict, or None when the site was never queued."""
    conn = _connect(path)
    try:
        row = conn.execute(
            f"SELECT site, queued_at, reason, dead_rows, status,"
            f" attempts, last_attempt_at, last_result"
            f" FROM {QUEUE_TABLE} WHERE site = ?",
            (str(site),)).fetchone()
        if row is None:
            return None
        return {"site": row[0], "queued_at": row[1], "reason": row[2],
                "dead_rows": row[3], "status": row[4], "attempts": row[5],
                "last_attempt_at": row[6], "last_result": row[7]}
    finally:
        conn.close()


def mark_attempt(path, site: str, result: str, *, done: bool) -> None:
    """Record one recheck attempt for ``site``.

    ``attempts`` increments and ``last_attempt_at`` stamps now either
    way. ``done=True`` (the chain verified a base) closes the row;
    ``done=False`` (still down / error) leaves it ``pending`` so the
    next drain retries it after the cooldown.
    """
    conn = _connect(path)
    try:
        conn.execute(
            f"""UPDATE {QUEUE_TABLE}
                SET attempts = attempts + 1,
                    last_attempt_at = ?,
                    last_result = ?,
                    status = ?
                WHERE site = ?""",
            (time.time(), str(result or ""),
             "done" if done else "pending", str(site)))
        conn.commit()
    finally:
        conn.close()


def count_pending(path) -> int:
    """Number of pending entries (due or cooling down)."""
    conn = _connect(path)
    try:
        row = conn.execute(
            f"SELECT COUNT(*) FROM {QUEUE_TABLE} WHERE status = 'pending'"
        ).fetchone()
        return int(row[0]) if row else 0
    finally:
        conn.close()
