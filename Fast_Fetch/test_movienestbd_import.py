#!/usr/bin/env python3
"""
test_movienestbd_import.py — Unit tests for the MovieNestBD importer (Session 118).

Tests:
  1. Movie detail: one movie post, watch URL as the single source.
  2. Series detail: series post, season from the slug, one source per
     links[] quality (qualities normalized) + the main watch URL.
  3. Adult flag: is_adult from the API is the 18+ authority.
  4. Quality normalization + season-from-slug helpers.
  5. No sources -> None.
  6. Save path: a saved title in a temp staging DB.
  7. Search sync: mocked search pages -> sitemap_cache rows.
  8. Registry: the movienestbd entry exists with the API endpoint.

100% Python Standard Library. Zero external dependencies. Offline — temp SQLite
files only, no network, no catalog read. Fixture watch URLs use the reserved
.test TLD; they exercise the mapping, never a real embed.
"""

import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock

import fast_fetch
import import_movienestbd

SAMPLE_MN_MOVIE_DETAIL = {
    "id": 17836,
    "slug": "ramayana-rise-of-a-legend",
    "title": "Ramayana: Rise of a Legend",
    "poster": "https://m.media-amazon.com/images/M/poster.jpg",
    "cover": "https://m.media-amazon.com/images/M/cover.jpg",
    "year": 2026,
    "quality": "PreDvD",
    "rating": 8.9,
    "description": "An ancient epic follows a young prince.",
    "casts": "Sai Pallavi, Ranbir Kapoor, Yash",
    "director": "Nitesh Tiwari",
    "is_series": False,
    "series_status": "",
    "is_adult": False,
    "is_trending": False,
    "is_pinned": True,
    "watch": "https://player.jiofiles.test/6aedc51e55672f6276c39b21",
    "notes": "Best Quality",
    "categories": [{"id": 2, "name": "Bollywood", "slug": "bollywood"}],
    "genres": [
        {"id": 1, "name": "Adventure", "slug": "adventure"},
        {"id": 17, "name": "Action", "slug": "action"},
    ],
    "episodes": None,
    "links": [],
}

SAMPLE_MN_SERIES_DETAIL = {
    "id": 6835,
    "slug": "legends-of-the-ramayana-with-amish-s1",
    "title": "Legends of the Ramayana with Amish S1",
    "poster": "https://m.media-amazon.com/images/M/poster2.jpg",
    "cover": "https://m.media-amazon.com/images/M/poster2.jpg",
    "year": 2022,
    "quality": "WEB-DL",
    "rating": 9.1,
    "description": "A story that has lived in hearts for generations.",
    "casts": "Rahul Kumar, Amish Tripathi",
    "director": "N/A",
    "is_series": True,
    "series_status": "",
    "is_adult": False,
    "watch": "https://embed.jiofiles.test/68e0ac13bc336a08aa362ba6",
    "categories": [{"id": 7, "name": "Web Series", "slug": "web-series"}],
    "genres": [{"id": 9, "name": "Documentary", "slug": "documentary"}],
    "episodes": None,
    "links": [
        {"id": 18689, "link": "https://embed.jiofiles.test/68e0ac12bc336a08aa362ba5",
         "quality_id": 4, "quality": {"id": 4, "name": "480P"}, "movie_id": 6835},
        {"id": 18690, "link": "https://embed.jiofiles.test/68e0ac13bc336a08aa362ba7",
         "quality_id": 5, "quality": {"id": 5, "name": "720P"}, "movie_id": 6835},
    ],
}


#: Live shape (2026-11-23, /api/v1/search?q=syrup): the API returns
#: year 0 and rating 0 for this row, and `casts` smashed together with
#: no separator at all. The detail endpoint for the same slug DOES
#: return a links[] entry even though search shows links null.
SAMPLE_SYRUP_DETAIL = {
    "id": 17005,
    "slug": "syrup",
    "title": "Syrup",
    "poster": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcS0LM_g6rWefOIipkXU67kAxos2Ee7jM4MP77P9SQmU9w&s=10",
    "cover": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcS0LM_g6rWefOIipkXU67kAxos2Ee7jM4MP77P9SQmU9w&s=10",
    "year": 0,
    "quality": "WEB-DL",
    "rating": 0,
    "description": "A slacker hatches a million-dollar idea.",
    "casts": "Amber HeardShiloh FernandezKellan Lutz",
    "director": "Aram Rappaport",
    "is_series": False,
    "series_status": "",
    "release_schedule": "",
    "release_day": "",
    "is_adult": False,
    "is_trending": False,
    "is_pinned": False,
    "watch": "",
    "notes": "Esub",
    "categories": [{"id": 1, "name": "Hollywood", "slug": "hollywood"}],
    "genres": None,
    "episodes": None,
    "links": [{"id": 62975,
               "link": "https://embed.jiofiles.test/6a991a1adc782d20c2a61535",
               "quality": {"id": 2, "name": "1080P"}}],
}


