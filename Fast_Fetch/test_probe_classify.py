#!/usr/bin/env python3
"""Fast_Fetch/test_probe_classify.py — offline tests for the probe gate.

Covers ``current_hosts.classify_probe`` and ``_video_mime_from_magic``:
a 206 ``application/octet-stream`` on an extension-less URL passes when
the first bytes prove a video container, and still fails ``unverified``
when they do not.

The fixtures are the measured live shape (Phase C lane E, hubcdn,
2026-11-20): hubcdn R2 assets answer 206, ``application/octet-stream``,
EBML magic ``1a45dfa3`` on an extension-less
``pub-*.r2.dev/<hex>`` URL — the gate called them ``unverified`` and the
press failed ``no_playable_source`` until the magic was read. No
network, stdlib only.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

BASE_DIR = Path(__file__).parent.resolve()
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import current_hosts
from current_hosts import FetchResult, classify_probe

R2_URL = ("https://pub-5703108c07374cf4bf82a6a6e2041364.r2.dev/"
          "f67a6cd2ba8d4be51a6e5530be7edb7c")
EBML_BODY = b"\x1a\x45\xdf\xa3\xa3\x42\x86\x81\x01\x42\xf7\x81\x01\x42\xf2\x81" + b"\x00" * 496
MP4_BODY = b"\x00\x00\x00\x18ftypisom" + b"\x00" * 500
AVI_BODY = b"RIFF\x00\x00\x00\x00AVI " + b"\x00" * 500
RANDOM_BODY = b"\x89PNG\r\n\x1a\n" + b"\x00" * 504


def _result(status=206, content_type="application/octet-stream",
            body=RANDOM_BODY, headers=None):
    return FetchResult(
        status=status, content_type=content_type, body=body,
        headers=headers or {"content-range": "bytes 0-511/999872522"},
        final_url=R2_URL)


class MagicSniffTests(unittest.TestCase):
    def test_ebml_is_matroska(self):
        self.assertEqual(
            current_hosts._video_mime_from_magic(EBML_BODY),
            "video/x-matroska")

    def test_ftyp_is_mp4(self):
        self.assertEqual(
            current_hosts._video_mime_from_magic(MP4_BODY), "video/mp4")

    def test_riff_avi_is_msvideo(self):
        self.assertEqual(
            current_hosts._video_mime_from_magic(AVI_BODY),
            "video/x-msvideo")

    def test_random_bytes_are_not_video(self):
        self.assertIsNone(
            current_hosts._video_mime_from_magic(RANDOM_BODY))

    def test_short_and_empty_bodies_do_not_crash(self):
        self.assertIsNone(current_hosts._video_mime_from_magic(b"\x1a\x45"))
        self.assertIsNone(current_hosts._video_mime_from_magic(b""))
        self.assertIsNone(current_hosts._video_mime_from_magic(None))


class ClassifyProbeOctetStreamTests(unittest.TestCase):
    def test_ebml_octet_stream_extensionless_passes(self):
        """The hubcdn R2 shape: 206 octet-stream, EBML magic, no suffix."""
        verdict = classify_probe(_result(body=EBML_BODY), R2_URL)
        self.assertTrue(verdict["ok"], verdict)
        self.assertEqual(verdict["kind"], "file")
        self.assertEqual(verdict["mime"], "video/x-matroska")
        self.assertTrue(verdict["resumable"])
        self.assertIsNone(verdict["failure"])

    def test_ftyp_octet_stream_passes_as_mp4(self):
        verdict = classify_probe(_result(body=MP4_BODY), R2_URL)
        self.assertTrue(verdict["ok"], verdict)
        self.assertEqual(verdict["mime"], "video/mp4")

    def test_avi_octet_stream_passes_as_msvideo(self):
        verdict = classify_probe(_result(body=AVI_BODY), R2_URL)
        self.assertTrue(verdict["ok"], verdict)
        self.assertEqual(verdict["mime"], "video/x-msvideo")

    def test_octet_stream_without_magic_stays_unverified(self):
        verdict = classify_probe(_result(body=RANDOM_BODY), R2_URL)
        self.assertFalse(verdict["ok"])
        self.assertEqual(verdict["failure"], "unverified")

    def test_empty_octet_stream_stays_unverified(self):
        verdict = classify_probe(_result(body=b""), R2_URL)
        self.assertFalse(verdict["ok"])
        self.assertEqual(verdict["failure"], "unverified")

    def test_suffix_rule_still_passes_without_magic(self):
        """The pre-existing rule is preserved: octet-stream + .mkv suffix
        passes even when the bytes prove nothing."""
        verdict = classify_probe(
            _result(body=RANDOM_BODY), "https://cdn.example/film.mkv")
        self.assertTrue(verdict["ok"], verdict)
        self.assertEqual(verdict["kind"], "file")
        self.assertEqual(verdict["mime"], "application/octet-stream")

    def test_video_mime_still_passes_without_magic(self):
        verdict = classify_probe(
            _result(content_type="video/mkv", body=RANDOM_BODY), R2_URL)
        self.assertTrue(verdict["ok"], verdict)
        self.assertEqual(verdict["mime"], "video/mkv")

    def test_html_shell_still_refused(self):
        verdict = classify_probe(
            _result(status=200, content_type="text/html",
                    body=b"<html><body>nope</body></html>"), R2_URL)
        self.assertFalse(verdict["ok"])
        self.assertEqual(verdict["failure"], "html_shell")

    def test_404_is_dead_file_not_unverified(self):
        verdict = classify_probe(_result(status=404, body=b""), R2_URL)
        self.assertFalse(verdict["ok"])
        self.assertEqual(verdict["failure"], "dead_file")

    def test_403_with_magic_is_blocked_not_a_file(self):
        verdict = classify_probe(_result(status=403, body=EBML_BODY), R2_URL)
        self.assertFalse(verdict["ok"])
        self.assertEqual(verdict["failure"], "blocked")

    def test_200_with_magic_and_accept_ranges_is_resumable(self):
        verdict = classify_probe(
            _result(status=200, body=EBML_BODY,
                    headers={"accept-ranges": "bytes"}), R2_URL)
        self.assertTrue(verdict["ok"], verdict)
        self.assertTrue(verdict["resumable"])


class ProbeMediaWiringTests(unittest.TestCase):
    def test_probe_media_accepts_ebml_octet_stream_file(self):
        """The function the resolver actually calls: a Range probe of the
        hubcdn R2 shape returns an ok file verdict."""
        def fake_fetch(url, referer=None, range_bytes=None, **_kw):
            self.assertEqual(range_bytes, (0, 512))
            return FetchResult(
                status=206, content_type="application/octet-stream",
                body=EBML_BODY,
                headers={"content-range": "bytes 0-511/999872522"},
                final_url=url)

        verdict = current_hosts.probe_media(
            R2_URL, kind_hint="file", fetch=fake_fetch)
        self.assertTrue(verdict["ok"], verdict)
        self.assertEqual(verdict["kind"], "file")
        self.assertEqual(verdict["mime"], "video/x-matroska")

    def test_probe_media_refuses_random_octet_stream_file(self):
        def fake_fetch(url, referer=None, range_bytes=None, **_kw):
            return FetchResult(
                status=206, content_type="application/octet-stream",
                body=RANDOM_BODY,
                headers={"content-range": "bytes 0-511/999872522"},
                final_url=url)

        verdict = current_hosts.probe_media(
            R2_URL, kind_hint="file", fetch=fake_fetch)
        self.assertFalse(verdict["ok"])
        self.assertEqual(verdict["failure"], "unverified")


if __name__ == "__main__":
    unittest.main()
