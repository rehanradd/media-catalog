#!/usr/bin/env python3
"""Tests for domain_recheck.py — the site recheck job (Branch 4).

Offline, stdlib, no network: the discovery chain runs against a stub
fetcher, the sweep batch against a fake connection factory. Pinned here:
* drain_queue resolves a queued site and marks it done; an unresolved
  site (or a resolver error) stays pending for the next drain;
* probe_stale_sites runs the full chain only for sites the stale probe
  finds down — blocked/unverifiable sites are left alone;
* end to end: a sweep batch whose rows sit on a parked site base queues
  the site, and the drain moves the registry to the live domain with
  the old domain closed as parked (timestamp + reason).
"""

import json
import socket
import sqlite3
import sys
import tempfile
import unittest
import unittest.mock
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import domain_recheck as drc
import domain_resolver as dr
import fast_probe
import recheck_queue as rq

WP_GOOD = json.dumps([
    {"link": "https://ssrmovies.surf/post/one/",
     "title": {"rendered": "The Great Indian Kapil Show S05 1080p 720p WEB-DL"}},
    {"link": "https://ssrmovies.surf/post/two/",
     "title": {"rendered": "Pushpa 2 The Rule 2024 Hindi 720p HDRip"}},
])
PARKED_PAGE = ("<html><head><title>new1.ssrmovies.page</title></head><body>"
               + ("x" * 3000)
               + "<p>This domain is for sale — buy this domain now on Sedo.</p>"
               + "</body></html>")

_FAKE_ADDRINFO = [(socket.AF_INET, socket.SOCK_STREAM, 6, "",
                   ("93.184.216.34", 80))]


def _public_dns(test):
    import functools

    @functools.wraps(test)
    def wrapper(self, *args, **kwargs):
        with unittest.mock.patch("socket.getaddrinfo",
                                 return_value=_FAKE_ADDRINFO):
            return test(self, *args, **kwargs)

    return wrapper


class StubFetch:
    """Exact-URL -> (status, final_url, text, error); unrouted fails closed."""

    def __init__(self, routes):
        self.routes = routes

    def __call__(self, url, timeout=8, max_bytes=200_000):
        for prefix, value in self.routes.items():
            if url == prefix or url.startswith(prefix + "?"):
                return value
        return (0, url, "", "unrouted-stub-url")


def temp_registry(sites):
    tmp = tempfile.TemporaryDirectory()
    path = Path(tmp.name) / "domain_registry.json"
    path.write_text(json.dumps({"version": "2.0", "sites": sites}), encoding="utf-8")
    return tmp, path


def site_entry(**over):
    entry = {"active_base_url": "https://new1.ssrmovies.page",
             "known_mirrors": ["https://new1.ssrmovies.page",
                               "https://ssrmovies.surf"],
             "slug_variants": ["ssrmovies"], "tld_history": ["page", "surf"],
             "api_type": "wp_rest_api", "status": "active"}
    entry.update(over)
    return entry


class DrainQueueTests(unittest.TestCase):
    def _setup(self, sites):
        tmp, reg_path = temp_registry(sites)
        qtmp = tempfile.TemporaryDirectory()
        qpath = Path(qtmp.name) / "domain_recheck_queue.db"
        return tmp, reg_path, qtmp, qpath

    def test_drain_resolves_and_marks_done(self):
        tmp, reg_path, qtmp, qpath = self._setup(
            {"ssrmovies": site_entry()})
        try:
            rq.enqueue(qpath, "ssrmovies", 12, "batch-dead-rows:12")
            outcomes = drc.drain_queue(
                qpath, registry_path=reg_path,
                resolver=lambda site: "https://ssrmovies.surf")
            self.assertEqual(len(outcomes), 1)
            self.assertEqual(outcomes[0]["result"],
                             "resolved:https://ssrmovies.surf")
            entry = rq.get_entry(qpath, "ssrmovies")
            self.assertEqual(entry["status"], "done")
            self.assertEqual(entry["attempts"], 1)
        finally:
            tmp.cleanup()
            qtmp.cleanup()

    def test_drain_unresolved_stays_pending(self):
        tmp, reg_path, qtmp, qpath = self._setup(
            {"ssrmovies": site_entry(status="down")})
        try:
            rq.enqueue(qpath, "ssrmovies", 12, "batch-dead-rows:12")
            outcomes = drc.drain_queue(
                qpath, registry_path=reg_path, resolver=lambda site: "")
            self.assertEqual(outcomes[0]["result"], "unresolved:down")
            entry = rq.get_entry(qpath, "ssrmovies")
            self.assertEqual(entry["status"], "pending")
            self.assertEqual(entry["attempts"], 1)
            # Cooling down: the next drain (default 6h) finds nothing due.
            self.assertEqual(drc.drain_queue(
                qpath, registry_path=reg_path, resolver=lambda site: ""), [])
        finally:
            tmp.cleanup()
            qtmp.cleanup()

    def test_drain_error_stays_pending(self):
        tmp, reg_path, qtmp, qpath = self._setup(
            {"ssrmovies": site_entry()})
        try:
            rq.enqueue(qpath, "ssrmovies", 12, "batch-dead-rows:12")

            def boom(site):
                raise RuntimeError("chain exploded")

            outcomes = drc.drain_queue(
                qpath, registry_path=reg_path, resolver=boom)
            self.assertTrue(outcomes[0]["result"].startswith("error:RuntimeError"))
            self.assertEqual(rq.get_entry(qpath, "ssrmovies")["status"], "pending")
        finally:
            tmp.cleanup()
            qtmp.cleanup()

    def test_drain_empty_queue_is_a_noop(self):
        tmp, reg_path, qtmp, qpath = self._setup(
            {"ssrmovies": site_entry()})
        try:
            self.assertEqual(drc.drain_queue(
                qpath, registry_path=reg_path,
                resolver=lambda site: "https://ssrmovies.surf"), [])
        finally:
            tmp.cleanup()
            qtmp.cleanup()


