#!/usr/bin/env python3
"""
Offline tests for the 1fichier resolver in host_resolvers.py — no live
socket, no database, no pip, no API key.

The fixtures are real: the five file URLs are live `download_sources` rows
(status active) sampled read-only from the merged catalog on 2026-11-20,
and the API shapes are the ones rclone's backend/fichier (current master)
and the meaSERIES Kodi client actually use:

    POST https://api.1fichier.com/v1/download/get_token.cgi
      Authorization: Bearer <api_key>
      {"url": "https://1fichier.com/?<id>", "single": 1, "restrict_ip": 0}
      -> {"url": "<direct link>", "Status": "OK", "Message": ""}

What is NOT proven here, and is owed at live-proof time with a real Premium
key (see checkpoints/20261120-phasec-1fichier.md): that the API answers OK
for a real file, that `restrict_ip: 0` makes the minted link playable from
the handset, and that the minted link Range-probes 206 video/*. The free
download page was never served to any server egress (403 error #350 on the
Oracle VM IP; "professional infrastructure detected" page on the hatch
egress), so there is no free-flow fixture to test against — and none is
built. Every request goes through an injected `fetch`, so the network code
is covered by calling the real functions with a fake transport, never by
monkey-patching a call away. `socket.socket` is replaced with a raiser so
an accidental live request fails the run instead of passing it.
"""

from __future__ import annotations

import json
import os
import socket
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import host_resolvers as hr  # noqa: E402


class _NoLiveSocket:
    def __init__(self, *a, **k):
        raise AssertionError("test attempted a live socket")


socket.socket = _NoLiveSocket  # type: ignore[attr-defined]

# Live catalog rows (read-only sample, 2026-11-20): status active.
FILE_URLS = (
    "https://1fichier.com/?maobk0tho3kglmwqi6ar",
    "https://1fichier.com/?s7lmp7q669nl2wjjalfd",
    "https://1fichier.com/?253ae2jy44ccqredoeka",
    "https://1fichier.com/?wdmlm3q3rmu3rhhnn9ry",
    "https://1fichier.com/?3wyzqcmhaiqicd342xpx",
)
FILE_IDS = (
    "maobk0tho3kglmwqi6ar",
    "s7lmp7q669nl2wjjalfd",
    "253ae2jy44ccqredoeka",
    "wdmlm3q3rmu3rhhnn9ry",
    "3wyzqcmhaiqicd342xpx",
)
SITE_PAGES = (
    "https://1fichier.com/",
    "https://1fichier.com/tarifs.html",
    "https://1fichier.com/console/params.pl",
    "https://1fichier.com/login.pl",
    "https://1fichier.com/register.pl",
)

MINTED = "https://o1234.1fichier.com/dl/TOKEN123/Example.Movie.2024.mkv"
MINTED_2 = "https://o5678.1fichier.com/dl/TOKEN456/Example.Movie.2024.mkv"
MP4_HEAD = b"\x00\x00\x00\x18ftypisom\x00\x00\x00\x00isomiso2" + b"\x00" * 480


def _ok_token(url=MINTED):
    return (200, json.dumps({"url": url, "Status": "OK", "Message": ""}).encode(),
            {"content-type": "application/json"})


def _probe_206():
    return (206, MP4_HEAD,
            {"content-type": "video/mp4",
             "content-range": "bytes 0-511/123456789"})


def scripted_fetch(script):
    """A fetch(url, headers, timeout, method, follow, data) stand-in.

    `script` maps a URL substring to a (status, body, headers) triple or an
    Exception to raise. Every call is recorded with its headers and body so
    the tests can assert what the resolver actually sent.
    """
    calls = []

    def fetch(url, headers=None, timeout=None, method="GET", follow=True, data=None):
        calls.append({"url": url, "headers": dict(headers or {}),
                      "method": method, "data": data})
        for key, value in script.items():
            if key in url:
                if isinstance(value, Exception):
                    raise value
                status, body, extra = value
                merged = {"content-type": "text/html"}
                merged.update(extra or {})
                return status, body, merged, url
        return 404, b"", {"content-type": "text/html"}, url

    fetch.calls = calls  # type: ignore[attr-defined]
    return fetch


