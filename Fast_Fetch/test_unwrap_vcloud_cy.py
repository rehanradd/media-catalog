#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline tests for the vcloud ``_cy``-first unwrap (sweep H1, 2026-11-23).

The audit behind this slice: the live ``vcloud.my/api/index.php?link=``
page no longer carries ``atob(atob(...))`` — it links a ``_cy``
intermediate page (``/_cy-<slug>``) that does. The old
``unwrap_vcloud`` searched only the first page and returned the landing
URL when the token was absent, so it now missed on every current link.
What this pins:

* the live chain: api page -> ``_cy`` page (relative href resolved
  against the api page's final URL) -> double-base64 token -> token
  page -> server buttons, with the ``_cy`` hop using the same 5 s /
  0-retry fetch and the same clearance as the other hops;
* the legacy path is kept: a token already on the first page still
  unwraps with exactly two fetches and no ``_cy`` hop;
* the refusals: no ``_cy`` link, a failed ``_cy`` fetch, or a ``_cy``
  page with no token all return the landing page unchanged (temp 0)
  so the caller's bridge guard refuses them — never a half-decoded URL.

No network, no production DB: ``http_get`` and ``unwrap_hubcloud``
are stubbed. Fixture tokens/URLs are fake.
"""

from __future__ import annotations

import base64
import os
import sys
import unittest
from unittest import mock

os.environ.setdefault("RADD_SKIP_LIVE_ENRICH", "1")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fast_fetch  # noqa: E402

API_URL = ("https://vcloud.my/api/index.php?link="
           "FAKELINKVALUEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==")
CY_HREF = "/_cy-1axevcv1ci_"
CY_URL = "https://vcloud.my/_cy-1axevcv1ci_"
TOKEN_URL = "https://token.example/x"
GOOGLE_URL = ("https://video-downloads.googleusercontent.com/"
              "FAKEADGPM2lDlJqym4xgQRGP8uIqg7TRpJmiuE1IV4K9s")


def _double_b64(url: str) -> str:
    return base64.b64encode(
        base64.b64encode(url.encode()).decode().encode()).decode()


def _token_script(url: str = TOKEN_URL) -> str:
    return f"var url = atob(atob('{_double_b64(url)}'));"


def _api_page_without_token(cy_href: str = CY_HREF) -> str:
    return ('<html><head><title>V-Cloud | File</title></head><body>'
            f'<a href="{cy_href}" class="btn">Direct Download</a>'
            "</body></html>")


def _api_page_with_token(url: str = TOKEN_URL) -> str:
    return f"<html><body><script>{_token_script(url)}</script></body></html>"


def _cy_page(url: str = TOKEN_URL) -> str:
    return f"<html><body><script>{_token_script(url)}</script></body></html>"


def _token_page() -> str:
    return ('<a href="https://gpdl2.hubcloud.example/1">HubCloud</a>')


class UnwrapVcloudCyTests(unittest.TestCase):
    def test_cy_page_fetched_and_decoded(self):
        """Live chain: api page (no token) -> _cy page -> token page.

        The _cy hop is the second fetch, its URL is the relative href
        resolved against the api page, and the decoded token page's
        HubCloud button fans out through unwrap_hubcloud.
        """
        getter = mock.Mock(side_effect=[
            (200, API_URL, _api_page_without_token()),
            (200, CY_URL, _cy_page()),
            (200, TOKEN_URL, _token_page()),
        ])
        with mock.patch.object(fast_fetch, "http_get", getter), \
                mock.patch.object(
                    fast_fetch, "unwrap_hubcloud",
                    return_value=(GOOGLE_URL, "google_cdn", 1)):
            out = fast_fetch.unwrap_vcloud(API_URL)
        self.assertEqual(getter.call_count, 3, getter.call_args_list)
        self.assertEqual(getter.call_args_list[1].args[0], CY_URL)
        for call in getter.call_args_list:
            self.assertEqual(call.kwargs.get("timeout"), 5)
            self.assertEqual(call.kwargs.get("max_retries"), 0)
        by_service = {svc: url for _name, svc, url, _t in out}
        self.assertEqual(by_service.get("google_cdn"), GOOGLE_URL)

    def test_cy_absolute_href_used_as_is(self):
        """A fully qualified _cy href is fetched verbatim."""
        getter = mock.Mock(side_effect=[
            (200, API_URL, _api_page_without_token(CY_URL)),
            (200, CY_URL, _cy_page()),
            (200, TOKEN_URL, "<html>no links</html>"),
        ])
        with mock.patch.object(fast_fetch, "http_get", getter):
            out = fast_fetch.unwrap_vcloud(API_URL)
        self.assertEqual(getter.call_args_list[1].args[0], CY_URL)
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0][2], TOKEN_URL)

    def test_first_page_token_still_works_as_fallback(self):
        """Legacy path: token on the first page unwraps with two
        fetches and no _cy hop."""
        getter = mock.Mock(side_effect=[
            (200, API_URL, _api_page_with_token()),
            (200, TOKEN_URL, "<html>no links</html>"),
        ])
        with mock.patch.object(fast_fetch, "http_get", getter):
            out = fast_fetch.unwrap_vcloud(API_URL)
        self.assertEqual(getter.call_count, 2, getter.call_args_list)
        fetched = [c.args[0] for c in getter.call_args_list]
        self.assertNotIn(CY_URL, fetched)
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0][2], TOKEN_URL)

    def test_no_cy_link_returns_landing(self):
        """First page with neither token nor _cy link: landing back,
        one fetch, temp 0."""
        getter = mock.Mock(return_value=(
            200, API_URL, "<html><body>no token here</body></html>"))
        with mock.patch.object(fast_fetch, "http_get", getter):
            out = fast_fetch.unwrap_vcloud(API_URL)
        self.assertEqual(getter.call_count, 1)
        self.assertEqual(out, [("V-Cloud", "vcloud", API_URL, 0)])

    def test_cy_fetch_fails_returns_landing(self):
        """_cy link present but its fetch fails: landing back, temp 0."""
        getter = mock.Mock(side_effect=[
            (200, API_URL, _api_page_without_token()),
            (0, CY_URL, "ERROR: timeout"),
        ])
        with mock.patch.object(fast_fetch, "http_get", getter):
            out = fast_fetch.unwrap_vcloud(API_URL)
        self.assertEqual(getter.call_count, 2)
        self.assertEqual(out, [("V-Cloud", "vcloud", API_URL, 0)])

    def test_cy_page_without_token_returns_landing(self):
        """_cy page fetched but carrying no token: landing back, temp 0."""
        getter = mock.Mock(side_effect=[
            (200, API_URL, _api_page_without_token()),
            (200, CY_URL, "<html><body>shell, no token</body></html>"),
        ])
        with mock.patch.object(fast_fetch, "http_get", getter):
            out = fast_fetch.unwrap_vcloud(API_URL)
        self.assertEqual(getter.call_count, 2)
        self.assertEqual(out, [("V-Cloud", "vcloud", API_URL, 0)])

    def test_clearance_forwarded_to_cy_fetch(self):
        """The H4 R8 clearance rides on the _cy hop as well as the
        api and token hops."""
        sentinel = object()
        seen = []

        def fake_http_get(url, timeout=5, max_retries=0, clearance=None):
            seen.append(clearance)
            if url == API_URL:
                return (200, API_URL, _api_page_without_token())
            if url == CY_URL:
                return (200, CY_URL, _cy_page())
            return (200, url, "<html>no links</html>")

        with mock.patch.object(fast_fetch, "http_get", fake_http_get):
            out = fast_fetch.unwrap_vcloud(API_URL, clearance=sentinel)
        self.assertEqual(len(seen), 3)
        self.assertTrue(all(c is sentinel for c in seen))
        self.assertEqual(out[0][2], TOKEN_URL)

    def test_default_domain_is_vcloud_my(self):
        """Sweep H1: vcloud rows are vcloud.my (14,538); vcloud.beer
        has 0 rows and 301s to .my — the static default must not
        point at the dead origin."""
        self.assertEqual(fast_fetch.DEFAULT_DOMAINS["vcloud"],
                         "https://vcloud.my")
        self.assertIn("my", fast_fetch.SITE_CANDIDATE_TLDS["vcloud"])
        self.assertEqual(
            fast_fetch.SITE_CANDIDATE_TLDS["vcloud"][0], "my")


if __name__ == "__main__":
    unittest.main()
