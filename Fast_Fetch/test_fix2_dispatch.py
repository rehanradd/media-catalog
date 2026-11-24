#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline dispatch tests for FIX2 (host/API sweep 2026-11-23): wire the
mp4moviez dl.php unwrapper into `fast_fetch.resolve_live_source`.

The converter stores the site's own dl.php redirect endpoint and never
follows it, so all 53,096 mp4moviez rows carry final_url = NULL (10,560
posts have no other source) and a press answered `no_unwrapper`. The FIX2
branch follows the site's redirects -- 301 to the live host, 302 to a
signed fastxmp4 .mp4 -- and banks the .mp4 as a temporary row (signed
e/st params, unmeasured expiry, resolved at play time). The generic
Stage-2 gate Range-probes the .mp4 (206, measured live); the unwrapper's
1-byte GET is not the gate evidence, so `_gate_from_verdict` stays False.

No network, no production DB: every test runs against a temp SQLite file
(via RADD_DB_PATH) and a loopback server that replays the canned chain,
with the Stage-2 probe and the TTL probe mocked. The tests drive the REAL
`unwrap_mp4moviez` (mocked `http_get` would not reach it -- it uses
`urllib.request.urlopen` directly), exactly as the FIX1 tests drive the
real vidout/gdrive unwrappers behind mocked fetches.
"""

import os
import sqlite3
import sys
import tempfile
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

os.environ.setdefault("RADD_SKIP_LIVE_ENRICH", "1")

_TMPDIR = tempfile.mkdtemp(prefix="fix2-dispatch-")
_DB_PATH = os.path.join(_TMPDIR, "fix2.db")
os.environ["RADD_DB_PATH"] = _DB_PATH

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)
sys.path.insert(0, os.path.join(_HERE, "importers"))

import fast_fetch  # noqa: E402
import unwrap_mp4moviez as um  # noqa: E402

DL_ID = "60928"
DL_Q = "480"
DL_TITLE = "Anora-(2024)-Hindi-Dubbed"
DL_PATH = f"/dl.php?id={DL_ID}&q={DL_Q}&title={DL_TITLE}"
MP4_BODY = (b"\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00mp42iso6"
            + b"\x00" * 500)


def _ok_file_probe(url):
    return {"ok": True, "kind": "file", "mime": "video/mp4",
            "size": 328540975, "resumable": True, "renditions": [],
            "failure": None}


def _no_directive_ttl(url):
    return None, {"observed": False, "reason": "no_directive"}


class _CountingHandler(BaseHTTPRequestHandler):
    requests_seen = []

    def _record(self):
        self.__class__.requests_seen.append(self.path)

    def log_message(self, *args):  # noqa: D102 - silence test output
        pass


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


class Fix2DispatchTests(unittest.TestCase):
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
                "VALUES (1, 1, 'Example 480p', '480p')")
            conn.commit()
        self._next_source = 0
        for handler_cls in (ChainHandler, DeadHandler, HtmlHandler):
            handler_cls.requests_seen = []

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

    # ---- the happy path ------------------------------------------------- #

    def test_mp4moviez_dispatch_resolves_dl_to_fastxmp4(self):
        """A press on an mp4moviez row (final_url NULL, as all 53,096 live
        rows are) follows the dl.php chain to the signed fastxmp4 .mp4,
        banks it as a temporary row, and answers success with it. The
        dispatch drives the real unwrapper over the canned 301 -> 302 ->
        200 chain: exactly three requests, no mocks on the chain itself."""
        base = SERVERS["chain"][1]
        sid = self._add_source("mp4moviez", base + DL_PATH, None)
        with patch.object(fast_fetch, "_stage2_probe",
                          side_effect=_ok_file_probe), \
                patch.object(fast_fetch, "probe_url_ttl",
                             side_effect=_no_directive_ttl):
            res = fast_fetch.resolve_live_source(sid, v2=True)
        self.assertTrue(res["success"], res)
        final = urllib.parse.urlsplit(res["url"])
        self.assertEqual(
            final.path,
            f"/download/{DL_ID}/{DL_TITLE}--{DL_Q}p-[Orgmovies].mp4")
        self.assertEqual(sorted(urllib.parse.parse_qs(final.query).keys()),
                         ["e", "st"])
        self.assertTrue(res["refreshed"])
        self.assertFalse(res["cached"])
        self.assertTrue(res["is_temporary"],
                        "signed e/st params: banked temporary, resolved at play time")
        self.assertEqual(self._row(sid)["final_url"], res["url"])
        self.assertEqual(len(ChainHandler.requests_seen), 3)

    # ---- honest failures ------------------------------------------------- #

    def test_mp4moviez_dispatch_dead_dl_is_honest_failure(self):
        """A dl.php that 404s is no_playable_source, not a redirect at the
        dead page — and the failure is measured (a branch ran), not
        unprobed."""
        base = SERVERS["dead"][1]
        sid = self._add_source("mp4moviez", base + DL_PATH, None)
        with patch.object(fast_fetch, "_stage2_probe",
                          side_effect=_ok_file_probe), \
                patch.object(fast_fetch, "probe_url_ttl",
                             side_effect=_no_directive_ttl):
            res = fast_fetch.resolve_live_source(sid, v2=True)
        self.assertFalse(res["success"])
        self.assertEqual(res.get("code"), "no_playable_source")
        self.assertFalse(res.get("unprobed"),
                         "a branch ran, so the failure is measured, not unprobed")

    def test_mp4moviez_dispatch_non_media_final_is_honest_failure(self):
        """A dl.php that 302s to an HTML page is no_playable_source — the
        unwrapper refuses a non-media final, and the branch never banks
        the page as a playable address."""
        base = SERVERS["html"][1]
        sid = self._add_source("mp4moviez", base + DL_PATH, None)
        with patch.object(fast_fetch, "_stage2_probe",
                          side_effect=_ok_file_probe), \
                patch.object(fast_fetch, "probe_url_ttl",
                             side_effect=_no_directive_ttl):
            res = fast_fetch.resolve_live_source(sid, v2=True)
        self.assertFalse(res["success"])
        self.assertEqual(res.get("code"), "no_playable_source")
        self.assertFalse(res.get("unprobed"))
        self.assertIsNone(self._row(sid)["final_url"])

    def test_mp4moviez_dispatch_wrong_title_final_404_is_honest_failure(self):
        """Live behaviour: a mistitled id still 302s, but to a fastxmp4
        file that 404s. The dispatch answers no_playable_source instead
        of banking the dead file."""
        base = SERVERS["chain"][1]
        bad = f"{base}/dl.php?id=99999&q=480&title=Wrong-Title"
        sid = self._add_source("mp4moviez", bad, None)
        with patch.object(fast_fetch, "_stage2_probe",
                          side_effect=_ok_file_probe), \
                patch.object(fast_fetch, "probe_url_ttl",
                             side_effect=_no_directive_ttl):
            res = fast_fetch.resolve_live_source(sid, v2=True)
        self.assertFalse(res["success"])
        self.assertEqual(res.get("code"), "no_playable_source")
        self.assertFalse(res.get("unprobed"))
        self.assertIsNone(self._row(sid)["final_url"])

    def test_mp4moviez_dispatch_non_dl_url_is_honest_failure_no_fetch(self):
        """An mp4moviez-labelled row whose URL is not a dl.php endpoint is
        no_playable_source — the unwrapper refuses it before opening a
        socket (the loopback chain sees zero requests)."""
        sid = self._add_source("mp4moviez", "https://example.test/file/abc",
                               "https://example.test/file/abc")
        with patch.object(fast_fetch, "_stage2_probe",
                          side_effect=AssertionError("no probe expected")), \
                patch.object(fast_fetch, "probe_url_ttl",
                             side_effect=_no_directive_ttl):
            res = fast_fetch.resolve_live_source(sid, v2=True)
        self.assertFalse(res["success"])
        self.assertEqual(res.get("code"), "no_playable_source")
        self.assertFalse(res.get("unprobed"))
        self.assertEqual(ChainHandler.requests_seen, [])
        self.assertEqual(DeadHandler.requests_seen, [])
        self.assertEqual(HtmlHandler.requests_seen, [])


if __name__ == "__main__":
    unittest.main()
