#!/usr/bin/env python3
"""Fast_Fetch/test_current_hosts.py — offline tests for the modiplay unwrap.

Covers ``current_hosts._unwrap_modiplay`` after the 2026-11-21 fix: the
embed page lists 2-3 servers and every listed server is tried in
preference order (streamhg, vidrift/bingr, vidara). The fixtures are the
measured live shapes (Oracle VM probes, 2026-11-21):

- streamhg proxy page carries ``/stream_proxy.php?url=...master.m3u8``
  (vibuxer master, 200 application/vnd.apple.mpegurl, 480p + 720p).
- vidrift/bingr proxy page is a ~5 KB iframe wrapper
  (``<iframe src="https://embed.vidrift.net/embed/movie/11667">``) — the
  old direct-orion regex never matched it, so the branch always ended
  "shell_unverified". The iframe chain is what unwrap_cineverse uses.
- vidara: ``POST https://vidara.to/api/stream`` with a JSON body
  ``{"filecode": code}`` answers 200 with a signed HLS master in
  ``streaming_url``; a form-encoded body answers 400.
- vidrift TV embeds answer ``"orionStreams":null`` — no_streams, honest.

Hubdrive + megaup (2026-11-21, port of nurlresolver hubdrive.ts /
megaup.ts, MIT) — fixtures are the measured live shapes (Oracle VM
probes, 2026-11-21):

- hubdrive: GET ``/file/<id>`` answers 200 (real title, ajax.php in the
  page) after a .pics -> .blog redirect and sets NO cookies; POST
  ``/ajax.php?ajax=direct-download`` form ``id`` with
  ``X-Requested-With`` answers 200 JSON ``{"code": 408, "title":
  "Direct Download Unavailable", ...}`` without the login
  HUBDRIVE_CRYPT cookie — the cookie is a browser-login credential, so
  the resolver reads it from RADD_HUBDRIVE_CRYPT and fails
  "hubdrive_needs_cookie" without it, never dead. The happy path
  (code "200" + file page -> .btn-primary href) is fixture-only.
- megaup: the file page carries ``class='btn btn-default' href='...'``;
  after a 6 s wait the button is followed without redirects and the
  Location header is the file; a page landing on error.html is a
  removed file ("dead_file"). megaup.net resets the connection from
  Oracle egress (megaup.mobi fails TLS), so the chain is unproven live.

No network, stdlib only.
"""

from __future__ import annotations

import json
import os
import sys
import unittest
from pathlib import Path
from unittest import mock

BASE_DIR = Path(__file__).parent.resolve()
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import current_hosts
from current_hosts import FetchResult

ORIGIN = "https://rozgarlelo.modiplay.xyz"
EMBED_MOVIE = f"{ORIGIN}/embed/tmdb/movie?id=11667"
EMBED_TV = f"{ORIGIN}/embed/tmdb/tv?id=133064&s=1&e=1"

EMBED_BOTH = (
    '<div class="servers">'
    '<a href="/proxy.php?p=streamhg&c=mt1vad6ck0ad&title=x">StreamHG</a>'
    '<a href="/proxy.php?p=vidrift&c=11667%3Amovie%3A0%3A0">Vidrift</a>'
    "</div>"
)
EMBED_VIDRIFT_ONLY = (
    '<div class="servers">'
    '<a href="/proxy.php?p=vidrift&c=11667%3Amovie%3A0%3A0">Vidrift</a>'
    "</div>"
)
EMBED_BINGR_ONLY = (
    '<div class="servers">'
    '<a href="/proxy.php?p=bingr&c=11667%3Amovie%3A0%3A0">Bingr</a>'
    "</div>"
)
EMBED_VIDARA_FIRST = (
    '<div class="servers">'
    '<a href="/proxy.php?p=vidara&c=g7qSga8egu3vZ">Vidara</a>'
    '<a href="/proxy.php?p=vidrift&c=133064%3Atv%3A1%3A1">Vidrift</a>'
    "</div>"
)
EMBED_UNKNOWN = (
    '<div class="servers">'
    '<a href="/proxy.php?p=zzq&c=abc123">Unknown</a>'
    "</div>"
)
EMBED_NO_SERVERS = "<html><body>No servers listed here.</body></html>"

STREAMHG_PROXY = (
    '<script>var player="x";</script>'
    '<a href="/stream_proxy.php?url=https%3A%2F%2Fvibuxer.com%2Fstream%2F'
    '1b2SjdrPgimpA7TT9Zzddw%2Fkjhhiuahiuhgihdf%2F1795243059%2F76192645%2F'
    'master.m3u8&ref=https%3A%2F%2Fvibuxer.com%2Fe%2Fmt1vad6ck0ad'
    '&tok=2386d64a">play</a>'
)
VIDRIFT_WRAPPER = (
    '<html><body><iframe src="https://embed.vidrift.net/embed/movie/11667" '
    'width="100%" height="100%"></iframe></body></html>'
)
VIDRIFT_EMBED = (
    '<html><head><script>var cfg = {"orionStreams":['
    '{"url":"/api/orion/abc123/master.m3u8","quality":"1080p"},'
    '{"url":"/api/orion/def456/master.m3u8","quality":"720p"}]};</script>'
    "</head></html>"
)
VIDRIFT_TV_EMPTY = (
    '<html><head><script>var cfg = {"orionStreams":null};</script></head></html>'
)
VIDARA_API = json.dumps({
    "default_sub_lang": "English",
    "filecode": "g7qSga8egu3vZ",
    "streaming_url": ("https://s10-5042yyg.s1q2105.com/hls/"
                      "hus4QuNjZmFPGlcTlVntWFU9Q2y9IIsV/master.m3u8?token=T"),
    "subtitles": [],
})

