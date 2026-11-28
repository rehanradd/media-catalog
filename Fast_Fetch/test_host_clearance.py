#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline tests for the H4 R8 per-host clearance-cookie store.

The audit behind this slice: no clearance is stored anywhere today —
``fast_fetch`` builds a fresh in-memory ``CookieJar`` per unwrap call and
discards it, and the probers send no cookies at all — so there is no
legitimate clearance to reuse until the store below exists. What this
pins:

* the store: capture only allowlisted names, only on a clean 200/206,
  only with the UA that earned the cookie; a stored cookie is sent only
  inside its effective window (``min(expires_at, obtained_at + 900)``);
  a challenge response invalidates the host at once; a bare 403/503
  without a challenge signal does not; hosts are isolated; one UA per
  replay; operator placement is the only path that accepts other names;
  the caps (8 cookies/host, 64 hosts, LRU) hold;
* the wiring: ``link_health.probe`` replays a stored clearance and
  surfaces ``challenge`` + ``set_cookie`` on the result;
  ``probe_many`` attaches per-host headers; ``link_state.default_prober``
  attaches and records back through the store; ``fast_probe._probe_host``
  attaches and invalidates on 403/503; ``fast_fetch.http_get`` and the
  protected-host unwraps (vcloud, hubcloud, direct-cloud, hubcdn) thread
  the store through;
* the old behaviour: a host with no clearance probes/resolves exactly
  as before, and the press-banking table coexists untouched in the same
  database.

