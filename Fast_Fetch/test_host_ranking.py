#!/usr/bin/env python3
"""R1 + R2 + R4 + DB-checkup fix #3: host reliability ranking.

(a) R1 decayed host score: the serve-time rank is a rolling live-rate
with a 7-day half-life — fresh evidence weighs full, 21-day-old
evidence weighs 1/8, and below one effective probe the host is
unmeasured again (neutral 0.5), so a host can recover from a bad
month. The all-time live-rate still exists for the circuit/watchdog
and is unchanged by the decay.
(b) 403/429 (and 401) neutrality: `record_probe_failure` leaves
`host_health` untouched for expired-signature/throttle statuses,
while 404/500 still count as host failures.
(c) Checkup #3 thresholds: the failover ladder suppresses hosts with
>= 50 consecutive failures and demotes >= 10 — no hardcoded host
names, the demotion comes from measured streaks.
(d) `_band_order` orders inside a band by host rank, band still beats
host rank, and the verified preference is untouched (a verified
lower-band print still wins over a healthy-host higher-band one).
(e) v1 detail ladder: the evidence rank still decides first; the host
rank breaks ties inside a rung. `_source_ladder_key` itself is
untouched, and no upstream URL leaks to the client.
(f) R2 annotation: verified / checking / unavailable per print,
batched, cross-checked against `_release_has_verified_candidate`;
`playable` is untouched, so a cold title still plays.
(g) R4: an expired deadline returns fast without network and records
nothing; slice lengths (filepress 15 s, default 5 s); the ladder
passes a deadline to every sibling.

Stdlib only, temp DB / :memory:, no network — any network attempt is
patched to raise, so a test that touches the wire fails loudly.

    python3 -S Fast_Fetch/test_host_ranking.py
"""

import json
import os
import sqlite3
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

sys_path = str(Path(__file__).parent.resolve())
import sys
sys.path.insert(0, sys_path)

import api_v2            # noqa: E402
import catalog_api       # noqa: E402
import catalog_v2        # noqa: E402
import fast_fetch        # noqa: E402
import resolved_cache    # noqa: E402
import serve_verify      # noqa: E402
import tiers             # noqa: E402
import viewer            # noqa: E402

NOW = 1_791_000_000.0
DAY = 86400.0
HOST = "http://our.host"
SECRET = "test-secret-for-host-ranking"


def _mem_conn():
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    return conn


def _no_network(*args, **kwargs):
    raise AssertionError("test touched the network")


# ── R1: the decayed score ────────────────────────────────────────────────

