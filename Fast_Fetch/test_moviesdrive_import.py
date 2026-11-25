#!/usr/bin/env python3
"""
test_moviesdrive_import.py — Unit tests for the MoviesDrive importer (Session 118).

Tests:
  1. Archive links: only mdrive.lol/archive/<id>/ links survive (Telegram,
     IMDb, mirror links are noise).
  2. Archive sources: hubcloud / gdflix anchors only, download playback.
  3. Post data: one release per archive link, quality/size/codec from the
     archive title, languages from the WP title.
  4. No archive links -> None; archives with no file host -> None.
  5. Language words helper.
  6. Save path: a saved post in a temp staging DB.
  7. Post sync: mocked wp-json pages -> sitemap_cache rows.
  8. Registry: the moviesdrive entry exists with the wp-json endpoint.
  9. Series Info block: rating/director/stars/country/language/year/
     storyline parsed from the post body; a 0.0 rating is dropped.
  10. Rich post: poster/backdrop/synopsis/genres/cast/director/imdb_id/
      raw_meta populated from the WP post (field population, 2026-11-23);
      duration/release_date stay NULL (the source carries neither).

100% Python Standard Library. Zero external pip packages. Offline — temp SQLite
files only, no network, no catalog read. Fixture file-host URLs use the
reserved .test TLD; they exercise the mapping, never a real download link.
"""

import json
import sqlite3
import tempfile
import unittest
import urllib.parse
from pathlib import Path
from unittest.mock import MagicMock

import fast_fetch
import import_moviesdrive

SAMPLE_MD_WP_POST = {
    "id": 65291,
    "slug": "wwe-crown-jewel-2026-2026-web-dl-hindi-dd2-0-english-480p-720p-1080p-x264-esubs-full-movie",
    "link": "https://new1.moviesdrive.cyou/wwe-crown-jewel-2026-2026-web-dl-hindi-dd2-0-english-480p-720p-1080p-x264-esubs-full-movie/",
    "title": {"rendered": "WWE Crown Jewel (2026) WEB-DL Hindi DD2.0 English 480p 720p 1080p x264 ESubs Full Movie"},
    "content": {"rendered": """
        <p><a href="https://moviesdrives.bid/">MoviesDrive</a></p>
        <p><a href="https://t.me/moviesdrivee">Telegram</a></p>
        <p><a href="https://www.imdb.com/title/tt44954660/">IMDb</a></p>
        <p><a href="https://mdrive.lol/archive/25388/">480p Download</a></p>
        <p><a href="https://mdrive.lol/archive/25387/">720p Download</a></p>
        <p><a href="https://mdrive.lol/archive/25388/">480p Download</a></p>
    """},
    "date": "2026-11-08T10:00:00",
}

SAMPLE_MD_ARCH_480_HTML = """
<!DOCTYPE html>
<html><head>
<title>WWE Crown Jewel (2026) WEB-DL [Hindi (DD2.0) &#038; English] 480p x264 [658.47 MB]</title>
</head><body>
<nav><a href="https://mdrive.lol/">Home</a></nav>
<p><a href="https://hubcloud.test/drive/dezdtvddj1zsmjs">Download 480p</a></p>
<p><a href="https://t.me/moviesdrivee">Telegram</a></p>
</body></html>
"""

SAMPLE_MD_ARCH_720_HTML = """
<!DOCTYPE html>
<html><head>
<title>WWE Crown Jewel (2026) WEB-DL [Hindi (DD2.0) &#038; English] 720p x264 [1.2 GB]</title>
</head><body>
<p><a href="https://gdflix.test/file/abc123">Download 720p</a></p>
</body></html>
"""

# Same-origin duplicate fixture (Rev-9 follow-up): two DIFFERENT archive
# pages carrying the IDENTICAL <title> — a series re-listing one season
# pack under two archive ids — with different file-host links. Before the
# fix the extractor emitted two releases with one title and one origin_url.
SAMPLE_MD_WP_POST_SAME_TITLE = {
    "id": 65292,
    "slug": "visionquest-season-1-2026-web-dl-hindi-720p",
    "link": "https://new3.moviesdrive.cyou/visionquest-season-1-2026-web-dl-hindi-720p/",
    "title": {"rendered": "VisionQuest (Season 1) 2026 WEB-DL Hindi 720p"},
    "content": {"rendered": """
        <p><a href="https://mdrive.lol/archive/91001/">720p Ep01-05</a></p>
        <p><a href="https://mdrive.lol/archive/91002/">720p Ep06-10</a></p>
    """},
    "date": "2026-11-12T10:00:00",
}

SAMPLE_MD_ARCH_SAME_TITLE_A_HTML = """
<!DOCTYPE html>
<html><head>
<title>VisionQuest (Season 1) 720p x264 [1.2 GB]</title>
</head><body>
<p><a href="https://hubcloud.test/drive/aaaa1111">Download 720p Ep01-05</a></p>
</body></html>
"""

SAMPLE_MD_ARCH_SAME_TITLE_B_HTML = """
<!DOCTYPE html>
<html><head>
<title>VisionQuest (Season 1) 720p x264 [1.2 GB]</title>
</head><body>
<p><a href="https://hubcloud.test/drive/bbbb2222">Download 720p Ep06-10</a></p>
</body></html>
"""

SAMPLE_MD_ARCH_NO_HOST_HTML = """
<!DOCTYPE html>
<html><head><title>Some Title (2026) 480p x264 [500 MB]</title></head>
<body><p><a href="https://mdrive.lol/">Home</a></p></body></html>
"""

# Rich post fixture (field population, 2026-11-23): modelled on the live
# "Bigg Boss (Season 20)" WP post (new4.moviesdrive.cyou, 2026-11-22) — a
# TMDB poster image + catimages screenshots, an IMDb link, a Series Info
# block with rating/stars/language/year/storyline, and _embedded categories.
# The live post's Director/Creator line is EMPTY, so the fixture keeps it
# empty: an empty director must stay None, not "".
SAMPLE_MD_WP_POST_RICH = {
    "id": 65293,
    "slug": "bigg-boss-season-20-2026-hindi-dd2-0-s20-ep78-added-jiohotstar-series-480p-720p-1080p-web-dl",
    "link": "https://new4.moviesdrive.cyou/bigg-boss-season-20-2026-hindi-dd2-0-s20-ep78-added-jiohotstar-series-480p-720p-1080p-web-dl/",
    "title": {"rendered": "Bigg Boss (Season 20) 2026 [Hindi DD2.0] (S20 Ep78 Added) JioHotstar Series 480p 720p 1080p \u2013 WEB-DL"},
    "content": {"rendered": """
        <p>Download Bigg Boss (2026) Season 20 WEB Series [Hindi] Complete All Episodes Available in 720p HEVC | 1080p HEVC qualities.</p>
        <p><img src="https://image.tmdb.org/t/p/w500/k5ope1puShzLycqcj3FIoGAPjva.jpg" alt="Bigg Boss"></p>
        <p><img src="https://catimages.org/images/2026/09/07/Vlcsnap.mkv-frame024377.jpg" alt="screenshot"></p>
        <p><img src="https://catimages.org/images/2026/09/07/Vlcsnap.mkv-frame042892.jpg" alt="screenshot"></p>
        <p><a href="https://www.imdb.com/title/tt1281973/">IMDb</a></p>
        <p>Series Info: \U0001f4ab IMDb Rating:- 3.6/10 \U0001f308 Series Name: Bigg Boss Season: 20
        \U0001f50a Language: {Hindi DD2.0} \U0001f4c5 Released Year: 2026
        \U0001f46eDirector/Creator: \u2b50Stars: Salman Khan, Atul Kapoor, Vijay Vikram Singh
        \U0001f4bf Episode Size: \u2013 Each Episode \U0001f3acQuality: 480p || 720p || 1080p \u2013 WEB-DL
        \U0001f4c1 Format: MKV \u2666 Storyline- Bigg Boss is a reality show based on the original
        Dutch Big Brother. A number of contestants live in a purpose-built house.
        \U0001f5bc\ufe0f SCREENSHOTS \u2013 Bigg Boss (Season 20) \u2013 Watch Free</p>
        <p><a href="https://mdrive.lol/archive/18530/">480p Download</a></p>
        <p><a href="https://mdrive.lol/archive/18529/">720p Download</a></p>
    """},
    "date": "2026-11-22T21:30:41",
    "_embedded": {
        "wp:term": [
            [{"id": 1, "name": "DRAMA", "taxonomy": "category"},
             {"id": 2, "name": "Dual Audio", "taxonomy": "category"},
             {"id": 3, "name": "Hotstar", "taxonomy": "category"}],
            [],
        ],
    },
}

SAMPLE_MD_ARCH_BB_480_HTML = """
<!DOCTYPE html>
<html><head>
<title>Bigg Boss (Season 20) [ Hindi DD2.0 ] 480p x264 [340MB/E]</title>
</head><body>
<p><a href="https://hubcloud.test/drive/bb480aaa">Download 480p</a></p>
</body></html>
"""

