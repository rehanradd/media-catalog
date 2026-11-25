"""Tests for the KMHD bridge JWT handling (API-07, 2026-11-24).

The dandndn API JWT used to sit as a literal default in
``fetch_kmhd_bridge`` (and again in the KatMovieHD ingest path). It is
now resolved per call from operator config only — never a literal in
code:

  1. explicit ``token`` argument (ingest passes the page's PUBLIC_TOKEN);
  2. ``RADD_KMHD_TOKEN`` environment variable (read fresh on every call);
  3. ``DOMAINS["kmhd_token"]`` (a domains_config DB row of that name).

With no token configured the bridge API is not called at all: the
caller gets the same bridge-page fallback row the API-failure path
yields, so an unconfigured token degrades to "no playable source"
instead of sending an empty/anonymous Bearer.

The no-literal test scans ``Fast_Fetch/fast_fetch.py`` for a JWT-shaped
string (three base64url segments, header starting "eyJ"). It asserts
on SHAPE only — this file contains no real token value anywhere, and
the fake bearer values below are plainly inert stand-ins.

Offline — mocked urlopen. Run:
``python3 -S Fast_Fetch/test_kmhd_token.py``
"""

import json
import os
import re
import sys
import unittest
import urllib.request
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fast_fetch

# JWT shape: three base64url segments, the first starting with the
# standard "eyJ" header prefix. The real literal's segments are long;
# ten-or-more chars per segment keeps the pattern tight while still
# catching any JWT-shaped default that is ever reintroduced.
JWT_SHAPE = re.compile(r"eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}")
FAST_FETCH_SRC = Path(__file__).with_name("fast_fetch.py")

# Inert stand-ins only: plain words, never a real credential, never
# accepted by any server.
ENV_TOKEN = "env-token-for-test-only"
DOMAINS_TOKEN = "domains-token-for-test-only"
EXPLICIT_TOKEN = "explicit-token-for-test-only"
PAGE_TOKEN = "page-public-token-for-test-only"
FILE_ID = "Ever_f8202fab"

FALLBACK_ROW = {
    "source_name": "KMHD Bridge",
    "service": "kmhd",
    "resource_id": FILE_ID,
    "source_url": f"https://links.kmhd.me/file/{FILE_ID}",
    "final_url": f"https://links.kmhd.me/file/{FILE_ID}",
    "is_temporary": 0,
}

KMHD_HTML = """<html><head><title>Example Film (2024) | KatMovieHD</title></head>
<body><h1>Example Film (2024) 720p HDRip</h1>
<div class="post_content">
<a href="https://links.kmhd.me/file/Ever_f8202fab">720p Links [821MB]</a>
</div></body></html>"""


