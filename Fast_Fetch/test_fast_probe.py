#!/usr/bin/env python3
"""Tests for fast_probe.py — host-partitioned, keep-alive, HEAD-only prober.

Offline, stdlib, no network: every HTTP hop goes through a fake connection
factory. Real DNS is also stubbed where the SSRF guard would otherwise call
``getaddrinfo`` (the guard itself still runs — the stub only answers the
lookup with a public IP).

Pinned here:
* verdict mapping is DELEGATED to ``link_state.classify`` — the same inputs
  give the same outputs as a ``link_health.ProbeResult``;
* dedup: one probe per distinct URL, verdicts applied to every row sharing it;
* selection: a batch spans many hosts — each host contributes at most
  ``max(4, max_rows // 24)`` rows, so one giant host cannot fill it;
* host partition: no two concurrent probes on one host, different hosts in
  parallel;
* keep-alive: one connection per host for the run, one reconnect on a dead
  keep-alive, timeouts reported (never retried);
* single writer: concurrent submissions land sequentially, lock retries work,
  lock exhaustion fails loud, enrichment columns are never touched.
"""

from __future__ import annotations

import http.client
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

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fast_probe
import link_health
import link_state

# A public IP for the stubbed resolver: the SSRF guard sees "public" and
# lets the URL through, while no real DNS ever happens.
_FAKE_ADDRINFO = [(socket.AF_INET, socket.SOCK_STREAM, 6, "",
                   ("93.184.216.34", 80))]


def _public_dns(test):
    """Decorator: stub getaddrinfo so the SSRF guard sees a public address."""
    import functools

    @functools.wraps(test)
    def wrapper(self, *args, **kwargs):
        with unittest.mock.patch("socket.getaddrinfo",
                                 return_value=_FAKE_ADDRINFO):
            return test(self, *args, **kwargs)

    return wrapper


# ── fake connections ─────────────────────────────────────────────────────────

class _FakeResponse:
    def __init__(self, status, headers=None, will_close=False):
        self.status = status
        self._headers = dict(headers or {})
        self.will_close = will_close

    def getheaders(self):
        return list(self._headers.items())

    def read(self, *args):
        return b""


class _FakeConnection:
    """http.client-shaped fake. ``behaviour(host, target, n)`` returns either
    ``(status, headers, will_close)`` or raises (to simulate a dead socket).
    """

    def __init__(self, host, port, https, timeout, behaviour, log):
        self.host = host
        self.port = port
        self.https = https
        self.timeout = timeout
        self._behaviour = behaviour
        self._log = log
        self._calls = 0
        self._pending = None
        log.append(("connect", host))

    def request(self, method, target, headers=None, **kwargs):
        self._calls += 1
        self._log.append(("request", self.host, target, method))
        self._pending = self._behaviour(self.host, target, self._calls)

    def getresponse(self):
        status, headers, will_close = self._pending
        return _FakeResponse(status, headers, will_close)

    def close(self):
        self._log.append(("close", self.host))


def _make_factory(behaviour, log):
    """connection_factory(host, port, https, timeout) -> _FakeConnection."""
    created = {"n": 0}

    def factory(host, port, https, timeout):
        created["n"] += 1
        return _FakeConnection(host, port, https, timeout, behaviour, log)

    factory.created = created
    return factory


def _status_map(mapping, default=(200, {}, False)):
    """behaviour from a {(host, target): (status, headers, will_close)} map."""
    def behaviour(host, target, _n):
        return mapping.get((host, target), mapping.get(("*", target), default))
    return behaviour


# ── verdict mapping ──────────────────────────────────────────────────────────

