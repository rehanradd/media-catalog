#!/usr/bin/env python3
"""
Offline tests for the multiup unwrapper in host_resolvers.py — no live
socket, no database, no pip.

The fixtures are REDUCED from the real pages as fetched 2026-11-20: the
download page for /download/3572141cc0682e3b05f9a602cc4ffbd4 (Girl
Interrupted 720p) is ~488 KB with the mirror form + _csrf_token at ~464 KB,
and the mirror list ("Liste d'hébergeurs") puts its links at ~464-476 KB —
both past the module's 200 KB PROBE_READ_BYTES, which is why the unwrapper
reads through a jar fetch with MULTIUP_READ_BYTES. The fixtures keep exactly
what the parser must survive: the form action, the token input, the four
real mirrors (gofile, mixdrop, dailyuploads, 1fichier with an &amp;), and
the share/reseller/upsell links that must NOT be picked as mirrors.

Every request goes through an injected `fetch`, so the network code is
covered by calling the real function with a fake transport, never by
monkey-patching a call away. `socket.socket` is replaced with a raiser so an
accidental live request fails the run instead of passing it.
"""

from __future__ import annotations

import socket
import sys
import unittest
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import host_resolvers as hr  # noqa: E402


class _NoLiveSocket:
    def __init__(self, *a, **k):
        raise AssertionError("test attempted a live socket")


socket.socket = _NoLiveSocket  # type: ignore[attr-defined]


DOWNLOAD_ID = "3572141cc0682e3b05f9a602cc4ffbd4"
PAGE_URL = (
    "https://multiup.io/download/" + DOWNLOAD_ID +
    "/Girl%20Interrupted%20%281999%29%20%20720p%20%20BluRay%20%20%20x264%20%20ESubs.mkv")
MIRROR_ACTION = "/fr/mirror/" + DOWNLOAD_ID
CSRF_TOKEN = "159a23d6607856e5f5e.0qKNh7B6OJSw4owqRbF3pLZ313ll-iSYp7MaCo85nhI.g-nD6PQCZ_PUq_hLN_ZP5_cEpkEtoBD88YBqR8pe2GSY47XxwyUO8OfUwQ"

DOWNLOAD_PAGE = """<html><head><title>T\xc3\xa9l\xc3\xa9charger Girl Interrupted (1999) - Upload miroir - MultiUp.io</title></head>
<body>
<a href="/download-fast/""" + DOWNLOAD_ID + """/Girl.mkv">T\xc3\xa9l\xc3\xa9charger plus rapidement sur USENET</a>
<form action=\"""" + MIRROR_ACTION + """" method="post" target="1965604609" onsubmit="window.open('','1965604609')">
<div class="panel-body text-center">
<a href="/download-fast/""" + DOWNLOAD_ID + """/Girl.mkv" class="btn btn-success">USENET</a>
<input type="hidden" class="hidden" name="_csrf_token" value=\"""" + CSRF_TOKEN + """" />
<button id="download-button" class="btn btn-info" type="submit"><strong>T\xc3\xa9l\xc3\xa9charger</strong></button>
</div></form>
</body></html>"""

MIRROR_PAGE = """<html><head><title>Liste d'h\xc3\xa9bergeurs Girl Interrupted (1999) - Upload mirroir - MultiUp.io</title>
<meta name="description" content="Mirror list Girl Interrupted (1999) (1.36 GB) on gofile.io mixdrop.ag dailyuploads.net 1fichier.com">
</head><body>
<a href="https://www.facebook.com/multiup.io">fb</a>
<a href="https://bit.ly/2pw5x86">short</a>
<a href="https://gofile.io/d/ldocgtHD">gofile</a>
<a href="https://mixdrop.ag/f/pjjpz9w9bnzxpw">mixdrop</a>
<a href="https://dailyuploads.net/y6jrm8dwbb8f">dailyuploads</a>
<a href="https://1fichier.com/?ybg5v1vw37huj7ei43zk&amp;af=62851">1fichier</a>
<a href="https://filesharingkey.com/multiup-premium-key.html">reseller</a>
<a href="/download-fast/""" + DOWNLOAD_ID + """/Girl.mkv">USENET</a>
</body></html>"""


