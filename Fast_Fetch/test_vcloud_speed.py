#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""VCloud one-round + bank-all (2026-11-23) — offline tests.

One token-page fetch must bank every playable destination at once,
and the api -> intermediate -> token hops must ride one keep-alive
connection instead of three fresh dials. What this pins:

* the pool: one connection per host, reused across hops; a dead
  connection is dropped and retried on a fresh one; redirects follow
  on the pool; an over-cap body drops (never poisons) the connection;
* the seam: ``_vcloud_fetch`` serves from the pool and falls back to
  ``http_get`` (timeout 5, 0 retries, clearance forwarded) only on a
  transport failure — an HTTP answer is never re-fetched;
* the chain: the bare-slug intermediate fresh api pages link instead
  of ``_cy`` resolves (``_cy`` still preferred when both are present;
  nav paths are never mistaken for a slug), the gpdl hop rides the
  passed fetch, and a token-less page fails honestly;
* the bank: pressing one vcloud row banks the fsl_r2 + google_cdn
  siblings filed under the SAME vcloud address, each with its own
  expiry (R2's parsed presigned window; Google's cleared to NULL);
  the HubCloud bridge row is untouched, a sibling filed under a
  DIFFERENT vcloud address (another file of the same release) is
  untouched, the dead PixelDrain placeholder is never banked, no rows
  are invented, and a gated-out press banks nothing.

Stdlib only. No network: every HTTP hop is a canned fetch, every DB
is a temp file. Run with the repo convention::

    python3 -S Fast_Fetch/test_vcloud_speed.py
    python3 -S -m unittest test_vcloud_speed
