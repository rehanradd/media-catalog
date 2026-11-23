"""Dispatch tests for the StreamWish-family mirror fix (2026-11-23).

Offline only: a temp SQLite DB (RADD_DB_PATH pattern from
test_database.py), a stubbed Stage-2 probe, and a scripted HTTP layer
keyed on exact URLs. No network, no production DB.

What is proven here:
- a StreamWish-family mirror service (sbbrisk) reaches the packer branch
  and resolves to a verified HLS master (the branch it fell through as
  no_unwrapper before this fix);
- a strwish loader URL is rewritten to the niramirus packer page its
  main.js navigates to (measured live 2026-11-23) and resolves there —
  the strwish host itself is never fetched;
- the pre-existing hglink.to -> vibuxer hop still goes through
  unwrap_streamwish, never the packer branch;
- a vibuxer /e/ embed still goes through unwrap_vibuxer, never the
  streamwish or packer branches (no double-handling of vibuxer links);
- a mirror that answers with a loader shell fails honestly
  (unwrap_failed), and an unknown service still falls through unprobed.
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "importers"))
import current_hosts
import fast_fetch
import host_resolvers as hr


# ---------------------------------------------------------------------------
# Canned pages
# ---------------------------------------------------------------------------

# Minimal Dean Edwards packer page, same eval shape as the repo's own
# PACKED_PAGE fixture in test_host_resolvers.py: payload "0", radix 36,
# count 1, one key holding the unpacked config snippet. find_packed_config
# substitutes the single token and returns the snippet verbatim.
_PACKER_FN = (
    "eval(function(p,a,c,k,e,d){e=function(c){return c};"
    "if(!''.replace(/^/,String)){while(c--)if(k[c])p=p.replace("
    "new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),k[c]);return p}"
    "('0',36,1,'%s'.split('|')))"
)


def _packer_page(snippet):
    return ("<html><head><title>embed</title></head><body>"
            "<script type='text/javascript'>"
            + (_PACKER_FN % snippet) +
            "</script></body></html>").encode("utf-8")


# The loader shell every StreamWish-family loader answers with today
# (hglink.to and strwish.com, both 819 bytes live): no packed config.
LOADER_SHELL = (b"<html><head><title>Loading...</title></head><body>"
                b"<p>Loading...</p>"
                b"<script src=\"/main.js?v=1.1.9\"></script>"
                b"</body></html>")

MASTER_URL = "https://cdn.example/hls2/01/1/master.m3u8?s=1795471653&e=129600"
MASTER_BODY = b"#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nv.m3u8\n"
MASTER_HEADERS = {"content-type": "application/vnd.apple.mpegurl",
                  "content-range": "bytes 0-511/12345"}


def _scripted_http(script):
    """Exact-URL fake for host_resolvers.http_request.

    `script` maps a URL to (status, body, headers). Any other URL raises:
    the test must account for every fetch the code makes, so a stray
    request (a missed redirect hop, a re-fetch of the loader) fails the
    test instead of silently 404ing.
    """
    calls = []

    def fake(url, headers=None, timeout=None, method="GET", follow=True,
             data=None, **_kw):
        calls.append((url, dict(headers or {}), method))
        if url in script:
            status, body, extra = script[url]
            return status, body, extra, url
        raise AssertionError("unexpected HTTP fetch: %s" % url)

    fake.calls = calls
    return fake


class StreamwishDispatchTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test.db"
        self.db_path_patch = patch.object(fast_fetch, "DB_PATH", self.db_path)
        self.db_path_patch.start()
        self.cinemeta_patch = patch.object(
            fast_fetch, "fetch_cinemeta_metadata", return_value=None)
        self.cinemeta_patch.start()
        # Stage-2 gate: the resolver probes every candidate address, and
        # this suite is offline. Fail every probe except an .m3u8 master,
        # so the cached embed page always falls through to the dispatch
        # while a genuinely resolved master still passes the gate.
        self.stage2_probe_patch = patch.object(
            fast_fetch, "_stage2_probe", side_effect=self._probe)
        self.stage2_probe_patch.start()
        self.stage1_backend_patch = patch.object(
            current_hosts, "classify_backend", return_value="unknown")
        self.stage1_backend_patch.start()
        # probe_url_ttl knocks on the resolved URL with real urllib; the
        # master carries its lifetime in s+e, so there is nothing to
        # observe from headers here.
        self.ttl_patch = patch.object(
            fast_fetch, "probe_url_ttl",
            return_value=(None, {"observed": False, "reason": "no_directive"}))
        self.ttl_patch.start()
        fast_fetch.init_db()

    def tearDown(self):
        self.ttl_patch.stop()
        self.stage1_backend_patch.stop()
        self.stage2_probe_patch.stop()
        self.cinemeta_patch.stop()
        self.db_path_patch.stop()
        self.temp_dir.cleanup()

    @staticmethod
    def _probe(url, *args, **kwargs):
        if url.split("?")[0].endswith(".m3u8"):
            return {"ok": True, "kind": "playlist",
                    "mime": "application/vnd.apple.mpegurl", "size": None,
                    "resumable": False, "renditions": [], "failure": None}
        return {"ok": False, "kind": None, "mime": None, "size": None,
                "resumable": False, "renditions": [], "failure": "probe_error"}

    def _save_source(self, service, source_url, final_url=None):
        media = {
            "url": "https://site-a.example/example-film/",
            "slug": "example-film",
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
            "source_site": "site_a",
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
                "intermediate_url": "https://site-a.example/release",
                "sources": [{
                    "source_name": "Example CDN",
                    "service": service,
                    "resource_id": "site_a-id",
                    "source_url": source_url,
                    "final_url": final_url if final_url is not None
                    else source_url,
                    "is_temporary": 0,
                }],
            }],
        }
        fast_fetch.save_scraped_data(media)
        with fast_fetch.get_db() as conn:
            return conn.execute(
                "SELECT id FROM download_sources LIMIT 1").fetchone()["id"]

    def _row(self, source_id):
        with fast_fetch.get_db() as conn:
            return conn.execute(
                "SELECT final_url, is_temporary, status, last_failed_at,"
                " failure_class FROM download_sources WHERE id = ?",
                (source_id,)).fetchone()

    # -- the fix: mirror services reach the packer branch -------------------

    def test_sbbrisk_mirror_routes_to_packer_branch_and_resolves(self):
        """A sbbrisk row (one of the 32 mirror labels) resolves through
        unwrap_packer_hls and banks the verified master."""
        embed = "https://sbbrisk.com/e/testid123"
        snippet = '"hls2":"%s"' % MASTER_URL
        fake = _scripted_http({
            embed: (200, _packer_page(snippet), {}),
            MASTER_URL: (206, MASTER_BODY, MASTER_HEADERS),
        })
        source_id = self._save_source("sbbrisk", embed)
        with patch.object(hr, "http_request", fake), \
             patch.object(hr, "unwrap_streamwish",
                          side_effect=AssertionError(
                              "sbbrisk must not reach unwrap_streamwish")):
            res = fast_fetch.resolve_live_source(source_id)
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], MASTER_URL, res)
        self.assertFalse(res["cached"], res)
        row = self._row(source_id)
        self.assertEqual(row["final_url"], MASTER_URL, row)
        self.assertEqual(row["is_temporary"], 1, row)
        self.assertEqual(row["status"], "active", row)
        self.assertIsNone(row["failure_class"], row)
        fetched = [url for url, _h, _m in fake.calls]
        self.assertEqual(fetched, [embed, MASTER_URL], fetched)

    def test_strwish_loader_redirects_to_niramirus_and_resolves(self):
        """A strwish loader URL is rewritten to the niramirus packer page
        its main.js navigates to — the strwish host itself is never
        fetched, and the packer branch resolves the niramirus page."""
        loader = "https://strwish.com/e/testid123"
        packer = "https://niramirus.com/e/testid123"
        snippet = '"hls2":"%s"' % MASTER_URL
        fake = _scripted_http({
            packer: (200, _packer_page(snippet), {}),
            MASTER_URL: (206, MASTER_BODY, MASTER_HEADERS),
        })
        source_id = self._save_source("strwish", loader)
        with patch.object(hr, "http_request", fake):
            res = fast_fetch.resolve_live_source(source_id)
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], MASTER_URL, res)
        row = self._row(source_id)
        self.assertEqual(row["final_url"], MASTER_URL, row)
        fetched = [url for url, _h, _m in fake.calls]
        self.assertEqual(fetched, [packer, MASTER_URL], fetched)
        self.assertNotIn(loader, fetched)

    # -- pre-existing routes must not move ----------------------------------

    def test_hglink_loader_still_uses_streamwish_unwrapper(self):
        """hglink.to/<id> still goes through unwrap_streamwish (loader ->
        vibuxer packed page, hls4 first), never the packer branch."""
        loader = "https://hglink.to/testid123"
        vibuxer = "https://vibuxer.com/testid123"
        hls4 = "https://vibuxer.com/stream/abc/master.m3u8"
        hls2 = MASTER_URL
        snippet = ('"hls4":"/stream/abc/master.m3u8",'
                   '"hls3":"https://example.com/hls3/master.txt",'
                   '"hls2":"%s"' % hls2)
        fake = _scripted_http({
            loader: (200, LOADER_SHELL, {}),
            vibuxer: (200, _packer_page(snippet), {}),
            hls4: (206, MASTER_BODY, MASTER_HEADERS),
        })
        source_id = self._save_source("streamwish", loader)
        with patch.object(hr, "http_request", fake), \
             patch.object(hr, "unwrap_packer_hls",
                          side_effect=AssertionError(
                              "hglink must not reach unwrap_packer_hls")):
            res = fast_fetch.resolve_live_source(source_id)
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], hls4, res)
        row = self._row(source_id)
        self.assertEqual(row["final_url"], hls4, row)
        fetched = [url for url, _h, _m in fake.calls]
        self.assertIn(loader, fetched)
        self.assertIn(vibuxer, fetched)
        # The vibuxer fetch carries Referer: the loader, as the live
        # chain does; the hls4 probe is never fetched bare.
        vibuxer_call = [c for c in fake.calls if c[0] == vibuxer][0]
        self.assertEqual(vibuxer_call[1].get("Referer"), loader)

    def test_vibuxer_embed_still_uses_vibuxer_unwrapper(self):
        """A vibuxer /e/ embed still goes through unwrap_vibuxer — the
        streamwish branch (bare-id only) and the packer branch must not
        claim it."""
        embed = "https://vibuxer.com/e/testid123"
        master = "https://cdn.example/vibuxer/master.m3u8?s=1795471653&e=129600"
        snippet = ('jwplayer("vplayer").setup({sources:[{file:"%s"}]})'
                   % master)
        fake = _scripted_http({
            embed: (200, _packer_page(snippet), {}),
            master: (206, MASTER_BODY, MASTER_HEADERS),
        })
        source_id = self._save_source("vibuxer-mirror", embed)
        with patch.object(hr, "http_request", fake), \
             patch.object(hr, "unwrap_streamwish",
                          side_effect=AssertionError(
                              "vibuxer /e/ must not reach unwrap_streamwish")), \
             patch.object(hr, "unwrap_packer_hls",
                          side_effect=AssertionError(
                              "vibuxer /e/ must not reach unwrap_packer_hls")):
            res = fast_fetch.resolve_live_source(source_id)
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], master, res)
        row = self._row(source_id)
        self.assertEqual(row["final_url"], master, row)

    # -- honest failures -----------------------------------------------------

    def test_mirror_loader_shell_fails_honestly(self):
        """A mirror that answers with the JS loader shell (no packed
        config) is routed to the packer branch and fails as unwrap_failed
        — never served, never silently unprobed."""
        embed = "https://sbbrisk.com/e/deadid"
        fake = _scripted_http({embed: (200, LOADER_SHELL, {})})
        source_id = self._save_source("sbbrisk", embed)
        with patch.object(hr, "http_request", fake):
            res = fast_fetch.resolve_live_source(source_id)
        self.assertFalse(res["success"], res)
        self.assertEqual(res["code"], "no_playable_source", res)
        row = self._row(source_id)
        self.assertEqual(row["failure_class"], "unwrap_failed", row)
        self.assertIsNotNone(row["last_failed_at"], row)
        self.assertEqual([url for url, _h, _m in fake.calls], [embed])

    def test_unknown_service_still_falls_through_unprobed(self):
        """Control: a service with no branch still records no_unwrapper."""
        source_id = self._save_source(
            "test-service", "https://site_a.example/source",
            final_url="https://cdn.example/site_a/page.html")
        with patch.object(hr, "unwrap_packer_hls",
                          side_effect=AssertionError(
                              "unknown service must not reach the packer")):
            res = fast_fetch.resolve_live_source(source_id)
        self.assertFalse(res["success"], res)
        self.assertEqual(res["code"], "no_playable_source", res)
        row = self._row(source_id)
        self.assertEqual(row["failure_class"], "no_unwrapper", row)

    # -- the helper itself ---------------------------------------------------

    def test_streamwish_loader_redirect(self):
        self.assertEqual(
            hr.streamwish_loader_redirect("https://strwish.com/e/xuveuqe1gnwl"),
            "https://niramirus.com/e/xuveuqe1gnwl")
        self.assertEqual(
            hr.streamwish_loader_redirect("https://strwish.com/e/xuveuqe1gnwl/"),
            "https://niramirus.com/e/xuveuqe1gnwl")
        for other in ("https://hglink.to/xuveuqe1gnwl",
                      "https://sbbrisk.com/e/xuveuqe1gnwl",
                      "https://vibuxer.com/e/xuveuqe1gnwl",
                      "https://watch-online.mom/e/xuveuqe1gnwl",
                      "https://strwish.com/",
                      "https://strwish.com/e/"):
            self.assertEqual(hr.streamwish_loader_redirect(other), "", other)

    def test_mirror_service_list(self):
        # The coverage-map census behind this fix: 32 labels.
        self.assertEqual(len(hr.STREAMWISH_MIRROR_SERVICES), 32)
        for label in ("sbbrisk", "sbfast", "sblongvu", "strwish", "wishfast",
                      "swhoi", "swdyu", "sbnet-mirror", "flaswish",
                      "flastwish", "cdnwish", "hlswish"):
            self.assertIn(label, hr.STREAMWISH_MIRROR_SERVICES, label)
        for label in ("streamwish", "stream", "vidmoly", "vibuxer",
                      "viewsb", "likessb", "streamlare", "filelions"):
            self.assertNotIn(label, hr.STREAMWISH_MIRROR_SERVICES, label)


if __name__ == "__main__":
    unittest.main()
