#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HubCloud dead PixelServer placeholder drop (2026-11-23) — offline tests.

The gamerxyt page prints the SAME ``pixeldrain.dev/u/negn6f`` on every
file, and it answers 404 on every Range check: a static placeholder,
not a per-file link. Both HubCloud paths must never emit or bank it:

* ``fast_fetch.unwrap_hubcloud_drive`` (ingest-time unwrapper) — the
  pixeldrain branch drops the placeholder and still banks a REAL
  per-file id, rewritten ``/u/<id>`` -> ``pixeldrain.com/api/file/<id>``
  by the existing ``unwrap_pixeldrain``.
* ``host_resolvers._hubcloud_candidates`` (live serve path) — the
  pixel candidate is dropped before it can reach the verdict's
  ``sources``, so the sibling-banking path can never bank it as the
  release's pixeldrain row.

What must NOT change: the generic ``unwrap_pixeldrain`` rewrite (a
pixeldrain link from any other host still rewrites, its tests pin
that), the FSL-first ordering (``_HUBCLOUD_PRIORITY`` puts FSLv2/FSL
first, and the ingest path sorts FSL first), the 8 h R2 expiry parse
(``r2_presigned_expiry``), and the dead-drive / empty-gamerxyt
fallback shapes.

Stdlib only. No network: every HTTP hop is a canned fetch. Run with
the repo convention::

    python3 -S Fast_Fetch/test_hubcloud_negn6f.py
    python3 -S -m unittest test_hubcloud_negn6f
"""

from __future__ import annotations

import json
import os
import socket
import sys
import time
import unittest
from pathlib import Path
from unittest.mock import patch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "importers"))

import fast_fetch  # noqa: E402
import host_resolvers as hr  # noqa: E402


class _NoLiveSocket:
    def __init__(self, *a, **k):
        raise AssertionError("test attempted a live socket")


socket.socket = _NoLiveSocket  # type: ignore[attr-defined]

# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

DRIVE_ID = "testdrive123"
DRIVE_URL = f"https://hubcloud.mov/drive/{DRIVE_ID}"
GX_URL = ("https://gamerxyt.com/hubcloud.php?host=hubcloud"
          f"&id={DRIVE_ID}&token=" + "B" * 60)

REAL_PIXEL_ID = "1KZUcLyh"  # seen live 2026-11-23 (Part 5 of the fast-path doc)
REAL_PIXEL_HREF = f"https://pixeldrain.dev/u/{REAL_PIXEL_ID}"
REAL_PIXEL_URL = f"https://pixeldrain.dev/api/file/{REAL_PIXEL_ID}?download"
DEAD_PIXEL_HREF = "https://pixeldrain.dev/u/negn6f"
GBPS_URL = "https://pixel.hubcloud.mov/?id=deadbeef::cafef00d"


def _r2_url(key, lifetime):
    """A presigned R2 URL issued NOW, so expires_in ~= lifetime."""
    now = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    return (f"https://db076825ef2334beaf4c317aed13a40c"
            f".r2.cloudflarestorage.com/hex/{key}"
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
            "&X-Amz-Credential=FAKECRED%2F20261123%2Fauto%2Fs3%2Faws4_request"
            f"&X-Amz-Date={now}&X-Amz-Expires={lifetime}"
            "&X-Amz-SignedHeaders=host&X-Amz-Signature=" + "0" * 64)


FSLV2_URL = _r2_url("1c8e2ebb17c55ccc855414dc97ed1e80", 18000)
FSL_URL = _r2_url("b26be7ab2ca58cec9372503da5e7eb9a", 28800)

MKV_HEAD = b"\x1a\x45\xdf\xa3\x01\x00\x00\x00\x00\x00\x00\x1f\x42\x82\x45\x9c"
R2_HEADERS = {"content-type": "video/x-matroska",
              "content-range": "bytes 0-511/1705388883"}
NOT_ENTITLED = (b'<?xml version="1.0" encoding="UTF-8"?><Error>'
                b'<Code>NotEntitled</Code><Message>Please enable R2 through '
                b'the Cloudflare Dashboard.</Message></Error>')

DRIVE_PAGE = f"""<!DOCTYPE html><html><head><title>Film.2024.1080p - HubCloud</title></head><body>
<div class="vd" style="display:none">
<a id="download" href="{GX_URL}">Download</a>
</div>
</body></html>"""


def _gx_page(buttons):
    """A gamerxyt page carrying exactly ``buttons``: (href, id, text)."""
    rows = "".join(
        f'<a href="{href}" id="{bid}" class="btn btn-success btn-lg h6">'
        f"{text}</a>"
        for href, bid, text in buttons)
    return f"""<!DOCTYPE html><html><head><title>HubCloud</title></head><body>