SAMPLE_MD_ARCH_BB_720_HTML = """
<!DOCTYPE html>
<html><head>
<title>Bigg Boss (Season 20) [ Hindi DD2.0 ] 720p x264 [800MB/E]</title>
</head><body>
<p><a href="https://gdflix.test/file/bb720bbb">Download 720p</a></p>
</body></html>
"""


class TestMoviesDriveExtract(unittest.TestCase):
    def test_archive_links_only_mdrive(self):
        links = import_moviesdrive.extract_archive_links(
            SAMPLE_MD_WP_POST["content"]["rendered"])
        self.assertEqual(links, [
            "https://mdrive.lol/archive/25388/",
            "https://mdrive.lol/archive/25387/",
        ])

    def test_archive_sources_hubcloud_only(self):
        sources = import_moviesdrive.extract_archive_sources(SAMPLE_MD_ARCH_480_HTML)
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["service"], "hubcloud")
        self.assertEqual(sources[0]["playback_type"], "download")
        self.assertEqual(sources[0]["final_url"], "https://hubcloud.test/drive/dezdtvddj1zsmjs")

    def test_archive_sources_gdflix(self):
        sources = import_moviesdrive.extract_archive_sources(SAMPLE_MD_ARCH_720_HTML)
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["service"], "gdflix")

    def test_post_data_one_release_per_archive(self):
        archive_pages = {
            "https://mdrive.lol/archive/25388/": SAMPLE_MD_ARCH_480_HTML,
            "https://mdrive.lol/archive/25387/": SAMPLE_MD_ARCH_720_HTML,
        }
        data = import_moviesdrive.extract_post_data_moviesdrive(SAMPLE_MD_WP_POST, archive_pages)
        self.assertIsNotNone(data)
        self.assertEqual(data["source_site"], "moviesdrive")
        self.assertEqual(data["year"], 2026)
        self.assertTrue(data["clean_title"].startswith("WWE Crown Jewel"))
        self.assertEqual(len(data["releases"]), 2)
        by_quality = {r["quality"]: r for r in data["releases"]}
        self.assertEqual(by_quality["480p"]["size"], "658.47 MB")
        self.assertEqual(by_quality["480p"]["codec"], "X264")
        self.assertEqual(by_quality["720p"]["size"], "1.2 GB")
        self.assertEqual(by_quality["480p"]["languages"], "Hindi + English")
        self.assertEqual(by_quality["480p"]["sources"][0]["service"], "hubcloud")
        self.assertEqual(by_quality["720p"]["sources"][0]["service"], "gdflix")

    def test_no_archive_links_is_none(self):
        post = dict(SAMPLE_MD_WP_POST)
        post["content"] = {"rendered": "<p>No links here</p>"}
        self.assertIsNone(import_moviesdrive.extract_post_data_moviesdrive(post, {}))

    def test_archives_without_file_host_is_none(self):
        archive_pages = {
            "https://mdrive.lol/archive/25388/": SAMPLE_MD_ARCH_NO_HOST_HTML,
            "https://mdrive.lol/archive/25387/": SAMPLE_MD_ARCH_NO_HOST_HTML,
        }
        self.assertIsNone(import_moviesdrive.extract_post_data_moviesdrive(SAMPLE_MD_WP_POST, archive_pages))

    def test_two_archive_pages_same_title_merge_into_one_release(self):
        # Rev-9 follow-up: two archive pages with the identical <title>
        # used to emit two releases with one title and one origin_url —
        # a same-origin duplicate the refresh delete cannot catch (it
        # runs before the inserts) and the old dedupe skipped.
        archive_pages = {
            "https://mdrive.lol/archive/91001/": SAMPLE_MD_ARCH_SAME_TITLE_A_HTML,
            "https://mdrive.lol/archive/91002/": SAMPLE_MD_ARCH_SAME_TITLE_B_HTML,
        }
        data = import_moviesdrive.extract_post_data_moviesdrive(
            SAMPLE_MD_WP_POST_SAME_TITLE, archive_pages)
        self.assertIsNotNone(data)
        self.assertEqual(len(data["releases"]), 1)
        self.assertEqual(
            data["releases"][0]["release_title"],
            "VisionQuest (Season 1) 720p x264 [1.2 GB]")
        self.assertEqual(len(data["releases"][0]["sources"]), 2)
        self.assertEqual(
            {s["source_url"] for s in data["releases"][0]["sources"]},
            {"https://hubcloud.test/drive/aaaa1111",
             "https://hubcloud.test/drive/bbbb2222"})

    def test_languages_from_title(self):
        self.assertEqual(
            import_moviesdrive.languages_from_title("Movie (2026) Hindi DD2.0 English 480p"),
            "Hindi + English")
        self.assertEqual(import_moviesdrive.languages_from_title("Scene (2026) Tamil"), "Tamil")
        self.assertEqual(import_moviesdrive.languages_from_title("No Language Here"), "")


class TestMoviesDriveSave(unittest.TestCase):
    def test_saved_post_in_staging_db(self):
        archive_pages = {
            "https://mdrive.lol/archive/25388/": SAMPLE_MD_ARCH_480_HTML,
            "https://mdrive.lol/archive/25387/": SAMPLE_MD_ARCH_720_HTML,
        }
        data = import_moviesdrive.extract_post_data_moviesdrive(SAMPLE_MD_WP_POST, archive_pages)
        self.assertIsNotNone(data)
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviesdrive.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            post_id = import_moviesdrive.save_post_record(data, db_path)
            self.assertGreater(post_id, 0)
            with sqlite3.connect(str(db_path)) as conn:
                conn.row_factory = sqlite3.Row
                row = conn.execute(
                    "SELECT source_site, content_type FROM posts WHERE id = ?", (post_id,)
                ).fetchone()
                n_rel = conn.execute("SELECT count(*) FROM releases WHERE post_id = ?", (post_id,)).fetchone()[0]
                n_src = conn.execute("SELECT count(*) FROM download_sources").fetchone()[0]
            self.assertEqual(row["source_site"], "moviesdrive")
            self.assertEqual(n_rel, 2)
            self.assertEqual(n_src, 2)

    def test_same_title_archives_save_as_one_release(self):
        # End to end for the merge above: one save must not insert two
        # rows with the same (post_id, release_title, origin_url).
        archive_pages = {
            "https://mdrive.lol/archive/91001/": SAMPLE_MD_ARCH_SAME_TITLE_A_HTML,
            "https://mdrive.lol/archive/91002/": SAMPLE_MD_ARCH_SAME_TITLE_B_HTML,
        }
        data = import_moviesdrive.extract_post_data_moviesdrive(
            SAMPLE_MD_WP_POST_SAME_TITLE, archive_pages)
        self.assertIsNotNone(data)
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviesdrive.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            post_id = import_moviesdrive.save_post_record(data, db_path)
            with sqlite3.connect(str(db_path)) as conn:
                n_rel = conn.execute("SELECT count(*) FROM releases WHERE post_id = ?", (post_id,)).fetchone()[0]
                n_src = conn.execute("SELECT count(*) FROM download_sources").fetchone()[0]
                dups = conn.execute(
                    "SELECT count(*) FROM (SELECT 1 FROM releases WHERE source_site = 'moviesdrive'"
                    " GROUP BY post_id, release_title HAVING count(*) > 1)"
                ).fetchone()[0]
            self.assertEqual(n_rel, 1)
            self.assertEqual(n_src, 2)
            self.assertEqual(dups, 0)


