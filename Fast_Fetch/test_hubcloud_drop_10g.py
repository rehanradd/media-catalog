#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Drop the HubCloud 10Gbps leg (2026-11-24) — offline tests.

The 10Gbps button's anchor (pixel.hubcloud.*) 302s to
rohitkiskk.workers.dev and ends at HTTP 400 "404 !" (banked anchors)
or at an HTML link-generator page (fresh-minted anchor) — verified
live from Oracle VM egress 2026-11-24, no-follow GETs, capped reads.
It never serves video bytes, so no unwrapper may emit it and no
sibling banking may refresh the 81,995 existing hubcloud_10g rows.

Pins, all offline (canned pages, no network, no production DB):
  * ``_parse_gamerxyt_buttons`` skips the 10Gbps button but still
    returns the FSL (fsl_r2, with true presigned expiry) and
    PixelDrain items from the same page;
  * ``unwrap_hubcloud_drive`` (mocked ``http_get``) returns
    fsl_r2 + pixeldrain (+ zipdisk), never hubcloud_10g;
  * the one-round variant (``host_resolvers.unwrap_hubcloud``) does
    the same: the 10Gbps button dispatches to no kind, is not a
    candidate, and appears in no verdict ``sources`` entry;
  * ``_normalize_hubcloud_candidates`` drops a kind-``10gbps`` entry
    and ``_HUBCLOUD_SIBLING_SERVICES`` no longer contains the leg;
  * ``is_bridge_url`` calls a pixel.hubcloud URL a bridge, so a
    stored 10Gbps anchor is refreshed, never served as a stream.

Stdlib only. Run with the repo convention::

    python3 -S Fast_Fetch/test_hubcloud_drop_10g.py
    python3 -S -m unittest test_hubcloud_drop_10g
