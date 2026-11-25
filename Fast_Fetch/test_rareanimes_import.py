#!/usr/bin/env python3
"""
test_rareanimes_import.py — Comprehensive unit tests for RareAnimes dedicated importer.

Tests:
  1. Title cleaning: Season detection, year extraction, canonical key generation.
  2. Content parsing: Info block extraction (Full Name, Season, Release Year, Synopsis).
  3. Episode & language release parsing: Hindi, Tamil, Telugu multi-audio splits.
  4. Stream unwrapping: PixelDrain direct CDN (api/file/) and HubCloud mapping.
  5. Staging SQLite database isolation and persistence.

100% Python Standard Library. Zero external dependencies.
"""

import json
import os
import sqlite3
import tempfile
import unittest
from pathlib import Path

import fast_fetch
import import_rareanimes


SAMPLE_EPISODE_CONTENT = """
<p>✅ Download Blue Box Season 2 Dual Audio {Hindi-Tamil-Telugu} In 480p &amp; 720p &amp; 1080p.</p>
<h5><span style="color: #ff0000;">Anime Series Info</span></h5>
<p>📰 Full Name: <span style="color: #339966;">Blue Box</span></p>
<p>🍂Season: <span style="color: #339966;">02</span></p>
<p>🎞Episodes: <span style="color: #339966;">24</span></p>
<p>🕓Release Year: <span style="color: #339966;">2026</span></p>
<p>🎭 Genre: <span style="color: #339966;">Animation and Drama</span></p>
<p>🔊Language: <span style="color: #339966;">Hindi, Tamil, Telugu</span></p>
<p>🎬Quality: <span style="color: #339966;">(480p, 720p, 1080p)</span></p>
<p><span style="color: #333399;"><span style="color: #ff0000;">Synopsis</span> <span style="color: #339966;">Badminton player Taiki admires basketball star Chinatsu.</span></span></p>
<hr />
<p>Episode 01 &#8211; Deja Vu</p>
<p><span style="color: #99cc00;">Hindi</span> – [<a href="https://hubcloud.ist/drive/nwryeqtyffgrhfv">HubCloud</a>] [<a href="https://pixeldrain.com/u/Ni52JxnV">DLBeta</a>] [<a href="https://mega.nz/file/iXJijBaB#abc">Mega</a>]</p>
<p><span style="color: #ff00ff;">Tamil </span>– [<a href="https://hubcloud.ist/drive/lecvdirs1ssv1cm">HubCloud</a>] [<a href="https://pixeldrain.com/u/EvUcka1z">DLBeta</a>]</p>
<p><span style="color: #00ccff;">Telugu </span>– [<a href="https://hubcloud.ist/drive/01asnensiabos1w">HubCloud</a>] [<a href="https://pixeldrain.com/u/jRdiaVPC">DLBeta</a>]</p>
<hr />
<p>Episode 02 &#8211; Admiration</p>
<p><span style="color: #99cc00;">Hindi</span> – [<a href="https://hubcloud.ist/drive/yoypi1yoiyff9jy">HubCloud</a>] [<a href="https://pixeldrain.com/u/5pzi5eWW">DLBeta</a>]</p>
"""

SAMPLE_MOVIE_CONTENT = """
<p>✅ Download The Iron Giant (1999) Movie BluRay Full Movie (Hindi+English).</p>
<h5><span style="color: #ff0000;">Movie Info</span></h5>
<p>📰 Full Name: <span style="color: #339966;">The Iron Giant</span></p>
<p>🕓Release Year: <span style="color: #339966;">1999</span></p>
<p>🔊Language: <span style="color: #339966;">Hindi</span></p>
<p><span style="color: #ff0000;">Synopsis</span> <span style="color: #339966;">A boy befriends a giant robot from outer space.</span></p>
<hr />
<h4 style="text-align: center;"><span style="color: #ff00ff;">Hindi &#8211; Download</span></h4>
<h3><a href="https://hubcloud.ist/drive/j00ejb0tylrlqo7">HubCloud</a></h3>
<h3><a href="https://pixeldrain.com/u/LmVxqSTH">DLBeta</a></h3>
<h3><a href="https://mega.nz/file/eaAkCBKS#xyz">Mega</a></h3>
"""