class TestMoviesDriveSeriesInfo(unittest.TestCase):
    def test_series_info_block_parsed(self):
        info = import_moviesdrive.parse_moviesdrive_series_info(
            SAMPLE_MD_WP_POST_RICH["content"]["rendered"])
        self.assertEqual(info["imdb_rating"], "3.6")
        self.assertIsNone(info["director"])  # live post: empty Director/Creator
        self.assertEqual(
            info["cast_names"], "Salman Khan, Atul Kapoor, Vijay Vikram Singh")
        self.assertEqual(info["language"], "Hindi")
        self.assertEqual(info["release_year"], 2026)
        self.assertTrue(
            info["synopsis"].startswith(
                "Bigg Boss is a reality show based on the original Dutch Big Brother."))
        self.assertNotIn("SCREENSHOTS", info["synopsis"])
        self.assertNotIn("🖼", info["synopsis"])
        self.assertIn("MKV", info["raw_meta"])
        self.assertIn("format", info["raw_meta"])

    def test_zero_rating_is_dropped_not_saved(self):
        content = SAMPLE_MD_WP_POST_RICH["content"]["rendered"].replace(
            "IMDb Rating:- 3.6/10", "IMDb Rating:- 0.0/10")
        info = import_moviesdrive.parse_moviesdrive_series_info(content)
        self.assertIsNone(info["imdb_rating"])

    def test_director_and_country_and_original_language(self):
        content = """
        <p>Series Info: \U0001f4ab IMDb Rating:- 7.9/10 \U0001f308 Series Name: Final Table
        Season: 1 \U0001f30d Country: South Korea \U0001f5e3 Original Language: Korean
        \U0001f50a Language: Hindi \u2013 Korean \U0001f4c5 Released Year: 2026
        \U0001f46eDirector/Creator: Kim Someone \u2b50Stars: Lee One, Park Two
        \u2666 Storyline- A cooking contest.</p>
        """
        info = import_moviesdrive.parse_moviesdrive_series_info(content)
        self.assertEqual(info["imdb_rating"], "7.9")
        self.assertEqual(info["director"], "Kim Someone")
        self.assertEqual(info["country"], "South Korea")
        self.assertEqual(info["original_language"], "Korean")
        self.assertEqual(info["language"], "Hindi + Korean")
        self.assertEqual(info["cast_names"], "Lee One, Park Two")
        self.assertEqual(info["synopsis"], "A cooking contest.")
        self.assertIn("Korean", info["raw_meta"])

    def test_no_series_info_block_is_all_none(self):
        info = import_moviesdrive.parse_moviesdrive_series_info(
            "<p>Just a plain paragraph, no info block.</p>")
        self.assertIsNone(info["imdb_rating"])
        self.assertIsNone(info["director"])
        self.assertIsNone(info["cast_names"])
        self.assertIsNone(info["synopsis"])
        self.assertIsNone(info["raw_meta"])

    def test_categories_keep_real_genres_only(self):
        genres = import_moviesdrive.extract_moviesdrive_categories(
            SAMPLE_MD_WP_POST_RICH)
        self.assertEqual(genres, ["Drama"])

    def test_imdb_id_from_content_link(self):
        self.assertEqual(
            import_moviesdrive.extract_moviesdrive_imdb_id(
                SAMPLE_MD_WP_POST_RICH["content"]["rendered"]),
            "tt1281973")
        self.assertIsNone(
            import_moviesdrive.extract_moviesdrive_imdb_id("<p>no link</p>"))


class TestMoviesDriveRichPost(unittest.TestCase):
    def _rich_data(self):
        archive_pages = {
            "https://mdrive.lol/archive/18530/": SAMPLE_MD_ARCH_BB_480_HTML,
            "https://mdrive.lol/archive/18529/": SAMPLE_MD_ARCH_BB_720_HTML,
        }
        data = import_moviesdrive.extract_post_data_moviesdrive(
            SAMPLE_MD_WP_POST_RICH, archive_pages)
        self.assertIsNotNone(data)
        return data

    def test_rich_post_populates_metadata_fields(self):
        data = self._rich_data()
        self.assertEqual(data["year"], 2026)
        self.assertTrue(data["clean_title"].startswith("Bigg Boss"))
        # Poster: the TMDB image from the post body (was ~11% before).
        self.assertEqual(
            data["poster_url"],
            "https://image.tmdb.org/t/p/w500/k5ope1puShzLycqcj3FIoGAPjva.jpg")
        # Backdrops: the catimages screenshots (poster excluded).
        backdrops = json.loads(data["backdrop_urls"])
        self.assertIn(
            "https://catimages.org/images/2026/09/07/Vlcsnap.mkv-frame024377.jpg",
            backdrops)
        self.assertNotIn(data["poster_url"], backdrops)
        # Synopsis: the Storyline text, not the SEO blurb.
        self.assertIn("reality show based on the original Dutch Big Brother",
                      data["synopsis"])
        self.assertNotIn("SCREENSHOTS", data["synopsis"])
        # Genres: DRAMA category survives; Dual Audio / Hotstar do not.
        self.assertEqual(json.loads(data["genres"]), ["Drama"])
        self.assertEqual(data["imdb_rating"], "3.6")
        self.assertEqual(
            data["cast_names"], "Salman Khan, Atul Kapoor, Vijay Vikram Singh")
        self.assertIsNone(data["director"])
        self.assertEqual(data["imdb_id"], "tt1281973")
        self.assertEqual(data["language"], "Hindi")
        # The source carries no duration or release date: NULL, not made up.
        self.assertIsNone(data["duration"])
        self.assertIsNone(data["release_date"])
        self.assertIsNotNone(data["raw_meta"])
        # Releases still work exactly as before.
        self.assertEqual(len(data["releases"]), 2)

    def test_rich_post_saves_metadata_to_staging_db(self):
        data = self._rich_data()
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviesdrive.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            post_id = import_moviesdrive.save_post_record(data, db_path)
            with sqlite3.connect(str(db_path)) as conn:
                conn.row_factory = sqlite3.Row
                row = conn.execute(
                    "SELECT poster_url, synopsis, genres, imdb_rating,"
                    " cast_names, imdb_id, duration, release_date, raw_meta"
                    " FROM posts WHERE id = ?", (post_id,)
                ).fetchone()
            self.assertIn("image.tmdb.org", row["poster_url"])
            self.assertIn("reality show", row["synopsis"])
            self.assertEqual(json.loads(row["genres"]), ["Drama"])
            self.assertEqual(row["imdb_rating"], "3.6")
            self.assertIn("Salman Khan", row["cast_names"])
            self.assertEqual(row["imdb_id"], "tt1281973")
            self.assertIsNone(row["duration"])
            self.assertIsNone(row["release_date"])
            self.assertIn("format", row["raw_meta"])


class TestMoviesDriveSync(unittest.TestCase):
    def test_sync_walks_wp_json_pages(self):
        client = MagicMock()
        client.wp_posts_page.side_effect = [
            ([SAMPLE_MD_WP_POST], 2),
            ([dict(SAMPLE_MD_WP_POST, id=65290, slug="scene-2026",
                   link="https://new1.moviesdrive.cyou/scene-2026/")], 2),
        ]
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviesdrive.db"
            n = import_moviesdrive.sync_posts_to_db(client, db_path)
            self.assertEqual(n, 2)
            with sqlite3.connect(str(db_path)) as conn:
                rows = conn.execute(
                    "SELECT loc, site FROM sitemap_cache WHERE site = 'moviesdrive'"
                ).fetchall()
            self.assertEqual(len(rows), 2)


class TestMoviesDriveRegistry(unittest.TestCase):
    def test_registry_entry_points_at_live_wp_json(self):
        reg_path = Path(__file__).parent / "domain_registry.json"
        reg = json.loads(reg_path.read_text())
        entry = reg["sites"]["moviesdrive"]
        self.assertEqual(entry["active_base_url"], "https://new4.moviesdrive.cyou")
        self.assertEqual(entry["api_type"], "wp_rest_api")
        self.assertEqual(entry["api_endpoint"], "/wp-json/wp/v2/posts")
        self.assertEqual(entry["status"], "active")


# --- Regression tests for the 2026-11-12 zero-persistence incident ----------
# A stale base (new2) 302s to new3 and drops the query string, so wp-json
# answered its default latest-posts list; process_one_url took found[0]
# unchecked and all 104 tasks saved the same post (Jailer 2). The tests
# below pin the three fixes: verify the slug, retry once on the live host,
# and mark the QUEUE loc scraped (not the fetched URL).

JAILER2_POST = {
    "id": 65295,
    "slug": "jailer-2-2026-web-dl-hindi-dd5-1-tamil-480p-720p-1080p-2160p-4k-sdr-x264-esubs-full-movie",
    "link": "https://new3.moviesdrive.cyou/jailer-2-2026-web-dl-hindi-dd5-1-tamil-480p-720p-1080p-2160p-4k-sdr-x264-esubs-full-movie/",
    "title": {"rendered": "Jailer 2 (2026) DS4K WEB-DL Hindi DD5.1 Tamil 480p 720p 1080p 2160p 4K SDR x264 ESubs Full Movie"},
    "content": {"rendered": "<p>No archives here</p>"},
}

WANTED_POST = {
    "id": 65290,
    "slug": "some-film-2026-web-dl-hindi-480p-x264-esubs",
    "link": "https://new3.moviesdrive.cyou/some-film-2026-web-dl-hindi-480p-x264-esubs/",
    "title": {"rendered": "Some Film (2026) WEB-DL Hindi 480p x264 ESubs"},
    "content": {"rendered": "<p>No archives here</p>"},
}


class FakeWpClient:
    """Minimal stand-in for MoviesDriveClient: canned _get answers by slug."""

    def __init__(self, base_url, mapping, request_interval=0.0):
        self.base_url = base_url
        self.request_interval = request_interval
        self.mapping = mapping  # slug -> (status, body)
        self.calls = []

    def _get(self, url, accept, timeout=20):
        self.calls.append(url)
        slug = urllib.parse.parse_qs(
            urllib.parse.urlparse(url).query).get("slug", [""])[0]
        status, body = self.mapping.get(slug, (0, ""))
        return status, {}, body