"""

from __future__ import annotations

import base64
import calendar
import http.client
import os
import sys
import tempfile
import time
import unittest
import urllib.parse
from pathlib import Path
from unittest import mock

os.environ.setdefault("RADD_SKIP_LIVE_ENRICH", "1")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "importers"))

import current_hosts  # noqa: E402
import fast_fetch  # noqa: E402

API_URL = ("https://vcloud.my/api/index.php?link="
           "FAKELINKVALUEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==")
CY_HREF = "/_cy-1axevcv1ci_"
CY_URL = "https://vcloud.my/_cy-1axevcv1ci_"
SLUG_HREF = "/4ptsbnwacaco4js"
SLUG_URL = "https://vcloud.my/4ptsbnwacaco4js"
TOKEN_URL = "https://vcloud.my/4ptsbnwacaco4js?token=FAKETOKEN"
SHORT_URL = "https://vcloud.my/hhqte6te-qukkzt"
SHORT_TOKEN_URL = "https://vcloud.my/hhqte6te-qukkzt?token=FAKETOKEN2"
GPDL_URL = "https://gpdl2.hubcloud.mov/?id=FAKEGPDLID"
GOOGLE_URL = ("https://video-downloads.googleusercontent.com/"
              "FAKEADGPM2lDlJqym4xgQRGP8uIqg7TRpJmiuE1IV4K9s")
HUB_URL = "https://hubcloud.foo/tg/go?id=FAKEHUBID"
PIXEL_DEAD_URL = "https://pixeldrain.dev/api/file/negn6f"

#: Fixed-date R2 URL (2026-11-23T02:58:09Z + 28800 s = 2026-11-23T10:58:09Z
#: = epoch 1795431489): the parse test pins this literal, so it never
#: depends on the clock.
FIXED_R2_URL = ("https://db076825ef2334beaf4c317aed13a40c"
                ".r2.cloudflarestorage.com/hub/0123456789abcdef"
                "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                "&X-Amz-Credential=FAKECRED%2F20261123%2Fauto%2Fs3%2Faws4_request"
                "&X-Amz-Date=20261123T025809Z&X-Amz-Expires=28800"
                "&X-Amz-SignedHeaders=host&X-Amz-Signature=" + "0" * 64)
FIXED_R2_EXPIRES_AT = 1795431489


def _r2_url(key, lifetime):
    """A presigned R2 URL issued NOW, so expires_in ~= lifetime."""
    now = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    return (f"https://db076825ef2334beaf4c317aed13a40c"
            f".r2.cloudflarestorage.com/hub/{key}"
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
            "&X-Amz-Credential=FAKECRED%2F20261123%2Fauto%2Fs3%2Faws4_request"
            f"&X-Amz-Date={now}&X-Amz-Expires={lifetime}"
            "&X-Amz-SignedHeaders=host&X-Amz-Signature=" + "0" * 64)


FRESH_R2_URL = _r2_url("1c8e2ebb17c55ccc855414dc97ed1e80", 28800)


def _double_b64(url: str) -> str:
    return base64.b64encode(
        base64.b64encode(url.encode()).decode().encode()).decode()


def _token_script(url: str) -> str:
    return f"var url = atob(atob('{_double_b64(url)}'));"


def _api_page_without_token(cy_href: str = CY_HREF) -> str:
    return ('<html><head><title>V-Cloud | File</title></head><body>'
            f'<a href="{cy_href}" class="btn">Direct Download</a>'
            "</body></html>")


def _api_page_both_links() -> str:
    return ('<html><head><title>V-Cloud | File</title></head><body>'
            f'<a href="{SLUG_HREF}" class="btn">Direct Download</a>'
            f'<a href="{CY_HREF}" class="btn">Direct Download</a>'
            "</body></html>")


def _api_page_nav_and_slug() -> str:
    return ('<html><head><title>V-Cloud | File</title></head><body>'
            '<a href="/download">Download</a>'
            '<a href="/assets/style.css">css</a>'
            f'<a href="{SLUG_HREF}" class="btn">Direct Download</a>'
            "</body></html>")


def _token_page() -> str:
    return (f'<a href="{FRESH_R2_URL}">FSL</a>'
            f'<a href="{GPDL_URL}">HubCloud</a>'
            f'<a href="{HUB_URL}">HubCloud 10Gbps</a>'
            '<a href="https://pixeldrain.dev/u/negn6f">PixelDrain</a>')


# ---------------------------------------------------------------------------
# Fake http.client connections for the pool tests
# ---------------------------------------------------------------------------

class _FakeResponse:
    def __init__(self, status=200, headers=None, body=b"ok"):
        self.status = status
        self._headers = headers or {}
        self._body = body

    def getheaders(self):
        return list(self._headers.items())

    def read(self, n=-1):
        if n is None or n < 0:
            out, self._body = self._body, b""
            return out
        out, self._body = self._body[:n], self._body[n:]
        return out


class _FakeConn:
    """A pooled connection: records requests, answers from a script."""

    def __init__(self, script):
        self.script = list(script)  # [(status, headers, body) | exc]
        self._last = None
        self.requests = []
        self.closed = False

    def request(self, method, target, headers=None, body=None):
        self.requests.append((method, target))
        if self.script and isinstance(self.script[0], Exception):
            raise self.script.pop(0)

    def getresponse(self):
        # A real connection answers every request: once the script is
        # spent, repeat the last scripted answer.
        if self.script:
            self._last = self.script.pop(0)
        return _FakeResponse(*self._last)

    def close(self):
        self.closed = True


class _FakeFactory:
    def __init__(self):
        self.made = []  # [(host, port, https, timeout)]
        self.conns = {}

    def __call__(self, host, port, https, timeout, context):
        self.made.append((host, port, https, timeout))
        key = (host, port, https)
        if key not in self.conns:
            self.conns[key] = _FakeConn([
                (200, {"Content-Type": "text/html; charset=utf-8"}, b"ok")])
        return self.conns[key]


class KeepAlivePoolTests(unittest.TestCase):
    def test_one_connection_reused_across_hops(self):
        """Two GETs to the same host ride one connection (factory once)."""
        factory = _FakeFactory()
        pool = fast_fetch._KeepAliveFetch(connection_factory=factory)
        try:
            code1, _f1, _t1 = pool.fetch("https://vcloud.my/api/index.php?link=x")
            code2, _f2, _t2 = pool.fetch("https://vcloud.my/_cy-1axevcv1ci_")
        finally:
            pool.close()
        self.assertEqual((code1, code2), (200, 200))
        self.assertEqual(len(factory.made), 1, factory.made)
        self.assertEqual(pool.pooled_connections, 0)  # closed
        conn = factory.conns[("vcloud.my", 443, True)]
        self.assertEqual(len(conn.requests), 2, conn.requests)

    def test_second_host_gets_its_own_connection(self):
        """A different host is a different pooled connection."""
        factory = _FakeFactory()
        pool = fast_fetch._KeepAliveFetch(connection_factory=factory)
        try:
            pool.fetch("https://vcloud.my/api/index.php?link=x")
            pool.fetch("https://gpdl2.hubcloud.mov/?id=y")
            self.assertEqual(pool.pooled_connections, 2)
            self.assertEqual(len(factory.made), 2, factory.made)
        finally:
            pool.close()

    def test_stale_connection_dropped_and_retried(self):
        """A dead pooled connection is dropped; the retry dials fresh."""
        factory = _FakeFactory()
        dead = _FakeConn([http.client.BadStatusLine("bad")])
        live = _FakeConn([(200, {}, b"fresh")])
        calls = {"n": 0}

        def flaky(host, port, https, timeout, context):
            calls["n"] += 1
            return dead if calls["n"] == 1 else live

        pool = fast_fetch._KeepAliveFetch(connection_factory=flaky)
        try:
            code, _final, text = pool.fetch("https://vcloud.my/api/index.php?link=x")
        finally:
            pool.close()
        self.assertEqual(code, 200)
        self.assertEqual(text, "fresh")
        self.assertTrue(dead.closed)
        self.assertEqual(calls["n"], 2, calls)

    def test_redirect_followed_on_the_pool(self):
        """A 302 to another host follows on that host's connection."""
        factory = _FakeFactory()
        first = _FakeConn([(302, {"Location": "https://gpdl2.hubcloud.mov/?id=z"}, b"")])
        second = _FakeConn([(200, {}, b"landed")])
        factory.conns[("vcloud.my", 443, True)] = first
        factory.conns[("gpdl2.hubcloud.mov", 443, True)] = second
        pool = fast_fetch._KeepAliveFetch(connection_factory=factory)
        try:
            code, final, text = pool.fetch("https://vcloud.my/api/index.php?link=x")
        finally:
            pool.close()
        self.assertEqual(code, 200)
        self.assertEqual(final, "https://gpdl2.hubcloud.mov/?id=z")
        self.assertEqual(text, "landed")

    def test_over_cap_body_drops_the_connection(self):
        """A body over the cap keeps its capped bytes but poisons nothing:
        the connection is dropped, not reused half-read."""
        factory = _FakeFactory()
        big = _FakeConn([(200, {}, b"x" * (fast_fetch._VCLOUD_KEEPALIVE_READ_CAP + 10))])
        factory.conns[("vcloud.my", 443, True)] = big
        pool = fast_fetch._KeepAliveFetch(connection_factory=factory)
        try:
            code, _final, text = pool.fetch("https://vcloud.my/api/index.php?link=x")
            self.assertEqual(code, 200)
            self.assertEqual(len(text), fast_fetch._VCLOUD_KEEPALIVE_READ_CAP)
            self.assertEqual(pool.pooled_connections, 0)
            self.assertTrue(big.closed)
        finally:
            pool.close()

    def test_transport_failure_reports_status_zero(self):
        """DNS/timeout on every attempt is status 0, so the caller can
        fall back to http_get instead of treating it as an answer."""
        def failing(host, port, https, timeout, context):
            raise OSError("boom")

        pool = fast_fetch._KeepAliveFetch(connection_factory=failing)
        try:
            code, _final, text = pool.fetch("https://vcloud.my/api/index.php?link=x")
        finally:
            pool.close()
        self.assertEqual(code, 0)
        self.assertIn("boom", text)