SAMPLE_RAW_POST_JSON = {
    "id": 12345,
    "title": {"rendered": "Blue Box Season 2 Hindi Episodes Download HD"},
    "link": "https://www.rareanimes.mov/blue-box-season-2-hindi-episodes-download-hd/",
    "slug": "blue-box-season-2-hindi-episodes-download-hd",
    "content": {"rendered": SAMPLE_EPISODE_CONTENT},
    "_embedded": {
        "wp:featuredmedia": [
            {"source_url": "https://www.rareanimes.mov/wp-content/uploads/2026/10/blue-box.png"}
        ],
        "wp:term": [
            [{"name": "Animes"}, {"name": "Hindi Dub"}]
        ]
    }
}


class TestTitleCleaning(unittest.TestCase):
    """Tests for clean_rareanimes_title."""

    def test_series_with_season(self):
        title, year, season, key = import_rareanimes.clean_rareanimes_title("Blue Box Season 2 Hindi Episodes Download HD")
        self.assertEqual(title, "Blue Box")
        self.assertEqual(season, 2)
        self.assertIn("blue-box", key)
        self.assertTrue(key.endswith("_s2"))

    def test_movie_with_year(self):
        title, year, season, key = import_rareanimes.clean_rareanimes_title("The Iron Giant (1999) Movie Hindi Dubbed Download HD")
        self.assertEqual(title, "The Iron Giant")
        self.assertEqual(year, 1999)
        self.assertIsNone(season)
        self.assertIn("the-iron-giant_1999", key)

    def test_multi_audio_title(self):
        title, year, season, key = import_rareanimes.clean_rareanimes_title("Ranma1/2 Season 3 Hindi Dubbed Episodes Download HD")
        self.assertEqual(season, 3)
        self.assertIn("ranma", key)


class TestContentParsing(unittest.TestCase):
    """Tests for parse_rareanimes_content."""

    def test_info_block_extraction(self):
        info, releases = import_rareanimes.parse_rareanimes_content(SAMPLE_EPISODE_CONTENT)
        self.assertEqual(info["full_name"], "Blue Box")
        self.assertEqual(info["season"], "02")
        self.assertEqual(info["episodes"], "24")
        self.assertEqual(info["year"], "2026")
        self.assertIn("Drama", info["genre"])
        self.assertIn("Taiki", info["synopsis"])

    def test_episode_releases_extraction(self):
        info, releases = import_rareanimes.parse_rareanimes_content(SAMPLE_EPISODE_CONTENT)
        self.assertGreaterEqual(len(releases), 4)

        # Check Episode 1 Hindi
        ep1_hi = next(r for r in releases if r["episode_number"] == 1 and "Hindi" in r["audio_languages"])
        self.assertEqual(ep1_hi["season_number"], 2)
        mirrors = dict(ep1_hi["mirrors"])
        self.assertIn("DLBeta", mirrors)
        self.assertIn("HubCloud", mirrors)
        self.assertIn("Mega", mirrors)
        self.assertEqual(mirrors["DLBeta"], "https://pixeldrain.com/u/Ni52JxnV")

        # Check Episode 1 Tamil
        ep1_ta = next(r for r in releases if r["episode_number"] == 1 and "Tamil" in r["audio_languages"])
        self.assertTrue(any("pixeldrain.com/u/EvUcka1z" in m[1] for m in ep1_ta["mirrors"]))

    def test_movie_content_parsing(self):
        info, releases = import_rareanimes.parse_rareanimes_content(SAMPLE_MOVIE_CONTENT)
        self.assertEqual(info["full_name"], "The Iron Giant")
        self.assertEqual(info["year"], "1999")
        self.assertEqual(len(releases), 1)
        r0 = releases[0]
        self.assertIsNone(r0["episode_number"])
        mirrors = dict(r0["mirrors"])
        self.assertIn("DLBeta", mirrors)
        self.assertEqual(mirrors["DLBeta"], "https://pixeldrain.com/u/LmVxqSTH")