STREAMHG_PROXY_URL = (f"{ORIGIN}/proxy.php?p=streamhg&c=mt1vad6ck0ad"
                      f"&title=x&site_ref=&noredirect=1")
VIDRIFT_PROXY_URL = (f"{ORIGIN}/proxy.php?p=vidrift&c=11667%3Amovie%3A0%3A0"
                     f"&title=x&noredirect=1")
BINGR_PROXY_URL = (f"{ORIGIN}/proxy.php?p=bingr&c=11667%3Amovie%3A0%3A0"
                   f"&title=x&noredirect=1")
VIDRIFT_EMBED_URL = "https://embed.vidrift.net/embed/movie/11667"
VIDARA_API_URL = "https://vidara.to/api/stream"


def _html(body: str, status: int = 200, final_url: str = "",
          set_cookies: list | None = None) -> FetchResult:
    return FetchResult(status=status, content_type="text/html",
                       body=body.encode(), final_url=final_url,
                       set_cookies=set_cookies)


def _ok_probe(url, referer, kind):
    return {"ok": True, "kind": kind or "playlist",
            "mime": "application/vnd.apple.mpegurl", "size": None,
            "resumable": False, "renditions": [(1280, 720)],
            "failure": None}


def _ok_file_probe(url, referer, kind):
    return {"ok": True, "kind": "file",
            "mime": "video/x-matroska", "size": 123456,
            "resumable": True, "renditions": [], "failure": None}


class StubFetch:
    """URL -> FetchResult map; records every call (url, kwargs)."""

    def __init__(self, mapping):
        self.mapping = mapping
        self.calls = []

    def __call__(self, url, referer=None, method="GET", range_bytes=None,
                 data=None, json_data=None, timeout=None, cookies=None,
                 headers=None, follow_redirects=True):
        self.calls.append((url, {"referer": referer, "method": method,
                                 "data": data, "json_data": json_data,
                                 "cookies": cookies, "headers": headers,
                                 "follow_redirects": follow_redirects}))
        return self.mapping.get(url, FetchResult(status=404))


