#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline tests for the Phase C GDFlix button-flow resolver.

The audit behind this slice: the old ``unwrap_gdflix`` only looked for an
embedded ``video-downloads.googleusercontent.com`` URL or a Drive file id on
the ``/file/<id>`` landing page — but the live page (measured 2026-11-20)
carries neither. It carries download buttons. What this pins:

* the live chain: ``Instant DL`` → ``busycdn.instadl.rest/<token>`` → 302 →
  ``fastdl-one.pages.dev/?url=<signed video-downloads URL>``; the ``?url=``
  value is the file (temporary);
* the legacy buttons (NuvioStreamsAddon / vega-app extractors): ``DIRECT DL``
  and ``CLOUD DOWNLOAD [R2]`` return their href, ``PixelDrain DL`` is
  rewritten to the ``/api/file/`` form, ``DRIVEBOT`` follows the ResumeBot
  token-POST flow (``formData.append('token', …)`` → POST ``/download?id=``
  → JSON ``.url``);
* the wrapper: a ``<body onload="location.replace('…')">`` page is followed
  once before the buttons are read;
* the refusals: a dead page, a Cloudflare challenge page, a page with no
  buttons, and a page with only login / Turnstile-gated ``/cflare`` links
  all return the landing page unchanged (temp 0) so the caller's bridge
  guard refuses them;
