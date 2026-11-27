#!/usr/bin/env python3
"""Tests for host_registry.py — the self-healing download-host registry.

Offline, stdlib, no network: every HTTP hop goes through an injected
fake fetcher. Real DNS is never touched.

Pinned here:
* the seeded registry loads: 49 entries (45 active + 4 dead hosts),
  39 moved pairs, and the four dead hosts are recorded, not rewritten;
* rewrite_url swaps ONLY the host (scheme/port/path/query/fragment
  preserved) and falls back to the stored URL for unknown, dead, or
  already-current hosts;
* record_redirect counts ONLY host-only cross-host redirects (same path
  + query); a path change, a redirect to a bare IP, and a same-host
  redirect never count; 20 observations of one pair apply the move and
  the pair never re-applies; counts persist across calls;
* validate_host_move follows the audit's standard (old-root redirect,
  then rewritten file URL 200, then new-root 200) and reports a WAF
  refusal as blocked, never dead;
* check_host_root classifies moved / alive / blocked / down / gone;
* the prober wires the observation in: _probe_host counts a redirect
  and never lets a registry failure break a verdict;
* fast_fetch reads the registry last in get_domains() (hubcloud is
  .mov, direct-cloud is the .org storage host) and the mobilejsr
  unwrapper short-circuits on a dead host instead of timing out.
"""

from __future__ import annotations

import copy
import json
import os
import socket
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import host_registry as hr


def _tmp_registry():
    """A temp copy of the seeded registry (path, data)."""
    src = Path(__file__).resolve().parent / "host_registry.json"
    data = json.loads(src.read_text(encoding="utf-8"))
    tmp = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False)
    json.dump(data, tmp)
    tmp.close()
    return tmp.name, data


def _fake_fetch(mapping):
    """fetch(url) -> (status, final_url, error) from a {url: (status, final)}
    or {url: (status, final, error)} map."""
    def fetch(url):
        hit = mapping.get(url)
        if hit is None:
            return 0, url, "dns"
        if len(hit) == 3:
            return hit[0], hit[1], hit[2]
        status, final = hit
        return status, final, None
    return fetch


def _public_getaddrinfo(host, port, *args, **kwargs):
    """Stub getaddrinfo so the SSRF guard sees a public address (the
    test VM's own resolver answers wildcard/private for fake TLDs)."""
    return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("93.184.216.34", 0))]


class TestSeededRegistry(unittest.TestCase):
    def test_seed_counts(self):
        reg = hr.load_registry()
        hosts = reg["hosts"]
        self.assertEqual(reg["version"], "2.0")
        self.assertEqual(len(hosts), 49)  # 45 active + 4 dead hosts
        # live_domains() counts MOVED pairs only (current_domain != host):
        # 6 of the 7 §2 hosts are no-move entries (current_domain == host),
        # so only thebollyflix (gen.in -> io.in) joins the moved set.
        self.assertEqual(len(hr.live_domains()), 39)
        # The audit's headline said 26 pairs; the enumerated lists sum to
        # 29, and the pair row counts sum to exactly the audit's 1,010,359.
        # The vcloud move (domain-doctor 2026-11-21, 237,014 rows) and the
        # subdomain lane (2026-11-21, 4 moves + 9,297 rows, 2 DNS-dead)
        # bring the total to 1,256,670. The registry-refresh lane
        # (2026-11-21, vegamovies/ssrmovies/hdhub4u/rogmovies) adds 4
        # moves with 0 rows on the old hosts, so the total is unchanged.
        # The registry §2 lane (2026-11-21) adds 7 hosts: themoviebox
        # (159,326 rows, service-column count) + 6 with 0 rows (the
        # aoneroom API host and 5 wp_family sites never merged into the
        # catalog), so the total becomes 1,415,996.
        rows = sum(h.get("rows_affected", 0) for h in hosts.values()
                   if h.get("status") == "active")
        self.assertEqual(rows, 1415996)

    def test_seed_evidence_states(self):
        reg = hr.load_registry()
        hosts = reg["hosts"]
        by_evidence = {}
        for h in hosts.values():
            by_evidence[h["evidence"]] = by_evidence.get(h["evidence"], 0) + 1
        self.assertEqual(by_evidence["audit-confirmed"], 15)
        self.assertEqual(by_evidence["audit-unconfirmed"], 11)
        self.assertEqual(by_evidence["audit-dead-sample"], 3)
        self.assertEqual(by_evidence["audit-dead"], 4)
        # 5 prober-learned moves + vcloud.beer, proven by the
        # domain-doctor's live probe (beer 301/302s to my, my serves 200),
        # + 4 registry-refresh moves (vegamovies/ssrmovies/hdhub4u/
        # rogmovies, 2026-11-21, each probed live from the Oracle VM),
        # + 6 registry §2 hosts (themoviebox, the aoneroom API host,
        # moviehax/animeworld/hindidubbedmovies/hindianimeszone, each
        # probed live 2026-11-21). The 7th §2 host, thebollyflix, is
        # audit-unconfirmed: the old root really redirects, but the new
        # root 403s under Cloudflare from the Oracle VM.
        self.assertEqual(by_evidence["prober"], 16)

    def test_pixel_excluded(self):
        # Both sides 400 (expired tokens): never a mapping, never rewritten.
        self.assertEqual(hr.current_domain_for("pixel.hubcloud.ist"), "")
        self.assertEqual(
            hr.rewrite_url("https://pixel.hubcloud.ist/?id=abc"),
            "https://pixel.hubcloud.ist/?id=abc")

    def test_dead_hosts_recorded_not_rewritten(self):
        self.assertTrue(hr.is_dead_host("mobilejsr.com"))
        self.assertTrue(hr.is_dead_host("uploadhub.dad"))
        self.assertEqual(hr.current_domain_for("mobilejsr.com"), "")
        self.assertEqual(hr.rewrite_url("https://mobilejsr.com/view/abc"),
                         "https://mobilejsr.com/view/abc")