class PayloadMappingTests(unittest.TestCase):
    # (head status, head error) -> expected head_verdict (None = no verdict)
    CASES = [
        ((200, ""), "active"),
        ((204, ""), "active"),
        ((301, ""), None),          # redirect, no Location
        ((302, ""), None),
        ((400, ""), None),          # HEAD refused -> GET judges it later
        ((405, ""), None),
        ((501, ""), None),
        ((401, ""), "expired"),
        ((403, ""), "expired"),
        ((429, ""), "expired"),
        ((404, ""), "dead"),
        ((410, ""), "dead"),
        ((451, ""), "dead"),
        ((500, ""), "dead"),
        ((502, ""), "dead"),
        ((503, ""), "dead"),
        ((0, "timeout"), None),
        ((0, "dns"), None),
        ((0, "tls"), None),
        ((0, "conn_reset"), None),
        ((0, "conn_error"), None),
        ((200, "redirect_loop"), "dead"),
        ((0, "redirect_limit"), "dead"),
        ((0, "blocked_target"), None),
        ((0, "invalid_url"), None),
        ((200, "empty_body"), "dead"),
    ]

    def test_head_status_to_verdict(self):
        for (status, error), expected in self.CASES:
            with self.subTest(status=status, error=error):
                self.assertEqual(fast_probe.head_verdict(status, error, 0.0),
                                 expected)

    def test_delegation_matches_link_health(self):
        """Same inputs through link_health.ProbeResult classify identically."""
        for (status, error), expected in self.CASES:
            with self.subTest(status=status, error=error):
                payload = fast_probe.payload_for_head(status, error, 0.0)
                if payload is None:
                    self.assertIsNone(expected)
                    continue
                via_payload = link_state.classify(payload)
                result = link_health.ProbeResult(
                    url="http://example.com/x.mp4",
                    ok=bool(payload["ok"]), http_status=status, error=error)
                via_result = link_state.classify(result)
                self.assertEqual(via_payload, via_result)
                self.assertEqual(via_payload,
                                 expected if expected is not None
                                 else link_state.STATUS_UNVERIFIED)


# ── row selection ────────────────────────────────────────────────────────────

def _make_ledger(path):
    conn = sqlite3.connect(path)
    conn.execute("""
        CREATE TABLE download_sources (
            id INTEGER PRIMARY KEY,
            source_url TEXT, final_url TEXT,
            status TEXT, http_status INTEGER,
            is_resumable INTEGER DEFAULT 0, file_size_bytes INTEGER,
            mime_type TEXT, last_checked_at TEXT,
            checked_at REAL, probe_error TEXT, protocol TEXT,
            latency_ms INTEGER, speed_mbps REAL, cors_open INTEGER DEFAULT 0
        )""")
    return conn


class SelectionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = os.path.join(self.tmp.name, "cat.db")
        self.conn = _make_ledger(self.db)

    def tearDown(self):
        self.conn.close()
        self.tmp.cleanup()

    def _add(self, rid, url, checked_at=None, status="active"):
        self.conn.execute(
            "INSERT INTO download_sources (id, source_url, final_url, checked_at, status)"
            " VALUES (?, ?, ?, ?, ?)", (rid, url, url, checked_at, status))

    def test_apply_selects_biggest_host_first_and_dedups(self):
        # big.example: 5 rows, 2 share one URL (dedup saves 1 probe)
        for i, url in enumerate([
                "http://big.example/a.mp4", "http://big.example/b.mp4",
                "http://big.example/a.mp4", "http://big.example/c.mp4",
                "http://big.example/d.mp4"], start=1):
            self._add(i, url)
        # small.example: 2 rows
        self._add(6, "http://small.example/x.mp4")
        self._add(7, "http://small.example/y.mp4")
        # unprobeable: magnet + empty + ftp (never selected)
        self._add(8, "magnet:?xt=urn:btih:deadbeef")
        self._add(9, "")
        self._add(10, "ftp://big.example/z.mp4")
        # already verdict (apply mode excludes)
        self._add(11, "http://big.example/old.mp4", checked_at=1795000000.0)
        self.conn.commit()

        sel = fast_probe.select_rows(self.conn, max_rows=4, dry_run=False)
        self.assertEqual(sel["scanned"], 10)  # row 11 already has a verdict
        self.assertEqual(sel["probeable"], 7)
        self.assertEqual(sel["distinct_urls"], 4)
        self.assertEqual(sel["saved_by_dedup"], 1)
        hosts = [h for h, _g in sel["hosts"]]
        self.assertEqual(hosts[0], "big.example")
        big_groups = dict((g["url"], g) for _h, gs in sel["hosts"]
                          for g in gs if _h == "big.example")
        self.assertEqual(sorted(big_groups["http://big.example/a.mp4"]["row_ids"]),
                         [1, 3])

    def test_dry_run_selects_recent_verdicts_with_stored_status(self):
        now = time.time()
        self._add(1, "http://big.example/a.mp4",
                  checked_at=now - 3600, status="dead")
        self._add(2, "http://big.example/b.mp4",
                  checked_at=now - 3600, status="active")
        # stale verdict (8 days) is outside the 7-day window
        self._add(3, "http://big.example/c.mp4",
                  checked_at=now - 8 * 86400, status="active")
        # never checked: not a dry-run candidate
        self._add(4, "http://big.example/d.mp4")
        self.conn.commit()

        sel = fast_probe.select_rows(self.conn, max_rows=10, dry_run=True)
        self.assertEqual(sel["distinct_urls"], 2)
        by_url = {g["url"]: g for _h, gs in sel["hosts"] for g in gs}
        self.assertEqual(by_url["http://big.example/a.mp4"]["stored"], "dead")
        self.assertEqual(by_url["http://big.example/b.mp4"]["stored"], "active")

    def test_apply_spreads_across_hosts_with_per_host_cap(self):
        # Skewed set, like the real catalog: one giant host (100k rows) and
        # 30 small hosts (40 rows each). Without a per-host cap the whole
        # 1,000-row batch would be the giant host (its count alone exceeds
        # 2x max_rows) — the first dry-run measured exactly that: 1 host,
        # 0.75 rows/s, the single-host spacing ceiling, not the prober's rate.
        rows = [(i + 1, "http://giant.example/f%d.mp4" % i,
                 "http://giant.example/f%d.mp4" % i)
                for i in range(100000)]
        rid = 100001
        for h in range(30):
            for j in range(40):
                url = "http://s%02d.example/f%d.mp4" % (h, j)
                rows.append((rid, url, url))
                rid += 1
        self.conn.executemany(
            "INSERT INTO download_sources (id, source_url, final_url)"
            " VALUES (?, ?, ?)", rows)
        self.conn.commit()

        sel = fast_probe.select_rows(self.conn, max_rows=1000, dry_run=False)
        self.assertEqual(sel["scanned"], 101200)
        self.assertEqual(sel["probeable"], 101200)
        self.assertEqual(sel["distinct_urls"], 1000)
        hosts = sel["hosts"]
        # Cap is max(4, 1000 // 24) = 41: the batch spans ~25 hosts.
        self.assertGreaterEqual(len(hosts), 20)
        per_host = {h: len(gs) for h, gs in hosts}
        self.assertLessEqual(max(per_host.values()), 41)
        self.assertEqual(per_host["giant.example"], 41)
        self.assertEqual(hosts[0][0], "giant.example")


