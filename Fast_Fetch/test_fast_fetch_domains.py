#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Domain-drift reconciliation tests (CROWN ORDER qoder14_ruling, 2026-11-05).

Covers the approved slice in ``fast_fetch.py``:
  * hunk A/B — ``registry_live_domains()`` + the read-time overlay at the tail
    of ``get_domains()``: ``domain_registry.json`` outranks BOTH the static
    ``DEFAULT_DOMAINS`` and the stale ``domains_config`` DB rows (the DB
    overrides the static table, so a static-only fix is a no-op).
  * hunk F — retirement markers: drifted values stay in place (no host string
    is ever removed; ``redact._registry_needles()`` harvests the registry into
    the PUBLIC SCRUBBER, so deletion would silently shrink redaction).
  * hunk G — ``refresh_domains()``: the CLI/sync fresh read. Documents the
    ``DOMAINS = get_domains()`` import-time staleness; no hot-reload.
  * the needle-superset guard: the redactor's needle set derived from the
    registry must never lose a host that existed before this change (98 → 98).
  * the second ``katmovie`` block in ``normalize_url``: shadowed by the first
    block whenever the always-present ``katmoviehd`` key exists; its only live
    reference is the guard-escape case, pinned here, retirement marker not
    deletion.

Offline: no network, no production DB. The synthetic registries and DBs are
temp files / in-memory sqlite.
"""

from __future__ import annotations

import contextlib
import io
import json
import pathlib
import sqlite3
import sys
import tempfile
import unittest
import urllib.parse
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import fast_fetch as ff  # noqa: E402
import redact  # noqa: E402

#: Snapshot of redact._registry_needles() RE-TAKEN on rebase onto main
#: 14d9063 (2026-11-11): main's drift work rotated 18 dead mirrors out
#: of domain_registry.json and 32 new hosts in since the branch base, so
#: the 2026-11-05 snapshot (71 needles) no longer matches the live set.
#: This snapshot is the new baseline: if a host disappears from the live
#: set AFTER this point, the public scrubber is shrinking — that is a
#: release-blocking regression, not a cleanup.
NEEDLES_BEFORE = frozenset({
    "10xflix", "10xflix.asia", "10xflix.bond", "1vegamovies",
    "1vegamovies.shop", "7starhd", "7starhd.dentist", "7starhd.surgery",
    "9xmovie", "9xmovie.bayern", "9xmovie.camera", "9xmovies",
    "bolly4u", "bolly4u.lgbt", "bolly4u.lol", "bolly4uv",
    "bolly4uv.com", "bulwik", "castle", "co",
    "desicinemas", "dramadog", "dramadog.app", "filmyzilla",
    "filmyzilla74", "filmyzilla75", "filmyzilla80", "filmyzilla80.com",
    "filmyzillahq", "filmyzillahq.in", "harpalgeo", "harpalgeo.tv",
    "hdhub4u", "hdhub4us.co.in", "hdtvb", "hdtvb.net",
    "hdutv", "hdutv.com", "hindilinks4u", "hindilinks4u.pink",
    "katmoviehd", "katmoviehd.mom", "katmoviehd.top", "katmovieshd",
    "katmovieshd.net", "m1.multimovies.courses", "megacartoons", "megacartoons.net",
    "moviedrive", "moviedrive.org", "movienestbd", "movienestbd.best",
    "moviesdrive", "moviesdrives", "moviesdrives.bid", "moviesdrives.cfd",
    "moviezguru", "moviezguru.guru", "moviezguru.technology", "multimovies",
    "multimovies.garden", "new.katmoviehd.top", "new1.moviesdrive.cyou", "new1.ssrmovies.page",
    "new1.vegamovies.lease", "new11.hdhub4u.cl", "new12.hdhub4u.cl", "new2.moviesdrive.cyou",
    "new4.moviesdrive.beer", "rareanimes", "rogmovies", "rogmovies.asia",
    "ssrmovies", "ssrmovies.gripe", "ssrmovies.quest", "ssrmovies.surf",
    "thefilmyzilla", "thefilmyzilla.in", "toonworld4all", "toonworld4all.me",
    "vegamovies", "vegamovies.lease", "world4ufree", "world4ufree.gay",
    "world4ufree.tours", "www.bulwik.com", "www.desicinemas.pk", "www.filmyzilla74.com",
    "www.filmyzilla75.com", "www.filmyzilla80.com", "www.harpalgeo.tv", "www.hindilinks4u.zip",
    "www.megacartoons.net", "www.rareanimes.fans", "www.rareanimes.mov", "www.toonworld4all.me",
    "xprimehub", "xprimehub.pics"
})


@contextlib.contextmanager
def _temp_registry(sites: dict):
    tmp = tempfile.TemporaryDirectory()
    try:
        path = pathlib.Path(tmp.name) / "domain_registry.json"
        path.write_text(json.dumps({"version": "1.0", "sites": sites}),
                        encoding="utf-8")
        yield path
    finally:
        tmp.cleanup()


def _memory_db(rows: list[tuple[str, str]]):
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.execute("CREATE TABLE domains_config (name TEXT PRIMARY KEY, "
                 "url TEXT, status TEXT, last_checked TEXT)")
    conn.executemany("INSERT INTO domains_config (name, url) VALUES (?, ?)",
                     rows)
    conn.commit()
    return conn


class TestRegistryLiveDomains(unittest.TestCase):
    def test_active_sites_are_returned(self):
        sites = {"vegamovies": {"active_base_url": "https://new1.vegamovies.lease",
                                "status": "active"}}
        with _temp_registry(sites) as reg:
            with mock.patch.object(ff, "REGISTRY_PATH", reg):
                self.assertEqual(ff.registry_live_domains(),
                                 {"vegamovies": "https://new1.vegamovies.lease"})

    def test_retired_site_is_not_served(self):
        # hdtvb is retired yet lists https://hdutv.com: the overlay must
        # not serve it. (The scrubber still harvests its strings — that
        # pin lives in TestNeedleSuperset.test_retired_rows_are_still_harvested.)
        sites = {"hdtvb": {"active_base_url": "https://hdutv.com",
                           "status": "retired"},
                 "vegamovies": {"active_base_url": "https://vegamovies.latino",
                                "status": "active"}}
        with _temp_registry(sites) as reg:
            with mock.patch.object(ff, "REGISTRY_PATH", reg):
                self.assertEqual(ff.registry_live_domains(),
                                 {"vegamovies": "https://vegamovies.latino"})

    def test_down_status_is_not_preferred(self):
        sites = {"rogmovies": {"active_base_url": "https://rogmovies.one",
                               "status": "down"}}
        with _temp_registry(sites) as reg:
            with mock.patch.object(ff, "REGISTRY_PATH", reg):
                self.assertEqual(ff.registry_live_domains(), {})

    def test_missing_status_still_counts(self):
        # hindilinks4u/toonworld4all carry status=None in the live registry;
        # unknown is not "down" and must not be dropped.
        sites = {"toonworld4all": {"active_base_url": "https://toonworld4all.me"}}
        with _temp_registry(sites) as reg:
            with mock.patch.object(ff, "REGISTRY_PATH", reg):
                self.assertIn("toonworld4all", ff.registry_live_domains())

    def test_non_https_and_blank_bases_are_skipped(self):
        sites = {
            "a": {"active_base_url": "http://plain.example", "status": "active"},
            "b": {"active_base_url": "", "status": "active"},
            "c": {"status": "active"},
            "d": "not-a-dict",
        }
        with _temp_registry(sites) as reg:
            with mock.patch.object(ff, "REGISTRY_PATH", reg):
                self.assertEqual(ff.registry_live_domains(), {})

    def test_missing_registry_is_empty_not_raise(self):
        missing = pathlib.Path(tempfile.gettempdir()) / "no_such_registry_q14.json"
        with mock.patch.object(ff, "REGISTRY_PATH", missing):
            self.assertEqual(ff.registry_live_domains(), {})

    def test_malformed_registry_is_empty_not_raise(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        path = pathlib.Path(tmp.name) / "domain_registry.json"
        path.write_text("{not json", encoding="utf-8")
        with mock.patch.object(ff, "REGISTRY_PATH", path):
            self.assertEqual(ff.registry_live_domains(), {})


class TestGetDomainsOverlay(unittest.TestCase):
    """The overlay must outrank the stale DB rows at read time, in place."""

    STALE_DB = [
        # The seven rows crown measured as stale in domains_config, plus one
        # row that agrees with the registry.
        ("vegamovies", "https://vegamovies.gallery"),
        ("vegamovies_sitemap", "https://vegamovies.gallery/sitemap.xml"),
        ("ssrmovies", "https://ssrmovies.gripe"),
        ("7starhd", "https://7starhd.rich"),
        ("10xflix", "https://10xflix.help"),
        ("multimovies", "https://multimovies.garden"),
        ("hdhub4u", "https://hdtvb.net"),
        ("hdhub4u_sitemap", "https://hdtvb.net/sitemap.xml"),
        ("filmyzilla", "https://www.filmyzilla74.com"),
        ("rogmovies", "https://rogmovies.one"),
    ]

    LIVE_REGISTRY = {
        "vegamovies": {"active_base_url": "https://new1.vegamovies.lease",
                       "status": "active"},
        "ssrmovies": {"active_base_url": "https://ssrmovies.surf",
                      "status": "active"},
        "7starhd": {"active_base_url": "https://7starhd.dentist",
                    "status": "active"},
        "10xflix": {"active_base_url": "https://10xflix.hair",
                    "status": "active"},
        "multimovies": {"active_base_url": "https://m1.multimovies.courses",
                        "status": "active"},
        "hdhub4u": {"active_base_url": "https://new8.hdhub4u.cl",
                    "status": "active"},
        "filmyzilla": {"active_base_url": "https://thefilmyzilla.in",
                       "status": "active"},
        "rogmovies": {"active_base_url": "https://rogmovies.one",
                      "status": "active"},
        "bolly4u": {"active_base_url": "https://bolly4uv.com",
                    "status": "active"},
    }

    def _get_domains(self):
        conn = _memory_db(self.STALE_DB)
        self.addCleanup(conn.close)

        @contextlib.contextmanager
        def fake_get_db(*a, **k):
            yield conn

        with _temp_registry(self.LIVE_REGISTRY) as reg:
            with mock.patch.object(ff, "REGISTRY_PATH", reg), \
                 mock.patch.object(ff, "get_db", fake_get_db):
                return ff.get_domains()

    def test_registry_outranks_stale_db_rows(self):
        domains = self._get_domains()
        self.assertEqual(domains["vegamovies"], "https://new1.vegamovies.lease")
        self.assertEqual(domains["ssrmovies"], "https://ssrmovies.surf")
        self.assertEqual(domains["7starhd"], "https://7starhd.dentist")
        self.assertEqual(domains["10xflix"], "https://10xflix.hair")
        self.assertEqual(domains["multimovies"], "https://m1.multimovies.courses")
        # The registry still outranks the DB — but the host registry then
        # rewrites thefilmyzilla.in to its moved domain filmdb.in (domain
        # audit, 18 Nov), so the value get_domains() serves is the new one.
        self.assertEqual(domains["filmyzilla"], "https://filmdb.in")

    def test_multimovies_precedence_is_pinned_from_outside(self):
        # Review note (crown, 2026-11-05 ~03:00): test_vidout derives its
        # expectation from fast_fetch.DOMAINS, so code and test could agree on
        # garbage. This test knows the answer independently: the registry is
        # the authority because domain_resolver is the only probe-verified
        # writer, so the expected host is written literally here, and BOTH
        # losing directions are pinned — the answer must equal the registry
        # fixture, and must NOT equal the DEFAULT_DOMAINS literal or the
        # stale domains_config row.
        stale_garden = "https://multimovies.garden"
        registry_live = "https://m1.multimovies.courses"
        self.assertEqual(ff.DEFAULT_DOMAINS.get("multimovies"), stale_garden,
                         "the static literal moved — this test's independence "
                         "claim needs re-checking before it can keep pinning it")
        conn = _memory_db([("multimovies", stale_garden)])
        self.addCleanup(conn.close)

        @contextlib.contextmanager
        def fake_get_db(*a, **k):
            yield conn

        with _temp_registry({"multimovies": {"active_base_url": registry_live,
                                             "status": "active"}}) as reg:
            with mock.patch.object(ff, "REGISTRY_PATH", reg), \
                 mock.patch.object(ff, "get_db", fake_get_db):
                domains = ff.get_domains()
        self.assertEqual(domains["multimovies"], registry_live)
        self.assertNotEqual(domains["multimovies"], stale_garden)
        self.assertNotEqual(domains["multimovies"],
                            ff.DEFAULT_DOMAINS.get("multimovies"))

        # The other precedence direction, so the test can actually fail: with
        # NO registry entry for the site, the stale DB row must win over the
        # static literal (that is today's documented behaviour — DB overrides
        # static, registry overrides DB only when it speaks). If someone
        # inverted the overlay order, this half goes red.
        with _temp_registry({"othersite": {"active_base_url":
                                           "https://other.example",
                                           "status": "active"}}) as reg:
            with mock.patch.object(ff, "REGISTRY_PATH", reg), \
                 mock.patch.object(ff, "get_db", fake_get_db):
                domains = ff.get_domains()
        self.assertEqual(domains["multimovies"], stale_garden,
                         "registry silence must NOT invent a value for a "
                         "site it does not name")

    def test_hdhub4u_mispointer_is_outranked_not_rewritten(self):
        domains = self._get_domains()
        # The hdtvb.net row was a pointer at a DIFFERENT site's domain; the
        # overlay fixes reads, and no string is deleted anywhere.
        self.assertEqual(domains["hdhub4u"], "https://new8.hdhub4u.cl")

    def test_sitemap_keys_follow_the_origin_host_swap(self):
        domains = self._get_domains()
        self.assertEqual(domains["vegamovies_sitemap"],
                         "https://new1.vegamovies.lease/sitemap.xml")
        self.assertEqual(domains["sitemap"],
                         "https://new1.vegamovies.lease/sitemap.xml")
        self.assertEqual(domains["hdhub4u_sitemap"],
                         "https://new8.hdhub4u.cl/sitemap.xml")

    def test_registry_only_sites_are_added(self):
        domains = self._get_domains()
        self.assertEqual(domains["bolly4u"], "https://bolly4uv.com")

    def test_agreeing_rows_pass_through_unchanged(self):
        domains = self._get_domains()
        self.assertEqual(domains["rogmovies"], "https://rogmovies.one")

    def test_unrelated_keys_are_untouched(self):
        domains = self._get_domains()
        self.assertEqual(domains["katmoviehd_landing"], "https://katmovieshd.net")
        # hubcloud is host-registry managed now: the static literal is
        # hubcloud.ist but the audit pair rewrites it to hubcloud.mov.
        self.assertEqual(domains["hubcloud"], "https://hubcloud.mov")
        self.assertEqual(domains["fastdl"], ff.DEFAULT_DOMAINS["fastdl"])

    def test_no_registry_falls_back_to_static_then_db(self):
        conn = _memory_db(self.STALE_DB)
        self.addCleanup(conn.close)

        @contextlib.contextmanager
        def fake_get_db(*a, **k):
            yield conn

        missing = pathlib.Path(tempfile.gettempdir()) / "no_such_registry_q14.json"
        with mock.patch.object(ff, "REGISTRY_PATH", missing), \
             mock.patch.object(ff, "get_db", fake_get_db):
            domains = ff.get_domains()
        self.assertEqual(domains["vegamovies"], "https://vegamovies.gallery")
        self.assertEqual(domains["hdhub4u"], "https://hdtvb.net")

    def test_down_registry_site_does_not_shadow_the_db(self):
        conn = _memory_db([("vegamovies", "https://db.example")])
        self.addCleanup(conn.close)

        @contextlib.contextmanager
        def fake_get_db(*a, **k):
            yield conn

        sites = {"vegamovies": {"active_base_url": "https://dead.vegamovies.zone",
                                "status": "down"}}
        with _temp_registry(sites) as reg:
            with mock.patch.object(ff, "REGISTRY_PATH", reg), \
                 mock.patch.object(ff, "get_db", fake_get_db):
                domains = ff.get_domains()
        self.assertEqual(domains["vegamovies"], "https://db.example")


class TestRefreshDomains(unittest.TestCase):
    def test_refresh_updates_snapshot_in_place(self):
        sites = {"vegamovies": {"active_base_url": "https://new1.vegamovies.lease",
                                "status": "active"}}
        snapshot = dict(ff.DOMAINS)
        self.addCleanup(ff.DOMAINS.update, snapshot)
        with _temp_registry(sites) as reg:
            with mock.patch.object(ff, "REGISTRY_PATH", reg):
                out = ff.refresh_domains()
        self.assertIs(out, ff.DOMAINS)
        self.assertEqual(ff.DOMAINS["vegamovies"], "https://new1.vegamovies.lease")

    def test_sync_refreshes_before_fetching(self):
        src = (HERE / "fast_fetch.py").read_text(encoding="utf-8")
        body = src.split("def sync_sitemaps", 1)[1].split("\ndef ", 1)[0]
        self.assertIn("refresh_domains()", body)


class TestNeedleSuperset(unittest.TestCase):
    """The one that protects the public edge: the scrubber's needle set is
    harvested from domain_registry.json by redact._registry_needles(). No host
    or mirror string may vanish. 98 → 98."""

    def test_needle_set_is_a_superset_of_the_pre_change_snapshot(self):
        current = set(redact._registry_needles())
        # Sibling literal pin (self-audit, crown order 2026-11-05): the
        # superset test alone is VACUOUS on an emptied NEEDLES_BEFORE — an
        # empty baseline loses nothing and the len floor below compares to
        # itself. 98 is the snapshot's own count, pinned from outside it.
        self.assertEqual(len(NEEDLES_BEFORE), 98)
        lost = NEEDLES_BEFORE - current
        self.assertFalse(lost,
                         f"redactor needles disappeared: {sorted(lost)}")
        self.assertGreaterEqual(len(current), len(NEEDLES_BEFORE))

    def test_hdtvb_net_is_owned_by_the_hdtvb_site_entry_not_hdhub4u(self):
        data = json.loads(ff.REGISTRY_PATH.read_text(encoding="utf-8"))
        sites = data["sites"]
        self.assertIn("https://hdtvb.net", sites["hdtvb"]["known_mirrors"])
        mirrors = sites["hdhub4u"]["known_mirrors"]
        # Floor pin (self-audit): the assertNotIn loop below is a
        # pass-on-nothing candidate if hdhub4u's mirror list is ever emptied.
        # 3 = measured on main 14d9063's registry at rebase (the
        # branch's 4th mirror rotated out in main's drift work;
        # the floor still catches an emptied list).
        self.assertGreaterEqual(len(mirrors), 3)
        for mirror in mirrors:
            self.assertNotIn("hdtvb", mirror)

    def test_retired_rows_are_still_harvested(self):
        # _registry_needles() iterates every entry and never checks `status`;
        # pin that so a future "skip retired rows" edit cannot shrink the
        # redactor silently.
        sites = {"oldsitename": {"active_base_url": "https://new.example",
                                 "status": "retired",
                                 "known_mirrors": ["https://new.example",
                                                   "https://retired.example"]}}
        with _temp_registry(sites) as reg:
            real_path = pathlib.Path

            class _FakePath:
                def __init__(self, value):
                    self._p = real_path(value)

                def with_name(self, name):
                    return reg

            with mock.patch.object(redact, "Path", _FakePath):
                needles = redact._registry_needles()
        self.assertIn("retired.example", needles)
        self.assertIn("new.example", needles)
        self.assertIn("oldsitename", needles)

    def test_registry_host_strings_survive_the_reconciliation(self):
        data = json.loads(ff.REGISTRY_PATH.read_text(encoding="utf-8"))
        # Floor pin (self-audit): the type-check loops below pass on nothing
        # if the sites map is ever emptied. 18 = measured 2026-11-05.
        self.assertGreaterEqual(len(data["sites"]), 18)
        for name, entry in data["sites"].items():
            self.assertIsInstance(entry, dict)
            for mirror in entry.get("known_mirrors") or []:
                self.assertIsInstance(mirror, str)


class TestRegistryScrubberWiring(unittest.TestCase):
    """The registry->scrubber drift guard proper. The needle baseline above
    protects the HARVEST SOURCE; these tests protect the WIRE. redact
    site_needles() (detection) and site_text_needles() (rewriting) each pull
    _registry_needles() into the public scrubber (redact.py:1191/:1236). If
    either `needles.update(...)` call is ever removed — or a registry prune
    quietly deletes a public needle — redaction shrinks with no other test
    noticing, and the public edge starts leaking host strings. A needle
    disappearing from EITHER public vocabulary fails here, in CI, on the
    live registry file, not just in the snapshot."""

    @staticmethod
    def _eligible(needles):
        """Exactly redact's own drop rules at redact.py:1220-1223/:1246-1249,
        so this guard can never demand a string the scrubber legitimately
        refuses (short tokens, null-words, GENERIC_TOKENS)."""
        return frozenset(
            n for n in needles
            if n and len(n) >= 4 and n not in {"unknown", "null", "none"}
            and n not in redact.GENERIC_TOKENS)

    def test_live_registry_harvest_survives_into_both_public_vocabularies(self):
        harvest = self._eligible(redact._registry_needles())
        self.assertTrue(harvest, "registry harvested nothing — CI checkout "
                                 "is missing domain_registry.json")
        lost_detect = harvest - set(redact.site_needles())
        lost_rewrite = harvest - set(redact.site_text_needles())
        self.assertFalse(lost_detect,
                         f"registry needles vanished from the DETECTION "
                         f"vocabulary (wire cut at redact.py:1191?): "
                         f"{sorted(lost_detect)}")
        self.assertFalse(lost_rewrite,
                         f"registry needles vanished from the REWRITE "
                         f"vocabulary (wire cut at redact.py:1236?): "
                         f"{sorted(lost_rewrite)}")

    def test_baseline_snapshot_survives_into_both_public_vocabularies(self):
        eligible = self._eligible(NEEDLES_BEFORE)
        missing_detect = eligible - set(redact.site_needles())
        missing_rewrite = eligible - set(redact.site_text_needles())
        self.assertFalse(missing_detect,
                         f"public scrubber lost baseline needles: "
                         f"{sorted(missing_detect)}")
        self.assertFalse(missing_rewrite,
                         f"public rewrite vocabulary lost baseline needles: "
                         f"{sorted(missing_rewrite)}")

    def test_synthetic_registry_needle_reaches_the_public_scrubber(self):
        # End-to-end proof of the wire itself: a host that exists ONLY in a
        # synthetic registry must still reach both vocabularies. Immune to
        # baseline re-pinning — if someone un-plugs _registry_needles() from
        # site_needles()/site_text_needles(), this goes red even when every
        # snapshot test above was re-pinned away.
        sites = {"driftprobe": {"active_base_url": "https://driftprobe.example",
                                "status": "live",
                                "known_mirrors": ["https://driftprobe.example",
                                                  "https://driftprobe-mirror.example",
                                                  "https://cdn.deep.driftprobe.example"]}}
        with _temp_registry(sites) as reg:
            real_path = pathlib.Path

            class _FakePath:
                def __init__(self, value):
                    self._p = real_path(value)

                def with_name(self, name):
                    return reg

            with mock.patch.object(redact, "Path", _FakePath):
                det = set(redact.site_needles())
                rew = set(redact.site_text_needles())
        probes = ("driftprobe", "driftprobe.example",
                  "driftprobe-mirror.example",
                  "cdn.deep.driftprobe.example")
        # Literal pin (self-audit): four harvest points — full domain, brand
        # label, mirror and deep-subdomain — so the loop cannot be quietly
        # shrunk to a vacuous pass-on-nothing.
        self.assertEqual(len(probes), 4)
        for needle in probes:
            self.assertIn(needle, det,
                          f"{needle!r} harvested from the registry but absent "
                          "from site_needles() — detection wire cut")
            self.assertIn(needle, rew,
                          f"{needle!r} harvested from the registry but absent "
                          "from site_text_needles() — rewrite wire cut")
        # The never-bare-the-TLD rule (redact.py:1263): "example" is a plain
        # English-ish token that must NOT join the vocabulary on its own.
        self.assertNotIn("example", det)
        self.assertNotIn("example", rew)


class TestKatmovieBlockShadowing(unittest.TestCase):
    """Proof the second ``katmovie`` block in normalize_url has no live
    reference: ``DEFAULT_DOMAINS`` always carries the ``katmoviehd`` key,
    ``get_domains()`` starts from that dict, and no code path DELETEs
    domains_config rows — so block 1 and block 2 compute the SAME
    ``active_host``. Block 1 either returns the rewrite or leaves the host
    equal to it; block 2 can therefore only fire through block 1's guard
    escape, which is pinned below as a bug-shape, not a feature."""

    ACTIVE = "https://new.katmoviehd.top"

    def setUp(self):
        self.assertIn("katmoviehd", ff.DEFAULT_DOMAINS)  # the invariant
        snapshot = dict(ff.DOMAINS)
        self.addCleanup(ff.DOMAINS.clear)
        self.addCleanup(ff.DOMAINS.update, snapshot)
        ff.DOMAINS.clear()
        ff.DOMAINS.update({**ff.DEFAULT_DOMAINS, "katmoviehd": self.ACTIVE})

    def test_old_domain_is_rewritten_by_the_first_block(self):
        self.assertEqual(
            ff.normalize_url("https://katmoviehd.fans/movie/x/"),
            self.ACTIVE + "/movie/x/")

    def test_active_domain_passes_through_both_blocks_unchanged(self):
        self.assertEqual(ff.normalize_url(self.ACTIVE + "/movie/x/"),
                         self.ACTIVE + "/movie/x/")

    def test_guard_escape_is_the_only_live_reference_to_the_second_block(self):
        # A host containing BOTH "katmovie" and a guard token skips block 1's
        # rewrite, then block 2 rewrites it WITHOUT the guard. Pin the current
        # behavior; the retirement marker documents it, deletion is withheld.
        self.assertEqual(
            ff.normalize_url("https://links.kmhd.katmovie.watch/x"),
            self.ACTIVE + "/x")

    def test_hosts_without_the_katmovie_substring_never_hit_either_block(self):
        self.assertEqual(ff.normalize_url("https://kathdrama.example/x"),
                         "https://kathdrama.example/x")

    def test_substring_looseness_of_normalize_url_is_preexisting_behavior(self):
        # normalize_url matches hosts by substring (unlike host_classifier's
        # strict suffix rule). "notkatmovie.example" therefore DOES hit the
        # blocks and gets rewritten. Pin the current behavior — narrowing it
        # is out of this slice's scope and would change ingest output.
        self.assertEqual(ff.normalize_url("https://notkatmovie.example/x"),
                         self.ACTIVE + "/x")


class TestSyncSitemapsReportsEmptyFeeds(unittest.TestCase):
    """2026-11-23 catalog drought: 7starhd / katmoviehd / 10xflix /
    bollyshares returned nothing for days with NO error line, and the step
    still reported "Global sync complete!" success. Every feed must now
    report its outcome loudly (HTTP failure vs empty parse) and the cycle
    must end with a "[!] Sync problems" summary line naming the dead
    feeds. The two feed-shape fixes are pinned too: the 7starhd regex
    must follow the host that was actually fetched (it hardcoded
    `7starhd.rich` after the move to .surgery), and katmoviehd must fall
    back to harvesting homepage post links when the page carries no
    slug:"..." embeds (the current domain's shape).

    Offline: http_get is stubbed, the DB is a temp file, no network, no
    production DB. The stubbed HTML is built on the host the sync
    actually requested, so the tests do not depend on the registry's
    current domain values.
    """

    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.addCleanup(self._td.cleanup)
        self._db = pathlib.Path(self._td.name) / "sync.db"
        self._db_patch = mock.patch.object(ff, "DB_PATH", self._db)
        self._db_patch.start()
        self.addCleanup(self._db_patch.stop)
        self._snapshot = dict(ff.DOMAINS)
        self.addCleanup(ff.DOMAINS.update, self._snapshot)
        self._calls: list[str] = []

    def _run(self, site, responses):
        """responses: URL-substring -> (code, body) or callable(url)
        returning (code, body). Longest substring wins, so a sitemap URL
        beats the generic homepage rule. Returns (total_cached, stdout)."""
        def fake_http_get(url, *a, **k):
            self._calls.append(url)
            for key in sorted(responses, key=len, reverse=True):
                if key in url:
                    resp = responses[key]
                    code, body = resp(url) if callable(resp) else resp
                    return (code, url, body)
            return (404, url, "")

        out = io.StringIO()
        with mock.patch.object(ff, "http_get", fake_http_get), \
             contextlib.redirect_stdout(out):
            total = ff.sync_sitemaps(site=site, verbose=True)
        return total, out.getvalue()

    def _cached_locs(self, site):
        with sqlite3.connect(self._db) as conn:
            return [r[0] for r in conn.execute(
                "SELECT loc FROM sitemap_cache WHERE site = ?", (site,))]

    def test_7starhd_harvests_links_on_the_fetched_host(self):
        def homepage(url):
            host = urllib.parse.urlparse(url).netloc
            return (200, (
                f'<a href="https://{host}/angel-has-fallen-2019-hindi-org-dual-audio-bluray-1080p-720p-480p-esubs-2/">post</a>'
                f'<a href="https://{host}/category/hindi-movies/">cat</a>'
                f'<a href="https://{host}/dmca/">dmca</a>'))
        total, out = self._run("7starhd", {"https://": homepage})
        self.assertEqual(total, 1)
        self.assertEqual(len(self._cached_locs("7starhd")), 1)
        self.assertIn("angel-has-fallen-2019", self._cached_locs("7starhd")[0])
        self.assertIn("[✓] [7STARHD]", out)
        self.assertNotIn("[!] [7STARHD]", out)
        self.assertNotIn("Sync problems", out)

    def test_7starhd_empty_homepage_reports_loudly(self):
        total, out = self._run("7starhd", {"https://": (200, "<html></html>")})
        self.assertEqual(total, 0)
        self.assertIn("[!] [7STARHD] feed returned 0 records", out)
        self.assertIn("[!] Sync problems (1)", out)

    def test_katmoviehd_homepage_fallback_harvests_links(self):
        def homepage(url):
            host = urllib.parse.urlparse(url).netloc
            return (200, (
                # No slug:"..." embeds: the current domain's shape.
                f'<a href="https://{host}/black-box-2026-hindi/">post 1</a>'
                f'<a href="https://{host}/the-social-reckoning-2026-hindi">post 2</a>'
                f'<a href="https://{host}/category/bollywood/">cat</a>'
                f'<a href="https://{host}/about/">about</a>'))
        total, out = self._run("katmoviehd", {"https://": homepage})
        self.assertEqual(total, 2)
        locs = sorted(self._cached_locs("katmoviehd"))
        self.assertIn("black-box-2026-hindi", locs[0])
        self.assertIn("the-social-reckoning-2026-hindi", locs[1])
        self.assertIn("[✓] [KATMOVIEHD]", out)
        self.assertNotIn("[!] [KATMOVIEHD]", out)

    def test_katmoviehd_slug_embeds_still_work(self):
        total, out = self._run(
            "katmoviehd",
            {"https://": (200, 'slug:"clashing-through-the-snow-2026-hindi"')})
        self.assertEqual(total, 1)
        self.assertIn("clashing-through-the-snow-2026-hindi",
                      self._cached_locs("katmoviehd")[0])

    def test_katmoviehd_empty_reports_loudly(self):
        total, out = self._run(
            "katmoviehd", {"https://": (200, "<html></html>")})
        self.assertEqual(total, 0)
        self.assertIn("[!] [KATMOVIEHD] feed returned 0 records", out)
        self.assertIn("[!] Sync problems (1)", out)

    def test_10xflix_http_error_reports_loudly(self):
        total, out = self._run("10xflix", {"https://": (404, "")})
        self.assertEqual(total, 0)
        self.assertIn("[!] [10XFLIX] feed HTTP 404", out)
        self.assertIn("[!] Sync problems (1)", out)

    def test_ssrmovies_empty_json_reports_loudly(self):
        total, out = self._run("ssrmovies", {"https://": (200, "[]")})
        self.assertEqual(total, 0)
        self.assertIn("[!] [SSRMOVIES] feed returned 0 records", out)
        self.assertIn("[!] Sync problems (1)", out)

    def test_bollyshares_dead_site_reports_loudly(self):
        # Sitemap 404s; the homepage fallback is a "Website Unavailable"
        # parking page with no /free- links — the site is down, not just
        # a moved feed. Both fetches must still end in a loud report.
        parking = "<html><head><title>Website Unavailable</title></head></html>"
        total, out = self._run("bollyshares", {
            "post-sitemap.xml": (404, ""),
            "https://": (200, parking),
        })
        self.assertEqual(total, 0)
        self.assertIn("[!] [BOLLYSHARES] feed HTTP 404", out)
        self.assertIn("[!] Sync problems (1)", out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
