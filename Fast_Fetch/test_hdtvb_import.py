#!/usr/bin/env python3
"""Offline tests for HDHub4u / hdtvb.net staging import path."""

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import fast_fetch
import import_hdtvb


class HDTVBImportTests(unittest.TestCase):
    def test_default_base_url_is_hdboss(self):
        # The site moved hdtvb.net -> hdutv.com -> hdboss.in (2026-11-23):
        # the fallback base must name the live host, not the dead one.
        self.assertEqual(import_hdtvb.DEFAULT_BASE_URL, "https://hdboss.in")

    def test_poster_joins_against_the_post_page_host(self):
        # A poster joined against a hardcoded dead host 404s at serve
        # time; the same page shape on hdboss.in must yield an hdboss.in
        # poster, while the recorded hdtvb.net shape keeps its host.
        html = (
            '<html><head><title>Film (2026)</title></head><body>'
            '<h1 class="entry-title">Film (2026)</h1>'
            '<img class="movie-cover" src="/uploads/posts/covers/p.png">'
            '<a href="https://hubcdn.io/cloud/tok">Download 1Gb {1080p}</a>'
            "</body></html>"
        )
        data = import_hdtvb.extract_post_data_hdtvb(
            "https://hdboss.in/film-2026.html", html, resolve_direct=False)
        self.assertEqual(
            data["poster_url"],
            "https://hdboss.in/uploads/posts/covers/p.png")
        data = import_hdtvb.extract_post_data_hdtvb(
            "https://hdtvb.net/film-2026.html", html, resolve_direct=False)
        self.assertEqual(
            data["poster_url"],
            "https://hdtvb.net/uploads/posts/covers/p.png")

    def test_parse_sitemap_urls(self):
        sample_xml = """<?xml version="1.0" encoding="UTF-8"?>
        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
            <url><loc>https://hdtvb.net/category-a/</loc></url>
            <url><loc>https://hdtvb.net/movie-one-2026.html</loc></url>
            <url><loc>https://hdtvb.net/movie-two-2025.html</loc></url>
            <url><loc>https://hdtvb.net/movie-one-2026.html</loc></url>
        </urlset>"""
        urls = import_hdtvb.parse_sitemap_urls(sample_xml)
        self.assertEqual(urls, [
            "https://hdtvb.net/movie-one-2026.html",
            "https://hdtvb.net/movie-two-2025.html",
        ])

    def test_extract_post_data_movie(self):
        sample_html = """
        <html>
        <head>
          <title>Love Of Your Life (2026) (Hindi + English) Dual Audio Hollywood Movie HD ESub &raquo; HDHub4u</title>
        </head>
        <body>
          <h1 class="entry-title">&#xE02C; Love Of Your Life (2026) (Hindi + English) Dual Audio Hollywood Movie HD ESub</h1>
          <img class="movie-cover" src="/uploads/posts/covers/photo_love.png" onerror="this.src='/no-poster.webp';">
          <div class="downloads">
            <a href="https://hubcdn.io/cloud/token1">Download 440Mb {480p-HEVC}</a>
            <a href="https://hubcdn.io/cloud/token2">Download 1.1Gb {720p-HD}</a>
            <a href="https://hubcdn.io/cloud/token3">Download 12.6Gb {2160p-WEB-DL}</a>
          </div>
        </body>
        </html>
        """
        data = import_hdtvb.extract_post_data_hdtvb(
            "https://hdtvb.net/love-of-your-life-2026.html",
            sample_html,
            resolve_direct=False,
        )
        self.assertIsNotNone(data)
        self.assertEqual(data["clean_title"], "Love Of Your Life")
        self.assertEqual(data["year"], 2026)
        self.assertEqual(data["content_type"], "movie")
        self.assertEqual(data["poster_url"], "https://hdtvb.net/uploads/posts/covers/photo_love.png")
        self.assertEqual(len(data["releases"]), 3)

        # Check quality groupings
        self.assertEqual(data["releases"][0]["quality"], "480p-HEVC")
        self.assertEqual(data["releases"][0]["size"], "440Mb")
        self.assertEqual(data["releases"][1]["quality"], "720p-HD")
        self.assertEqual(data["releases"][1]["size"], "1.1Gb")
        self.assertEqual(data["releases"][2]["quality"], "2160p-WEB-DL")
        self.assertEqual(data["releases"][2]["size"], "12.6Gb")

        # Check source gateway
        sources = data["releases"][0]["sources"]
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["playback_type"], "url")
        self.assertEqual(sources[0]["source_url"], "https://hubcdn.io/cloud/token1")

    def test_extract_post_data_series(self):
        sample_html = """
        <html>
        <head><title>The Trap S01 (2026) Dual Audio Web Series &raquo; HDHub4u</title></head>
        <body>
          <h1 class="entry-title">The Trap S01 (2026) Dual Audio Completed Web Series HEVC</h1>
          <a href="https://hubcdn.io/cloud/series_token">Download 4.5Gb {1080p-HEVC}</a>
        </body>
        </html>
        """
        data = import_hdtvb.extract_post_data_hdtvb(
            "https://hdtvb.net/the-trap-s01.html",
            sample_html,
            resolve_direct=False,
        )
        self.assertIsNotNone(data)
        self.assertEqual(data["clean_title"], "The Trap")
        self.assertEqual(data["content_type"], "series")
        self.assertEqual(data["year"], 2026)
        self.assertEqual(len(data["releases"]), 1)
        self.assertEqual(data["releases"][0]["quality"], "1080p-HEVC")

    def test_extract_carries_industry_country_for_hindi_series(self):
        # A4-F6: Kesariya@100 was filed Hollywood/USA because the
        # extractor never set industry/country. A Hindi web series now
        # carries Bollywood/India; a Hindi-dubbed Hollywood movie still
        # carries Hollywood/USA.
        series_html = """
        <html>
        <head><title>Kesariya@100 S01 (2025) Hindi Completed Web Series</title></head>
        <body>
          <h1 class="entry-title">Kesariya@100 S01 (2025) Hindi Completed Web Series HEVC ESub</h1>
          <a href="https://hubcdn.io/cloud/series_token">Download 4.5Gb {1080p-HEVC}</a>
        </body>
        </html>
        """
        data = import_hdtvb.extract_post_data_hdtvb(
            "https://hdtvb.net/kesariya-100-s01-2025-hindi-completed-web-series-hevc-esub.html",
            series_html,
            resolve_direct=False,
        )
        self.assertIsNotNone(data)
        self.assertEqual(data["industry"], "Bollywood")
        self.assertEqual(data["country"], "India")
        movie_html = series_html.replace(
            "Kesariya@100 S01 (2025) Hindi Completed Web Series HEVC ESub",
            "Avengers Endgame (2019) Hindi Dubbed BluRay",
        ).replace(
            "Kesariya@100 S01 (2025) Hindi Completed Web Series",
            "Avengers Endgame (2019) Hindi Dubbed BluRay",
        )
        data = import_hdtvb.extract_post_data_hdtvb(
            "https://hdtvb.net/avengers-endgame-2019-hindi-dubbed-bluray.html",
            movie_html,
            resolve_direct=False,
        )
        self.assertIsNotNone(data)
        self.assertEqual(data["industry"], "Hollywood")
        self.assertEqual(data["country"], "USA")

    def test_save_post_record_into_staging_db(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            temp_db = Path(tmp_dir) / "test_hdtvb.db"
            fast_fetch.DB_PATH = temp_db
            fast_fetch.init_db()

            # Pre-populate sitemap_cache
            with fast_fetch.get_db() as conn:
                conn.execute(
                    "INSERT INTO sitemap_cache (loc, slug, scraped, site) VALUES (?, ?, 0, ?)",
                    ("https://hdtvb.net/test-movie.html", "test-movie", import_hdtvb.SITE_KEY),
                )

            data = {
                "url": "https://hdtvb.net/test-movie.html",
                "slug": "test-movie",
                "title": "Test Movie 2026",
                "clean_title": "Test Movie",
                "year": 2026,
                "content_type": "movie",
                "poster_url": "https://hdtvb.net/poster.jpg",
                "source_site": import_hdtvb.SITE_KEY,
                "canonical_key": "test_movie_2026",
                "releases": [{
                    "release_title": "Test Movie 2026 1080p",
                    "quality": "1080p",
                    "codec": "x264",
                    "size": "2.5Gb",
                    "languages": "Hindi",
                    "season": None,
                    "episode": None,
                    "intermediate_url": "https://hubcdn.io/cloud/abc",
                    "source_site": import_hdtvb.SITE_KEY,
                    "sources": [{
                        "source_name": "Cloudflare R2 Direct",
                        "service": "cloudflare_r2",
                        "source_url": "https://pub-xyz.r2.dev/stream.mkv",
                        "final_url": "https://pub-xyz.r2.dev/stream.mkv",
                        "playback_type": "direct",
                    }],
                }],
            }

            post_id = import_hdtvb.save_post_record(data, temp_db)
            self.assertGreater(post_id, 0)

            # Verify in DB
            with fast_fetch.get_db() as conn:
                row = conn.execute("SELECT clean_title, year, source_site FROM posts WHERE id = ?", (post_id,)).fetchone()
                self.assertEqual(row["clean_title"], "Test Movie")
                self.assertEqual(row["year"], 2026)
                self.assertEqual(row["source_site"], "hdtvb")

                src_row = conn.execute(
                    "SELECT ds.source_name, ds.playback_type, ds.source_url "
                    "FROM download_sources ds JOIN releases r ON r.id = ds.release_id WHERE r.post_id = ?",
                    (post_id,)
                ).fetchone()
                self.assertEqual(src_row["playback_type"], "direct")
                self.assertEqual(src_row["source_url"], "https://pub-xyz.r2.dev/stream.mkv")

                cache_row = conn.execute(
                    "SELECT scraped FROM sitemap_cache WHERE loc = ?",
                    ("https://hdtvb.net/test-movie.html",)
                ).fetchone()
                self.assertEqual(cache_row["scraped"], 1)


class TestHDTVBGenreFilter(unittest.TestCase):
    #: The info-genre span as it appears on live pages (token census,
    #: 2026-11-23): real genres mixed with format tags, language tags,
    #: categories, slug duplicates and a sponsor token.
    POLLUTED_SPAN = (
        "Drama, Music, 300MB Movies, Dual Audio, HD Movies, Hindi Dubbed, "
        "Action, HollyWood, dual-audio, dubbed-movie, sci-fi, thriller, "
        "Amazon Prime Video, 4rabet, Science Fiction, Drama")

    def _page_with_genres(self, span):
        return """
        <html><head><title>Puli (2015) Hindi Tamil Dual Audio Uncut South Movie HD ESub</title></head>
        <body><h1 class="entry-title">Puli (2015) Hindi Tamil Dual Audio Uncut South Movie HD ESub</h1>
        <span class="info-genre">%s</span>
        <a href="https://hubcdn.io/cloud/token1">Download 1.2Gb {720p-HD}</a>
        </body></html>
        """ % span

    def test_only_real_genres_survive_the_span(self):
        data = import_hdtvb.extract_post_data_hdtvb(
            "https://hdtvb.net/puli-2015-hindi-tamil-dual-audio-uncut-south-movie-hd-esub.html",
            self._page_with_genres(self.POLLUTED_SPAN), resolve_direct=False)
        self.assertEqual(json.loads(data["genres"]),
                         ["Drama", "Music", "Action", "Sci-Fi", "Thriller",
                          "Science Fiction"])

    def test_normalize_genres_unit(self):
        self.assertEqual(
            import_hdtvb.normalize_hdtvb_genres(
                "Action, Drama, action, DRAMA, Romantic, scifi, 300MB Movies"),
            ["Action", "Drama", "Romance", "Sci-Fi"])
        self.assertEqual(import_hdtvb.normalize_hdtvb_genres(
            "300MB Movies, Dual Audio, HollyWood"), [])
        self.assertEqual(import_hdtvb.normalize_hdtvb_genres(None), [])
        self.assertEqual(import_hdtvb.normalize_hdtvb_genres(""), [])

    def test_cast_is_absent_not_an_empty_list_string(self):
        # The audit's literal "[]" cast is a historical artifact: this
        # extractor carries no cast_names key at all, so the save path
        # stores NULL, never "[]".
        data = import_hdtvb.extract_post_data_hdtvb(
            "https://hdtvb.net/puli-2015-hindi-tamil-dual-audio-uncut-south-movie-hd-esub.html",
            self._page_with_genres(self.POLLUTED_SPAN), resolve_direct=False)
        self.assertNotIn("cast_names", data)


class TestHDTVBProvenanceStampTests(unittest.TestCase):
    """D3 regression: this importer must only ever stamp its own site key."""

    PAGE_HTML = """
    <html><head><title>Dummy Film (2026) 1080p &raquo; HDTVB</title></head>
    <body><h1 class="entry-title">Dummy Film (2026) 1080p</h1>
    <a href="https://hubcdn.io/cloud/dummy">Download 1.2Gb {1080p-HEVC}</a>
    </body></html>
    """

    def test_site_key_is_hdtvb(self):
        self.assertEqual(import_hdtvb.SITE_KEY, "hdtvb")

    def test_no_hdhub4u_string_literal_survives_in_the_importer(self):
        source = Path(import_hdtvb.__file__).read_text(encoding="utf-8")
        self.assertNotIn("'hdhub4u'", source)
        self.assertNotIn('"hdhub4u"', source)

    def test_extractor_stamps_hdtvb_on_post_and_releases(self):
        data = import_hdtvb.extract_post_data_hdtvb(
            "https://hdtvb.net/dummy-film-2026.html", self.PAGE_HTML, resolve_direct=False
        )
        self.assertEqual(data["source_site"], "hdtvb")
        self.assertTrue(data["releases"])
        for release in data["releases"]:
            self.assertEqual(release["source_site"], "hdtvb")

    def test_mark_scraped_is_site_scoped(self):
        source = Path(import_hdtvb.__file__).read_text(encoding="utf-8")
        self.assertIn(
            "UPDATE sitemap_cache SET scraped = 1 WHERE loc = ? AND site = ?",
            source,
        )
        self.assertIn(
            "SELECT loc FROM sitemap_cache WHERE site = ? AND scraped = 0 ORDER BY rowid ASC",
            source,
        )



class _FakeHubCDNClient:
    def resolve_hubcdn_direct(self, url):
        return {
            "direct_r2": "https://r2.example/f.mkv",
            "pixeldrain": "https://pixeldrain.com/u/abc",
            "gofile": "https://gofile.io/d/xyz",
            "hubcloud": "https://hubcloud.pw/drive/aaa",
            "gdflix": "https://gdflix.dev/file/bbb",
        }


class Session104HDTVBTests(unittest.TestCase):
    """Session 104: hubcdn mirrors hubcloud + gdflix are stored, not dropped."""

    def test_hubcloud_and_gdflix_appended(self):
        html = (
            "<html><head><title>Test Movie (2026)</title></head><body><h1>Test Movie (2026)</h1>"
            '<a href="https://hubcdn.io/cloud/abc123">Download {1080p} [2GB]</a>'
            "</body></html>"
        )
        data = import_hdtvb.extract_post_data_hdtvb(
            "https://hdutv.com/test-movie-2026.html", html,
            client=_FakeHubCDNClient(), resolve_direct=True,
        )
        self.assertIsNotNone(data)
        services = [s["service"] for r in data["releases"] for s in r["sources"]]
        self.assertIn("hubcloud", services)
        self.assertIn("gdflix", services)


if __name__ == "__main__":
    unittest.main()
