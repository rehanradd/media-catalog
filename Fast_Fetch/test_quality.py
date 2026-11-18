#!/usr/bin/env python3
"""Tests for quality.normalize_quality (DB checkup fix #6).

The ground truth is the checkup's fragmentation table: ``720p`` vs ``720P``,
bare ``HD``, ``full hd``/``fhd`` spellings, and empty strings. The table pins
the *rule*, not the catalog: every row the backfill touches must come out of
this function, and every row the ingest path writes must go through it.
"""

from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from quality import normalize_quality


class NormalizeQualityTests(unittest.TestCase):
    def test_none_and_blank_are_null(self):
        self.assertIsNone(normalize_quality(None))
        self.assertIsNone(normalize_quality(""))
        self.assertIsNone(normalize_quality("   "))
        self.assertIsNone(normalize_quality("\t\n"))

    def test_case_and_whitespace_collapse(self):
        self.assertEqual(normalize_quality("720P"), "720p")
        self.assertEqual(normalize_quality(" 1080p "), "1080p")
        self.assertEqual(normalize_quality("2160P"), "2160p")
        self.assertEqual(normalize_quality("4K"), "4k")

    def test_bare_hd_is_720p(self):
        self.assertEqual(normalize_quality("HD"), "720p")
        self.assertEqual(normalize_quality("hd"), "720p")
        self.assertEqual(normalize_quality("  Hd "), "720p")

    def test_full_hd_spellings_are_1080p(self):
        self.assertEqual(normalize_quality("Full HD"), "1080p")
        self.assertEqual(normalize_quality("FULL HD"), "1080p")
        self.assertEqual(normalize_quality("full-hd"), "1080p")
        self.assertEqual(normalize_quality("FHD"), "1080p")
        self.assertEqual(normalize_quality("fhd"), "1080p")

    def test_other_labels_keep_lowercased_spelling(self):
        self.assertEqual(normalize_quality("CAM"), "cam")
        self.assertEqual(normalize_quality("HLS"), "hls")
        self.assertEqual(normalize_quality("WEB-DL"), "web-dl")
        self.assertEqual(normalize_quality("WEBRip"), "webrip")
        self.assertEqual(normalize_quality("HDRip"), "hdrip")
        # "HDTC" is a telecine tag, not a bare HD claim — it stays itself.
        self.assertEqual(normalize_quality("HDTC"), "hdtc")
        self.assertEqual(normalize_quality("Pre-DVD"), "pre-dvd")

    def test_already_canonical_is_unchanged(self):
        for value in ("720p", "480p", "1080p", "360p", "240p", "4k", "2160p"):
            self.assertEqual(normalize_quality(value), value)

    def test_non_string_is_stringified_then_normalized(self):
        self.assertEqual(normalize_quality(720), "720")


if __name__ == "__main__":
    unittest.main()