class FakeResponse:
    """Minimal urlopen stand-in: canned JSON body, context manager."""

    def __init__(self, payload):
        self._payload = json.dumps(payload).encode("utf-8")

    def read(self):
        return self._payload

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class KmhdTokenTests(unittest.TestCase):
    def setUp(self):
        # The operator env var must not leak in from the ambient shell.
        self._saved_env_token = os.environ.pop("RADD_KMHD_TOKEN", None)

    def tearDown(self):
        if self._saved_env_token is None:
            os.environ.pop("RADD_KMHD_TOKEN", None)
        else:
            os.environ["RADD_KMHD_TOKEN"] = self._saved_env_token

    # -- the literal must be gone -------------------------------------

    def test_no_jwt_shaped_literal_in_fast_fetch_source(self):
        """No JWT-shaped literal anywhere in fast_fetch.py (shape, not
        value: this test never contains, prints, or reconstructs the
        removed token)."""
        text = FAST_FETCH_SRC.read_text(encoding="utf-8")
        match = JWT_SHAPE.search(text)
        self.assertIsNone(match, f"JWT-shaped literal at offset {match.start()}"
                          if match else "JWT-shaped literal present")
        # The resolver itself must exist: a future edit that deletes
        # the helper instead of the literal must fail here, loudly.
        self.assertIn("_kmhd_token", text)
        self.assertIn("RADD_KMHD_TOKEN", text)

    # -- per-call resolution ------------------------------------------

    def _bridge(self, *, token="", env_token=None, domains_token=None):
        """Call fetch_kmhd_bridge with a canned empty-mirrors API reply;
        return (result, [requests sent])."""
        calls = []

        def _fake_urlopen(req, context=None, timeout=None):
            calls.append(req)
            return FakeResponse({"upload_links": {}})

        domains = {}
        if domains_token is not None:
            domains["kmhd_token"] = domains_token
        env_patch = {}
        if env_token is not None:
            env_patch["RADD_KMHD_TOKEN"] = env_token
        with patch.object(fast_fetch, "DOMAINS", domains), \
                patch.dict(os.environ, env_patch), \
                patch.object(urllib.request, "urlopen",
                             side_effect=_fake_urlopen):
            result = fast_fetch.fetch_kmhd_bridge(FILE_ID, token)
        return result, calls

    @staticmethod
    def _bearer(req):
        return req.get_header("Authorization")

    def test_env_token_sent_as_bearer(self):
        result, calls = self._bridge(env_token=ENV_TOKEN)
        self.assertEqual(result, [])  # empty mirrors, no fallback
        self.assertEqual(len(calls), 1)
        self.assertEqual(self._bearer(calls[0]), f"Bearer {ENV_TOKEN}")

    def test_explicit_token_beats_env(self):
        result, calls = self._bridge(token=EXPLICIT_TOKEN,
                                     env_token=ENV_TOKEN)
        self.assertEqual(len(calls), 1)
        self.assertEqual(self._bearer(calls[0]),
                         f"Bearer {EXPLICIT_TOKEN}")

    def test_domains_token_used_when_no_env(self):
        result, calls = self._bridge(domains_token=DOMAINS_TOKEN)
        self.assertEqual(len(calls), 1)
        self.assertEqual(self._bearer(calls[0]),
                         f"Bearer {DOMAINS_TOKEN}")

    def test_env_beats_domains(self):
        _, calls = self._bridge(env_token=ENV_TOKEN,
                                domains_token=DOMAINS_TOKEN)
        self.assertEqual(self._bearer(calls[0]), f"Bearer {ENV_TOKEN}")

    def test_no_token_means_no_api_call(self):
        """Unconfigured: the API is never called and the caller gets
        the bridge-page fallback row (same shape as API failure)."""
        with patch.object(fast_fetch, "DOMAINS", {}), \
                patch.object(urllib.request, "urlopen") as urlopen:
            result = fast_fetch.fetch_kmhd_bridge(FILE_ID)
        urlopen.assert_not_called()
        self.assertEqual(result, [FALLBACK_ROW])

    def test_env_token_reread_per_call(self):
        """A rotated env value is picked up by the next call with no
        restart and no code change (per-call resolution)."""
        with patch.object(fast_fetch, "DOMAINS", {}), \
                patch.object(urllib.request, "urlopen",
                             return_value=FakeResponse(
                                 {"upload_links": {}})) as urlopen:
            with patch.dict(os.environ, {"RADD_KMHD_TOKEN": "first-token"}):
                fast_fetch.fetch_kmhd_bridge(FILE_ID)
            with patch.dict(os.environ, {"RADD_KMHD_TOKEN": "second-token"}):
                fast_fetch.fetch_kmhd_bridge(FILE_ID)
        self.assertEqual(
            [c.args[0].get_header("Authorization")
             for c in urlopen.call_args_list],
            ["Bearer first-token", "Bearer second-token"])

    # -- ingest path ---------------------------------------------------

    def test_ingest_skips_lookup_without_token(self):
        """No PUBLIC_TOKEN on the page and no operator token: the
        ingest name/size lookup is skipped (no dandndn call at all)
        and the release still lands with the bridge fallback row."""
        with patch.object(fast_fetch, "DOMAINS", {}), \
                patch.object(urllib.request, "urlopen") as urlopen:
            data = fast_fetch.extract_post_data_katmoviehd(
                "https://katmoviehd.example/example-film-2024/",
                KMHD_HTML, verbose=False)
        urlopen.assert_not_called()
        self.assertEqual(len(data["releases"]), 1)
        release = data["releases"][0]
        self.assertEqual(release["quality"], "720p")
        self.assertEqual(release["sources"], [FALLBACK_ROW])

    def test_ingest_uses_page_token_for_lookup_and_bridge(self):
        """A page PUBLIC_TOKEN still drives both the name/size lookup
        and the bridge call, with the token on each request."""
        html = KMHD_HTML.replace(
            "</body>",
            '<script>"PUBLIC_TOKEN":"%s"</script></body>' % PAGE_TOKEN)
        payload = {"name": "Example Film 720p HDRip.mkv",
                   "size": 821 * 1024 * 1024, "upload_links": {}}
        calls = []

        def _fake_urlopen(req, context=None, timeout=None):
            calls.append(req)
            return FakeResponse(payload)

        with patch.object(fast_fetch, "DOMAINS", {}), \
                patch.object(urllib.request, "urlopen",
                             side_effect=_fake_urlopen):
            data = fast_fetch.extract_post_data_katmoviehd(
                "https://katmoviehd.example/example-film-2024/",
                html, verbose=False)
        # One call for the name/size lookup, one for the bridge itself.
        self.assertEqual(len(calls), 2)
        for req in calls:
            self.assertEqual(req.get_header("Authorization"),
                             f"Bearer {PAGE_TOKEN}")
        release = data["releases"][0]
        self.assertEqual(release["release_title"], "Example Film 720p HDRip")
        self.assertEqual(release["size"], "821.0MB")
        self.assertEqual(release["sources"], [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