class VcloudFetchSeamTests(unittest.TestCase):
    def test_pool_miss_falls_back_to_http_get(self):
        """A transport failure in the pool runs plain http_get with the
        same 5 s / 0-retry contract the _cy hop always had, and the
        clearance rides along."""
        sentinel = object()
        canned = (200, API_URL, _api_page_without_token())
        getter = mock.Mock(return_value=canned)
        with mock.patch.object(fast_fetch._VCLOUD_KEEPALIVE, "fetch",
                               return_value=(0, API_URL, "ERROR: boom")), \
                mock.patch.object(fast_fetch, "http_get", getter):
            code, final, text = fast_fetch._vcloud_fetch(
                API_URL, timeout=5, clearance=sentinel)
        self.assertEqual((code, final, text), canned)
        self.assertEqual(getter.call_count, 1)
        call = getter.call_args
        self.assertEqual(call.args[0], API_URL)
        self.assertEqual(call.kwargs.get("timeout"), 5)
        self.assertEqual(call.kwargs.get("max_retries"), 0)
        self.assertIs(call.kwargs.get("clearance"), sentinel)

    def test_pool_hit_never_calls_http_get(self):
        """A pool-served answer is returned as-is; http_get is not paid."""
        getter = mock.Mock(side_effect=AssertionError("must not run"))
        with mock.patch.object(fast_fetch._VCLOUD_KEEPALIVE, "fetch",
                               return_value=(200, API_URL, "<html/>")), \
                mock.patch.object(fast_fetch, "http_get", getter):
            code, final, text = fast_fetch._vcloud_fetch(API_URL, timeout=5)
        self.assertEqual((code, final, text), (200, API_URL, "<html/>"))


