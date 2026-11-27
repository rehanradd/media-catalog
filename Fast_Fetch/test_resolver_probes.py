#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline tests for the H4 R9 serve-time resolver probe.

The audit behind this slice: no dead resolution is ever *banked* — every
bank path goes through a probe (``resolve_live_source`` Stage-1/Stage-2/
fresh, ``verify_candidate``, the pool fill, and ``record_resolved_cache``
refusing stale envelopes) — but the stream/download routes *serve* a banked
URL on a clock horizon alone, up to 24 h for a permanent direct file, with
no liveness evidence after the probe that banked it. What this pins:

* the recheck (``serve_verify.recheck_banked``): a row banked within the
  600 s fresh-verdict window serves at once with no probe; an older row is
  re-probed once with the existing capped prober (5 s, ranged GET, the same
  HEAD-liar fallback and clearance the resolver uses). A verifying probe
  serves the row and refreshes it (``resolved_at``/``reverify_after`` move
  to now); an endpoint-answered failure (404, page, empty file, expired
  URL) refuses it; a probe that gets no HTTP answer at all serves the row
  anyway (fail-open — a network blip says nothing about the link) without
  refreshing it, so the next press re-probes. A download additionally
  refuses a playlist even when the probe passes;
* the route half (``viewer._servable_banked_hit``): lookup + recheck in one
  call — a refused row reads as a cache miss, so the live resolver below
  re-resolves and fails over instead of 302-ing a dead winner; a fresh row
  is returned untouched, and a missing row is still None;
* the bank law, pinned again here: a stale (unre-resolvable) envelope is
  never banked, whoever the caller is.

