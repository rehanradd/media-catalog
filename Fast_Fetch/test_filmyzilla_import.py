#!/usr/bin/env python3
"""
test_filmyzilla_import.py — Comprehensive unit tests for FilmyZilla dedicated scraper.
Tests cover:
  1. Title cleaning and canonical key generation.
  2. Byte size string parser.
  3. Quality, resolution, and codec parser.
  4. Movie page HTML parser (title, poster, genres, release servers).
  5. Isolated SQLite staging database initialization & foreign key cascade.
  6. Atomic post, release, and source persistence.
  7. Direct Google CDN redirect resolution.
"""

import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import import_filmyzilla


class TestFilmyZillaImport(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test_filmyzilla.db"
        import_filmyzilla.init_staging_db(self.db_path)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_clean_filmyzilla_title(self):
        raw = "Street Fighter (2026) Hindi Dubbed Movie Free Download Filmyzilla"
        title, year, canon = import_filmyzilla.clean_filmyzilla_title(raw)
        self.assertEqual(title, "Street Fighter")
        self.assertEqual(year, 2026)
        self.assertEqual(canon, "street_fighter__2026")

        raw2 = "Aryabhatt Ka Zero (2026) Hindi Movie Free Download Filmyzilla"
        title2, year2, canon2 = import_filmyzilla.clean_filmyzilla_title(raw2)
        self.assertEqual(title2, "Aryabhatt Ka Zero")
        self.assertEqual(year2, 2026)
        self.assertEqual(canon2, "aryabhatt_ka_zero__2026")

        raw3 = "Blue (2026) Hindi Dubbed Movie"
        title3, year3, canon3 = import_filmyzilla.clean_filmyzilla_title(raw3)
        self.assertEqual(title3, "Blue")
        self.assertEqual(year3, 2026)
        self.assertEqual(canon3, "blue__2026")

    def test_parse_size_string(self):
        self.assertEqual(import_filmyzilla.parse_size_string("1.03 GB"), int(1.03 * 1024 * 1024 * 1024))
        self.assertEqual(import_filmyzilla.parse_size_string("450 MB"), int(450 * 1024 * 1024))
        self.assertEqual(import_filmyzilla.parse_size_string("500 KB"), int(500 * 1024))
        self.assertIsNone(import_filmyzilla.parse_size_string(None))
        self.assertIsNone(import_filmyzilla.parse_size_string("N/A"))

    def test_parse_quality_and_codec(self):
        q1, r1, c1 = import_filmyzilla.parse_quality_and_codec("Street Fighter (2026) Movie 1080p HDTS.mkv")
        self.assertEqual(q1, "1080p HDTS")
        self.assertEqual(r1, "1080p")
        self.assertEqual(c1, "x264")

        q2, r2, c2 = import_filmyzilla.parse_quality_and_codec("Street Fighter (2026) Movie HEVC 720p HDTS.mkv")
        self.assertEqual(q2, "720p HDTS")
        self.assertEqual(r2, "720p")
        self.assertEqual(c2, "hevc")

        q3, r3, c3 = import_filmyzilla.parse_quality_and_codec("Street Fighter (2026) Movie 480p HDRip.mkv")
        self.assertEqual(q3, "480p HDRip")
        self.assertEqual(r3, "480p")
        self.assertEqual(c3, "x264")

    def test_parse_filmyzilla_movie_page(self):
        sample_html = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>Street Fighter (2026) Hindi Dubbed Movie Free Download Filmyzilla</title>
        </head>
        <body>
            <img src="https://www.filmyzilla75.com/files/images/Street-fighter-(2026)-hindi-dubbed-movie.jpg?t=123" height="300" width="200" alt="poster" />
            <div class="info">
                <p>Genres : Action, Adventure</p>
                <p>Language : Hindi, English</p>
                <p>Release Date : 16 Oct 2026</p>
            </div>
            <div class="links">
                <a href="https://www.filmyzilla75.com/server/39913/Street-Fighter-(2026)-Hindi-Dubbed-Movie-480p-HDTS.mkv.html">Street Fighter (2026) Hindi Dubbed Movie 480p HDTS.mkv</a>
                <a href="/server/39911/Street-Fighter-(2026)-Hindi-Dubbed-Movie-720p-HDTS.mkv.html">Street Fighter (2026) Hindi Dubbed Movie 720p HDTS.mkv</a>
            </div>
        </body>
        </html>
        """
        parsed = import_filmyzilla.parse_filmyzilla_movie_page(
            sample_html,
            "https://www.filmyzilla75.com/movie/22418/Street-fighter-(2026)-hindi-dubbed-movie.html"
        )
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed["title"], "Street Fighter")
        self.assertEqual(parsed["year"], 2026)
        self.assertEqual(parsed["canonical_key"], "street_fighter__2026")
        self.assertEqual(parsed["poster_url"], "https://www.filmyzilla75.com/files/images/Street-fighter-(2026)-hindi-dubbed-movie.jpg")
        self.assertIn("Action", parsed["genres"])
        self.assertIn("Hindi", parsed["languages"])
        self.assertEqual(len(parsed["servers"]), 2)
        self.assertEqual(parsed["servers"][0]["server_id"], "39913")
        self.assertEqual(parsed["servers"][0]["quality"], "480p HDTS")
        self.assertEqual(parsed["servers"][1]["server_id"], "39911")
        self.assertEqual(parsed["servers"][1]["quality"], "720p HDTS")

    def test_modern_thefilmyzilla_page_parsing(self):
        sample_modern_html = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>Uunchai (2022) Hindi HD FilmyZilla</title>
            <meta property="og:image" content="https://thefilmyzilla.in/upload/uunchai-2022-hindi-hd-4215.webp">
        </head>
        <body>
            <div class="info-row"><div class="info-label">Release Year</div><div class="info-value">2022</div></div>
            <div class="info-row"><div class="info-label">Genres</div><div class="info-value">Bollywood,Comedy,Drama</div></div>
            <div class="info-row"><div class="info-label">Language</div><div class="info-value language">Hindi</div></div>
            <div class="info-row"><div class="info-label">Director</div><div class="info-value">Sooraj Barjatya</div></div>
            <div class="info-row"><div class="info-label">Cast</div><div class="info-value">Amitabh Bachchan, Anupam Kher, Boman Irani</div></div>
            <a href="/server/uunchai-2022-hindi-hd-4215.html" class="file-button">Download Now</a>
        </body>
        </html>
        """
        parsed = import_filmyzilla.parse_filmyzilla_movie_page(
            sample_modern_html,
            "https://thefilmyzilla.in/uunchai-2022-hindi-hd-4215.html"
        )
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed["title"], "Uunchai")
        self.assertEqual(parsed["year"], 2022)
        self.assertEqual(parsed["canonical_key"], "uunchai__2022")
        self.assertEqual(parsed["director"], "Sooraj Barjatya")
        self.assertEqual(parsed["cast_names"], "Amitabh Bachchan, Anupam Kher, Boman Irani")
        self.assertEqual(parsed["poster_url"], "https://thefilmyzilla.in/upload/uunchai-2022-hindi-hd-4215.webp")
        self.assertIn("Bollywood", parsed["genres"])
        self.assertIn("Hindi", parsed["languages"])
        self.assertIn("/server/uunchai-2022-hindi-hd-4215.html", parsed["modern_server_page"])

    def test_staging_db_init_and_foreign_keys(self):
        with sqlite3.connect(str(self.db_path)) as conn:
            cur = conn.cursor()
            tables = [r[0] for r in cur.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()]
            self.assertIn("sitemap_cache", tables)
            self.assertIn("posts", tables)
            self.assertIn("releases", tables)
            self.assertIn("sources", tables)

    def test_save_post_to_staging_db(self):
        post_data = {
            "canonical_key": "street_fighter__2026",
            "title": "Street Fighter",
            "original_title": "Street Fighter (2026) Hindi Dubbed Movie Free Download Filmyzilla",
            "year": 2026,
            "media_type": "movie",
            "imdb_id": None,
            "poster_url": "https://www.filmyzilla75.com/files/images/Street-fighter-(2026).jpg",
            "genres": json.dumps(["Action", "Adventure"]),
            "synopsis": "Released: 16 Oct 2026",
            "site": "filmyzilla",
            "post_url": "https://www.filmyzilla75.com/movie/22418/Street-fighter.html",
            "releases": [
                {
                    "label": "Street Fighter 720p HDTS",
                    "quality": "720p HDTS",
                    "resolution": "720p",
                    "size_bytes": 1105954119,
                    "season_number": None,
                    "episode_number": None,
                    "audio_languages": json.dumps(["Hindi"]),
                    "codec": "x264",
                    "bridge_url": "https://www.filmyzilla75.com/download/39911/server_1",
                    "sources": [
                        {
                            "source_name": "⚡ Google CDN Direct Stream",
                            "service": "google_cdn",
                            "resource_id": "39911",
                            "source_url": "https://www.filmyzilla75.com/download/39911/server_1",
                            "final_url": "https://lh3.googleusercontent.com/pw/sample-direct-stream",
                            "is_temporary": 1,
                            "quality": "720p HDTS",
                        },
                        {
                            "source_name": "⚡ FilmyZilla Direct Gateway",
                            "service": "filmyzilla_server",
                            "resource_id": "39911",
                            "source_url": "https://www.filmyzilla75.com/server/39911/Street-Fighter.html",
                            "final_url": "https://www.filmyzilla75.com/download/39911/server_1",
                            "is_temporary": 0,
                            "quality": "720p HDTS",
                        },
                    ],
                }
            ],
        }

        ok = import_filmyzilla.save_post_to_staging_db(self.db_path, post_data)
        self.assertTrue(ok)

        with sqlite3.connect(str(self.db_path)) as conn:
            cur = conn.cursor()
            post_cnt = cur.execute("SELECT count(*) FROM posts").fetchone()[0]
            rel_cnt = cur.execute("SELECT count(*) FROM releases").fetchone()[0]
            src_cnt = cur.execute("SELECT count(*) FROM sources").fetchone()[0]
            self.assertEqual(post_cnt, 1)
            self.assertEqual(rel_cnt, 1)
            self.assertEqual(src_cnt, 2)

    def test_resolve_direct_cdn(self):
        client = import_filmyzilla.FilmyZillaClient()
        fake_redirect = import_filmyzilla.urllib.error.HTTPError(
            url="https://www.filmyzilla75.com/download/39911/server_1",
            code=302,
            msg="Found",
            hdrs={"Location": "https://video-downloads.googleusercontent.com/sample_hash"},
            fp=None,
        )

        with patch.object(client.no_redirect_opener, "open", side_effect=fake_redirect):
            cdn = client.resolve_direct_cdn(
                "https://www.filmyzilla75.com/download/39911/server_1",
                referer="https://www.filmyzilla75.com/server/39911/page.html"
            )
            self.assertEqual(cdn, "https://video-downloads.googleusercontent.com/sample_hash")



