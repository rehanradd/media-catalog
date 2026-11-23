#!/usr/bin/env python3
"""
test_9xmovies.py — Offline Unit Tests for 9xmovies Importer & Bridge Parser
"""

import json
import os
import sqlite3
import tempfile
import unittest
from pathlib import Path

import wp_rest
from import_9xmovies import (
    NineXMoviesClient,
    clean_9xmovies_title,
    enrich_existing_metadata,
    init_staging_db,
    parse_9xmovies_content,
    parse_size_string,
    save_post_to_staging,
    scrape_post_item,
    sync_api_catalog,
)

# WP REST adoption (2026-11-23): live post 48141 "Max (2025)", fetched
# from 9xmovie.cologne/wp-json/wp/v2/posts/48141?_embed=1 on 2026-11-23.
# Transcribed faithfully: h2 titles, poster img, fname info block (all
# labels, <br />-separated), File Size line, Film Story, Screenshots
# heading, and the three quality blocks with their real linksddr URLs.
# The yoast og_description is the same info block, truncated mid-word —
# it is never used as a synopsis, only og_image as a poster fallback.
SAMPLE_9XMOVIES_REST_POST = {
    "id": 48141,
    "date": "2024-12-25T10:00:00",
    "modified": "2024-12-25T10:30:00",
    "slug": "max-2025-hindi-org-dubbed-1080p-720p-480p-hdrip-download",
    "link": "https://9xmovie.cologne/max-2025-hindi-org-dubbed-1080p-720p-480p-hdrip-download/",
    "title": {"rendered": "Max (2025) Hindi ORG Dubbed 1080p | 720p | 480p HDRip Download"},
    "content": {"rendered": """
<h2 style="text-align: center;">Download Max (2025) Hindi ORG Dubbed 1080p | 720p | 480p HDRip</h2>
<p style="text-align: center;"><img decoding="async" class="aligncenter" src="https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEjlm4Bfavugacns9KmKmi0VV4S9x1FiVGUwUZwR6yCAHv8utKWkRATYr2xlNMQmf6cS3WL3wnRx1RrMCUDIsNReV9Fzyn0FTOE1zxQpwyoZuDNa4sjI4q_-Zs4tRNkCCOVy65Cf1NdZltpGCBiMsQ6sZ-vHn1BGJLzJSjp-Ma2qN7aNN4MdjB4eMygB4d_X/s1600/Max%20(2025)%20Hindi.jpg" alt="" /></p>
<h2 style="text-align: center;">Max (2025) Hindi ORG Dubbed 1080p | 720p | 480p HDRip</h2>
<div class="fname" style="text-align: center;">
<div class="fname">IMDB&nbsp;Ratings: 7.0/10<br />
Released Date: December 25, 2024 (India)<br />
Genres: Action<br />
Languages: Hindi ORG Dubbed<br />
Director: Vijay Karthikeyan<br />
Writer: Vijay Karthikeyan<br />
Stars: SunilSudeep Krishna Hebbale</div>
</div>
<p style="text-align: center;">File Size : 2200MB | 920MB | 230MB</p>
<p style="text-align: center;"><span><strong>Film Story</strong> : Inspector Arjun Mahakshay, law and order inspector reports to duty at a new police station after a 2 months suspension and faces an unexpected situation. Can Arjun save the day?</span></p>
<h3 style="text-align: center;">(Must See Before Downloading)…Screenshots</h3>
<p>|| 1080p Download Links [2.2GB] ||</p>
<p><a href="https://linksddr.com/view/gCOrL77YXN">Download Instant &amp; Direct Link</a> <a href="https://linksddr.com/view/gCOrL77YXN">Watch Online &amp; Single Download Links</a></p>
<p>|| 720p Download Links [900MB] ||</p>
<p><a href="https://linksddr.com/view/pn9LSbDk9f">Download Instant &amp; Direct Link</a> <a href="https://linksddr.com/view/pn9LSbDk9f">Watch Online &amp; Single Download Links</a></p>
<p>|| 480p Download Links [230MB] ||</p>
<p><a href="https://linksddr.com/view/ndPRmWqGTR">Download Instant &amp; Direct Link</a> <a href="https://linksddr.com/view/ndPRmWqGTR">Watch Online &amp; Single Download Links</a></p>
"""},
    "excerpt": {"rendered": "<p>Download Max (2025) Hindi ORG Dubbed 1080p | 720p | 480p HDRip Max (2025) Hindi ORG Dubbed 1080p | 720p | 480p HDRip IMDB Ratings: 7.0/10 Released Date: December 25, 2024 (India) Genres: Action Languages: Hindi ORG Dubbed Director: Vijay Karthikeyan Writer: Vijay Karthike […]</p>"},
    "categories": [8],
    "featured_media": 0,
    "yoast_head_json": {
        "og_description": "IMDB Ratings: 7.0/10Released Date: December 25, 2024 (India)Genres: ActionLanguages: Hindi ORG DubbedDirector: Vijay KarthikeyanWriter: Vijay Karthike",
        "og_image": [{"url": "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEjlm4Bfavugacns9KmKmi0VV4S9x1FiVGUwUZwR6yCAHv8utKWkRATYr2xlNMQmf6cS3WL3wnRx1RrMCUDIsNReV9Fzyn0FTOE1zxQpwyoZuDNa4sjI4q_-Zs4tRNkCCOVy65Cf1NdZltpGCBiMsQ6sZ-vHn1BGJLzJSjp-Ma2qN7aNN4MdjB4eMygB4d_X/s1600/Max%20(2025)%20Hindi.jpg"}],
    },
    "_embedded": {
        "wp:term": [[{"id": 8, "name": "South Hindi Dubbad 720p", "taxonomy": "category"}]],
    },
}