class TestRewrite(unittest.TestCase):
    def test_host_only_swap(self):
        self.assertEqual(
            hr.rewrite_url("https://hubcloud.ist/drive/ABC123"),
            "https://hubcloud.mov/drive/ABC123")
        self.assertEqual(
            hr.rewrite_url("https://hubcloud.pw/drive/x?y=1#frag"),
            "https://hubcloud.mov/drive/x?y=1#frag")
        self.assertEqual(
            hr.rewrite_url("https://hubdrive.space/file/1"),
            "https://hubdrive.tips/file/1")
        self.assertEqual(
            hr.rewrite_url("https://new5.gdflix.io/file/abc"),
            "https://new10.gdflix.io/file/abc")
        self.assertEqual(
            hr.rewrite_url("https://www.mp4moviez.deal/post/1"),
            "https://www.mp4moviez.computer/post/1")
        # Registry-refresh lane (2026-11-21, all four probed live from
        # the Oracle VM): the moved site bases rewrite at serve time.
        self.assertEqual(
            hr.rewrite_url("https://vegamovies.lease/post/1"),
            "https://vegamovies.latino/post/1")
        self.assertEqual(
            hr.rewrite_url("https://new1.ssrmovies.page/post/1"),
            "https://site4.ssrmovies.page/post/1")
        self.assertEqual(
            hr.rewrite_url("https://new12.hdhub4u.cl/post/1"),
            "https://new15.hdhub4u.cl/post/1")
        self.assertEqual(
            hr.rewrite_url("https://rogmovies.asia/post/1"),
            "https://rogmovies.beauty/post/1")
        self.assertEqual(
            hr.rewrite_url("https://vd.1xplayer.com/embed/1"),
            "https://asrmo449ddj.com/embed/1")  # prober reclassified 2026-11-20 (was geffe448mik.com)
        self.assertEqual(
            hr.rewrite_url("https://vcloud.beer/---2jb-ia-7-i91"),
            "https://vcloud.my/---2jb-ia-7-i91")  # domain-doctor 2026-11-21
        self.assertEqual(
            hr.rewrite_url("https://dl.direct-cloud.top/f/1"),
            "https://storage.direct-cloud.org/f/1")

    def test_fallback_unchanged(self):
        self.assertEqual(hr.rewrite_url("https://gofile.io/d/xyz"),
                         "https://gofile.io/d/xyz")
        self.assertEqual(hr.rewrite_url("https://new1.filepress.lat/f/1"),
                         "https://new1.filepress.lat/f/1")  # no audited pair
        self.assertEqual(hr.rewrite_url("not a url"), "not a url")
        self.assertEqual(hr.rewrite_url(""), "")

    def test_www_fallback(self):
        # A stored www. variant of a bare-host entry still rewrites.
        self.assertEqual(
            hr.rewrite_url("https://www.hubcloud.ist/drive/1"),
            "https://hubcloud.mov/drive/1")

    def test_port_preserved(self):
        self.assertEqual(
            hr.rewrite_url("https://hubcloud.ist:8443/drive/1"),
            "https://hubcloud.mov:8443/drive/1")

    def test_subdomain_moves_are_explicit_entries(self):
        # Subdomain rewrite lane (2026-11-21): each subdomain is its own
        # registry entry, validated live from the Oracle VM on a real file
        # URL. new8/new.filebee.xyz follow the parent's chain to
        # rt.filepress.lat; links.kmhd.eu keeps its prefix on .me (200);
        # gd.kmhd.eu ends at new10.gdflix.io (CF 403, same family).
        self.assertEqual(
            hr.rewrite_url("https://new8.filebee.xyz/file/65ae3485869e1f441cf55927"),
            "https://rt.filepress.lat/file/65ae3485869e1f441cf55927")
        self.assertEqual(
            hr.rewrite_url("https://new.filebee.xyz/file/64d1d50be6431cadaee0dc94"),
            "https://rt.filepress.lat/file/64d1d50be6431cadaee0dc94")
        self.assertEqual(
            hr.rewrite_url("https://links.kmhd.eu/atchs/dl2.jiocloud.link/Sample/x.mkv"),
            "https://links.kmhd.me/atchs/dl2.jiocloud.link/Sample/x.mkv")
        self.assertEqual(
            hr.rewrite_url("https://gd.kmhd.eu/file/nb4O4OtJWn"),
            "https://new10.gdflix.io/file/nb4O4OtJWn")

    def test_subdomain_dead_hosts_recorded_not_rewritten(self):
        # new5/new6.filebee.xyz have no DNS record at all (2026-11-21):
        # dead with no successor, so callers skip them instead of timing
        # out — exactly like mobilejsr.com.
        self.assertTrue(hr.is_dead_host("new5.filebee.xyz"))
        self.assertTrue(hr.is_dead_host("new6.filebee.xyz"))
        self.assertEqual(
            hr.rewrite_url("https://new5.filebee.xyz/file/65671442ca2e7262ef2278c8"),
            "https://new5.filebee.xyz/file/65671442ca2e7262ef2278c8")
        self.assertEqual(
            hr.rewrite_url("https://new6.filebee.xyz/file/658420503993a3b20797a65b"),
            "https://new6.filebee.xyz/file/658420503993a3b20797a65b")

    def test_no_suffix_match(self):
        # Deliberate: a subdomain with NO entry never inherits its
        # parent's move. A blanket suffix-match would rewrite
        # pixel.hubcloud.ist (pinned excluded in TestSeededRegistry) and
        # would have sent links.kmhd.eu to bare kmhd.me instead of
        # links.kmhd.me. Subdomains move only as explicit entries.
        self.assertEqual(
            hr.rewrite_url("https://foo.hubcloud.ist/drive/1"),
            "https://foo.hubcloud.ist/drive/1")
        self.assertEqual(
            hr.rewrite_url("https://wait.kmhd.eu/page"),
            "https://wait.kmhd.eu/page")  # alive host, 200 at root; no entry


