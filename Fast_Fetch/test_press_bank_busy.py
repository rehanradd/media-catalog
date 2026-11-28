#!/usr/bin/env python3
"""Offline tests for the press-bank busy fix (sweep-load hardening).

The incident class: under sweep load, presses failed with
``sqlite3.OperationalError: database is locked`` while banking their
writes. The sweep writer holds ``BEGIN IMMEDIATE`` on
``download_sources`` in 100-row batches (``fast_probe.py`` VerdictWriter,
30 s busy timeout, 5 lock retries) — but the press path banks the SAME
table with NO lock retry at all: ``_bank_hubcloud_siblings`` in
``fast_fetch.py`` and the two ``record_resolved_cache`` banks in
``viewer.py`` (stream + download routes). SQLite's busy-wait has no
fairness, so under continuous sweep writes a press can starve past
30 s. The fix mirrors the ``_init_db_with_retry`` pattern that fixed
the same class for ``init_db`` (gate 2026-11-20): a bounded retry (3
attempts, 0.5 s + 1.0 s backoff) that retries ONLY "database is
locked", each attempt on a FRESH connection. The 30 s busy_timeout is
the live value and is NOT weakened.

The companion fix is in ``fast_probe.py``: the sweep writer connection
now gets ``db_pragmas.apply_serve_pragmas`` (64 MiB cache,
wal_autocheckpoint 1000) instead of SQLite's 2 MiB default cache, so
each batch holds the lock for less time. That side is pinned by
``test_scale_pragmas.py`` (pragma values) and the call site is pinned
below by source check.

Run from the repo root (the gate) or from Fast_Fetch/:
    python3 -S Fast_Fetch/test_press_bank_busy.py
Stdlib only, temp-file SQLite only, no network, no live DB.
"""
from __future__ import annotations

import sqlite3
import tempfile
import threading
import time
import unittest
from contextlib import contextmanager
from pathlib import Path
from unittest import mock

import fast_fetch


