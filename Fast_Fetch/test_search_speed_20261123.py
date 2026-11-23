"""Search-speed tests (2026-11-23, light-speed order).

Two fixes, both behaviour-preserving — the tests assert the behaviour,
not the path:

1. `catalog_api.normalize_text` is memoized. The serve path calls it once
   per row per query (the v2 relevance ORDER BY evaluates it ~4x per
   matching work; suggest scans every title per keystroke), and at ~25
   us/call that was the search bottleneck. The function is pure, so the
   cache cannot change a result.
2. `catalog_api.suggest` asks the FTS index for candidate rows first
   instead of scanning all of `cat_works` per keystroke, then applies the
   exact same prefix test and the exact same order in Python. The tests
   compare `suggest` against a pure-Python reference computed straight
   from `cat_works`, with the index present (FTS path) and dropped
   (scan fallback) — both must agree with the reference.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import catalog_api
import fts_search
from test_catalog_api import CatalogApiTestCase


class SearchSpeedTests(CatalogApiTestCase):
    def setUp(self):
        super().setUp()
        # Tricky titles: diacritic, &, parens (via the default title
        # shape), short, and one adult-flagged row that must stay hidden.
        self.add_post("test", "https://test.example/love/",
                      clean_title="Love", year=2015)
        self.add_post("test", "https://test.example/love-story/",
                      clean_title="Love Story", year=2020)
        self.add_post("test", "https://test.example/lovely-runner/",
                      clean_title="Lovely Runner", year=2024)
        self.add_post("test", "https://test.example/cafe-love/",
                      clean_title="Café Love", year=2022)
        self.add_post("test", "https://test.example/love-and-war/",
                      clean_title="Love & War", year=2019)
        self.add_post("test", "https://test.example/the-love-hypothesis/",
                      clean_title="The Love Hypothesis", year=2023)
        self.add_post("test", "https://test.example/lost/",
                      clean_title="Lost", year=2004)
        self.add_post("test", "https://test.example/love-adult/",
                      clean_title="Love Adult", year=2021, is_18_plus=1)
        self.add_post("test", "https://test.example/salaar/",
                      clean_title="Salaar", year=2023)
        self.add_post("test", "https://test.example/avatar/",
                      clean_title="Avatar", year=2009)
        self.project(rev=2)
        fts_search.rebuild_fts(self.conn)

    def reference_suggest(self, q, limit=10):
        """The old contract, computed straight from `cat_works` in Python:
        public (non-adult) titles whose normalised form starts with the
        normalised query, shortest first, then newest, then lowest id."""
        norm = catalog_api.normalize_text(q)
        rows = self.conn.execute(
            "SELECT id, title, year FROM cat_works WHERE title IS NOT NULL"
            " AND (is_18_plus IS NULL OR is_18_plus = 0)").fetchall()
        keep = []
        for row in rows:
            title_norm = catalog_api.normalize_text(row["title"] or "")
            if title_norm.startswith(norm):
                year = row["year"]
                keep.append((len(title_norm),
                             -(year if isinstance(year, (int, float)) else 0),
                             row["id"]))
        keep.sort()
        return [item[2] for item in keep[:limit]]

    def suggest_ids(self, q):
        return [item["id"] for item in
                catalog_api.suggest(self.conn, q=q, limit=10)["items"]]

    def test_normalize_text_memoized_and_unchanged(self):
        self.assertEqual(catalog_api.normalize_text("Dune (2024)"),
                         "dune 2024")
        self.assertEqual(catalog_api.normalize_text("Café & Bar"),
                         "cafe and bar")
        self.assertEqual(catalog_api.normalize_text("Salaar: Part 1 – Ceasefire"),
                         "salaar part 1 ceasefire")
        self.assertEqual(catalog_api.normalize_text(None), "")
        self.assertTrue(hasattr(catalog_api.normalize_text, "cache_info"))
        probe = "Zqxj Unique Memo Probe 12345"
        before = catalog_api.normalize_text.cache_info()
        catalog_api.normalize_text(probe)
        catalog_api.normalize_text(probe)
        after = catalog_api.normalize_text.cache_info()
        self.assertEqual(after.hits, before.hits + 1)
        self.assertEqual(after.misses, before.misses + 1)

    def test_suggest_matches_reference_with_fts(self):
        for query in ("lo", "lov", "love", "love ", "the", "sa", "av",
                      "aveng", "sala", "caf", "dun"):
            with self.subTest(query=query):
                self.assertEqual(self.suggest_ids(query),
                                 self.reference_suggest(query), query)

    def test_suggest_matches_reference_without_fts(self):
        # The scan fallback answers exactly the same rows when the index
        # is gone (stale/missing FTS must never change a suggestion).
        self.conn.execute("DROP TABLE cat_works_fts")
        self.conn.commit()
        for query in ("lo", "lov", "love", "the", "sa", "av", "caf"):
            with self.subTest(query=query):
                self.assertEqual(self.suggest_ids(query),
                                 self.reference_suggest(query), query)

    def test_suggest_hides_adult_titles(self):
        adult_id = self.conn.execute(
            "SELECT id FROM cat_works WHERE title LIKE '%Love Adult%'"
            ).fetchone()["id"]
        for query in ("lov", "love", "love a"):
            self.assertNotIn(adult_id, self.suggest_ids(query), query)

    def test_suggest_candidate_query_uses_the_fts_index(self):
        plan = self.conn.execute(
            "EXPLAIN QUERY PLAN SELECT w.id FROM cat_works w"
            " JOIN cat_works_fts f ON f.rowid = w.id"
            " WHERE (w.is_18_plus IS NULL OR w.is_18_plus = 0)"
            " AND f.cat_works_fts MATCH '\"lo\"*'").fetchall()
        text = " ".join(str(row[-1]) for row in plan)
        # The FTS table is scanned through its full-text index (not a
        # row-by-row scan of cat_works), and each candidate seeks its
        # work row by primary key.
        self.assertIn("VIRTUAL TABLE INDEX", text)
        self.assertNotIn("SCAN w", text)

    def test_search_still_finds_titles_with_cached_udf(self):
        # The memoized UDF inside SQL: diacritic folding still works,
        # and the relevance order still puts the exact title first.
        # ("love" alone is a pure mood query — a genre browse by design —
        # so the title checks use queries with a literal remainder.)
        found = catalog_api.search(self.conn, q="story")
        self.assertIn("Love Story",
                      {item["title"] for item in found["items"]})
        found = catalog_api.search(self.conn, q="lovely")
        self.assertIn("Lovely Runner",
                      {item["title"] for item in found["items"]})
        found = catalog_api.search(self.conn, q="cafe")
        self.assertIn("Café Love",
                      {item["title"] for item in found["items"]})
        found = catalog_api.search(self.conn, q="salaar")
        self.assertEqual([item["title"] for item in found["items"]],
                         ["Salaar"])


if __name__ == "__main__":
    unittest.main()
