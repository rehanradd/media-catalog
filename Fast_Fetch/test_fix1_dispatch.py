#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline dispatch tests for FIX1 (host/API sweep 2026-11-23): wire the
two previously ingest-only resolvers into
`fast_fetch.resolve_live_source`.

- vidout: `resolve_vidout_playlist` existed but was called only at ingest;
  a press on a vidout row answered `no_unwrapper`. The FIX1 branch runs
  BEFORE the Phase C `unwrap_vidout` branch (wecollege -> GitHub API):
  a page-published playlist wins; a page with no playlist falls back to
  the API path inside the same branch (the Phase C test pins that
  fallback, so the branch never shadows it).
- gdrive / google_drive: `host_resolvers.unwrap_gdrive` existed but was
  called only at ingest (import_animahd); a press on a gdrive row answered
  `no_unwrapper`.
- filepress: already wired on current main — `host_resolvers.unwrap_filepress`
  is the real downlaod/downlaod2 API unwrapper (cracked 2026-11-13) with a
  dispatch branch of its own. The sweep's "identity function, not wired"
  note described its stale worktree base (where only the ingest-only
  `fast_fetch.unwrap_filepress` existed). FIX1 adds no filepress branch
  and leaves that one alone; the test pins the routing instead.

No network, no production DB: every test runs against a temp SQLite file
(via RADD_DB_PATH) with all fetches/probes mocked. The vidout test drives
the real `resolve_vidout_playlist` (mocked `http_get`); the gdrive tests
drive the real `unwrap_gdrive` (mocked `host_resolvers.http_request`).
"""

import os
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch

os.environ.setdefault("RADD_SKIP_LIVE_ENRICH", "1")

_TMPDIR = tempfile.mkdtemp(prefix="fix1-dispatch-")
_DB_PATH = os.path.join(_TMPDIR, "fix1.db")
os.environ["RADD_DB_PATH"] = _DB_PATH

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fast_fetch  # noqa: E402

FILE_ID = "10i-vf0OzJqDkR0-a7MpPWKI0qERYAOp1"
EMBED_URL = "https://vidout.pages.dev/movie/tt1234567"
PLAYLIST_URL = "https://cdn.example.test/hls/master.m3u8?token=abc123"
DRIVE_UC_URL = f"https://drive.google.com/uc?export=download&id={FILE_ID}"
DRIVE_BYTE_URL = (
    f"https://drive.usercontent.google.com/download?id={FILE_ID}"
    "&export=download&confirm=t"
)


def _ok_playlist_probe(url):
    return {"ok": True, "kind": "playlist", "mime": "application/vnd.apple.mpegurl",
            "size": None, "resumable": False, "renditions": [], "failure": None}


def _no_directive_ttl(url):
    return None, {"observed": False, "reason": "no_directive"}


class Fix1DispatchTests(unittest.TestCase):
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

    # ---- vidout ------------------------------------------------------- #

    def test_vidout_dispatch_routes_embed_to_playlist(self):
        """A press on a vidout row fetches the embed page, banks the
        playlist it publishes, and answers success with it."""
        sid = self._add_source("vidout", EMBED_URL, EMBED_URL)

        def fake_http_get(url, timeout=12):
            self.assertEqual(url, EMBED_URL)
            return 200, url, (
                '<script>const src="https://cdn.example.test/hls/master.m3u8'
                '?token=abc123";</script>')

        with patch.object(fast_fetch, "http_get", side_effect=fake_http_get), \
                patch.object(fast_fetch, "_stage2_probe",
                             side_effect=_ok_playlist_probe), \
                patch.object(fast_fetch, "probe_url_ttl",
                             side_effect=_no_directive_ttl):
            res = fast_fetch.resolve_live_source(sid, v2=True)
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], PLAYLIST_URL)
        self.assertTrue(res["refreshed"])
        self.assertFalse(res["cached"])
        self.assertEqual(self._row(sid)["final_url"], PLAYLIST_URL)

    def test_vidout_dispatch_without_playlist_is_honest_failure(self):
        """An embed page with no playlist is no_playable_source, not a
        redirect at the embed page — provided the Phase C API fallback
        also answers inactive (mocked here, so the test stays offline).
        The fallback is what keeps currently-working vidout rows alive:
        the /movie/<imdb> page carries no extractable .m3u8."""
        sid = self._add_source("vidout", EMBED_URL, EMBED_URL)

        def fake_http_get(url, timeout=12):
            return 200, url, "<html><body>no video here</body></html>"

        hr = fast_fetch._embed_resolvers()
        self.assertIsNotNone(hr)
        with patch.object(fast_fetch, "http_get", side_effect=fake_http_get), \
                patch.object(hr, "unwrap_vidout",
                             return_value={"status": "unknown",
                                           "final_url": None,
                                           "service": "vidout",
                                           "is_temporary": 1}):
            res = fast_fetch.resolve_live_source(sid, v2=True)
        self.assertFalse(res["success"])
        self.assertEqual(res.get("code"), "no_playable_source")
        self.assertFalse(res.get("unprobed"),
                         "a branch ran, so the failure is measured, not unprobed")

    # ---- gdrive / google_drive ---------------------------------------- #

    def _fake_drive_fetch(self, url, headers=None, timeout=10,
                          method="GET", follow=True, data=None):
        if url.startswith("https://drive.google.com/uc"):
            self.assertIn(FILE_ID, url)
            return (200, b"", {"content-type": "video/mp4"}, DRIVE_BYTE_URL)
        if url.startswith("https://drive.usercontent.google.com/download"):
            self.assertTrue((headers or {}).get("Range", "").startswith("bytes=0-"),
                            "the byte probe must carry a Range")
            return (206, b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 500,
                    {"content-type": "video/mp4",
                     "content-range": "bytes 0-511/424242"}, url)
        raise AssertionError(f"unexpected fetch: {url}")

    def test_gdrive_dispatch_resolves_uc_url(self):
        """A press on a gdrive row (drive.google.com/uc?id=...) reaches
        unwrap_gdrive and answers success with the byte URL."""
        sid = self._add_source("gdrive", DRIVE_UC_URL, DRIVE_UC_URL)
        hr = fast_fetch._embed_resolvers()
        self.assertIsNotNone(hr)
        with patch.object(hr, "http_request",
                          side_effect=self._fake_drive_fetch), \
                patch.object(fast_fetch, "probe_url_ttl",
                             side_effect=_no_directive_ttl):
            res = fast_fetch.resolve_live_source(sid, v2=True)
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], DRIVE_BYTE_URL)
        self.assertTrue(res["refreshed"])
        self.assertFalse(res["is_temporary"],
                         "a small public file's byte URL is not temporary")
        self.assertEqual(self._row(sid)["final_url"], DRIVE_BYTE_URL)

    def test_google_drive_usercontent_url_is_rewritten_to_uc(self):
        """The moviezguru rows store the byte host directly
        (drive.usercontent.google.com/download?id=...), which
        gdrive_file_id does not read. The branch rewrites the same file id
        onto the /uc endpoint the unwrapper parses — the routing is proven
        by the first fetch being the /uc URL.

        KNOWN LIMIT (pre-existing, shared logic, not this branch): the
        unwrapper proves the byte URL, but it EQUALS the stored source_url,
        so the resolver's `fresh_url != source_u` guard discards the
        gate-proven URL and v2 answers no_playable_source. The live rows
        are is_temporary=0, so while fresh they are served by the cached
        Stage-2 branch without ever reaching dispatch; only a stale or
        demoted usercontent row hits this guard. Lifting the guard is a
        cross-branch behaviour change for the gate to decide, not a
        one-branch fix — this test pins the routing and the current
        outcome, so a future guard change shows up here."""
        sid = self._add_source("google_drive", DRIVE_BYTE_URL, DRIVE_BYTE_URL)
        hr = fast_fetch._embed_resolvers()
        seen = []

        def spy_fetch(url, **kwargs):
            seen.append(url)
            return self._fake_drive_fetch(url, **kwargs)

        with patch.object(hr, "http_request", side_effect=spy_fetch), \
                patch.object(fast_fetch, "probe_url_ttl",
                             side_effect=_no_directive_ttl):
            res = fast_fetch.resolve_live_source(sid, v2=True)
        self.assertTrue(
            seen and seen[0].startswith("https://drive.google.com/uc?"),
            f"first fetch must be the /uc rewrite, saw: {seen}")
        self.assertFalse(res["success"], res)
        self.assertEqual(res.get("code"), "no_playable_source")

    def test_gdrive_non_drive_url_is_honest_failure(self):
        """A gdrive-labelled row whose URL names no Drive file is
        no_playable_source — unwrap_gdrive answers 'unknown' without
        opening a socket."""
        sid = self._add_source("gdrive", "https://example.test/file/abc",
                               "https://example.test/file/abc")
        hr = fast_fetch._embed_resolvers()
        with patch.object(hr, "http_request",
                          side_effect=AssertionError("no fetch expected")):
            res = fast_fetch.resolve_live_source(sid, v2=True)
        self.assertFalse(res["success"])
        self.assertEqual(res.get("code"), "no_playable_source")

    # ---- filepress: already wired on main, FIX1 leaves it alone ------- #

    def test_filepress_routes_to_real_unwrapper(self):
        """Filepress IS wired on current main (hr.unwrap_filepress, the
        downlaod/downlaod2 API chain cracked 2026-11-13, with a dispatch
        branch above the FIX1 branches) — the sweep's "identity function,
        not wired" note described the stale worktree base. FIX1 adds no
        filepress branch and leaves that one alone. This test pins the
        routing: a press calls the real unwrapper, and when it answers
        inactive the press is a measured no_playable_source (unprobed
        False), not an unprobed no_unwrapper. If the branch is ever
        removed, this test fails on the unprobed assertion."""
        sid = self._add_source(
            "filepress",
            "https://new3.filepress.baby/file/6a8adba687ebcb22d0263089",
            "https://new3.filepress.baby/file/6a8adba687ebcb22d0263089")
        hr = fast_fetch._embed_resolvers()
        self.assertIsNotNone(hr)
        with patch.object(hr, "unwrap_filepress",
                          return_value={"status": "unknown",
                                        "final_url": None,
                                        "service": "filepress",
                                        "is_temporary": 1}) as mocked:
            res = fast_fetch.resolve_live_source(sid, v2=True)
        mocked.assert_called_once()
        self.assertFalse(res["success"])
        self.assertEqual(res.get("code"), "no_playable_source")
        self.assertFalse(res.get("unprobed"),
                         "a branch ran, so the failure is measured, not unprobed")

    # ---- unknown service: the generic fall-through -------------------- #

    def test_unknown_service_falls_through_unprobed(self):
        """A service with no branch at all still falls through to the
        generic path: v2 answers no_playable_source, unprobed, with no
        socket opened."""
        sid = self._add_source("unknownhost9",
                               "https://nowhere.example.test/film",
                               "https://nowhere.example.test/film")
        with patch.object(fast_fetch, "_stage2_probe",
                          side_effect=AssertionError("no probe expected")):
            res = fast_fetch.resolve_live_source(sid, v2=True)
        self.assertFalse(res["success"])
        self.assertEqual(res.get("code"), "no_playable_source")
        self.assertTrue(res.get("unprobed"), res)


if __name__ == "__main__":
    unittest.main()