class ModiplayUnwrapTests(unittest.TestCase):
    def test_streamhg_chain(self):
        fetch = StubFetch({EMBED_MOVIE: _html(EMBED_BOTH),
                           STREAMHG_PROXY_URL: _html(STREAMHG_PROXY)})
        backend, cands, failure = current_hosts._unwrap_modiplay(
            EMBED_MOVIE, fetch)
        self.assertIsNone(failure, (backend, failure))
        self.assertEqual(backend, "streamhg")
        self.assertEqual(len(cands), 1)
        self.assertIn("/stream_proxy.php?url=", cands[0]["url"])
        self.assertIn("master.m3u8", cands[0]["url"])

    def test_streamhg_preferred_over_vidrift(self):
        # Both servers listed; the preferred one wins and the vidrift
        # proxy is never fetched.
        fetch = StubFetch({EMBED_MOVIE: _html(EMBED_BOTH),
                           STREAMHG_PROXY_URL: _html(STREAMHG_PROXY)})
        backend, cands, failure = current_hosts._unwrap_modiplay(
            EMBED_MOVIE, fetch)
        self.assertIsNone(failure)
        self.assertEqual(backend, "streamhg")
        self.assertNotIn(VIDRIFT_PROXY_URL,
                         [c[0] for c in fetch.calls])

    def test_streamhg_failure_falls_through_to_vidrift(self):
        # streamhg proxy 403s; the vidrift sibling still resolves.
        fetch = StubFetch({
            EMBED_MOVIE: _html(EMBED_BOTH),
            STREAMHG_PROXY_URL: _html("", status=403),
            VIDRIFT_PROXY_URL: _html(VIDRIFT_WRAPPER),
            VIDRIFT_EMBED_URL: _html(VIDRIFT_EMBED),
        })
        backend, cands, failure = current_hosts._unwrap_modiplay(
            EMBED_MOVIE, fetch)
        self.assertIsNone(failure, (backend, failure))
        self.assertEqual(backend, "vidrift")
        self.assertEqual(cands[0]["url"],
                         "https://embed.vidrift.net/api/orion/abc123/master.m3u8")

    def test_vidrift_iframe_chain(self):
        fetch = StubFetch({
            EMBED_MOVIE: _html(EMBED_VIDRIFT_ONLY),
            VIDRIFT_PROXY_URL: _html(VIDRIFT_WRAPPER),
            VIDRIFT_EMBED_URL: _html(VIDRIFT_EMBED),
        })
        backend, cands, failure = current_hosts._unwrap_modiplay(
            EMBED_MOVIE, fetch)
        self.assertIsNone(failure, (backend, failure))
        self.assertEqual(backend, "vidrift")
        self.assertEqual(len(cands), 2)
        self.assertTrue(cands[0]["url"].endswith("/master.m3u8"))

    def test_bingr_wrapper_is_the_same_vidrift_chain(self):
        fetch = StubFetch({
            EMBED_MOVIE: _html(EMBED_BINGR_ONLY),
            BINGR_PROXY_URL: _html(VIDRIFT_WRAPPER),
            VIDRIFT_EMBED_URL: _html(VIDRIFT_EMBED),
        })
        backend, cands, failure = current_hosts._unwrap_modiplay(
            EMBED_MOVIE, fetch)
        self.assertIsNone(failure, (backend, failure))
        self.assertEqual(backend, "vidrift")
        self.assertTrue(cands[0]["url"].endswith("/master.m3u8"))

    def test_vidrift_tv_embed_has_no_streams(self):
        # The TV wrapper iframe points at the tv embed, not the movie one.
        fetch = StubFetch({
            EMBED_TV: _html(EMBED_VIDRIFT_ONLY),
            VIDRIFT_PROXY_URL: _html(
                '<iframe src="https://embed.vidrift.net/embed/tv/133064/1/1">'),
            "https://embed.vidrift.net/embed/tv/133064/1/1":
                _html(VIDRIFT_TV_EMPTY),
        })
        backend, cands, failure = current_hosts._unwrap_modiplay(
            EMBED_TV, fetch)
        self.assertEqual(cands, [])
        self.assertEqual(failure, "no_streams")

    def test_vidara_chain_posts_json(self):
        fetch = StubFetch({
            EMBED_TV: _html(EMBED_VIDARA_FIRST),
            VIDARA_API_URL: _html(VIDARA_API),
        })
        backend, cands, failure = current_hosts._unwrap_modiplay(
            EMBED_TV, fetch)
        self.assertIsNone(failure, (backend, failure))
        self.assertEqual(backend, "vidara")
        self.assertEqual(len(cands), 1)
        self.assertIn("s1q2105.com/hls/", cands[0]["url"])
        self.assertIn("master.m3u8", cands[0]["url"])
        api_calls = [c for c in fetch.calls if c[0] == VIDARA_API_URL]
        self.assertEqual(len(api_calls), 1)
        self.assertEqual(api_calls[0][1]["method"], "POST")
        self.assertEqual(api_calls[0][1]["json_data"],
                         {"filecode": "g7qSga8egu3vZ"})
        self.assertIsNone(api_calls[0][1]["data"])

    def test_vidara_failure_falls_through_to_vidrift(self):
        fetch = StubFetch({
            EMBED_TV: _html(EMBED_VIDARA_FIRST),
            VIDARA_API_URL: _html(
                '{"error":"Missing or invalid filecode"}', status=400),
        })
        # vidrift sibling fails too (tv embed, no streams) — the reported
        # failure is the preferred server's (vidrift's, tried first),
        # not the fallback's.
        fetch.mapping[
            f"{ORIGIN}/proxy.php?p=vidrift&c=133064%3Atv%3A1%3A1"
            f"&title=x&noredirect=1"] = _html(
            '<iframe src="https://embed.vidrift.net/embed/tv/133064/1/1">')
        fetch.mapping["https://embed.vidrift.net/embed/tv/133064/1/1"] = (
            _html(VIDRIFT_TV_EMPTY))
        backend, cands, failure = current_hosts._unwrap_modiplay(
            EMBED_TV, fetch)
        self.assertEqual(cands, [])
        self.assertEqual((backend, failure), ("vidrift", "no_streams"))

    def test_unknown_server_is_unsupported_not_dead(self):
        fetch = StubFetch({EMBED_MOVIE: _html(EMBED_UNKNOWN)})
        backend, cands, failure = current_hosts._unwrap_modiplay(
            EMBED_MOVIE, fetch)
        self.assertEqual((backend, cands, failure),
                         ("modiplay", [], "unsupported_server"))

    def test_no_proxy_links_is_unverified(self):
        fetch = StubFetch({EMBED_MOVIE: _html(EMBED_NO_SERVERS)})
        backend, cands, failure = current_hosts._unwrap_modiplay(
            EMBED_MOVIE, fetch)
        self.assertEqual((backend, cands, failure),
                         ("modiplay", [], "unverified"))

    def test_embed_403_is_blocked(self):
        fetch = StubFetch({EMBED_MOVIE: _html("", status=403)})
        backend, cands, failure = current_hosts._unwrap_modiplay(
            EMBED_MOVIE, fetch)
        self.assertEqual((backend, cands, failure),
                         ("modiplay", [], "blocked"))


