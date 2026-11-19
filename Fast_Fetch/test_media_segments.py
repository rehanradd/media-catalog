"""Tests for MX #4 Skip Intro / Skip Recap: TheIntroDB parsing, the segments
backfill, and the `play_bundle` wire. No network anywhere: the HTTP layer
is faked, the catalog is a temp-file fixture built through the same
ingestion pipeline the other catalog tests use, and the pure helper tests
run on an in-memory database."""

from __future__ import annotations

import io
import json
import sqlite3
import tempfile
import unittest
import unittest.mock
import urllib.error
from pathlib import Path

import backfill_segments
import catalog_api
import catalog_v2
import fast_fetch
import viewer

# The Breaking Bad S1E1 answer TheIntroDB v3 returned live from the
# Oracle VM on 2026-11-19 — the canned payload the parser tests run on.
BB_S1E1 = {"tmdb_id": 1396, "type": "tv", "season": 1, "episode": 1,
           "intro": [{"start_ms": 228665, "end_ms": 246498}],
           "credits": [{"start_ms": 3432884, "end_ms": None}]}


class FakeResponse:
    """A minimal urllib response: status, body bytes, headers mapping."""

    def __init__(self, status=200, body=b"{}", headers=None):
        self.status = status
        self._body = body
        self.headers = headers or {}

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FakeOpener:
    """Records the URLs the client asks for; answers from a canned map."""

    def __init__(self, routes):
        # routes: url-prefix -> (status, body-dict-or-bytes) | HTTPError
        self.routes = routes
        self.urls = []

    def __call__(self, url, headers):
        self.urls.append(url)
        for prefix, answer in self.routes.items():
            if url.startswith(prefix):
                if isinstance(answer, urllib.error.HTTPError):
                    raise answer
                status, body = answer
                if isinstance(body, dict):
                    body = json.dumps(body).encode("utf-8")
                return FakeResponse(status=status, body=body,
                                    headers={"Retry-After": "7"})
        raise AssertionError(f"no canned route for {url}")


def _http_error(url, code, headers=None):
    return urllib.error.HTTPError(url, code, "err", headers or {}, io.BytesIO(b""))


class ParseSegmentsTestCase(unittest.TestCase):
    def test_real_breaking_bad_payload_parses(self):
        segments = backfill_segments.parse_segments(BB_S1E1)
        self.assertEqual(segments, [
            {"type": "intro", "start_ms": 228665, "end_ms": 246498},
            {"type": "credits", "start_ms": 3432884, "end_ms": None},
        ])

    def test_unknown_type_and_bad_windows_are_dropped(self):
        payload = {"intro": [{"start_ms": 1000, "end_ms": 2000},
                             {"start_ms": -5, "end_ms": 2000},
                             {"start_ms": 3000, "end_ms": 3000},
                             {"start_ms": 4000, "end_ms": 3500},
                             {"start_ms": "x", "end_ms": 2000},
                             "not a dict"],
                   "commercial": [{"start_ms": 100, "end_ms": 200}],
                   "recap": []}
        self.assertEqual(backfill_segments.parse_segments(payload), [
            {"type": "intro", "start_ms": 1000, "end_ms": 2000},
        ])

    def test_non_dict_payload_is_empty(self):
        self.assertEqual(backfill_segments.parse_segments(None), [])
        self.assertEqual(backfill_segments.parse_segments([1, 2]), [])
        self.assertEqual(backfill_segments.parse_segments({}), [])