class _FakeFZClient:
    base_url = "https://www.filmyzilla80.com"

    def __init__(self, pages, resolves):
        self._pages = pages
        self._resolves = resolves

    def get_text(self, url, **kw):
        code, _final, html = self._pages.get(url, (404, url, ""))
        return code, url, html

    def resolve_direct_cdn(self, url, referer=None, timeout=10):
        return self._resolves.get(url)


class Session104FilmyzillaTests(unittest.TestCase):
    """Session 104: no fabricated fzcdn URLs; gateway 302 followed to gdflix;
    FZ80 /verified/chqv/server_N/ buttons read; gateways built on the post page's
    own domain."""

    def test_modern_path_stores_real_gdflix_never_fzcdn(self):
        movie = (
            "<html><head><title>Jailer 2 (2026) Hindi Dubbed Movie</title></head><body>"
            '<a href="/server/105261/jailer-2.html">Watch</a></body></html>'
        )
        server = '<a href="/download/105261/server_1">1080p - 3.22GB</a>'
        client = _FakeFZClient(
            {
                "https://filmyzillahq.in/movie/1/jailer-2.html": (200, "", movie),
                "https://filmyzillahq.in/server/105261/jailer-2.html": (200, "", server),
            },
            {"https://filmyzillahq.in/download/105261/server_1": "https://gdflix.dev/file/PHwypAkHUPxSizS"},
        )
        data = import_filmyzilla.extract_post_data_filmyzilla(
            client, "https://filmyzillahq.in/movie/1/jailer-2.html", resolve_direct=True
        )
        services = [s["service"] for r in data["releases"] for s in r["sources"]]
        self.assertIn("gdflix", services)
        self.assertNotIn("fzcdn", services)
        gw = [s for r in data["releases"] for s in r["sources"] if s["service"] == "filmyzilla_server"]
        self.assertTrue(gw[0]["final_url"].startswith("https://filmyzillahq.in/download/"))

    def test_legacy_path_reads_verified_buttons_on_own_domain(self):
        movie = (
            "<html><head><title>Jailer 2 (2026) Hindi Dubbed Movie</title></head><body>"
            '<a href="https://www.filmyzilla80.com/server/40321/jailer-2-hindi.html">720p</a>'
            "</body></html>"
        )
        server = (
            '<a href="/verified/chqv/server_1/">Start Download Now - Server 3</a>'
            '<a href="/verified/chqv/server_2/">Start Download Now - Server 4</a>'
        )
        client = _FakeFZClient(
            {
                "https://www.filmyzilla80.com/movie/22407/jailer-2.html": (200, "", movie),
                "https://www.filmyzilla80.com/server/40321/jailer-2-hindi.html": (200, "", server),
            },
            {"https://www.filmyzilla80.com/verified/chqv/server_1/": "https://gdflix.dev/file/VERIFIED123",
             "https://www.filmyzilla80.com/verified/chqv/server_2/": "https://gdflix.dev/file/VERIFIED123"},
        )
        data = import_filmyzilla.extract_post_data_filmyzilla(
            client, "https://www.filmyzilla80.com/movie/22407/jailer-2.html", resolve_direct=True
        )
        services = [s["service"] for r in data["releases"] for s in r["sources"]]
        self.assertIn("gdflix", services)
        gw = [s for r in data["releases"] for s in r["sources"] if s["service"] == "filmyzilla_server"]
        self.assertTrue(gw[0]["final_url"].startswith("https://www.filmyzilla80.com/download/40321/"))


