#!/usr/bin/env python3
"""Offline tests for the init_db busy fix: once-per-process hot path +
bounded busy retry.

The incident (gate 2026-11-20, live press probes 08:03:01 / 08:04:48 /
08:12:15 PKT): ``sqlite3.OperationalError: database is locked`` inside
``fast_fetch._init_db`` (the domains_config INSERT) during live resolves.
``resolve_live_source`` called ``init_db()`` on EVERY press, and
``init_db()`` ran the full DDL + defaults INSERTs + migrations + the
post_sources backfill check on every call — a fresh connection, a full
``executescript`` and several full-table scans, all competing for the
SQLite write lock with the sweep (48 workers), the backfill and other
resolve threads. The 30 s busy_timeout is the live value and is NOT
weakened. The fix is twofold, and the split between the two functions
is deliberate:

1. ``init_db_once()`` — the hot-path form. Runs the real init once
   per database file in this process (keyed by resolved path + device
   + inode, so a gate swap that renames a new file over the path
   re-initialises) and is a no-op afterwards. Used by the per-press /
   per-save / per-redirect callers (``resolve_live_source``,
   ``_save_scraped_data``, ``set_domain``).
2. ``init_db()`` — unchanged repair semantics. It still runs the whole
   body on every call, because callers rely on that: dropping a table,
   adding a column, or filing a post without its post_sources row and
   then calling ``init_db()`` again must heal it (pinned by
   ``test_database.py``). A busy database is retried, bounded (3
   attempts, short backoff) instead of failing the call; any other
   OperationalError still raises at once.

Run from the repo root (the gate) or from Fast_Fetch/:
    python3 -S Fast_Fetch/test_init_db_busy.py
Stdlib only, temp-file SQLite only, no network, no live DB.
"""
from __future__ import annotations

import os
import sqlite3
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

import db_pragmas
import fast_fetch


