#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HubCloud one-round refresh (2026-11-23) — offline tests.

The gamerxyt page returns ALL buttons in one response, so one drive
GET + one gamerxyt GET must bank every fresh link at once, with each
signed URL's true expiry — instead of today's behaviour (only the
pressed row's service is banked, then every sibling button re-resolves
the same drive, and the probe_url_ttl HEAD round trip usually clears
the expiry to NULL).

Stdlib only. No network: every HTTP hop is a canned fetch, every DB
is a temp file. Run with the repo convention::

    python3 -S Fast_Fetch/test_hubcloud_oneround.py
    python3 -S -m unittest test_hubcloud_oneround
"""

from __future__ import annotations

import calendar
import http.client
import os
import sys
import tempfile
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import Mock, patch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "importers"))

import current_hosts  # noqa: E402
import fast_fetch  # noqa: E402
import host_resolvers as hr  # noqa: E402

# ---------------------------------------------------------------------------
# Canned pages (shaped on test_host_resolvers' HC_* fixtures)
# ---------------------------------------------------------------------------

DRIVE_ID = "rn_37lnqqexentk"
DRIVE_URL = f"https://hubcloud.pw/drive/{DRIVE_ID}"
GX_URL = ("https://gamerxyt.com/hubcloud.php?host=hubcloud"
          f"&id={DRIVE_ID}&token=" + "A" * 60)
PIXEL_HREF = "https://pixeldrain.dev/u/negn6f"
PIXEL_URL = "https://pixeldrain.dev/api/file/negn6f?download"
#: What fast_fetch.unwrap_pixeldrain returns for the same href (the
#: pixeldrain-branch control test presses that path, not the verdict's).
PIXEL_DIRECT_URL = "https://pixeldrain.com/api/file/negn6f"
GBPS_URL = "https://pixel.hubcloud.pw/?id=deadbeef::cafef00d"
ZIPDISK_URL = ("https://storage.googleapis.com/"
               "photos-web-downloads-anonymous/20261122T154917.734Z/"
               "4133399871716478688/50e02071-f7e0-4664-88e6-fa415ff087f5/1/"
               "8db86a6b-996c-440f-a652-d9ffec7e2b8a")

# Fixed-date R2 URL (2026-11-23T02:58:09Z + 28800 s = 2026-11-23T10:58:09Z
# = epoch 1795431489): the parse test pins this literal, so it never
# depends on the clock.
FIXED_R2_URL = ("https://db076825ef2334beaf4c317aed13a40c"
                ".r2.cloudflarestorage.com/hex/0123456789abcdef0123456789abcdef"
                "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                "&X-Amz-Credential=FAKECRED%2F20261123%2Fauto%2Fs3%2Faws4_request"
                "&X-Amz-Date=20261123T025809Z&X-Amz-Expires=28800"
                "&X-Amz-SignedHeaders=host&X-Amz-Signature=" + "0" * 64)
FIXED_R2_EXPIRES_AT = 1795431489


def _r2_url(key, filename, lifetime):
    """A presigned R2 URL issued NOW, so expires_in ~= lifetime."""
    now = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    return (f"https://db076825ef2334beaf4c317aed13a40c"
            f".r2.cloudflarestorage.com/hub/{key}"
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
            "&X-Amz-Credential=FAKECRED%2F20261123%2Fauto%2Fs3%2Faws4_request"
            f"&X-Amz-Date={now}&X-Amz-Expires={lifetime}"
            "&X-Amz-SignedHeaders=host&X-Amz-Signature=" + "0" * 64)


FSLV2_URL = _r2_url("1c8e2ebb17c55ccc855414dc97ed1e80",
                    "Ramayana.2026.720p.mkv", 18000)
FSL_URL = _r2_url("b26be7ab2ca58cec9372503da5e7eb9a",
                  "79cc21f44d8f883216a4e509f53a3a7e", 28800)

MKV_HEAD = b"\x1a\x45\xdf\xa3" + b"\x00" * 508
R2_HEADERS = {"content-type": "video/x-matroska",
              "content-range": "bytes 0-511/1705388883"}

DRIVE_PAGE = f"""<!DOCTYPE html><html><head><title>Maari.2015.1080p - HubCloud</title></head><body>
<div class="vd" style="display:none">
<a id="download" href="{GX_URL}">Download</a>
</div>
</body></html>"""

GX_PAGE = f"""<!DOCTYPE html><html><head><title>HubCloud</title></head><body>
<h2><div class="main" id="section2"><div class="card h6">
<div class="card-header text-white bg-primary mb-3"> Maari.2015.1080p.WEB.DL.HIN.TAM.2.0.ESub.x264.mkv </div>
<div class="card-body"><ul class="list-group">
<li class="list-group-item">File Size<i id="size">1.59 GB</i></li>
<li class="list-group-item">File Type<i>video/x-matroska</i></li>
</ul></div></div></div></h2>
<a href="{FSLV2_URL}" id="s3" target="_blank" class="btn btn-success btn-lg h6">Download [FSLv2 Server]</a>
<a href="{PIXEL_HREF}" id="pxl-1" target="_blank" class="btn btn-success btn-lg h6">Download [PixelServer : 2]</a>
<a href="{FSL_URL}" id="fsl" target="_blank" class="btn btn-success btn-lg h6">Download [FSL Server]</a>
<a href="{GBPS_URL}" target="_blank" class="btn btn-danger btn-lg h6">Download [Server : 10Gbps]</a>
<a href="{ZIPDISK_URL}" target="_blank" class="btn btn-danger btn-lg h6">Download [ZipDisk Server]</a>
</body></html>"""

#: A gamerxyt page with no buttons at all: the stale-token shape (the
#: expiry alert renders instead of buttons).
GX_EMPTY_PAGE = """<!DOCTYPE html><html><head><title>HubCloud</title></head><body>
<h2>Token Expired!</h2><p>Token Link has been expired.</p>
</body></html>"""


def scripted_fetch(script):
    """A `fetch(url, headers, timeout, method, follow, data)` stand-in.

    `script` maps a URL substring to a (status, body, headers) triple.
    Records every call on ``fetch.calls`` as (url, headers, method).
    """
    calls = []

    def fetch(url, headers=None, timeout=None, method="GET", follow=True,
              data=None):
        calls.append((url, headers or {}, method))
        for key, value in script.items():
            if key in url:
                status, body, extra = value
                merged = {"content-type": "text/html"}
                merged.update(extra or {})
                return status, body, merged, url
        return 404, b"", {"content-type": "text/html"}, url

    fetch.calls = calls  # type: ignore[attr-defined]
    return fetch


def hubcloud_fetch(gx_page=GX_PAGE, fslv2_probe=(206, MKV_HEAD, R2_HEADERS)):
    """The full canned HubCloud chain: drive page, gamerxyt page, probes."""
    return scripted_fetch({
        f"/drive/{DRIVE_ID}": (200, DRIVE_PAGE, {}),
        "gamerxyt.com/hubcloud.php": (200, gx_page, {}),
        "1c8e2ebb17c55ccc855414dc97ed1e80": fslv2_probe,
        "b26be7ab2ca58cec9372503da5e7eb9a": (206, MKV_HEAD, R2_HEADERS),
        "pixeldrain.dev/api/file/negn6f": (206, MKV_HEAD, R2_HEADERS),
        "pixel.hubcloud.pw": (200, b"<html>landing</html>",
                              {"content-type": "text/html"}),
    })


# ---------------------------------------------------------------------------
# Fake http.client connections (keep-alive pool tests)
# ---------------------------------------------------------------------------

class _FakeResp:
    def __init__(self, status, body, headers=None):
        self.status = status
        self._body = body
        self._headers = headers or {}

    def read(self, amt=None):
        if amt is None:
            out, self._body = self._body, b""
            return out
        out, self._body = self._body[:amt], self._body[amt:]
        return out

    def getheaders(self):
        return list(self._headers.items())


class _FakeConn:
    """An http.client connection stand-in that answers from a script."""

    def __init__(self, script):
        self.script = script  # {(host, target): (status, body, headers)}
        self.requests = []
        self.closed = False
        self.fail_next = None  # exception to raise on the next request

    def request(self, method, target, body=None, headers=None):
        self.requests.append((method, target))
        if self.fail_next is not None:
            exc, self.fail_next = self.fail_next, None
            raise exc

    def getresponse(self):
        method, target = self.requests[-1]
        status, body, headers = self.script.get(
            ("h", target), (200, b"ok", {}))
        return _FakeResp(status, body, headers)

    def close(self):
        self.closed = True


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class HubcloudOneroundTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test.db"
        self.db_path_patch = patch.object(fast_fetch, "DB_PATH", self.db_path)
        self.db_path_patch.start()
        self.cinemeta_patch = patch.object(
            fast_fetch, "fetch_cinemeta_metadata", return_value=None)
        self.cinemeta_patch.start()
        # Offline like test_database.DatabaseSaveTests: Stage-1 never
        # runs (no fixture backend is proven) and the Stage-2 gate
        # passes, so the legacy HubCloud branch runs exactly as live.
        self.stage2_probe_patch = patch.object(
            fast_fetch, "_stage2_probe",
            return_value={"ok": True, "kind": "file", "mime": "video/mp4",
                          "size": None, "resumable": True, "renditions": [],
                          "failure": None})
        self.stage2_probe_patch.start()
        self.stage1_backend_patch = patch.object(
            current_hosts, "classify_backend", return_value="unknown")
        self.stage1_backend_patch.start()
        fast_fetch.init_db()
        self._seed_release()

    def tearDown(self):
        self.stage1_backend_patch.stop()
        self.stage2_probe_patch.stop()
        self.cinemeta_patch.stop()
        self.db_path_patch.stop()
        self.temp_dir.cleanup()

    def _seed_release(self):
        """One release with the four rows one drive fans out to at ingest."""
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
                "sources": [
                    {"source_name": "HubCloud Drive", "service": "hubcloud",
                     "resource_id": DRIVE_ID, "source_url": DRIVE_URL,
                     "final_url": DRIVE_URL, "is_temporary": 0},
                    {"source_name": "HubCloud [FSL Cloudflare R2]",
                     "service": "fsl_r2", "resource_id": DRIVE_ID,
                     "source_url": DRIVE_URL,
                     "final_url": "https://old.r2.cloudflarestorage.com/hex/stale"
                                  "?X-Amz-Date=20261101T000000Z&X-Amz-Expires=28800"
                                  "&X-Amz-Signature=00",
                     "is_temporary": 1},
                    {"source_name": "HubCloud [10Gbps Fast Server]",
                     "service": "hubcloud_10g", "resource_id": DRIVE_ID,
                     "source_url": DRIVE_URL,
                     "final_url": "https://gpdl.hubcloud.mov/?id=stale::stale",
                     "is_temporary": 0},
                    {"source_name": "PixelDrain [Direct CDN]",
                     "service": "pixeldrain", "resource_id": "negn6f",
                     "source_url": PIXEL_HREF, "final_url": PIXEL_HREF,
                     "is_temporary": 0},
                ],
            }],
        }
        fast_fetch.save_scraped_data(media)
        with fast_fetch.get_db() as conn:
            self.release_id = conn.execute(
                "SELECT id FROM releases LIMIT 1").fetchone()[0]
            self.rows = {
                r["service"]: dict(r) for r in conn.execute(
                    "SELECT * FROM download_sources").fetchall()}
        self.assertEqual(
            sorted(self.rows), ["fsl_r2", "hubcloud", "hubcloud_10g",
                                "pixeldrain"])

    def _row(self, service):
        with fast_fetch.get_db() as conn:
            return dict(conn.execute(
                "SELECT * FROM download_sources WHERE service = ?",
                (service,)).fetchone())

    def _resolve_with(self, fetch, source_id):
        """resolve_live_source with the canned HubCloud fetch + a TTL recorder."""
        ttl_calls = []
        probe = Mock(side_effect=lambda url, **kw: ttl_calls.append(url) or (
            None, {"observed": False, "reason": "no_directive"}))
        with patch.object(hr, "keepalive_fetch", fetch), \
                patch.object(fast_fetch, "probe_url_ttl", probe):
            res = fast_fetch.resolve_live_source(source_id)
        return res, fetch, ttl_calls

    # (i) one gamerxyt page banks ALL sibling rows ---------------------

    def test_one_gamerxyt_fetch_banks_all_siblings(self):
        """Pressing the FSL row banks the PixelDrain sibling from the
        SAME gamerxyt fetch — one gamerxyt GET, two rows fresh — and
        the drive row is untouched. The 10Gbps button on the same page
        is NOT banked (dropped 2026-11-24): its anchor is dead by
        construction, so the 10Gbps row keeps its stale URL."""
        fetch = hubcloud_fetch()
        res, _, ttl_calls = self._resolve_with(
            fetch, self.rows["fsl_r2"]["id"])
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], FSLV2_URL, res)
        self.assertTrue(res["refreshed"], res)
        # The winner's signed 5 h window rides the answer.
        self.assertGreater(res["expires_in"], 17000)
        self.assertLessEqual(res["expires_in"], 18000)
        # ONE gamerxyt fetch for the whole release.
        gx_calls = [c for c in fetch.calls if "gamerxyt.com" in c[0]]
        self.assertEqual(len(gx_calls), 1, fetch.calls)
        self.assertEqual(len(fetch.calls), 3, fetch.calls)  # drive + gx + probe
        # Pressed row: fresh R2 URL + true expiry banked.
        pressed = self._row("fsl_r2")
        self.assertEqual(pressed["final_url"], FSLV2_URL)
        self.assertEqual(pressed["status"], "active")
        self.assertEqual(pressed["ttl_source"], "signed_params")
        self.assertAlmostEqual(pressed["expires_at"],
                               time.time() + 18000, delta=120)
        # Siblings: fresh URLs from the same page, no re-resolve.
        ten_g = self._row("hubcloud_10g")
        self.assertEqual(ten_g["final_url"], self.rows["hubcloud_10g"]["final_url"])
        self.assertIsNone(ten_g["expires_at"])
        pixel = self._row("pixeldrain")
        self.assertEqual(pixel["final_url"], PIXEL_URL)
        self.assertEqual(pixel["status"], "active")
        self.assertIsNone(pixel["expires_at"])
        # The drive row is not a sibling: its final_url IS the drive page.
        drive = self._row("hubcloud")
        self.assertEqual(drive["final_url"], DRIVE_URL)
        # No rows were invented: banking UPDATEs, never INSERTs.
        with fast_fetch.get_db() as conn:
            count = conn.execute(
                "SELECT COUNT(*) FROM download_sources").fetchone()[0]
        self.assertEqual(count, 4)
        # (iv) the probe_url_ttl HEAD step never ran on this path.
        self.assertEqual(ttl_calls, [])

    def test_pressing_10gbps_row_banks_fsl_sibling_with_winner(self):
        """Pressing the 10Gbps row still resolves to the probed winner
        (FSLv2) and banks it onto the FSL sibling: FSLv2 + FSL both map
        to the one fsl_r2 row, and the winner wins over priority order."""
        fetch = hubcloud_fetch()
        res, _, ttl_calls = self._resolve_with(
            fetch, self.rows["hubcloud_10g"]["id"])
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], FSLV2_URL, res)
        self.assertEqual(self._row("fsl_r2")["final_url"], FSLV2_URL)
        self.assertEqual(self._row("fsl_r2")["status"], "active")
        self.assertAlmostEqual(self._row("fsl_r2")["expires_at"],
                               time.time() + 18000, delta=120)
        self.assertEqual(self._row("pixeldrain")["final_url"], PIXEL_URL)
        self.assertEqual(ttl_calls, [])

    def test_zipdisk_button_is_not_banked(self):
        """The canned page carries a ZipDisk button and the release has
        no zipdisk row: banking must not invent one (ZipDisk is a zip
        archive, not a stream, and the resolver never banks it)."""
        fetch = hubcloud_fetch()
        res, _, _ = self._resolve_with(fetch, self.rows["fsl_r2"]["id"])
        self.assertTrue(res["success"], res)
        with fast_fetch.get_db() as conn:
            services = [r[0] for r in conn.execute(
                "SELECT service FROM download_sources").fetchall()]
        self.assertNotIn("hubcloud_zipdisk", services)

    def test_verdict_sources_carry_kind_and_expiry(self):
        """The one-round data contract: every candidate publishes its
        dispatch kind and its own presigned expiry (None where the URL
        carries no signed lifetime)."""
        fetch = hubcloud_fetch()
        verdict = hr.unwrap_hubcloud(DRIVE_URL, fetch=fetch)
        self.assertEqual(verdict["status"], "active")
        kinds = [(s["kind"], s["label"]) for s in verdict["sources"]]
        self.assertEqual(kinds, [("fslv2", "FSLv2"), ("fsl", "FSL"),
                                 ("pixel", "PixelServer"),
                                 ("zipdisk", "ZipDisk")])
        by_kind = {s["kind"]: s for s in verdict["sources"]}
        self.assertAlmostEqual(by_kind["fslv2"]["expires_at"],
                               time.time() + 18000, delta=120)
        self.assertAlmostEqual(by_kind["fsl"]["expires_at"],
                               time.time() + 28800, delta=120)
        self.assertIsNone(by_kind["pixel"]["expires_at"])
        self.assertIsNone(by_kind["zipdisk"]["expires_at"])
        self.assertNotIn("10gbps", by_kind)

    # (ii) X-Amz-Expires parses to the correct expires_at --------------

    def test_r2_expiry_parses_with_timegm(self):
        """X-Amz-Date (UTC) + X-Amz-Expires (lifetime) -> the exact
        epoch: 20261123T025809Z + 28800 s = 2026-11-23T10:58:09Z."""
        self.assertEqual(fast_fetch._r2_presigned_expiry(FIXED_R2_URL),
                         FIXED_R2_EXPIRES_AT)
        self.assertEqual(
            fast_fetch._r2_presigned_expiry(FIXED_R2_URL),
            hr.r2_presigned_expiry(FIXED_R2_URL)[0])
        self.assertEqual(
            FIXED_R2_EXPIRES_AT,
            int(datetime(2026, 11, 23, 10, 58, 9,
                         tzinfo=timezone.utc).timestamp()))
        # No signed query, no expiry — never a made-up window.
        self.assertIsNone(fast_fetch._r2_presigned_expiry(
            "https://example.com/f.mkv"))
        self.assertIsNone(fast_fetch._r2_presigned_expiry(
            "https://x.r2.cloudflarestorage.com/hub/f?X-Amz-Expires=28800"))
        self.assertIsNone(fast_fetch._r2_presigned_expiry(
            "https://x.r2.cloudflarestorage.com/hub/f?X-Amz-Date=not-a-date"
            "&X-Amz-Expires=28800"))

    def test_r2_expiry_regression_mktime_is_wrong_outside_utc(self):
        """Regression for the 12 s probe error: ``time.mktime`` reads
        the struct as LOCAL time, so under a non-UTC TZ it shifts the
        answer by the UTC offset. The parser must return the UTC-correct
        epoch whatever the process TZ is."""
        old_tz = os.environ.get("TZ")
        try:
            os.environ["TZ"] = "Asia/Karachi"  # UTC+5, no DST
            time.tzset()
            buggy = int(time.mktime(time.strptime(
                "20261123T025809Z", "%Y%m%dT%H%M%SZ")))
            issue = calendar.timegm(time.strptime(
                "20261123T025809Z", "%Y%m%dT%H%M%SZ"))
            self.assertNotEqual(buggy, FIXED_R2_EXPIRES_AT)
            # mktime read the issue instant as local (UTC+5) time, so
            # it sits exactly one UTC offset below the true issue epoch.
            self.assertEqual(buggy, issue - 5 * 3600)
            self.assertEqual(fast_fetch._r2_presigned_expiry(FIXED_R2_URL),
                             FIXED_R2_EXPIRES_AT)
            self.assertEqual(hr.r2_presigned_expiry(FIXED_R2_URL)[0],
                             FIXED_R2_EXPIRES_AT)
        finally:
            if old_tz is None:
                os.environ.pop("TZ", None)
            else:
                os.environ["TZ"] = old_tz
            time.tzset()

    def test_normalize_prefers_probed_winner_over_priority(self):
        """FSLv2 + FSL both map to the one fsl_r2 row; the probed winner
        (prefer_url) wins, otherwise the first in priority order does."""
        cands = fast_fetch._normalize_hubcloud_candidates(
            [{"kind": "fslv2", "url": FSLV2_URL, "expires_at": 111},
             {"kind": "fsl", "url": FSL_URL, "expires_at": 222},
             {"kind": "10gbps", "url": GBPS_URL, "expires_at": None},
             {"kind": "zipdisk", "url": ZIPDISK_URL, "expires_at": None}],
            prefer_url=FSL_URL)
        by_service = {c["service"]: c for c in cands}
        self.assertEqual(sorted(by_service), ["fsl_r2"])
        self.assertEqual(by_service["fsl_r2"]["final_url"], FSL_URL)
        self.assertEqual(by_service["fsl_r2"]["expires_at"], 222)
        self.assertEqual(by_service["fsl_r2"]["is_temporary"], 1)
        # The 10gbps kind maps to no sibling service since the drop:
        # it is never banked, even when the page still publishes it.

    # (iii) stale-token retry happens exactly once ----------------------

    def test_drive_unwrapper_retries_stale_token_once_then_fails(self):
        """Empty gamerxyt page twice: the drive page is fetched twice
        (once per token), the gamerxyt page twice, then the honest
        fallback row — never a third attempt, never a fake link."""
        calls = {"drive": 0, "gx": 0}

        def fake_get(url, **kwargs):
            if "gamerxyt.com" in url:
                calls["gx"] += 1
                return 200, url, GX_EMPTY_PAGE
            calls["drive"] += 1
            return 200, url, DRIVE_PAGE

        with patch.object(fast_fetch, "http_get", side_effect=fake_get):
            out = fast_fetch.unwrap_hubcloud_drive(DRIVE_URL)
        self.assertEqual(calls, {"drive": 2, "gx": 2}, calls)
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0]["service"], "hubcloud")
        self.assertEqual(out[0]["final_url"], GX_URL)

    def test_drive_unwrapper_recovers_when_retry_mints_fresh_token(self):
        """Empty gamerxyt page once, then buttons: the retry recovers
        and the items carry their true expiry."""
        seen = {"gx": 0}

        def fake_get(url, **kwargs):
            if "gamerxyt.com" in url:
                seen["gx"] += 1
                page = GX_EMPTY_PAGE if seen["gx"] == 1 else GX_PAGE
                return 200, url, page
            return 200, url, DRIVE_PAGE

        with patch.object(fast_fetch, "http_get", side_effect=fake_get):
            out = fast_fetch.unwrap_hubcloud_drive(DRIVE_URL)
        self.assertEqual(seen["gx"], 2)
        services = [o["service"] for o in out]
        self.assertEqual(services, ["fsl_r2", "pixeldrain", "fsl_r2",
                                    "hubcloud_zipdisk"])
        fsl_items = [o for o in out if o["service"] == "fsl_r2"]
        self.assertEqual(len(fsl_items), 2)
        for item in fsl_items:
            self.assertIsNotNone(item["expires_at"])
        self.assertAlmostEqual(fsl_items[0]["expires_at"],
                               time.time() + 18000, delta=120)

    def test_drive_unwrapper_dead_drive_page_is_not_retried(self):
        """A drive page that does not answer is dead, not stale: exactly
        one http_get call (the dead-page test's pin, re-asserted here
        so the retry above cannot regress it)."""
        getter = Mock(return_value=(0, DRIVE_URL, ""))
        with patch.object(fast_fetch, "http_get", getter):
            out = fast_fetch.unwrap_hubcloud_drive(DRIVE_URL)
        self.assertEqual(getter.call_count, 1)
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0]["service"], "hubcloud")

    # (iv) probe_url_ttl is not called on the HubCloud path -------------

    def test_probe_url_ttl_still_runs_for_other_hosts(self):
        """Control: pressing the PixelDrain row (a non-HubCloud path)
        still calls probe_url_ttl — only the HubCloud path skips it."""
        ttl_calls = []
        probe = Mock(side_effect=lambda url, **kw: ttl_calls.append(url) or (
            None, {"observed": False, "reason": "no_directive"}))
        with patch.object(fast_fetch, "probe_url_ttl", probe):
            res = fast_fetch.resolve_live_source(
                self.rows["pixeldrain"]["id"])
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], PIXEL_DIRECT_URL, res)
        self.assertEqual(ttl_calls, [PIXEL_DIRECT_URL])
        # The pixeldrain press banked its own row and nothing else.
        self.assertEqual(self._row("pixeldrain")["final_url"],
                         PIXEL_DIRECT_URL)
        self.assertEqual(self._row("fsl_r2")["final_url"],
                         self.rows["fsl_r2"]["final_url"])


class KeepAliveFetchTests(unittest.TestCase):
    def _pool(self, script, max_connections=8):
        conns = []

        def factory(host, port, https, timeout):
            conn = _FakeConn(script)
            conn.host = host
            conns.append(conn)
            return conn

        pool = hr.KeepAliveFetch(connection_factory=factory,
                                 max_connections=max_connections)
        return pool, conns

    def test_reuses_one_connection_per_host(self):
        pool, conns = self._pool({("h", "/a"): (200, b"a", {}),
                                  ("h", "/b"): (200, b"b", {})})
        try:
            s1, b1, _, _ = pool.fetch("https://example.com/a")
            s2, b2, _, _ = pool.fetch("https://example.com/b")
            self.assertEqual((s1, b1), (200, b"a"))
            self.assertEqual((s2, b2), (200, b"b"))
            self.assertEqual(len(conns), 1, "second GET must reuse the conn")
            self.assertEqual(len(conns[0].requests), 2)
            self.assertEqual(pool.pooled_connections, 1)
        finally:
            pool.close()

    def test_pool_is_bounded_and_evicts_oldest(self):
        pool, conns = self._pool({("h", "/"): (200, b"ok", {})},
                                 max_connections=2)
        try:
            pool.fetch("https://one.example/")
            pool.fetch("https://two.example/")
            self.assertEqual(pool.pooled_connections, 2)
            pool.fetch("https://three.example/")
            self.assertEqual(pool.pooled_connections, 2)
            self.assertTrue(conns[0].closed, "oldest conn must be closed")
            self.assertEqual(len(conns), 3)
        finally:
            pool.close()

    def test_dead_connection_is_dropped_and_retried_once(self):
        pool, conns = self._pool({("h", "/a"): (200, b"ok", {})})
        try:
            pool.fetch("https://example.com/a")
            self.assertEqual(len(conns), 1)
            conns[0].fail_next = http.client.RemoteDisconnected(
                "server closed the idle connection")
            status, body, _, _ = pool.fetch("https://example.com/a")
            self.assertEqual((status, body), (200, b"ok"))
            self.assertEqual(len(conns), 2, "retry must use a fresh conn")
            self.assertTrue(conns[0].closed)
        finally:
            pool.close()

    def test_transport_failure_after_retries_is_status_zero(self):
        def failing_factory(host, port, https, timeout):
            conn = _FakeConn({})
            # Fail every attempt, not just the first.
            def always_fail(method, target, body=None, headers=None):
                raise TimeoutError("timed out")

            conn.request = always_fail
            return conn

        pool = hr.KeepAliveFetch(connection_factory=failing_factory)
        try:
            with patch.object(time, "sleep", lambda *a: None):
                status, body, headers, _ = pool.fetch(
                    "https://example.com/a", timeout=1)
            self.assertEqual(status, 0)
            self.assertIn("timed out", str(body))
            self.assertEqual(headers, {})
        finally:
            pool.close()

    def test_redirect_is_followed_and_final_url_returned(self):
        script = {
            ("h", "/old"): (302, b"", {"location": "/new"}),
            ("h", "/new"): (200, b"final", {}),
        }
        pool, _ = self._pool(script)
        try:
            status, body, _, final = pool.fetch("https://example.com/old")
            self.assertEqual((status, body), (200, b"final"))
            self.assertEqual(final, "https://example.com/new")
        finally:
            pool.close()

    def test_no_follow_returns_the_3xx(self):
        script = {("h", "/dl"): (302, b"", {"hx-redirect": "https://cdn/x"})}
        pool, _ = self._pool(script)
        try:
            status, body, headers, _ = pool.fetch(
                "https://example.com/dl", follow=False)
            self.assertEqual(status, 302)
            self.assertEqual(headers.get("hx-redirect"), "https://cdn/x")
        finally:
            pool.close()

    def test_over_cap_body_drops_the_connection(self):
        script = {("h", "/big"): (200, b"x" * (hr.PROBE_READ_BYTES + 100), {})}
        pool, conns = self._pool(script)
        try:
            status, body, _, _ = pool.fetch("https://example.com/big")
            self.assertEqual(status, 200)
            self.assertEqual(len(body), hr.PROBE_READ_BYTES)
            # The half-read connection is dropped, never pooled.
            self.assertEqual(pool.pooled_connections, 0)
            self.assertTrue(conns[0].closed)
        finally:
            pool.close()


if __name__ == "__main__":
    unittest.main()