# ── keep-alive session ───────────────────────────────────────────────────────

class HostSessionTests(unittest.TestCase):
    @_public_dns
    def test_keep_alive_reuses_one_connection_per_host(self):
        log = []
        factory = _make_factory(_status_map({}), log)
        session = fast_probe.HostSession(connection_factory=factory, timeout=5.0)
        for target in ("/a.mp4", "/b.mp4", "/c.mp4"):
            status, _h, error, _ms = session.head(
                f"http://big.example{target}", fast_probe.probe_headers())
            self.assertEqual((status, error), (200, ""))
        connects = [e for e in log if e[0] == "connect"]
        self.assertEqual(len(connects), 1)
        self.assertEqual(factory.created["n"], 1)

    @_public_dns
    def test_dead_keepalive_reconnects_once_and_probe_succeeds(self):
        log = []

        def behaviour(host, target, n):
            if target == "/b.mp4" and n == 2:
                raise http.client.RemoteDisconnected("server closed it")
            return (200, {}, False)

        factory = _make_factory(behaviour, log)
        session = fast_probe.HostSession(connection_factory=factory, timeout=5.0)
        session.head("http://big.example/a.mp4", fast_probe.probe_headers())
        status, _h, error, _ms = session.head(
            "http://big.example/b.mp4", fast_probe.probe_headers())
        self.assertEqual((status, error), (200, ""))
        self.assertEqual(session.reconnects, 1)
        self.assertEqual(factory.created["n"], 2)

    @_public_dns
    def test_timeout_reported_not_retried(self):
        log = []

        def behaviour(host, target, n):
            raise socket.timeout("slow server")

        # A timeout surfaces as a transport error from the session itself.
        factory = _make_factory(behaviour, log)
        session = fast_probe.HostSession(connection_factory=factory, timeout=5.0)
        # The fake raises socket.timeout out of request(); HostSession maps it.
        status, _h, error, _ms = session.head(
            "http://big.example/a.mp4", fast_probe.probe_headers())
        self.assertEqual((status, error), (0, "timeout"))
        self.assertEqual(factory.created["n"], 1)  # no retry, no reconnect


# ── host partition ───────────────────────────────────────────────────────────