class TestMovieNestBDExtract(unittest.TestCase):
    def test_cast_director_rating_are_saved(self):
        # Before the fields fix these were read into locals and dropped.
        data = import_movienestbd.extract_title_data_movienestbd(SAMPLE_MN_MOVIE_DETAIL)
        self.assertEqual(data["cast_names"], "Sai Pallavi, Ranbir Kapoor, Yash")
        self.assertEqual(data["director"], "Nitesh Tiwari")
        self.assertEqual(data["imdb_rating"], "8.9")

    def test_smashed_casts_are_split_without_breaking_single_names(self):
        self.assertEqual(
            import_movienestbd.split_cast_names(
                "Amber HeardShiloh FernandezKellan Lutz"),
            ["Amber Heard", "Shiloh Fernandez", "Kellan Lutz"])
        self.assertEqual(
            import_movienestbd.split_cast_names(
                "Ibrahim Risyad, Anggika Bolsterli, Messi Gusti"),
            ["Ibrahim Risyad", "Anggika Bolsterli", "Messi Gusti"])
        # A single word is never split, so McDonald/DiCaprio survive.
        self.assertEqual(import_movienestbd.split_cast_names("McDonald"),
                         ["McDonald"])
        self.assertEqual(import_movienestbd.split_cast_names("DiCaprio"),
                         ["DiCaprio"])
        self.assertEqual(import_movienestbd.split_cast_names("N/A"), [])
        self.assertEqual(import_movienestbd.split_cast_names(None), [])

    def test_series_schedule_fields_land_in_raw_meta(self):
        detail = dict(SAMPLE_MN_SERIES_DETAIL, series_status="Ongoing",
                      release_schedule="Weekly", release_day="Friday",
                      is_trending=True)
        data = import_movienestbd.extract_title_data_movienestbd(detail)
        self.assertEqual(json.loads(data["raw_meta"]), {
            "is_series": True, "series_status": "Ongoing",
            "release_schedule": "Weekly", "release_day": "Friday",
            "is_trending": True})
        # The movie fixture has no series fields and no trending flag, so
        # only its pinned flag and notes survive.
        movie = import_movienestbd.extract_title_data_movienestbd(
            SAMPLE_MN_MOVIE_DETAIL)
        self.assertEqual(json.loads(movie["raw_meta"]),
                         {"is_pinned": True, "notes": "Best Quality"})

    def test_na_director_is_not_saved(self):
        # The series fixture carries director "N/A" — a placeholder, not a person.
        data = import_movienestbd.extract_title_data_movienestbd(SAMPLE_MN_SERIES_DETAIL)
        self.assertIsNone(data["director"])

    def test_zero_rating_is_not_saved(self):
        data = import_movienestbd.extract_title_data_movienestbd(SAMPLE_SYRUP_DETAIL)
        self.assertIsNotNone(data)
        self.assertIsNone(data["imdb_rating"])
        self.assertEqual(data["cast_names"],
                         "Amber Heard, Shiloh Fernandez, Kellan Lutz")
        self.assertEqual(data["director"], "Aram Rappaport")

    def test_movie_detail_maps_to_post(self):
        data = import_movienestbd.extract_title_data_movienestbd(SAMPLE_MN_MOVIE_DETAIL)
        self.assertIsNotNone(data)
        self.assertEqual(data["source_site"], "movienestbd")
        self.assertEqual(data["content_type"], "movie")
        self.assertEqual(data["clean_title"], "Ramayana: Rise of a Legend")
        self.assertEqual(data["year"], 2026)
        self.assertEqual(data["poster_url"], "https://m.media-amazon.com/images/M/poster.jpg")
        self.assertEqual(data["is_18_plus"], 0)
        self.assertEqual(len(data["releases"]), 1)
        rel = data["releases"][0]
        self.assertEqual(rel["quality"], "PreDvD")
        self.assertEqual(len(rel["sources"]), 1)
        src = rel["sources"][0]
        self.assertEqual(src["service"], "jiofiles")
        self.assertEqual(src["playback_type"], "url")
        self.assertEqual(src["final_url"], "https://player.jiofiles.test/6aedc51e55672f6276c39b21")

    def test_series_detail_maps_season_and_links(self):
        data = import_movienestbd.extract_title_data_movienestbd(SAMPLE_MN_SERIES_DETAIL)
        self.assertIsNotNone(data)
        self.assertEqual(data["content_type"], "series")
        self.assertEqual(data["seasons"], "S01")
        rel = data["releases"][0]
        self.assertEqual(rel["season"], "S01")
        # 2 links[] entries + the main watch URL (distinct) = 3 sources.
        self.assertEqual(len(rel["sources"]), 3)
        for src in rel["sources"]:
            self.assertEqual(src["service"], "jiofiles")
            self.assertEqual(src["playback_type"], "url")
        self.assertIn("Documentary", json.loads(data["genres"]))
        self.assertIn("Web Series", json.loads(data["genres"]))

    def test_adult_flag_from_api(self):
        detail = dict(SAMPLE_MN_MOVIE_DETAIL, is_adult=True)
        data = import_movienestbd.extract_title_data_movienestbd(detail)
        self.assertIsNotNone(data)
        self.assertEqual(data["is_18_plus"], 1)

    def test_normalize_quality(self):
        self.assertEqual(import_movienestbd.normalize_quality("480P"), "480p")
        self.assertEqual(import_movienestbd.normalize_quality("1080p"), "1080p")
        self.assertEqual(import_movienestbd.normalize_quality("4K"), "2160p")
        self.assertEqual(import_movienestbd.normalize_quality("PreDvD"), "PreDvD")
        self.assertEqual(import_movienestbd.normalize_quality(None), "unknown")

    def test_season_from_slug(self):
        self.assertEqual(import_movienestbd.season_from_slug("legends-of-the-ramayana-with-amish-s1"), "S01")
        self.assertEqual(import_movienestbd.season_from_slug("show-s12"), "S12")
        self.assertIsNone(import_movienestbd.season_from_slug("ramayana-rise-of-a-legend"))

    def test_no_sources_is_none(self):
        detail = dict(SAMPLE_MN_MOVIE_DETAIL, watch="", links=[])
        self.assertIsNone(import_movienestbd.extract_title_data_movienestbd(detail))
        self.assertIsNone(import_movienestbd.extract_title_data_movienestbd({}))
        self.assertIsNone(import_movienestbd.extract_title_data_movienestbd({"slug": "x"}))