class ModiplayResolveCopyTests(unittest.TestCase):
    def test_resolve_copy_vidrift_only_movie(self):
        fetch = StubFetch({
            EMBED_MOVIE: _html(EMBED_VIDRIFT_ONLY),
            VIDRIFT_PROXY_URL: _html(VIDRIFT_WRAPPER),
            VIDRIFT_EMBED_URL: _html(VIDRIFT_EMBED),
        })
        res = current_hosts.resolve_copy(EMBED_MOVIE, fetch=fetch,
                                         probe=_ok_probe)
        self.assertTrue(res["ok"], res)
        self.assertEqual(res["backend"], "vidrift")
        self.assertIn("/api/orion/abc123/master.m3u8", res["url"])

    def test_resolve_copy_vidara_tv(self):
        fetch = StubFetch({
            EMBED_TV: _html(EMBED_VIDARA_FIRST),
            VIDARA_API_URL: _html(VIDARA_API),
        })
        res = current_hosts.resolve_copy(EMBED_TV, fetch=fetch,
                                         probe=_ok_probe)
        self.assertTrue(res["ok"], res)
        self.assertEqual(res["backend"], "vidara")
        self.assertIn("s1q2105.com/hls/", res["url"])


class VidaraBackendRegistryTests(unittest.TestCase):
    def test_vidara_is_a_stage1_backend(self):
        self.assertIn("vidara", current_hosts.STAGE1_BACKENDS)
        self.assertEqual(current_hosts.INDEPENDENCE_GROUP["vidara"],
                         "vidara")
        self.assertIn("vidara", current_hosts.WATCH_PREFERENCE)


# ── hubdrive + megaup (2026-11-21) ───────────────────────────────────────────

HD_SAVED = "https://hubdrive.pics/file/2194260094"
HD_FINAL = "https://hubdrive.blog/file/2194260094"
HD_AJAX = "https://hubdrive.blog/ajax.php?ajax=direct-download"
HD_FILE_PAGE_URL = "https://hubdrive.blog/download/2194260094"
HD_CDN = "https://cdn.example.com/Kingsman.mkv"
HD_PAGE = (
    "<html><head><title>HubDrive | Kingsman.The.Secret.Service.2014.480p"
    ".BluRay.Hindi.English.DD2.0.x264.ESubs.mkv</title></head><body>"
    '<form action="/ajax.php?ajax=direct-download">direct-download</form>'
    "</body></html>"
)
HD_AJAX_OK = json.dumps({"code": "200", "file": HD_FILE_PAGE_URL})
# Verbatim measured answer without the login cookie (Oracle VM, 2026-11-21).
HD_AJAX_408 = json.dumps({
    "code": 408, "title": "Direct Download Unavailable",
    "file": ("We couldn't generate a direct download link. "
             "Please try another download method."),
})
HD_FILE_PAGE = (
    '<div class="card-body"><a class="card-body btn-primary" '
    f'href="{HD_CDN}">Download</a></div>'
)
HD_FILE_PAGE_REVERSED = (
    '<div class="card-body"><a href="'
    f'{HD_CDN}" class="btn btn-primary">Download</a></div>'
)

MU_SAVED = ("https://megaup.net/34cd8461f0137ef599d99e1843705c88/"
            "Girl_Interrupted_(1999).mkv")
MU_BUTTON = "https://megaup.net/dl/abc123"
MU_CDN = "https://cdn.example.com/Girl_Interrupted.mkv"
MU_PAGE = (
    "<html><body><a class='btn btn-default' "
    f"href='{MU_BUTTON}'>Download</a></body></html>"
)


