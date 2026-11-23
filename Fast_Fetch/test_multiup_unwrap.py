#!/usr/bin/env python3
"""Offline tests for the multiup mirror-hub unwrap (Phase C, 2026-11-20).

Stdlib only, zero network: every fetch goes through the ``_http`` seam and
every mirror unwrap through the ``_resolvers`` seam that
``fast_fetch.unwrap_multiup`` already exposes, so no test can knock on a
third party or slow a CI box.

The chain under test is the one proven live from the Oracle VM egress on
20 Nov 2026: GET the download page (CSRF token + session cookie), POST the
token to /en/mirror/<id>, parse the plain mirror anchors, unwrap the best
mirror (gofile, then mixdrop) with the existing resolvers. Fixtures below
are small synthetic pages with the same structure as the real pages fetched
that day — never the recorded 60 KB pages (they carry a live session token).

Ordering (speed, 23 Nov 2026 — user Chrome capture, The Terminal List
720p): mirrors are tried in Multiup's own validity order first — every
"valid" mirror, then every "unknown" one — and "invalid" mirrors are never
attempted. The capture had exactly one Valid mirror (mixdrop, live) while
gofile was Unknown and dailyuploads + 1fichier were Invalid; the old
host-major order paid a gofile unwrap before reaching the Valid mirror.
`ValidityFirstTests` pins that order; the older tests pin the chain itself.

    python3 -S Fast_Fetch/test_multiup_unwrap.py
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).parent.resolve()))

import fast_fetch  # noqa: E402

FILE_ID = "237586a5b8d4b4353680788bc76552a7"
OTHER_ID = "624dfb8c2f1036c6c84370209c4c8a78"
DOWNLOAD_URL = f"https://multiup.io/download/{FILE_ID}/Overlord%202024%20480p.mkv"
ORG_URL = f"https://multiup.org/download/{OTHER_ID}/"
GOFILE_LINK = "https://gofile.io/d/VrmvqV"
MIXDROP_LINK = "https://mixdrop.ag/f/pjmm1d8zuogown"
MIXDROP_EMBED = "https://mixdrop.ag/e/pjmm1d8zuogown"
DIRECT_MP4 = "https://store-xyz.gofile.io/download/abc/movie.mkv"


def download_page(token: str = "cc50c6b04cc763373.EA1234567890abcdef") -> str:
    return (
        "<html><head><title>Overlord 2024 - Mirror Upload - MultiUp.io</title></head>"
        "<body><form action=\"/en/mirror/" + FILE_ID + "\" method=\"post\">"
        "<input type=\"hidden\" class=\"hidden\" name=\"_csrf_token\" value=\""
        + token + "\" />"
        "<button type=\"submit\">Download</button></form></body></html>"
    )


def mirror_anchor(host: str, link: str, validity: str = "unknown") -> str:
    # Default "unknown", like the parser's own default for a missing
    # validity attribute — an anchor that says nothing about itself is a
    # fallback candidate, never an invalid one.
    return (
        f'<a href="{link}" target="_blank" nameHost="{host}" '
        f'link="{link}" validity="{validity}">Download</a>'
    )


def mirror_page(anchors) -> str:
    body = "\n".join(anchors)
    return (
        "<html><head><title>Mirror list - MultiUp.io</title></head><body>"
        "<h2>Mirror links</h2><p>Download on your favorite host</p>"
        + body + "</body></html>"
    )


class FakeHttp:
    """The ``_http`` seam: canned (status, final_url, body) per method+URL."""

    def __init__(self, pages: dict):
        self.pages = pages
        self.calls: list = []

    def __call__(self, method, url, data=None, referer=None, timeout=12):
        self.calls.append((method, url, data, referer))
        return self.pages.get((method, url),
                              (404, url, ""))


class StubResolvers:
    """The ``_resolvers`` seam: canned verdicts, records every mirror tried."""

    def __init__(self, gofile=None, mixdrop=None):
        self.gofile = gofile
        self.mixdrop = mixdrop
        self.tried: list = []

    def unwrap_gofile(self, url, verify=True):
        self.tried.append(("gofile", url))
        return dict(self.gofile or {"status": "dead", "notes": ["stub"]})

    def unwrap_mxdrop(self, url, verify=True):
        self.tried.append(("mixdrop", url))
        return dict(self.mixdrop or {"status": "dead", "notes": ["stub"]})


def active_verdict(final_url: str) -> dict:
    return {"status": "active", "final_url": final_url, "is_temporary": 1,
            "notes": []}


class ExtractIdTests(unittest.TestCase):
    def test_io_url_with_filename(self):
        self.assertEqual(fast_fetch.multiup_extract_id(DOWNLOAD_URL), FILE_ID)

    def test_org_url_bare_id(self):
        self.assertEqual(fast_fetch.multiup_extract_id(ORG_URL), OTHER_ID)

    def test_trailing_slash_and_query(self):
        url = f"https://multiup.io/download/{FILE_ID}/?x=1"
        self.assertEqual(fast_fetch.multiup_extract_id(url), FILE_ID)

    def test_non_multiup_is_empty(self):
        self.assertEqual(fast_fetch.multiup_extract_id(
            "https://gofile.io/d/VrmvqV"), "")
        self.assertEqual(fast_fetch.multiup_extract_id(""), "")
        self.assertEqual(fast_fetch.multiup_extract_id(
            "https://multiup.io/en/mirror/" + FILE_ID), "")


class ParseMirrorsTests(unittest.TestCase):
    def test_page_order_and_fields(self):
        page = mirror_page([
            mirror_anchor("UseNet", f"/download-fast/{FILE_ID}/Movie.mkv", "valid"),
            mirror_anchor("gofile.io", GOFILE_LINK, "invalid"),
            mirror_anchor("mixdrop.ag", MIXDROP_LINK, "unknown"),
        ])
        mirrors = fast_fetch.multiup_parse_mirrors(page)
        self.assertEqual([m["host"] for m in mirrors],
                         ["UseNet", "gofile.io", "mixdrop.ag"])
        self.assertEqual([m["validity"] for m in mirrors],
                         ["valid", "invalid", "unknown"])
        self.assertEqual(mirrors[1]["link"], GOFILE_LINK)
        # The Usenet upsell carries a relative link; it is kept, not dropped.
        self.assertTrue(mirrors[0]["link"].startswith("/download-fast/"))

    def test_ampersand_unescaped(self):
        page = mirror_page([
            mirror_anchor("1fichier.com",
                          "https://1fichier.com/?abc&amp;af=62851", "invalid"),
        ])
        mirrors = fast_fetch.multiup_parse_mirrors(page)
        self.assertEqual(mirrors[0]["link"],
                         "https://1fichier.com/?abc&af=62851")

    def test_empty_and_garbage(self):
        self.assertEqual(fast_fetch.multiup_parse_mirrors(""), [])
        self.assertEqual(fast_fetch.multiup_parse_mirrors("<html></html>"), [])


class UnwrapTests(unittest.TestCase):
    def _http_for(self, mirror_anchors, token="tok123"):
        mirror_url = f"https://multiup.io/en/mirror/{FILE_ID}"
        return FakeHttp({
            ("GET", DOWNLOAD_URL): (200, DOWNLOAD_URL, download_page(token)),
            ("POST", mirror_url): (200, mirror_url,
                                   mirror_page(mirror_anchors)),
        })

    def test_gofile_active_wins(self):
        http = self._http_for([mirror_anchor("gofile.io", GOFILE_LINK)])
        resolvers = StubResolvers(gofile=active_verdict(DIRECT_MP4))
        direct, rid, is_temp, verdict = fast_fetch.unwrap_multiup(
            DOWNLOAD_URL, _http=http, _resolvers=resolvers)
        self.assertEqual(direct, DIRECT_MP4)
        self.assertEqual(rid, FILE_ID)
        self.assertEqual(is_temp, 1)
        self.assertEqual(verdict["status"], "active")
        # GET first, then POST to the mirror endpoint carrying the token.
        self.assertEqual(http.calls[0][0], "GET")
        self.assertEqual(http.calls[1][0], "POST")
        self.assertIn(f"/en/mirror/{FILE_ID}", http.calls[1][1])
        self.assertIn(b"_csrf_token=tok123", http.calls[1][2] or b"")
        self.assertEqual(resolvers.tried, [("gofile", GOFILE_LINK)])

    def test_gofile_dead_falls_through_to_mixdrop(self):
        http = self._http_for([
            mirror_anchor("gofile.io", GOFILE_LINK),
            mirror_anchor("mixdrop.ag", MIXDROP_LINK),
        ])
        resolvers = StubResolvers(
            gofile={"status": "dead", "notes": ["folder deleted"]},
            mixdrop=active_verdict(DIRECT_MP4))
        direct, _rid, _temp, verdict = fast_fetch.unwrap_multiup(
            DOWNLOAD_URL, _http=http, _resolvers=resolvers)
        self.assertEqual(direct, DIRECT_MP4)
        self.assertEqual(verdict["status"], "active")
        # The mixdrop mirror is the /f/ file page; the embed /e/ is unwrapped.
        self.assertEqual(resolvers.tried,
                         [("gofile", GOFILE_LINK),
                          ("mixdrop", MIXDROP_EMBED)])

    def test_all_mirrors_dead_is_empty(self):
        http = self._http_for([
            mirror_anchor("gofile.io", GOFILE_LINK),
            mirror_anchor("mixdrop.ag", MIXDROP_LINK),
        ])
        resolvers = StubResolvers(
            gofile={"status": "dead", "notes": ["folder deleted"]},
            mixdrop={"status": "dead", "notes": ["video cannot be found"]})
        self.assertEqual(
            fast_fetch.unwrap_multiup(DOWNLOAD_URL, _http=http,
                                      _resolvers=resolvers),
            ("", FILE_ID, 0, None))

    def test_usenet_only_never_calls_a_resolver(self):
        http = self._http_for([
            mirror_anchor("UseNet", f"/download-fast/{FILE_ID}/M.mkv", "valid"),
        ])
        resolvers = StubResolvers(gofile=active_verdict(DIRECT_MP4))
        self.assertEqual(
            fast_fetch.unwrap_multiup(DOWNLOAD_URL, _http=http,
                                      _resolvers=resolvers),
            ("", FILE_ID, 0, None))
        self.assertEqual(resolvers.tried, [])

    def test_download_404_fails_fast_without_post(self):
        http = FakeHttp({("GET", DOWNLOAD_URL): (404, DOWNLOAD_URL, "")})
        resolvers = StubResolvers(gofile=active_verdict(DIRECT_MP4))
        self.assertEqual(
            fast_fetch.unwrap_multiup(DOWNLOAD_URL, _http=http,
                                      _resolvers=resolvers),
            ("", FILE_ID, 0, None))
        self.assertEqual([c[0] for c in http.calls], ["GET"])
        self.assertEqual(resolvers.tried, [])

    def test_no_csrf_token_never_posts(self):
        page = "<html><body><form></form></body></html>"
        http = FakeHttp({("GET", DOWNLOAD_URL): (200, DOWNLOAD_URL, page)})
        resolvers = StubResolvers(gofile=active_verdict(DIRECT_MP4))
        self.assertEqual(
            fast_fetch.unwrap_multiup(DOWNLOAD_URL, _http=http,
                                      _resolvers=resolvers),
            ("", FILE_ID, 0, None))
        self.assertEqual([c[0] for c in http.calls], ["GET"])

    def test_non_multiup_url_never_fetches(self):
        http = FakeHttp({})
        resolvers = StubResolvers(gofile=active_verdict(DIRECT_MP4))
        self.assertEqual(
            fast_fetch.unwrap_multiup("https://gofile.io/d/VrmvqV",
                                      _http=http, _resolvers=resolvers),
            ("", "", 0, None))
        self.assertEqual(http.calls, [])

    def test_no_resolvers_is_empty(self):
        http = self._http_for([mirror_anchor("gofile.io", GOFILE_LINK)])
        with mock.patch.object(fast_fetch, "_embed_resolvers",
                               return_value=None):
            self.assertEqual(
                fast_fetch.unwrap_multiup(DOWNLOAD_URL, _http=http),
                ("", FILE_ID, 0, None))

    def test_active_verdict_without_https_is_refused(self):
        http = self._http_for([mirror_anchor("gofile.io", GOFILE_LINK)])
        resolvers = StubResolvers(
            gofile={"status": "active", "final_url": "http://x/y.mkv",
                    "is_temporary": 1})
        self.assertEqual(
            fast_fetch.unwrap_multiup(DOWNLOAD_URL, _http=http,
                                      _resolvers=resolvers),
            ("", FILE_ID, 0, None))

    def test_valid_mirror_tried_before_invalid_within_a_host(self):
        http = self._http_for([
            mirror_anchor("gofile.io", "https://gofile.io/d/DeadOne", "invalid"),
            mirror_anchor("gofile.io", "https://gofile.io/d/LiveOne", "valid"),
        ])

        class OrderResolvers(StubResolvers):
            def unwrap_gofile(self, url, verify=True):
                self.tried.append(("gofile", url))
                if url.endswith("LiveOne"):
                    return active_verdict(DIRECT_MP4)
                return {"status": "dead", "notes": ["gone"]}

        resolvers = OrderResolvers()
        direct, _rid, _temp, _v = fast_fetch.unwrap_multiup(
            DOWNLOAD_URL, _http=http, _resolvers=resolvers)
        self.assertEqual(direct, DIRECT_MP4)
        self.assertEqual(resolvers.tried[0],
                         ("gofile", "https://gofile.io/d/LiveOne"))
        # The invalid mirror is never attempted at all, not merely tried last.
        self.assertEqual(resolvers.tried,
                         [("gofile", "https://gofile.io/d/LiveOne")])

    def test_org_url_posts_to_io_mirror(self):
        # multiup.org 301s to .io in real life; the POST follows the GET's
        # final host, so an org download URL still mints an .io mirror URL.
        mirror_url = f"https://multiup.io/en/mirror/{OTHER_ID}"
        http = FakeHttp({
            ("GET", ORG_URL): (200, f"https://multiup.io/download/{OTHER_ID}",
                               download_page("tok9").replace(FILE_ID, OTHER_ID)),
            ("POST", mirror_url): (200, mirror_url,
                                   mirror_page([mirror_anchor(
                                       "gofile.io", GOFILE_LINK)])),
        })
        resolvers = StubResolvers(gofile=active_verdict(DIRECT_MP4))
        direct, rid, _temp, _v = fast_fetch.unwrap_multiup(
            ORG_URL, _http=http, _resolvers=resolvers)
        self.assertEqual(direct, DIRECT_MP4)
        self.assertEqual(rid, OTHER_ID)
        self.assertEqual(http.calls[1][1], mirror_url)


class ValidityFirstTests(unittest.TestCase):
    """Speed (23 Nov 2026): validity order first, host order second.

    Fixtures mirror the user's Chrome capture for one file (The Terminal
    List 720p): mixdrop Valid (the only mirror Multiup marks valid),
    gofile Unknown, dailyuploads + 1fichier Invalid. The mirror list is
    read fresh per file — these URLs pin the ORDERING logic, never a
    claim that any other file carries the same mirrors.
    """

    CAPTURED_MIXDROP = "https://mixdrop.ag/f/vkk61vj4hmd41w"
    CAPTURED_GOFILE = "https://gofile.io/d/n0auSD4K"
    CAPTURED_DAILYUPLOADS = "https://dailyuploads.net/gauvtle9p4x8"
    CAPTURED_1FICHIER = "https://1fichier.com/?pcar5m0q1m236glum8ly&af=62851"

    def _http_for(self, mirror_anchors, token="tok123"):
        mirror_url = f"https://multiup.io/en/mirror/{FILE_ID}"
        return FakeHttp({
            ("GET", DOWNLOAD_URL): (200, DOWNLOAD_URL, download_page(token)),
            ("POST", mirror_url): (200, mirror_url,
                                   mirror_page(mirror_anchors)),
        })

    def _captured_anchors(self):
        return [
            mirror_anchor("mixdrop.ag", self.CAPTURED_MIXDROP, "valid"),
            mirror_anchor("gofile.io", self.CAPTURED_GOFILE, "unknown"),
            mirror_anchor("dailyuploads.net", self.CAPTURED_DAILYUPLOADS,
                          "invalid"),
            mirror_anchor("1fichier.com", self.CAPTURED_1FICHIER, "invalid"),
        ]

    def test_captured_case_valid_mixdrop_before_unknown_gofile(self):
        # Both resolvers would win if tried — the Valid mirror must win
        # because it is tried FIRST, and the Unknown gofile must never
        # be attempted at all.
        http = self._http_for(self._captured_anchors())
        resolvers = StubResolvers(
            gofile=active_verdict(DIRECT_MP4),
            mixdrop=active_verdict(DIRECT_MP4))
        direct, rid, _temp, verdict = fast_fetch.unwrap_multiup(
            DOWNLOAD_URL, _http=http, _resolvers=resolvers)
        self.assertEqual(direct, DIRECT_MP4)
        self.assertEqual(rid, FILE_ID)
        self.assertEqual(verdict["status"], "active")
        self.assertEqual(
            resolvers.tried,
            [("mixdrop", "https://mixdrop.ag/e/vkk61vj4hmd41w")])

    def test_unknown_is_fallback_when_no_valid_mirror(self):
        http = self._http_for([
            mirror_anchor("gofile.io", GOFILE_LINK, "unknown"),
            mirror_anchor("mixdrop.ag", MIXDROP_LINK, "invalid"),
            mirror_anchor("dailyuploads.net",
                          "https://dailyuploads.net/gauvtle9p4x8", "invalid"),
        ])
        resolvers = StubResolvers(
            gofile=active_verdict(DIRECT_MP4),
            mixdrop=active_verdict(DIRECT_MP4))
        direct, _rid, _temp, _v = fast_fetch.unwrap_multiup(
            DOWNLOAD_URL, _http=http, _resolvers=resolvers)
        self.assertEqual(direct, DIRECT_MP4)
        # The invalid mixdrop is never attempted even though no valid
        # mirror exists — Unknown is the only fallback tier.
        self.assertEqual(resolvers.tried, [("gofile", GOFILE_LINK)])

    def test_all_invalid_is_honest_failure(self):
        http = self._http_for([
            mirror_anchor("gofile.io", GOFILE_LINK, "invalid"),
            mirror_anchor("mixdrop.ag", MIXDROP_LINK, "invalid"),
        ])
        resolvers = StubResolvers(
            gofile=active_verdict(DIRECT_MP4),
            mixdrop=active_verdict(DIRECT_MP4))
        self.assertEqual(
            fast_fetch.unwrap_multiup(DOWNLOAD_URL, _http=http,
                                      _resolvers=resolvers),
            ("", FILE_ID, 0, None))
        self.assertEqual(resolvers.tried, [])

    def test_invalid_never_served_even_when_resolver_would_win(self):
        # Multiup's Invalid flag is the verdict: a mirror the resolver
        # would call active is still never served from an Invalid row.
        http = self._http_for([
            mirror_anchor("gofile.io", GOFILE_LINK, "invalid"),
        ])
        resolvers = StubResolvers(gofile=active_verdict(DIRECT_MP4))
        self.assertEqual(
            fast_fetch.unwrap_multiup(DOWNLOAD_URL, _http=http,
                                      _resolvers=resolvers),
            ("", FILE_ID, 0, None))
        self.assertEqual(resolvers.tried, [])

    def test_empty_mirror_list_is_honest_failure(self):
        http = self._http_for([])
        resolvers = StubResolvers(
            gofile=active_verdict(DIRECT_MP4),
            mixdrop=active_verdict(DIRECT_MP4))
        self.assertEqual(
            fast_fetch.unwrap_multiup(DOWNLOAD_URL, _http=http,
                                      _resolvers=resolvers),
            ("", FILE_ID, 0, None))
        self.assertEqual(resolvers.tried, [])

    def test_valid_tier_keeps_gofile_before_mixdrop(self):
        # Within one validity tier the host order is unchanged (gofile,
        # then mixdrop) — only the tier order moved.
        http = self._http_for([
            mirror_anchor("mixdrop.ag", MIXDROP_LINK, "valid"),
            mirror_anchor("gofile.io", GOFILE_LINK, "valid"),
        ])
        resolvers = StubResolvers(
            gofile=active_verdict(DIRECT_MP4),
            mixdrop=active_verdict(DIRECT_MP4))
        direct, _rid, _temp, _v = fast_fetch.unwrap_multiup(
            DOWNLOAD_URL, _http=http, _resolvers=resolvers)
        self.assertEqual(direct, DIRECT_MP4)
        self.assertEqual(resolvers.tried, [("gofile", GOFILE_LINK)])

    def test_unknown_fallthrough_after_unknown_gofile_dead(self):
        http = self._http_for([
            mirror_anchor("gofile.io", GOFILE_LINK, "unknown"),
            mirror_anchor("mixdrop.ag", MIXDROP_LINK, "unknown"),
        ])
        resolvers = StubResolvers(
            gofile={"status": "dead", "notes": ["folder deleted"]},
            mixdrop=active_verdict(DIRECT_MP4))
        direct, _rid, _temp, _v = fast_fetch.unwrap_multiup(
            DOWNLOAD_URL, _http=http, _resolvers=resolvers)
        self.assertEqual(direct, DIRECT_MP4)
        self.assertEqual(resolvers.tried,
                         [("gofile", GOFILE_LINK),
                          ("mixdrop", "https://mixdrop.ag/e/pjmm1d8zuogown")])

    def test_resolver_exception_falls_through_to_next_mirror(self):
        http = self._http_for([
            mirror_anchor("gofile.io", GOFILE_LINK, "unknown"),
            mirror_anchor("mixdrop.ag", MIXDROP_LINK, "unknown"),
        ])

        class RaisingResolvers(StubResolvers):
            def unwrap_gofile(self, url, verify=True):
                self.tried.append(("gofile", url))
                raise RuntimeError("gofile transport blew up")

        resolvers = RaisingResolvers(mixdrop=active_verdict(DIRECT_MP4))
        direct, _rid, _temp, _v = fast_fetch.unwrap_multiup(
            DOWNLOAD_URL, _http=http, _resolvers=resolvers)
        self.assertEqual(direct, DIRECT_MP4)
        self.assertEqual(resolvers.tried,
                         [("gofile", GOFILE_LINK),
                          ("mixdrop", "https://mixdrop.ag/e/pjmm1d8zuogown")])


if __name__ == "__main__":
    unittest.main(verbosity=2)
