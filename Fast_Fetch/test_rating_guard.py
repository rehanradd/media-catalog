#!/usr/bin/env python3
"""Tests for the A4-F6 rating guard and the Hindi-series classification.

Three write paths are pinned:

* ``title_clean.valid_rating`` — the store guard: anything outside 0–10
  is rejected, never divided by 10. (``normalize_rating`` still converts
  for display; that behaviour is pinned by the 59619eb4 audit tests and
  is deliberately left alone.)
* The projector (``catalog_v2.project``) and the merge
  (``merge_catalogs.merge_site_db``) — a raw "100" / "25" filed on a post
  must never become a work rating or a catalog post rating.
* ``fast_fetch.classify_media`` — "Hindi ... Web Series" is an Indian
  original (Kesariya@100, Dupahiya), while a Hindi-dubbed Hollywood
  movie still files Hollywood.
"""

from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
import unittest
import unittest.mock
from pathlib import Path

os.environ.setdefault("RADD_SKIP_LIVE_ENRICH", "1")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import catalog_v2
import fast_fetch
import merge_catalogs
import title_clean
import viewer


class ValidRatingTests(unittest.TestCase):
    def test_in_range_values_pass_through(self):
        self.assertEqual(title_clean.valid_rating("8.6"), 8.6)
        self.assertEqual(title_clean.valid_rating(7.5), 7.5)
        self.assertEqual(title_clean.valid_rating("10"), 10.0)
        self.assertEqual(title_clean.valid_rating("7.8/10"), 7.8)

    def test_percent_and_vote_counts_are_rejected_not_divided(self):
        # A4-F6: "100" must not become 10.0 and "25" must not become 2.5.
        self.assertIsNone(title_clean.valid_rating("100"))
        self.assertIsNone(title_clean.valid_rating(100.0))
        self.assertIsNone(title_clean.valid_rating("25"))
        self.assertIsNone(title_clean.valid_rating("1000"))

    def test_zero_negative_missing_and_noise_are_rejected(self):
        self.assertIsNone(title_clean.valid_rating("0"))
        self.assertIsNone(title_clean.valid_rating(-1))
        self.assertIsNone(title_clean.valid_rating(None))
        self.assertIsNone(title_clean.valid_rating(""))
        self.assertIsNone(title_clean.valid_rating("abc"))
        self.assertIsNone(title_clean.valid_rating(True))


class ClassifyHindiSeriesTests(unittest.TestCase):
    def test_kesariya_hindi_web_series_is_bollywood(self):
        meta = fast_fetch.classify_media(
            "hdtvb",
            "Kesariya@100 S01 (2025) Hindi Completed Web Series HEVC ESub",
            "kesariya-100-s01-2025-hindi-completed-web-series-hevc-esub",
            "",
        )
        self.assertEqual(meta["industry"], "Bollywood")
        self.assertEqual(meta["country"], "India")
        self.assertEqual(meta["language"], "Hindi")

    def test_dupahiya_hindi_web_series_is_bollywood(self):
        meta = fast_fetch.classify_media(
            "hdtvb",
            "Dupahiya S02 (2026) Hindi Completed Web Series HEVC ESub",
            "dupahiya-s02-2026-hindi-completed-web-series-hevc-esub",
            "",
        )
        self.assertEqual(meta["industry"], "Bollywood")
        self.assertEqual(meta["country"], "India")

    def test_hindi_dubbed_hollywood_movie_stays_hollywood(self):
        meta = fast_fetch.classify_media(
            "hdtvb",
            "Avengers Endgame (2019) Hindi Dubbed BluRay",
            "avengers-endgame-2019-hindi-dubbed-bluray",
            "",
        )
        self.assertEqual(meta["industry"], "Hollywood")
        self.assertEqual(meta["country"], "USA")

    def test_documented_tradeoff_non_indian_hindi_dubbed_series(self):
        # A non-Indian series dubbed in Hindi also reads Bollywood here:
        # the Hindi+series signal cannot tell an original from a dub.
        # Documented, not hidden — the catalog's Hindi-series rows are
        # overwhelmingly Indian originals, and the language reads Hindi
        # either way.
        meta = fast_fetch.classify_media(
            "hdtvb",
            "Wednesday S01 Hindi Dubbed Web Series",
            "wednesday-s01-hindi-dubbed-web-series",
            "",
        )
        self.assertEqual(meta["industry"], "Bollywood")


