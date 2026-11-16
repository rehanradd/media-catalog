"""HTTP-level proof that the list routes honor `content_type=...`.

Bug (live, self-check 13:31 PKT 2026-11-16): ``GET /api/v1/posts?content_type=series``
returned total 208,176 — every unflagged post — because the HTTP handler read
only the ``type`` query param and never looked at ``content_type``. The function
layer (``viewer.api_v1_get_posts``, ``catalog_api.browse``) filters correctly,
and the function-level tests in ``test_database.py`` call it directly, so the
drop between the URL and the call was invisible. The same drop existed on
``/api/v1/works``, ``/api/v1/trending`` and v2's ``listing_kwargs`` (which read
``type``/``work_type`` only).

The fix is an alias, not a rename: the app sends ``type=`` today
(``RaddStreamApi.getPosts``, ``browseWorks``), so ``type`` keeps working and
``content_type`` wins when both are sent.
"""

from __future__ import annotations

import json
import os
import sqlite3
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path
from unittest.mock import patch

import api_v2
import fast_fetch
import redact
import viewer

TOKEN_ENV = "RADD_ADMIN_TOKEN"
STATE_DB_ENV = "RADD_USER_STATE_DB"


def _media(site, url, *, clean_title, year, content_type):
    """One ingest payload in the shape a scraper hands the ledger."""
    return {
        "url": url,
        "slug": url.rstrip("/").split("/")[-1],
        "title": f"{clean_title} ({year})",
        "clean_title": clean_title,
        "year": year,
        "content_type": content_type,
        "seasons": None,
        "poster_url": None,
        "backdrop_urls": None,
        "imdb_rating": 7.1,
        "genres": json.dumps(["Action"]),
        "synopsis": f"A title seen on {site}.",
        "is_18_plus": 0,
        "is_animated": 0,
        "source_site": site,
        "industry": "Hollywood",
        "country": "USA",
        "language": "English",
        "canonical_key": fast_fetch.generate_canonical_key(clean_title, year),
        "tmdb_id": None,
        "imdb_id": None,
        "releases": [{
            "release_title": f"{clean_title} ({year}) 1080p",
            "quality": "1080p",
            "codec": "x264",
            "size": "2.4GB",
            "languages": "English",
            "season": None,
            "episode": None,
            "intermediate_url": f"https://{site}.example/release",
            "sources": [{
                "source_name": "PixelDrain [Direct CDN]",
                "service": "pixeldrain",
                "resource_id": f"{site}-res-1",
                "source_url": f"https://{site}.example/dl/1",
                "final_url": "https://pixeldrain.com/u/AbCdEf12",
                "is_temporary": 0,
            }],
        }],
    }