class HubdriveUnwrapTests(unittest.TestCase):
    def setUp(self):
        # The env cookie must not leak between tests.
        self._saved_env = os.environ.pop("RADD_HUBDRIVE_CRYPT", None)
        self.addCleanup(self._restore_env)

    def _restore_env(self):
        if self._saved_env is None:
            os.environ.pop("RADD_HUBDRIVE_CRYPT", None)
        else:
            os.environ["RADD_HUBDRIVE_CRYPT"] = self._saved_env

    def _happy_fetch(self):
        return StubFetch({
            HD_SAVED: _html(HD_PAGE, final_url=HD_FINAL),
            HD_AJAX: _html(HD_AJAX_OK),
            HD_FILE_PAGE_URL: _html(HD_FILE_PAGE,
                                   final_url=HD_FILE_PAGE_URL),
        })

    def test_happy_path_with_explicit_cookie(self):
        fetch = self._happy_fetch()
        backend, cands, failure = current_hosts._unwrap_hubdrive(
            HD_SAVED, fetch, cookies={"HUBDRIVE_CRYPT": "test-cookie"})
        self.assertIsNone(failure, (backend, failure))
        self.assertEqual(backend, "hubdrive")
        self.assertEqual(len(cands), 1)
        self.assertEqual(cands[0]["url"], HD_CDN)
        self.assertEqual(cands[0]["kind_hint"], "file")
        self.assertEqual(cands[0]["referer"], HD_FILE_PAGE_URL)
        ajax_calls = [c for c in fetch.calls if c[0] == HD_AJAX]
        self.assertEqual(len(ajax_calls), 1)
        self.assertEqual(ajax_calls[0][1]["method"], "POST")
        self.assertEqual(ajax_calls[0][1]["data"], {"id": "2194260094"})
        self.assertEqual(ajax_calls[0][1]["headers"]["X-Requested-With"],
                         "XMLHttpRequest")
        self.assertEqual(ajax_calls[0][1]["cookies"]["HUBDRIVE_CRYPT"],
                         "test-cookie")

    def test_env_cookie_is_read_when_no_cookie_arg(self):
        os.environ["RADD_HUBDRIVE_CRYPT"] = "env-crypt-value"
        fetch = self._happy_fetch()
        backend, cands, failure = current_hosts._unwrap_hubdrive(
            HD_SAVED, fetch)
        self.assertIsNone(failure, (backend, failure))
        ajax_calls = [c for c in fetch.calls if c[0] == HD_AJAX]
        self.assertEqual(ajax_calls[0][1]["cookies"]["HUBDRIVE_CRYPT"],
                         "env-crypt-value")

    def test_ajax_408_without_cookie_is_needs_cookie(self):
        # Measured live shape: no login cookie -> 408, and the copy is
        # an unmet requirement, never a dead file. The file page is
        # never fetched.
        fetch = StubFetch({
            HD_SAVED: _html(HD_PAGE, final_url=HD_FINAL),
            HD_AJAX: _html(HD_AJAX_408),
        })
        backend, cands, failure = current_hosts._unwrap_hubdrive(
            HD_SAVED, fetch)
        self.assertEqual((backend, cands, failure),
                         ("hubdrive", [], "hubdrive_needs_cookie"))
        self.assertEqual(len(fetch.calls), 2)

    def test_ajax_408_with_cookie_is_unverified(self):
        fetch = StubFetch({
            HD_SAVED: _html(HD_PAGE, final_url=HD_FINAL),
            HD_AJAX: _html(HD_AJAX_408),
        })
        backend, cands, failure = current_hosts._unwrap_hubdrive(
            HD_SAVED, fetch, cookies={"HUBDRIVE_CRYPT": "stale-cookie"})
        self.assertEqual((backend, cands, failure),
                         ("hubdrive", [], "hubdrive_unverified"))

    def test_non_numeric_file_id_is_unverified(self):
        fetch = StubFetch({
            "https://hubdrive.blog/file/abc": _html(
                HD_PAGE, final_url="https://hubdrive.blog/file/abc"),
        })
        backend, cands, failure = current_hosts._unwrap_hubdrive(
            "https://hubdrive.blog/file/abc", fetch)
        self.assertEqual((backend, cands, failure),
                         ("hubdrive", [], "unverified"))
        self.assertEqual(len(fetch.calls), 1)

    def test_page_403_is_blocked(self):
        fetch = StubFetch({HD_SAVED: _html("", status=403)})
        backend, cands, failure = current_hosts._unwrap_hubdrive(
            HD_SAVED, fetch)
        self.assertEqual((backend, cands, failure),
                         ("hubdrive", [], "blocked"))

    def test_file_page_without_button_is_unverified(self):
        fetch = StubFetch({
            HD_SAVED: _html(HD_PAGE, final_url=HD_FINAL),
            HD_AJAX: _html(HD_AJAX_OK),
            HD_FILE_PAGE_URL: _html("<html><body>No link here.</body></html>",
                                   final_url=HD_FILE_PAGE_URL),
        })
        backend, cands, failure = current_hosts._unwrap_hubdrive(
            HD_SAVED, fetch, cookies={"HUBDRIVE_CRYPT": "test-cookie"})
        self.assertEqual((backend, cands, failure),
                         ("hubdrive", [], "hubdrive_unverified"))

    def test_btn_primary_attribute_order(self):
        fetch = StubFetch({
            HD_SAVED: _html(HD_PAGE, final_url=HD_FINAL),
            HD_AJAX: _html(HD_AJAX_OK),
            HD_FILE_PAGE_URL: _html(HD_FILE_PAGE_REVERSED,
                                   final_url=HD_FILE_PAGE_URL),
        })
        backend, cands, failure = current_hosts._unwrap_hubdrive(
            HD_SAVED, fetch, cookies={"HUBDRIVE_CRYPT": "test-cookie"})
        self.assertIsNone(failure, (backend, failure))
        self.assertEqual(cands[0]["url"], HD_CDN)