class Test9xmoviesImporter(unittest.TestCase):
    def test_clean_9xmovies_title_movie(self):
        raw = "Star Wars: Episode IX – The Rise of Skywalker (2019) Hindi ORG Dual Audio 1080p | 720p | 480p BluRay ESubs Download"
        title, year, season, mtype, key = clean_9xmovies_title(raw)
        self.assertIn("Star Wars", title)
        self.assertIn("Skywalker", title)
        self.assertEqual(year, 2019)
        self.assertIsNone(season)
        self.assertEqual(mtype, "movie")
        self.assertTrue(key.startswith("star-wars"))
        self.assertIn("2019", key)

    def test_clean_9xmovies_title_series(self):
        raw = "The Night Manager S01 (2023) Hindi Complete Web Series 720p HDRip"
        title, year, season, mtype, key = clean_9xmovies_title(raw)
        self.assertIn("The Night Manager", title)
        self.assertEqual(year, 2023)
        self.assertEqual(season, 1)
        self.assertEqual(mtype, "series")

    def test_parse_size_string(self):
        self.assertEqual(parse_size_string("2.6GB"), int(2.6 * 1024**3))
        self.assertEqual(parse_size_string("650MB"), int(650 * 1024**2))
        self.assertEqual(parse_size_string("1.4GB"), int(1.4 * 1024**3))
        self.assertIsNone(parse_size_string("invalid"))
        self.assertIsNone(parse_size_string(None))

    def test_parse_9xmovies_content(self):
        sample_html = """
        <p><img src="https://blogger.googleusercontent.com/img/b/R29vZ2xl/test/s320/poster.jpg" /></p>
        <p>IMDb: <a href="https://www.imdb.com/title/tt2527338/">tt2527338</a></p>
        <p>|| 1080p Download Links [2.6GB] ||</p>
        <p><a href="https://linksddr.com/view/98G4s90PXx">Download 1080p</a></p>
        <p>|| 720p Download Links [1.4GB] ||</p>
        <p><a href="https://linksddr.com/view/jZ4HySKWxb">Download 720p</a></p>
        <p>|| 480p Download Links [650MB] ||</p>
        <p><a href="https://linksddr.com/view/F2yXn6jGwW">Download 480p</a></p>
        """
        imdb_id, poster, releases = parse_9xmovies_content(
            sample_html, "Star Wars (2019) 1080p"
        )
        self.assertEqual(imdb_id, "tt2527338")
        self.assertIsNotNone(poster)
        self.assertIn("/s1600/", poster)
        self.assertEqual(len(releases), 3)

        q_list = [r["quality"] for r in releases]
        self.assertIn("1080P", q_list)
        self.assertIn("720P", q_list)
        self.assertIn("480P", q_list)

        r1080 = [r for r in releases if r["quality"] == "1080P"][0]
        self.assertEqual(r1080["bridge_url"], "https://linksddr.com/view/98G4s90PXx")
        self.assertEqual(r1080["size_bytes"], int(2.6 * 1024**3))

    def test_db_init_and_save(self):
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as tf:
            db_path = Path(tf.name)

        try:
            init_staging_db(db_path)

            # Insert test item in sitemap_cache
            with sqlite3.connect(str(db_path)) as conn:
                conn.execute(
                    "INSERT INTO sitemap_cache (loc, slug, scraped) VALUES (?, ?, 0);",
                    ("https://9xmovie.bayern/test-movie/", "test-movie"),
                )

            res = {
                "loc": "https://9xmovie.bayern/test-movie/",
                "success": True,
                "post": {
                    "canonical_key": "test-movie-2024",
                    "title": "Test Movie",
                    "original_title": "Test Movie 2024",
                    "year": 2024,
                    "media_type": "movie",
                    "imdb_id": "tt1234567",
                    "imdb_rating": 7.5,
                    "poster_url": "https://blogger.googleusercontent.com/poster.jpg",
                    "synopsis": "A great test movie.",
                    "genres": "Action",
                    "release_date": "January 1, 2024 (India)",
                    "site": "9xmovies",
                    "post_url": "https://9xmovie.bayern/test-movie/",
                },
                "releases": [
                    {
                        "label": "1080P x264 2.6GB",
                        "quality": "1080P",
                        "resolution": "1080p",
                        "size_bytes": 2791728742,
                        "codec": "x264",
                        "season_number": None,
                        "episode_number": None,
                        "audio_languages": "Hindi",
                        "bridge_url": "https://linksddr.com/view/test1080",
                        "sources": [
                            {
                                "source_name": "⚡ HubCloud 10Gbps CDN",
                                "service": "hubcloud",
                                "resource_id": "testvideo1",
                                "source_url": "https://hubcloud.ist/video/testvideo1",
                                "final_url": "https://hubcloud.ist/video/testvideo1",
                                "is_temporary": 0,
                            }
                        ],
                    }
                ],
            }

            save_post_to_staging(db_path, res)

            # Verify contents
            with sqlite3.connect(str(db_path)) as conn:
                cur = conn.cursor()
                p_row = cur.execute(
                    "SELECT title, year, imdb_id, imdb_rating, release_date FROM posts WHERE canonical_key = 'test-movie-2024';"
                ).fetchone()
                self.assertIsNotNone(p_row)
                self.assertEqual(p_row[0], "Test Movie")
                self.assertEqual(p_row[1], 2024)
                self.assertEqual(p_row[3], 7.5)
                self.assertEqual(p_row[4], "January 1, 2024 (India)")

                r_row = cur.execute(
                    "SELECT quality, bridge_url FROM releases WHERE post_canonical_key = 'test-movie-2024';"
                ).fetchone()
                self.assertIsNotNone(r_row)
                self.assertEqual(r_row[0], "1080P")

                s_row = cur.execute(
                    "SELECT source_name, service FROM sources;"
                ).fetchone()
                self.assertIsNotNone(s_row)
                self.assertEqual(s_row[1], "hubcloud")

                scraped = cur.execute(
                    "SELECT scraped FROM sitemap_cache WHERE loc = 'https://9xmovie.bayern/test-movie/';"
                ).fetchone()[0]
                self.assertEqual(scraped, 1)

        finally:
            if db_path.exists():
                os.remove(db_path)