"""

from __future__ import annotations

import os
import sys
import time
import unittest
from unittest.mock import patch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "importers"))

import fast_fetch  # noqa: E402
import host_resolvers as hr  # noqa: E402

DRIVE_ID = "rn_37lnqqexentk"
DRIVE_URL = f"https://hubcloud.pw/drive/{DRIVE_ID}"
GX_URL = ("https://gamerxyt.com/hubcloud.php?host=hubcloud"
          f"&id={DRIVE_ID}&token=" + "A" * 60)
PIXEL_HREF = "https://pixeldrain.dev/u/negn6f"
PIXEL_DIRECT_URL = "https://pixeldrain.com/api/file/negn6f"
GBPS_URL = "https://pixel.hubcloud.pw/?id=deadbeef::cafef00d"


def _r2_url(key, lifetime):
    """A presigned R2 URL issued NOW, so expires_in ~= lifetime."""
    now = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    return (f"https://db076825ef2334beaf4c317aed13a40c"
            f".r2.cloudflarestorage.com/hub/{key}"
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
            "&X-Amz-Credential=FAKECRED%2F20261123%2Fauto%2Fs3%2Faws4_request"
            f"&X-Amz-Date={now}&X-Amz-Expires={lifetime}"
            "&X-Amz-SignedHeaders=host&X-Amz-Signature=" + "0" * 64)


FSL_URL = _r2_url("b26be7ab2ca58cec9372503da5e7eb9a", 28800)

DRIVE_PAGE = f"""<!DOCTYPE html><html><head><title>Film - HubCloud</title></head><body>
<div class="vd" style="display:none">
<a id="download" href="{GX_URL}">Download</a>
</div>
</body></html>"""

GX_PAGE = f"""<!DOCTYPE html><html><head><title>HubCloud</title></head><body>
<a href="{FSL_URL}" id="fsl" target="_blank" class="btn btn-success btn-lg h6">Download [FSL Server]</a>
<a href="{GBPS_URL}" target="_blank" class="btn btn-danger btn-lg h6">Download [Server : 10Gbps]</a>
<a href="{PIXEL_HREF}" id="pxl-1" target="_blank" class="btn btn-success btn-lg h6">Download [PixelServer : 2]</a>
</body></html>"""


def scripted_fetch(script):
    """A `fetch(url, headers, timeout, method, follow, data)` stand-in."""
    def fetch(url, headers=None, timeout=None, method="GET", follow=True,
              data=None):
        for key, value in script.items():
            if key in url:
                status, body, extra = value
                merged = {"content-type": "text/html"}
                merged.update(extra or {})
                return status, body, merged, url
        return 404, b"", {"content-type": "text/html"}, url
    return fetch


class Drop10gParserTests(unittest.TestCase):
    def test_parse_gamerxyt_buttons_drops_10gbps_keeps_fsl_and_pixel(self):
        """Same page, three buttons: FSL + PixelDrain come out, the
        10Gbps button does not — and the FSL item still carries its
        true presigned expiry."""
        out = fast_fetch._parse_gamerxyt_buttons(GX_PAGE)
        services = [o["service"] for o in out]
        self.assertEqual(services, ["fsl_r2", "pixeldrain"])
        self.assertNotIn("hubcloud_10g", services)
        fsl = out[0]
        self.assertEqual(fsl["final_url"], FSL_URL)
        self.assertEqual(fsl["is_temporary"], 1)
        self.assertAlmostEqual(fsl["expires_at"],
                               time.time() + 28800, delta=120)
        pixel = out[1]
        self.assertEqual(pixel["final_url"], PIXEL_DIRECT_URL)
        self.assertEqual(pixel["is_temporary"], 0)

    def test_unwrap_hubcloud_drive_drops_10gbps(self):
        """The full drive unwrapper on the canned chain: the items are
        fsl_r2 + pixeldrain only, even though the gamerxyt page still
        publishes a 10Gbps button."""
        def fake_get(url, **kwargs):
            if "gamerxyt.com" in url:
                return 200, url, GX_PAGE
            return 200, url, DRIVE_PAGE

        with patch.object(fast_fetch, "http_get", side_effect=fake_get):
            out = fast_fetch.unwrap_hubcloud_drive(DRIVE_URL)
        services = [o["service"] for o in out]
        self.assertEqual(services, ["fsl_r2", "pixeldrain"])
        self.assertNotIn("hubcloud_10g", services)


class Drop10gOneroundTests(unittest.TestCase):
    def test_dispatch_ignores_10gbps_button(self):
        """A 10Gbps button dispatches to no kind — text or href — so
        it lands in the ignored notes, never in the candidates."""
        self.assertEqual(hr._hubcloud_dispatch(
            {}, "Download [Server : 10Gbps]"), "")
        self.assertEqual(hr._hubcloud_dispatch(
            {"href": GBPS_URL, "class": "btn"},
            "Download [Server : 10Gbps]"), "")
        page = (f'<a href="{GBPS_URL}" class="btn btn-danger">'
                "Download [Server : 10Gbps]</a>")
        cands, notes = hr._hubcloud_candidates(scripted_fetch({}), page,
                                               GX_URL)
        self.assertEqual(cands, [])
        self.assertTrue(any("10Gbps" in n for n in notes), notes)

    def test_unwrap_hubcloud_verdict_has_no_10gbps_source(self):
        """The one-round verdict publishes FSL + PixelServer sources
        and no 10gbps kind, so the caller has nothing to bank onto a
        hubcloud_10g row."""
        fetch = scripted_fetch({
            f"/drive/{DRIVE_ID}": (200, DRIVE_PAGE, {}),
            "gamerxyt.com/hubcloud.php": (200, GX_PAGE, {}),
        })
        verdict = hr.unwrap_hubcloud(DRIVE_URL, fetch=fetch, verify=False)
        kinds = [s["kind"] for s in verdict["sources"]]
        self.assertEqual(kinds, ["fsl", "pixel"])
        self.assertNotIn("10gbps", kinds)


class Drop10gBankingTests(unittest.TestCase):
    def test_normalize_drops_10gbps_kind(self):
        """Defence in depth: a verdict source carrying kind 10gbps
        (e.g. from an older caller) maps to no sibling service."""
        cands = fast_fetch._normalize_hubcloud_candidates(
            [{"kind": "10gbps", "url": GBPS_URL, "expires_at": None},
             {"kind": "fsl", "url": FSL_URL, "expires_at": 123},
             {"kind": "pixel", "url": PIXEL_DIRECT_URL,
              "expires_at": None}])
        services = sorted(c["service"] for c in cands)
        self.assertEqual(services, ["fsl_r2", "pixeldrain"])

    def test_sibling_tables_exclude_10g(self):
        self.assertNotIn("hubcloud_10g",
                         fast_fetch._HUBCLOUD_SIBLING_SERVICES)
        self.assertNotIn("10gbps", fast_fetch._HUBCLOUD_KIND_TO_SERVICE)
        self.assertNotIn("hubcloud_10g",
                         fast_fetch._HUBCLOUD_SIBLING_TEMPORARY)

    def test_pixel_hubcloud_url_is_a_bridge(self):
        """A stored 10Gbps anchor is a landing page, not media: the
        resolver refreshes it instead of serving it to a player."""
        for url in ("https://pixel.hubcloud.mov/?id=abc",
                    "https://pixel.hubcloud.ist/?id=abc",
                    "https://pixel.hubcloud.pw/?id=abc"):
            self.assertTrue(fast_fetch.is_bridge_url(url), url)
            self.assertFalse(fast_fetch.is_direct_media_url(url), url)

    def test_resolver_version_is_17(self):
        """RV 17 stacks on the expiry-voe-stream branch's RV 16 (15 is
        the parallel keepalive branch's number)."""
        self.assertEqual(fast_fetch.RESOLVER_VERSION, 17)


if __name__ == "__main__":
    unittest.main()
