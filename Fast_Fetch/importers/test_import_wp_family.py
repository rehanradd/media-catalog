#!/usr/bin/env python3
"""
test_import_wp_family.py — offline unit tests for the WordPress-family importer.

No network: every case feeds a captured page shape into parse_page and writes
into a temp staging file.
"""

from __future__ import annotations

import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import import_wp_family as wp  # noqa: E402

MOVIE_PAGE = """<!DOCTYPE html><html><head>
<title>Jawan (2023) Full Movie Download MoviesMela</title>
<meta property="og:title" content="Jawan (2023) Full Movie Download">
<meta property="og:description" content="A soldier takes on corruption.">
<meta property="og:image" content="https://cdn.moviesmela.com/jawan.jpg">
<meta name="keywords" content="Jawan, Hindi">
</head><body>
<h1>Jawan (2023)</h1>
<iframe src="https://pkembed.site/e/abc123"></iframe>
<iframe src="https://doubleclick.net/pixel"></iframe>
<div class="dl">
 <a href="https://cdn.moviesmela.com/jawan-720p.mkv">Jawan 2023 720p WEB-DL 1.2GB</a>
 <a href="https://cdn.moviesmela.com/jawan-1080p.mkv">Jawan 2023 1080p WEB-DL 3.5 GB</a>
</div>
<a rel="tag" href="/tag/action">Action</a>
<a rel="tag" href="/tag/thriller">Thriller</a>
</body></html>"""

SERIES_PAGE = """<html><head>
<meta property="og:title" content="Mirzapur Season 2 Episode 3 Hindi Complete Series">
<meta property="og:description" content="Mafia saga.">
</head><body>
<h1>Mirzapur S02E03</h1>
<iframe src="//player.example.com/e/mp-203"></iframe>
<a href="https://cdn.example.com/mirzapur-s02e03-720p.mkv">Mirzapur S02E03 720p 500MB</a>
</body></html>"""

ADULT_PAGE = """<html><head>
<meta property="og:title" content="Palang Tod Season 1 (2024) Ullu Web Series">
</head><body>
<iframe src="https://player.example.com/e/adult1"></iframe>
</body></html>"""

PAGE_NO_PLAYER = """<html><head>
<meta property="og:title" content="About Us">
</head><body><h1>About</h1><p>Contact us.</p></body></html>"""


class ParsePageTests(unittest.TestCase):
    def test_movie_page_splits_iframe_and_quality_anchors(self):
        post = wp.parse_page(MOVIE_PAGE, "https://www.moviesmela.com/jawan-2023/", "hindidubbedmovies")
        self.assertIsNotNone(post)
        self.assertEqual(post["clean_title"], "Jawan")
        self.assertEqual(post["year"], 2023)
        self.assertEqual(post["content_type"], "movie")
        self.assertEqual(post["is_18_plus"], 0)
        self.assertEqual(post["poster_url"], "https://cdn.moviesmela.com/jawan.jpg")
        self.assertIn("Action", post["genres"])
        kinds = [rel["sources"][0]["playback_type"] for rel in post["releases"]]
        self.assertIn("iframe", kinds)
        self.assertEqual(kinds.count("download"), 2, "each quality is its own release")

    def test_ad_network_iframe_is_dropped(self):
        post = wp.parse_page(MOVIE_PAGE, "https://www.moviesmela.com/jawan-2023/", "hindidubbedmovies")
        urls = [s["source_url"] for rel in post["releases"] for s in rel["sources"]]
        self.assertFalse(any("doubleclick" in u for u in urls))

    def test_protocol_relative_iframe_gets_https(self):
        post = wp.parse_page(SERIES_PAGE, "https://thebollyflix.gen.in/mirzapur-s02e03/", "bollyflix")
        iframes = [s["source_url"] for rel in post["releases"]
                   for s in rel["sources"] if s["playback_type"] == "iframe"]
        self.assertEqual(iframes, ["https://player.example.com/e/mp-203"])

    def test_series_page_carries_season_and_episode(self):
        post = wp.parse_page(SERIES_PAGE, "https://thebollyflix.gen.in/mirzapur-s02e03/", "bollyflix")
        self.assertEqual(post["content_type"], "series")
        self.assertEqual(post["season_number"], 2)
        rel = post["releases"][0]
        self.assertEqual(rel["season"], 2)
        self.assertEqual(rel["episode"], 3)

    def test_adult_title_is_flagged_at_parse_time(self):
        post = wp.parse_page(ADULT_PAGE, "https://moviehax.run/palang-tod-season-1/", "moviehax")
        self.assertEqual(post["is_18_plus"], 1)

    def test_page_without_playable_object_returns_none(self):
        self.assertIsNone(wp.parse_page(PAGE_NO_PLAYER, "https://animeworld.live/about/", "animeworld"))

    def test_size_is_parsed_to_bytes(self):
        self.assertEqual(wp._size_bytes("1.2GB"), int(1.2 * 1024 ** 3))
        self.assertEqual(wp._size_bytes("500 mb"), 500 * 1024 ** 2)
        self.assertIsNone(wp._size_bytes("no size"))

    def test_lazyloaded_iframe_is_read_and_about_blank_skipped(self):
        html = ('<iframe data-lazyloaded="1" src="about:blank" '
                'data-litespeed-src="https://hindidubbedmovies.pk/?trembed=0&#038;trid=8&trtype=1">'
                '</iframe>')
        got = wp._sources_from_html(html, "https://hindidubbedmovies.pk/movies/maidaan-m/")
        self.assertEqual([s["source_url"] for s in got],
                         ["https://hindidubbedmovies.pk/?trembed=0&trid=8&trtype=1"])

    def test_player_host_config_catches_a_url_printed_outside_an_iframe(self):
        html = '<p>Server: https://abscloud.org/1/edd6c03c3f514d31556b9044017614be</p>'
        cfg = {"base": "https://hindianimeszone.com", "player_hosts": ["abscloud.org"]}
        got = wp._sources_from_html(html, "https://hindianimeszone.com/x/", cfg)
        self.assertEqual([s["source_url"] for s in got],
                         ["https://abscloud.org/1/edd6c03c3f514d31556b9044017614be"])

    def test_category_shelf_links_are_not_download_sources(self):
        html = ('<a href="https://thebollyflix.gen.in/movies-by-quality/720p-movies/">'
                '720p MOVIES</a>'
                '<a class="hsl" href="https://mobilejsr.com/view/088MKgTAey">720p [318MB]</a>')
        cfg = {"base": "https://thebollyflix.gen.in", "internal_hosts": ["bollyflix.co.za"]}
        got = wp._sources_from_html(html, "https://thebollyflix.gen.in/x/", cfg)
        self.assertEqual([s["source_url"] for s in got],
                         ["https://mobilejsr.com/view/088MKgTAey"])

    def test_first_party_download_button_is_kept(self):
        html = ('<a rel="nofollow" href="https://hindidubbedmovies.pk/?trdownload=1&#038;trid=8"'
                ' class="Button">Download</a>')
        cfg = {"base": "https://hindidubbedmovies.pk"}
        got = wp._sources_from_html(html, "https://hindidubbedmovies.pk/movies/x/", cfg)
        self.assertEqual([s["source_url"] for s in got],
                         ["https://hindidubbedmovies.pk/?trdownload=1&trid=8"])

    def test_generic_og_title_falls_back_to_description_head(self):
        page = ('<html><head><meta property="og:title" content="Bollyflix">'
                '<meta property="og:description" content="Girth Meets Lily (2026) Brazzers '
                'Originals English Adult Video 720p HDRip Title: Girth Meets Lily '
                '(2026) Genres: Erotic">'
                '<title>Bollyflix</title></head><body>'
                '<iframe src="https://mobilejsr.com/view/abc"></iframe></body></html>')
        post = wp.parse_page(page, "https://thebollyflix.gen.in/girth-meets-lily-2026/",
                             "bollyflix")
        self.assertIsNotNone(post)
        self.assertEqual(post["title"], "Girth Meets Lily (2026) Brazzers Originals English Adult Video")
        self.assertEqual(post["is_18_plus"], 1)


