"""Over-cap search dedupe: a broad query must not show duplicate prints.

Regression test for the `_SEARCH_DEDUPE_CAP` skip (viewer.py): a search
matching more than 1000 rows used to skip dedupe entirely and serve
duplicate prints of the same work (live: ``q=love`` matched 6,295 rows
with 653 duplicate prints, 10.6%). The windowed path dedupes the top of
the order instead, so every served page holds one row per work.

Fixture-based, no live DB: the fixture inserts duplicate prints directly
with SQL — ``save_scraped_data`` deliberately files the same film from
several sites as ONE post (canonical key), so it cannot build a
duplicate-print fixture. 240 works x 5 prints = 1200 matching rows.
"""

import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fast_fetch
import viewer

WORKS = 240
PRINTS_PER_WORK = 5
TOTAL_ROWS = WORKS * PRINTS_PER_WORK  # 1200, over the 1000 cap
QUERY = "lovetest"


def _seed_fixture(db_path):
    """240 works, each filed 5 times (distinct URLs, same title/year)."""
    conn = sqlite3.connect(db_path)
    try:
        posts = []
        releases = []
        sources = []
        for work in range(WORKS):
            title = f"Lovetest {work:03d} (2021)"
            clean = f"Lovetest {work:03d}"
            for p in range(PRINTS_PER_WORK):
                posts.append((
                    f"https://site-{p}.example/lovetest-{work:03d}/",
                    f"lovetest-{work:03d}-{p}",
                    title,
                    clean,
                    2021,
                    "movie",
                ))
        conn.executemany(
            "INSERT INTO posts (url, slug, title, clean_title, year,"
            " content_type) VALUES (?, ?, ?, ?, ?, ?)",
            posts)
        post_ids = [r[0] for r in conn.execute(
            "SELECT id FROM posts ORDER BY id").fetchall()]
        assert len(post_ids) == TOTAL_ROWS
        for post_id in post_ids:
            releases.append((post_id, "Lovetest 720p", "720p"))
        conn.executemany(
            "INSERT INTO releases (post_id, release_title, quality)"
            " VALUES (?, ?, ?)",
            releases)
        release_ids = [r[0] for r in conn.execute(
            "SELECT id FROM releases ORDER BY id").fetchall()]
        for release_id in release_ids:
            sources.append((release_id, "Example CDN", "test-service"))
        conn.executemany(
            "INSERT INTO download_sources (release_id, source_name, service)"
            " VALUES (?, ?, ?)",
            sources)
        conn.commit()
    finally:
        conn.close()


def _clean_titles(conn, ids):
    marks = ",".join("?" * len(ids))
    rows = conn.execute(
        f"SELECT clean_title FROM posts WHERE id IN ({marks})",
        list(ids)).fetchall()
    return [r[0] for r in rows]


class DedupeCapSearchTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test.db"
        self.db_path_patch = patch.object(fast_fetch, "DB_PATH", self.db_path)
        self.db_path_patch.start()
        fast_fetch.init_db()
        _seed_fixture(self.db_path)
        self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row

    def tearDown(self):
        self.conn.close()
        self.db_path_patch.stop()
        self.temp_dir.cleanup()

    def windowed(self, **kwargs):
        args = dict(conn=self.conn,
                    where_sql="p.clean_title LIKE ?",
                    params=["%lovetest%"],
                    step1_order="p.id DESC",
                    page=1, limit=10, total_rows=TOTAL_ROWS)
        args.update(kwargs)
        return viewer._dedupe_search_page_windowed(**args)

    def test_windowed_page_one_has_no_duplicates(self):
        page_ids, total, has_more, last_rep = self.windowed()
        self.assertEqual(len(page_ids), 10)
        self.assertEqual(len(set(page_ids)), 10)
        titles = _clean_titles(self.conn, page_ids)
        self.assertEqual(len(set(titles)), 10,
                         f"duplicate prints on the page: {titles}")
        self.assertEqual(total, TOTAL_ROWS)
        self.assertTrue(has_more)
        self.assertIsNotNone(last_rep)

    def test_windowed_offset_pages_are_disjoint_and_deduped(self):
        first, _, _, _ = self.windowed(limit=20)
        second, _, _, _ = self.windowed(page=2, limit=20)
        self.assertEqual(len(first), 20)
        self.assertEqual(len(second), 20)
        self.assertEqual(len(set(first) & set(second)), 0,
                         "a work repeats across pages")
        titles = _clean_titles(self.conn, first + second)
        self.assertEqual(len(set(titles)), 40,
                         "a work repeats across pages")

    def test_windowed_cursor_page_follows(self):
        first, _, _, last_rep = self.windowed(limit=10)
        second, _, has_more, _ = self.windowed(
            limit=10, after_id=int(last_rep["id"]))
        self.assertEqual(len(second), 10)
        self.assertEqual(len(set(first) & set(second)), 0)
        titles = _clean_titles(self.conn, first + second)
        self.assertEqual(len(set(titles)), 20)
        self.assertTrue(has_more)

    def test_windowed_cursor_past_window_answers_empty(self):
        # The oldest row sits past the 1000-row window (order is id DESC),
        # so its group is unreachable — the empty page, like a group a
        # merge re-filed, never a wrong page.
        oldest = self.conn.execute(
            "SELECT id FROM posts ORDER BY id ASC LIMIT 1").fetchone()[0]
        page_ids, total, has_more, last_rep = self.windowed(
            limit=10, after_id=int(oldest))
        self.assertEqual(page_ids, [])
        self.assertEqual(total, TOTAL_ROWS)
        self.assertFalse(has_more)
        self.assertIsNone(last_rep)

    def test_broad_search_end_to_end_has_no_duplicates(self):
        with patch.object(viewer, "DB_PATH", self.db_path):
            resp = viewer.api_v1_get_posts(query=QUERY, limit=10)
        results = resp["results"]
        self.assertEqual(len(results), 10)
        titles = [r["clean_title"] for r in results]
        self.assertEqual(len(set(titles)), 10,
                         f"duplicate prints in search results: {titles}")
        self.assertEqual(resp["total"], TOTAL_ROWS)
        self.assertTrue(resp["has_more"])
        self.assertIsNotNone(resp["next_cursor"])
        # Page 2 (offset) and the cursor page both continue without
        # repeating a work from page 1 or each other.
        with patch.object(viewer, "DB_PATH", self.db_path):
            page2 = viewer.api_v1_get_posts(query=QUERY, limit=10, page=2)
            cursor_page = viewer.api_v1_get_posts(
                query=QUERY, limit=10, cursor=resp["next_cursor"])
        first_ids = {r["id"] for r in results}
        for other in (page2["results"], cursor_page["results"]):
            self.assertEqual(len(other), 10)
            other_titles = [r["clean_title"] for r in other]
            self.assertEqual(len(set(other_titles)), 10)
            self.assertEqual(
                len({r["id"] for r in other} & first_ids), 0,
                "a work repeats across pages")
            self.assertEqual(
                len(set(other_titles) & set(titles)), 0,
                "a work repeats across pages")

    def test_under_cap_search_still_exact(self):
        # 5 prints of one work, under the cap: the exact path still
        # counts works, not rows.
        with patch.object(viewer, "DB_PATH", self.db_path):
            resp = viewer.api_v1_get_posts(query="lovetest 007", limit=10)
        self.assertEqual(len(resp["results"]), 1)
        self.assertEqual(resp["results"][0]["clean_title"], "Lovetest 007")
        self.assertEqual(resp["total"], 1)


if __name__ == "__main__":
    unittest.main()