class TestPostDataExtraction(unittest.TestCase):
    """Tests for extract_post_data_rareanimes."""

    def test_series_post_extraction(self):
        data = import_rareanimes.extract_post_data_rareanimes(
            post_url="https://www.rareanimes.mov/blue-box-season-2-hindi-episodes-download-hd/",
            raw_post_json=SAMPLE_RAW_POST_JSON,
            resolve_direct=False,
        )
        self.assertIsNotNone(data)
        self.assertEqual(data["title"], "Blue Box")
        self.assertEqual(data["media_type"], "series")
        self.assertEqual(data["poster_url"], "https://www.rareanimes.mov/wp-content/uploads/2026/10/blue-box.png")

        # Verify PixelDrain direct CDN resolution
        pd_sources = [s for r in data["releases"] for s in r["sources"] if s["service"] == "pixeldrain"]
        self.assertGreater(len(pd_sources), 0)
        self.assertTrue(pd_sources[0]["final_url"].startswith("https://pixeldrain.com/api/file/"))
        self.assertEqual(pd_sources[0]["is_temporary"], 0)

        # Genres come from the content Genre: label ("Animation and
        # Drama" -> two genres), not the raw WP categories — "Animes"
        # and "Hindi Dub" are junk buckets, never genres.
        genres = json.loads(data["genres"])
        self.assertEqual(set(genres), {"Animation", "Drama"})
        self.assertNotIn("Animes", genres)
        self.assertNotIn("Hindi Dub", genres)

        # No fabricated quality/codec: the episode blocks state no
        # quality, so releases bank None; audio comes from the info
        # block's Language: (Hindi, Tamil, Telugu).
        for rel in data["releases"]:
            self.assertIsNone(rel["quality"])
            self.assertIsNone(rel["codec"])
        self.assertEqual(
            json.loads(data["releases"][0]["audio_languages"]), ["Hindi"])


# Live-shape REST posts, fetched 23 Nov 2026 from
# https://www.rareanimes.foo/wp-json/wp/v2/posts?per_page=2&_embed=1
# (post 28899 Tokyo Revengers, post 80052 Eminence in Shadow).
# The Tokyo post's excerpt AND info block belong to another show — the
# site pastes the wrong block in. The excerpt is Eminence text on a
# Tokyo post; the block names Eminence with Tokyo's plot attached.
TOKYO_REVENGERS_CONTENT = """
<p>📰 Full Name: <span style="color: #339966;">The Eminence in Shadow</span></p>
<p>🍂Season: <span style="color: #339966;">02</span></p>
<p>🎞Episodes: <span style="color: #339966;">13</span></p>
<p>🕓Release Year: <span style="color: #339966;">2021</span></p>
<p>🎭 Genre: <span style="color: #339966;">Animation, Action &amp; Adventure, Crime, and Drama</span></p>
<p>🔊Language: <span style="color: #339966;">Hindi</span></p>
<p>🎬Quality: <span style="color: #339966;">(480p, 720p, 1080p)</span></p>
<p><span style="color: #ff0000;">Synopsis</span><span style="color: #333399;">: <span style="color: #339966;">Takemichi Hanagaki is a freelancer who decides to go back in time to save his girlfriend.</span></span></p>
<hr />
<p>Episode 01 &#8211; Reborn</p>
<p><span style="color: #99cc00;">Hindi</span> – [<a href="https://pixeldrain.com/u/TokyoEp01">DLBeta</a>]</p>
"""

TOKYO_RAW_POST_JSON = {
    "id": 28899,
    "title": {"rendered": "Tokyo Revengers Season 2 Hindi Dubbed Episodes Download HD"},
    "link": "https://www.rareanimes.foo/tokyo-revengers-season-2-hindi-dubbed-episodes-download-hd/",
    "slug": "tokyo-revengers-season-2-hindi-dubbed-episodes-download-hd",
    "content": {"rendered": TOKYO_REVENGERS_CONTENT},
    "excerpt": {"rendered": "<p>Shadowbrokers are those who go unnoticed, pretending to be unremarkable people.</p>"},
    "_embedded": {
        "wp:featuredmedia": [
            {"id": 28901,
             "source_url": "https://www.rareanimes.foo/wp-content/uploads/2023/01/Tokyo-Revengers-Episodes-in-Hindi-Subbed-Download-1080p-FHD.jpg",
             "media_type": "image", "mime_type": "image/jpeg"}
        ],
        "wp:term": [
            [{"name": "Animes"}, {"name": "COMPLETED"}, {"name": "Hindi Anime"},
             {"name": "Hindi Dub"}, {"name": "Hindi Sub"}]
        ]
    }
}

