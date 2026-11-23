#!/usr/bin/env python3
"""
test_ssrmovies_import.py — Comprehensive unit tests for SSRMovies dedicated importer.

Tests:
  1. Release button parsing: quality (1080p, 720p, 480p, 4K), size, codec, season/episode.
  2. Post metadata extraction: clean title, year, audio/language, poster, synopsis, canonical key.
  3. LinksZilla mirror mapping and resolution into stream and download sources.
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
import import_ssrmovies


SAMPLE_SSR_MOVIE_HTML = """
<!DOCTYPE html>
<html>
<head>
    <title>Thaapi 2026 Punjabi (Cleaned) 1080p 720p 480p HQ HDTC x264 – SSRmovies</title>
    <meta name="description" content="Thaapi 2026 Punjabi Full Movie watch online and download in 1080p, 720p and 480p HD." />
    <meta property="og:image" content="https://imgurworld.top/images/2026/10/17/thaapi-poster.jpg" />
</head>
<body>
    <div class="entry-content">
        <p style="text-align: center;">Thaapi enjoy the full movie in 480p 720p and 1080p HQ HDTC x264 best HD quality. Thaapi is available in Punjabi language.</p>
        <p class="poster" align="center"><img src="https://imgurworld.top/images/2026/10/17/thaapi-main.jpg" alt="poster" /></p>
        <p style="text-align: center;"><strong>Thaapi (2026) 1080p 720p 480p HQ HDTC x264</strong></p>
        <hr />
        <p style="text-align: center;">
            <a href="https://secure.linkszilla.top/view/-Zw8TA6H1r" target="_blank" class="emd_dl_purple_light">Watch &amp; Download in 1080p - 2.8GB</a>
        </p>
        <p style="text-align: center;">
            <a href="https://secure.linkszilla.top/view/pbeNXh-zEb" target="_blank" class="emd_dl_blue_four">Watch &amp; Download in 720p - 1.2GB</a>
        </p>
        <p style="text-align: center;">
            <a href="https://secure.linkszilla.top/view/re-x6kHg5c" target="_blank" class="emd_dl_red">Watch &amp; Download in 480p - 434MB</a>
        </p>
    </div>
</body>
</html>
"""


# WP REST adoption (2026-11-23): live post 132230 "The Great Indian Kapil
# Show S05", fetched from site4.ssrmovies.page/wp-json/wp/v2/posts/132230
# ?_embed=1 on 2026-11-23. Transcribed faithfully (info block, first img,
# Ep 09 heading, 3 linkszilla + 1 direct-cloud buttons) — the full live
# content is 61 KB, so only the parts extraction reads are kept. Note the
# trap this pins: featured_media is -1 and _embedded.wp:featuredmedia[0]
# is the SITE INDEX (name/url/home/gmt_offset — no source_url), so the
# poster must come from the first content image, never from _embedded.
SAMPLE_SSR_REST_POST = {
    "id": 132230,
    "date": "2026-10-03T10:00:00",
    "modified": "2026-11-21T09:00:00",
    "slug": "the-great-indian-kapil-show-s05",
    "link": "https://site4.ssrmovies.page/the-great-indian-kapil-show-s05/",
    "title": {"rendered": "The Great Indian Kapil Show S05 NF 1080p 720p 480p WEB-DL x264 ESubs [Ep 09 ADDED]"},
    "content": {"rendered": """
