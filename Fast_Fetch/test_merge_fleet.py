#!/usr/bin/env python3
"""Offline unit tests for merge_fleet.py — the fingerprint-based re-merge.

Proves the 2026-11-16 ledger defect is fixed: the fleet used to skip any site
with a `posts_merged` ledger entry forever, so the 08:00 autocrawl synced
30,015 posts and merged 0. A site now re-merges when its staging fingerprint
changed since the recorded one, and skips only when the fingerprint is
unchanged. No network, no live database: all fixtures are temp files.
"""

import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

BASE_DIR = Path(__file__).parent.resolve()
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import fast_fetch
import merge_fleet


def _save_post(url, title, site="testsite"):
    """One post + one release + one source into the fixture staging DB."""
    fast_fetch.save_scraped_data({
        "url": url,
        "slug": url.rsplit("/", 1)[-1],
        "title": title,
        "clean_title": title,
        "year": 2024,
        "content_type": "movie",
        "source_site": site,
        "releases": [{
            "release_title": title,
            "quality": "1080p",
            "source_site": site,
            "sources": [{
                "source_name": "s",
                "service": "svc",
                "final_url": "https://x.example/y.mp4",
            }],
        }],
    })


class FingerprintTests(unittest.TestCase):
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.src = Path(self._td.name) / "testsite.db"
        fast_fetch.DB_PATH = self.src
        fast_fetch.init_db()
        _save_post("https://t.example/a", "Alpha (2024)")

    def tearDown(self):
        self._td.cleanup()

    def test_fingerprint_stable_on_unchanged_db(self):
        self.assertEqual(
            merge_fleet.staging_fingerprint(self.src),
            merge_fleet.staging_fingerprint(self.src))

    def test_fingerprint_changes_on_append(self):
        before = merge_fleet.staging_fingerprint(self.src)
        _save_post("https://t.example/b", "Beta (2024)")
        after = merge_fleet.staging_fingerprint(self.src)
        self.assertNotEqual(before, after)
        self.assertEqual(after["posts"][0], 2)

    def test_fingerprint_changes_on_inplace_edit(self):
        # An in-place edit that flows through the upsert path stamps a new
        # updated_at; the fingerprint must move even though no row is added.
        before = merge_fleet.staging_fingerprint(self.src)
        conn = sqlite3.connect(self.src)
        conn.execute(
            "UPDATE posts SET title = ?, updated_at = '2099-01-01 00:00:00' "
            "WHERE url = ?", ("Alpha REDUX (2024)", "https://t.example/a"))
        conn.commit()
        conn.close()
        after = merge_fleet.staging_fingerprint(self.src)
        self.assertNotEqual(before, after)
        self.assertEqual(after["posts"][0], 1)

    def test_fingerprint_missing_file_returns_none(self):
        self.assertIsNone(
            merge_fleet.staging_fingerprint(Path(self._td.name) / "nope.db"))

    def test_fingerprint_handles_staging_family(self):
        # Older "staging" family: a `sources` table instead of download_sources.
        # The fingerprint must include it, omit the absent table, not crash.
        conn = sqlite3.connect(self.src)
        conn.execute("DROP TABLE download_sources")
        conn.execute("CREATE TABLE sources (id INTEGER PRIMARY KEY, release_id INTEGER)")
        conn.execute("INSERT INTO sources (release_id) VALUES (1)")
        conn.commit()
        conn.close()
        fp = merge_fleet.staging_fingerprint(self.src)
        self.assertIn("sources", fp)
        self.assertNotIn("download_sources", fp)
        self.assertEqual(fp["sources"][0], 1)


class NeedsMergeTests(unittest.TestCase):
    FP = {"size": 10, "posts": [2, 2, "2026-11-16 00:00:00"]}

    def test_no_ledger_entry_merges(self):
        self.assertTrue(merge_fleet._needs_merge({}, self.FP))

    def test_error_entry_retries(self):
        self.assertTrue(merge_fleet._needs_merge({"error": "boom"}, self.FP))

    def test_legacy_entry_merges_once(self):
        # Merged before fingerprints existed: posts_merged but no fingerprint.
        # One catch-up merge, then it gains a fingerprint.
        self.assertTrue(
            merge_fleet._needs_merge({"posts_merged": 5}, self.FP))

    def test_unchanged_fingerprint_skips(self):
        self.assertFalse(merge_fleet._needs_merge(
            {"posts_merged": 5, "staging_fingerprint": self.FP}, self.FP))

    def test_changed_fingerprint_remerges(self):
        self.assertTrue(merge_fleet._needs_merge(
            {"posts_merged": 5,
             "staging_fingerprint": {"size": 9, "posts": [1, 1, "2026-11-15"]}},
            self.FP))

    def test_unreadable_staging_fails_open_to_merge(self):
        self.assertTrue(merge_fleet._needs_merge(
            {"posts_merged": 5, "staging_fingerprint": self.FP}, None))