EMINENCE_CONTENT = """
<p>📰 Full Name: <span style="color: #339966;">The Eminence in Shadow</span></p>
<p>🍂Season: <span style="color: #339966;">02</span></p>
<p>🎞Episodes: <span style="color: #339966;">12</span></p>
<p>🕓Release Year: <span style="color: #339966;">2023</span></p>
<p>🎭 Genre: <span style="color: #339966;">Animation, Comedy, Sci-Fi &amp; Fantasy, and Action &amp; Adventure</span></p>
<p>🔊Language: <span style="color: #339966;">Hindi</span></p>
<p>🎬Quality: <span style="color: #339966;">(480p, 720p, 1080p)</span></p>
<p><span style="color: #ff0000;">Synopsis</span><span style="color: #333399;">: <span style="color: #339966;">Shadowbrokers are those who go unnoticed, pretending to be unremarkable people.</span></span></p>
<hr />
<p>Episode 01 &#8211; The Outlaw</p>
<p><span style="color: #99cc00;">Hindi</span> – [<a href="https://pixeldrain.com/u/EminenceEp01">DLBeta</a>]</p>
"""

EMINENCE_RAW_POST_JSON = {
    "id": 80052,
    "title": {"rendered": "The Eminence in Shadow Season 2 Hindi Dubbed Episodes Download HD"},
    "link": "https://www.rareanimes.foo/the-eminence-in-shadow-season-2-hindi-dubbed-episodes-download-hd/",
    "slug": "the-eminence-in-shadow-season-2-hindi-dubbed-episodes-download-hd",
    "content": {"rendered": EMINENCE_CONTENT},
    "excerpt": {"rendered": "<p>Shadowbrokers are those who go unnoticed, pretending to be unremarkable people.</p>"},
    "_embedded": {
        "wp:featuredmedia": [
            {"id": 80058,
             "source_url": "https://www.rareanimes.foo/wp-content/uploads/2023/01/Eminence-in-Shadow-Season-2-Hindi-Dubbed.jpg",
             "media_type": "image", "mime_type": "image/jpeg"}
        ],
        "wp:term": [
            [{"name": "Animes"}, {"name": "Hindi Anime"}, {"name": "Hindi Dub"}]
        ]
    }
}

# A post whose info block names a DIFFERENT show with a different
# season — the block's season must never reach the releases.
WRONG_BLOCK_CONTENT = """
<p>📰 Full Name: <span style="color: #339966;">Other Show</span></p>
<p>🍂Season: <span style="color: #339966;">09</span></p>
<p>🎞Episodes: <span style="color: #339966;">5</span></p>
<p>🕓Release Year: <span style="color: #339966;">2020</span></p>
<p>🎭 Genre: <span style="color: #339966;">Drama</span></p>
<p>🔊Language: <span style="color: #339966;">Hindi</span></p>
<p><span style="color: #ff0000;">Synopsis</span> <span style="color: #339966;">Some plot.</span></p>
<hr />
<p>Episode 01 &#8211; First</p>
<p><span style="color: #99cc00;">Hindi</span> – [<a href="https://pixeldrain.com/u/WrongBlock01">DLBeta</a>]</p>
"""

WRONG_BLOCK_POST_JSON = {
    "id": 99901,
    "title": {"rendered": "Show X Season 1 Hindi Dubbed Episodes Download HD"},
    "link": "https://www.rareanimes.foo/show-x-season-1-hindi-dubbed-episodes-download-hd/",
    "slug": "show-x-season-1-hindi-dubbed-episodes-download-hd",
    "content": {"rendered": WRONG_BLOCK_CONTENT},
    "excerpt": {"rendered": "<p>Some plot.</p>"},
    "_embedded": {
        "wp:featuredmedia": [
            {"id": 99902,
             "source_url": "https://www.rareanimes.foo/wp-content/uploads/2023/01/show-x.jpg",
             "media_type": "image", "mime_type": "image/jpeg"}
        ],
        "wp:term": [[{"name": "Animes"}]]
    }
}

MULTI_LANG_CONTENT = """
<p>📰 Full Name: <span style="color: #339966;">Multi Lang Show</span></p>
<p>🔊Language: <span style="color: #339966;">Hindi, Tamil</span></p>
<p><span style="color: #ff0000;">Synopsis</span> <span style="color: #339966;">A show in two languages.</span></p>
<hr />
<p><a href="https://pixeldrain.com/u/MultiLang1">Download</a></p>
"""

