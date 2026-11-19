#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Offline tests for H4 R10 — network-intercept fallback.

Two rules, both proven here without touching the network:

1. A 5xx is an intermediary's answer, not the link's death
   (``link_state.classify``): 500/502/503/504/522/524 -> ``expired``
   (re-resolved, never quarantined), while 404/410/451 stay ``dead`` and
   transport failures stay ``unverified``. The serve side already agrees
   (``serve_verify.classify_failure`` calls >=500 "flaky").

2. A HEAD refused by an intermediary gets one bounded second opinion
   (``link_health.probe`` + ``fast_probe._needs_get_fallback``): HEAD
   403/429/5xx falls through to a single ranged GET with the same
   headers/UA/timeout; the GET verdict replaces HEAD's when it earns one.
   HEAD 404 stays immediate, and a transport failure never triggers a GET.

Probe tests hit a ``ThreadingHTTPServer`` on 127.0.0.1 with
``allow_private=True`` (which the suite also proves is mandatory in
``test_link_health``); sweep tests drive ``fast_probe._probe_host`` through
a fake connection. Run from the repo root:
``python -S Fast_Fetch/test_network_intercept.py``.
"""

import sys
import threading
import time
import unittest
import unittest.mock
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

BASE_DIR = Path(__file__).parent.resolve()
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import fast_probe
import link_health as lh
import link_state
import serve_verify


# ── rule 1: a 5xx is expired, never dead ─────────────────────────────────────

class ClassifyTests(unittest.TestCase):
    """The H4 R10 verdict table: gateway answers are not dead links."""

    def _failed(self, status):
        return {"ok": False, "error": "http_%d" % status,
                "http_status": status, "checked_at": 0.0}

    def test_gateway_statuses_are_expired_not_dead(self):
        # Every 5xx the edge can answer: gateway, bad gateway, unavailable,
        # timeout, Cloudflare's connect-timeout and origin-timeout.
        for status in (500, 502, 503, 504, 522, 524):
            with self.subTest(status=status):
                self.assertEqual(link_state.classify(self._failed(status)),
                                 "expired")

    def test_gone_statuses_stay_dead(self):
        for status in (404, 410, 451):
            with self.subTest(status=status):
                self.assertEqual(link_state.classify(self._failed(status)),
                                 "dead")

    def test_other_4xx_stay_dead(self):
        # A client error that is not a token/throttle is still the link's.
        for status in (400, 405, 409):
            with self.subTest(status=status):
                self.assertEqual(link_state.classify(self._failed(status)),
                                 "dead")

    def test_token_and_throttle_stay_expired(self):
        for status in (401, 403, 429):
            with self.subTest(status=status):
                self.assertEqual(link_state.classify(self._failed(status)),
                                 "expired")

    def test_transport_stays_unverified(self):
        for error in ("timeout", "dns", "tls", "conn_reset", "conn_error"):
            with self.subTest(error=error):
                payload = {"ok": False, "error": error, "http_status": 0,
                           "checked_at": 0.0}
                self.assertEqual(link_state.classify(payload), "unverified")

    def test_sweep_and_serve_agree_a_5xx_is_not_death(self):
        # The two surfaces share one vocabulary: the sweep says expired,
        # the serve side says flaky — neither says dead, so a gateway
        # blip can never quarantine a link from either direction.
        for status in (500, 502, 503):
            with self.subTest(status=status):
                self.assertEqual(link_state.classify(self._failed(status)),
                                 "expired")
                self.assertEqual(
                    serve_verify.classify_failure(status, ""), "flaky")
        self.assertEqual(serve_verify.classify_failure(404, ""), "hard")

    def test_expired_projects_as_expired_not_dead(self):
        # state_for is what the projection reads: expired must reach
        # cat_links as "expired" (re-resolved, never a quarantine
        # candidate), not as "dead".
        self.assertEqual(link_state.state_for("expired"), "expired")
        self.assertEqual(link_state.state_for("dead"), "dead")


# ── rule 2a: the fallback decision (pure) ───────────────────────────────────

class NeedsGetFallbackTests(unittest.TestCase):
    """``fast_probe._needs_get_fallback``: the H4 R10 trigger rows."""

    def test_intermediary_refusal_triggers_on_a_plain_host(self):
        # Any host now, not just the two listed liars.
        for status in (403, 429, 500, 502, 503, 524):
            with self.subTest(status=status):
                self.assertTrue(fast_probe._needs_get_fallback(
                    "plain.example", status, "", "expired"))

    def test_gone_and_active_never_trigger(self):
        self.assertFalse(fast_probe._needs_get_fallback(
            "plain.example", 404, "", "dead"))
        self.assertFalse(fast_probe._needs_get_fallback(
            "plain.example", 410, "", "dead"))
        self.assertFalse(fast_probe._needs_get_fallback(
            "plain.example", 200, "", "active"))

    def test_transport_never_triggers(self):
        self.assertFalse(fast_probe._needs_get_fallback(
            "plain.example", 0, "timeout", None))
        self.assertFalse(fast_probe._needs_get_fallback(
            "plain.example", 0, "dns", None))

    def test_method_refusal_still_triggers(self):
        self.assertTrue(fast_probe._needs_get_fallback(
            "plain.example", 405, "", None))


# ── rule 2b: the intercept at the HTTP boundary ─────────────────────────────

_MP4_BODY = b"\x00\x00\x00\x18ftypmp42" + b"m" * 4096

# path -> (HEAD status, GET status); GET serves the mp4 body on 200.
_BEHAVIOUR = {
    "/403-then-200": (403, 200),
    "/429-then-200": (429, 200),
    "/503-then-200": (503, 200),
    "/503-then-503": (503, 503),
    "/404": (404, 404),
    "/200": (200, 200),
}


class _Handler(BaseHTTPRequestHandler):
    """Answers HEAD/GET from _BEHAVIOUR; counts every hit per method."""

    protocol_version = "HTTP/1.1"
    hits = {}

    def log_message(self, *args):
        pass

    def _count(self, method):
        key = (method, self.path)
        _Handler.hits[key] = _Handler.hits.get(key, 0) + 1

    def _serve(self, method):
        self._count(method)
        if self.path == "/hang":
            time.sleep(3.0)
            return
        head_status, get_status = _BEHAVIOUR[self.path]
        status = head_status if method == "HEAD" else get_status
        body = _MP4_BODY if status == 200 else b""
        self.send_response(status)
        self.send_header("Content-Type", "video/mp4"
                         if status == 200 else "text/html")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if method == "GET" and body:
            self.wfile.write(body)

    def do_HEAD(self):
        self._serve("HEAD")

    def do_GET(self):
        self._serve("GET")


_SERVER = None
_BASE = ""


def setUpModule():
    global _SERVER, _BASE
    _SERVER = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    threading.Thread(target=_SERVER.serve_forever, daemon=True).start()
    _BASE = "http://127.0.0.1:%d" % _SERVER.server_address[1]


def tearDownModule():
    if _SERVER is not None:
        _SERVER.shutdown()
        _SERVER.server_close()


class ProbeInterceptTests(unittest.TestCase):
    """``link_health.probe`` retries a refused HEAD with one ranged GET."""

    def _probe(self, path, timeout=5.0):
        _Handler.hits.clear()
        return lh.probe(_BASE + path, timeout=timeout,
                        allow_private=True)

    def _hits(self, path):
        return (_Handler.hits.get(("HEAD", path), 0),
                _Handler.hits.get(("GET", path), 0))

    def test_head_403_then_get_200_is_ok(self):
        # The vcloud.beer case, generalised: HEAD 403, GET 200.
        result = self._probe("/403-then-200")
        self.assertTrue(result.ok)
        self.assertEqual(self._hits("/403-then-200"), (1, 1))

    def test_head_503_then_get_200_is_ok(self):
        result = self._probe("/503-then-200")
        self.assertTrue(result.ok)
        self.assertEqual(self._hits("/503-then-200"), (1, 1))

    def test_head_429_then_get_200_is_ok(self):
        result = self._probe("/429-then-200")
        self.assertTrue(result.ok)
        self.assertEqual(self._hits("/429-then-200"), (1, 1))

    def test_head_503_then_get_503_keeps_the_get_verdict(self):
        result = self._probe("/503-then-503")
        self.assertFalse(result.ok)
        self.assertEqual(result.http_status, 503)
        self.assertEqual(result.error, "http_503")
        self.assertEqual(self._hits("/503-then-503"), (1, 1))

    def test_head_404_gets_no_second_opinion(self):
        result = self._probe("/404")
        self.assertFalse(result.ok)
        self.assertEqual(result.http_status, 404)
        self.assertEqual(result.error, "http_404")
        self.assertEqual(self._hits("/404"), (1, 0))

    def test_head_200_gets_no_get(self):
        # probe() always samples the body (phase 2) when allow_download is
        # on — the GET here is the measurement, not a retry: HEAD 200
        # needs no second opinion, so the HEAD evidence stands.
        result = self._probe("/200")
        self.assertTrue(result.ok)
        self.assertEqual(self._hits("/200"), (1, 1))

    def test_head_timeout_gets_no_get(self):
        result = self._probe("/hang", timeout=0.5)
        self.assertFalse(result.ok)
        self.assertEqual(result.error, "timeout")
        self.assertEqual(self._hits("/hang"), (1, 0))

    def test_budget_is_one_head_plus_one_get(self):
        for path in ("/403-then-200", "/503-then-503", "/404", "/200"):
            self._probe(path)
            head, get = self._hits(path)
            self.assertLessEqual(head + get, 2, path)


# ── rule 2c: the intercept inside the sweep chain ────────────────────────────

class _FakeResponse:
    def __init__(self, status):
        self.status = status
        self.will_close = False

    def getheaders(self):
        return []

    def read(self, *args):
        return b""


import socket as _socket


def _public_dns(test):
    """Decorator: stub getaddrinfo so the SSRF guard sees a public address.

    This sandbox's resolver answers any name with a CGNAT address, which
    the guard (rightly) treats as private — the same stub test_fast_probe
    uses. The fake connection answers the HTTP itself; DNS is never real.
    """
    import functools

    _fake = [(_socket.AF_INET, _socket.SOCK_STREAM, 6, "",
              ("93.184.216.34", 0))]  # public IP; doc ranges count as private

    @functools.wraps(test)
    def wrapper(self, *args, **kwargs):
        with unittest.mock.patch("socket.getaddrinfo", return_value=_fake):
            return test(self, *args, **kwargs)

    return wrapper


class _FakeConn:
    """http.client-shaped stub: answers from a {(method, path): status} map."""

    def __init__(self, mapping, log):
        self._mapping = mapping
        self._log = log
        self._last = ("HEAD", "/")

    def request(self, method, target, headers=None):
        self._last = (method, target)
        self._log.append(("request", method, target))

    def getresponse(self):
        status = self._mapping.get(self._last, 200)
        return _FakeResponse(status)

    def close(self):
        pass


class SweepInterceptTests(unittest.TestCase):
    """``fast_probe._probe_host``: the GET verdict replaces HEAD's."""

    def _run(self, mapping):
        log = []
        factory = lambda host, port, https, timeout: _FakeConn(mapping, log)
        registry = unittest.mock.Mock()
        registry.record_redirect.return_value = {"count": 0, "applied": False}
        groups = [{"url": "http://plain.example/x.mp4", "row_ids": [1],
                   "stored": None}]
        with unittest.mock.patch.object(fast_probe, "host_registry",
                                        registry):
            stats = fast_probe._probe_host(
                "plain.example", groups, session_factory=factory,
                timeout=5.0, spacing_s=0.0,
                headers=fast_probe.probe_headers(), out_queue=None)
        return stats, log

    @_public_dns
    def test_head_503_then_get_200_is_active(self):
        stats, log = self._run({("HEAD", "/x.mp4"): 503,
                                ("GET", "/x.mp4"): 200})
        self.assertEqual(stats["results"]["http://plain.example/x.mp4"],
                         ("active", 200, ""))
        self.assertEqual(stats["get_fallbacks"], 1)

    @_public_dns
    def test_head_503_then_get_503_is_expired_not_dead(self):
        stats, log = self._run({("HEAD", "/x.mp4"): 503,
                                ("GET", "/x.mp4"): 503})
        # The sweep stores the chain error, which is "" for any answered
        # status — the verdict + http_status carry the meaning.
        self.assertEqual(stats["results"]["http://plain.example/x.mp4"],
                         ("expired", 503, ""))
        self.assertEqual(stats["get_fallbacks"], 1)

    @_public_dns
    def test_head_404_on_a_plain_host_stays_dead_with_no_get(self):
        stats, log = self._run({("HEAD", "/x.mp4"): 404,
                                ("GET", "/x.mp4"): 200})
        self.assertEqual(stats["results"]["http://plain.example/x.mp4"],
                         ("dead", 404, ""))
        self.assertEqual(stats["get_fallbacks"], 0)
        self.assertEqual([e for e in log if e[1] == "GET"], [])


if __name__ == "__main__":
    unittest.main()
