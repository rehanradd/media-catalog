#!/usr/bin/env python3
"""Offline tests for `simkl_enricher.py` (pure extraction, no network).

Nothing here opens a socket. The payloads are the real shapes returned
by the Simkl summary endpoints, captured live 2026-11-20 (The Matrix
``/movies/tt0133093`` and Breaking Bad ``/tv/tt0903747``). The tests pin
the decisions that can quietly poison the catalog if they drift:

* a list-shaped answer resolves to its first dict element (the probe
  crashed on this shape once);
* image URLs use the verified suffixes — poster ``_m.jpg``, fanart
  ``_medium.jpg`` (fanart ``_m.jpg`` 301s to an alert placeholder);
* the IMDb rating wins over the Simkl score, and a 0 / out-of-range
  value is not a score;
* the movie payload's ``director`` string is extracted when present —
  blank or non-string values are not a director;
* a missing field stays absent — nothing is ever invented.
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import simkl_enricher as se

MATRIX = {
    "title": "The Matrix", "year": 1999, "runtime": 136,
    "overview": "Set in the 22nd century, a hacker joins the rebellion.",
    "director": "Lana Wachowski",
    "genres": ["Action", "Science Fiction"],
    "ratings": {"simkl": {"rating": 8.6, "votes": 13222},
                "imdb": {"rating": 8.7, "votes": 2295151}},
    "poster": "80/8008234e702d8ed33",
    "fanart": "12/12560269d5c490d440",
    "ids": {"simkl": 53992, "slug": "the-matrix", "tmdb": "603",
            "imdb": "tt0133093", "tvdb": "169"},
}

BREAKING_BAD = {
    "title": "Breaking Bad", "year": 2008, "runtime": 47,
    "overview": "A chemistry teacher turns to crime.",
    "genres": ["Crime", "Drama", "Thriller", "Western"],
    "ratings": {"simkl": {"rating": 9.2, "votes": 15706},
                "imdb": {"rating": 9.5, "votes": 2706877}},
    "poster": "97/978264e8bbc2303",
    "fanart": "97/97857fa360b6cc6",
    "ids": {"simkl": 11121, "slug": "breaking-bad", "tvdb": "81189",
            "imdb": "tt0903747", "tmdb": "1396"},
}


class EndpointTests(unittest.TestCase):
    def test_kinds(self):
        self.assertEqual(se.endpoint_for(), "movies")
        self.assertEqual(se.endpoint_for(series=True), "tv")
        self.assertEqual(se.endpoint_for(anime=True), "anime")
        self.assertEqual(se.endpoint_for(series=True, anime=True), "anime")

    def test_lookup_url_shape(self):
        url = se.lookup_url("KEY123", "movies", "tt0133093")
        self.assertTrue(url.startswith("https://api.simkl.com/movies/tt0133093?"))
        self.assertIn("client_id=KEY123", url)
        self.assertIn("app-name=radd-stream", url)
        self.assertIn("app-version=1.0", url)


class NormalizePayloadTests(unittest.TestCase):
    def test_dict_passes_through(self):
        self.assertIs(se.normalize_payload(MATRIX), MATRIX)

    def test_list_takes_first_dict(self):
        self.assertEqual(se.normalize_payload([MATRIX, BREAKING_BAD]), MATRIX)
        self.assertTrue(se.is_list_response([MATRIX]))
        self.assertFalse(se.is_list_response(MATRIX))

    def test_list_skips_non_dicts(self):
        self.assertEqual(se.normalize_payload(["junk", 42, MATRIX]), MATRIX)

    def test_no_dict_means_no_record(self):
        self.assertIsNone(se.normalize_payload([]))
        self.assertIsNone(se.normalize_payload(["junk", 42]))
        self.assertIsNone(se.normalize_payload("junk"))
        self.assertIsNone(se.normalize_payload(None))
        self.assertIsNone(se.normalize_payload(42))


class ImageUrlTests(unittest.TestCase):
    def test_poster_medium(self):
        self.assertEqual(
            se.poster_url("80/8008234e702d8ed33"),
            "https://simkl.in/posters/80/8008234e702d8ed33_m.jpg")

    def test_fanart_medium_1920(self):
        self.assertEqual(
            se.fanart_url("12/12560269d5c490d440"),
            "https://simkl.in/fanart/12/12560269d5c490d440_medium.jpg")

    def test_blank_paths_are_no_image(self):
        for bad in (None, "", "   ", " / "):
            self.assertIsNone(se.poster_url(bad))
            self.assertIsNone(se.fanart_url(bad))

    def test_full_url_passes_through(self):
        url = "https://simkl.in/posters/80/8008234e702d8ed33_m.jpg"
        self.assertEqual(se.poster_url(url), url)


class BestRatingTests(unittest.TestCase):
    def test_imdb_wins(self):
        value, kind = se.best_rating(MATRIX["ratings"])
        self.assertEqual((value, kind), (8.7, "imdb"))

    def test_simkl_score_is_the_fallback(self):
        value, kind = se.best_rating({"simkl": {"rating": 7.5, "votes": 3}})
        self.assertEqual((value, kind), (7.5, "simkl"))

    def test_zero_and_out_of_range_are_not_scores(self):
        self.assertEqual(se.best_rating({"imdb": {"rating": 0}}), (None, None))
        self.assertEqual(se.best_rating({"imdb": {"rating": 11}}), (None, None))
        self.assertEqual(se.best_rating({"imdb": {"rating": "8.7"}}), (None, None))
        self.assertEqual(se.best_rating({"imdb": {"rating": True}}), (None, None))

    def test_missing_ratings(self):
        self.assertEqual(se.best_rating(None), (None, None))
        self.assertEqual(se.best_rating({}), (None, None))
        self.assertEqual(se.best_rating({"imdb": {}}), (None, None))


class ExtractMetadataTests(unittest.TestCase):
    def test_matrix_full_shape(self):
        out = se.extract_metadata(MATRIX)
        self.assertEqual(out["title"], "The Matrix")
        self.assertEqual(out["year"], 1999)
        self.assertEqual(out["runtime"], 136)
        self.assertEqual(out["overview"], MATRIX["overview"])
        self.assertEqual(out["director"], "Lana Wachowski")
        self.assertEqual(out["genres"], ["Action", "Science Fiction"])
        self.assertEqual(out["rating"], 8.7)
        self.assertEqual(out["rating_kind"], "imdb")
        self.assertEqual(out["poster"],
                         "https://simkl.in/posters/80/8008234e702d8ed33_m.jpg")
        self.assertEqual(out["fanart"],
                         "https://simkl.in/fanart/12/12560269d5c490d440_medium.jpg")
        self.assertEqual(out["tmdb_id"], "603")
        self.assertEqual(out["imdb_id"], "tt0133093")

    def test_breaking_bad_tv_shape(self):
        out = se.extract_metadata(BREAKING_BAD)
        self.assertEqual(out["title"], "Breaking Bad")
        self.assertEqual(out["year"], 2008)
        self.assertEqual(out["runtime"], 47)
        self.assertEqual(out["rating"], 9.5)
        self.assertEqual(out["tmdb_id"], "1396")

    def test_list_payload_is_extracted(self):
        out = se.extract_metadata([MATRIX])
        self.assertEqual(out["title"], "The Matrix")
        self.assertEqual(out["rating"], 8.7)

    def test_empty_payload_is_empty(self):
        self.assertEqual(se.extract_metadata({}), {})
        self.assertEqual(se.extract_metadata([]), {})
        self.assertEqual(se.extract_metadata(None), {})

    def test_missing_fields_stay_absent(self):
        out = se.extract_metadata({"title": "Unknown Film", "year": 2021})
        self.assertEqual(out, {"title": "Unknown Film", "year": 2021})

    def test_simkl_only_rating_is_marked_simkl(self):
        out = se.extract_metadata({
            "title": "Obscure", "year": 2019,
            "ratings": {"simkl": {"rating": 6.4, "votes": 12}}})
        self.assertEqual((out["rating"], out["rating_kind"]), (6.4, "simkl"))

    def test_blank_and_non_string_director_are_dropped(self):
        out = se.extract_metadata({"title": "X", "director": "   "})
        self.assertNotIn("director", out)
        out = se.extract_metadata({"title": "X", "director": ["A", "B"]})
        self.assertNotIn("director", out)
        out = se.extract_metadata({"title": "X", "director": None})
        self.assertNotIn("director", out)

    def test_bad_year_and_runtime_are_dropped(self):
        out = se.extract_metadata({"title": "X", "year": 1066, "runtime": 0})
        self.assertEqual(out, {"title": "X"})
        out = se.extract_metadata({"title": "X", "year": "not-a-year",
                                   "runtime": "long"})
        self.assertEqual(out, {"title": "X"})

    def test_blank_genres_are_dropped(self):
        out = se.extract_metadata({"title": "X", "genres": ["", "  ", "Drama"]})
        self.assertEqual(out["genres"], ["Drama"])
        out = se.extract_metadata({"title": "X", "genres": ["", "  "]})
        self.assertNotIn("genres", out)

    def test_bad_ids_are_dropped(self):
        out = se.extract_metadata({"title": "X", "ids": {"tmdb": "", "imdb": "nope"}})
        self.assertNotIn("tmdb_id", out)
        self.assertNotIn("imdb_id", out)


if __name__ == "__main__":
    unittest.main()
