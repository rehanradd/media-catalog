#!/usr/bin/env python3
"""Tests for the self-healing durability layer (Branch 6, design §4).

Offline, stdlib, no network: the log, the prune, the reconciliation
and the weekly job all run against tmp paths, and every HTTP hop in
the parked-flag path goes through an injected fake fetcher. The log
path is redirected with RADD_SELF_HEALING_LOG so no test ever writes
into the repo tree. Pinned here:

* log_event writes exactly one JSON line with all six fields plus
  ts; an unknown kind or channel writes nothing and never raises;
* rotate_log moves only a log whose last write is from an earlier
  ISO week, to a week-suffixed file;
* prune_observations drops only stale, never-fired records — an old
  applied_at / evaluated_at record is kept (it is the spent marker
  that stops the same pair re-applying), and a record with no
  parsable timestamp is kept;
* the reconciliation test (design §4 "Verified by"): both registries
  loaded, a disagreement injected, the resolve path rewrites via the
  host registry AND the site lands in the recheck queue; a dead host
  queues too; agreement queues nothing;
* the log-coverage test (design §4.1): every auto-change kind writes
  exactly one line through its real call site — site-move via
  _record_verified, host-move via apply_move, parked-flag via
  record_html_observation, cache-refusal + expiry-banked via
  record_resolved_cache — and an explicit resolver expiry is NOT
  logged as a derivation;
* the merge-rule hook in resolve_live_source checks a site at most
  once per hour per process;
* no backend module reads movie-downloader-state.json (design §4.4:
  the script's last-known-good file stays script-local, never
  backend truth).
"""

from __future__ import annotations

import calendar
import io
import json
import os
import sqlite3
import sys
import tempfile
import time
import unittest
import unittest.mock
from datetime import datetime, timezone
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import domain_resolver as dr
import host_registry as hr
import recheck_queue as rq
import resolved_cache
import self_healing_log as shl

NOW = int(time.time())
FIXED_TS = "2026-11-27T15:30:00Z"


