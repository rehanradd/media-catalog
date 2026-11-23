#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""VidBolt one-round + bank-all (2026-11-23) — offline tests.

One /api/resolve response already returns every rendition, so one resolve
must bank them all — each with the expiry its own signed URL carries —
instead of today's behaviour (only the pressed rendition is banked, then
every sibling quality re-resolves the same title, and the signed
`Expires` epoch was never even parsed: the old reader wanted `s` + `e`,
VidBolt signs `Expires`, so every resolve banked the 4-hour guess).

Two halves, both mocked — no network:

* `host_resolvers.unwrap_vidbolt`: the response's sources all land in
  `result["sources"]` with per-source `expires_in` / `expires_at`; the
  /api/resolve GET carries `Referer: https://vidbolt.xyz/`; the call rides
  the shared keep-alive pool when the caller passes `fetch=keepalive_fetch`
  (one connection per host, reused across resolves); a dead/empty response
  fails honestly (dead for no stream / empty sources, unknown for bad JSON,
  blocked/dead by HTTP status).
* `current_hosts`: `_vidbolt_candidates` attaches the same per-candidate
  `expires_at`, and the winning `resolve_copy` result carries the
  non-winning candidates as `siblings` (the hubcdn `mirrors` shape) so the
  caller can bank or fail over to them from this one resolve.

Stdlib only. Run with the repo convention::

    python3 -S Fast_Fetch/test_vidbolt_speed.py
    python3 -S -m unittest test_vidbolt_speed