class ClientTestCase(unittest.TestCase):
    def test_episode_url_carries_season_episode_and_duration(self):
        opener = FakeOpener({backfill_segments.BASE_URL: (200, BB_S1E1)})
        client = backfill_segments.TheIntroDBClient(opener=opener)
        status, segments = client.fetch(1396, season=1, episode=1, duration_ms=3480000)
        self.assertEqual(status, "found")
        self.assertEqual(len(segments), 2)
        self.assertIn("tmdb_id=1396", opener.urls[0])
        self.assertIn("season=1", opener.urls[0])
        self.assertIn("episode=1", opener.urls[0])
        self.assertIn("duration_ms=3480000", opener.urls[0])

    def test_movie_url_has_no_season_or_episode(self):
        opener = FakeOpener({backfill_segments.BASE_URL: (200, {"intro": []})})
        client = backfill_segments.TheIntroDBClient(opener=opener)
        status, _ = client.fetch(693134, season=-1, episode=-1)
        self.assertEqual(status, "found")
        self.assertEqual(opener.urls[0],
                         f"{backfill_segments.BASE_URL}/media?tmdb_id=693134")

    def test_404_is_not_found_not_an_error(self):
        opener = FakeOpener({backfill_segments.BASE_URL:
                             _http_error("https://x", 404)})
        client = backfill_segments.TheIntroDBClient(opener=opener)
        self.assertEqual(client.fetch(1, season=1, episode=1), ("not_found", []))

    def test_429_raises_rate_limited_with_retry_after(self):
        opener = FakeOpener({backfill_segments.BASE_URL:
                             _http_error("https://x", 429,
                                         {"Retry-After": "7"})})
        client = backfill_segments.TheIntroDBClient(opener=opener)
        with self.assertRaises(backfill_segments.RateLimited) as ctx:
            client.fetch(1, season=1, episode=1)
        self.assertEqual(ctx.exception.retry_after, 7.0)

    def test_other_http_errors_and_bad_json_are_runtime_errors(self):
        opener = FakeOpener({backfill_segments.BASE_URL:
                             _http_error("https://x", 500)})
        client = backfill_segments.TheIntroDBClient(opener=opener)
        with self.assertRaises(RuntimeError):
            client.fetch(1, season=1, episode=1)
        bad = FakeOpener({backfill_segments.BASE_URL: (200, b"{not json")})
        client = backfill_segments.TheIntroDBClient(opener=bad)
        with self.assertRaises(RuntimeError):
            client.fetch(1, season=1, episode=1)


class _MemoryDbTestCase(unittest.TestCase):
    """An in-memory DB with the v2 schema and nothing else."""

    def setUp(self):
        self.conn = sqlite3.connect(":memory:")
        self.conn.row_factory = sqlite3.Row
        catalog_v2.ensure_schema(self.conn)

    def tearDown(self):
        self.conn.close()

    def add_work(self, title, *, tmdb_id=None, work_type="tv", is_18_plus=0,
                 runtime_minutes=None):
        cur = self.conn.execute(
            "INSERT INTO cat_works (work_key, title, work_type, is_18_plus,"
            " tmdb_id, runtime_minutes, updated_at) VALUES (?, ?, ?, ?, ?, ?, 1)",
            (title.lower().replace(" ", "-"), title, work_type, is_18_plus,
             tmdb_id, runtime_minutes))
        return cur.lastrowid

    def add_episode(self, work_id, season, episode):
        season_row = self.conn.execute(
            "SELECT id FROM cat_seasons WHERE work_id = ? AND number = ?",
            (work_id, season)).fetchone()
        if season_row is None:
            cur = self.conn.execute(
                "INSERT INTO cat_seasons (work_id, number) VALUES (?, ?)",
                (work_id, season))
            season_id = cur.lastrowid
        else:
            season_id = season_row["id"]
        cur = self.conn.execute(
            "INSERT INTO cat_episodes (work_id, season_id, number) VALUES (?, ?, ?)",
            (work_id, season_id, episode))
        return cur.lastrowid

    def add_segment(self, work_id, tmdb_id, season, episode, seg_type, start, end,
                    media_type="tv"):
        self.conn.execute(
            "INSERT INTO cat_media_segments (work_id, tmdb_id, media_type,"
            " season_number, episode_number, segment_type, start_ms, end_ms,"
            " source, fetched_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'theintrodb', 1)",
            (work_id, tmdb_id, media_type, season, episode, seg_type, start, end))
        self.conn.commit()


