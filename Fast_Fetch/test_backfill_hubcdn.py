#!/usr/bin/env python3
"""Offline tests for backfill_hubcdn.py (Phase C lane E, 2026-11-20).

The fixture is one row per shape the live catalog actually holds: a happy
bridge, a Google-only bridge (no R2 copy), an unwrap failure, a gate-probe
failure, an already-cracked row, a foreign-service row carrying a cloud
page, a NULL ``final_url``, a bridge with a stale failure record and a set
expiry, an unwrap that raises, and a "Preparing Download" bridge routed
to filesdl. The network edges are injected fakes, so
no test can knock on a third party; the persist shape is asserted column by
column against what ``resolve_live_source`` writes for the same press.

    python3 -S Fast_Fetch/test_backfill_hubcdn.py
"""
from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import backfill_hubcdn as bh

CLOUD = "https://hubcdn.io/cloud/{}"
R2 = "https://pub-x123.r2.dev/{}"
GOOGLE = "https://video-downloads.googleusercontent.com/{}"

COLUMNS = ("id, service, source_url, final_url, status, is_temporary,"
           " last_checked_at, updated_at, last_failed_at, failure_class,"
           " mime_type, http_status, is_resumable, expires_at")

#: (id, service, source_url, final_url, status, is_temporary,
#:  last_checked_at, updated_at, last_failed_at, failure_class,
#:  mime_type, http_status, is_resumable, expires_at)
FIXTURE = [
    (1, "hubcdn", CLOUD.format("AAA"), CLOUD.format("AAA"),
     "active", 0, None, None, None, None, None, None, None, None),
    (2, "hubcdn", CLOUD.format("BBB"), CLOUD.format("BBB"),
     "active", 0, None, None, None, None, None, None, None, None),
    (3, "hubcdn", CLOUD.format("CCC"), CLOUD.format("CCC"),
     "active", 0, None, None, None, None, None, None, None, None),
    (4, "hubcdn", CLOUD.format("DDD"), CLOUD.format("DDD"),
     "active", 0, None, None, None, None, None, None, None, None),
    (5, "hubcdn", CLOUD.format("EEE"), R2.format("direct"),
     "active", 0, "2026-11-19 13:05:57", None, None, None,
     "video/mkv", 206, 1, None),
    (6, "gdflix", "https://gdflix.example/file/1", CLOUD.format("FFF"),
     "active", 0, None, None, None, None, None, None, None, None),
    (7, "hubcdn", CLOUD.format("GGG"), None,
     "active", 0, None, None, None, None, None, None, None, None),
    (8, "hubcdn", CLOUD.format("HHH"), CLOUD.format("HHH"),
     "active", 0, None, None, 1730000000, "unwrap_failed",
     None, None, None, 1730000000 + 3600),
    (9, "hubcdn", CLOUD.format("III"), CLOUD.format("III"),
     "active", 0, None, None, None, None, None, None, None, None),
    (10, "hubcdn", CLOUD.format("JJJ"), CLOUD.format("JJJ"),
     "active", 0, None, None, None, None, None, None, None, None),
    (11, "hubcdn", CLOUD.format("KKK"), CLOUD.format("KKK"),
     "active", 0, None, None, None, None, None, None, None, None),
]


def fake_unwrap(url, timeout=8):
    """The live answers, keyed by the stored cloud id."""
    key = url.rsplit("/", 1)[-1]
    if key == "AAA":
        return {"ok": True, "r2_url": R2.format("a"),
                "google_url": GOOGLE.format("a")}
    if key == "BBB":
        return {"ok": True, "r2_url": None,
                "google_url": GOOGLE.format("b")}
    if key == "CCC":
        return {"ok": False, "error": "no_dl_trigger_buttons"}
    if key == "DDD":
        return {"ok": True, "r2_url": R2.format("d"),
                "google_url": GOOGLE.format("d")}
    if key == "HHH":
        return {"ok": True, "r2_url": R2.format("h"),
                "google_url": GOOGLE.format("h")}
    if key == "III":
        raise RuntimeError("gateway timed out")
    if key == "JJJ":
        return {"ok": False, "error": "preparing_download",
                "preparing_target": "https://new1.filesdl.in/drive/xyz"}
    if key == "KKK":
        return {"ok": True,
                "r2_url": "https://pub-5703108c07374cf4bf82a6a6e2041364.r2.dev/k",
                "google_url": GOOGLE.format("k")}
    raise AssertionError(f"unexpected unwrap of {url}")


