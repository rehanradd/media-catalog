#!/usr/bin/env python3
"""Offline tests for the complete MultiMovies import path.

Field-population tests (2026-11-23) use fixtures modelled on the live
12th Fail detail page: JSON-LD (datePublished, duration, rating), the
cinejoy spec box (Runtime / Release Date / Quality), and the cast grid.
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import fast_fetch
import import_multimovies


class MultiMoviesImportTests(unittest.TestCase):
    def test_sitemap_locs_support_namespace_and_entities(self):
        xml = """<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
          <url><loc>https://m1.multimovies.courses/movie/a?x=1&amp;y=2</loc></url>
        </urlset>"""
        self.assertEqual(
            import_multimovies._locs(xml),
            ["https://m1.multimovies.courses/movie/a?x=1&y=2"],
        )

    def test_episode_url_maps_to_root_and_coordinates(self):
        result = import_multimovies.series_root_for_episode(
            "https://m1.multimovies.courses/series/show-name/season/2/episode/7"
        )
        self.assertEqual(
            result,
            ("https://m1.multimovies.courses/series/show-name", 2, 7),
        )

    @patch("import_multimovies.fetch_sitemap_inventory")
    def test_discover_sitemaps_groups_episodes_by_root(self, mock_inventory):
        mock_inventory.return_value = (
            ["https://m1.multimovies.courses/movie/movie-a"],
            [
                "https://m1.multimovies.courses/series/show-x/season/1/episode/2",
                "https://m1.multimovies.courses/series/show-x/season/1/episode/1",
            ],
        )
        client = import_multimovies.SiteClient("https://m1.multimovies.courses", 0.0)
        titles, episodes, grouped = import_multimovies.discover_sitemaps(client)
        self.assertEqual(titles, ["https://m1.multimovies.courses/movie/movie-a"])
        self.assertEqual(len(episodes), 2)
        root = "https://m1.multimovies.courses/series/show-x"
        self.assertIn(root, grouped)
        self.assertEqual(grouped[root][0], "https://m1.multimovies.courses/series/show-x/season/1/episode/1")
        self.assertEqual(grouped[root][1], "https://m1.multimovies.courses/series/show-x/season/1/episode/2")

    def test_download_page_discovery_only_accepts_same_site_download_routes(self):
        page = """
        <a class="cinejoy-btn-download" href="/download/movie-a">Download</a>
        <a href="https://m1.multimovies.courses/download/movie-b">More</a>
        <a href="https://ads.example/redirect">Ad</a>
        """
        self.assertEqual(
            import_multimovies.download_page_urls(
                "https://m1.multimovies.courses/movie/movie-a",
                page,
                "m1.multimovies.courses",
            ),
            [
                "https://m1.multimovies.courses/download/movie-a",
                "https://m1.multimovies.courses/download/movie-b",
            ],
        )

    def test_download_mirrors_are_grouped_by_quality_and_typed(self):
        page = """
        <div class="quality-group"><h3>1080p</h3>
          <a class="dl-mirror-btn" href="https://fastdl.beer/dl?id=abc">GDFlix</a>
          <a class="dl-mirror-btn" href="https://filebee.xyz/file/xyz">Google Drive</a>
        </div>
        <a href="https://ads.example/out">Advertisement</a>
        """
        releases = import_multimovies.parse_download_mirrors(
            page,
            "https://m1.multimovies.courses/download/movie-a",
            "m1.multimovies.courses",
            {"clean_title": "Movie A", "year": 2024, "language": "Hindi"},
        )
        self.assertEqual(len(releases), 1)
        self.assertEqual(releases[0]["quality"], "1080P")
        self.assertEqual(len(releases[0]["sources"]), 2)
        self.assertEqual(
            {source["playback_type"] for source in releases[0]["sources"]},
            {"download"},
        )
        self.assertNotIn("ads.example", str(releases))

    def test_episode_watch_servers_preserve_each_server_type(self):
        page = """
        <script>
        var watchConfig = {
          "initialServers": [
            {"name":"Cineverse","type":"iframe","url":"https://embed.example/movie/1","quality":"HD"},
            {"name":"Direct HLS","type":"hls","url":"https://cdn.example/movie/1.m3u8","quality":"FHD"}
          ]
        };
        </script>
        """
        media = {"clean_title": "Series A", "language": "Hindi"}
        releases = import_multimovies.episode_releases(
            page,
            "https://m1.multimovies.courses/series/series-a/season/1/episode/2",
            media,
            1,
            2,
        )
        self.assertEqual(len(releases), 2)
        self.assertEqual(
            [release["episode"] for release in releases],
            ["E02", "E02"],
        )
        self.assertEqual(
            [release["sources"][0]["playback_type"] for release in releases],
            ["iframe", "hls"],
        )
        self.assertEqual(releases[0]["season"], "S01")

    def test_parser_can_skip_episode_fetch_and_link_resolution(self):
        page = """
        <title>Series A (2024) - MultiMovies</title>
        <a href="/series/series-a/season/8/episode/1">Episode</a>
        <script>var watchConfig = {"initialServers":[
          {"name":"Cineverse","type":"iframe","url":"https://embed.example/movie/1"}
        ]};</script>
        """
        with patch.object(fast_fetch, "http_get", side_effect=AssertionError("unexpected fetch")), \
             patch.object(fast_fetch, "resolve_vidout_playlist", side_effect=AssertionError("unexpected resolve")):
            data = fast_fetch.extract_post_data_multimovies(
                "https://m1.multimovies.courses/series/series-a",
                page,
                verbose=False,
                crawl_episodes=False,
                resolve_streams=False,
            )
        self.assertEqual(data["content_type"], "series")
        self.assertEqual(data["releases"][0]["sources"][0]["playback_type"], "iframe")

    def test_restricted_title_filter_matches_project_rules(self):
        self.assertTrue(import_multimovies._is_restricted("Some Adult Title", "some-title"))
        self.assertTrue(import_multimovies._is_restricted("Show", "show-nsfw"))
        self.assertFalse(import_multimovies._is_restricted("Family Movie", "family-movie-2024"))

    def test_source_playback_type_is_saved_after_schema_migration(self):
        original_db_path = fast_fetch.DB_PATH
        try:
            with tempfile.TemporaryDirectory() as temp_dir:
                fast_fetch.DB_PATH = Path(temp_dir) / "test.db"
                fast_fetch.init_db()
                post_id = fast_fetch.save_scraped_data(
                    {
                        "url": "https://m1.multimovies.courses/movie/test-title",
                        "slug": "test-title",
                        "title": "Test Title",
                        "clean_title": "Test Title",
                        "content_type": "movie",
                        "source_site": "multimovies",
                        "releases": [{
                            "release_title": "Test Title Cineverse",
                            "quality": "HD",
                            "source_site": "multimovies",
                            "sources": [{
                                "source_name": "Cineverse [iframe]",
                                "service": "cineverse",
                                "resource_id": "1",
                                "source_url": "https://embed.example/movie/1",
                                "final_url": "https://embed.example/movie/1",
                                "playback_type": "iframe",
                            }],
                        }],
                    },
                    enrich_metadata=False,
                    ensure_db=False,
                )
                with fast_fetch.get_db() as conn:
                    row = conn.execute(
                        """
                        SELECT ds.playback_type
                        FROM download_sources ds
                        JOIN releases r ON r.id = ds.release_id
                        WHERE r.post_id = ?
                        """,
                        (post_id,),
                    ).fetchone()
                self.assertEqual(row["playback_type"], "iframe")
        finally:
            fast_fetch.DB_PATH = original_db_path


SAMPLE_MM_DETAIL_HTML = """
<!DOCTYPE html>
<html><head>
<title>12th Fail (2023) - MultiMovies</title>
<meta name="description" content="Inspired by millions of UPSC students' lives and struggles.">
<meta property="og:image" content="https://image.tmdb.org/t/p/w342/98KWNgcxjdkGaenBS2bKLjZMayZ.jpg">
<script type="application/ld+json">
{"@context":"https://schema.org","@type":"Movie","name":"12th Fail",
 "url":"https://m1.multimovies.courses/movie/12th-fail-33452",
 "description":"Inspired by millions of UPSC students' lives and struggles.",
 "image":"https://image.tmdb.org/t/p/w1280/a7u1CU5wb05PSd1IWuP57rSx8zt.jpg",
 "genre":["Animation","Drama"],"datePublished":"2023-10-27","duration":"PT2H26M",
 "aggregateRating":{"@type":"AggregateRating","ratingValue":7.4,"ratingCount":4}}
