#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline tests for the out-of-process sweep runner — `link_state_runner.py`
and the `ids` mode it drives in `link_state.propagate`.

No network is used or allowed: `link_health.probe`/`probe_many` are patched
to explode, every verdict comes from a fake prober built out of real
`link_health.ProbeResult` objects, and the runner's load gate + systemd
checks are injected fakes. The fixture ledger is created by
`fast_fetch.init_db`, so these tests run against the production schema.
"""

from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
import time
import unittest
import unittest.mock
from pathlib import Path

os.environ.setdefault("RADD_SKIP_LIVE_ENRICH", "1")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import db_pragmas           # noqa: E402
import fast_fetch            # noqa: E402
import link_health           # noqa: E402
import link_state            # noqa: E402
import link_state_runner     # noqa: E402


def live(url, *, status=200) -> link_health.ProbeResult:
    return link_health.ProbeResult(
        url=url, ok=True, http_status=status, protocol="progressive",
        content_type="video/mp4", size_bytes=2_000_000_000,
        accepts_ranges=True, resumable=True, latency_ms=120,
        download_speed_mbps=20.0, streamable=True, downloadable=True,
        checked_at=1_730_000_000.0)


def failed(url, *, status=0, error="conn_error") -> link_health.ProbeResult:
    return link_health.ProbeResult(url=url, ok=False, http_status=status,
                                   protocol="unusable", error=error,
                                   checked_at=1_730_000_000.0)


class FakeProber:
    """Deterministic prober: answers by URL, records the ids it was asked about."""

    def __init__(self, answers: dict):
        self.answers = dict(answers)
        self.calls: list[list[int]] = []

    def __call__(self, rows) -> list:
        self.calls.append([int(row["id"]) for row in rows])
        return [self.answers.get(row.get("final_url") or row.get("source_url"))
                for row in rows]


class RunnerTestCase(unittest.TestCase):
    LIVE1 = "https://cdn.example.com/a.mp4"
    LIVE2 = "https://cdn.example.com/b.mp4"
    DEAD = "https://gone.example.com/c.mp4"
    MAGNET = "magnet:?xt=urn:btih:deadbeef"

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp.name) / "catalog.db"
        self._patch = unittest.mock.patch.object(fast_fetch, "DB_PATH", self.db_path)
        self._patch.start()
        fast_fetch.init_db(self.db_path)
        self._no_net = [
            unittest.mock.patch.object(link_health, "probe",
                                       side_effect=AssertionError("network use in a test")),
            unittest.mock.patch.object(link_health, "probe_many",
                                       side_effect=AssertionError("network use in a test")),
        ]
        for patcher in self._no_net:
            patcher.start()
        self.conn = link_state.connect(self.db_path)
        link_state.ensure_schema(self.conn)
        self.ids = {}
        for url in (self.LIVE1, self.LIVE2, self.DEAD, self.MAGNET):
            self.conn.execute(
                "INSERT INTO download_sources (release_id, source_name, service,"
                " source_url, final_url, playback_type, is_temporary, status)"
                " VALUES (1, 'test', 'cdn', ?, ?, 'direct', 0, 'active')",
                (url, url))
            self.ids[url] = self.conn.execute(
                "SELECT last_insert_rowid()").fetchone()[0]
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS resolved_cache ("
            " link_id INTEGER PRIMARY KEY, url TEXT, resolved_at REAL,"
            " expires_at REAL, reverify_after REAL)")
        self.conn.commit()

    def tearDown(self):
        self.conn.close()
        for patcher in self._no_net:
            patcher.stop()
        self._patch.stop()
        self.tmp.cleanup()

    def prober(self) -> FakeProber:
        return FakeProber({
            self.LIVE1: live(self.LIVE1),
            self.LIVE2: live(self.LIVE2),
            self.DEAD: failed(self.DEAD, status=404, error="http_404"),
        })

    def press(self, url: str, *, resolved_at: float) -> None:
        """Record a press of a row in resolved_cache (the demand signal)."""
        self.conn.execute(
            "INSERT OR REPLACE INTO resolved_cache (link_id, url, resolved_at)"
            " VALUES (?, ?, ?)", (self.ids[url], url, resolved_at))
        self.conn.commit()

    def row_status(self, url: str) -> str:
        row = self.conn.execute(
            "SELECT status FROM download_sources WHERE id = ?",
            (self.ids[url],)).fetchone()
        return row[0]


class IdsModeTests(RunnerTestCase):
    def test_walks_exactly_the_given_ids_in_order(self):
        prober = self.prober()
        summary = link_state.propagate(
            self.conn, scope="test-ids", ids=[self.ids[self.DEAD], self.ids[self.LIVE1]],
            prober=prober, batch_size=10)
        self.assertEqual(summary["state"], "complete")
        self.assertEqual(summary["rows_checked"], 2)
        # The list is deduped and sorted (the docstring's contract), so the
        # prober sees ascending ids regardless of the caller's order.
        self.assertEqual(prober.calls, [[self.ids[self.LIVE1], self.ids[self.DEAD]]])
        self.assertEqual(self.row_status(self.DEAD), "dead")
        self.assertEqual(self.row_status(self.LIVE1), "active")
        # The row NOT in the list was never touched.
        self.assertIsNone(self.conn.execute(
            "SELECT checked_at FROM download_sources WHERE id = ?",
            (self.ids[self.LIVE2],)).fetchone()[0])

    def test_ids_are_deduped_sorted_and_ignore_the_stored_cursor(self):
        # Park the scope cursor past every row: an id-ordered walk would
        # find nothing, but the explicit list IS the work.
        self.conn.execute(
            "INSERT INTO link_state_cursor (scope, last_id, state)"
            " VALUES ('test-ids', 999999, 'running')")
        self.conn.commit()
        prober = self.prober()
        summary = link_state.propagate(
            self.conn, scope="test-ids",
            ids=[self.ids[self.LIVE2], self.ids[self.LIVE1], self.ids[self.LIVE1]],
            prober=prober, batch_size=10)
        self.assertEqual(summary["state"], "complete")
        self.assertEqual(summary["rows_checked"], 2)
        self.assertEqual(prober.calls[0], [self.ids[self.LIVE1], self.ids[self.LIVE2]])

    def test_unprobeable_ids_advance_without_counting_a_batch(self):
        prober = self.prober()
        summary = link_state.propagate(
            self.conn, scope="test-ids",
            ids=[self.ids[self.MAGNET], self.ids[self.LIVE1]],
            prober=prober, batch_size=10)
        self.assertEqual(summary["state"], "complete")
        self.assertEqual(summary["rows_checked"], 1)
        self.assertEqual(summary["batches"], 1)
        self.assertEqual(prober.calls, [[self.ids[self.LIVE1]]])

    def test_empty_list_is_complete_with_zero_rows(self):
        summary = link_state.propagate(
            self.conn, scope="test-ids", ids=[], prober=self.prober())
        self.assertEqual(summary["state"], "complete")
        self.assertEqual(summary["rows_checked"], 0)


class DemandIdsTests(RunnerTestCase):
    def test_pressed_rows_most_recent_first_skipping_fresh_and_missing(self):
        now = time.time()
        self.press(self.LIVE1, resolved_at=now - 100)
        self.press(self.LIVE2, resolved_at=now - 10)
        self.press(self.DEAD, resolved_at=now - 50)
        # LIVE1 already carries a fresh verdict: it is not demand.
        self.conn.execute(
            "UPDATE download_sources SET checked_at = ? WHERE id = ?",
            (now, self.ids[self.LIVE1]))
        # A pressed row whose ledger row is gone: skipped, never probed.
        self.conn.execute(
            "INSERT INTO resolved_cache (link_id, url, resolved_at)"
            " VALUES (424242, 'https://gone.example.com/x.mp4', ?)", (now - 5,))
        self.conn.commit()
        got = link_state_runner.demand_ids(self.conn, limit=10)
        self.assertEqual(got, [self.ids[self.LIVE2], self.ids[self.DEAD]])

    def test_no_resolved_cache_table_is_empty_not_an_error(self):
        self.conn.execute("DROP TABLE resolved_cache")
        self.conn.commit()
        self.assertEqual(link_state_runner.demand_ids(self.conn, limit=10), [])


class RunOnceTests(RunnerTestCase):
    def _quiet(self, ceiling, max_wait):  # noqa: ANN001 - injected shape
        return False  # load is fine, never waits

    def _no_neighbour(self, name):  # noqa: ANN001 - injected shape
        return False

    def _fake_propagate(self, calls):
        def fake(conn, **kwargs):
            calls.append(kwargs)
            return {"rows_checked": 1, "rows_updated": 1, "by_status": {},
                    "last_id": 0, "state": "complete"}
        return fake

    def test_demand_phase_runs_before_background_with_the_right_scopes(self):
        now = time.time()
        self.press(self.LIVE2, resolved_at=now - 10)
        self.press(self.DEAD, resolved_at=now - 20)
        calls = []
        with unittest.mock.patch.object(link_state, "propagate",
                                        self._fake_propagate(calls)):
            summary = link_state_runner.run_once(
                db_path=self.db_path, max_rows=5, demand_limit=10,
                wait_quiet=self._quiet, check_service=self._no_neighbour,
                mem_mb=9000.0)
        self.assertIsNone(summary["yielded"])
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0]["scope"], link_state_runner.DEMAND_SCOPE)
        self.assertEqual(calls[0]["ids"], [self.ids[self.LIVE2], self.ids[self.DEAD]])
        self.assertEqual(calls[1]["scope"], link_state_runner.DEFAULT_SCOPE)
        self.assertEqual(calls[1]["max_rows"], 5)
        self.assertNotIn("ids", calls[1])

    def test_no_demand_rows_still_runs_the_background_phase(self):
        calls = []
        with unittest.mock.patch.object(link_state, "propagate",
                                        self._fake_propagate(calls)):
            summary = link_state_runner.run_once(
                db_path=self.db_path, max_rows=5,
                wait_quiet=self._quiet, check_service=self._no_neighbour,
                mem_mb=9000.0)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0]["scope"], link_state_runner.DEFAULT_SCOPE)
        self.assertEqual(summary["demand"]["state"], "complete")
        self.assertEqual(summary["demand"]["rows_checked"], 0)

    def test_sweep_connections_carry_the_serve_pragmas(self):
        # Search-speed (2026-11-17): the sweep's explicit-path branch
        # used to open a raw sqlite3.connect and silently skip every
        # serve pragma, so the production sweep ran on sqlite's 2 MiB
        # default cache. The connections propagate() receives must read
        # back the helper's values — read inside the fake, because the
        # runner closes its connections before the test can see them.
        seen = []

        def recording(conn, **kwargs):
            seen.append(db_pragmas.read_pragmas(conn))
            return {"rows_checked": 1, "rows_updated": 1, "by_status": {},
                    "last_id": 0, "state": "complete"}

        with unittest.mock.patch.object(link_state, "propagate",
                                        side_effect=recording):
            link_state_runner.run_once(
                db_path=self.db_path, max_rows=5,
                wait_quiet=self._quiet, check_service=self._no_neighbour,
                mem_mb=9000.0)
        self.assertTrue(seen, "propagate must run at least once")
        for got in seen:
            self.assertEqual(got["cache_size"], -65536)
            self.assertEqual(got["mmap_size"], 268435456)
            self.assertEqual(got["journal_size_limit"], 268435456)
            self.assertEqual(got["busy_timeout"], 30000)
            self.assertEqual(got["journal_mode"], "wal")

    def test_yields_when_the_prefetch_sweep_is_running(self):
        calls = []
        with unittest.mock.patch.object(link_state, "propagate",
                                        self._fake_propagate(calls)):
            summary = link_state_runner.run_once(
                db_path=self.db_path,
                wait_quiet=self._quiet, mem_mb=9000.0,
                check_service=lambda name: name == "radd-prefetch.service")
        self.assertEqual(summary["yielded"], "radd-prefetch.service is running")
        self.assertEqual(calls, [])

    def test_yields_when_the_load_never_quietens(self):
        calls = []
        with unittest.mock.patch.object(link_state, "propagate",
                                        self._fake_propagate(calls)):
            summary = link_state_runner.run_once(
                db_path=self.db_path, mem_mb=9000.0,
                wait_quiet=lambda ceiling, max_wait: True,
                check_service=self._no_neighbour)
        self.assertIn("load stayed above", summary["yielded"])
        self.assertEqual(calls, [])

    def test_yields_below_the_memory_floor(self):
        calls = []
        with unittest.mock.patch.object(link_state, "propagate",
                                        self._fake_propagate(calls)):
            summary = link_state_runner.run_once(
                db_path=self.db_path, mem_mb=100.0,
                wait_quiet=self._quiet, check_service=self._no_neighbour)
        self.assertIn("memory floor", summary["yielded"])
        self.assertEqual(calls, [])

    def test_sweep_prunes_resolved_cache_at_the_end(self):
        # DB checkup fix #8: orphans + expired rows leave at the end of
        # every sweep, so press-banking never serves a dead link_id. The
        # fixture resolved_cache table is minimal (link_id, url,
        # resolved_at, expires_at, reverify_after) — the prune only reads
        # link_id and expires_at, so it works on it as-is.
        now = time.time()
        live_id = self.ids[self.LIVE1]
        self.conn.execute(
            "INSERT INTO resolved_cache (link_id, url, resolved_at,"
            " expires_at, reverify_after) VALUES (?, ?, ?, ?, ?)",
            (live_id, self.LIVE1, now - 60, now - 10, now + 3600))
        self.conn.execute(
            "INSERT INTO resolved_cache (link_id, url, resolved_at,"
            " expires_at, reverify_after) VALUES (?, ?, ?, ?, ?)",
            (424242, "https://gone.example.com/d.mp4",
             now - 60, now + 3600, now + 3600))
        self.conn.commit()
        calls = []
        with unittest.mock.patch.object(link_state, "propagate",
                                        self._fake_propagate(calls)):
            summary = link_state_runner.run_once(
                db_path=self.db_path, max_rows=5,
                wait_quiet=self._quiet, check_service=self._no_neighbour,
                mem_mb=9000.0)
        self.assertEqual(summary["cache_prune"],
                         {"orphans_deleted": 1, "expired_deleted": 1})
        left = [r[0] for r in self.conn.execute(
            "SELECT link_id FROM resolved_cache").fetchall()]
        self.assertEqual(left, [])

    def test_dry_run_skips_the_cache_prune(self):
        now = time.time()
        self.conn.execute(
            "INSERT INTO resolved_cache (link_id, url, resolved_at,"
            " expires_at, reverify_after) VALUES (?, ?, ?, ?, ?)",
            (424242, "https://gone.example.com/d.mp4",
             now - 60, now + 3600, now + 3600))
        self.conn.commit()
        calls = []
        with unittest.mock.patch.object(link_state, "propagate",
                                        self._fake_propagate(calls)):
            summary = link_state_runner.run_once(
                db_path=self.db_path, max_rows=5, dry_run=True,
                wait_quiet=self._quiet, check_service=self._no_neighbour,
                mem_mb=9000.0)
        self.assertIsNone(summary["cache_prune"])
        left = [r[0] for r in self.conn.execute(
            "SELECT link_id FROM resolved_cache").fetchall()]
        self.assertEqual(left, [424242])


class CliTests(RunnerTestCase):
    def test_once_is_required_and_args_reach_run_once(self):
        with self.assertRaises(SystemExit):
            link_state_runner.main([])
        with unittest.mock.patch.object(link_state_runner, "run_once",
                                        return_value={}) as run:
            self.assertEqual(link_state_runner.main(
                ["--once", "--db", str(self.db_path), "--max-rows", "7",
                 "--demand-limit", "3", "--dry-run"]), 0)
        kwargs = run.call_args.kwargs
        self.assertEqual(kwargs["max_rows"], 7)
        self.assertEqual(kwargs["demand_limit"], 3)
        self.assertTrue(kwargs["dry_run"])
        self.assertEqual(kwargs["db_path"], str(self.db_path))


class ServiceActiveTests(unittest.TestCase):
    """service_active() reads the state word, not the exit code.

    Regression: ``systemctl is-active`` exits 3 for ``activating`` —
    the same code as ``inactive`` — so the old ``returncode == 0``
    check let a neighbour that was still starting up slip past the
    guard (same hole as backfill_durations, fixed on main 5199f81f).
    """

    def _fake_run(self, stdout, returncode=3):
        result = unittest.mock.Mock()
        result.stdout = stdout
        result.returncode = returncode
        return unittest.mock.patch.object(
            link_state_runner.subprocess, "run",
            return_value=result)

    def test_active_is_active(self):
        with self._fake_run("active\n", returncode=0):
            self.assertTrue(
                link_state_runner.service_active("radd-prefetch.service"))

    def test_activating_is_active(self):
        with self._fake_run("activating\n", returncode=3):
            self.assertTrue(
                link_state_runner.service_active("radd-prefetch.service"))

    def test_activating_case_and_whitespace(self):
        with self._fake_run("  Activating \n", returncode=3):
            self.assertTrue(
                link_state_runner.service_active("radd-prefetch.service"))

    def test_reloading_and_deactivating_are_active(self):
        for state in ("reloading", "deactivating"):
            with self._fake_run(state + "\n", returncode=3):
                self.assertTrue(
                    link_state_runner.service_active(
                        "radd-prefetch.service"))

    def test_inactive_failed_unknown_are_inactive(self):
        for state in ("inactive", "failed", "unknown"):
            with self._fake_run(state + "\n", returncode=3):
                self.assertFalse(
                    link_state_runner.service_active(
                        "radd-prefetch.service"))

    def test_empty_stdout_falls_back_to_exit_code(self):
        with self._fake_run("", returncode=0):
            self.assertTrue(
                link_state_runner.service_active("radd-prefetch.service"))
        with self._fake_run("", returncode=3):
            self.assertFalse(
                link_state_runner.service_active("radd-prefetch.service"))

    def test_missing_systemctl_is_inactive(self):
        with unittest.mock.patch.object(
                link_state_runner.subprocess, "run",
                side_effect=OSError("no systemctl")):
            self.assertFalse(
                link_state_runner.service_active("radd-prefetch.service"))


if __name__ == "__main__":
    unittest.main()
