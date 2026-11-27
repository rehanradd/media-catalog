#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline tests for the two bank defects in the resolver latency audit
(2026-11-23, ~/workspace/resolver-latency-20261123.md, "Banked press
times"), both measured live against the production API:

(a) A dead cached FSL URL returns 404 instead of triggering a
    refresh/re-resolve. fsl_r2 release 3122427: press 1 = 404 in
    1,262 ms, press 2 = 404 in 25 ms — the dead banked URL was served
    fast instead of re-resolving. The drive re-mints fine (proven live:
    unwrap_hubcloud on the release's drive URL returns a fresh R2 URL),
    so the 404 was a code-path failure, not a dead drive: a fresh
    negative-cache record made resolve_live_source skip the copy's own
    unwrap entirely for 12 h, even though the record most often names
    an expired token and the re-mint is the cure. The fix: a negative
    hit on a TEMPORARY copy with a bridge to mint from (a recognised
    bridge URL, or a source_url distinct from the stored final_url)
    still runs the fresh unwrap — only the cached probes stay skipped.
    A permanent copy, and a temporary copy with no bridge (the stored
    URL is the token itself), keep the skip.

(b) mxdrop never serves from the bank. Release 2221661: press 1 = 302
    in 8,404 ms, press 2 = 302 in 3,755 ms — the 2nd press still
    re-resolved/re-probed instead of serving the banked row. Two
    causes, both fixed here:
    (b1) a stillborn banked row: the resolve banked an expires_at 3.5
    days BEFORE its resolved_at (the pkembed row's stale ledger value
    rode through because the bare TTL probe 403'd on mxcontent and no
    fresh window was measured), so _compute_reverify_after floored the
    horizon to resolved_at + 120 s and every press past 2 min
    re-resolved. The fix: a successful resolve — which just proved the
    URL plays NOW — never banks an already-expired expiry; the stale
    value is cleared and the answer falls back to the documented 6 h
    temporary-link ceiling.
    (b2) a bare re-probe that can never verify the row: the mxcontent
    CDN 403s the bare re-probe, so an aging mxdrop row fails the
    serve-time re-probe on every press and falls through to a full cold
    resolve. The fix: a 403 on a signed URL whose own window is still
    open is a header refusal, not a dead file — the row is served (and
    not refreshed, so the next press re-probes). A 403 with no window,
    or a window already closed, is still refused.

No network, no production DB: a temp SQLite DB, a scripted HTTP layer
keyed on exact URLs, and stub probers. The resolve tests reuse the
StreamWish dispatch harness (test_streamwish_dispatch.py): the sbbrisk
packer branch is the offline re-mint vehicle — an embed page that
unpacks to a fresh HLS master.
"""

from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault("RADD_SKIP_LIVE_ENRICH", "1")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "importers"))

import current_hosts  # noqa: E402
import fast_fetch  # noqa: E402
import host_resolvers as hr  # noqa: E402
import resolved_cache  # noqa: E402
import serve_verify  # noqa: E402


def setUpModule():
    """Branch 2: ``record_resolved_cache`` now probes the URL at bank
    time. This suite banks fixture URLs offline, so the probe is faked
    as passing — the refusal itself is proven by
    ``test_resolved_cache.TestBankRefusesNonMedia``."""
    global _probe_patcher
    _probe_patcher = patch.object(
        resolved_cache, "_media_probe", return_value=(True, None))
    _probe_patcher.start()


def tearDownModule():
    _probe_patcher.stop()


NOW = 1_800_000_000.0

# ---------------------------------------------------------------------------
# Shared canned pages (StreamWish dispatch harness shape)
# ---------------------------------------------------------------------------

_PACKER_FN = (
    "eval(function(p,a,c,k,e,d){e=function(c){return c};"
    "if(!''.replace(/^/,String)){while(c--)if(k[c])p=p.replace("
    "new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),k[c]);return p}"
    "('0',36,1,'%s'.split('|')))"
)


def _packer_page(snippet):
    return ("<html><head><title>embed</title></head><body>"
            "<script type='text/javascript'>"
            + (_PACKER_FN % snippet) +
            "</script></body></html>").encode("utf-8")


MASTER_BODY = b"#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nv.m3u8\n"
MASTER_HEADERS = {"content-type": "application/vnd.apple.mpegurl",
                  "content-range": "bytes 0-511/12345"}

# The master the packer page mints, with no expiry in its query: the
# verdict's expires_in is None, so the resolve's expiry block has
# nothing fresh to record.
MASTER_URL = "https://cdn.example/hls2/01/1/master.m3u8"
# The same master with a future epoch expiry (`e` is an absolute
# timestamp for expiry_from_url): the verdict carries a real window.
MASTER_URL_FUTURE = ("https://cdn.example/hls2/01/1/master.m3u8"
                     "?e=1900000000")
# The stale stored address the copy carried before the re-mint: a direct
# file, not a bridge, so it can never be re-minted itself.
OLD_URL = "https://cdn.example/hls2/01/1/old.mp4"


def _scripted_http(script):
    """Exact-URL fake for host_resolvers.http_request (see
    test_streamwish_dispatch.py): any URL outside the script raises, so
    a stray fetch fails the test instead of silently 404ing."""
    calls = []

    def fake(url, headers=None, timeout=None, method="GET", follow=True,
             data=None, **_kw):
        calls.append((url, dict(headers or {}), method))
        if url in script:
            status, body, extra = script[url]
            return status, body, extra, url
        raise AssertionError("unexpected HTTP fetch: %s" % url)

    fake.calls = calls
    return fake


# ---------------------------------------------------------------------------
# Part 1 — resolve_live_source: defect (a) re-mint + defect (b1) stale expiry
# ---------------------------------------------------------------------------

class BankDefectsResolveTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test.db"
        self.db_path_patch = patch.object(fast_fetch, "DB_PATH", self.db_path)
        self.db_path_patch.start()
        self.cinemeta_patch = patch.object(
            fast_fetch, "fetch_cinemeta_metadata", return_value=None)
        self.cinemeta_patch.start()
        # Stage-2 gate: fail every probe except an .m3u8 master, so a
        # genuinely resolved master still passes the gate.
        self.stage2_probe_patch = patch.object(
            fast_fetch, "_stage2_probe", side_effect=self._probe)
        self.stage2_probe_patch.start()
        self.stage1_backend_patch = patch.object(
            current_hosts, "classify_backend", return_value="unknown")
        self.stage1_backend_patch.start()
        # The host declares no freshness window; the lifetime, if any,
        # is signed into the query the packer handed us.
        self.ttl_patch = patch.object(
            fast_fetch, "probe_url_ttl",
            return_value=(None, {"observed": False, "reason": "no_directive"}))
        self.ttl_patch.start()
        fast_fetch.init_db()

    def tearDown(self):
        self.ttl_patch.stop()
        self.stage1_backend_patch.stop()
        self.stage2_probe_patch.stop()
        self.cinemeta_patch.stop()
        self.db_path_patch.stop()
        self.temp_dir.cleanup()

    @staticmethod
    def _probe(url, *args, **kwargs):
        if url.split("?")[0].endswith(".m3u8"):
            return {"ok": True, "kind": "playlist",
                    "mime": "application/vnd.apple.mpegurl", "size": None,
                    "resumable": False, "renditions": [], "failure": None}
        return {"ok": False, "kind": None, "mime": None, "size": None,
                "resumable": False, "renditions": [], "failure": "probe_error"}

    def _save_source(self, service, source_url, final_url=None,
                     is_temporary=0):
        media = {
            "url": "https://site-a.example/example-film/",
            "slug": "example-film",
            "title": "Example Film (2024)",
            "clean_title": "Example Film",
            "year": 2024,
            "content_type": "movie",
            "seasons": None,
            "poster_url": None,
            "backdrop_urls": None,
            "imdb_rating": None,
            "genres": None,
            "synopsis": None,
            "is_18_plus": 0,
            "is_animated": 0,
            "source_site": "site_a",
            "industry": "Hollywood",
            "country": "USA",
            "language": "English",
            "canonical_key": fast_fetch.generate_canonical_key(
                "Example Film", 2024),
            "tmdb_id": None,
            "imdb_id": None,
            "releases": [{
                "release_title": "Example Film 1080p",
                "quality": "1080p",
                "codec": "x264",
                "size": "1GB",
                "languages": "English",
                "season": None,
                "episode": None,
                "intermediate_url": "https://site-a.example/release",
                "sources": [{
                    "source_name": "Example CDN",
                    "service": service,
                    "resource_id": "site_a-id",
                    "source_url": source_url,
                    "final_url": final_url if final_url is not None
                    else source_url,
                    "is_temporary": is_temporary,
                }],
            }],
        }
        fast_fetch.save_scraped_data(media)
        with fast_fetch.get_db() as conn:
            return conn.execute(
                "SELECT id FROM download_sources LIMIT 1").fetchone()["id"]

    def _set_row(self, source_id, *, last_failed_at=None,
                 failure_class=None, failure_resolver_v=None,
                 expires_at=None):
        with fast_fetch.get_db() as conn:
            conn.execute(
                "UPDATE download_sources SET last_failed_at = ?,"
                " failure_class = ?, failure_resolver_v = ?,"
                " expires_at = ? WHERE id = ?",
                (last_failed_at, failure_class, failure_resolver_v,
                 expires_at, source_id))

    def _row(self, source_id):
        with fast_fetch.get_db() as conn:
            return conn.execute(
                "SELECT final_url, is_temporary, status, last_failed_at,"
                " failure_class, expires_at FROM download_sources"
                " WHERE id = ?", (source_id,)).fetchone()

    def _packer_script(self, embed, master_url):
        snippet = '"hls2":"%s"' % master_url
        return _scripted_http({
            embed: (200, _packer_page(snippet), {}),
            master_url: (206, MASTER_BODY, MASTER_HEADERS),
        })

    # -- defect (a): a negative hit re-mints a temporary copy -----------

    def test_negative_hit_remints_temporary_copy_with_distinct_bridge(self):
        """The audit's case: a temporary copy with a fresh negative
        record and a bridge distinct from its stored URL still runs the
        fresh unwrap — the re-mint — instead of skipping to a 404."""
        embed = "https://sbbrisk.com/e/testid123"
        fake = self._packer_script(embed, MASTER_URL)
        source_id = self._save_source("sbbrisk", embed, final_url=OLD_URL,
                                      is_temporary=1)
        self._set_row(source_id, last_failed_at=time.time() - 60,
                      failure_class="unwrap_failed",
                      failure_resolver_v=fast_fetch.RESOLVER_VERSION)
        with patch.object(hr, "http_request", fake), \
             patch.object(hr, "unwrap_streamwish",
                          side_effect=AssertionError(
                              "sbbrisk must not reach unwrap_streamwish")):
            res = fast_fetch.resolve_live_source(source_id)
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], MASTER_URL, res)
        self.assertFalse(res["cached"], res)
        fetched = [url for url, _h, _m in fake.calls]
        self.assertIn(embed, fetched, fetched)
        row = self._row(source_id)
        self.assertEqual(row["final_url"], MASTER_URL, row)
        self.assertIsNone(row["last_failed_at"], row)
        self.assertIsNone(row["failure_class"], row)

    def test_negative_hit_still_skips_permanent_copy(self):
        """The negative cache keeps its power for a permanent copy: the
        same fresh record refuses the unwrap, the embed is never
        fetched, and the answer is no_playable_source."""
        embed = "https://sbbrisk.com/e/testid123"
        fake = self._packer_script(embed, MASTER_URL)
        source_id = self._save_source("sbbrisk", embed, final_url=OLD_URL,
                                      is_temporary=0)
        self._set_row(source_id, last_failed_at=time.time() - 60,
                      failure_class="unwrap_failed",
                      failure_resolver_v=fast_fetch.RESOLVER_VERSION)
        with patch.object(hr, "http_request", fake):
            res = fast_fetch.resolve_live_source(source_id)
        self.assertFalse(res["success"], res)
        fetched = [url for url, _h, _m in fake.calls]
        self.assertNotIn(embed, fetched, fetched)

    def test_negative_hit_still_skips_temporary_copy_without_bridge(self):
        """A temporary copy whose stored URL IS the bridge (source_url
        == final_url, and the URL is not a recognised bridge) has
        nothing to re-mint from: the skip holds, the embed is never
        fetched, and the answer is no_playable_source."""
        embed = "https://sbbrisk.com/e/testid123"
        fake = self._packer_script(embed, MASTER_URL)
        source_id = self._save_source("sbbrisk", embed, is_temporary=1)
        self._set_row(source_id, last_failed_at=time.time() - 60,
                      failure_class="unwrap_failed",
                      failure_resolver_v=fast_fetch.RESOLVER_VERSION)
        with patch.object(hr, "http_request", fake):
            res = fast_fetch.resolve_live_source(source_id)
        self.assertFalse(res["success"], res)
        fetched = [url for url, _h, _m in fake.calls]
        self.assertNotIn(embed, fetched, fetched)

    # -- defect (b1): a stale ledger expiry is cleared, not banked ------

    def test_stale_ledger_expiry_is_cleared_on_fresh_resolve(self):
        """The audit's stillborn row: the ledger carries an expires_at
        already in the past, the fresh mint measures no window (the
        verdict's expires_in is None and the bare TTL probe is
        unreachable — mxcontent 403s it), so the stale value would ride
        into the answer. A successful resolve proves the URL plays NOW,
        so the stale value is cleared and the answer reports no expiry
        — the bank falls back to the 6 h temporary ceiling, not the
        120 s reverify floor."""
        embed = "https://sbbrisk.com/e/testid123"
        fake = self._packer_script(embed, MASTER_URL)
        source_id = self._save_source("sbbrisk", embed, final_url=OLD_URL,
                                      is_temporary=1)
        self._set_row(source_id, expires_at=time.time() - 86400)
        with patch.object(hr, "http_request", fake), \
             patch.object(fast_fetch, "probe_url_ttl",
                          return_value=(None, {"observed": False,
                                               "reason": "unreachable"})):
            res = fast_fetch.resolve_live_source(source_id)
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], MASTER_URL, res)
        self.assertIsNone(res["expires_at"], res)
        self.assertIsNone(res["expires_in"], res)
        row = self._row(source_id)
        self.assertEqual(row["final_url"], MASTER_URL, row)
        self.assertIsNone(row["expires_at"], row)

    def test_fresh_signed_expiry_is_kept(self):
        """Control: when the fresh mint DOES carry a future window
        (signed into the master's `e`), it is recorded and kept — the
        guard only clears an expiry already in the past."""
        embed = "https://sbbrisk.com/e/testid123"
        fake = self._packer_script(embed, MASTER_URL_FUTURE)
        source_id = self._save_source("sbbrisk", embed, final_url=OLD_URL,
                                      is_temporary=1)
        self._set_row(source_id, expires_at=time.time() - 86400)
        with patch.object(hr, "http_request", fake), \
             patch.object(fast_fetch, "probe_url_ttl",
                          return_value=(None, {"observed": False,
                                               "reason": "unreachable"})):
            res = fast_fetch.resolve_live_source(source_id)
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], MASTER_URL_FUTURE, res)
        self.assertAlmostEqual(res["expires_at"], 1900000000, delta=2,
                               msg=res)
        row = self._row(source_id)
        self.assertAlmostEqual(row["expires_at"], 1900000000, delta=2,
                               msg=dict(row))


# ---------------------------------------------------------------------------
# Part 2 — serve_verify.recheck_banked: defect (b2) 403 with an open window
# ---------------------------------------------------------------------------

FILE_URL = "https://pixeldrain.com/api/file/s2yMcLkb"
LEDGER_ID = 7


def dead_403(url, timeout=None):
    return {"ok": False, "http_status": 403, "protocol": "",
            "content_type": "text/html", "size_bytes": 0, "error": ""}


def dead_404(url, timeout=None):
    return {"ok": False, "http_status": 404, "protocol": "",
            "content_type": "text/html", "size_bytes": 0, "error": ""}


def ok_file(url, timeout=None):
    return {"ok": True, "http_status": 200, "protocol": "progressive",
            "content_type": "video/mp4", "size_bytes": 12345, "error": ""}


class BankDefectsRecheckTests(unittest.TestCase):
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
            " VALUES (?, 1, 'pixeldrain', ?, ?, 'direct', 'active', 1)",
            (LEDGER_ID, FILE_URL, FILE_URL))
        self.conn.commit()

    def tearDown(self):
        self.conn.close()
        self.tmp.cleanup()

    def bank(self, *, age=3600, expires_in=21600.0):
        resolved_cache.record_resolved_cache(
            self.conn, LEDGER_ID,
            {"url": FILE_URL, "mime_type": "video/mp4",
             "service": "pixeldrain", "expires_in": expires_in,
             "is_temporary": 1},
            now=NOW - age)
        self.conn.commit()

    def _hit(self, *, resolved_at, expires_at):
        return {"link_id": LEDGER_ID, "url": FILE_URL,
                "resolved_at": resolved_at, "expires_at": expires_at,
                "category": "B", "kind": "file"}

    def test_aged_row_with_403_and_open_window_serves(self):
        """An hour-old row whose signature window is still open (5 h
        left) and whose re-probe 403s is served: the 403 is a header
        refusal from a Referer-gated CDN, not a dead file. Not
        refreshed, so the next press re-probes."""
        old_resolved_at = NOW - 3600
        self.bank(age=3600, expires_in=21600.0)
        hit = resolved_cache.lookup_cache(self.conn, LEDGER_ID,
                                          want="watch", now=NOW)
        self.assertIsNotNone(hit)
        check = serve_verify.recheck_banked(hit, conn=self.conn, now=NOW,
                                            prober=dead_403)
        self.assertTrue(check["serve"], check)
        self.assertEqual(check["reason"], "transport", check)
        self.assertFalse(check["refreshed"], check)
        row = self.conn.execute(
            "SELECT resolved_at FROM resolved_cache WHERE link_id = ?",
            (LEDGER_ID,)).fetchone()
        self.assertEqual(row["resolved_at"], old_resolved_at)

    def test_aged_row_with_403_and_closed_window_is_refused(self):
        """A 403 on a row whose window already closed is still refused:
        the signature may genuinely be expired, so the caller falls
        through to the live resolver."""
        hit = self._hit(resolved_at=NOW - 3600, expires_at=NOW - 60)
        check = serve_verify.recheck_banked(hit, conn=self.conn, now=NOW,
                                            prober=dead_403)
        self.assertFalse(check["serve"], check)
        self.assertEqual(check["reason"], "http_403", check)
        self.assertFalse(check["refreshed"], check)

    def test_aged_row_with_403_and_no_window_is_refused(self):
        """A 403 on a row with no measured window is refused: without a
        window there is nothing proving the signature is still valid."""
        hit = self._hit(resolved_at=NOW - 3600, expires_at=None)
        check = serve_verify.recheck_banked(hit, conn=self.conn, now=NOW,
                                            prober=dead_403)
        self.assertFalse(check["serve"], check)
        self.assertEqual(check["reason"], "http_403", check)

    def test_aged_row_with_404_and_open_window_is_still_refused(self):
        """Control: a 404 is a dead file whatever the window says — the
        403 exception is only for a header refusal, never for a
        missing file."""
        self.bank(age=3600, expires_in=21600.0)
        hit = resolved_cache.lookup_cache(self.conn, LEDGER_ID,
                                          want="watch", now=NOW)
        self.assertIsNotNone(hit)
        check = serve_verify.recheck_banked(hit, conn=self.conn, now=NOW,
                                            prober=dead_404)
        self.assertFalse(check["serve"], check)
        self.assertEqual(check["reason"], "http_404", check)


if __name__ == "__main__":
    unittest.main(verbosity=2)