class ProbeStaleTests(unittest.TestCase):
    def test_down_site_gets_full_chain(self):
        # The cheap probe fails on every candidate (the only mirror is the
        # dead base itself), so the site is down and the full chain runs —
        # and discovers the live domain through the TLD sibling step.
        tmp, reg_path = temp_registry({"ssrmovies": site_entry(
            known_mirrors=["https://new1.ssrmovies.page"])})
        try:
            reg = dr.load_registry(reg_path)
            reg["sites"]["ssrmovies"]["last_verified"] = "2020-01-01T00:00:00Z"
            dr.save_registry(reg, reg_path)
            stub = StubFetch({
                "https://new1.ssrmovies.page/wp-json/wp/v2/posts": (
                    404, "https://new1.ssrmovies.page/wp-json/wp/v2/posts?per_page=3",
                    "", None),
                "https://ssrmovies.surf/wp-json/wp/v2/posts": (
                    200, "https://ssrmovies.surf/wp-json/wp/v2/posts?per_page=3",
                    WP_GOOD, None),
            })
            outcomes = drc.probe_stale_sites(
                registry_path=reg_path, stale_days=7, fetcher=stub)
            self.assertEqual(len(outcomes), 1)
            self.assertEqual(outcomes[0]["site"], "ssrmovies")
            self.assertEqual(outcomes[0]["result"],
                             "resolved:https://ssrmovies.surf")
            reg = dr.load_registry(reg_path)
            self.assertEqual(reg["sites"]["ssrmovies"]["active_base_url"],
                             "https://ssrmovies.surf")
        finally:
            tmp.cleanup()

    def test_blocked_site_is_left_alone(self):
        tmp, reg_path = temp_registry({"ssrmovies": site_entry()})
        try:
            reg = dr.load_registry(reg_path)
            reg["sites"]["ssrmovies"]["last_verified"] = "2020-01-01T00:00:00Z"
            dr.save_registry(reg, reg_path)
            stub = StubFetch({
                "https://new1.ssrmovies.page/wp-json/wp/v2/posts": (
                    403, "https://new1.ssrmovies.page/wp-json/wp/v2/posts?per_page=3",
                    "<html><head><title>Just a moment</title></head></html>", None),
            })
            outcomes = drc.probe_stale_sites(
                registry_path=reg_path, stale_days=7, fetcher=stub)
            self.assertEqual(outcomes, [])
            reg = dr.load_registry(reg_path)
            self.assertEqual(reg["sites"]["ssrmovies"]["status"], "unverifiable")
        finally:
            tmp.cleanup()

    def test_fresh_site_is_not_probed(self):
        tmp, reg_path = temp_registry({"ssrmovies": site_entry()})
        try:
            reg = dr.load_registry(reg_path)
            reg["sites"]["ssrmovies"]["last_verified"] = dr._now_iso()
            dr.save_registry(reg, reg_path)
            outcomes = drc.probe_stale_sites(
                registry_path=reg_path, stale_days=7, fetcher=StubFetch({}))
            self.assertEqual(outcomes, [])
        finally:
            tmp.cleanup()


# ── end to end: sweep batch -> queue -> drain -> registry move ───────────────

class _FakeResp:
    def __init__(self, status, body=b""):
        self.status = status
        self._body = body or b""
        self._read = False

    def getheaders(self):
        return []

    def read(self, *args):
        if self._read:
            return b""
        self._read = True
        return self._body