# Moviehax writes href=/src= attributes with no quotes at all (sweep S4,
# 2026-11-23: 110 unquoted vs 4 quoted href= per post). The quoted-only
# patterns used to miss every bridge on such a page.
UNQUOTED_PAGE = """<html><head>
<meta property="og:title" content="Terminator Zero (2024) Hindi Dubbed">
</head><body>
<iframe src=https://pixeldrain.com/u/abc123></iframe>
<iframe src='https://listeamed.net/e/xyz789'></iframe>
<div class="dl">
 <a href=https://pixeldrain.com/u/def456>Terminator Zero 2024 720p WEB-DL 900MB</a>
 <a href="https://filemoon.sx/d/ghi789">Terminator Zero 2024 1080p WEB-DL 2.1 GB</a>
 <a href='https://cdn.example.com/tz-480p.mkv'>Terminator Zero 2024 480p 400MB</a>
</div>
</body></html>"""


class UnquotedAttributeTests(unittest.TestCase):
    def test_unquoted_iframe_src_is_collected(self):
        html = ('<iframe src=https://pixeldrain.com/u/abc123></iframe>'
                '<iframe src="https://filemoon.sx/e/xyz789"></iframe>')
        got = wp._sources_from_html(html, "https://moviehax.run/x/",
                                    {"base": "https://moviehax.run"})
        self.assertEqual([s["source_url"] for s in got],
                         ["https://pixeldrain.com/u/abc123",
                          "https://filemoon.sx/e/xyz789"])

    def test_unquoted_anchor_href_is_collected_with_quality(self):
        html = ('<a href=https://pixeldrain.com/u/def456>'
                'Terminator Zero 2024 720p WEB-DL 900MB</a>')
        got = wp._sources_from_html(html, "https://moviehax.run/x/",
                                    {"base": "https://moviehax.run"})
        self.assertEqual(len(got), 1)
        self.assertEqual(got[0]["source_url"], "https://pixeldrain.com/u/def456")
        self.assertEqual(got[0]["quality"], "720p")
        self.assertEqual(got[0]["playback_type"], "download")

    def test_unquoted_value_stops_at_space_and_keeps_query_string(self):
        html = ('<a href=https://pixeldrain.com/u/abc123 class="dl-btn">'
                'Film 2024 1080p 1GB</a>'
                '<iframe src=https://example.com/e/abc?x=1&y=2></iframe>')
        got = wp._sources_from_html(html, "https://moviehax.run/x/",
                                    {"base": "https://moviehax.run"})
        self.assertEqual([s["source_url"] for s in got],
                         ["https://example.com/e/abc?x=1&y=2",
                          "https://pixeldrain.com/u/abc123"])

    def test_quoted_value_containing_gt_is_not_cut_short(self):
        html = ('<a href="https://example.com/?q=a>b">Film 2024 720p 500MB</a>'
                "<a href='https://example.com/?q=c>d'>Film 2024 480p 400MB</a>")
        got = wp._sources_from_html(html, "https://moviehax.run/x/",
                                    {"base": "https://moviehax.run"})
        self.assertEqual([s["source_url"] for s in got],
                         ["https://example.com/?q=a>b",
                          "https://example.com/?q=c>d"])

    def test_mixed_quotes_and_unquoted_on_one_moviehax_page(self):
        post = wp.parse_page(UNQUOTED_PAGE,
                             "https://moviehax.run/movies/terminator-zero-2024/",
                             "moviehax")
        self.assertIsNotNone(post)
        self.assertEqual(post["clean_title"], "Terminator Zero Hindi Dubbed")
        self.assertEqual(post["year"], 2024)
        urls = sorted(s["source_url"] for rel in post["releases"]
                      for s in rel["sources"])
        self.assertEqual(urls, [
            "https://cdn.example.com/tz-480p.mkv",
            "https://filemoon.sx/d/ghi789",
            "https://listeamed.net/e/xyz789",
            "https://pixeldrain.com/u/abc123",
            "https://pixeldrain.com/u/def456",
        ])
        kinds = sorted(((rel["quality"], s["playback_type"])
                        for rel in post["releases"] for s in rel["sources"]),
                       key=lambda k: (k[0] or "", k[1]))
        self.assertEqual(kinds, [(None, "iframe"), (None, "iframe"),
                                 ("1080p", "download"), ("480p", "download"),
                                 ("720p", "download")])

    def test_same_unquoted_page_parses_for_a_sibling_site(self):
        # The patterns live in the shared WP-family helper, so every site it
        # serves (moviehax, bollyflix, animeworld, hindidubbedmovies,
        # hindianimeszone) gains the fix, not just moviehax.
        got = wp._sources_from_html(UNQUOTED_PAGE, "https://thebollyflix.gen.in/x/",
                                    {"base": "https://thebollyflix.gen.in"})
        self.assertEqual(sorted(s["source_url"] for s in got), [
            "https://cdn.example.com/tz-480p.mkv",
            "https://filemoon.sx/d/ghi789",
            "https://listeamed.net/e/xyz789",
            "https://pixeldrain.com/u/abc123",
            "https://pixeldrain.com/u/def456",
        ])


