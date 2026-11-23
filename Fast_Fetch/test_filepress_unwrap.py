"""Tests for the filepress Fast Download chain (2026-11-23).

Offline — every network edge is a fake fetch. Covers the base (new4:
new3.filepress.baby is dead, new2 301s to new4), the happy path through
the gpixelFastDownload prepare flow, the bounded prepare poll, honest
dead-file failures, and the resolve_live_source dispatch routing.

Measured live 2026-11-23 (The Terminal List, via WARP egress):
downlaod(method=gpixelFastDownload) mints a 24-hex token in ~0.4 s and
downlaod2 (same method) mints a bare Google user-content URL string in
~0.4 s (the fast copy already existed); indexDownlaod for the same file
answers METHOD_HIDDEN, so the index chain is a per-file lottery and the
fast chain is the fallback, not the first try. The /prepare/ and
/download/ pages are SPA shells (1,457 B, no links), so the resolver
drives the API and never scrapes those pages.
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import current_hosts
import fast_fetch

hr = fast_fetch._embed_resolvers()

FILE_ID = "6ad8c86e69b577b11530c5be"
PAGE_NEW4 = "https://new4.filepress.lat/file/%s/" % FILE_ID
PAGE_NEW3 = "https://new3.filepress.baby/file/%s/" % FILE_ID
TOKEN = "6ad7ecde69b577b115172142"
GOOGLE_URL = ("https://video-downloads.googleusercontent.com/"
              "AbCdEf0123456789/file.mkv")
FLAPDOODLE_URL = ("https://aws.flapdoodle.cyou/download/abc123/"
                  "Film%20%282024%29%20%5B1080p%5D.mkv")
MKV_BYTES = b"\x1a\x45\xdf\xa3" + b"\x00" * 508


def _api_body(status, data, code=200):
    return json.dumps({"status": status, "statusCode": code,
                       "data": data}).encode()


class _FakeFetch:
    """Routes POSTs by (endpoint, method) and Range probes by header.

    ``script`` maps (endpoint, method) -> list of (status, body) replies
    consumed in order; a single reply repeats. Any GET carrying a Range
    header is the byte probe and answers from ``probe``.
    """

    def __init__(self, script, probe=(206, MKV_BYTES)):
        self.script = script
        self.probe = probe
        self.calls = []          # (endpoint, method, payload_id)
        self.probe_calls = []
        self.seen_urls = []

    def __call__(self, url, headers=None, timeout=25, method="GET",
                 follow=True, data=None):
        self.seen_urls.append(url)
        headers = headers or {}
        if "Range" in headers:
            self.probe_calls.append(url)
            status, body = self.probe
            return (status, body,
                    {"content-type": "video/x-matroska",
                     "content-range": "bytes 0-511/1800000000"}, url)
        payload = json.loads(data.decode("utf-8")) if data else {}
        endpoint = ("downlaod2" if url.endswith("/api/file/downlaod2/")
                    else "downlaod")
        key = (endpoint, payload.get("method"))
        self.calls.append((endpoint, payload.get("method"),
                           payload.get("id")))
        replies = self.script.get(key) or self.script.get((endpoint, None))
        if not replies:
            raise AssertionError("unexpected API call %r" % (key,))
        if len(replies) > 1:
            status, body = replies.pop(0)
        else:
            status, body = replies[0]
        return (status, body, {}, url)


def _index_ok_script():
    return {
        ("downlaod", "indexDownlaod"): [
            (200, _api_body(True, TOKEN))],
        ("downlaod2", "indexDownlaod"): [
            (200, _api_body(True, [FLAPDOODLE_URL.replace("%20", " ")
                                   .replace("%28", "(").replace("%29", ")")
                                   .replace("%5B", "[").replace("%5D", "]")]))],
    }


def _fast_ok_script(preparing=0):
    replies = [(200, _api_body(True, {"status": "PREPARING",
                                      "progress": 40, "error": None})),
               ] * preparing
    replies.append((200, _api_body(True, TOKEN)))
    return {
        ("downlaod", "indexDownlaod"): [
            (200, _api_body(False, "METHOD_HIDDEN", code=404))],
        ("downlaod", "gpixelFastDownload"): replies,
        ("downlaod2", "gpixelFastDownload"): [
            (200, _api_body(True, GOOGLE_URL))],
    }


class FilepressFastChainTests(unittest.TestCase):
    def test_index_chain_still_mints_first_and_fast_chain_never_runs(self):
        """The 2026-11-13 index chain is unchanged: a working index mint
        never pays for the fast chain."""
        fetch = _FakeFetch(_index_ok_script())
        verdict = hr.unwrap_filepress(PAGE_NEW4, fetch=fetch)
        self.assertEqual(verdict["status"], "active", verdict)
        self.assertEqual(verdict["final_url"], FLAPDOODLE_URL)
        self.assertEqual(verdict["http_status"], 206)
        self.assertNotIn(("downlaod", "gpixelFastDownload", FILE_ID),
                         fetch.calls)

    def test_fast_chain_mints_google_url_when_index_is_hidden(self):
        """METHOD_HIDDEN means 'not in this mirror's index' — unknown,
        never dead — so the fast chain runs and mints the Google URL.
        The minted URL carries no query expiry, so expires_in is None;
        the branch still banks it temporary (see the dispatch test)."""
        fetch = _FakeFetch(_fast_ok_script())
        verdict = hr.unwrap_filepress(PAGE_NEW4, fetch=fetch)
        self.assertEqual(verdict["status"], "active", verdict)
        self.assertEqual(verdict["final_url"], GOOGLE_URL)
        self.assertIsNone(verdict["expires_in"])
        self.assertEqual(verdict["http_status"], 206)
        notes = " ".join(verdict["notes"])
        self.assertIn("METHOD_HIDDEN", notes)
        self.assertIn("fast downlaod/downlaod2", notes)
        api_urls = [u for u in fetch.seen_urls if "/api/file/" in u]
        self.assertTrue(api_urls)
        self.assertTrue(all(u.startswith("https://new4.filepress.lat")
                            for u in api_urls),
                        api_urls)

    def test_new3_page_url_still_resolves_through_the_new4_base(self):
        """Base is new4 (2026-11-23): new3.filepress.baby is dead and
        new2 301s to new4, but a catalog row carrying the old host still
        resolves — every API call goes to new4 regardless of the page
        host in the row."""
        fetch = _FakeFetch(_fast_ok_script())
        verdict = hr.unwrap_filepress(PAGE_NEW3, fetch=fetch)
        self.assertEqual(verdict["status"], "active", verdict)
        self.assertEqual(verdict["final_url"], GOOGLE_URL)
        api_urls = [u for u in fetch.seen_urls if "/api/file/" in u]
        self.assertTrue(api_urls)
        self.assertTrue(all(u.startswith("https://new4.filepress.lat")
                            for u in api_urls),
                        api_urls)

    def test_fast_chain_polls_while_preparing_then_mints(self):
        """While downlaod answers a preparing object the resolver
        re-calls it (the /prepare/ page's ~2 min wait, as API calls);
        the token then mints on the first downlaod2. The list form of
        a minted link is accepted too."""
        fetch = _FakeFetch({
            ("downlaod", "indexDownlaod"): [
                (200, _api_body(False, "METHOD_HIDDEN", code=404))],
            ("downlaod", "gpixelFastDownload"): [
                (200, _api_body(True, {"status": "PREPARING",
                                       "progress": 40, "error": None})),
                (200, _api_body(True, {"status": "PREPARING",
                                       "progress": 80, "error": None})),
                (200, _api_body(True, TOKEN))],
            ("downlaod2", "gpixelFastDownload"): [
                (200, _api_body(True, [GOOGLE_URL]))],
        })
        sleeps = []
        verdict = hr.unwrap_filepress(PAGE_NEW4, fetch=fetch,
                                      _sleep=sleeps.append)
        self.assertEqual(verdict["status"], "active", verdict)
        self.assertEqual(verdict["final_url"], GOOGLE_URL)
        self.assertEqual(sleeps, [hr.FILEPRESS_PREPARE_POLL_SECONDS] * 2)
        self.assertEqual(
            fetch.calls.count(("downlaod", "gpixelFastDownload", FILE_ID)),
            3)

    def test_prepare_poll_is_bounded(self):
        """A copy that never finishes preparing is an honest unknown
        after a bounded number of polls — never an open-ended wait
        inside a press. The bound is a poll count, so an instant test
        sleep proves it without waiting it out."""
        fetch = _FakeFetch({
            ("downlaod", "indexDownlaod"): [
                (200, _api_body(False, "METHOD_HIDDEN", code=404))],
            ("downlaod", "gpixelFastDownload"): [
                (200, _api_body(True, {"status": "PREPARING",
                                       "progress": 10, "error": None}))],
        })
        sleeps = []
        with patch.object(hr, "FILEPRESS_PREPARE_MAX_SECONDS", 30):
            verdict = hr.unwrap_filepress(PAGE_NEW4, fetch=fetch,
                                          _sleep=sleeps.append)
        self.assertEqual(verdict["status"], "unknown", verdict)
        self.assertIn("timed out", " ".join(verdict["notes"]))
        self.assertEqual(sleeps, [hr.FILEPRESS_PREPARE_POLL_SECONDS] * 2)
        self.assertEqual(
            fetch.calls.count(("downlaod", "gpixelFastDownload", FILE_ID)),
            3)

    def test_prepare_error_is_unknown_not_dead(self):
        """A preparing object carrying an error ends the poll at once:
        the copy failed, which is unknown (another mirror may mint),
        never dead."""
        fetch = _FakeFetch({
            ("downlaod", "indexDownlaod"): [
                (200, _api_body(False, "METHOD_HIDDEN", code=404))],
            ("downlaod", "gpixelFastDownload"): [
                (200, _api_body(True, {"status": "FAILED",
                                       "progress": 55,
                                       "error": "COPY_FAILED"}))],
        })
        sleeps = []
        verdict = hr.unwrap_filepress(PAGE_NEW4, fetch=fetch,
                                      _sleep=sleeps.append)
        self.assertEqual(verdict["status"], "unknown", verdict)
        self.assertIn("COPY_FAILED", " ".join(verdict["notes"]))
        self.assertEqual(sleeps, [])

    def test_dead_file_short_circuits_both_chains(self):
        """A 404 on the index chain is dead, and dead never pays for
        the fast chain — a deleted file has no copy to prepare."""
        fetch = _FakeFetch({
            ("downlaod", "indexDownlaod"): [(404, b"", )],
        })
        verdict = hr.unwrap_filepress(PAGE_NEW4, fetch=fetch)
        self.assertEqual(verdict["status"], "dead", verdict)
        self.assertNotIn(("downlaod", "gpixelFastDownload", FILE_ID),
                         fetch.calls)

    def test_fpgo_url_is_dead_without_any_fetch(self):
        """fpgo.xyz is where retired filepress domains redirect, not a
        file host: a link already there has no filepress copy left."""
        calls = []

        def fetch(url, **kwargs):
            calls.append(url)
            raise AssertionError("must not fetch for a retired host")

        verdict = hr.unwrap_filepress(
            "https://fpgo.xyz/file/%s/" % FILE_ID, fetch=fetch)
        self.assertEqual(verdict["status"], "dead", verdict)
        self.assertEqual(calls, [])

    def test_fast_mint_522_is_error_never_active(self):
        """A minted Google URL that cannot be read (522) is an error,
        never active — the gate must refuse it, and the row must not
        be served."""
        fetch = _FakeFetch(_fast_ok_script(),
                           probe=(522, b""))
        verdict = hr.unwrap_filepress(PAGE_NEW4, fetch=fetch)
        self.assertEqual(verdict["status"], "error", verdict)
        self.assertEqual(verdict["http_status"], 522)

    def test_oracle_blocked_index_falls_through_to_fast_chain(self):
        """The Oracle VM egress is 403-challenged on the API (Turnstile):
        a blocked index chain falls through to the fast chain instead
        of reporting the egress block as the file's verdict."""
        fetch = _FakeFetch({
            ("downlaod", "indexDownlaod"): [(403, b"", )],
            ("downlaod", "gpixelFastDownload"): [
                (200, _api_body(True, TOKEN))],
            ("downlaod2", "gpixelFastDownload"): [
                (200, _api_body(True, GOOGLE_URL))],
        })
        verdict = hr.unwrap_filepress(PAGE_NEW4, fetch=fetch)
        self.assertEqual(verdict["status"], "active", verdict)
        self.assertEqual(verdict["final_url"], GOOGLE_URL)


class _FakeResolvers:
    """Minimal stand-in for importers/host_resolvers: only the filepress
    branch may match, so every other resolver attribute answers "no"."""
    SERVICE_FILEPRESS = "filepress"

    def __init__(self, verdict=None):
        self.verdict = verdict
        self.calls = []

    def __getattr__(self, name):
        if name.startswith("SERVICE_"):
            return "zzz-no-such-service"
        if name.startswith("unwrap_"):
            def _never(url, fetch=None, verify=True, **kwargs):
                raise AssertionError(
                    "unexpected unwrapper call %s" % name)
            return _never
        return lambda *args, **kwargs: False

    def unwrap_filepress(self, url, fetch=None, verify=True):
        self.calls.append(("filepress", url, verify))
        return self.verdict or {
            "status": "active", "final_url": GOOGLE_URL,
            "is_temporary": True, "expires_in": None,
            "content_type": "video/x-matroska", "http_status": 206,
            "size_bytes": 1432132599}


class FilepressDispatchTests(unittest.TestCase):
    """resolve_live_source routes a filepress row through hr.unwrap_filepress
    and banks the minted Google URL temporary — the dispatch branch
    already existed; this pins that the new verdict flows through it."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test.db"
        self.db_path_patch = patch.object(fast_fetch, "DB_PATH", self.db_path)
        self.db_path_patch.start()
        self.cinemeta_patch = patch.object(
            fast_fetch, "fetch_cinemeta_metadata", return_value=None)
        self.cinemeta_patch.start()
        self.stage2_probe_patch = patch.object(
            fast_fetch, "_stage2_probe",
            return_value={"ok": True, "kind": "file", "mime": "video/mp4",
                          "size": None, "resumable": True, "renditions": [],
                          "failure": None})
        self.stage2_probe_patch.start()
        self.stage1_backend_patch = patch.object(
            current_hosts, "classify_backend", return_value="unknown")
        self.stage1_backend_patch.start()
        fast_fetch.init_db()

    def tearDown(self):
        self.stage1_backend_patch.stop()
        self.stage2_probe_patch.stop()
        self.cinemeta_patch.stop()
        self.db_path_patch.stop()
        self.temp_dir.cleanup()

    def _embed_source(self, site, service, embed):
        media_data = {
            "url": "https://%s.example/film/" % site,
            "slug": "film",
            "title": "Example Film (2024)",
            "clean_title": "Example Film",
            "year": 2024,
            "content_type": "movie",
            "seasons": None,
            "poster_url": None,
            "backdrop_urls": None,
            "imdb_rating": None,
            "genres": None,
            "synopsis": None,
            "is_18_plus": 0,
            "is_animated": 0,
            "source_site": site,
            "industry": "Hollywood",
            "country": "USA",
            "language": "English",
            "canonical_key": fast_fetch.generate_canonical_key(
                "Example Film", 2024),
            "tmdb_id": None,
            "imdb_id": None,
            "releases": [{
                "release_title": "Example Film 1080p",
                "quality": "1080p",
                "codec": "x264",
                "size": "1GB",
                "languages": "English",
                "season": None,
                "episode": None,
                "intermediate_url": "https://%s.example/release" % site,
                "sources": [{
                    "source_name": service,
                    "service": service,
                    "resource_id": "54343",
                    "source_url": embed,
                    "final_url": embed,
                    "is_temporary": 1,
                }],
            }],
        }
        fast_fetch.save_scraped_data(media_data)
        with fast_fetch.get_db() as conn:
            return conn.execute(
                "SELECT id, final_url FROM download_sources LIMIT 1"
            ).fetchone()

    def test_dispatch_routes_filepress_row_to_fast_chain_verdict(self):
        stored = self._embed_source("moviesmela", "filepress", PAGE_NEW3)
        resolvers = _FakeResolvers()
        failing = {"ok": False, "kind": None, "mime": None, "size": None,
                   "resumable": False, "renditions": [],
                   "failure": "probe_error"}
        with patch.object(fast_fetch, "_embed_resolvers",
                          return_value=resolvers), \
             patch.object(fast_fetch, "_stage2_probe",
                          return_value=failing), \
             patch.object(fast_fetch, "probe_url_ttl",
                          return_value=(None, {"observed": False,
                                               "reason": "no_directive"})):
            res = fast_fetch.resolve_live_source(stored["id"])

        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], GOOGLE_URL)
        self.assertTrue(res["refreshed"])
        self.assertTrue(res["is_temporary"],
                        "a minted Google URL is never permanent")
        self.assertEqual(resolvers.calls,
                         [("filepress", PAGE_NEW3, True)])
        with fast_fetch.get_db() as conn:
            row = conn.execute(
                "SELECT final_url, is_temporary, status FROM download_sources"
                " WHERE id = ?", (stored["id"],)).fetchone()
        self.assertEqual(row["final_url"], GOOGLE_URL)
        self.assertEqual((row["is_temporary"], row["status"]), (1, "active"))


if __name__ == "__main__":
    unittest.main()