class Test9xmoviesRestAdoption(unittest.TestCase):
    """WP REST adoption (2026-11-23): the rating the parser extracted
    is now banked, with the release date; sync fetches with _embed=1;
    the Yoast og:image is a poster fallback."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test_9xmovies.db"
        init_staging_db(self.db_path)

    def tearDown(self):
        self.temp_dir.cleanup()

    def _item(self, post=None):
        post = post or SAMPLE_9XMOVIES_REST_POST
        return (post["link"], post.get("modified", ""), post["slug"],
                json.dumps(post))

    def test_scrape_post_item_stores_rating_and_metadata(self):
        res = scrape_post_item(
            self._item(), NineXMoviesClient(), unwrap_bridges=False)
        self.assertIsNotNone(res)
        self.assertTrue(res["success"])
        p = res["post"]
        self.assertEqual(p["title"], "Max")
        self.assertEqual(p["year"], 2025)
        self.assertEqual(p["media_type"], "movie")
        self.assertEqual(p["imdb_id"], None)
        # The rating used to be extracted and then thrown away.
        self.assertEqual(p["imdb_rating"], 7.0)
        self.assertEqual(p["release_date"], "December 25, 2024 (India)")
        self.assertEqual(json.loads(p["genres"]), ["Action"])
        self.assertEqual(p["director"], "Vijay Karthikeyan")
        self.assertIn("Sunil", p["cast_names"])
        self.assertTrue(p["synopsis"].startswith("Inspector Arjun Mahakshay"))
        self.assertIn("/s1600/Max%20(2025)%20Hindi.jpg", p["poster_url"])
        self.assertEqual(p["is_18_plus"], 0)
        self.assertEqual(len(res["releases"]), 3)
        self.assertEqual([r["quality"] for r in res["releases"]],
                         ["1080P", "720P", "480P"])
        self.assertEqual(res["releases"][0]["size_bytes"],
                         int(2.2 * 1024 ** 3))
        self.assertEqual(res["releases"][0]["sources"][0]["service"],
                         "linksddr")

    def test_junk_category_is_not_a_genre(self):
        # The only WP category is the "South Hindi Dubbad 720p" bucket:
        # with no Genres: label anywhere (content or excerpt), the
        # filtered-category fallback must return nothing for it.
        post = dict(SAMPLE_9XMOVIES_REST_POST)
        post["content"] = {"rendered": "<p>No genre label here.</p>"}
        post["excerpt"] = {"rendered": ""}
        self.assertIsNone(wp_rest.genres_from_post(post))

    def test_yoast_poster_fallback(self):
        post = {
            "id": 99999,
            "slug": "no-poster-2026",
            "link": "https://9xmovie.cologne/no-poster-2026/",
            "title": {"rendered": "No Poster (2026) Hindi 720p HDRip"},
            "content": {"rendered": (
                "<p>IMDB Ratings: 8.0/10 Genres: Drama</p>"
                "<p>|| 720p Download Links [900MB] ||</p>"
                '<p><a href="https://linksddr.com/view/TEST123">Download 720p</a></p>'
            )},
            "excerpt": {"rendered": ""},
            "categories": [],
            "featured_media": 0,
            "yoast_head_json": {"og_image": [
                {"url": "https://blogger.googleusercontent.com/img/b/yoast-poster.jpg"}]},
            "_embedded": {"wp:term": [[
                {"id": 3, "name": "Bollywood Movies", "taxonomy": "category"}]]},
        }
        res = scrape_post_item(
            self._item(post), NineXMoviesClient(), unwrap_bridges=False)
        self.assertTrue(res["success"])
        self.assertEqual(
            res["post"]["poster_url"],
            "https://blogger.googleusercontent.com/img/b/yoast-poster.jpg")
        self.assertEqual(res["post"]["imdb_rating"], 8.0)

    def test_sync_requests_embed_and_stores_raw_json(self):
        from unittest.mock import MagicMock
        client = NineXMoviesClient()
        client.get_json = MagicMock(side_effect=[
            (200, [SAMPLE_9XMOVIES_REST_POST],
             {"x-wp-total": "1", "x-wp-totalpages": "1"}),
            (400, None, {}),
        ])
        total = sync_api_catalog(client, self.db_path, max_pages=2)
        self.assertEqual(total, 1)
        self.assertIn("_embed=1", client.get_json.call_args_list[0].args[0])
        with sqlite3.connect(str(self.db_path)) as conn:
            conn.row_factory = sqlite3.Row
            row = conn.execute(
                "SELECT loc, raw_json FROM sitemap_cache").fetchone()
            self.assertIsNotNone(row)
            self.assertEqual(row["loc"], SAMPLE_9XMOVIES_REST_POST["link"])
            self.assertEqual(json.loads(row["raw_json"])["id"], 48141)

    def test_save_and_enrich_bank_rating_and_release_date(self):
        # Enrich reads raw_json back out of sitemap_cache, so the queue
        # row must exist first — exactly as a real sync leaves it.
        with sqlite3.connect(str(self.db_path)) as conn:
            conn.execute(
                "INSERT INTO sitemap_cache (loc, slug, scraped, site, raw_json) "
                "VALUES (?, ?, 0, '9xmovies', ?)",
                (SAMPLE_9XMOVIES_REST_POST["link"],
                 SAMPLE_9XMOVIES_REST_POST["slug"],
                 json.dumps(SAMPLE_9XMOVIES_REST_POST)),
            )
            conn.commit()
        res = scrape_post_item(
            self._item(), NineXMoviesClient(), unwrap_bridges=False)
        save_post_to_staging(self.db_path, res)
        with sqlite3.connect(str(self.db_path)) as conn:
            row = conn.execute(
                "SELECT imdb_rating, release_date, genres FROM posts "
                "WHERE canonical_key = 'max-2025'").fetchone()
            self.assertIsNotNone(row)
            self.assertEqual(row[0], 7.0)
            self.assertEqual(row[1], "December 25, 2024 (India)")
            self.assertEqual(json.loads(row[2]), ["Action"])

        # Enrich backfills the same fields on a row that lacks them.
        with sqlite3.connect(str(self.db_path)) as conn:
            conn.execute(
                "UPDATE posts SET imdb_rating = NULL, release_date = NULL "
                "WHERE canonical_key = 'max-2025'")
            conn.commit()
        enrich_existing_metadata(self.db_path)
        with sqlite3.connect(str(self.db_path)) as conn:
            row = conn.execute(
                "SELECT imdb_rating, release_date FROM posts "
                "WHERE canonical_key = 'max-2025'").fetchone()
            self.assertEqual(row[0], 7.0)
            self.assertEqual(row[1], "December 25, 2024 (India)")


class Session104NineXMoviesTests(unittest.TestCase):
    """Session 104: screenshot anchors skipped; one bridge URL keeps every tier."""

    def test_screenshot_skipped_and_tiers_kept(self):
        html = (
            "<p>1080p [2.0GB]</p>"
            '<p><a href="https://linksddr.com/view/XXX111">Download 1080p</a></p>'
            "<p>720p [1.0GB]</p>"
            '<p><a href="https://linksddr.com/view/XXX111">Download 720p</a></p>'
            '<p><a href="https://linksddr.com/view/YYY222">Screenshot</a></p>'
        )
        _imdb, _poster, rels = parse_9xmovies_content(html, "Test Movie 2026 Hindi")
        self.assertEqual(len(rels), 2)
        self.assertEqual(rels[0]["quality"], "1080P")
        self.assertEqual(rels[1]["quality"], "720P")


if __name__ == "__main__":
    unittest.main()
