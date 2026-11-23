#!/usr/bin/env python3
"""Offline tests for the shared source_site attribution fix (audit 2026-11-23).

`fast_fetch.save_scraped_data()` is the one insert helper every importer
and every merge funnels through. It used to file any payload without a
source_site under 'vegamovies' — which is how ~90,700 rows from other
sites ended up labelled vegamovies. The helper now derives the site from
the page's own URL (domain_registry.json first, then the domain stem),
an explicit site still wins, and a URL with no attributable host is
refused instead of filed under another site's name. The same rule covers
the legacy page parser, the init_db auto-classify pass, and the
staging -> unified merge.

Temp databases only, no network, never touches a real catalog.
"""

import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import fast_fetch
import merge_catalogs


def _ledger(db_path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(str(db_path))
    conn.row_factory = sqlite3.Row
    return conn


def _payload(url, clean_title, year=2024, *, releases=None, **extra):
    """An importer-style payload in the shape a scraper hands the ledger.

    Deliberately carries NO source_site unless the caller passes one:
    that is the case the helper must now attribute from the URL.
    """
    data = {
        "url": url,
        "slug": url.rstrip("/").split("/")[-1],
        "title": f"{clean_title} ({year})",
        "clean_title": clean_title,
        "year": year,
        "content_type": "movie",
        "releases": releases if releases is not None else [],
    }
    data.update(extra)
    return data


def _one_release():
    return [{
        "release_title": "Example Film 1080p",
        "quality": "1080p",
        "codec": "x264",
        "size": "1GB",
        "languages": "English",
        "season": "",
        "episode": "",
        "intermediate_url": "",
        "sources": [{
            "source_name": "Direct",
            "service": "cdn",
            "resource_id": "ex1",
            "source_url": "https://cdn.example/ex1.mp4",
            "final_url": "https://cdn.example/ex1.mp4",
            "is_temporary": 0,
        }],
    }]


class SourceSiteHelperTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "site.db"
        self.patch = patch.object(fast_fetch, "DB_PATH", self.db_path)
        self.patch.start()
        fast_fetch.init_db()

    def tearDown(self):
        self.patch.stop()
        self.temp_dir.cleanup()

    def _site_of(self, post_id):
        with _ledger(self.db_path) as conn:
            return conn.execute(
                "SELECT source_site FROM posts WHERE id = ?", (post_id,)
            ).fetchone()["source_site"]

    def test_missing_source_site_is_derived_from_post_url(self):
        """The audit's headline case: a 9xmovies page with no site on the
        payload must be filed as 9xmovies, not vegamovies — post, release
        and post_sources alike."""
        post_id = fast_fetch.save_scraped_data(
            _payload("https://9xmovie.cologne/movie/example-film-2024/",
                     "Example Film", releases=_one_release()),
            enrich_metadata=False, ensure_db=False)
        self.assertEqual(self._site_of(post_id), "9xmovies")
        with _ledger(self.db_path) as conn:
            rel_site = conn.execute(
                "SELECT source_site FROM releases WHERE post_id = ?",
                (post_id,)).fetchone()["source_site"]
            src_site = conn.execute(
                "SELECT source_site FROM post_sources WHERE post_id = ?",
                (post_id,)).fetchone()["source_site"]
        self.assertEqual(rel_site, "9xmovies")
        self.assertEqual(src_site, "9xmovies")

    def test_registry_mirror_variants_map_to_the_site(self):
        """Slug variants and mirror hosts from domain_registry.json name
        the site even when the host spells it differently."""
        cases = [
            ("https://katmovieshd.net/movie/example-film-2024/",
             "Katmoviehd Film", "katmoviehd"),
            ("https://www.bulwik.com/movie/example-film-2024/",
             "Castle Film", "castle"),
            ("https://123moviesfree.club/movie/example-film-2024/",
             "Moviebox Film", "themoviebox"),
            ("https://hindilinks4u.pink/movie/example-film-2024/",
             "Hindilinks Film", "hindilinks4u"),
            ("https://new16.hdhub4u.cl/movie/example-film-2024/",
             "Hdhub Film", "hdhub4u"),
            ("https://vegamovies.lease/movie/example-film-2024/",
             "Vegamovies Film", "vegamovies"),
        ]
        for url, title, expected in cases:
            with self.subTest(url=url):
                post_id = fast_fetch.save_scraped_data(
                    _payload(url, title), enrich_metadata=False, ensure_db=False)
                self.assertEqual(self._site_of(post_id), expected)

    def test_unknown_host_falls_back_to_domain_stem(self):
        """A host the registry has never seen is still its own site: the
        site's name IS its domain stem for every importer in this tree."""
        post_id = fast_fetch.save_scraped_data(
            _payload("https://some-new-site.example/movie/example-film-2024/",
                     "Example Film"), enrich_metadata=False, ensure_db=False)
        self.assertEqual(self._site_of(post_id), "some-new-site")

    def test_explicit_source_site_wins(self):
        """The helper fills a gap; it never second-guesses an importer
        that names its site. (A stem heuristic cannot tell 'site_a' from
        'site-a', so an explicit value is authoritative.)"""
        post_id = fast_fetch.save_scraped_data(
            _payload("https://9xmovie.cologne/movie/example-film-2024/",
                     "Example Film", source_site="vegamovies"),
            enrich_metadata=False, ensure_db=False)
        self.assertEqual(self._site_of(post_id), "vegamovies")

    def test_unattributable_url_is_refused_not_mislabelled(self):
        """An IP host names no site. The write must fail loudly and write
        nothing — never file the row under another site's name."""
        with self.assertRaises(ValueError):
            fast_fetch.save_scraped_data(
                _payload("http://127.0.0.1/movie/example-film-2024/",
                         "Example Film"),
                enrich_metadata=False, ensure_db=False)
        with _ledger(self.db_path) as conn:
            count = conn.execute("SELECT COUNT(*) FROM posts").fetchone()[0]
        self.assertEqual(count, 0)

    def test_inferred_site_drives_the_animation_flag(self):
        """The inferred site must feed the adult/animation flags exactly
        like an asserted one, or a rareanimes page filed without a site
        would lose its Anime flag."""
        post_id = fast_fetch.save_scraped_data(
            _payload("https://rareanimes.test/suke-shi-donghuarenge/",
                     "Suke Shi", content_type="series"),
            enrich_metadata=False, ensure_db=False)
        self.assertEqual(self._site_of(post_id), "rareanimes")
        with _ledger(self.db_path) as conn:
            animated = conn.execute(
                "SELECT is_animated FROM posts WHERE id = ?",
                (post_id,)).fetchone()[0]
        self.assertEqual(animated, 1)

    def test_fresh_db_has_no_vegamovies_default(self):
        """A fresh database must not stamp 'vegamovies' on a raw insert
        that omits source_site: unattributed stays NULL, not mislabelled."""
        with _ledger(self.db_path) as conn:
            conn.execute(
                "INSERT INTO posts (url, slug, title, clean_title, canonical_key)"
                " VALUES ('https://9xmovie.cologne/movie/example-film-2024/',"
                " 'example-film-2024', 'Example Film (2024)', 'Example Film', ?)",
                (fast_fetch.generate_canonical_key("Example Film", 2024),))
            conn.commit()
            site = conn.execute(
                "SELECT source_site FROM posts WHERE slug = 'example-film-2024'"
            ).fetchone()["source_site"]
        self.assertIsNone(site)

    def test_init_db_does_not_stamp_unattributed_rows_vegamovies(self):
        """The init_db auto-classify pass used to stamp every NULL-site row
        'vegamovies' on every init. A row from a 9xmovies page must come
        out 9xmovies; a row from an unattributable host must stay NULL."""
        with _ledger(self.db_path) as conn:
            for url, slug in (
                ("https://9xmovie.cologne/movie/example-film-2024/",
                 "example-film-2024"),
                ("http://127.0.0.1/movie/other-film-2024/",
                 "other-film-2024"),
            ):
                conn.execute(
                    "INSERT INTO posts (url, slug, title, clean_title,"
                    " year, content_type, canonical_key)"
                    " VALUES (?, ?, ?, ?, 2024, 'movie', ?)",
                    (url, slug, f"{slug} (2024)", slug,
                     fast_fetch.generate_canonical_key(slug, 2024)))
            conn.commit()
        fast_fetch.init_db()
        with _ledger(self.db_path) as conn:
            rows = {r["slug"]: r["source_site"] for r in conn.execute(
                "SELECT slug, source_site FROM posts")}
        self.assertEqual(rows["example-film-2024"], "9xmovies")
        self.assertIsNone(rows["other-film-2024"])

    def test_legacy_page_parser_names_the_page_host(self):
        """parse_post_metadata is the fallback parser for pages no site
        adapter matches. It used to guess rogmovies-else-vegamovies for
        every such page; the page's own host must name the site now."""
        meta = fast_fetch.parse_post_metadata(
            "https://9xmovie.cologne/movie/example-film-2024/",
            "<html><head><title>Example Film (2024) 720p</title></head></html>")
        self.assertEqual(meta["source_site"], "9xmovies")

    def test_infer_source_site(self):
        """Unit pins for the URL -> site derivation itself."""
        self.assertEqual(
            fast_fetch.infer_source_site("https://katmovieshd.net/x/"), "katmoviehd")
        self.assertEqual(
            fast_fetch.infer_source_site("https://1vegamovies.shop/x/"), "vegamovies")
        self.assertEqual(
            fast_fetch.infer_source_site("https://www.bulwik.com/x/"), "castle")
        self.assertEqual(
            fast_fetch.infer_source_site("https://new1.vegamovies.lease/x/"),
            "vegamovies")
        self.assertIsNone(fast_fetch.infer_source_site("http://127.0.0.1/x/"))
        self.assertIsNone(fast_fetch.infer_source_site(""))
        self.assertIsNone(fast_fetch.infer_source_site(None))
        # strong_only: registry evidence only, no stem fallback.
        self.assertEqual(
            fast_fetch.infer_source_site("https://katmovieshd.net/x/",
                                         strong_only=True), "katmoviehd")
        self.assertIsNone(
            fast_fetch.infer_source_site("https://some-new-site.example/x/",
                                         strong_only=True))


class MergeAttributionTests(unittest.TestCase):
    """The staging -> unified merge funnels through the same helper: a
    staging row that does not name its site is attributed from the page
    it scraped, not from the staging file's name."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.td = Path(self.temp_dir.name)
        self.orig_db_path = fast_fetch.DB_PATH

    def tearDown(self):
        fast_fetch.DB_PATH = self.orig_db_path
        self.temp_dir.cleanup()

    def _staging_db(self, filename, *, site_value, post_url, title):
        """A minimal staging-family DB (posts/releases/sources), mirroring
        the per-site staging importers' schema."""
        path = self.td / filename
        with sqlite3.connect(str(path)) as conn:
            conn.execute(
                """
                CREATE TABLE posts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    canonical_key TEXT UNIQUE,
                    title TEXT NOT NULL,
                    clean_title TEXT,
                    year INTEGER,
                    media_type TEXT DEFAULT 'movie',
                    poster_url TEXT,
                    synopsis TEXT,
                    genres TEXT,
                    cast_names TEXT,
                    director TEXT,
                    languages TEXT,
                    site TEXT,
                    post_url TEXT
                )
                """)
            conn.execute(
                """
                CREATE TABLE releases (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    post_canonical_key TEXT,
                    label TEXT,
                    quality TEXT,
                    resolution TEXT,
                    size_bytes INTEGER,
                    codec TEXT,
                    season_number INTEGER,
                    episode_number INTEGER,
                    audio_languages TEXT,
                    bridge_url TEXT
                )
                """)
            conn.execute(
                """
                CREATE TABLE sources (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    release_id INTEGER,
                    source_name TEXT,
                    service TEXT,
                    resource_id TEXT,
                    source_url TEXT,
                    final_url TEXT,
                    is_temporary INTEGER DEFAULT 0,
                    quality TEXT
                )
                """)
            key = fast_fetch.generate_canonical_key(title, 2024)
            cur = conn.execute(
                "INSERT INTO posts (canonical_key, title, clean_title, year,"
                " media_type, site, post_url) VALUES (?, ?, ?, 2024, 'movie', ?, ?)",
                (key, f"{title} (2024)", title, site_value, post_url))
            cur = conn.execute(
                "INSERT INTO releases (post_canonical_key, label, quality)"
                " VALUES (?, ?, '1080p')", (key, f"{title} 1080p"))
            rel_id = cur.lastrowid
            conn.execute(
                "INSERT INTO sources (release_id, source_name, service,"
                " resource_id, source_url, final_url)"
                " VALUES (?, 'Direct', 'cdn', ?, ?, ?)",
                (rel_id, f"{title}-1", "https://cdn.example/x.mp4",
                 "https://cdn.example/x.mp4"))
            conn.commit()
        return path

    def _merged_site(self, target_db, clean_title):
        with _ledger(target_db) as conn:
            return conn.execute(
                "SELECT source_site FROM posts WHERE clean_title = ?",
                (clean_title,)).fetchone()["source_site"]

    def test_merge_labels_a_siteless_staging_row_from_its_url(self):
        """The staging file is named neutrally and the row names no site:
        the merged row must still be 9xmovies, from its page URL."""
        src = self._staging_db(
            "staging.db", site_value=None, title="Staging Film",
            post_url="https://9xmovie.cologne/movie/staging-film-2024/")
        target = self.td / "unified.db"
        stats = merge_catalogs.merge_site_db(src, target, verbose=False)
        self.assertEqual(stats["posts_merged"], 1)
        self.assertEqual(self._merged_site(target, "Staging Film"), "9xmovies")

    def test_merge_keeps_the_staging_rows_own_site(self):
        """A staging row that names its site keeps it."""
        src = self._staging_db(
            "hindilinks4u.db", site_value="hindilinks4u", title="Staging Film",
            post_url="https://www.hindilinks4u.zip/movie/staging-film-2024/")
        target = self.td / "unified.db"
        merge_catalogs.merge_site_db(src, target, verbose=False)
        self.assertEqual(self._merged_site(target, "Staging Film"), "hindilinks4u")

    def test_merge_filename_stem_is_only_the_last_resort(self):
        """Neither the row nor the page names a site (IP host): only then
        does the staging file's name supply the attribution."""
        src = self._staging_db(
            "9xmovies.db", site_value=None, title="Staging Film",
            post_url="http://127.0.0.1/movie/staging-film-2024/")
        target = self.td / "unified.db"
        merge_catalogs.merge_site_db(src, target, verbose=False)
        self.assertEqual(self._merged_site(target, "Staging Film"), "9xmovies")


if __name__ == "__main__":
    unittest.main(verbosity=2)