* the busycdn scan (speed path, 23 Nov 2026): the file page embeds the
  ``busycdn.instadl.rest/<token>?bytes=N`` URL, so ``unwrap_gdflix``
  reads it straight off the page — one page GET, no button parsing —
  and ``bytes=`` parses to the file size. The no-follow hop still runs
  (the busycdn URL 302s to a fastdl HTML interstitial, not to bytes,
  so only the hop's ``?url=`` is the file); a hop that yields nothing
  falls through to the legacy buttons without repeating the same hop.
* the old behaviour is kept: an embedded video-downloads URL or Drive file
  id still unwraps first;
* the H4 R8 clearance is threaded to the page fetch and to the Instant DL
  hop, and the no-follow helper returns the ``Location`` header (following
  that hop with ``http_get`` would download the file itself).

No network, no production DB: ``http_get``, the redirect helper and the
DriveBot POST are all stubbed. Fixture tokens/URLs are fake.
"""

from __future__ import annotations

import email.message
import io
import json
import os
import sys
import unittest
import urllib.error
from unittest import mock

os.environ.setdefault("RADD_SKIP_LIVE_ENRICH", "1")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fast_fetch  # noqa: E402

FILE_URL = "https://new10.gdflix.io/file/N4NC704WLKFJAKL"
GOOGLE_URL = ("https://video-downloads.googleusercontent.com/"
              "FAKEADGPM2lDlJqym4xgQRGP8uIqg7TRpJmiuE1IV4K9s")
BUSY_URL = "https://busycdn.instadl.rest/FAKETOKEN123?bytes=938314815"
FASTDL_URL = "https://fastdl-one.pages.dev/?url=" + GOOGLE_URL


def _page(*buttons: str) -> str:
    body = "".join(buttons)
    return (
        "<html><head><title>GDFlix | Final.Table.S01E03.1080p.WEB-DL.mkv"
        "</title></head><body>"
        '<ul><li class="list-group-item">Name : '
        "Final.Table.S01E03.1080p.WEB-DL.mkv</li>"
        '<li class="list-group-item">Size : 894.85MB | Views : 16</li></ul>'
        f'<div class="text-center">{body}</div>'
        "</body></html>"
    )


INSTANT_BTN = (
    f'<a href="{BUSY_URL}" target="_blank" class="btn btn-danger">'
    "Instant DL [10GBPS]</a>"
)
DIRECT_BTN = (
    '<a href="https://r2.example.invalid/direct/file.mkv" '
    'class="btn btn-danger">DIRECT DL</a>'
)
CLOUD_BTN = (
    '<a href="https://r2.example.invalid/cloud/file.mkv" '
    'class="btn btn-outline-success">CLOUD DOWNLOAD [R2]</a>'
)
PIXEL_BTN = (
    '<a href="https://pixeldrain.com/u/abc123XYZ" '
    'class="btn btn-success">PixelDrain DL</a>'
)
BOT_BTN = (
    '<a href="https://drivebot.sbs/download?id=QUJDREVG&do=REVG" '
    'class="btn btn-secondary">DRIVEBOT [50MB/S]</a>'
)
LOGIN_BTN = (
    f'<a href="/login?ref=/file/N4NC704WLKFJAKL" class="btn btn-primary">'
    "Login To DL [10GBPS]</a>"
)
CFLARE_BTN = (
    '<a href="/cflare/1795124562/N4NC704WLKFJAKL" '
    'class="btn btn-secondary">FAST CLOUD / ZIPDISK</a>'
)


class GDFlixInstantTests(unittest.TestCase):
    def test_instant_dl_chain_returns_the_wrapped_google_url(self):
        """The proven live chain ends at the signed Google URL (temp=1)."""
        getter = mock.Mock(
            return_value=(200, FILE_URL, _page(INSTANT_BTN)))
        locator = mock.Mock(return_value=FASTDL_URL)
        with mock.patch.object(fast_fetch, "http_get", getter), \
                mock.patch.object(fast_fetch, "_gdflix_redirect_location",
                                  locator):
            url, res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, GOOGLE_URL)
        self.assertEqual(res_id, "N4NC704WLKFJAKL")
        self.assertEqual(temp, 1)
        locator.assert_called_once_with(BUSY_URL, timeout=10, clearance=None)

    def test_instant_dl_without_location_falls_through_to_direct_dl(self):
        """A busycdn hop that does not 302 must not swallow the other buttons."""
        getter = mock.Mock(
            return_value=(200, FILE_URL, _page(INSTANT_BTN, DIRECT_BTN)))
        locator = mock.Mock(return_value=None)
        with mock.patch.object(fast_fetch, "http_get", getter), \
                mock.patch.object(fast_fetch, "_gdflix_redirect_location",
                                  locator):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, "https://r2.example.invalid/direct/file.mkv")
        self.assertEqual(temp, 1)

    def test_instant_dl_location_without_url_param_falls_through(self):
        """A redirect to a bare worker URL (no ?url=, not a Google host)
        is not a file — the next button wins."""
        getter = mock.Mock(
            return_value=(200, FILE_URL, _page(INSTANT_BTN, CLOUD_BTN)))
        locator = mock.Mock(
            return_value="https://fastdl-one.pages.dev/abc")
        with mock.patch.object(fast_fetch, "http_get", getter), \
                mock.patch.object(fast_fetch, "_gdflix_redirect_location",
                                  locator):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, "https://r2.example.invalid/cloud/file.mkv")
        self.assertEqual(temp, 1)

    def test_instant_dl_location_to_google_host_is_used_directly(self):
        """If the hop lands straight on the Google host, that is the file."""
        getter = mock.Mock(
            return_value=(200, FILE_URL, _page(INSTANT_BTN)))
        locator = mock.Mock(return_value=GOOGLE_URL + "/file.mkv")
        with mock.patch.object(fast_fetch, "http_get", getter), \
                mock.patch.object(fast_fetch, "_gdflix_redirect_location",
                                  locator):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, GOOGLE_URL + "/file.mkv")
        self.assertEqual(temp, 1)


class GDFlixBusycdnScanTests(unittest.TestCase):
    """The speed path: busycdn URL read off the page, not off the button.

    Fixture tokens/URLs are fake; the live shape (``::`` token,
    ``bytes=`` size) is pinned by ``test_busycdn_live_shape_parses``.
    """

    def test_busycdn_embedded_outside_any_button_is_used(self):
        """A busycdn URL in a <script> (no buttons at all) still unwraps
        — the scan, not the button parse, finds it. One page GET."""
        script_page = (
            "<html><head><title>GDFlix | f.mkv</title></head><body>"
            '<script>var dl="https://busycdn.instadl.rest/FAKETOKEN123'
            '?bytes=938314815";</script></body></html>'
        )
        getter = mock.Mock(return_value=(200, FILE_URL, script_page))
        locator = mock.Mock(return_value=FASTDL_URL)
        with mock.patch.object(fast_fetch, "http_get", getter), \
                mock.patch.object(fast_fetch, "_gdflix_redirect_location",
                                  locator):
            url, res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, GOOGLE_URL)
        self.assertEqual(res_id, "N4NC704WLKFJAKL")
        self.assertEqual(temp, 1)
        locator.assert_called_once_with(BUSY_URL, timeout=10, clearance=None)
        self.assertEqual(getter.call_count, 1)

    def test_busycdn_live_shape_parses(self):
        """Live shape (23 Nov, Tor): long hex token with ``::``, a short
        hex hash, ``bytes=`` the file size in bytes. Token is synthetic
        — only the shape and the live byte size are pinned."""
        live = ("https://busycdn.instadl.rest/" + "ab" * 80 + "::"
                + "cd" * 16 + "?bytes=1780539782")
        got = fast_fetch._gdflix_busycdn_from_page(
            f'<a href="{live}">Instant DL [10GBPS]</a>')
        self.assertEqual(got, (live, 1780539782))

    def test_busycdn_without_bytes_is_ignored(self):
        """A busycdn mention with no ``bytes=`` is not the file link:
        the scan passes and the legacy buttons still win."""
        page = _page(
            '<a href="https://busycdn.instadl.rest/FAKETOKEN123">x</a>',
            DIRECT_BTN)
        getter = mock.Mock(return_value=(200, FILE_URL, page))
        with mock.patch.object(fast_fetch, "http_get", getter):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, "https://r2.example.invalid/direct/file.mkv")
        self.assertEqual(temp, 1)

    def test_busycdn_scan_hop_failure_falls_through_without_repeating(self):
        """Scan hit + hop yields nothing: DIRECT DL wins, and the
        Instant DL button (same URL) does NOT pay for a second hop."""
        getter = mock.Mock(
            return_value=(200, FILE_URL, _page(INSTANT_BTN, DIRECT_BTN)))
        locator = mock.Mock(return_value=None)
        with mock.patch.object(fast_fetch, "http_get", getter), \
                mock.patch.object(fast_fetch, "_gdflix_redirect_location",
                                  locator):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, "https://r2.example.invalid/direct/file.mkv")
        self.assertEqual(temp, 1)
        self.assertEqual(locator.call_count, 1)

    def test_different_instant_button_still_gets_its_hop(self):
        """Scan hit (URL-A, hop fails) + an Instant DL button at a
        DIFFERENT URL-B: the button still gets its own hop."""
        other = "https://busycdn.instadl.rest/OTHERTOKEN999?bytes=111"
        other_btn = (
            f'<a href="{other}" class="btn btn-danger">'
            "Instant DL [10GBPS]</a>"
        )
        script_page = (
            "<html><body>"
            '<script>var dl="https://busycdn.instadl.rest/FAKETOKEN123'
            '?bytes=938314815";</script>'
            f"{other_btn}</body></html>"
        )
        getter = mock.Mock(return_value=(200, FILE_URL, script_page))
        locator = mock.Mock(side_effect=[None, FASTDL_URL])
        with mock.patch.object(fast_fetch, "http_get", getter), \
                mock.patch.object(fast_fetch, "_gdflix_redirect_location",
                                  locator):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, GOOGLE_URL)
        self.assertEqual(temp, 1)
        self.assertEqual(locator.call_count, 2)
        self.assertEqual(locator.call_args_list[0].args[0], BUSY_URL)
        self.assertEqual(locator.call_args_list[1].args[0], other)


class GDFlixLegacyButtonTests(unittest.TestCase):
    def test_direct_dl_beats_cloud_download(self):
        getter = mock.Mock(
            return_value=(200, FILE_URL, _page(CLOUD_BTN, DIRECT_BTN)))
        with mock.patch.object(fast_fetch, "http_get", getter):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, "https://r2.example.invalid/direct/file.mkv")
        self.assertEqual(temp, 1)

    def test_cloud_download_alone_returns_its_href(self):
        getter = mock.Mock(
            return_value=(200, FILE_URL, _page(CLOUD_BTN)))
        with mock.patch.object(fast_fetch, "http_get", getter):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, "https://r2.example.invalid/cloud/file.mkv")
        self.assertEqual(temp, 1)

    def test_pixeldrain_dl_rewrites_to_api_form(self):
        """The /u/ view URL becomes the stable /api/file/ URL (temp=0)."""
        getter = mock.Mock(
            return_value=(200, FILE_URL, _page(PIXEL_BTN)))
        with mock.patch.object(fast_fetch, "http_get", getter):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(
            url, "https://pixeldrain.dev/api/file/abc123XYZ?download")
        self.assertEqual(temp, 0)


class GDFlixDriveBotTests(unittest.TestCase):
    def test_drivebot_token_post_returns_json_url(self):
        """ResumeBot flow: token from the page, POST /download?id=,
        JSON .url is the file (temp=1)."""
        bot_page = (
            "<html><body><script>"
            "var fd = new FormData();"
            "formData.append('token', 'tok-9f8e7d');"
            "</script></body></html>"
        )
        getter = mock.Mock(side_effect=[
            (200, FILE_URL, _page(BOT_BTN)),
            (200, "https://drivebot.sbs/download?id=QUJDREVG&do=REVG", bot_page),
        ])
        payload = json.dumps({"url": GOOGLE_URL}).encode("utf-8")
        resp = mock.MagicMock()
        resp.__enter__.return_value.read.return_value = payload
        poster = mock.Mock(return_value=resp)
        with mock.patch.object(fast_fetch, "http_get", getter), \
                mock.patch.object(fast_fetch.urllib.request, "urlopen",
                                  poster):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, GOOGLE_URL)
        self.assertEqual(temp, 1)
        posted = poster.call_args.args[0]
        self.assertIn("/download?id=QUJDREVG", posted.full_url)
        self.assertEqual(posted.data, b"token=tok-9f8e7d")

    def test_drivebot_without_token_returns_landing_unchanged(self):
        """A DriveBot page with no token form is not a playable link."""
        getter = mock.Mock(side_effect=[
            (200, FILE_URL, _page(BOT_BTN)),
            (200, "https://drivebot.sbs/download?id=QUJDREVG&do=REVG",
             "<html><body>no token here</body></html>"),
        ])
        with mock.patch.object(fast_fetch, "http_get", getter):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, FILE_URL)
        self.assertEqual(temp, 0)


class GDFlixWrapperAndRefusalTests(unittest.TestCase):
    def test_onload_location_replace_wrapper_is_followed_once(self):
        wrapper = (
            '<html><body onload="location.replace('
            "'/file/REALFILEID123')\">redirecting</body></html>"
        )
        getter = mock.Mock(side_effect=[
            (200, FILE_URL, wrapper),
            (200, "https://new10.gdflix.io/file/REALFILEID123",
             _page(INSTANT_BTN)),
        ])
        locator = mock.Mock(return_value=FASTDL_URL)
        with mock.patch.object(fast_fetch, "http_get", getter), \
                mock.patch.object(fast_fetch, "_gdflix_redirect_location",
                                  locator):
            url, res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, GOOGLE_URL)
        self.assertEqual(res_id, "N4NC704WLKFJAKL")
        self.assertEqual(temp, 1)
        self.assertEqual(getter.call_count, 2)
        self.assertEqual(
            getter.call_args_list[1].args[0],
            "https://new10.gdflix.io/file/REALFILEID123")

    def test_dead_page_returns_landing_unchanged(self):
        getter = mock.Mock(return_value=(0, FILE_URL, ""))
        with mock.patch.object(fast_fetch, "http_get", getter):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, FILE_URL)
        self.assertEqual(temp, 0)

    def test_challenge_page_returns_landing_unchanged(self):
        """A Cloudflare 'Just a moment...' page has no buttons to unwrap."""
        page = ("<!DOCTYPE html><html><head><title>Just a moment...</title>"
                "</head><body>challenge</body></html>")
        getter = mock.Mock(return_value=(403, FILE_URL, page))
        with mock.patch.object(fast_fetch, "http_get", getter):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, FILE_URL)
        self.assertEqual(temp, 0)

    def test_page_with_no_buttons_returns_landing_unchanged(self):
        getter = mock.Mock(return_value=(200, FILE_URL, _page()))
        with mock.patch.object(fast_fetch, "http_get", getter):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, FILE_URL)
        self.assertEqual(temp, 0)

    def test_login_and_cflare_buttons_only_return_landing_unchanged(self):
        """Login needs an account; /cflare is Turnstile-gated. Neither is
        chased, so the bridge guard still refuses the page."""
        getter = mock.Mock(
            return_value=(200, FILE_URL, _page(LOGIN_BTN, CFLARE_BTN)))
        with mock.patch.object(fast_fetch, "http_get", getter):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(url, FILE_URL)
        self.assertEqual(temp, 0)


class GDFlixEmbeddedTests(unittest.TestCase):
    def test_embedded_video_downloads_url_still_unwraps_first(self):
        page = ('<a href="https://video-downloads.googleusercontent.com/'
                'abc/file.mp4">play</a>')
        getter = mock.Mock(return_value=(200, FILE_URL, page))
        with mock.patch.object(fast_fetch, "http_get", getter):
            url, _res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertEqual(
            url, "https://video-downloads.googleusercontent.com/abc/file.mp4")
        self.assertEqual(temp, 1)

    def test_embedded_drive_file_id_still_unwraps(self):
        page = ('<script>const player={"fileId":'
                '"1AbCdEfGhIjKlMnOpQrStUvWxYz12"};</script>')
        getter = mock.Mock(return_value=(200, FILE_URL, page))
        with mock.patch.object(fast_fetch, "http_get", getter):
            url, res_id, temp = fast_fetch.unwrap_gdflix(FILE_URL)
        self.assertTrue(
            url.startswith(
                "https://drive.usercontent.google.com/download?id="), url)
        self.assertEqual(res_id, "N4NC704WLKFJAKL")
        self.assertEqual(temp, 1)


class GDFlixClearanceTests(unittest.TestCase):
    def test_clearance_is_threaded_to_the_page_fetch(self):
        """H4 R8: a stored clearance rides the file-page request."""
        clearance = mock.Mock()
        getter = mock.Mock(return_value=(200, FILE_URL, _page()))
        with mock.patch.object(fast_fetch, "http_get", getter):
            fast_fetch.unwrap_gdflix(FILE_URL, clearance=clearance)
        self.assertIs(getter.call_args.kwargs.get("clearance"), clearance)

    def test_clearance_is_threaded_to_the_instant_dl_hop(self):
        clearance = mock.Mock()
        getter = mock.Mock(return_value=(200, FILE_URL, _page(INSTANT_BTN)))
        locator = mock.Mock(return_value=FASTDL_URL)
        with mock.patch.object(fast_fetch, "http_get", getter), \
                mock.patch.object(fast_fetch, "_gdflix_redirect_location",
                                  locator):
            fast_fetch.unwrap_gdflix(FILE_URL, clearance=clearance)
        self.assertIs(locator.call_args.kwargs.get("clearance"), clearance)


class GDFlixRedirectHelperTests(unittest.TestCase):
    def _headers(self, location):
        msg = email.message.Message()
        if location is not None:
            msg.add_header("Location", location)
        return msg

    def test_redirect_location_returns_the_location_header(self):
        err = urllib.error.HTTPError(
            BUSY_URL, 302, "Found", self._headers(FASTDL_URL),
            io.BytesIO(b""))
        opener = mock.Mock()
        opener.open.side_effect = err
        with mock.patch.object(fast_fetch.urllib.request, "build_opener",
                               return_value=opener):
            got = fast_fetch._gdflix_redirect_location(BUSY_URL, timeout=10)
        self.assertEqual(got, FASTDL_URL)
        sent = opener.open.call_args.args[0]
        self.assertEqual(sent.full_url, BUSY_URL)

    def test_redirect_location_returns_none_on_200(self):
        opener = mock.Mock()
        opener.open.return_value = mock.Mock()
        with mock.patch.object(fast_fetch.urllib.request, "build_opener",
                               return_value=opener):
            got = fast_fetch._gdflix_redirect_location(BUSY_URL, timeout=10)
        self.assertIsNone(got)

    def test_redirect_location_returns_none_on_error(self):
        opener = mock.Mock()
        opener.open.side_effect = urllib.error.URLError("boom")
        with mock.patch.object(fast_fetch.urllib.request, "build_opener",
                               return_value=opener):
            got = fast_fetch._gdflix_redirect_location(BUSY_URL, timeout=10)
        self.assertIsNone(got)


if __name__ == "__main__":
    unittest.main(verbosity=2)