class _FakeConn:
    def __init__(self, behaviour):
        self._behaviour = behaviour
        self._pending = None

    def request(self, method, target, headers=None, **kwargs):
        self._pending = self._behaviour(method, target)

    def getresponse(self):
        status, body = self._pending
        return _FakeResp(status, body)

    def close(self):
        pass


def _make_factory(behaviour):
    def factory(host, port, https, timeout):
        return _FakeConn(behaviour)

    return factory


class EndToEndTests(unittest.TestCase):
    """The brief's integration test: a test site's active base is a
    parked domain; the sweep batch queues it, the drain moves the
    registry to the live domain, and the old domain is closed parked
    with timestamp + reason."""

    PARKED_BODY = (b"<!doctype html><html><head><title>Buy this domain</title>"
                   b"</head><body><h1>This domain is for sale</h1></body></html>")

    @_public_dns
    def test_parked_site_queues_then_moves(self):
        tmp = tempfile.TemporaryDirectory()
        try:
            reg_tmp, reg_path = temp_registry(
                {"ssrmovies": site_entry()})
            db_path = Path(tmp.name) / "staging.db"
            conn = sqlite3.connect(str(db_path))
            conn.execute("""
                CREATE TABLE download_sources (
                    id INTEGER PRIMARY KEY,
                    source_url TEXT, final_url TEXT,
                    status TEXT, http_status INTEGER,
                    is_resumable INTEGER DEFAULT 0, file_size_bytes INTEGER,
                    mime_type TEXT, last_checked_at TEXT,
                    checked_at REAL, probe_error TEXT, protocol TEXT,
                    latency_ms INTEGER, speed_mbps REAL, cors_open INTEGER DEFAULT 0
                )""")
            for i in range(12):
                conn.execute(
                    "INSERT INTO download_sources (id, source_url, final_url)"
                    " VALUES (?, ?, ?)",
                    (i + 1, f"https://new1.ssrmovies.page/post/{i}",
                     f"https://new1.ssrmovies.page/post/{i}"))
            conn.commit()

            def behaviour(method, target):
                if method == "HEAD":
                    return (403, b"")
                return (200, self.PARKED_BODY)

            mock_registry = unittest.mock.Mock()
            mock_registry.record_redirect.return_value = {
                "count": 0, "applied": False}
            mock_registry.record_html_observation.return_value = {
                "count": 1, "threshold_reached": False, "applied": False,
                "moved_to": None, "parked_candidate": False}
            with unittest.mock.patch.object(fast_probe, "host_registry",
                                           mock_registry):
                summary = fast_probe.run_pilot(
                    conn, max_rows=500, workers=2, timeout=5.0,
                    spacing_s=0.0, session_factory=_make_factory(behaviour),
                    stop_file="", registry_path=reg_path)
            conn.close()
            self.assertEqual(summary["by_status"], {"parked": 12})
            self.assertEqual(summary["site_signals"], {"ssrmovies": 12})
            self.assertEqual(summary["site_rechecks_queued"], ["ssrmovies"])

            qpath = rq.queue_path_for_db(db_path)
            entry = rq.get_entry(qpath, "ssrmovies")
            self.assertIsNotNone(entry)
            self.assertEqual(entry["dead_rows"], 12)

            stub = StubFetch({
                "https://new1.ssrmovies.page/wp-json/wp/v2/posts": (
                    200, "https://new1.ssrmovies.page/wp-json/wp/v2/posts?per_page=3",
                    PARKED_PAGE, None),
                "https://ssrmovies.surf/wp-json/wp/v2/posts": (
                    200, "https://ssrmovies.surf/wp-json/wp/v2/posts?per_page=3",
                    WP_GOOD, None),
            })
            outcomes = drc.drain_queue(qpath, registry_path=reg_path,
                                       fetcher=stub)
            self.assertEqual(len(outcomes), 1)
            self.assertEqual(outcomes[0]["result"],
                             "resolved:https://ssrmovies.surf")
            reg = dr.load_registry(reg_path)
            site = reg["sites"]["ssrmovies"]
            self.assertEqual(site["active_base_url"], "https://ssrmovies.surf")
            hist = site["rotation_history"]
            self.assertEqual(hist[0]["domain"], "https://new1.ssrmovies.page")
            self.assertEqual(hist[0]["status"], "parked")
            self.assertTrue(hist[0]["reason"].startswith("parked-marker:"))
            self.assertTrue(hist[0]["seen_until"])
            self.assertEqual(hist[1]["domain"], "https://ssrmovies.surf")
            self.assertEqual(hist[1]["discovered_by"], "mirror")
            self.assertEqual(rq.get_entry(qpath, "ssrmovies")["status"], "done")
            reg_tmp.cleanup()
        finally:
            tmp.cleanup()


if __name__ == "__main__":
    unittest.main()