class TestSlugLookup(unittest.TestCase):
    def test_matching_slug_returns_the_post(self):
        client = FakeWpClient("https://new3.moviesdrive.cyou", {
            WANTED_POST["slug"]: (200, json.dumps([WANTED_POST])),
        })
        post, error, link = import_moviesdrive.lookup_post_by_slug(
            client, WANTED_POST["slug"])
        self.assertIsNone(error)
        self.assertEqual(post["slug"], WANTED_POST["slug"])
        self.assertEqual(link, WANTED_POST["link"])

    def test_mismatched_slug_is_rejected_not_saved(self):
        # The 2026-11-12 failure: the query was dropped, wp-json answered its
        # default list, and found[0] (Jailer 2) was saved for another slug.
        client = FakeWpClient("https://new2.moviesdrive.cyou", {
            WANTED_POST["slug"]: (200, json.dumps([JAILER2_POST])),
        })
        post, error, link = import_moviesdrive.lookup_post_by_slug(
            client, WANTED_POST["slug"])
        self.assertIsNone(post)
        self.assertIn("slug mismatch", error)
        self.assertIn("jailer-2", error)
        self.assertEqual(link, JAILER2_POST["link"])

    def test_empty_list_is_post_not_found(self):
        client = FakeWpClient("https://new3.moviesdrive.cyou", {
            "gone-2026": (200, "[]"),
        })
        post, error, _ = import_moviesdrive.lookup_post_by_slug(client, "gone-2026")
        self.assertIsNone(post)
        self.assertEqual(error, "post not found in wp-json")

    def test_non_200_is_a_transport_error(self):
        client = FakeWpClient("https://new3.moviesdrive.cyou", {
            "some-film-2026": (404, ""),
        })
        post, error, _ = import_moviesdrive.lookup_post_by_slug(client, "some-film-2026")
        self.assertIsNone(post)
        self.assertIn("HTTP 404", error)

    def test_unparsable_body_is_an_error(self):
        client = FakeWpClient("https://new3.moviesdrive.cyou", {
            "some-film-2026": (200, "<html>not json</html>"),
        })
        post, error, _ = import_moviesdrive.lookup_post_by_slug(client, "some-film-2026")
        self.assertIsNone(post)
        self.assertIn("unparsable", error)


class TestFetchPostRetry(unittest.TestCase):
    def test_retry_on_live_host_after_rotation(self):
        stale = FakeWpClient("https://new2.moviesdrive.cyou", {
            WANTED_POST["slug"]: (200, json.dumps([JAILER2_POST])),
        })
        live = FakeWpClient("https://new3.moviesdrive.cyou", {
            WANTED_POST["slug"]: (200, json.dumps([WANTED_POST])),
        })
        factory_calls = []
        registry_calls = []

        def factory(base_url, request_interval=0.0):
            factory_calls.append(base_url)
            return live

        def updater(site, changes):
            registry_calls.append((site, changes))

        post, error = import_moviesdrive.fetch_post_for_pending_url(
            stale, WANTED_POST["slug"],
            client_factory=factory, registry_updater=updater)
        self.assertIsNone(error)
        self.assertEqual(post["slug"], WANTED_POST["slug"])
        self.assertEqual(factory_calls, ["https://new3.moviesdrive.cyou"])
        self.assertEqual(len(registry_calls), 1)
        self.assertEqual(registry_calls[0][0], "moviesdrive")
        self.assertEqual(registry_calls[0][1]["active_base_url"],
                         "https://new3.moviesdrive.cyou")

    def test_retry_that_also_mismatches_fails_loudly(self):
        stale = FakeWpClient("https://new2.moviesdrive.cyou", {
            WANTED_POST["slug"]: (200, json.dumps([JAILER2_POST])),
        })
        still_stale = FakeWpClient("https://new3.moviesdrive.cyou", {
            WANTED_POST["slug"]: (200, json.dumps([JAILER2_POST])),
        })
        post, error = import_moviesdrive.fetch_post_for_pending_url(
            stale, WANTED_POST["slug"],
            client_factory=lambda base_url, request_interval=0.0: still_stale,
            registry_updater=lambda site, changes: None)
        self.assertIsNone(post)
        self.assertIn("slug mismatch", error)
        self.assertIn("also failed", error)

    def test_mismatch_without_a_live_host_hint_does_not_retry(self):
        no_link = dict(JAILER2_POST, link="")
        stale = FakeWpClient("https://new2.moviesdrive.cyou", {
            WANTED_POST["slug"]: (200, json.dumps([no_link])),
        })
        called = []

        def factory(base_url, request_interval=0.0):
            called.append(base_url)
            raise AssertionError("must not retry without a host hint")

        post, error = import_moviesdrive.fetch_post_for_pending_url(
            stale, WANTED_POST["slug"],
            client_factory=factory, registry_updater=lambda s, c: None)
        self.assertIsNone(post)
        self.assertIn("slug mismatch", error)
        self.assertEqual(called, [])

    def test_transport_failure_does_not_retry(self):
        client = FakeWpClient("https://new3.moviesdrive.cyou", {})
        called = []

        def factory(base_url, request_interval=0.0):
            called.append(base_url)
            raise AssertionError("must not retry a transport failure")

        post, error = import_moviesdrive.fetch_post_for_pending_url(
            client, "some-film-2026",
            client_factory=factory, registry_updater=lambda s, c: None)
        self.assertIsNone(post)
        self.assertIn("HTTP 0", error)
        self.assertEqual(called, [])


def _post_data_for_rotation(url):
    """SAMPLE post data re-pointed at another domain (same slug/title)."""
    post = dict(SAMPLE_MD_WP_POST)
    post["link"] = url
    archive_pages = {
        "https://mdrive.lol/archive/25388/": SAMPLE_MD_ARCH_480_HTML,
        "https://mdrive.lol/archive/25387/": SAMPLE_MD_ARCH_720_HTML,
    }
    return import_moviesdrive.extract_post_data_moviesdrive(post, archive_pages)


class TestQueueLocScrapedFlag(unittest.TestCase):
    def test_save_marks_the_pending_loc_not_the_fetched_url(self):
        # Pending loc is on the old domain; the fetched post links the new one.
        pending_loc = "https://new2.moviesdrive.cyou/wwe-crown-jewel-2026-2026-web-dl-hindi-dd2-0-english-480p-720p-1080p-x264-esubs-full-movie/"
        data = _post_data_for_rotation(
            "https://new3.moviesdrive.cyou/wwe-crown-jewel-2026-2026-web-dl-hindi-dd2-0-english-480p-720p-1080p-x264-esubs-full-movie/")
        self.assertIsNotNone(data)
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviesdrive.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            with sqlite3.connect(str(db_path)) as conn:
                conn.execute(
                    "INSERT INTO sitemap_cache (loc, slug, scraped, site) VALUES (?, ?, 0, 'moviesdrive')",
                    (pending_loc, data["slug"]),
                )
            post_id = import_moviesdrive.save_post_record(
                data, db_path, queue_loc=pending_loc)
            self.assertGreater(post_id, 0)
            with sqlite3.connect(str(db_path)) as conn:
                scraped = conn.execute(
                    "SELECT scraped FROM sitemap_cache WHERE loc = ?", (pending_loc,)
                ).fetchone()[0]
            self.assertEqual(scraped, 1)

    def test_resave_across_rotation_does_not_duplicate_releases(self):
        slug = "wwe-crown-jewel-2026-2026-web-dl-hindi-dd2-0-english-480p-720p-1080p-x264-esubs-full-movie"
        data_old = _post_data_for_rotation(f"https://new2.moviesdrive.cyou/{slug}/")
        data_new = _post_data_for_rotation(f"https://new3.moviesdrive.cyou/{slug}/")
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviesdrive.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            id1 = import_moviesdrive.save_post_record(data_old, db_path)
            id2 = import_moviesdrive.save_post_record(data_new, db_path)
            self.assertEqual(id1, id2)
            with sqlite3.connect(str(db_path)) as conn:
                n_rel = conn.execute(
                    "SELECT count(*) FROM releases WHERE post_id = ?", (id1,)
                ).fetchone()[0]
                n_src = conn.execute(
                    "SELECT count(*) FROM download_sources "
                    "WHERE release_id IN (SELECT id FROM releases WHERE post_id = ?)",
                    (id1,),
                ).fetchone()[0]
            self.assertEqual(n_rel, 2)
            self.assertEqual(n_src, 2)

    def test_sibling_page_releases_survive_a_resave(self):
        # Two pages of the same work (same title/year, different slugs) share
        # one post; re-saving one must not delete the other's releases.
        def post_for(slug, title, arch_id, arch_html):
            return import_moviesdrive.extract_post_data_moviesdrive(
                {
                    "id": 1,
                    "slug": slug,
                    "link": f"https://new3.moviesdrive.cyou/{slug}/",
                    "title": {"rendered": title},
                    "content": {"rendered": f'<p><a href="https://mdrive.lol/archive/{arch_id}/">DL</a></p>'},
                },
                {f"https://mdrive.lol/archive/{arch_id}/": arch_html},
            )

        data_a = post_for("film-2026-web-dl-hindi-480p-x264",
                          "Film (2026) WEB-DL Hindi 480p x264",
                          25388, SAMPLE_MD_ARCH_480_HTML)
        data_b = post_for("film-2026-web-dl-hindi-720p-x264",
                          "Film (2026) WEB-DL Hindi 720p x264",
                          25387, SAMPLE_MD_ARCH_720_HTML)
        self.assertIsNotNone(data_a)
        self.assertIsNotNone(data_b)
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviesdrive.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            id_a = import_moviesdrive.save_post_record(data_a, db_path)
            id_b = import_moviesdrive.save_post_record(data_b, db_path)
            self.assertEqual(id_a, id_b)
            with sqlite3.connect(str(db_path)) as conn:
                before = conn.execute(
                    "SELECT count(*) FROM releases WHERE post_id = ?", (id_a,)
                ).fetchone()[0]
            self.assertEqual(before, 2)
            import_moviesdrive.save_post_record(data_a, db_path)
            with sqlite3.connect(str(db_path)) as conn:
                rows = conn.execute(
                    "SELECT quality, origin_url FROM releases WHERE post_id = ? ORDER BY quality",
                    (id_a,),
                ).fetchall()
            self.assertEqual([r[0] for r in rows], ["480p", "720p"])
            self.assertTrue(all("film-2026-web-dl-hindi-480p" in r[1] for r in rows if r[0] == "480p"))
            self.assertTrue(all("film-2026-web-dl-hindi-720p" in r[1] for r in rows if r[0] == "720p"))