<h2><div class="main" id="section2"><div class="card h6">
<div class="card-header text-white bg-primary mb-3"> Film.2024.1080p.WEB-DL.x264.mkv </div>
</div></div></h2>
{rows}
</body></html>"""


FSL_BUTTON = (FSL_URL, "fsl", "Download [FSL Server]")
FSLV2_BUTTON = (FSLV2_URL, "s3", "Download [FSLv2 Server]")
DEAD_PIXEL_BUTTON = (DEAD_PIXEL_HREF, "pxl-1", "Download [PixelServer : 2]")
REAL_PIXEL_BUTTON = (REAL_PIXEL_HREF, "pxl-1", "Download [PixelServer : 2]")
GBPS_BUTTON = (GBPS_URL, "", "Download [Server : 10Gbps]")

FULL_PAGE_DEAD_PIXEL = _gx_page([FSLV2_BUTTON, DEAD_PIXEL_BUTTON,
                                 FSL_BUTTON, GBPS_BUTTON])
FULL_PAGE_REAL_PIXEL = _gx_page([FSLV2_BUTTON, REAL_PIXEL_BUTTON,
                                 FSL_BUTTON, GBPS_BUTTON])
# PixelServer listed FIRST: FSL must still lead the returned list.
PIXEL_FIRST_PAGE = _gx_page([REAL_PIXEL_BUTTON, GBPS_BUTTON, FSL_BUTTON])


def _ff_http_get(gx_html, drive_status=200):
    """A fake ``fast_fetch.http_get`` serving canned drive/gamerxyt pages."""
    def fake(url, **kwargs):
        if "gamerxyt.com" in url:
            return 200, url, gx_html
        if "/drive/" in url:
            if drive_status != 200:
                return drive_status, url, ""
            return 200, url, DRIVE_PAGE
        return 404, url, ""
    return fake


def scripted_fetch(script):
    """A ``fetch(url, headers, timeout, method, follow, data)`` stand-in.

    ``script`` maps a URL substring to a (status, body, headers) triple.
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


def _hc_fetch(gx_page, fslv2_probe, fsl_probe, pixel_probe=None,
              gbps_probe=None):
    script = {
        f"/drive/{DRIVE_ID}": (200, DRIVE_PAGE, {}),
        "gamerxyt.com/hubcloud.php": (200, gx_page, {}),
        "1c8e2ebb17c55ccc855414dc97ed1e80": fslv2_probe,
        "b26be7ab2ca58cec9372503da5e7eb9a": fsl_probe,
    }
    if pixel_probe is not None:
        script[f"pixeldrain.dev/api/file/{REAL_PIXEL_ID}"] = pixel_probe
    if gbps_probe is not None:
        script["pixel.hubcloud.mov"] = gbps_probe
    return scripted_fetch(script)


# ---------------------------------------------------------------------------
# fast_fetch.unwrap_hubcloud_drive (ingest path)
# ---------------------------------------------------------------------------