class MegaupUnwrapTests(unittest.TestCase):
    def test_happy_path(self):
        slept = []

        def fake_sleep(seconds):
            slept.append(seconds)

        fetch = StubFetch({
            MU_SAVED: _html(MU_PAGE, final_url=MU_SAVED),
            MU_BUTTON: FetchResult(status=302,
                                   headers={"location": MU_CDN}),
        })
        backend, cands, failure = current_hosts._unwrap_megaup(
            MU_SAVED, fetch, sleep=fake_sleep)
        self.assertIsNone(failure, (backend, failure))
        self.assertEqual(backend, "megaup")
        self.assertEqual(len(cands), 1)
        self.assertEqual(cands[0]["url"], MU_CDN)
        self.assertEqual(cands[0]["kind_hint"], "file")
        self.assertEqual(cands[0]["referer"], MU_SAVED)
        self.assertEqual(slept, [6.0])
        button_calls = [c for c in fetch.calls if c[0] == MU_BUTTON]
        self.assertEqual(len(button_calls), 1)
        self.assertFalse(button_calls[0][1]["follow_redirects"])

    def test_error_html_is_dead_file(self):
        fetch = StubFetch({
            MU_SAVED: _html("<html><body>File has been removed due to "
                            "inactivity.</body></html>",
                            final_url="https://megaup.net/error.html"),
        })
        backend, cands, failure = current_hosts._unwrap_megaup(
            MU_SAVED, fetch, sleep=lambda s: None)
        self.assertEqual((backend, cands, failure),
                         ("megaup", [], "dead_file"))
        self.assertEqual(len(fetch.calls), 1)

    def test_no_button_is_unverified(self):
        fetch = StubFetch({
            MU_SAVED: _html("<html><body>No download button.</body></html>",
                            final_url=MU_SAVED),
        })
        backend, cands, failure = current_hosts._unwrap_megaup(
            MU_SAVED, fetch, sleep=lambda s: None)
        self.assertEqual((backend, cands, failure),
                         ("megaup", [], "unverified"))

    def test_button_without_location_is_unverified(self):
        fetch = StubFetch({
            MU_SAVED: _html(MU_PAGE, final_url=MU_SAVED),
            MU_BUTTON: _html("<html><body>wait...</body></html>"),
        })
        backend, cands, failure = current_hosts._unwrap_megaup(
            MU_SAVED, fetch, sleep=lambda s: None)
        self.assertEqual((backend, cands, failure),
                         ("megaup", [], "unverified"))

    def test_page_403_is_blocked(self):
        fetch = StubFetch({MU_SAVED: _html("", status=403)})
        backend, cands, failure = current_hosts._unwrap_megaup(
            MU_SAVED, fetch, sleep=lambda s: None)
        self.assertEqual((backend, cands, failure),
                         ("megaup", [], "blocked"))


class HubdriveMegaupRegistryTests(unittest.TestCase):
    def test_both_are_stage1_backends(self):
        for backend in ("hubdrive", "megaup"):
            self.assertIn(backend, current_hosts.STAGE1_BACKENDS)
            self.assertEqual(current_hosts.INDEPENDENCE_GROUP[backend],
                             backend)
            self.assertIn(backend, current_hosts.WATCH_PREFERENCE)
            self.assertIn(backend, current_hosts.DOWNLOAD_PREFERENCE)

    def test_classify_hubdrive_tlds(self):
        for host in ("hubdrive.pics", "hubdrive.blog", "hubdrive.bar",
                     "hubdrive.in", "www.hubdrive.blog"):
            self.assertEqual(
                current_hosts.classify_backend(f"https://{host}/file/1"),
                "hubdrive", host)

    def test_classify_megaup_hosts(self):
        for host in ("megaup.net", "megaup.mobi", "www.megaup.net"):
            self.assertEqual(
                current_hosts.classify_backend(f"https://{host}/abc/file.mkv"),
                "megaup", host)

    def test_classify_lookalikes_are_unknown(self):
        self.assertEqual(current_hosts.classify_backend(
            "https://megaup.com/abc/file.mkv"), "unknown")
        self.assertEqual(current_hosts.classify_backend(
            "https://hubdrivecdn.com/file/1"), "unknown")

    def test_resolve_copy_hubdrive_end_to_end(self):
        os.environ["RADD_HUBDRIVE_CRYPT"] = "env-crypt-value"
        self.addCleanup(os.environ.pop, "RADD_HUBDRIVE_CRYPT")
        fetch = StubFetch({
            HD_SAVED: _html(HD_PAGE, final_url=HD_FINAL),
            HD_AJAX: _html(HD_AJAX_OK),
            HD_FILE_PAGE_URL: _html(HD_FILE_PAGE,
                                   final_url=HD_FILE_PAGE_URL),
        })
        res = current_hosts.resolve_copy(HD_SAVED, fetch=fetch,
                                         probe=_ok_file_probe)
        self.assertTrue(res["ok"], res)
        self.assertEqual(res["backend"], "hubdrive")
        self.assertEqual(res["group"], "hubdrive")
        self.assertEqual(res["kind"], "file")
        self.assertEqual(res["url"], HD_CDN)

    def test_resolve_copy_megaup_end_to_end(self):
        fetch = StubFetch({
            MU_SAVED: _html(MU_PAGE, final_url=MU_SAVED),
            MU_BUTTON: FetchResult(status=302,
                                   headers={"location": MU_CDN}),
        })
        with mock.patch("time.sleep"):
            res = current_hosts.resolve_copy(MU_SAVED, fetch=fetch,
                                             probe=_ok_file_probe)
        self.assertTrue(res["ok"], res)
        self.assertEqual(res["backend"], "megaup")
        self.assertEqual(res["group"], "megaup")
        self.assertEqual(res["kind"], "file")
        self.assertEqual(res["url"], MU_CDN)


# ── bbupload (2026-11-21) ───────────────────────────────────────────────────

BB_SAVED = "https://download.bbupload.to/download?v=BG863REX"
BB_AJAX = ("https://download.bbupload.to/ajax.php"
           "?action=getdownload&data=BG863REX")