No network, no production DB: every test runs against an in-memory
SQLite fixture, a loopback stub server, or a stubbed fetch seam
(``fast_fetch._fetch_once`` — the keep-alive transport under http_get).
"""

from __future__ import annotations

import email.message
import http.server
import io
import os
import sqlite3
import sys
import threading
import time
import unittest
from unittest import mock

os.environ.setdefault("RADD_SKIP_LIVE_ENRICH", "1")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fast_fetch  # noqa: E402
import fast_probe  # noqa: E402
import host_clearance as hc  # noqa: E402
import link_health  # noqa: E402
import link_state  # noqa: E402
import resolved_cache  # noqa: E402

UA_A = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0.0.0"
UA_B = "Mozilla/5.0 (X11; Linux x86_64) Chrome/125.0.0.0"
HOST = "vcloud.beer"
NOW = 1_800_000_000.0


def _mem():
    conn = sqlite3.connect(":memory:")
    hc.init_host_clearance(conn)
    return conn


def _place(conn, host=HOST, name="cf_clearance", value="tok-1", ua=UA_A,
           now=NOW, ttl=900):
    return hc.place_clearance(conn, host, name, value, user_agent=ua,
                              ttl_s=ttl, now=now)


class StoreTests(unittest.TestCase):
    def test_capture_on_clean_200_with_allowlisted_cookie(self):
        conn = _mem()
        out = hc.note_response(
            conn, HOST, status=200,
            set_cookie_values=["cf_clearance=tok-1; Path=/; HttpOnly"],
            user_agent=UA_A, now=NOW)
        self.assertEqual(out, {"captured": 1, "invalidated": False, "kept": 1})
        header, ua = hc.cookie_header_for(conn, HOST, now=NOW + 10)
        self.assertEqual(header, "cf_clearance=tok-1")
        self.assertEqual(ua, UA_A)

    def test_non_allowlisted_cookie_is_never_stored(self):
        conn = _mem()
        out = hc.note_response(
            conn, HOST, status=200,
            set_cookie_values=["sessionid=abc123; Path=/",
                               "cf_clearance=tok-1; Path=/"],
            user_agent=UA_A, now=NOW)
        self.assertEqual(out["captured"], 1)
        header, _ = hc.cookie_header_for(conn, HOST, now=NOW + 10)
        self.assertEqual(header, "cf_clearance=tok-1")

    def test_no_capture_without_a_user_agent(self):
        conn = _mem()
        out = hc.note_response(
            conn, HOST, status=200,
            set_cookie_values=["cf_clearance=tok-1; Path=/"],
            user_agent="", now=NOW)
        self.assertEqual(out["captured"], 0)

    def test_no_capture_on_error_status(self):
        conn = _mem()
        for status in (301, 403, 404, 500):
            out = hc.note_response(
                conn, HOST, status=status,
                set_cookie_values=["cf_clearance=tok-1; Path=/"],
                user_agent=UA_A, now=NOW)
            self.assertEqual(out["captured"], 0, status)
        header, _ = hc.cookie_header_for(conn, HOST, now=NOW + 10)
        self.assertIsNone(header)

    def test_reused_within_lifetime_expired_never_sent(self):
        conn = _mem()
        _place(conn, now=NOW)
        header, _ = hc.cookie_header_for(conn, HOST, now=NOW + 899)
        self.assertEqual(header, "cf_clearance=tok-1")
        header, ua = hc.cookie_header_for(conn, HOST, now=NOW + 900)
        self.assertIsNone(header)
        self.assertIsNone(ua)

    def test_cookie_own_expires_never_extends_the_ceiling(self):
        conn = _mem()
        # The vendor says this cookie lives an hour; the store caps at 15 min.
        hc.note_response(
            conn, HOST, status=200,
            set_cookie_values=["cf_clearance=tok-1; Max-Age=3600; Path=/"],
            user_agent=UA_A, now=NOW)
        header, _ = hc.cookie_header_for(conn, HOST, now=NOW + 899)
        self.assertEqual(header, "cf_clearance=tok-1")
        header, _ = hc.cookie_header_for(conn, HOST, now=NOW + 901)
        self.assertIsNone(header)

    def test_cookie_own_expires_can_shorten_the_window(self):
        conn = _mem()
        hc.note_response(
            conn, HOST, status=200,
            set_cookie_values=["cf_clearance=tok-1; Max-Age=60; Path=/"],
            user_agent=UA_A, now=NOW)
        header, _ = hc.cookie_header_for(conn, HOST, now=NOW + 59)
        self.assertEqual(header, "cf_clearance=tok-1")
        header, _ = hc.cookie_header_for(conn, HOST, now=NOW + 61)
        self.assertIsNone(header)

    def test_challenge_body_invalidates_the_host(self):
        conn = _mem()
        _place(conn)
        out = hc.note_response(
            conn, HOST, status=200,
            body=b"<html><body>cf-turnstile please wait</body></html>",
            user_agent=UA_A, now=NOW + 10)
        self.assertTrue(out["invalidated"])
        header, _ = hc.cookie_header_for(conn, HOST, now=NOW + 20)
        self.assertIsNone(header)

    def test_challenge_header_invalidates_the_host(self):
        conn = _mem()
        _place(conn)
        out = hc.note_response(
            conn, HOST, status=403,
            headers={"cf-mitigated": "challenge"},
            body=b"", user_agent=UA_A, now=NOW + 10)
        self.assertTrue(out["invalidated"])
        header, _ = hc.cookie_header_for(conn, HOST, now=NOW + 20)
        self.assertIsNone(header)

    def test_bare_403_without_a_challenge_signal_does_not_invalidate(self):
        conn = _mem()
        _place(conn)
        out = hc.note_response(
            conn, HOST, status=403, body=b"Forbidden", user_agent=UA_A,
            now=NOW + 10)
        self.assertFalse(out["invalidated"])
        header, _ = hc.cookie_header_for(conn, HOST, now=NOW + 20)
        self.assertEqual(header, "cf_clearance=tok-1")

    def test_host_isolation_exact_hostname_only(self):
        conn = _mem()
        _place(conn, host="vcloud.beer")
        for other in ("api.vcloud.beer", "vcloud.beer.evil.test", "hubcloud.mov"):
            header, _ = hc.cookie_header_for(conn, other, now=NOW + 10)
            self.assertIsNone(header, other)
        header, _ = hc.cookie_header_for(conn, "https://VCLOUD.beer/x", now=NOW + 10)
        self.assertEqual(header, "cf_clearance=tok-1")

    def test_replay_uses_one_user_agent_the_newest(self):
        conn = _mem()
        _place(conn, name="cf_clearance", value="tok-a", ua=UA_A, now=NOW)
        _place(conn, name="__cf_bm", value="tok-b", ua=UA_B, now=NOW + 10)
        header, ua = hc.cookie_header_for(conn, HOST, now=NOW + 20)
        self.assertEqual(ua, UA_B)
        self.assertEqual(header, "__cf_bm=tok-b")

    def test_operator_placement_accepts_any_name_and_ttl_bounds(self):
        conn = _mem()
        self.assertTrue(hc.place_clearance(
            conn, HOST, "clearance", "op-1", user_agent=UA_A, ttl_s=3600, now=NOW))
        header, _ = hc.cookie_header_for(conn, HOST, now=NOW + 10)
        self.assertEqual(header, "clearance=op-1")
        self.assertFalse(hc.place_clearance(
            conn, HOST, "x", "y", user_agent=UA_A, ttl_s=59, now=NOW))
        self.assertFalse(hc.place_clearance(
            conn, HOST, "x", "y", user_agent=UA_A, ttl_s=86401, now=NOW))
        self.assertFalse(hc.place_clearance(
            conn, HOST, "bad;name", "y", user_agent=UA_A, now=NOW))
        self.assertFalse(hc.place_clearance(
            conn, HOST, "x", "bad;value", user_agent=UA_A, now=NOW))
        self.assertFalse(hc.place_clearance(
            conn, HOST, "x", "y", user_agent="", now=NOW))

    def test_per_host_cap_prunes_least_recently_used(self):
        conn = _mem()
        for i in range(hc.MAX_COOKIES_PER_HOST + 2):
            hc.place_clearance(conn, HOST, f"c{i}", f"v{i}", user_agent=UA_A,
                               ttl_s=86400, now=NOW + i)
        # The two oldest names are gone; the newest eight survive.
        rows = conn.execute(
            "SELECT cookie_name FROM host_clearance WHERE host = ?",
            (HOST,)).fetchall()
        names = {r[0] for r in rows}
        self.assertEqual(len(names), hc.MAX_COOKIES_PER_HOST)
        self.assertNotIn("c0", names)
        self.assertNotIn("c1", names)
        self.assertIn(f"c{hc.MAX_COOKIES_PER_HOST + 1}", names)

    def test_global_host_cap_prunes_least_recently_used_hosts(self):
        conn = _mem()
        for i in range(hc.MAX_HOSTS + 2):
            hc.place_clearance(conn, f"h{i}.test", "cf_clearance", "v",
                               user_agent=UA_A, ttl_s=86400, now=NOW + i)
        hc.prune(conn, now=NOW + 100)
        hosts = {r[0] for r in conn.execute(
            "SELECT DISTINCT host FROM host_clearance").fetchall()}
        self.assertEqual(len(hosts), hc.MAX_HOSTS)
        self.assertNotIn("h0.test", hosts)
        self.assertNotIn("h1.test", hosts)

    def test_prune_drops_expired_rows(self):
        conn = _mem()
        _place(conn, now=NOW)
        _place(conn, host="other.test", now=NOW)
        self.assertEqual(hc.prune(conn, now=NOW + 901), 2)
        self.assertEqual(
            conn.execute("SELECT COUNT(*) FROM host_clearance").fetchone()[0], 0)

    def test_note_use_counts_replays(self):
        conn = _mem()
        _place(conn)
        self.assertTrue(hc.note_use(conn, HOST, "cf_clearance", now=NOW + 5))
        row = conn.execute(
            "SELECT use_count, last_used_at FROM host_clearance"
            " WHERE host = ? AND cookie_name = 'cf_clearance'",
            (HOST,)).fetchone()
        self.assertEqual(row, (1, NOW + 5))
        self.assertFalse(hc.note_use(conn, HOST, "nope", now=NOW + 6))

    def test_normalize_host(self):
        self.assertEqual(hc.normalize_host("https://VCLOUD.beer:8443/x?y=1"),
                         "vcloud.beer")
        self.assertEqual(hc.normalize_host("vcloud.beer"), "vcloud.beer")
        self.assertEqual(hc.normalize_host(""), "")
        self.assertEqual(hc.normalize_host("not a host !!!"), "")

    def test_parse_set_cookie(self):
        name, value, expires = hc.parse_set_cookie(
            "cf_clearance=tok-1; Path=/; HttpOnly")
        self.assertEqual((name, value), ("cf_clearance", "tok-1"))
        self.assertIsNone(expires)
        _, _, expires = hc.parse_set_cookie(
            "cf_clearance=tok-1; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/")
        self.assertIsNotNone(expires)
        _, _, expires = hc.parse_set_cookie(
            "cf_clearance=tok-1; Max-Age=3600; Expires=Wed, 21 Oct 2026 07:28:00 GMT")
        self.assertAlmostEqual(expires, time.time() + 3600, delta=5)
        self.assertEqual(hc.parse_set_cookie("garbage"), (None, None, None))
        self.assertEqual(hc.parse_set_cookie("sessionid=abc"), ("sessionid", "abc", None))

    def test_store_never_raises_on_a_broken_connection(self):
        conn = _mem()
        conn.close()
        self.assertEqual(hc.cookie_header_for(conn, HOST), (None, None))
        self.assertEqual(
            hc.note_response(conn, HOST, status=200,
                             set_cookie_values=["cf_clearance=x"],
                             user_agent=UA_A),
            {"captured": 0, "invalidated": False, "kept": 0})
        self.assertFalse(hc.place_clearance(conn, HOST, "x", "y", user_agent=UA_A))
        self.assertEqual(hc.invalidate_host(conn, HOST), 0)
        self.assertEqual(hc.prune(conn), 0)

    def test_resolved_cache_coexists_in_the_same_database(self):
        conn = _mem()
        resolved_cache.init_resolved_cache(conn)
        _place(conn)
        tables = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type = 'table'").fetchall()}
        self.assertIn("host_clearance", tables)
        self.assertIn("resolved_cache", tables)
        header, _ = hc.cookie_header_for(conn, HOST, now=NOW + 10)
        self.assertEqual(header, "cf_clearance=tok-1")


class AdapterTests(unittest.TestCase):
    def test_path_adapter_round_trip_and_never_raises(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "store.db")
            store = hc.HostClearance(path)
            self.assertTrue(store.place(HOST, "cf_clearance", "tok-1",
                                        user_agent=UA_A, now=NOW))
            header, ua = store.header_for(HOST, now=NOW + 10)
            self.assertEqual((header, ua), ("cf_clearance=tok-1", UA_A))
            self.assertEqual(store.note_challenge(HOST), 1)
            self.assertEqual(store.header_for(HOST, now=NOW + 20), (None, None))
            self.assertEqual(store.prune(now=NOW + 30), 0)
            # A missing directory degrades to empty results, never raises.
            broken = hc.HostClearance(os.path.join(d, "nope", "x.db"))
            self.assertEqual(broken.header_for(HOST), (None, None))
            self.assertFalse(broken.place(HOST, "x", "y", user_agent=UA_A))

    def test_note_probe_captures_clean_and_invalidates_challenge(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "store.db")
            store = hc.HostClearance(path)
            clean = link_health.ProbeResult(
                url=f"https://{HOST}/f.mp4", ok=True, http_status=200,
                set_cookie=("cf_clearance=tok-9; Path=/",),
                checked_at=NOW)
            out = store.note_probe(f"https://{HOST}/f.mp4", clean,
                                   user_agent=UA_A, now=NOW)
            self.assertEqual(out["captured"], 1)
            header, _ = store.header_for(HOST, now=NOW + 10)
            self.assertEqual(header, "cf_clearance=tok-9")
            # No UA earns no capture — but the challenge half still runs.
            store.note_challenge(HOST)
            out = store.note_probe(f"https://{HOST}/f.mp4", clean,
                                   user_agent="", now=NOW + 20)
            self.assertEqual(out["captured"], 0)
            challenged = link_health.ProbeResult(
                url=f"https://{HOST}/f.mp4", ok=False, http_status=403,
                challenge=True, checked_at=NOW + 30)
            store.place(HOST, "cf_clearance", "tok-1", user_agent=UA_A, now=NOW + 30)
            out = store.note_probe(f"https://{HOST}/f.mp4", challenged, now=NOW + 40)
            self.assertTrue(out["invalidated"])
            self.assertEqual(store.header_for(HOST, now=NOW + 50), (None, None))


# ── loopback stub server for the probe wiring ────────────────────────────────

SEEN = {}


class _StubHandler(http.server.BaseHTTPRequestHandler):
    def _record(self):
        SEEN[self.path] = {
            "Cookie": self.headers.get("Cookie"),
            "User-Agent": self.headers.get("User-Agent"),
            "method": self.command,
        }

    def _send(self, status, body, extra=None):
        self.send_response(status)
        self.send_header("Content-Type", "text/html")
        self.send_header("Content-Length", str(len(body)))
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_HEAD(self):
        self._record()
        self._route()

    def do_GET(self):
        self._record()
        self._route()

    def _route(self):
        if self.path == "/clearance":
            self._send(200, b"<html>ok</html>", {
                "Set-Cookie": "cf_clearance=TOKEN123; Path=/; HttpOnly",
            })
        elif self.path == "/challenge":
            self._send(200, b"<html>cf-turnstile please wait</html>")
        elif self.path == "/blocked":
            self._send(403, b"Forbidden", {"cf-mitigated": "challenge"})
        elif self.path == "/bare403":
            self._send(403, b"Forbidden")
        else:
            self._send(200, b"<html>plain</html>")

    def log_message(self, *args):
        pass


class _StubServer(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = http.server.ThreadingHTTPServer(
            ("127.0.0.1", 0), _StubHandler)
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def _url(self, path):
        return f"http://127.0.0.1:{self.port}{path}"


class ProbeWiringTests(_StubServer):
    def test_probe_replays_stored_clearance_with_pinned_ua(self):
        url = self._url("/plain")
        result = link_health.probe(
            url, allow_private=True, timeout=5.0,
            cookie_header="cf_clearance=tok-1", user_agent=UA_B)
        self.assertTrue(result.ok)
        seen = SEEN["/plain"]
        self.assertEqual(seen["Cookie"], "cf_clearance=tok-1")
        self.assertEqual(seen["User-Agent"], UA_B)

    def test_probe_without_clearance_sends_no_cookie(self):
        SEEN.clear()
        result = link_health.probe(self._url("/plain"), allow_private=True,
                                   timeout=5.0)
        self.assertTrue(result.ok)
        self.assertIsNone(SEEN["/plain"]["Cookie"])

    def test_probe_surfaces_clearance_set_cookie(self):
        result = link_health.probe(self._url("/clearance"), allow_private=True,
                                   timeout=5.0)
        self.assertTrue(result.ok)
        self.assertFalse(result.challenge)
        self.assertEqual(result.set_cookie,
                         ("cf_clearance=TOKEN123; Path=/; HttpOnly",))

    def test_probe_surfaces_challenge_from_body(self):
        result = link_health.probe(self._url("/challenge"), allow_private=True,
                                   timeout=5.0)
        self.assertTrue(result.challenge)
        self.assertEqual(result.set_cookie, ())

    def test_probe_surfaces_challenge_from_header(self):
        result = link_health.probe(self._url("/blocked"), allow_private=True,
                                   timeout=5.0)
        self.assertTrue(result.challenge)
        self.assertFalse(result.ok)

    def test_probe_bare_403_is_not_a_challenge(self):
        result = link_health.probe(self._url("/bare403"), allow_private=True,
                                   timeout=5.0)
        self.assertFalse(result.challenge)

    def test_probe_many_attaches_per_host_headers(self):
        SEEN.clear()
        out = link_health.probe_many(
            [{"url": self._url("/plain")},
             {"url": "http://127.0.0.1:9/nope"}],
            workers=2, timeout=2.0, allow_private=True,
            cookie_headers={"127.0.0.1": "cf_clearance=tok-1"})
        self.assertEqual(len(out), 2)
        self.assertEqual(SEEN["/plain"]["Cookie"], "cf_clearance=tok-1")


class DefaultProberTests(unittest.TestCase):
    def test_default_prober_attaches_and_records_back(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "store.db")
            store = hc.HostClearance(path)
            store.place(HOST, "cf_clearance", "old-tok", user_agent=UA_A, now=NOW)
            seen = {}

            def fake_probe_many(items, **kwargs):
                seen["items"] = items
                return [link_health.ProbeResult(
                    url=items[0]["url"], ok=True, http_status=200,
                    set_cookie=("cf_clearance=new-tok; Path=/",),
                    checked_at=NOW)]

            with mock.patch.object(link_health, "probe_many", fake_probe_many):
                results = link_state.default_prober(
                    [{"final_url": f"https://{HOST}/f.mp4", "source_url": "x"}],
                    clearance=store)
            self.assertEqual(len(results), 1)
            headers = seen["items"][0]["headers"]
            self.assertEqual(headers["Cookie"], "cf_clearance=old-tok")
            self.assertEqual(headers["User-Agent"], UA_A)
            # The capture stamps real time (the prober passes no clock),
            # so the fresh row is read back on the real clock too.
            header, _ = store.header_for(HOST, now=time.time() + 10)
            self.assertEqual(header, "cf_clearance=new-tok")

    def test_default_prober_challenge_invalidates(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "store.db")
            store = hc.HostClearance(path)
            store.place(HOST, "cf_clearance", "old-tok", user_agent=UA_A, now=NOW)

            def fake_probe_many(items, **kwargs):
                return [link_health.ProbeResult(
                    url=items[0]["url"], ok=False, http_status=403,
                    challenge=True, checked_at=NOW)]

            with mock.patch.object(link_health, "probe_many", fake_probe_many):
                link_state.default_prober(
                    [{"final_url": f"https://{HOST}/f.mp4"}],
                    clearance=store)
            self.assertEqual(store.header_for(HOST, now=NOW + 10), (None, None))

    def test_default_prober_without_clearance_is_unchanged(self):
        seen = {}

        def fake_probe_many(items, **kwargs):
            seen["items"] = items
            return [link_health.ProbeResult(url=items[0]["url"], ok=True,
                                            http_status=200, checked_at=NOW)]

        with mock.patch.object(link_health, "probe_many", fake_probe_many):
            link_state.default_prober([{"final_url": f"https://{HOST}/f.mp4"}])
        self.assertNotIn("headers", seen["items"][0])


class FastProbeWiringTests(unittest.TestCase):
    # A host that is NOT a KNOWN_HEAD_LIAR (vcloud.beer is one). H4 R10
    # triggers the ranged-GET fallback on ANY host's 403/429/5xx HEAD, so
    # both chains are stubbed here — the point is the clearance
    # attach/invalidate, not the liar.
    FP_HOST = "probe.example.test"

    def test_probe_host_attaches_clearance_and_invalidates_on_403(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "store.db")
            store = hc.HostClearance(path)
            store.place(self.FP_HOST, "cf_clearance", "tok-1", user_agent=UA_A, now=NOW)
            seen = {}

            def fake_head_chain(url, session, base_headers, timeout, spacing_s):
                seen["headers"] = dict(base_headers)
                return (403, "", url)

            def fake_get_chain(url, session, base_headers, timeout, spacing_s):
                # H4 R10: a refused HEAD gets one ranged GET. The challenge
                # is still there (GET 403 too), so the clearance dies.
                return (403, "", url)

            groups = [{"url": f"https://{self.FP_HOST}/f.mp4", "row_ids": [1]}]
            # The sandbox resolver answers a CGNAT address for every name,
            # so the SSRF guard would block the probe here; CI resolves
            # for real. Stub the guard — the point is the clearance, not DNS.
            with mock.patch.object(fast_probe, "head_chain", fake_head_chain), \
                    mock.patch.object(fast_probe, "get_chain", fake_get_chain), \
                    mock.patch.object(link_health, "_block_reason", return_value=""):
                stats = fast_probe._probe_host(
                    self.FP_HOST, groups, timeout=5.0, spacing_s=0.0,
                    headers={"User-Agent": link_health.USER_AGENT},
                    clearance=store)
            self.assertEqual(seen["headers"]["Cookie"], "cf_clearance=tok-1")
            self.assertEqual(seen["headers"]["User-Agent"], UA_A)
            self.assertEqual(store.header_for(self.FP_HOST, now=NOW + 10), (None, None))
            self.assertEqual(stats["verdicts"], 1)

    def test_probe_host_keeps_clearance_when_get_overrules_head_403(self):
        # H4 R10: HEAD 403 was a method refusal, not a dead clearance —
        # the ranged GET answers 200 with the same cookie, so the
        # clearance is kept and the verdict is active.
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "store.db")
            store = hc.HostClearance(path)
            store.place(self.FP_HOST, "cf_clearance", "tok-1", user_agent=UA_A, now=NOW)

            def fake_head_chain(url, session, base_headers, timeout, spacing_s):
                return (403, "", url)

            def fake_get_chain(url, session, base_headers, timeout, spacing_s):
                return (200, "", url)

            groups = [{"url": f"https://{self.FP_HOST}/f.mp4", "row_ids": [1]}]
            with mock.patch.object(fast_probe, "head_chain", fake_head_chain), \
                    mock.patch.object(fast_probe, "get_chain", fake_get_chain), \
                    mock.patch.object(link_health, "_block_reason", return_value=""):
                stats = fast_probe._probe_host(
                    self.FP_HOST, groups, timeout=5.0, spacing_s=0.0,
                    headers={"User-Agent": link_health.USER_AGENT},
                    clearance=store)
            self.assertEqual(store.header_for(self.FP_HOST, now=NOW + 10),
                             ("cf_clearance=tok-1", UA_A))
            self.assertEqual(stats["results"][f"https://{self.FP_HOST}/f.mp4"],
                             ("active", 200, ""))
            self.assertEqual(stats["get_fallbacks"], 1)

    def test_probe_host_without_clearance_is_unchanged(self):
        seen = {}

        def fake_head_chain(url, session, base_headers, timeout, spacing_s):
            seen["headers"] = dict(base_headers)
            return (200, "", url)

        groups = [{"url": f"https://{self.FP_HOST}/f.mp4", "row_ids": [1]}]
        with mock.patch.object(fast_probe, "head_chain", fake_head_chain), \
                mock.patch.object(link_health, "_block_reason", return_value=""):
            stats = fast_probe._probe_host(
                self.FP_HOST, groups, timeout=5.0, spacing_s=0.0,
                headers={"User-Agent": link_health.USER_AGENT})
        self.assertNotIn("Cookie", seen["headers"])
        self.assertEqual(stats["verdicts"], 1)


# ── fast_fetch wiring (stubbed _fetch_once, no network) ─────────────────────
# The seam moved with the keep-alive port (2026-11-28): http_get's direct
# path no longer calls urllib.request.urlopen, so these tests stub the
# transport under it (_fetch_once) instead. The assertions — clearance
# replay, capture, invalidation, unchanged behaviour — are untouched.

class _FakeResponse:
    def __init__(self, status=200, body=b"<html>ok</html>", url="https://x.test/",
                 headers=None):
        self.status = status
        self._body = body
        self._url = url
        self.headers = email.message.Message()
        for key, value in (headers or {}).items():
            self.headers.add_header(key, value)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def read(self):
        return self._body

    def geturl(self):
        return self._url


class HttpGetWiringTests(unittest.TestCase):
    def test_http_get_replays_stored_clearance(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "store.db")
            store = hc.HostClearance(path)
            store.place(HOST, "cf_clearance", "tok-1", user_agent=UA_B, now=NOW)
            seen = {}

            def fake_urlopen(req, timeout=None, context=None):
                seen["headers"] = dict(req.headers)
                return _FakeResponse(url=f"https://{HOST}/page")

            with mock.patch.object(fast_fetch, "_fetch_once", fake_urlopen):
                status, _, _ = fast_fetch.http_get(
                    f"https://{HOST}/page", max_retries=0, clearance=store)
            self.assertEqual(status, 200)
            self.assertEqual(seen["headers"].get("Cookie"), "cf_clearance=tok-1")
            # Header names are capitalised by urllib's Request.
            ua = seen["headers"].get("User-agent") or seen["headers"].get("User-Agent")
            self.assertEqual(ua, UA_B)

    def test_http_get_captures_fresh_clearance_on_200(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "store.db")
            store = hc.HostClearance(path)

            def fake_urlopen(req, timeout=None, context=None):
                return _FakeResponse(
                    url=f"https://{HOST}/page",
                    headers={"Set-Cookie": "cf_clearance=fresh-1; Path=/"})

            with mock.patch.object(fast_fetch, "_fetch_once", fake_urlopen):
                fast_fetch.http_get(f"https://{HOST}/page", max_retries=0,
                                    clearance=store)
            # The capture stamps real time (http_get passes no clock).
            header, ua = store.header_for(HOST, now=time.time() + 10)
            self.assertEqual(header, "cf_clearance=fresh-1")
            self.assertEqual(ua, fast_fetch.USER_AGENT)

    def test_http_get_challenge_invalidates_on_final_attempt(self):
        import tempfile
        import urllib.error
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "store.db")
            store = hc.HostClearance(path)
            store.place(HOST, "cf_clearance", "tok-1", user_agent=UA_A, now=NOW)
            calls = {"n": 0}

            def fake_urlopen(req, timeout=None, context=None):
                calls["n"] += 1
                hdrs = email.message.Message()
                raise urllib.error.HTTPError(
                    f"https://{HOST}/page", 403, "Forbidden", hdrs,
                    io.BytesIO(b"<html>cf-turnstile wait</html>"))

            with mock.patch.object(fast_fetch, "_fetch_once", fake_urlopen):
                status, _, content = fast_fetch.http_get(
                    f"https://{HOST}/page", max_retries=0, clearance=store)
            self.assertEqual(status, 0)
            self.assertIn("ERROR", content)
            self.assertEqual(calls["n"], 1)
            self.assertEqual(store.header_for(HOST, now=NOW + 10), (None, None))

    def test_http_get_without_clearance_is_unchanged(self):
        def fake_urlopen(req, timeout=None, context=None):
            return _FakeResponse(url="https://x.test/page")

        with mock.patch.object(fast_fetch, "_fetch_once", fake_urlopen):
            status, final, content = fast_fetch.http_get(
                "https://x.test/page", max_retries=0)
        self.assertEqual((status, final), (200, "https://x.test/page"))
        self.assertIn("ok", content)


# ── keep-alive pool (2026-11-28, Phase 3 item 11) ───────────────────────────
# Loopback server, HTTP/1.1 so the connection CAN stay open (the probe
# stub above is HTTP/1.0, which closes after every response and could
# never prove reuse). Connection count is server-side: handle() runs
# once per accepted TCP connection.

_KA_SEEN = {}


class _KeepAliveHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    connections = 0
    last_conn = None

    def handle(self):
        type(self).connections += 1
        type(self).last_conn = self.connection
        super().handle()

    def _send(self, body, status=200, extra=None):
        data = body if isinstance(body, bytes) else body.encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        _KA_SEEN[self.path] = {
            "Cookie": self.headers.get("Cookie"),
            "User-Agent": self.headers.get("User-Agent"),
        }
        if self.path == "/redir":
            self._send("", status=302, extra={"Location": "/page"})
        elif self.path == "/redir-cross":
            # Same loopback server, different hostname: the second hop
            # must not carry the first hop's Cookie.
            port = self.server.server_address[1]
            self._send("", status=302,
                       extra={"Location": f"http://localhost:{port}/page"})
        elif self.path == "/missing":
            self._send("nope", status=404)
        else:
            self._send("<html>ok</html>")

    def log_message(self, *args):
        pass


class _QuietServer(http.server.ThreadingHTTPServer):
    # A test that kills a socket must not spam the test log: the
    # traceback printer lives on the SERVER (process_request_thread),
    # not on the handler.
    def handle_error(self, request, client_address):
        pass


class KeepAliveTests(unittest.TestCase):
    def setUp(self):
        _KeepAliveHandler.connections = 0
        _KeepAliveHandler.last_conn = None
        _KA_SEEN.clear()
        fast_fetch._close_http_pool()
        self.httpd = _QuietServer(("127.0.0.1", 0), _KeepAliveHandler)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever,
                                       daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        fast_fetch._close_http_pool()

    def _url(self, path):
        return f"http://127.0.0.1:{self.port}{path}"

    def test_sequential_gets_reuse_one_connection(self):
        s1, _, c1 = fast_fetch.http_get(self._url("/page"), max_retries=0)
        s2, _, c2 = fast_fetch.http_get(self._url("/page"), max_retries=0)
        self.assertEqual((s1, s2), (200, 200))
        self.assertIn("ok", c1)
        self.assertIn("ok", c2)
        self.assertEqual(_KeepAliveHandler.connections, 1)

    def test_redirect_reuses_connection_and_reports_final_url(self):
        status, final, content = fast_fetch.http_get(
            self._url("/redir"), max_retries=0)
        self.assertEqual(status, 200)
        self.assertEqual(final, self._url("/page"))
        self.assertIn("ok", content)
        self.assertEqual(_KeepAliveHandler.connections, 1)

    def test_error_status_keeps_urlopen_semantics(self):
        status, _, content = fast_fetch.http_get(
            self._url("/missing"), max_retries=0)
        self.assertEqual(status, 0)
        self.assertIn("ERROR", content)

    def test_stale_pooled_connection_retried_transparently(self):
        status, _, _ = fast_fetch.http_get(self._url("/page"), max_retries=0)
        self.assertEqual(status, 200)
        self.assertEqual(_KeepAliveHandler.connections, 1)
        # Kill the server side of the pooled socket: the next request
        # must discover the dead socket, drop it, and succeed on a
        # fresh connection — never fail the caller for a stale pool.
        dead = _KeepAliveHandler.last_conn
        dead.shutdown(1)
        dead.close()
        time.sleep(0.2)
        status, _, content = fast_fetch.http_get(
            self._url("/page"), max_retries=0)
        self.assertEqual(status, 200)
        self.assertIn("ok", content)
        self.assertEqual(_KeepAliveHandler.connections, 2)

    def test_cookie_dropped_on_cross_host_redirect(self):
        import urllib.request
        req = urllib.request.Request(
            self._url("/redir-cross"),
            headers={"User-Agent": fast_fetch.USER_AGENT,
                     "Cookie": "cf_clearance=tok-1"})
        # localhost resolves to the same loopback server, but it is a
        # different hostname — the second hop must not carry the cookie.
        resp = fast_fetch._fetch_once(req, timeout=5)
        with resp:
            self.assertEqual(resp.status, 200)
            self.assertEqual(resp.geturl(),
                             f"http://localhost:{self.port}/page")
        self.assertEqual(_KA_SEEN["/redir-cross"]["Cookie"],
                         "cf_clearance=tok-1")
        self.assertIsNone(_KA_SEEN["/page"]["Cookie"])

    def test_pool_bound_evicts_oldest(self):
        made = []

        class _Dummy:
            def __init__(self):
                self.closed = False

            def close(self):
                self.closed = True

        for i in range(fast_fetch._HTTP_POOL_MAX + 1):
            dummy = _Dummy()
            made.append(dummy)
            fast_fetch._pool_checkin(("http", f"h{i}.test", 80), dummy)
        self.assertEqual(fast_fetch._http_pool_total(),
                         fast_fetch._HTTP_POOL_MAX)
        self.assertTrue(made[0].closed)
        self.assertFalse(made[-1].closed)
        fast_fetch._close_http_pool()


class UnwrapWiringTests(unittest.TestCase):
    def test_unwrap_hubcloud_forwards_clearance(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            store = hc.HostClearance(os.path.join(d, "store.db"))
            seen = {}

            def fake_http_get(url, timeout=5, max_retries=1, clearance=None):
                seen["clearance"] = clearance
                return (200, "https://hubcloud.foo/gpdl/1?link="
                        "https%3A%2F%2Fvideo-downloads.googleusercontent.com%2Ff",
                        "")

            with mock.patch.object(fast_fetch, "http_get", fake_http_get):
                direct, svc, _ = fast_fetch.unwrap_hubcloud(
                    "https://hubcloud.foo/gpdl/1", clearance=store)
            self.assertIs(seen["clearance"], store)
            self.assertEqual(svc, "google_cdn")
            self.assertIn("video-downloads.googleusercontent.com", direct)

    def test_unwrap_vcloud_forwards_clearance(self):
        import base64
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            store = hc.HostClearance(os.path.join(d, "store.db"))
            seen = {"n": 0}
            token_url = "https://token.test/page"
            inner = base64.b64encode(
                base64.b64encode(token_url.encode()).decode().encode()
            ).decode()

            def fake_http_get(url, timeout=10, max_retries=2, clearance=None):
                seen["n"] += 1
                self.assertIs(clearance, store)
                if "vcloud" in url:
                    return (200, url, f"<script>var url = atob(atob('{inner}'));</script>")
                return (200, url, "<html>no links</html>")

            with mock.patch.object(fast_fetch, "http_get", fake_http_get):
                out = fast_fetch.unwrap_vcloud(
                    "https://vcloud.beer/abc", clearance=store)
            self.assertEqual(seen["n"], 2)
            self.assertEqual(out[0][2], token_url)

    def test_unwrap_hubcdn_forwards_clearance_to_gateway_fetch(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            store = hc.HostClearance(os.path.join(d, "store.db"))
            seen = {}

            def fake_page(url, timeout, clearance=None):
                seen["clearance"] = clearance
                return ("<html><title>Film - HubCDN</title></html>", "sess=1")

            with mock.patch.object(fast_fetch, "_hubcdn_page", fake_page):
                fast_fetch.unwrap_hubcdn("https://hubcdn.io/cloud/abc",
                                         clearance=store)
            self.assertIs(seen["clearance"], store)

    def test_unwrap_direct_cloud_attaches_stored_clearance(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            store = hc.HostClearance(os.path.join(d, "store.db"))
            store.place("dl.direct-cloud.top", "cf_clearance", "tok-1",
                        user_agent=UA_B, now=NOW)
            seen = {}

            class _FakeOpener:
                def open(self, req, timeout=None):
                    seen["headers"] = dict(req.headers)
                    return _FakeResponse(
                        url="https://dl.direct-cloud.top/d/1",
                        body=b"<html>no uid here</html>")

            with mock.patch.object(fast_fetch.urllib.request, "build_opener",
                                   return_value=_FakeOpener()):
                out = fast_fetch.unwrap_direct_cloud(
                    "https://dl.direct-cloud.top/d/1", clearance=store)
            self.assertEqual(out[1], "direct_cloud")
            self.assertEqual(seen["headers"].get("Cookie"), "cf_clearance=tok-1")
            ua = seen["headers"].get("User-agent") or seen["headers"].get("User-Agent")
            self.assertEqual(ua, UA_B)


if __name__ == "__main__":
    unittest.main(verbosity=2)