class ProjectorRatingGuardTests(unittest.TestCase):
    """A raw "100" on a post (the merge path, which bypasses the
    save-time normaliser) must never mint a work rating."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp.name) / "catalog.db"
        self._patches = [
            unittest.mock.patch.object(fast_fetch, "DB_PATH", self.db_path),
            unittest.mock.patch.object(viewer, "DB_PATH", self.db_path),
        ]
        for patcher in self._patches:
            patcher.start()
        fast_fetch.init_db(self.db_path)
        self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row

    def tearDown(self):
        self.conn.close()
        for patcher in self._patches:
            patcher.stop()
        self.tmp.cleanup()

    def _add_post(self, site, url, clean_title, year):
        data = {
            "url": url,
            "slug": url.rstrip("/").split("/")[-1],
            "title": f"{clean_title} ({year})",
            "clean_title": clean_title,
            "year": year,
            "content_type": "series",
            "seasons": None,
            "poster_url": "https://cdn/poster.jpg",
            "backdrop_urls": None,
            "imdb_rating": 7.5,
            "genres": '["Drama"]',
            "synopsis": f"{clean_title} synopsis",
            "is_18_plus": 0,
            "is_animated": 0,
            "source_site": site,
            "industry": "Bollywood",
            "country": "India",
            "language": "Hindi",
            "canonical_key": fast_fetch.generate_canonical_key(clean_title, year),
            "tmdb_id": None,
            "imdb_id": None,
            "releases": [],
        }
        return fast_fetch.save_scraped_data(
            data, enrich_metadata=False, ensure_db=False
        )

    def test_percent_rating_on_post_mints_no_work_rating(self):
        post_id = self._add_post(
            "moviecok",
            "https://moviecok.example/kesariya-100-s01/",
            "Kesariya@100",
            2025,
        )
        # The merge path files the raw staging value, bypassing the
        # save-time normaliser — reproduce that with a direct UPDATE.
        self.conn.execute(
            "UPDATE posts SET imdb_rating = '100' WHERE id = ?", (post_id,)
        )
        self.conn.commit()
        catalog_v2.project(self.conn, rev=1, verbose=False)
        works = self.conn.execute(
            "SELECT imdb_rating FROM cat_works WHERE title = 'Kesariya@100'"
        ).fetchall()
        self.assertEqual(len(works), 1)
        self.assertIsNone(works[0]["imdb_rating"])

    def test_in_range_rating_still_mints(self):
        self._add_post(
            "vegamovies",
            "https://vegamovies.example/salaar-2023/",
            "Salaar",
            2023,
        )
        catalog_v2.project(self.conn, rev=1, verbose=False)
        works = self.conn.execute(
            "SELECT imdb_rating FROM cat_works WHERE title = 'Salaar'"
        ).fetchall()
        self.assertEqual(len(works), 1)
        self.assertEqual(works[0]["imdb_rating"], 7.5)


class MergeRatingGuardTests(unittest.TestCase):
    def _staging_db(self, path, rows):
        conn = sqlite3.connect(path)
        conn.executescript(
            """
            CREATE TABLE posts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                canonical_key TEXT, title TEXT, clean_title TEXT,
                year INTEGER, media_type TEXT, imdb_id TEXT, tmdb_id TEXT,
                poster_url TEXT, backdrop_url TEXT, rating REAL,
                genres TEXT, synopsis TEXT, languages TEXT,
                is_18_plus INTEGER DEFAULT 0, site TEXT, post_url TEXT
            );
            CREATE TABLE releases (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                post_id INTEGER, label TEXT, quality TEXT,
                resolution TEXT, size_bytes INTEGER,
                season_number INTEGER, episode_number INTEGER,
                codec TEXT, bridge_url TEXT, audio_languages TEXT
            );
            CREATE TABLE sources (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                release_id INTEGER, source_name TEXT, service TEXT,
                resource_id TEXT, source_url TEXT, final_url TEXT,
                is_temporary INTEGER, quality TEXT
            );
            """
        )
        for key, title, rating in rows:
            conn.execute(
                "INSERT INTO posts (canonical_key, title, clean_title,"
                " year, media_type, rating, site, post_url)"
                " VALUES (?, ?, ?, 2025, 'series', ?, 'moviecok', ?)",
                (key, title, title, rating,
                 f"https://moviecok.example/{key}/"),
            )
        conn.commit()
        conn.close()

    def test_staging_percent_rating_is_rejected_at_merge(self):
        with tempfile.TemporaryDirectory() as td:
            src_db = Path(td) / "source.db"
            tgt_db = Path(td) / "unified.db"
            self._staging_db(src_db, [
                ("kesariya-100_2025", "Kesariya@100", 100.0),
                ("dupahiya_2025", "Dupahiya", 25.0),
                ("salaar_2023", "Salaar", 7.5),
            ])
            stats = merge_catalogs.merge_site_db(src_db, tgt_db, verbose=False)
            self.assertEqual(stats["posts_merged"], 3)
            with sqlite3.connect(tgt_db) as conn:
                got = dict(conn.execute(
                    "SELECT clean_title, imdb_rating FROM posts"
                ).fetchall())
            self.assertIsNone(got["Kesariya@100"])
            self.assertIsNone(got["Dupahiya"])
            self.assertEqual(float(got["Salaar"]), 7.5)

    def test_unified_percent_rating_is_rejected_at_merge(self):
        with tempfile.TemporaryDirectory() as td:
            src_db = Path(td) / "source.db"
            tgt_db = Path(td) / "unified.db"
            fast_fetch.DB_PATH = src_db
            fast_fetch.init_db()
            post_id = fast_fetch.save_scraped_data(
                {
                    "url": "https://moviecok.example/kesariya-100-s01/",
                    "slug": "kesariya-100-s01",
                    "title": "Kesariya@100 (2025)",
                    "clean_title": "Kesariya@100",
                    "year": 2025,
                    "content_type": "series",
                    "source_site": "moviecok",
                    "releases": [],
                },
                enrich_metadata=False,
                ensure_db=False,
            )
            with fast_fetch.get_db() as conn:
                conn.execute(
                    "UPDATE posts SET imdb_rating = '100' WHERE id = ?",
                    (post_id,),
                )
            stats = merge_catalogs.merge_site_db(src_db, tgt_db, verbose=False)
            self.assertEqual(stats["posts_merged"], 1)
            with sqlite3.connect(tgt_db) as conn:
                row = conn.execute(
                    "SELECT imdb_rating FROM posts WHERE clean_title = ?",
                    ("Kesariya@100",),
                ).fetchone()
            self.assertIsNone(row[0])


if __name__ == "__main__":
    unittest.main()
