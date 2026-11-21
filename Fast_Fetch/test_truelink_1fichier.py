#!/usr/bin/env python3
"""Fast_Fetch/test_truelink_1fichier.py — offline tests for the 1fichier port.

Covers ``truelink_1fichier._unwrap_1fichier`` (port of TrueLink 1.4.8
fichier.py + nurlresolver 4.0.0 fichier.js, both MIT, logic only):

- GET file page -> POST its first form's hidden fields (``a=1``) back
  to the page URL (Referer set, GET cookies re-presented) -> scrape
  the direct link from ``a.btn-general`` (TrueLink's exact button is
  ``ok btn-general btn-orange``).
- Title from the premium table (``.premium tr:first-child
  td.normal:last-child``); password via TrueLink's ``pass`` field.
- Failure classes are honest and distinct: 404 is ``dead_file``,
  403 is ``blocked``, status 0 is ``transport``, and the ct_warn
  warnings map to ``1fichier_wait`` / ``1fichier_bad_password`` /
  ``1fichier_premium`` / ``1fichier_needs_password`` /
  ``1fichier_needs_validation`` — none of them is death. No link and
  no warning is ``unverified`` (structure changed).

The fixtures are BUILT from the reference selectors above, not
captured live pages: 1fichier is never probed here (the Oracle IP is
LOCKED, error #350 — no live probes from the Oracle VM or hatch).
Live proof waits on the WARP-in-production decision (owner-only).

No network, stdlib only. The fake fetch matches the
``current_hosts.default_fetch`` call shape and FetchResult interface
(``.status``, ``.text()``, ``.final_url``, ``.headers``,
``.set_cookies``) so the module can be wired in without changes.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

BASE_DIR = Path(__file__).parent.resolve()
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import truelink_1fichier as t1f

FILE_URL = "https://1fichier.com/?a1b2c3d4e5f6g7h8"
FILE_URL_WWW = "https://www.1fichier.com/?a1b2c3d4e5f6g7h8"
DIRECT = "https://o-1.1fichier.com/a1b2c3d4e5f6g7h8/My.Movie.2024.mkv"

PAGE_OK = (
    '<html><head><title>My.Movie.2024.mkv - 1fichier.com</title></head>'
    '<body><table class="premium">'
    '<tr><td class="normal">2.1 Go</td>'
    '<td class="normal">My.Movie.2024.mkv</td></tr>'
    '<tr><td class="normal">Added 2026-11-20</td></tr>'
    "</table>"
    '<form action="/other-path" method="post">'
    '<input type="hidden" name="a" value="1" />'
    '<input type="submit" class="btn-general" value="Download" />'
    "</form></body></html>"
)

PAGE_NO_FORM = (
    "<html><body><table class=\"premium\">"
    "<tr><td class=\"normal\">My.Movie.2024.mkv</td></tr>"
    "</table><p>No download form here.</p></body></html>"
)

PAGE_NO_TITLE = (
    "<html><body>"
    '<form action="https://1fichier.com/?a1b2c3d4e5f6g7h8" method="post">'
    '<input type="hidden" name="a" value="1" />'
    "</form></body></html>"
)

POST_OK = (
    "<html><body><p>Your download is ready.</p>"
    '<a class="ok btn-general btn-orange" href="'
    + DIRECT + '">Download My.Movie.2024.mkv</a>'
    "</body></html>"
)

POST_PLAIN_BUTTON = (
    "<html><body>"
    '<a class="btn-general" href="' + DIRECT + '">Download</a>'
    "</body></html>"
)

POST_HREF_FIRST = (
    "<html><body>"
    '<a href="' + DIRECT + '" class="ok btn-general btn-orange">'
    "Download</a></body></html>"
)

POST_RELATIVE = (
    "<html><body>"
    '<a class="ok btn-general btn-orange" href="/dl/a1b2c3/file.mkv">'
    "Download</a></body></html>"
)

POST_WAIT = (
    "<html><body>"
    '<div class="ct_warn">You must wait 5 minutes before downloading '
    "another file.</div></body></html>"
)

POST_NEEDS_PASSWORD = (
    "<html><body>"
    '<div class="ct_warn">To protect access to this file, '
    "enter the password.</div></body></html>"
)

POST_BAD_PASSWORD = (
    "<html><body>"
    '<div class="ct_warn">Bad password. Please try again.</div>'
    "</body></html>"
)

POST_PREMIUM = (
    "<html><body>"
    '<div class="ct_warn">You have to create a premium account '
    "to download this file.</div></body></html>"
)

POST_VALIDATION = (
    "<html><body>"
    "<p>In order to access this file, you will have to validate "
    "a first download.</p></body></html>"
)

POST_EMPTY = "<html><body><p>Nothing here.</p></body></html>"


class _Resp:
    """Minimal FetchResult stand-in (status/text/final_url/headers/cookies)."""

    def __init__(self, status=200, body="", final_url="", headers=None,
                 set_cookies=None):
        self.status = status
        self._body = body
        self.final_url = final_url
        self.headers = headers or {}
        self.set_cookies = list(set_cookies or [])

    def text(self):
        return self._body


class FakeFetch:
    """Records calls; answers from a {(method, url): _Resp} route table."""

    def __init__(self, routes):
        self.routes = routes
        self.calls = []

    def __call__(self, url, referer=None, method="GET", data=None,
                 cookies=None, headers=None, follow_redirects=True):
        self.calls.append({
            "url": url, "referer": referer, "method": method,
            "data": dict(data or {}), "cookies": dict(cookies or {}),
            "headers": dict(headers or {}),
            "follow_redirects": follow_redirects,
        })
        resp = self.routes.get((method, url))
        if resp is None:
            return _Resp(status=404, body="not found", final_url=url)
        return resp


def _routes(page_body=PAGE_OK, post_body=POST_OK, page_status=200,
            post_status=200, page_final=None, post_final=None,
            page_cookies=None, post_headers=None):
    return {
        ("GET", FILE_URL): _Resp(status=page_status, body=page_body,
                                 final_url=page_final or FILE_URL,
                                 set_cookies=page_cookies or []),
        ("POST", page_final or FILE_URL): _Resp(
            status=post_status, body=post_body,
            final_url=post_final or (page_final or FILE_URL),
            headers=post_headers or {}),
    }


class Unwrap1FichierTests(unittest.TestCase):
    def test_happy_path_posts_hidden_form_and_scrapes_link(self):
        fetch = FakeFetch(_routes())
        backend, cands, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertEqual(backend, "1fichier")
        self.assertIsNone(failure)
        self.assertEqual(len(cands), 1)
        self.assertEqual(cands[0]["url"], DIRECT)
        self.assertEqual(cands[0]["kind_hint"], "file")
        self.assertEqual(cands[0]["title"], "My.Movie.2024.mkv")
        self.assertEqual(len(fetch.calls), 2)
        post = fetch.calls[1]
        self.assertEqual(post["method"], "POST")
        self.assertEqual(post["url"], FILE_URL)
        self.assertEqual(post["data"], {"a": "1"})
        self.assertEqual(post["referer"], FILE_URL)
        self.assertFalse(post["follow_redirects"])

    def test_form_action_is_ignored_post_goes_to_page_url(self):
        # PAGE_OK's form says action="/other-path"; both references
        # POST back to the page URL anyway.
        fetch = FakeFetch(_routes())
        t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertEqual(fetch.calls[1]["url"], FILE_URL)

    def test_plain_btn_general_falls_back(self):
        fetch = FakeFetch(_routes(post_body=POST_PLAIN_BUTTON))
        _, cands, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertIsNone(failure)
        self.assertEqual(cands[0]["url"], DIRECT)

    def test_href_before_class_attribute_order(self):
        fetch = FakeFetch(_routes(post_body=POST_HREF_FIRST))
        _, cands, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertIsNone(failure)
        self.assertEqual(cands[0]["url"], DIRECT)

    def test_relative_button_href_resolves_against_final_url(self):
        fetch = FakeFetch(_routes(post_body=POST_RELATIVE))
        _, cands, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertIsNone(failure)
        self.assertEqual(cands[0]["url"],
                         "https://1fichier.com/dl/a1b2c3/file.mkv")

    def test_get_redirect_posts_to_final_url_with_cookies(self):
        final = "https://1fichier.com/?z9y8x7w6v5u4t3s2"
        fetch = FakeFetch(_routes(
            page_final=final,
            page_cookies=["sess=abc123; path=/; HttpOnly",
                          "AD=1; path=/"]))
        _, cands, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertIsNone(failure)
        self.assertEqual(cands[0]["url"], DIRECT)
        post = fetch.calls[1]
        self.assertEqual(post["url"], final)
        self.assertEqual(post["referer"], final)
        self.assertEqual(post["cookies"], {"sess": "abc123", "AD": "1"})

    def test_password_adds_pass_field(self):
        fetch = FakeFetch(_routes())
        t1f._unwrap_1fichier(FILE_URL, fetch, password="secret")
        self.assertEqual(fetch.calls[1]["data"],
                         {"a": "1", "pass": "secret"})

    def test_post_redirect_location_is_the_direct_link(self):
        fetch = FakeFetch(_routes(
            post_status=302, post_body="",
            post_headers={"location": DIRECT}))
        _, cands, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertIsNone(failure)
        self.assertEqual(cands[0]["url"], DIRECT)

    def test_get_404_is_dead_file(self):
        fetch = FakeFetch(_routes(page_status=404, page_body="not found"))
        _, cands, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertEqual(cands, [])
        self.assertEqual(failure, "dead_file")
        self.assertEqual(len(fetch.calls), 1)

    def test_get_403_is_blocked_not_dead(self):
        fetch = FakeFetch(_routes(page_status=403, page_body="forbidden"))
        _, _, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertEqual(failure, "blocked")

    def test_get_transport_failure(self):
        fetch = FakeFetch(_routes(page_status=0, page_body=""))
        _, _, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertEqual(failure, "transport")

    def test_post_404_is_dead_file(self):
        fetch = FakeFetch(_routes(post_status=404, post_body="gone"))
        _, _, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertEqual(failure, "dead_file")

    def test_no_form_on_page_is_unverified(self):
        fetch = FakeFetch(_routes(page_body=PAGE_NO_FORM))
        _, _, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertEqual(failure, "unverified")
        self.assertEqual(len(fetch.calls), 1)

    def test_no_link_no_warning_is_unverified(self):
        fetch = FakeFetch(_routes(post_body=POST_EMPTY))
        _, _, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertEqual(failure, "unverified")

    def test_wait_warning_is_rate_limited_not_dead(self):
        fetch = FakeFetch(_routes(post_body=POST_WAIT))
        _, _, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertEqual(failure, "1fichier_wait")

    def test_password_required_warning(self):
        fetch = FakeFetch(_routes(post_body=POST_NEEDS_PASSWORD))
        _, _, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertEqual(failure, "1fichier_needs_password")

    def test_bad_password_warning(self):
        fetch = FakeFetch(_routes(post_body=POST_BAD_PASSWORD))
        _, _, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertEqual(failure, "1fichier_bad_password")

    def test_premium_warning(self):
        fetch = FakeFetch(_routes(post_body=POST_PREMIUM))
        _, _, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertEqual(failure, "1fichier_premium")

    def test_validation_string_is_needs_validation(self):
        fetch = FakeFetch(_routes(post_body=POST_VALIDATION))
        _, _, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertEqual(failure, "1fichier_needs_validation")

    def test_missing_title_is_not_an_error(self):
        fetch = FakeFetch(_routes(page_body=PAGE_NO_TITLE))
        _, cands, failure = t1f._unwrap_1fichier(FILE_URL, fetch)
        self.assertIsNone(failure)
        self.assertIsNone(cands[0]["title"])


class HelpersTests(unittest.TestCase):
    def test_is_1fichier_url(self):
        self.assertTrue(t1f.is_1fichier_url(FILE_URL))
        self.assertTrue(t1f.is_1fichier_url(FILE_URL_WWW))
        self.assertTrue(t1f.is_1fichier_url(
            "https://1fichier.com/?a1b2c3"))
        self.assertFalse(t1f.is_1fichier_url("https://1fichier.org/?x"))
        self.assertFalse(t1f.is_1fichier_url(
            "https://example.com/1fichier.com/?x"))
        self.assertFalse(t1f.is_1fichier_url("not a url"))

    def test_parse_hidden_form_collects_all_inputs(self):
        html = (
            '<form method="post">'
            '<input type="hidden" name="a" value="1">'
            "<input name='dl' value='xyz'>"
            '<input type="hidden" name="empty" value="">'
            '<input type="hidden" value="no-name">'
            '<input type="hidden" name="no-value">'
            "</form>"
        )
        self.assertEqual(t1f.parse_hidden_form(html),
                         {"a": "1", "dl": "xyz", "empty": ""})

    def test_parse_hidden_form_first_form_wins(self):
        html = (
            '<form><input name="a" value="1"></form>'
            '<form><input name="a" value="2"></form>'
        )
        self.assertEqual(t1f.parse_hidden_form(html), {"a": "1"})
        self.assertIsNone(t1f.parse_hidden_form("<html>no form</html>"))

    def test_scrape_premium_title_last_normal_cell(self):
        html = (
            '<table class="premium"><tr>'
            '<td class="normal">2.1 Go</td>'
            '<td class="normal">Film &amp; Co.mkv</td>'
            "</tr></table>"
        )
        self.assertEqual(t1f.scrape_premium_title(html), "Film & Co.mkv")
        self.assertIsNone(t1f.scrape_premium_title("<html></html>"))

    def test_module_imports_without_the_repo(self):
        # Standalone: no current_hosts / fast_fetch import at module load.
        import importlib
        module = importlib.import_module("truelink_1fichier")
        self.assertEqual(module.BACKEND, "1fichier")


if __name__ == "__main__":
    unittest.main()