class TestDedupeDuplicateReleases(unittest.TestCase):
    def test_dedupe_keeps_newest_and_drops_old_domain_copy(self):
        data = _post_data_for_rotation(
            "https://new2.moviesdrive.cyou/wwe-crown-jewel-2026-2026-web-dl-hindi-dd2-0-english-480p-720p-1080p-x264-esubs-full-movie/")
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviesdrive.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            post_id = import_moviesdrive.save_post_record(data, db_path)
            with sqlite3.connect(str(db_path)) as conn:
                conn.row_factory = sqlite3.Row
                first = conn.execute(
                    "SELECT id, release_title FROM releases WHERE post_id = ? ORDER BY id LIMIT 1",
                    (post_id,),
                ).fetchone()
                # Simulate the pre-fix residue: the same release re-saved from
                # the new domain without the old copy being deleted.
                conn.execute(
                    "INSERT INTO releases (post_id, release_title, quality, source_site, origin_url)"
                    " VALUES (?, ?, '480p', 'moviesdrive', ?)",
                    (post_id, first["release_title"],
                     "https://new3.moviesdrive.cyou/wwe-crown-jewel-2026-2026-web-dl-hindi-dd2-0-english-480p-720p-1080p-x264-esubs-full-movie/"),
                )
                dup_id = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
                conn.execute(
                    "INSERT INTO download_sources (release_id, source_name, service, source_url)"
                    " VALUES (?, 'Dup', 'hubcloud', 'https://hubcloud.test/dup')",
                    (dup_id,),
                )
                conn.commit()
                before = conn.execute(
                    "SELECT count(*) FROM releases WHERE post_id = ?", (post_id,)
                ).fetchone()[0]
            self.assertEqual(before, 3)
            stats = import_moviesdrive.dedupe_duplicate_releases(db_path)
            self.assertEqual(stats, {"groups": 1, "releases_deleted": 1, "sources_deleted": 1})
            with sqlite3.connect(str(db_path)) as conn:
                rows = conn.execute(
                    "SELECT id, origin_url FROM releases WHERE post_id = ? ORDER BY id",
                    (post_id,),
                ).fetchall()
                n_src = conn.execute("SELECT count(*) FROM download_sources").fetchone()[0]
            self.assertEqual(len(rows), 2)
            self.assertTrue(any("new3.moviesdrive.cyou" in r[1] for r in rows))
            self.assertEqual(n_src, 2)

    def test_dedupe_is_a_noop_without_cross_domain_dupes(self):
        data = _post_data_for_rotation(
            "https://new3.moviesdrive.cyou/wwe-crown-jewel-2026-2026-web-dl-hindi-dd2-0-english-480p-720p-1080p-x264-esubs-full-movie/")
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviesdrive.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            import_moviesdrive.save_post_record(data, db_path)
            stats = import_moviesdrive.dedupe_duplicate_releases(db_path)
            self.assertEqual(stats, {"groups": 0, "releases_deleted": 0, "sources_deleted": 0})

    def test_dedupe_removes_same_origin_dupes(self):
        # Rev-9 follow-up: the 21 same-origin groups the old dedupe
        # skipped (hosts > 1 filter). Same (post_id, release_title) and
        # the SAME origin_url — two archive pages, one title, one save.
        data = _post_data_for_rotation(
            "https://new3.moviesdrive.cyou/wwe-crown-jewel-2026-2026-web-dl-hindi-dd2-0-english-480p-720p-1080p-x264-esubs-full-movie/")
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviesdrive.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            post_id = import_moviesdrive.save_post_record(data, db_path)
            with sqlite3.connect(str(db_path)) as conn:
                conn.row_factory = sqlite3.Row
                first = conn.execute(
                    "SELECT id, release_title, origin_url FROM releases WHERE post_id = ? ORDER BY id LIMIT 1",
                    (post_id,),
                ).fetchone()
                # Simulate the pre-fix residue: the same release saved
                # twice from the SAME origin URL.
                conn.execute(
                    "INSERT INTO releases (post_id, release_title, quality, source_site, origin_url)"
                    " VALUES (?, ?, '480p', 'moviesdrive', ?)",
                    (post_id, first["release_title"], first["origin_url"]),
                )
                dup_id = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
                conn.execute(
                    "INSERT INTO download_sources (release_id, source_name, service, source_url)"
                    " VALUES (?, 'Dup', 'hubcloud', 'https://hubcloud.test/dup')",
                    (dup_id,),
                )
                conn.commit()
                before = conn.execute(
                    "SELECT count(*) FROM releases WHERE post_id = ?", (post_id,)
                ).fetchone()[0]
            self.assertEqual(before, 3)
            stats = import_moviesdrive.dedupe_duplicate_releases(db_path)
            self.assertEqual(stats, {"groups": 1, "releases_deleted": 1, "sources_deleted": 1})
            with sqlite3.connect(str(db_path)) as conn:
                rows = conn.execute(
                    "SELECT id, origin_url FROM releases WHERE post_id = ? ORDER BY id",
                    (post_id,),
                ).fetchall()
                n_src = conn.execute("SELECT count(*) FROM download_sources").fetchone()[0]
            self.assertEqual(len(rows), 2)
            self.assertTrue(any("new3.moviesdrive.cyou" in r[1] for r in rows))
            self.assertEqual(n_src, 2)

    def test_dedupe_keeps_distinct_titles_untouched(self):
        data = _post_data_for_rotation(
            "https://new3.moviesdrive.cyou/wwe-crown-jewel-2026-2026-web-dl-hindi-dd2-0-english-480p-720p-1080p-x264-esubs-full-movie/")
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviesdrive.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            post_id = import_moviesdrive.save_post_record(data, db_path)
            stats = import_moviesdrive.dedupe_duplicate_releases(db_path)
            self.assertEqual(stats, {"groups": 0, "releases_deleted": 0, "sources_deleted": 0})
            with sqlite3.connect(str(db_path)) as conn:
                n_rel = conn.execute("SELECT count(*) FROM releases WHERE post_id = ?", (post_id,)).fetchone()[0]
            self.assertEqual(n_rel, 2)


# --- Direct-hubcloud shape (2026-11-13) -------------------------------------
# Newer MoviesDrive posts link hubcloud.ist/drive/search-recover.php directly,
# one link per quality, instead of mdrive.lol/archive pages. The fixtures
# below are the live shape (Eye for an Eye 2 / The Pitt, probed from the
# Oracle VM 2026-11-13 02:15 PKT), re-pointed at the reserved hubcloud.test
# host with synthetic from_ac tokens; the `q` values are the real base64
# payloads ("Eye for an Eye 2 2024 480p", "The Pitt 480p zip", ...).

_TOK = lambda ch: ch * 60  # synthetic from_ac token (never validated)

SAMPLE_MD_WP_POST_DIRECT = {
    "id": 65296,
    "slug": "eye-for-an-eye-2-2024-bluray-hindi-dd5-1-english-480p-720p-1080p-x264-esubs-full-movie",
    "link": "https://new3.moviesdrive.cyou/eye-for-an-eye-2-2024-bluray-hindi-dd5-1-english-480p-720p-1080p-x264-esubs-full-movie/",
    "title": {"rendered": "Eye for an Eye 2 (2024) BluRay [Hindi (DD5.1) & English] 480p | 720p | 1080p [x264/ESubs] | Full Movie"},
    "content": {"rendered": """
        <h4>-: DOWNLOAD LINKS :-</h4><hr>
        <p><a href="https://moviesdrives.mov/">MoviesDrives.mov</a></p>
        <p><a href="https://t.me/moviesdrivee">Telegram</a></p>
        <h5 style="text-align: center;">Eye for an Eye 2 (2024) BluRay [Hindi (DD5.1) &amp; English] 480p [310.53 MB]</h5>
        <h5 style="text-align: center;"><a href="https://hubcloud.test/drive/search-recover.php?from_ac=%s&amp;q=RXllIGZvciBhbiBFeWUgMiAyMDI0IDQ4MHA" target="_blank" rel="noopener noreferrer">480p [310.53 MB]</a></h5>
        <h5 style="text-align: center;">Eye for an Eye 2 (2024) BluRay [Hindi (DD5.1) &amp; English] 720p x264 [908.16 MB]</h5>
        <h5 style="text-align: center;"><a href="https://hubcloud.test/drive/search-recover.php?from_ac=%s&amp;q=RXllIGZvciBhbiBFeWUgMiAyMDI0IDcyMHA" target="_blank" rel="noopener noreferrer">720p x264 [908.16 MB]</a></h5>
        <h5 style="text-align: center;">Eye for an Eye 2 (2024) BluRay [Hindi (DD5.1) &amp; English] 1080p x264 [1.61 GB]</h5>
        <h5 style="text-align: center;"><a href="https://hubcloud.test/drive/search-recover.php?from_ac=%s&amp;q=RXllIGZvciBhbiBFeWUgMiAyMDI0IDEwODBw" target="_blank" rel="noopener noreferrer">1080p x264 [1.61 GB]</a></h5>
    """ % (_TOK("A"), _TOK("B"), _TOK("C"))},
    "date": "2026-11-12T10:00:00",
}

