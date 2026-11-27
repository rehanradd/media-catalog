#!/usr/bin/env python3
"""Tests for recheck_queue.py — the domain recheck queue (Branch 4).

Offline, stdlib, no network: the queue is a sidecar SQLite table next
to a temp DB path. Pinned here:
* enqueue tops up dead_rows and re-activates a done row;
* pending() only returns rows past the cooldown, so a down site is
  retried at most once per cooldown window;
* mark_attempt(done=True) closes a row, (done=False) leaves it pending.
"""

import tempfile
import time
import unittest
from pathlib import Path

import recheck_queue as rq


class RecheckQueueTests(unittest.TestCase):
    def _qpath(self):
        tmp = tempfile.TemporaryDirectory()
        return tmp, Path(tmp.name) / "domain_recheck_queue.db"

    def test_enqueue_creates_pending_entry(self):
        tmp, qpath = self._qpath()
        try:
            rq.enqueue(qpath, "ssrmovies", 10, "batch-dead-rows:10")
            entry = rq.get_entry(qpath, "ssrmovies")
            self.assertIsNotNone(entry)
            self.assertEqual(entry["dead_rows"], 10)
            self.assertEqual(entry["reason"], "batch-dead-rows:10")
            self.assertEqual(entry["status"], "pending")
            self.assertEqual(entry["attempts"], 0)
            self.assertIsNone(entry["last_attempt_at"])
            self.assertEqual(rq.count_pending(qpath), 1)
        finally:
            tmp.cleanup()

    def test_enqueue_accumulates_dead_rows(self):
        tmp, qpath = self._qpath()
        try:
            rq.enqueue(qpath, "ssrmovies", 10, "batch-dead-rows:10")
            rq.enqueue(qpath, "ssrmovies", 7, "batch-dead-rows:7")
            entry = rq.get_entry(qpath, "ssrmovies")
            self.assertEqual(entry["dead_rows"], 17)
            self.assertEqual(entry["reason"], "batch-dead-rows:7")
            self.assertEqual(rq.count_pending(qpath), 1)
        finally:
            tmp.cleanup()

    def test_enqueue_reactivates_a_done_row(self):
        tmp, qpath = self._qpath()
        try:
            rq.enqueue(qpath, "ssrmovies", 10, "batch-dead-rows:10")
            rq.mark_attempt(qpath, "ssrmovies", "resolved:https://x", done=True)
            self.assertEqual(rq.get_entry(qpath, "ssrmovies")["status"], "done")
            rq.enqueue(qpath, "ssrmovies", 11, "batch-dead-rows:11")
            entry = rq.get_entry(qpath, "ssrmovies")
            self.assertEqual(entry["status"], "pending")
            self.assertEqual(entry["dead_rows"], 21)
        finally:
            tmp.cleanup()

    def test_pending_respects_cooldown(self):
        tmp, qpath = self._qpath()
        try:
            rq.enqueue(qpath, "ssrmovies", 10, "batch-dead-rows:10")
            rq.mark_attempt(qpath, "ssrmovies", "unresolved:down", done=False)
            # Just attempted: not due under the default 6h cooldown.
            self.assertEqual(rq.pending(qpath), [])
            # Cooldown 0: due at once.
            due = rq.pending(qpath, cooldown_s=0)
            self.assertEqual([e["site"] for e in due], ["ssrmovies"])
        finally:
            tmp.cleanup()

    def test_pending_orders_oldest_first_and_honours_limit(self):
        tmp, qpath = self._qpath()
        try:
            rq.enqueue(qpath, "ssrmovies", 10, "first")
            time.sleep(0.01)
            rq.enqueue(qpath, "vegamovies", 12, "second")
            due = rq.pending(qpath, limit=1)
            self.assertEqual([e["site"] for e in due], ["ssrmovies"])
            due = rq.pending(qpath)
            self.assertEqual([e["site"] for e in due],
                             ["ssrmovies", "vegamovies"])
        finally:
            tmp.cleanup()

    def test_mark_attempt_done_closes_row(self):
        tmp, qpath = self._qpath()
        try:
            rq.enqueue(qpath, "ssrmovies", 10, "batch-dead-rows:10")
            rq.mark_attempt(qpath, "ssrmovies", "resolved:https://y", done=True)
            entry = rq.get_entry(qpath, "ssrmovies")
            self.assertEqual(entry["status"], "done")
            self.assertEqual(entry["attempts"], 1)
            self.assertIsNotNone(entry["last_attempt_at"])
            self.assertEqual(entry["last_result"], "resolved:https://y")
            self.assertEqual(rq.count_pending(qpath), 0)
            self.assertEqual(rq.pending(qpath), [])
        finally:
            tmp.cleanup()

    def test_queue_path_for_db(self):
        self.assertEqual(
            rq.queue_path_for_db("/home/ubuntu/radd-stream-data/merged_catalog_20261025.db"),
            Path("/home/ubuntu/radd-stream-data/domain_recheck_queue.db"))
        self.assertEqual(rq.queue_path_for_db("/tmp/staging.db"),
                         Path("/tmp/domain_recheck_queue.db"))

    def test_get_entry_returns_none_for_unknown_site(self):
        tmp, qpath = self._qpath()
        try:
            self.assertIsNone(rq.get_entry(qpath, "nosuchsite"))
        finally:
            tmp.cleanup()


if __name__ == "__main__":
    unittest.main()
