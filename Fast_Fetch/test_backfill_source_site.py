#!/usr/bin/env python3
"""Offline tests for `backfill_source_site.py` + `source_site.py`.

Temp databases only, no network, never touches a real catalog. The
fixture is a minimal `posts` table (id, url, source_site) with one row
per route the live catalog actually carries, measured 2026-11-23.
"""
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import backfill_source_site as bf
import fast_fetch
import source_site

#: (url, stored label, expected target or None when the row must not move)
FIXTURE = [
    ("https://hindilinks4u.com/foo/", "vegamovies", "hindilinks4u"),
    ("https://katmoviehd.icu/bar/", "vegamovies", "katmoviehd"),
    ("https://www.desicinemas.pk/movies/x/", "vegamovies", "desicinemas"),
    ("https://www.mp4moviez.deal/movie-1.html", "vegamovies", "mp4moviez"),
    ("https://themoviebox.xyz/detail/x-AbC/", "vegamovies", "themoviebox"),
    ("https://new1.vegamovies.lease/download-x/", "vegamovies", None),
    ("https://bulwik.com/movie-y/", "vegamovies", "castle"),
    ("https://hdutv.com/movie-z.html", "vegamovies", "hdtvb"),
    ("https://9xmovies.foo/movie/", "pagalmovies", "9xmovies"),
    ("https://kuttymovies.wiki/movie/", "ssrmovies", "kuttymovies"),
    ("https://unknown-host.example/movie/", "vegamovies", None),
    ("https://hindilinks4u.com/ok/", "hindilinks4u", None),
    ("", "vegamovies", None),
    ("https://filmiy.in/movie/", "vegamovies", "filmiyin"),
    ("https://archive.org/details/movie", "vegamovies", "archive.org"),
    ("https://9xmovie.bayern/movie/", "vegamovies", "9xmovies"),
]

URL_CASES = [
    ("https://www.9xmovies.win/x/", "9xmovies"),
    ("https://9xmovie.bayern/x/", "9xmovies"),
    ("https://bolly4u.foo/x/", "bolly4u"),
    ("https://10xflix.beer/x/", "10xflix"),
    ("https://world4ufree.men/x/", "world4ufree"),
    ("https://filmyzilla.foo/x/", "filmyzilla"),
    ("https://hdhub4u.cl/x/", "hdhub4u"),
    ("https://toonworld4all.foo/x/", "toonworld4all"),
    ("https://www.dramadog.foo/x/", "dramadog"),
    ("https://moviesflix.casa/x/", "moviesflix"),
    ("https://filmyhit.mobile/x/", "filmyhit"),
    ("https://www.pagalmovies.fit/x/", "pagalmovies"),
    ("https://www.5movierulz.green/x/", "5movierulz"),
    ("https://moviecok.xyz/x/", "moviecok"),
    ("https://beta.levidia.ch/x/", "levidia"),
    ("https://filmywap.site/x/", "filmywap"),
    ("https://kisskh.asia/x/", "kisskh"),
    ("https://hum.tv/x/", "humtv"),
    ("https://www.moviesmela.com/x/", "moviesmela"),
    ("https://WWW.MP4MOVIEZ.DEAL/X.HTML", "mp4moviez"),
    ("https://castlevania.example/x/", None),
    ("not a url", None),
    (None, None),
    ("", None),
]


class BackfillSourceSiteTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "site.db"
        conn = sqlite3.connect(str(self.db_path))
        conn.execute("CREATE TABLE posts (id INTEGER PRIMARY KEY, url TEXT, "
                     "source_site TEXT)")
        conn.executemany("INSERT INTO posts (url, source_site) VALUES (?, ?)",
                         [(url, stored) for url, stored, _ in FIXTURE])
        conn.commit()
        conn.close()

    def tearDown(self):
        self.temp_dir.cleanup()

    def _rows(self):
        conn = sqlite3.connect(str(self.db_path))
        try:
            return conn.execute(
                "SELECT url, source_site FROM posts ORDER BY id").fetchall()
        finally:
            conn.close()

    def test_site_for_url_cases(self):
        for url, expected in URL_CASES:
            with self.subTest(url=url):
                self.assertEqual(source_site.site_for_url(url), expected)

    def test_scan_plans_only_mislabelled_rows(self):
        conn = sqlite3.connect(str(self.db_path))
        try:
            plan = bf.scan(conn)
        finally:
            conn.close()
        self.assertEqual(plan["total"], len(FIXTURE))
        self.assertEqual(plan["unknown"], 2)
        expected = {(url, stored, target)
                    for url, stored, target in FIXTURE if target is not None}
        got = {(self._url_by_id(pid), stored, target)
               for pid, stored, target in plan["planned"]}
        self.assertEqual(got, expected)
        self.assertEqual(plan["by_route"][("vegamovies", "hindilinks4u")], 1)
        self.assertEqual(plan["by_route"][("vegamovies", "desicinemas")], 1)
        self.assertEqual(plan["by_route"][("pagalmovies", "9xmovies")], 1)

    def _url_by_id(self, post_id):
        conn = sqlite3.connect(str(self.db_path))
        try:
            return conn.execute("SELECT url FROM posts WHERE id = ?",
                                (post_id,)).fetchone()[0]
        finally:
            conn.close()

    def test_dry_run_writes_nothing(self):
        before = self._rows()
        report = bf.run(self.db_path, apply=False, progress=False)
        self.assertEqual(report["planned"], 12)
        self.assertEqual(report["applied"], 0)
        self.assertEqual(self._rows(), before)

    def test_apply_relabels_and_second_run_is_empty(self):
        report = bf.run(self.db_path, apply=True, progress=False)
        self.assertEqual(report["applied"], 12)
        self.assertEqual(report["verified_remaining"], 0)
        self.assertEqual(report["total_posts"], len(FIXTURE))
        rows = dict(self._rows())
        self.assertEqual(rows["https://hindilinks4u.com/foo/"], "hindilinks4u")
        self.assertEqual(rows["https://www.desicinemas.pk/movies/x/"], "desicinemas")
        self.assertEqual(rows["https://bulwik.com/movie-y/"], "castle")
        self.assertEqual(rows["https://hdutv.com/movie-z.html"], "hdtvb")
        self.assertEqual(rows["https://9xmovies.foo/movie/"], "9xmovies")
        self.assertEqual(rows["https://kuttymovies.wiki/movie/"], "kuttymovies")
        self.assertEqual(rows["https://new1.vegamovies.lease/download-x/"], "vegamovies")
        self.assertEqual(rows["https://unknown-host.example/movie/"], "vegamovies")
        self.assertEqual(report["after"]["vegamovies"], 3)
        second = bf.run(self.db_path, apply=True, progress=False)
        self.assertEqual(second["planned"], 0)
        self.assertEqual(second["applied"], 0)

    def test_live_filename_is_refused_without_the_flag(self):
        live_path = Path(self.temp_dir.name) / "merged_catalog_20261025.db"
        self.assertEqual(bf.main([str(live_path)]), 2)
        self.assertFalse(live_path.exists())


class SaveScrapedDataSourceSiteGuardTests(unittest.TestCase):
    """The save choke point must file a post under its page's site.

    Regression test for the misattribution: a payload with no
    source_site (or the 'vegamovies' the validator defaults to) used to
    land every site's posts under the vegamovies label. Temp DB only.
    """

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "site.db"
        self.patch = patch.object(fast_fetch, "DB_PATH", self.db_path)
        self.patch.start()
        fast_fetch.init_db()

    def tearDown(self):
        self.patch.stop()
        self.temp_dir.cleanup()

    def _save(self, url, **extra):
        data = {"url": url, "title": "Guard Test (2024)",
                "clean_title": "Guard Test", "year": 2024,
                "content_type": "movie", "releases": []}
        data.update(extra)
        return fast_fetch.save_scraped_data(data, enrich_metadata=False)

    def _stored_site(self, url):
        conn = sqlite3.connect(str(self.db_path))
        try:
            return conn.execute("SELECT source_site FROM posts WHERE url = ?",
                                (url,)).fetchone()[0]
        finally:
            conn.close()

    def test_missing_source_site_is_derived_from_url(self):
        url = "https://hindilinks4u.com/guard-test/"
        self._save(url)
        self.assertEqual(self._stored_site(url), "hindilinks4u")

    def test_explicit_vegamovies_default_is_overridden(self):
        url = "https://katmoviehd.icu/guard-test/"
        self._save(url, source_site="vegamovies")
        self.assertEqual(self._stored_site(url), "katmoviehd")

    def test_explicit_non_default_label_is_never_overridden(self):
        url = "https://new1.vegamovies.lease/guard-test/"
        self._save(url, source_site="multimovies")
        self.assertEqual(self._stored_site(url), "multimovies")

    def test_true_vegamovies_url_keeps_the_label(self):
        url = "https://new1.vegamovies.lease/guard-test-2/"
        self._save(url)
        self.assertEqual(self._stored_site(url), "vegamovies")

    def test_unknown_host_falls_back_to_domain_stem(self):
        # (Wave 6 merge: the 'vegamovies' expectation was stale —
        # pre-insert-helper, when the validator defaulted to it.
        # Insert-helper derives the domain stem for an unknown host,
        # pinned by its own test, so the stem is stored.)
        url = "https://unknown-host.example/guard-test/"
        self._save(url)
        self.assertEqual(self._stored_site(url), "unknown-host")


if __name__ == "__main__":
    unittest.main()