# Moviehax's download buttons are image-button anchors: <a href=...><img></a>
# with empty inner text, so the quality/size label gate drops them. They count
# ONLY on a proven bridge host (IMAGE_BUTTON_BRIDGE_HOSTS). The fixture below
# mirrors the live Jab Mila Tu pages (2026-11-23): unquoted hrefs, the site's
# own banner images, filemoon /d/<code>/<Title>.mp4 + pixeldrain /u/<id>.
JAB_PAGE = """<html><head>
<meta property="og:title" content="Jab Mila Tu (2024 Ep 5-8) Hindi Season 1">
</head><body><div class="wp-content">
<p><a href=https://filemoon.sx/d/emhq833ezmhg/Jab_Mila_Tu__2024_Ep_5-8__Hindi_Season_1.mp4><img src=https://moviehax.run/wp-content/uploads/2022/11/fast-download-logo-300x20.jpg alt width=1230 height=82></a></p>
<p><a href=https://pixeldrain.com/u/fF29RHAM><img src=https://moviehax.run/wp-content/uploads/2022/10/a-300x22.jpg alt width=1240 height=91></a></p>
<p><a href=https://wishfast.top/o3vfpze8wdcx><img src=https://moviehax.run/wp-content/uploads/2022/10/a-300x22.jpg alt width=1240 height=91></a></p>
<p><a href=https://d0000d.com/d/covwa5i4fzmt><img src=https://moviehax.run/wp-content/uploads/2022/10/v-300x21.jpg alt width=1242 height=87></a></p>
</div></body></html>"""


def _moviehax_sources(html):
    return wp._sources_from_html(html, "https://moviehax.run/x/",
                                 {"base": "https://moviehax.run"})


class ImageButtonBridgeTests(unittest.TestCase):
    def test_image_button_filemoon_is_collected(self):
        html = ('<a href=https://filemoon.sx/d/emhq833ezmhg/'
                'Jab_Mila_Tu__2024_Ep_5-8__Hindi_Season_1.mp4>'
                '<img src=https://moviehax.run/wp-content/uploads/2022/11/'
                'fast-download-logo-300x20.jpg></a>')
        got = _moviehax_sources(html)
        self.assertEqual(len(got), 1)
        self.assertEqual(got[0]["source_url"],
                         "https://filemoon.sx/d/emhq833ezmhg/"
                         "Jab_Mila_Tu__2024_Ep_5-8__Hindi_Season_1.mp4")
        self.assertEqual(got[0]["playback_type"], "download")
        self.assertIsNone(got[0]["quality"])
        self.assertEqual(got[0]["release_title"], "Download")

    def test_image_button_pixeldrain_is_collected(self):
        html = ('<a href="https://pixeldrain.com/u/fF29RHAM">'
                '<img src="https://moviehax.run/wp-content/uploads/2022/10/'
                'a-300x22.jpg" alt=""></a>')
        got = _moviehax_sources(html)
        self.assertEqual([s["source_url"] for s in got],
                         ["https://pixeldrain.com/u/fF29RHAM"])

    def test_image_button_subdomain_of_bridge_host_is_collected(self):
        html = ('<a href="https://www.pixeldrain.com/u/abc123">'
                '<img src="https://moviehax.run/b.jpg"></a>')
        got = _moviehax_sources(html)
        self.assertEqual([s["source_url"] for s in got],
                         ["https://www.pixeldrain.com/u/abc123"])

    def test_image_button_wishfast_is_dropped(self):
        # Live Gen V page: the same banner slot also links wishfast.top, whose
        # URL 302s to an ad redirector — an ad button, not a bridge.
        html = ('<a href=https://wishfast.top/o3vfpze8wdcx>'
                '<img src=https://moviehax.run/wp-content/uploads/2022/10/'
                'a-300x22.jpg></a>')
        self.assertEqual(_moviehax_sources(html), [])

    def test_image_button_d0000d_is_dropped(self):
        # Unproven: its /d/<code> 301s to playmogo.com behind a Cloudflare
        # challenge, so no live file was confirmed — it stays out until proven.
        html = ('<a href=https://d0000d.com/d/covwa5i4fzmt>'
                '<img src=https://moviehax.run/wp-content/uploads/2022/10/'
                'v-300x21.jpg></a>')
        self.assertEqual(_moviehax_sources(html), [])

    def test_image_button_internal_logo_is_dropped(self):
        html = ('<a href="https://moviehax.run/">'
                '<img src="https://moviehax.run/wp-content/uploads/2026/11/'
                'moviehax-logo-360x120-1.webp" alt="MovieHax"></a>')
        self.assertEqual(_moviehax_sources(html), [])

    def test_empty_anchor_without_img_to_bridge_host_is_dropped(self):
        # The exception is for image buttons, not for any empty anchor to a
        # bridge host (e.g. a tracking pixel link).
        html = '<a href="https://filemoon.sx/d/abc123"></a>'
        self.assertEqual(_moviehax_sources(html), [])

    def test_labelled_anchor_without_quality_still_dropped(self):
        # The gate is unchanged for labelled anchors: no quality/size token,
        # no bridge-host image button, no source — even off the allowlist.
        html = ('<a href="https://example.com/watch">Watch Online</a>'
                '<a href="https://filemoon.sx/d/abc123">Watch Online</a>')
        self.assertEqual(_moviehax_sources(html), [])

    def test_jab_page_collects_bridges_and_drops_ad_buttons(self):
        got = _moviehax_sources(JAB_PAGE)
        self.assertEqual(sorted(s["source_url"] for s in got), [
            "https://filemoon.sx/d/emhq833ezmhg/"
            "Jab_Mila_Tu__2024_Ep_5-8__Hindi_Season_1.mp4",
            "https://pixeldrain.com/u/fF29RHAM",
        ])

    def test_parse_page_with_only_image_button_bridges_returns_post(self):
        with mock.patch.object(wp, "_dooplay_player_sources", return_value=[]):
            post = wp.parse_page(
                JAB_PAGE,
                "https://moviehax.run/jab-mila-tu-2024-ep-5-8-hindi-season-1/",
                "moviehax")
        self.assertIsNotNone(post)
        self.assertEqual(len(post["releases"]), 1)
        rel = post["releases"][0]
        self.assertIsNone(rel["quality"])
        self.assertEqual(
            sorted(s["source_url"] for s in rel["sources"]), [
                "https://filemoon.sx/d/emhq833ezmhg/"
                "Jab_Mila_Tu__2024_Ep_5-8__Hindi_Season_1.mp4",
                "https://pixeldrain.com/u/fF29RHAM",
            ])


