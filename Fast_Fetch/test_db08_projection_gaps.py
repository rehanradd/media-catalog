#!/usr/bin/env python3
"""DB-08 projection-gap tests (2026-11-24).

Covers the four fixes for the three gaps measured live that day
(46,586 sourceless releases; 1,201 release-less posts; 34,891 posts not
yet projected to cat_works):

* bridge fallbacks at scrape time (bolly4u / katmoviehd / desicinemas),
  so a failed unwrap keeps the pointer instead of a sourceless release;
* the backfill script that mints those fallbacks for existing rows
  (dry-run default, idempotent, never fabricates a bridgeless row);
* the source_site guard at the save choke point (a foreign page is no
  longer filed under 'vegamovies');
* the projection-gap auditor (folding vs staleness, watermarked).

Offline only: stub clients, in-memory / tmp-file SQLite, no sockets.
"""

from __future__ import annotations

import json
import os
import sqlite3
import tempfile
import unittest
import unittest.mock
from pathlib import Path

os.environ.setdefault("RADD_SKIP_LIVE_ENRICH", "1")

import backfill_sourceless_bridges as backfill
import catalog_v2
import fast_fetch
import import_bolly4u
import import_desicinemas
import import_katmoviehd


# --------------------------------------------------------------------- #
# source_site_from_url
# --------------------------------------------------------------------- #
class SourceSiteFromUrlTests(unittest.TestCase):
    def test_evidenced_foreign_hosts(self):
        cases = {
            "https://moviesflix.casa/movie/stop-2025-gujarati-hdtc-300mb-": "moviesflix",
            "https://www.mp4moviez.deal/47/bengali-movies-2014.html": "mp4moviez",
            "https://filmyhit.mobile/movie/x": "filmyhit",
            "https://themoviebox.xyz/movie/y": "themoviebox",
            "https://aryplus.tv/movie/z": "aryplus",
            "https://m1.multimovies.courses/p/1": "multimovies",
            "https://www.desicinemas.pk/movie/a": "desicinemas",
            "https://bolly4uz.click/the-christophers-2025/": "bolly4u",
        }
        for url, site in cases.items():
            with self.subTest(url=url):
                self.assertEqual(fast_fetch.source_site_from_url(url), site)

    def test_own_hosts_and_rotations(self):
        self.assertEqual(
            fast_fetch.source_site_from_url("https://vegamovies.lease/p/1"),
            "vegamovies")
        self.assertEqual(
            fast_fetch.source_site_from_url("https://vegamovies.click/p/1"),
            "vegamovies")
        self.assertEqual(
            fast_fetch.source_site_from_url("https://new.katmoviehd.top/x"),
            "katmoviehd")
        self.assertEqual(
            fast_fetch.source_site_from_url("https://rogmovies.one/p/1"),
            "rogmovies")

    def test_unknown_and_garbage(self):
        self.assertEqual(fast_fetch.source_site_from_url("https://example.com/x"), "")
        self.assertEqual(fast_fetch.source_site_from_url(""), "")
        self.assertEqual(fast_fetch.source_site_from_url("not a url"), "")
        self.assertEqual(fast_fetch.source_site_from_url(None), "")