"""

from __future__ import annotations

import http.client
import json
import os
import sys
import time
import unittest
from unittest.mock import patch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "importers"))

import current_hosts  # noqa: E402
import host_resolvers as hr  # noqa: E402

MOVIE_URL = "https://vidbolt.xyz/movie/11667"
RESOLVE_URL = "https://vidbolt.xyz/api/resolve?type=movie&id=11667&hevc=0"
TV_URL = "https://vidbolt.xyz/tv/1396/1/1"
TV_RESOLVE_URL = ("https://vidbolt.xyz/api/resolve?type=tv&id=1396"
                  "&season=1&episode=1&hevc=0")
#: host_resolvers.unwrap_vidbolt builds the same resolve WITHOUT
#: &hevc=0 (only current_hosts._vidbolt_direct appends it) — the
#: hr-side tests below use these, the current_hosts ones above.
HR_RESOLVE_URL = "https://vidbolt.xyz/api/resolve?type=movie&id=11667"
HR_TV_RESOLVE_URL = ("https://vidbolt.xyz/api/resolve?type=tv&id=1396"
                     "&season=1&episode=1")

NOW = int(time.time())
EXP_1H = NOW + 3600
EXP_2H = NOW + 7200

#: One response, four renditions — shaped on the live 2026-11-23 WARP
#: capture behind test_current_hosts.VIDBOLT_STREAM, but with DISTINCT
#: `Expires` epochs per HLS master so a shared stamp cannot pass. The
#: mp4 proxy URLs carry no expiry at all (None, never invented).
STREAM_JSON = json.dumps({
    "stream": {
        "server": "quasar",
        "sources": [
            {"url": ("https://img1.hscow.com/hls_mps/272726923fa4a6c5949f7a0aeefcd"
                     "8e5d6dab598/720/index_287.m3u8?Expires=%d"
                     "&KeyName=Signature&Signature=SIGNATURE1" % EXP_1H),
             "kind": "hls", "quality": "1080p", "language": "Hindi"},
            {"url": ("https://img1.fcxmb.com/hls_mps/272726923fa4a6c5949f7a0aeefcd"
                     "8e5d6dab598/480/index_266.m3u8?Expires=%d"
                     "&KeyName=Signature&Signature=SIGNATURE2" % EXP_2H),
             "kind": "hls", "quality": "1080p", "language": "Hindi"},
            {"url": ("https://scraper.vidbolt.xyz/proxy/segment/"
                     "aHR0cHM6Ly9leGFtcGxlLmNvbS92aWRlby0xMDgwcC5tcDQ="),
             "kind": "mp4", "quality": "1080p", "language": None},
            {"url": ("https://scraper.vidbolt.xyz/proxy/segment/"
                     "aHR0cHM6Ly9leGFtcGxlLmNvbS92aWRlby03MjBwLm1wNA=="),
             "kind": "mp4", "quality": "720p", "language": None},
        ],
    },
})

#: Today's rotating shape (live 2026-11-23): a `stream.vidbolt.xyz/v/`
#: bearer path with no query at all — no parsable lifetime anywhere.
LUNA_JSON = json.dumps({
    "stream": {
        "server": "luna",
        "sources": [
            {"url": "https://stream.vidbolt.xyz/v/WGM4N6CyhXpVfidnlkYlRHuDqfy4zFhXpcQcDwt_vAq93w5",
             "kind": "hls", "quality": "1080p", "language": None},
        ],
    },
})

HLS_MASTER = b"#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10.0,\nseg0.ts\n"


def _scripted_fetch(script, calls):
    """A `fetch(url, headers, timeout, method, follow, data)` stand-in.

    ``script`` maps a URL to (status, body, headers); anything unmapped
    is a transport failure (status 0), never a silent 200.
    """
    def _fetch(url, headers=None, timeout=hr.DEFAULT_TIMEOUT,
               method="GET", follow=True, data=None):
        calls.append((url, dict(headers or {})))
        if url not in script:
            return 0, "no such URL in script", {}, url
        status, body, resp_headers = script[url]
        if isinstance(body, str):
            body = body.encode("utf-8")
        return status, body, resp_headers, url

    return _fetch


def _resolve_fetch(body_by_url, calls, master_body=HLS_MASTER):
    """Resolve-JSON fetch where the master probe answers a live HLS body."""
    def _fetch(url, headers=None, timeout=hr.DEFAULT_TIMEOUT,
               method="GET", follow=True, data=None):
        calls.append((url, dict(headers or {})))
        if url in body_by_url:
            status, body, resp_headers = body_by_url[url]
            if isinstance(body, str):
                body = body.encode("utf-8")
            return status, body, resp_headers, url
        if (headers or {}).get("Range", "").startswith("bytes=0-"):
            return 200, master_body, {
                "content-type": "application/vnd.apple.mpegurl"}, url
        return 0, "no such URL in script", {}, url

    return _fetch


class VidboltSignedExpiryTests(unittest.TestCase):
    def test_expires_epoch_is_parsed(self):
        at, seconds = hr.vidbolt_signed_expiry(
            "https://img1.hscow.com/x/index.m3u8?Expires=%d&Signature=z" % EXP_1H)
        self.assertEqual(at, EXP_1H)
        self.assertGreater(seconds, 3500)
        self.assertLessEqual(seconds, 3600)

    def test_lowercase_expires_accepted(self):
        at, _ = hr.vidbolt_signed_expiry(
            "https://img1.hscow.com/x/index.m3u8?expires=%d" % EXP_2H)
        self.assertEqual(at, EXP_2H)

    def test_no_expiry_param_is_none(self):
        self.assertEqual(hr.vidbolt_signed_expiry(
            "https://stream.vidbolt.xyz/v/sometoken"), (None, None))
        self.assertEqual(hr.vidbolt_signed_expiry(
            "https://scraper.vidbolt.xyz/proxy/segment/abc"), (None, None))

    def test_short_or_nondigit_expiry_is_none(self):
        self.assertEqual(hr.vidbolt_signed_expiry(
            "https://x.example/a.m3u8?Expires=43200"), (None, None))
        self.assertEqual(hr.vidbolt_signed_expiry(
            "https://x.example/a.m3u8?Expires=notanepoch"), (None, None))


class VidboltBankAllTests(unittest.TestCase):
    def test_all_sources_banked_with_own_expiry(self):
        calls = []
        fetch = _scripted_fetch(
            {HR_RESOLVE_URL: (200, STREAM_JSON, {"content-type": "application/json"})},
            calls)
        result = hr.unwrap_vidbolt(MOVIE_URL, fetch=fetch, verify=False)
        self.assertEqual(result["status"], "unverified")  # verify=False: no probe
        self.assertEqual(len(result["sources"]), 4)
        by_url = {s["url"]: s for s in result["sources"]}
        hls1 = [s for s in result["sources"]
                if "hscow" in s["url"]][0]
        hls2 = [s for s in result["sources"]
                if "fcxmb" in s["url"]][0]
        # Each HLS master carries ITS OWN expiry — not one shared stamp.
        self.assertEqual(hls1["expires_at"], float(EXP_1H))
        self.assertEqual(hls2["expires_at"], float(EXP_2H))
        self.assertGreater(hls1["expires_in"], 3500)
        self.assertLessEqual(hls1["expires_in"], 3600)
        self.assertGreater(hls2["expires_in"], 7100)
        self.assertLessEqual(hls2["expires_in"], 7200)
        self.assertNotEqual(hls1["expires_in"], hls2["expires_in"])
        # The mp4 proxy URLs carry no lifetime: None, never invented.
        for s in result["sources"]:
            if "scraper.vidbolt.xyz" in s["url"]:
                self.assertIsNone(s["expires_in"])
                self.assertIsNone(s["expires_at"])
        # The winner's own expiry is the FIRST source's measured one —
        # the old code banked the 4-hour guess for every resolve.
        self.assertEqual(result["final_url"], hls1["url"])
        self.assertGreater(result["expires_in"], 3500)
        self.assertLessEqual(result["expires_in"], 3600)
        self.assertEqual(len(by_url), 4)

    def test_luna_shape_banks_with_no_expiry(self):
        calls = []
        fetch = _scripted_fetch(
            {HR_RESOLVE_URL: (200, LUNA_JSON, {"content-type": "application/json"})},
            calls)
        result = hr.unwrap_vidbolt(MOVIE_URL, fetch=fetch, verify=False)
        self.assertEqual(len(result["sources"]), 1)
        self.assertEqual(result["sources"][0]["url"],
                         "https://stream.vidbolt.xyz/v/WGM4N6CyhXpVfidnlkYlRHuDqfy4zFhXpcQcDwt_vAq93w5")
        self.assertIsNone(result["sources"][0]["expires_in"])
        self.assertIsNone(result["sources"][0]["expires_at"])
        # No signed lifetime anywhere: the conservative default stands.
        self.assertEqual(result["expires_in"],
                         hr.VIDBOLT_DEFAULT_EXPIRY_SECONDS)

    def test_referer_sent_on_resolve(self):
        calls = []
        fetch = _scripted_fetch(
            {HR_RESOLVE_URL: (200, STREAM_JSON, {"content-type": "application/json"})},
            calls)
        hr.unwrap_vidbolt(MOVIE_URL, fetch=fetch, verify=False)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][0], HR_RESOLVE_URL)
        self.assertEqual(calls[0][1].get("Referer"), "https://vidbolt.xyz/")

    def test_tv_resolve_carries_season_episode(self):
        calls = []
        fetch = _scripted_fetch(
            {HR_TV_RESOLVE_URL: (200, LUNA_JSON, {"content-type": "application/json"})},
            calls)
        result = hr.unwrap_vidbolt(TV_URL, fetch=fetch, verify=False)
        self.assertEqual(len(result["sources"]), 1)
        self.assertEqual(calls[0][0], HR_TV_RESOLVE_URL)

    def test_verify_probes_the_master(self):
        calls = []
        fetch = _resolve_fetch(
            {HR_RESOLVE_URL: (200, STREAM_JSON, {"content-type": "application/json"})},
            calls)
        result = hr.unwrap_vidbolt(MOVIE_URL, fetch=fetch, verify=True)
        self.assertEqual(result["status"], "active")
        self.assertEqual(result["final_url"],
                         result["sources"][0]["url"])
        self.assertEqual(len(result["sources"]), 4)
        probe_calls = [c for c in calls if c[0] != HR_RESOLVE_URL]
        self.assertEqual(len(probe_calls), 1)
        self.assertTrue(probe_calls[0][1].get("Range", "").startswith("bytes=0-"))

    def test_null_stream_is_dead_not_unknown(self):
        calls = []
        fetch = _scripted_fetch(
            {HR_RESOLVE_URL: (200, '{"stream": null}', {})}, calls)
        result = hr.unwrap_vidbolt(MOVIE_URL, fetch=fetch, verify=False)
        self.assertEqual(result["status"], "dead")
        self.assertEqual(result["sources"], [])

    def test_empty_sources_is_dead(self):
        calls = []
        fetch = _scripted_fetch(
            {HR_RESOLVE_URL: (200, '{"stream": {"server": "quasar", "sources": []}}', {})},
            calls)
        result = hr.unwrap_vidbolt(MOVIE_URL, fetch=fetch, verify=False)
        self.assertEqual(result["status"], "dead")

    def test_bad_json_is_unknown(self):
        calls = []
        fetch = _scripted_fetch(
            {HR_RESOLVE_URL: (200, "not json", {})}, calls)
        result = hr.unwrap_vidbolt(MOVIE_URL, fetch=fetch, verify=False)
        self.assertEqual(result["status"], "unknown")

    def test_http_403_is_blocked_404_is_dead(self):
        for status, expected in ((403, "blocked"), (404, "dead"), (500, "unknown")):
            calls = []
            fetch = _scripted_fetch({HR_RESOLVE_URL: (status, "", {})}, calls)
            result = hr.unwrap_vidbolt(MOVIE_URL, fetch=fetch, verify=False)
            self.assertEqual(result["status"], expected, status)

    def test_transport_failure_is_unknown(self):
        calls = []
        fetch = _scripted_fetch({}, calls)
        result = hr.unwrap_vidbolt(MOVIE_URL, fetch=fetch, verify=False)
        self.assertEqual(result["status"], "unknown")


class VidboltKeepAliveTests(unittest.TestCase):
    def test_two_resolves_reuse_one_connection(self):
        """The pool's whole point: the second /api/resolve must not pay
        for a new connection to vidbolt.xyz."""
        script = {
            ("vidbolt.xyz", "/api/resolve?type=movie&id=11667"):
                (200, STREAM_JSON.encode(), {"content-type": "application/json"}),
        }
        conns = []

        def factory(host, port, https, timeout):
            conn = _FakeConn(script)
            conn.host = host
            conns.append(conn)
            return conn

        pool = hr.KeepAliveFetch(connection_factory=factory)
        try:
            for _ in range(2):
                result = hr.unwrap_vidbolt(
                    MOVIE_URL, fetch=pool.fetch, verify=False)
                self.assertEqual(len(result["sources"]), 4)
            self.assertEqual(len(conns), 1,
                             "second resolve must reuse the connection")
            self.assertEqual(len(conns[0].requests), 2)
            self.assertEqual(pool.pooled_connections, 1)
        finally:
            pool.close()

    def test_pool_fetch_shape_matches_http_request(self):
        calls = []
        fetch = _scripted_fetch(
            {HR_RESOLVE_URL: (200, STREAM_JSON, {"content-type": "application/json"})},
            calls)
        # keepalive_fetch's contract is http_request's: the resolver must
        # accept either without a shape check.
        status, body, headers, final = fetch(
            HR_RESOLVE_URL, headers={"Referer": "https://vidbolt.xyz/"})
        self.assertEqual(status, 200)
        self.assertIn(b"quasar", body if isinstance(body, bytes) else body.encode())
        self.assertEqual(final, HR_RESOLVE_URL)


class _FakeResp:
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
    """An http.client connection stand-in that answers from a script."""

    def __init__(self, script):
        self.script = script  # {(host, target): (status, body, headers)}
        self.requests = []
        self.closed = False
        self.fail_next = None  # exception to raise on the next request

    def request(self, method, target, body=None, headers=None):
        self.requests.append((method, target))
        if self.fail_next is not None:
            exc, self.fail_next = self.fail_next, None
            raise exc

    def getresponse(self):
        method, target = self.requests[-1]
        status, body, headers = self.script.get(
            ("vidbolt.xyz", target), (200, b"ok", {}))
        return _FakeResp(status, body, headers)

    def close(self):
        self.closed = True


class VidboltKeepAlivePoolTests(unittest.TestCase):
    def _pool(self, script, max_connections=8):
        conns = []

        def factory(host, port, https, timeout):
            conn = _FakeConn(script)
            conn.host = host
            conns.append(conn)
            return conn

        pool = hr.KeepAliveFetch(connection_factory=factory,
                                 max_connections=max_connections)
        return pool, conns

    def test_pool_is_bounded_and_evicts_oldest(self):
        pool, conns = self._pool({}, max_connections=2)
        try:
            pool.fetch("https://one.example/")
            pool.fetch("https://two.example/")
            self.assertEqual(pool.pooled_connections, 2)
            pool.fetch("https://three.example/")
            self.assertEqual(pool.pooled_connections, 2)
            self.assertTrue(conns[0].closed, "oldest conn must be closed")
            self.assertEqual(len(conns), 3)
        finally:
            pool.close()

    def test_dead_connection_is_dropped_and_retried(self):
        pool, conns = self._pool(
            {("vidbolt.xyz", "/api/resolve?type=movie&id=11667&hevc=0"):
             (200, STREAM_JSON.encode(), {})})
        try:
            pool.fetch(RESOLVE_URL)
            self.assertEqual(len(conns), 1)
            conns[0].fail_next = http.client.RemoteDisconnected(
                "server closed the idle connection")
            status, body, _, _ = pool.fetch(RESOLVE_URL)
            self.assertEqual(status, 200)
            self.assertEqual(len(conns), 2, "retry must use a fresh conn")
            self.assertTrue(conns[0].closed)
        finally:
            pool.close()

    def test_transport_failure_after_retries_is_status_zero(self):
        def failing_factory(host, port, https, timeout):
            conn = _FakeConn({})

            def always_fail(method, target, body=None, headers=None):
                raise TimeoutError("timed out")

            conn.request = always_fail
            return conn

        pool = hr.KeepAliveFetch(connection_factory=failing_factory)
        try:
            with patch.object(time, "sleep", lambda *a: None):
                status, body, headers, _ = pool.fetch(
                    "https://vidbolt.xyz/api/resolve?type=movie&id=1", timeout=1)
            self.assertEqual(status, 0)
            self.assertIn("timed out", str(body))
            self.assertEqual(headers, {})
        finally:
            pool.close()


class VidboltStageOneTests(unittest.TestCase):
    """current_hosts: per-candidate expiry + siblings on the win."""

    def _stub(self, body, status=200):
        calls = []

        class _Res:
            def __init__(self, status, body):
                self.status = status
                self._body = body

            def text(self):
                return self._body

        def fetch(url, referer=None, *args, **kwargs):
            calls.append((url, referer))
            return _Res(status, body)

        return fetch, calls

    def test_candidates_carry_own_expiry(self):
        fetch, _ = self._stub(STREAM_JSON)
        backend, candidates, failure = current_hosts._unwrap_vidbolt(
            MOVIE_URL, fetch)
        self.assertEqual(backend, "vidbolt")
        self.assertIsNone(failure)
        self.assertEqual(len(candidates), 4)
        hls = [c for c in candidates if "hscow" in c["url"]][0]
        hls2 = [c for c in candidates if "fcxmb" in c["url"]][0]
        self.assertEqual(hls["expires_at"], float(EXP_1H))
        self.assertEqual(hls2["expires_at"], float(EXP_2H))
        self.assertNotEqual(hls["expires_at"], hls2["expires_at"])
        for c in candidates:
            if "scraper.vidbolt.xyz" in c["url"]:
                self.assertIsNone(c["expires_at"])

    def test_luna_candidates_have_no_expiry(self):
        fetch, _ = self._stub(LUNA_JSON)
        _backend, candidates, failure = current_hosts._unwrap_vidbolt(
            MOVIE_URL, fetch)
        self.assertIsNone(failure)
        self.assertEqual(len(candidates), 1)
        self.assertIsNone(candidates[0]["expires_at"])

    def test_pooled_adapter_leaves_stub_fetch_untouched(self):
        """A non-default fetch is never routed to the pool: the adapter
        returns it as-is, so mocked tests cannot touch the network."""
        fetch, calls = self._stub(STREAM_JSON)
        self.assertIs(current_hosts._vidbolt_pooled_fetch(fetch), fetch)
        backend, candidates, failure = current_hosts._unwrap_vidbolt(
            MOVIE_URL, fetch)
        self.assertIsNone(failure)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][0], RESOLVE_URL)

    def test_resolve_copy_winner_carries_siblings(self):
        fetch, _ = self._stub(STREAM_JSON)

        def ok_probe(url, referer, kind_hint):
            return {"ok": True, "kind": kind_hint, "mime": "application/vnd.apple.mpegurl",
                    "size": None, "resumable": False, "renditions": [],
                    "failure": None}

        result = current_hosts.resolve_copy(MOVIE_URL, fetch=fetch,
                                            probe=ok_probe)
        self.assertTrue(result["ok"], result)
        # The winner is the first candidate (first HLS master); the other
        # three ride along as siblings from the same resolve.
        self.assertIn("hscow", result["url"])
        siblings = result.get("siblings") or []
        # One resolve published 4 candidates; the winner + 3 siblings.
        self.assertEqual(len(siblings), 3)
        self.assertNotIn(result["url"], [s["url"] for s in siblings])
        self.assertTrue(all("expires_at" in s for s in siblings))
        self.assertTrue(all("quality" in s for s in siblings))

    def test_resolve_copy_single_candidate_has_no_siblings(self):
        fetch, _ = self._stub(LUNA_JSON)

        def ok_probe(url, referer, kind_hint):
            return {"ok": True, "kind": kind_hint, "mime": "application/vnd.apple.mpegurl",
                    "size": None, "resumable": False, "renditions": [],
                    "failure": None}

        result = current_hosts.resolve_copy(MOVIE_URL, fetch=fetch,
                                            probe=ok_probe)
        self.assertTrue(result["ok"], result)
        self.assertNotIn("siblings", result)


if __name__ == "__main__":
    unittest.main()
