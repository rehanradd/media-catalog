#!/usr/bin/env python3
"""
test_7starhd_import.py — Comprehensive unit tests for 7StarHD / 10xflix dedicated importer.

Tests:
  1. Release link parsing: quality (1080p, 720p, 480p, 4K), size, codec, MobileJSR URL.
  2. Post metadata extraction: clean title, year, audio/language, poster, synopsis, canonical key.
  3. MobileJSR session negotiation & CSRF unlock integration.
  4. WP REST API pagination and sitemap_cache population.
  5. Staging SQLite database isolation and atomic persistence.

100% Python Standard Library. Zero external dependencies.
"""

import json
import os
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import fast_fetch
import import_7starhd


SAMPLE_7STARHD_HTML = """
<!DOCTYPE html>
<html>
<head>
    <title>Pattas (2020) Hindi ORG Dual Audio South Movie HDRip | 1080p | 720p | 480p | ESubs - 7StarHD</title>
    <meta name="description" content="Pattas 2020 Hindi Dual Audio South Movie watch online and download in 1080p, 720p and 480p." />
    <meta property="og:image" content="https://img.7starhd.rich/posters/2020/pattas.jpg" />
</head>
<body>
    <div class="entry-content">
        <p style="text-align: center;"><img src="https://img.7starhd.rich/posters/2020/pattas-main.jpg" alt="Pattas" /></p>
        <p><strong>Storyline:</strong> A petty thief learns about his past and seeks justice.</p>
        <hr />
        <p style="text-align: center;">
            <a href="https://mobilejsr.com/view/sdq9ZKg54N" target="_blank" class="btn btn-primary">1080p [2.48GB]</a>
        </p>
        <p style="text-align: center;">
            <a href="https://mobilejsr.com/view/YYppC1M8Hk" target="_blank" class="btn btn-primary">720p [1.40GB]</a>
        </p>
        <p style="text-align: center;">
            <a href="https://mobilejsr.com/view/AF3zsDkznt" target="_blank" class="btn btn-primary">480p [481MB]</a>
        </p>
    </div>
</body>
</html>
"""


# WP REST adoption (2026-11-23): live posts from
# 7starhd.surgery/wp-json/wp/v2/posts?_embed=1, fetched 2026-11-23.
# Transcribed faithfully (info block, first img, MobileJSR buttons).
# The adult fixture omits the explicit Plot text deliberately — the test
# covers classification (genres / category / title), not synopsis.
SAMPLE_7STARHD_REST_POST = {
    "id": 85549,
    "date": "2026-11-22T10:00:00",
    "modified": "2026-11-22T10:30:00",
    "slug": "indian-idol-legends-returns-2026-hindi-org-s017e12-series-hdrip-1080p-720p-480p",
    "link": "https://7starhd.surgery/indian-idol-legends-returns-2026-hindi-org-s017e12-series-hdrip-1080p-720p-480p/",
    "title": {"rendered": "Indian Idol: Legends Returns (2026) Hindi ORG S017E12 Series HDRip | 1080p | 720p | 480p"},
    "content": {"rendered": """
<p><img src="https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEh0TH0fG6vzpjTPkdHOBrzhP-40YegOZL3OooU92ezUcq1JG2ud02fB6EutFnGUyabPXWa_0clk0qhJYnyJbuEa5TbOb-a6SAKv2oUy31lCdbWiYOdS-t3qsx-7Ot5tIu-7/s1600/Indian%20Idol%20Legends%20Returns%20(2026).jpg" /></p>
<p>Indian Idol: Legends Returns (2026) Hindi ORG S017E12 Series HDRip | 1080p | 720p | 480p Title: Indian Idol: Legends Returns (2026) IMDB Ratings: 5.1/10 Released Date: 22nd November 2026 Genres: Reality Show Languages: Hindi Director: Saahil Chhabaria Host: Aditya Narayan Judges: Himesh Reshammiya Quality: 1080p | 720p | 480p | HDRip Plot: Indian Idol – Legends Return features a unique two-phase journey. Phase 1 features Himesh Reshammiya and 24 former contestants competing in duets evaluated by peer scores and a secret judge score, forming a winning jodi. This pair becomes mentors in Phase 2, guiding fresh aspirants through auditions alongside the returning judges. Screenshots Download Links</p>
<p><a href="https://mobilejsr.com/view/TRHdNOcLc5">1080p [1.47GB]</a></p>
<p><a href="https://mobilejsr.com/view/msCzKcMmy3">720p [786MB]</a></p>
<p><a href="https://mobilejsr.com/view/7SLxB3KGeg">480p [350MB]</a></p>
"""},
    "excerpt": {"rendered": "<p>Indian Idol: Legends Returns (2026) Hindi ORG S017E12 Series HDRip | 1080p | 720p | 480p Title: Indian Idol: Legends Returns (2026) IMDB Ratings: 5.1/10 Released Date: 22nd November 2026 Genres: Reality Show Languages: Hindi Director: Saahil Chhabaria Host: Aditya Narayan Judges: Himesh Reshammiya Quality: 1080p | 720p | 480p | HDRip Plot: Indian Idol […]</p>"},
    "categories": [5],
    "featured_media": 0,
    "_embedded": {
        "wp:term": [[{"id": 5, "name": "TV Shows", "taxonomy": "category"}]],
    },
}

