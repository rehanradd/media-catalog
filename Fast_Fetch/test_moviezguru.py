#!/usr/bin/env python3
"""
test_moviezguru.py — Offline Unit Tests for MoviezGuru Importer & Google CDN Resolver
"""

import json
import os
import sqlite3
import tempfile
import unittest
from pathlib import Path

import import_moviezguru
from import_moviezguru import (
    clean_moviezguru_title,
    init_staging_db,
    is_adult_content,
    parse_moviezguru_content,
    parse_size_string,
    save_post_to_staging,
    scrape_post_item,
    sync_api_catalog,
)


class TestMoviezGuruImporter(unittest.TestCase):
    def test_clean_moviezguru_title_movie(self):
        raw = "Romanchakam (2026) Full Movie NETFLIX ORG. Hindi Dubbed WEBRip ESubs 720p 480p Download"
        title, year, season, mtype, key = clean_moviezguru_title(raw)
        self.assertIn("Romanchakam", title)
        self.assertEqual(year, 2026)
        self.assertIsNone(season)
        self.assertEqual(mtype, "movie")
        self.assertEqual(key, "romanchakam-2026")

    def test_clean_moviezguru_title_series(self):
        raw = "Mirzapur S03 (2024) Hindi Complete Web Series 720p HDRip"
        title, year, season, mtype, key = clean_moviezguru_title(raw)
        self.assertIn("Mirzapur", title)
        self.assertEqual(year, 2024)
        self.assertEqual(season, 3)
        self.assertEqual(mtype, "series")
        self.assertIn("s3", key)

    def test_parse_size_string(self):
        self.assertEqual(parse_size_string("450 MB"), int(450 * 1024**2))
        self.assertEqual(parse_size_string("1.3 GB"), int(1.3 * 1024**3))
        self.assertIsNone(parse_size_string("invalid"))
        self.assertIsNone(parse_size_string(None))

    def test_adult_filter(self):
        self.assertTrue(is_adult_content("Hotshots 18+ Sensual Web Series"))
        self.assertTrue(is_adult_content("Ullu Erotic Hindi Movie"))
        self.assertFalse(is_adult_content("Romanchakam (2026) Full Movie"))

    def test_parse_moviezguru_content(self):
        sample_html = """
        <p><img src="https://moviezguru.guru/wp-content/uploads/2026/10/poster.jpg" /></p>
        <p>IMDb: <a href="https://www.imdb.com/title/tt2527338/">tt2527338</a></p>
        <p>Romanchakam (2026) – [ORG. Hindi Dubbed] – 480p WEBRip (450 MB): <a href="https://mzgserver.shop/file/PAR67eDVwySa8vF">Download</a></p>
        <p>Romanchakam (2026) – [ORG. Hindi Dubbed] – 720p WEBRip (1.3 GB): <a href="https://mzgserver.shop/file/onI5f0F8nrURb7G">Download</a></p>
        """
        imdb_id, poster, releases = parse_moviezguru_content(
            sample_html, "Romanchakam (2026) 720p"
        )
        self.assertEqual(imdb_id, "tt2527338")
        self.assertIsNotNone(poster)
        self.assertEqual(len(releases), 2)

        q_list = [r["quality"] for r in releases]
        self.assertIn("480P", q_list)
        self.assertIn("720P", q_list)

        r720 = [r for r in releases if r["quality"] == "720P"][0]
        self.assertEqual(r720["bridge_url"], "https://mzgserver.shop/file/onI5f0F8nrURb7G")
        self.assertEqual(r720["size_bytes"], int(1.3 * 1024**3))

    def test_db_init_and_save(self):
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as tf:
            db_path = Path(tf.name)

        try:
            init_staging_db(db_path)

            with sqlite3.connect(str(db_path)) as conn:
                conn.execute(
                    "INSERT INTO sitemap_cache (loc, slug, scraped) VALUES (?, ?, 0);",
                    ("https://moviezguru.guru/test-movie/", "test-movie"),
                )

            res = {
                "loc": "https://moviezguru.guru/test-movie/",
                "success": True,
                "post": {
                    "canonical_key": "test-movie-2026",
                    "title": "Test Movie",
                    "original_title": "Test Movie 2026",
                    "year": 2026,
                    "media_type": "movie",
                    "imdb_id": "tt1234567",
                    "poster_url": "https://moviezguru.guru/poster.jpg",
                    "synopsis": "A great test movie.",
                    "genres": "Action",
                    "site": "moviezguru",
                    "post_url": "https://moviezguru.guru/test-movie/",
                },
                "releases": [
                    {
                        "label": "720P WEBRip 1.3 GB",
                        "quality": "720P",
                        "resolution": "720p",
                        "size_bytes": 1395864371,
                        "codec": "x264",
                        "season_number": None,
                        "episode_number": None,
                        "audio_languages": "Hindi",
                        "bridge_url": "https://mzgserver.shop/file/testfile",
                        "sources": [
                            {
                                "source_name": "⚡ Google CDN 10Gbps Direct",
                                "service": "google_drive",
                                "resource_id": "1fbNEX328pgWXE2W3vj83550DYSNaindJ",
                                "source_url": "https://drive.usercontent.google.com/download?id=1fbNEX328pgWXE2W3vj83550DYSNaindJ&export=download",
                                "final_url": "https://drive.usercontent.google.com/download?id=1fbNEX328pgWXE2W3vj83550DYSNaindJ&export=download",
                                "is_temporary": 0,
                            }
                        ],
                    }
                ],
            }

            save_post_to_staging(db_path, res)

            with sqlite3.connect(str(db_path)) as conn:
                cur = conn.cursor()
                p_row = cur.execute(
                    "SELECT title, year, imdb_id FROM posts WHERE canonical_key = 'test-movie-2026';"
                ).fetchone()
                self.assertIsNotNone(p_row)
                self.assertEqual(p_row[0], "Test Movie")
                self.assertEqual(p_row[1], 2026)

                r_row = cur.execute(
                    "SELECT quality, bridge_url FROM releases WHERE post_canonical_key = 'test-movie-2026';"
                ).fetchone()
                self.assertIsNotNone(r_row)
                self.assertEqual(r_row[0], "720P")

                s_row = cur.execute(
                    "SELECT source_name, service FROM sources;"
                ).fetchone()
                self.assertIsNotNone(s_row)
                self.assertEqual(s_row[1], "google_drive")

                scraped = cur.execute(
                    "SELECT scraped FROM sitemap_cache WHERE loc = 'https://moviezguru.guru/test-movie/';"
                ).fetchone()[0]
                self.assertEqual(scraped, 1)

        finally:
            if db_path.exists():
                os.remove(db_path)