class TestIdExtraction(unittest.TestCase):
    def test_sample_stored_urls_yield_their_ids(self):
        for url, want in zip(FILE_URLS, FILE_IDS):
            with self.subTest(url=url):
                self.assertEqual(hr._fichier_extract_id(url), want)

    def test_site_pages_have_no_file_id(self):
        for url in SITE_PAGES:
            with self.subTest(url=url):
                self.assertEqual(hr._fichier_extract_id(url), "")

    def test_path_form_id_is_accepted(self):
        self.assertEqual(
            hr._fichier_extract_id("https://1fichier.com/maobk0tho3kglmwqi6ar"),
            "maobk0tho3kglmwqi6ar")

    def test_other_hosts_have_no_file_id(self):
        self.assertEqual(
            hr._fichier_extract_id("https://gofile.io/d/maobk0tho3kglmwqi6ar"), "")
        self.assertEqual(hr._fichier_extract_id(""), "")


class TestRecognition(unittest.TestCase):
    def test_file_urls_recognised(self):
        for url in FILE_URLS:
            with self.subTest(url=url):
                self.assertTrue(hr.is_1fichier_url(url))

    def test_site_pages_not_recognised(self):
        for url in SITE_PAGES:
            with self.subTest(url=url):
                self.assertFalse(hr.is_1fichier_url(url))

    def test_www_host_recognised(self):
        self.assertTrue(hr.is_1fichier_url(
            "https://www.1fichier.com/?maobk0tho3kglmwqi6ar"))


class TestNoKey(unittest.TestCase):
    def test_no_key_is_unknown_never_dead_and_makes_no_request(self):
        fetch = scripted_fetch({})
        with mock.patch.dict(os.environ, {}, clear=True):
            verdict = hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch, verify=False)
        self.assertEqual(verdict["status"], "unknown")
        self.assertEqual(verdict["reason"], "no_api_key")
        self.assertEqual(verdict["final_url"], "")
        self.assertEqual(fetch.calls, [])

    def test_empty_env_key_is_the_same_as_no_key(self):
        fetch = scripted_fetch({})
        with mock.patch.dict(os.environ, {hr.FICHIER_API_KEY_ENV: "   "}):
            verdict = hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch, verify=False)
        self.assertEqual(verdict["status"], "unknown")
        self.assertEqual(verdict["reason"], "no_api_key")

    def test_no_key_note_names_the_env_var(self):
        fetch = scripted_fetch({})
        with mock.patch.dict(os.environ, {}, clear=True):
            verdict = hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch, verify=False)
        self.assertIn(hr.FICHIER_API_KEY_ENV, " ".join(verdict["notes"]))

    def test_bad_url_without_a_key_is_still_unknown(self):
        fetch = scripted_fetch({})
        with mock.patch.dict(os.environ, {}, clear=True):
            verdict = hr.unwrap_1fichier("https://1fichier.com/tarifs.html",
                                         fetch=fetch, verify=False)
        self.assertEqual(verdict["status"], "unknown")
        self.assertEqual(fetch.calls, [])


class TestApiKeySource(unittest.TestCase):
    def test_explicit_key_beats_the_env_var(self):
        fetch = scripted_fetch({"get_token.cgi": _ok_token(),
                                "dl/TOKEN123": _probe_206()})
        with mock.patch.dict(os.environ, {hr.FICHIER_API_KEY_ENV: "env-key"}):
            verdict = hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch,
                                         api_key="explicit-key")
        self.assertEqual(verdict["status"], "active")
        post = [c for c in fetch.calls if c["method"] == "POST"][0]
        self.assertEqual(post["headers"].get("Authorization"),
                         "Bearer explicit-key")

    def test_env_key_is_used_when_no_explicit_key(self):
        fetch = scripted_fetch({"get_token.cgi": _ok_token(),
                                "dl/TOKEN123": _probe_206()})
        with mock.patch.dict(os.environ, {hr.FICHIER_API_KEY_ENV: "env-key"}):
            verdict = hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch)
        self.assertEqual(verdict["status"], "active")
        post = [c for c in fetch.calls if c["method"] == "POST"][0]
        self.assertEqual(post["headers"].get("Authorization"), "Bearer env-key")