# --------------------------------------------------------------------- #
# save choke point: re-label, validator default
# --------------------------------------------------------------------- #
class SaveChokePointTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp.name) / "catalog.db"
        self._patch = unittest.mock.patch.object(fast_fetch, "DB_PATH", self.db_path)
        self._patch.start()
        fast_fetch.init_db(self.db_path)
        self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row

    def tearDown(self):
        self.conn.close()
        self._patch.stop()
        self.tmp.cleanup()

    def _save(self, url, site, **kw):
        data = {
            "url": url,
            "slug": url.rstrip("/").split("/")[-1],
            "title": kw.get("title", "Stop (2025)"),
            "clean_title": kw.get("clean_title", "Stop"),
            "year": 2025,
            "content_type": "movie",
            "source_site": site,
            "canonical_key": fast_fetch.generate_canonical_key("Stop", 2025),
            "releases": kw.get("releases", []),
        }
        return fast_fetch.save_scraped_data(
            data, enrich_metadata=False, ensure_db=False)

    def test_foreign_page_is_not_filed_as_vegamovies(self):
        """The 1,073-row mislabel: a moviesflix.casa page passed in as
        'vegamovies' must land under its own host's site."""
        post_id = self._save(
            "https://moviesflix.casa/movie/stop-2025-gujarati-hdtc-300mb-",
            "vegamovies")
        row = self.conn.execute(
            "SELECT source_site, url FROM posts WHERE id = ?", (post_id,)).fetchone()
        self.assertEqual(row["source_site"], "moviesflix")
        self.assertIn("moviesflix.casa", row["url"])
        src = self.conn.execute(
            "SELECT source_site FROM post_sources WHERE post_id = ?",
            (post_id,)).fetchone()
        self.assertEqual(src["source_site"], "moviesflix")

    def test_own_host_label_survives(self):
        post_id = self._save("https://vegamovies.lease/p/stop-2025", "vegamovies")
        row = self.conn.execute(
            "SELECT source_site FROM posts WHERE id = ?", (post_id,)).fetchone()
        self.assertEqual(row["source_site"], "vegamovies")

    def test_missing_label_derives_from_host_not_vegamovies(self):
        url, _ct, _t, site, _cty, _rels = fast_fetch._validate_scraped_data({
            "url": "https://www.mp4moviez.deal/47/bengali-movies-2014.html",
            "clean_title": "Bengali Movies",
            "title": "Bengali Movies",
            "releases": [],
        })
        self.assertEqual(site, "mp4moviez")

    def test_missing_label_unknown_host_is_unknown(self):
        _url, _ct, _t, site, _cty, _rels = fast_fetch._validate_scraped_data({
            "url": "https://example.com/some-film",
            "clean_title": "Some Film",
            "title": "Some Film",
            "releases": [],
        })
        self.assertEqual(site, "unknown")

    def test_release_rows_carry_the_corrected_site(self):
        post_id = self._save(
            "https://moviesflix.casa/movie/stop-2025-gujarati-hdtc-300mb-",
            "vegamovies",
            releases=[{
                "release_title": "Stop 480p",
                "quality": "480p",
                "intermediate_url": "https://moviesflix.casa/movie/stop-2025",
                "sources": [],
            }])
        rel = self.conn.execute(
            "SELECT source_site FROM releases WHERE post_id = ?",
            (post_id,)).fetchone()
        self.assertEqual(rel["source_site"], "moviesflix")


# --------------------------------------------------------------------- #
# bridge fallbacks at scrape time
# --------------------------------------------------------------------- #
class Bolly4uFallbackTests(unittest.TestCase):
    BOLLY_HTML = (
        "<h4>|| 720p Download Links: 800MB ||</h4>"
        "<p><a href=\"https://new2.linkskeep.lat/view/MW6CrrfDG5\">Download 720p</a></p>"
    )

    class _DeadClient(import_bolly4u.Bolly4uClient):
        def unwrap_linkskeep(self, lk_url):
            return []

        def unwrap_photojin(self, pj_url):
            return []

    class _LiveClient(import_bolly4u.Bolly4uClient):
        def unwrap_linkskeep(self, lk_url):
            return [{
                "source_name": "⚡ ShareDrive [Cloud]",
                "service": "sharedrive",
                "resource_id": "abc",
                "source_url": "https://sharedrive.example/abc",
                "final_url": "https://sharedrive.example/abc",
                "is_temporary": 0,
            }]

        def unwrap_photojin(self, pj_url):
            return []

    def _item(self):
        raw = json.dumps({"title": {"rendered": "The Christophers (2025)"},
                          "content": {"rendered": self.BOLLY_HTML}})
        return ("https://bolly4uz.click/the-christophers-2025/", "", "slug", raw)

    def test_failed_unwrap_keeps_the_bridge(self):
        result = import_bolly4u.scrape_post_item(self._item(), self._DeadClient())
        self.assertTrue(result["success"])
        sources = result["releases"][0]["sources"]
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["service"], "linkskeep")
        self.assertEqual(sources[0]["final_url"],
                         "https://new2.linkskeep.lat/view/MW6CrrfDG5")

    def test_successful_unwrap_adds_no_fallback(self):
        result = import_bolly4u.scrape_post_item(self._item(), self._LiveClient())
        sources = result["releases"][0]["sources"]
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["service"], "sharedrive")

    def test_bridge_helper_names(self):
        self.assertEqual(
            import_bolly4u._bridge_fallback_source(
                "https://new2.linkskeep.lat/view/X")["service"], "linkskeep")
        self.assertEqual(
            import_bolly4u._bridge_fallback_source(
                "https://photojin.study/f/X")["service"], "photojin")
        self.assertIsNone(import_bolly4u._bridge_fallback_source(
            "https://movcloud.example/f/X"))