class TestApplyMove(unittest.TestCase):
    def test_apply_updates_and_persists(self):
        path, _data = _tmp_registry()
        try:
            entry = hr.apply_move("example.old", "example.new", "manual",
                                  registry_path=path, rows_affected=42)
            self.assertEqual(entry["current_domain"], "example.new")
            self.assertEqual(entry["evidence"], "manual")
            self.assertEqual(entry["status"], "active")
            self.assertEqual(entry["rows_affected"], 42)
            self.assertEqual(entry["known_mirrors"], ["https://example.new"])
            fresh = hr.load_registry(path)
            self.assertEqual(fresh["hosts"]["example.old"]["current_domain"],
                             "example.new")
            self.assertEqual(hr.current_domain_for("example.old",
                                                   registry_path=path),
                             "example.new")
        finally:
            os.unlink(path)

    def test_apply_rejects_same_host(self):
        path, _data = _tmp_registry()
        try:
            with self.assertRaises(ValueError):
                hr.apply_move("example.old", "example.old", "manual",
                              registry_path=path)
        finally:
            os.unlink(path)

    def test_apply_keeps_rotation_history(self):
        path, _data = _tmp_registry()
        try:
            hr.apply_move("hubcloud.ist", "hubcloud.new", "manual",
                          registry_path=path)
            entry = hr.load_registry(path)["hosts"]["hubcloud.ist"]
            domains = [h["domain"] for h in entry["rotation_history"]]
            self.assertIn("hubcloud.mov", domains)  # the superseded current
            self.assertIn("hubcloud.new", domains)
        finally:
            os.unlink(path)