class FastFetchNegn6fTests(unittest.TestCase):
    def test_dead_pixel_never_emitted(self):
        with patch.object(fast_fetch, "http_get",
                          side_effect=_ff_http_get(FULL_PAGE_DEAD_PIXEL)):
            results = fast_fetch.unwrap_hubcloud_drive(DRIVE_URL)
        self.assertNotIn("negn6f", json.dumps(results))
        services = [r["service"] for r in results]
        self.assertNotIn("pixeldrain", services)
        self.assertIn("fsl_r2", services)
        self.assertIn("hubcloud_10g", services)

    def test_real_pixel_id_banked_as_api_file(self):
        with patch.object(fast_fetch, "http_get",
                          side_effect=_ff_http_get(FULL_PAGE_REAL_PIXEL)):
            results = fast_fetch.unwrap_hubcloud_drive(DRIVE_URL)
        pixel = [r for r in results if r["service"] == "pixeldrain"]
        self.assertEqual(len(pixel), 1)
        self.assertEqual(pixel[0]["final_url"],
                         f"https://pixeldrain.com/api/file/{REAL_PIXEL_ID}")
        self.assertEqual(pixel[0]["resource_id"], REAL_PIXEL_ID)
        self.assertEqual(pixel[0]["source_url"], REAL_PIXEL_HREF)
        self.assertEqual(pixel[0]["is_temporary"], 0)

    def test_fsl_still_first_when_page_lists_pixel_first(self):
        with patch.object(fast_fetch, "http_get",
                          side_effect=_ff_http_get(PIXEL_FIRST_PAGE)):
            results = fast_fetch.unwrap_hubcloud_drive(DRIVE_URL)
        self.assertEqual(results[0]["service"], "fsl_r2")
        self.assertEqual([r["service"] for r in results],
                         ["fsl_r2", "pixeldrain", "hubcloud_10g"])

    def test_negn6f_only_pixel_page_has_no_pixeldrain_row(self):
        page = _gx_page([FSL_BUTTON, DEAD_PIXEL_BUTTON])
        with patch.object(fast_fetch, "http_get",
                          side_effect=_ff_http_get(page)):
            results = fast_fetch.unwrap_hubcloud_drive(DRIVE_URL)
        self.assertEqual([r["service"] for r in results], ["fsl_r2"])
        self.assertNotIn("negn6f", json.dumps(results))

    def test_negn6f_on_pixeldrain_com_host_also_dropped(self):
        page = _gx_page([FSL_BUTTON,
                         ("https://pixeldrain.com/u/negn6f", "pxl-1",
                          "Download [PixelServer : 2]")])
        with patch.object(fast_fetch, "http_get",
                          side_effect=_ff_http_get(page)):
            results = fast_fetch.unwrap_hubcloud_drive(DRIVE_URL)
        self.assertEqual([r["service"] for r in results], ["fsl_r2"])

    def test_negn6f_with_query_dropped(self):
        page = _gx_page([FSL_BUTTON,
                         (DEAD_PIXEL_HREF + "?x=1", "pxl-1",
                          "Download [PixelServer : 2]")])
        with patch.object(fast_fetch, "http_get",
                          side_effect=_ff_http_get(page)):
            results = fast_fetch.unwrap_hubcloud_drive(DRIVE_URL)
        self.assertEqual([r["service"] for r in results], ["fsl_r2"])

    def test_dead_drive_page_unchanged(self):
        with patch.object(fast_fetch, "http_get",
                          side_effect=_ff_http_get(FULL_PAGE_DEAD_PIXEL,
                                                   drive_status=404)):
            results = fast_fetch.unwrap_hubcloud_drive(DRIVE_URL)
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["service"], "hubcloud")
        self.assertEqual(results[0]["final_url"], DRIVE_URL)

    def test_empty_gamerxyt_page_unchanged(self):
        with patch.object(fast_fetch, "http_get",
                          side_effect=_ff_http_get("<html><body>no buttons"
                                                   "</body></html>")):
            results = fast_fetch.unwrap_hubcloud_drive(DRIVE_URL)
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["service"], "hubcloud")
        self.assertEqual(results[0]["final_url"], DRIVE_URL)

    def test_helper_matches_exact_id_only(self):
        is_dead = fast_fetch._is_dead_hubcloud_pixeldrain
        self.assertTrue(is_dead("https://pixeldrain.dev/u/negn6f"))
        self.assertTrue(is_dead("https://pixeldrain.com/u/negn6f"))
        self.assertTrue(is_dead(
            "https://pixeldrain.dev/api/file/negn6f?download"))
        self.assertFalse(is_dead(REAL_PIXEL_HREF))
        self.assertFalse(is_dead("https://pixeldrain.dev/u/negn6fX"))
        self.assertFalse(is_dead(""))
        self.assertEqual(
            fast_fetch._hubcloud_pixeldrain_file_id(REAL_PIXEL_HREF),
            REAL_PIXEL_ID)


# ---------------------------------------------------------------------------
# host_resolvers.unwrap_hubcloud (live serve path + sibling banking source)
# ---------------------------------------------------------------------------

