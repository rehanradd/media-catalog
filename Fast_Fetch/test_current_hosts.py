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

No network, stdlib only.
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

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


def _html(body: str, status: int = 200) -> FetchResult:
    return FetchResult(status=status, content_type="text/html",
                       body=body.encode(), final_url="")


def _ok_probe(url, referer, kind):
    return {"ok": True, "kind": kind or "playlist",
            "mime": "application/vnd.apple.mpegurl", "size": None,
            "resumable": False, "renditions": [(1280, 720)],
            "failure": None}


class StubFetch:
    """URL -> FetchResult map; records every call (url, kwargs)."""

    def __init__(self, mapping):
        self.mapping = mapping
        self.calls = []

    def __call__(self, url, referer=None, method="GET", range_bytes=None,
                 data=None, json_data=None, timeout=None):
        self.calls.append((url, {"referer": referer, "method": method,
                                 "data": data, "json_data": json_data}))
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
