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


if __name__ == "__main__":
    unittest.main(verbosity=2)