class TestGetTokenRequest(unittest.TestCase):
    def test_post_shape_matches_the_rclone_chain(self):
        fetch = scripted_fetch({"get_token.cgi": _ok_token(),
                                "dl/TOKEN123": _probe_206()})
        verdict = hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch,
                                     api_key="k", verify=False)
        self.assertEqual(verdict["status"], "unverified")
        posts = [c for c in fetch.calls if c["method"] == "POST"]
        self.assertEqual(len(posts), 1)
        post = posts[0]
        self.assertEqual(post["url"], hr.FICHIER_GET_TOKEN)
        self.assertEqual(post["headers"].get("Content-Type"), "application/json")
        body = json.loads(post["data"].decode("utf-8"))
        self.assertEqual(body["url"], FILE_URLS[0])
        self.assertEqual(body["single"], 1)
        # The server mints, the phone plays: the token must not be bound to
        # the server IP.
        self.assertEqual(body["restrict_ip"], 0)

    def test_file_id_is_rebuilt_into_the_canonical_url(self):
        fetch = scripted_fetch({"get_token.cgi": _ok_token(),
                                "dl/TOKEN123": _probe_206()})
        hr.unwrap_1fichier("https://1fichier.com/maobk0tho3kglmwqi6ar",
                           fetch=fetch, api_key="k", verify=False)
        post = [c for c in fetch.calls if c["method"] == "POST"][0]
        body = json.loads(post["data"].decode("utf-8"))
        self.assertEqual(body["url"], FILE_URLS[0])


class TestMintSuccess(unittest.TestCase):
    def test_verified_mint_is_active_with_video_probe(self):
        fetch = scripted_fetch({"get_token.cgi": _ok_token(),
                                "dl/TOKEN123": _probe_206()})
        verdict = hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch, api_key="k")
        self.assertEqual(verdict["status"], "active")
        self.assertEqual(verdict["final_url"], MINTED)
        self.assertEqual(verdict["http_status"], 206)
        self.assertEqual(verdict["content_type"], "video/mp4")
        self.assertEqual(verdict["service"], "1fichier")
        self.assertEqual(verdict["file_id"], FILE_IDS[0])

    def test_minted_link_is_temporary_with_a_short_ttl(self):
        fetch = scripted_fetch({"get_token.cgi": _ok_token(),
                                "dl/TOKEN123": _probe_206()})
        verdict = hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch, api_key="k")
        self.assertTrue(verdict["is_temporary"])
        self.assertEqual(verdict["expires_in"], hr.FICHIER_TOKEN_TTL_SECONDS)
        self.assertEqual(verdict["sources"], [{"url": MINTED, "mime": "", "label": ""}])

    def test_unverified_mint_stops_before_the_byte_endpoint(self):
        fetch = scripted_fetch({"get_token.cgi": _ok_token(),
                                "dl/TOKEN123": _probe_206()})
        verdict = hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch, api_key="k",
                                     verify=False)
        self.assertEqual(verdict["status"], "unverified")
        self.assertEqual(verdict["final_url"], MINTED)
        self.assertEqual([c["method"] for c in fetch.calls], ["POST"])

    def test_refused_probe_is_never_active(self):
        fetch = scripted_fetch({
            "get_token.cgi": _ok_token(),
            "dl/TOKEN123": (403, b"", {"content-type": "text/html"})})
        verdict = hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch, api_key="k")
        self.assertEqual(verdict["status"], "blocked")
        self.assertEqual(verdict["final_url"], MINTED)


