#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline tests for the vidmoly unwrapper (sweep S2, desicinemas,
2026-11-23): `fast_fetch.unwrap_vidmoly` + its `resolve_live_source`
dispatch branch.

Live shape the fixtures reproduce (Oracle egress, 2026-11-23, desicinemas
trembed -> vidmoly.org/embed-nety8pu43f8i.html): the embed page is 200,
~71 KB, carries a P.A.C.K.E.R. block that unpacks to ~1 KB with NO media
key, and publishes exactly one .m3u8 in plain JWPlayer setup —
`player.setup({ sources: [{ file: 'https://prx-am-s-1.vmpx.online/hls2/
.../master.m3u8?t=..&s=<10-digit issue epoch>&e=43200&srv=..&i=0.4&sp=0
&asn=0' }] })`. The Range probe of that master answers 200
application/vnd.apple.mpegurl with a real #EXTM3U body in ~0.6 s. No
Referer is required on either hop (proven with and without).

That shape is exactly why the old packer branch fails here: it only reads
media URLs out of the unpacked config, so it answers "unknown" on every
real vidmoly page. The canned pages below all carry a media-less packed
block, so a test that passes here proves the JWPlayer path — if routing
ever fell back to the packer, the same fixture would fail.

No network, no production DB: the unwrap tests drive the real
`unwrap_vidmoly` with canned `_fetch`/`_probe` seams, the probe tests
run against a loopback HTTP server, and the dispatch tests run against a
temp SQLite file (via RADD_DB_PATH) with all fetches/probes mocked.
"""

import http.server
import os
import socket
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

os.environ.setdefault("RADD_SKIP_LIVE_ENRICH", "1")

_TMPDIR = tempfile.mkdtemp(prefix="vidmoly-unwrap-")
_DB_PATH = os.path.join(_TMPDIR, "vidmoly.db")
os.environ["RADD_DB_PATH"] = _DB_PATH

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fast_fetch  # noqa: E402

EMBED_URL = "https://vidmoly.org/embed-nety8pu43f8i.html"
TREMBED_URL = "https://www.desicinemas.pk/?trembed=0&trid=13723&trtype=1"
MASTER_HOST = "https://prx-am-s-1.vmpx.online"
MASTER_PATH = "/hls2/01/03082/nety8pu43f8i_n/master.m3u8"

# Packed block with no media key, as on the live page: the packer
# unwrapper unpacks this and finds no hls2/hls4/file URL.
_PACKED_STUB = (
    "<script>eval(function(p,a,c,k,e,d){e=function(c){return c.toString(36)};"
    "if(!''.replace(/^/,String)){while(c--){d[c.toString(a)]=k[c]||c.toString(a)}"
    "k=[function(e){return d[e]}];e=function(){return'\\\\w+'};c=1};"
    "while(c--){if(k[c]){p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c])}}"
    "return p}('0 1=\"2\";',3,3,'var|title|VidMoly'.split('|'),0,{}))</script>")

MASTER_QUERY = "?t=len44:k92igVGB&s=len10:17954303&e=len5:43200&srv=len7:e-n-c-3"


def _master_url(issued_ago=10, lifetime=43200):
    """The live master shape, with a fresh s= issue epoch so expires_in
    is measurable (param values are shapes only — no real token)."""
    issued = int(time.time()) - issued_ago
    return (f"{MASTER_HOST}{MASTER_PATH}?t={'x' * 44}&s={issued}"
            f"&e={lifetime}&srv=e-n-c-3&i=0.4&sp=0&asn=0")


def _embed_page(master_url, jwplayer=True):
    if jwplayer:
        setup = (
            "<script>var playerInstance = player.setup({\n"
            f"      sources: [{{ file: '{master_url}' }}],\n"
            "      image: 'https://vidmoly.org/poster.jpg',\n"
            "      autostart: false });</script>")
    else:
        setup = ("<script>var direct = "
                 f"\"{master_url}\";</script>")
    return ("<!DOCTYPE html><html><head><title>VidMoly</title></head><body>"
            + _PACKED_STUB + setup + "</body></html>")


def _ok_playlist_probe(url, referer=None):
    return {"status": "active", "http_status": 200,
            "content_type": "application/vnd.apple.mpegurl",
            "size_bytes": 1223, "is_playlist": True,
            "reason": "HLS master playlist"}


def _no_directive_ttl(url):
    return None, {"observed": False, "reason": "no_directive"}


class VidmolyUnwrapTests(unittest.TestCase):
    def test_jwplayer_file_extracted_and_verified(self):
        """The live shape: JWPlayer file: URL on a page whose packed block
        carries no media key — extracted, signed-window parsed, probe
        active."""
        master = _master_url()
        page = _embed_page(master)
        seen = {}

        def fake_fetch(url, **kwargs):
            self.assertEqual(url, EMBED_URL)
            return 200, url, page

        def fake_probe(url, referer):
            seen["url"] = url
            seen["referer"] = referer
            return _ok_playlist_probe(url, referer)

        res = fast_fetch.unwrap_vidmoly(EMBED_URL, verify=True,
                                        _fetch=fake_fetch, _probe=fake_probe)
        self.assertEqual(res["status"], "active", res)
        self.assertEqual(res["final_url"], master)
        self.assertEqual(seen["url"], master)
        self.assertEqual(seen["referer"], EMBED_URL)
        self.assertTrue(res["is_temporary"])
        self.assertIsNotNone(res["expires_in"])
        self.assertGreater(res["expires_in"], 43100)
        self.assertLessEqual(res["expires_in"], 43200)
        self.assertEqual(res["http_status"], 200)
        self.assertEqual(res["content_type"],
                         "application/vnd.apple.mpegurl")
        self.assertEqual(res["sources"][0]["label"], "hls")

    def test_verify_false_extracts_only(self):
        """verify=False answers 'unverified' with the master set and never
        calls the probe."""
        master = _master_url()

        def fake_fetch(url, **kwargs):
            return 200, url, _embed_page(master)

        res = fast_fetch.unwrap_vidmoly(
            EMBED_URL, verify=False, _fetch=fake_fetch,
            _probe=lambda u, r: (_ for _ in ()).throw(
                AssertionError("probe must not run")))
        self.assertEqual(res["status"], "unverified", res)
        self.assertEqual(res["final_url"], master)

    def test_generic_m3u8_fallback(self):
        """A page with no JWPlayer setup but a bare .m3u8 URL still
        resolves through the generic fallback."""
        master = _master_url()
        page = ("<html><body>" + _PACKED_STUB
                + f"<a href=\"{master}\">watch</a></body></html>")

        def fake_fetch(url, **kwargs):
            return 200, url, page

        res = fast_fetch.unwrap_vidmoly(EMBED_URL, verify=True,
                                        _fetch=fake_fetch,
                                        _probe=_ok_playlist_probe)
        self.assertEqual(res["status"], "active", res)
        self.assertEqual(res["final_url"], master)

    def test_no_m3u8_is_unknown_not_dead(self):
        """An embed page with no .m3u8 at all is 'unknown' — the page
        answered, the media is just not on it."""
        def fake_fetch(url, **kwargs):
            return 200, url, "<html><body>" + _PACKED_STUB + "</body></html>"

        res = fast_fetch.unwrap_vidmoly(
            EMBED_URL, verify=True, _fetch=fake_fetch,
            _probe=lambda u, r: (_ for _ in ()).throw(
                AssertionError("probe must not run")))
        self.assertEqual(res["status"], "unknown", res)
        self.assertEqual(res["final_url"], "")
        self.assertIn("no .m3u8", " ".join(res["notes"]))

    def test_embed_404_is_dead(self):
        """A removed embed (404) is 'dead'."""
        def fake_fetch(url, **kwargs):
            return 404, url, "not found"

        res = fast_fetch.unwrap_vidmoly(EMBED_URL, _fetch=fake_fetch)
        self.assertEqual(res["status"], "dead", res)
        self.assertEqual(res["http_status"], 404)

    def test_embed_403_is_blocked(self):
        """A challenged embed (403) is 'blocked', not 'dead'."""
        def fake_fetch(url, **kwargs):
            return 403, url, "forbidden"

        res = fast_fetch.unwrap_vidmoly(EMBED_URL, _fetch=fake_fetch)
        self.assertEqual(res["status"], "blocked", res)

    def test_fetch_exception_is_transport_unknown(self):
        """A fetch that never gets an HTTP answer is transport 'unknown'
        with http_status 0, so the dispatcher does not record a failure
        for our bad night."""
        def fake_fetch(url, **kwargs):
            raise TimeoutError("timed out")

        res = fast_fetch.unwrap_vidmoly(EMBED_URL, _fetch=fake_fetch)
        self.assertEqual(res["status"], "unknown", res)
        self.assertEqual(res["http_status"], 0)

    def test_probe_403_is_blocked(self):
        """A master the probe cannot read (403) is 'blocked'."""
        master = _master_url()

        def fake_fetch(url, **kwargs):
            return 200, url, _embed_page(master)

        def fake_probe(url, referer):
            return {"status": "blocked", "http_status": 403,
                    "content_type": "", "size_bytes": None,
                    "is_playlist": False, "reason": "HTTP 403"}

        res = fast_fetch.unwrap_vidmoly(EMBED_URL, verify=True,
                                        _fetch=fake_fetch, _probe=fake_probe)
        self.assertEqual(res["status"], "blocked", res)
        self.assertEqual(res["final_url"], master)

    def test_probe_non_playlist_is_unknown(self):
        """A 200 that is not a playlist (HTML landing page) is 'unknown',
        never 'active'."""
        master = _master_url()

        def fake_fetch(url, **kwargs):
            return 200, url, _embed_page(master)

        def fake_probe(url, referer):
            return {"status": "unknown", "http_status": 200,
                    "content_type": "text/html", "size_bytes": 512,
                    "is_playlist": False, "reason": "200 text/html"}

        res = fast_fetch.unwrap_vidmoly(EMBED_URL, verify=True,
                                        _fetch=fake_fetch, _probe=fake_probe)
        self.assertEqual(res["status"], "unknown", res)

    def test_non_vidmoly_url_refused(self):
        """A URL that does not name vidmoly is refused without a fetch."""
        res = fast_fetch.unwrap_vidmoly(
            "https://example.test/embed-abc.html",
            _fetch=lambda u, **k: (_ for _ in ()).throw(
                AssertionError("no fetch expected")))
        self.assertEqual(res["status"], "unknown", res)
        self.assertEqual(res["final_url"], "")


class _ProbeHandler(http.server.BaseHTTPRequestHandler):
    seen = {}

    def log_message(self, *args):
        pass

    def _send(self, status, ctype, body):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        _ProbeHandler.seen[self.path] = dict(self.headers)
        if self.path == "/master.m3u8":
            self._send(200, "application/vnd.apple.mpegurl",
                       b"#EXTM3U\n#EXT-X-VERSION:3\n")
        elif self.path == "/gone.m3u8":
            self._send(404, "text/html", b"not found")
        elif self.path == "/blocked.m3u8":
            self._send(403, "text/html", b"forbidden")
        else:
            self._send(200, "text/html", b"<html>landing</html>")


class VidmolyProbeTests(unittest.TestCase):
    """The real `_vidmoly_probe_playlist` against a loopback server:
    the Range + Referer it sends and the status map it returns."""

    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(
            ("127.0.0.1", 0), _ProbeHandler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever,
                                      daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def _url(self, path):
        return f"http://127.0.0.1:{self.port}{path}"

    def test_playlist_active_and_headers_sent(self):
        res = fast_fetch._vidmoly_probe_playlist(
            self._url("/master.m3u8"), EMBED_URL)
        self.assertEqual(res["status"], "active", res)
        self.assertEqual(res["http_status"], 200)
        self.assertTrue(res["is_playlist"])
        headers = _ProbeHandler.seen["/master.m3u8"]
        self.assertTrue(headers.get("Range", "").startswith("bytes=0-"),
                        "the probe must carry a Range")
        self.assertEqual(headers.get("Referer"), EMBED_URL)

    def test_404_is_dead(self):
        res = fast_fetch._vidmoly_probe_playlist(
            self._url("/gone.m3u8"), EMBED_URL)
        self.assertEqual(res["status"], "dead", res)
        self.assertEqual(res["http_status"], 404)

    def test_403_is_blocked(self):
        res = fast_fetch._vidmoly_probe_playlist(
            self._url("/blocked.m3u8"), EMBED_URL)
        self.assertEqual(res["status"], "blocked", res)
        self.assertEqual(res["http_status"], 403)

    def test_non_playlist_200_is_unknown(self):
        res = fast_fetch._vidmoly_probe_playlist(
            self._url("/page"), EMBED_URL)
        self.assertEqual(res["status"], "unknown", res)


class VidmolyDispatchTests(unittest.TestCase):
    def setUp(self):
        if os.path.exists(_DB_PATH):
            os.remove(_DB_PATH)
        fast_fetch.init_db()
        with sqlite3.connect(_DB_PATH) as conn:
            conn.execute(
                "INSERT INTO posts (id, url, title, is_18_plus) "
                "VALUES (1, 'https://example.test/post-1', 'Example', 0)")
            conn.execute(
                "INSERT INTO releases (id, post_id, release_title, quality) "
                "VALUES (1, 1, 'Example 1080p', '1080p')")
            conn.commit()
        self._next_source = 0

    def _add_source(self, service, source_url, final_url,
                    is_temporary=1, status="active"):
        self._next_source += 1
        sid = self._next_source
        with sqlite3.connect(_DB_PATH) as conn:
            conn.execute(
                "INSERT INTO download_sources "
                "(id, release_id, service, source_name, source_url, "
                " final_url, is_temporary, status) "
                "VALUES (?, 1, ?, ?, ?, ?, ?, ?)",
                (sid, service, service, source_url, final_url,
                 is_temporary, status))
            conn.commit()
        return sid

    def _row(self, sid):
        with sqlite3.connect(_DB_PATH) as conn:
            conn.row_factory = sqlite3.Row
            return dict(conn.execute(
                "SELECT * FROM download_sources WHERE id = ?", (sid,)).fetchone())

    def test_vidmoly_dispatch_routes_embed_to_master(self):
        """A press on a vidmoly row fetches the embed page, banks the
        master it publishes, and answers success with it. The canned page
        carries the media-less packed block of the live shape, so this
        also proves the press does NOT fall through to the packer branch
        (which would answer 'unknown' on this page)."""
        master = _master_url()
        sid = self._add_source("vidmoly", EMBED_URL, EMBED_URL)

        def fake_http_get(url, **kwargs):
            self.assertEqual(url, EMBED_URL)
            return 200, url, _embed_page(master)

        with patch.object(fast_fetch, "http_get",
                          side_effect=fake_http_get), \
                patch.object(fast_fetch, "_vidmoly_probe_playlist",
                             side_effect=_ok_playlist_probe), \
                patch.object(fast_fetch, "probe_url_ttl",
                             side_effect=_no_directive_ttl):
            res = fast_fetch.resolve_live_source(sid, v2=True)
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], master)
        self.assertTrue(res["refreshed"])
        self.assertFalse(res["cached"])
        self.assertEqual(self._row(sid)["final_url"], master)

    def test_vidmoly_dispatch_from_trembed_row(self):
        """The desicinemas row shape: source_url is the trembed bridge,
        the vidmoly embed lives only in the cached final_url. The branch
        must still pick the embed up and resolve it."""
        master = _master_url()
        sid = self._add_source("vidmoly", TREMBED_URL, EMBED_URL)

        def fake_http_get(url, **kwargs):
            self.assertEqual(url, EMBED_URL)
            return 200, url, _embed_page(master)

        with patch.object(fast_fetch, "http_get",
                          side_effect=fake_http_get), \
                patch.object(fast_fetch, "_vidmoly_probe_playlist",
                             side_effect=_ok_playlist_probe), \
                patch.object(fast_fetch, "probe_url_ttl",
                             side_effect=_no_directive_ttl):
            res = fast_fetch.resolve_live_source(sid, v2=True)
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], master)
        self.assertEqual(self._row(sid)["final_url"], master)

    def test_vidmoly_dispatch_without_master_is_honest_failure(self):
        """An embed page with no .m3u8 is no_playable_source, not a
        redirect at the embed page — and the failure is measured
        (unprobed False), so it is recorded, not retried blindly."""
        sid = self._add_source("vidmoly", EMBED_URL, EMBED_URL)

        def fake_http_get(url, **kwargs):
            return 200, url, "<html><body>" + _PACKED_STUB + "</body></html>"

        with patch.object(fast_fetch, "http_get",
                          side_effect=fake_http_get), \
                patch.object(fast_fetch, "probe_url_ttl",
                             side_effect=_no_directive_ttl):
            res = fast_fetch.resolve_live_source(sid, v2=True)
        self.assertFalse(res["success"])
        self.assertEqual(res.get("code"), "no_playable_source")
        self.assertFalse(res.get("unprobed"),
                         "a branch ran, so the failure is measured, not unprobed")


if __name__ == "__main__":
    unittest.main()