SAMPLE_7STARHD_ADULT_REST_POST = {
    "id": 85553,
    "date": "2026-11-22T09:00:00",
    "modified": "2026-11-22T09:15:00",
    "slug": "18-keep-on-suckin-2026-brazzers-originals-english-adult-video-720p-hdrip",
    "link": "https://7starhd.surgery/18-keep-on-suckin-2026-brazzers-originals-english-adult-video-720p-hdrip/",
    "title": {"rendered": "[18+] Keep On Suckin (2026) Brazzers Originals English Adult Video 720p HDRip"},
    "content": {"rendered": """
<p><img src="https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEinSXRPoBjfejubFAwzBGWVTDT-cpItCtb4-d1oFkbJ45Epk5OslYAhQIfbfYm2eQ6cp1hDw-X3zPDW68b4pANpo7pax5lcvKQQTIPGLx1tFHoY8dtSOswldSu_QmsqjeWPMOBxd_nX4j2KQGD2QU_cG18K2aotCiLNWjtRjCEt6t4EVBSjO2wkfaHlt94/s1600/Keep%20On%20Suckin.webp" /></p>
<p>[18+] Keep On Suckin (2026) Brazzers Originals English Adult Video 720p HDRip Title: Keep On Suckin (2026) Released Date: November 22, 2026 Genres: Erotic, Adult Languages: English Stars: Cyber Doll, Ace Hardz Quality: 720p | HDRip Screenshots Download Links</p>
<p><a href="https://mobilejsr.com/view/aeDTOkFGca">Click to view Screenshoot</a></p>
<p><a href="https://mobilejsr.com/view/TMjqcBgYh5">720p [242MB]</a></p>
"""},
    "excerpt": {"rendered": "<p>[18+] Keep On Suckin (2026) Brazzers Originals English Adult Video 720p HDRip Title: Keep On Suckin (2026) Released Date: November 22, 2026 Genres: Erotic, Adult Languages: English Stars: Cyber Doll, Ace Hardz Quality: 720p | HDRip Plot: […]</p>"},
    "categories": [9],
    "featured_media": 0,
    "_embedded": {
        "wp:term": [[{"id": 9, "name": "18+ Adult", "taxonomy": "category"}]],
    },
}