class SeriesFilterHttpTests(unittest.TestCase):
    """One movie row + one series row, served over real HTTP."""

    @classmethod
    def setUpClass(cls):
        cls.saved_token = os.environ.get(TOKEN_ENV)
        os.environ[TOKEN_ENV] = "series-filter-suite-operator-credential"
        cls.saved_state = os.environ.get(STATE_DB_ENV)
        cls.state_tmp = tempfile.TemporaryDirectory()
        os.environ[STATE_DB_ENV] = str(Path(cls.state_tmp.name) / "user_state.db")
        cls.server = viewer.make_server("127.0.0.1", 0, operator=False)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.port = cls.server.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        if cls.saved_token is None:
            os.environ.pop(TOKEN_ENV, None)
        else:
            os.environ[TOKEN_ENV] = cls.saved_token
        if cls.saved_state is None:
            os.environ.pop(STATE_DB_ENV, None)
        else:
            os.environ[STATE_DB_ENV] = cls.saved_state
        cls.state_tmp.cleanup()

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "series_filter.db"
        self.patches = [
            patch.object(fast_fetch, "DB_PATH", self.db_path),
            patch.object(fast_fetch, "fetch_cinemeta_metadata", return_value=None),
            patch.object(viewer, "DB_PATH", self.db_path),
        ]
        for p in self.patches:
            p.start()
        fast_fetch.init_db()
        fast_fetch.save_scraped_data(_media(
            "site_a", "https://site-a.example/example-film/",
            clean_title="Example Film", year=2024, content_type="movie"))
        fast_fetch.save_scraped_data(_media(
            "site_b", "https://site-b.example/example-series/",
            clean_title="Example Series", year=2024, content_type="series"))
        viewer._ensure_schema()
        redact.limiter._buckets.clear()

    def tearDown(self):
        for p in reversed(self.patches):
            p.stop()
        self.temp_dir.cleanup()

    def _get(self, path):
        with urllib.request.urlopen(
                f"http://127.0.0.1:{self.port}{path}", timeout=10) as resp:
            self.assertEqual(resp.status, 200, path)
            return json.loads(resp.read().decode("utf-8"))

    def test_content_type_series_filters_posts(self):
        data = self._get("/api/v1/posts?content_type=series&limit=10")
        self.assertEqual(data["total"], 1, data)
        self.assertEqual([r["clean_title"] for r in data["results"]],
                         ["Example Series"])

    def test_content_type_movie_filters_posts(self):
        data = self._get("/api/v1/posts?content_type=movie&limit=10")
        self.assertEqual(data["total"], 1, data)
        self.assertEqual([r["clean_title"] for r in data["results"]],
                         ["Example Film"])

    def test_unfiltered_posts_see_both_rows(self):
        data = self._get("/api/v1/posts?limit=10")
        self.assertEqual(data["total"], 2, data)

    def test_type_alias_still_filters_posts(self):
        """The app sends `type=` today; the alias must keep working."""
        data = self._get("/api/v1/posts?type=series&limit=10")
        self.assertEqual(data["total"], 1, data)
        self.assertEqual([r["clean_title"] for r in data["results"]],
                         ["Example Series"])

    def test_content_type_wins_over_type(self):
        data = self._get("/api/v1/posts?content_type=series&type=movie&limit=10")
        self.assertEqual(data["total"], 1, data)
        self.assertEqual([r["clean_title"] for r in data["results"]],
                         ["Example Series"])

    def test_content_type_series_filters_works(self):
        data = self._get("/api/v1/works?content_type=series&limit=10")
        self.assertEqual(data["total"], 1, data)
        self.assertEqual([r["clean_title"] for r in data["results"]],
                         ["Example Series"])

    def test_content_type_series_filters_trending(self):
        data = self._get("/api/v1/trending?content_type=series&limit=10")
        self.assertEqual(data["total"], 1, data)
        self.assertEqual([r["clean_title"] for r in data["results"]],
                         ["Example Series"])


class ListingKwargsTests(unittest.TestCase):
    """v2's `listing_kwargs` must read `content_type` too, not only
    the `type`/`work_type` aliases the app sends today."""

    def _kwargs(self, query, *, for_search=False):
        return api_v2.listing_kwargs(query, operator=False, for_search=for_search)

    def test_content_type_is_read(self):
        self.assertEqual(
            self._kwargs({"content_type": ["series"]})["content_type"], "series")

    def test_type_alias_still_read(self):
        self.assertEqual(
            self._kwargs({"type": ["movie"]})["content_type"], "movie")

    def test_work_type_alias_still_read(self):
        self.assertEqual(
            self._kwargs({"work_type": ["series"]})["content_type"], "series")

    def test_content_type_wins_over_aliases(self):
        self.assertEqual(
            self._kwargs({"content_type": ["series"], "type": ["movie"],
                          "work_type": ["movie"]})["content_type"], "series")

    def test_content_type_is_read_for_search(self):
        self.assertEqual(
            self._kwargs({"content_type": ["series"]},
                         for_search=True)["content_type"], "series")


if __name__ == "__main__":
    unittest.main()