def fake_probe(url):
    """The Stage-2 gate: everything proves except row 4's asset."""
    if url == R2.format("d"):
        return None
    return {"ok": True, "kind": "file", "mime": "video/x-matroska",
            "resumable": True}


class BackfillHubcdnTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "catalog.db"
        with sqlite3.connect(self.db) as conn:
            conn.execute(
                "CREATE TABLE download_sources (id INTEGER PRIMARY KEY,"
                " service TEXT, source_url TEXT, final_url TEXT,"
                " status TEXT, is_temporary INTEGER, last_checked_at TEXT,"
                " updated_at TEXT, last_failed_at REAL, failure_class TEXT,"
                " mime_type TEXT, http_status INTEGER, is_resumable INTEGER,"
                " expires_at REAL)")
            conn.executemany(
                f"INSERT INTO download_sources ({COLUMNS})"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", FIXTURE)

    def tearDown(self):
        self.tmp.cleanup()

    def row(self, row_id):
        with sqlite3.connect(self.db) as conn:
            return dict(zip(
                ("id", "service", "source_url", "final_url", "status",
                 "is_temporary", "last_checked_at", "updated_at",
                 "last_failed_at", "failure_class", "mime_type",
                 "http_status", "is_resumable", "expires_at"),
                conn.execute(
                    "SELECT * FROM download_sources WHERE id = ?",
                    (row_id,)).fetchone()))

    def test_report_counts_bridges_without_network_or_writes(self):
        calls = []

        def counting_unwrap(url, timeout=8):
            calls.append(url)
            return fake_unwrap(url, timeout)

        stats = bh.backfill(self.db, apply=False, delay=0.0,
                            unwrap=counting_unwrap, probe=fake_probe)
        self.assertEqual(calls, [])
        self.assertEqual(stats["scanned"], 8)  # rows 1, 2, 3, 4, 8, 9, 10, 11
        self.assertEqual(stats["rewritten"], 0)
        self.assertEqual(stats["skipped_direct"], 1)  # row 5 only
        self.assertEqual(self.row(1)["final_url"], CLOUD.format("AAA"))

    def test_apply_rewrites_happy_rows_in_resolve_shape(self):
        stats = bh.backfill(self.db, apply=True, delay=0.0,
                            unwrap=fake_unwrap, probe=fake_probe)
        self.assertEqual(stats, {
            "scanned": 8, "rewritten": 3, "google_only": 1,
            "unwrap_failed": 2, "probe_failed": 1, "preparing": 1,
            "skipped_direct": 1, "projection_mirrored": 0})
        first = self.row(1)
        self.assertEqual(first["final_url"], R2.format("a"))
        self.assertEqual(first["status"], "active")
        # Row 1's bucket (pub-x123) is unproven, so it banks temporary.
        self.assertEqual(first["is_temporary"], 1)
        self.assertEqual(first["mime_type"], "video/x-matroska")
        self.assertEqual(first["http_status"], 206)
        self.assertEqual(first["is_resumable"], 1)
        self.assertIsNotNone(first["last_checked_at"])
        self.assertIsNone(first["last_failed_at"])
        self.assertIsNone(first["failure_class"])
        self.assertIsNone(first["expires_at"])
        # Row 11's bucket is the measured-proven one: permanent.
        eleventh = self.row(11)
        self.assertEqual(
            eleventh["final_url"],
            "https://pub-5703108c07374cf4bf82a6a6e2041364.r2.dev/k")
        self.assertEqual(eleventh["is_temporary"], 0)

    def test_apply_banks_fresh_bucket_temporary_and_proven_permanent(self):
        bh.backfill(self.db, apply=True, delay=0.0,
                    unwrap=fake_unwrap, probe=fake_probe)
        self.assertEqual(self.row(1)["is_temporary"], 1)  # pub-x123, unproven
        self.assertEqual(self.row(11)["is_temporary"], 0)  # proven bucket

    def test_apply_clears_stale_failure_and_expiry(self):
        bh.backfill(self.db, apply=True, delay=0.0,
                    unwrap=fake_unwrap, probe=fake_probe)
        eighth = self.row(8)
        self.assertEqual(eighth["final_url"], R2.format("h"))
        self.assertIsNone(eighth["last_failed_at"])
        self.assertIsNone(eighth["failure_class"])
        self.assertIsNone(eighth["expires_at"])

    def test_google_only_and_failed_rows_are_left_alone(self):
        bh.backfill(self.db, apply=True, delay=0.0,
                    unwrap=fake_unwrap, probe=fake_probe)
        for row_id, cloud_id in ((2, "BBB"), (3, "CCC"), (4, "DDD"),
                                 (9, "III"), (10, "JJJ")):
            self.assertEqual(self.row(row_id)["final_url"],
                             CLOUD.format(cloud_id))

    def test_preparing_rows_are_counted_separately(self):
        stats = bh.backfill(self.db, apply=True, delay=0.0,
                            unwrap=fake_unwrap, probe=fake_probe)
        self.assertEqual(stats["preparing"], 1)
        self.assertEqual(self.row(10)["final_url"], CLOUD.format("JJJ"))

    def test_selection_ignores_foreign_service_null_final_and_direct(self):
        bh.backfill(self.db, apply=True, delay=0.0,
                    unwrap=fake_unwrap, probe=fake_probe)
        self.assertEqual(self.row(5)["final_url"], R2.format("direct"))
        self.assertEqual(self.row(6)["final_url"], CLOUD.format("FFF"))
        self.assertIsNone(self.row(7)["final_url"])

    def test_limit_caps_the_work(self):
        stats = bh.backfill(self.db, apply=True, limit=1, delay=0.0,
                            unwrap=fake_unwrap, probe=fake_probe)
        self.assertEqual(stats["scanned"], 1)
        self.assertEqual(stats["rewritten"], 1)
        self.assertEqual(self.row(1)["final_url"], R2.format("a"))
        self.assertEqual(self.row(8)["final_url"], CLOUD.format("HHH"))

    def test_projection_mirror_clears_the_published_twin(self):
        with sqlite3.connect(self.db) as conn:
            conn.execute("CREATE TABLE cat_links (ledger_link_id INTEGER,"
                         " expires_at REAL)")
            conn.execute("INSERT INTO cat_links VALUES (?, ?)",
                         (1, 1730000000 + 3600))
            conn.execute("INSERT INTO cat_links VALUES (?, ?)",
                         (2, 1730000000 + 3600))
        stats = bh.backfill(self.db, apply=True, delay=0.0,
                            unwrap=fake_unwrap, probe=fake_probe)
        self.assertEqual(stats["projection_mirrored"], 1)  # row 1 only
        with sqlite3.connect(self.db) as conn:
            got = dict(conn.execute(
                "SELECT ledger_link_id, expires_at FROM cat_links").fetchall())
        self.assertIsNone(got[1])
        self.assertEqual(got[2], 1730000000 + 3600)  # untouched: not rewritten

    def test_main_report_exits_zero_without_writing(self):
        self.assertEqual(bh.main([str(self.db)]), 0)
        self.assertEqual(self.row(1)["final_url"], CLOUD.format("AAA"))

    def test_main_missing_database_exits_two(self):
        self.assertEqual(bh.main([str(Path(self.tmp.name) / "nope.db")]), 2)


if __name__ == "__main__":
    unittest.main()