class TestRecordRedirect(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix="host-reg-test-")
        self.reg_path = os.path.join(self.tmpdir, "host_registry.json")
        self.obs_path = os.path.join(self.tmpdir, "obs.json")
        with open(self.reg_path, "w", encoding="utf-8") as fh:
            json.dump({"version": "2.0", "hosts": {}}, fh)

    def tearDown(self):
        for p in (self.reg_path, self.obs_path):
            try:
                os.unlink(p)
            except OSError:
                pass
        try:
            os.rmdir(self.tmpdir)
        except OSError:
            pass

    def _record(self, old, new, n=1):
        out = None
        for _ in range(n):
            out = hr.record_redirect(old, new, registry_path=self.reg_path,
                                     observations_path=self.obs_path)
        return out

    def test_host_only_counts(self):
        out = self._record("https://a.old/drive/1", "https://a.new/drive/1")
        self.assertEqual(out["count"], 1)
        self.assertFalse(out["threshold_reached"])

    def test_path_change_does_not_count(self):
        out = self._record("https://a.old/drive/1", "https://cdn.example/x/y")
        self.assertEqual(out["count"], 0)
        out = self._record("https://a.old/drive/1", "https://a.new/other/2")
        self.assertEqual(out["count"], 0)

    def test_ip_target_does_not_count(self):
        out = self._record("https://a.old/drive/1", "https://93.184.216.34/drive/1")
        self.assertEqual(out["count"], 0)

    def test_same_host_does_not_count(self):
        out = self._record("https://a.old/drive/1", "https://a.old/drive/1")
        self.assertEqual(out["count"], 0)

    def test_threshold_applies_once(self):
        out = self._record("https://b.old/file/1", "https://b.new/file/1",
                           n=hr.REDIRECT_MOVE_THRESHOLD)
        self.assertTrue(out["threshold_reached"])
        self.assertTrue(out["applied"])
        self.assertEqual(hr.current_domain_for("b.old", registry_path=self.reg_path),
                         "b.new")
        # One more observation must not re-apply or duplicate history.
        out2 = self._record("https://b.old/file/2", "https://b.new/file/2")
        self.assertFalse(out2["applied"])
        entry = hr.load_registry(self.reg_path)["hosts"]["b.old"]
        self.assertEqual(len(entry["rotation_history"]), 2)

    def test_counts_persist_across_calls(self):
        self._record("https://c.old/f/1", "https://c.new/f/1", n=5)
        hr._drop_cache()
        out = self._record("https://c.old/f/2", "https://c.new/f/2")
        self.assertEqual(out["count"], 6)

    def test_already_current_marks_applied_without_rewrite(self):
        hr.apply_move("d.old", "d.new", "manual", registry_path=self.reg_path)
        out = self._record("https://d.old/f/1", "https://d.new/f/1",
                           n=hr.REDIRECT_MOVE_THRESHOLD)
        self.assertTrue(out["threshold_reached"])
        self.assertFalse(out["applied"])  # nothing to change
        entry = hr.load_registry(self.reg_path)["hosts"]["d.old"]
        self.assertEqual(entry["evidence"], "manual")  # untouched


def _blocked_search(url, timeout=10):
    """A DDG fetcher that is always blocked — keeps the HTML-observation
    tests offline and deterministic (no candidate from search)."""
    return 0, url, "", "blocked"