class KatmoviehdFallbackTests(unittest.TestCase):
    class _ApiClient(import_katmoviehd.KatMovieHDClient):
        def __init__(self, payload):
            self._payload = payload

        def get_api_json(self, url, timeout=12):
            return self._payload

    def test_bare_api_answer_keeps_the_gateway(self):
        client = self._ApiClient({"size": 123, "upload_links": {}})
        _size, sources = import_katmoviehd.resolve_file_sources(
            client=client, file_id="Spid_b498506f",
            bridge_url="https://links.kmhd.me/file/Spid_b498506f")
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["service"], "kmhd")
        self.assertEqual(sources[0]["final_url"],
                         "https://links.kmhd.me/file/Spid_b498506f")

    def test_no_api_answer_still_keeps_the_gateway(self):
        client = self._ApiClient(None)
        _size, sources = import_katmoviehd.resolve_file_sources(
            client=client, file_id="Spid_b498506f",
            bridge_url="https://links.kmhd.me/file/Spid_b498506f")
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["service"], "kmhd")

    def test_a_real_mirror_adds_no_gateway(self):
        client = self._ApiClient(
            {"size": 123, "upload_links": {"gdflix_res": "GdAb12"}})
        _size, sources = import_katmoviehd.resolve_file_sources(
            client=client, file_id="Spid_b498506f",
            bridge_url="https://links.kmhd.me/file/Spid_b498506f")
        services = {s["service"] for s in sources}
        self.assertIn("gdflix", services)
        self.assertNotIn("kmhd", services)


class DesicinemasFallbackTests(unittest.TestCase):
    HTML = (
        "<h1>Test Film (2024)</h1>"
        "<iframe src=\"https://trembed.example/e?trembed=abc123\"></iframe>"
    )

    class _DeadClient(import_desicinemas.DesiCinemasClient):
        def get_text(self, url, referer=None, timeout=12.0):
            return 200, url, DesicinemasFallbackTests.HTML, {}

        def unwrap_trembed(self, trembed_url, referer=None):
            return []

    class _LiveClient(import_desicinemas.DesiCinemasClient):
        def get_text(self, url, referer=None, timeout=12.0):
            return 200, url, DesicinemasFallbackTests.HTML, {}

        def unwrap_trembed(self, trembed_url, referer=None):
            return [{
                "source_name": "▶ Stream",
                "service": "vidsrc",
                "resource_id": "abc123",
                "source_url": trembed_url,
                "final_url": "https://vidsrc.example/abc123",
                "is_temporary": 0,
            }]

    def test_failed_trembed_unwrap_keeps_the_bridge(self):
        result = import_desicinemas.scrape_post_item(
            ("https://www.desicinemas.pk/test-film-2024/", "", "test-film-2024"),
            self._DeadClient())
        self.assertTrue(result["success"])
        sources = result["releases"][0]["sources"]
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["service"], "trembed")
        self.assertIn("trembed=abc123", sources[0]["final_url"])
        self.assertIsNotNone(result["releases"][0]["bridge_url"])

    def test_successful_unwrap_adds_no_fallback(self):
        result = import_desicinemas.scrape_post_item(
            ("https://www.desicinemas.pk/test-film-2024/", "", "test-film-2024"),
            self._LiveClient())
        sources = result["releases"][0]["sources"]
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["service"], "vidsrc")


