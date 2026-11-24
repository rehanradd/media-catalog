#!/usr/bin/env python3
"""
Offline tests for the FilePress prepare/download (attempts) resolver —
no live socket, no database, no pip.

The fixtures are the shapes the host actually served, measured live from
the Oracle VM egress 2026-11-24 (paced ~1 req/s, capped bodies, no live
writes):

  * the attempts create answer for cloudR2Downlaod: CHALLENGE_PENDING with
    its challenge block (only the state/kind/action are asserted on — the
    siteKey/cData values are the server's, never hardcoded here);
  * the old downlaod API answers: METHOD_HIDDEN for indexDownlaod, a
    tgfiles deep link for telegramDownload, 403 Forbidden for
    gpixelDownload;
  * the minted Google user-content URL shape (video-downloads.
    googleusercontent.com with X-Goog-Date + X-Goog-Expires), Range-probed
    206 video/x-matroska with EBML magic — the 19:34 23 Nov residential
    browser proof, and the 20 Nov WARP re-measure.

Every request goes through an injected `fetch`, so the network code is
covered by calling the real functions with a fake transport, never by
monkey-patching a call away. `socket.socket` is replaced with a raiser so
an accidental live request fails the run instead of passing it. The poll
loop runs on a fake clock, so a 150 s budget costs no wall time.

What the suite pins, in one line each:

  * the challenge is NEVER attempted — no challenge POST, no link mint
    while CHALLENGE_PENDING, no Turnstile token anywhere;
  * a Telegram deep link is NEVER served as a playable file;
  * a prepare that outruns its budget is transient (unknown), never a
    failure verdict;
  * a minted link is only "active" after a 206 video Range probe, and its
    expiry is read off the URL itself, never invented.
"""

from __future__ import annotations

import json
import socket
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
# Fast_Fetch/ too: the dispatch budget helper lives in fast_fetch.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import host_resolvers as hr  # noqa: E402
import fast_fetch  # noqa: E402


class _NoLiveSocket:
    def __init__(self, *a, **k):
        raise AssertionError("test attempted a live socket")


socket.socket = _NoLiveSocket  # type: ignore[attr-defined]