class TestApiErrors(unittest.TestCase):
    def _verdict(self, token_response, api_key="k"):
        fetch = scripted_fetch({"get_token.cgi": token_response})
        return hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch, api_key=api_key,
                                  verify=False)

    def test_ko_file_not_found_is_dead(self):
        verdict = self._verdict(
            (200, json.dumps({"Status": "KO", "Message": "File not found",
                              "url": ""}).encode(),
             {"content-type": "application/json"}))
        self.assertEqual(verdict["status"], "dead")
        self.assertEqual(verdict["final_url"], "")

    def test_ko_bad_api_key_is_blocked(self):
        verdict = self._verdict(
            (200, json.dumps({"Status": "KO", "Message": "Bad api key",
                              "url": ""}).encode(),
             {"content-type": "application/json"}))
        self.assertEqual(verdict["status"], "blocked")

    def test_ko_download_limit_is_blocked(self):
        verdict = self._verdict(
            (200, json.dumps({"Status": "KO",
                              "Message": "Download limit reached",
                              "url": ""}).encode(),
             {"content-type": "application/json"}))
        self.assertEqual(verdict["status"], "blocked")

    def test_ko_unrecognised_message_is_unknown_never_dead(self):
        verdict = self._verdict(
            (200, json.dumps({"Status": "KO", "Message": "Something odd",
                              "url": ""}).encode(),
             {"content-type": "application/json"}))
        self.assertEqual(verdict["status"], "unknown")

    def test_http_403_is_blocked(self):
        verdict = self._verdict((403, b"", {"content-type": "text/html"}))
        self.assertEqual(verdict["status"], "blocked")

    def test_http_429_is_blocked(self):
        verdict = self._verdict((429, b"", {"content-type": "text/html"}))
        self.assertEqual(verdict["status"], "blocked")

    def test_http_500_is_unknown(self):
        verdict = self._verdict((500, b"", {"content-type": "text/html"}))
        self.assertEqual(verdict["status"], "unknown")

    def test_non_json_200_is_unknown(self):
        verdict = self._verdict((200, b"not json at all",
                                 {"content-type": "text/html"}))
        self.assertEqual(verdict["status"], "unknown")

    def test_ok_without_an_https_url_is_unknown(self):
        verdict = self._verdict(
            (200, json.dumps({"Status": "OK", "Message": "",
                              "url": "ftp://example.invalid/x.mkv"}).encode(),
             {"content-type": "application/json"}))
        self.assertEqual(verdict["status"], "unknown")
        self.assertEqual(verdict["final_url"], "")

    def test_transport_failure_is_unknown_never_dead(self):
        fetch = scripted_fetch(
            {"get_token.cgi": (0, "connection reset by peer", {})})
        verdict = hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch, api_key="k",
                                     verify=False)
        self.assertEqual(verdict["status"], "unknown")

    def test_fetch_raising_is_unknown_never_dead(self):
        fetch = scripted_fetch(
            {"get_token.cgi": ConnectionResetError("reset by peer")})
        verdict = hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch, api_key="k",
                                     verify=False)
        self.assertEqual(verdict["status"], "unknown")


class TestNoStoredUrl(unittest.TestCase):
    """The minted link is a return value, never a stored value: two resolves
    mint twice, and the verdict always follows the fresh mint."""

    def test_two_resolves_follow_their_own_mints(self):
        fetch1 = scripted_fetch({"get_token.cgi": _ok_token(MINTED),
                                 "dl/TOKEN123": _probe_206()})
        first = hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch1, api_key="k")
        fetch2 = scripted_fetch({"get_token.cgi": _ok_token(MINTED_2),
                                 "dl/TOKEN456": _probe_206()})
        second = hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch2, api_key="k")
        self.assertEqual(first["final_url"], MINTED)
        self.assertEqual(second["final_url"], MINTED_2)
        self.assertNotEqual(first["final_url"], second["final_url"])

    def test_final_url_is_never_the_page_url(self):
        fetch = scripted_fetch({"get_token.cgi": _ok_token(),
                                "dl/TOKEN123": _probe_206()})
        verdict = hr.unwrap_1fichier(FILE_URLS[0], fetch=fetch, api_key="k")
        self.assertNotEqual(verdict["final_url"], FILE_URLS[0])
        self.assertNotIn("1fichier.com/?", verdict["final_url"])


class TestWiring(unittest.TestCase):
    def test_resolver_for_service(self):
        self.assertIs(hr.resolver_for_service("1fichier"), hr.unwrap_1fichier)
        self.assertIs(hr.resolver_for_service("  1FICHIER "), hr.unwrap_1fichier)
        self.assertIsNone(hr.resolver_for_service("not-a-service"))

    def test_resolve_url_routes_1fichier_without_a_key(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            verdict = hr.resolve_url(FILE_URLS[0], verify=False)
        self.assertEqual(verdict["service"], "1fichier")
        self.assertEqual(verdict["status"], "unknown")
        self.assertEqual(verdict["reason"], "no_api_key")

    def test_resolve_service_routes_1fichier(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            verdict = hr.resolve_service("1fichier", FILE_URLS[1], verify=False)
        self.assertEqual(verdict["service"], "1fichier")
        self.assertEqual(verdict["status"], "unknown")
        self.assertEqual(verdict["reason"], "no_api_key")

    def test_resolve_url_shape_routing_reaches_the_unwrapper(self):
        # The URL-shape dispatcher, not just the service map, must know the host.
        names = {service for service, _pred, _fn in hr.SERVICE_RESOLVERS}
        self.assertIn("1fichier", names)


if __name__ == "__main__":
    unittest.main(verbosity=2)
