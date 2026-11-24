#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline tests for the H4 R5 shared streaming cache slice.

The audit behind this slice: `resolved_cache` was ALREADY shared across
all devices/users — it is keyed by `link_id` only and `lookup_cache` takes
no identity. What was per-device was only the rate bucket. So this slice
closes the remaining gaps in the already-shared cache instead of building
a second one:

* A — `resolved_cache`: a demand ledger (`shared_warm_demand`) counting
  cold presses per (day, hour, work, band) — aggregates only, no user,
  no device, no URL, no link id — and `hot_works`, the hottest works in
  the last 6 hours (flagged works and their unflagged twins excluded).
* B — `viewer`: a cold press on the stream/download route records its
  demand and warms the print's other bands in the background (at most 2
  siblings, only when definitely cold, only when the process-wide warm
  budget has room).
* C — `prefetch_worker`: `--demand` promotes the ledger's hot works to
  pins in a fill/top-up, after operator pins and before the priority pick.

What this pins, and what it deliberately does NOT build (see the session
log for the research notes behind each):

* never-serve-expired: a banked row is shared by construction, and a row
  past `reverify_after` is refused by `lookup_cache` for every caller —
  so a shared cache can never hand one user another user's expired token.
  The 302 `no-store` rule is already pinned elsewhere
  (`test_bundle_handles.py`, `test_api_v2.py`); the demand ledger stores
  no URL at all, so there is nothing here that could be served.
* stampede: `_resolve_coalesced`'s 12 s waiter bound is PINNED by
  `test_edge_phase1.py` (a timed-out waiter resolves on its own), so the
  duplicate-resolve-after-12 s hole stays as a documented residual.
* stale-while-revalidate past `reverify_after` was rejected: it would gain
  at most the 60 s in-flight death margin and eats the guarantee a token
  never dies between our 302 and the player's first byte.

No network, no production DB: every test runs against an in-memory SQLite
fixture with stubbed resolvers and stubbed warm machinery.
"""

from __future__ import annotations

import os
import sqlite3
import sys
import time
import unittest
from contextlib import contextmanager
from unittest import mock

os.environ.setdefault("RADD_SKIP_LIVE_ENRICH", "1")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fast_fetch  # noqa: E402
import prefetch_worker  # noqa: E402
import resolved_cache  # noqa: E402
import viewer  # noqa: E402


#: Fixed epoch for deterministic day/hour cells (2023-11-14 22:13 UTC).
NOW = 1_700_000_000.0

#: The only tables the slice's queries touch, with the columns `tiers`
#: reads when it ranks a release's links (same shape as the prefetch
#: worker's own fixture).
_SCHEMA = """
CREATE TABLE posts (
    id INTEGER PRIMARY KEY, clean_title TEXT, title TEXT,
    is_18_plus INTEGER, canonical_key TEXT);
CREATE TABLE releases (
    id INTEGER PRIMARY KEY, post_id INTEGER, quality TEXT, codec TEXT,
    size TEXT, size_bytes INTEGER, resolution TEXT, quality_label TEXT,
    languages TEXT);
CREATE TABLE download_sources (
    id INTEGER PRIMARY KEY, release_id INTEGER, kind TEXT, status TEXT,
    source_url TEXT, final_url TEXT, service TEXT, source_name TEXT,
    http_status INTEGER, is_resumable INTEGER, rank_score INTEGER,
    file_size_bytes INTEGER, mime_type TEXT, playable INTEGER,
    streamable INTEGER, speed_mbps REAL, latency_ms INTEGER,
    last_checked_at TEXT);