class SegmentsForPlayTestCase(_MemoryDbTestCase):
    def test_episode_segments_come_back_unshifted_for_a_single_episode(self):
        work = self.add_work("Show", tmdb_id=1396)
        self.add_segment(work, 1396, 1, 1, "intro", 228665, 246498)
        self.add_segment(work, 1396, 1, 1, "credits", 3432884, None)
        segments = catalog_api._segments_for_play(
            self.conn, work_id=work, season=1, episode=1, is_movie=False,
            coverage_type="single_episode", offset_seconds=None)
        self.assertEqual(segments, [
            {"type": "intro", "start_ms": 228665, "end_ms": 246498},
            {"type": "credits", "start_ms": 3432884, "end_ms": None},
        ])

    def test_pack_offset_shifts_every_window(self):
        work = self.add_work("Show", tmdb_id=1396)
        self.add_segment(work, 1396, 1, 4, "intro", 1000, 2000)
        self.add_segment(work, 1396, 1, 4, "credits", 5000, None)
        segments = catalog_api._segments_for_play(
            self.conn, work_id=work, season=1, episode=4, is_movie=False,
            coverage_type="season_pack", offset_seconds=3000)
        self.assertEqual(segments, [
            {"type": "intro", "start_ms": 3001000, "end_ms": 3002000},
            {"type": "credits", "start_ms": 3005000, "end_ms": None},
        ])

    def test_pack_without_a_known_offset_serves_nothing(self):
        # A wrongly placed Skip button is worse than none: the episode's
        # window inside an uncalibrated pack is unknown, so omit it.
        # `episode_range` has the same problem as `season_pack` even
        # though the calibration flag only names the latter.
        work = self.add_work("Show", tmdb_id=1396)
        self.add_segment(work, 1396, 1, 4, "intro", 1000, 2000)
        for coverage_type in ("season_pack", "episode_range", "parts", "whole_work"):
            segments = catalog_api._segments_for_play(
                self.conn, work_id=work, season=1, episode=4, is_movie=False,
                coverage_type=coverage_type, offset_seconds=None)
            self.assertEqual(segments, [], coverage_type)

    def test_movie_segments_use_the_sentinel(self):
        work = self.add_work("Dune", tmdb_id=693134, work_type="movie")
        self.add_segment(work, 693134, -1, -1, "credits", 9000000, None,
                         media_type="movie")
        segments = catalog_api._segments_for_play(
            self.conn, work_id=work, season=None, episode=None, is_movie=True,
            coverage_type="whole_work", offset_seconds=None)
        self.assertEqual(segments, [
            {"type": "credits", "start_ms": 9000000, "end_ms": None},
        ])

    def test_pack_with_no_episode_chosen_serves_nothing(self):
        work = self.add_work("Show", tmdb_id=1396)
        self.add_segment(work, 1396, 1, 1, "intro", 1000, 2000)
        segments = catalog_api._segments_for_play(
            self.conn, work_id=work, season=1, episode=None, is_movie=False,
            coverage_type="season_pack", offset_seconds=None)
        self.assertEqual(segments, [])

    def test_unknown_segment_types_are_never_served(self):
        work = self.add_work("Show", tmdb_id=1396)
        self.add_segment(work, 1396, 1, 1, "commercial", 1000, 2000)
        segments = catalog_api._segments_for_play(
            self.conn, work_id=work, season=1, episode=1, is_movie=False,
            coverage_type="single_episode", offset_seconds=None)
        self.assertEqual(segments, [])

    def test_no_stored_segments_is_an_empty_list(self):
        work = self.add_work("Show", tmdb_id=1396)
        segments = catalog_api._segments_for_play(
            self.conn, work_id=work, season=1, episode=1, is_movie=False,
            coverage_type="single_episode", offset_seconds=None)
        self.assertEqual(segments, [])


class FakeClient:
    """A TheIntroDB client that answers from a canned map, no network."""

    def __init__(self, answers):
        # answers: (tmdb_id, season, episode) -> ("found", segments) |
        # ("not_found", []) | exception to raise
        self.answers = answers
        self.calls = []

    def fetch(self, tmdb_id, season=None, episode=None, duration_ms=None):
        self.calls.append((tmdb_id, season, episode))
        answer = self.answers[(tmdb_id, season, episode)]
        if isinstance(answer, Exception):
            raise answer
        return answer