# --- WP REST adoption tests (2026-11-23) -----------------------------------
# Live moviezguru fixture: post 34631 "The Odyssey (2026) ...", fetched
# from the Oracle VM 2026-11-23. The "Details:" block carries Genres:,
# Cast:, Quality:, Size:, Language:, Subtitles: and a Storyline: label;
# the excerpt is SEO prose (never a synopsis); featured media carries the
# poster; categories carry real genres plus industry/quality buckets.

ODYSSEY_POSTER = (
    "https://moviezguru.host/wp-content/uploads/2026/11/"
    "The-Odyssey-2026-Full-Movie-IMAX-Original-Dual-Audio-Hindi-English-"
    "WEBRip-ESubs-1080p-720p-480p-HEVC-Download.jpg")

ODYSSEY_POST = {
    "id": 34631,
    "slug": "the-odyssey-2026-full-movie-imax-org-dual-audio-hindi-english-bluray-esubs-1080p-720p-480p-hevc-download",
    "link": "https://moviezguru.host/the-odyssey-2026-full-movie-imax-org-dual-audio-hindi-english-bluray-esubs-1080p-720p-480p-hevc-download/",
    "title": {"rendered": "The Odyssey (2026) Full Movie IMAX ORG. Dual Audio "
              "[Hindi-English] BluRay ESubs 1080p 720p 480p HEVC Download"},
    "content": {"rendered": """
        <p>The Odyssey 2026 Movie is based on Adventure. This Movie is available in Hindi Dubbed and 1080p, 720p, 480p qualities of Original IMAX BluRay &amp; Original AAC-5.1 Audio.</p>
        <p><img src="https://moviezguru.host/wp-content/uploads/2026/11/The-Odyssey-2026-Full-Movie-IMAX-Original-Dual-Audio-Hindi-English-WEBRip-ESubs-1080p-720p-480p-HEVC-Download.jpg" /></p>
        <p>The Odyssey (2026) Full Movie in Hindi Dubbed Download ~ [MoviezGuru] Details: Name: The Odyssey (2026) Genres: Action, Adventure, Fantasy Cast: Matt Damon, Tom Holland, Anne Hathaway Quality: IMAX Original BluRay &amp; AAC-5.1 Audio Size: 1080p | 720p | 480p Language: ORG. Dual Audio (Hindi, English) Subtitles: English Subtitles Storyline: Odysseus, the legendary King of Ithaca, embarks on a long and perilous journey home following the Trojan War. Throughout his voyage, he is forced to confront the whims of gods, mythological monsters, and trials that stretch both his cunning and his humanity to the breaking point. Screenshots:</p>
        <p>The Odyssey (2026) \u2013 ORG. [Hindi + English] \u2013 480p IMAX BluRay (650 MB): <a href="https://mzgserver.test/file/odyssey480">Download</a></p>
        <p>The Odyssey (2026) \u2013 ORG. [Hindi + English] \u2013 720p \u201cHEVC 10bit\u201d IMAX BluRay (1.2 GB): <a href="https://mzgserver.test/file/odyssey720">Download</a></p>
        <p>The Odyssey (2026) \u2013 ORG. [Hindi + English] \u2013 1080p IMAX BluRay AAC-5.1 (3.5 GB): <a href="https://mzgserver.test/file/odyssey1080">Download</a></p>
    """},
    "excerpt": {"rendered": "<p>The Odyssey 2026 Movie is based on Adventure. This Movie is available in Hindi Dubbed and 1080p, 720p, 480p qualities of Original IMAX BluRay &amp; Original AAC-5.1 Audio. MoviezGuru is a Free Downloading and Online Streaming Website. [\u2026]</p>"},
    "_embedded": {
        "wp:featuredmedia": [{
            "id": 34634,
            "source_url": ODYSSEY_POSTER,
            "media_type": "image",
            "mime_type": "image/jpeg",
        }],
        "wp:term": [[
            {"id": 1, "name": "1080p Movies", "taxonomy": "category"},
            {"id": 2, "name": "2026", "taxonomy": "category"},
            {"id": 3, "name": "Action", "taxonomy": "category"},
            {"id": 4, "name": "Adventure", "taxonomy": "category"},
            {"id": 5, "name": "Drama", "taxonomy": "category"},
            {"id": 6, "name": "Dual Audio", "taxonomy": "category"},
            {"id": 7, "name": "Fantasy", "taxonomy": "category"},
            {"id": 8, "name": "Hindi Dubbed", "taxonomy": "category"},
            {"id": 9, "name": "Hollywood", "taxonomy": "category"},
        ]],
    },
    "date": "2026-11-20T10:00:00",
}