"""


def _make_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.executescript(_SCHEMA)
    resolved_cache.init_resolved_cache(conn)
    return conn


def _add_post(conn, post_id, title, *, flagged=False, canonical_key=None,
              releases=None):
    """One post with the given releases, each with one playable link.

    `releases` is ((release_id, quality), ...); when omitted the post gets
    one 1080p release whose id derives from the post id, so several posts
    never collide on the release id.
    """
    if releases is None:
        releases = ((post_id * 10, "1080p"),)
    conn.execute(
        "INSERT INTO posts VALUES (?, ?, ?, ?, ?)",
        (post_id, title, title, 1 if flagged else 0,
         canonical_key or title.lower().replace(" ", "-")))
    for release_id, quality in releases:
        conn.execute(
            "INSERT INTO releases VALUES (?, ?, ?, NULL, NULL, NULL, NULL,"
            " NULL, NULL)",
            (release_id, post_id, quality))
        conn.execute(
            """INSERT INTO download_sources
               (id, release_id, kind, status, source_url, service,
                source_name, http_status, is_resumable, file_size_bytes,
                mime_type)
               VALUES (?, ?, 'direct', 'active',
                       'https://pixeldrain.com/api/file/TEST', 'pixeldrain',
                       'Pixeldrain', 200, 1, 2000000000, 'video/mp4')""",
            (release_id * 10, release_id))
    conn.commit()


def _press(conn, work_id, band, count=1, now=NOW):
    for _ in range(count):
        resolved_cache.note_cold_press(conn, work_id=work_id, band=band,
                                       now=now)
    conn.commit()


@contextmanager
def _yield_conn(conn):
    yield conn


def _fake_resolve_factory(calls):
    """A resolver that always succeeds with a direct file, recording calls."""
    def _resolve(source_id, v2=True, **_kw):
        calls.append((source_id, v2))
        return {"success": True, "id": source_id,
                "url": "https://pixeldrain.com/api/file/TEST100",
                "service": "pixeldrain", "is_temporary": False,
                "is_resumable": True, "mime_type": "video/mp4",
                "file_size_bytes": 2_000_000_000}
    return _resolve


class TestDemandLedger(unittest.TestCase):
    def test_note_counts_and_returns_new_count(self):
        conn = _make_conn()
        self.assertEqual(
            resolved_cache.note_cold_press(conn, work_id=1, band="1080p",
                                           now=NOW), 1)
        self.assertEqual(
            resolved_cache.note_cold_press(conn, work_id=1, band="1080p",
                                           now=NOW), 2)
        # A different band is a different cell, not a second press of 1080p.
        self.assertEqual(
            resolved_cache.note_cold_press(conn, work_id=1, band="480p",
                                           now=NOW), 1)

    def test_note_separates_hours(self):
        conn = _make_conn()
        self.assertEqual(
            resolved_cache.note_cold_press(conn, work_id=1, band="1080p",
                                           now=NOW), 1)
        one_hour_later = NOW + 3600
        self.assertEqual(
            resolved_cache.note_cold_press(conn, work_id=1, band="1080p",
                                           now=one_hour_later), 1)

    def test_note_rejects_bad_work_id(self):
        conn = _make_conn()
        for bad in (0, -3, "not-a-work", None):
            self.assertIsNone(
                resolved_cache.note_cold_press(conn, work_id=bad,
                                               band="1080p", now=NOW))
        tables = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type = 'table'")}
        self.assertNotIn("shared_warm_demand", tables)

    def test_note_band_fallback_and_cap(self):
        conn = _make_conn()
        self.assertEqual(
            resolved_cache.note_cold_press(conn, work_id=1, band=None,
                                           now=NOW), 1)
        row = conn.execute(
            "SELECT band FROM shared_warm_demand WHERE work_id = 1"
            ).fetchone()
        self.assertEqual(row[0], "unspecified")
        self.assertEqual(
            resolved_cache.note_cold_press(
                conn, work_id=2, band="a-very-long-band-name", now=NOW), 1)
        row = conn.execute(
            "SELECT band FROM shared_warm_demand WHERE work_id = 2"
            ).fetchone()
        self.assertEqual(len(row[0]), 16)

    def test_ledger_schema_holds_no_identity(self):
        conn = _make_conn()
        resolved_cache.note_cold_press(conn, work_id=1, band="1080p",
                                       now=NOW)
        cols = {r[1] for r in conn.execute(
            "PRAGMA table_info(shared_warm_demand)")}
        self.assertEqual(
            cols, {"day", "hour", "work_id", "band", "cold_presses"})
        for forbidden in ("user", "device", "url", "link_id", "source_id",
                          "ip"):
            self.assertNotIn(forbidden, cols)


class TestHotWorks(unittest.TestCase):
    def setUp(self):
        self.conn = _make_conn()
        _add_post(self.conn, 1, "Big Film")
        _add_post(self.conn, 2, "Small Film")
        _add_post(self.conn, 3, "Hot Film")

    def test_orders_hottest_first(self):
        _press(self.conn, 1, "1080p", count=5)
        _press(self.conn, 2, "1080p", count=2)
        _press(self.conn, 3, "1080p", count=3)
        self.assertEqual(
            [(w["work_id"], w["presses"]) for w in
             resolved_cache.hot_works(self.conn, now=NOW)],
            [(1, 5), (3, 3), (2, 2)])

    def test_window_excludes_old_presses(self):
        _press(self.conn, 1, "1080p", count=5, now=NOW - 7 * 3600)
        _press(self.conn, 2, "1080p", count=2, now=NOW - 5 * 3600)
        self.assertEqual(
            [w["work_id"] for w in resolved_cache.hot_works(self.conn,
                                                            now=NOW)],
            [2])

    def test_window_spans_midnight(self):
        # Read at 02:00 local; a press at 21:00 the previous local day is
        # inside the 6 h window, a press at 19:00 is outside — the
        # (day, hour) row comparison must span local midnight, not stop
        # at the calendar-day boundary.
        local = time.localtime(NOW)
        midnight = NOW - (local.tm_hour * 3600 + local.tm_min * 60
                          + local.tm_sec)
        read_at = midnight + 26 * 3600  # 02:00 next day, local
        _press(self.conn, 1, "1080p", count=2, now=midnight + 21 * 3600)
        _press(self.conn, 2, "1080p", count=2, now=midnight + 19 * 3600)
        self.assertEqual(
            [w["work_id"] for w in resolved_cache.hot_works(self.conn,
                                                            now=read_at)],
            [1])

    def test_threshold(self):
        _press(self.conn, 1, "1080p", count=1)
        _press(self.conn, 2, "1080p", count=2)
        self.assertEqual(
            [w["work_id"] for w in resolved_cache.hot_works(self.conn,
                                                            now=NOW)],
            [2])

    def test_limit(self):
        _press(self.conn, 1, "1080p", count=5)
        _press(self.conn, 2, "1080p", count=4)
        _press(self.conn, 3, "1080p", count=3)
        self.assertEqual(
            [w["work_id"] for w in resolved_cache.hot_works(
                self.conn, now=NOW, limit=2)],
            [1, 2])

    def test_flagged_and_twin_excluded(self):
        _add_post(self.conn, 4, "Flagged Film", flagged=True,
                  canonical_key="flagged-film")
        _add_post(self.conn, 5, "Flagged Film", canonical_key="flagged-film")
        _press(self.conn, 4, "1080p", count=9)
        _press(self.conn, 5, "1080p", count=9)
        _press(self.conn, 1, "1080p", count=2)
        self.assertEqual(
            [w["work_id"] for w in resolved_cache.hot_works(self.conn,
                                                            now=NOW)],
            [1])

    def test_missing_post_excluded(self):
        _press(self.conn, 99, "1080p", count=9)
        _press(self.conn, 1, "1080p", count=2)
        self.assertEqual(
            [w["work_id"] for w in resolved_cache.hot_works(self.conn,
                                                            now=NOW)],
            [1])

    def test_empty_when_no_tables(self):
        conn = sqlite3.connect(":memory:")
        self.assertEqual(resolved_cache.hot_works(conn, now=NOW), [])


class TestSharedByConstruction(unittest.TestCase):
    """The cache is shared by construction; expiry is refused per row."""

    def _bank_row(self, conn, link_id, *, reverify_after):
        conn.execute(
            "INSERT INTO resolved_cache (link_id, url, category, kind,"
            " mime_type, file_size_bytes, resolved_at, expires_at,"
            " reverify_after, is_resumable, http_status, source, ip_bound,"
            " fail_count) VALUES (?, ?, 'A', 'file', 'video/mp4', 1000, ?,"
            " ?, ?, 1, 200, 'jit', NULL, 0)",
            (link_id, "https://pixeldrain.com/api/file/TEST100",
             NOW, NOW + 3600, reverify_after))
        conn.commit()

    def test_fresh_row_serves_any_caller(self):
        conn = _make_conn()
        self._bank_row(conn, 100, reverify_after=NOW + 120)
        hit = resolved_cache.lookup_cache(conn, 100, now=NOW)
        self.assertIsNotNone(hit)
        self.assertEqual(hit["url"],
                         "https://pixeldrain.com/api/file/TEST100")

    def test_expired_row_is_refused(self):
        conn = _make_conn()
        self._bank_row(conn, 100, reverify_after=NOW - 1)
        self.assertIsNone(resolved_cache.lookup_cache(conn, 100, now=NOW))

    def test_ip_bound_row_is_refused_without_opt_in(self):
        # API-01: opting in alone no longer serves a bound row — the old
        # opt-in replayed one client's token to any caller, which is the
        # hole (F-01). The row serves only to the IP it was minted for.
        conn = _make_conn()
        conn.execute(
            "INSERT INTO resolved_cache (link_id, url, category, kind,"
            " mime_type, file_size_bytes, resolved_at, expires_at,"
            " reverify_after, is_resumable, http_status, source, ip_bound,"
            " minted_ip, fail_count) VALUES (100,"
            " 'https://pixeldrain.com/api/file/X',"
            " 'A', 'file', 'video/mp4', 1000, ?, ?, ?, 1, 200, 'jit', 1,"
            " '203.0.113.7', 0)",
            (NOW, NOW + 3600, NOW + 120))
        conn.commit()
        self.assertIsNone(resolved_cache.lookup_cache(conn, 100, now=NOW))
        self.assertIsNone(
            resolved_cache.lookup_cache(conn, 100, now=NOW,
                                        allow_ip_bound=True),
            "opt-in without the minting IP proves nothing")
        self.assertIsNone(
            resolved_cache.lookup_cache(conn, 100, now=NOW,
                                        allow_ip_bound=True,
                                        client_ip="198.51.100.9"),
            "a foreign IP reads as a miss and re-resolves")
        self.assertIsNotNone(
            resolved_cache.lookup_cache(conn, 100, now=NOW,
                                        allow_ip_bound=True,
                                        client_ip="203.0.113.7"))


class TestNoteSharedDemand(unittest.TestCase):
    def test_records_work_and_band_from_release_row(self):
        conn = _make_conn()
        _add_post(conn, 1, "Test Film", releases=((10, "1080p"),))
        viewer._note_shared_demand(conn, 100)
        row = conn.execute(
            "SELECT work_id, band, cold_presses FROM shared_warm_demand"
            ).fetchone()
        self.assertEqual(tuple(row), (1, "1080p", 1))

    def test_unknown_link_records_nothing(self):
        conn = _make_conn()
        viewer._note_shared_demand(conn, 999)
        tables = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type = 'table'")}
        self.assertNotIn("shared_warm_demand", tables)

    def test_never_raises(self):
        broken = mock.Mock()
        broken.execute.side_effect = sqlite3.OperationalError("boom")
        viewer._note_shared_demand(broken, 100)  # must not raise


class TestWarmSiblingBands(unittest.TestCase):
    def _run(self, conn, fill_links, warm_map):
        started = []
        with mock.patch.object(viewer, "get_db",
                               side_effect=lambda: _yield_conn(conn)), \
             mock.patch.object(prefetch_worker, "fill_links_for_post",
                               return_value=fill_links), \
             mock.patch.object(
                 viewer, "_preresolve_is_warm",
                 side_effect=lambda link_id: warm_map.get(link_id, False)), \
             mock.patch.object(viewer, "_start_preresolve",
                               side_effect=started.append):
            viewer._warm_sibling_bands(100)
        return started

    def _conn_with_link(self):
        conn = _make_conn()
        _add_post(conn, 1, "Test Film", releases=((10, "1080p"),))
        return conn

    def test_warms_siblings_not_pressed_link(self):
        conn = self._conn_with_link()
        self.assertEqual(self._run(conn, [100, 101, 102], {}), [101, 102])

    def test_skips_warm_sibling(self):
        conn = self._conn_with_link()
        self.assertEqual(self._run(conn, [100, 101, 102], {101: True}),
                         [102])

    def test_unknown_warmth_is_not_spawned(self):
        # Fail-closed, like the detail warm: an unknown row stays unspawned.
        conn = self._conn_with_link()
        self.assertEqual(self._run(conn, [100, 101], {101: None}), [])

    def test_no_siblings_when_fill_is_pressed_link_only(self):
        conn = self._conn_with_link()
        self.assertEqual(self._run(conn, [100], {}), [])

    def test_caps_at_two_siblings(self):
        conn = self._conn_with_link()
        self.assertEqual(self._run(conn, [100, 101, 102, 103], {}),
                         [101, 102])

    def test_missing_link_row_warms_nothing(self):
        conn = _make_conn()
        self.assertEqual(self._run(conn, [100, 101], {}), [])

    def test_never_raises(self):
        conn = self._conn_with_link()
        with mock.patch.object(viewer, "get_db",
                               side_effect=RuntimeError("boom")):
            viewer._warm_sibling_bands(100)  # must not raise


class TestDemandPins(unittest.TestCase):
    def setUp(self):
        self.conn = _make_conn()
        # Post 1 is the priority pick (two releases); post 3 is the hot
        # one (one release, three cold presses).
        _add_post(self.conn, 1, "Big Film",
                  releases=((10, "1080p"), (11, "480p")))
        _add_post(self.conn, 2, "Small Film", releases=((20, "1080p"),))
        _add_post(self.conn, 3, "Hot Film", releases=((30, "1080p"),))
        # Presses use real now: the pool reads a rolling 6 h window from
        # the clock, so a fixed-epoch press would fall outside it.
        _press(self.conn, 3, "1080p", count=3, now=time.time())

    def _run(self, **over):
        calls = []
        kwargs = dict(limit=1, min_fresh=3600.0, load_ceiling=4.0,
                      fail_backoff=900.0, dry_run=False, verbose=False,
                      max_wait_seconds=300.0, _failure_backoff={})
        kwargs.update(over)
        with mock.patch.object(fast_fetch, "get_db",
                               side_effect=lambda: _yield_conn(self.conn)), \
             mock.patch.object(fast_fetch, "resolve_live_source",
                               side_effect=_fake_resolve_factory(calls)), \
             mock.patch.object(prefetch_worker, "current_load",
                               return_value=0.1):
            return prefetch_worker.prefetch_once(**kwargs), calls

    def test_demand_pin_ids_hottest_first(self):
        _press(self.conn, 1, "1080p", count=2, now=time.time())
        self.assertEqual(prefetch_worker._demand_pin_ids(self.conn),
                         (3, 1))

    def test_demand_pin_ids_capped(self):
        _press(self.conn, 1, "1080p", count=2, now=time.time())
        self.assertEqual(prefetch_worker._demand_pin_ids(self.conn, limit=1),
                         (3,))

    def test_demand_promotes_hot_work_over_priority_pick(self):
        stats, calls = self._run(mode="fill", demand=True)
        self.assertEqual(stats["demand"], 1)
        self.assertEqual({c[0] for c in calls}, {300})

    def test_without_demand_priority_pick_wins(self):
        stats, calls = self._run(mode="fill")
        self.assertEqual(stats["demand"], 0)
        # A fill warms two links per title: start rung 480p (110) + auto
        # rung 1080p (100).
        self.assertEqual({c[0] for c in calls}, {100, 110})

    def test_operator_pins_ordered_before_demand(self):
        # Operator pins beat demand in ORDER, not by excluding it: both
        # are pins (pins always warm, even past the limit), the operator's
        # post resolves first and the hot work still gets promoted.
        stats, calls = self._run(mode="fill", demand=True, pins=(1,))
        self.assertEqual(stats["demand"], 1)
        ids = [c[0] for c in calls]
        self.assertEqual(set(ids), {100, 110, 300})
        self.assertLess(min(ids.index(100), ids.index(110)),
                        ids.index(300))

    def test_sweep_ignores_demand(self):
        stats, calls = self._run(mode="sweep", demand=True)
        self.assertEqual(stats["demand"], 0)
        # A sweep warms the one Tier-1 winner only.
        self.assertEqual({c[0] for c in calls}, {100})

    def test_flagged_hot_work_is_never_promoted(self):
        _add_post(self.conn, 4, "Flagged Film", flagged=True,
                  canonical_key="flagged-film",
                  releases=((40, "1080p"),))
        _press(self.conn, 4, "1080p", count=9, now=time.time())
        stats, calls = self._run(mode="fill", demand=True)
        self.assertEqual(stats["demand"], 1)
        self.assertEqual({c[0] for c in calls}, {300})


if __name__ == "__main__":
    unittest.main()