class TestRecordHtmlObservation(unittest.TestCase):
    """Self-healing Branch 3: HTML-instead-of-media observations.

    A parked file host answers HTTP 200 with a parking page and no
    redirect, so ``record_redirect`` never fires for it — that is the
    vCloud case (vcloud.lol parked, same codes live on vcloud.my, proven
    2026-11-24). The sweep's ranged-GET fallback records one observation
    per 200 HTML answer here; at ``REDIRECT_MOVE_THRESHOLD`` the host is
    a parked-candidate and discovery runs. Blocked (403/429) never reaches
    this function — the caller only records real 200 HTML answers — so a
    WAF refusal can never count toward the threshold.
    """

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix="host-reg-html-test-")
        self.reg_path = os.path.join(self.tmpdir, "host_registry.json")
        self.obs_path = os.path.join(self.tmpdir, "obs.json")
        with open(self.reg_path, "w", encoding="utf-8") as fh:
            json.dump({"version": "2.0", "hosts": {}}, fh)

    def tearDown(self):
        for p in (self.reg_path, self.obs_path, self.reg_path + ".lock",
                  self.obs_path + ".lock"):
            try:
                os.unlink(p)
            except OSError:
                pass
        try:
            os.rmdir(self.tmpdir)
        except OSError:
            pass

    def _record(self, host, sample_url, n=1, **kwargs):
        kwargs.setdefault("search_fetcher", _blocked_search)
        out = None
        for _ in range(n):
            out = hr.record_html_observation(
                host, sample_url, registry_path=self.reg_path,
                observations_path=self.obs_path, **kwargs)
        return out

    def test_counts_below_threshold_and_nineteen_is_not_twenty(self):
        out = self._record("a.old", "https://a.old/drive/1", n=19)
        self.assertEqual(out["count"], 19)
        self.assertFalse(out["threshold_reached"])
        self.assertFalse(out["applied"])
        self.assertFalse(out["parked_candidate"])

    def test_threshold_without_candidate_marks_parked_candidate_once(self):
        out = self._record("x.old", "https://x.old/drive/1",
                           n=hr.REDIRECT_MOVE_THRESHOLD)
        self.assertTrue(out["threshold_reached"])
        self.assertFalse(out["applied"])
        self.assertIsNone(out["moved_to"])
        self.assertTrue(out["parked_candidate"])
        entry = hr.load_registry(self.reg_path)["hosts"]["x.old"]
        self.assertTrue(entry.get("parked_candidate"))
        self.assertNotIn("parked", entry, "a candidate is not a parked host")
        self.assertTrue(entry.get("parked_at"))
        self.assertIn("no candidate validated", entry.get("parked_reason", ""))
        self.assertNotIn("current_domain", entry, "no move was applied")
        # Evaluated once: a 21st observation counts but never re-evaluates.
        out2 = self._record("x.old", "https://x.old/drive/2")
        self.assertEqual(out2["count"], hr.REDIRECT_MOVE_THRESHOLD + 1)
        self.assertFalse(out2["threshold_reached"])
        self.assertFalse(out2["parked_candidate"])

    def test_redirect_target_validates_and_applies(self):
        fetch = _fake_fetch({"https://b.old/": (302, "https://b.new/")})
        out = self._record("b.old", "https://b.old/drive/1",
                           n=hr.REDIRECT_MOVE_THRESHOLD,
                           redirect_target="b.new", fetcher=fetch)
        self.assertTrue(out["threshold_reached"])
        self.assertTrue(out["applied"])
        self.assertEqual(out["moved_to"], "b.new")
        self.assertFalse(out["parked_candidate"])
        self.assertEqual(hr.current_domain_for("b.old", registry_path=self.reg_path),
                         "b.new")
        entry = hr.load_registry(self.reg_path)["hosts"]["b.old"]
        self.assertEqual(entry["evidence"], "parked-sweep")
        self.assertTrue(entry.get("parked"), "the old host is parked after the move")
        self.assertTrue(entry.get("parked_at"))
        self.assertIn("b.new", entry.get("parked_reason", ""))

    def test_history_candidate_validates_and_applies(self):
        reg = hr.load_registry(self.reg_path)
        reg["hosts"]["h.old"] = {
            "current_domain": "h.old", "evidence": "manual", "status": "active",
            "known_mirrors": ["https://h.new"],
            "rotation_history": [
                {"domain": "h.old", "seen_until": "2026-11-20T19:36:00Z"},
                {"domain": "h.new", "seen_from": "2026-11-20T19:36:00Z"}],
        }
        hr.save_registry(reg, self.reg_path)
        fetch = _fake_fetch({
            "https://h.old/": (200, "https://h.old/"),
            "https://h.new/drive/1": (200, "https://h.new/drive/1"),
        })
        out = self._record("h.old", "https://h.old/drive/1",
                           n=hr.REDIRECT_MOVE_THRESHOLD, fetcher=fetch)
        self.assertTrue(out["applied"])
        self.assertEqual(out["moved_to"], "h.new")
        self.assertEqual(hr.current_domain_for("h.old", registry_path=self.reg_path),
                         "h.new")

    def test_candidate_failing_validation_is_not_applied(self):
        fetch = _fake_fetch({
            "https://c.old/": (404, "https://c.old/"),
            "https://c.new/drive/1": (404, "https://c.new/drive/1"),
            "https://c.new/": (404, "https://c.new/"),
        })
        out = self._record("c.old", "https://c.old/drive/1",
                           n=hr.REDIRECT_MOVE_THRESHOLD,
                           redirect_target="c.new", fetcher=fetch)
        self.assertTrue(out["threshold_reached"])
        self.assertFalse(out["applied"])
        self.assertIsNone(out["moved_to"])
        self.assertTrue(out["parked_candidate"])
        entry = hr.load_registry(self.reg_path)["hosts"]["c.old"]
        self.assertTrue(entry.get("parked_candidate"))
        self.assertEqual(hr.current_domain_for("c.old", registry_path=self.reg_path),
                         "", "a failed candidate must not move the host")

    def test_vcloud_fixture(self):
        """The real case (proven 2026-11-24): vcloud.lol parked, the same
        codes live on vcloud.my, and the registry already knows
        vcloud.beer -> vcloud.my. The parked sibling must find .my through
        its sibling TLD and move — never be marked dead."""
        reg = hr.load_registry(self.reg_path)
        reg["hosts"]["vcloud.beer"] = {
            "current_domain": "vcloud.my", "evidence": "prober",
            "status": "active", "last_verified": "2026-11-20T19:36:00Z",
            "verified_by": "domain-doctor-20261121", "rows_affected": 237014,
            "known_mirrors": ["https://vcloud.my"],
            "rotation_history": [
                {"domain": "vcloud.beer", "seen_until": "2026-11-20T19:36:00Z"},
                {"domain": "vcloud.my", "seen_from": "2026-11-20T19:36:00Z"}],
        }
        hr.save_registry(reg, self.reg_path)
        fetch = _fake_fetch({
            "https://vcloud.lol/": (200, "https://vcloud.lol/"),
            "https://vcloud.my/drive/abc123": (200, "https://vcloud.my/drive/abc123"),
        })
        out = self._record("vcloud.lol", "https://vcloud.lol/drive/abc123",
                           n=hr.REDIRECT_MOVE_THRESHOLD, fetcher=fetch)
        self.assertTrue(out["threshold_reached"])
        self.assertTrue(out["applied"])
        self.assertEqual(out["moved_to"], "vcloud.my")
        self.assertFalse(out["parked_candidate"])
        self.assertEqual(hr.current_domain_for("vcloud.lol", registry_path=self.reg_path),
                         "vcloud.my")
        lol = hr.load_registry(self.reg_path)["hosts"]["vcloud.lol"]
        self.assertTrue(lol.get("parked"))
        self.assertTrue(lol.get("parked_at"))
        self.assertIn("vcloud.my", lol.get("parked_reason", ""))
        # The sibling that named the new domain is untouched.
        beer = hr.load_registry(self.reg_path)["hosts"]["vcloud.beer"]
        self.assertEqual(beer["current_domain"], "vcloud.my")
        self.assertEqual(beer["evidence"], "prober")

    def test_ddg_candidate_validates_and_applies(self):
        page = ('<a href="https://duckduckgo.com/l/?uddg='
                'https%3A%2F%2Fzsite.my%2Fdrive%2F1">zsite.my</a>')

        def search(url, timeout=10):
            return 200, url, page, None

        fetch = _fake_fetch({
            "https://zsite.lol/": (200, "https://zsite.lol/"),
            "https://zsite.my/drive/1": (200, "https://zsite.my/drive/1"),
        })
        out = self._record("zsite.lol", "https://zsite.lol/drive/1",
                           n=hr.REDIRECT_MOVE_THRESHOLD,
                           fetcher=fetch, search_fetcher=search)
        self.assertTrue(out["applied"])
        self.assertEqual(out["moved_to"], "zsite.my")

    def test_ip_host_does_not_count(self):
        out = self._record("93.184.216.34", "https://93.184.216.34/drive/1")
        self.assertEqual(out["count"], 0)
        self.assertFalse(out["threshold_reached"])

    def test_html_and_redirect_counts_are_separate(self):
        """The two signals share a file, not a counter: 19 + 19 must not
        reach either threshold."""
        html = self._record("s.old", "https://s.old/drive/1", n=19)
        redir = None
        for _ in range(19):
            redir = hr.record_redirect("https://s.old/drive/1",
                                       "https://s.new/drive/1",
                                       registry_path=self.reg_path,
                                       observations_path=self.obs_path)
        self.assertEqual(html["count"], 19)
        self.assertFalse(html["threshold_reached"])
        self.assertEqual(redir["count"], 19)
        self.assertFalse(redir["threshold_reached"])


