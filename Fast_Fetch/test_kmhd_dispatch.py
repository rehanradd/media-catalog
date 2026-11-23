"""Offline dispatch tests for the kmhd bridge wiring (2026-11-23).

The KatMovieHD bridge API (``fetch_kmhd_bridge``) existed but no play-time
branch called it, so every kmhd row answered ``no_unwrapper``. The new
branch in ``resolve_live_source`` routes ``links.kmhd.me/file/<id>`` rows
through the bridge; the HTTP layer is mocked throughout with canned
responses matching the live shape measured 2026-11-23 from Oracle egress
(``api.dandndn.one`` answers in ~2-3 s with hubdrive / gdflix / katdrive /
1fichier / sendcm mirrors for a real file id such as ``Ever_f8202fab``).

Two live facts the tests pin, because the naive fan-out gets them wrong:

* the bridge's raw ``hubcloud_10g`` href is NOT playable — the pixel host
  302s to a workers.dev root and ends at an HTML "Link Generator" page —
  so the branch re-resolves the hubdrive id through the verified
  ``hr.unwrap_hubcloud(verify=True)`` path instead of serving the href;
* the gdflix mirror is NOT unwrapped here — ``unwrap_gdflix`` returns it
  unchanged from service egress (403 Turnstile; the sweep needs Tor).

Offline — temp SQLite file, mocked HTTP/unwrappers/probe. Run:
``python3 -S Fast_Fetch/test_kmhd_dispatch.py``
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import current_hosts
import fast_fetch

# Live shape, measured 2026-11-23 from Oracle egress for file id
# Ever_f8202fab (download_sources id 868742, release 473189): the bridge
# API returned these five mirrors in ~2.97 s. Only the resource_id of the
# hubcloud row matters to the branch (it is the hubdrive id); the pixel
# href is deliberately the real shape — a bare client must NOT serve it.
LIVE_MIRRORS_NO_R2 = [
    {
        "source_name": "\u26a1 HubCloud [10Gbps Fast Server]",
        "service": "hubcloud_10g",
        "resource_id": "wrdyafkfs8ak",
        "source_url": "https://hubcloud.mov/drive/wrdyafkfs8ak",
        "final_url": "https://pixel.hubcloud.mov/?id=wrdyafkfs8ak",
        "is_temporary": 0,
    },
    {
        "source_name": "\u26a1 GDFlix [KatMirror]",
        "service": "gdflix",
        "resource_id": "v0Jz07X4kUgaRBK",
        "source_url": "https://new10.gdflix.io/file/v0Jz07X4kUgaRBK",
        "final_url": "https://new10.gdflix.io/file/v0Jz07X4kUgaRBK",
        "is_temporary": 0,
    },
    {
        "source_name": "\u26a1 KatDrive [Ultra Speed]",
        "service": "katdrive",
        "resource_id": "1759900018",
        "source_url": "https://katdrive.net/file/1759900018",
        "final_url": "https://katdrive.net/file/1759900018",
        "is_temporary": 0,
    },
    {
        "source_name": "\u26a1 1fichier Cloud",
        "service": "1fichier",
        "resource_id": "?e4b6ixjrof0",
        "source_url": "https://1fichier.com/?e4b6ixjrof0",
        "final_url": "https://1fichier.com/?e4b6ixjrof0",
        "is_temporary": 0,
    },
    {
        "source_name": "\u26a1 Send.cm Mirror",
        "service": "sendcm",
        "resource_id": "h3p22hanqjjb",
        "source_url": "https://send.cm/h3p22hanqjjb",
        "final_url": "https://send.cm/h3p22hanqjjb",
        "is_temporary": 0,
    },
]

# The bridge's exception fallback: a single kmhd row pointing at the
# bridge page itself (what hatch egress returns — the API is unreachable
# there, so the bridge yields only this row after ~12 s).
BRIDGE_FALLBACK_ONLY = [
    {
        "source_name": "KMHD Bridge",
        "service": "kmhd",
        "resource_id": "Ever_f8202fab",
        "source_url": "https://links.kmhd.me/file/Ever_f8202fab",
        "final_url": "https://links.kmhd.me/file/Ever_f8202fab",
        "is_temporary": 0,
    },
]

# The verified hubcloud verdict for the drive URL rebuilt from the
# hubcloud row above — measured live: status active, final host
# index.herokucdn.workers.dev, 64-hex path. The final URL here is a
# same-shape stand-in (64 a's, not the live file's id) so the test stays
# offline; its host is not a bridge host, so the post-dispatch guard
# keeps it exactly as it keeps the live one.
HUBCLOUD_ACTIVE_VERDICT = {
    "status": "active",
    "final_url": "https://index.herokucdn.workers.dev/" + "a" * 64,
    "service": "hubcloud",
    "is_temporary": 0,
    "content_type": "video/mp4",
    "size_bytes": 1234567,
    "http_status": 206,
}

PASSING_FILE = {"ok": True, "kind": "file", "mime": "video/mp4",
                "size": None, "resumable": True, "renditions": [],
                "failure": None}
FAILING = {"ok": False, "kind": None, "mime": None, "size": None,
           "resumable": False, "renditions": [], "failure": "probe_error"}


class KmhdDispatchTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "test.db"
        self.db_path_patch = patch.object(fast_fetch, "DB_PATH", self.db_path)
        self.db_path_patch.start()
        self.cinemeta_patch = patch.object(
            fast_fetch, "fetch_cinemeta_metadata", return_value=None)
        self.cinemeta_patch.start()
        # Stage-1 dispatch is stubbed off: no fixture backend is proven
        # here, so the legacy branches run exactly as in production for
        # an "unknown" backend.
        self.stage1_backend_patch = patch.object(
            current_hosts, "classify_backend", return_value="unknown")
        self.stage1_backend_patch.start()
        # The real host_resolvers module: a bare Mock would answer every
        # `hr.is_*_player(...)` predicate truthy and swallow the row in
        # an earlier branch. Only unwrap_hubcloud is stubbed, per test.
        self.real_hr = fast_fetch._embed_resolvers()
        self.assertIsNotNone(self.real_hr)
        fast_fetch.init_db()

    def tearDown(self):
        self.stage1_backend_patch.stop()
        self.cinemeta_patch.stop()
        self.db_path_patch.stop()
        self.temp_dir.cleanup()

    @staticmethod
    def _probe_for(*, fail_hosts=()):
        """A _stage2_probe stub: the kmhd bridge page fails (it is HTML,
        as the live gate proves), every other address passes."""
        def _probe(url):
            if any(h in (url or "") for h in fail_hosts):
                return dict(FAILING)
            return dict(PASSING_FILE)
        return Mock(side_effect=_probe)

    @staticmethod
    def _media(source_url, final_url, service="kmhd"):
        return {
            "url": "https://katmoviehd.example/example-film/",
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
            "source_site": "katmoviehd",
            "industry": "Hollywood",
            "country": "USA",
            "language": "English",
            "canonical_key": fast_fetch.generate_canonical_key(
                "Example Film", 2024),
            "tmdb_id": None,
            "imdb_id": None,
            "releases": [
                {
                    "release_title": "Example Film 1080p",
                    "quality": "1080p",
                    "codec": "x264",
                    "size": "1GB",
                    "languages": "English",
                    "season": None,
                    "episode": None,
                    "intermediate_url": "https://katmoviehd.example/release",
                    "sources": [
                        {
                            "source_name": "KMHD Bridge",
                            "service": service,
                            "resource_id": "Ever_f8202fab",
                            "source_url": source_url,
                            "final_url": final_url,
                            "is_temporary": 0,
                        }
                    ],
                }
            ],
        }

    def _save_kmhd_row(self, source_url, final_url, service="kmhd"):
        fast_fetch.save_scraped_data(
            self._media(source_url, final_url, service=service))
        with fast_fetch.get_db() as conn:
            row = conn.execute(
                "SELECT id FROM download_sources LIMIT 1").fetchone()
        return row["id"]

    def _hubcloud_patch(self, verdict):
        """Patch unwrap_hubcloud on the real resolvers module; returns
        the patcher so the test can assert on the mock."""
        return patch.object(self.real_hr, "unwrap_hubcloud",
                            return_value=verdict)

    def test_hubcloud_mirror_resolves_via_verified_unwrap(self):
        """The live Ever_f8202fab shape: no direct mirror, so the branch
        re-resolves the hubdrive id through hr.unwrap_hubcloud and serves
        its verified URL — never the raw pixel href."""
        source_id = self._save_kmhd_row(
            "https://links.kmhd.me/file/Ever_f8202fab",
            "https://links.kmhd.me/file/Ever_f8202fab")
        probe = self._probe_for(fail_hosts=("kmhd.me",))
        with patch.object(fast_fetch, "fetch_kmhd_bridge",
                          return_value=[dict(m) for m in LIVE_MIRRORS_NO_R2]) as bridge, \
             self._hubcloud_patch(HUBCLOUD_ACTIVE_VERDICT) as hub_unwrap, \
             patch.object(fast_fetch, "_stage2_probe", probe):
            res = fast_fetch.resolve_live_source(source_id)
        bridge.assert_called_once_with("Ever_f8202fab")
        # The drive URL is rebuilt from the hubcloud row's resource_id
        # (the hubdrive id), on the registry's current hubcloud domain.
        hub_unwrap.assert_called_once()
        drive_url = hub_unwrap.call_args.args[0]
        self.assertIn("/drive/wrdyafkfs8ak", drive_url)
        self.assertTrue(res["success"], res)
        self.assertEqual(
            res["url"],
            "https://index.herokucdn.workers.dev/" + "a" * 64)
        self.assertTrue(res["refreshed"])
        self.assertFalse(res["cached"])
        # The pixel href itself is never served, and the gate probe never
        # pays to re-prove the unwrapper's verified link.
        self.assertNotIn("pixel.hubcloud.mov", res["url"])
        probed = [c.args[0] for c in probe.call_args_list]
        self.assertNotIn(
            "https://pixel.hubcloud.mov/?id=wrdyafkfs8ak", probed)

    def test_direct_r2_mirror_wins_without_reunwrap(self):
        """When the bridge returns a signed R2 mirror, the branch serves
        it after one gate probe and never pays the hubcloud re-unwrap."""
        mirrors = [
            {
                "source_name": "\u26a1 HubCloud [FSL Cloudflare R2]",
                "service": "fsl_r2",
                "resource_id": "wrdyafkfs8ak",
                "source_url": "https://hubcloud.mov/drive/wrdyafkfs8ak",
                "final_url": "https://example-r2.cloudflarestorage.com/file.mkv",
                "is_temporary": 1,
            },
        ] + [dict(m) for m in LIVE_MIRRORS_NO_R2]
        source_id = self._save_kmhd_row(
            "https://links.kmhd.me/file/Ever_f8202fab",
            "https://links.kmhd.me/file/Ever_f8202fab")
        probe = self._probe_for(fail_hosts=("kmhd.me",))
        with patch.object(fast_fetch, "fetch_kmhd_bridge",
                          return_value=mirrors), \
             self._hubcloud_patch(HUBCLOUD_ACTIVE_VERDICT) as hub_unwrap, \
             patch.object(fast_fetch, "_stage2_probe", probe):
            res = fast_fetch.resolve_live_source(source_id)
        self.assertTrue(res["success"], res)
        self.assertEqual(
            res["url"], "https://example-r2.cloudflarestorage.com/file.mkv")
        self.assertTrue(res["is_temporary"])
        hub_unwrap.assert_not_called()
        probed = [c.args[0] for c in probe.call_args_list]
        self.assertIn("https://example-r2.cloudflarestorage.com/file.mkv",
                      probed)

    def test_bridge_fallback_row_is_never_served(self):
        """When the bridge API is unreachable it yields only its own
        fallback row (service kmhd, bridge page). That page must never
        be served as a stream — the answer is an honest failure."""
        source_id = self._save_kmhd_row(
            "https://links.kmhd.me/file/Ever_f8202fab",
            "https://links.kmhd.me/file/Ever_f8202fab")
        probe = self._probe_for(fail_hosts=("kmhd.me",))
        with patch.object(fast_fetch, "fetch_kmhd_bridge",
                          return_value=[dict(m) for m in BRIDGE_FALLBACK_ONLY]), \
             self._hubcloud_patch(HUBCLOUD_ACTIVE_VERDICT) as hub_unwrap, \
             patch.object(fast_fetch, "_stage2_probe", probe):
            res = fast_fetch.resolve_live_source(source_id)
        self.assertFalse(res["success"], res)
        self.assertEqual(res["code"], "no_playable_source", res)
        self.assertNotEqual(res.get("url"),
                            "https://links.kmhd.me/file/Ever_f8202fab")
        hub_unwrap.assert_not_called()

    def test_archive_row_never_calls_the_bridge(self):
        """The 17,464 kmhd.me/archives/<n> rows carry no bridge file id
        (the page holds other hosts' buttons, no links.kmhd.me/file
        link), so the branch must not pay the API call for them."""
        source_id = self._save_kmhd_row(
            "https://kmhd.me/archives/28166",
            "https://kmhd.eu/archives/28166")
        probe = self._probe_for(fail_hosts=("kmhd.me", "kmhd.eu"))
        with patch.object(fast_fetch, "fetch_kmhd_bridge") as bridge, \
             self._hubcloud_patch(HUBCLOUD_ACTIVE_VERDICT) as hub_unwrap, \
             patch.object(fast_fetch, "_stage2_probe", probe):
            res = fast_fetch.resolve_live_source(source_id)
        bridge.assert_not_called()
        hub_unwrap.assert_not_called()
        self.assertFalse(res["success"], res)
        self.assertEqual(res["code"], "no_playable_source", res)

    def test_play_and_pack_shapes_never_call_the_bridge(self):
        """links.kmhd.me/play?id=… and /pack/… are not /file/<id>
        addresses, so the bridge API (keyed by file id) is not called."""
        for url in ("https://links.kmhd.me/play?id=5ec53831",
                    "https://links.kmhd.me/pack/362e39fa"):
            source_id = self._save_kmhd_row(url, url)
            probe = self._probe_for(fail_hosts=("kmhd.me",))
            with patch.object(fast_fetch, "fetch_kmhd_bridge") as bridge, \
                 patch.object(fast_fetch, "_stage2_probe", probe):
                res = fast_fetch.resolve_live_source(source_id)
            bridge.assert_not_called()
            self.assertFalse(res["success"], res)
            with fast_fetch.get_db() as conn:
                conn.execute("DELETE FROM download_sources")

    def test_unrelated_service_row_is_unaffected(self):
        """A non-kmhd row resolves exactly as before: cached success,
        bridge never called, RESOLVER_VERSION bump is the only change
        the row can see."""
        source_id = self._save_kmhd_row(
            "https://cdn.example/source",
            "https://cdn.example/film.mkv",
            service="test-service")
        probe = self._probe_for()
        with patch.object(fast_fetch, "fetch_kmhd_bridge") as bridge, \
             patch.object(fast_fetch, "_stage2_probe", probe):
            res = fast_fetch.resolve_live_source(source_id)
        bridge.assert_not_called()
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], "https://cdn.example/film.mkv")
        self.assertTrue(res["cached"])

    def test_resolver_version_covers_kmhd_rows(self):
        """The v6→v7 bump: rows that failed under v6 with no_unwrapper
        are re-pressed instead of being refused from the negative cache."""
        self.assertEqual(fast_fetch.RESOLVER_VERSION, 11)


if __name__ == "__main__":
    unittest.main(verbosity=2)