class RestSeedTests(unittest.TestCase):
    def test_rest_seed_sends_categories_and_pages(self):
        payloads = {
            1: ('[{"link":"https://animeworld.live/one-piece-2024/"},'
                '{"link":"https://animeworld.live/category/anime/"}]',
                {"x-wp-totalpages": "2"}),
            2: ('[{"link":"https://animeworld.live/naruto-2024/"}]', {}),
        }
        seen_urls = []

        def fake_full(url, **_kw):
            seen_urls.append(url)
            page = 2 if "page=2" in url else 1
            text, headers = payloads[page]
            return 200, url, text, headers

        cfg = {"base": "https://animeworld.live", "sitemaps": [],
               "rest": "https://animeworld.live/wp-json/wp/v2/posts",
               "rest_categories": "1,2,3,5",
               "exclude": r"/category/"}
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(wp, "_http_get_full", side_effect=fake_full):
            db = Path(tmp) / "aw.db"
            fresh = wp.sync_catalog("animeworld", db, config=cfg, verbose=False)
            self.assertEqual(fresh, 2)
            self.assertTrue(all("categories=1,2,3,5" in u for u in seen_urls),
                            "every REST page carries the category filter")
            conn = sqlite3.connect(db)
            try:
                self.assertEqual(
                    conn.execute("SELECT loc FROM sitemap_cache ORDER BY loc").fetchall(),
                    [("https://animeworld.live/naruto-2024/",),
                     ("https://animeworld.live/one-piece-2024/",)])
            finally:
                conn.close()

    def test_animeworld_config_uses_rest_without_blog(self):
        cfg = wp.SITES["animeworld"]
        self.assertEqual(cfg["rest"], "https://animeworld.live/wp-json/wp/v2/posts")
        cats = (cfg.get("rest_categories") or "").split(",")
        self.assertEqual(cats, ["1", "2", "3", "5"])
        self.assertNotIn("17", cats, "Blog (17) editorials are not title entries")


ANIMEWORLD_TITLE_PAGE = """<html><head>
<meta property="og:title" content="The Quintessential Quintuplets - Anime World">
<meta property="og:description" content="Five sisters, one tutor.">
<meta property="og:image" content="https://animeworld.live/wp-content/uploads/qq.jpg">
</head><body>
<h1>The Quintessential Quintuplets</h1>
<iframe src="https://www.youtube-nocookie.com/embed/cj6NGP9nNwI" title="Official trailer"></iframe>
<a href="https://www.crunchyroll.com/series/GY4PD7Z06/the-quintessential-quintuplets">Watch</a>
</body></html>"""


class AnimeworldTests(unittest.TestCase):
    def test_title_page_with_trailer_only_has_no_playable_object(self):
        # Measured live 2026-11-23: 34/34 sampled title pages carry only a
        # YouTube trailer iframe + outbound platform links. The parser must
        # decline them, not bank the trailer or the Crunchyroll link.
        self.assertIsNone(wp.parse_page(
            ANIMEWORLD_TITLE_PAGE,
            "https://animeworld.live/the-quintessential-quintuplets/",
            "animeworld"))


class DooplayTests(unittest.TestCase):
    PAGE = ('<html><head><meta property="og:title" content="Generation Iron 2 (2017)">'
            '</head><body><ul id="playeroptionsul" class="ajax_mode">'
            '<li class="dooplay_player_option" data-type=movie data-post=3678 data-nume=1>'
            '<span class=title>Openload</span></li>'
            '<li class="dooplay_player_option" data-type=movie data-post=3678 data-nume=2>'
            '<span class=title>Trailer</span></li></ul></body></html>')

    def test_player_button_answer_becomes_an_iframe_source(self):
        replies = iter([
            (200, '{"embed_url":"https://openload.co/embed/NiLbbkCBBVw","type":"iframe"}'),
            (200, '{"embed_url":"https://www.youtube.com/embed/trailer1","type":"iframe"}'),
        ])
        with mock.patch.object(wp, "_http_post", side_effect=lambda *a, **k: next(replies)):
            got = wp._dooplay_player_sources(
                self.PAGE, "https://moviehax.run/movies/x/", wp.SITES["moviehax"])
        self.assertEqual([s["source_url"] for s in got],
                         ["https://openload.co/embed/NiLbbkCBBVw"])

    def test_a_429_from_the_player_route_pauses_the_run(self):
        with mock.patch.object(wp, "_http_post", return_value=(429, "")):
            with self.assertRaises(wp.RateLimited):
                wp._dooplay_player_sources(
                    self.PAGE, "https://moviehax.run/movies/x/", wp.SITES["moviehax"])


class ExportStagingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "wp.db"

    def tearDown(self):
        self.tmp.cleanup()

    def test_export_writes_post_release_source_and_sitemap_row(self):
        post = wp.parse_page(MOVIE_PAGE, "https://www.moviesmela.com/jawan-2023/", "hindidubbedmovies")
        out = wp.export_staging(self.db, "hindidubbedmovies", [post])
        self.assertEqual(out["posts_written"], 1)
        self.assertGreaterEqual(out["releases_written"], 2)
        self.assertGreaterEqual(out["sources_written"], 3)
        conn = sqlite3.connect(self.db)
        try:
            row = conn.execute("SELECT title, media_type, is_18_plus, source_site"
                               " FROM posts").fetchone()
            self.assertEqual(row[1], "movie")
            self.assertEqual(row[2], 0)
            self.assertEqual(row[3], "hindidubbedmovies")
            src = conn.execute("SELECT status, is_temporary FROM sources").fetchone()
            self.assertEqual(src, ("unverified", 0))
            sm = conn.execute("SELECT scraped FROM sitemap_cache").fetchone()
            self.assertEqual(sm[0], 1)
        finally:
            conn.close()

    def test_reexport_replaces_this_pages_releases_not_the_posts(self):
        post = wp.parse_page(MOVIE_PAGE, "https://www.moviesmela.com/jawan-2023/", "hindidubbedmovies")
        wp.export_staging(self.db, "hindidubbedmovies", [post])
        conn = sqlite3.connect(self.db)
        first = conn.execute("SELECT COUNT(*) FROM releases").fetchone()[0]
        conn.close()
        wp.export_staging(self.db, "hindidubbedmovies", [post])
        conn = sqlite3.connect(self.db)
        try:
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM releases").fetchone()[0], first)
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM posts").fetchone()[0], 1)
        finally:
            conn.close()

    def test_adult_flag_survives_upsert(self):
        adult = wp.parse_page(ADULT_PAGE, "https://moviehax.run/palang-tod-season-1/", "moviehax")
        wp.export_staging(self.db, "moviehax", [adult])
        moviehax_clean = dict(adult, is_18_plus=0)
        wp.export_staging(self.db, "moviehax", [moviehax_clean])
        conn = sqlite3.connect(self.db)
        try:
            self.assertEqual(
                conn.execute("SELECT is_18_plus FROM posts").fetchone()[0], 1)
        finally:
            conn.close()


class SyncAndCrawlTests(unittest.TestCase):
    def test_sitemap_parser_reads_loc_and_lastmod(self):
        text = ("<urlset><url><loc>https://x.site/a-2023/</loc>"
                "<lastmod>2024-01-01</lastmod></url>"
                "<url><loc>https://x.site/b/</loc></url></urlset>")
        self.assertEqual(wp.parse_sitemap(text),
                         [("https://x.site/a-2023/", "2024-01-01"),
                          ("https://x.site/b/", None)])

    def test_cdata_wrapped_locs_are_unwrapped(self):
        text = ("<sitemapindex><sitemap><loc><![CDATA[https://x.site/addl-sitemap.xml]]></loc>"
                "<lastmod><![CDATA[2026-10-28T16:04:02+00:00]]></lastmod></sitemap>"
                "</sitemapindex>")
        self.assertEqual(wp.parse_sitemap(text),
                         [("https://x.site/addl-sitemap.xml", "2026-10-28T16:04:02+00:00")])

    def test_rest_seed_pages_until_x_wp_totalpages(self):
        payloads = {
            1: ('[{"id":1,"link":"https://x.site/one-2024/","slug":"one-2024",'
                '"title":{"rendered":"One (2024)"},"content":{"rendered":"<p>x</p>"},'
                '"excerpt":{"rendered":""},"_embedded":{}},'
                '{"link":"https://x.site/category/a/"}]',
                {"x-wp-totalpages": "2"}),
            2: ('[{"id":2,"link":"https://x.site/two-2024/","slug":"two-2024",'
                '"title":{"rendered":"Two"},"content":{"rendered":"<p>y</p>"},'
                '"excerpt":{"rendered":""},"_embedded":{}}]', {}),
        }

        def fake_full(url, **_kw):
            page = 2 if "page=2" in url else 1
            text, headers = payloads[page]
            return 200, url, text, headers

        cfg = {"base": "https://x.site", "sitemaps": [],
               "rest": "https://x.site/wp-json/wp/v2/posts",
               "exclude": r"/category/"}
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(wp, "_http_get_full", side_effect=fake_full):
            db = Path(tmp) / "x.db"
            fresh = wp.sync_catalog("bollyflix", db, config=cfg, verbose=False)
            self.assertEqual(fresh, 2)
            conn = sqlite3.connect(db)
            try:
                self.assertEqual(
                    conn.execute("SELECT loc FROM sitemap_cache ORDER BY loc").fetchall(),
                    [("https://x.site/one-2024/",), ("https://x.site/two-2024/",)])
                # The full post is stored, so crawl needs no page fetch.
                raws = conn.execute(
                    "SELECT raw_json FROM sitemap_cache ORDER BY loc").fetchall()
                self.assertEqual(len(raws), 2)
                import json as _json
                self.assertEqual(_json.loads(raws[0][0])["id"], 1)
                self.assertEqual(_json.loads(raws[1][0])["id"], 2)
            finally:
                conn.close()

    def test_sync_follows_index_and_filters_excluded_paths(self):
        index = "<sitemapindex><sitemap><loc>https://x.site/post-sitemap.xml</loc></sitemap></sitemapindex>"
        child = ("<urlset><url><loc>https://x.site/good-film-2023/</loc></url>"
                 "<url><loc>https://x.site/category/action/</loc></url></urlset>")
        cfg = {"base": "https://x.site", "sitemaps": ["https://x.site/sitemap_index.xml"],
               "exclude": r"/(?:category|tag)/"}

        def fake_get(url, **_kw):
            return (200, url, index) if url.endswith("index.xml") else (200, url, child)

        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(wp, "http_get", side_effect=fake_get):
            db = Path(tmp) / "x.db"
            fresh = wp.sync_catalog("hindidubbedmovies", db, config=cfg, verbose=False)
            self.assertEqual(fresh, 1)
            conn = sqlite3.connect(db)
            try:
                self.assertEqual(
                    conn.execute("SELECT loc FROM sitemap_cache").fetchall(),
                    [("https://x.site/good-film-2023/",)])
            finally:
                conn.close()

    def test_a_429_pauses_the_run_without_spending_attempts(self):
        def fake_get(url, **_kw):
            return 429, url, ""

        with tempfile.TemporaryDirectory() as tmp:
            db = Path(tmp) / "x.db"
            conn = sqlite3.connect(db)
            try:
                wp.ensure_schema(conn)
                conn.executemany(
                    "INSERT INTO sitemap_cache (loc, scraped) VALUES (?, 0)",
                    [("https://x.site/a/",), ("https://x.site/b/",), ("https://x.site/c/",)])
                conn.commit()
            finally:
                conn.close()
            with mock.patch.object(wp, "http_get", side_effect=fake_get):
                totals = wp.crawl("hindidubbedmovies", db, delay=0, verbose=False)
            self.assertEqual(totals["pages"], 1, "later rows are skipped, not fetched")
            self.assertEqual(totals["paused_rate_limit"], 2)
            self.assertTrue(wp._RATE_LIMITED.is_set())
            conn = sqlite3.connect(db)
            try:
                self.assertEqual(conn.execute(
                    "SELECT COUNT(*) FROM sitemap_cache WHERE error_count = 1").fetchone()[0], 1)
                self.assertEqual(conn.execute(
                    "SELECT COUNT(*) FROM sitemap_cache WHERE error_count = 0").fetchone()[0], 2)
            finally:
                conn.close()

    def test_a_404_is_parked_with_a_reason(self):
        def fake_get(url, **_kw):
            return 404, url, ""

        with tempfile.TemporaryDirectory() as tmp:
            db = Path(tmp) / "x.db"
            conn = sqlite3.connect(db)
            try:
                wp.ensure_schema(conn)
                conn.execute("INSERT INTO sitemap_cache (loc, scraped) VALUES ('https://x.site/a/', 0)")
                conn.commit()
            finally:
                conn.close()
            with mock.patch.object(wp, "http_get", side_effect=fake_get):
                totals = wp.crawl("hindidubbedmovies", db, delay=0, verbose=False)
            self.assertEqual(totals["declined_by_reason"], {"http-404": 1})
            conn = sqlite3.connect(db)
            try:
                self.assertEqual(conn.execute(
                    "SELECT scraped FROM sitemap_cache").fetchone()[0], -1)
            finally:
                conn.close()


