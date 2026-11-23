#!/usr/bin/env python3
"""
Offline tests for keep-alive in http_request (host_resolvers.py) —
resolver-latency audit fix 3, 2026-11-23.

http_request runs HTTP(S) over the shared per-host http.client pool
instead of a fresh urllib connection per call. These tests drive it
through fake connections (no live socket, no database, no pip) and
prove: one connection is reused across calls, a stale pooled
connection is dropped and the request retried on a fresh one, the
response shape / headers / method / redirect behaviour match what the
urllib path used to return, the retry budget is unchanged, and
non-HTTP(S) URLs still fall back to the urllib path.

The urllib fallback itself is covered by TestTransport in
test_host_resolvers.py (retargeted at _http_request_urllib); the pool
primitives are covered by KeepAliveFetchTests in
test_hubcloud_oneround.py. This file covers http_request's delegation.
"""

from __future__ import annotations

import http.client
import socket
import sys
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
# Fast_Fetch/ too: host_resolvers imports warp_proxy from there.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import host_resolvers as hr  # noqa: E402


class _NoLiveSocket:
    def __init__(self, *a, **k):
        raise AssertionError("test attempted a live socket")


socket.socket = _NoLiveSocket  # type: ignore[attr-defined]


class _FakeResp:
    """An http.client response stand-in that answers from a script."""

    def __init__(self, status, body, headers=None):
        self.status = status
        self._body = body
        self._headers = headers or {}

    def read(self, amt=None):
        if amt is None:
            out, self._body = self._body, b""
            return out
        out, self._body = self._body[:amt], self._body[amt:]
        return out

    def getheaders(self):
        return list(self._headers.items())


class _FakeConn:
    """An http.client connection stand-in that answers from a script.

    ``script`` maps (host, target) to (status, body, headers).
    ``fail_budget`` is a shared [n]: while positive, each request
    raises OSError and decrements it, so a flaky edge can be scripted
    across the fresh connections the pool opens on retry.
    ``fail_next`` is a one-shot exception for the next request only
    (a stale pooled connection).
    """

    def __init__(self, script, fail_budget=None):
        self.script = script
        self.fail_budget = fail_budget
        self.requests = []  # (method, target, body, headers)
        self.closed = False
        self.fail_next = None
        self.host = ""

    def request(self, method, target, body=None, headers=None):
        self.requests.append((method, target, body, dict(headers or {})))
        if self.fail_next is not None:
            exc, self.fail_next = self.fail_next, None
            raise exc
        if self.fail_budget is not None and self.fail_budget[0] > 0:
            self.fail_budget[0] -= 1
            raise OSError("TLS reset")

    def getresponse(self):
        _method, target, _body, _headers = self.requests[-1]
        status, body, headers = self.script.get(
            (self.host, target), (200, b"ok", {}))
        return _FakeResp(status, body, headers)

    def close(self):
        self.closed = True