class _FakeFetch:
    """(status, body) per (method, url); records every call."""

    def __init__(self, routes):
        self.routes = routes
        self.calls = []

    def __call__(self, url, headers=None, timeout=None, method="GET",
                 follow=True, data=None):
        self.calls.append({"url": url, "method": method, "data": data,
                           "headers": dict(headers or {})})
        status, body = self.routes.get((method, url), (0, "no route"))
        return status, body, {}, url


def _page_fetch(page_body=DOWNLOAD_PAGE, mirror_body=MIRROR_PAGE,
                page_status=200, mirror_status=200):
    routes = {
        ("GET", PAGE_URL): (page_status, page_body),
        ("POST", "https://multiup.io" + MIRROR_ACTION): (mirror_status, mirror_body),
    }
    return _FakeFetch(routes)


class MultiupUrlTests(unittest.TestCase):
    def test_download_id_from_stored_url(self):
        self.assertEqual(hr.multiup_download_id(PAGE_URL), DOWNLOAD_ID)

    def test_download_id_bare_and_locale(self):
        self.assertEqual(
            hr.multiup_download_id("https://multiup.io/download/" + DOWNLOAD_ID),
            DOWNLOAD_ID)
        self.assertEqual(
            hr.multiup_download_id("https://multiup.io/fr/download/" + DOWNLOAD_ID + "/x.mkv"),
            DOWNLOAD_ID)
        self.assertEqual(
            hr.multiup_download_id(PAGE_URL + "?_locale=en"), DOWNLOAD_ID)

    def test_download_id_rejects_other_hosts_and_shapes(self):
        self.assertEqual(hr.multiup_download_id("https://megaup.net/" + DOWNLOAD_ID + "/x.mkv"), "")
        self.assertEqual(hr.multiup_download_id("https://multiup.io/upload"), "")
        self.assertEqual(hr.multiup_download_id(""), "")

    def test_is_multiup_url(self):
        self.assertTrue(hr.is_multiup_url(PAGE_URL))
        self.assertFalse(hr.is_multiup_url("https://gofile.io/d/ldocgtHD"))
        self.assertFalse(hr.is_multiup_url("https://multiup.io/"))