class DecayedHostScoreTests(unittest.TestCase):
    def setUp(self):
        self.conn = _mem_conn()
        self.addCleanup(self.conn.close)

    def test_fresh_outcomes_feed_the_decayed_rate(self):
        serve_verify.record_host_failure(self.conn, "a.example", now=NOW)
        serve_verify.record_host_failure(self.conn, "a.example", now=NOW)
        serve_verify.record_host_success(self.conn, "a.example", now=NOW)
        self.conn.commit()
        self.assertAlmostEqual(
            serve_verify.host_decayed_rate(self.conn, "a.example", now=NOW),
            1 / 3)
        self.assertAlmostEqual(
            serve_verify.host_live_rate(self.conn, "a.example"), 1 / 3)
        self.assertAlmostEqual(
            serve_verify.host_rank_score(self.conn, "a.example", now=NOW),
            1 / 3)

    def test_ancient_evidence_exits_to_neutral(self):
        serve_verify.record_host_failure(self.conn, "b.example", now=NOW)
        self.conn.commit()
        # One failure 21 days ago weighs 1/8 of a probe — below one
        # effective probe, so the host is unmeasured again, not dead.
        self.assertIsNone(serve_verify.host_decayed_rate(
            self.conn, "b.example", now=NOW + 21 * DAY))
        self.assertEqual(serve_verify.host_rank_score(
            self.conn, "b.example", now=NOW + 21 * DAY), 0.5)
        # ... while the all-time rate still remembers the failure.
        self.assertEqual(
            serve_verify.host_live_rate(self.conn, "b.example"), 0.0)

    def test_recent_success_climbs_back_from_old_failures(self):
        for _ in range(10):
            serve_verify.record_host_failure(self.conn, "c.example", now=NOW)
        serve_verify.record_host_success(
            self.conn, "c.example", now=NOW + 21 * DAY)
        self.conn.commit()
        # 10 failures decayed to 1.25 effective probes, plus 1 fresh
        # success: 1 / 2.25 — climbing back, not erased, not stuck.
        self.assertAlmostEqual(serve_verify.host_decayed_rate(
            self.conn, "c.example", now=NOW + 21 * DAY), 4 / 9)
        self.assertAlmostEqual(serve_verify.host_live_rate(
            self.conn, "c.example"), 1 / 11)

    def test_decay_preserves_the_rate_until_the_weight_runs_out(self):
        serve_verify.seed_host_ranking(
            self.conn, [{"host": "d.example", "probes": 20, "successes": 19}],
            now=NOW)
        # Pure time passage decays probes and successes together, so the
        # rate is unchanged — only the weight fades.
        self.assertAlmostEqual(serve_verify.host_decayed_rate(
            self.conn, "d.example", now=NOW + 7 * DAY), 0.95)
        # 70 days on, the weight is below one effective probe: neutral.
        self.assertIsNone(serve_verify.host_decayed_rate(
            self.conn, "d.example", now=NOW + 70 * DAY))
        self.assertEqual(serve_verify.host_rank_score(
            self.conn, "d.example", now=NOW + 70 * DAY), 0.5)

    def test_seed_seeds_decayed_counters(self):
        # The checkup's streamtape shape: 63 probes, 6 successes.
        serve_verify.seed_host_ranking(
            self.conn, [{"host": "e.example", "probes": 63, "successes": 6}],
            now=NOW)
        self.assertAlmostEqual(serve_verify.host_rank_score(
            self.conn, "e.example", now=NOW), 6 / 63)
        self.assertEqual(serve_verify.host_rank_info(
            self.conn, "e.example", now=NOW), (2, 6 / 63))

    def test_old_schema_migrates_decay_columns(self):
        self.conn.execute(
            "CREATE TABLE host_health (host TEXT PRIMARY KEY,"
            " consecutive_failures INTEGER NOT NULL DEFAULT 0,"
            " opened_until REAL NOT NULL DEFAULT 0,"
            " backoff_s REAL NOT NULL DEFAULT 120)")
        self.conn.execute(
            "INSERT INTO host_health (host) VALUES ('old.example')")
        self.assertTrue(serve_verify._ensure_host_health(self.conn))
        cols = {row[1] for row in self.conn.execute(
            "PRAGMA table_info(host_health)").fetchall()}
        self.assertTrue({"probes", "successes", "measured_at",
                         "decay_probes", "decay_successes"} <= cols)
        serve_verify.record_host_failure(self.conn, "old.example", now=NOW)
        self.assertEqual(
            serve_verify.host_decayed_rate(self.conn, "old.example", now=NOW),
            0.0)

    def test_host_rank_info_fails_open(self):
        self.assertEqual(serve_verify.host_rank_info(
            self.conn, "nope.example"), (0, 0.5))
        self.assertEqual(serve_verify.host_rank_info(None, "x.example"),
                         (0, 0.5))


# ── R1: 403/429 neutrality ───────────────────────────────────────────────

class ProbeFailureNeutralityTests(unittest.TestCase):
    def setUp(self):
        self.conn = _mem_conn()
        self.addCleanup(self.conn.close)

    def _host_rows(self):
        try:
            return self.conn.execute(
                "SELECT host, probes, successes FROM host_health"
                ).fetchall()
        except sqlite3.Error:
            return []

    def test_403_429_401_record_nothing_for_the_host(self):
        for status, url in ((403, "https://x.example/f"),
                            (429, "https://y.example/f"),
                            (401, "https://z.example/f")):
            serve_verify.record_probe_failure(
                self.conn, 1, "refused", http_status=status,
                final_url=url, now=NOW)
        self.assertEqual(self._host_rows(), [])

    def test_404_and_500_still_count(self):
        serve_verify.record_probe_failure(
            self.conn, 1, "gone", http_status=404,
            final_url="https://y.example/f", now=NOW)
        serve_verify.record_probe_failure(
            self.conn, 2, "server", http_status=500,
            final_url="https://v.example/f", now=NOW)
        rows = {row["host"]: (row["probes"], row["successes"])
                for row in self._host_rows()}
        self.assertEqual(rows, {"y.example": (1, 0), "v.example": (1, 0)})


# ── shared projected fixture ─────────────────────────────────────────────