# Live-shape hindianimeszone REST post (id 12610, fetched 23 Nov 2026
# from https://hindianimeszone.com/wp-json/wp/v2/posts?per_page=2&_embed=1).
# Content trimmed to the info box + 2 episode blocks + the How-to tail;
# the live post carries 7 episode blocks of 24 total.
BLUE_BOX_CONTENT = """
<link rel="stylesheet" href="https://hindianimeszone.com/1.css">
<div class="episode">
<div style="font-family: Arial, sans-serif;">
<br>
  <b><span style="color: magenta;">Genre:</span></b> <b>Romance, Sports, Comedy, Drama, School, Slice of Life</b><br>
  <b><span style="color: blue;">Language:</span></b> <b>Hindi, Tamil, Telugu, English, Japanese</b><br>
  <b><span style="color: blue;">Subtitle:</span></b> <b>English</b><br>
    <b><span style="color: darkviolet;">Episodes:</span></b> <b>24</b><br>
  <b><span style="color: magenta;">Quality:</span></b> <b>480p 720p 1080p</b><br><br>
  <b><span style="color: magenta;">Synopsis:</span></b><br>
  <b>Taiki Inomata continues balancing his badminton dreams with his growing feelings for Chinatsu Kano. As competitions and school life become more intense, their relationship faces new emotions, challenges, and romantic complications.</b>
</div><b>
<hr class="divider">
<!-- Episode 1 (Code: twPj6VGX0hWeLAFSVv) -->
<div class="episode">
  <span class="episode-title">Episode 1</span><br>
  <span class="language">Multi Audio</span>
  <div class="btn-group">
    <a class="watch-online" href="https://myanimes.in/series/blue-box/" rel="noopener">Watch Online</a>
    <a class="download-480p" href="https://002.hindianimeszone.com/download1.php?code=tsD7YgT56c6CvY5Ek60czx2K&amp;q=480p+x264">480p x264</a>
    <a class="download-720p" href="https://002.hindianimeszone.com/download1.php?code=m9hOka3KyXKZJzMXgn&amp;q=720p+x264">720p x264</a>
    <a class="download-720p" href="https://002.hindianimeszone.com/download1.php?code=MkyaCuOPb2fhqMKCOG01rryHD&amp;q=720p+x265">720p x265</a>
    <a class="download-1080p" href="https://002.hindianimeszone.com/download1.php?code=qSj0dNPae8pl6eo9&amp;q=1080p+x265">1080p x265</a>
    <a class="download-1080p" href="https://002.hindianimeszone.com/download1.php?code=twPj6VGX0hWeLAFSVv&amp;q=1080p+HQ">1080p HQ</a>
  </div>
</div>
<hr class="divider">
<!-- Episode 2 (Code: 0cUBppxbbcjYxnh) -->
<div class="episode">
  <span class="episode-title">Episode 2</span><br>
  <span class="language">Multi Audio</span>
  <div class="btn-group">
    <a class="watch-online" href="https://myanimes.in/series/blue-box/" rel="noopener">Watch Online</a>
    <a class="download-480p" href="https://002.hindianimeszone.com/download1.php?code=q0eTfwFnTU9C9Yjj3OokC&amp;q=480p+x264">480p x264</a>
    <a class="download-720p" href="https://002.hindianimeszone.com/download1.php?code=LGw3aeXCR2Q726bO&amp;q=720p+x264">720p x264</a>
    <a class="download-720p" href="https://002.hindianimeszone.com/download1.php?code=orWOd7hAvJmo8lILW3w&amp;q=720p+x265">720p x265</a>
    <a class="download-1080p" href="https://002.hindianimeszone.com/download1.php?code=0cUBppxbbcjYxnh&amp;q=1080p+x265">1080p x265</a>
    <a class="download-1080p" href="https://002.hindianimeszone.com/download1.php?code=0cUBppxbbcjYxnh&amp;q=1080p+HQ">1080p HQ</a>
  </div>
</div>
<hr class="divider">
<div style="text-align: center; font-weight: bold;">
   <span class="episode-title">How to Download</span><br>
   <a class="btn-download" href="https://hindianimeszone.com/how-to-download/">Guide Video</a> </div>
"""