SAMPLE_MD_WP_POST_DIRECT_SERIES = {
    "id": 65297,
    "slug": "the-pitt-season-2-2026-dual-audio-hindi-english-s01-ep01-added-hbo-series-480p-720p-1080p-web-dl",
    "link": "https://new3.moviesdrive.cyou/the-pitt-season-2-2026-dual-audio-hindi-english-s01-ep01-added-hbo-series-480p-720p-1080p-web-dl/",
    "title": {"rendered": "The Pitt (Season 2) 2026 Dual Audio [Hindi – English] (S02 Ep15 Added) HBO Series 480p 720p 1080p 2160p 4K – WEB-DL"},
    "content": {"rendered": """
        <h5 style="text-align: center;">The Pitt (Season 2) [Ep15 Added] [Hindi – English] 480p x264 [250MB/E]</h5>
        <h5 style="text-align: center;"><a href="https://hubcloud.test/drive/search-recover.php?from_ac=%s&amp;q=VGhlIFBpdHQgNDgwcA">480p Single Episode</a></h5>
        <h5 style="text-align: center;">The Pitt (Season 2) [Ep15 Added] [Hindi – English] 480p x264 [3.01GB]</h5>
        <h5 style="text-align: center;"><a href="https://hubcloud.test/drive/search-recover.php?from_ac=%s&amp;q=VGhlIFBpdHQgNDgwcCB6aXA">Zip [3.01GB]</a></h5>
        <h5 style="text-align: center;">The Pitt (Season 2) [Ep15 Added] [Hindi – English] 720p x264 [500MB/E]</h5>
        <h5 style="text-align: center;"><a href="https://hubcloud.test/drive/search-recover.php?from_ac=%s&amp;q=VGhlIFBpdHQgNzIwcA">720p Single Episode</a></h5>
        <h5 style="text-align: center;">The Pitt (Season 2) [Ep15 Added] [Hindi – English] 720p x264 [6.74GB]</h5>
        <h5 style="text-align: center;"><a href="https://hubcloud.test/drive/search-recover.php?from_ac=%s&amp;q=VGhlIFBpdHQgNzIwcCB6aXA">Zip [6.74GB]</a></h5>
        <h5 style="text-align: center;">The Pitt (Season 2) [Ep15 Added] [Hindi – English] 1080p x264 [900MB/E]</h5>
        <h5 style="text-align: center;"><a href="https://hubcloud.test/drive/search-recover.php?from_ac=%s&amp;q=VGhlIFBpdHQgMTA4MHA">1080p Single Episode</a></h5>
        <h5 style="text-align: center;">The Pitt (Season 2) [Ep15 Added] [Hindi – English] 1080p x264 [14.86GB]</h5>
        <h5 style="text-align: center;"><a href="https://hubcloud.test/drive/search-recover.php?from_ac=%s&amp;q=VGhlIFBpdHQgMTA4MHAgemlw">Zip [14.86GB]</a></h5>
        <h5 style="text-align: center;">The Pitt (Season 2) [Ep15 Added] [Hindi – English] 4K x264 [250MB/E]</h5>
        <h5 style="text-align: center;"><a href="https://hubcloud.test/drive/search-recover.php?from_ac=%s&amp;q=VGhlIFBpdHQgNEs">4K 2160p Single Episode</a></h5>
    """ % (_TOK("D"), _TOK("E"), _TOK("F"), _TOK("G"), _TOK("H"), _TOK("I"), _TOK("J"))},
    "date": "2026-11-12T10:00:00",
}


class TestDirectHubcloudExtract(unittest.TestCase):
    def test_direct_links_one_per_quality_with_size_and_codec(self):
        links = import_moviesdrive.extract_direct_hubcloud_links(
            SAMPLE_MD_WP_POST_DIRECT["content"]["rendered"])
        self.assertEqual(len(links), 3)
        by_quality = {g["quality"]: g for g in links}
        self.assertEqual(by_quality["480p"]["size"], "310.53 MB")
        self.assertEqual(by_quality["480p"]["codec"], "")
        self.assertEqual(by_quality["720p"]["size"], "908.16 MB")
        self.assertEqual(by_quality["720p"]["codec"], "X264")
        self.assertEqual(by_quality["1080p"]["size"], "1.61 GB")
        self.assertEqual(by_quality["1080p"]["codec"], "X264")
        for g in links:
            self.assertEqual(len(g["sources"]), 1)
            src = g["sources"][0]
            self.assertEqual(src["service"], "hubcloud")
            self.assertEqual(src["playback_type"], "download")
            self.assertTrue(src["source_url"].startswith(
                "https://hubcloud.test/drive/search-recover.php?"))
            self.assertEqual(src["final_url"], src["source_url"])
        self.assertEqual(by_quality["480p"]["sources"][0]["resource_id"],
                         "Eye for an Eye 2 2024 480p")
        self.assertEqual(by_quality["480p"]["sources"][0]["source_name"],
                         "480p [310.53 MB]")

    def test_direct_links_series_zip_and_4k(self):
        links = import_moviesdrive.extract_direct_hubcloud_links(
            SAMPLE_MD_WP_POST_DIRECT_SERIES["content"]["rendered"])
        self.assertEqual(len(links), 7)
        by_key = {(g["quality"], g["is_zip"]): g for g in links}
        self.assertFalse(by_key[("480p", False)]["is_zip"])
        self.assertTrue(by_key[("480p", True)]["is_zip"])
        self.assertEqual(by_key[("480p", True)]["size"], "3.01GB")
        self.assertEqual(by_key[("720p", True)]["size"], "6.74GB")
        self.assertEqual(by_key[("1080p", True)]["size"], "14.86GB")
        # Bare "4K" in the anchor/`q` normalises to the 2160p tier, and the
        # size/codec come from the <h5> heading (the anchor carries none).
        self.assertIn(("2160p", False), by_key)
        self.assertEqual(by_key[("2160p", False)]["size"], "250MB/E")
        self.assertEqual(by_key[("2160p", False)]["codec"], "X264")
        # The zip quality comes from `q` when the anchor text has none.
        self.assertEqual(by_key[("720p", True)]["sources"][0]["resource_id"],
                         "The Pitt 720p zip")

    def test_direct_links_ignore_mdrive_and_noise(self):
        # The classic fixture carries mdrive archive links, Telegram and
        # IMDb — none of them is a direct-hubcloud link.
        links = import_moviesdrive.extract_direct_hubcloud_links(
            SAMPLE_MD_WP_POST["content"]["rendered"])
        self.assertEqual(links, [])

    def test_direct_link_without_any_quality_is_dropped(self):
        content = (
            '<h5><a href="https://hubcloud.test/drive/search-recover.php'
            '?from_ac=AAAA&amp;q=U29tZSBUaXRsZQ">Download</a></h5>'
        )
        self.assertEqual(
            import_moviesdrive.extract_direct_hubcloud_links(content), [])

    def test_direct_links_same_file_twice_is_one_release_two_sources(self):
        content = """
            <h5>Film (2026) 480p [100 MB]</h5>
            <h5><a href="https://hubcloud.test/drive/search-recover.php?from_ac=%s&amp;q=RmlsbSAyMDI2IDQ4MHA">480p [100 MB]</a></h5>
            <h5><a href="https://hubcloud.test/drive/search-recover.php?from_ac=%s&amp;q=RmlsbSAyMDI2IDQ4MHA">480p [100 MB]</a></h5>
        """ % (_TOK("K"), _TOK("L"))
        links = import_moviesdrive.extract_direct_hubcloud_links(content)
        self.assertEqual(len(links), 1)
        self.assertEqual(len(links[0]["sources"]), 2)
        self.assertNotEqual(links[0]["sources"][0]["source_url"],
                            links[0]["sources"][1]["source_url"])


