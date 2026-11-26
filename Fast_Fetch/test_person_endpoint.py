#!/usr/bin/env python3
"""Offline tests for GET /api/v1/person — the TMDb cast lookup.

What had to be proven, in this order:

1. **Happy path.** Search + detail compose the documented payload:
   canonical TMDb name, w185 photo URL, stripped biography, birthday,
   place of birth, and `known_for` from combined_credits, most popular
   first, with title-less credits skipped, never invented.
2. **Best match.** An exact name match wins over TMDb's first result;
   with no exact match, TMDb's own ranking stands.
3. **No match is a miss, not a photo.** Empty results answer None (the
   route maps it to 404); a match without a `profile_path` answers
   `photo_url: None`, never a fabricated URL.
4. **Outage is not "no such person".** A failed search raises
   `PersonUnavailable` (route: 503); a failed detail still serves the
   search row; no credential at all fails before any HTTP call.
5. **Cache.** Hits and misses alike are cached per casefolded name, so a
   repeat tap — even in different case — spends no second TMDb call.
6. **Auth + transport.** Bearer token preferred (no `api_key` param);
   API key as a query param only with no bearer; the fetch goes through
   the WARP proxy handler whenever one is configured (direct egress to
   api.themoviedb.org gets its TLS handshake reset — measured 2026-11-26
   on the Oracle VM: 14/50 direct calls through, 50/50 via WARP).
7. **The edge.** The route maps blank name / miss / outage / hit to
   400 / 404 / 503 / 200 over a real loopback server.

    python3 -S Fast_Fetch/test_person_endpoint.py

No test touches the network: the lookup runs against an injected fake
HTTP layer, the route tests patch the lookup, and the transport test
patches the opener builder.
"""
import json
import os
import sys
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).parent.resolve()))

import redact  # noqa: E402
import viewer  # noqa: E402

_SEARCH_S_R_KHAN = {
    "results": [
        {"id": 111, "name": "Some Other", "profile_path": "/other.jpg",
         "popularity": 9.9},
        {"id": 35742, "name": "Shah Rukh Khan", "profile_path": "/srk.jpg",
         "popularity": 4.3},
    ]
}
_SEARCH_ONLY_OTHER = {
    "results": [
        {"id": 111, "name": "Some Other", "profile_path": "/other.jpg",
         "popularity": 9.9},
    ]
}
_DETAIL_S_R_KHAN = {
    "id": 35742, "name": "Shah Rukh Khan",
    "profile_path": "/srk.jpg",
    # TMDb bios can open with a zero-width space (measured on Akshay
    # Kumar, 2026-11-26): the lookup must strip it.
    "biography": "\u200bShah Rukh Khan is an Indian actor. ",
    "birthday": "1965-11-02",
    "place_of_birth": "New Delhi, Delhi, India",
    "combined_credits": {"cast": [
        {"title": "Jawan", "media_type": "movie",
         "release_date": "2023-09-07", "popularity": 50.0},
        {"title": "Pathaan", "media_type": "movie",
         "release_date": "2023-01-25", "popularity": 40.0},
        {"name": "Fauji", "media_type": "tv",
         "first_air_date": "1989-01-13", "popularity": 5.0},
        # Title-less credit: skipped, never invented.
        {"title": "", "media_type": "movie", "popularity": 99.0},
    ]},
}


class _FakeTmdb:
    """A fake `_http_get`: substring -> payload dict, or None for a
    transport failure. Records every URL it was asked for."""

    def __init__(self, mapping):
        self.mapping = mapping
        self.calls = []

    def __call__(self, url):
        self.calls.append(url)
        for needle, payload in self.mapping.items():
            if needle in url:
                if payload is None:
                    return None
                return json.loads(json.dumps(payload))
        raise AssertionError("unexpected TMDb URL: " + url)