class TestMoviezguruRestScrape(unittest.TestCase):
    def test_scrape_post_item_full_metadata(self):
        item = (ODYSSEY_POST["link"], None, ODYSSEY_POST["slug"],
                json.dumps(ODYSSEY_POST, ensure_ascii=False))
        res = scrape_post_item(item, None, unwrap_bridges=False)
        self.assertIsNotNone(res)
        self.assertTrue(res["success"])
        post = res["post"]
        self.assertEqual(post["poster_url"], ODYSSEY_POSTER)
        # Synopsis is the Storyline: label, never the SEO excerpt.
        self.assertTrue(post["synopsis"].startswith(
            "Odysseus, the legendary King of Ithaca"))
        self.assertNotIn("Screenshots", post["synopsis"])
        self.assertNotIn("MoviezGuru is a Free Downloading",
                         post["synopsis"])
        self.assertEqual(json.loads(post["genres"]),
                         ["Action", "Adventure", "Fantasy"])
        self.assertEqual(post["cast_names"],
                         "Matt Damon, Tom Holland, Anne Hathaway")
        self.assertEqual(len(res["releases"]), 3)
        by_quality = {r["quality"]: r for r in res["releases"]}
        self.assertEqual(set(by_quality), {"480P", "720P", "1080P"})
        self.assertEqual(by_quality["480P"]["size_bytes"],
                         int(650 * 1024 ** 2))
        self.assertEqual(by_quality["1080P"]["bridge_url"],
                         "https://mzgserver.test/file/odyssey1080")
        # unwrap_bridges=False keeps the gateway as the source.
        self.assertEqual(
            by_quality["480P"]["sources"][0]["service"], "mzgserver")