def _iso(epoch: float) -> str:
    return datetime.fromtimestamp(epoch, tz=timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%SZ")


def _r2_url(expires_in: int, *, issued_ago: int = 0,
            now: int = NOW) -> str:
    issued = datetime.fromtimestamp(now - issued_ago, tz=timezone.utc)
    date = issued.strftime("%Y%m%dT%H%M%SZ")
    return ("https://abc123.r2.cloudflarestorage.com/hex/file.mkv"
            f"?X-Amz-Date={date}&X-Amz-Expires={expires_in}"
            "&X-Amz-Signature=00")


def _read_lines(path) -> list:
    path = Path(path)
    if not path.exists():
        return []
    return [json.loads(line) for line in
            path.read_text(encoding="utf-8").splitlines() if line.strip()]


class _TmpLogTest(unittest.TestCase):
    """A tmp log path, redirected via the env var the production
    call sites read — so apply_move / _record_verified /
    record_resolved_cache log to the tmp file, never the repo."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.log_path = Path(self.tmp.name) / "self_healing.log"
        self._env = unittest.mock.patch.dict(
            os.environ,
            {"RADD_SELF_HEALING_LOG": str(self.log_path)})
        self._env.start()
        self.addCleanup(self._env.stop)
        self.addCleanup(self.tmp.cleanup)

    def lines(self):
        return _read_lines(self.log_path)


class LogEventTests(_TmpLogTest):
    def test_one_line_with_all_fields(self):
        rec = shl.log_event(
            "site-move", site="vegamovies", old="https://vegamovies.old",
            new="https://vegamovies.new", discovered_by="tld",
            reason="tld-probe 200", verified_by="probe", ts=FIXED_TS)
        self.assertIsNotNone(rec)
        lines = self.lines()
        self.assertEqual(len(lines), 1)
        line = lines[0]
        for field in ("ts", "kind", "site", "host", "old", "new",
                      "discovered_by", "reason", "verified_by"):
            self.assertIn(field, line)
        self.assertEqual(line["ts"], FIXED_TS)
        self.assertEqual(line["kind"], "site-move")
        self.assertEqual(line["site"], "vegamovies")
        self.assertEqual(line["old"], "https://vegamovies.old")
        self.assertEqual(line["new"], "https://vegamovies.new")
        self.assertEqual(line["discovered_by"], "tld")
        self.assertEqual(line["reason"], "tld-probe 200")
        self.assertEqual(line["verified_by"], "probe")

    def test_unknown_kind_writes_nothing(self):
        self.assertIsNone(shl.log_event(
            "nope", host="example.com", discovered_by="sweep"))
        self.assertFalse(self.log_path.exists())

    def test_unknown_channel_writes_nothing(self):
        self.assertIsNone(shl.log_event(
            "host-move", host="example.com", discovered_by="manual"))
        self.assertFalse(self.log_path.exists())

    def test_two_events_append_in_order(self):
        shl.log_event("host-move", host="a.old", old="a.old",
                      new="a.new", discovered_by="redirect",
                      reason="r", verified_by="prober", ts=FIXED_TS)
        shl.log_event("host-move", host="b.old", old="b.old",
                      new="b.new", discovered_by="sweep",
                      reason="r", verified_by="parked-sweep", ts=FIXED_TS)
        lines = self.lines()
        self.assertEqual([l["host"] for l in lines], ["a.old", "b.old"])

    def test_unwritable_path_returns_none_without_raising(self):
        blocker = Path(self.tmp.name) / "not-a-dir"
        blocker.write_text("x", encoding="utf-8")
        self.assertIsNone(shl.log_event(
            "host-move", host="a.old", discovered_by="redirect",
            log_path=blocker / "self_healing.log"))
        self.assertFalse((blocker / "self_healing.log").exists())

    def test_default_path_is_the_log_filename(self):
        self.assertEqual(shl.default_log_path().name, "self_healing.log")

    def test_channel_mappings(self):
        self.assertEqual(
            shl.discovered_by_for_site_method("tld-probe"), "tld")
        self.assertEqual(
            shl.discovered_by_for_site_method("numeric-probe"), "tld")
        self.assertEqual(
            shl.discovered_by_for_site_method("search-ddg"), "search")
        self.assertEqual(
            shl.discovered_by_for_site_method("bookmark-follow"),
            "redirect")
        self.assertEqual(
            shl.discovered_by_for_site_method("mirror"), "recheck")
        self.assertEqual(
            shl.discovered_by_for_site_method("something-new"), "recheck")
        self.assertEqual(
            shl.discovered_by_for_host_evidence("prober"), "redirect")
        self.assertEqual(
            shl.discovered_by_for_host_evidence("parked-sweep"), "sweep")
        self.assertEqual(
            shl.discovered_by_for_host_evidence("manual"), "recheck")


class RotateLogTests(_TmpLogTest):
    def test_current_week_file_is_left_alone(self):
        self.log_path.write_text('{"kind": "x"}\n', encoding="utf-8")
        self.assertIsNone(shl.rotate_log(self.log_path))
        self.assertTrue(self.log_path.exists())

    def test_last_week_file_rotates_to_week_suffix(self):
        self.log_path.write_text('{"kind": "x"}\n', encoding="utf-8")
        old_mtime = time.time() - 8 * 86400
        os.utime(self.log_path, (old_mtime, old_mtime))
        week = time.strftime("%G-W%V", time.gmtime(old_mtime))
        rotated = shl.rotate_log(self.log_path)
        self.assertEqual(rotated, str(self.log_path.with_name(
            "self_healing.log.%s" % week)))
        self.assertFalse(self.log_path.exists())
        self.assertTrue(Path(rotated).exists())

    def test_missing_file_rotates_to_nothing(self):
        self.assertIsNone(shl.rotate_log(self.log_path))


class PruneObservationsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.obs_path = Path(self.tmp.name) / "obs.json"
        self.addCleanup(self.tmp.cleanup)
        self.now = float(NOW)

    def _write(self, observations, html_observations):
        data = {"version": 1, "observations": observations,
                "html_observations": html_observations}
        self.obs_path.write_text(json.dumps(data), encoding="utf-8")
        return data

    def _rec(self, *, days_ago=None, applied=False, evaluated=False,
             no_timestamp=False):
        rec = {"count": 3, "sample_urls": []}
        if not no_timestamp:
            seen = _iso(self.now - (days_ago or 0) * 86400)
            rec["first_seen"] = seen
            rec["last_seen"] = seen
        if applied:
            rec["applied_at"] = _iso(self.now - 100 * 86400)
        if evaluated:
            rec["evaluated_at"] = _iso(self.now - 100 * 86400)
        return rec

    def test_old_unfired_records_are_pruned(self):
        self._write({"a.old|a.new": self._rec(days_ago=100)},
                    {"stale.host": self._rec(days_ago=120)})
        out = hr.prune_observations(self.obs_path, now=self.now)
        self.assertEqual(out["observations_pruned"], 1)
        self.assertEqual(out["html_pruned"], 1)
        data = json.loads(self.obs_path.read_text(encoding="utf-8"))
        self.assertEqual(data["observations"], {})
        self.assertEqual(data["html_observations"], {})

    def test_old_applied_and_evaluated_records_are_kept(self):
        # The spent markers stop the same pair re-applying: pruning
        # them would let a dead pair re-accumulate from zero.
        self._write({"a.old|a.new": self._rec(days_ago=100, applied=True)},
                    {"stale.host": self._rec(days_ago=100, evaluated=True)})
        out = hr.prune_observations(self.obs_path, now=self.now)
        self.assertEqual(out["observations_pruned"], 0)
        self.assertEqual(out["html_pruned"], 0)
        self.assertEqual(out["observations_kept"], 1)
        self.assertEqual(out["html_kept"], 1)

    def test_recent_and_undated_records_are_kept(self):
        self._write({"a.old|a.new": self._rec(days_ago=10),
                     "b.old|b.new": self._rec(no_timestamp=True)},
                    {"fresh.host": self._rec(days_ago=89)})
        out = hr.prune_observations(self.obs_path, now=self.now)
        self.assertEqual(out["observations_pruned"], 0)
        self.assertEqual(out["html_pruned"], 0)
        self.assertEqual(out["observations_kept"], 2)
        self.assertEqual(out["html_kept"], 1)

    def test_dry_run_measures_without_rewriting(self):
        self._write({"a.old|a.new": self._rec(days_ago=100)}, {})
        before = self.obs_path.read_text(encoding="utf-8")
        out = hr.prune_observations(self.obs_path, now=self.now,
                                     dry_run=True)
        self.assertEqual(out["observations_pruned"], 1)
        self.assertEqual(self.obs_path.read_text(encoding="utf-8"), before)

    def test_missing_file_returns_zeros(self):
        out = hr.prune_observations(self.obs_path, now=self.now)
        self.assertEqual(out, {"observations_pruned": 0, "html_pruned": 0,
                               "observations_kept": 0, "html_kept": 0})


class ReconciliationTests(_TmpLogTest):
    """Design §4 'Verified by': both registries loaded, a
    disagreement injected, the resolve path rewrites via the host
    registry AND the site lands in the recheck queue."""

    def setUp(self):
        super().setUp()
        self.reg_path = Path(self.tmp.name) / "domain_registry.json"
        self.host_path = Path(self.tmp.name) / "host_registry.json"
        self.queue_path = Path(self.tmp.name) / "queue.db"
        dr.save_registry(
            {"version": "2.0", "sites": {
                "vegamovies": {
                    "site_name": "vegamovies",
                    "active_base_url": "https://vegamovies.old/",
                    "status": "active"}}},
            registry_path=self.reg_path)

    def _set_host(self, entry):
        hr.save_registry({"version": "2.0", "hosts": {"vegamovies.old": entry}},
                         registry_path=self.host_path)

    def test_moved_host_rewrites_and_queues(self):
        self._set_host({"current_domain": "vegamovies.new",
                        "status": "active", "evidence": "prober"})
        out = shl.reconcile_site_host(
            "vegamovies", queue_path=self.queue_path,
            domain_registry_path=self.reg_path,
            host_registry_path=self.host_path,
            log_path=self.log_path)
        self.assertEqual(out["status"], "moved")
        self.assertEqual(out["current"], "vegamovies.new")
        self.assertTrue(out["queued"])
        # The resolve path rewrites via the host registry.
        self.assertEqual(
            hr.rewrite_url("https://vegamovies.old/x/file.mkv",
                           registry_path=self.host_path),
            "https://vegamovies.new/x/file.mkv")
        # And the site lands in the recheck queue.
        entry = rq.get_entry(self.queue_path, "vegamovies")
        self.assertIsNotNone(entry)
        self.assertEqual(entry["status"], "pending")
        self.assertIn("registry-disagreement", entry["reason"])
        # The disagreement is logged, once.
        lines = self.lines()
        self.assertEqual(len(lines), 1)
        self.assertEqual(lines[0]["kind"], "site-move")
        self.assertEqual(lines[0]["site"], "vegamovies")
        self.assertEqual(lines[0]["discovered_by"], "recheck")
        self.assertEqual(lines[0]["verified_by"], "host-registry")

    def test_dead_host_queues_with_no_successor(self):
        self._set_host({"status": "dead", "evidence": "audit-dead"})
        out = shl.reconcile_site_host(
            "vegamovies", queue_path=self.queue_path,
            domain_registry_path=self.reg_path,
            host_registry_path=self.host_path,
            log_path=self.log_path)
        self.assertEqual(out["status"], "dead")
        self.assertEqual(out["current"], "")
        self.assertTrue(out["queued"])
        self.assertTrue(hr.is_dead_host("vegamovies.old",
                                        registry_path=self.host_path))
        # A dead host is never rewritten to a guess.
        self.assertEqual(
            hr.rewrite_url("https://vegamovies.old/x/file.mkv",
                           registry_path=self.host_path),
            "https://vegamovies.old/x/file.mkv")
        self.assertIsNotNone(rq.get_entry(self.queue_path, "vegamovies"))

    def test_agreement_queues_nothing(self):
        self._set_host({"current_domain": "vegamovies.old",
                        "status": "active"})
        out = shl.reconcile_site_host(
            "vegamovies", queue_path=self.queue_path,
            domain_registry_path=self.reg_path,
            host_registry_path=self.host_path,
            log_path=self.log_path)
        self.assertIsNone(out)
        self.assertIsNone(rq.get_entry(self.queue_path, "vegamovies"))
        self.assertEqual(self.lines(), [])

    def test_unknown_site_returns_none(self):
        self.assertIsNone(shl.reconcile_site_host(
            "not-a-site", queue_path=self.queue_path,
            domain_registry_path=self.reg_path,
            host_registry_path=self.host_path,
            log_path=self.log_path))


class LogCoverageTests(_TmpLogTest):
    """Every auto-change kind writes exactly one line, through its
    real call site."""

    def test_site_move_logs_once_via_record_verified(self):
        dr.save_registry(
            {"version": "2.0", "sites": {
                "vegamovies": {
                    "site_name": "vegamovies",
                    "active_base_url": "https://vegamovies.old/",
                    "status": "down"}}},
            registry_path=Path(self.tmp.name) / "reg.json")
        reg_path = Path(self.tmp.name) / "reg.json"
        hit = {"live": "https://vegamovies.new/",
               "reason": "mirror 200", "verified_by": "probe",
               "method": "mirror"}
        base = dr._record_verified(
            {}, "vegamovies", {"site_name": "vegamovies"}, hit, reg_path,
            discovered_by="mirror")
        self.assertEqual(base, "https://vegamovies.new/")
        lines = self.lines()
        self.assertEqual(len(lines), 1)
        self.assertEqual(lines[0]["kind"], "site-move")
        self.assertEqual(lines[0]["site"], "vegamovies")
        self.assertEqual(lines[0]["old"], "https://vegamovies.old/")
        self.assertEqual(lines[0]["new"], "https://vegamovies.new/")
        self.assertEqual(lines[0]["discovered_by"], "recheck")
        self.assertEqual(lines[0]["verified_by"], "probe")
        # A re-verified same base is not a move: no second line.
        hit_same = {"live": "https://vegamovies.new/",
                    "reason": "cached 200", "verified_by": "probe",
                    "method": "cached"}
        dr._record_verified({}, "vegamovies",
                            {"site_name": "vegamovies"}, hit_same, reg_path,
                            discovered_by="cached")
        self.assertEqual(len(self.lines()), 1)

    def test_host_move_logs_once_via_apply_move(self):
        reg_path = Path(self.tmp.name) / "host.json"
        hr.apply_move("a.old", "a.new", "prober", registry_path=reg_path)
        lines = self.lines()
        self.assertEqual(len(lines), 1)
        self.assertEqual(lines[0]["kind"], "host-move")
        self.assertEqual(lines[0]["host"], "a.old")
        self.assertEqual(lines[0]["old"], "a.old")
        self.assertEqual(lines[0]["new"], "a.new")
        self.assertEqual(lines[0]["discovered_by"], "redirect")
        hr.apply_move("b.old", "b.new", "parked-sweep",
                      registry_path=reg_path)
        lines = self.lines()
        self.assertEqual(len(lines), 2)
        self.assertEqual(lines[1]["discovered_by"], "sweep")

    def test_parked_flag_logs_once_via_html_observation(self):
        reg_path = Path(self.tmp.name) / "host.json"
        obs_path = Path(self.tmp.name) / "obs.json"
        # Pre-seed 19 observations: this call is the 20th, the
        # threshold, so the host is evaluated — and with no candidate
        # validating it becomes a parked-candidate.
        obs_path.write_text(json.dumps({
            "version": 1, "observations": {},
            "html_observations": {"parked.example": {
                "count": hr.REDIRECT_MOVE_THRESHOLD - 1,
                "first_seen": _iso(NOW - 3600),
                "last_seen": _iso(NOW - 60),
                "sample_urls": [], "evaluated_at": None}}}),
            encoding="utf-8")

        def _dead_fetch(url, timeout=10):
            return 0, url, "connection refused"

        out = hr.record_html_observation(
            "parked.example", "https://parked.example/file.mkv",
            registry_path=reg_path, observations_path=obs_path,
            fetcher=_dead_fetch, search_fetcher=lambda q: ([], "no"))
        self.assertTrue(out["threshold_reached"])
        self.assertTrue(out["parked_candidate"])
        lines = self.lines()
        self.assertEqual(len(lines), 1)
        self.assertEqual(lines[0]["kind"], "parked-flag")
        self.assertEqual(lines[0]["host"], "parked.example")
        self.assertEqual(lines[0]["new"], "parked_candidate")
        self.assertEqual(lines[0]["discovered_by"], "sweep")

    def test_cache_refusal_logs_once(self):
        conn = sqlite3.connect(":memory:")
        data = {"url": "https://example.com/landing-page.html"}
        with unittest.mock.patch.object(
                resolved_cache, "_media_probe",
                return_value=(False, None)):
            self.assertFalse(resolved_cache.record_resolved_cache(
                conn, 7, data, source="jit"))
        lines = self.lines()
        self.assertEqual(len(lines), 1)
        self.assertEqual(lines[0]["kind"], "cache-refusal")
        self.assertEqual(lines[0]["host"], "example.com")
        self.assertEqual(lines[0]["discovered_by"], "recheck")
        self.assertEqual(lines[0]["verified_by"], "verify_media_bytes")
        # The pool channel reads "sweep".
        with unittest.mock.patch.object(
                resolved_cache, "_media_probe",
                return_value=(False, None)):
            self.assertFalse(resolved_cache.record_resolved_cache(
                conn, 8, data, source="pool"))
        self.assertEqual(self.lines()[1]["discovered_by"], "sweep")

    def test_derived_expiry_logs_once_and_explicit_does_not(self):
        conn = sqlite3.connect(":memory:")
        url = _r2_url(28800)
        with unittest.mock.patch.object(
                resolved_cache, "_media_probe",
                return_value=(True, None)):
            out = resolved_cache.record_resolved_cache(
                conn, 9, {"url": url}, source="jit", now=float(NOW))
        self.assertIsInstance(out, dict)
        self.assertIsNotNone(out["expires_at"])
        lines = self.lines()
        self.assertEqual(len(lines), 1)
        self.assertEqual(lines[0]["kind"], "expiry-banked")
        self.assertEqual(lines[0]["host"],
                         "abc123.r2.cloudflarestorage.com")
        self.assertEqual(lines[0]["new"], str(out["expires_at"]))
        self.assertEqual(lines[0]["verified_by"],
                         "expiry_bank.derive_expiry")
        # An explicit resolver expiry is the resolver's own data, not
        # a derivation: banking it writes no expiry-banked line.
        with unittest.mock.patch.object(
                resolved_cache, "_media_probe",
                return_value=(True, None)):
            resolved_cache.record_resolved_cache(
                conn, 10, {"url": "https://pixeldrain.com/api/file/abc",
                           "expires_at": float(NOW + 3600)},
                source="jit", now=float(NOW))
        self.assertEqual(len(self.lines()), 1)


class ResolveHookMemoTests(unittest.TestCase):
    """The merge-rule hook in resolve_live_source checks a site at
    most once per hour per process."""

    def setUp(self):
        import fast_fetch
        self.ff = fast_fetch
        self._memo = dict(fast_fetch._site_host_reconciled)
        fast_fetch._site_host_reconciled.clear()
        self.calls = []
        self._log_patch = unittest.mock.patch.object(
            fast_fetch, "self_healing_log")
        self._queue_patch = unittest.mock.patch.object(
            fast_fetch, "recheck_queue")
        self._log = self._log_patch.start()
        self._queue = self._queue_patch.start()
        self._log.reconcile_site_host.side_effect = (
            lambda site, **kw: self.calls.append(site))
        self._queue.queue_path_for_db.side_effect = (
            lambda p: Path("/tmp/q.db"))
        self.addCleanup(self._log_patch.stop)
        self.addCleanup(self._queue_patch.stop)
        self.addCleanup(
            lambda: fast_fetch._site_host_reconciled.update(self._memo))

    def test_second_press_within_the_hour_is_memoised(self):
        self.ff._maybe_reconcile_site_host("vegamovies")
        self.ff._maybe_reconcile_site_host("vegamovies")
        self.assertEqual(self.calls, ["vegamovies"])
        self.ff._maybe_reconcile_site_host("hindilinks4u")
        self.assertEqual(self.calls, ["vegamovies", "hindilinks4u"])

    def test_empty_site_is_never_checked(self):
        self.ff._maybe_reconcile_site_host("")
        self.ff._maybe_reconcile_site_host(None)
        self.assertEqual(self.calls, [])


class WeeklyJobTests(_TmpLogTest):
    def test_dry_run_changes_nothing(self):
        import self_heal_weekly as weekly
        obs_path = Path(self.tmp.name) / "obs.json"
        obs_path.write_text(json.dumps({
            "version": 1, "observations": {
                "a.old|a.new": {
                    "count": 3, "first_seen": _iso(NOW - 100 * 86400),
                    "last_seen": _iso(NOW - 100 * 86400),
                    "sample_urls": [], "applied_at": None}},
            "html_observations": {}}), encoding="utf-8")
        before = obs_path.read_text(encoding="utf-8")
        out = io.StringIO()
        with unittest.mock.patch.object(sys, "stdout", out):
            rc = weekly.main(["--observations", str(obs_path),
                              "--log", str(self.log_path), "--dry-run"])
        self.assertEqual(rc, 0)
        summary = json.loads(out.getvalue())
        self.assertEqual(summary["job"], "self-heal-weekly")
        self.assertEqual(summary["would_prune"], 1)
        self.assertEqual(obs_path.read_text(encoding="utf-8"), before)

    def test_apply_prunes_and_rotates(self):
        import self_heal_weekly as weekly
        obs_path = Path(self.tmp.name) / "obs.json"
        obs_path.write_text(json.dumps({
            "version": 1, "observations": {
                "a.old|a.new": {
                    "count": 3, "first_seen": _iso(NOW - 100 * 86400),
                    "last_seen": _iso(NOW - 100 * 86400),
                    "sample_urls": [], "applied_at": None}},
            "html_observations": {}}), encoding="utf-8")
        self.log_path.write_text('{"kind": "x"}\n', encoding="utf-8")
        old_mtime = time.time() - 8 * 86400
        os.utime(self.log_path, (old_mtime, old_mtime))
        out = io.StringIO()
        with unittest.mock.patch.object(sys, "stdout", out):
            rc = weekly.main(["--observations", str(obs_path),
                              "--log", str(self.log_path)])
        self.assertEqual(rc, 0)
        summary = json.loads(out.getvalue())
        self.assertEqual(summary["observations_pruned"], 1)
        self.assertIsNotNone(summary["log_rotated"])
        self.assertFalse(self.log_path.exists())


class NoSecondCopyTests(unittest.TestCase):
    """Design §4.4: the script's last-known-good file stays
    script-local. No backend module may read it."""

    def test_no_backend_module_references_the_script_state(self):
        offenders = []
        for path in BASE_DIR.glob("*.py"):
            if path.name == "test_self_healing_log.py":
                continue
            if "movie-downloader-state" in path.read_text(
                    encoding="utf-8", errors="ignore"):
                offenders.append(path.name)
        self.assertFalse(
            offenders,
            "backend code reads the script-local state file: %s"
            % offenders)


if __name__ == "__main__":
    unittest.main(verbosity=2)