BLUE_BOX_POST = {
    "id": 12610,
    "title": {"rendered": "Blue Box Season 2 [Hindi-Tamil-Telugu-Eng-Jap] Multi Audio 480p, 720p &amp; 1080p HD WEB-DL | 10bit HEVC ESub"},
    "link": "https://hindianimeszone.com/blue-box-season-2-hindi-tamil-telugu-eng-jap-multi-audio-480p-720p-1080p-hd-web-dl-10bit-hevc-esub/",
    "slug": "blue-box-season-2-hindi-tamil-telugu-eng-jap-multi-audio-480p-720p-1080p-hd-web-dl-10bit-hevc-esub",
    "content": {"rendered": BLUE_BOX_CONTENT},
    "excerpt": {"rendered": "<p>Genre: Romance, Sports, Comedy, Drama, School, Slice of Life Language: Hindi, Tamil, Telugu, English, Japanese Subtitle: English Episodes: 24 Quality: 480p 720p 1080p Synopsis: Taiki Inomata continues balancing his badminton dreams with his growing feelings for Chinatsu Kano. As competitions and school life become more intense, their relationship faces new emotions, challenges, and romantic complications. [&hellip;]</p>"},
    "_embedded": {
        "wp:featuredmedia": [
            {"id": 12612,
             "source_url": "https://i.ibb.co/3mQFqtN0/web-optimized-1791101733-webp.webp",
             "media_type": "image", "mime_type": "image/jpeg"}
        ],
        "wp:term": [[
            {"name": "Anime"}, {"name": "Comedy"}, {"name": "Drama"},
            {"name": "English"}, {"name": "Hindi"}, {"name": "Japanese"},
            {"name": "Ongoing"}, {"name": "Romance"}, {"name": "School"},
            {"name": "Series"}, {"name": "Slice of Life"}, {"name": "Sports"},
            {"name": "Tamil"}, {"name": "Telugu"},
        ]]
    }
}

# Second live post shape (id 12856): the Language: line reads
# "Hindi Fan Dubbed + Jap Org" and Quality: is empty.
WITCH_CONTENT = """
<div class="episode">
<div style="font-family: Arial, sans-serif;">
<br>
  <b><span style="color: magenta;">Genre:</span></b> <b>Fantasy, Romance, Comedy</b><br>
  <b><span style="color: blue;">Language:</span></b> <b>Hindi Fan Dubbed + Jap Org</b><br>
  <b><span style="color: blue;">Subtitle:</span></b> <b>English</b><br>
    <b><span style="color: darkviolet;">Episodes:</span></b> <b>12</b><br>
  <b><span style="color: magenta;">Quality:</span></b> <b></b><br><br>
  <b><span style="color: magenta;">Synopsis:</span></b><br>
  <b>\u201cMake me a love potion.\u201d And with that single sentence, the Good Witch of the Lake\u2019s secret crush ends in heartbreak.</b>
</div><b>
<hr class="divider">
<!-- Episode 1 (Code: WitchEp1Code) -->
<div class="episode">
  <span class="episode-title">Episode 1</span><br>
  <span class="language">Multi Audio</span>
  <div class="btn-group">
    <a class="watch-online" href="https://myanimes.in/series/witch-love-potion/" rel="noopener">Watch Online</a>
    <a class="download-480p" href="https://002.hindianimeszone.com/download1.php?code=Witch480a&amp;q=480p+x264">480p x264</a>
    <a class="download-720p" href="https://002.hindianimeszone.com/download1.php?code=Witch720a&amp;q=720p+x264">720p x264</a>
    <a class="download-720p" href="https://002.hindianimeszone.com/download1.php?code=Witch720b&amp;q=720p+x265">720p x265</a>
    <a class="download-1080p" href="https://002.hindianimeszone.com/download1.php?code=Witch1080a&amp;q=1080p+x265">1080p x265</a>
    <a class="download-1080p" href="https://002.hindianimeszone.com/download1.php?code=Witch1080b&amp;q=1080p+HQ">1080p HQ</a>
  </div>
</div>
"""

WITCH_POST = {
    "id": 12856,
    "title": {"rendered": "Hello, I am a Witch and my Crush Wants me to Make a Love Potion! Season 1 [Hindi Fan-Dub | 480p, 720p &amp; 1080p HD WEB-DL]"},
    "link": "https://hindianimeszone.com/hello-i-am-a-witch-season-1/",
    "slug": "hello-i-am-a-witch-season-1",
    "content": {"rendered": WITCH_CONTENT},
    "excerpt": {"rendered": "<p>Genre: Fantasy, Romance, Comedy Language: Hindi Fan Dubbed + Jap Org Subtitle: English Episodes: 12 Quality: Synopsis: \u201cMake me a love potion.\u201d</p>"},
    "_embedded": {
        "wp:featuredmedia": [
            {"id": 12860,
             "source_url": "https://i.ibb.co/example/witch-poster.webp",
             "media_type": "image", "mime_type": "image/jpeg"}
        ],
        "wp:term": [[{"name": "Anime"}, {"name": "Fantasy"},
                     {"name": "Romance"}, {"name": "Comedy"}]]
    }
}

# Same shape with NO Genre: line — genres must fall back to the filtered
# WP categories (Anime/Ongoing/Series/language buckets dropped).
NO_LABEL_CONTENT = """
<div class="episode">
<div style="font-family: Arial, sans-serif;">
<br>
  <b><span style="color: blue;">Language:</span></b> <b>Hindi</b><br>
    <b><span style="color: darkviolet;">Episodes:</span></b> <b>3</b><br>
  <b><span style="color: magenta;">Synopsis:</span></b><br>
  <b>A test show.</b>
</div><b>
<hr class="divider">
<!-- Episode 1 (Code: NoLabelEp1) -->
<div class="episode">
  <span class="episode-title">Episode 1</span><br>
  <span class="language">Hindi</span>
  <div class="btn-group">
    <a class="download-480p" href="https://002.hindianimeszone.com/download1.php?code=NoLabel480&amp;q=480p+x264">480p x264</a>
  </div>
</div>
"""

