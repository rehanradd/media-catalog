#!/usr/bin/env python3
"""
Offline tests for unwrap_mp4moviez.py — the stored-dl.php resolver.

Everything here runs against ThreadingHTTPServer instances on 127.0.0.1
with ephemeral ports; no third-party network traffic at all. The canned
chain mirrors the LIVE chain measured 2026-11-23 from Oracle VM egress
(see unwrap_mp4moviez.py docstring):

    GET /dl.php?id=<n>&q=<q>&title=<slug>          301 -> /dl-live.php (same query)
    GET /dl-live.php                                302 -> /download/<n>/<slug>--<q>p-[Orgmovies].mp4?e=..&st=..
    GET /download/<n>/...mp4                       200 video/mp4

Live (not asserted here — network-dependent, recorded for the record):
    hop 1 (301) 57-65 ms; hop 2 (302) 206-258 ms; hop 3 (200) 521-523 ms;
    full 3-hop resolve 788-833 ms; Range bytes=0-0 -> 206, file
    326,751,422 / 328,540,975 bytes for the two probed ids.

The one thing the canned chain cannot reproduce is the live host hop
(.computer -> mp4moviez11.com -> dl2.fastxmp4.com): the local server is one
host, so the domain hop is simulated as a path hop and the test server's
netloc is added to DL_HOSTS for the duration of the suite. Host validation
itself is still tested against the production set.

Run from the repo root:  python3 -S Fast_Fetch/importers/test_unwrap_mp4moviez.py
"""

from __future__ import annotations

import sys
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import unwrap_mp4moviez as um  # noqa: E402

# Canned chain fixture — the real dl.php page shape, redacted: dl.php is a
# pure redirect endpoint (live probe: zero anchors, zero forms, no HTML
# body at all), so the "page" is the redirect chain itself. Ids below are
# the two ids probed live (60928, 44418).
DL_ID = "60928"
DL_Q = "480"
DL_TITLE = "Tomorrow-When-the-War-Began-(2010)-Hindi-Dubbed"
DL_PATH = f"/dl.php?id={DL_ID}&q={DL_Q}&title={DL_TITLE}"
MP4_BODY = b"\x00\x00\x00\x18ftypmp42" + b"m" * 100


class _CountingHandler(BaseHTTPRequestHandler):
    requests_seen: list = []

    def log_message(self, *a):  # noqa: ANN001 - silence test server chatter
        pass

    def _record(self):
        self.requests_seen.append(self.path)


class ChainHandler(_CountingHandler):
    """301 -> 302 -> 200 video/mp4; id 99999 404s on the final hop (the live
    wrong-title behaviour: a mistitled id still 302s, but to a dead file)."""

    def do_GET(self):  # noqa: N802 - handler name is the http.server contract
        self._record()
        parts = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(parts.query)
        if parts.path == "/dl.php":
            loc = f"/dl-live.php?{parts.query}"
            self.send_response(301)
            self.send_header("Location", loc)
            self.send_header("Content-Type", "text/html; charset=UTF-8")
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif parts.path == "/dl-live.php":
            dl_id = query.get("id", [""])[0]
            q = query.get("q", [""])[0]
            title = query.get("title", [""])[0]
            loc = (f"/download/{dl_id}/{title}--{q}p-[Orgmovies].mp4"
                   f"?e=testexpiry&st=testsignature")
            self.send_response(302)
            self.send_header("Location", loc)
            self.send_header("Content-Type", "text/html; charset=UTF-8")
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif parts.path.startswith("/download/"):
            dl_id = parts.path.split("/")[2]
            if dl_id == "99999":
                self.send_response(404)
                self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if self.headers.get("Range") == "bytes=0-0":
                self.send_response(206)
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Content-Range", "bytes 0-0/328540975")
                self.send_header("Content-Length", "1")
                self.end_headers()
                self.wfile.write(MP4_BODY[:1])
                return
            self.send_response(200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(len(MP4_BODY)))
            self.end_headers()
            self.wfile.write(MP4_BODY)
        else:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()


class DeadHandler(_CountingHandler):
    """dl.php itself is gone."""

    def do_GET(self):  # noqa: N802
        self._record()
        self.send_response(404)
        self.send_header("Content-Type", "text/html")
        self.send_header("Content-Length", "0")
        self.end_headers()


class HtmlHandler(_CountingHandler):
    """dl.php 302s to an HTML page, not media — must not count as resolved."""

    def do_GET(self):  # noqa: N802
        self._record()
        if self.path.startswith("/dl.php"):
            self.send_response(302)
            self.send_header("Location", "/page.html")
            self.send_header("Content-Length", "0")
            self.end_headers()
        else:
            body = b"<html><body>not media</body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=UTF-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)


def _start(handler_cls):
    srv = ThreadingHTTPServer(("127.0.0.1", 0), handler_cls)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, kwargs={"poll_interval": 0.05},
                     daemon=True).start()
    return srv, f"http://127.0.0.1:{srv.server_address[1]}"


SERVERS = {}


