#!/usr/bin/env python3
"""Offline tests for `metadata_backfill.py`.

Nothing here opens a socket: upstream clients are fed by a fake HTTP layer
returning canned payloads, and the database is a temporary file. The tests
pin the decisions that can quietly poison the catalog if they drift:

* only EMPTY fields are ever filled — a stored rating is never overwritten
  by a bulk pass (the IMDb-datasets overwrite guard);
* a title search must not accept a different work (year/title guard);
* a rating with source=tmdb is a TMDb vote_average, not an IMDb rating, and
  the report says so;
* the input database is never written — the plan is staging SQL, and
  --apply-to only ever writes a copy;
* adult rows are skipped by every pass.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import os
import sqlite3
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))

import metadata_backfill as mb

POST_COLUMNS = (
    "id INTEGER PRIMARY KEY", "url TEXT", "slug TEXT", "title TEXT",
    "clean_title TEXT", "year INTEGER", "content_type TEXT", "seasons INTEGER",
    "poster_url TEXT", "backdrop_urls TEXT", "imdb_rating TEXT", "genres TEXT",
    "synopsis TEXT", "is_18_plus INTEGER", "is_animated INTEGER",
    "lastmod TEXT", "created_at TEXT", "updated_at TEXT", "source_site TEXT",
    "industry TEXT", "country TEXT", "language TEXT", "canonical_key TEXT",
    "tmdb_id TEXT", "imdb_id TEXT", "cast_names TEXT", "duration TEXT",
    "director TEXT", "release_date TEXT", "raw_meta TEXT",
)


def make_db(path: Path, rows) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.execute(f"CREATE TABLE posts ({', '.join(POST_COLUMNS)})")
    for row in rows:
        conn.execute(
            "INSERT INTO posts (id, title, year, content_type, imdb_rating,"
            " synopsis, poster_url, cast_names, director, imdb_id, tmdb_id,"
            " genres, canonical_key, is_18_plus, is_animated)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row)
    conn.commit()
    return conn


def fixture_rows():
    """(id, title, year, type, rating, synopsis, poster, cast, director,
    imdb_id, tmdb_id, genres, key, adult, animated)."""
    return [
        (1, "Dune: Part Two", 2024, "movie", "8.6", "Paul unites the Fremen.",
         "http://x/poster.jpg", "Timothee Chalamet, Zendaya", "Denis Villeneuve",
         "tt15239678", "693134", '["Sci-Fi"]', "dune-2024", 0, 0),
        (2, "Dune Part Two Hindi Dubbed", 2024, "movie", None, None, None,
         None, None, None, None, None, "dune-2024", 0, 0),
        (3, "Solo Film", 2020, "movie", None, None, None, None, None,
         "tt0000001", None, None, "solo-2020", 0, 0),
        (4, "Rated Already", 2019, "movie", "6.1", None, None, None, None,
         "tt0000002", None, None, "rated-2019", 0, 0),
        (5, "Adult Film", 2021, "movie", None, None, None, None, None,
         "tt0000003", None, None, "adult-2021", 1, 0),
        (6, "Keyless Film", 2019, "movie", None, None, None, None, None,
         None, None, None, None, 0, 0),
        (7, "Anime Title", 2023, "series", None, None, None, None, None,
         None, None, None, "anime-2023", 0, 1),
    ]


class FakeHttp:
    """A canned HTTP layer: URL substring → payload (or exception)."""

    def __init__(self, routes: dict):
        self.routes = routes
        self.calls: list[str] = []

    def _match(self, url: str):
        self.calls.append(url)
        for needle, payload in self.routes.items():
            if needle in url:
                if isinstance(payload, Exception):
                    raise payload
                return payload
        raise mb.UpstreamError("HTTP 404")

    def get_json(self, url: str, *, headers=None):
        return self._match(url)

    def post_json(self, url: str, payload: dict, *, headers=None):
        return self._match(url)


class FakeTmdb(mb.Source):
    """A source that answers one title and nothing else (no network)."""

    NAME = "tmdb"

    def lookup(self, query: dict):
        if query.get("title") == "Dune Part Two Hindi Dubbed":
            out = mb.Metadata()
            out.set("synopsis", "Paul unites the Fremen.", "tmdb")
            out.set("rating", 8.6, "tmdb")
            out.set("cast", ["Timothee Chalamet", "Zendaya"], "tmdb")
            return out
        return None


class FailingSource(mb.Source):
    NAME = "omdb"

    def lookup(self, query: dict):
        raise mb.UpstreamError("HTTP 500")


class NormalizeRatingTests(unittest.TestCase):
    def test_plain_decimal(self):
        self.assertEqual(mb.normalize_rating("7.5"), "7.5")
        self.assertEqual(mb.normalize_rating(8.6), "8.6")
        self.assertEqual(mb.normalize_rating("8.54"), "8.5")

    def test_suffixed(self):
        self.assertEqual(mb.normalize_rating("7.5/10"), "7.5")
        self.assertEqual(mb.normalize_rating("75/100"), "7.5")

    def test_hundred_scale(self):
        self.assertEqual(mb.normalize_rating(86), "8.6")
        self.assertEqual(mb.normalize_rating("82"), "8.2")
        # A bare 11 is an 11/100 score, not garbage: 1.1 is its conversion.
        self.assertEqual(mb.normalize_rating(11), "1.1")

    def test_not_a_score(self):
        for bad in (None, 0, "0", "0.0", 101, "101", "N/A", "86%", "", True):
            with self.subTest(bad=bad):
                self.assertIsNone(mb.normalize_rating(bad))


class TitleMatchingTests(unittest.TestCase):
    def test_noise_is_stripped(self):
        self.assertEqual(mb.normalize_title("Dune: Part Two (2024) Hindi Dubbed"),
                         "dune part two")
        self.assertEqual(mb.normalize_title("Dune Part Two 720p"), "dune part two")

    def test_exact_and_suffix_match(self):
        self.assertTrue(mb.titles_match("Dune Part Two", "Dune: Part Two"))
        self.assertTrue(mb.titles_match("One Piece Season 1", "One Piece"))

    def test_different_work_does_not_match(self):
        self.assertFalse(mb.titles_match("Dune", "Dune Part Two"))
        self.assertFalse(mb.titles_match("Dune Part Two", "Dune Part Three"))
        self.assertFalse(mb.titles_match("", "Dune"))

    def test_query_may_carry_a_suffix(self):
        # The query may be longer (a season suffix); the candidate may not.
        self.assertTrue(mb.titles_match("Dune Part Two", "Dune"))

    def test_year_guard(self):
        self.assertTrue(mb.years_compatible(2023, 2024))
        self.assertTrue(mb.years_compatible(None, 2024))
        self.assertFalse(mb.years_compatible(2019, 2024))

    def test_match_candidate(self):
        query = {"title": "Dune Part Two", "year": 2024}
        self.assertTrue(mb.match_candidate(query, "Dune: Part Two", "2024-03-01"))
        self.assertFalse(mb.match_candidate(query, "Dune: Part Two", "2019-01-01"))
        self.assertFalse(mb.match_candidate(query, "Dune Part Three", "2024-01-01"))


class HelperTests(unittest.TestCase):
    def test_parse_year(self):
        self.assertEqual(mb.parse_year("2018-03-02"), 2018)
        self.assertEqual(mb.parse_year(2018), 2018)
        self.assertIsNone(mb.parse_year("soon"))
        self.assertIsNone(mb.parse_year(None))

    def test_placeholders_are_empty(self):
        for value in (None, "", "[]", "{}", "N/A", "unknown", 0):
            with self.subTest(value=value):
                self.assertTrue(mb.is_empty_value(value))
        self.assertFalse(mb.is_empty_value("7.5"))
        self.assertFalse(mb.is_empty_value("Timothee Chalamet"))

    def test_split_and_join_names(self):
        self.assertEqual(mb.split_names("A, B / C"), ["A", "B", "C"])
        self.assertEqual(mb.split_names("[]"), [])
        self.assertEqual(mb.split_names('["A", "B"]'), ["A", "B"])
        self.assertEqual(mb.join_names(["A", "B", "C"], limit=2), "A, B")
        self.assertIsNone(mb.join_names([]))

    def test_genres_round_trip(self):
        self.assertEqual(mb.parse_genres('["A", "B"]'), ["A", "B"])
        self.assertEqual(mb.format_genres(["A", "B"]), '["A", "B"]')
        self.assertEqual(mb.parse_genres(None), [])

    def test_sql_literal_escapes_quotes(self):
        self.assertEqual(mb.sql_literal("o'clock"), "'o''clock'")
        self.assertEqual(mb.sql_literal(2024), "2024")
        self.assertEqual(mb.sql_literal(None), "NULL")

    def test_clean_text(self):
        self.assertEqual(mb.clean_text("  a   b  "), "a b")
        self.assertIsNone(mb.clean_text("N/A"))
        self.assertIsNone(mb.clean_text("Unknown"))


class MetadataTests(unittest.TestCase):
    def test_merge_keeps_first_source(self):
        first = mb.Metadata()
        first.set("rating", 8.6, "omdb")
        second = mb.Metadata()
        second.set("rating", 7.0, "tmdb")
        second.set("synopsis", "A film.", "tmdb")
        first.merge(second)
        self.assertEqual(first.get("rating"), 8.6)
        self.assertEqual(first.sources["rating"], "omdb")
        self.assertEqual(first.get("synopsis"), "A film.")

    def test_to_post_updates_shapes(self):
        meta = mb.Metadata()
        meta.set("rating", 86, "anilist")  # 0–100 scale
        meta.set("year", "2024-03-01", "tmdb")
        meta.set("cast", ["A", "B"], "tmdb")
        meta.set("director", "D", "tmdb")
        meta.set("genres", ["Sci-Fi"], "tmdb")
        meta.set("poster", "https://img/poster.jpg", "tmdb")
        meta.set("backdrop", ["https://img/bg.jpg"], "tmdb")
        meta.set("tmdb_id", 693134, "tmdb")
        columns = set(mb.FIELD_COLUMN.values())
        updates = meta.to_post_updates(columns)
        self.assertEqual(updates["imdb_rating"], "8.6")
        self.assertEqual(updates["year"], 2024)
        self.assertEqual(updates["cast_names"], "A, B")
        self.assertEqual(updates["genres"], '["Sci-Fi"]')
        self.assertEqual(updates["poster_url"], "https://img/poster.jpg")
        self.assertEqual(updates["backdrop_urls"], '["https://img/bg.jpg"]')
        self.assertEqual(updates["tmdb_id"], "693134")

    def test_poster_must_be_http(self):
        meta = mb.Metadata()
        meta.set("poster", "N/A", "omdb")
        self.assertNotIn("poster_url",
                         meta.to_post_updates(set(mb.FIELD_COLUMN.values())))


class PaceAndCacheTests(unittest.TestCase):
    def test_pace_enforces_interval(self):
        pace = mb.Pace(0.05)
        started = time.monotonic()
        pace.wait()
        pace.wait()
        self.assertGreaterEqual(time.monotonic() - started, 0.05)

    def test_cache_round_trip_and_expiry(self):
        cache = mb.ResponseCache(None, ttl=60)
        cache.put("tmdb:q:dune", {"id": 1})
        self.assertEqual(cache.get("tmdb:q:dune"), {"id": 1})
        stale = mb.ResponseCache(None, ttl=0)
        stale.put("tmdb:q:dune", {"id": 1})
        self.assertIsNone(stale.get("tmdb:q:dune"))

    def test_cache_flush_and_reload(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cache.json"
            cache = mb.ResponseCache(path)
            cache.put("k", {"v": 1})
            cache.flush()
            self.assertTrue(path.exists())
            self.assertEqual(mb.ResponseCache(path).get("k"), {"v": 1})


class ImdbDatasetsTests(unittest.TestCase):
    def _write_fixture(self, directory: Path):
        with gzip.open(directory / "title.ratings.tsv.gz", "wt", encoding="utf-8") as fh:
            fh.write("tconst\taverageRating\tnumVotes\n"
                     "tt0000001\t7.2\t1234\ntt0000002\t6.1\t10\n")
        with gzip.open(directory / "title.basics.tsv.gz", "wt", encoding="utf-8") as fh:
            fh.write("tconst\ttitleType\tprimaryTitle\toriginalTitle\tisAdult\t"
                     "startYear\tendYear\truntimeMinutes\tgenres\n"
                     "tt0000001\tmovie\tSolo Film\tSolo Film\t0\t2020\t\\N\t120\tDrama,Action\n")
        with gzip.open(directory / "title.crew.tsv.gz", "wt", encoding="utf-8") as fh:
            fh.write("tconst\tdirectors\twriters\ntt0000001\tnm0000001,nm0000002\t\\N\n")

    def test_ratings_basics_crew(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            self._write_fixture(directory)
            data = mb.ImdbDatasets(directory)
            self.assertEqual(data.load_ratings(), 2)
            self.assertEqual(data.load_basics(), 1)
            self.assertEqual(data.load_crew(), 1)
            meta = data.metadata_for("tt0000001")
            self.assertEqual(meta.get("rating"), "7.2")
            self.assertEqual(meta.get("year"), 2020)
            self.assertEqual(meta.get("genres"), ["Drama", "Action"])
            self.assertEqual(meta.get("director"),
                             ["nm0000001", "nm0000002"])
            self.assertTrue(data.metadata_for("tt9999999").is_empty())


class SitePassTests(unittest.TestCase):
    def test_sibling_fill_and_adult_skip(self):
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", fixture_rows())
            columns = mb.post_columns(conn)
            plan = mb.Plan()
            stats = mb.plan_site_pass(conn, columns, list(mb.FIELDS), plan)
            self.assertGreater(stats["filled"], 0)
            filled = plan.updates.get(2, {})
            self.assertEqual(filled["rating"][0], "8.6")
            self.assertEqual(filled["director"][0], "Denis Villeneuve")
            self.assertEqual(filled["cast"][0], "Timothee Chalamet, Zendaya")
            self.assertEqual(filled["synopsis"][0], "Paul unites the Fremen.")
            self.assertEqual(filled["poster"][0], "http://x/poster.jpg")
            self.assertEqual(filled["rating"][1], "site")
            self.assertNotIn(5, plan.updates)   # adult row: never filled
            self.assertNotIn(6, plan.updates)   # keyless row: no siblings
            self.assertNotIn(1, plan.updates)   # donor itself: nothing missing
            conn.close()

    def test_propagation_from_planned_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", fixture_rows())
            columns = mb.post_columns(conn)
            plan = mb.Plan()
            mb.plan_site_pass(conn, columns, list(mb.FIELDS), plan)
            # An API win lands on post 1 only; the propagation run must
            # carry it to sibling post 2.
            plan.add(1, "backdrop", '["https://img/bg.jpg"]', "tmdb")
            mb.plan_site_pass(conn, columns, list(mb.FIELDS), plan,
                              include_plan_donors=True)
            self.assertEqual(plan.updates[2]["backdrop"][0],
                             '["https://img/bg.jpg"]')
            conn.close()


class DatasetsPassTests(unittest.TestCase):
    def test_fills_missing_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            with gzip.open(directory / "title.ratings.tsv.gz", "wt",
                           encoding="utf-8") as fh:
                fh.write("tconst\taverageRating\tnumVotes\n"
                         "tt0000001\t7.2\t1234\ntt0000002\t9.9\t10\n")
            conn = make_db(Path(tmp) / "t.db", fixture_rows())
            columns = mb.post_columns(conn)
            plan = mb.Plan()
            data = mb.ImdbDatasets(directory)
            data.load_ratings()
            stats = mb.plan_datasets_pass(conn, columns, list(mb.FIELDS),
                                          data, plan)
            self.assertEqual(plan.updates[3]["rating"][0], "7.2")
            self.assertEqual(plan.updates[3]["rating"][1], "imdb-datasets")
            # Post 4 already has 6.1: the datasets' 9.9 must NOT overwrite it.
            self.assertNotIn(4, plan.updates)
            self.assertEqual(stats["posts"], 1)
            conn.close()


class ApiPassTests(unittest.TestCase):
    def _sources(self):
        cache = mb.ResponseCache(None)
        return {"tmdb": FakeTmdb(cache), "omdb": FailingSource(cache)}

    def test_api_fills_representative_and_propagates(self):
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", fixture_rows())
            columns = mb.post_columns(conn)
            plan = mb.Plan()
            stats = mb.plan_api_pass(conn, columns, list(mb.FIELDS),
                                     self._sources(), plan, workers=2)
            self.assertGreater(stats["filled"], 0)
            self.assertEqual(plan.updates[2]["rating"][0], "8.6")
            self.assertEqual(plan.updates[2]["rating"][1], "tmdb")
            self.assertEqual(plan.updates[2]["synopsis"][0],
                             "Paul unites the Fremen.")
            # The failing omdb source is counted, not fatal.
            self.assertGreaterEqual(stats["failed"], 1)
            conn.close()

    def test_full_run_pipeline(self):
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", fixture_rows())
            cache = mb.ResponseCache(None)
            plan, stats = mb.run(conn, wanted=list(mb.FIELDS),
                                 sources=self._sources(), datasets=None,
                                 cache=cache, workers=2)
            # Post 2: filled by the site pass (donor post 1) AND the API
            # pass on the representative; the site value wins the tie.
            self.assertEqual(plan.updates[2]["rating"][1], "site")
            self.assertIn("rating", stats["works_improved"])
            conn.close()

    def test_fallback_to_next_candidate_when_first_title_fails(self):
        """The emptiest post's title may be unsearchable; the work's next
        candidate must then get a try instead of a no-match."""
        rows = [
            (10, "Unmatchable Title XYZ", 2024, "movie", None, None, None,
             None, None, None, None, None, "fallback-2024", 0, 0),
            (11, "Dune Part Two Hindi Dubbed", 2024, "movie", None, None, None,
             None, None, None, None, None, "fallback-2024", 0, 0),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", rows)
            columns = mb.post_columns(conn)
            plan = mb.Plan()
            stats = mb.plan_api_pass(conn, columns, list(mb.FIELDS),
                                     self._sources(), plan, workers=1)
            self.assertEqual(plan.updates[11]["rating"][0], "8.6")
            self.assertNotIn(10, plan.updates)
            self.assertEqual(stats["filled"], 3)  # rating + synopsis + cast
            conn.close()


class TmdbClientTests(unittest.TestCase):
    DETAIL = {
        "id": 693134, "vote_average": 8.6, "overview": "Paul unites.",
        "poster_path": "/p.jpg", "backdrop_path": "/b.jpg",
        "release_date": "2024-03-01",
        "genres": [{"name": "Sci-Fi"}],
        "credits": {
            "cast": [{"name": f"Actor {i}"} for i in range(10)],
            "crew": [{"name": "Denis Villeneuve", "job": "Director"}],
        },
        "external_ids": {"imdb_id": "tt15239678"},
        "images": {"backdrops": [{"file_path": "/b2.jpg"}]},
    }

    def _source(self, routes):
        cache = mb.ResponseCache(None)
        source = mb.TmdbSource(cache)
        source.http = FakeHttp(routes)
        return source

    def test_search_then_detail(self):
        source = self._source({
            "/search/movie": {"results": [
                {"id": 693134, "title": "Dune: Part Two",
                 "release_date": "2024-03-01"}]},
            "/movie/693134": self.DETAIL,
        })
        meta = source.lookup({"title": "Dune Part Two", "year": 2024,
                              "content_type": "movie", "seasons": 0})
        self.assertIsNotNone(meta)
        self.assertEqual(meta.get("rating"), 8.6)
        self.assertEqual(meta.get("director"), ["Denis Villeneuve"])
        self.assertEqual(len(meta.get("cast")), 8)  # top 8 only
        self.assertEqual(meta.get("imdb_id"), "tt15239678")
        self.assertEqual(meta.get("tmdb_id"), 693134)

    def test_wrong_year_is_rejected(self):
        source = self._source({
            "/search/movie": {"results": [
                {"id": 693134, "title": "Dune: Part Two",
                 "release_date": "2024-03-01"}]},
        })
        meta = source.lookup({"title": "Dune Part Two", "year": 2019,
                              "content_type": "movie", "seasons": 0})
        self.assertIsNone(meta)
        self.assertEqual(len(source.http.calls), 1)  # detail never called

    def test_find_by_imdb_id(self):
        source = self._source({
            "/find/tt15239678": {"movie_results": [
                {"id": 693134, "title": "Dune: Part Two",
                 "release_date": "2024-03-01"}], "tv_results": []},
            "/movie/693134": self.DETAIL,
        })
        meta = source.lookup({"title": "Dune: Part Two", "year": 2024,
                              "content_type": "movie", "seasons": 0,
                              "imdb_id": "tt15239678"})
        self.assertIsNotNone(meta)
        self.assertEqual(meta.get("rating"), 8.6)


class CinemetaClientTests(unittest.TestCase):
    def test_meta_by_imdb_id(self):
        cache = mb.ResponseCache(None)
        source = mb.CinemetaSource(cache)
        source.http = FakeHttp({
            "/meta/movie/tt15239678": {"meta": {
                "id": "tt15239678", "name": "Dune: Part Two", "year": "2024",
                "poster": "https://img/p.jpg", "background": "https://img/b.jpg",
                "description": "Paul unites.", "director": ["Denis Villeneuve"],
                "cast": ["Timothee Chalamet"], "imdbRating": "8.6",
                "genres": ["Sci-Fi"]}},
        })
        meta = source.lookup({"imdb_id": "tt15239678", "content_type": "movie",
                              "seasons": 0})
        self.assertIsNotNone(meta)
        self.assertEqual(meta.get("rating"), "8.6")
        self.assertEqual(meta.get("director"), ["Denis Villeneuve"])
        self.assertEqual(meta.get("year"), "2024")

    def test_no_imdb_id_means_no_lookup(self):
        cache = mb.ResponseCache(None)
        source = mb.CinemetaSource(cache)
        source.http = FakeHttp({})
        self.assertIsNone(source.lookup({"title": "Dune", "content_type": "movie"}))
        self.assertEqual(source.http.calls, [])


class SimklClientTests(unittest.TestCase):
    """Simkl — the Phase-2 fallback after TMDb (probe 2026-11-20: 86/100
    on no-rating works, vs TMDb 26/68 completed calls). Payloads are the
    real summary shapes (The Matrix /movies, Breaking Bad /tv)."""

    MATRIX = {
        "title": "The Matrix", "year": 1999, "runtime": 136,
        "overview": "Set in the 22nd century, a hacker joins the rebellion.",
        "genres": ["Action", "Science Fiction"],
        "ratings": {"simkl": {"rating": 8.6, "votes": 13222},
                    "imdb": {"rating": 8.7, "votes": 2295151}},
        "poster": "80/8008234e702d8ed33",
        "fanart": "12/12560269d5c490d440",
        "ids": {"simkl": 53992, "tmdb": "603", "imdb": "tt0133093"},
    }

    def _source(self, routes):
        cache = mb.ResponseCache(None)
        source = mb.SimklSource(cache)
        source.http = FakeHttp(routes)
        return source

    def test_movie_by_imdb_id(self):
        with patch.dict("os.environ", {"SIMKL_CLIENT_ID": "test-key"}):
            source = self._source({"api.simkl.com": self.MATRIX})
            meta = source.lookup({"imdb_id": "tt0133093",
                                  "content_type": "movie", "seasons": 0})
        self.assertIsNotNone(meta)
        self.assertEqual(meta.get("rating"), 8.7)  # the IMDb rating wins
        self.assertEqual(meta.get("synopsis"), self.MATRIX["overview"])
        self.assertEqual(meta.get("poster"),
                         "https://simkl.in/posters/80/8008234e702d8ed33_m.jpg")
        self.assertEqual(meta.get("backdrop"),
                         ["https://simkl.in/fanart/12/12560269d5c490d440_medium.jpg"])
        self.assertEqual(meta.get("year"), 1999)
        self.assertEqual(meta.get("genres"), ["Action", "Science Fiction"])
        self.assertEqual(meta.get("tmdb_id"), "603")
        self.assertIsNone(meta.get("cast"))  # Simkl summaries carry no cast
        self.assertTrue(source.http.calls[0].startswith(
            "https://api.simkl.com/movies/tt0133093?"))

    def test_series_uses_tv_endpoint(self):
        with patch.dict("os.environ", {"SIMKL_CLIENT_ID": "test-key"}):
            source = self._source({"api.simkl.com": {
                "title": "Breaking Bad", "year": 2008,
                "ratings": {"imdb": {"rating": 9.5, "votes": 1}},
                "poster": "97/978264e8bbc2303",
                "ids": {"tmdb": "1396", "imdb": "tt0903747"}}})
            meta = source.lookup({"imdb_id": "tt0903747",
                                  "content_type": "series", "seasons": 5})
        self.assertIsNotNone(meta)
        self.assertEqual(meta.get("rating"), 9.5)
        self.assertTrue(source.http.calls[0].startswith(
            "https://api.simkl.com/tv/tt0903747?"))

    def test_list_response_is_the_first_match(self):
        with patch.dict("os.environ", {"SIMKL_CLIENT_ID": "test-key"}):
            source = self._source({"api.simkl.com": [self.MATRIX]})
            meta = source.lookup({"imdb_id": "tt0133093",
                                  "content_type": "movie", "seasons": 0})
        self.assertIsNotNone(meta)
        self.assertEqual(meta.get("rating"), 8.7)

    def test_simkl_only_rating_is_marked_simkl(self):
        with patch.dict("os.environ", {"SIMKL_CLIENT_ID": "test-key"}):
            source = self._source({"api.simkl.com": {
                "title": "Obscure", "year": 2019,
                "ratings": {"simkl": {"rating": 6.4, "votes": 12}}}})
            meta = source.lookup({"imdb_id": "tt9999999",
                                  "content_type": "movie", "seasons": 0})
        self.assertIsNotNone(meta)
        self.assertEqual(meta.get("rating"), 6.4)
        self.assertEqual(meta.sources.get("rating"), "simkl")

    def test_no_imdb_id_means_no_lookup(self):
        source = self._source({"api.simkl.com": self.MATRIX})
        self.assertIsNone(source.lookup({"title": "The Matrix",
                                         "content_type": "movie"}))
        self.assertEqual(source.http.calls, [])

    def test_unknown_imdb_id_raises_like_every_source(self):
        with patch.dict("os.environ", {"SIMKL_CLIENT_ID": "test-key"}):
            source = self._source({})
            with self.assertRaises(mb.UpstreamError):
                source.lookup({"imdb_id": "tt0000000",
                               "content_type": "movie", "seasons": 0})

    def test_disabled_without_a_key(self):
        cache = mb.ResponseCache(None)
        source = mb.SimklSource(cache)
        with patch.dict("os.environ", {}, clear=False):
            if "SIMKL_CLIENT_ID" in os.environ:
                del os.environ["SIMKL_CLIENT_ID"]
            self.assertFalse(source.enabled)

    def test_wiring_after_tmdb(self):
        names = [cls.NAME for cls in mb.SOURCE_CLASSES]
        self.assertIn("simkl", names)
        for field in ("rating", "synopsis", "poster", "backdrop",
                      "year", "genres", "tmdb_id"):
            chain = mb.FIELD_SOURCES[field]
            self.assertIn("simkl", chain)
            self.assertEqual(chain[chain.index("tmdb") + 1], "simkl",
                             "%s: simkl must sit right after tmdb" % field)
        self.assertNotIn("simkl", mb.FIELD_SOURCES["cast"])  # TMDb-only


class AnilistGuardTests(unittest.TestCase):
    def test_year_mismatch_rejected(self):
        cache = mb.ResponseCache(None)
        source = mb.AnilistSource(cache)
        source.http = FakeHttp({
            "graphql.anilist.co": {"data": {"Media": {
                "title": {"romaji": "Anime Title", "english": None, "native": None},
                "description": "A show.", "averageScore": 82,
                "coverImage": {"large": "https://img/c.jpg"},
                "genres": ["Action"], "startDate": {"year": 1999},
                "characters": {"edges": []}}}},
        })
        meta = source.lookup({"title": "Anime Title", "year": 2023,
                              "content_type": "series", "seasons": 1})
        self.assertIsNone(meta)


class StagingWriterTests(unittest.TestCase):
    def test_sql_is_deterministic_and_commented(self):
        plan = mb.Plan()
        plan.add(2, "rating", "8.6", "site")
        plan.add(2, "director", "Denis Villeneuve", "site")
        plan.add(3, "rating", "7.2", "imdb-datasets")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "out.sql"
            wrote = mb.write_staging_sql(
                path, plan, {"generated_at": "2026-11-18T00:00:00Z",
                             "db": "fixture.db"})
            self.assertEqual(wrote, 2)
            text = path.read_text()
            self.assertIn("UPDATE posts SET director = 'Denis Villeneuve', "
                          "imdb_rating = '8.6' WHERE id = 2; -- director: site; "
                          "rating: site", text)
            self.assertIn("imdb_rating = '7.2' WHERE id = 3; -- rating: imdb-datasets",
                          text)
            self.assertLess(text.index("id = 2"), text.index("id = 3"))

    def test_apply_to_copy_and_guards(self):
        with tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "fixture.db"
            conn = make_db(db_path, fixture_rows())
            conn.close()
            before = hashlib.sha256(db_path.read_bytes()).hexdigest()
            plan = mb.Plan()
            plan.add(2, "rating", "8.6", "site")
            sql_path = Path(tmp) / "out.sql"
            mb.write_staging_sql(sql_path, plan, {"generated_at": "x", "db": "y"})
            copy_path = Path(tmp) / "copy.db"
            result = mb.apply_to_copy(db_path, sql_path, copy_path)
            self.assertEqual(result["statements"], 1)
            self.assertEqual(result["integrity"], "ok")
            copy = sqlite3.connect(copy_path)
            self.assertEqual(copy.execute(
                "SELECT imdb_rating FROM posts WHERE id=2").fetchone()[0], "8.6")
            copy.close()
            self.assertEqual(hashlib.sha256(db_path.read_bytes()).hexdigest(),
                             before)  # input untouched
            with self.assertRaises(ValueError):
                mb.apply_to_copy(db_path, sql_path, db_path)  # out == src
            with self.assertRaises(ValueError):
                mb.apply_to_copy(db_path, sql_path, Path(mb.LIVE_CATALOG))
            with self.assertRaises(ValueError):
                mb.apply_to_copy(db_path, sql_path, copy_path)  # exists

    def test_readonly_connection_refuses_writes(self):
        with tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "fixture.db"
            conn = make_db(db_path, fixture_rows())
            conn.close()
            ro = mb.connect_readonly(db_path)
            with self.assertRaises(sqlite3.OperationalError):
                ro.execute("UPDATE posts SET imdb_rating='1.0' WHERE id=1")
            ro.close()
            with self.assertRaises(ValueError):
                mb.connect_readonly(Path(tmp) / "missing.db")


class CliTests(unittest.TestCase):
    def test_dry_run_writes_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "fixture.db"
            conn = make_db(db_path, fixture_rows())
            conn.close()
            out = Path(tmp) / "out.sql"
            code = mb.main(["--db", str(db_path), "--sources", "fanart",
                            "--cache-dir", str(Path(tmp) / "cache"),
                            "--out", str(out), "--dry-run"])
            self.assertEqual(code, 0)
            self.assertFalse(out.exists())

    def test_unknown_field_is_a_usage_error(self):
        with self.assertRaises(SystemExit):
            mb.main(["--only", "nope"])

    def test_missing_db_is_exit_2(self):
        with tempfile.TemporaryDirectory() as tmp:
            code = mb.main(["--db", str(Path(tmp) / "nope.db"),
                            "--cache-dir", str(Path(tmp) / "cache")])
            self.assertEqual(code, 2)


class LanguageDisambiguationTests(unittest.TestCase):
    """The Marco fix: same title + same year must not take the first match.
    Language/industry first, then cast/director, then refuse (never guess)."""

    MARCO_EN = {"title": "Marco", "year": 2022, "language": "en",
                "country": "US", "cast": [], "director": []}
    MARCO_ML = {"title": "Marco", "year": 2022, "language": "ml",
                "country": "IN", "cast": ["Unni Mukundan"], "director": []}

    def test_language_and_country_codes(self):
        self.assertEqual(mb.language_codes("Malayalam"), {"ml"})
        self.assertEqual(mb.language_codes("Malayalam, English"), {"ml", "en"})
        self.assertEqual(mb.language_codes("ml"), {"ml"})
        self.assertEqual(mb.language_codes("Unknown"), set())
        self.assertEqual(mb.language_codes(None), set())
        self.assertEqual(mb.country_codes("India"), {"IN"})
        self.assertEqual(mb.country_codes("USA"), {"US"})
        self.assertEqual(mb.country_codes("UK"), {"GB"})
        self.assertEqual(mb.country_codes("Unknown"), set())

    def test_single_match_is_chosen(self):
        chosen, ambiguous, tied = mb.choose_candidate(
            {"title": "Marco", "year": 2022}, [self.MARCO_ML])
        self.assertIs(chosen, self.MARCO_ML)
        self.assertFalse(ambiguous)
        self.assertEqual(tied, [])

    def test_language_wins_for_marco(self):
        query = {"title": "Marco", "year": 2022,
                 "language": "Malayalam", "country": "India"}
        chosen, ambiguous, _ = mb.choose_candidate(
            query, [self.MARCO_EN, self.MARCO_ML])
        self.assertIs(chosen, self.MARCO_ML)
        self.assertFalse(ambiguous)

    def test_tie_with_no_language_info_is_ambiguous(self):
        chosen, ambiguous, tied = mb.choose_candidate(
            {"title": "Marco", "year": 2022},
            [self.MARCO_EN, self.MARCO_ML])
        self.assertIsNone(chosen)
        self.assertTrue(ambiguous)
        self.assertEqual(len(tied), 2)

    def test_cast_overlap_breaks_a_language_tie(self):
        query = {"title": "Marco", "year": 2022,
                 "cast": ["Unni Mukundan"], "director": []}
        chosen, ambiguous, _ = mb.choose_candidate(
            query, [self.MARCO_EN, self.MARCO_ML])
        self.assertIs(chosen, self.MARCO_ML)
        self.assertFalse(ambiguous)

    def test_tie_after_everything_is_ambiguous(self):
        query = {"title": "Marco", "year": 2022,
                 "cast": ["Somebody Else"], "director": []}
        chosen, ambiguous, tied = mb.choose_candidate(
            query, [self.MARCO_EN, self.MARCO_ML])
        self.assertIsNone(chosen)
        self.assertTrue(ambiguous)
        self.assertEqual(len(tied), 2)

    def test_year_guard_still_applies(self):
        query = {"title": "Marco", "year": 2022}
        chosen, ambiguous, _ = mb.choose_candidate(
            query, [dict(self.MARCO_EN, year=2019),
                    dict(self.MARCO_ML, year=2019)])
        self.assertIsNone(chosen)
        self.assertFalse(ambiguous)

    def test_language_veto(self):
        # A Malayalam post vs an English-only payload: refuse.
        self.assertTrue(mb.language_veto({"language": "Malayalam"}, "English"))
        # The same post vs a Malayalam payload: accept.
        self.assertFalse(mb.language_veto({"language": "Malayalam"}, "Malayalam"))
        # The post's bare 'English' never vetoes — a migration default.
        self.assertFalse(mb.language_veto({"language": "English"}, "Malayalam"))
        # No language on the post: no evidence, no veto.
        self.assertFalse(mb.language_veto({}, "Malayalam"))
        # Country: an Indian post vs a USA payload: refuse.
        self.assertTrue(mb.language_veto({"country": "India"}, "English", "USA"))
        # ... and a bare 'USA' never vetoes.
        self.assertFalse(mb.language_veto({"country": "USA"}, "English", "India"))


class TmdbDisambiguationTests(unittest.TestCase):
    """TMDb search results go through choose_candidate: the Malayalam
    Marco wins for a Malayalam post, a tie is refused, and an IMDb id never
    searches at all."""

    def _detail(self, tmdb_id, cast):
        return {
            "id": tmdb_id, "vote_average": 7.0, "overview": "A film.",
            "poster_path": "/p.jpg", "backdrop_path": "/b.jpg",
            "release_date": "2022-01-01", "genres": [{"name": "Action"}],
            "credits": {"cast": [{"name": name} for name in cast],
                        "crew": [{"name": "D", "job": "Director"}]},
            "external_ids": {"imdb_id": None},
            "images": {"backdrops": []},
        }

    def _search_routes(self):
        return {
            "/search/movie": {"results": [
                {"id": 1, "title": "Marco", "release_date": "2022-01-01",
                 "original_language": "en", "origin_country": ["US"]},
                {"id": 2, "title": "Marco", "release_date": "2022-12-25",
                 "original_language": "ml", "origin_country": ["IN"]},
            ]},
            "/movie/1": self._detail(1, ["Tom Cruise"]),
            "/movie/2": self._detail(2, ["Unni Mukundan"]),
        }

    def _source(self, routes):
        cache = mb.ResponseCache(None)
        source = mb.TmdbSource(cache)
        source.http = FakeHttp(routes)
        return source

    def _query(self, **extra):
        query = {"title": "Marco", "year": 2022,
                 "content_type": "movie", "seasons": 0}
        query.update(extra)
        return query

    def test_malayalam_query_uses_the_ml_detail_only(self):
        source = self._source(self._search_routes())
        meta = source.lookup(self._query(language="Malayalam", country="India"))
        self.assertIsNotNone(meta)
        self.assertEqual(meta.get("cast"), ["Unni Mukundan"])
        # The English detail was never fetched: language decided it.
        self.assertFalse(any("/movie/1" in url for url in source.http.calls))
        self.assertEqual(source.ambiguities, [])

    def test_cast_breaks_the_tie_and_reuses_the_cached_detail(self):
        source = self._source(self._search_routes())
        meta = source.lookup(self._query(cast=["Unni Mukundan"], director=[]))
        self.assertIsNotNone(meta)
        self.assertEqual(meta.get("cast"), ["Unni Mukundan"])
        # Both details were fetched to break the tie; the winner's detail
        # was then reused from the cache, not fetched twice.
        self.assertEqual(sum(1 for url in source.http.calls
                             if "/movie/1" in url), 1)
        self.assertEqual(sum(1 for url in source.http.calls
                             if "/movie/2" in url), 1)

    def test_no_language_no_cast_is_ambiguous_and_fetches_no_detail(self):
        source = self._source(self._search_routes())
        meta = source.lookup(self._query())
        self.assertIsNone(meta)
        self.assertEqual(len(source.ambiguities), 1)
        self.assertEqual(source.ambiguities[0]["reason"],
                         "tie-after-language-and-cast")
        self.assertEqual(len(source.ambiguities[0]["candidates"]), 2)
        self.assertFalse(any("/movie/" in url for url in source.http.calls))

    def test_imdb_id_bypasses_disambiguation(self):
        source = self._source({
            "/find/tt1234567": {"movie_results": [
                {"id": 2, "title": "Marco", "release_date": "2022-12-25"}],
                "tv_results": []},
            "/movie/2": self._detail(2, ["Unni Mukundan"]),
        })
        meta = source.lookup(self._query(imdb_id="tt1234567"))
        self.assertIsNotNone(meta)
        self.assertEqual(meta.get("cast"), ["Unni Mukundan"])
        self.assertFalse(any("/search/movie" in url for url in source.http.calls))
        self.assertEqual(source.ambiguities, [])


class OmdbLanguageVetoTests(unittest.TestCase):
    def _source(self, payload):
        cache = mb.ResponseCache(None)
        source = mb.OmdbSource(cache)
        source.http = FakeHttp({"omdbapi.com": payload})
        return source

    def _payload(self, language="Malayalam", country="India"):
        return {"Response": "True", "Title": "Marco", "Year": "2022",
                "Language": language, "Country": country,
                "imdbRating": "8.0", "Plot": "A film.",
                "Poster": "N/A", "Actors": "Unni Mukundan",
                "Director": "Haneef Adeni"}

    def test_contradicting_language_is_rejected(self):
        source = self._source(self._payload(language="English", country="USA"))
        meta = source.lookup({"title": "Marco", "year": 2022,
                              "language": "Malayalam", "country": "India"})
        self.assertIsNone(meta)
        self.assertEqual(len(source.ambiguities), 1)
        self.assertEqual(source.ambiguities[0]["reason"],
                         "language-contradiction")

    def test_matching_language_is_accepted(self):
        source = self._source(self._payload())
        meta = source.lookup({"title": "Marco", "year": 2022,
                              "language": "Malayalam", "country": "India"})
        self.assertIsNotNone(meta)
        self.assertEqual(meta.get("rating"), "8.0")
        self.assertEqual(source.ambiguities, [])

    def test_english_post_language_never_vetoes(self):
        # The post's 'English' is a migration default, not evidence: a
        # Malayalam payload for an English-labelled post is accepted.
        source = self._source(self._payload())
        meta = source.lookup({"title": "Marco", "year": 2022,
                              "language": "English", "country": "USA"})
        self.assertIsNotNone(meta)


class BatchWriterTests(unittest.TestCase):
    def _plan_with(self, count, *, source="site"):
        plan = mb.Plan()
        for post_id in range(1, count + 1):
            plan.add(post_id, "rating", "7.5", source)
        return plan

    def _writer(self, tmp, *, batch_size=100):
        sql_path = Path(tmp) / "out.sql"
        progress_path = Path(tmp) / "out.progress.json"
        writer = mb.BatchWriter(
            sql_path, progress_path,
            {"generated_at": "2026-11-19T00:00:00Z", "db": "fixture.db"},
            batch_size=batch_size)
        writer.open()
        return writer, sql_path, progress_path

    def _updates(self, sql_path):
        return [line for line in sql_path.read_text().splitlines()
                if line.startswith("UPDATE posts SET")]

    def test_flush_writes_exact_batches(self):
        with tempfile.TemporaryDirectory() as tmp:
            writer, sql_path, progress_path = self._writer(tmp, batch_size=100)
            plan = self._plan_with(250)
            self.assertEqual(writer.flush(plan), 200)
            self.assertEqual(len(self._updates(sql_path)), 200)
            progress = json.loads(progress_path.read_text())
            self.assertEqual(progress["posts_updated"], 200)
            self.assertEqual(progress["fields_filled"], 200)
            self.assertEqual(progress["by_field"], {"rating": 200})
            self.assertFalse(progress["complete"])
            self.assertEqual(writer.finish(plan), 250)
            self.assertEqual(len(self._updates(sql_path)), 250)
            self.assertTrue(sql_path.read_text().rstrip().endswith("COMMIT;"))
            progress = json.loads(progress_path.read_text())
            self.assertTrue(progress["complete"])
            self.assertEqual(progress["posts_updated"], 250)
            self.assertIsNotNone(progress["finished_at"])

    def test_below_batch_size_flush_waits(self):
        with tempfile.TemporaryDirectory() as tmp:
            writer, sql_path, _ = self._writer(tmp, batch_size=100)
            plan = self._plan_with(50)
            self.assertEqual(writer.flush(plan), 0)
            self.assertEqual(self._updates(sql_path), [])
            self.assertEqual(writer.finish(plan), 50)
            self.assertEqual(len(self._updates(sql_path)), 50)

    def test_resume_loads_flushed_and_adds_only_new(self):
        with tempfile.TemporaryDirectory() as tmp:
            writer, sql_path, progress_path = self._writer(tmp, batch_size=100)
            plan = self._plan_with(150)
            writer.flush(plan)
            self.assertEqual(writer.finish(plan), 150)
            self.assertEqual(len(self._updates(sql_path)), 150)
            resumed = mb.BatchWriter(
                sql_path, progress_path,
                {"generated_at": "2026-11-19T01:00:00Z", "db": "fixture.db"},
                batch_size=100)
            resumed.open(resume=True)
            self.assertEqual(resumed.resumed_posts, 150)
            self.assertEqual(resumed.resumed_fields, 150)
            plan = mb.Plan()
            for post_id in range(151, 161):
                plan.add(post_id, "rating", "7.5", "site")
            self.assertEqual(resumed.finish(plan), 160)
            updates = self._updates(sql_path)
            self.assertEqual(len(updates), 160)
            ids = [int(line.split("WHERE id = ")[1].split(";")[0])
                   for line in updates]
            self.assertEqual(len(set(ids)), 160)  # no post written twice
            progress = json.loads(progress_path.read_text())
            self.assertEqual(progress["resumed_posts"], 150)
            self.assertEqual(progress["posts_updated"], 160)

    def test_file_without_commit_is_an_interrupted_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            writer, sql_path, _ = self._writer(tmp, batch_size=100)
            plan = self._plan_with(120)
            writer.flush(plan)
            flushed, complete = mb._parse_resume_sql(sql_path)
            self.assertEqual(len(flushed), 100)
            self.assertFalse(complete)  # killed mid-run: resume, never apply
            writer.finish(plan)
            flushed, complete = mb._parse_resume_sql(sql_path)
            self.assertEqual(len(flushed), 120)
            self.assertTrue(complete)

    def test_seeded_plan_never_refills(self):
        plan = mb.Plan()
        plan.seed(2, {"rating": "site"})
        self.assertTrue(plan.has(2, "rating"))
        self.assertFalse(plan.add(2, "rating", "9.9", "tmdb"))
        self.assertEqual(plan.count(), 0)
        self.assertEqual(plan.by_source(), {})
        self.assertEqual(plan.seeded_posts(), 1)
        self.assertEqual(plan.seeded_fields(), 1)


class ReportTests(unittest.TestCase):
    def test_report_carries_resumed_and_ambiguous(self):
        plan = mb.Plan()
        plan.add(2, "rating", "8.6", "site")
        stats = {"wanted": ["rating"], "posts_updated": 1, "fields_filled": 1,
                 "by_field": {"rating": {"site": 1}}, "works_improved": {},
                 "passes": {}, "sources": {},
                 "resumed_posts": 150, "resumed_fields": 150,
                 "ambiguous": [{"post_id": 9, "title": "Marco", "year": 2022,
                                "source": "tmdb",
                                "reason": "tie-after-language-and-cast",
                                "candidates": []}],
                 "ambiguous_count": 1}
        report = mb.build_report(Path("fixture.db"), plan, stats, {})
        self.assertEqual(report["resumed_posts"], 150)
        self.assertEqual(report["resumed_fields"], 150)
        self.assertEqual(len(report["ambiguous_review"]), 1)
        self.assertEqual(report["ambiguous_review"][0]["title"], "Marco")


class ResumeIntegrationTests(unittest.TestCase):
    def test_cli_run_twice_with_resume_adds_no_new_updates(self):
        with tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "fixture.db"
            conn = make_db(db_path, fixture_rows())
            conn.close()
            out = Path(tmp) / "out.sql"
            cache_dir = str(Path(tmp) / "cache")
            code = mb.main(["--db", str(db_path), "--sources", "fanart",
                            "--cache-dir", cache_dir, "--out", str(out)])
            self.assertEqual(code, 0)
            first = [line for line in out.read_text().splitlines()
                     if line.startswith("UPDATE posts SET")]
            self.assertGreater(len(first), 0)
            progress = json.loads((Path(tmp) / "out.progress.json").read_text())
            self.assertTrue(progress["complete"])
            # Second run with --resume: everything is already filled, so no
            # new UPDATE lines appear and the report says what was resumed.
            code = mb.main(["--db", str(db_path), "--sources", "fanart",
                            "--cache-dir", cache_dir, "--out", str(out),
                            "--resume"])
            self.assertEqual(code, 0)
            second = [line for line in out.read_text().splitlines()
                      if line.startswith("UPDATE posts SET")]
            self.assertEqual(len(second), len(first))
            report = json.loads(out.with_suffix(".json").read_text())
            self.assertEqual(report["resumed_posts"], len(first))
            self.assertEqual(report["posts_updated"], 0)


class FillOneTests(unittest.TestCase):
    """The live fill (detail open): one work, same four passes at group
    scope, writes through the caller's connection. The app fires it in the
    background and patches the screen from `filled` — so the tests pin
    both the write and the answer."""

    def _sources(self):
        cache = mb.ResponseCache(None)
        return {"tmdb": FakeTmdb(cache), "omdb": FailingSource(cache)}

    def _marco_routes(self):
        return {
            "/search/movie": {"results": [
                {"id": 1, "title": "Marco", "release_date": "2022-01-01",
                 "original_language": "en", "origin_country": ["US"]},
                {"id": 2, "title": "Marco", "release_date": "2022-12-25",
                 "original_language": "ml", "origin_country": ["IN"]},
            ]},
            "/movie/1": self._marco_detail(1, ["Tom Cruise"]),
            "/movie/2": self._marco_detail(2, ["Unni Mukundan"]),
        }

    @staticmethod
    def _marco_detail(tmdb_id, cast):
        return {
            "id": tmdb_id, "vote_average": 7.0, "overview": "A film.",
            "poster_path": "/p.jpg", "backdrop_path": "/b.jpg",
            "release_date": "2022-01-01", "genres": [{"name": "Action"}],
            "credits": {"cast": [{"name": name} for name in cast],
                        "crew": [{"name": "D", "job": "Director"}]},
            "external_ids": {"imdb_id": None},
            "images": {"backdrops": []},
        }

    def _tmdb_source(self, routes):
        cache = mb.ResponseCache(None)
        source = mb.TmdbSource(cache)
        source.http = FakeHttp(routes)
        return {"tmdb": source}

    def test_site_pass_fills_empty_sibling_and_writes_the_db(self):
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", fixture_rows())
            result = mb.fill_one(conn, post_id=2,
                                 sources=self._sources(),
                                 cache=mb.ResponseCache(None))
            self.assertEqual(result["status"], "filled")
            self.assertEqual(result["filled"]["rating"], "8.6")
            # Genres reach the screen comma-joined, not as a JSON list.
            self.assertEqual(result["filled"]["genres"], "Sci-Fi")
            self.assertEqual(result["sources"]["rating"], "site")
            self.assertEqual(result["posts_updated"], [2])
            self.assertEqual(
                conn.execute("SELECT imdb_rating FROM posts WHERE id = 2")
                .fetchone()[0], "8.6")
            conn.close()

    def test_already_complete_when_every_field_is_present(self):
        rows = [(30, "Full Film", 2021, "movie", "7.7", "A full synopsis.",
                 "http://x/full.jpg", "Some Actor", "Some Director",
                 "tt0000030", "123", '["Drama"]', "full-2021", 0, 0)]
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", rows)
            conn.execute("UPDATE posts SET backdrop_urls = ? WHERE id = 30",
                         ('["http://x/backdrop.jpg"]',))
            conn.commit()
            result = mb.fill_one(conn, post_id=30,
                                 sources=self._sources(),
                                 cache=mb.ResponseCache(None))
            self.assertEqual(result["status"], "already_complete")
            self.assertEqual(result["fields_filled"], 0)
            self.assertEqual(result["filled"]["backdrop"],
                             '["http://x/backdrop.jpg"]')
            conn.close()

    def test_not_found_for_unknown_post_and_unknown_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", fixture_rows())
            cache = mb.ResponseCache(None)
            self.assertEqual(
                mb.fill_one(conn, post_id=999, sources=self._sources(),
                            cache=cache)["status"], "not_found")
            self.assertEqual(
                mb.fill_one(conn, work_key="nope-1999",
                            sources=self._sources(),
                            cache=cache)["status"], "not_found")
            conn.close()

    def test_adult_post_and_flagged_sibling_are_skipped(self):
        rows = fixture_rows() + [
            (8, "Clean Twin", 2022, "movie", None, None, None, None, None,
             None, None, None, "twin-2022", 0, 0),
            (9, "Flagged Twin", 2022, "movie", "9.1", None, None, None, None,
             None, None, None, "twin-2022", 1, 0),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", rows)
            cache = mb.ResponseCache(None)
            result = mb.fill_one(conn, post_id=5,
                                 sources=self._sources(), cache=cache)
            self.assertEqual(result["status"], "skipped_adult")
            self.assertEqual(result["fields_filled"], 0)
            # A flagged sibling blocks the whole group: the clean twin's
            # donor is never read, so nothing is written for either row.
            result = mb.fill_one(conn, post_id=8,
                                 sources=self._sources(), cache=cache)
            self.assertEqual(result["status"], "skipped_adult")
            self.assertIsNone(
                conn.execute("SELECT imdb_rating FROM posts WHERE id = 8")
                .fetchone()[0])
            conn.close()

    def test_empty_only_never_overwrites_a_stored_value(self):
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", fixture_rows())
            result = mb.fill_one(conn, post_id=4,
                                 sources=self._sources(),
                                 cache=mb.ResponseCache(None))
            self.assertEqual(
                conn.execute("SELECT imdb_rating FROM posts WHERE id = 4")
                .fetchone()[0], "6.1")
            self.assertNotEqual(result["sources"].get("rating"), "tmdb")
            conn.close()

    def test_apply_plan_guard_refuses_a_value_written_after_planning(self):
        """Two detail opens racing: the plan was made when the field was
        empty, but by write time another writer filled it — the UPDATE's
        empty-guard must refuse, not overwrite."""
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", fixture_rows())
            plan = mb.Plan()
            plan.add(2, "rating", "8.6", "site")
            conn.execute("UPDATE posts SET imdb_rating = '9.9' WHERE id = 2")
            conn.commit()
            self.assertEqual(mb._apply_plan(conn, plan), [])
            self.assertEqual(
                conn.execute("SELECT imdb_rating FROM posts WHERE id = 2")
                .fetchone()[0], "9.9")
            plan2 = mb.Plan()
            plan2.add(3, "rating", "8.6", "site")
            self.assertEqual(mb._apply_plan(conn, plan2), [3])
            conn.close()

    def test_api_fill_for_a_lone_post(self):
        rows = [(20, "Dune Part Two Hindi Dubbed", 2024, "movie", None, None,
                 None, None, None, None, None, None, "dune-lone-2024", 0, 0)]
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", rows)
            result = mb.fill_one(conn, post_id=20,
                                 sources=self._sources(),
                                 cache=mb.ResponseCache(None))
            self.assertEqual(result["status"], "filled")
            self.assertEqual(result["sources"]["rating"], "tmdb")
            self.assertEqual(
                conn.execute("SELECT imdb_rating FROM posts WHERE id = 20")
                .fetchone()[0], "8.6")
            conn.close()

    def test_donor_echo_without_network(self):
        """No sources at all: the site pass still fills from the sibling,
        and nothing can have touched a socket."""
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", fixture_rows())
            result = mb.fill_one(conn, post_id=2, sources={},
                                 cache=mb.ResponseCache(None))
            self.assertEqual(result["status"], "filled")
            self.assertEqual(result["filled"]["rating"], "8.6")
            conn.close()

    def test_post_ids_union_covers_a_work_spanning_keys(self):
        """The route resolves a work_id through cat_work_sites; one work
        can span several canonical keys, and the fill must see all of
        them as one group."""
        rows = [
            (50, "Split Work", 2020, "movie", "9.0", None, None, None, None,
             None, None, None, "split-a-2020", 0, 0),
            (51, "Split Work", 2020, "movie", None, None, None, None, None,
             None, None, None, "split-b-2020", 0, 0),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", rows)
            result = mb.fill_one(conn, post_ids=[50, 51], sources={},
                                 cache=mb.ResponseCache(None))
            self.assertEqual(result["status"], "filled")
            self.assertEqual(
                conn.execute("SELECT imdb_rating FROM posts WHERE id = 51")
                .fetchone()[0], "9.0")
            conn.close()

    def test_fill_one_needs_exactly_one_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", fixture_rows())
            cache = mb.ResponseCache(None)
            with self.assertRaises(ValueError):
                mb.fill_one(conn, sources={}, cache=cache)
            with self.assertRaises(ValueError):
                mb.fill_one(conn, post_id=1, work_key="dune-2024",
                            sources={}, cache=cache)
            conn.close()

    def test_tie_is_ambiguous_and_fills_nothing(self):
        rows = [(40, "Marco", 2022, "movie", None, None, None, None, None,
                 None, None, None, "marco-2022", 0, 0)]
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", rows)
            with patch.dict("os.environ", {"TMDB_API_KEY": "test-key"}):
                result = mb.fill_one(conn, post_id=40,
                                     sources=self._tmdb_source(
                                         self._marco_routes()),
                                     cache=mb.ResponseCache(None))
            self.assertEqual(result["status"], "ambiguous")
            self.assertEqual(result["fields_filled"], 0)
            self.assertEqual(len(result["ambiguous"]), 1)
            self.assertEqual(len(result["ambiguous"][0]["candidates"]), 2)
            self.assertEqual(result["ambiguous"][0]["post_id"], 40)
            self.assertIsNone(
                conn.execute("SELECT imdb_rating FROM posts WHERE id = 40")
                .fetchone()[0])
            conn.close()

    def test_language_wins_for_marco(self):
        rows = [(41, "Marco", 2022, "movie", None, None, None, None, None,
                 None, None, None, "marco-2022", 0, 0)]
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", rows)
            conn.execute("UPDATE posts SET language = 'Malayalam',"
                         " country = 'India' WHERE id = 41")
            conn.commit()
            with patch.dict("os.environ", {"TMDB_API_KEY": "test-key"}):
                result = mb.fill_one(conn, post_id=41,
                                     sources=self._tmdb_source(
                                         self._marco_routes()),
                                     cache=mb.ResponseCache(None))
            self.assertEqual(result["status"], "filled")
            self.assertEqual(result["filled"]["cast"], "Unni Mukundan")
            self.assertEqual(
                conn.execute("SELECT cast_names FROM posts WHERE id = 41")
                .fetchone()[0], "Unni Mukundan")
            conn.close()

    def test_imdb_id_bypasses_disambiguation(self):
        rows = [(42, "Marco", 2022, "movie", None, None, None, None, None,
                 "tt1234567", None, None, "marco-2022", 0, 0)]
        routes = {
            "/find/tt1234567": {"movie_results": [
                {"id": 2, "title": "Marco", "release_date": "2022-12-25"}],
                "tv_results": []},
            "/movie/2": self._marco_detail(2, ["Unni Mukundan"]),
        }
        with tempfile.TemporaryDirectory() as tmp:
            conn = make_db(Path(tmp) / "t.db", rows)
            with patch.dict("os.environ", {"TMDB_API_KEY": "test-key"}):
                result = mb.fill_one(conn, post_id=42,
                                     sources=self._tmdb_source(routes),
                                     cache=mb.ResponseCache(None))
            self.assertEqual(result["status"], "filled")
            self.assertEqual(
                conn.execute("SELECT cast_names FROM posts WHERE id = 42")
                .fetchone()[0], "Unni Mukundan")
            conn.close()

    def test_budget_seeds_and_records_a_metered_source(self):
        """The batch's per-run cap is a per-day file on the live path: the
        source starts from yesterday's count, not zero, and the file is
        updated after the fill."""

        class FakeMetered(mb.Source):
            NAME = "omdb"
            DAILY_BUDGET = 950

            def lookup(self, query: dict):
                if self.calls >= self.DAILY_BUDGET:
                    self.exhausted = True
                    return None
                self.calls += 1
                out = mb.Metadata()
                out.set("rating", 7.5, "omdb")
                return out

        rows = [(60, "Metered Film", 2021, "movie", None, None, None, None,
                 None, "tt0000060", None, None, "metered-2021", 0, 0)]
        with tempfile.TemporaryDirectory() as tmp:
            budget_path = Path(tmp) / "budget.json"
            budget = mb.DailyBudget(budget_path)
            budget.record("omdb", 5)
            conn = make_db(Path(tmp) / "t.db", rows)
            cache = mb.ResponseCache(None)
            source = FakeMetered(cache)
            result = mb.fill_one(conn, post_id=60, sources={"omdb": source},
                                 budget=mb.DailyBudget(budget_path),
                                 cache=cache)
            self.assertEqual(result["status"], "filled")
            self.assertEqual(result["calls"]["omdb"]["calls"], 6)
            self.assertEqual(mb.DailyBudget(budget_path).count("omdb"), 6)
            conn.close()


class DailyBudgetTests(unittest.TestCase):
    def test_empty_budget_counts_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(
                mb.DailyBudget(Path(tmp) / "b.json").count("omdb"), 0)

    def test_record_persists_and_is_monotonic(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "b.json"
            mb.DailyBudget(path).record("omdb", 3)
            self.assertEqual(mb.DailyBudget(path).count("omdb"), 3)
            # A stale lower count never rewinds the file.
            mb.DailyBudget(path).record("omdb", 2)
            self.assertEqual(mb.DailyBudget(path).count("omdb"), 3)

    def test_yesterday_rolls_over_to_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "b.json"
            yesterday = time.strftime(
                "%Y-%m-%d", time.localtime(time.time() - 86400))
            path.write_text(json.dumps({"date": yesterday,
                                        "calls": {"omdb": 900}}),
                            encoding="utf-8")
            self.assertEqual(mb.DailyBudget(path).count("omdb"), 0)

    def test_corrupt_file_counts_zero_and_does_not_raise(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "b.json"
            path.write_text("{not json", encoding="utf-8")
            self.assertEqual(mb.DailyBudget(path).count("omdb"), 0)
            mb.DailyBudget(path).record("omdb", 1)
            self.assertEqual(mb.DailyBudget(path).count("omdb"), 1)

    def test_no_path_is_a_noop(self):
        budget = mb.DailyBudget(None)
        budget.record("omdb", 4)
        self.assertEqual(budget.count("omdb"), 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
