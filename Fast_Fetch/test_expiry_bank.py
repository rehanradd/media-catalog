#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""API-03 — bank expiry at resolve time for every signed host.

`expires_at` is NULL on 99.97% of `download_sources` because only the
HubCloud path ever banked one. The fix derives the expiry from the
freshly minted final URL itself (R2 SigV4, s+e, absolute e/exp) or
banks the measured window for a token-bound origin with no parsable
expiry (Google usercontent, 900 s), in `resolve_live_source` and in
the HubCloud one-round sibling bank — and the already-existing serve
guard (`expires_in <= 0` -> re-resolve) then fires on those rows.

Stdlib only. No network: every HTTP hop is a stub, every DB is a temp
file. Run with the repo convention::

    python3 -S Fast_Fetch/test_expiry_bank.py
    python3 -S -m unittest test_expiry_bank
"""

from __future__ import annotations

import os
import sys
import tempfile
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "importers"))

import current_hosts  # noqa: E402
import expiry_bank  # noqa: E402
import fast_fetch  # noqa: E402

NOW = int(time.time())


def _r2_url(expires_in: int, *, issued_ago: int = 0) -> str:
    issued = datetime.fromtimestamp(NOW - issued_ago, tz=timezone.utc)
    date = issued.strftime("%Y%m%dT%H%M%SZ")
    return ("https://abc123.r2.cloudflarestorage.com/hex/file.mkv"
            f"?X-Amz-Date={date}&X-Amz-Expires={expires_in}"
            "&X-Amz-Signature=00")


GOOGLE_URL = ("https://video-downloads.googleusercontent.com/"
              "ADGPM2abc123/file.mp4?x=1")
LH3_URL = "https://lh3.googleusercontent.com/pw/AMW1xYz1234567890=m18"
MULTICLOUD_URL = ("https://dr1.multidownload.click/d/ab12cd34"
                  "?eid=0123456789abcdef&token=0123456789abcdef01234567"
                  f"&exp={NOW + 14400}&action=watch")
PIXEL_URL = "https://pixeldrain.com/api/file/GjBUdSeg"


# ---------------------------------------------------------------------------
# Pure parsing
# ---------------------------------------------------------------------------

class ParseSignedExpiryTests(unittest.TestCase):
    def test_r2_fsl_five_hours(self):
        expires_at, basis = expiry_bank.parse_signed_expiry(
            _r2_url(18000), now=NOW)
        self.assertEqual(expires_at, NOW + 18000)
        self.assertEqual(basis, "r2_presigned")

    def test_r2_old_eight_hour_path(self):
        expires_at, basis = expiry_bank.parse_signed_expiry(
            _r2_url(28800), now=NOW)
        self.assertEqual(expires_at, NOW + 28800)
        self.assertEqual(basis, "r2_presigned")

    def test_r2_uses_utc_not_local(self):
        # The date is minted at `now`; the lifetime must read exactly,
        # whatever TZ this process runs under (the mktime trap).
        expires_at, _ = expiry_bank.parse_signed_expiry(
            _r2_url(18000), now=NOW)
        self.assertEqual(expires_at, NOW + 18000)

    def test_r2_missing_signature_params(self):
        url = ("https://abc123.r2.cloudflarestorage.com/hex/file.mkv"
               "?X-Amz-Date=20261124T050000Z")
        self.assertEqual(expiry_bank.parse_signed_expiry(url, now=NOW),
                         (None, None))

    def test_r2_bad_date(self):
        url = ("https://abc123.r2.cloudflarestorage.com/hex/file.mkv"
               "?X-Amz-Date=not-a-date&X-Amz-Expires=18000")
        self.assertEqual(expiry_bank.parse_signed_expiry(url, now=NOW),
                         (None, None))

    def test_s_plus_e_vidmoly(self):
        url = (f"https://prx-am-s-1.vmpx.online/hls2/01/03082/x_n/master.m3u8"
               f"?t=tok&s={NOW}&e=43200&v=1")
        expires_at, basis = expiry_bank.parse_signed_expiry(url, now=NOW)
        self.assertEqual(expires_at, NOW + 43200)
        self.assertEqual(basis, "s_plus_e")

    def test_s_plus_e_vidhide_36h(self):
        url = (f"https://x.cdn-centaurus.com/hls2/01/1/x_n/master.m3u8"
               f"?t=tok&s={NOW}&e=129600")
        expires_at, _ = expiry_bank.parse_signed_expiry(url, now=NOW)
        self.assertEqual(expires_at, NOW + 129600)

    def test_s_without_e_is_not_an_expiry(self):
        url = f"https://x.example/f.mp4?s={NOW}"
        self.assertEqual(expiry_bank.parse_signed_expiry(url, now=NOW),
                         (None, None))

    def test_absolute_e_mixdrop(self):
        url = f"https://mxcontent.net/v2/abc.mp4?e={NOW + 21600}"
        expires_at, basis = expiry_bank.parse_signed_expiry(url, now=NOW)
        self.assertEqual(expires_at, NOW + 21600)
        self.assertEqual(basis, "absolute_epoch")

    def test_absolute_exp_greenmotors(self):
        expires_at, basis = expiry_bank.parse_signed_expiry(
            MULTICLOUD_URL, now=NOW)
        self.assertEqual(expires_at, NOW + 14400)
        self.assertEqual(basis, "absolute_epoch")

    def test_absolute_expires_param(self):
        url = f"https://x.example/f.mp4?expires={NOW + 3600}"
        expires_at, _ = expiry_bank.parse_signed_expiry(url, now=NOW)
        self.assertEqual(expires_at, NOW + 3600)

    def test_bare_lifetime_e_is_not_read(self):
        # A bare `e=14400` with no `s` is a lifetime, not an epoch —
        # that shape belongs to API-10's duration_param_expiry
        # (voe/stream unwrappers). This module must not guess it.
        url = ("https://x.cloudwindow-route.com/engine/hls2/a/master.m3u8"
               "?t=tok&s=tok&e=14400")
        self.assertEqual(expiry_bank.parse_signed_expiry(url, now=NOW),
                         (None, None))

    def test_stillborn_expiry_still_parses(self):
        # A token minted already dead is banked as past, so the serve
        # guard (expires_in <= 0 -> re-resolve) fires instead of NULL
        # pretending the row is fresh.
        expires_at, basis = expiry_bank.parse_signed_expiry(
            _r2_url(18000, issued_ago=200000), now=NOW)
        self.assertIsNotNone(expires_at)
        self.assertLess(expires_at, NOW)
        self.assertEqual(basis, "r2_presigned")

    def test_absurd_epoch_rejected(self):
        url = "https://x.example/f.mp4?e=99999999999"
        self.assertEqual(expiry_bank.parse_signed_expiry(url, now=NOW),
                         (None, None))

    def test_non_numeric_rejected(self):
        url = "https://x.example/f.mp4?e=soon&exp=later"
        self.assertEqual(expiry_bank.parse_signed_expiry(url, now=NOW),
                         (None, None))

    def test_non_http_and_empty(self):
        for bad in ("", None, "ftp://x.example/f.mp4", "not a url"):
            self.assertEqual(expiry_bank.parse_signed_expiry(bad, now=NOW),
                             (None, None))

    def test_permanent_url_has_no_expiry(self):
        self.assertEqual(expiry_bank.parse_signed_expiry(PIXEL_URL, now=NOW),
                         (None, None))

    def test_themoviebox_t_is_not_read(self):
        # themoviebox `t` is the ISSUE time, not an expiry — and a
        # 29 h-old URL still plays, so no window may be derived here.
        url = (f"https://bcdnxw.hakunaymatata.com/resource/abcdef"
               f"?sign=0123456789abcdef0123456789abcdef&t={NOW - 29 * 3600}")
        self.assertEqual(expiry_bank.parse_signed_expiry(url, now=NOW),
                         (None, None))


class MeasuredWindowTests(unittest.TestCase):
    def test_google_video_downloads(self):
        seconds, basis = expiry_bank.conservative_window_seconds(GOOGLE_URL)
        self.assertEqual(seconds, 900)
        self.assertEqual(basis, "measured_window:googleusercontent")

    def test_google_lh3_pw(self):
        seconds, _ = expiry_bank.conservative_window_seconds(LH3_URL)
        self.assertEqual(seconds, 900)

    def test_google_drive_usercontent_excluded(self):
        # The file id never expires; only the confirm step is
        # per-session. A 900 s window here would be a false expiry.
        url = ("https://drive.usercontent.google.com/download"
               "?id=0123456789abcdefghijklmnopqrstuvw&export=download")
        self.assertEqual(
            expiry_bank.conservative_window_seconds(url), (None, None))

    def test_lh3_non_pw_path_excluded(self):
        url = "https://lh3.googleusercontent.com/a-/photo.jpg"
        self.assertEqual(
            expiry_bank.conservative_window_seconds(url), (None, None))

    def test_zipdisk_host_excluded(self):
        url = ("https://storage.googleapis.com/"
               "photos-web-downloads-anonymous/abc123/file.zip")
        self.assertEqual(
            expiry_bank.conservative_window_seconds(url), (None, None))

    def test_other_token_hosts_have_no_window(self):
        # No measured death, no parsable expiry: no number is banked
        # rather than a made-up one.
        for url in (
            "https://download1326.mediafire.com/abc/file.mkv",
            "https://cloud.uploadflix.net:8080/d/abc/file.mkv",
            "https://gfs123.userstorage.mega.co.nz/dl/abc",
            "https://cold-eu-par-1.gofile.io/download/abc/file.mkv",
        ):
            self.assertEqual(
                expiry_bank.conservative_window_seconds(url), (None, None),
                url)


class DeriveExpiryTests(unittest.TestCase):
    def test_parsed_beats_window(self):
        # An R2 URL on a Google-shaped host would take the parse, not
        # the window — the parse is the measured truth.
        url = _r2_url(18000)
        derived = expiry_bank.derive_expiry(url, now=NOW)
        self.assertEqual(derived["observed_via"], "signed_params")
        self.assertEqual(derived["ttl_seconds"], 18000)

    def test_google_url_derives_window(self):
        derived = expiry_bank.derive_expiry(GOOGLE_URL, now=NOW)
        self.assertEqual(derived["expires_at"], NOW + 900)
        self.assertEqual(derived["ttl_seconds"], 900)
        self.assertEqual(derived["observed_via"], "measured_window")

    def test_greenmotors_derives_parsed(self):
        derived = expiry_bank.derive_expiry(MULTICLOUD_URL, now=NOW)
        self.assertEqual(derived["expires_at"], NOW + 14400)
        self.assertEqual(derived["observed_via"], "signed_params")

    def test_permanent_derives_nothing(self):
        self.assertIsNone(expiry_bank.derive_expiry(PIXEL_URL, now=NOW))

    def test_stillborn_derives_past(self):
        derived = expiry_bank.derive_expiry(
            _r2_url(18000, issued_ago=200000), now=NOW)
        self.assertIsNotNone(derived)
        self.assertLess(derived["expires_at"], NOW)
        self.assertLess(derived["ttl_seconds"], 0)


# ---------------------------------------------------------------------------
# Resolve-time banking (temp DB, stubbed network)
# ---------------------------------------------------------------------------

def _no_directive(*args, **kwargs):
    return None, {"observed": False, "reason": "no_directive"}


class ResolveBankingTests(unittest.TestCase):
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
        # passes, so the legacy branches run exactly as live. The
        # header probe declares nothing for these hosts, which is the
        # honest shape: signed CDNs carry no Cache-Control/Expires.
        self.stage2_probe_patch = patch.object(
            fast_fetch, "_stage2_probe",
            return_value={"ok": True, "kind": "file", "mime": "video/mp4",
                          "size": None, "resumable": True, "renditions": [],
                          "failure": None})
        self.stage2_probe_patch.start()
        self.stage1_backend_patch = patch.object(
            current_hosts, "classify_backend", return_value="unknown")
        self.stage1_backend_patch.start()
        self.ttl_patch = patch.object(
            fast_fetch, "probe_url_ttl", side_effect=_no_directive)
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
    def _media(service, source_url, final_url, is_temporary=1):
        return {
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
                    "source_name": "Example",
                    "service": service,
                    "resource_id": "site_a-id",
                    "source_url": source_url,
                    "final_url": final_url,
                    "is_temporary": is_temporary,
                }],
            }],
        }

    def _row(self, source_id):
        with fast_fetch.get_db() as conn:
            return dict(conn.execute(
                "SELECT * FROM download_sources WHERE id = ?",
                (source_id,)).fetchone())

    def _seed(self, service, source_url, final_url, is_temporary=1):
        fast_fetch.save_scraped_data(
            self._media(service, source_url, final_url, is_temporary))
        with fast_fetch.get_db() as conn:
            row = conn.execute(
                "SELECT id, release_id FROM download_sources LIMIT 1"
            ).fetchone()
        return row["id"], row["release_id"]

    def test_gdflix_google_url_banks_900s_window(self):
        """A fresh Google usercontent URL banks now+900, not NULL."""
        source_id, _ = self._seed(
            "gdflix", "https://new10.gdflix.io/file/abc123",
            "https://new10.gdflix.io/file/abc123")
        before = int(time.time())
        with patch.object(fast_fetch, "unwrap_gdflix",
                          return_value=(GOOGLE_URL, "abc123", 1)):
            res = fast_fetch.resolve_live_source(source_id)
        after = int(time.time())
        self.assertTrue(res["success"], res)
        self.assertTrue(res["refreshed"], res)
        self.assertEqual(res["url"], GOOGLE_URL)
        row = self._row(source_id)
        self.assertIsNotNone(row["expires_at"])
        self.assertGreaterEqual(row["expires_at"], before + 900)
        self.assertLessEqual(row["expires_at"], after + 900)
        self.assertEqual(row["ttl_source"], "measured_window")
        self.assertAlmostEqual(res["expires_in"], 900, delta=5)

    def test_greenmotors_exp_banks_parsed_4h(self):
        """The multicloudlinks exp epoch banks exactly, via the URL."""
        source_id, _ = self._seed(
            "greenmotors", "https://greenmotors.cc/?id=abc123",
            "https://greenmotors.cc/?id=abc123")
        with patch.object(fast_fetch, "unwrap_greenmotors",
                          return_value=[{
                              "source_name": "MultiCloud [Direct]",
                              "service": "multicloud",
                              "resource_id": "abc",
                              "source_url": "https://new.multicloudlinks.com/player.php/?v=abc",
                              "final_url": MULTICLOUD_URL,
                              "is_temporary": 1,
                              "expires_at": NOW + 14400,
                          }]):
            res = fast_fetch.resolve_live_source(source_id)
        self.assertTrue(res["success"], res)
        row = self._row(source_id)
        self.assertEqual(row["expires_at"], NOW + 14400)
        self.assertEqual(row["ttl_source"], "signed_params")
        self.assertAlmostEqual(res["expires_in"], 14400, delta=5)

    def test_hubcdn_google_url_banks_900s_window(self):
        """HubCDN's rotating Google twin banks 900 s; the R2 choice
        (not taken here) stays permanent and untouched."""
        source_id, _ = self._seed(
            "hubcdn", "https://hubcdn.io/cloud/abc123",
            "https://hubcdn.io/cloud/abc123")
        before = int(time.time())
        with patch.object(fast_fetch, "unwrap_hubcdn",
                          return_value={"ok": True, "r2_url": None,
                                        "google_url": GOOGLE_URL}):
            res = fast_fetch.resolve_live_source(source_id)
        after = int(time.time())
        self.assertTrue(res["success"], res)
        row = self._row(source_id)
        self.assertEqual(row["is_temporary"], 0)
        self.assertIsNotNone(row["expires_at"])
        self.assertGreaterEqual(row["expires_at"], before + 900)
        self.assertLessEqual(row["expires_at"], after + 900)
        self.assertEqual(row["ttl_source"], "measured_window")

    def test_pixeldrain_banks_nothing(self):
        """A permanent host derives no expiry: NULL stays NULL."""
        source_id, _ = self._seed(
            "pixeldrain", "https://pixeldrain.com/u/abc123", PIXEL_URL,
            is_temporary=0)
        with patch.object(fast_fetch, "unwrap_pixeldrain",
                          return_value=(PIXEL_URL, "abc123", 0)):
            res = fast_fetch.resolve_live_source(source_id)
        self.assertTrue(res["success"], res)
        row = self._row(source_id)
        self.assertIsNone(row["expires_at"])

    def test_past_expiry_re_resolves(self):
        """A row banked past its expiry is re-resolved, never served."""
        source_id, _ = self._seed(
            "gdflix", "https://new10.gdflix.io/file/abc123", GOOGLE_URL)
        with fast_fetch.get_db() as conn:
            conn.execute(
                "UPDATE download_sources SET expires_at = ?,"
                " ttl_source = 'measured_window' WHERE id = ?",
                (time.time() - 60, source_id))
        unwrap = patch.object(
            fast_fetch, "unwrap_gdflix",
            return_value=(GOOGLE_URL, "abc123", 1)).start()
        try:
            res = fast_fetch.resolve_live_source(source_id)
        finally:
            unwrap.stop()
        self.assertTrue(res["success"], res)
        self.assertTrue(res["refreshed"], res)
        unwrap.assert_called_once()
        row = self._row(source_id)
        self.assertGreater(row["expires_at"], time.time() + 800)

    def test_future_expiry_serves_cached(self):
        """A row inside its banked window is served cached, no unwrap."""
        source_id, _ = self._seed(
            "gdflix", "https://new10.gdflix.io/file/abc123", GOOGLE_URL)
        with fast_fetch.get_db() as conn:
            conn.execute(
                "UPDATE download_sources SET expires_at = ?,"
                " ttl_source = 'measured_window' WHERE id = ?",
                (time.time() + 600, source_id))
        unwrap = patch.object(
            fast_fetch, "unwrap_gdflix",
            return_value=(GOOGLE_URL, "abc123", 1)).start()
        try:
            res = fast_fetch.resolve_live_source(source_id)
        finally:
            unwrap.stop()
        self.assertTrue(res["success"], res)
        self.assertTrue(res["cached"], res)
        self.assertFalse(res["refreshed"], res)
        unwrap.assert_not_called()
        self.assertAlmostEqual(res["expires_in"], 600, delta=5)

    def test_hubcloud_sibling_10g_banks_window(self):
        """One gamerxyt page banks the 10Gbps sibling's Google URL
        with 900 s; the FSL sibling keeps its parsed R2 expiry and
        the PixelServer sibling stays NULL (permanent)."""
        drive_url = "https://hubcloud.ist/drive/abc123"
        media = self._media("hubcloud", drive_url, drive_url, 0)
        media["releases"][0]["sources"] = [
            {"source_name": "HubCloud Drive", "service": "hubcloud",
             "resource_id": "abc123", "source_url": drive_url,
             "final_url": drive_url, "is_temporary": 0},
            {"source_name": "HubCloud [FSL]", "service": "fsl_r2",
             "resource_id": "abc123", "source_url": drive_url,
             "final_url": _r2_url(28800, issued_ago=200000),
             "is_temporary": 1},
            {"source_name": "HubCloud [10Gbps]", "service": "hubcloud_10g",
             "resource_id": "abc123", "source_url": drive_url,
             "final_url": "https://gpdl.hubcloud.mov/?id=stale",
             "is_temporary": 0},
            {"source_name": "PixelDrain", "service": "pixeldrain",
             "resource_id": "abc123", "source_url": drive_url,
             "final_url": PIXEL_URL, "is_temporary": 0},
        ]
        fast_fetch.save_scraped_data(media)
        with fast_fetch.get_db() as conn:
            rows = {r["service"]: dict(r) for r in conn.execute(
                "SELECT * FROM download_sources").fetchall()}
            release_id = conn.execute(
                "SELECT id FROM releases LIMIT 1").fetchone()[0]
        before = int(time.time())
        candidates = [
            {"service": "fsl_r2", "final_url": _r2_url(18000),
             "is_temporary": 1, "expires_at": NOW + 18000},
            {"service": "hubcloud_10g", "final_url": GOOGLE_URL,
             "is_temporary": 1, "expires_at": None},
            {"service": "pixeldrain", "final_url": PIXEL_URL,
             "is_temporary": 0, "expires_at": None},
        ]
        banked = fast_fetch._bank_hubcloud_siblings(
            release_id=release_id, pressed_id=rows["hubcloud"]["id"],
            candidates=candidates)
        after = int(time.time())
        self.assertEqual(banked, 3)
        fsl = self._row(rows["fsl_r2"]["id"])
        self.assertEqual(fsl["expires_at"], NOW + 18000)
        self.assertEqual(fsl["ttl_source"], "signed_params")
        ten_g = self._row(rows["hubcloud_10g"]["id"])
        self.assertGreaterEqual(ten_g["expires_at"], before + 900)
        self.assertLessEqual(ten_g["expires_at"], after + 900)
        self.assertEqual(ten_g["ttl_source"], "measured_window")
        pixel = self._row(rows["pixeldrain"]["id"])
        self.assertIsNone(pixel["expires_at"])


if __name__ == "__main__":
    unittest.main()