class TestValidate(unittest.TestCase):
    def test_root_redirect_passes(self):
        fetch = _fake_fetch({
            "https://old.example/": (302, "https://new.example/"),
        })
        res = hr.validate_host_move("old.example", "new.example", fetcher=fetch)
        self.assertTrue(res["ok"])
        self.assertEqual(res["method"], "root-redirect")

    def test_sample_200_passes(self):
        fetch = _fake_fetch({
            "https://old.example/": (200, "https://old.example/"),
            "https://new.example/drive/1": (200, "https://new.example/drive/1"),
        })
        res = hr.validate_host_move("old.example", "new.example",
                                    sample_path="/drive/1", fetcher=fetch)
        self.assertTrue(res["ok"])
        self.assertEqual(res["method"], "sample-200")

    def test_new_root_200_passes(self):
        fetch = _fake_fetch({
            "https://old.example/": (200, "https://old.example/"),
            "https://new.example/drive/1": (404, "https://new.example/drive/1"),
            "https://new.example/": (200, "https://new.example/"),
        })
        res = hr.validate_host_move("old.example", "new.example",
                                    sample_path="/drive/1", fetcher=fetch)
        self.assertTrue(res["ok"])
        self.assertEqual(res["method"], "root-200")

    def test_all_fail(self):
        fetch = _fake_fetch({
            "https://old.example/": (404, "https://old.example/"),
            "https://new.example/drive/1": (404, "https://new.example/drive/1"),
            "https://new.example/": (404, "https://new.example/"),
        })
        res = hr.validate_host_move("old.example", "new.example",
                                    sample_path="/drive/1", fetcher=fetch)
        self.assertFalse(res["ok"])
        self.assertIn("does not redirect", res["detail"])

    def test_blocked_is_not_dead(self):
        fetch = _fake_fetch({
            "https://old.example/": (403, "https://old.example/"),
            "https://new.example/drive/1": (403, "https://new.example/drive/1"),
            "https://new.example/": (403, "https://new.example/"),
        })
        res = hr.validate_host_move("old.example", "new.example",
                                    sample_path="/drive/1", fetcher=fetch)
        self.assertFalse(res["ok"])
        self.assertIn("blocked", res["detail"])

    def test_same_host_rejected(self):
        res = hr.validate_host_move("a.example", "a.example")
        self.assertFalse(res["ok"])