class BackfillPlanTestCase(_MemoryDbTestCase):
    def setUp(self):
        super().setUp()
        self.show = self.add_work("Show", tmdb_id=1396, runtime_minutes=58)
        self.add_episode(self.show, 1, 1)
        self.add_episode(self.show, 1, 2)
        self.movie = self.add_work("Dune", tmdb_id=693134, work_type="movie",
                                   runtime_minutes=166)
        self.add_work("No Tmdb", tmdb_id=None)
        self.add_work("Adult", tmdb_id=999, is_18_plus=1)
        self.add_work("No Episodes", tmdb_id=888)
        self.conn.commit()

    def test_targets_are_one_per_movie_and_one_per_episode(self):
        targets = backfill_segments.iter_targets(self.conn)
        self.assertEqual(
            [(t["tmdb_id"], t["season"], t["episode"]) for t in targets],
            [(1396, 1, 1), (1396, 1, 2), (693134, -1, -1)])
        movie = targets[2]
        self.assertEqual(movie["duration_ms"], 166 * 60000)
        self.assertEqual(targets[0]["duration_ms"], 58 * 60000)

    def test_adult_and_tmdb_less_works_are_never_targets(self):
        keys = {(t["tmdb_id"]) for t in backfill_segments.iter_targets(self.conn)}
        self.assertNotIn(999, keys)   # adult
        self.assertNotIn(888, keys)   # a show with no enumerated episodes

    def test_fresh_lookups_and_stored_segments_are_skipped(self):
        self.conn.execute(
            "INSERT INTO cat_media_segment_lookups VALUES (1396, 1, 1, 'not_found', 1000)")
        self.add_segment(self.movie, 693134, -1, -1, "credits", 9000000, None,
                         media_type="movie")
        planned = backfill_segments.plan_targets(self.conn, recheck_days=30,
                                                 now=2000.0)
        self.assertEqual([(t["tmdb_id"], t["season"], t["episode"])
                          for t in planned], [(1396, 1, 2)])

    def test_stale_not_found_is_replanned(self):
        self.conn.execute(
            "INSERT INTO cat_media_segment_lookups VALUES (1396, 1, 1, 'not_found', 1000)")
        planned = backfill_segments.plan_targets(
            self.conn, recheck_days=30, now=1000.0 + 31 * 86400)
        self.assertIn((1396, 1, 1),
                      [(t["tmdb_id"], t["season"], t["episode"]) for t in planned])

    def test_found_lookup_is_never_replanned(self):
        self.conn.execute(
            "INSERT INTO cat_media_segment_lookups VALUES (1396, 1, 1, 'found', 1)")
        planned = backfill_segments.plan_targets(
            self.conn, recheck_days=30, now=1.0 + 365 * 86400)
        self.assertNotIn((1396, 1, 1),
                         [(t["tmdb_id"], t["season"], t["episode"]) for t in planned])


class RunBackfillTestCase(_MemoryDbTestCase):
    def setUp(self):
        super().setUp()
        self.show = self.add_work("Show", tmdb_id=1396)
        self.add_episode(self.show, 1, 1)
        self.add_episode(self.show, 1, 2)
        self.movie = self.add_work("Dune", tmdb_id=693134, work_type="movie")
        self.conn.commit()

    def test_found_stores_segments_and_a_lookup_not_found_only_a_lookup(self):
        client = FakeClient({
            (1396, 1, 1): ("found", [{"type": "intro", "start_ms": 1000,
                                      "end_ms": 2000}]),
            (1396, 1, 2): ("not_found", []),
            (693134, -1, -1): ("found", [{"type": "credits", "start_ms": 5000,
                                          "end_ms": None}]),
        })
        segment_rows, lookup_rows, summary = backfill_segments.run_backfill(
            self.conn, client, rate=0, now=1000.0, sleep=lambda s: None)
        self.assertEqual(summary, {"targets": 3, "found": 2, "not_found": 1,
                                   "errors": 0, "segments": 2,
                                   "rate_limited_retries": 0})
        self.assertEqual(len(segment_rows), 2)
        self.assertEqual(segment_rows[0][:7],
                         (self.show, 1396, "tv", 1, 1, "intro", 1000))
        self.assertEqual(segment_rows[1][:7],
                         (self.movie, 693134, "movie", -1, -1, "credits", 5000))
        self.assertEqual({(r[0], r[1], r[2], r[3]) for r in lookup_rows},
                         {(1396, 1, 1, "found"), (1396, 1, 2, "not_found"),
                          (693134, -1, -1, "found")})

    def test_errors_are_counted_not_stored_and_do_not_abort_the_run(self):
        client = FakeClient({
            (1396, 1, 1): RuntimeError("boom"),
            (1396, 1, 2): ("found", []),
            (693134, -1, -1): ("not_found", []),
        })
        _, lookup_rows, summary = backfill_segments.run_backfill(
            self.conn, client, rate=0, now=1000.0, sleep=lambda s: None)
        self.assertEqual(summary["errors"], 1)
        self.assertEqual(summary["found"], 1)
        self.assertNotIn((1396, 1, 1),
                         {(r[0], r[1], r[2]) for r in lookup_rows})

    def test_rate_limit_is_retried_once_then_counted(self):
        calls = {"n": 0}

        class Flaky:
            def fetch(self, tmdb_id, season=None, episode=None, duration_ms=None):
                calls["n"] += 1
                if calls["n"] == 1:
                    raise backfill_segments.RateLimited(0.5)
                return "found", []

        _, _, summary = backfill_segments.run_backfill(
            self.conn, Flaky(), rate=0, now=1000.0, sleep=lambda s: None)
        self.assertEqual(summary["rate_limited_retries"], 1)
        self.assertEqual(summary["found"], 3)
        self.assertEqual(summary["errors"], 0)

    def test_dry_run_writes_no_rows_and_limit_caps_targets(self):
        client = FakeClient({
            (1396, 1, 1): ("found", [{"type": "intro", "start_ms": 1, "end_ms": 2}]),
            (1396, 1, 2): ("found", []),
            (693134, -1, -1): ("found", []),
        })
        segment_rows, lookup_rows, summary = backfill_segments.run_backfill(
            self.conn, client, limit=1, dry_run=True, rate=0, now=1000.0,
            sleep=lambda s: None)
        self.assertEqual(segment_rows, [])
        self.assertEqual(lookup_rows, [])
        self.assertEqual(summary["targets"], 1)
        self.assertEqual(len(client.calls), 1)