BB_FILE = "https://superduperload.site/get/9f8e7d6c5b4a"
# Verbatim measured answers (Oracle VM egress, 2026-11-21): the live file
# is Fall.2.Deadpoint.2026.1080p (4,212,416,206 bytes); a bogus slug
# still answers status ok, with null name/size — the dead signal.
BB_AJAX_OK = json.dumps({
    "status": "ok",
    "data": {"FileName": "Fall.2.Deadpoint.2026.1080p.CAMRip.HIN.ENG"
                         ".1XBET.mkv",
             "filesize": 4212416206, "downloadUrl": BB_FILE},
})
BB_AJAX_DEAD = json.dumps({
    "status": "ok",
    "data": {"FileName": None, "filesize": None,
             "downloadUrl": "https://superduperload.site/get/deadbeef"},
})
BB_PAGE_WITH_SLUG = (
    '<html><head><script>var slug = "1UPSHVRN";</script></head><body>'
    "</body></html>"
)
BB_AJAX_1UP = ("https://download.bbupload.to/ajax.php"
               "?action=getdownload&data=1UPSHVRN")
BB_AJAX_1UP_OK = json.dumps({
    "status": "ok",
    "data": {"FileName": "Fall.2.Deadpoint.2026.720p.mkv",
             "filesize": 610331802,
             "downloadUrl": "https://superduperload.site/get/1uptoken"},
})


def _json(body: str, status: int = 200) -> FetchResult:
    return FetchResult(status=status, content_type="application/json",
                       body=body.encode())


class BbuploadUnwrapTests(unittest.TestCase):
    def test_happy_path_uses_v_param_without_page_fetch(self):
        fetch = StubFetch({BB_AJAX: _json(BB_AJAX_OK)})
        backend, cands, failure = current_hosts._unwrap_bbupload(
            BB_SAVED, fetch)
        self.assertIsNone(failure, (backend, failure))
        self.assertEqual(backend, "bbupload")
        self.assertEqual(len(cands), 1)
        self.assertEqual(cands[0]["url"], BB_FILE)
        self.assertEqual(cands[0]["kind_hint"], "file")
        self.assertEqual(cands[0]["referer"], BB_SAVED)
        # The file page itself is never fetched: the slug is in the URL.
        self.assertEqual(len(fetch.calls), 1)
        self.assertEqual(fetch.calls[0][0], BB_AJAX)
        self.assertEqual(fetch.calls[0][1]["method"], "GET")
        self.assertEqual(fetch.calls[0][1]["referer"], BB_SAVED)
        self.assertEqual(fetch.calls[0][1]["headers"]["X-Requested-With"],
                         "XMLHttpRequest")
        self.assertEqual(fetch.calls[0][1]["headers"]["Accept"],
                         "application/json")

    def test_protocol_relative_url_is_normalized(self):
        fetch = StubFetch({BB_AJAX: _json(BB_AJAX_OK)})
        backend, cands, failure = current_hosts._unwrap_bbupload(
            "//download.bbupload.to/download?v=BG863REX", fetch)
        self.assertIsNone(failure, (backend, failure))
        self.assertEqual(cands[0]["url"], BB_FILE)

    def test_string_filesize_is_accepted(self):
        # Live variance (Oracle VM, 2026-11-21): the 1080p ajax answers
        # filesize as an int, the 720p one as the string "610331802".
        # The unwrapper checks truthiness, never the type.
        fetch = StubFetch({BB_AJAX: _json(json.dumps({
            "status": "ok",
            "data": {"FileName": "film.mkv", "filesize": "610331802",
                     "downloadUrl": BB_FILE}}))})
        backend, cands, failure = current_hosts._unwrap_bbupload(
            BB_SAVED, fetch)
        self.assertIsNone(failure, (backend, failure))
        self.assertEqual(cands[0]["url"], BB_FILE)

    def test_missing_v_param_reads_slug_from_page(self):
        fetch = StubFetch({
            "https://download.bbupload.to/download":
                _html(BB_PAGE_WITH_SLUG),
            BB_AJAX_1UP: _json(BB_AJAX_1UP_OK),
        })
        backend, cands, failure = current_hosts._unwrap_bbupload(
            "https://download.bbupload.to/download", fetch)
        self.assertIsNone(failure, (backend, failure))
        self.assertEqual(cands[0]["url"],
                         "https://superduperload.site/get/1uptoken")
        self.assertEqual(len(fetch.calls), 2)

    def test_page_without_slug_is_unverified(self):
        fetch = StubFetch({
            "https://download.bbupload.to/download":
                _html("<html><body>No slug here.</body></html>"),
        })
        backend, cands, failure = current_hosts._unwrap_bbupload(
            "https://download.bbupload.to/download", fetch)
        self.assertEqual((backend, cands, failure),
                         ("bbupload", [], "unverified"))
        self.assertEqual(len(fetch.calls), 1)

    def test_null_filename_is_dead_file(self):
        # The site's own removed-file signal: status ok, null name/size
        # — even though a downloadUrl is present, it serves an empty
        # HTML body, so the copy is dead, never unverified.
        fetch = StubFetch({BB_AJAX: _json(BB_AJAX_DEAD)})
        backend, cands, failure = current_hosts._unwrap_bbupload(
            BB_SAVED, fetch)
        self.assertEqual((backend, cands, failure),
                         ("bbupload", [], "dead_file"))

    def test_ajax_403_is_blocked(self):
        fetch = StubFetch({BB_AJAX: _json("", status=403)})
        backend, cands, failure = current_hosts._unwrap_bbupload(
            BB_SAVED, fetch)
        self.assertEqual((backend, cands, failure),
                         ("bbupload", [], "blocked"))

    def test_ajax_transport_is_transport(self):
        fetch = StubFetch({BB_AJAX: FetchResult(status=0)})
        backend, cands, failure = current_hosts._unwrap_bbupload(
            BB_SAVED, fetch)
        self.assertEqual((backend, cands, failure),
                         ("bbupload", [], "transport"))

    def test_bad_json_is_bbupload_unverified(self):
        fetch = StubFetch({BB_AJAX: _json("not json at all")})
        backend, cands, failure = current_hosts._unwrap_bbupload(
            BB_SAVED, fetch)
        self.assertEqual((backend, cands, failure),
                         ("bbupload", [], "bbupload_unverified"))

    def test_status_not_ok_is_bbupload_unverified(self):
        fetch = StubFetch({BB_AJAX: _json(
            json.dumps({"status": "error", "data": None}))})
        backend, cands, failure = current_hosts._unwrap_bbupload(
            BB_SAVED, fetch)
        self.assertEqual((backend, cands, failure),
                         ("bbupload", [], "bbupload_unverified"))

    def test_missing_download_url_is_bbupload_unverified(self):
        fetch = StubFetch({BB_AJAX: _json(json.dumps({
            "status": "ok",
            "data": {"FileName": "film.mkv", "filesize": 123,
                     "downloadUrl": ""}}))})
        backend, cands, failure = current_hosts._unwrap_bbupload(
            BB_SAVED, fetch)
        self.assertEqual((backend, cands, failure),
                         ("bbupload", [], "bbupload_unverified"))

    def test_superduperload_url_passes_through(self):
        # Already resolved (the importer banks these as final_url): no
        # fetch at all, the file URL is the candidate.
        fetch = StubFetch({})
        backend, cands, failure = current_hosts._unwrap_bbupload(
            BB_FILE, fetch)
        self.assertIsNone(failure, (backend, failure))
        self.assertEqual(backend, "bbupload")
        self.assertEqual(cands, [{"url": BB_FILE, "referer": BB_FILE,
                                  "kind_hint": "file"}])
        self.assertEqual(fetch.calls, [])

    def test_page_403_on_slug_fallback_is_blocked(self):
        fetch = StubFetch({
            "https://download.bbupload.to/download":
                _html("", status=403),
        })
        backend, cands, failure = current_hosts._unwrap_bbupload(
            "https://download.bbupload.to/download", fetch)
        self.assertEqual((backend, cands, failure),
                         ("bbupload", [], "blocked"))


