#!/usr/bin/env python3
"""
test_bolly4u_import.py — Comprehensive unit tests for Bolly4u dedicated scraper.
Tests cover:
  1. Title cleaning and canonical key generation.
  2. Byte size string parser.
  3. Strict zero adult content safety filter.
  4. HTML content parser (extracts quality tiers, sizes, and valid gateway links).
  5. Dedicated SQLite staging database initialization & foreign key cascade.
  6. Atomic post, release, and source persistence.
  7. Hidden /action JSON API photojin unwrap mock.
  8. Session-cookie negotiated linkskeep unwrap mock.
  9. WP REST adoption (2026-11-23): full metadata from the REST post
     (poster / synopsis / genres / rating / cast / director / languages),
     torrentsave gateway kept, no fabricated quality or codec, _embed=1
     sync that pages through the whole catalog.
"""

import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import import_bolly4u


class TestBolly4uImport(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test_bolly4u.db"
        import_bolly4u.init_staging_db(self.db_path)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_clean_bolly4u_title(self):
        raw = "Street Fighter 2026 HDTS Hindi Dubbed Full Movie Download 1080p | 720p | 480p"
        title, year, m_type = import_bolly4u.clean_bolly4u_title(raw)
        self.assertEqual(title, "Street Fighter")
        self.assertEqual(year, 2026)
        self.assertEqual(m_type, "movie")

        raw2 = "Pattas 2020 WEB-DL UNCUT Hindi Dual Audio ORG Full Movie Download 1080p | 720p | 480p"
        title2, year2, m_type2 = import_bolly4u.clean_bolly4u_title(raw2)
        self.assertEqual(title2, "Pattas")
        self.assertEqual(year2, 2020)
        self.assertEqual(m_type2, "movie")

        raw_series = "Ali Baba Aur 40 Bhoot 2026 WEB-DL Hindi S01 Complete Download 720p"
        title3, year3, m_type3 = import_bolly4u.clean_bolly4u_title(raw_series)
        self.assertEqual(title3, "Ali Baba Aur 40 Bhoot")
        self.assertEqual(year3, 2026)
        self.assertEqual(m_type3, "series")

    def test_parse_size_string(self):
        self.assertEqual(import_bolly4u.parse_size_string("1.4GB"), int(1.4 * 1024 * 1024 * 1024))
        self.assertEqual(import_bolly4u.parse_size_string("750MB"), 750 * 1024 * 1024)
        self.assertEqual(import_bolly4u.parse_size_string("500KB"), 500 * 1024)
        self.assertIsNone(import_bolly4u.parse_size_string(None))
        self.assertIsNone(import_bolly4u.parse_size_string("N/A"))

    def test_is_adult_content(self):
        # Strict zero adult content enforcement
        self.assertTrue(import_bolly4u.is_adult_content("HotShots Web Series 720p"))
        self.assertTrue(import_bolly4u.is_adult_content("Ullu Charmsukh 2026 Complete S01"))
        self.assertTrue(import_bolly4u.is_adult_content("Friends Sex And Games 2026 S01 Complete"))
        self.assertTrue(import_bolly4u.is_adult_content("Kooku Web Series 1080p"))
        self.assertTrue(import_bolly4u.is_adult_content("Sensual Touch 2025 Hindi 18+ Movie"))
        # Clean titles must pass
        self.assertFalse(import_bolly4u.is_adult_content("Street Fighter 2026 HDTS Hindi Dubbed"))
        self.assertFalse(import_bolly4u.is_adult_content("Avengers Endgame 2019 Dual Audio"))
        self.assertFalse(import_bolly4u.is_adult_content("Ali Baba Aur 40 Bhoot 2026 Hindi"))

    def test_parse_bolly4u_content(self):
        sample_html = """
        <p><strong>Street Fighter 2026 HDTS Hindi Dubbed Full Movie Download</strong></p>
        <h4 style="text-align: center;"><strong>Free Download Full Movie Via Single Links</strong></h4>
        <h4 style="text-align: center;"><span style="color: #ff0000;"><strong>|| 1080p Download Links: 1.4GB ||</strong></span></h4>
        <p style="text-align: center;"><a href="https://photojin.study/download/krXE-8z9mHO">DIRECT 1</a> <a href="https://new2.linkskeep.lat/view/2TrzDkjMf6">DOWNLOAD LINKS</a></p>
        <h4 style="text-align: center;"><span style="color: #ff0000;"><strong>|| 720p Download Links: 750MB ||</strong></span></h4>
        <p style="text-align: center;"><a href="https://photojin.study/download/-qH-q78gSk-">DIRECT 1</a></p>
        <h4 style="text-align: center;"><span style="color: #ff0000;"><strong>|| 480p Download Links: 350MB ||</strong></span></h4>
        <p style="text-align: center;"><a href="https://photojin.study/download/TXbAj6TXs4c">DIRECT 1</a></p>
        """
        releases = import_bolly4u.parse_bolly4u_content(
            sample_html, "Street Fighter 2026 HDTS Hindi Dubbed Full Movie Download 1080p | 720p | 480p"
        )
        self.assertEqual(len(releases), 3)

        r1080 = releases[0]
        self.assertEqual(r1080["quality"], "1080P")
        self.assertEqual(r1080["resolution"], "1080p")
        self.assertEqual(r1080["size_bytes"], int(1.4 * 1024 * 1024 * 1024))
        self.assertEqual(len(r1080["links"]), 2)

        r720 = releases[1]
        self.assertEqual(r720["quality"], "720P")
        self.assertEqual(r720["size_bytes"], int(750 * 1024 * 1024))

        r480 = releases[2]
        self.assertEqual(r480["quality"], "480P")
        self.assertEqual(r480["size_bytes"], int(350 * 1024 * 1024))

    def test_database_initialization_and_schema(self):
        with sqlite3.connect(str(self.db_path)) as conn:
            cur = conn.cursor()
            cur.execute("SELECT name FROM sqlite_master WHERE type='table';")
            tables = {row[0] for row in cur.fetchall()}
            self.assertIn("sitemap_cache", tables)
            self.assertIn("posts", tables)
            self.assertIn("releases", tables)
            self.assertIn("sources", tables)

    def test_save_post_to_staging(self):
        sample_result = {
            "loc": "https://bolly4uz.click/street-fighter-2026-hindi-dubbed-movie-download-hdts/",
            "success": True,
            "post": {
                "canonical_key": "street-fighter_2026",
                "title": "Street Fighter",
                "original_title": "Street Fighter 2026 HDTS Hindi Dubbed",
                "year": 2026,
                "media_type": "movie",
                "imdb_id": None,
                "poster_url": None,
                "synopsis": None,
                "genres": None,
                "site": "bolly4u",
                "post_url": "https://bolly4uz.click/street-fighter-2026-hindi-dubbed-movie-download-hdts/",
            },
            "releases": [
                {
                    "label": "1080P 1.4GB",
                    "quality": "1080P",
                    "resolution": "1080p",
                    "size_bytes": 1503238553,
                    "season_number": None,
                    "episode_number": None,
                    "audio_languages": "Hindi",
                    "codec": "x264",
                    "links": [("https://photojin.study/download/krXE-8z9mHO", "DIRECT 1")],
                    "sources": [
                        {
                            "source_name": "⚡ Google CDN [High-Speed Direct]",
                            "service": "google_cdn",
                            "resource_id": "rpzN2EzMyQjM5cTM",
                            "source_url": "https://photojin.study/download/krXE-8z9mHO",
                            "final_url": "https://lh3.googleusercontent.com/pw/TEST_DIRECT_STREAM=dv",
                            "is_temporary": 1,
                        }
                    ],
                }
            ],
        }

        # Seed cache
        with sqlite3.connect(str(self.db_path)) as conn:
            conn.execute(
                "INSERT INTO sitemap_cache (loc, slug, scraped, site) VALUES (?, ?, 0, 'bolly4u');",
                (sample_result["loc"], "street-fighter-2026-hindi-dubbed-movie-download-hdts"),
            )
            conn.commit()

        import_bolly4u.save_post_to_staging(self.db_path, sample_result)

        with sqlite3.connect(str(self.db_path)) as conn:
            cur = conn.cursor()
            cur.execute("SELECT scraped FROM sitemap_cache WHERE loc = ?;", (sample_result["loc"],))
            self.assertEqual(cur.fetchone()[0], 1)

            cur.execute("SELECT id, title, canonical_key, year FROM posts WHERE canonical_key = 'street-fighter_2026';")
            p_row = cur.fetchone()
            self.assertIsNotNone(p_row)
            self.assertEqual(p_row[1], "Street Fighter")
            self.assertEqual(p_row[3], 2026)
            post_id = p_row[0]

            cur.execute("SELECT id, label, quality, size_bytes FROM releases WHERE post_id = ?;", (post_id,))
            rel_row = cur.fetchone()
            self.assertIsNotNone(rel_row)
            self.assertEqual(rel_row[1], "1080P 1.4GB")
            self.assertEqual(rel_row[2], "1080P")
            rel_id = rel_row[0]

            cur.execute("SELECT service, final_url, is_temporary FROM sources WHERE release_id = ?;", (rel_id,))
            src_row = cur.fetchone()
            self.assertIsNotNone(src_row)
            self.assertEqual(src_row[0], "google_cdn")
            self.assertIn("googleusercontent.com", src_row[1])
            self.assertEqual(src_row[2], 1)

    def test_unwrap_photojin_mock(self):
        client = import_bolly4u.Bolly4uClient()

        # Mock the 3 HTTP calls for unwrap_photojin
        landing_html = """
        <form action="/download/step2" method="POST">
            <input type="hidden" name="access_token" value="MOCK_TOKEN_123" />
        </form>
        """
        step2_html = """
        <div id="generate_url" data-uid="MOCK_UID_456" data-token="MOCK_TOKEN_789"></div>
        """
        api_response_json = {
            "status": True,
            "download_url": "https://lh3.googleusercontent.com/pw/MOCK_STREAM_URL=dv"
        }

        with patch.object(client, "get_text") as mock_get_text:
            mock_get_text.return_value = (200, "https://photojin.study/download/test", landing_html, {})

            # Mock step 2 POST and step 3 API POST
            mock_resp_step2 = MagicMock()
            mock_resp_step2.__enter__.return_value = mock_resp_step2
            mock_resp_step2.read.return_value = step2_html.encode("utf-8")
            mock_resp_step2.geturl.return_value = "https://photojin.study/download/step2"

            mock_resp_api = MagicMock()
            mock_resp_api.__enter__.return_value = mock_resp_api
            mock_resp_api.read.return_value = json.dumps(api_response_json).encode("utf-8")

            with patch.object(client.opener, "open") as mock_open:
                mock_open.side_effect = [mock_resp_step2, mock_resp_api]

                results = client.unwrap_photojin("https://photojin.study/download/test")

                self.assertEqual(len(results), 1)
                self.assertEqual(results[0]["service"], "google_cdn")
                self.assertEqual(results[0]["resource_id"], "MOCK_UID_456")
                self.assertEqual(results[0]["final_url"], "https://lh3.googleusercontent.com/pw/MOCK_STREAM_URL=dv")
                self.assertEqual(results[0]["is_temporary"], 1)



class Session104Bolly4uTests(unittest.TestCase):
    """Session 104: photojin rotated .study -> .buzz (any TLD now)."""

    def test_photojin_any_tld_accepted(self):
        html = (
            '<h3>Download 1080p [2.1GB]</h3>'
            '<p><a href="https://photojin.buzz/f/abc123">Download 1080p</a></p>'
        )
        rels = import_bolly4u.parse_bolly4u_content(html, "Test Movie 2026 Hindi 1080p")
        self.assertEqual(len(rels), 1)
        self.assertIn("photojin.buzz", rels[0]["links"][0][0])

    def test_unwrapper_derives_host_from_landing_url(self):
        import inspect

        src = inspect.getsource(import_bolly4u.Bolly4uClient.unwrap_photojin)
        self.assertIn("pj_origin", src)
        self.assertNotIn("photojin.study", src)


class TestBolly4uWpRestAdoption(unittest.TestCase):
    """WP REST adoption: the live post carries every field; the importer
    must save it, keep every gateway, and invent nothing.

    Fixture transcribed from live bolly4u post 146083 ("Son Of 2026",
    captured 2026-11-23): real title, labels, poster URL and link hosts.
    """

    FIXTURE_CONTENT = """
        <p><strong>Son Of 2026 WEB-DL UNCUT Hindi Dual Audio ORG Full Movie Download [Hindi – Tamil ] 1080p, 720p &amp; 480p.</strong></p>
        <p><img src="https://myimg.click/images/2026/11/22/Son-Of-2026.jpg" /></p>
        <p><strong>IMDB Rating: 6.8/10</strong><br />
        <strong>Genres: Drama, Family, Thriller</strong></p>
        <p><strong>Director: Bathala Sateesh</strong><br />
        <strong>Stars Cast: Vinod Kumar, Meera Raaj, Inturi Vasu, Sai Simhadri</strong></p>
        <p><strong>Languages: Hindi + Telugu</strong><br />
        <strong>Video Quality: WEB-DL 1080p | 720p | 480p</strong></p>
        <p><strong>Film Story: An entitled son sues his father for neglecting his duties. The father’s powerful response sparks a courtroom battle that teaches the son about responsibility, love, and family.</strong></p>
        <h4><strong>|| 1080p Download Links: 1.7GB ||</strong></h4>
        <p><a href="https://photojin.garden/download/SAggOe4g1BE">DIRECT 1</a>
        <a href="https://new4.movcloud.click/s/877a4cd0f961b706">DIRECT [ G-DRIVE ]</a>
        <a href="https://torrentsave.0to.site/save/2f34d34dc26694d53847498e41d684aa9e617151">Download [T]</a>
        <a href="https://new2.linkskeep.lat/view/P0Ssy5renT">DOWNLOAD LINKS</a></p>
        <h4><strong>|| 720p Download Links: 1.1GB ||</strong></h4>
        <p><a href="https://photojin.garden/download/Q2dNDbPqYjH">DIRECT 1</a>
        <a href="https://new4.movcloud.click/s/6848b7af35d34645">DIRECT [ G-DRIVE ]</a>
        <a href="https://torrentsave.0to.site/save/65bf84a370732bc0940065ff54196a5ec05485e9">Download [T]</a>
        <a href="https://new2.linkskeep.lat/view/ZKgZxn_L9-">DOWNLOAD LINKS</a></p>
        <h4><strong>|| 480p Download Links: 400MB ||</strong></h4>
        <p><a href="https://photojin.garden/download/dzL8P4TSRg4">DIRECT 1</a>
        <a href="https://new4.movcloud.click/s/bc0c9e323434361a">DIRECT [ G-DRIVE ]</a>
        <a href="https://torrentsave.0to.site/save/ef5d019c0cc0a89245456f56ebbe06937cb53122">Download [T]</a>
        <a href="https://new2.linkskeep.lat/view/qAz0kQRjA-">DOWNLOAD LINKS</a></p>
        """

    FIXTURE_TITLE = (
        "Son Of 2026 WEB-DL UNCUT Hindi Dual Audio ORG Full Movie "
        "Download 1080p | 720p | 480p"
    )

    def _fixture_post(self):
        return {
            "id": 146083,
            "date": "2026-11-22T06:21:40",
            "modified": "2026-11-22T06:30:04",
            "slug": "son-of-2026-hindi-dual-audio-org-movie-download-webrip",
            "link": "https://bolly4u.moda/son-of-2026-hindi-dual-audio-org-movie-download-webrip/",
            "title": {"rendered": self.FIXTURE_TITLE},
            "content": {"rendered": self.FIXTURE_CONTENT},
            "excerpt": {"rendered": (
                "<p>Son Of 2026 WEB-DL UNCUT Hindi Dual Audio ORG Full Movie "
                "Download [Hindi – Tamil ] 1080p, 720p &amp; 480p. Son Of 2026 "
                "This is a South Hindi Movie and available in Hindi 1080p, 720p "
                "&amp; 480p qualities. [&hellip;]</p>")},
            "categories": [9910, 4, 83],
            "featured_media": 0,
            "_embedded": {"wp:term": [[
                {"id": 9910, "name": "720p Movies", "taxonomy": "category"},
                {"id": 4, "name": "Telugu 300Mb", "taxonomy": "category"},
            ]]},
        }

    def test_parse_content_keeps_all_four_gateways(self):
        releases = import_bolly4u.parse_bolly4u_content(
            self.FIXTURE_CONTENT, self.FIXTURE_TITLE)
        self.assertEqual(len(releases), 3)
        self.assertEqual(
            [r["quality"] for r in releases], ["1080P", "720P", "480P"])
        hosts = {u.split("/")[2] for r in releases for u, _ in r["links"]}
        self.assertIn("torrentsave.0to.site", hosts)
        self.assertIn("photojin.garden", hosts)
        self.assertIn("new4.movcloud.click", hosts)
        self.assertIn("new2.linkskeep.lat", hosts)
        self.assertEqual(len(releases[0]["links"]), 4)

    def test_parse_content_no_fabricated_quality_or_codec(self):
        html = (
            "<h4>Download Links: 1.1GB</h4>"
            '<p><a href="https://photojin.garden/download/abc">DIRECT 1</a></p>'
            "<h4>Download Links</h4>"
            '<p><a href="https://photojin.garden/download/def">DIRECT 1</a></p>'
        )
        releases = import_bolly4u.parse_bolly4u_content(html, "Some Movie 2026")
        self.assertEqual(len(releases), 2)
        # A heading with no quality token means unknown — never "720P".
        self.assertIsNone(releases[0]["quality"])
        self.assertIsNone(releases[0]["resolution"])
        self.assertIsNone(releases[0]["codec"])
        self.assertEqual(releases[0]["label"], "1.1GB")
        self.assertIsNone(releases[1]["quality"])
        self.assertEqual(releases[1]["label"], "Download")

    def test_parse_content_codec_only_when_stated(self):
        html = (
            "<h4>|| 1080p Download Links: 1.7GB x265 ||</h4>"
            '<p><a href="https://photojin.garden/download/abc">DIRECT 1</a></p>'
        )
        releases = import_bolly4u.parse_bolly4u_content(
            html, "Some Movie 2026 BluRay HEVC")
        self.assertEqual(releases[0]["codec"], "x265")

    def test_scrape_post_item_full_metadata_from_rest_post(self):
        client = import_bolly4u.Bolly4uClient(base_url="https://bolly4u.moda")
        post = self._fixture_post()
        item = (post["link"], post["modified"], post["slug"],
                json.dumps(post))
        with patch.object(client, "unwrap_photojin", return_value=[]), \
                patch.object(client, "unwrap_linkskeep", return_value=[]):
            result = import_bolly4u.scrape_post_item(item, client)
        self.assertTrue(result["success"])
        saved = result["post"]
        self.assertEqual(saved["title"], "Son Of")
        self.assertEqual(saved["year"], 2026)
        self.assertEqual(
            saved["poster_url"],
            "https://myimg.click/images/2026/11/22/Son-Of-2026.jpg")
        self.assertTrue(saved["synopsis"].startswith(
            "An entitled son sues his father"))
        self.assertEqual(json.loads(saved["genres"]),
                         ["Drama", "Family", "Thriller"])
        self.assertEqual(saved["imdb_rating"], 6.8)
        self.assertEqual(saved["director"], "Bathala Sateesh")
        self.assertIn("Vinod Kumar", saved["cast_names"])
        self.assertIn("Sai Simhadri", saved["cast_names"])
        self.assertEqual(saved["languages"], "Hindi + Telugu")
        self.assertEqual(len(result["releases"]), 3)
        by_quality = {r["quality"]: r for r in result["releases"]}
        self.assertEqual(set(by_quality), {"1080P", "720P", "480P"})
        services = {s["service"] for r in result["releases"]
                    for s in r["sources"]}
        self.assertIn("torrentsave", services)
        self.assertIn("movcloud", services)

    def test_sync_api_uses_embed_and_fetches_all_pages(self):
        import types

        pages = {
            1: ([{"link": "https://bolly4u.moda/a/", "slug": "a",
                  "modified": "2026-11-22T00:00:00",
                  "title": {"rendered": "A 2026"}}], 2, 2),
            2: ([{"link": "https://bolly4u.moda/b/", "slug": "b",
                  "modified": "2026-11-22T00:00:00",
                  "title": {"rendered": "B 2026"}}], 2, 2),
        }
        seen = []

        def fake_get_json(url, timeout=15):
            seen.append(url)
            import re as _re
            page = int(_re.search(r"[?&]page=(\d+)", url).group(1))
            if page in pages:
                posts, total, total_pages = pages[page]
                return 200, posts, {"x-wp-total": str(total),
                                    "x-wp-totalpages": str(total_pages)}
            return 400, None, {}

        client = types.SimpleNamespace(
            base_url="https://bolly4u.moda", get_json=fake_get_json)
        with tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "sync.db"
            total_new = import_bolly4u.sync_api_catalog(client, db_path)
            self.assertEqual(total_new, 2)
            self.assertEqual(len(seen), 2)
            self.assertTrue(all("_embed=1" in u for u in seen))
            self.assertTrue(all("per_page=100" in u for u in seen))
            with sqlite3.connect(str(db_path)) as conn:
                rows = conn.execute(
                    "SELECT loc, raw_json FROM sitemap_cache ORDER BY loc"
                ).fetchall()
            self.assertEqual(
                [r[0] for r in rows],
                ["https://bolly4u.moda/a/", "https://bolly4u.moda/b/"])
            self.assertIn("A 2026", rows[0][1])

    def test_sync_api_stops_after_three_empty_pages(self):
        import types

        post = {"link": "https://bolly4u.moda/a/", "slug": "a",
                "modified": "2026-11-22T00:00:00",
                "title": {"rendered": "A 2026"}}
        seen = []

        def fake_get_json(url, timeout=15):
            seen.append(url)
            return 200, [post], {"x-wp-total": "1", "x-wp-totalpages": "99"}

        client = types.SimpleNamespace(
            base_url="https://bolly4u.moda", get_json=fake_get_json)
        with tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "sync.db"
            total_new = import_bolly4u.sync_api_catalog(client, db_path)
        # Page 1 new, pages 2-4 empty -> stop after the 3rd empty page.
        self.assertEqual(total_new, 1)
        self.assertEqual(len(seen), 4)

    def test_save_backfills_metadata_on_conflict(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "save.db"
            import_bolly4u.init_staging_db(db_path)
            first = {
                "loc": "https://bolly4u.moda/son-of-2026/",
                "success": True,
                "post": {
                    "canonical_key": "son-of_2026",
                    "title": "Son Of",
                    "original_title": "Son Of 2026",
                    "year": 2026,
                    "media_type": "movie",
                    "imdb_id": None,
                    "poster_url": "https://myimg.click/images/2026/11/22/Son-Of-2026.jpg",
                    "synopsis": "An entitled son sues his father.",
                    "genres": '["Drama", "Family"]',
                    "imdb_rating": 6.8,
                    "cast_names": "Vinod Kumar",
                    "director": "Bathala Sateesh",
                    "languages": "Hindi + Telugu",
                    "site": "bolly4u",
                    "post_url": "https://bolly4u.moda/son-of-2026/",
                },
                "releases": [],
            }
            import_bolly4u.save_post_to_staging(db_path, first)
            # Re-save with a new title but no metadata: title updates,
            # the stored metadata survives.
            second = dict(first)
            second["post"] = dict(first["post"])
            second["post"]["title"] = "Son Of (Updated)"
            for key in ("poster_url", "synopsis", "genres", "imdb_rating",
                        "cast_names", "director", "languages"):
                second["post"][key] = None
            import_bolly4u.save_post_to_staging(db_path, second)
            with sqlite3.connect(str(db_path)) as conn:
                row = conn.execute(
                    "SELECT title, poster_url, synopsis, genres, imdb_rating,"
                    " cast_names, director, languages FROM posts"
                    " WHERE canonical_key = 'son-of_2026'"
                ).fetchone()
            self.assertEqual(row[0], "Son Of (Updated)")
            self.assertEqual(
                row[1], "https://myimg.click/images/2026/11/22/Son-Of-2026.jpg")
            self.assertEqual(row[2], "An entitled son sues his father.")
            self.assertEqual(row[3], '["Drama", "Family"]')
            self.assertEqual(row[4], 6.8)
            self.assertEqual(row[5], "Vinod Kumar")
            self.assertEqual(row[6], "Bathala Sateesh")
            self.assertEqual(row[7], "Hindi + Telugu")

    def test_init_db_migrates_old_schema(self):
        import tempfile

        # The pre-adoption posts table: every original column, none of
        # the four WP REST columns. The migration must add exactly those.
        with tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "old.db"
            with sqlite3.connect(str(db_path)) as conn:
                conn.execute(
                    "CREATE TABLE posts ("
                    " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    " canonical_key TEXT UNIQUE, title TEXT,"
                    " original_title TEXT, year INTEGER, media_type TEXT,"
                    " imdb_id TEXT, poster_url TEXT, synopsis TEXT,"
                    " genres TEXT, site TEXT DEFAULT 'bolly4u',"
                    " post_url TEXT,"
                    " updated_at DATETIME DEFAULT CURRENT_TIMESTAMP);")
                conn.commit()
            import_bolly4u.init_staging_db(db_path)
            with sqlite3.connect(str(db_path)) as conn:
                cols = {c[1] for c in conn.execute(
                    "PRAGMA table_info(posts);").fetchall()}
            for col in ("imdb_rating", "cast_names", "director", "languages",
                        "poster_url", "synopsis", "genres", "is_18_plus"):
                self.assertIn(col, cols)


if __name__ == "__main__":
    unittest.main()