<p><img class="aligncenter" src="https://imgurworld.top/images/2026/10/03/f60eb35cc4beaaf8e5689bdcfece0e5e.jpg" /></p>
<p style="text-align: center;">The Great Indian Kapil Show 2025 S05 NF 1080p 720p 480p WEB-DL x264</p>
<p style="text-align: center;">IMDb: 6.5/10 || Size: See below || Language: Hindi (ORG 5.1) Genres: Comedy Show Creators: Kapil Sharma Judges: Archana Puran Singh, Navjot Singh Sidhu Stars: Kapil Sharma, Sunil Grover, Krishna Abhishek, Kiku Sharda Storyline: Comedian Kapil Sharma hosts this laugh-out-loud variety talk show with celebrity guests, hilarious antics and his signature supporting cast. Rest Episodes Will Be Added Release Accordingly… || Ep 09 (Shreya Ghoshal, Badshah) – 21st November 2026 ||</p>
<h2>Ep 09 (Shreya Ghoshal, Badshah) – 21st November 2026</h2>
<p><a href="https://secure.linkszilla.top/view/LAZ-Z3ySyQ">Watch &amp; Download in 1080p - 1.85GB</a></p>
<p><a href="https://secure.linkszilla.top/view/hLAaxa79nc">Watch &amp; Download in 720p - 811MB</a></p>
<p><a href="https://secure.linkszilla.top/view/xQz96a-OXP">Watch &amp; Download in 480p - 306MB</a></p>
<p><a href="https://dl.direct-cloud.top/d/BBB222">Download in 1080p - 1.8GB</a></p>
"""},
    "excerpt": {"rendered": "<p>The Great Indian Kapil Show 2025 S05 NF 1080p 720p 480p WEB-DL x264 IMDb: 6.5/10 || Size: See below || Language: Hindi (ORG 5.1) Genres: Comedy Show Creators: Kapil Sharma Judges: Archana Puran Singh, Navjot Singh Sidhu Stars: Kapil Sharm…</p>"},
    "categories": [12],
    "featured_media": -1,
    "_embedded": {
        "wp:term": [[{"id": 12, "name": "Bollywood Movies", "taxonomy": "category"}]],
        "wp:featuredmedia": [{"name": "ssrmovies", "url": "https://site4.ssrmovies.page", "home": "https://site4.ssrmovies.page", "gmt_offset": "+5.5"}],
    },
}


class TestSSRMoviesImport(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test_ssrmovies.db"
        fast_fetch.DB_PATH = self.db_path
        fast_fetch.init_db()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_default_base_url_points_at_the_live_domain(self):
        """Session 103: ssrmovies.surf 301s to new1.ssrmovies.page (verified
        live 2026-11-08: the WP REST API answers there with 27,471 posts).
        The importer points at the live domain directly so no request pays
        a redirect hop; if the site rotates again this canary fails loudly
        instead of the scraper silently following a stale domain."""
        self.assertEqual(import_ssrmovies.DEFAULT_BASE_URL,
                         "https://new1.ssrmovies.page")

    def test_parse_release_buttons(self):
        btn_1080p = '<a href="https://secure.linkszilla.top/view/-Zw8TA6H1r">Watch &amp; Download in 1080p - 2.8GB</a>'
        btn_720p = '<a href="https://secure.linkszilla.top/view/pbeNXh-zEb">Watch &amp; Download in 720p - 1.2GB</a>'
        btn_480p = '<a href="https://secure.linkszilla.top/view/re-x6kHg5c">Watch &amp; Download in 480p - 434MB</a>'
        btn_4k_hevc = '<a href="https://secure.linkszilla.top/view/4k-hevc">Watch &amp; Download in 4K 2160p 10Bit HEVC - 6.5GB</a>'

        r_1080p = import_ssrmovies.parse_ssrmovies_release_button(btn_1080p)
        self.assertIsNotNone(r_1080p)
        self.assertEqual(r_1080p["quality"], "1080p")
        self.assertEqual(r_1080p["size"], "2.8GB")
        self.assertEqual(r_1080p["codec"], "x264")

        r_720p = import_ssrmovies.parse_ssrmovies_release_button(btn_720p)
        self.assertEqual(r_720p["quality"], "720p")
        self.assertEqual(r_720p["size"], "1.2GB")

        r_480p = import_ssrmovies.parse_ssrmovies_release_button(btn_480p)
        self.assertEqual(r_480p["quality"], "480p")
        self.assertEqual(r_480p["size"], "434MB")

        r_4k = import_ssrmovies.parse_ssrmovies_release_button(btn_4k_hevc)
        self.assertEqual(r_4k["quality"], "2160p")
        self.assertEqual(r_4k["size"], "6.5GB")
        self.assertEqual(r_4k["codec"], "hevc")

    def test_extract_post_metadata(self):
        data = import_ssrmovies.extract_post_data_ssrmovies_enhanced(
            post_url="https://ssrmovies.surf/thaapi-2026-punjabi-cleaned-1080p-720p-480p-hq-hdtc-x264/",
            page_html=SAMPLE_SSR_MOVIE_HTML,
            resolve_direct=False,
        )
        self.assertIsNotNone(data)
        self.assertEqual(data["clean_title"], "Thaapi")
        self.assertEqual(data["year"], 2026)
        self.assertEqual(data["content_type"], "movie")
        self.assertEqual(data["language"], "Punjabi")
        self.assertEqual(data["poster_url"], "https://imgurworld.top/images/2026/10/17/thaapi-main.jpg")
        self.assertIn("Thaapi 2026 Punjabi Full Movie", data["synopsis"])
        # WP REST adoption: no genre/rating labels in this page, so neither
        # is fabricated — the old code banked ["Action", "Drama"] here.
        self.assertIsNone(data["genres"])
        self.assertIsNone(data["imdb_rating"])
        self.assertIsNone(data["cast_names"])
        self.assertEqual(len(data["releases"]), 3)

        qualities = [r["quality"] for r in data["releases"]]
        self.assertIn("1080p", qualities)
        self.assertIn("720p", qualities)
        self.assertIn("480p", qualities)

    @patch("fast_fetch.unwrap_linkszilla")
    def test_extract_post_with_resolved_mirrors(self, mock_unwrap_lz):
        mock_unwrap_lz.return_value = [
            {
                "source_name": "▶ Watch Online Stream",
                "service": "stream",
                "resource_id": "stream_01",
                "source_url": "https://watch-online.mom/e/stream_01",
                "final_url": "https://watch-online.mom/e/stream_01",
                "is_temporary": 0,
            },
            {
                "source_name": "⚡ Direct-Cloud [High Speed]",
                "service": "google_cdn",
                "resource_id": "dc_01",
                "source_url": "https://dl.direct-cloud.top/d/dc_01",
                "final_url": "https://lh3.googleusercontent.com/pw/sample_direct_cdn",
                "is_temporary": 1,
            },
        ]

        data = import_ssrmovies.extract_post_data_ssrmovies_enhanced(
            post_url="https://ssrmovies.surf/thaapi-2026-punjabi-cleaned-1080p-720p-480p-hq-hdtc-x264/",
            page_html=SAMPLE_SSR_MOVIE_HTML,
            resolve_direct=True,
        )
        self.assertEqual(len(data["releases"]), 3)
        sources_1080p = data["releases"][0]["sources"]
        self.assertEqual(len(sources_1080p), 2)
        services = [s["service"] for s in sources_1080p]
        self.assertIn("stream", services)
        self.assertIn("google_cdn", services)

    def test_sync_api_to_db(self):
        client = import_ssrmovies.SSRMoviesClient(base_url="https://ssrmovies.surf")

        sample_posts = [
            {
                "id": 101,
                "link": "https://ssrmovies.surf/movie-alpha-2026/",
                "slug": "movie-alpha-2026",
                "modified": "2026-10-17T12:00:00",
                "title": {"rendered": "Movie Alpha 2026"},
            },
            {
                "id": 102,
                "link": "https://ssrmovies.surf/movie-beta-2026/",
                "slug": "movie-beta-2026",
                "modified": "2026-10-17T12:30:00",
                "title": {"rendered": "Movie Beta 2026"},
            },
        ]

        with patch.object(client, "get_json") as mock_json:
            mock_json.side_effect = [
                # Probe call
                (200, [{"id": 101}], {"x-wp-total": "2", "x-wp-totalpages": "1"}),
                # Page 1 call
                (200, sample_posts, {"x-wp-total": "2", "x-wp-totalpages": "1"}),
            ]

            synced = import_ssrmovies.sync_api_to_db(client, self.db_path, max_pages=1)
            self.assertEqual(synced, 2)

        with sqlite3.connect(self.db_path) as conn:
            conn.row_factory = sqlite3.Row
            rows = conn.execute("SELECT loc, slug, scraped, site FROM sitemap_cache").fetchall()
            self.assertEqual(len(rows), 2)
            self.assertEqual(rows[0]["loc"], "https://ssrmovies.surf/movie-alpha-2026/")
            self.assertEqual(rows[0]["site"], "ssrmovies")

    def test_end_to_end_worker_scrape_and_save(self):
        client = import_ssrmovies.SSRMoviesClient(base_url="https://ssrmovies.surf")

        # Insert a pending URL
        test_url = "https://ssrmovies.surf/thaapi-2026-punjabi-cleaned-1080p-720p-480p-hq-hdtc-x264/"
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                "INSERT INTO sitemap_cache (loc, slug, scraped, site) VALUES (?, ?, 0, 'ssrmovies')",
                (test_url, "thaapi-2026"),
            )
            conn.commit()

        with patch.object(client, "get_text") as mock_text:
            mock_text.return_value = (200, test_url, SAMPLE_SSR_MOVIE_HTML)

            ok, rels, srcs = import_ssrmovies.scrape_ssrmovies_worker(
                post_url=test_url,
                client=client,
                resolve_direct=False,
            )
            self.assertTrue(ok)
            self.assertEqual(rels, 3)

        # Check DB
        with sqlite3.connect(self.db_path) as conn:
            p_row = conn.execute("SELECT title, year, clean_title, content_type FROM posts WHERE clean_title = 'Thaapi'").fetchone()
            self.assertIsNotNone(p_row)
            self.assertEqual(p_row[1], 2026)

            r_rows = conn.execute("SELECT release_title, quality, size FROM releases").fetchall()
            self.assertEqual(len(r_rows), 3)

            scraped_flag = conn.execute("SELECT scraped FROM sitemap_cache WHERE loc = ?", (test_url,)).fetchone()[0]
            self.assertEqual(scraped_flag, 1)



class TestSSRMoviesRestAdoption(unittest.TestCase):
    """WP REST adoption (2026-11-23): extract from the REST post itself,
    sync stores raw_json, worker never fetches when raw_json is present."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test_ssrmovies.db"
        fast_fetch.DB_PATH = self.db_path
        fast_fetch.init_db()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_extract_from_rest_post_kapil_show(self):
        data = import_ssrmovies.extract_post_data_ssrmovies_enhanced(
            post_url=SAMPLE_SSR_REST_POST["link"],
            page_html="",
            resolve_direct=False,
            rest_post=SAMPLE_SSR_REST_POST,
        )
        self.assertIsNotNone(data)
        self.assertEqual(data["clean_title"], "The Great Indian Kapil Show")
        # Year is not in the title — it comes from the content heading.
        self.assertEqual(data["year"], 2025)
        self.assertEqual(data["content_type"], "series")
        # Poster is the first content image: _embedded carries only the
        # site index (no source_url), never a poster.
        self.assertEqual(
            data["poster_url"],
            "https://imgurworld.top/images/2026/10/03/f60eb35cc4beaaf8e5689bdcfece0e5e.jpg")
        self.assertTrue(data["synopsis"].startswith("Comedian Kapil Sharma hosts"))
        self.assertNotIn("Watch & Download", data["synopsis"])
        self.assertEqual(json.loads(data["genres"]), ["Comedy Show"])
        self.assertEqual(data["imdb_rating"], 6.5)
        self.assertEqual(data["director"], "Kapil Sharma")
        self.assertIn("Kapil Sharma", data["cast_names"])
        self.assertIn("Sunil Grover", data["cast_names"])
        # Judges are on-screen talent too — they join the cast.
        self.assertIn("Archana Puran Singh", data["cast_names"])
        self.assertEqual(len(data["releases"]), 4)
        self.assertEqual([r["episode"] for r in data["releases"]], [9, 9, 9, 9])
        qualities = [r["quality"] for r in data["releases"]]
        self.assertIn("1080p", qualities)
        self.assertIn("720p", qualities)
        self.assertIn("480p", qualities)
        services = [r["sources"][0]["service"] for r in data["releases"]]
        self.assertIn("linkszilla", services)
        self.assertIn("direct_cloud", services)

    def test_sxxeyy_title_is_a_series(self):
        # Branch-2 live proof banked 'Sa Re Ga Ma Pa' S01E020 as a movie:
        # S\d+ needs a word boundary after the digits, which "S01E020"
        # does not have. The compact SxxEyy form must mark a series.
        post = {
            "id": 132231,
            "slug": "sa-re-ga-ma-pa-2026-hindi-org-s01e020-series-hdrip-1080p-720p-480p",
            "link": "https://site4.ssrmovies.page/sa-re-ga-ma-pa-2026-hindi-org-s01e020-series-hdrip-1080p-720p-480p/",
            "title": {"rendered": "Sa Re Ga Ma Pa (2026) Hindi ORG S01E020 Series HDRip | 1080p | 720p | 480p"},
            "content": {"rendered": '<p>Sa Re Ga Ma Pa (2026) Hindi ORG S01E020 Series HDRip</p><p><a href="https://secure.linkszilla.top/view/AAA111">Watch &amp; Download in 1080p - 1.69GB</a></p>'},
            "excerpt": {"rendered": ""},
        }
        data = import_ssrmovies.extract_post_data_ssrmovies_enhanced(
            post_url=post["link"], page_html="", resolve_direct=False,
            rest_post=post)
        self.assertEqual(data["content_type"], "series")
        self.assertEqual(data["clean_title"], "Sa Re Ga Ma Pa")
        self.assertEqual(data["year"], 2026)

    def test_no_fabricated_genres_on_dual_audio_page(self):
        html_doc = """<html><head><title>Some Movie 2024 Dual Audio 720p – SSRmovies</title></head>
<body><p><a href="https://secure.linkszilla.top/view/AAA111">Watch &amp; Download in 720p - 1.1GB</a></p></body></html>"""
        data = import_ssrmovies.extract_post_data_ssrmovies_enhanced(
            "https://new1.ssrmovies.page/some-movie-2024-dual-audio-720p/",
            html_doc, resolve_direct=False)
        self.assertIsNone(data["genres"])
        self.assertIsNone(data["imdb_rating"])

    def test_sync_stores_raw_json_and_requests_embed(self):
        client = import_ssrmovies.SSRMoviesClient(base_url="https://ssrmovies.surf")
        sample_posts = [SAMPLE_SSR_REST_POST]
        with patch.object(client, "get_json") as mock_json:
            mock_json.side_effect = [
                (200, [{"id": 132230}], {"x-wp-total": "1", "x-wp-totalpages": "1"}),
                (200, sample_posts, {"x-wp-total": "1", "x-wp-totalpages": "1"}),
            ]
            synced = import_ssrmovies.sync_api_to_db(client, self.db_path, max_pages=1)
            self.assertEqual(synced, 1)
            # The probe only reads headers; the page fetch carries _embed=1.
            self.assertEqual(len(mock_json.call_args_list), 2)
            self.assertIn("_embed=1", mock_json.call_args_list[1].args[0])
        with sqlite3.connect(self.db_path) as conn:
            conn.row_factory = sqlite3.Row
            row = conn.execute("SELECT loc, slug, raw_json FROM sitemap_cache").fetchone()
            self.assertIsNotNone(row)
            self.assertEqual(row["loc"], SAMPLE_SSR_REST_POST["link"])
            stored = json.loads(row["raw_json"])
            self.assertEqual(stored["id"], 132230)
            self.assertEqual(stored["title"]["rendered"], SAMPLE_SSR_REST_POST["title"]["rendered"])

    def test_worker_uses_raw_json_without_fetch(self):
        client = import_ssrmovies.SSRMoviesClient(base_url="https://ssrmovies.surf")
        test_url = SAMPLE_SSR_REST_POST["link"]
        import_ssrmovies._ensure_sitemap_raw_json(self.db_path)
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                "INSERT INTO sitemap_cache (loc, slug, scraped, site, raw_json) VALUES (?, ?, 0, 'ssrmovies', ?)",
                (test_url, "the-great-indian-kapil-show-s05", json.dumps(SAMPLE_SSR_REST_POST)),
            )
            conn.commit()
        with patch.object(client, "get_text") as mock_text:
            mock_text.side_effect = AssertionError("page fetch must not run with raw_json")
            ok, rels, srcs = import_ssrmovies.scrape_ssrmovies_worker(
                post_url=test_url, client=client, resolve_direct=False,
                raw_json_str=json.dumps(SAMPLE_SSR_REST_POST))
            self.assertTrue(ok)
            self.assertEqual(rels, 4)
        with sqlite3.connect(self.db_path) as conn:
            p_row = conn.execute(
                "SELECT imdb_rating, genres, cast_names FROM posts WHERE clean_title = 'The Great Indian Kapil Show'").fetchone()
            self.assertIsNotNone(p_row)
            # posts.imdb_rating is a TEXT column; normalize_rating stores
            # the float as its string form.
            self.assertEqual(float(p_row[0]), 6.5)
            self.assertEqual(json.loads(p_row[1]), ["Comedy Show"])
            self.assertIn("Archana Puran Singh", p_row[2])
            self.assertEqual(
                conn.execute("SELECT scraped FROM sitemap_cache WHERE loc = ?", (test_url,)).fetchone()[0], 1)