def _src(tag, host, *, status="active", service="google_cdn",
         final_url=True):
    url = f"https://{host}/{tag}"
    return {"source_name": "CDN", "service": service,
            "resource_id": tag, "source_url": url,
            "final_url": url if final_url else "",
            "playback_type": "direct",
            "is_temporary": 0 if final_url else 1,
            "status": status}


def _rel(title, quality, sources):
    return {"release_title": title, "season": "", "episode": "",
            "quality": quality, "codec": "x264", "size": "2.5GB",
            "languages": '["Hindi"]', "intermediate_url": "",
            "origin_url": "", "source_site": "", "sources": sources}


def _work_data(title, releases):
    return {"url": "https://siteone.example/" + title.lower().replace(" ", "-"),
            "slug": title.lower().replace(" ", "-"),
            "title": f"{title} (2024)", "clean_title": title, "year": 2024,
            "content_type": "movie", "seasons": None,
            "poster_url": "https://img.siteone.watch/x.jpg",
            "backdrop_urls": None, "imdb_rating": 7.4,
            "genres": '["Action"]', "synopsis": f"{title}: fixture",
            "is_18_plus": 0, "is_animated": 0, "source_site": "siteone",
            "industry": "Bollywood", "country": "India", "language": "Hindi",
            "canonical_key": fast_fetch.generate_canonical_key(title, 2024),
            "tmdb_id": None, "imdb_id": None, "releases": releases}


class HostRankingFixture(unittest.TestCase):
    """One temp catalog per test: full isolation, no shared mutable state."""

    def setUp(self):
        os.environ["RADD_STREAM_SECRET"] = SECRET
        self.addCleanup(os.environ.pop, "RADD_STREAM_SECRET", None)
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp.name) / "rank.db"
        self._patches = [
            mock.patch.object(fast_fetch, "DB_PATH", self.db_path),
            mock.patch.object(viewer, "DB_PATH", self.db_path),
        ]
        for patcher in self._patches:
            patcher.start()
        fast_fetch.init_db(self.db_path)
        self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row
        catalog_v2.ensure_schema(self.conn)
        self.conn.commit()
        catalog_api.configure_public(HOST, SECRET)

    def tearDown(self):
        self.conn.close()
        for patcher in self._patches:
            patcher.stop()
        self.tmp.cleanup()

    def _project(self, rev=1):
        self.conn.commit()
        return catalog_v2.project(self.conn, rev=rev, verbose=False)

    def _ingest_rank_work(self):
        """Two 1080p prints (620 vs 400 — same band, score order [1, 2])
        plus a 720p print with two copies on different hosts."""
        fast_fetch.save_scraped_data(_work_data("Rank Probe", [
            _rel("Rank Probe 1080p BluRay", "1080p",
                 [_src("rank-a", "dead.example")]),
            _rel("Rank Probe 1080p", "1080p",
                 [_src("rank-b", "good.example")]),
            _rel("Rank Probe 720p", "720p",
                 [_src("rank-c", "good.example"),
                  _src("rank-d", "dead.example")]),
        ]), enrich_metadata=False, ensure_db=False)
        self.conn.commit()
        self._project()
        releases = self.conn.execute(
            "SELECT id, resolution, quality_score FROM cat_releases"
            " ORDER BY id").fetchall()
        self.assertEqual(len(releases), 3)
        self.assertEqual([int(r["quality_score"]) for r in releases],
                         [620, 400, 300])
        return [int(r["id"]) for r in releases]

    def _seed_hosts(self, good=19, good_probes=20, bad=6, bad_probes=63):
        now = time.time()
        serve_verify.seed_host_ranking(self.conn, [
            {"host": "good.example", "probes": good_probes,
             "successes": good},
            {"host": "dead.example", "probes": bad_probes,
             "successes": bad},
        ], now=now)
        self.conn.commit()
        return now


# ── R1: the band walk ────────────────────────────────────────────────────

