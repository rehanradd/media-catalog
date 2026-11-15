"""Tests for title_clean.group_work_rows (Session 152).

The grouping is shared by v1 (`viewer._dedupe_search_page`,
`_dedupe_page_rows`) and v2 (`catalog_api._dedupe_search_items`), so its
contract is pinned here, not in either surface's suite: same token core in
the same family folds, years link transitively within ±1, a row with no
year joins any cluster, and a remake twenty years later keeps its own
group — including when the titles are IDENTICAL (the 2026-11-15 fix: the
year split used to run only on the subset merge between DIFFERENT cores,
so identical-title remakes folded).
"""

import unittest

import title_clean


def _cand(cid, title, year, content_type="movie", release_count=1):
    return {"id": cid, "title": title, "year": year,
            "content_type": content_type,
            "release_count": release_count, "source_count": 0}


def _groups(cands):
    return [[c["id"] for c in g] for g in title_clean.group_work_rows(cands)]


class GroupWorkRowsTests(unittest.TestCase):

    def test_identical_title_remakes_keep_their_own_groups(self):
        groups = _groups([
            _cand(1, "Dune", 1984),
            _cand(2, "Dune", 2021),
            _cand(3, "Dune", 2024, release_count=2),
        ])
        self.assertEqual(sorted(map(sorted, groups)), [[1], [2], [3]])

    def test_split_film_folds_when_years_are_within_one(self):
        groups = _groups([
            _cand(1, "Salaar", 2023),
            _cand(2, "Salaar - Part 1 Ceasefire", 2024, release_count=2),
            _cand(3, "Salaar Cease Fire", 2023),
        ])
        self.assertEqual(groups, [[2, 3, 1]])
        rep = title_clean.representative(
            title_clean.group_work_rows([
                _cand(1, "Salaar", 2023),
                _cand(2, "Salaar - Part 1 Ceasefire", 2024, release_count=2),
                _cand(3, "Salaar Cease Fire", 2023),
            ])[0])
        self.assertEqual(rep["id"], 2)

    def test_years_link_transitively(self):
        groups = _groups([
            _cand(1, "Same Film", 2020),
            _cand(2, "Same Film", 2021),
            _cand(3, "Same Film", 2022),
        ])
        self.assertEqual(groups, [[1, 2, 3]])

    def test_a_row_with_no_year_joins_any_cluster(self):
        groups = _groups([
            _cand(1, "Same Film", None),
            _cand(2, "Same Film", 2030),
        ])
        self.assertEqual(groups, [[1, 2]])

    def test_movie_and_series_never_fold_together(self):
        groups = _groups([
            _cand(1, "Nova", 2019, content_type="movie"),
            _cand(2, "Nova", 2019, content_type="series"),
        ])
        self.assertEqual(sorted(map(sorted, groups)), [[1], [2]])

    def test_subset_core_still_folds_only_when_years_agree(self):
        # "Avatar" never joins "Avatar: The Way of Water" — different film,
        # and the years differ by more than one.
        groups = _groups([
            _cand(1, "Avatar", 2009),
            _cand(2, "Avatar The Way of Water", 2022),
        ])
        self.assertEqual(sorted(map(sorted, groups)), [[1], [2]])


if __name__ == "__main__":
    unittest.main(verbosity=2)