MULTI_LANG_POST_JSON = {
    "id": 99903,
    "title": {"rendered": "Multi Lang Show Hindi Dubbed Episodes Download HD"},
    "link": "https://www.rareanimes.foo/multi-lang-show-hindi-dubbed-episodes-download-hd/",
    "slug": "multi-lang-show-hindi-dubbed-episodes-download-hd",
    "content": {"rendered": MULTI_LANG_CONTENT},
    "excerpt": {"rendered": "<p>A show in two languages.</p>"},
    "_embedded": {"wp:featuredmedia": [], "wp:term": [[{"name": "Animes"}]]}
}


class TestRareanimesRestExtract(unittest.TestCase):
    """Live-shape REST posts: wrong-title excerpt, trust guard, poster."""

    def test_tokyo_revengers_wrong_title_block(self):
        data = import_rareanimes.extract_post_data_rareanimes(
            post_url="https://www.rareanimes.foo/tokyo-revengers-season-2-hindi-dubbed-episodes-download-hd/",
            raw_post_json=TOKYO_RAW_POST_JSON,
            resolve_direct=False,
        )
        self.assertIsNotNone(data)
        # The info block names another show — the WP title is the
        # authority, so the block's name/year are distrusted.
        self.assertEqual(data["title"], "Tokyo Revengers")
        self.assertIsNone(data["year"])
        # The excerpt is the other show's text — it must never leak in.
        self.assertTrue(data["synopsis"].startswith("Takemichi Hanagaki"))
        self.assertNotIn("Shadowbrokers", data["synopsis"])
        self.assertEqual(json.loads(data["genres"]),
                         ["Animation", "Action & Adventure", "Crime", "Drama"])
        self.assertEqual(data["poster_url"],
                         "https://www.rareanimes.foo/wp-content/uploads/2023/01/Tokyo-Revengers-Episodes-in-Hindi-Subbed-Download-1080p-FHD.jpg")
        self.assertEqual(len(data["releases"]), 1)
        rel = data["releases"][0]
        self.assertEqual(rel["episode_number"], 1)
        self.assertEqual(rel["season_number"], 2)
        self.assertIsNone(rel["quality"])
        self.assertIsNone(rel["codec"])
        self.assertEqual(json.loads(rel["audio_languages"]), ["Hindi"])

    def test_eminence_in_shadow_trusted_block(self):
        data = import_rareanimes.extract_post_data_rareanimes(
            post_url="https://www.rareanimes.foo/the-eminence-in-shadow-season-2-hindi-dubbed-episodes-download-hd/",
            raw_post_json=EMINENCE_RAW_POST_JSON,
            resolve_direct=False,
        )
        self.assertIsNotNone(data)
        self.assertEqual(data["title"], "The Eminence in Shadow")
        self.assertEqual(data["year"], 2023)
        self.assertTrue(data["synopsis"].startswith("Shadowbrokers"))
        self.assertEqual(json.loads(data["genres"]),
                         ["Animation", "Comedy", "Sci-Fi & Fantasy",
                          "Action & Adventure"])
        self.assertEqual(len(data["releases"]), 1)
        self.assertEqual(data["releases"][0]["season_number"], 2)

    def test_distrusted_block_season_not_banked(self):
        data = import_rareanimes.extract_post_data_rareanimes(
            post_url="https://www.rareanimes.foo/show-x-season-1-hindi-dubbed-episodes-download-hd/",
            raw_post_json=WRONG_BLOCK_POST_JSON,
            resolve_direct=False,
        )
        self.assertIsNotNone(data)
        self.assertEqual(data["title"], "Show X")
        self.assertIsNone(data["year"])
        # The block says season 09; the title says season 1 — the
        # title wins on the release too.
        self.assertEqual(data["releases"][0]["season_number"], 1)

    def test_poster_validation_rejects_non_image_featured_media(self):
        post = dict(MULTI_LANG_POST_JSON)
        post["title"] = {"rendered": "Poster Test Show Hindi Dubbed Episodes Download HD"}
        post["content"] = {"rendered": MULTI_LANG_CONTENT.replace(
            "Multi Lang Show", "Poster Test Show") + (
                '<p><img src="https://www.rareanimes.foo/wp-content/uploads/2023/01/real-poster.jpg" /></p>')}
        post["_embedded"] = {"wp:featuredmedia": [
            {"id": 1, "media_type": "video", "mime_type": "video/mp4",
             "source_url": "https://www.rareanimes.foo/wp-content/uploads/2023/01/index-clip.mp4"}],
            "wp:term": [[{"name": "Animes"}]]}
        data = import_rareanimes.extract_post_data_rareanimes(
            post_url="https://www.rareanimes.foo/poster-test-show/",
            raw_post_json=post, resolve_direct=False)
        self.assertIsNotNone(data)
        # The video "featured media" is rejected; the content image wins.
        self.assertEqual(data["poster_url"],
                         "https://www.rareanimes.foo/wp-content/uploads/2023/01/real-poster.jpg")

        post2 = dict(MULTI_LANG_POST_JSON)
        post2["_embedded"] = {"wp:featuredmedia": [], "wp:term": []}
        data2 = import_rareanimes.extract_post_data_rareanimes(
            post_url="https://www.rareanimes.foo/multi-lang-show/",
            raw_post_json=post2, resolve_direct=False)
        self.assertIsNotNone(data2)
        self.assertIsNone(data2["poster_url"])

    def test_multi_language_info_block(self):
        data = import_rareanimes.extract_post_data_rareanimes(
            post_url="https://www.rareanimes.foo/multi-lang-show-hindi-dubbed-episodes-download-hd/",
            raw_post_json=MULTI_LANG_POST_JSON,
            resolve_direct=False,
        )
        self.assertIsNotNone(data)
        self.assertEqual(len(data["releases"]), 1)
        rel = data["releases"][0]
        self.assertEqual(json.loads(rel["audio_languages"]), ["Hindi", "Tamil"])
        self.assertIsNone(rel["quality"])
        self.assertIsNone(rel["codec"])


