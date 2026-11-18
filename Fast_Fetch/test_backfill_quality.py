#!/usr/bin/env python3
"""Tests for backfill_quality.py (DB checkup fix #6).

The fixture is one row per fragmentation shape the checkup measured —
``720P``, bare ``HD``, ``Full HD``/``fhd``, empty strings, NULL, and an
already-canonical row — so the per-step counts below pin the *rule*, not the
catalog. A row can be touched by two steps (``HD`` is lower-cased by step 1
and then mapped by step 2), so the per-step counts do not sum; the distinct
total is ``rows_changed``.
"""

from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import backfill_quality as bq

#: (id, quality) — one row per shape.
FIXTURE = [
    (1, "720P"),      # step 1 only
    (2, "HD"),        # steps 1 + 2
    (3, "hd"),        # step 2 only
    (4, "Full HD"),   # steps 1 + 3
    (5, "fhd"),       # step 3 only
    (6, ""),          # step 4 only
    (7, "   "),       # steps 1 + 4
    (8, None),        # untouched
    (9, "720p"),      # untouched
    (10, "4K"),       # step 1 only
]


class BackfillQualityTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "catalog.db"
        with sqlite3.connect(self.db) as conn:
            conn.execute("CREATE TABLE releases (id INTEGER PRIMARY KEY,"
                         " quality TEXT)")
            conn.executemany("INSERT INTO releases VALUES (?, ?)", FIXTURE)

    def tearDown(self):
        self.tmp.cleanup()

    def qualities(self):
        with sqlite3.connect(self.db) as conn:
            return dict(conn.execute(
                "SELECT id, quality FROM releases").fetchall())

    def test_dry_run_counts_without_writing(self):
        stats = bq.backfill(self.db, apply=False)
        self.assertEqual(stats["lower_trim"], 5)
        self.assertEqual(stats["hd_to_720p"], 2)
        self.assertEqual(stats["fullhd_to_1080p"], 2)
        self.assertEqual(stats["empty_to_null"], 2)
        self.assertEqual(stats["rows_changed"], 8)
        self.assertEqual(stats["remaining_unnormalized"], 8)
        self.assertEqual(self.qualities(),
                         {i: q for i, q in FIXTURE})

    def test_apply_normalizes_and_is_idempotent(self):
        stats = bq.backfill(self.db, apply=True)
        self.assertEqual(stats["lower_trim"], 5)
        self.assertEqual(stats["hd_to_720p"], 2)
        self.assertEqual(stats["fullhd_to_1080p"], 2)
        self.assertEqual(stats["empty_to_null"], 2)
        self.assertEqual(stats["rows_changed"], 8)
        self.assertEqual(stats["remaining_unnormalized"], 0)
        self.assertEqual(self.qualities(), {
            1: "720p", 2: "720p", 3: "720p", 4: "1080p", 5: "1080p",
            6: None, 7: None, 8: None, 9: "720p", 10: "4k",
        })
        again = bq.backfill(self.db, apply=True)
        self.assertEqual(again["rows_changed"], 0)
        self.assertEqual(again["remaining_unnormalized"], 0)
        self.assertEqual(again["lower_trim"], 0)
        self.assertEqual(again["hd_to_720p"], 0)
        self.assertEqual(again["fullhd_to_1080p"], 0)
        self.assertEqual(again["empty_to_null"], 0)

    def test_missing_quality_column_refuses(self):
        with sqlite3.connect(self.db) as conn:
            conn.execute("DROP TABLE releases")
            conn.execute("CREATE TABLE releases (id INTEGER PRIMARY KEY)")
        with self.assertRaises(ValueError):
            bq.backfill(self.db, apply=False)
        with self.assertRaises(ValueError):
            bq.backfill(self.db, apply=True)


if __name__ == "__main__":
    unittest.main()