NO_LABEL_POST = {
    "id": 99901,
    "title": {"rendered": "Test Show Season 1 [Hindi] Multi Audio 480p HD WEB-DL"},
    "link": "https://hindianimeszone.com/test-show-season-1/",
    "slug": "test-show-season-1",
    "content": {"rendered": NO_LABEL_CONTENT},
    "excerpt": {"rendered": "<p>A test show.</p>"},
    "_embedded": {
        "wp:featuredmedia": [],
        "wp:term": [[
            {"name": "Anime"}, {"name": "Comedy"}, {"name": "Drama"},
            {"name": "English"}, {"name": "Ongoing"}, {"name": "Romance"},
            {"name": "Series"},
        ]]
    }
}


class ParseRestPostTests(unittest.TestCase):
    def test_blue_box_full_metadata(self):
        post = wp.parse_rest_post(BLUE_BOX_POST, "hindianimeszone")
        self.assertIsNotNone(post)
        self.assertEqual(post["title"], "Blue Box Season 2")
        # Family convention (parse_page): the season marker stays in
        # clean_title, so the canonical key agrees across both paths.
        self.assertEqual(post["clean_title"], "Blue Box Season 2")
        self.assertIsNone(post["year"])
        self.assertEqual(post["content_type"], "series")
        self.assertEqual(post["season_number"], 2)
        self.assertEqual(post["poster_url"],
                         "https://i.ibb.co/3mQFqtN0/web-optimized-1791101733-webp.webp")
        self.assertEqual(post["genres"],
                         "Romance, Sports, Comedy, Drama, School, Slice of Life")
        self.assertEqual(post["languages"],
                         "Hindi, Tamil, Telugu, English, Japanese")
        self.assertEqual(post["synopsis"],
                         "Taiki Inomata continues balancing his badminton dreams "
                         "with his growing feelings for Chinatsu Kano. As competitions "
                         "and school life become more intense, their relationship faces "
                         "new emotions, challenges, and romantic complications.")
        self.assertEqual(post["is_animated"], 1)
        self.assertEqual(post["is_18_plus"], 0)

    def test_blue_box_releases_are_per_episode_per_quality(self):
        post = wp.parse_rest_post(BLUE_BOX_POST, "hindianimeszone")
        self.assertEqual(len(post["releases"]), 10, "2 episodes x 5 qualities")
        ep1 = [r for r in post["releases"] if r["episode"] == 1]
        self.assertEqual([r["quality"] for r in ep1],
                         ["480p", "720p", "720p", "1080p", "1080p"])
        self.assertEqual([r["codec"] for r in ep1],
                         ["x264", "x264", "x265", "x265", None])
        self.assertEqual([r["label"] for r in ep1],
                         ["Episode 1 [480p x264]", "Episode 1 [720p x264]",
                          "Episode 1 [720p x265]", "Episode 1 [1080p x265]",
                          "Episode 1 [1080p]"])
        self.assertTrue(all(r["season"] == 2 for r in post["releases"]))
        urls = [s["source_url"] for r in post["releases"] for s in r["sources"]]
        self.assertIn("https://002.hindianimeszone.com/download1.php"
                      "?code=tsD7YgT56c6CvY5Ek60czx2K&q=480p+x264", urls)
        self.assertFalse(any("myanimes.in" in u for u in urls),
                         "Watch Online is the series page, not an episode source")
        self.assertFalse(any("how-to-download" in u for u in urls))

    def test_witch_language_normalization(self):
        post = wp.parse_rest_post(WITCH_POST, "hindianimeszone")
        self.assertIsNotNone(post)
        self.assertEqual(post["languages"], "Hindi, Japanese")
        self.assertEqual(post["genres"], "Fantasy, Romance, Comedy")
        self.assertEqual(len(post["releases"]), 5)
        self.assertTrue(post["synopsis"].startswith("\u201cMake me a love potion.\u201d"))

    def test_genres_fall_back_to_filtered_categories(self):
        post = wp.parse_rest_post(NO_LABEL_POST, "hindianimeszone")
        self.assertIsNotNone(post)
        self.assertEqual(post["genres"], "Comedy, Drama, Romance")
        self.assertEqual(len(post["releases"]), 1)
        self.assertIsNone(post["poster_url"])

    def test_quality_codec_parsing(self):
        self.assertEqual(wp._haz_quality_codec("480p+x264"), ("480p", "x264"))
        self.assertEqual(wp._haz_quality_codec("1080p+HQ"), ("1080p", None))
        self.assertEqual(wp._haz_quality_codec("720p"), ("720p", None))
        self.assertEqual(wp._haz_quality_codec("bogus"), (None, None))

    def test_crawl_uses_stored_raw_json_without_any_fetch(self):
        import json as _json
        with tempfile.TemporaryDirectory() as tmp:
            db = Path(tmp) / "haz.db"
            conn = sqlite3.connect(db)
            try:
                wp.ensure_schema(conn)
                conn.execute(
                    "INSERT INTO sitemap_cache (loc, scraped, raw_json)"
                    " VALUES (?, 0, ?)",
                    (BLUE_BOX_POST["link"], _json.dumps(BLUE_BOX_POST)))
                conn.commit()
            finally:
                conn.close()
            with mock.patch.object(
                    wp, "http_get",
                    side_effect=AssertionError("must not fetch the page")):
                totals = wp.crawl("hindianimeszone", db, limit=1, delay=0,
                                  verbose=False)
            self.assertEqual(totals["posts"], 1)
            self.assertEqual(totals["releases"], 10)
            self.assertEqual(totals["sources"], 10)
            conn = sqlite3.connect(db)
            try:
                row = conn.execute(
                    "SELECT title, genres FROM posts").fetchone()
                self.assertEqual(row[0], "Blue Box Season 2")
                self.assertIn("Romance", row[1])
                codecs = conn.execute(
                    "SELECT DISTINCT codec FROM releases").fetchall()
                self.assertEqual({c[0] for c in codecs},
                                 {"x264", "x265", None})
            finally:
                conn.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
