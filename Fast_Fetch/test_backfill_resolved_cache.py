#!/usr/bin/env python3
"""Tests for backfill_resolved_cache.py (DB checkup fix #8).

The fixture mirrors the live shape at checkup scale, shrunk: one servable
row, one expired row whose link still exists, one orphan whose link is gone,
one row that is both. The script must delete exactly the rows the prune
function deletes — it calls it, it does not restate it — and a re-run must
delete zero.
"""

from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import backfill_resolved_cache as brc

NOW = 1_800_000_000.0


def _fixture(db: Path) -> None:
    with sqlite3.connect(db) as conn:
        conn.execute("CREATE TABLE download_sources (id INTEGER PRIMARY KEY)")
        conn.executemany("INSERT INTO download_sources VALUES (?)",
                         [(1,), (2,)])
        conn.execute(
            "CREATE TABLE resolved_cache ("
            " link_id INTEGER PRIMARY KEY, url TEXT NOT NULL,"
            " category TEXT NOT NULL, kind TEXT NOT NULL,"
            " resolved_at REAL NOT NULL, expires_at REAL,"
            " reverify_after REAL NOT NULL, fail_count INTEGER NOT NULL"
            " DEFAULT 0)")
        conn.executemany(
            "INSERT INTO resolved_cache (link_id, url, category, kind,"
            " resolved_at, expires_at, reverify_after) VALUES"
            " (?, ?, ?, ?, ?, ?, ?)",
            [
                (1, "https://cdn.example.com/a.mp4", "A", "file",
                 NOW - 60, NOW + 3600, NOW + 3600),   # servable: kept
                (2, "https://cdn.example.com/b.mp4", "B", "file",
                 NOW - 3600, NOW - 10, NOW + 3600),   # expired: deleted
                (3, "https://cdn.example.com/c.mp4", "B", "file",
                 NOW - 60, NOW + 3600, NOW + 3600),   # orphan: deleted
                (4, "https://cdn.example.com/d.mp4", "B", "playlist",
                 NOW - 3600, NOW - 10, NOW + 3600),   # orphan + expired
            ])


class BackfillResolvedCacheTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "catalog.db"
        _fixture(self.db)
        self._now = mock.patch.object(time, "time", return_value=NOW)
        self._now.start()

    def tearDown(self):
        self._now.stop()
        self.tmp.cleanup()

    def _link_ids(self):
        with sqlite3.connect(self.db) as conn:
            return [r[0] for r in conn.execute(
                "SELECT link_id FROM resolved_cache ORDER BY link_id"
            ).fetchall()]

    def test_dry_run_counts_without_writing(self):
        # Dry-run counts overlap: row 4 is both orphan and expired, so it
        # is counted in both buckets. In --apply the orphan DELETE runs
        # first, so the row is deleted once, as an orphan.
        stats = brc.backfill(self.db, apply=False)
        self.assertEqual(stats, {"orphans_deleted": 2,
                                 "expired_deleted": 2,
                                 "rows_remaining": 4})
        self.assertEqual(self._link_ids(), [1, 2, 3, 4])

    def test_apply_prunes_and_is_idempotent(self):
        stats = brc.backfill(self.db, apply=True)
        self.assertEqual(stats, {"orphans_deleted": 2,
                                 "expired_deleted": 1,
                                 "rows_remaining": 1})
        self.assertEqual(self._link_ids(), [1])
        again = brc.backfill(self.db, apply=True)
        self.assertEqual(again, {"orphans_deleted": 0,
                                 "expired_deleted": 0,
                                 "rows_remaining": 1})


if __name__ == "__main__":
    unittest.main()