class HostResolversNegn6fTests(unittest.TestCase):
    def test_candidates_drop_dead_pixel(self):
        fetch = scripted_fetch({})
        ordered, notes = hr._hubcloud_candidates(
            fetch, FULL_PAGE_DEAD_PIXEL, GX_URL)
        self.assertEqual([c["kind"] for c in ordered],
                         ["fslv2", "fsl", "10gbps"])
        self.assertNotIn("negn6f", json.dumps(ordered))
        self.assertIn("negn6f", " ".join(notes))

    def test_real_pixel_id_kept_as_candidate(self):
        fetch = scripted_fetch({})
        ordered, notes = hr._hubcloud_candidates(
            fetch, FULL_PAGE_REAL_PIXEL, GX_URL)
        self.assertEqual([c["kind"] for c in ordered],
                         ["fslv2", "fsl", "pixel", "10gbps"])
        pixel = [c for c in ordered if c["kind"] == "pixel"][0]
        self.assertEqual(pixel["url"], REAL_PIXEL_URL)

    def test_dead_pixel_then_real_pixel_still_banks_real_one(self):
        # The drop `continue`s before `found` is set, so a second,
        # real PixelServer button on the same page still lands.
        page = _gx_page([FSL_BUTTON, DEAD_PIXEL_BUTTON,
                         (REAL_PIXEL_HREF, "pxl-2",
                          "Download [PixelServer : 2]")])
        fetch = scripted_fetch({})
        ordered, _notes = hr._hubcloud_candidates(fetch, page, GX_URL)
        pixel = [c for c in ordered if c["kind"] == "pixel"]
        self.assertEqual(len(pixel), 1)
        self.assertEqual(pixel[0]["url"], REAL_PIXEL_URL)

    def test_both_r2_blocked_falls_back_to_real_pixel(self):
        # The fallback the old test pinned still works — for a REAL id.
        fetch = _hc_fetch(
            FULL_PAGE_REAL_PIXEL,
            (403, NOT_ENTITLED, {"content-type": "application/xml"}),
            (403, NOT_ENTITLED, {"content-type": "application/xml"}),
            pixel_probe=(206, MKV_HEAD, R2_HEADERS))
        verdict = hr.unwrap_hubcloud(DRIVE_URL, fetch=fetch)
        self.assertEqual(verdict["status"], "active")
        self.assertEqual(verdict["final_url"], REAL_PIXEL_URL)
        self.assertIn("PixelServer",
                      [s["label"] for s in verdict["sources"]])

    def test_both_r2_blocked_dead_pixel_is_unknown(self):
        fetch = _hc_fetch(
            FULL_PAGE_DEAD_PIXEL,
            (403, NOT_ENTITLED, {"content-type": "application/xml"}),
            (403, NOT_ENTITLED, {"content-type": "application/xml"}),
            pixel_probe=(206, MKV_HEAD, R2_HEADERS))
        verdict = hr.unwrap_hubcloud(DRIVE_URL, fetch=fetch)
        self.assertEqual(verdict["status"], "unknown")
        self.assertEqual(verdict["final_url"], FSLV2_URL)
        probed = [c[0] for c in fetch.calls]
        self.assertFalse(any("pixeldrain" in u for u in probed))
        self.assertIn("negn6f", json.dumps(verdict["notes"]))

    def test_fsl_first_and_r2_expiry_preserved(self):
        # FSL-first ordering and the 8 h R2 expiry parse are untouched
        # by the drop: the unverified verdict still leads with FSLv2
        # and still carries its signed lifetime.
        fetch = _hc_fetch(
            FULL_PAGE_DEAD_PIXEL,
            (206, MKV_HEAD, R2_HEADERS),
            (206, MKV_HEAD, R2_HEADERS))
        verdict = hr.unwrap_hubcloud(DRIVE_URL, fetch=fetch, verify=False)
        self.assertEqual(verdict["status"], "unverified")
        self.assertEqual(verdict["final_url"], FSLV2_URL)
        self.assertEqual(verdict["sources"][0]["label"], "FSLv2")
        self.assertGreater(verdict["expires_in"], 17000)
        self.assertLessEqual(verdict["expires_in"], 18000)
        self.assertNotIn("negn6f", json.dumps(verdict["sources"]))

    def test_helper_matches_exact_id_only(self):
        is_dead = hr._is_dead_hubcloud_pixeldrain
        self.assertTrue(is_dead("https://pixeldrain.dev/u/negn6f"))
        self.assertTrue(is_dead("https://pixeldrain.com/u/negn6f"))
        self.assertTrue(is_dead(
            "https://pixeldrain.dev/api/file/negn6f?download"))
        self.assertFalse(is_dead(REAL_PIXEL_HREF))
        self.assertFalse(is_dead("https://pixeldrain.dev/u/negn6fX"))
        self.assertFalse(is_dead(""))


if __name__ == "__main__":
    unittest.main(verbosity=2)
