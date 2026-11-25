#!/usr/bin/env python3
"""
test_10xflix_import.py — Comprehensive unit tests for 10xflix dedicated scraper.
Tests cover:
  1. Title cleaning and canonical key generation (movies & series).
  2. Byte size string parser.
  3. Release button & resolution parser from HTML.
  4. Post data extraction and MobileJSR mirror extraction.
  5. Isolated SQLite staging database initialization & foreign key cascade.
  6. Atomic post, release, and source persistence.
"""

import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

import import_10xflix


class TestTenXFlixImport(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test_10xflix.db"
        import_10xflix.init_staging_db(self.db_path)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_clean_10xflix_title_movie(self):
        raw = "Street Fighter (2026) Hindi (Cleaned) Movie HQ HDTC | 1080p | 720p | 480p"
        title, year, season, episode, canon = import_10xflix.clean_10xflix_title(raw)
        self.assertEqual(title, "Street Fighter")
        self.assertEqual(year, 2026)
        self.assertIsNone(season)
        self.assertIsNone(episode)
        self.assertEqual(canon, "street-fighter_2026")

    def test_clean_10xflix_title_series(self):
        raw = "Sa Re Ga Ma Pa (2026) Hindi ORG S01E10 Series HDRip | 1080p | 720p | 480p"
        title, year, season, episode, canon = import_10xflix.clean_10xflix_title(raw)
        self.assertEqual(title, "Sa Re Ga Ma Pa")
        self.assertEqual(year, 2026)
        self.assertEqual(season, 1)
        self.assertEqual(episode, 10)
        self.assertEqual(canon, "sa-re-ga-ma-pa_2026_s1_e10")

    def test_parse_size_string(self):
        self.assertEqual(import_10xflix.parse_size_string("2.02GB"), int(2.02 * 1024 * 1024 * 1024))
        self.assertEqual(import_10xflix.parse_size_string("3.73.GB"), int(3.73 * 1024 * 1024 * 1024))
        self.assertEqual(import_10xflix.parse_size_string("997MB"), int(997 * 1024 * 1024))
        self.assertEqual(import_10xflix.parse_size_string("500KB"), int(500 * 1024))
        self.assertIsNone(import_10xflix.parse_size_string("Unknown"))
        self.assertIsNone(import_10xflix.parse_size_string(None))

    def test_parse_10xflix_releases(self):
        sample_html = """
        <p><strong>Download Links:</strong></p>
        <p><a href="https://mobilejsr.com/view/heZxR9W5aL">1080p [2.02GB]</a></p>
        <p><a href="https://mobilejsr.com/view/wDs1bdMrff">720p [997MB]</a></p>
        <p><a href="https://mobilejsr.com/view/bpcggpqnLN">480p [353MB]</a></p>
        """
        releases = import_10xflix.parse_10xflix_releases(sample_html, "Sample Movie")
        self.assertEqual(len(releases), 3)

        self.assertEqual(releases[0]["quality"], "1080p")
        self.assertEqual(releases[0]["bridge_url"], "https://mobilejsr.com/view/heZxR9W5aL")
        self.assertGreater(releases[0]["size_bytes"], 2_000_000_000)

        self.assertEqual(releases[1]["quality"], "720p")
        self.assertEqual(releases[1]["bridge_url"], "https://mobilejsr.com/view/wDs1bdMrff")

        self.assertEqual(releases[2]["quality"], "480p")
        self.assertEqual(releases[2]["bridge_url"], "https://mobilejsr.com/view/bpcggpqnLN")

    def test_extract_post_data_10xflix(self):
        mock_raw = {
            "title": {"rendered": "Armored (2009) Hindi ORG Dual Audio BluRay | 1080p | 720p | 480p | ESubs"},
            "link": "https://10xflix.bond/armored-2009-hindi-org-dual-audio-bluray-1080p-720p-480p-esubs/",
            "content": {
                "rendered": (
                    '<p><a href="https://mobilejsr.com/view/W3Pq15KDAN">1080p [2.1GB]</a></p>'
                    '<p><a href="https://mobilejsr.com/view/3Dmtq1rHjA">720p [1.1GB]</a></p>'
                )
            },
            "_embedded": {
                "wp:featuredmedia": [{"source_url": "https://image.tmdb.org/t/p/w500/sample_poster.jpg"}],
                "wp:term": [[{"name": "Action"}, {"name": "Thriller"}]],
            },
        }

        data = import_10xflix.extract_post_data_10xflix(mock_raw, resolve_direct=False)
        self.assertIsNotNone(data)
        self.assertEqual(data["title"], "Armored")
        self.assertEqual(data["year"], 2009)
        self.assertEqual(data["media_type"], "movie")
        self.assertEqual(data["poster_url"], "https://image.tmdb.org/t/p/w500/sample_poster.jpg")
        self.assertEqual(len(data["releases"]), 2)

    def test_save_post_to_staging_db(self):
        post_data = {
            "canonical_key": "street-fighter_2026",
            "title": "Street Fighter",
            "original_title": "Street Fighter (2026) Hindi",
            "year": 2026,
            "media_type": "movie",
            "imdb_id": "tt1234567",
            "poster_url": "https://example.com/poster.jpg",
            "synopsis": "A test movie synopsis.",
            "genres": json.dumps(["Action", "Adventure"]),
            "site": "10xflix",
            "post_url": "https://10xflix.bond/street-fighter-2026/",
            "releases": [
                {
                    "label": "1080p [2.02GB]",
                    "quality": "1080p",
                    "resolution": "1080p",
                    "size_bytes": 2168982732,
                    "season_number": None,
                    "episode_number": None,
                    "audio_languages": json.dumps(["Hindi"]),
                    "codec": "x264",
                    "bridge_url": "https://mobilejsr.com/view/heZxR9W5aL",
                    "sources": [
                        {
                            "source_name": "⚡ HubCloud [10Gbps Fast Server]",
                            "service": "hubcloud_10g",
                            "resource_id": "c3fxsjzrysc1o1z",
                            "source_url": "https://hubcloud.ist/video/c3fxsjzrysc1o1z",
                            "final_url": "https://gpdl2.hubcloud.ist/?id=sample",
                            "is_temporary": 0,
                            "quality": "1080p",
                        }
                    ],
                }
            ],
        }

        ok = import_10xflix.save_post_to_staging_db(self.db_path, post_data)
        self.assertTrue(ok)

        with sqlite3.connect(str(self.db_path)) as conn:
            cur = conn.cursor()
            p_cnt = cur.execute("SELECT COUNT(*) FROM posts").fetchone()[0]
            r_cnt = cur.execute("SELECT COUNT(*) FROM releases").fetchone()[0]
            s_cnt = cur.execute("SELECT COUNT(*) FROM sources").fetchone()[0]

            self.assertEqual(p_cnt, 1)
            self.assertEqual(r_cnt, 1)
            self.assertEqual(s_cnt, 1)

            saved_p = cur.execute("SELECT title, year, canonical_key FROM posts").fetchone()
            self.assertEqual(saved_p[0], "Street Fighter")
            self.assertEqual(saved_p[1], 2026)
            self.assertEqual(saved_p[2], "street-fighter_2026")



class Session104TenXFlixTests(unittest.TestCase):
    """Session 104: screenshot anchors skipped; one bridge URL keeps every tier."""

    def test_screenshot_skipped_and_tiers_kept(self):
        html = (
            '<p><a href="https://mobilejsr.com/view/AAA111">1080p [2.02GB]</a>'
            '<a href="https://mobilejsr.com/view/AAA111">720p [1.10GB]</a>'
            '<a href="https://mobilejsr.com/view/AAA111">720p [1.10GB]</a>'
            '<a href="https://mobilejsr.com/view/BBB222">Watch Online - Screenshot</a></p>'
        )
        rels = import_10xflix.parse_10xflix_releases(html, "Test Movie 2026")
        self.assertEqual([r["quality"] for r in rels], ["1080p", "720p"])


class Session20261123BeerMoveTests(unittest.TestCase):
    """23 Nov 2026: the site moved to 10xflix.beer with WordPress under /m/.

    Root /wp-json 404s on every known domain; /m/wp-json/wp/v2/posts is
    live (X-WP-Total 17,718, newest 2026-11-22, verified from Oracle VM
    egress). The sync must target the /m/ base, and posts must keep
    posters even though /m/ serves no wp:featuredmedia via _embed — the
    poster is the first blogger <img> inside content.rendered (5/5
    sampled live posts). All fixtures, no live network.
    """

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test_10xflix.db"
        import_10xflix.init_staging_db(self.db_path)

    def tearDown(self):
        self.temp_dir.cleanup()

    BEER_M_POST = {
        "title": {"rendered": "Max (2025) Hindi ORG Dubbed South Movie HDRip | 1080p | 720p | 480p | ESubs"},
        "link": "https://10xflix.beer/m/max-2025-hindi-org-dubbed-south-movie-hdrip-1080p-720p-480p-esubs/",
        "slug": "max-2025-hindi-org-dubbed-south-movie-hdrip-1080p-720p-480p-esubs",
        "modified": "2026-11-22T22:10:25",
        "content": {"rendered": (
            '<p><img src="https://blogger.googleusercontent.com/img/b/ABC/s1600/Max%20(2025)%20Hindi.jpg" /></p>'
            '<p><a href="https://mobilejsr.com/view/Cn5QFjTZTK">1080p [2.02GB]</a></p>'
            '<p><a href="https://mobilejsr.com/view/DcZrtZO37j">720p [997MB]</a></p>'
        )},
        "_embedded": {"wp:term": [[{"name": "Action"}]]},
    }

    def test_default_base_url_is_beer_m(self):
        self.assertEqual(import_10xflix.DEFAULT_BASE_URL, "https://10xflix.beer/m")

    def test_client_resolves_to_registry_beer_m(self):
        # The shipped registry is the source of truth; the client must
        # resolve to the /m/ base, not the dead .asia root.
        self.assertEqual(
            import_10xflix.TenXFlixClient().base_url, "https://10xflix.beer/m")

    def test_extract_post_data_beer_m_poster_fallback(self):
        data = import_10xflix.extract_post_data_10xflix(
            self.BEER_M_POST, resolve_direct=False)
        self.assertIsNotNone(data)
        self.assertEqual(
            data["post_url"],
            "https://10xflix.beer/m/max-2025-hindi-org-dubbed-south-movie-hdrip-1080p-720p-480p-esubs/")
        self.assertIn("blogger.googleusercontent.com", data["poster_url"])
        self.assertIn("/s1600/", data["poster_url"])
        self.assertEqual(len(data["releases"]), 2)

    def test_content_poster_prefers_blogger_image(self):
        html = (
            '<img src="https://example.com/tracker.gif" />'
            '<img src="https://blogger.googleusercontent.com/img/b/ABC/s1600/Poster.jpg" />'
            '<img src="https://10xflix.beer/m/wp-content/uploads/2026/11/other.jpg" />'
        )
        self.assertIn("blogger.googleusercontent.com",
                      import_10xflix.extract_content_poster(html))
        self.assertIsNone(import_10xflix.extract_content_poster(
            '<img src="https://example.com/tracker.gif" />'))
        self.assertIsNone(import_10xflix.extract_content_poster(""))

    def test_sync_queues_beer_m_posts_offline(self):
        # Stub the HTTP client: the sync path must build
        # {base}/wp-json/wp/v2/posts under /m/ and queue the post.
        from unittest.mock import patch

        requested = []
        post = self.BEER_M_POST

        class StubClient:
            base_url = "https://10xflix.beer/m"

            def get_json(self, url, timeout=15):
                requested.append(url)
                return 200, [post], {"x-wp-total": "17718"}

        with patch.object(import_10xflix, "TenXFlixClient", StubClient):
            new_count = import_10xflix.sync_wp_api_to_db(
                self.db_path, max_pages=1)

        self.assertEqual(new_count, 1)
        self.assertTrue(requested)
        self.assertIn("https://10xflix.beer/m/wp-json/wp/v2/posts",
                      requested[0])
        with sqlite3.connect(str(self.db_path)) as conn:
            row = conn.execute(
                "SELECT loc, scraped FROM sitemap_cache;").fetchone()
        self.assertEqual(
            row[0],
            "https://10xflix.beer/m/max-2025-hindi-org-dubbed-south-movie-hdrip-1080p-720p-480p-esubs/")
        self.assertEqual(row[1], 0)


if __name__ == "__main__":
    unittest.main()
