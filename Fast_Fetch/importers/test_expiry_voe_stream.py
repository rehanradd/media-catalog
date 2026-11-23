#!/usr/bin/env python3
"""
Offline tests for the voe/stream `e`-duration expiry parse (latency audit
fix 5, 2026-11-23) — no live socket, no database, no pip.

`unwrap_voe` and `unwrap_packer_hls` used to return `signed_params`
(`e`, `s`, `t`) but no `expires_in`: both called `expiry_from_url`, which
reads `e` as an ABSOLUTE epoch (9–11 digits), so a voe source carrying
`e=14400` (4 h) or a stream source carrying `e=129600` (36 h) banked a
NULL expiry and every press past the 6 h ceiling cold re-resolved
(3.1 s / 1.7 s). The fix adds `duration_param_expiry` — `e` as a bare
lifetime in seconds, plausible only below 10^6 s — as the fallback after
`expiry_from_url` returns (None, None). The existing `signed_params`
branch in `resolve_live_source` banks it; no plumbing change.

The fixtures are the live shapes: the voe payload is encoded the way
VOE's page does (the `_voe_encode` helper is the inverse of
`voe_decode`, copied from Fast_Fetch/test_database.py), and the packer
page is the minimal two-key P.A.C.K.E.R. block the Session 103 packer
test uses, with a signed hls2 URL. Every request goes through an
injected `fetch`; `socket.socket` is replaced with a raiser so an
accidental live request fails the run instead of passing it.

Run from the repo root: python3 -S Fast_Fetch/importers/test_expiry_voe_stream.py
"""

from __future__ import annotations

import base64
import json
import socket
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
# Fast_Fetch/ too: host_resolvers imports warp_proxy from there.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import host_resolvers as hr  # noqa: E402


class _NoLiveSocket:
    def __init__(self, *a, **k):
        raise AssertionError("test attempted a live socket")


socket.socket = _NoLiveSocket  # type: ignore[attr-defined]

HLS_BODY = b"#EXTM3U\n#EXT-X-VERSION:3\n"
HLS_HEADERS = {"content-type": "application/vnd.apple.mpegurl",
               "content-range": "bytes 0-511/512"}


class TestDurationParamExpiry(unittest.TestCase):
    def test_voe_four_hour_lifetime(self):
        expires_at, ttl = hr.duration_param_expiry(
            "https://cdn.example/hls/master.m3u8?s=1794000000&e=14400&t=x")
        self.assertEqual(ttl, 14400)
        self.assertAlmostEqual(expires_at, int(time.time()) + 14400, delta=5)

    def test_stream_thirty_six_hour_lifetime(self):
        _expires_at, ttl = hr.duration_param_expiry(
            "https://cdn.example/hls2/01/1/f/master.m3u8?t=x&s=1&e=129600&f=1")
        self.assertEqual(ttl, 129600)

    def test_boundary_999999_is_plausible(self):
        _expires_at, ttl = hr.duration_param_expiry(
            "https://cdn.example/hls/master.m3u8?e=999999")
        self.assertEqual(ttl, 999999)

    def test_missing_e_is_nothing(self):
        self.assertEqual(
            hr.duration_param_expiry("https://cdn.example/hls/master.m3u8?s=1"),
            (None, None))

    def test_epoch_shaped_e_is_not_a_duration(self):
        # A 9-digit `e` is an epoch, not a lifetime — `expiry_from_url`
        # already owns that shape, and this helper must not claim it.
        self.assertEqual(
            hr.duration_param_expiry(
                "https://cdn.example/hls/master.m3u8?e=1794166028"),
            (None, None))

    def test_seven_digit_e_is_rejected(self):
        self.assertEqual(
            hr.duration_param_expiry(
                "https://cdn.example/hls/master.m3u8?e=1000000"),
            (None, None))

    def test_non_numeric_e_is_nothing(self):
        self.assertEqual(
            hr.duration_param_expiry(
                "https://cdn.example/hls/master.m3u8?e=abc"),
            (None, None))

    def test_negative_e_is_nothing(self):
        self.assertEqual(
            hr.duration_param_expiry(
                "https://cdn.example/hls/master.m3u8?e=-5"),
            (None, None))

    def test_zero_e_is_nothing(self):
        self.assertEqual(
            hr.duration_param_expiry(
                "https://cdn.example/hls/master.m3u8?e=0"),
            (None, None))

    def test_empty_url_is_nothing(self):
        self.assertEqual(hr.duration_param_expiry(""), (None, None))


def _voe_encode(data):
    """Encode a payload the way VOE's page does (inverse of voe_decode)."""
    raw = json.dumps(data, separators=(",", ":"))
    step = base64.b64encode(raw.encode()).decode()[::-1]
    step = "".join(chr(ord(c) + 3) for c in step)
    step = base64.b64encode(step.encode()).decode()
    text = step[:5] + "@#" + step[5:10] + "~!" + step[10:]
    out = []
    for ch in text:
        x = ord(ch)
        if 65 <= x <= 90:
            x = (x - 78) % 26 + 65
        elif 97 <= x <= 122:
            x = (x - 110) % 26 + 97
        out.append(chr(x))
    return "".join(out), "['@#','~!']"