class TestCheckHostRoot(unittest.TestCase):
    def test_moved(self):
        fetch = _fake_fetch({"https://old.example/": (301, "https://new.example/")})
        res = hr.check_host_root("old.example", fetcher=fetch)
        self.assertEqual(res["moved_to"], "new.example")
        self.assertEqual(res["status"], 301)

    def test_alive(self):
        fetch = _fake_fetch({"https://ok.example/": (200, "https://ok.example/")})
        res = hr.check_host_root("ok.example", fetcher=fetch)
        self.assertEqual(res["status"], 200)
        self.assertIsNone(res["moved_to"])

    def test_blocked_down_gone(self):
        cases = [
            ("https://b.example/", (403, "https://b.example/"), "blocked http403"),
            ("https://d.example/", (0, "https://d.example/", "dns"), "down"),
            ("https://g.example/", (404, "https://g.example/"), "gone http404"),
        ]
        for url, hit, reason_start in cases:
            host = url.split("/")[2]
            fetch = _fake_fetch({url: hit})
            res = hr.check_host_root(host, fetcher=fetch)
            self.assertTrue(res["reason"].startswith(reason_start), res)


class TestRootCheckScript(unittest.TestCase):
    def test_check_all_flags_move_and_records_observation(self):
        import check_host_roots
        path, _data = _tmp_registry()
        obs = os.path.join(tempfile.mkdtemp(prefix="host-reg-obs-"), "obs.json")
        try:
            mapping = {
                "https://hubcloud.ist/": (302, "https://hubcloud.mov/"),
                "https://hubcloud.mov/": (200, "https://hubcloud.mov/"),
            }
            # Every other host answers 200 on its own root (no move).
            summary = check_host_roots.check_all(
                registry_path=path, observations_path=obs, only="hubcloud",
                fetcher=_fake_fetch(mapping))
            # hubcloud.ist + hubcloud.pw (old) + hubcloud.mov (current).
            self.assertEqual(summary["hosts_checked"], 3)
            moved = [r for r in summary["results"] if r.get("moved_to")]
            self.assertEqual(len(moved), 1)
            self.assertEqual(moved[0]["host"], "hubcloud.ist")
            self.assertEqual(moved[0]["moved_to"], "hubcloud.mov")
            self.assertEqual(moved[0].get("observation_count"), 1)
        finally:
            os.unlink(path)
            try:
                os.unlink(obs)
            except OSError:
                pass