class HostPartitionTests(unittest.TestCase):
    def _timed_factory(self, log, delay=0.05):
        """behaviour that sleeps per request and records (host, start, end)."""
        def behaviour(host, target, _n):
            start = time.monotonic()
            time.sleep(delay)
            log.append((host, threading.current_thread().name, start,
                        time.monotonic()))
            return (200, {}, False)
        return _make_factory(behaviour, [])

    @_public_dns
    def test_same_host_probes_never_overlap(self):
        log = []
        factory = self._timed_factory(log)
        groups = [{"url": f"http://one.example/{i}.mp4", "row_ids": [i],
                   "stored": None} for i in range(4)]
        stats = fast_probe._probe_host(
            "one.example", groups, session_factory=factory, timeout=5.0,
            spacing_s=0.0, headers=fast_probe.probe_headers(), out_queue=None)
        self.assertEqual(stats["probed"], 4)
        spans = sorted((s, e) for _h, _t, s, e in log)
        for (s1, e1), (s2, _e2) in zip(spans, spans[1:]):
            self.assertGreaterEqual(s2, e1)  # strictly serial on one host

    @_public_dns
    def test_different_hosts_probe_in_parallel(self):
        log = []
        factory = self._timed_factory(log, delay=0.10)
        groups_a = [{"url": f"http://a.example/{i}.mp4", "row_ids": [i],
                     "stored": None} for i in range(4)]
        groups_b = [{"url": f"http://b.example/{i}.mp4", "row_ids": [10 + i],
                     "stored": None} for i in range(4)]
        started = time.monotonic()
        import concurrent.futures
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            fa = pool.submit(fast_probe._probe_host, "a.example", groups_a,
                             session_factory=factory, timeout=5.0,
                             spacing_s=0.0, headers=fast_probe.probe_headers(),
                             out_queue=None)
            fb = pool.submit(fast_probe._probe_host, "b.example", groups_b,
                             session_factory=factory, timeout=5.0,
                             spacing_s=0.0, headers=fast_probe.probe_headers(),
                             out_queue=None)
            fa.result()
            fb.result()
        elapsed = time.monotonic() - started
        # 8 sleeps of 0.10s = 0.80s serial; parallel must beat that clearly.
        self.assertLess(elapsed, 0.65)
        hosts = {h for h, _t, _s, _e in log}
        self.assertEqual(hosts, {"a.example", "b.example"})


# ── single writer ────────────────────────────────────────────────────────────

class WriterTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = os.path.join(self.tmp.name, "cat.db")
        self.conn = _make_ledger(self.db)
        for i in range(1, 26):
            self.conn.execute(
                "INSERT INTO download_sources (id, source_url, final_url, status,"
                " is_resumable, file_size_bytes, mime_type, protocol)"
                " VALUES (?, ?, ?, 'active', 1, 12345, 'video/mp4', 'progressive')",
                (i, f"http://w.example/{i}.mp4", f"http://w.example/{i}.mp4"))
        self.conn.commit()

    def tearDown(self):
        self.conn.close()
        self.tmp.cleanup()

    def _writer(self, **kwargs):
        conn = sqlite3.connect(self.db, timeout=30.0, check_same_thread=False)
        self.addCleanup(conn.close)
        return fast_probe.VerdictWriter(conn, **kwargs)

    def test_concurrent_submits_all_land_sequentially(self):
        writer = self._writer(batch_size=10)
        writer.start()
        errors = []

        def submit_many(start):
            try:
                for i in range(start, start + 5):
                    writer.submit({"row_ids": [i], "status": "dead",
                                   "http_status": 404, "probe_error": "http_404",
                                   "checked_at": 1795000000.0})
            except Exception as exc:  # noqa: BLE001 - collected, asserted below
                errors.append(exc)

        threads = [threading.Thread(target=submit_many, args=(1 + 5 * k,))
                   for k in range(5)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(errors, [])
        stats = writer.finish()
        self.assertEqual(stats["rows_written"], 25)
        self.assertEqual(stats["lock_errors"], 0)
        self.assertEqual(stats["batches"], 3)  # 25 rows in batches of 10
        rows = self.conn.execute(
            "SELECT status, http_status FROM download_sources").fetchall()
        self.assertTrue(all(r == ("dead", 404) for r in rows))

    def test_enrichment_columns_untouched(self):
        writer = self._writer(batch_size=10)
        writer.start()
        writer.submit({"row_ids": [1], "status": "dead", "http_status": 404,
                       "probe_error": "http_404", "checked_at": 1795000000.0})
        writer.finish()
        row = self.conn.execute(
            "SELECT is_resumable, file_size_bytes, mime_type, protocol"
            " FROM download_sources WHERE id = 1").fetchone()
        self.assertEqual(row, (1, 12345, "video/mp4", "progressive"))

    def test_lock_retry_then_success(self):
        # A second connection holds an exclusive lock for ~1.5s; the writer's
        # BEGIN IMMEDIATE fails fast (50ms busy timeout), retries with
        # backoff (1s, 2s), and lands once the lock releases. The holder
        # connection is created and used inside its own thread (sqlite3
        # objects are thread-bound). The holder signals `locked` only after
        # BEGIN EXCLUSIVE succeeds, so the writer cannot walk in before the
        # lock exists (a bare start() race failed twice in CI).
        locked = threading.Event()

        def hold_lock():
            holder = sqlite3.connect(self.db, timeout=30.0)
            holder.execute("BEGIN EXCLUSIVE")
            locked.set()
            time.sleep(1.5)
            holder.execute("COMMIT")
            holder.close()

        holder_thread = threading.Thread(target=hold_lock)
        holder_thread.start()
        self.assertTrue(locked.wait(timeout=10.0))
        writer = self._writer(batch_size=10, busy_timeout_ms=50)
        writer.start()
        writer.submit({"row_ids": [1, 2], "status": "dead",
                       "http_status": 404, "probe_error": "http_404",
                       "checked_at": 1795000000.0})
        stats = writer.finish()
        holder_thread.join()
        self.assertGreaterEqual(stats["lock_retries"], 1)
        self.assertEqual(stats["lock_errors"], 0)
        self.assertEqual(stats["rows_written"], 2)

    def test_lock_exhaustion_fails_loud(self):
        # The lock never releases: retries exhaust and finish() raises.
        # Same thread-owned holder as above, but it never commits.
        # `locked` is set only after BEGIN EXCLUSIVE succeeds, so the
        # writer cannot walk in before the lock exists (see the retry test).
        stop = threading.Event()
        locked = threading.Event()

        def hold_forever():
            holder = sqlite3.connect(self.db, timeout=30.0)
            holder.execute("BEGIN EXCLUSIVE")
            locked.set()
            stop.wait()
            holder.execute("ROLLBACK")
            holder.close()

        holder_thread = threading.Thread(target=hold_forever)
        holder_thread.start()
        self.assertTrue(locked.wait(timeout=10.0))
        try:
            writer = self._writer(batch_size=10, busy_timeout_ms=50, lock_retries=2)
            writer.start()
            writer.submit({"row_ids": [1], "status": "dead",
                           "http_status": 404, "probe_error": "http_404",
                           "checked_at": 1795000000.0})
            with self.assertRaises(sqlite3.OperationalError):
                writer.finish()
            self.assertEqual(writer.stats["lock_errors"], 1)
            self.assertEqual(writer.stats["lock_retries"], 2)
        finally:
            stop.set()
            holder_thread.join()


# ── DNS cache ────────────────────────────────────────────────────────────────

class DnsCacheTests(unittest.TestCase):
    def setUp(self):
        fast_probe.clear_dns_cache()

    def tearDown(self):
        fast_probe.clear_dns_cache()

    def test_resolve_once_per_process(self):
        calls = {"n": 0}

        def fake_getaddrinfo(host, port, *args, **kwargs):
            calls["n"] += 1
            return _FAKE_ADDRINFO

        with unittest.mock.patch("socket.getaddrinfo",
                                side_effect=fake_getaddrinfo):
            a = fast_probe.resolve_sockaddr("big.example", 443)
            b = fast_probe.resolve_sockaddr("big.example", 443)
            c = fast_probe.resolve_sockaddr("other.example", 443)
        self.assertEqual(a, b)
        self.assertEqual(calls["n"], 2)  # one per distinct host

    def test_cached_https_connection_uses_sni_and_cached_address(self):
        created = {}

        def fake_create_connection(address, timeout=None, source_address=None):
            created["address"] = address
            sock = unittest.mock.Mock()
            sock.settimeout = unittest.mock.Mock()
            return sock

        context = unittest.mock.Mock()
        wrapped = unittest.mock.Mock()
        context.wrap_socket.return_value = wrapped

        def port_aware_getaddrinfo(host, port, *args, **kwargs):
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, "",
                     ("93.184.216.34", int(port)))]

        with unittest.mock.patch("socket.getaddrinfo",
                                side_effect=port_aware_getaddrinfo), \
             unittest.mock.patch("socket.create_connection",
                                side_effect=fake_create_connection):
            conn = fast_probe._CachedHTTPSConnection(
                "big.example", 443, timeout=5.0, context=context)
            conn.connect()
        self.assertEqual(created["address"], ("93.184.216.34", 443))
        context.wrap_socket.assert_called_once_with(
            unittest.mock.ANY, server_hostname="big.example")
        self.assertIs(conn.sock, wrapped)