FILE_ID = "6ad44fdf69b577b115b03617"
FILE_URL = "https://new4.filepress.lat/file/%s/" % FILE_ID
ATTEMPT_ID = "41f51d1d5368889d17de722d8d3ca0b4"
def _google_url_for_now(lifetime=3600, age=100):
    """A Google signed URL minted `age` seconds ago, living `lifetime`.

    Built from the real clock so the expiry assertions stay exact
    whenever the suite runs — a static fixture date would drift.
    Returns (url, issued_epoch)."""
    import datetime
    issued = int(time.time()) - age
    date = datetime.datetime.fromtimestamp(
        issued, datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    url = ("https://video-downloads.googleusercontent.com/abc123/Film.mkv"
           "?X-Goog-Algorithm=GOOG4-RSA-SHA256"
           "&X-Goog-Date=%s&X-Goog-Expires=%d"
           "&X-Goog-Signature=deadbeef" % (date, lifetime))
    return url, issued
DOWNLOAD_HOP = ("https://new4.filepress.lat/download/?d=TOKEN123"
                "&m=gpixelFastDownload")
EBML_206 = (b"\x1a\x45\xdf\xa3" + b"\x00" * 508,
            {"content-type": "video/x-matroska",
             "content-range": "bytes 0-511/1432132599"})


def _envelope(data, status=True, status_code=200):
    return json.dumps({"status": status, "statusCode": status_code,
                       "statusText": "Ok" if status else "Error",
                       "data": data}).encode()


def _challenge_attempt():
    return {"kind": "CHALLENGE", "attemptId": ATTEMPT_ID,
            "state": "CHALLENGE_PENDING", "fileId": FILE_ID,
            "challenge": {"profileId": "default",
                          "mode": "managed", "action": "r2_download"},
            "tries": 0}


class _FakeClock:
    """monotonic() + sleep() on controllable time."""

    def __init__(self):
        self.now = 1000.0
        self.sleeps: list[float] = []

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(float(seconds))
        self.now += float(seconds)


class _ScriptedFetch:
    """http_request-shaped fetch driven by a route table.

    routes: list of (method, url_suffix, handler) where handler receives
    (url, headers, data) and returns (status, body_bytes, headers_dict).
    The first matching route wins. Unmatched GETs with a Range header are
    answered by the media probe handler (set via `probe`); anything else
    raises, so a test that triggers an unscripted hop fails loudly.
    """

    def __init__(self):
        self.routes: list = []
        self.calls: list = []
        self.probe = None

    def add(self, method, suffix, handler):
        self.routes.append((method, suffix, handler))
        return self

    def __call__(self, url, headers=None, timeout=25, method="GET",
                 follow=True, data=None):
        self.calls.append({"method": method, "url": url,
                           "headers": dict(headers or {}),
                           "follow": follow, "data": data})
        for m, suffix, handler in self.routes:
            if method == m and url.endswith(suffix):
                return (*handler(url, headers or {}, data), url)
        if method == "GET" and (headers or {}).get("Range") and self.probe:
            return (*self.probe(url, headers or {}, data), url)
        raise AssertionError("unscripted fetch: %s %s" % (method, url))

    def calls_to(self, suffix):
        return [c for c in self.calls if c["url"].endswith(suffix)]


def _media_probe_206(url, headers, data):
    body, hdrs = EBML_206
    return 206, body, hdrs


def _legacy_method_hidden(url, headers, data):
    return 200, _envelope("METHOD_HIDDEN", status=False, status_code=404), {}


def _legacy_token_then_links(url, headers, data):
    if url.endswith("/api/file/downlaod/"):
        return 200, _envelope("6ad7ecde69b577b115172142"), {}
    return 200, _envelope(
        ["https://aws.flapdoodle.cyou/download/abc123/Film.mkv"]), {}


class FilepressAttemptsTests(unittest.TestCase):
    def _run(self, fetch, **kw):
        kw.setdefault("_sleep", lambda s: None)
        kw.setdefault("_monotonic", time.monotonic)
        return hr.unwrap_filepress(FILE_URL, fetch=fetch, **kw)

    def test_encode_filename_preserves_signed_query(self):
        """A signed URL's query must reach the host verbatim — encoding
        `?`/`&` breaks the signature (regression: the first version
        encoded the whole tail past the last `/`)."""
        url, _issued = _google_url_for_now()
        self.assertEqual(hr._filepress_encode_filename(url), url)
        self.assertEqual(
            hr._filepress_encode_filename(
                "https://x.cyou/download/abc/Film (2024) [1080p].mkv"
                "?token=abc&expires=123"),
            "https://x.cyou/download/abc/Film%20%282024%29%20%5B1080p%5D"
            ".mkv?token=abc&expires=123")

    def test_no_file_id_is_unknown_without_any_fetch(self):
        fetch = _ScriptedFetch()
        verdict = hr.unwrap_filepress("https://new3.filepress.lat/",
                                      fetch=fetch)
        self.assertEqual(verdict["status"], "unknown")
        self.assertIn("no file id", " ".join(verdict["notes"]))
        self.assertEqual(fetch.calls, [])

    def test_challenge_pending_is_blocked_and_never_attempted(self):
        """CHALLENGE_PENDING stops the chain at once: no poll, no link
        mint, no challenge POST, no Turnstile token anywhere. The only
        requests are the legacy downlaod POST (dead method) and the
        attempts create."""
        fetch = _ScriptedFetch()
        fetch.add("POST", "/api/file/downlaod/", _legacy_method_hidden)
        fetch.add("POST", "/api/downloads/attempts",
                  lambda u, h, d: (200, _envelope(_challenge_attempt()), {}))
        verdict = self._run(fetch)
        self.assertEqual(verdict["status"], "blocked", verdict)
        self.assertTrue(any("challenge_required" in n
                            for n in verdict["notes"]), verdict)
        self.assertEqual(len(fetch.calls_to("/api/file/downlaod/")), 1)
        self.assertEqual(len(fetch.calls_to("/api/downloads/attempts")), 1)
        self.assertEqual(fetch.calls_to("/link"), [])
        self.assertEqual([c for c in fetch.calls
                          if "challenge" in c["url"]], [])
        bodies = b" ".join(c["data"] or b"" for c in fetch.calls)
        self.assertNotIn(b"turnstile", bodies.lower())
        self.assertEqual(verdict["final_url"], "")

    def test_blocked_create_is_blocked(self):
        fetch = _ScriptedFetch()
        fetch.add("POST", "/api/file/downlaod/", _legacy_method_hidden)
        fetch.add("POST", "/api/downloads/attempts",
                  lambda u, h, d: (403, b"Just a moment...",
                                   {"content-type": "text/html"}))
        verdict = self._run(fetch)
        self.assertEqual(verdict["status"], "blocked", verdict)
        self.assertIn("attempts: blocked:403", " ".join(verdict["notes"]))

    def test_full_prepare_poll_mints_google_url_with_true_expiry(self):
        """PREPARING -> poll READY -> link mints a Google signed URL ->
        206 video probe -> active, with the expiry read off the URL
        (X-Goog-Date + X-Goog-Expires = 3600 s). The fp_dl cookie the
        create set is replayed on the poll."""
        fetch = _ScriptedFetch()
        fetch.probe = _media_probe_206
        fetch.add("POST", "/api/file/downlaod/", _legacy_method_hidden)

        def create(url, headers, data):
            body = json.loads(data.decode("utf-8"))
            self.assertEqual(body["fileId"], FILE_ID)
            self.assertEqual(body["method"], "cloudR2Downlaod")
            self.assertIn("idempotencyKey", body)
            attempt = {"kind": "PREPARING", "attemptId": ATTEMPT_ID,
                       "state": "PREPARING", "fileId": FILE_ID}
            return 200, _envelope(attempt), {
                "set-cookie": "fp_dl=COOKIE123; Path=/; HttpOnly"}

        def view(url, headers, data):
            self.assertEqual(headers.get("Cookie"), "fp_dl=COOKIE123")
            attempt = {"kind": "READY", "attemptId": ATTEMPT_ID,
                       "state": "READY", "fileId": FILE_ID}
            return 200, _envelope(attempt), {}

        fetch.add("POST", "/api/downloads/attempts", create)
        fetch.add("GET", "/api/downloads/attempts/%s" % ATTEMPT_ID, view)
        google_url, _issued = _google_url_for_now()
        fetch.add("POST", "/api/downloads/attempts/%s/link" % ATTEMPT_ID,
                  lambda u, h, d: (200, _envelope(google_url), {}))
        verdict = self._run(fetch)
        self.assertEqual(verdict["status"], "active", verdict)
        self.assertEqual(verdict["final_url"], google_url)
        self.assertEqual(verdict["content_type"], "video/x-matroska")
        self.assertEqual(verdict["http_status"], 206)
        self.assertIsNotNone(verdict["expires_in"])
        self.assertGreater(verdict["expires_in"], 0)
        self.assertLessEqual(verdict["expires_in"], 3600)

    def test_prepare_budget_exhausted_is_transient_not_failure(self):
        """A prep that outruns its budget is unknown + transient, and
        the link is never minted. Budget 25 s at 10 s polls = 3 polls."""
        clock = _FakeClock()
        fetch = _ScriptedFetch()
        fetch.add("POST", "/api/file/downlaod/", _legacy_method_hidden)
        attempt = {"kind": "PREPARING", "attemptId": ATTEMPT_ID,
                   "state": "PREPARING", "fileId": FILE_ID}
        fetch.add("POST", "/api/downloads/attempts",
                  lambda u, h, d: (200, _envelope(attempt), {}))
        fetch.add("GET", "/api/downloads/attempts/%s" % ATTEMPT_ID,
                  lambda u, h, d: (200, _envelope(attempt), {}))
        verdict = self._run(fetch, poll_budget_seconds=25,
                            poll_interval_seconds=10,
                            _sleep=clock.sleep, _monotonic=clock.monotonic)
        self.assertEqual(verdict["status"], "unknown", verdict)
        self.assertTrue(verdict.get("transient"), verdict)
        self.assertTrue(any("preparing" in n for n in verdict["notes"]),
                        verdict)
        self.assertEqual(
            len(fetch.calls_to("/api/downloads/attempts/%s" % ATTEMPT_ID)),
            3)
        self.assertEqual(fetch.calls_to("/link"), [])
        self.assertEqual(clock.sleeps, [10.0, 10.0, 5.0])

    def test_terminal_attempt_state_is_unknown_without_mint(self):
        fetch = _ScriptedFetch()
        fetch.add("POST", "/api/file/downlaod/", _legacy_method_hidden)
        preparing = {"kind": "PREPARING", "attemptId": ATTEMPT_ID,
                     "state": "PREPARING", "fileId": FILE_ID}
        refused = {"kind": "REFUSED", "attemptId": ATTEMPT_ID,
                   "state": "REFUSED", "fileId": FILE_ID}
        fetch.add("POST", "/api/downloads/attempts",
                  lambda u, h, d: (200, _envelope(preparing), {}))
        fetch.add("GET", "/api/downloads/attempts/%s" % ATTEMPT_ID,
                  lambda u, h, d: (200, _envelope(refused), {}))
        verdict = self._run(fetch)
        self.assertEqual(verdict["status"], "unknown", verdict)
        self.assertIn("attempt refused", " ".join(verdict["notes"]))
        self.assertEqual(fetch.calls_to("/link"), [])

    def test_download_hop_302_is_followed_to_the_file(self):
        """The link answer may be a /download/?d=<token> URL, not the
        file: one no-follow GET reads the 302 Location, the real file."""
        fetch = _ScriptedFetch()
        fetch.probe = _media_probe_206
        fetch.add("POST", "/api/file/downlaod/", _legacy_method_hidden)
        ready = {"kind": "READY", "attemptId": ATTEMPT_ID,
                 "state": "READY", "fileId": FILE_ID}
        fetch.add("POST", "/api/downloads/attempts",
                  lambda u, h, d: (200, _envelope(ready), {}))
        fetch.add("POST", "/api/downloads/attempts/%s/link" % ATTEMPT_ID,
                  lambda u, h, d: (200, _envelope(DOWNLOAD_HOP), {}))

        def hop(url, headers, data):
            google_url, _issued = _google_url_for_now()
            return 302, b"", {"location": google_url}

        fetch.add("GET", "download/?d=TOKEN123&m=gpixelFastDownload", hop)
        verdict = self._run(fetch)
        self.assertEqual(verdict["status"], "active", verdict)
        self.assertTrue(verdict["final_url"].startswith(
            "https://video-downloads.googleusercontent.com/"))
        hops = fetch.calls_to("download/?d=TOKEN123&m=gpixelFastDownload")
        self.assertEqual(len(hops), 1)
        self.assertFalse(hops[0]["follow"],
                         "the download hop must be read no-follow")

    def test_telegram_link_from_mint_is_never_served(self):
        fetch = _ScriptedFetch()
        fetch.add("POST", "/api/file/downlaod/", _legacy_method_hidden)
        ready = {"kind": "READY", "attemptId": ATTEMPT_ID,
                 "state": "READY", "fileId": FILE_ID}
        fetch.add("POST", "/api/downloads/attempts",
                  lambda u, h, d: (200, _envelope(ready), {}))
        fetch.add("POST", "/api/downloads/attempts/%s/link" % ATTEMPT_ID,
                  lambda u, h, d: (200, _envelope(
                      "https://tgfiles.space?start=2AtoR7SVcxpboomrsiZEmnvowd6ZnJAGpDkocYO"), {}))
        verdict = self._run(fetch)
        self.assertEqual(verdict["status"], "unknown", verdict)
        self.assertEqual(verdict["final_url"], "")
        self.assertTrue(any("telegram" in n for n in verdict["notes"]),
                        verdict)

    def test_attempt_view_without_cookie_is_a_session_note(self):
        """A view GET without the fp_dl cookie answers data=null — the
        session binding proving itself, not a JSON error."""
        fetch = _ScriptedFetch()
        fetch.add("POST", "/api/file/downlaod/", _legacy_method_hidden)
        preparing = {"kind": "PREPARING", "attemptId": ATTEMPT_ID,
                     "state": "PREPARING", "fileId": FILE_ID}
        fetch.add("POST", "/api/downloads/attempts",
                  lambda u, h, d: (200, _envelope(preparing), {}))
        fetch.add("GET", "/api/downloads/attempts/%s" % ATTEMPT_ID,
                  lambda u, h, d: (200, _envelope(None), {}))
        verdict = self._run(fetch)
        self.assertEqual(verdict["status"], "unknown", verdict)
        self.assertIn("attempt poll: no_session_cookie",
                      " ".join(verdict["notes"]))

    def test_create_transport_failure_is_unknown(self):
        fetch = _ScriptedFetch()
        fetch.add("POST", "/api/file/downlaod/", _legacy_method_hidden)
        fetch.add("POST", "/api/downloads/attempts",
                  lambda u, h, d: (0, "timed out", {}))
        verdict = self._run(fetch)
        self.assertEqual(verdict["status"], "unknown", verdict)
        self.assertIn("attempts: transport", " ".join(verdict["notes"]))

    def test_both_chains_down_is_unknown_with_both_notes(self):
        """Legacy METHOD_HIDDEN plus an attempts route that 404s: the
        verdict names both failures instead of blaming the file."""
        fetch = _ScriptedFetch()
        fetch.add("POST", "/api/file/downlaod/", _legacy_method_hidden)
        fetch.add("POST", "/api/downloads/attempts",
                  lambda u, h, d: (404, b"not found", {}))
        verdict = self._run(fetch)
        self.assertEqual(verdict["status"], "unknown", verdict)
        notes = " ".join(verdict["notes"])
        self.assertIn("downlaod: METHOD_HIDDEN", notes)
        self.assertIn("attempts: dead:404", notes)

    def test_legacy_chain_still_mints_first(self):
        """The old chain, when it answers, still mints without ever
        touching the attempts API — the legacy-first order, pinned."""
        fetch = _ScriptedFetch()
        fetch.probe = _media_probe_206
        fetch.add("POST", "/api/file/downlaod/",
                  lambda u, h, d: (200, _envelope(
                      "6ad7ecde69b577b115172142"), {}))
        fetch.add("POST", "/api/file/downlaod2/",
                  lambda u, h, d: (200, _envelope(
                      ["https://aws.flapdoodle.cyou/download/abc123/"
                       "Film.mkv"]), {}))
        verdict = self._run(fetch)
        self.assertEqual(verdict["status"], "active", verdict)
        self.assertEqual(verdict["final_url"],
                         "https://aws.flapdoodle.cyou/download/abc123/"
                         "Film.mkv")
        self.assertEqual(fetch.calls_to("/api/downloads/attempts"), [])

    def test_double_telegram_on_legacy_is_telegram_only(self):
        """Two tgfiles answers in a row: telegram-only, no downlaod2
        call with the URL (400), no attempts call either."""
        fetch = _ScriptedFetch()
        fetch.add("POST", "/api/file/downlaod/",
                  lambda u, h, d: (200, _envelope(
                      "https://tgfiles.space?start=2AtkaHmtIxReE5mXEKEEfiwvMT9yn2XjjdCXZ4T"), {}))
        verdict = self._run(fetch)
        self.assertEqual(verdict["status"], "unknown", verdict)
        self.assertEqual(verdict["final_url"], "")
        self.assertTrue(any("telegram" in n for n in verdict["notes"]),
                        verdict)
        self.assertEqual(fetch.calls_to("/api/file/downlaod2/"), [])
        self.assertEqual(fetch.calls_to("/api/downloads/attempts"), [])

    def test_legacy_blocked_never_tries_attempts(self):
        """A Cloudflare block on the old API is the same challenge on
        the new one from this egress — one verdict, one request."""
        fetch = _ScriptedFetch()
        fetch.add("POST", "/api/file/downlaod/",
                  lambda u, h, d: (403, b"", {}))
        verdict = self._run(fetch)
        self.assertEqual(verdict["status"], "blocked", verdict)
        self.assertEqual(fetch.calls_to("/api/downloads/attempts"), [])


class FilepressExpiryTests(unittest.TestCase):
    def test_google_signed_expiry_reads_date_plus_lifetime(self):
        url, issued = _google_url_for_now(lifetime=3600, age=100)
        expires_at, seconds = hr._filepress_google_expiry(url)
        self.assertEqual(expires_at, issued + 3600)
        # Minted 100 s ago with a 3600 s life: ~3500 s left, give or
        # take the second between the two time.time() reads.
        self.assertGreaterEqual(seconds, 3490)
        self.assertLessEqual(seconds, 3600)

    def test_google_signed_expiry_missing_is_none_never_invented(self):
        self.assertEqual(
            hr._filepress_google_expiry(
                "https://video-downloads.googleusercontent.com/abc/file"),
            (None, None))
        self.assertEqual(
            hr._filepress_google_expiry(
                "https://example.com/f.mkv?X-Goog-Date=not-a-date"
                "&X-Goog-Expires=3600"),
            (None, None))

    def test_minted_expiry_prefers_google_shape(self):
        url, issued = _google_url_for_now(lifetime=3600, age=100)
        expires_at, seconds = hr._filepress_minted_expiry(url)
        self.assertEqual(expires_at, issued + 3600)
        self.assertIsNotNone(seconds)


class FilepressDispatchBudgetTests(unittest.TestCase):
    def test_press_budget_is_short_and_background_is_long(self):
        self.assertEqual(
            fast_fetch._filepress_poll_budget(background=False), 30.0)
        self.assertEqual(
            fast_fetch._filepress_poll_budget(background=True), 150.0)

    def test_deadline_caps_either_budget(self):
        now = time.monotonic()
        self.assertAlmostEqual(
            fast_fetch._filepress_poll_budget(
                background=True, deadline=now + 5.0), 5.0, places=1)
        self.assertEqual(
            fast_fetch._filepress_poll_budget(
                background=False, deadline=now - 1.0), 0.0)

    def test_transient_verdict_is_not_a_failure(self):
        self.assertTrue(fast_fetch._is_transient_verdict(
            {"status": "unknown", "transient": True}))
        self.assertFalse(fast_fetch._is_transient_verdict(
            {"status": "unknown"}))
        self.assertFalse(fast_fetch._is_transient_verdict(
            {"status": "blocked", "http_status": 403}))
        self.assertFalse(fast_fetch._is_transient_verdict(None))


if __name__ == "__main__":
    unittest.main()