class TestStagingDatabase(unittest.TestCase):
    """Tests for staging database schema and persistence."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test_rareanimes.db"
        import_rareanimes.init_staging_db(self.db_path)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_schema_created(self):
        with sqlite3.connect(str(self.db_path)) as conn:
            tables = [r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()]
        self.assertIn("sitemap_cache", tables)
        self.assertIn("posts", tables)
        self.assertIn("releases", tables)
        self.assertIn("sources", tables)

    def test_save_post(self):
        post_data = {
            "canonical_key": "blue-box_s2",
            "title": "Blue Box",
            "original_title": "Blue Box Season 2 Hindi Episodes Download HD",
            "year": 2026,
            "media_type": "series",
            "poster_url": "https://www.rareanimes.mov/poster.png",
            "synopsis": "Taiki badminton anime",
            "genres": json.dumps(["Animes", "Sports"]),
            "site": "rareanimes",
            "post_url": "https://www.rareanimes.mov/blue-box/",
            "releases": [
                {
                    "label": "Episode 1 [Hindi]",
                    "quality": "720p",
                    "resolution": "720p",
                    "season_number": 2,
                    "episode_number": 1,
                    "audio_languages": json.dumps(["Hindi"]),
                    "codec": "x264",
                    "bridge_url": "https://pixeldrain.com/u/Ni52JxnV",
                    "sources": [
                        {
                            "source_name": "⚡ PixelDrain [Direct CDN]",
                            "service": "pixeldrain",
                            "resource_id": "Ni52JxnV",
                            "source_url": "https://pixeldrain.com/u/Ni52JxnV",
                            "final_url": "https://pixeldrain.com/api/file/Ni52JxnV",
                            "is_temporary": 0,
                            "quality": "720p",
                        }
                    ],
                }
            ],
        }

        ok = import_rareanimes.save_post_to_staging_db(self.db_path, post_data)
        self.assertTrue(ok)

        with sqlite3.connect(str(self.db_path)) as conn:
            p_cnt = conn.execute("SELECT COUNT(*) FROM posts").fetchone()[0]
            r_cnt = conn.execute("SELECT COUNT(*) FROM releases").fetchone()[0]
            s_cnt = conn.execute("SELECT COUNT(*) FROM sources").fetchone()[0]
            pd_src = conn.execute("SELECT final_url FROM sources WHERE service = 'pixeldrain'").fetchone()[0]

        self.assertEqual(p_cnt, 1)
        self.assertEqual(r_cnt, 1)
        self.assertEqual(s_cnt, 1)
        self.assertEqual(pd_src, "https://pixeldrain.com/api/file/Ni52JxnV")

    def test_upsert_existing_post(self):
        post1 = {
            "canonical_key": "naruto-shippuden_s1",
            "title": "Naruto Shippuden",
            "original_title": "Naruto Shippuden Season 1",
            "year": 2007,
            "media_type": "series",
            "site": "rareanimes",
            "post_url": "https://www.rareanimes.mov/naruto/",
            "releases": [{
                "label": "Ep 1", "quality": "720p", "resolution": "720p",
                "season_number": 1, "episode_number": 1,
                "sources": [{"source_name": "PD", "service": "pixeldrain", "resource_id": "1", "source_url": "1", "final_url": "1", "is_temporary": 0, "quality": "720p"}]
            }]
        }
        import_rareanimes.save_post_to_staging_db(self.db_path, post1)

        # Update with new poster and synopsis
        post1["poster_url"] = "https://www.rareanimes.mov/new_poster.jpg"
        post1["synopsis"] = "Updated synopsis"
        import_rareanimes.save_post_to_staging_db(self.db_path, post1)

        with sqlite3.connect(str(self.db_path)) as conn:
            cnt = conn.execute("SELECT COUNT(*) FROM posts").fetchone()[0]
            poster = conn.execute("SELECT poster_url FROM posts WHERE canonical_key = 'naruto-shippuden_s1'").fetchone()[0]
        self.assertEqual(cnt, 1)
        self.assertEqual(poster, "https://www.rareanimes.mov/new_poster.jpg")

    def test_save_empty_returns_false(self):
        self.assertFalse(import_rareanimes.save_post_to_staging_db(self.db_path, None))
        self.assertFalse(import_rareanimes.save_post_to_staging_db(self.db_path, {"releases": []}))


class TestEdgeCases(unittest.TestCase):
    """Tests for edge cases in title parsing and null handling."""

    def test_extract_none_returns_none(self):
        self.assertIsNone(import_rareanimes.extract_post_data_rareanimes("https://www.rareanimes.mov/bad/", None, None))

    def test_clean_title_with_colon_and_symbols(self):
        t, y, s, k = import_rareanimes.clean_rareanimes_title("JoJo's Bizarre Adventure Season 4 – Golden Wind Hindi Dubbed Episodes Download HD")
        self.assertIn("JoJo's Bizarre Adventure", t)
        self.assertEqual(s, 4)
        self.assertTrue(k.endswith("_s4"))

    def test_clean_title_bengali_tag(self):
        t, y, s, k = import_rareanimes.clean_rareanimes_title("Ben 10 Classic Season 1 Hindi – Tamil – Telugu – Bengali Episodes Download HD")
        self.assertEqual(t, "Ben 10 Classic")
        self.assertEqual(s, 1)



class Session104RareAnimesTests(unittest.TestCase):
    """Session 104: yournewdomain.com + hubcloud.pw are hubcloud-family bridges."""

    def test_hubcloud_family_branches(self):
        import copy

        content = SAMPLE_EPISODE_CONTENT.replace(
            "https://hubcloud.ist/drive/nwryeqtyffgrhfv",
            "https://yournewdomain.com/drive/nwryeqtyffgrhfv",
        ).replace(
            "https://hubcloud.ist/drive/lecvdirs1ssv1cm",
            "https://hubcloud.pw/drive/lecvdirs1ssv1cm",
        )
        post = copy.deepcopy(SAMPLE_RAW_POST_JSON)
        post["content"] = {"rendered": content}
        data = import_rareanimes.extract_post_data_rareanimes(
            post_url="https://www.rareanimes.fans/blue-box-season-2-hindi-episodes-download-hd/",
            raw_post_json=post,
            resolve_direct=False,
        )
        self.assertIsNotNone(data)
        hc = [s for r in data["releases"] for s in r["sources"] if s["service"] == "hubcloud"]
        urls = [s["source_url"] for s in hc]
        self.assertIn("https://yournewdomain.com/drive/nwryeqtyffgrhfv", urls)
        self.assertIn("https://hubcloud.pw/drive/lecvdirs1ssv1cm", urls)


if __name__ == "__main__":
    unittest.main(verbosity=2)