# --------------------------------------------------------------------- #
# backfill script
# --------------------------------------------------------------------- #
class BackfillTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp.name) / "mini.db"
        conn = sqlite3.connect(self.db_path)
        conn.executescript(
            "CREATE TABLE releases (id INTEGER PRIMARY KEY, post_id INTEGER,"
            " source_site TEXT, intermediate_url TEXT);"
            "CREATE TABLE download_sources (id INTEGER PRIMARY KEY,"
            " release_id INTEGER, source_name TEXT, service TEXT,"
            " resource_id TEXT, source_url TEXT, final_url TEXT,"
            " is_temporary INTEGER DEFAULT 0, status TEXT DEFAULT 'active');")
        rows = [
            # (id, site, bridge, has_source)
            (1, "bolly4u", "https://new2.linkskeep.lat/view/AAA", False),
            (2, "bolly4u", "https://photojin.study/f/BBB", False),
            (3, "katmoviehd", "https://links.kmhd.me/file/CCC", False),
            (4, "katmoviehd", "https://links.kmhd.eu/file/DDD", False),
            (5, "desicinemas", None, False),                    # bridgeless
            (6, "desicinemas", "https://www.desicinemas.pk/x", False),  # site itself
            (7, "world4ufree", "https://unknown-bridge.example/f/EEE", False),
            (8, "bolly4u", "https://new2.linkskeep.lat/view/FFF", True),
        ]
        for rid, site, bridge, has_source in rows:
            conn.execute(
                "INSERT INTO releases (id, post_id, source_site, intermediate_url)"
                " VALUES (?, ?, ?, ?)", (rid, rid * 10, site, bridge))
            if has_source:
                conn.execute(
                    "INSERT INTO download_sources (release_id, service, source_url,"
                    " final_url, status) VALUES (?, 'gofile', 'https://x',"
                    " 'https://x', 'active')", (rid,))
        conn.commit()
        conn.close()

    def tearDown(self):
        self.tmp.cleanup()

    def test_dry_run_reports_and_writes_nothing(self):
        summary = backfill.run(str(self.db_path))
        self.assertFalse(summary["apply"])
        self.assertEqual(summary["candidates_with_bridge"], 6)
        self.assertEqual(summary["would_insert"], 4)
        self.assertEqual(summary["bridgeless_no_pointer"], 1)
        self.assertEqual(summary["unmapped_hosts"],
                         {"unknown-bridge.example": 1, "www.desicinemas.pk": 1})
        self.assertEqual(summary["by_service"],
                         {"linkskeep": 1, "photojin": 1, "kmhd": 2})
        conn = sqlite3.connect(self.db_path)
        self.assertEqual(
            conn.execute("SELECT COUNT(*) FROM download_sources").fetchone()[0], 1)
        conn.close()

    def test_apply_is_idempotent(self):
        first = backfill.run(str(self.db_path), apply=True)
        self.assertEqual(first["inserted"], 4)
        conn = sqlite3.connect(self.db_path)
        rows = conn.execute(
            "SELECT release_id, service, status FROM download_sources"
            " WHERE release_id IN (1, 2, 3, 4) ORDER BY release_id").fetchall()
        self.assertEqual([r[1] for r in rows],
                         ["linkskeep", "photojin", "kmhd", "kmhd"])
        self.assertTrue(all(r[2] == "unverified" for r in rows))
        conn.close()
        second = backfill.run(str(self.db_path), apply=True)
        self.assertEqual(second["inserted"], 0)
        self.assertEqual(second["candidates_with_bridge"], 2)  # unmapped only

    def test_site_filter_and_limit(self):
        summary = backfill.run(str(self.db_path), apply=True, site="bolly4u")
        self.assertEqual(summary["inserted"], 2)
        limited = backfill.run(str(self.db_path), apply=True, limit=1)
        self.assertEqual(limited["inserted"], 1)

    def test_bridge_for(self):
        self.assertEqual(
            backfill.bridge_for("https://new2.linkskeep.lat/view/X")[0], "linkskeep")
        self.assertEqual(
            backfill.bridge_for("https://links.kmhd.eu/file/X")[0], "kmhd")
        self.assertIsNone(backfill.bridge_for("https://www.desicinemas.pk/x"))
        self.assertIsNone(backfill.bridge_for("https://example.com/x"))
        self.assertIsNone(backfill.bridge_for(None))