class PersonLookupTests(unittest.TestCase):
    def setUp(self):
        viewer._PERSON_CACHE.clear()
        self.addCleanup(viewer._PERSON_CACHE.clear)

    def test_happy_path(self):
        fake = _FakeTmdb({"/search/person": _SEARCH_S_R_KHAN,
                          "/person/35742": _DETAIL_S_R_KHAN})
        person = viewer.api_v1_get_person("Shah Rukh Khan", _http_get=fake)
        self.assertEqual(person["tmdb_person_id"], 35742)
        self.assertEqual(person["name"], "Shah Rukh Khan")
        self.assertEqual(
            person["photo_url"],
            "https://image.tmdb.org/t/p/w185/srk.jpg")
        self.assertEqual(person["biography"],
                         "Shah Rukh Khan is an Indian actor.")
        self.assertEqual(person["birthday"], "1965-11-02")
        self.assertEqual(person["place_of_birth"], "New Delhi, Delhi, India")
        self.assertEqual(person["known_for"], [
            {"title": "Jawan", "media_type": "movie", "year": 2023},
            {"title": "Pathaan", "media_type": "movie", "year": 2023},
            {"title": "Fauji", "media_type": "tv", "year": 1989},
        ])
        self.assertEqual(len(fake.calls), 2)

    def test_exact_match_beats_first_result(self):
        # TMDb ranks "Some Other" first; the exact name still wins.
        fake = _FakeTmdb({"/search/person": _SEARCH_S_R_KHAN,
                          "/person/35742": _DETAIL_S_R_KHAN})
        person = viewer.api_v1_get_person("Shah Rukh Khan", _http_get=fake)
        self.assertEqual(person["tmdb_person_id"], 35742)
        self.assertNotIn("/person/111", " ".join(fake.calls))

    def test_first_result_when_no_exact_match(self):
        fake = _FakeTmdb({"/search/person": _SEARCH_ONLY_OTHER,
                          "/person/111": dict(_DETAIL_S_R_KHAN, id=111,
                                              name="Some Other")})
        person = viewer.api_v1_get_person("Shah Rukh", _http_get=fake)
        self.assertEqual(person["tmdb_person_id"], 111)
        self.assertEqual(person["name"], "Some Other")

    def test_no_results_returns_none_and_caches_the_miss(self):
        fake = _FakeTmdb({"/search/person": {"results": []}})
        self.assertIsNone(
            viewer.api_v1_get_person("Various Artist", _http_get=fake))
        self.assertIsNone(
            viewer.api_v1_get_person("various artist", _http_get=fake))
        self.assertEqual(len(fake.calls), 1)

    def test_match_without_photo_means_no_photo_url(self):
        search = {"results": [{"id": 7, "name": "No Photo",
                                "profile_path": None, "popularity": 1.0}]}
        detail = {"id": 7, "name": "No Photo", "profile_path": None,
                  "biography": "", "birthday": None, "place_of_birth": None,
                  "combined_credits": {"cast": []}}
        fake = _FakeTmdb({"/search/person": search, "/person/7": detail})
        person = viewer.api_v1_get_person("No Photo", _http_get=fake)
        self.assertIsNone(person["photo_url"])
        self.assertEqual(person["name"], "No Photo")

    def test_search_failure_raises_unavailable(self):
        fake = _FakeTmdb({"/search/person": None})
        with self.assertRaises(viewer.PersonUnavailable):
            viewer.api_v1_get_person("Shah Rukh Khan", _http_get=fake)

    def test_detail_failure_falls_back_to_search_row(self):
        fake = _FakeTmdb({"/search/person": _SEARCH_S_R_KHAN,
                          "/person/35742": None})
        person = viewer.api_v1_get_person("Shah Rukh Khan", _http_get=fake)
        self.assertEqual(person["tmdb_person_id"], 35742)
        self.assertEqual(
            person["photo_url"],
            "https://image.tmdb.org/t/p/w185/srk.jpg")
        self.assertEqual(person["known_for"], [])

    def test_cache_hit_reuses_first_answer_across_case(self):
        fake = _FakeTmdb({"/search/person": _SEARCH_S_R_KHAN,
                          "/person/35742": _DETAIL_S_R_KHAN})
        first = viewer.api_v1_get_person("Shah Rukh Khan", _http_get=fake)
        second = viewer.api_v1_get_person("SHAH RUKH KHAN", _http_get=fake)
        self.assertEqual(first, second)
        self.assertEqual(len(fake.calls), 2)

    def test_blank_name_never_calls_tmdb(self):
        fake = _FakeTmdb({})
        self.assertIsNone(viewer.api_v1_get_person("   ", _http_get=fake))
        self.assertEqual(fake.calls, [])

    def test_no_credential_fails_before_any_http_call(self):
        env = {"TMDB_BEARER_TOKEN": "", "TMDB_API_KEY": ""}
        with mock.patch.dict(os.environ, env, clear=False):
            with mock.patch.object(
                    viewer, "_tmdb_person_http_get",
                    side_effect=AssertionError("must not be called")):
                with self.assertRaises(viewer.PersonUnavailable):
                    viewer.api_v1_get_person("Shah Rukh Khan")

    def test_bearer_preferred_over_api_key(self):
        env = {"TMDB_BEARER_TOKEN": "BEARER-UNDER-TEST",
               "TMDB_API_KEY": "KEY-UNDER-TEST"}
        with mock.patch.dict(os.environ, env, clear=False):
            self.assertEqual(viewer._tmdb_person_headers(),
                             {"Authorization": "Bearer BEARER-UNDER-TEST"})
            self.assertNotIn(
                "api_key",
                viewer._tmdb_person_url("/search/person", query="X"))

    def test_api_key_in_query_when_no_bearer(self):
        env = {"TMDB_BEARER_TOKEN": "", "TMDB_API_KEY": "KEY-UNDER-TEST"}
        with mock.patch.dict(os.environ, env, clear=False):
            self.assertEqual(viewer._tmdb_person_headers(), {})
            self.assertIn(
                "api_key=KEY-UNDER-TEST",
                viewer._tmdb_person_url("/search/person", query="X"))

    def test_warp_handler_used_when_configured(self):
        sentinel = object()

        class _Resp:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                return json.dumps({"results": []}).encode("utf-8")

        class _Opener:
            def __init__(self):
                self.opened = []

            def open(self, request, timeout=None):
                self.opened.append((request.full_url, timeout))
                return _Resp()

        opener = _Opener()
        with mock.patch.object(viewer.warp_proxy, "urllib_proxy_handler",
                               return_value=sentinel), \
            mock.patch.object(viewer.warp_proxy, "capped_timeout",
                              return_value=5.0), \
            mock.patch.object(urllib.request, "build_opener",
                              return_value=opener) as build:
            self.assertEqual(
                viewer._tmdb_person_http_get("https://example.invalid/x"),
                {"results": []})
            build.assert_called_once_with(sentinel)
            self.assertEqual(opener.opened[0][1], 5.0)

    def test_direct_opener_when_no_proxy(self):
        opener = mock.MagicMock()
        opener.open.return_value.__enter__.return_value.read.return_value = \
            json.dumps({"results": []}).encode("utf-8")
        with mock.patch.object(viewer.warp_proxy, "urllib_proxy_handler",
                               return_value=None), \
            mock.patch.object(urllib.request, "build_opener",
                              return_value=opener) as build:
            self.assertEqual(
                viewer._tmdb_person_http_get("https://example.invalid/x"),
                {"results": []})
            build.assert_called_once_with()


class PersonRouteTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.public_server = viewer.make_server("127.0.0.1", 0, operator=False)
        threading.Thread(target=cls.public_server.serve_forever,
                         daemon=True).start()
        cls.public_port = cls.public_server.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.public_server.shutdown()
        cls.public_server.server_close()

    def setUp(self):
        viewer._PERSON_CACHE.clear()
        self.addCleanup(viewer._PERSON_CACHE.clear)

    def get(self, path):
        redact.limiter._buckets.clear()
        req = urllib.request.Request(
            "http://127.0.0.1:%d%s" % (self.public_port, path))
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as exc:
            return exc.code, json.loads(exc.read())

    def test_hit_returns_envelope(self):
        payload = {"tmdb_person_id": 35742, "name": "Shah Rukh Khan",
                   "photo_url": "https://image.tmdb.org/t/p/w185/srk.jpg",
                   "biography": "Actor.", "birthday": "1965-11-02",
                   "place_of_birth": "New Delhi, Delhi, India",
                   "known_for": []}
        with mock.patch.object(viewer, "api_v1_get_person",
                               return_value=payload):
            status, data = self.get("/api/v1/person?name=Shah%20Rukh%20Khan")
        self.assertEqual(status, 200, data)
        self.assertTrue(data["success"])
        self.assertEqual(data["data"], payload)

    def test_blank_name_is_400(self):
        status, data = self.get("/api/v1/person?name=%20%20")
        self.assertEqual(status, 400, data)
        self.assertEqual(data["error"], "missing name")

    def test_missing_name_param_is_400(self):
        status, data = self.get("/api/v1/person")
        self.assertEqual(status, 400, data)

    def test_no_match_is_404(self):
        with mock.patch.object(viewer, "api_v1_get_person",
                               return_value=None):
            status, data = self.get("/api/v1/person?name=Various%20Artist")
        self.assertEqual(status, 404, data)
        self.assertEqual(data["error"], "person_not_found")

    def test_outage_is_503_not_404(self):
        with mock.patch.object(
                viewer, "api_v1_get_person",
                side_effect=viewer.PersonUnavailable("down")):
            status, data = self.get("/api/v1/person?name=Shah%20Rukh%20Khan")
        self.assertEqual(status, 503, data)
        self.assertEqual(data["error"], "person_unavailable")


if __name__ == "__main__":
    unittest.main()