class BandOrderHostRankTests(HostRankingFixture):
    def test_unmeasured_hosts_keep_score_order(self):
        r1, r2, _r3 = self._ingest_rank_work()
        self.assertEqual(api_v2._band_order(self.conn, [r1, r2]), [r1, r2])

    def test_healthy_host_beats_higher_score_in_band(self):
        r1, r2, _r3 = self._ingest_rank_work()
        self._seed_hosts()
        # Print 1 outscores print 2 (620 vs 400) but sits on the dead
        # host (6/63 ≈ 0.095) while print 2 sits on the good one (0.95).
        self.assertEqual(api_v2._band_order(self.conn, [r1, r2]), [r2, r1])

    def test_band_still_beats_host_rank(self):
        r1, _r2, r3 = self._ingest_rank_work()
        self._seed_hosts()
        # The 1080p print on the dead host still goes before the 720p
        # print on the good host: band is the first key, host the second.
        self.assertEqual(api_v2._band_order(self.conn, [r1, r3]), [r1, r3])

    def test_verified_lower_band_still_wins_over_host_rank(self):
        r1, _r2, r3 = self._ingest_rank_work()
        self._seed_hosts()
        # Strongest pin on the verified preference: print 1 has the
        # higher band AND the healthier host, but print 3 is verified.
        link = self.conn.execute(
            "SELECT id FROM download_sources WHERE resource_id = ?",
            ("rank-c",)).fetchone()
        self.assertIsNotNone(link)
        resolved_cache.record_resolved_cache(
            self.conn, int(link["id"]),
            {"url": "https://video-downloads.googleusercontent.com/rank-c",
             "resolved_at": time.time(),
             "expires_in": 3600, "http_status": 200})
        self.conn.commit()
        bundle = api_v2._play_first_playable(
            self.conn, [r1, r3], episode_id=None, include_adult=False)
        self.assertEqual(bundle["release_id"], r3)


# ── R1: the v1 detail ladder ─────────────────────────────────────────────

class DetailLadderHostRankTests(HostRankingFixture):
    def _ladder_sources(self, r3):
        post = self.conn.execute("SELECT id FROM posts").fetchone()
        _releases, rel_list, _total_r, _total_s = (
            viewer._fetch_releases_with_sources(self.conn, [int(post["id"])]))
        # The projected release carries "id", not "release_id".
        rel_720p = next(r for r in rel_list if int(r["id"]) == r3)
        return rel_720p["sources"]

    def _source_hosts(self, sources):
        """The host behind each projected source, decoded from its own
        stream handle — the projected dict names no hoster by design."""
        import redact
        host_by_ledger = {
            int(row["id"]): serve_verify._host_of(row["source_url"])
            for row in self.conn.execute(
                "SELECT id, source_url FROM download_sources").fetchall()}
        hosts = []
        for source in sources:
            ledger_id = redact.parse_token(source["stream_id"],
                                           viewer.signing_secret())
            self.assertIsNotNone(ledger_id, "stream handle did not decode")
            hosts.append(host_by_ledger[int(ledger_id)])
        return hosts

    def test_ladder_breaks_evidence_ties_by_host(self):
        _r1, _r2, r3 = self._ingest_rank_work()
        # Unmeasured: both copies are unprobed claims (rank 1), so the
        # ladder keeps id order.
        sources = self._ladder_sources(r3)
        self.assertEqual(self._source_hosts(sources),
                         ["good.example", "dead.example"])
        # Seeded against id order: the dead host measures good, the good
        # host measures bad — the ladder follows the measurement.
        serve_verify.seed_host_ranking(self.conn, [
            {"host": "good.example", "probes": 63, "successes": 6},
            {"host": "dead.example", "probes": 20, "successes": 19},
        ], now=time.time())
        self.conn.commit()
        sources = self._ladder_sources(r3)
        self.assertEqual(self._source_hosts(sources),
                         ["dead.example", "good.example"])

    def test_ladder_key_itself_unchanged(self):
        _r1, _r2, r3 = self._ingest_rank_work()
        rows = self.conn.execute(
            "SELECT * FROM download_sources WHERE release_id = ?"
            " ORDER BY id",
            (r3,)).fetchall()
        # Both copies are the same evidence rung — the tie the host rank
        # exists to break — and `_source_ladder_key` still says so.
        keys = [viewer._source_ladder_key(dict(r)) for r in rows]
        self.assertEqual(len(keys), 2)
        self.assertEqual(keys[0], keys[1])

    def test_no_source_url_leaks_to_the_client(self):
        _r1, _r2, _r3 = self._ingest_rank_work()
        self._seed_hosts()
        post = self.conn.execute("SELECT id FROM posts").fetchone()
        _releases, rel_list, _t, _s = (
            viewer._fetch_releases_with_sources(self.conn, [int(post["id"])]))
        dumped = json.dumps(rel_list)
        self.assertNotIn("good.example", dumped)
        self.assertNotIn("dead.example", dumped)
        self.assertNotIn("source_url", dumped)
        self.assertNotIn("final_url", dumped)