class StagingSqlTestCase(_MemoryDbTestCase):
    def test_staging_sql_applies_and_is_idempotent(self):
        rows = [(7, 1396, "tv", 1, 1, "intro", 1000, 2000, "theintrodb", 1000.0)]
        lookups = [(1396, 1, 1, "found", 1000.0)]
        with tempfile.TemporaryDirectory() as tmp:
            sql_path = Path(tmp) / "out.sql"
            count = backfill_segments.write_staging_sql(
                sql_path, rows, lookups, {"generated_at": "2026-11-19T00:00:00Z"})
            self.assertEqual(count, 2)
            self.conn.executescript(sql_path.read_text(encoding="utf-8"))
            self.conn.executescript(sql_path.read_text(encoding="utf-8"))
            stored = self.conn.execute(
                "SELECT * FROM cat_media_segments").fetchall()
            self.assertEqual(len(stored), 1)
            self.assertEqual(stored[0]["segment_type"], "intro")

    def test_apply_to_copy_refuses_the_source_and_lands_rows_on_a_copy(self):
        rows = [(7, 1396, "tv", 1, 1, "intro", 1000, 2000, "theintrodb", 1000.0)]
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "catalog.db"
            src_conn = sqlite3.connect(str(src))
            catalog_v2.ensure_schema(src_conn)
            src_conn.close()
            sql_path = Path(tmp) / "out.sql"
            backfill_segments.write_staging_sql(sql_path, rows, [], {})
            with self.assertRaises(ValueError):
                backfill_segments.apply_to_copy(src, sql_path, src)
            copy = Path(tmp) / "copy.db"
            backfill_segments.apply_to_copy(src, sql_path, copy)
            check = sqlite3.connect(str(copy))
            stored = check.execute("SELECT * FROM cat_media_segments").fetchall()
            check.close()
            self.assertEqual(len(stored), 1)

    def test_sql_literal_quotes_strings(self):
        self.assertEqual(backfill_segments._sql_lit("o'clock"), "'o''clock'")
        self.assertEqual(backfill_segments._sql_lit(None), "NULL")
        self.assertEqual(backfill_segments._sql_lit(42), "42")


class TypeSyncTestCase(unittest.TestCase):
    def test_backfill_and_api_agree_on_segment_types(self):
        self.assertEqual(backfill_segments.SEGMENT_TYPES,
                         catalog_api._SEGMENT_TYPES)


# ── play_bundle wire ─────────────────────────────────────────────────────

