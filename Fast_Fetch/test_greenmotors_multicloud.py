#!/usr/bin/env python3
"""Offline tests for the greenmotors -> multicloudlinks step (sweep H3b, 2026-11-23).

Stdlib only, zero network: every fetch goes through a mocked ``http_get``
and every downstream unwrap through a mocked helper, so no test can knock
on a third party or slow a CI box.

The chain under test is the one proven live from the Oracle VM egress on
23 Nov 2026: greenmotors.cc/?id=<enc> -> 200 "Redirecting…" page carrying
the 4-layer base64/rot13 payload (same scheme as
``decode_greenmotors_payload``) -> decode yields
``https://new.multicloudlinks.com/player.php/?v=<id>`` (NOT hblinks — the
only destination the old code followed) -> player page (200, ~50 KB)
carries inline JS with ``streamSrc =
https://dr1.multidownload.click/d/<8-hex>?eid=<50ch>&token=<24ch>
&exp=<epoch>&action=watch`` -> Range probe of streamSrc: 206
video/matroska. Token stripped -> 403, signing mandatory. ``exp - now`` =
14,400 s exactly (4 h). Fixtures below are small synthetic pages with the
same structure as the real pages fetched that day — never the recorded
60 KB pages (they carry live signed tokens).

Also covered: the old hblinks destination still resolves (hubcloud +
pixeldrain), and the hblinks-page gdflix anchor the old loop dropped is
now collected via the existing ``unwrap_gdflix``.

    python3 -m pytest Fast_Fetch/test_greenmotors_multicloud.py -q
    python3 -S Fast_Fetch/test_greenmotors_multicloud.py
"""
from __future__ import annotations

import base64
import codecs
import json
import sys
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).parent.resolve()))

import fast_fetch  # noqa: E402

GM_URL = "https://greenmotors.club/?id=testgm123"
PLAYER_URL = "https://new.multicloudlinks.com/player.php/?v=jfanw1"
HB_URL = "https://hblinks.lol/archives/119453"
EID = "a" * 50
TOKEN = "b" * 24


def encode_payload(dest_url: str) -> str:
    """The 4-layer greenmotors encode (inverse of decode_greenmotors_payload)."""
    b64_dest = base64.b64encode(dest_url.encode()).decode()
    json_obj = json.dumps({"w": 10, "l": "https://greenmotors.cc/homelander/",
                           "o": b64_dest})
    p4 = base64.b64encode(json_obj.encode()).decode()
    p3 = codecs.encode(p4, "rot_13")
    p2 = base64.b64encode(p3.encode()).decode()
    return base64.b64encode(p2.encode()).decode()


def gm_page(dest_url: str) -> str:
    return (
        "<html><body><script>\n"
        f"s('o','{encode_payload(dest_url)}',180*1000);\n"
        "setTimeout(()=>window.location.href='https://greenmotors.cc/homelander/', 2000);\n"
        "</script></body></html>"
    )


def player_page(exp: int | None = 14400, stream_src: str | None = None) -> str:
    """Player page in the live shape. ``exp`` is seconds from now (None = no exp)."""
    if stream_src is None:
        exp_q = f"&exp={int(time.time()) + exp}&action=watch" if exp is not None else "&action=watch"
        stream_src = (f"https://dr1.multidownload.click/d/ab12cd34"
                      f"?eid={EID}&token={TOKEN}{exp_q}")
    return (
        "<html><body><script>\n"
        f'var streamSrc = "{stream_src}";\n'
        'var fallbackSrc = "https://dr1.multidownload.click/d/ab12cd34'
        f'?eid={EID}&token={TOKEN}&exp=1&action=watch";\n'
        'var prepareSrc = "https://dr1.multidownload.click/prepare/ab12cd34";\n'
        "</script></body></html>"
    )


def hb_page(anchors: str) -> str:
    return f"<html><body>\n{anchors}\n</body></html>"