class _FakeUrllibResp:
    """A urllib response stand-in for the non-HTTP fallback test."""

    def __init__(self, status=200, body=b"ok", headers=None,
                 url="ftp://x.test/f"):
        self.status = status
        self._body = body
        self._headers = headers or {}
        self._url = url

    def read(self, size=-1):
        return self._body if size < 0 else self._body[:size]

    def getheaders(self):
        return list(self._headers.items())

    def geturl(self):
        return self._url

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class TestHttpRequestKeepAlive(unittest.TestCase):
    def _pool(self, script, fail_budget=None, max_connections=8):
        """A fresh pool with a fake factory, plus the conns it builds."""
        conns = []

        def factory(host, port, https, timeout):
            conn = _FakeConn(script, fail_budget=fail_budget)
            conn.host = host
            conn.timeout = timeout
            conns.append(conn)
            return conn

        pool = hr.KeepAliveFetch(connection_factory=factory,
                                 max_connections=max_connections)
        return pool, conns

    def test_reuses_one_connection_across_calls(self):
        script = {("example.com", "/a"): (200, b"a", {}),
                  ("example.com", "/b"): (200, b"b", {})}
        pool, conns = self._pool(script)
        try:
            with mock.patch.object(hr, "_KEEPALIVE", pool):
                s1, b1, _, _ = hr.http_request("https://example.com/a")
                s2, b2, _, _ = hr.http_request("https://example.com/b")
            self.assertEqual((s1, b1), (200, b"a"))
            self.assertEqual((s2, b2), (200, b"b"))
            self.assertEqual(len(conns), 1,
                             "second GET must reuse the pooled conn")
            self.assertEqual(len(conns[0].requests), 2)
            self.assertEqual(pool.pooled_connections, 1)
        finally:
            pool.close()

    def test_each_host_gets_its_own_connection(self):
        pool, conns = self._pool({("one.example", "/"): (200, b"1", {}),
                                  ("two.example", "/"): (200, b"2", {})})
        try:
            with mock.patch.object(hr, "_KEEPALIVE", pool):
                hr.http_request("https://one.example/")
                hr.http_request("https://two.example/")
            self.assertEqual(len(conns), 2)
            self.assertEqual(pool.pooled_connections, 2)
        finally:
            pool.close()

    def test_stale_connection_is_dropped_and_retried_on_a_fresh_one(self):
        # The audit's stale-connection guard: a server-closed idle
        # connection must not become the verdict — the request is
        # re-opened once on a fresh connection.
        pool, conns = self._pool({("example.com", "/a"): (200, b"ok", {})})
        try:
            with mock.patch.object(hr, "_KEEPALIVE", pool):
                status, body, _, _ = hr.http_request(
                    "https://example.com/a")
            self.assertEqual((status, body), (200, b"ok"))
            self.assertEqual(len(conns), 1)
            conns[0].fail_next = http.client.RemoteDisconnected(
                "server closed the idle connection")
            with mock.patch.object(hr, "_KEEPALIVE", pool), \
                    mock.patch.object(time, "sleep"):
                status, body, _, _ = hr.http_request(
                    "https://example.com/a")
            self.assertEqual((status, body), (200, b"ok"))
            self.assertEqual(len(conns), 2,
                             "retry must use a fresh connection")
            self.assertTrue(conns[0].closed, "stale conn must be closed")
            self.assertEqual(pool.pooled_connections, 1)
        finally:
            pool.close()

    def test_response_shape_headers_and_method_pass_through(self):
        pool, conns = self._pool({("example.com", "/a"): (
            200, b"ok", {"Content-Type": "text/plain"})})
        try:
            with mock.patch.object(hr, "_KEEPALIVE", pool):
                status, body, headers, final = hr.http_request(
                    "https://example.com/a", headers={"Range": "bytes=0-1"},
                    method="GET", timeout=5)
            self.assertEqual((status, body, final),
                             (200, b"ok", "https://example.com/a"))
            self.assertEqual(headers["content-type"], "text/plain")
            method, target, sent_body, sent_headers = conns[0].requests[0]
            self.assertEqual((method, target), ("GET", "/a"))
            self.assertIsNone(sent_body)
            self.assertEqual(sent_headers.get("User-Agent"), hr.USER_AGENT)
            self.assertEqual(sent_headers.get("Range"), "bytes=0-1")
            self.assertNotIn("Referer", sent_headers)
            self.assertEqual(conns[0].timeout, 5)
        finally:
            pool.close()

    def test_post_sends_its_body(self):
        pool, conns = self._pool({("example.com", "/p"): (200, b"ok", {})})
        try:
            with mock.patch.object(hr, "_KEEPALIVE", pool):
                status, body, _, _ = hr.http_request(
                    "https://example.com/p", method="POST", data=b"form=1")
            self.assertEqual((status, body), (200, b"ok"))
            method, target, sent_body, _ = conns[0].requests[0]
            self.assertEqual((method, target, sent_body),
                             ("POST", "/p", b"form=1"))
        finally:
            pool.close()

    def test_http_error_is_returned_not_retried(self):
        # An HTTP answer is evidence about the URL, not the network:
        # a 429 returns at once, however many transport retries are
        # budgeted.
        pool, conns = self._pool({("example.com", "/a"): (
            429, b"slow", {})})
        try:
            with mock.patch.object(hr, "_KEEPALIVE", pool), \
                    mock.patch.object(time, "sleep"):
                status, body, _, _ = hr.http_request("https://example.com/a")
            self.assertEqual((status, body), (429, b"slow"))
            self.assertEqual(len(conns[0].requests), 1)
        finally:
            pool.close()

    def test_transport_failure_is_retried_then_succeeds(self):
        # Session 126 follow-up: the MovieBox edge resets ~2 of 3
        # handshakes, so the first reset must not be the verdict — a
        # retry that answers is a success, not a transport failure.
        # (The budget is unchanged: http_request still gets
        # TRANSPORT_RETRIES retries through the pool.)
        pool, conns = self._pool({("example.com", "/a"): (200, b"ok", {})},
                                 fail_budget=[2])
        try:
            with mock.patch.object(hr, "_KEEPALIVE", pool), \
                    mock.patch.object(time, "sleep"):
                status, body, _, _ = hr.http_request("https://example.com/a")
            self.assertEqual((status, body), (200, b"ok"))
            total = sum(len(c.requests) for c in conns)
            self.assertEqual(total, 3, "two resets then one success")
            self.assertTrue(all(c.closed for c in conns[:2]),
                            "dead conns are dropped, not pooled")
        finally:
            pool.close()

    def test_transport_failure_after_all_retries_reports_status_zero(self):
        pool, conns = self._pool({}, fail_budget=[100])
        try:
            with mock.patch.object(hr, "_KEEPALIVE", pool), \
                    mock.patch.object(time, "sleep"):
                status, body, headers, final = hr.http_request(
                    "https://example.com/a")
            self.assertEqual((status, body, headers, final),
                             (0, "TLS reset", {}, "https://example.com/a"))
            total = sum(len(c.requests) for c in conns)
            self.assertEqual(total, hr.TRANSPORT_RETRIES + 1)
        finally:
            pool.close()

    def test_explicit_zero_retries_means_one_attempt(self):
        pool, conns = self._pool({}, fail_budget=[100])
        try:
            with mock.patch.object(hr, "_KEEPALIVE", pool), \
                    mock.patch.object(time, "sleep"):
                status, _, _, _ = hr.http_request(
                    "https://example.com/a", transport_retries=0)
            self.assertEqual(status, 0)
            total = sum(len(c.requests) for c in conns)
            self.assertEqual(total, 1)
        finally:
            pool.close()

    def test_no_follow_returns_the_3xx_unfollowed(self):
        pool, conns = self._pool({("example.com", "/dl"): (
            302, b"", {"location": "/new"})})
        try:
            with mock.patch.object(hr, "_KEEPALIVE", pool):
                status, _body, headers, final = hr.http_request(
                    "https://example.com/dl", follow=False, timeout=7)
            self.assertEqual(status, 302)
            self.assertEqual(headers.get("location"), "/new")
            self.assertEqual(final, "https://example.com/dl")
            self.assertEqual(len(conns[0].requests), 1)
            self.assertEqual(conns[0].timeout, 7)
        finally:
            pool.close()

    def test_redirect_chain_is_followed_past_the_old_pool_cap(self):
        # The pool used to follow at most 6 redirects; urllib follows
        # 10, and http_request must not shrink that — an 8-hop chain
        # (which the old pool would have answered as a 3xx) reaches
        # the final page.
        script = {}
        for i in range(8):
            script[("example.com", f"/r{i}")] = (
                302, b"", {"location": f"/r{i + 1}"})
        script[("example.com", "/r8")] = (200, b"final", {})
        pool, conns = self._pool(script)
        try:
            with mock.patch.object(hr, "_KEEPALIVE", pool):
                status, body, _, final = hr.http_request(
                    "https://example.com/r0")
            self.assertEqual((status, body), (200, b"final"))
            self.assertEqual(final, "https://example.com/r8")
            self.assertEqual(len(conns), 1,
                             "same-host hops reuse the one connection")
            self.assertEqual(len(conns[0].requests), 9)
        finally:
            pool.close()

    def test_post_becomes_get_after_a_302(self):
        # urllib converts a POST to a GET when it follows a 301/302;
        # the pool must do the same or a POST that redirects would
        # re-POST its body to the target.
        script = {("example.com", "/p"): (302, b"", {"location": "/q"}),
                  ("example.com", "/q"): (200, b"got", {})}
        pool, conns = self._pool(script)
        try:
            with mock.patch.object(hr, "_KEEPALIVE", pool):
                status, body, _, final = hr.http_request(
                    "https://example.com/p", method="POST", data=b"form=1")
            self.assertEqual((status, body), (200, b"got"))
            self.assertEqual(final, "https://example.com/q")
            self.assertEqual(
                [(m, t, b) for (m, t, b, _h) in conns[0].requests],
                [("POST", "/p", b"form=1"), ("GET", "/q", None)])
        finally:
            pool.close()

    def test_non_http_scheme_falls_back_to_urllib(self):
        # The pool cannot speak ftp: — such a URL takes the old urllib
        # path, which still honours _open (and its tests).
        seen = {}

        def fake_open(request, timeout, follow=True):
            seen["url"] = request.full_url
            seen["timeout"], seen["follow"] = timeout, follow
            return _FakeUrllibResp(200, b"ok", {}, "ftp://x.test/f")

        with mock.patch.object(hr, "_open", fake_open):
            status, body, _headers, final = hr.http_request(
                "ftp://x.test/f", timeout=5)
        self.assertEqual((status, body, final),
                         (200, b"ok", "ftp://x.test/f"))
        self.assertEqual(seen, {"url": "ftp://x.test/f",
                                "timeout": 5, "follow": True})


if __name__ == "__main__":
    unittest.main()