class TestDirectHubcloudPostData(unittest.TestCase):
    def test_direct_only_post_yields_one_release_per_quality(self):
        data = import_moviesdrive.extract_post_data_moviesdrive(
            SAMPLE_MD_WP_POST_DIRECT, {})
        self.assertIsNotNone(data)
        self.assertEqual(data["source_site"], "moviesdrive")
        self.assertEqual(data["year"], 2024)
        self.assertEqual(data["clean_title"], "Eye for an Eye 2 (2024)")
        self.assertEqual(data["content_type"], "movie")
        self.assertEqual(len(data["releases"]), 3)
        by_quality = {r["quality"]: r for r in data["releases"]}
        self.assertEqual(by_quality["480p"]["size"], "310.53 MB")
        self.assertEqual(by_quality["720p"]["size"], "908.16 MB")
        self.assertEqual(by_quality["720p"]["codec"], "X264")
        self.assertEqual(by_quality["1080p"]["size"], "1.61 GB")
        for r in data["releases"]:
            self.assertEqual(r["source_site"], "moviesdrive")
            self.assertEqual(len(r["sources"]), 1)
            self.assertEqual(r["sources"][0]["service"], "hubcloud")
            self.assertEqual(r["sources"][0]["playback_type"], "download")
            self.assertTrue(r["intermediate_url"].startswith(
                "https://hubcloud.test/drive/search-recover.php?"))

    def test_direct_series_post_zip_is_batch_and_season_is_kept(self):
        data = import_moviesdrive.extract_post_data_moviesdrive(
            SAMPLE_MD_WP_POST_DIRECT_SERIES, {})
        self.assertIsNotNone(data)
        self.assertEqual(data["content_type"], "series")
        self.assertEqual(len(data["releases"]), 7)
        by_key = {}
        for r in data["releases"]:
            by_key[(r["quality"], r.get("episode"))] = r
        self.assertEqual(by_key[("480p", "Batch")]["size"], "3.01GB")
        self.assertEqual(by_key[("720p", "Batch")]["size"], "6.74GB")
        self.assertEqual(by_key[("1080p", "Batch")]["size"], "14.86GB")
        self.assertEqual(by_key[("2160p", None)]["quality"], "2160p")
        for r in data["releases"]:
            self.assertEqual(r["season"], "S02")

    def test_direct_only_post_without_quality_is_none(self):
        post = dict(SAMPLE_MD_WP_POST_DIRECT)
        post["content"] = {"rendered":
            '<h5><a href="https://hubcloud.test/drive/search-recover.php'
            '?from_ac=AAAA&amp;q=U29tZSBUaXRsZQ">Download</a></h5>'}
        self.assertIsNone(
            import_moviesdrive.extract_post_data_moviesdrive(post, {}))

    def test_mixed_post_keeps_mdrive_and_direct_releases(self):
        archive_pages = {
            "https://mdrive.lol/archive/25388/": SAMPLE_MD_ARCH_480_HTML,
        }
        post = dict(SAMPLE_MD_WP_POST)
        post["content"] = {"rendered": SAMPLE_MD_WP_POST["content"]["rendered"] + """
            <h5>WWE Crown Jewel (2026) 1080p x264 [1.61 GB]</h5>
            <h5><a href="https://hubcloud.test/drive/search-recover.php?from_ac=%s&amp;q=V1dFIENyb3duIEpld2VsIDIwMjYgMTA4MHA">1080p x264 [1.61 GB]</a></h5>
        """ % _TOK("M")}
        data = import_moviesdrive.extract_post_data_moviesdrive(post, archive_pages)
        self.assertIsNotNone(data)
        qualities = sorted(r["quality"] for r in data["releases"])
        self.assertEqual(qualities, ["1080p", "480p"])
        services = {r["quality"]: r["sources"][0]["service"]
                    for r in data["releases"]}
        self.assertEqual(services["480p"], "hubcloud")
        self.assertEqual(services["1080p"], "hubcloud")
        titles = [r["release_title"] for r in data["releases"]]
        self.assertEqual(len(titles), len(set(titles)))


class TestDirectHubcloudSave(unittest.TestCase):
    def test_saved_direct_only_post_and_resave_does_not_duplicate(self):
        data = import_moviesdrive.extract_post_data_moviesdrive(
            SAMPLE_MD_WP_POST_DIRECT, {})
        self.assertIsNotNone(data)
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviesdrive.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            post_id = import_moviesdrive.save_post_record(data, db_path)
            self.assertGreater(post_id, 0)
            import_moviesdrive.save_post_record(data, db_path)
            with sqlite3.connect(str(db_path)) as conn:
                n_rel = conn.execute(
                    "SELECT count(*) FROM releases WHERE post_id = ?",
                    (post_id,)).fetchone()[0]
                n_src = conn.execute(
                    "SELECT count(*) FROM download_sources").fetchone()[0]
                rows = conn.execute(
                    "SELECT quality, intermediate_url FROM releases "
                    "WHERE post_id = ? ORDER BY quality", (post_id,)).fetchall()
            self.assertEqual(n_rel, 3)
            self.assertEqual(n_src, 3)
            self.assertEqual([r[0] for r in rows], ["1080p", "480p", "720p"])
            self.assertTrue(all("search-recover.php" in r[1] for r in rows))


# --- WP REST adoption tests (2026-11-23) -----------------------------------
# Live moviesdrive fixtures (posts 64643 Final Table and 62666 Bigg Boss,
# fetched from the Oracle VM 2026-11-23): the "Series Info:" block carries
# "IMDb Rating:- N.N/10", "Director/Creator:", "Stars:", "Episode Size:",
# "Quality:", a "Storyline-" (dash, not colon) synopsis, and a SCREENSHOTS
# heading after it. The full block is pinned in test_wp_rest.py; here the
# extractor is pinned end to end (labels -> saved post).

MD_REST_POST = {
    "id": 64643,
    "slug": "final-table-season-1-2026-hindi-korean-ep08-added",
    "link": "https://new4.moviesdrive.cyou/final-table-season-1-2026-hindi-korean-ep08-added/",
    "title": {"rendered": "Final Table (Season 1) 2026 [Hindi \u2013 Korean] "
              "(Ep08 Added) Korean Drama 480p 720p 1080p \u2013 WEB-DL"},
    "content": {"rendered": """
        <p><img src="https://image.tmdb.org/t/p/original/uaMjtALuvZC7dGkYllGKKWEWW1J.jpg" /></p>
        <p>Series Info: \U0001f4ab IMDb Rating:- 0.0/10 \U0001f308 Series Name: Final Table Season: 1 \U0001f30d Country: South Korea \U0001f5e3\ufe0f Original Language: Korean \U0001f50a Language: Hindi \u2013 Korean \U0001f4c5 Released Year: 2026 \U0001f46eDirector/Creator: Kim Do-hoon \u2b50Stars: Ahn Hyo-seop, Hong Hwa-yeon, Chang Ryul, Eugene Jung, Jang Young-nam, Cha Ji-yeon \U0001f4bf Episode Size: 240MB || 310MB || 950MB \u2013 Each Episode \U0001f3acQuality: 480p || 720p || 1080p \u2013 WEB-DL \U0001f4c1 Format: MKV \u2666 Storyline- The Table: K-Chef 2026 brings together Korea\u2019s top chefs to compete for the title of the country\u2019s best chef. \U0001f5bc\ufe0f SCREENSHOTS \u2013 Final Table (Season 1) WEB-DL [ Hindi \u2013 Korean ] 480p x264 [240MB/E] 480p Single Episode</p>
        <p><a href="https://mdrive.lol/archive/24104/">480p Download</a></p>
    """},
    "excerpt": {"rendered": "<p>\u2611\ufe0f Download Final Table (2026) Season 1 K-Drama [Hindi \u2013 Korean] Complete All Episodes Available in 480p, 720p &amp; 1080p qualities.</p>"},
    "_embedded": {"wp:term": [[
        {"id": 1, "name": "DRAMA", "taxonomy": "category"},
        {"id": 2, "name": "Dual Audio", "taxonomy": "category"},
        {"id": 3, "name": "Hindi Dubbed", "taxonomy": "category"},
        {"id": 4, "name": "K Drama", "taxonomy": "category"},
        {"id": 5, "name": "Television", "taxonomy": "category"},
    ]]},
    "date": "2026-11-08T10:00:00",
}