class GreenmotorsMulticloudTests(unittest.TestCase):
    def _run(self, gm_html, pages, hub_mock=None, gdflix_mock=None):
        """pages: {substring: html} routed by URL; hub/gdflix mocks optional."""
        def mock_get(url, **kwargs):
            for key, html in pages.items():
                if key in url:
                    return 200, url, html
            return 404, url, ""

        patches = [mock.patch.object(fast_fetch, "http_get", side_effect=mock_get)]
        if hub_mock is not None:
            patches.append(mock.patch.object(
                fast_fetch, "unwrap_hubcloud_drive", return_value=hub_mock))
        if gdflix_mock is not None:
            patches.append(mock.patch.object(
                fast_fetch, "unwrap_gdflix", return_value=gdflix_mock))
        for p in patches:
            p.start()
        self.addCleanup(lambda: [p.stop() for p in patches])
        return fast_fetch.unwrap_greenmotors(GM_URL)

    def test_multicloudlinks_dest_followed(self):
        exp = int(time.time()) + 14400
        stream = (f"https://dr1.multidownload.click/d/ab12cd34"
                  f"?eid={EID}&token={TOKEN}&exp={exp}&action=watch")
        sources = self._run(gm_page(PLAYER_URL),
                            {"greenmotors": gm_page(PLAYER_URL),
                             "multicloudlinks": player_page(stream_src=stream)})
        self.assertEqual(len(sources), 1)
        s = sources[0]
        self.assertEqual(s["service"], "multicloud")
        self.assertEqual(s["resource_id"], "jfanw1")
        self.assertEqual(s["source_url"], PLAYER_URL)
        self.assertEqual(s["final_url"], stream)
        self.assertEqual(s["is_temporary"], 1)
        self.assertEqual(s["expires_at"], float(exp))
        self.assertAlmostEqual(s["expires_at"] - time.time(), 14400, delta=5)

    def test_multicloudlinks_player_without_streamsrc_falls_back(self):
        sources = self._run(gm_page(PLAYER_URL),
                            {"greenmotors": gm_page(PLAYER_URL),
                             "multicloudlinks": "<html><body>no player here</body></html>"})
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["service"], "greenmotors")
        self.assertEqual(sources[0]["final_url"], PLAYER_URL)

    def test_multicloudlinks_stream_without_exp_still_temporary(self):
        sources = self._run(gm_page(PLAYER_URL),
                            {"greenmotors": gm_page(PLAYER_URL),
                             "multicloudlinks": player_page(exp=None)})
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["service"], "multicloud")
        self.assertEqual(sources[0]["is_temporary"], 1)
        self.assertIsNone(sources[0]["expires_at"])

    def test_hblinks_dest_still_resolves(self):
        hub_mock = [{
            "source_name": "⚡ HubCloud [FSL R2 CDN]",
            "service": "fsl_r2",
            "resource_id": "9sospqs8q1nnhc8",
            "source_url": "https://hubcloud.ist/drive/9sospqs8q1nnhc8",
            "final_url": "https://r2.cloudflarestorage.com/hub/token123",
            "is_temporary": 1,
        }]
        hb = hb_page(
            '<a href="https://hubcloud.ist/drive/9sospqs8q1nnhc8">HubCloud 1080p</a>\n'
            '<a href="https://pixeldrain.com/u/negn6f">PixelDrain Mirror</a>')
        sources = self._run(gm_page(HB_URL),
                            {"greenmotors": gm_page(HB_URL), "hblinks": hb},
                            hub_mock=hub_mock)
        self.assertEqual(len(sources), 2)
        svcs = [s["service"] for s in sources]
        self.assertIn("fsl_r2", svcs)
        self.assertIn("pixeldrain", svcs)
        self.assertTrue(any("pixeldrain.com/api/file/negn6f" in s["final_url"]
                            for s in sources))

    def test_hblinks_gdflix_anchor_collected(self):
        gdflix_mock = ("https://video-downloads.googleusercontent.com/file/abc",
                       "gdflix123", 1)
        hb = hb_page(
            '<a href="https://new10.gdflix.io/file/gdflix123">GDFlix 720p</a>')
        sources = self._run(gm_page(HB_URL),
                            {"greenmotors": gm_page(HB_URL), "hblinks": hb},
                            gdflix_mock=gdflix_mock)
        self.assertEqual(len(sources), 1)
        s = sources[0]
        self.assertEqual(s["service"], "gdflix")
        self.assertEqual(s["final_url"], gdflix_mock[0])
        self.assertEqual(s["is_temporary"], 1)

    def test_multicloudlinks_anchor_in_hblinks_page(self):
        hb = hb_page(
            '<a href="https://new.multicloudlinks.com/player.php/?v=jfanw1">'
            "MultiCloud 1080p</a>")
        sources = self._run(gm_page(HB_URL),
                            {"greenmotors": gm_page(HB_URL),
                             "hblinks": hb,
                             "multicloudlinks": player_page()})
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["service"], "multicloud")
        self.assertEqual(sources[0]["resource_id"], "jfanw1")

    def test_dead_page_returns_bridge_fallback(self):
        with mock.patch.object(fast_fetch, "http_get",
                               return_value=(0, GM_URL, "")):
            sources = fast_fetch.unwrap_greenmotors(GM_URL)
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]["service"], "greenmotors")
        self.assertEqual(sources[0]["final_url"], GM_URL)


if __name__ == "__main__":
    unittest.main(verbosity=2)