# --------------------------------------------------------------------- #
# projection gap auditor
# --------------------------------------------------------------------- #
class ProjectionGapTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp.name) / "catalog.db"
        self._patches = [
            unittest.mock.patch.object(fast_fetch, "DB_PATH", self.db_path),
        ]
        for patcher in self._patches:
            patcher.start()
        fast_fetch.init_db(self.db_path)
        self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row

    def tearDown(self):
        self.conn.close()
        for patcher in self._patches:
            patcher.stop()
        self.tmp.cleanup()

    def _add_post(self, site, url, clean_title, year):
        return fast_fetch.save_scraped_data({
            "url": url,
            "slug": url.rstrip("/").split("/")[-1],
            "title": f"{clean_title} ({year})",
            "clean_title": clean_title,
            "year": year,
            "content_type": "movie",
            "source_site": site,
            "canonical_key": fast_fetch.generate_canonical_key(clean_title, year),
            "releases": [],
        }, enrich_metadata=False, ensure_db=False)

    def _insert_duplicate_post(self, site, url, clean_title, year):
        """A second ledger row for a work that already has one.

        Save-time identity would merge a same-key row, so a duplicate is
        inserted directly — which is how the live catalog holds its
        duplicates (different stored keys, same clean title + year, so the
        projector folds them into one work).
        """
        key = fast_fetch.generate_canonical_key(clean_title + " Hindi", year)
        cur = self.conn.execute(
            "INSERT INTO posts (url, slug, title, clean_title, year,"
            " content_type, source_site, canonical_key)"
            " VALUES (?, ?, ?, ?, ?, 'movie', ?, ?)",
            (url, url.rstrip("/").split("/")[-1], f"{clean_title} ({year})",
             clean_title, year, site, key))
        post_id = cur.lastrowid
        self.conn.execute(
            "INSERT INTO post_sources (post_id, source_site, url)"
            " VALUES (?, ?, ?)", (post_id, site, url))
        self.conn.commit()
        return post_id

    def test_gap_splits_fold_from_staleness(self):
        # Two posts, one work (fold); one post, one work.
        self._add_post("vegamovies", "https://vegamovies.lease/p/alpha",
                       "Alpha Film", 2024)
        self._insert_duplicate_post(
            "rogmovies", "https://rogmovies.one/p/alpha", "Alpha Film", 2024)
        self._add_post("vegamovies", "https://vegamovies.lease/p/beta",
                       "Beta Film", 2024)
        summary = catalog_v2.project(self.conn, rev=1, verbose=False)
        self.assertEqual(summary["works"], 2)
        meta = dict(self.conn.execute(
            "SELECT key, value FROM cat_projection_meta").fetchall())
        self.assertEqual(meta["posts_max_id"], "3")
        gap = catalog_v2.projection_gap(self.conn)
        self.assertEqual(gap["posts"], 3)
        self.assertEqual(gap["works"], 2)
        self.assertEqual(gap["gap_posts_minus_works"], 1)  # the fold
        self.assertEqual(gap["posts_since_projection"], 0)
        self.assertEqual(gap["new_works"], 0)

        # One brand-new title + one more post of an existing work.
        self._add_post("vegamovies", "https://vegamovies.lease/p/gamma",
                       "Gamma Film", 2025)
        self._insert_duplicate_post(
            "ssrmovies", "https://new1.ssrmovies.page/p/alpha", "Alpha Film", 2024)
        gap = catalog_v2.projection_gap(self.conn)
        self.assertEqual(gap["posts"], 5)
        self.assertEqual(gap["posts_since_projection"], 2)
        self.assertEqual(gap["new_works"], 1)
        self.assertEqual(gap["folding_posts"], 1)

    def test_gap_falls_back_to_the_wall_clock_stamp(self):
        self._add_post("vegamovies", "https://vegamovies.lease/p/alpha",
                       "Alpha Film", 2024)
        catalog_v2.project(self.conn, rev=1, verbose=False)
        self.conn.execute(
            "DELETE FROM cat_projection_meta WHERE key = 'posts_max_id'")
        # Stamp the projection in 2001: every post is "since" it by the
        # clock, but only keys missing from cat_works are new works.
        self.conn.execute(
            "UPDATE cat_projection_meta SET value = '1000000000'"
            " WHERE key = 'projected_at'")
        self.conn.commit()
        gap = catalog_v2.projection_gap(self.conn)
        self.assertIsNone(gap["posts_max_id"])
        self.assertEqual(gap["posts_since_projection"], 1)
        self.assertEqual(gap["new_works"], 0)
        self.assertEqual(gap["folding_posts"], 1)


if __name__ == "__main__":
    unittest.main()