def _cdn(tag, *, status="active"):
    url = f"https://video-downloads.googleusercontent.com/{tag}"
    return {"source_name": "CDN", "service": "google_cdn", "resource_id": tag,
            "source_url": url, "final_url": url, "playback_type": "direct",
            "is_temporary": 0, "status": status}


class PlayBundleWireTestCase(unittest.TestCase):
    """One projected corpus per test: full isolation, no shared state."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp.name) / "catalog.db"
        self._patches = [
            unittest.mock.patch.object(fast_fetch, "DB_PATH", self.db_path),
            unittest.mock.patch.object(viewer, "DB_PATH", self.db_path),
        ]
        for patcher in self._patches:
            patcher.start()
        fast_fetch.init_db(self.db_path)
        self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row
        self.work = {}
        self.print_of = {}
        self._build_corpus()

    def tearDown(self):
        self.conn.close()
        for patcher in self._patches:
            patcher.stop()
        self.tmp.cleanup()

    def _add_post(self, site, url, *, clean_title, year=2024, content_type="movie",
                  tmdb_id=None, releases=()):
        data = {
            "url": url, "slug": url.rstrip("/").split("/")[-1],
            "title": f"{clean_title} ({year})", "clean_title": clean_title,
            "year": year, "content_type": content_type, "seasons": None,
            "poster_url": "https://cdn/poster.jpg",
            "backdrop_urls": '["https://cdn/bd1.jpg"]', "imdb_rating": 7.5,
            "genres": '["Action"]', "synopsis": f"{clean_title} synopsis",
            "is_18_plus": 0, "is_animated": 0, "source_site": site,
            "industry": "Bollywood", "country": "India", "language": "Hindi",
            "canonical_key": fast_fetch.generate_canonical_key(clean_title, year),
            "tmdb_id": tmdb_id, "imdb_id": None, "releases": [],
        }
        for spec in releases:
            data["releases"].append({
                "release_title": spec.get("release_title", f"{clean_title} 1080p"),
                "season": spec.get("season", ""), "episode": spec.get("episode", ""),
                "quality": spec.get("quality", "1080p"), "codec": "x264",
                "size": "1.5GB", "languages": '["Hindi"]',
                "intermediate_url": "", "origin_url": "", "source_site": "",
                "sources": spec.get("sources", []),
            })
        fast_fetch.save_scraped_data(data, enrich_metadata=False, ensure_db=False)
        self.conn.commit()

    def _build_corpus(self):
        self._add_post("hdhub4u", "https://hdhub4u.test/dune-2024/",
                       clean_title="Dune", year=2024, tmdb_id=693134,
                       releases=[{"release_title": "Dune (2024) 1080p WEB-DL",
                                  "quality": "1080p", "sources": [_cdn("DUNE")]}])
        self._add_post("multimovies", "https://mm.test/campus-beats-2020/",
                       clean_title="Campus Beats", year=2020, content_type="series",
                       tmdb_id=1396,
                       releases=[{"release_title": "Campus Beats S01 EP(01-06) 720p",
                                  "season": "1", "episode": "1-6", "quality": "720p",
                                  "sources": [_cdn("CB-R16")]},
                                 {"release_title": "Campus Beats S01E07 720p",
                                  "season": "1", "episode": "7", "quality": "720p",
                                  "sources": [_cdn("CB-E7")]}])
        self.conn.commit()
        catalog_v2.project(self.conn, rev=1, verbose=False)
        for row in self.conn.execute("SELECT * FROM cat_works").fetchall():
            self.work[row["title"]] = dict(row)
        for row in self.conn.execute("SELECT * FROM cat_releases").fetchall():
            title = self.conn.execute(
                "SELECT title FROM cat_works WHERE id = ?",
                (row["work_id"],)).fetchone()["title"]
            self.print_of[(title, row["label"])] = dict(row)

    def _print(self, title, fragment):
        for (work_title, label), value in self.print_of.items():
            if work_title == title and fragment in label:
                return value
        raise AssertionError(f"no print for {title!r} containing {fragment!r}")

    def _episode_id(self, title, season, episode):
        return self.conn.execute(
            "SELECT e.id FROM cat_episodes e JOIN cat_seasons s ON s.id = e.season_id"
            " WHERE e.work_id = ? AND s.number = ? AND e.number = ?",
            (self.work[title]["id"], season, episode)).fetchone()["id"]

    def _store(self, title, season, episode, seg_type, start, end):
        work_id = self.work[title]["id"]
        tmdb_id = self.work[title]["tmdb_id"]
        self.conn.execute(
            "INSERT INTO cat_media_segments (work_id, tmdb_id, media_type,"
            " season_number, episode_number, segment_type, start_ms, end_ms,"
            " source, fetched_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'theintrodb', 1)",
            (work_id, tmdb_id, "movie" if season == -1 else "tv",
             season, episode, seg_type, start, end))
        self.conn.commit()

    def test_movie_bundle_serves_credits_unshifted(self):
        self._store("Dune", -1, -1, "credits", 9000000, None)
        release = self._print("Dune", "1080p")
        bundle = catalog_api.play_bundle(self.conn, release_id=release["id"])
        self.assertTrue(bundle["ok"])
        self.assertEqual(bundle["segments"], [
            {"type": "credits", "start_ms": 9000000, "end_ms": None},
        ])

    def test_episode_bundle_shifts_segments_by_the_calibrated_offset(self):
        self._store("Campus Beats", 1, 4, "intro", 1000, 2000)
        self._store("Campus Beats", 1, 4, "credits", 5000, None)
        release = self._print("Campus Beats", "EP(01-06)")
        episode_id = self._episode_id("Campus Beats", 1, 4)
        self.conn.execute(
            "UPDATE cat_release_episodes SET offset_seconds = 3000,"
            " chapter_title = 'Chapter Four' WHERE release_id = ? AND episode_id = ?",
            (release["id"], episode_id))
        self.conn.commit()
        bundle = catalog_api.play_bundle(self.conn, release_id=release["id"],
                                         episode_id=episode_id)
        self.assertTrue(bundle["ok"])
        self.assertEqual(bundle["coverage"]["offset_seconds"], 3000)
        self.assertEqual(bundle["segments"], [
            {"type": "intro", "start_ms": 3001000, "end_ms": 3002000},
            {"type": "credits", "start_ms": 3005000, "end_ms": None},
        ])

    def test_uncalibrated_pack_bundle_serves_no_segments(self):
        self._store("Campus Beats", 1, 4, "intro", 1000, 2000)
        release = self._print("Campus Beats", "EP(01-06)")
        episode_id = self._episode_id("Campus Beats", 1, 4)
        bundle = catalog_api.play_bundle(self.conn, release_id=release["id"],
                                         episode_id=episode_id)
        self.assertTrue(bundle["ok"])
        # The offset is unknown for this episode in this file — the
        # materialised chapter row carries no offset — so the window
        # cannot be placed and is omitted. (The calibration flag itself
        # only names season_pack/parts; episode_range is unflagged but
        # equally unplaced, which is why the omit rule keys on the
        # offset, not the flag.)
        self.assertIsNone(bundle["coverage"]["offset_seconds"])
        self.assertEqual(bundle["segments"], [])

    def test_bundle_without_stored_segments_carries_an_empty_list(self):
        release = self._print("Dune", "1080p")
        bundle = catalog_api.play_bundle(self.conn, release_id=release["id"])
        self.assertTrue(bundle["ok"])
        self.assertEqual(bundle["segments"], [])
        single = self._print("Campus Beats", "S01E07")
        episode_id = self._episode_id("Campus Beats", 1, 7)
        bundle = catalog_api.play_bundle(self.conn, release_id=single["id"],
                                         episode_id=episode_id)
        self.assertTrue(bundle["ok"])
        self.assertEqual(bundle["segments"], [])

    def test_segments_belong_to_the_episode_being_played(self):
        # S1E4's intro must not ride along on an S1E7 bundle.
        self._store("Campus Beats", 1, 4, "intro", 1000, 2000)
        single = self._print("Campus Beats", "S01E07")
        episode_id = self._episode_id("Campus Beats", 1, 7)
        bundle = catalog_api.play_bundle(self.conn, release_id=single["id"],
                                         episode_id=episode_id)
        self.assertTrue(bundle["ok"])
        self.assertEqual(bundle["segments"], [])


if __name__ == "__main__":
    unittest.main()