class UnwrapVcloudSpeedTests(unittest.TestCase):
    def _fetch_mock(self, pages):
        """A _fetch mock serving canned pages by exact URL."""
        def fake_fetch(url, timeout=5, clearance=None):
            if url not in pages:
                raise AssertionError(f"unexpected fetch: {url}")
            return pages[url]
        return fake_fetch

    def test_bare_slug_intermediate_resolves(self):
        """Fresh shape: api page (no token, no _cy) -> bare-slug page ->
        token page -> fan-out. The slug hop is the second fetch."""
        pages = {
            API_URL: (200, API_URL, _api_page_without_token(SLUG_HREF)),
            SLUG_URL: (200, SLUG_URL, f"<html><body><script>{_token_script(TOKEN_URL)}</script></body></html>"),
            TOKEN_URL: (200, TOKEN_URL, _token_page()),
        }
        seen = []

        def recording_fetch(url, timeout=5, clearance=None):
            seen.append(url)
            return pages[url]

        with mock.patch.object(fast_fetch, "unwrap_hubcloud",
                               return_value=(GOOGLE_URL, "google_cdn", 1)):
            out = fast_fetch.unwrap_vcloud(API_URL, _fetch=recording_fetch)
        self.assertEqual(seen, [API_URL, SLUG_URL, TOKEN_URL], seen)
        by_service = {svc: url for _name, svc, url, _t in out}
        self.assertEqual(by_service.get("fsl_r2"), FRESH_R2_URL)
        self.assertEqual(by_service.get("google_cdn"), GOOGLE_URL)

    def test_cy_preferred_when_both_links_present(self):
        """_cy-first: when the api page links both shapes, the _cy page
        is fetched, never the bare slug."""
        pages = {
            API_URL: (200, API_URL, _api_page_both_links()),
            CY_URL: (200, CY_URL, f"<html><body><script>{_token_script(TOKEN_URL)}</script></body></html>"),
            TOKEN_URL: (200, TOKEN_URL, "<html>no links</html>"),
        }
        seen = []

        def recording_fetch(url, timeout=5, clearance=None):
            seen.append(url)
            return pages[url]

        out = fast_fetch.unwrap_vcloud(API_URL, _fetch=recording_fetch)
        self.assertEqual(seen, [API_URL, CY_URL, TOKEN_URL], seen)
        self.assertEqual(out[0][2], TOKEN_URL)

    def test_nav_paths_never_mistaken_for_slug(self):
        """Same-host nav hrefs (/download, /assets/...) are skipped; the
        real slug is the intermediate."""
        pages = {
            API_URL: (200, API_URL, _api_page_nav_and_slug()),
            SLUG_URL: (200, SLUG_URL, f"<html><body><script>{_token_script(TOKEN_URL)}</script></body></html>"),
            TOKEN_URL: (200, TOKEN_URL, "<html>no links</html>"),
        }
        seen = []

        def recording_fetch(url, timeout=5, clearance=None):
            seen.append(url)
            return pages[url]

        out = fast_fetch.unwrap_vcloud(API_URL, _fetch=recording_fetch)
        self.assertEqual(seen, [API_URL, SLUG_URL, TOKEN_URL], seen)
        self.assertEqual(out[0][2], TOKEN_URL)

    def test_gpdl_hop_rides_the_passed_fetch(self):
        """The gpdl redirect is fetched through the caller's fetch, not
        through a fresh http_get: patching http_get to explode still
        resolves, and the gpdl URL is the fourth fetch."""
        gpdl_final = ("https://example.invalid/x?link="
                      + urllib.parse.quote(GOOGLE_URL, safe=""))
        pages = {
            SHORT_URL: (200, SHORT_URL, f"<html><body><script>{_token_script(SHORT_TOKEN_URL)}</script></body></html>"),
            SHORT_TOKEN_URL: (200, SHORT_TOKEN_URL,
                              f'<a href="{GPDL_URL}">HubCloud</a>'),
            GPDL_URL: (200, gpdl_final, ""),
        }
        seen = []

        def recording_fetch(url, timeout=5, clearance=None):
            seen.append(url)
            return pages[url]

        with mock.patch.object(
                fast_fetch, "http_get",
                side_effect=AssertionError("http_get must not run")):
            out = fast_fetch.unwrap_vcloud(SHORT_URL, _fetch=recording_fetch)
        self.assertEqual(seen, [SHORT_URL, SHORT_TOKEN_URL, GPDL_URL], seen)
        by_service = {svc: url for _name, svc, url, _t in out}
        self.assertEqual(by_service.get("google_cdn"), GOOGLE_URL)

    def test_tokenless_page_fails_honestly(self):
        """Api page with neither token nor any intermediate link: the
        landing back, one fetch, temp 0 — never a half-decoded URL."""
        fetch = mock.Mock(return_value=(
            200, API_URL, "<html><body>no token here</body></html>"))
        out = fast_fetch.unwrap_vcloud(API_URL, _fetch=fetch)
        self.assertEqual(fetch.call_count, 1)
        self.assertEqual(out, [("V-Cloud", "vcloud", API_URL, 0)])