</script>
<script>
var watchItem = {"titleId":33452,"title":"12th Fail",
 "poster":"https://image.tmdb.org/t/p/w342/98KWNgcxjdkGaenBS2bKLjZMayZ.jpg",
 "backdrop":"https://image.tmdb.org/t/p/w780/a7u1CU5wb05PSd1IWuP57rSx8zt.jpg",
 "type":"movie","url":"/movie/12th-fail-33452"};
var watchConfig = {"initialServers":[
  {"name":"Cineverse","type":"iframe","url":"https://embed.example/movie/1","quality":"HD"}
]};
</script>
</head><body>
<div class="cinejoy-spec-box">
<div class="cinejoy-spec-row">
<span class="cinejoy-spec-label">Runtime</span>
<span class="cinejoy-spec-val">2h 26m</span>
</div>
<div class="cinejoy-spec-row">
<span class="cinejoy-spec-label">Language</span>
<span class="cinejoy-spec-val">Not specified</span>
</div>
<div class="cinejoy-spec-row">
<span class="cinejoy-spec-label">Release Date</span>
<span class="cinejoy-spec-val">Oct 27, 2023</span>
</div>
<div class="cinejoy-spec-row">
<span class="cinejoy-spec-label">Quality</span>
<span class="cinejoy-spec-val">1080p</span>
</div>
</div>
<div class="cinejoy-cast-section">
<h2 class="cinejoy-section-title">Cast</h2>
<div class="cinejoy-cast-grid">
<a href="/person/1208682" class="cinejoy-cast-card">
<img src="https://image.tmdb.org/t/p/w185/zvpP52P7CtVXYONmvWrH1aOcRTx.jpg" alt="Vikrant Massey" class="cinejoy-cast-img" />
<div class="cinejoy-cast-name">Vikrant Massey</div>
<div class="cinejoy-cast-role">Manoj Kumar Sharma</div>
</a>
<a href="/person/3112841" class="cinejoy-cast-card">
<img src="https://image.tmdb.org/t/p/w185/aVemlgs6LIf1GzrpTPjyoV14Y2s.jpg" alt="Medha Shankr" class="cinejoy-cast-img" />
<div class="cinejoy-cast-name">Medha Shankr</div>
<div class="cinejoy-cast-role">Shraddha Joshi</div>
</a>
</div>
</div>
</body></html>
"""

SAMPLE_MM_DETAIL_NO_JSONLD_HTML = """
<!DOCTYPE html>
<html><head><title>Plain Film (2024) - MultiMovies</title></head><body>
<script>
var watchConfig = {"initialServers":[
  {"name":"Cineverse","type":"iframe","url":"https://embed.example/movie/2","quality":"HD"}
]};
</script>
<div class="cinejoy-spec-box">
<div class="cinejoy-spec-row">
<span class="cinejoy-spec-label">Runtime</span>
<span class="cinejoy-spec-val">1h 45m</span>
</div>
<div class="cinejoy-spec-row">
<span class="cinejoy-spec-label">Release Date</span>
<span class="cinejoy-spec-val">Mar 3, 2024</span>
</div>
</div>
</body></html>
"""

SAMPLE_MM_DETAIL_NO_YEAR_HTML = """
<!DOCTYPE html>
<html><head><title>Mystery Film - MultiMovies</title></head><body>
<script>
var watchConfig = {"initialServers":[
  {"name":"Cineverse","type":"iframe","url":"https://embed.example/movie/3","quality":"HD"}
]};
</script>
</body></html>
"""


class MultiMoviesFieldPopulationTests(unittest.TestCase):
    def _extract(self, page_html, url):
        with patch.object(fast_fetch, "http_get", side_effect=AssertionError("unexpected fetch")), \
             patch.object(fast_fetch, "resolve_vidout_playlist", side_effect=AssertionError("unexpected resolve")):
            return fast_fetch.extract_post_data_multimovies(
                url, page_html, verbose=False,
                crawl_episodes=False, resolve_streams=False)

    def test_jsonld_duration_release_date_cast_quality(self):
        data = self._extract(
            SAMPLE_MM_DETAIL_HTML, "https://m1.multimovies.courses/movie/12th-fail-33452")
        self.assertIsNotNone(data)
        self.assertEqual(data["year"], 2023)
        # PT2H26M -> 146 min (the catalog's "90 min" convention).
        self.assertEqual(data["duration"], "146 min")
        self.assertEqual(data["release_date"], "2023-10-27")
        # Actor names only — the character roles are not cast members.
        self.assertEqual(data["cast_names"], "Vikrant Massey, Medha Shankr")
        self.assertNotIn("Manoj Kumar Sharma", data["cast_names"])
        # The spec box's Quality is the discover-API quality value.
        self.assertEqual(json.loads(data["raw_meta"]), {"quality": "1080p"})
        self.assertEqual(data["imdb_rating"], "7.4")
        self.assertIn("Drama", data["genres"])
        self.assertEqual(
            data["releases"][0]["release_title"],
            "12th Fail (2023) Stream Server Cineverse")

    def test_spec_box_fills_duration_and_release_date_without_jsonld(self):
        data = self._extract(
            SAMPLE_MM_DETAIL_NO_JSONLD_HTML, "https://m1.multimovies.courses/movie/plain-film")
        self.assertEqual(data["duration"], "105 min")
        self.assertEqual(data["release_date"], "Mar 3, 2024")
        self.assertIsNone(data["cast_names"])
        self.assertIsNone(data["raw_meta"])

    def test_no_year_means_no_empty_parens_in_release_title(self):
        data = self._extract(
            SAMPLE_MM_DETAIL_NO_YEAR_HTML, "https://m1.multimovies.courses/movie/mystery-film")
        self.assertIsNone(data["year"])
        self.assertEqual(
            data["releases"][0]["release_title"],
            "Mystery Film Stream Server Cineverse")
        self.assertNotIn("()", data["releases"][0]["release_title"])

    def test_duration_helpers(self):
        self.assertEqual(fast_fetch._iso8601_duration_minutes("PT2H26M"), 146)
        self.assertEqual(fast_fetch._iso8601_duration_minutes("PT45M"), 45)
        self.assertEqual(fast_fetch._iso8601_duration_minutes("PT1H"), 60)
        self.assertIsNone(fast_fetch._iso8601_duration_minutes("PT0S"))
        self.assertIsNone(fast_fetch._iso8601_duration_minutes("not-a-duration"))
        self.assertEqual(fast_fetch._display_runtime_minutes("2h 26m"), 146)
        self.assertEqual(fast_fetch._display_runtime_minutes("45m"), 45)
        self.assertIsNone(fast_fetch._display_runtime_minutes("Not specified"))

    def test_fields_save_to_staging_db(self):
        data = self._extract(
            SAMPLE_MM_DETAIL_HTML, "https://m1.multimovies.courses/movie/12th-fail-33452")
        original_db_path = fast_fetch.DB_PATH
        try:
            with tempfile.TemporaryDirectory() as temp_dir:
                fast_fetch.DB_PATH = Path(temp_dir) / "test.db"
                fast_fetch.init_db()
                post_id = fast_fetch.save_scraped_data(
                    data, enrich_metadata=False, ensure_db=False)
                with fast_fetch.get_db() as conn:
                    row = conn.execute(
                        "SELECT duration, release_date, cast_names, raw_meta"
                        " FROM posts WHERE id = ?",
                        (post_id,),
                    ).fetchone()
                self.assertEqual(row["duration"], "146 min")
                self.assertEqual(row["release_date"], "2023-10-27")
                self.assertEqual(row["cast_names"], "Vikrant Massey, Medha Shankr")
                self.assertEqual(json.loads(row["raw_meta"]), {"quality": "1080p"})
        finally:
            fast_fetch.DB_PATH = original_db_path


if __name__ == "__main__":
    unittest.main()
