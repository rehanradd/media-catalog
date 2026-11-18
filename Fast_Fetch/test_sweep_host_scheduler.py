#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline tests for the host-partitioned sweep — ``Fast_Fetch/sweep_host_scheduler.py``.

Why this file exists: the per-site lanes collapsed twice in one day on
SQLite writer locks (12 dead 15:44, 7 more ~16:22, 8 of 15 by 16:36 on
2026-11-18) while the rate fell 7.6 → ~0.5 rows/s. This suite pins the
replacement's shape: one writer, host partitioning, dedup before probing,
HEAD-only verdicts through the SAME ``link_state`` verdict policy — and
that a transport failure still earns no verdict.

No network is used or allowed, except the httpx-parity tests, which knock
on a loopback stub server this file boots itself (skipped where httpx is
not installed). ``link_health.probe``/``probe_many`` are patched to
explode, and every verdict in the offline tests comes from a fake prober
built out of real ``link_health.ProbeResult`` objects. The fixture ledger
is created by ``fast_fetch.init_db`` so these tests run against the
production schema, not a caricature of it.
"""

from __future__ import annotations

import http.server
import os
import socket
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
import unittest.mock
from pathlib import Path

os.environ.setdefault("RADD_SKIP_LIVE_ENRICH", "1")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fast_fetch  # noqa: E402
import link_health  # noqa: E402
import link_state  # noqa: E402
import sweep_host_scheduler as shs  # noqa: E402

HTTPX_AVAILABLE = shs.httpx is not None


def _result(url, **overrides):
    base = {"url": url, "ok": False, "http_status": 0, "protocol": "unusable",
            "content_type": "", "size_bytes": 0, "accepts_ranges": False,
            "resumable": False, "cors_open": False, "latency_ms": 120,
            "download_speed_mbps": 0.0, "streamable": False,
            "downloadable": False, "redirects_to": "", "error": "",
            "checked_at": 1_730_000_000.0}
    base.update(overrides)
    return link_health.ProbeResult(**base)


def live(url, **overrides):
    """A probe that says: this server answers, plays, and resumes."""
    defaults = {"ok": True, "http_status": 200, "protocol": "progressive",
                "content_type": "video/mp4", "size_bytes": 2_000_000_000,
                "accepts_ranges": True, "resumable": True, "cors_open": True,
                "download_speed_mbps": 0.0, "streamable": True,
                "downloadable": True}
    defaults.update(overrides)
    return _result(url, **defaults)


def http_error(url, status):
    return _result(url, http_status=status, error=f"http_{status}")


def transport(url, error="timeout"):
    return _result(url, error=error)


class FakeProber:
    """A prober that answers from a table and records every call + time."""

    def __init__(self, answers):
        self.answers = dict(answers)
        self.calls: list[tuple[str, float]] = []

    def probe(self, url):
        self.calls.append((url, time.monotonic()))
        if url not in self.answers:
            raise AssertionError(f"unexpected probe: {url}")
        return self.answers[url]


class FakeDns(shs.DnsCache):
    """Deterministic DNS for the offline suite: every name resolves to one
    public address, so no test ever touches a resolver. Literal IPs and
    localhost still go through the real guard (they never reach resolve)."""

    def resolve(self, host):
        key = (host or "").strip().lower().rstrip(".")
        if key not in self._cache:
            self._cache[key] = ("93.184.216.34",)
            self.resolved += 1
        return self._cache[key]


class SchedulerTestCase(unittest.TestCase):
    """One temp catalog: dupes on one host, a dead, an expired, a 429, a
    bridge page, a transport failure, a blocked target, a magnet."""

    CDN_A = "https://cdn.example.com/a.mp4"
    CDN_B = "https://cdn.example.com/b.mp4"
    DEAD = "https://gone.example.com/x.mp4"
    EXPIRED = "https://token.example.com/y.mp4?sig=old"
    LIMITED = "https://limited.example.com/z.mp4"
    PAGE = "https://bridge.example.com/get/abc"
    FLAKY = "https://flaky.example.com/z.mp4"
    BLOCKED = "http://127.0.0.1:9/internal"
    MAGNET = "magnet:?xt=urn:btih:deadbeef"

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp.name) / "catalog.db"
        self._patch = unittest.mock.patch.object(fast_fetch, "DB_PATH", self.db_path)
        self._patch.start()
        fast_fetch.init_db(self.db_path)
        # Nothing in this file may reach the network (except the loopback
        # parity tests, which boot their own stub server).
        self._no_net = [
            unittest.mock.patch.object(link_health, "probe",
                                       side_effect=AssertionError("network use in a test")),
            unittest.mock.patch.object(link_health, "probe_many",
                                       side_effect=AssertionError("network use in a test")),
        ]
        for patcher in self._no_net:
            patcher.start()
        self.conn = link_state.connect(self.db_path)
        link_state.ensure_schema(self.conn)
        self.ids = self._seed()

    def tearDown(self):
        self.conn.close()
        for patcher in self._no_net:
            patcher.stop()
        self._patch.stop()
        self.tmp.cleanup()

    def _seed(self):
        conn = self.conn
        conn.execute(
            "INSERT INTO posts (url, slug, title, clean_title, year, content_type,"
            " source_site, is_18_plus) VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
            ("https://site.test/movie/", "movie", "Movie (2024)", "Movie", 2024,
             "movie", "testsite"))
        post_id = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
        conn.execute(
            "INSERT INTO releases (post_id, release_title, quality, codec, size,"
            " source_site, origin_url) VALUES (?, ?, ?, 'x265', '2.1GB', 'testsite', ?)",
            (post_id, "Movie 1080p", "1080p", "https://site.test/1080p/"))
        release_id = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
        urls = [self.CDN_A, self.CDN_A, self.CDN_B, self.DEAD, self.EXPIRED,
                self.LIMITED, self.PAGE, self.FLAKY, self.BLOCKED, self.MAGNET]
        ids = {}
        for index, url in enumerate(urls):
            conn.execute(
                "INSERT INTO download_sources (release_id, source_name, service,"
                " resource_id, source_url, final_url, playback_type, is_temporary,"
                " status) VALUES (?, ?, ?, ?, ?, ?, 'direct', 0, 'active')",
                (release_id, f"link-{index}", "Direct", f"res-{index}", url, url))
            ids.setdefault(url, []).append(
                conn.execute("SELECT last_insert_rowid()").fetchone()[0])
        conn.commit()
        return ids

    def row(self, url, occurrence=0):
        return self.conn.execute(
            "SELECT * FROM download_sources WHERE id = ?",
            (self.ids[url][occurrence],)).fetchone()

    def answers(self):
        return {
            self.CDN_A: live(self.CDN_A),
            self.CDN_B: live(self.CDN_B),
            self.DEAD: http_error(self.DEAD, 404),
            self.EXPIRED: http_error(self.EXPIRED, 403),
            self.LIMITED: http_error(self.LIMITED, 429),
            # A 200 that answers with a page: live endpoint, not media.
            self.PAGE: _result(self.PAGE, ok=True, http_status=200,
                               protocol="html_page", content_type="text/html"),
            self.FLAKY: transport(self.FLAKY, "timeout"),
        }

    def run_sched(self, prober=None, **kwargs):
        kwargs.setdefault("spacing_s", 0.0)
        kwargs.setdefault("dry_run", True)
        kwargs.setdefault("dns", FakeDns())
        return shs.run_scheduler(self.conn, prober=prober or FakeProber(self.answers()),
                                 **kwargs)

    # ── planning ─────────────────────────────────────────────────────────────

    def test_plan_partitions_by_host_and_dedups_by_target(self):
        plan = shs.plan_slice(self.conn, max_rows=100, dns=FakeDns())
        hosts = plan["representatives"]
        self.assertEqual(sorted(hosts), [
            "bridge.example.com", "cdn.example.com", "flaky.example.com",
            "gone.example.com", "limited.example.com", "token.example.com"])
        # cdn.example.com carries two distinct targets (a.mp4, b.mp4)...
        self.assertEqual(len(hosts["cdn.example.com"]), 2)
        # ...and a.mp4's second row is a sibling, not a second probe.
        self.assertEqual(plan["siblings"], {self.CDN_A: [self.ids[self.CDN_A][1]]})
        self.assertEqual(plan["dupes_saved"], 1)
        self.assertEqual(plan["rows_planned"], 7)
        # The blocked target never reaches the plan: the guard skips it.
        self.assertEqual(plan["dns_skipped"], {"127.0.0.1:9": 1})
        # The magnet is un-probeable: the cursor moves past it, no probe.
        self.assertTrue(plan["exhausted"])
        # Slice end is the last CONSUMED row (the blocked target), not the
        # magnet the scan saw past it.
        self.assertEqual(plan["slice_end_id"], self.ids[self.BLOCKED][0])

    def test_plan_hosts_filter_is_a_pilot_subset(self):
        plan = shs.plan_slice(self.conn, max_rows=100,
                              hosts={"cdn.example.com"}, dns=FakeDns())
        self.assertEqual(sorted(plan["representatives"]), ["cdn.example.com"])
        self.assertEqual(plan["rows_planned"], 2)
        self.assertEqual(plan["dupes_saved"], 1)

    def test_plan_only_counts_without_touching_the_network(self):
        exploding = FakeProber({})
        exploding.probe = lambda url: (_ for _ in ()).throw(
            AssertionError("network use in a plan"))
        summary = shs.run_scheduler(self.conn, prober=exploding, plan_only=True,
                                    spacing_s=0.0, dns=FakeDns())
        self.assertTrue(summary["plan_only"])
        self.assertEqual(summary["rows_planned"], 7)
        self.assertEqual(summary["rows_probed"], 0)

    # ── verdicts ─────────────────────────────────────────────────────────────

    def test_dry_run_probes_once_per_target_and_writes_nothing(self):
        prober = FakeProber(self.answers())
        summary = self.run_sched(prober)
        self.assertEqual(summary["rows_probed"], 7)
        self.assertEqual(sorted(url for url, _ in prober.calls), sorted(self.answers()))
        # 7 probes, 1 transport no-verdict, +1 sibling fan-out = 7 updates.
        self.assertEqual(summary["would_update"], 7)
        self.assertEqual(summary["rows_updated"], 0)
        # Nothing was written: every row is still evidence-free.
        for url in self.answers():
            self.assertIsNone(self.row(url)["checked_at"])
        # And the cursor did not move.
        self.assertIsNone(self.conn.execute(
            "SELECT * FROM link_state_cursor WHERE scope = ?",
            (shs.SCOPE,)).fetchone())

    def test_apply_fans_the_verdict_out_to_sibling_rows(self):
        summary = self.run_sched(dry_run=False)
        self.assertEqual(summary["rows_updated"], 7)
        # The dupe row got its sibling's verdict without a probe.
        self.assertEqual(self.row(self.CDN_A, 1)["status"], "active")
        self.assertEqual(self.row(self.CDN_A, 1)["http_status"], 200)
        self.assertIsNotNone(self.row(self.CDN_A, 1)["checked_at"])
        self.assertEqual(summary["by_status"], {
            "active": 4, "dead": 1, "expired": 2})
        # 429 is counted, and lands as expired — never dead.
        self.assertEqual(summary["http_429"], 1)
        self.assertEqual(self.row(self.LIMITED)["status"], "expired")
        self.assertEqual(self.row(self.EXPIRED)["status"], "expired")
        self.assertEqual(self.row(self.DEAD)["status"], "dead")

    def test_transport_failure_earns_no_verdict(self):
        summary = self.run_sched(dry_run=False)
        row = self.row(self.FLAKY)
        self.assertEqual(row["status"], "active")  # untouched, still the old claim
        self.assertIsNone(row["checked_at"])
        self.assertIn(row["http_status"], (None, 0))
        self.assertNotIn("unverified", summary["by_status"])

    def test_blocked_target_is_never_probed_and_never_marked(self):
        prober = FakeProber(self.answers())
        self.run_sched(prober, dry_run=False)
        self.assertNotIn(self.BLOCKED, [url for url, _ in prober.calls])
        row = self.row(self.BLOCKED)
        self.assertIsNone(row["checked_at"])
        self.assertIn(row["http_status"], (None, 0))

    def test_second_run_skips_rows_the_first_run_banked(self):
        first = self.run_sched(dry_run=False)
        self.assertEqual(first["rows_updated"], 7)
        second = self.run_sched(dry_run=False)
        # Only the transport failure is still pending; it earns no verdict.
        self.assertEqual(second["rows_planned"], 1)
        self.assertEqual(second["rows_updated"], 0)
        self.assertEqual(second["state"], "complete")

    def test_slice_resume_continues_after_the_cursor(self):
        first = self.run_sched(dry_run=False, max_rows=3)
        # 3 rows collected, but only 2 distinct targets to probe.
        self.assertEqual(first["rows_planned"], 2)
        self.assertEqual(first["rows_updated"], 3)
        self.assertEqual(first["state"], "running")
        second = self.run_sched(dry_run=False, max_rows=100)
        # The first slice's rows are banked; the rest follow, then complete.
        self.assertEqual(second["rows_updated"], 7 - first["rows_updated"])
        self.assertEqual(second["state"], "complete")

    def test_per_host_spacing_is_enforced_within_one_host(self):
        prober = FakeProber(self.answers())
        self.run_sched(prober, spacing_s=0.05)
        by_host: dict[str, list[float]] = {}
        for url, when in prober.calls:
            by_host.setdefault(shs.host_of_url(url), []).append(when)
        gaps = [b - a for times in by_host.values() for a, b in zip(times, times[1:])]
        # cdn.example.com was probed twice (a.mp4, b.mp4): one gap, >= spacing.
        self.assertEqual(len(gaps), 1)
        self.assertGreaterEqual(gaps[0], 0.05)

    def test_host_health_is_fed_by_the_apply(self):
        self.run_sched(dry_run=False)
        hosts = {row[0] for row in self.conn.execute(
            "SELECT host FROM host_health")}
        self.assertIn("cdn.example.com", hosts)
        self.assertIn("gone.example.com", hosts)

    # ── prober selection ─────────────────────────────────────────────────────

    def test_make_prober_stdlib_is_the_fallback(self):
        prober, owns_spacing = shs.make_prober("stdlib")
        self.assertIsInstance(prober, shs.StdlibHeadProber)
        self.assertFalse(owns_spacing)

    def test_make_prober_auto_prefers_httpx_when_installed(self):
        prober, owns_spacing = shs.make_prober("auto")
        if HTTPX_AVAILABLE:
            self.assertIsInstance(prober, shs.HttpxHeadProber)
            self.assertTrue(owns_spacing)
        else:
            self.assertIsInstance(prober, shs.StdlibHeadProber)
            self.assertFalse(owns_spacing)

    @unittest.skipUnless(HTTPX_AVAILABLE, "httpx is not installed here")
    def test_httpx_head_prober_rejects_private_targets(self):
        prober = shs.HttpxHeadProber()
        result = prober.probe("http://127.0.0.1:9/internal")
        self.assertFalse(result.ok)
        self.assertEqual(result.error, "blocked_target")
        result = prober.probe("ftp://example.com/file.mp4")
        self.assertEqual(result.error, "invalid_url")

    def test_dns_cache_blocks_private_names_once(self):
        dns = shs.DnsCache()
        self.assertEqual(dns.blocked_reason("localhost"), "localhost")
        self.assertEqual(dns.blocked_reason("10.0.0.5"), "private address 10.0.0.5")
        self.assertEqual(dns.blocked_reason("127.0.0.1:9"), "private address 127.0.0.1")
        self.assertEqual(dns.blocked_reason("[::1]:8080"), "private address ::1")
        # A public name resolves once and is cached — the resolver is stubbed
        # so this test never touches the network.
        calls = {"n": 0}

        def fake_getaddrinfo(host, port, *args, **kwargs):
            calls["n"] += 1
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, "",
                     ("93.184.216.34", 0))]

        with unittest.mock.patch("socket.getaddrinfo", fake_getaddrinfo):
            self.assertEqual(dns.blocked_reason("example.com"), "")
            self.assertEqual(dns.blocked_reason("example.com"), "")
        self.assertEqual(calls["n"], 1)


@unittest.skipUnless(HTTPX_AVAILABLE, "httpx is not installed here")
class HttpxParityTests(unittest.TestCase):
    """The httpx HEAD path must fold a response exactly like the stdlib
    HEAD-only path does — same verdict, same protocol — or the two probers
    would disagree about the same link. Probed against a loopback stub, so
    no real network leaves the box."""

    @classmethod
    def setUpClass(cls):
        class Stub(http.server.BaseHTTPRequestHandler):
            def _send(self, status, ctype, length=123456):
                self.send_response(status)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(length))
                self.send_header("Accept-Ranges", "bytes")
                self.end_headers()

            def do_HEAD(self):
                if self.path == "/movie.mp4":
                    self._send(200, "video/mp4")
                elif self.path == "/page":
                    self._send(200, "text/html", 2048)
                elif self.path == "/gone":
                    self._send(404, "text/html", 0)
                elif self.path == "/token":
                    self._send(403, "text/html", 0)
                elif self.path == "/nohead.mp4":
                    self._send(405, "text/html", 0)
                else:
                    self._send(404, "text/html", 0)

            def do_GET(self):
                if self.path == "/nohead.mp4":
                    self.send_response(206)
                    self.send_header("Content-Type", "video/mp4")
                    self.send_header("Content-Range", "bytes 0-0/123456")
                    self.send_header("Content-Length", "1")
                    self.end_headers()
                    self.wfile.write(b"\x00")
                else:
                    self.do_HEAD()

            def log_message(self, *args):  # noqa: D102 - quiet stub
                pass

        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Stub)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def url(self, path):
        return f"http://127.0.0.1:{self.port}{path}"

    def test_head_verdicts_match_stdlib_head_only(self):
        class AllowAll(shs.DnsCache):
            def blocked_reason(self, host):  # noqa: D102 - loopback stub
                return ""

        prober = shs.HttpxHeadProber(dns=AllowAll())
        for path, expected_status, expected_protocol in (
                ("/movie.mp4", 200, "progressive"),
                ("/page", 200, "html_page"),
                ("/gone", 404, "unusable"),
                ("/token", 403, "unusable")):
            target = self.url(path)
            fast = prober.probe(target)
            slow = link_health.probe(target, allow_download=False,
                                     allow_private=True)
            self.assertEqual(fast.http_status, slow.http_status,
                             f"{path}: status differs")
            self.assertEqual(fast.http_status, expected_status)
            self.assertEqual(fast.protocol, slow.protocol,
                             f"{path}: protocol differs")
            self.assertEqual(fast.protocol, expected_protocol)
            self.assertEqual(link_state.classify(fast),
                             link_state.classify(slow),
                             f"{path}: verdict differs")

    def test_head_refused_falls_back_to_one_byte_range_get(self):
        class AllowAll(shs.DnsCache):
            def blocked_reason(self, host):  # noqa: D102 - loopback stub
                return ""

        prober = shs.HttpxHeadProber(dns=AllowAll())
        result = prober.probe(self.url("/nohead.mp4"))
        # 405 on HEAD, 206 on the Range GET: live, not dead.
        self.assertTrue(result.ok)
        self.assertEqual(result.http_status, 206)
        self.assertEqual(result.protocol, "progressive")
        self.assertEqual(link_state.classify(result), "active")


if __name__ == "__main__":
    unittest.main()