class TestMovieNestBDSave(unittest.TestCase):
    def test_saved_title_in_staging_db(self):
        data = import_movienestbd.extract_title_data_movienestbd(SAMPLE_MN_SERIES_DETAIL)
        self.assertIsNotNone(data)
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "movienestbd.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            post_id = import_movienestbd.save_post_record(data, db_path)
            self.assertGreater(post_id, 0)
            with sqlite3.connect(str(db_path)) as conn:
                conn.row_factory = sqlite3.Row
                row = conn.execute(
                    "SELECT source_site, content_type, is_18_plus FROM posts WHERE id = ?", (post_id,)
                ).fetchone()
                n_src = conn.execute("SELECT count(*) FROM download_sources").fetchone()[0]
            self.assertEqual(row["source_site"], "movienestbd")
            self.assertEqual(row["content_type"], "series")
            self.assertEqual(row["is_18_plus"], 0)
            self.assertEqual(n_src, 3)

    def test_syrup_row_saves_split_cast_and_drops_zero_year_rating(self):
        # The API genuinely returns year 0 / rating 0 for Syrup — the save
        # path drops both, but the smashed casts still split and land.
        data = import_movienestbd.extract_title_data_movienestbd(SAMPLE_SYRUP_DETAIL)
        self.assertIsNotNone(data)
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "movienestbd.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            post_id = import_movienestbd.save_post_record(data, db_path)
            with sqlite3.connect(str(db_path)) as conn:
                conn.row_factory = sqlite3.Row
                row = conn.execute(
                    "SELECT year, cast_names, director, imdb_rating, raw_meta"
                    " FROM posts WHERE id = ?", (post_id,)).fetchone()
            self.assertIsNone(row["year"])
            self.assertIsNone(row["imdb_rating"])
            self.assertEqual(row["cast_names"],
                             "Amber Heard, Shiloh Fernandez, Kellan Lutz")
            self.assertEqual(row["director"], "Aram Rappaport")
            self.assertEqual(json.loads(row["raw_meta"]), {"notes": "Esub"})


class TestMovieNestBDSync(unittest.TestCase):
    def test_sync_sweeps_search_pages(self):
        page1 = {
            "success": True, "count": 2, "page": 1, "total_pages": 2,
            "has_next": True, "has_prev": False,
            "data": [{"slug": "ramayana-rise-of-a-legend", "title": "Ramayana: Rise of a Legend", "year": 2026}],
        }
        page2 = {
            "success": True, "count": 2, "page": 2, "total_pages": 2,
            "has_next": False, "has_prev": True,
            "data": [{"slug": "legends-of-the-ramayana-with-amish-s1", "title": "Legends...", "year": 2022}],
        }
        client = MagicMock()
        client.base_url = "https://movienestbd.best"
        client.search_page.side_effect = lambda q, page=1: page1 if page == 1 else page2
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "movienestbd.db"
            n = import_movienestbd.sync_search_to_db(client, db_path, letters="a")
            self.assertEqual(n, 2)
            with sqlite3.connect(str(db_path)) as conn:
                rows = conn.execute(
                    "SELECT loc, site FROM sitemap_cache WHERE site = 'movienestbd'"
                ).fetchall()
            self.assertEqual(len(rows), 2)
            self.assertTrue(all(r[0].startswith("https://movienestbd.best/api/v1/movie/") for r in rows))


class TestMovieNestBDRegistry(unittest.TestCase):
    def test_registry_entry_points_at_live_api(self):
        reg_path = Path(__file__).parent / "domain_registry.json"
        reg = json.loads(reg_path.read_text())
        entry = reg["sites"]["movienestbd"]
        self.assertEqual(entry["active_base_url"], "https://movienestbd.best")
        self.assertEqual(entry["api_type"], "json_api")
        self.assertEqual(entry["api_endpoint"], "/api/v1/search")
        self.assertEqual(entry["status"], "active")


if __name__ == "__main__":
    unittest.main(verbosity=2)