class TestSevenStarHDImport(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test_7starhd.db"
        fast_fetch.DB_PATH = self.db_path
        fast_fetch.init_db()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_parse_release_links(self):
        releases = import_7starhd.parse_7starhd_release_links(SAMPLE_7STARHD_HTML)
        self.assertEqual(len(releases), 3)

        r_1080p = releases[0]
        self.assertEqual(r_1080p["quality"], "1080p")
        self.assertEqual(r_1080p["size"], "2.48GB")
        self.assertEqual(r_1080p["codec"], "x264")
        self.assertEqual(r_1080p["jsr_url"], "https://mobilejsr.com/view/sdq9ZKg54N")

        r_720p = releases[1]
        self.assertEqual(r_720p["quality"], "720p")
        self.assertEqual(r_720p["size"], "1.40GB")
        self.assertEqual(r_720p["jsr_url"], "https://mobilejsr.com/view/YYppC1M8Hk")

        r_480p = releases[2]
        self.assertEqual(r_480p["quality"], "480p")
        self.assertEqual(r_480p["size"], "481MB")
        self.assertEqual(r_480p["jsr_url"], "https://mobilejsr.com/view/AF3zsDkznt")

    def test_extract_post_metadata(self):
        data = import_7starhd.extract_post_data_7starhd(
            post_url="https://7starhd.rich/pattas-2020-hindi-org-dual-audio-south-movie-hdrip-1080p-720p-480p-esubs/",
            page_html=SAMPLE_7STARHD_HTML,
            site_name="7starhd",
            resolve_direct=False,
        )
        self.assertIsNotNone(data)
        self.assertEqual(data["clean_title"], "Pattas")
        self.assertEqual(data["year"], 2020)
        self.assertEqual(data["source_site"], "7starhd")
        # WP REST adoption: no genre/rating labels in this page, so neither
        # is fabricated — the old code banked ["Action", "Drama"] here.
        self.assertIsNone(data["genres"])
        self.assertIsNone(data["imdb_rating"])
        self.assertEqual(data["is_18_plus"], 0)
        self.assertEqual(len(data["releases"]), 3)

        # Check releases structure
        r0 = data["releases"][0]
        self.assertIn("1080P", r0["release_title"])
        self.assertEqual(r0["quality"], "1080p")
        self.assertEqual(r0["size"], "2.48GB")
        self.assertEqual(len(r0["sources"]), 1)
        self.assertEqual(r0["sources"][0]["service"], "mobilejsr")

    def test_mobilejsr_unwrapping_mock(self):
        mock_html = """
        <html>
        <body>
            <a href="https://hubcloud.ist/video/test12345">HubCloud Direct</a>
            <a href="https://1fichier.com/?testfile1fichier">1fichier Stream</a>
            <a href="https://new2.filepress.lat/file/abcdef">Filepress Drive</a>
        </body>
        </html>
        """
        with patch.object(fast_fetch, "http_get", return_value=(200, "https://mobilejsr.com/view/test", mock_html)):
            with patch.object(fast_fetch, "unwrap_hubcloud_drive", return_value=[{
                "source_name": "⚡ HubCloud [FSL Cloudflare R2]",
                "service": "fsl_r2",
                "resource_id": "test12345",
                "source_url": "https://hubcloud.ist/video/test12345",
                "final_url": "https://pub-r2.dev/video.mp4",
                "is_temporary": 1,
            }]):
                with patch.object(fast_fetch, "unwrap_filepress", return_value=("https://drive.google.com/uc?id=abc", "abcdef", 0)):
                    sources = fast_fetch.unwrap_mobilejsr("https://mobilejsr.com/view/test")
                    self.assertGreaterEqual(len(sources), 3)
                    services = [s["service"] for s in sources]
                    self.assertIn("fsl_r2", services)
                    self.assertIn("1fichier", services)
                    self.assertIn("filepress", services)

    def test_sync_api_to_db(self):
        mock_posts = [
            {"link": "https://7starhd.rich/movie-one-2026/", "slug": "movie-one-2026", "modified": "2026-10-18T10:00:00"},
            {"link": "https://7starhd.rich/movie-two-2026/", "slug": "movie-two-2026", "modified": "2026-10-18T11:00:00"},
        ]
        client = import_7starhd.SevenStarHDClient()
        with patch.object(client, "get_json", return_value=(200, mock_posts, {})):
            total = import_7starhd.sync_api_to_db(client, self.db_path, max_pages=1, site_name="7starhd")
            self.assertEqual(total, 2)

            with sqlite3.connect(str(self.db_path)) as conn:
                c = conn.cursor()
                c.execute("SELECT count(*) FROM sitemap_cache WHERE site = '7starhd' AND scraped = 0")
                self.assertEqual(c.fetchone()[0], 2)

    def test_scrape_worker_saves_to_db(self):
        client = import_7starhd.SevenStarHDClient()
        import_7starhd.init_staging_db(self.db_path)
        with sqlite3.connect(str(self.db_path)) as conn:
            conn.execute(
                "INSERT INTO sitemap_cache (loc, slug, scraped, site) VALUES (?, ?, 0, '7starhd')",
                ("https://7starhd.rich/pattas-2020/", "pattas-2020"),
            )
            conn.commit()

        with patch.object(client, "get_text", return_value=(200, "https://7starhd.rich/pattas-2020/", SAMPLE_7STARHD_HTML)):
            ok, rels, srcs = import_7starhd.scrape_7starhd_worker(
                "https://7starhd.rich/pattas-2020/",
                client,
                site_name="7starhd",
                resolve_direct=False,
            )
            self.assertTrue(ok)
            self.assertEqual(rels, 3)
            self.assertEqual(srcs, 3)

            stats = import_7starhd.get_db_stats(self.db_path, site_name="7starhd")
            self.assertEqual(stats["posts"], 1)
            self.assertEqual(stats["releases"], 3)
            self.assertEqual(stats["sources"], 3)
            self.assertEqual(stats["sitemap_scraped"], 1)
            self.assertEqual(stats["sitemap_pending"], 0)



class TestSevenStarHDRestAdoption(unittest.TestCase):
    """WP REST adoption (2026-11-23): extract from the REST post itself,
    sync stores raw_json, worker never fetches when raw_json is present."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test_7starhd.db"
        fast_fetch.DB_PATH = self.db_path
        fast_fetch.init_db()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_extract_from_rest_post_indian_idol(self):
        data = import_7starhd.extract_post_data_7starhd(
            post_url=SAMPLE_7STARHD_REST_POST["link"],
            page_html="",
            site_name="7starhd",
            resolve_direct=False,
            rest_post=SAMPLE_7STARHD_REST_POST,
        )
        self.assertIsNotNone(data)
        self.assertEqual(data["clean_title"], "Indian Idol: Legends Returns")
        self.assertEqual(data["year"], 2026)
        self.assertEqual(data["content_type"], "series")
        self.assertEqual(data["language"], "Hindi")
        self.assertEqual(
            data["poster_url"],
            "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEh0TH0fG6vzpjTPkdHOBrzhP-40YegOZL3OooU92ezUcq1JG2ud02fB6EutFnGUyabPXWa_0clk0qhJYnyJbuEa5TbOb-a6SAKv2oUy31lCdbWiYOdS-t3qsx-7Ot5tIu-7/s1600/Indian%20Idol%20Legends%20Returns%20(2026).jpg")
        # Synopsis is the Plot: label — never the excerpt (which is the
        # same metadata dump as the info block).
        self.assertTrue(data["synopsis"].startswith(
            "Indian Idol – Legends Return features a unique two-phase journey."))
        self.assertNotIn("Released Date", data["synopsis"])
        self.assertEqual(json.loads(data["genres"]), ["Reality Show"])
        self.assertEqual(data["imdb_rating"], 5.1)
        self.assertEqual(data["director"], "Saahil Chhabaria")
        self.assertEqual(data["release_date"], "22nd November 2026")
        # Judges + Host are on-screen talent — they join the cast.
        self.assertIn("Himesh Reshammiya", data["cast_names"])
        self.assertIn("Aditya Narayan", data["cast_names"])
        self.assertEqual(data["is_18_plus"], 0)
        self.assertEqual(len(data["releases"]), 3)
        self.assertEqual([r["quality"] for r in data["releases"]],
                         ["1080p", "720p", "480p"])
        self.assertEqual(data["releases"][0]["sources"][0]["service"], "mobilejsr")

    def test_adult_post_flagged_18_plus(self):
        data = import_7starhd.extract_post_data_7starhd(
            post_url=SAMPLE_7STARHD_ADULT_REST_POST["link"],
            page_html="",
            site_name="7starhd",
            resolve_direct=False,
            rest_post=SAMPLE_7STARHD_ADULT_REST_POST,
        )
        self.assertIsNotNone(data)
        self.assertEqual(data["clean_title"], "[18+] Keep On Suckin")
        self.assertEqual(data["year"], 2026)
        self.assertEqual(data["language"], "English")
        self.assertEqual(json.loads(data["genres"]), ["Erotic", "Adult"])
        self.assertEqual(data["is_18_plus"], 1)
        self.assertIsNone(data["imdb_rating"])
        self.assertEqual(data["release_date"], "November 22, 2026")
        self.assertIn("Cyber Doll", data["cast_names"])
        self.assertIn("Ace Hardz", data["cast_names"])
        # The screenshot anchor is skipped; only the 720p button remains.
        self.assertEqual(len(data["releases"]), 1)
        self.assertEqual(data["releases"][0]["quality"], "720p")

    def test_sync_stores_raw_json_and_requests_embed(self):
        client = import_7starhd.SevenStarHDClient()
        with patch.object(client, "get_json",
                          return_value=(200, [SAMPLE_7STARHD_REST_POST], {})) as mock_json:
            total = import_7starhd.sync_api_to_db(
                client, self.db_path, max_pages=1, site_name="7starhd")
            self.assertEqual(total, 1)
            self.assertIn("_embed=1", mock_json.call_args.args[0])
        with sqlite3.connect(str(self.db_path)) as conn:
            conn.row_factory = sqlite3.Row
            row = conn.execute("SELECT loc, slug, raw_json FROM sitemap_cache").fetchone()
            self.assertIsNotNone(row)
            self.assertEqual(row["loc"], SAMPLE_7STARHD_REST_POST["link"])
            stored = json.loads(row["raw_json"])
            self.assertEqual(stored["id"], 85549)
            self.assertEqual(stored["title"]["rendered"],
                             SAMPLE_7STARHD_REST_POST["title"]["rendered"])

    def test_worker_uses_raw_json_without_fetch(self):
        client = import_7starhd.SevenStarHDClient()
        import_7starhd.init_staging_db(self.db_path)
        import_7starhd._ensure_sitemap_raw_json(self.db_path)
        test_url = SAMPLE_7STARHD_REST_POST["link"]
        with sqlite3.connect(str(self.db_path)) as conn:
            conn.execute(
                "INSERT INTO sitemap_cache (loc, slug, scraped, site, raw_json) VALUES (?, ?, 0, '7starhd', ?)",
                (test_url, "indian-idol-legends-returns-2026-hindi-org-s017e12-series-hdrip-1080p-720p-480p",
                 json.dumps(SAMPLE_7STARHD_REST_POST)),
            )
            conn.commit()
        with patch.object(client, "get_text") as mock_text:
            mock_text.side_effect = AssertionError("page fetch must not run with raw_json")
            ok, rels, srcs = import_7starhd.scrape_7starhd_worker(
                test_url, client, site_name="7starhd", resolve_direct=False,
                raw_json_str=json.dumps(SAMPLE_7STARHD_REST_POST))
            self.assertTrue(ok)
            self.assertEqual(rels, 3)
        with sqlite3.connect(str(self.db_path)) as conn:
            p_row = conn.execute(
                "SELECT imdb_rating, genres, cast_names, director, is_18_plus FROM posts "
                "WHERE clean_title = 'Indian Idol: Legends Returns'").fetchone()
            self.assertIsNotNone(p_row)
            self.assertEqual(float(p_row[0]), 5.1)
            self.assertEqual(json.loads(p_row[1]), ["Reality Show"])
            self.assertIn("Himesh Reshammiya", p_row[2])
            self.assertEqual(p_row[3], "Saahil Chhabaria")
            self.assertEqual(p_row[4], 0)
            self.assertEqual(
                conn.execute("SELECT scraped FROM sitemap_cache WHERE loc = ?", (test_url,)).fetchone()[0], 1)


class Session104SevenStarHDTests(unittest.TestCase):
    """Session 104: screenshot anchors skipped; one bridge URL keeps every tier."""

    def test_screenshot_skipped_and_tiers_kept(self):
        html = (
            '<p><a href="https://mobilejsr.com/view/AAA111">1080p [2.02GB]</a>'
            '<a href="https://mobilejsr.com/view/AAA111">720p [1.10GB]</a>'
            '<a href="https://mobilejsr.com/view/AAA111">720p [1.10GB]</a>'
            '<a href="https://mobilejsr.com/view/BBB222">Watch Online - Screenshot</a></p>'
        )
        rels = import_7starhd.parse_7starhd_release_links(html)
        self.assertEqual([r["quality"] for r in rels], ["1080p", "720p"])


if __name__ == "__main__":
    unittest.main()