class Session104SSRMoviesTests(unittest.TestCase):
    """Session 104: dl.direct-cloud.top buttons + Ep NN headings (were dropped)."""

    def test_direct_cloud_button_gateway_recorded(self):
        btn = '<a href="https://dl.direct-cloud.top/d/BBB222">Download in 1080p - 1.8GB</a>'
        parsed = import_ssrmovies.parse_ssrmovies_release_button(btn, "")
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed["gateway"], "direct_cloud")
        self.assertEqual(parsed["quality"], "1080p")

    def test_ep_headings_attribute_series_episodes(self):
        html = """<html><body>
<h2>Ep 07</h2>
<p><a href="https://secure.linkszilla.top/view/AAA111">Watch &amp; Download in 1080p - 2.54GB</a></p>
<p><a href="https://dl.direct-cloud.top/d/BBB222">Download in 1080p - 1.8GB</a></p>
<h2>Ep 08</h2>
<p><a href="https://secure.linkszilla.top/view/CCC333">Watch &amp; Download in 720p - 1.1GB</a></p>
</body></html>"""
        data = import_ssrmovies.extract_post_data_ssrmovies_enhanced(
            "https://new1.ssrmovies.page/kapil-show-s05/", html, resolve_direct=False
        )
        self.assertEqual(len(data["releases"]), 3)
        self.assertEqual([r["episode"] for r in data["releases"]], [7, 7, 8])
        self.assertEqual(data["releases"][1]["sources"][0]["service"], "direct_cloud")


if __name__ == "__main__":
    unittest.main()