def _voe_fetch(source_url):
    """Fake fetch for the voe.sx -> mirror -> loader -> probe chain."""
    mirror = "https://shannoncoldalready.example/e/n15ncgjxnnbx"
    code, luts = _voe_encode({"source": source_url,
                              "fallback": "https://cdn.example/f.mp4"})
    page = ('<html><script type="application/json">["' + code
            + '"]</script><script src="/js/loader.test.js">'
            '</script></html>')

    def fake_fetch(url, headers=None, timeout=25, method="GET", follow=True):
        if url == "https://voe.sx/n15ncgjxnnbx":
            return (200, ("<script>window.location.href = '"
                          + mirror + "';</script>").encode(), {}, url)
        if url == mirror:
            return (200, page.encode(), {}, url)
        if url.endswith("/js/loader.test.js"):
            return (200, ("var x = " + luts + ";").encode(), {}, url)
        if "master.m3u8" in url:
            return (206, HLS_BODY, HLS_HEADERS, url)
        return (404, b"", {}, url)

    return fake_fetch


class TestVoeExpiry(unittest.TestCase):
    def test_voe_banks_the_four_hour_e_lifetime(self):
        source = ("https://cdn.example/hls/master.m3u8"
                  "?s=1794000000&e=14400&t=tok")
        verdict = hr.unwrap_voe("https://voe.sx/n15ncgjxnnbx",
                                fetch=_voe_fetch(source))
        self.assertEqual(verdict["status"], "active", verdict)
        self.assertEqual(verdict["final_url"], source)
        self.assertEqual(verdict["expires_in"], 14400)
        self.assertIn("e", verdict["signed_params"])

    def test_voe_without_e_still_banks_nothing(self):
        verdict = hr.unwrap_voe(
            "https://voe.sx/n15ncgjxnnbx",
            fetch=_voe_fetch("https://cdn.example/hls/master.m3u8"))
        self.assertEqual(verdict["status"], "active", verdict)
        self.assertIsNone(verdict["expires_in"])

    def test_voe_epoch_e_still_uses_the_epoch_reader(self):
        # The fallback must never override `expiry_from_url`: an
        # epoch-shaped `e` banks the real remaining window, not int(e).
        future = int(time.time()) + 3600
        source = f"https://cdn.example/hls/master.m3u8?e={future}"
        verdict = hr.unwrap_voe("https://voe.sx/n15ncgjxnnbx",
                                fetch=_voe_fetch(source))
        self.assertEqual(verdict["status"], "active", verdict)
        self.assertAlmostEqual(verdict["expires_in"], 3600, delta=5)


def _packer_fetch(media_url):
    """Fake fetch for a packer embed page carrying one signed hls2."""
    page = ('<html><script>eval(function(p,a,c,k,e,d){e=function(c){return c};'
            'while(c--)if(k[c])p=p.replace(new RegExp(\'\\\\b\'+e(c)+\'\\\\b\',\'g\'),k[c]);'
            'return p}(\'0={"1":"' + media_url + '"};\',10,2,\'var|hls2\'.split(\'|\'),0,{}))'
            '</script></html>')

    def fake_fetch(url, headers=None, timeout=25, method="GET", follow=True):
        if "master.m3u8" in url:
            return (206, HLS_BODY, HLS_HEADERS, url)
        return (200, page.encode(), {}, url)

    return fake_fetch


class TestPackerHlsExpiry(unittest.TestCase):
    def test_packer_banks_the_thirty_six_hour_e_lifetime(self):
        media = ("https://cdn.example/hls/master.m3u8"
                 "?s=1794000000&e=129600&t=tok")
        verdict = hr.unwrap_packer_hls(
            "https://watch-online.mom/e/r80snwale95i", "stream",
            fetch=_packer_fetch(media))
        self.assertEqual(verdict["status"], "active", verdict)
        self.assertEqual(verdict["final_url"], media)
        self.assertEqual(verdict["expires_in"], 129600)
        self.assertIn("e", verdict["signed_params"])

    def test_packer_without_e_still_banks_nothing(self):
        verdict = hr.unwrap_packer_hls(
            "https://watch-online.mom/e/r80snwale95i", "stream",
            fetch=_packer_fetch("https://cdn.example/hls/master.m3u8"))
        self.assertEqual(verdict["status"], "active", verdict)
        self.assertIsNone(verdict["expires_in"])

    def test_packer_epoch_e_still_uses_the_epoch_reader(self):
        future = int(time.time()) + 3600
        media = f"https://cdn.example/hls/master.m3u8?e={future}"
        verdict = hr.unwrap_packer_hls(
            "https://watch-online.mom/e/r80snwale95i", "stream",
            fetch=_packer_fetch(media))
        self.assertEqual(verdict["status"], "active", verdict)
        self.assertAlmostEqual(verdict["expires_in"], 3600, delta=5)


if __name__ == "__main__":
    unittest.main()