class TestMoviezguruSaveBackfill(unittest.TestCase):
    def _res(self, synopsis, genres, cast_names):
        return {
            "loc": "https://moviezguru.host/the-odyssey-2026/",
            "success": True,
            "post": {
                "canonical_key": "the-odyssey-2026",
                "title": "The Odyssey",
                "original_title": "The Odyssey (2026)",
                "year": 2026,
                "media_type": "movie",
                "imdb_id": None,
                "poster_url": ODYSSEY_POSTER,
                "synopsis": synopsis,
                "genres": genres,
                "cast_names": cast_names,
                "site": "moviezguru",
                "post_url": "https://moviezguru.host/the-odyssey-2026/",
            },
            "releases": [],
        }

    def test_resave_backfills_synopsis_genres_cast(self):
        # Regression: the upsert never touched synopsis/genres, so a
        # re-save could never fill the NULLs the old scraper banked.
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviezguru.db"
            import_moviezguru.init_staging_db(db_path)
            with sqlite3.connect(str(db_path)) as conn:
                conn.execute(
                    "INSERT INTO sitemap_cache (loc, slug, scraped) "
                    "VALUES (?, ?, 0);",
                    ("https://moviezguru.host/the-odyssey-2026/",
                     "the-odyssey-2026"),
                )
            save_post_to_staging(
                db_path, self._res(None, None, None))
            save_post_to_staging(
                db_path, self._res("Odysseus sails home.",
                                   '["Action", "Adventure"]',
                                   "Matt Damon, Tom Holland"))
            with sqlite3.connect(str(db_path)) as conn:
                row = conn.execute(
                    "SELECT synopsis, genres, cast_names FROM posts "
                    "WHERE canonical_key = 'the-odyssey-2026'"
                ).fetchone()
            self.assertEqual(row[0], "Odysseus sails home.")
            self.assertEqual(json.loads(row[1]), ["Action", "Adventure"])
            self.assertEqual(row[2], "Matt Damon, Tom Holland")
            # A later NULL re-save keeps the banked values.
            save_post_to_staging(
                db_path, self._res(None, None, None))
            with sqlite3.connect(str(db_path)) as conn:
                row = conn.execute(
                    "SELECT synopsis, genres, cast_names FROM posts "
                    "WHERE canonical_key = 'the-odyssey-2026'"
                ).fetchone()
            self.assertEqual(row[0], "Odysseus sails home.")


class TestMoviezguruSyncEmbed(unittest.TestCase):
    def test_sync_requests_embed(self):
        seen = {}

        class FakeClient:
            def get_json(self, url, timeout=15.0):
                seen["url"] = url
                post = {"link": "https://moviezguru.host/test/",
                        "modified": "2026-11-20T00:00:00", "slug": "test"}
                return 200, [post], {"x-wp-total": "1",
                                     "x-wp-totalpages": "1"}

        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviezguru.db"
            n = sync_api_catalog(FakeClient(), db_path, max_pages=1)
            self.assertEqual(n, 1)
            self.assertIn("_embed=1", seen["url"])
            with sqlite3.connect(str(db_path)) as conn:
                raw = conn.execute(
                    "SELECT raw_json FROM sitemap_cache").fetchone()[0]
            self.assertEqual(json.loads(raw)["slug"], "test")


if __name__ == "__main__":
    unittest.main()
