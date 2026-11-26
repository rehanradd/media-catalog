#!/usr/bin/env python3
"""Offline tests for the API-14 retry/timeout caps (2026-11-24).

The latency audit's top-5 fix #4, measured live 2026-11-23/24:
filmu cold 15.9 s was 2x 12.5 s transport timeouts for a dead verdict,
mixdrop has a 51.7 s tail (4x 12.5 s), gdflix burns ~24 s in an untraced
warp hang, and HubCDN ``l.php`` 403s "Too Many Requests" when hammered
from the shared egress IP. What this pins, all stdlib-only, zero network:

* ``host_resolvers.http_request`` fails fast after the FIRST timeout
  (``TIMEOUT_RETRIES = 0``) while a reset/refusal/DNS failure keeps the
  ``TRANSPORT_RETRIES = 3`` budget — the MovieBox edge resets ~2 of 3
  handshakes, so resets must still retry. (The urllib-transport tests
  below target ``_http_request_urllib`` directly: after the keep-alive
  merge ``http_request`` is a dispatcher that routes HTTPS to the pool,
  and the pool does not use the ``_open`` seam those tests mock. The
  dispatcher routes a ``timeout_retries`` opt-in to the urllib
  transport, and the pool applies the same fail-fast — see
  ``KeepAliveFetch``.)
* after 2 consecutive timeouts one host is skipped without a socket
  call for 120 s (the breaker); any HTTP answer resets it at once;
* ``KeepAliveFetch.fetch`` applies the same timeout fail-fast (a dead
  pooled connection still gets one fresh retry — that is a dead
  connection, not a silent host);
* ``fast_fetch.http_get`` returns at once on a timeout instead of
  re-paying it, while resets still retry;
* the WARP tunnel is capped at 5 s (``warp_proxy.WARP_TIMEOUT_SECONDS``)
  on both the http.client path and the gdflix urllib path;
* HubCDN ``l.php`` hops are paced to ~1 req/s, stop at once on a
  429/403 (never retried in a tight loop, 60 s backoff), and stop as
  soon as both assets are banked.

    python3 -S Fast_Fetch/test_retry_caps.py
"""

from __future__ import annotations

import http.client
import io
import os
import sys
import time
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent / "importers"))

import fast_fetch  # noqa: E402
import host_resolvers as hr  # noqa: E402
import warp_proxy  # noqa: E402

R2_ASSET = "https://pub-abc123.r2.dev/movie.mkv"
GOOGLE_ASSET = "https://video-downloads.googleusercontent.com/dl/movie"


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

class FakeResponse:
    """The ``_open`` seam's answer: a context manager with a status."""

    def __init__(self, status=200, body=b"ok", headers=None, url="https://x.test/a"):
        self.status = status
        self._body = body if isinstance(body, bytes) else body.encode()
        self._headers = headers or {}
        self._url = url

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def read(self, n=-1):
        return self._body

    def getheaders(self):
        return list(self._headers.items())

    def geturl(self):
        return self._url


def _http_error(code, headers=None):
    msg = {}
    if headers:
        for key, value in headers.items():
            msg[key] = value
    import email.message
    message = email.message.Message()
    for key, value in (headers or {}).items():
        message.add_header(key, value)
    return urllib.error.HTTPError("https://x.test/a", code, "err", message,
                                  io.BytesIO(b""))