No network, no production DB: every test runs against a scratch SQLite
file with an injected stub prober. The stub returns the same dict shape
``link_health.probe`` returns (``ok`` / ``http_status`` / ``protocol`` /
``content_type`` / ``size_bytes`` / ``error``).
"""

from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

os.environ.setdefault("RADD_SKIP_LIVE_ENRICH", "1")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fast_fetch  # noqa: E402
import resolved_cache  # noqa: E402
import serve_verify  # noqa: E402
import viewer  # noqa: E402


def setUpModule():
    """Branch 2: ``record_resolved_cache`` now probes the URL at bank
    time. This suite banks fixture URLs offline, so the probe is faked
    as passing — the refusal itself is proven by
    ``test_resolved_cache.TestBankRefusesNonMedia``."""
    global _probe_patcher
    _probe_patcher = mock.patch.object(
        resolved_cache, "_media_probe", return_value=(True, None))
    _probe_patcher.start()


def tearDownModule():
    _probe_patcher.stop()


NOW = 1_800_000_000.0
LEDGER_ID = 7
FILE_URL = "https://pixeldrain.com/api/file/s2yMcLkb"
PLAYLIST_URL = "https://pixeldrain.com/api/file/master.m3u8"
EXPIRED_URL = "https://pixeldrain.com/api/file/s2yMcLkb?expires=1700000000"


def ok_file(url, timeout=None):
    return {"ok": True, "http_status": 200, "protocol": "progressive",
            "content_type": "video/mp4", "size_bytes": 12345, "error": ""}


def ok_playlist(url, timeout=None):
    return {"ok": True, "http_status": 200, "protocol": "hls",
            "content_type": "application/vnd.apple.mpegurl",
            "size_bytes": 5000, "error": ""}


def dead_404(url, timeout=None):
    return {"ok": False, "http_status": 404, "protocol": "",
            "content_type": "text/html", "size_bytes": 0, "error": ""}


def webview_page(url, timeout=None):
    return {"ok": True, "http_status": 200, "protocol": "html_page",
            "content_type": "text/html", "size_bytes": 100, "error": ""}


def empty_file(url, timeout=None):
    return {"ok": True, "http_status": 200, "protocol": "progressive",
            "content_type": "video/mp4", "size_bytes": 0, "error": ""}


def no_answer(url, timeout=None):
    return {"ok": False, "http_status": 0, "protocol": "",
            "content_type": "", "size_bytes": 0, "error": "transport"}


def raising_prober(url, timeout=None):
    raise RuntimeError("prober blew up")


class CountingProber:
    """A prober that records every call and answers from a fixed verdict."""

    def __init__(self, verdict):
        self.verdict = verdict
        self.calls = []

    def __call__(self, url, timeout=None):
        self.calls.append((url, timeout))
        if callable(self.verdict):
            return self.verdict(url, timeout=timeout)
        return dict(self.verdict)


class ResolverProbesFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp.name) / "wire.db"
        orig_db_path = fast_fetch.DB_PATH
        fast_fetch.DB_PATH = self.db_path
        self.addCleanup(setattr, fast_fetch, "DB_PATH", orig_db_path)
        fast_fetch.init_db(self.db_path)
        self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row
        resolved_cache.init_resolved_cache(self.conn)
        self.conn.execute(
            "INSERT INTO download_sources (id, release_id, service,"
            " source_url, final_url, playback_type, status, is_temporary)"
            " VALUES (?, 1, 'pixeldrain', ?, ?, 'direct', 'active', 0)",
            (LEDGER_ID, FILE_URL, FILE_URL))
        self.conn.commit()

    def tearDown(self):
        self.conn.close()
        self.tmp.cleanup()

    # ---- harness ------------------------------------------------------
    def bank(self, *, url=FILE_URL, mime="video/mp4", age=0,
             expires_in=86400.0, is_temporary=0):
        resolved_at = NOW - age
        resolved_cache.record_resolved_cache(
            self.conn, LEDGER_ID,
            {"url": url, "mime_type": mime, "service": "pixeldrain",
             "expires_in": expires_in, "is_temporary": is_temporary},
            now=resolved_at)
        self.conn.commit()
        return resolved_at

    def lookup(self, *, want="watch"):
        return resolved_cache.lookup_cache(self.conn, LEDGER_ID, want=want,
                                           now=NOW)

    def row(self):
        return self.conn.execute(
            "SELECT resolved_at, reverify_after, fail_count"
            " FROM resolved_cache WHERE link_id = ?",
            (LEDGER_ID,)).fetchone()


class RecheckBankedTests(ResolverProbesFixture):
    def test_fresh_row_serves_without_a_probe(self):
        """A row banked 60 s ago is still inside the 600 s fresh window:
        served at once, and the prober is never called."""
        self.bank(age=60)
        prober = CountingProber(ok_file)
        hit = self.lookup()
        self.assertIsNotNone(hit)
        check = serve_verify.recheck_banked(hit, conn=self.conn, now=NOW,
                                            prober=prober)
        self.assertTrue(check["serve"])
        self.assertEqual(check["reason"], "fresh")
        self.assertFalse(check["refreshed"])
        self.assertEqual(prober.calls, [])

    def test_boundary_600s_is_fresh_601s_is_probed(self):
        """The fresh window is exactly VERIFIED_FRESHNESS_S: 600 s old
        serves unprobed, 601 s old pays one probe."""
        self.bank(age=600)
        prober = CountingProber(ok_file)
        hit = self.lookup()
        check = serve_verify.recheck_banked(hit, conn=self.conn, now=NOW,
                                            prober=prober)
        self.assertTrue(check["serve"])
        self.assertEqual(prober.calls, [])
        self.bank(age=601)
        prober = CountingProber(ok_file)
        hit = self.lookup()
        check = serve_verify.recheck_banked(hit, conn=self.conn, now=NOW,
                                            prober=prober)
        self.assertTrue(check["serve"])
        self.assertEqual(len(prober.calls), 1)

    def test_aged_row_with_verifying_probe_serves_and_refreshes(self):
        """An hour-old row that still plays is served, and its clock moves
        to now — the next press inside the window is instant again."""
        old_resolved_at = self.bank(age=3600)
        old_reverify = self.row()["reverify_after"]
        hit = self.lookup()
        check = serve_verify.recheck_banked(hit, conn=self.conn, now=NOW,
                                            prober=ok_file)
        self.assertTrue(check["serve"])
        self.assertEqual(check["reason"], "reprobed")
        self.assertTrue(check["refreshed"])
        self.conn.commit()
        row = self.row()
        self.assertEqual(row["resolved_at"], NOW)
        self.assertGreater(row["resolved_at"], old_resolved_at)
        # The horizon is expiry-bound (the measured expiry caps it), so it
        # does not move — but the refresh must never shrink it, and the row
        # stays servable.
        self.assertGreaterEqual(row["reverify_after"], old_reverify)
        self.assertIsNotNone(self.lookup())

    def test_aged_row_with_404_is_refused_and_not_refreshed(self):
        """An hour-old row whose file is gone is NOT served, and its clock
        is untouched — the caller falls through to the live resolver."""
        old_resolved_at = self.bank(age=3600)
        hit = self.lookup()
        check = serve_verify.recheck_banked(hit, conn=self.conn, now=NOW,
                                            prober=dead_404)
        self.assertFalse(check["serve"])
        self.assertEqual(check["reason"], "http_404")
        self.assertFalse(check["refreshed"])
        self.assertEqual(self.row()["resolved_at"], old_resolved_at)

    def test_aged_row_with_no_answer_serves_but_is_not_refreshed(self):
        """A probe that gets no HTTP answer at all says nothing about the
        link: the row is served anyway (fail-open), but its clock does NOT
        move, so the next press re-probes."""
        old_resolved_at = self.bank(age=3600)
        hit = self.lookup()
        check = serve_verify.recheck_banked(hit, conn=self.conn, now=NOW,
                                            prober=no_answer)
        self.assertTrue(check["serve"])
        self.assertEqual(check["reason"], "transport")
        self.assertFalse(check["refreshed"])
        self.assertEqual(self.row()["resolved_at"], old_resolved_at)

    def test_aged_row_serving_a_page_is_refused(self):
        """A 200 that is a bridge/soft-404 page is not media: refused."""
        self.bank(age=3600)
        hit = self.lookup()
        check = serve_verify.recheck_banked(hit, conn=self.conn, now=NOW,
                                            prober=webview_page)
        self.assertFalse(check["serve"])
        self.assertEqual(check["reason"], "webview")

    def test_aged_row_with_empty_file_is_refused(self):
        """Content-Length 0 on a video URL is dead without reading a body."""
        self.bank(age=3600)
        hit = self.lookup()
        check = serve_verify.recheck_banked(hit, conn=self.conn, now=NOW,
                                            prober=empty_file)
        self.assertFalse(check["serve"])
        self.assertEqual(check["reason"], "empty_file")

    def test_playlist_is_refused_for_download_but_not_for_watch(self):
        """A download needs a single file: a verifying playlist probe still
        refuses the row for a download caller, while a watch caller serves
        it."""
        self.bank(url=PLAYLIST_URL,
                  mime="application/vnd.apple.mpegurl", age=3600)
        hit = self.lookup()
        self.assertEqual(hit["kind"], "playlist")
        check = serve_verify.recheck_banked(hit, want="download",
                                            conn=self.conn, now=NOW,
                                            prober=ok_playlist)
        self.assertFalse(check["serve"])
        self.assertEqual(check["reason"], "playlist_for_download")
        check = serve_verify.recheck_banked(hit, want="watch",
                                            conn=self.conn, now=NOW,
                                            prober=ok_playlist)
        self.assertTrue(check["serve"])

    def test_expired_url_is_refused_without_a_probe(self):
        """An explicit expiry in the URL that has passed is evidence
        without a probe: refused, and the prober is never called."""
        self.bank(url=EXPIRED_URL, age=3600)
        prober = CountingProber(ok_file)
        hit = self.lookup()
        check = serve_verify.recheck_banked(hit, conn=self.conn, now=NOW,
                                            prober=prober)
        self.assertFalse(check["serve"])
        self.assertEqual(check["reason"], "expired_url")
        self.assertEqual(prober.calls, [])

    def test_prober_fault_is_fail_open(self):
        """A prober that raises is 'no answer': serve, do not refresh."""
        old_resolved_at = self.bank(age=3600)
        hit = self.lookup()
        check = serve_verify.recheck_banked(hit, conn=self.conn, now=NOW,
                                            prober=raising_prober)
        self.assertTrue(check["serve"])
        self.assertEqual(check["reason"], "transport")
        self.assertFalse(check["refreshed"])
        self.assertEqual(self.row()["resolved_at"], old_resolved_at)

    def test_row_without_a_url_is_refused(self):
        """A hit with no URL cannot be served at all."""
        check = serve_verify.recheck_banked(
            {"link_id": LEDGER_ID, "url": "", "resolved_at": NOW - 3600,
             "expires_at": None, "category": "B", "kind": "file"},
            conn=self.conn, now=NOW, prober=ok_file)
        self.assertFalse(check["serve"])
        self.assertEqual(check["reason"], "no_url")

    def test_verified_row_serves_without_a_connection_but_is_not_refreshed(self):
        """With no connection the verdict is the same — only the refresh
        is skipped, because there is nowhere to write it."""
        self.bank(age=3600)
        hit = self.lookup()
        check = serve_verify.recheck_banked(hit, now=NOW, prober=ok_file)
        self.assertTrue(check["serve"])
        self.assertFalse(check["refreshed"])


class ServableBankedHitTests(ResolverProbesFixture):
    def test_fresh_row_is_returned_without_a_probe(self):
        """The route helper returns a fresh row untouched, probe-free."""
        self.bank(age=60)
        prober = CountingProber(ok_file)
        hit = viewer._servable_banked_hit(self.conn, LEDGER_ID,
                                           want="watch", now=NOW,
                                           prober=prober)
        self.assertIsNotNone(hit)
        self.assertEqual(hit["url"], FILE_URL)
        self.assertEqual(prober.calls, [])

    def test_aged_dead_row_reads_as_a_cache_miss(self):
        """An aged row whose file 404s reads as a miss (None), so the
        route falls through to the live resolver and its failover."""
        self.bank(age=3600)
        hit = viewer._servable_banked_hit(self.conn, LEDGER_ID,
                                           want="watch", now=NOW,
                                           prober=dead_404)
        self.assertIsNone(hit)

    def test_aged_live_row_is_returned_and_refreshed(self):
        """An aged row that still plays is returned, and its clock moves
        to now on the same connection."""
        self.bank(age=3600)
        hit = viewer._servable_banked_hit(self.conn, LEDGER_ID,
                                           want="watch", now=NOW,
                                           prober=ok_file)
        self.assertIsNotNone(hit)
        self.assertEqual(hit["url"], FILE_URL)
        self.conn.commit()
        self.assertEqual(self.row()["resolved_at"], NOW)

    def test_aged_playlist_row_is_a_miss_for_download(self):
        """A playlist row is never a download candidate: the lookup
        itself refuses it for want='download'."""
        self.bank(url=PLAYLIST_URL,
                  mime="application/vnd.apple.mpegurl", age=3600)
        hit = viewer._servable_banked_hit(self.conn, LEDGER_ID,
                                           want="download", now=NOW,
                                           prober=ok_playlist)
        self.assertIsNone(hit)

    def test_missing_row_is_none(self):
        """No banked row is still a miss, probe or no probe."""
        hit = viewer._servable_banked_hit(self.conn, LEDGER_ID,
                                           want="watch", now=NOW,
                                           prober=ok_file)
        self.assertIsNone(hit)


class BankLawTests(ResolverProbesFixture):
    def test_stale_envelope_is_never_banked(self):
        """The bank law, pinned again here: a v1 stale envelope (a URL we
        could NOT re-resolve) is never bankable, whoever the caller is."""
        stored = resolved_cache.record_resolved_cache(
            self.conn, LEDGER_ID,
            {"url": FILE_URL, "mime_type": "video/mp4",
             "service": "pixeldrain", "stale": True},
            now=NOW)
        self.assertIsNone(stored)
        self.assertIsNone(self.lookup())


if __name__ == "__main__":
    unittest.main(verbosity=2)