class VcloudExpiryTests(unittest.TestCase):
    def test_r2_presigned_expiry_parsed(self):
        """Fixed-date R2 URL: X-Amz-Date + X-Amz-Expires = exact epoch."""
        self.assertEqual(
            fast_fetch._parse_vcloud_signed_expiry(FIXED_R2_URL),
            float(FIXED_R2_EXPIRES_AT))

    def test_r2_expiry_is_now_relative(self):
        """A URL issued now with 28800 s lives ~8 h from now."""
        exp = fast_fetch._parse_vcloud_signed_expiry(FRESH_R2_URL)
        self.assertIsNotNone(exp)
        self.assertGreater(exp - time.time(), 28000)
        self.assertLessEqual(exp - time.time(), 28800)

    def test_google_and_bare_urls_have_no_signed_expiry(self):
        """No signed window in the query is None, never an invented one."""
        self.assertIsNone(
            fast_fetch._parse_vcloud_signed_expiry(GOOGLE_URL))
        self.assertIsNone(
            fast_fetch._parse_vcloud_signed_expiry(
                "https://example.invalid/file.mkv"))
        self.assertIsNone(
            fast_fetch._parse_vcloud_signed_expiry(
                "https://x.r2.cloudflarestorage.com/hub/f"
                "?X-Amz-Date=not-a-date&X-Amz-Expires=28800"))

    def test_normalize_candidates(self):
        """One token page -> one candidate per sibling service, R2 with
        its parsed expiry, Google with none; the bridge, the landing
        and the dead PixelDrain placeholder are dropped."""
        results = [
            ("V-Cloud [FSL Cloudflare R2]", "fsl_r2", FIXED_R2_URL, 0),
            ("V-Cloud [Google Direct CDN]", "google_cdn", GOOGLE_URL, 1),
            ("V-Cloud [HubCloud 10Gbps]", "hubcloud", HUB_URL, 0),
            ("V-Cloud [PixelDrain]", "pixeldrain", PIXEL_DEAD_URL, 0),
            ("V-Cloud", "vcloud", API_URL, 0),
        ]
        cands = {c["service"]: c
                 for c in fast_fetch._normalize_vcloud_candidates(results)}
        self.assertEqual(sorted(cands), ["fsl_r2", "google_cdn"])
        self.assertEqual(cands["fsl_r2"]["is_temporary"], 1)
        self.assertEqual(cands["fsl_r2"]["expires_at"],
                         float(FIXED_R2_EXPIRES_AT))
        self.assertEqual(cands["google_cdn"]["is_temporary"], 1)
        self.assertIsNone(cands["google_cdn"]["expires_at"])


class VcloudBankTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test.db"
        self.db_path_patch = mock.patch.object(
            fast_fetch, "DB_PATH", self.db_path)
        self.db_path_patch.start()
        self.cinemeta_patch = mock.patch.object(
            fast_fetch, "fetch_cinemeta_metadata", return_value=None)
        self.cinemeta_patch.start()
        # Offline like test_hubcloud_oneround: Stage-1 never runs and
        # the Stage-2 gate passes, so the legacy VCloud branch runs
        # exactly as live.
        self.stage2_probe_patch = mock.patch.object(
            fast_fetch, "_stage2_probe",
            return_value={"ok": True, "kind": "file", "mime": "video/mp4",
                          "size": None, "resumable": True, "renditions": [],
                          "failure": None})
        self.stage2_probe_patch.start()
        self.stage1_backend_patch = mock.patch.object(
            current_hosts, "classify_backend", return_value="unknown")
        self.stage1_backend_patch.start()
        fast_fetch.init_db()
        self._seed_release()

    def tearDown(self):
        self.stage1_backend_patch.stop()
        self.stage2_probe_patch.stop()
        self.cinemeta_patch.stop()
        self.db_path_patch.stop()
        self.temp_dir.cleanup()

    def _seed_release(self):
        """One release: the vcloud row + the rows one short vcloud link
        fanned out to at ingest (fsl_r2 + google_cdn + hubcloud, all
        filed under SHORT_URL), plus a google_cdn row filed under a
        DIFFERENT vcloud address (another file of the same release)."""
        media = {
            "url": "https://site-a.example/example-film/",
            "slug": "example-film",
            "title": "Example Film (2024)",
            "clean_title": "Example Film",
            "year": 2024,
            "content_type": "movie",
            "seasons": None,
            "poster_url": None,
            "backdrop_urls": None,
            "imdb_rating": None,
            "genres": None,
            "synopsis": None,
            "is_18_plus": 0,
            "is_animated": 0,
            "source_site": "site_a",
            "industry": "Hollywood",
            "country": "USA",
            "language": "English",
            "canonical_key": fast_fetch.generate_canonical_key(
                "Example Film", 2024),
            "tmdb_id": None,
            "imdb_id": None,
            "releases": [{
                "release_title": "Example Film 480p",
                "quality": "480p",
                "codec": "x264",
                "size": "500MB",
                "languages": "English",
                "season": None,
                "episode": None,
                "intermediate_url": "https://site-a.example/release",
                "sources": [
                    {"source_name": "V-Cloud", "service": "vcloud",
                     "resource_id": "hhqte6te-qukkzt",
                     "source_url": SHORT_URL, "final_url": SHORT_URL,
                     "is_temporary": 0},
                    {"source_name": "V-Cloud [FSL Cloudflare R2]",
                     "service": "fsl_r2", "resource_id": "hhqte6te-qukkzt",
                     "source_url": SHORT_URL,
                     "final_url": "https://old.r2.cloudflarestorage.com/hex/stale"
                                  "?X-Amz-Date=20261101T000000Z&X-Amz-Expires=28800"
                                  "&X-Amz-Signature=00",
                     "is_temporary": 1},
                    {"source_name": "V-Cloud [Google Direct CDN]",
                     "service": "google_cdn",
                     "resource_id": "hhqte6te-qukkzt",
                     "source_url": SHORT_URL,
                     "final_url": "https://video-downloads.googleusercontent.com/STALE",
                     "is_temporary": 1},
                    {"source_name": "V-Cloud [HubCloud 10Gbps]",
                     "service": "hubcloud",
                     "resource_id": "hhqte6te-qukkzt",
                     "source_url": SHORT_URL, "final_url": HUB_URL,
                     "is_temporary": 0},
                    {"source_name": "V-Cloud [Google Direct CDN]",
                     "service": "google_cdn",
                     "resource_id": "other-file",
                     "source_url": "https://vcloud.my/otherslug123456",
                     "final_url": "https://video-downloads.googleusercontent.com/OTHER",
                     "is_temporary": 1},
                ],
            }],
        }
        fast_fetch.save_scraped_data(media)
        with fast_fetch.get_db() as conn:
            self.release_id = conn.execute(
                "SELECT id FROM releases LIMIT 1").fetchone()[0]
            self.rows = {
                (r["service"], r["source_url"]): dict(r)
                for r in conn.execute(
                    "SELECT * FROM download_sources").fetchall()}
        self.assertEqual(len(self.rows), 5, self.rows)

    def _row(self, service, source_url=SHORT_URL):
        with fast_fetch.get_db() as conn:
            return dict(conn.execute(
                "SELECT * FROM download_sources "
                "WHERE service = ? AND source_url = ?",
                (service, source_url)).fetchone())

    def _canned_fetch(self):
        gpdl_final = ("https://example.invalid/x?link="
                      + urllib.parse.quote(GOOGLE_URL, safe=""))
        pages = {
            SHORT_URL: (200, SHORT_URL,
                        f"<html><body><script>{_token_script(SHORT_TOKEN_URL)}</script></body></html>"),
            SHORT_TOKEN_URL: (200, SHORT_TOKEN_URL, _token_page()),
            GPDL_URL: (200, gpdl_final, ""),
        }

        def fake_fetch(url, timeout=5, clearance=None):
            return pages[url]

        return fake_fetch

    def _resolve_pressed_vcloud(self):
        ttl_calls = []
        probe = mock.Mock(side_effect=lambda url, **kw: ttl_calls.append(url) or (
            None, {"observed": False, "reason": "no_directive"}))
        with mock.patch.object(fast_fetch, "_vcloud_fetch",
                               self._canned_fetch()), \
                mock.patch.object(fast_fetch, "probe_url_ttl", probe):
            res = fast_fetch.resolve_live_source(
                self.rows[("vcloud", SHORT_URL)]["id"])
        return res, ttl_calls

    def test_one_token_page_banks_all_siblings(self):
        """Pressing the vcloud row banks the fsl_r2 + google_cdn
        siblings filed under the SAME vcloud address — one token-page
        fetch, three rows fresh — each with its own expiry."""
        res, ttl_calls = self._resolve_pressed_vcloud()
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], FRESH_R2_URL, res)
        self.assertTrue(res["refreshed"], res)
        # Pressed row: fresh R2 URL, the unwrapper's own temp flag, and
        # the SIGNED presigned window — not the HEAD's max-age, which
        # reads the response's freshness instead of the token's life.
        pressed = self._row("vcloud")
        self.assertEqual(pressed["final_url"], FRESH_R2_URL)
        self.assertEqual(pressed["status"], "active")
        self.assertAlmostEqual(pressed["expires_at"], time.time() + 28800,
                               delta=120)
        self.assertEqual(pressed["ttl_source"], "signed_params")
        self.assertGreater(res["expires_in"], 28000)
        self.assertLessEqual(res["expires_in"], 28800)
        # Siblings: fresh URLs from the same token page, no re-resolve.
        fsl = self._row("fsl_r2")
        self.assertEqual(fsl["final_url"], FRESH_R2_URL)
        self.assertEqual(fsl["status"], "active")
        self.assertEqual(fsl["is_temporary"], 1)
        self.assertAlmostEqual(fsl["expires_at"], time.time() + 28800,
                               delta=120)
        self.assertEqual(fsl["ttl_source"], "signed_params")
        google = self._row("google_cdn")
        self.assertEqual(google["final_url"], GOOGLE_URL)
        self.assertEqual(google["status"], "active")
        self.assertEqual(google["is_temporary"], 1)
        # No signed window on the Google token: the stale measurement
        # is cleared, never carried over.
        self.assertIsNone(google["expires_at"])
        # The HubCloud bridge row is not a sibling: its landing link
        # is untouched.
        hub = self._row("hubcloud")
        self.assertEqual(hub["final_url"], HUB_URL)
        # The other file's google_cdn row is untouched: same release,
        # different vcloud address, different file.
        other = self._row("google_cdn", "https://vcloud.my/otherslug123456")
        self.assertEqual(
            other["final_url"],
            "https://video-downloads.googleusercontent.com/OTHER")
        # No rows were invented: banking UPDATEs, never INSERTs.
        with fast_fetch.get_db() as conn:
            count = conn.execute(
                "SELECT COUNT(*) FROM download_sources").fetchone()[0]
        self.assertEqual(count, 5)
        # The pressed-row TTL probe never ran on this path: the signed
        # window already answered, so no per-press HEAD round trip.
        self.assertEqual(ttl_calls, [])

    def test_api_link_press_banks_nothing_without_siblings(self):
        """An api-link vcloud row has no rows filed under its address
        (live: 0 fan-out siblings): the press still resolves, and the
        bank touches nothing."""
        with fast_fetch.get_db() as conn:
            conn.execute(
                "UPDATE download_sources SET source_url = ?, final_url = ? "
                "WHERE service = 'vcloud'",
                (API_URL, API_URL))
        pages = {
            API_URL: (200, API_URL, _api_page_without_token()),
            CY_URL: (200, CY_URL, f"<html><body><script>{_token_script(TOKEN_URL)}</script></body></html>"),
            TOKEN_URL: (200, TOKEN_URL, _token_page()),
            GPDL_URL: (200, "https://example.invalid/x?link="
                       + urllib.parse.quote(GOOGLE_URL, safe=""), ""),
        }

        def fake_fetch(url, timeout=5, clearance=None):
            return pages[url]

        with mock.patch.object(fast_fetch, "_vcloud_fetch", fake_fetch), \
                mock.patch.object(fast_fetch, "probe_url_ttl", return_value=(
                    None, {"observed": False, "reason": "no_directive"})):
            res = fast_fetch.resolve_live_source(
                self.rows[("vcloud", SHORT_URL)]["id"])
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], FRESH_R2_URL, res)
        # Siblings still carry their stale addresses: nothing banked.
        self.assertEqual(
            self._row("fsl_r2")["final_url"],
            "https://old.r2.cloudflarestorage.com/hex/stale"
            "?X-Amz-Date=20261101T000000Z&X-Amz-Expires=28800"
            "&X-Amz-Signature=00")
        self.assertEqual(
            self._row("google_cdn")["final_url"],
            "https://video-downloads.googleusercontent.com/STALE")

    def test_google_first_press_still_probes_ttl(self):
        """A fresh URL with no signed window (Google first) keeps the
        old path: the pressed row's TTL probe still runs."""
        gpdl_final = ("https://example.invalid/x?link="
                      + urllib.parse.quote(GOOGLE_URL, safe=""))
        pages = {
            SHORT_URL: (200, SHORT_URL,
                        f"<html><body><script>{_token_script(SHORT_TOKEN_URL)}</script></body></html>"),
            SHORT_TOKEN_URL: (200, SHORT_TOKEN_URL,
                              f'<a href="{GPDL_URL}">HubCloud</a>'),
            GPDL_URL: (200, gpdl_final, ""),
        }

        def fake_fetch(url, timeout=5, clearance=None):
            return pages[url]

        ttl_calls = []
        probe = mock.Mock(side_effect=lambda url, **kw: ttl_calls.append(url) or (
            None, {"observed": False, "reason": "no_directive"}))
        with mock.patch.object(fast_fetch, "_vcloud_fetch", fake_fetch), \
                mock.patch.object(fast_fetch, "probe_url_ttl", probe):
            res = fast_fetch.resolve_live_source(
                self.rows[("vcloud", SHORT_URL)]["id"])
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], GOOGLE_URL, res)
        self.assertEqual(ttl_calls, [GOOGLE_URL])

    def test_gated_out_press_banks_nothing(self):
        """A fresh URL the Stage-2 gate rejects banks nothing: the
        siblings keep their stale addresses."""
        with mock.patch.object(
                fast_fetch, "_stage2_probe",
                return_value={"ok": False, "kind": "file", "mime": None,
                              "size": None, "resumable": False,
                              "renditions": [], "failure": "not_media"}):
            res, _ttl = self._resolve_pressed_vcloud()
        self.assertFalse(res["success"], res)
        self.assertEqual(
            self._row("fsl_r2")["final_url"],
            "https://old.r2.cloudflarestorage.com/hex/stale"
            "?X-Amz-Date=20261101T000000Z&X-Amz-Expires=28800"
            "&X-Amz-Signature=00")
        self.assertEqual(
            self._row("google_cdn")["final_url"],
            "https://video-downloads.googleusercontent.com/STALE")


if __name__ == "__main__":
    unittest.main()