class SeriesPocketB5Tests(unittest.TestCase):
    """B5 (2026-11-18): filmyzilla series rows must not file as movies.

    The trap, measured live: the site prints "Seaosn" (its own typo) and
    "Web Series" in series titles while the importer hardcoded
    media_type="movie". The loose pocket (clean_title LIKE '%season%' OR
    '%web series%') is 243 rows — but a bare "season" word is NOT a marker:
    "Season of the Witch", "Open Season", "Killing Season" are real movies,
    so the rule needs a number, the typo, or the "Web Series" suffix.
    """

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test_filmyzilla.db"
        import_filmyzilla.init_staging_db(self.db_path)

    def tearDown(self):
        self.temp_dir.cleanup()

    def _page(self, title: str, server_href: str = "/server/39911/Money-Heist-Seaosn-3.html") -> str:
        return (
            "<html><head><title>" + title + "</title></head><body>"
            '<a href="' + server_href + '">720p</a>'
            "</body></html>"
        )

    def test_seaosn_money_heist_is_series_with_season(self):
        parsed = import_filmyzilla.parse_filmyzilla_movie_page(
            self._page("Money HeistSeaosn 3 Netflix Original Web Series Free Download Filmyzilla"),
            "https://www.filmyzilla80.com/movie/1/money-heist.html",
        )
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed["media_type"], "series")
        self.assertEqual(parsed["seasons"], "Season 3")
        self.assertEqual(parsed["season_number"], 3)

    def test_web_series_without_number_is_series_without_season(self):
        parsed = import_filmyzilla.parse_filmyzilla_movie_page(
            self._page("Money HeistNetflix Original Web Series Free Download Filmyzilla"),
            "https://www.filmyzilla80.com/movie/1/money-heist.html",
        )
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed["media_type"], "series")
        self.assertIsNone(parsed["seasons"])
        self.assertIsNone(parsed["season_number"])

    def test_plain_movie_stays_movie(self):
        parsed = import_filmyzilla.parse_filmyzilla_movie_page(
            self._page("Street Fighter (2026) Hindi Dubbed Movie Free Download Filmyzilla",
                       "/server/39911/Street-Fighter.html"),
            "https://www.filmyzilla80.com/movie/22418/street-fighter.html",
        )
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed["media_type"], "movie")
        self.assertIsNone(parsed["seasons"])

    def test_bare_season_word_stays_movie(self):
        # The false-positive guard: a "season" with no number is a movie
        # title, not a series marker.
        for title in (
            "Season of the Witch (2011) Hindi Dubbed Movie Free Download Filmyzilla",
            "Open Season (2015) Hindi Dubbed Movie Free Download Filmyzilla",
            "Killing Season (2013) Hindi Dubbed Movie Free Download Filmyzilla",
        ):
            is_series, _num = import_filmyzilla.detect_series_markers(title)
            self.assertFalse(is_series, title)
        parsed = import_filmyzilla.parse_filmyzilla_movie_page(
            self._page("Season of the Witch (2011) Hindi Dubbed Movie Free Download Filmyzilla",
                       "/server/39911/Season-of-the-Witch.html"),
            "https://www.filmyzilla80.com/movie/1/season-of-the-witch.html",
        )
        self.assertEqual(parsed["media_type"], "movie")

    def test_bare_s_token_is_not_a_season(self):
        # Live row 222787, "S4: The Bob Lazar Story": "S4" is the Area 51
        # facility the film is named after, not a fourth season. Only the
        # S0[0-9] shape is claimed; a bare S<N> token is not a marker.
        self.assertFalse(import_filmyzilla.detect_series_markers("S4: The Bob Lazar Story")[0])
        self.assertFalse(import_filmyzilla.detect_series_markers("Show Name S2")[0])
        self.assertTrue(import_filmyzilla.detect_series_markers("Show Name S01")[0])
        self.assertEqual(import_filmyzilla.detect_series_markers("Show Name S01")[1], 1)

    def test_dash_season_is_series(self):
        parsed = import_filmyzilla.parse_filmyzilla_movie_page(
            self._page("Panchayat Season - 3 Hindi Series Free Download Filmyzilla",
                       "/server/39911/Panchayat-Season-3.html"),
            "https://www.filmyzilla80.com/movie/1/panchayat.html",
        )
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed["media_type"], "series")
        self.assertEqual(parsed["seasons"], "Season 3")

    def test_extract_carries_season_number_onto_releases(self):
        movie = self._page(
            "Money HeistSeaosn 3 Netflix Original Web Series Free Download Filmyzilla",
            "/server/105261/money-heist.html",
        )
        server = '<a href="/download/105261/server_1">1080p - 3.22GB</a>'
        client = _FakeFZClient(
            {
                "https://filmyzillahq.in/movie/1/money-heist.html": (200, "", movie),
                "https://filmyzillahq.in/server/105261/money-heist.html": (200, "", server),
            },
            {},
        )
        data = import_filmyzilla.extract_post_data_filmyzilla(
            client, "https://filmyzillahq.in/movie/1/money-heist.html", resolve_direct=False
        )
        self.assertIsNotNone(data)
        self.assertEqual(data["media_type"], "series")
        self.assertEqual(data["seasons"], "Season 3")
        self.assertEqual(data["releases"][0]["season_number"], 3)

    def test_save_series_post_persists_media_type_and_seasons(self):
        post_data = {
            "canonical_key": "money_heistseaosn_3_netflix_original_web_series",
            "title": "Money HeistSeaosn 3 Netflix Original Web Series",
            "original_title": "Money HeistSeaosn 3 Netflix Original Web Series",
            "year": None,
            "media_type": "series",
            "seasons": "Season 3",
            "site": "filmyzilla",
            "post_url": "https://www.filmyzilla80.com/movie/1/money-heist.html",
            "releases": [{
                "label": "Money HeistSeaosn 3 720p",
                "quality": "720p",
                "resolution": "720p",
                "size_bytes": None,
                "season_number": 3,
                "episode_number": None,
                "audio_languages": '["Hindi"]',
                "codec": "x264",
                "bridge_url": "https://www.filmyzilla80.com/download/1/server_1",
                "sources": [],
            }],
        }
        self.assertTrue(import_filmyzilla.save_post_to_staging_db(self.db_path, post_data))
        with sqlite3.connect(str(self.db_path)) as conn:
            row = conn.execute(
                "SELECT media_type, seasons FROM posts WHERE canonical_key = ?",
                (post_data["canonical_key"],),
            ).fetchone()
            self.assertEqual(row, ("series", "Season 3"))
            rel = conn.execute(
                "SELECT season_number FROM releases WHERE post_id = "
                "(SELECT id FROM posts WHERE canonical_key = ?)",
                (post_data["canonical_key"],),
            ).fetchone()
            self.assertEqual(rel, (3,))

    def test_staging_db_migrates_seasons_column(self):
        old_db = Path(self.temp_dir.name) / "old_filmyzilla.db"
        with sqlite3.connect(str(old_db)) as conn:
            conn.execute(
                "CREATE TABLE posts (id INTEGER PRIMARY KEY, canonical_key TEXT UNIQUE, "
                "title TEXT, media_type TEXT DEFAULT 'movie')"
            )
            conn.commit()
        import_filmyzilla.init_staging_db(old_db)
        with sqlite3.connect(str(old_db)) as conn:
            cols = {r[1] for r in conn.execute("PRAGMA table_info(posts);").fetchall()}
        self.assertIn("seasons", cols)

    def test_backfill_plan_and_apply_on_fixture(self):
        import backfill_series_pocket

        db = sqlite3.connect(":memory:")
        db.execute(
            "CREATE TABLE posts (id INTEGER PRIMARY KEY, title TEXT, clean_title TEXT, "
            "content_type TEXT, seasons TEXT, source_site TEXT, is_18_plus INTEGER)"
        )
        fixture = [
            (101820, "Money HeistSeaosn 3 Netflix Original Web Series",
             "Money HeistSeaosn 3 Netflix Original Web Series", "movie", None, "filmyzilla", 0),
            (101821, "Money HeistSeaosn 4 Netflix Original Web Series",
             "Money HeistSeaosn 4 Netflix Original Web Series", "movie", None, "filmyzilla", 0),
            (101822, "Money HeistSeaosn 5Netflix Web Series",
             "Money HeistSeaosn 5Netflix Web Series", "movie", None, "filmyzilla", 0),
            (101823, "Money HeistSeaosn 2 Netflix Original Web Series",
             "Money HeistSeaosn 2 Netflix Original Web Series", "movie", None, "filmyzilla", 0),
            (101828, "Money HeistNetflix Original Web Series",
             "Money HeistNetflix Original Web Series", "movie", None, "filmyzilla", 0),
            (32053, "Season of the Witch In", "Season of the Witch In",
             "movie", None, "hindilinks4u", 0),
            (886, "Open Season", "Open Season", "movie", None, "rareanimes", 0),
            (999999, "Street Fighter", "Street Fighter", "movie", None, "filmyzilla", 0),
        ]
        db.executemany("INSERT INTO posts VALUES (?, ?, ?, ?, ?, ?, ?)", fixture)
        plan = backfill_series_pocket.plan_backfill(db)
        self.assertEqual(len(plan["hits"]), 5)
        self.assertEqual(plan["servable_flips"], 5)
        self.assertEqual(plan["loose_pocket"], 7)
        self.assertEqual(len(plan["loose_minus_strict"]), 2)
        self.assertEqual(backfill_series_pocket.series_count(db), 0)

        flipped = backfill_series_pocket.apply_backfill(db, plan)
        db.commit()
        self.assertEqual(flipped, 5)
        self.assertEqual(backfill_series_pocket.series_count(db), 5)
        seasons = dict(db.execute(
            "SELECT id, seasons FROM posts WHERE content_type = 'series'").fetchall())
        self.assertEqual(seasons[101820], "Season 3")
        self.assertEqual(seasons[101821], "Season 4")
        self.assertEqual(seasons[101822], "Season 5")
        self.assertEqual(seasons[101823], "Season 2")
        self.assertIsNone(seasons[101828])
        still_movie = db.execute(
            "SELECT id FROM posts WHERE content_type = 'movie' ORDER BY id").fetchall()
        self.assertEqual([r[0] for r in still_movie], [886, 32053, 999999])

        # Idempotent: a second plan finds nothing left to flip.
        plan2 = backfill_series_pocket.plan_backfill(db)
        self.assertEqual(len(plan2["hits"]), 0)
        self.assertEqual(backfill_series_pocket.apply_backfill(db, plan2), 0)


if __name__ == "__main__":
    unittest.main()