class BankRetryTests(unittest.TestCase):
    """The bounded retry helper itself."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db = Path(self.tmp.name) / "test.db"

    def _hold_lock(self, hold_s: float):
        """Hold the write lock in a thread; return (locked, released)."""
        locked = threading.Event()
        released = threading.Event()

        def hold_and_release():
            conn = sqlite3.connect(self.db)
            try:
                conn.execute("BEGIN IMMEDIATE")
                locked.set()
                time.sleep(hold_s)
                conn.execute("COMMIT")
            finally:
                conn.close()
                released.set()

        holder = threading.Thread(target=hold_and_release, daemon=True)
        holder.start()
        self.assertTrue(locked.wait(timeout=10),
                        "holder thread never took the write lock")
        return holder, released

    def test_locked_bank_retries_instead_of_failing(self):
        # Hold the lock ~1.2 s while the bank runs: attempt 1 fails fast
        # (short connect timeout), the 0.5 s + 1.0 s backoff absorbs the
        # hold, attempt 3 succeeds after the release. Fails on the old
        # code: the first attempt raises at once.
        holder, released = self._hold_lock(1.2)
        calls = []

        def bank_once():
            calls.append(1)
            conn = sqlite3.connect(self.db, timeout=0.1)
            try:
                conn.execute("CREATE TABLE IF NOT EXISTS t (id INTEGER)")
                conn.execute("INSERT INTO t VALUES (1)")
                conn.commit()
            finally:
                conn.close()

        try:
            fast_fetch._bank_with_locked_retry(bank_once)
        finally:
            self.assertTrue(released.wait(timeout=10),
                            "holder thread never released the lock")
            holder.join(timeout=10)
        self.assertEqual(len(calls), 3,
                         "the bank must have retried twice before succeeding")
        conn = sqlite3.connect(self.db)
        try:
            count = conn.execute("SELECT COUNT(*) FROM t").fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(count, 1, "the retried bank must have written")

    def test_non_lock_error_raises_at_once(self):
        # A failure that is NOT "database is locked" must raise at once
        # with no retry: the spy proves the bank ran exactly once.
        calls = []

        def bank_once():
            calls.append(1)
            raise sqlite3.OperationalError("no such table: t")

        with self.assertRaises(sqlite3.OperationalError):
            fast_fetch._bank_with_locked_retry(bank_once)
        self.assertEqual(len(calls), 1,
                         "a non-lock error must not be retried")

    def test_retry_exhausted_raises_last_lock_error(self):
        # Hold the lock longer than the whole retry window (0.5 + 1.0 s
        # of backoff): all 3 attempts fail and the last lock error is
        # raised, not swallowed.
        holder, released = self._hold_lock(3.0)
        calls = []

        def bank_once():
            calls.append(1)
            conn = sqlite3.connect(self.db, timeout=0.1)
            try:
                conn.execute("CREATE TABLE IF NOT EXISTS t (id INTEGER)")
                conn.execute("INSERT INTO t VALUES (1)")
                conn.commit()
            finally:
                conn.close()

        try:
            with self.assertRaises(sqlite3.OperationalError) as ctx:
                fast_fetch._bank_with_locked_retry(bank_once)
        finally:
            self.assertTrue(released.wait(timeout=10),
                            "holder thread never released the lock")
            holder.join(timeout=10)
        self.assertIn("locked", str(ctx.exception).lower())
        self.assertEqual(len(calls), 3,
                         "all three attempts must have run before giving up")


class SiblingBankTests(unittest.TestCase):
    """The real _bank_hubcloud_siblings path under contention."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db = Path(self.tmp.name) / "test.db"
        conn = sqlite3.connect(self.db)
        try:
            conn.execute(
                "CREATE TABLE download_sources ("
                "id INTEGER PRIMARY KEY, release_id INTEGER, "
                "service TEXT, final_url TEXT, is_temporary INTEGER, "
                "status TEXT, last_checked_at TEXT, updated_at TEXT, "
                "last_failed_at TEXT, failure_class TEXT)")
            conn.executemany(
                "INSERT INTO download_sources "
                "(id, release_id, service, status) VALUES (?, ?, ?, ?)",
                [(1, 90212, "fsl_r2", "active"),
                 (2, 90212, "fsl_r2", "unknown"),
                 (3, 90212, "pixeldrain", "unknown"),
                 (4, 90212, "hubcloud_10g", "unknown")])
            conn.commit()
        finally:
            conn.close()

    @contextmanager
    def _scratch_get_db(self):
        # get_db() pointed at the scratch file with a SHORT timeout so
        # the lock surfaces fast instead of waiting out the live 30 s.
        conn = sqlite3.connect(self.db, timeout=0.1)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def test_siblings_bank_after_lock_release(self):
        # The pressed row is id 1; siblings 2 (fsl_r2) and 3 (pixeldrain)
        # must be banked, row 4 (hubcloud_10g, not a sibling service)
        # untouched — even though the first two attempts hit the sweep
        # writer's lock. Fails on the old code: attempt 1 raises and
        # the function returns 0 with nothing banked.
        locked = threading.Event()
        released = threading.Event()

        def hold_and_release():
            conn = sqlite3.connect(self.db)
            try:
                conn.execute("BEGIN IMMEDIATE")
                locked.set()
                time.sleep(1.2)
                conn.execute("COMMIT")
            finally:
                conn.close()
                released.set()

        holder = threading.Thread(target=hold_and_release, daemon=True)
        holder.start()
        self.assertTrue(locked.wait(timeout=10),
                        "holder thread never took the write lock")
        candidates = [
            {"service": "fsl_r2", "final_url": "https://example.invalid/fsl",
             "is_temporary": 1, "expires_at": 1234567890},
            {"service": "pixeldrain",
             "final_url": "https://example.invalid/pixel",
             "is_temporary": 0, "expires_at": 1234567890},
        ]
        try:
            with mock.patch.object(fast_fetch, "get_db",
                                   self._scratch_get_db), \
                    mock.patch.object(fast_fetch, "record_link_expiry"):
                banked = fast_fetch._bank_hubcloud_siblings(
                    release_id=90212, pressed_id=1, candidates=candidates)
        finally:
            self.assertTrue(released.wait(timeout=10),
                            "holder thread never released the lock")
            holder.join(timeout=10)
        self.assertEqual(banked, 2, "both sibling rows must be banked")
        conn = sqlite3.connect(self.db)
        try:
            rows = {r[0]: (r[1], r[2]) for r in conn.execute(
                "SELECT id, final_url, status FROM download_sources")}
        finally:
            conn.close()
        self.assertEqual(rows[2], ("https://example.invalid/fsl", "active"))
        self.assertEqual(rows[3],
                         ("https://example.invalid/pixel", "active"))
        self.assertIsNone(rows[4][0], "non-sibling rows must not be banked")
        self.assertIsNone(rows[1][0], "the pressed row must not be banked")