def setUpModule():
    for name, cls in (("chain", ChainHandler), ("dead", DeadHandler),
                      ("html", HtmlHandler)):
        SERVERS[name] = _start(cls)
    # The canned chain runs on loopback, not a mp4moviez host: admit this
    # one test netloc so the follow logic is exercised end to end.
    for _srv, base in SERVERS.values():
        um.DL_HOSTS = um.DL_HOSTS | {urllib.parse.urlsplit(base).netloc}


def tearDownModule():
    for srv, _base in SERVERS.values():
        srv.shutdown()
        srv.server_close()


class ParseTests(unittest.TestCase):
    def test_valid_stored_url(self):
        url = f"https://www.mp4moviez.computer/dl.php?id={DL_ID}&q={DL_Q}&title={DL_TITLE}"
        parsed = um.parse_dl_url(url)
        self.assertEqual(parsed, {"id": DL_ID, "quality": DL_Q,
                                  "title": DL_TITLE,
                                  "host": "www.mp4moviez.computer"})

    def test_live_redirect_host_accepted(self):
        url = f"https://www.mp4moviez11.com/dl.php?id={DL_ID}&q={DL_Q}&title={DL_TITLE}"
        self.assertTrue(um.is_mp4moviez_dl_url(url))

    def test_missing_id_or_quality_rejected(self):
        base = "https://www.mp4moviez.computer/dl.php"
        self.assertIsNone(um.parse_dl_url(f"{base}?q=480&title=x"))
        self.assertIsNone(um.parse_dl_url(f"{base}?id=60928&title=x"))
        self.assertIsNone(um.parse_dl_url(f"{base}?id=abc&q=480"))

    def test_wrong_host_path_and_garbage_rejected(self):
        self.assertIsNone(um.parse_dl_url(
            "https://www.mp4moviez.deal/dl.php?id=60928&q=480"))
        self.assertIsNone(um.parse_dl_url(
            "https://example.com/dl.php?id=60928&q=480"))
        self.assertIsNone(um.parse_dl_url(
            "https://www.mp4moviez.computer/movie.html"))
        self.assertIsNone(um.parse_dl_url(""))
        self.assertIsNone(um.parse_dl_url(None))
        self.assertIsNone(um.parse_dl_url("not a url"))

    def test_is_helper(self):
        self.assertTrue(um.is_mp4moviez_dl_url(
            "https://www.mp4moviez.computer/dl.php?id=60928&q=720&title=x"))
        self.assertFalse(um.is_mp4moviez_dl_url(
            "https://www.mp4moviez.computer/dl.php?id=60928"))


class ResolveTests(unittest.TestCase):
    def setUp(self):
        ChainHandler.requests_seen = []

    def test_follows_canned_chain_to_mp4(self):
        base = SERVERS["chain"][1]
        resolved = um.resolve_mp4moviez_dl(base + DL_PATH)
        self.assertIsNotNone(resolved)
        final = urllib.parse.urlsplit(resolved["final_url"])
        self.assertEqual(final.path,
                         f"/download/{DL_ID}/{DL_TITLE}--{DL_Q}p-[Orgmovies].mp4")
        self.assertEqual(sorted(urllib.parse.parse_qs(final.query).keys()),
                         ["e", "st"])
        self.assertEqual(resolved["content_type"], "video/mp4")
        self.assertGreaterEqual(resolved["ms"], 0)
        # Exactly the three hops of the live chain: 301, 302, 200.
        self.assertEqual(len(ChainHandler.requests_seen), 3)

    def test_unwrap_row_shape(self):
        base = SERVERS["chain"][1]
        rows = um.unwrap_mp4moviez(base + DL_PATH)
        self.assertEqual(len(rows), 1)
        row = rows[0]
        self.assertEqual(row["service"], "fastxmp4")
        self.assertEqual(row["resource_id"], DL_ID)
        self.assertEqual(row["source_url"], base + DL_PATH)
        # The signed fastxmp4 URL carries ?e=..&st=.. after the .mp4 path,
        # so the path (not the raw URL) ends in .mp4.
        self.assertTrue(urllib.parse.urlsplit(row["final_url"]).path.endswith(".mp4"))
        self.assertEqual(row["source_name"], "mp4moviez 480p download")
        # Signed fastxmp4 params (e/st) of unmeasured expiry: temporary.
        self.assertEqual(row["is_temporary"], 1)

    def test_dead_dl_returns_empty(self):
        base = SERVERS["dead"][1]
        self.assertEqual(um.unwrap_mp4moviez(base + DL_PATH), [])
        self.assertIsNone(um.resolve_mp4moviez_dl(base + DL_PATH))

    def test_non_media_final_returns_empty(self):
        base = SERVERS["html"][1]
        self.assertEqual(um.unwrap_mp4moviez(base + DL_PATH), [])

    def test_wrong_title_final_404_returns_empty(self):
        # Live: a mistitled id 302s to a fastxmp4 file that 404s.
        base = SERVERS["chain"][1]
        bad = f"{base}/dl.php?id=99999&q=480&title=Wrong-Title"
        self.assertEqual(um.unwrap_mp4moviez(bad), [])

    def test_garbage_input_returns_empty_without_raising(self):
        for bad in ("", None, "not a url",
                    "https://example.com/dl.php?id=1&q=480"):
            self.assertEqual(um.unwrap_mp4moviez(bad), [])


if __name__ == "__main__":
    unittest.main()