MD_REST_POST_BIGG_BOSS = {
    "id": 62666,
    "slug": "bigg-boss-season-20-2026-hindi-dd2-0-s20-ep78-added",
    "link": "https://new4.moviesdrive.cyou/bigg-boss-season-20-2026-hindi-dd2-0-s20-ep78-added/",
    "title": {"rendered": "Bigg Boss (Season 20) 2026 [Hindi DD2.0] "
              "(S20 Ep78 Added) JioHotstar Series 480p 720p 1080p "
              "\u2013 WEB-DL"},
    "content": {"rendered": """
        <p>Series Info: \U0001f4ab IMDb Rating:- 3.6/10 \U0001f308 Series Name: Bigg Boss Season: 20 \U0001f50a Language: {Hindi DD2.0} \U0001f4c5 Released Year: 2026 \U0001f46eDirector/Creator:  \u2b50Stars: Salman Khan, Atul Kapoor, Vijay Vikram Singh \U0001f4bf Episode Size:  \u2013 Each Episode \U0001f3acQuality: 480p || 720p || 1080p \u2013 WEB-DL \U0001f4c1 Format: MKV \u2666 Storyline- Bigg Boss is a reality show based on the original Dutch Big Brother. A number of contestants live in a purpose-built house. \U0001f5bc\ufe0f SCREENSHOTS \u2013 Bigg Boss (Season 20) \u2013 Watch Free</p>
        <p><a href="https://mdrive.lol/archive/24102/">720p Download</a></p>
    """},
    "excerpt": {"rendered": "<p>\u2611\ufe0f Download Bigg Boss (2026) Season 20 WEB Series [Hindi] Complete All Episodes Available in 720p HEVC | 1080p HEVC qualities.</p>"},
    "_embedded": {"wp:term": [[
        {"id": 1, "name": "DRAMA", "taxonomy": "category"},
        {"id": 2, "name": "Hotstar", "taxonomy": "category"},
    ]]},
    "date": "2026-11-08T10:00:00",
}


class TestMoviesdriveRestExtract(unittest.TestCase):
    def test_final_table_full_metadata_from_rest_post(self):
        archive_pages = {
            "https://mdrive.lol/archive/24104/": SAMPLE_MD_ARCH_480_HTML,
        }
        data = import_moviesdrive.extract_post_data_moviesdrive(
            MD_REST_POST, archive_pages)
        self.assertIsNotNone(data)
        self.assertEqual(
            data["poster_url"],
            "https://image.tmdb.org/t/p/original/"
            "uaMjtALuvZC7dGkYllGKKWEWW1J.jpg")
        self.assertTrue(data["synopsis"].startswith(
            "The Table: K-Chef 2026 brings together"))
        self.assertNotIn("SCREENSHOTS", data["synopsis"])
        self.assertEqual(json.loads(data["genres"]), ["Drama", "K Drama"])
        # 0.0/10 is the site's "not rated" placeholder — never banked.
        self.assertIsNone(data["imdb_rating"])
        self.assertEqual(
            data["cast_names"],
            "Ahn Hyo-seop, Hong Hwa-yeon, Chang Ryul, Eugene Jung, "
            "Jang Young-nam, Cha Ji-yeon")
        self.assertEqual(data["director"], "Kim Do-hoon")
        self.assertEqual(data["language"], "Hindi + Korean")
        self.assertEqual(len(data["releases"]), 1)
        self.assertEqual(data["releases"][0]["size"], "658.47 MB")
        self.assertEqual(data["releases"][0]["languages"], "Hindi + Korean")

    def test_bigg_boss_rating_cast_and_language(self):
        archive_pages = {
            "https://mdrive.lol/archive/24102/": SAMPLE_MD_ARCH_720_HTML,
        }
        data = import_moviesdrive.extract_post_data_moviesdrive(
            MD_REST_POST_BIGG_BOSS, archive_pages)
        self.assertIsNotNone(data)
        self.assertEqual(data["imdb_rating"], 3.6)
        # "Director/Creator:" is empty on this post — nothing banked.
        self.assertIsNone(data["director"])
        self.assertEqual(
            data["cast_names"],
            "Salman Khan, Atul Kapoor, Vijay Vikram Singh")
        self.assertEqual(data["language"], "Hindi")
        self.assertTrue(data["synopsis"].startswith(
            "Bigg Boss is a reality show"))
        self.assertNotIn("SCREENSHOTS", data["synopsis"])

    def test_languages_from_labels(self):
        fn = import_moviesdrive._languages_from_labels
        self.assertEqual(fn({"languages": "Hindi \u2013 Korean"}),
                         "Hindi + Korean")
        self.assertEqual(fn({"languages": "{Hindi DD2.0}"}), "Hindi")
        self.assertEqual(fn({"languages": "Hindi (ORG 5.1)"}), "Hindi")
        self.assertEqual(fn({}), "")
        self.assertEqual(fn({"languages": ""}), "")


class TestMoviesdriveSyncRawJson(unittest.TestCase):
    def test_sync_stores_raw_json(self):
        client = MagicMock()
        client.wp_posts_page.side_effect = [([SAMPLE_MD_WP_POST], 1)]
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviesdrive.db"
            n = import_moviesdrive.sync_posts_to_db(client, db_path)
            self.assertEqual(n, 1)
            with sqlite3.connect(str(db_path)) as conn:
                raw = conn.execute(
                    "SELECT raw_json FROM sitemap_cache").fetchone()[0]
            post = json.loads(raw)
            self.assertEqual(post["id"], SAMPLE_MD_WP_POST["id"])
            self.assertEqual(post["slug"], SAMPLE_MD_WP_POST["slug"])
            self.assertIn("content", post)


class TestWorkerUsesStoredRawJson(unittest.TestCase):
    def test_no_slug_lookup_when_raw_json_present(self):
        # Queue one row carrying the full REST post; the worker must
        # extract from it without any wp-json slug lookup.
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "moviesdrive.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            import_moviesdrive._ensure_sitemap_raw_json(db_path)
            with sqlite3.connect(str(db_path)) as conn:
                conn.execute(
                    "INSERT INTO sitemap_cache "
                    "(loc, lastmod, slug, scraped, site, raw_json) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    (MD_REST_POST["link"], None, MD_REST_POST["slug"], 0,
                     "moviesdrive",
                     json.dumps(MD_REST_POST, ensure_ascii=False)),
                )
                conn.commit()
            calls = {"slug_lookups": 0, "archive_fetches": 0}

            class FakeClient:
                def __init__(self, base_url, request_interval=0.0):
                    self.base_url = base_url

                def _get(self, url, accept, timeout=20):
                    # Any wp-json call here is a slug lookup — must not happen.
                    calls["slug_lookups"] += 1
                    raise AssertionError(f"unexpected wp-json call: {url}")

                def get_text(self, url, referer=None, timeout=20):
                    calls["archive_fetches"] += 1
                    return 200, url, SAMPLE_MD_ARCH_480_HTML

                def wp_posts_page(self, page=1, per_page=100):
                    raise AssertionError("sync must not run with pending rows")

            real_client = import_moviesdrive.MoviesDriveClient
            real_status = import_moviesdrive.STATUS_FILE
            import_moviesdrive.MoviesDriveClient = FakeClient
            import_moviesdrive.STATUS_FILE = Path(td) / "status.json"
            try:
                import_moviesdrive.run_moviesdrive_scraper(
                    db_path=db_path, limit=1, workers=1, delay=0.0)
            finally:
                import_moviesdrive.MoviesDriveClient = real_client
                import_moviesdrive.STATUS_FILE = real_status
            self.assertEqual(calls["slug_lookups"], 0)
            self.assertEqual(calls["archive_fetches"], 1)
            with sqlite3.connect(str(db_path)) as conn:
                conn.row_factory = sqlite3.Row
                row = conn.execute(
                    "SELECT poster_url, synopsis, genres, imdb_rating, "
                    "cast_names, director, language FROM posts"
                ).fetchone()
                self.assertIsNotNone(row)
                self.assertEqual(
                    row["poster_url"],
                    "https://image.tmdb.org/t/p/original/"
                    "uaMjtALuvZC7dGkYllGKKWEWW1J.jpg")
                self.assertTrue(row["synopsis"].startswith(
                    "The Table: K-Chef 2026"))
                self.assertEqual(json.loads(row["genres"]),
                                 ["Drama", "K Drama"])
                self.assertIsNone(row["imdb_rating"])
                self.assertIn("Ahn Hyo-seop", row["cast_names"])
                self.assertEqual(row["director"], "Kim Do-hoon")
                self.assertEqual(row["language"], "Hindi + Korean")
                scraped = conn.execute(
                    "SELECT scraped FROM sitemap_cache").fetchone()[0]
            self.assertEqual(scraped, 1)


class TestWpPostsPageMethod(unittest.TestCase):
    def test_wp_posts_page_is_a_real_client_method(self):
        # Regression: wp_posts_page used to be nested inside
        # fetch_post_for_pending_url (dead code after its return), so the
        # real --sync-posts path crashed with AttributeError while the
        # MagicMock sync test passed. Pin it on the class with a stubbed
        # transport: one page, total_pages honoured, _embed=1, no _fields.
        client = import_moviesdrive.MoviesDriveClient(
            "https://new4.moviesdrive.cyou")
        seen = {}

        def fake_get(url, accept, timeout=20):
            seen["url"] = url
            return 200, {"X-WP-TotalPages": "98"}, json.dumps(
                [SAMPLE_MD_WP_POST])

        client._get = fake_get
        posts, total_pages = client.wp_posts_page(1)
        self.assertEqual(total_pages, 98)
        self.assertEqual(len(posts), 1)
        self.assertEqual(posts[0]["id"], SAMPLE_MD_WP_POST["id"])
        self.assertIn("_embed=1", seen["url"])
        self.assertNotIn("_fields=", seen["url"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