class ReaderWriterTests(unittest.TestCase):
    """WAL readers must not block on the sweep writer (diagnosis pin).

    The posts 503 under sweep load is NOT a lock — it is the 10 s query
    deadline (viewer.py SEARCH_DEADLINE_SECONDS): a 2–5 s baseline query
    crosses 10 s when 48 sweep workers + the writer share 1 vCPU. WAL
    readers never block on writers, so a reader must never see
    "database is locked" or "interrupted" from the lock. If someone
    "fixes" the 503 by raising busy_timeout, this test still passes but
    the deadline stays the real ceiling — the fix is shorter writer lock
    holds (serve pragmas on the sweep writer), not a longer timeout.
    """

    def test_readers_survive_writer_batches(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        db = Path(tmp.name) / "test.db"
        conn = sqlite3.connect(db)
        try:
            conn.execute("PRAGMA journal_mode = WAL")
            conn.execute("CREATE TABLE posts (id INTEGER PRIMARY KEY)")
            conn.execute("CREATE TABLE download_sources (id INTEGER PRIMARY KEY)")
            conn.executemany("INSERT INTO posts VALUES (?)",
                             [(i,) for i in range(200)])
            conn.commit()
        finally:
            conn.close()

        errors = []
        stop = threading.Event()

        def reader():
            c = sqlite3.connect(db, timeout=30.0)
            try:
                c.execute("PRAGMA journal_mode = WAL")
                while not stop.is_set():
                    c.execute("SELECT COUNT(*) FROM posts").fetchone()
            except Exception as exc:  # noqa: BLE001
                errors.append(exc)
            finally:
                c.close()

        def writer():
            c = sqlite3.connect(db, timeout=30.0)
            try:
                c.execute("PRAGMA journal_mode = WAL")
                for batch in range(5):
                    c.execute("BEGIN IMMEDIATE")
                    c.executemany(
                        "INSERT INTO download_sources VALUES (?)",
                        [(batch * 100 + i,) for i in range(100)])
                    c.execute("COMMIT")
                    time.sleep(0.05)
            finally:
                c.close()

        readers = [threading.Thread(target=reader, daemon=True)
                   for _ in range(3)]
        for t in readers:
            t.start()
        writer_thread = threading.Thread(target=writer, daemon=True)
        writer_thread.start()
        writer_thread.join(timeout=30)
        stop.set()
        for t in readers:
            t.join(timeout=10)
        self.assertEqual(errors, [],
                         "WAL readers must never hit a lock error: %r" % errors)


class WiringTests(unittest.TestCase):
    """The fix is wired where the audit said it is (source pin)."""

    def test_sweep_writer_gets_serve_pragmas(self):
        # fast_probe.py must call apply_serve_pragmas on the writer
        # connection right after the connect — the 2 MiB default cache
        # is the lock-hold aggravator. If the call is removed, batches
        # hold the lock longer again.
        src = (Path(__file__).parent / "fast_probe.py").read_text(
            encoding="utf-8")
        self.assertIn("db_pragmas.apply_serve_pragmas(writer_conn)", src,
                      "sweep writer connection must get the serve pragmas")

    def test_both_viewer_bank_sites_use_the_retry(self):
        # viewer.py stream + download banks must both go through the
        # retry helper, each on a fresh connection per attempt.
        src = (Path(__file__).parent / "viewer.py").read_text(
            encoding="utf-8")
        self.assertEqual(
            src.count("_bank_with_locked_retry(_do_bank)"), 2,
            "both press-bank sites (stream + download) must use the retry")
        self.assertIn("from fast_fetch import _bank_with_locked_retry", src)


if __name__ == "__main__":
    unittest.main(verbosity=2)