class TestProbeWiring(unittest.TestCase):
    def test_probe_host_records_redirect_observation(self):
        import fast_probe
        import unittest.mock
        seen = []

        def spy(old, new):
            seen.append((old, new))
            return {"old_host": "a.old", "new_host": "a.new", "count": 1,
                    "threshold_reached": False, "applied": False}

        real = hr.record_redirect
        fast_probe.host_registry.record_redirect = spy
        try:
            import http.client

            class FakeConn:
                """A keep-alive connection: request() + getresponse().
                Only answers 302 on the OLD host; the new host answers 200,
                or the chain would loop on the same path."""
                def __init__(self, host, *a, **k):
                    self._host = host
                    self._last = None

                def request(self, method, target, headers=None):
                    self._last = target

                def getresponse(self):
                    host = self._host

                    class Resp:
                        def __init__(self, target):
                            self.status = 302 if (host == "a.old"
                                                  and target == "/drive/1") else 200
                            self.will_close = False

                        def getheaders(self):
                            if self.status == 302:
                                return [("Location", "https://a.new/drive/1")]
                            return []

                        def read(self):
                            return b""
                    return Resp(self._last)

                def close(self):
                    pass

            with unittest.mock.patch(
                    "socket.getaddrinfo",
                    side_effect=_public_getaddrinfo):
                stats = fast_probe._probe_host(
                    "a.old", [{"url": "https://a.old/drive/1", "row_ids": [1],
                               "host": "a.old", "stored": None}],
                    session_factory=lambda host, port, https, timeout, **k: FakeConn(host),
                    timeout=8.0, spacing_s=0.0, headers={})
            self.assertEqual(seen, [("https://a.old/drive/1",
                                     "https://a.new/drive/1")])
            self.assertEqual(stats["redirects_seen"], 1)
            self.assertEqual(stats["verdicts"], 1)  # verdict policy untouched
        finally:
            fast_probe.host_registry.record_redirect = real

    def test_merge_stats_back_compat(self):
        import fast_probe
        merged = fast_probe._merge_stats([
            {"host": "a", "probed": 1, "verdicts": 1, "by_status": {},
             "no_verdict": 0, "blocked": 0, "reconnects": 0},
        ])
        self.assertEqual(merged["redirects_seen"], 0)
        self.assertEqual(merged["moves_applied"], 0)


class TestFastFetchIntegration(unittest.TestCase):
    def test_get_domains_reads_host_registry_last(self):
        import fast_fetch as ff
        domains = ff.get_domains()
        self.assertEqual(domains.get("hubcloud"), "https://hubcloud.mov")
        self.assertEqual(domains.get("directcloud"),
                         "https://storage.direct-cloud.org")
        # No audited pair: the literal survives (honest fallback).
        self.assertEqual(domains.get("filepress"), "https://new1.filepress.lat")

    def test_mobilejsr_unwrap_short_circuits(self):
        import fast_fetch as ff
        rows = ff.unwrap_mobilejsr("https://mobilejsr.com/view/abc")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["service"], "mobilejsr")
        self.assertEqual(rows[0]["source_url"], "https://mobilejsr.com/view/abc")


if __name__ == "__main__":
    unittest.main()