# ── R2: the verification annotation ──────────────────────────────────────

class VerificationAnnotationTests(HostRankingFixture):
    def _bank_fresh(self, resource_id):
        link = self.conn.execute(
            "SELECT id FROM download_sources WHERE resource_id = ?",
            (resource_id,)).fetchone()
        self.assertIsNotNone(link)
        resolved_cache.record_resolved_cache(
            self.conn, int(link["id"]),
            {"url": "https://video-downloads.googleusercontent.com/" + resource_id,
             "resolved_at": time.time(),
             "expires_in": 3600, "http_status": 200})
        self.conn.commit()
        return int(link["id"])

    def _detail(self):
        work = self.conn.execute(
            "SELECT id FROM cat_works WHERE title = 'Rank Probe'").fetchone()
        return catalog_api.work_detail(self.conn, work_id=int(work["id"]))

    def test_verified_checking_unavailable(self):
        r1, _r2, r3 = self._ingest_rank_work()
        self._bank_fresh("rank-a")
        detail = self._detail()
        by_id = {p["release_id"]: p for p in detail["prints"]}
        self.assertEqual(by_id[r1]["verification"]["state"], "verified")
        self.assertIsNotNone(by_id[r1]["verification"]["verified_at"])
        self.assertEqual(by_id[_r2]["verification"]["state"], "checking")
        self.assertIsNone(by_id[_r2]["verification"]["verified_at"])
        # Kill every copy of the 720p print: no playable copy left.
        self.conn.execute(
            "UPDATE cat_links SET state = 'dead' WHERE release_id = ?",
            (r3,))
        self.conn.commit()
        detail = self._detail()
        by_id = {p["release_id"]: p for p in detail["prints"]}
        self.assertEqual(by_id[r3]["verification"]["state"], "unavailable")
        self.assertIsNone(by_id[r3]["verification"]["verified_at"])

    def test_annotation_matches_release_has_verified_candidate(self):
        r1, r2, r3 = self._ingest_rank_work()
        self._bank_fresh("rank-b")
        detail = self._detail()
        by_id = {p["release_id"]: p for p in detail["prints"]}
        for rid in (r1, r2, r3):
            expected = api_v2._release_has_verified_candidate(self.conn, rid)
            self.assertEqual(by_id[rid]["verification"]["state"] == "verified",
                             expected,
                             f"print {rid}: annotation disagrees with the walk")

    def test_playable_untouched_by_annotation(self):
        _r1, r2, _r3 = self._ingest_rank_work()
        detail = self._detail()
        by_id = {p["release_id"]: p for p in detail["prints"]}
        # Checking, not verified — and still playable: a cold title
        # plays through the walk's fallback, exactly as before.
        self.assertEqual(by_id[r2]["verification"]["state"], "checking")
        self.assertTrue(any(link["playable"] for link in by_id[r2]["links"]))

    def test_annotation_fails_open_without_tables(self):
        conn = _mem_conn()
        prints = [{"release_id": 1}, {"release_id": 2}]
        catalog_api._annotate_print_verification(conn, prints)
        self.assertNotIn("verification", prints[0])
        conn.close()


# ── R4: the failover slice + deadline ────────────────────────────────────