class BbuploadRegistryTests(unittest.TestCase):
    def test_bbupload_is_a_stage1_backend(self):
        self.assertIn("bbupload", current_hosts.STAGE1_BACKENDS)
        self.assertEqual(current_hosts.INDEPENDENCE_GROUP["bbupload"],
                         "bbupload")
        self.assertIn("bbupload", current_hosts.WATCH_PREFERENCE)
        self.assertIn("bbupload", current_hosts.DOWNLOAD_PREFERENCE)

    def test_classify_bridge_and_file_hosts(self):
        self.assertEqual(
            current_hosts.classify_backend(BB_SAVED), "bbupload")
        self.assertEqual(
            current_hosts.classify_backend(
                "https://download.bbupload.to/watch?v=1UPSHVRN"),
            "bbupload")
        self.assertEqual(
            current_hosts.classify_backend(BB_FILE), "bbupload")

    def test_classify_lookalikes_are_unknown(self):
        self.assertEqual(current_hosts.classify_backend(
            "https://download.bbupload.to.evil.com/download?v=X"), "unknown")
        self.assertEqual(current_hosts.classify_backend(
            "https://notbbupload.to/download?v=X"), "unknown")
        self.assertEqual(current_hosts.classify_backend(
            "https://superduperload.example/get/x"), "unknown")

    def test_resolve_copy_bbupload_end_to_end(self):
        fetch = StubFetch({BB_AJAX: _json(BB_AJAX_OK)})
        res = current_hosts.resolve_copy(BB_SAVED, fetch=fetch,
                                         probe=_ok_file_probe)
        self.assertTrue(res["ok"], res)
        self.assertEqual(res["backend"], "bbupload")
        self.assertEqual(res["group"], "bbupload")
        self.assertEqual(res["kind"], "file")
        self.assertEqual(res["url"], BB_FILE)

    def test_resolve_copy_superduperload_end_to_end(self):
        fetch = StubFetch({})
        res = current_hosts.resolve_copy(BB_FILE, fetch=fetch,
                                         probe=_ok_file_probe)
        self.assertTrue(res["ok"], res)
        self.assertEqual(res["backend"], "bbupload")
        self.assertEqual(res["url"], BB_FILE)


if __name__ == "__main__":
    unittest.main(verbosity=2)