class MultiupUnwrapTests(unittest.TestCase):
    def test_returns_best_mirror_unverified(self):
        fetch = _page_fetch()
        verdict = hr.unwrap_multiup(PAGE_URL, fetch=fetch)
        self.assertEqual(verdict["status"], "unverified")
        self.assertEqual(verdict["final_url"], "https://gofile.io/d/ldocgtHD")
        self.assertEqual(verdict["mirror_host"], "gofile.io")
        self.assertEqual(verdict["service"], "multiup")
        self.assertEqual(verdict["file_id"], DOWNLOAD_ID)
        self.assertEqual(verdict["sources"],
                         [{"url": "https://gofile.io/d/ldocgtHD",
                           "mime": "", "label": "gofile.io"}])
        self.assertIn("4 mirror(s)", verdict["notes"][0])
        # The input URL is never served back as the result.
        self.assertNotEqual(verdict["final_url"], PAGE_URL)

    def test_post_carries_page_token_and_referer(self):
        fetch = _page_fetch()
        hr.unwrap_multiup(PAGE_URL, fetch=fetch)
        posts = [c for c in fetch.calls if c["method"] == "POST"]
        self.assertEqual(len(posts), 1)
        self.assertEqual(posts[0]["url"], "https://multiup.io" + MIRROR_ACTION)
        body = urllib.parse.parse_qs(posts[0]["data"].decode("ascii"))
        self.assertEqual(body.get("_csrf_token"), [CSRF_TOKEN])
        self.assertEqual(posts[0]["headers"].get("Referer"), PAGE_URL)
        self.assertEqual(
            posts[0]["headers"].get("Content-Type"),
            "application/x-www-form-urlencoded")

    def test_verify_flag_changes_nothing_and_mirror_is_never_fetched(self):
        for verify in (True, False):
            fetch = _page_fetch()
            verdict = hr.unwrap_multiup(PAGE_URL, fetch=fetch, verify=verify)
            self.assertEqual(verdict["status"], "unverified")
            self.assertEqual(verdict["final_url"], "https://gofile.io/d/ldocgtHD")
            hosts = {urllib.parse.urlsplit(c["url"]).netloc for c in fetch.calls}
            self.assertEqual(hosts, {"multiup.io"})
            self.assertEqual(len(fetch.calls), 2)

    def test_mixdrop_preferred_when_no_gofile(self):
        mirror = MIRROR_PAGE.replace(
            '<a href="https://gofile.io/d/ldocgtHD">gofile</a>\n', "")
        verdict = hr.unwrap_multiup(PAGE_URL, fetch=_page_fetch(mirror_body=mirror))
        self.assertEqual(verdict["final_url"], "https://mixdrop.ag/f/pjjpz9w9bnzxpw")
        self.assertEqual(verdict["mirror_host"], "mixdrop.ag")

    def test_page_order_fallback_when_no_resolver_backed_mirror(self):
        mirror = MIRROR_PAGE.replace(
            '<a href="https://gofile.io/d/ldocgtHD">gofile</a>\n', "").replace(
            '<a href="https://mixdrop.ag/f/pjjpz9w9bnzxpw">mixdrop</a>\n', "")
        verdict = hr.unwrap_multiup(PAGE_URL, fetch=_page_fetch(mirror_body=mirror))
        self.assertEqual(verdict["final_url"], "https://dailyuploads.net/y6jrm8dwbb8f")

    def test_amp_entity_unescaped_in_mirror_link(self):
        mirror = MIRROR_PAGE.replace(
            '<a href="https://gofile.io/d/ldocgtHD">gofile</a>\n', "").replace(
            '<a href="https://mixdrop.ag/f/pjjpz9w9bnzxpw">mixdrop</a>\n', "").replace(
            '<a href="https://dailyuploads.net/y6jrm8dwbb8f">dailyuploads</a>\n', "")
        verdict = hr.unwrap_multiup(PAGE_URL, fetch=_page_fetch(mirror_body=mirror))
        self.assertEqual(
            verdict["final_url"],
            "https://1fichier.com/?ybg5v1vw37huj7ei43zk&af=62851")
        self.assertNotIn("&amp;", verdict["final_url"])

    def test_share_reseller_and_usenet_links_are_never_mirrors(self):
        links = hr._multiup_mirror_links(MIRROR_PAGE)
        self.assertEqual(links, [
            "https://gofile.io/d/ldocgtHD",
            "https://mixdrop.ag/f/pjjpz9w9bnzxpw",
            "https://dailyuploads.net/y6jrm8dwbb8f",
            "https://1fichier.com/?ybg5v1vw37huj7ei43zk&af=62851",
        ])

    def test_dead_page_is_dead(self):
        verdict = hr.unwrap_multiup(PAGE_URL, fetch=_page_fetch(page_status=404))
        self.assertEqual(verdict["status"], "dead")
        self.assertEqual(verdict["final_url"], "")

    def test_blocked_page_is_blocked(self):
        verdict = hr.unwrap_multiup(PAGE_URL, fetch=_page_fetch(page_status=403))
        self.assertEqual(verdict["status"], "blocked")

    def test_transport_failure_is_unknown_never_dead(self):
        # The Oracle VM egress TLS reset surfaces exactly like this.
        fetch = _FakeFetch({("GET", PAGE_URL): (0, "Connection reset by peer")})
        verdict = hr.unwrap_multiup(PAGE_URL, fetch=fetch)
        self.assertEqual(verdict["status"], "unknown")
        self.assertIn("HTTP 0", verdict["notes"][0])

    def test_page_without_token_or_form_is_unknown(self):
        verdict = hr.unwrap_multiup(
            PAGE_URL, fetch=_page_fetch(page_body="<html><body>no form here</body></html>"))
        self.assertEqual(verdict["status"], "unknown")
        self.assertIn("no mirror form", verdict["notes"][0])

    def test_mirror_post_failure_is_not_dead(self):
        verdict = hr.unwrap_multiup(PAGE_URL, fetch=_page_fetch(mirror_status=429))
        self.assertEqual(verdict["status"], "blocked")
        self.assertIn("mirror list HTTP 429", verdict["notes"][0])

    def test_mirror_list_without_links_is_unknown(self):
        verdict = hr.unwrap_multiup(
            PAGE_URL, fetch=_page_fetch(mirror_body="<html><body>empty</body></html>"))
        self.assertEqual(verdict["status"], "unknown")
        self.assertIn("no external mirror link", verdict["notes"][0])

    def test_not_a_multiup_url_is_unknown(self):
        verdict = hr.unwrap_multiup("https://gofile.io/d/ldocgtHD",
                                    fetch=_page_fetch())
        self.assertEqual(verdict["status"], "unknown")
        self.assertIn("not a multiup download URL", verdict["notes"][0])

    def test_hidden_inputs_survive_attribute_reorder(self):
        page = DOWNLOAD_PAGE.replace(
            'name="_csrf_token" value="' + CSRF_TOKEN + '"',
            'value="' + CSRF_TOKEN + '" name="_csrf_token"')
        verdict = hr.unwrap_multiup(PAGE_URL, fetch=_page_fetch(page_body=page))
        self.assertEqual(verdict["final_url"], "https://gofile.io/d/ldocgtHD")

    def test_mirror_form_action_locale_is_honoured(self):
        page = DOWNLOAD_PAGE.replace("/fr/mirror/", "/en/mirror/")
        routes = {
            ("GET", PAGE_URL): (200, page),
            ("POST", "https://multiup.io/en/mirror/" + DOWNLOAD_ID): (200, MIRROR_PAGE),
        }
        verdict = hr.unwrap_multiup(PAGE_URL, fetch=_FakeFetch(routes))
        self.assertEqual(verdict["final_url"], "https://gofile.io/d/ldocgtHD")