class FailoverSliceTests(HostRankingFixture):
    def test_slice_lengths(self):
        self.assertEqual(fast_fetch._first_slice_for_service("filepress"),
                         15.0)
        self.assertEqual(fast_fetch._first_slice_for_service("FilePress"),
                         15.0)
        self.assertEqual(fast_fetch._first_slice_for_service("gofile"), 5.0)
        self.assertEqual(fast_fetch._first_slice_for_service(None), 5.0)
        self.assertEqual(fast_fetch._first_slice_for_service(""), 5.0)
        self.assertEqual(fast_fetch.FAILOVER_FIRST_SLICE_SECONDS, 5.0)

    def _ingest_ladder_work(self, sibling_services=("google_cdn",) * 3):
        """One release, four copies: the primary on an unknown host
        (no final_url, so no cached probe runs) plus three siblings."""
        sources = [_src("ladder-primary", "unknown.example",
                        final_url=False)]
        for index, service in enumerate(sibling_services):
            sources.append(_src(f"ladder-sib-{index}",
                                f"h{index}.example", service=service))
        fast_fetch.save_scraped_data(_work_data("Ladder Probe", [
            _rel("Ladder Probe 1080p", "1080p", sources),
        ]), enrich_metadata=False, ensure_db=False)
        self.conn.commit()
        self._project()
        rows = self.conn.execute(
            "SELECT id, resource_id FROM download_sources ORDER BY id"
            ).fetchall()
        self.assertEqual(len(rows), 4)
        return {row["resource_id"]: int(row["id"]) for row in rows}

    def test_expired_deadline_returns_fast_without_network(self):
        ids = self._ingest_ladder_work()
        primary = ids["ladder-primary"]
        with mock.patch("urllib.request.urlopen", _no_network):
            answer = fast_fetch.resolve_live_source(
                primary, deadline=time.monotonic() - 1)
        self.assertFalse(answer["success"])
        self.assertEqual(answer["code"], "no_playable_source")
        self.assertEqual(answer["reason"], "failover_slice_expired")
        self.assertTrue(answer["deadline_expired"])
        # Recorded nowhere: no negative-cache record, and the deadline
        # path never even created the host_health table.
        row = self.conn.execute(
            "SELECT last_failed_at FROM download_sources WHERE id = ?",
            (primary,)).fetchone()
        self.assertIsNone(row["last_failed_at"])
        self.assertIsNone(self.conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type = 'table'"
            " AND name = 'host_health'").fetchone())

    def test_ladder_orders_siblings_by_persistent_host_health(self):
        ids = self._ingest_ladder_work()
        # Streaks, not names: 5 (healthy), 15 (demoted), 60 (suppressed).
        now = time.time()
        for index, streak in enumerate((5, 15, 60)):
            serve_verify.seed_host_ranking(self.conn, [
                {"host": f"h{index}.example", "probes": 20, "successes": 19},
            ], now=now)
            self.conn.execute(
                "UPDATE host_health SET consecutive_failures = ?"
                " WHERE host = ?", (streak, f"h{index}.example"))
        self.conn.commit()
        calls = []

        def _recorder(source_id, *, v2=False, want="either",
                      _failover=True, deadline=None):
            calls.append((int(source_id), deadline))
            return {"success": False, "id": int(source_id),
                    "error": "no_playable_source",
                    "code": "no_playable_source", "reason": "mocked",
                    "stale": False}

        real = fast_fetch.resolve_live_source
        with mock.patch("urllib.request.urlopen", _no_network), \
                mock.patch.object(fast_fetch, "resolve_live_source",
                                  side_effect=_recorder):
            real(ids["ladder-primary"])
        self.assertEqual([sid for sid, _d in calls],
                         [ids["ladder-sib-0"], ids["ladder-sib-1"],
                          ids["ladder-sib-2"]])
        # Every sibling got a deadline, inside the default first slice.
        for _sid, deadline in calls:
            self.assertIsNotNone(deadline)
            self.assertLessEqual(deadline, time.monotonic() + 5.5)

    def test_ladder_gives_filepress_a_longer_slice(self):
        ids = self._ingest_ladder_work(
            sibling_services=("google_cdn", "filepress", "google_cdn"))
        calls = {}

        def _recorder(source_id, *, v2=False, want="either",
                      _failover=True, deadline=None):
            calls[int(source_id)] = deadline
            return {"success": False, "id": int(source_id),
                    "error": "no_playable_source",
                    "code": "no_playable_source", "reason": "mocked",
                    "stale": False}

        real = fast_fetch.resolve_live_source
        with mock.patch("urllib.request.urlopen", _no_network), \
                mock.patch.object(fast_fetch, "resolve_live_source",
                                  side_effect=_recorder):
            real(ids["ladder-primary"])
        default = calls[ids["ladder-sib-0"]]
        filepress = calls[ids["ladder-sib-1"]]
        self.assertIsNotNone(default)
        self.assertIsNotNone(filepress)
        # The 15 s slice is capped by the ladder's 10 s budget, so
        # filepress gets the whole budget while the default gets 5 s.
        self.assertGreater(filepress - default, 4.0)
        self.assertLessEqual(filepress, time.monotonic() + 10.5)


if __name__ == "__main__":
    unittest.main()