class InitDbOnceTests(unittest.TestCase):
    """The once-per-process guard on the hot path."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db = Path(self.tmp.name) / "test.db"

    def _spy(self):
        """A spy around the real init_db that counts calls."""
        calls = []
        real = fast_fetch.init_db

        def spy(db_path=None):
            calls.append(db_path)
            return real(db_path)

        return calls, spy

    def _tables(self, path):
        conn = sqlite3.connect(path)
        try:
            return {r[0] for r in conn.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            ).fetchall()}
        finally:
            conn.close()

    def test_second_init_once_same_path_is_noop(self):
        # The hot-path fix: the second init_db_once() on the same file
        # must not run the DDL again. Fails while resolve_live_source
        # pays the full init on every press (no such guard exists).
        calls, spy = self._spy()
        with mock.patch.object(fast_fetch, "init_db", spy):
            fast_fetch.init_db_once(self.db)
            fast_fetch.init_db_once(self.db)
        self.assertEqual(len(calls), 1,
                         "init_db_once ran the full DDL twice on one file")
        self.assertIn("domains_config", self._tables(self.db))
        conn = sqlite3.connect(self.db)
        try:
            count = conn.execute(
                "SELECT COUNT(*) FROM domains_config").fetchone()[0]
        finally:
            conn.close()
        self.assertGreater(count, 0, "first init must seed the defaults")

    def test_guard_is_per_database_path(self):
        # Two different files each get one real init; a repeat of the
        # first is still a no-op. Fails without the guard (3 calls).
        other = Path(self.tmp.name) / "other.db"
        calls, spy = self._spy()
        with mock.patch.object(fast_fetch, "init_db", spy):
            fast_fetch.init_db_once(self.db)
            fast_fetch.init_db_once(other)
            fast_fetch.init_db_once(self.db)
        self.assertEqual(len(calls), 2,
                         "each database file gets exactly one real init")

    def test_swapped_file_reinitializes(self):
        # A gate swap renames a new file over the path: the guard keys
        # on file identity (device + inode), not just the path, so the
        # new file must be initialised. Pins the guard against a
        # path-only key, which would wrongly skip the swapped-in file.
        calls, spy = self._spy()
        with mock.patch.object(fast_fetch, "init_db", spy):
            fast_fetch.init_db_once(self.db)
            staged = Path(self.tmp.name) / "staged.db"
            sqlite3.connect(staged).close()  # empty file, its own inode
            os.replace(staged, self.db)  # the gate-style swap
            fast_fetch.init_db_once(self.db)
        self.assertEqual(len(calls), 2,
                         "a swapped-in file must be initialised, not skipped")
        self.assertIn("domains_config", self._tables(self.db))

    def test_memory_db_always_inits(self):
        # :memory: has no file identity and every connection is a fresh
        # empty database, so it can never be cached: init must run
        # every time. Pins the never-cache decision.
        calls, spy = self._spy()
        with mock.patch.object(fast_fetch, "init_db", spy):
            fast_fetch.init_db_once(":memory:")
            fast_fetch.init_db_once(":memory:")
        self.assertEqual(len(calls), 2,
                         ":memory: databases must initialise on every call")

    def test_init_db_itself_still_runs_every_time(self):
        # The split, pinned: plain init_db() keeps its repair
        # semantics and is NOT guarded — a second call still runs the
        # whole body. If someone re-adds the guard to init_db, the
        # test_database.py repair tests (backfill-after-drop,
        # index-after-column) break with it.
        calls, spy = self._spy()
        with mock.patch.object(fast_fetch, "init_db", spy):
            fast_fetch.init_db(self.db)
            fast_fetch.init_db(self.db)
        self.assertEqual(len(calls), 2,
                         "init_db must keep running the full body per call")


class InitDbBusyRetryTests(unittest.TestCase):
    """The bounded retry on SQLITE_BUSY."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db = Path(self.tmp.name) / "test.db"

    def test_busy_init_retries_instead_of_failing(self):
        # Hold the SQLite write lock from another connection, release
        # it mid-retry, and prove init_db() still succeeds instead of
        # raising "database is locked". The holder runs in its own
        # thread (a connection may only be used on its creating thread)
        # and signals once BEGIN IMMEDIATE has the lock. The busy
        # timeout is patched to 100 ms so the test runs in ~2 s instead
        # of waiting out the live 30 s value; the retry sleeps, not the
        # timeout, are what absorb the contention. Fails on the old
        # code: the first attempt raises at once.
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
        orig_pragmas = db_pragmas.apply_serve_pragmas

        def fast_pragmas(conn, **kw):
            return orig_pragmas(conn, busy_timeout_ms=100)

        holder.start()
        self.assertTrue(locked.wait(timeout=10),
                        "holder thread never took the write lock")
        try:
            with mock.patch.object(db_pragmas, "apply_serve_pragmas",
                                   fast_pragmas):
                fast_fetch.init_db(self.db)  # must not raise
        finally:
            self.assertTrue(released.wait(timeout=10),
                            "holder thread never released the lock")
            holder.join(timeout=10)
        conn = sqlite3.connect(self.db)
        try:
            tables = {r[0] for r in conn.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            ).fetchall()}
        finally:
            conn.close()
        self.assertIn("domains_config", tables,
                      "the retried init must have created the schema")

    def test_non_busy_error_raises_without_retry(self):
        # A failure that is NOT "database is locked" must raise at once
        # with no retry: the spy proves _init_db ran exactly once.
        calls = []
        real = fast_fetch._init_db

        def spy(db_path=None):
            calls.append(db_path)
            return real(db_path)

        missing_dir = Path(self.tmp.name) / "no-such-dir" / "test.db"
        with mock.patch.object(fast_fetch, "_init_db", spy):
            with self.assertRaises(sqlite3.OperationalError):
                fast_fetch.init_db(missing_dir)
        self.assertEqual(len(calls), 1,
                         "a non-busy error must not be retried")


if __name__ == "__main__":
    unittest.main(verbosity=2)