class FakeClock:
    """A clock + sleep recorder: sleeping advances the clock, so pacing
    is proved without waiting on a wall clock."""

    def __init__(self, now=1000.0):
        self.now = now
        self.sleeps: list[float] = []

    def clock(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


def _hubcdn_page(buttons):
    anchors = "\n".join(
        f'<a class="btn dl-trigger" href="{href}">{label}</a>'
        for href, label in buttons)
    return (f"<html><head><title>Movie 1080p - HubCDN</title></head>"
            f"<body>{anchors}</body></html>")


class _HopRecorder:
    def __init__(self, hops):
        self.hops = hops
        self.urls: list[str] = []

    def __call__(self, url, headers):
        self.urls.append(url)
        import re
        stripped = re.sub(r"[&?]dl_token=[^&]*", "", url)
        return self.hops.get(stripped, (404, ""))


# ---------------------------------------------------------------------------
# _is_timeout classification
# ---------------------------------------------------------------------------

class IsTimeoutTests(unittest.TestCase):
    def test_timeout_error_is_a_timeout(self):
        self.assertTrue(hr._is_timeout(TimeoutError("timed out")))
        self.assertTrue(fast_fetch._is_timeout(TimeoutError("timed out")))

    def test_url_error_wrapping_a_timeout_is_a_timeout(self):
        err = urllib.error.URLError(TimeoutError("timed out"))
        self.assertTrue(hr._is_timeout(err))
        self.assertTrue(fast_fetch._is_timeout(err))

    def test_url_error_with_timeout_text_is_a_timeout(self):
        self.assertTrue(hr._is_timeout(urllib.error.URLError("timed out")))

    def test_reset_refusal_and_dns_are_not_timeouts(self):
        self.assertFalse(hr._is_timeout(ConnectionResetError("reset")))
        self.assertFalse(hr._is_timeout(ConnectionRefusedError("refused")))
        self.assertFalse(hr._is_timeout(
            urllib.error.URLError("Name or service not known")))
        self.assertFalse(hr._is_timeout(urllib.error.URLError("boom")))


# ---------------------------------------------------------------------------
# http_request: timeout fail-fast, reset retries, breaker
# ---------------------------------------------------------------------------

class HttpRequestTimeoutTests(unittest.TestCase):
    def setUp(self):
        hr.reset_host_timeout_state()

    def test_timeout_fails_fast_after_one_attempt(self):
        """filmu's 2x 12.5 s tail: the first timeout is the verdict."""
        calls = []

        def fake_open(request, timeout, follow=True):
            calls.append(timeout)
            raise TimeoutError("timed out")

        with mock.patch.object(hr, "_open", fake_open):
            status, body, _headers, _final = hr._http_request_urllib(
                "https://vidly.pro/e/abc", timeout=12.5)
        self.assertEqual(status, 0)
        self.assertEqual(len(calls), 1)

    def test_timeout_retries_param_still_allows_an_opt_in_retry(self):
        calls = []

        def fake_open(request, timeout, follow=True):
            calls.append(1)
            if len(calls) == 1:
                raise TimeoutError("timed out")
            return FakeResponse(200, "ok")

        with mock.patch.object(hr, "_open", fake_open):
            status, _body, _h, _f = hr.http_request(
                "https://vidly.pro/e/abc", timeout=5, timeout_retries=1)
        self.assertEqual(status, 200)
        self.assertEqual(len(calls), 2)

    def test_reset_still_retries_up_to_transport_retries(self):
        """The MovieBox edge resets ~2 of 3 handshakes: a reset is not a
        timeout and keeps the retry budget."""
        calls = []

        def fake_open(request, timeout, follow=True):
            calls.append(1)
            if len(calls) < 3:
                raise ConnectionResetError("reset by peer")
            return FakeResponse(200, "ok")

        with mock.patch.object(hr, "_open", fake_open), \
                mock.patch("time.sleep"):
            status, _body, _h, _f = hr._http_request_urllib(
                "https://themoviebox.xyz/e/abc", timeout=5)
        self.assertEqual(status, 200)
        self.assertEqual(len(calls), 3)

    def test_reset_exhausting_the_budget_returns_status_zero(self):
        calls = []

        def fake_open(request, timeout, follow=True):
            calls.append(1)
            raise ConnectionResetError("reset by peer")

        with mock.patch.object(hr, "_open", fake_open), \
                mock.patch("time.sleep"):
            status, _body, _h, _f = hr._http_request_urllib(
                "https://themoviebox.xyz/e/abc", timeout=5)
        self.assertEqual(status, 0)
        self.assertEqual(len(calls), 4)  # 1 + TRANSPORT_RETRIES

    def test_http_answer_is_never_retried(self):
        calls = []

        def fake_open(request, timeout, follow=True):
            calls.append(1)
            raise _http_error(403)

        with mock.patch.object(hr, "_open", fake_open):
            status, _body, _h, _f = hr._http_request_urllib(
                "https://hubcdn.io/l.php?file=x", timeout=5)
        self.assertEqual(status, 403)
        self.assertEqual(len(calls), 1)

    def test_default_timeout_is_fifteen_seconds(self):
        """25 s -> 15 s (API-14): slowest live cold resolve is 5.9 s, so
        15 s keeps 2.5x headroom while a hung host costs 15 s, not 25 s."""
        self.assertEqual(hr.DEFAULT_TIMEOUT, 15)


class HostTimeoutBreakerTests(unittest.TestCase):
    def setUp(self):
        hr.reset_host_timeout_state()

    def test_two_consecutive_timeouts_trip_the_breaker(self):
        def fake_open(request, timeout, follow=True):
            raise TimeoutError("timed out")

        with mock.patch.object(hr, "_open", fake_open):
            hr._http_request_urllib("https://vidly.pro/e/one", timeout=5)
            hr._http_request_urllib("https://vidly.pro/e/two", timeout=5)
            calls = []
            with mock.patch.object(hr, "_open",
                                   lambda *a, **k: calls.append(1)):
                status, body, _h, _f = hr._http_request_urllib(
                    "https://vidly.pro/e/three", timeout=5)
        self.assertEqual(status, 0)
        self.assertIn("budget exhausted", body)
        self.assertEqual(calls, [])  # no socket call at all

    def test_breaker_is_per_host(self):
        def fake_open(request, timeout, follow=True):
            raise TimeoutError("timed out")

        with mock.patch.object(hr, "_open", fake_open):
            hr._http_request_urllib("https://vidly.pro/e/one", timeout=5)
            hr._http_request_urllib("https://vidly.pro/e/two", timeout=5)
        seen = []

        def ok_open(request, timeout, follow=True):
            seen.append(request.full_url)
            return FakeResponse(200, "ok")

        with mock.patch.object(hr, "_open", ok_open):
            status, _b, _h, _f = hr._http_request_urllib(
                "https://mixdrop.to/e/abc", timeout=5)
        self.assertEqual(status, 200)
        self.assertEqual(len(seen), 1)

    def test_http_answer_resets_the_breaker_count(self):
        calls = []

        def flaky_open(request, timeout, follow=True):
            calls.append(1)
            if len(calls) == 1:
                raise TimeoutError("timed out")
            raise _http_error(404)

        with mock.patch.object(hr, "_open", flaky_open):
            hr._http_request_urllib("https://vidly.pro/e/one", timeout=5)
            hr._http_request_urllib("https://vidly.pro/e/two", timeout=5)
        # One timeout + one 404 answer: the count is back to zero, so a
        # further timeout is attempted (not skipped) and then fails fast.
        attempts = []

        def timeout_open(request, timeout, follow=True):
            attempts.append(1)
            raise TimeoutError("timed out")

        with mock.patch.object(hr, "_open", timeout_open):
            status, _b, _h, _f = hr._http_request_urllib(
                "https://vidly.pro/e/three", timeout=5)
        self.assertEqual(status, 0)
        self.assertEqual(len(attempts), 1)

    def test_breaker_lapses_after_the_cooldown(self):
        hr._HOST_TIMEOUTS["vidly.pro"] = (
            2, time.monotonic() - hr.HOST_TIMEOUT_COOLDOWN_SECONDS - 1)
        seen = []

        def ok_open(request, timeout, follow=True):
            seen.append(1)
            return FakeResponse(200, "ok")

        with mock.patch.object(hr, "_open", ok_open):
            status, _b, _h, _f = hr._http_request_urllib(
                "https://vidly.pro/e/abc", timeout=5)
        self.assertEqual(status, 200)
        self.assertEqual(len(seen), 1)


class FilmuInheritsTheCapTests(unittest.TestCase):
    """The unwrapper itself needs no change: it calls http_request with
    the defaults, so a timing-out filmu page now costs one timeout and
    answers a verdict instead of 2-4x the timeout."""

    def setUp(self):
        hr.reset_host_timeout_state()

    def test_filmu_page_timeout_is_one_attempt_and_a_verdict(self):
        calls = []

        def fake_open(request, timeout, follow=True):
            calls.append(1)
            raise TimeoutError("timed out")

        with mock.patch.object(hr, "_open", fake_open):
            res = hr.unwrap_filmu("https://vidly.pro/movie/550",
                                  fetch=hr._http_request_urllib,
                                  verify=False)
        self.assertEqual(len(calls), 1)
        self.assertEqual(res["status"], "unknown")
        self.assertIn("filmu page HTTP 0", res["notes"])


# ---------------------------------------------------------------------------
# KeepAliveFetch: timeout fail-fast, dead-conn retry, breaker
# ---------------------------------------------------------------------------

class _FakeResp:
    def __init__(self, status=200, body=b"ok"):
        self.status = status
        self._body = body

    def getheaders(self):
        return []

    def read(self, n=-1):
        return self._body


class _ScriptedConn:
    """A connection whose request/getresponse follow a script."""

    def __init__(self, actions):
        self._actions = list(actions)
        self.requests = []

    def request(self, method, target, body=None, headers=None):
        self.requests.append((method, target))
        action = self._actions.pop(0) if self._actions else ("resp", _FakeResp())
        if action[0] == "raise":
            raise action[1]

    def getresponse(self):
        action = self._actions.pop(0) if self._actions else ("resp", _FakeResp())
        if action[0] == "raise":
            raise action[1]
        return action[1]

    def close(self):
        pass


class KeepAliveTimeoutTests(unittest.TestCase):
    def setUp(self):
        hr.reset_host_timeout_state()

    def _fetch_with(self, conns):
        made = []

        def factory(host, port, https, timeout):
            conn = conns[len(made)]
            made.append(conn)
            return conn

        pool = hr.KeepAliveFetch(connection_factory=factory)
        return pool, made

    def test_timeout_is_not_retried_on_a_fresh_connection(self):
        pool, made = self._fetch_with([
            _ScriptedConn([("raise", TimeoutError("timed out"))]),
            _ScriptedConn([("resp", _FakeResp(200, b"ok"))]),
        ])
        status, _body, _h, _f = pool.fetch("https://drive.test/x", timeout=5)
        self.assertEqual(status, 0)
        self.assertEqual(len(made), 1)  # no fresh-connection re-pay

    def test_dead_pooled_connection_still_gets_one_fresh_retry(self):
        pool, made = self._fetch_with([
            _ScriptedConn([("raise",
                            http.client.RemoteDisconnected("closed"))]),
            _ScriptedConn([("resp", _FakeResp(200, b"ok"))]),
        ])
        status, body, _h, _f = pool.fetch("https://drive.test/x", timeout=5)
        self.assertEqual(status, 200)
        self.assertEqual(body, b"ok")
        self.assertEqual(len(made), 2)

    def test_two_timeouts_trip_the_breaker_for_keepalive_too(self):
        def factory(host, port, https, timeout):
            return _ScriptedConn([("raise", TimeoutError("timed out"))])

        pool = hr.KeepAliveFetch(connection_factory=factory)
        pool.fetch("https://drive.test/one", timeout=5)
        pool.fetch("https://drive.test/two", timeout=5)
        made = []
        pool2 = hr.KeepAliveFetch(
            connection_factory=lambda h, p, s, t: made.append(1) or
            _ScriptedConn([("resp", _FakeResp(200, b"ok"))]))
        status, body, _h, _f = pool2.fetch("https://drive.test/three",
                                          timeout=5)
        self.assertEqual(status, 0)
        self.assertIn("budget exhausted", body)
        self.assertEqual(made, [])


# ---------------------------------------------------------------------------
# fast_fetch.http_get: timeout fail-fast, reset retries
# ---------------------------------------------------------------------------

class HttpGetTimeoutTests(unittest.TestCase):
    def _fake_200(self, url="https://gdflix.test/file/abc"):
        resp = mock.MagicMock()
        resp.__enter__.return_value = resp
        resp.status = 200
        resp.geturl.return_value = url
        resp.headers.get_content_charset.return_value = "utf-8"
        resp.read.return_value = b"<html></html>"
        return resp

    def test_timeout_returns_at_once_without_a_retry(self):
        with mock.patch.dict(os.environ, {}, clear=False), \
                mock.patch.object(fast_fetch.warp_proxy,
                                  "urllib_proxy_handler",
                                  return_value=None), \
                mock.patch.object(fast_fetch.urllib.request, "urlopen",
                                  side_effect=TimeoutError("timed out")) as opener:
            status, _final, body = fast_fetch.http_get(
                "https://gdflix.test/file/abc", timeout=10, max_retries=2)
        self.assertEqual(status, 0)
        self.assertTrue(body.startswith("ERROR:"))
        self.assertEqual(opener.call_count, 1)

    def test_reset_still_retries_and_then_succeeds(self):
        with mock.patch.object(fast_fetch.warp_proxy,
                               "urllib_proxy_handler",
                               return_value=None), \
                mock.patch.object(fast_fetch.urllib.request, "urlopen",
                                  side_effect=[ConnectionResetError("reset"),
                                               self._fake_200()]) as opener, \
                mock.patch("time.sleep"):
            status, _final, _body = fast_fetch.http_get(
                "https://gdflix.test/file/abc", timeout=10, max_retries=2)
        self.assertEqual(status, 200)
        self.assertEqual(opener.call_count, 2)


# ---------------------------------------------------------------------------
# WARP tunnel cap: 5 s
# ---------------------------------------------------------------------------

class WarpCapTests(unittest.TestCase):
    def test_capped_timeout_is_a_noop_when_the_proxy_is_off(self):
        env = {k: v for k, v in os.environ.items()
               if k != "RADD_WARP_PROXY"}
        with mock.patch.dict(os.environ, env, clear=True):
            self.assertEqual(warp_proxy.capped_timeout(10), 10)
            self.assertEqual(warp_proxy.capped_timeout(25), 25)

    def test_capped_timeout_is_five_seconds_when_the_proxy_is_on(self):
        with mock.patch.dict(os.environ, {"RADD_WARP_PROXY": "1"}):
            self.assertEqual(warp_proxy.capped_timeout(10), 5)
            self.assertEqual(warp_proxy.capped_timeout(25), 5)
            self.assertEqual(warp_proxy.capped_timeout(3), 3)

    def test_make_connection_caps_the_tunnel_socket_at_five(self):
        with mock.patch.dict(os.environ, {"RADD_WARP_PROXY": "1"}):
            conn = warp_proxy.make_connection("example.com", 443, True, 25)
        try:
            self.assertEqual(conn.timeout, 5)
        finally:
            conn.close()

    def test_make_connection_keeps_a_short_timeout_unchanged(self):
        with mock.patch.dict(os.environ, {"RADD_WARP_PROXY": "1"}):
            conn = warp_proxy.make_connection("example.com", 443, True, 3)
        try:
            self.assertEqual(conn.timeout, 3)
        finally:
            conn.close()


class GdflixWarpCapTests(unittest.TestCase):
    FILE_URL = "https://new10.gdflix.io/file/N4NC704WLKFJAKL"

    def _landing(self):
        return ("<html><head><title>GDFlix | Movie.mkv</title></head>"
                "<body><div>No buttons here</div></body></html>")

    def test_gdflix_landing_fetch_is_capped_at_five_under_warp(self):
        getter = mock.Mock(
            return_value=(200, self.FILE_URL, self._landing()))
        with mock.patch.dict(os.environ, {"RADD_WARP_PROXY": "1"}), \
                mock.patch.object(fast_fetch, "http_get", getter):
            fast_fetch.unwrap_gdflix(self.FILE_URL)
        self.assertEqual(getter.call_args.kwargs["timeout"], 5)

    def test_gdflix_landing_fetch_keeps_ten_seconds_direct(self):
        getter = mock.Mock(
            return_value=(200, self.FILE_URL, self._landing()))
        env = {k: v for k, v in os.environ.items()
               if k != "RADD_WARP_PROXY"}
        with mock.patch.dict(os.environ, env, clear=True), \
                mock.patch.object(fast_fetch, "http_get", getter):
            fast_fetch.unwrap_gdflix(self.FILE_URL)
        self.assertEqual(getter.call_args.kwargs["timeout"], 10)

    def test_gdflix_redirect_hop_is_capped_at_five_under_warp(self):
        err = urllib.error.HTTPError(
            "https://busycdn.test/x", 302, "Found",
            _http_error(302).headers, io.BytesIO(b""))
        opener = mock.Mock()
        opener.open.side_effect = err
        with mock.patch.dict(os.environ, {"RADD_WARP_PROXY": "1"}), \
                mock.patch.object(fast_fetch.urllib.request, "build_opener",
                                  return_value=opener):
            fast_fetch._gdflix_redirect_location("https://busycdn.test/x",
                                                 timeout=10)
        self.assertEqual(opener.open.call_args.kwargs["timeout"], 5)


# ---------------------------------------------------------------------------
# HubCDN l.php pacing + back-off
# ---------------------------------------------------------------------------

class HubcdnPacerTests(unittest.TestCase):
    def test_second_hop_waits_for_the_one_second_interval(self):
        fake = FakeClock()
        pacer = fast_fetch.HubcdnPacer(sleep=fake.sleep, clock=fake.clock)
        pacer.wait()
        pacer.wait()
        self.assertEqual(fake.sleeps, [1.0])

    def test_no_wait_when_the_interval_already_elapsed(self):
        fake = FakeClock()
        pacer = fast_fetch.HubcdnPacer(sleep=fake.sleep, clock=fake.clock)
        pacer.wait()
        fake.now += 5.0
        pacer.wait()
        self.assertEqual(fake.sleeps, [])

    def test_403_arms_a_sixty_second_backoff(self):
        fake = FakeClock()
        pacer = fast_fetch.HubcdnPacer(sleep=fake.sleep, clock=fake.clock)
        self.assertFalse(pacer.is_limited())
        pacer.note(403)
        self.assertTrue(pacer.is_limited())
        fake.now += 59.0
        self.assertTrue(pacer.is_limited())
        fake.now += 2.0
        self.assertFalse(pacer.is_limited())

    def test_429_arms_the_backoff_but_200_and_302_do_not(self):
        fake = FakeClock()
        pacer = fast_fetch.HubcdnPacer(sleep=fake.sleep, clock=fake.clock)
        pacer.note(200)
        pacer.note(302)
        pacer.note(404)
        self.assertFalse(pacer.is_limited())
        pacer.note(429)
        self.assertTrue(pacer.is_limited())

    def test_reset_clears_pacing_and_backoff(self):
        fake = FakeClock()
        pacer = fast_fetch.HubcdnPacer(sleep=fake.sleep, clock=fake.clock)
        pacer.wait()
        pacer.note(403)
        pacer.reset()
        self.assertFalse(pacer.is_limited())
        pacer.wait()
        self.assertEqual(fake.sleeps, [])


class HubcdnUnwrapPacingTests(unittest.TestCase):
    def _unwrap(self, hops, buttons, pacer):
        page = _hubcdn_page(buttons)
        recorder = _HopRecorder(hops)
        res = fast_fetch.unwrap_hubcdn(
            "https://hubcdn.io/cloud/abc123",
            _fetch=lambda u: (page, "session=xyz"), _hop=recorder,
            _pacer=pacer)
        return res, recorder

    def _pacer(self):
        fake = FakeClock()
        return fast_fetch.HubcdnPacer(sleep=fake.sleep, clock=fake.clock), fake

    def test_both_assets_stop_the_loop_after_two_hops(self):
        """The old loop hopped all 8 buttons (4 links x 2 labels) even
        after both slots were full — pure limiter budget burned."""
        pacer, fake = self._pacer()
        buttons = [
            ("/l.php?id=1", "480p [500MB]"),
            ("/l.php?id=2", "720p [1.2GB]"),
            ("/l.php?id=3", "1080p [4.2GB]"),
            ("/l.php?id=4", "2160p [8.1GB]"),
            ("/l.php?id=5", "480p [500MB]"),
            ("/l.php?id=6", "720p [1.2GB]"),
            ("/l.php?id=7", "1080p [4.2GB]"),
            ("/l.php?id=8", "2160p [8.1GB]"),
        ]
        hops = {
            "https://hubcdn.io/l.php?id=1": (302, R2_ASSET),
            "https://hubcdn.io/l.php?id=2": (302, GOOGLE_ASSET),
            "https://hubcdn.io/l.php?id=3": (302, R2_ASSET),
            "https://hubcdn.io/l.php?id=4": (302, GOOGLE_ASSET),
            "https://hubcdn.io/l.php?id=5": (302, R2_ASSET),
            "https://hubcdn.io/l.php?id=6": (302, GOOGLE_ASSET),
            "https://hubcdn.io/l.php?id=7": (302, R2_ASSET),
            "https://hubcdn.io/l.php?id=8": (302, GOOGLE_ASSET),
        }
        res, rec = self._unwrap(hops, buttons, pacer)
        self.assertTrue(res["ok"], res)
        self.assertEqual(res["r2_url"], R2_ASSET)
        self.assertEqual(res["google_url"], GOOGLE_ASSET)
        self.assertEqual(len(rec.urls), 2)
        self.assertEqual(fake.sleeps, [1.0])  # 1st hop free, 2nd paced

    def test_403_stops_hopping_at_once_and_is_never_retried(self):
        pacer, _fake = self._pacer()
        buttons = [("/l.php?id=1", "480p [500MB]"),
                   ("/l.php?id=1", "480p [500MB]")]
        hops = {"https://hubcdn.io/l.php?id=1": (403, "")}
        res, rec = self._unwrap(hops, buttons, pacer)
        self.assertFalse(res["ok"])
        self.assertEqual(res["error"], "hubcdn_rate_limited")
        self.assertEqual(len(rec.urls), 1)
        self.assertTrue(pacer.is_limited())

    def test_backoff_blocks_the_next_unwrap_entirely(self):
        pacer, _fake = self._pacer()
        buttons = [("/l.php?id=1", "480p [500MB]")]
        hops = {"https://hubcdn.io/l.php?id=1": (403, "")}
        self._unwrap(hops, buttons, pacer)
        res, rec = self._unwrap(hops, buttons, pacer)
        self.assertFalse(res["ok"])
        self.assertEqual(res["error"], "hubcdn_rate_limited")
        self.assertEqual(len(rec.urls), 0)  # no hop spent inside backoff

    def test_429_after_one_asset_keeps_the_banked_asset(self):
        pacer, _fake = self._pacer()
        buttons = [("/l.php?id=1", "480p [500MB]"),
                   ("/l.php?id=2", "1080p [4.2GB]")]
        hops = {
            "https://hubcdn.io/l.php?id=1": (302, R2_ASSET),
            "https://hubcdn.io/l.php?id=2": (429, ""),
        }
        res, rec = self._unwrap(hops, buttons, pacer)
        self.assertTrue(res["ok"], res)
        self.assertEqual(res["r2_url"], R2_ASSET)
        self.assertIsNone(res["google_url"])
        self.assertNotIn("error", res)
        self.assertEqual(len(rec.urls), 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