# ── end-to-end engines (fake network) ────────────────────────────────────────

class EngineTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = os.path.join(self.tmp.name, "cat.db")
        self.conn = _make_ledger(self.db)
        rows = [
            (1, "http://big.example/a.mp4", None, "active"),
            (2, "http://big.example/b.mp4", None, "active"),
            (3, "http://big.example/a.mp4", None, "active"),  # dupe of 1
            (4, "http://big.example/gone.mp4", None, "active"),
            (5, "http://small.example/x.mp4", None, "active"),
        ]
        for rid, url, checked_at, status in rows:
            self.conn.execute(
                "INSERT INTO download_sources (id, source_url, final_url,"
                " checked_at, status, is_resumable, file_size_bytes)"
                " VALUES (?, ?, ?, ?, ?, 1, 999)", (rid, url, url, checked_at, status))
        self.conn.commit()
        mapping = {("big.example", "/gone.mp4"): (404, {}, False)}
        self.log = []
        self.factory = _make_factory(_status_map(mapping), self.log)

    def tearDown(self):
        self.conn.close()
        self.tmp.cleanup()

    @_public_dns
    def test_run_pilot_apply_writes_verdicts_only(self):
        summary = fast_probe.run_pilot(
            self.conn, max_rows=10, workers=4, timeout=5.0, spacing_s=0.0,
            session_factory=self.factory)
        self.assertEqual(summary["mode"], "apply")
        self.assertEqual(summary["distinct_urls"], 4)   # a, b, gone, x
        self.assertEqual(summary["saved_by_dedup"], 1)  # a.mp4 twice
        self.assertEqual(summary["by_status"], {"active": 3, "dead": 1})
        self.assertEqual(summary["writer"]["lock_errors"], 0)
        self.assertEqual(summary["writer"]["rows_written"], 5)
        rows = {r[0]: r[1] for r in self.conn.execute(
            "SELECT id, status FROM download_sources")}
        self.assertEqual(rows, {1: "active", 2: "active", 3: "active",
                                4: "dead", 5: "active"})
        # Enrichment columns untouched by the HEAD-only pass.
        kept = self.conn.execute(
            "SELECT is_resumable, file_size_bytes FROM download_sources"
            " WHERE id = 1").fetchone()
        self.assertEqual(kept, (1, 999))
        self.assertIsNotNone(self.conn.execute(
            "SELECT checked_at FROM download_sources WHERE id = 1").fetchone()[0])

    @_public_dns
    def test_run_compare_agreement(self):
        fast_probe.run_pilot(self.conn, max_rows=10, workers=4, timeout=5.0,
                             spacing_s=0.0, session_factory=self.factory)
        summary = fast_probe.run_compare(
            self.conn, max_rows=10, workers=4, timeout=5.0, spacing_s=0.0,
            session_factory=self.factory)
        self.assertEqual(summary["mode"], "dry-run")
        self.assertEqual(summary["agreement"], 1.0)
        self.assertEqual(summary["rows_total"], 5)
        # Flip one stored verdict: the compare must catch exactly that row.
        self.conn.execute(
            "UPDATE download_sources SET status = 'dead' WHERE id = 2")
        self.conn.commit()
        summary = fast_probe.run_compare(
            self.conn, max_rows=10, workers=4, timeout=5.0, spacing_s=0.0,
            session_factory=self.factory)
        self.assertEqual(summary["agreed"], 4)
        self.assertEqual(summary["agreement"], 0.8)
        self.assertEqual(summary["disagreements"], {"dead->active": 1})
        self.assertEqual(summary["examples"][0]["url"],
                         "http://big.example/b.mp4")


if __name__ == "__main__":
    unittest.main()