class FleetSiteListTests(unittest.TestCase):
    """mp4moviez + humtv joined the fleet 2026-11-17: their staging lives under
    `*_staging.db`, never the `<site>.db` default — `mp4moviez.db` has no
    sources table (the merge would raise) and `humtv.db` does not exist.
    A silent drop of either entry would skip the site forever with only a
    "[!] missing" line in the fleet log, so the wiring is pinned here."""

    def test_mp4moviez_and_humtv_are_fleet_sites(self):
        self.assertIn("mp4moviez", merge_fleet.SITES)
        self.assertIn("humtv", merge_fleet.SITES)

    def test_staging_sources_point_at_the_staging_dbs(self):
        self.assertEqual(merge_fleet.SOURCES["mp4moviez"].name,
                         "mp4moviez_staging.db")
        self.assertEqual(merge_fleet.SOURCES["humtv"].name,
                         "humtv_staging.db")

    def test_every_source_entry_names_an_existing_site(self):
        for site in merge_fleet.SOURCES:
            self.assertIn(site, merge_fleet.SITES)


class MainLoopTests(unittest.TestCase):
    """End-to-end through main() with a stubbed merge: skip / re-merge / retry."""

    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.dir = Path(self._td.name)
        self.staging = self.dir / "staging"
        self.staging.mkdir()
        self.src = self.staging / "testsite.db"
        fast_fetch.DB_PATH = self.src
        fast_fetch.init_db()
        _save_post("https://t.example/a", "Alpha (2024)")
        self.target = self.dir / "merged.db"
        self.report = Path(str(self.target) + ".report.json")
        self.calls = []

        def fake_merge(src, target, verbose=False):
            self.calls.append((str(src), str(target)))
            return {"posts_merged": 1, "posts_created": 1,
                    "releases_merged": 1, "sources_merged": 1,
                    "posts_conflicted": 0}

        self._merge_patch = mock.patch.object(
            merge_fleet.merge_catalogs, "merge_site_db", fake_merge)
        self._merge_patch.start()
        self._data_patch = mock.patch.object(
            merge_fleet, "DATA_DIR", self.staging)
        self._data_patch.start()
        self._sites_patch = mock.patch.object(
            merge_fleet, "SITES", ["testsite"])
        self._sites_patch.start()

    def tearDown(self):
        self._sites_patch.stop()
        self._data_patch.stop()
        self._merge_patch.stop()
        self._td.cleanup()

    def _run_main(self):
        with mock.patch.object(sys, "argv", ["merge_fleet.py", str(self.target)]):
            self.assertEqual(merge_fleet.main(), 0)

    def _read_report(self):
        return json.loads(self.report.read_text())

    def test_skip_unchanged_remerge_changed(self):
        self._run_main()
        self.assertEqual(len(self.calls), 1)
        self.assertIn("staging_fingerprint", self._read_report()["testsite"])
        # Second run, nothing changed: skipped, merge not called again.
        self._run_main()
        self.assertEqual(len(self.calls), 1)
        # Append a post: the fingerprint moves, the site re-merges.
        _save_post("https://t.example/b", "Beta (2024)")
        self._run_main()
        self.assertEqual(len(self.calls), 2)

    def test_legacy_entry_merges_once_then_skips(self):
        self._run_main()
        report = self._read_report()
        del report["testsite"]["staging_fingerprint"]
        self.report.write_text(json.dumps(report, indent=2))
        # Catch-up merge (fingerprint recorded again) ...
        self._run_main()
        self.assertEqual(len(self.calls), 2)
        # ... then unchanged skips.
        self._run_main()
        self.assertEqual(len(self.calls), 2)

    def test_failed_merge_retries_next_cycle(self):
        self._run_main()
        self.assertEqual(len(self.calls), 1)
        # Staging changed, so the fleet attempts the merge — and it fails.
        _save_post("https://t.example/b", "Beta (2024)")
        with mock.patch.object(
                merge_fleet.merge_catalogs, "merge_site_db",
                side_effect=RuntimeError("boom")):
            self._run_main()
        entry = self._read_report()["testsite"]
        self.assertIn("error", entry)
        self.assertNotIn("staging_fingerprint", entry)
        # Next cycle retries (and succeeds) instead of skipping forever.
        self._run_main()
        self.assertEqual(len(self.calls), 2)


if __name__ == "__main__":
    unittest.main()
