#!/usr/bin/env python3
"""Tests for backfill_kesariya_data.py (A4-F6).

The fixture is the exact live shape measured 2026-11-18: 2 work rows and
3 post rows with imdb_rating > 10, the 8837 row filed Hollywood/USA, the
4136 row filed as a movie with year NULL and Unknown industry/country/
language, plus in-range and NULL controls that must never be touched.
"""

from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import backfill_kesariya_data as bk

WORKS_FIXTURE = [
    ("series:kesariya 100:2025", 100.0),   # NULLed
    ("series:dupahiya:2026", 25.0),        # NULLed
    ("movie:salaar:2023", 8.6),            # untouched
    ("series:ok:2024", None),              # untouched
]

# (id, source_site, content_type, year, imdb_rating, industry, country,
#  language)
POSTS_FIXTURE = [
    (131710, "moviecok", "series", 2025, "100", "Web Series", "India", "Hindi"),
    (131992, "moviecok", "series", 2025, "25", "Web Series", "India", "Hindi"),
    (145112, "filmiyin", "series", 2026, "25", "Bollywood", "India", "Hindi"),
    (8837, "hdtvb", "series", 2025, None, "Hollywood", "USA", "Hindi"),
    (4136, "desicinemas", "movie", None, None, "Unknown", "Unknown", "Unknown"),
    (999, "vegamovies", "movie", 2023, "7.5", "Bollywood", "India", "Hindi"),
    (1000, "vegamovies", "movie", 2023, None, "Hollywood", "USA", "English"),
]


class BackfillKesariyaTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "catalog.db"
        with sqlite3.connect(self.db) as conn:
            conn.execute(
                "CREATE TABLE cat_works (work_key TEXT PRIMARY KEY,"
                " imdb_rating REAL)"
            )
            conn.executemany(
                "INSERT INTO cat_works VALUES (?, ?)", WORKS_FIXTURE
            )
            conn.execute(
                "CREATE TABLE posts (id INTEGER PRIMARY KEY,"
                " source_site TEXT, content_type TEXT, year INTEGER,"
                " imdb_rating TEXT, industry TEXT, country TEXT,"
                " language TEXT)"
            )
            conn.executemany(
                "INSERT INTO posts VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                POSTS_FIXTURE,
            )

    def tearDown(self):
        self.tmp.cleanup()

    def _works(self):
        with sqlite3.connect(self.db) as conn:
            return dict(
                conn.execute(
                    "SELECT work_key, imdb_rating FROM cat_works"
                ).fetchall()
            )

    def _posts(self):
        with sqlite3.connect(self.db) as conn:
            return {
                row[0]: row[1:]
                for row in conn.execute(
                    "SELECT id, imdb_rating, industry, country, language,"
                    " content_type, year FROM posts"
                ).fetchall()
            }

    def test_dry_run_counts_without_writing(self):
        stats = bk.backfill(self.db, apply=False)
        self.assertEqual(stats["works_nulled"], 0)
        self.assertEqual(stats["posts_nulled"], 0)
        self.assertEqual(stats["rows_changed"], 0)
        self.assertEqual(stats["remaining_works"], 2)
        self.assertEqual(stats["remaining_posts"], 3)
        self.assertEqual(
            [w["work_key"] for w in stats["plan"]["works"]],
            ["series:kesariya 100:2025", "series:dupahiya:2026"],
        )
        self.assertEqual(
            [p["id"] for p in stats["plan"]["posts"]],
            [131710, 131992, 145112],
        )
        self.assertEqual(self._works(), dict(WORKS_FIXTURE))
        self.assertEqual(
            {i: r[0] for i, r in self._posts().items()},
            {row[0]: row[4] for row in POSTS_FIXTURE},
        )

    def test_apply_nulls_ratings_and_fixes_kesariya_rows(self):
        stats = bk.backfill(self.db, apply=True)
        self.assertEqual(stats["works_nulled"], 2)
        self.assertEqual(stats["posts_nulled"], 3)
        self.assertTrue(stats["post_8837_fixed"])
        self.assertTrue(stats["post_4136_fixed"])
        self.assertEqual(stats["rows_changed"], 7)
        self.assertEqual(stats["remaining_works"], 0)
        self.assertEqual(stats["remaining_posts"], 0)
        self.assertEqual(
            self._works(),
            {
                "series:kesariya 100:2025": None,
                "series:dupahiya:2026": None,
                "movie:salaar:2023": 8.6,
                "series:ok:2024": None,
            },
        )
        posts = self._posts()
        self.assertIsNone(posts[131710][0])
        self.assertIsNone(posts[131992][0])
        self.assertIsNone(posts[145112][0])
        # 8837: industry/country corrected, rating untouched (was NULL).
        self.assertEqual(posts[8837][1], "Bollywood")
        self.assertEqual(posts[8837][2], "India")
        self.assertEqual(posts[8837][3], "Hindi")
        # 4136: kept (not deleted), corrected in place.
        self.assertEqual(posts[4136][4], "series")
        self.assertEqual(posts[4136][5], 2025)
        self.assertEqual(posts[4136][1], "Bollywood")
        self.assertEqual(posts[4136][2], "India")
        self.assertEqual(posts[4136][3], "Hindi")
        # Controls untouched.
        self.assertEqual(posts[999][0], "7.5")
        self.assertEqual(posts[1000][1], "Hollywood")
        self.assertEqual(posts[1000][2], "USA")

    def test_apply_is_idempotent(self):
        first = bk.backfill(self.db, apply=True)
        self.assertEqual(first["rows_changed"], 7)
        again = bk.backfill(self.db, apply=True)
        self.assertEqual(again["works_nulled"], 0)
        self.assertEqual(again["posts_nulled"], 0)
        self.assertFalse(again["post_8837_fixed"])
        self.assertFalse(again["post_4136_fixed"])
        self.assertEqual(again["rows_changed"], 0)
        self.assertEqual(again["remaining_works"], 0)
        self.assertEqual(again["remaining_posts"], 0)


if __name__ == "__main__":
    unittest.main()