class MultiupWiringTests(unittest.TestCase):
    def test_resolver_by_service(self):
        self.assertIs(hr.resolver_for_service("multiup"), hr.unwrap_multiup)
        self.assertIs(hr.resolver_for_service("MULTIUP"), hr.unwrap_multiup)

    def test_shape_routing_precedes_mxdrop_catch_all(self):
        entries = list(hr.SERVICE_RESOLVERS)
        services = [service for service, _pred, _fn in entries]
        self.assertIn(hr.SERVICE_MULTIUP, services)
        self.assertLess(services.index(hr.SERVICE_MULTIUP),
                        services.index(hr.SERVICE_MXDROP))
        for service, predicate, fn in entries:
            if service == hr.SERVICE_MULTIUP:
                self.assertTrue(predicate(PAGE_URL))
                self.assertFalse(predicate("https://gofile.io/d/ldocgtHD"))
                self.assertIs(fn, hr.unwrap_multiup)

    def test_cookie_jar_fetch_accepts_a_read_bytes_cap(self):
        fetch = hr._cookie_jar_fetch(read_bytes=hr.MULTIUP_READ_BYTES)
        self.assertTrue(callable(fetch))
        self.assertGreaterEqual(hr.MULTIUP_READ_BYTES, 488_000)


if __name__ == "__main__":
    unittest.main()
