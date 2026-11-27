#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Resolve-output validation core tests (self-healing Branch 1).

Covers the checker-only port in ``fast_fetch.py``:
  * ``verify_media_bytes`` — pure byte sniffing, ported from the
    movie-downloader script's ``is_video_bytes`` (same rules, same
    order).
  * ``probe_media_bytes`` — the Range probe, ported from the script's
    ``probe_bytes`` (first 4 KB, ``(ok, total_size_or_None)``).

Offline: no network — the probe tests fake ``urlopen``. No production
DB. The checker has no callers yet (Branch 2 hooks it into the
stage-2 probe, the resolved-cache bank and pool banking); this suite
proves the port before anything depends on it.
"""

from __future__ import annotations

import io
import pathlib
import sys
import unittest
import urllib.error
import urllib.request
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import fast_fetch as ff  # noqa: E402

# Real container heads (first bytes only — the checker never reads past
# the first 512 bytes for the HTML rules, and magic lives in the head).
MKV_HEAD = b"\x1a\x45\xdf\xa3\x42\x82\x84\x01\x02\x03\x04\x05\x06\x07"
MP4_HEAD = b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isomiso2"
AVI_HEAD = b"RIFF\x24\x00\x00\x00AVI LIST\x00\x00\x00\x00"
HLS_HEAD = b"#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n"
HTML_PAGE = (b"<!DOCTYPE html><html><head><title>404 Not Found</title>"
             b"</head><body><h1>Not Found</h1></body></html>")
HTML_NO_DOCTYPE = (b"<html><head><title>Error</title></head>"
                   b"<body>Something went wrong</body></html>")
HTML_TITLE_ONLY = b"\x00\x01\x02\x03<title>Gateway Timeout</title>" + b"x" * 64


class TestVerifyMediaBytes(unittest.TestCase):
    """Table-driven byte sniffing: magic wins, HTML loses, the
    Content-Type is only the first word, never the verdict alone."""

    def test_mkv_ebml_magic_accepted(self):
        self.assertTrue(ff.verify_media_bytes(MKV_HEAD, "video/x-matroska"))
        self.assertTrue(ff.verify_media_bytes(MKV_HEAD, "application/octet-stream"))
        # Magic beats a lying Content-Type: text/* is rejected first.
        self.assertFalse(ff.verify_media_bytes(MKV_HEAD, "text/html"))

    def test_mp4_ftyp_at_offset_4_accepted(self):
        self.assertTrue(ff.verify_media_bytes(MP4_HEAD, "video/mp4"))
        self.assertTrue(ff.verify_media_bytes(MP4_HEAD, "application/mp4"))
        # ftyp needs 8 bytes to be read; a truncated prefix is not magic.
        self.assertFalse(ff.verify_media_bytes(b"\x00\x00\x00\x18fty", ""))

    def test_avi_riff_accepted(self):
        self.assertTrue(ff.verify_media_bytes(AVI_HEAD, "video/avi"))
        # RIFF without the AVI marker at offset 8 is not an AVI.
        self.assertFalse(ff.verify_media_bytes(b"RIFF\x24\x00\x00\x00WAVE", ""))

    def test_hls_manifest_accepted(self):
        self.assertTrue(ff.verify_media_bytes(HLS_HEAD, "application/vnd.apple.mpegurl"))
        self.assertTrue(ff.verify_media_bytes(HLS_HEAD, "application/x-mpegURL"))
        # Leading whitespace is stripped before the #EXTM3U check.
        self.assertTrue(ff.verify_media_bytes(b"  \n" + HLS_HEAD, ""))

    def test_html_page_rejected_whatever_the_type_claims(self):
        # An error page with an honest text/html type.
        self.assertFalse(ff.verify_media_bytes(HTML_PAGE, "text/html"))
        self.assertFalse(ff.verify_media_bytes(HTML_PAGE, "text/html; charset=utf-8"))
        # The same page served with a video-ish type is still HTML: the
        # body check runs regardless of what the server claims.
        self.assertFalse(ff.verify_media_bytes(HTML_PAGE, "video/mp4"))
        self.assertFalse(ff.verify_media_bytes(HTML_PAGE, "application/octet-stream"))
        self.assertFalse(ff.verify_media_bytes(HTML_NO_DOCTYPE, "text/html"))

    def test_title_in_first_512_bytes_rejected(self):
        # No <html> start, but a <title> inside the first 512 bytes.
        self.assertFalse(ff.verify_media_bytes(HTML_TITLE_ONLY, "application/octet-stream"))
    def test_title_past_512_bytes_is_out_of_the_sniff_window(self):
        # A <title> past the first 512 bytes is not seen by the check,
        # so the generic octet-stream accept applies.
        self.assertTrue(ff.verify_media_bytes(
            b"x" * 600 + b"<title>late</title>", "application/octet-stream"))

    def test_html_as_mkv_regression(self):
        """v2.0.0 incident: an HTML bridge/landing page was saved as
        .mkv and reported success. The URL said .mkv; the bytes said
        HTML. The bytes win — this test must fail if the check is
        removed, and it pins the exact shape of the bug: a 200 + HTML
        body behind a video filename.
        """
        # The file the mirror served for "film-720p.mkv".
        self.assertFalse(ff.verify_media_bytes(HTML_PAGE, "text/html"))
        self.assertFalse(ff.verify_media_bytes(HTML_PAGE, "video/x-matroska"))
        # And the same page served with no Content-Type at all.
        self.assertFalse(ff.verify_media_bytes(HTML_PAGE, ""))

    def test_empty_body_rejected(self):
        self.assertFalse(ff.verify_media_bytes(b"", "video/mp4"))
        self.assertFalse(ff.verify_media_bytes(b"", ""))
        self.assertFalse(ff.verify_media_bytes(None, "video/mp4"))

    def test_generic_octet_stream_without_magic_accepted(self):
        # Some hosts serve video with a generic type and no sniffable
        # header; the advertised-size check (Branch 2) is the backstop.
        self.assertTrue(ff.verify_media_bytes(b"\x00\x01\x02\x03" * 64,
                                              "application/octet-stream"))
        self.assertTrue(ff.verify_media_bytes(b"\x00\x01\x02\x03" * 64,
                                              "binary/octet-stream"))

    def test_unknown_type_without_magic_rejected(self):
        self.assertFalse(ff.verify_media_bytes(b"\x00\x01\x02\x03" * 64, ""))
        self.assertFalse(ff.verify_media_bytes(b"\x00\x01\x02\x03" * 64,
                                              "application/json"))

    def test_video_type_without_magic_accepted(self):
        self.assertTrue(ff.verify_media_bytes(b"\x00\x01\x02\x03" * 64, "video/mp4"))
        self.assertTrue(ff.verify_media_bytes(b"\x00\x01\x02\x03" * 64,
                                              "application/x-matroska"))

    def test_xhtml_rejected_like_text(self):
        self.assertFalse(ff.verify_media_bytes(MKV_HEAD, "application/xhtml+xml"))


class _FakeResp:
    """Minimal urlopen response: status, headers, a body, read(n)."""

    def __init__(self, status, headers, body):
        self.status = status
        self.headers = headers
        self._body = io.BytesIO(body)

    def read(self, n=-1):
        return self._body.read(n)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def _fake_urlopen_factory(resp=None, exc=None, seen=None):
    def _open(req, timeout=None):
        if seen is not None:
            seen.append(req)
        if exc is not None:
            raise exc
        return resp
    return _open


class TestProbeMediaBytes(unittest.TestCase):
    """The Range probe, with urlopen faked — no network, ever."""

    def test_206_mkv_returns_ok_and_total(self):
        resp = _FakeResp(206, {"Content-Range": "bytes 0-4095/1432132599",
                               "Content-Type": "video/x-matroska",
                               "Accept-Ranges": "bytes"}, MKV_HEAD)
        seen = []
        with mock.patch("urllib.request.urlopen",
                        side_effect=_fake_urlopen_factory(resp, seen=seen)):
            ok, total = ff.probe_media_bytes("https://cdn.example.com/f.mkv")
        self.assertTrue(ok)
        self.assertEqual(total, 1432132599)
        self.assertEqual(seen[0].get_header("Range"), "bytes=0-4095")

    def test_200_hls_manifest_returns_ok(self):
        resp = _FakeResp(200, {"Content-Type": "application/vnd.apple.mpegurl",
                               "Content-Length": "1180"}, HLS_HEAD)
        with mock.patch("urllib.request.urlopen",
                        side_effect=_fake_urlopen_factory(resp)):
            ok, total = ff.probe_media_bytes("https://cdn.example.com/master.m3u8")
        self.assertTrue(ok)
        self.assertEqual(total, 1180)

    def test_probe_rejects_html_served_at_mkv_url(self):
        """The v2.0.0 incident at the probe level: the mirror answered
        HTTP 200 for a .mkv URL, but the first 4 KB were the site's
        homepage HTML. The probe must say no — the URL ending in .mkv
        proves nothing.
        """
        resp = _FakeResp(200, {"Content-Type": "text/html",
                               "Content-Length": str(len(HTML_PAGE))}, HTML_PAGE)
        with mock.patch("urllib.request.urlopen",
                        side_effect=_fake_urlopen_factory(resp)):
            ok, total = ff.probe_media_bytes(
                "https://mirror.example.com/film-720p.mkv")
        self.assertFalse(ok)
        self.assertEqual(total, len(HTML_PAGE))

    def test_lying_content_type_still_rejected_by_bytes(self):
        resp = _FakeResp(200, {"Content-Type": "video/mp4",
                               "Content-Length": str(len(HTML_PAGE))}, HTML_PAGE)
        with mock.patch("urllib.request.urlopen",
                        side_effect=_fake_urlopen_factory(resp)):
            ok, _total = ff.probe_media_bytes("https://cdn.example.com/f.mp4")
        self.assertFalse(ok)

    def test_non_200_206_status_is_not_video(self):
        resp = _FakeResp(302, {"Content-Type": "video/mp4"}, MKV_HEAD)
        with mock.patch("urllib.request.urlopen",
                        side_effect=_fake_urlopen_factory(resp)):
            ok, total = ff.probe_media_bytes("https://cdn.example.com/f.mkv")
        self.assertFalse(ok)
        self.assertIsNone(total)

    def test_http_error_returns_false_with_total(self):
        err = urllib.error.HTTPError(
            "https://cdn.example.com/f.mkv", 404, "Not Found",
            {"Content-Range": "bytes */987654"}, io.BytesIO(b""))
        with mock.patch("urllib.request.urlopen",
                        side_effect=_fake_urlopen_factory(exc=err)):
            ok, total = ff.probe_media_bytes("https://cdn.example.com/f.mkv")
        self.assertFalse(ok)
        self.assertEqual(total, 987654)

    def test_transport_failure_never_raises(self):
        with mock.patch("urllib.request.urlopen",
                        side_effect=_fake_urlopen_factory(
                            exc=urllib.error.URLError("conn refused"))):
            ok, total = ff.probe_media_bytes("https://cdn.example.com/f.mkv")
        self.assertFalse(ok)
        self.assertIsNone(total)

    def test_garbage_content_length_gives_total_none_not_failure(self):
        resp = _FakeResp(206, {"Content-Range": "",
                               "Content-Length": "not-a-number",
                               "Content-Type": "video/x-matroska"}, MKV_HEAD)
        with mock.patch("urllib.request.urlopen",
                        side_effect=_fake_urlopen_factory(resp)):
            ok, total = ff.probe_media_bytes("https://cdn.example.com/f.mkv")
        self.assertTrue(ok)
        self.assertIsNone(total)

    def test_unreadable_body_is_not_video(self):
        class _BadResp(_FakeResp):
            def read(self, n=-1):
                raise OSError("short read")
        resp = _BadResp(200, {"Content-Type": "video/x-matroska",
                              "Content-Length": "1432132599"}, MKV_HEAD)
        with mock.patch("urllib.request.urlopen",
                        side_effect=_fake_urlopen_factory(resp)):
            ok, total = ff.probe_media_bytes("https://cdn.example.com/f.mkv")
        self.assertFalse(ok)
        self.assertEqual(total, 1432132599)


if __name__ == "__main__":
    unittest.main(verbosity=2)
