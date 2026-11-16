"""Offline tests for the playback-beacon aggregates.

The beacon is the one new thing a phone may tell the server about playback:
one finished session becomes one row-add in `usage_host_quality`, keyed by
(day, host, network) — and the tests below are grouped by the rules that keep
that honest, mirroring `test_usage_stats.py`:

1. **Aggregates only.** The stored row names a day, a host and a network
   class. No session id, no stream handle, no title, no device — so the tests
   assert the *schema* and the *bytes*, not just the numbers, because a counter
   table that grew a `stream_id` column would still return correct counts.
2. **The host comes from our ledger, never from the client.** The app never
   names a hoster, so the beacon's opaque handle is resolved here, from
   `download_sources`, and then discarded. A handle this server did not mint
   — forged, or lapsed past its expiry — names no host and is dropped.
3. **Collection only.** Nothing here ranks a pick. The summary's floor
   (20 plays) and 30-day window are the gates that keep one good evening
   from crowning a host, and the tests prove the gates, not a ranking.

Everything runs against temp SQLite files with an injected clock: no network,
no live catalog, no real token.
"""

import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import accounts                        # noqa: E402
import accounts_api                    # noqa: E402
import catalog_v2                      # noqa: E402
import redact                          # noqa: E402
import usage_stats                     # noqa: E402

#: 08:00:00 UTC on a day number of 20833, so "40 minutes ago" cannot fall on
#: yesterday and make a window assertion depend on when the suite is run.
NOW = 1_799_971_200.0 + 28_800.0
DAY = 86_400.0
SECRET = "beacon-test" + "-key"
WORK_KEY = "series:q3 example work"


def sync_key(label):
    return ("q3-" + label + "-" + "k" * 24)[:40]


def beacon(sid, stream_id, *, outcome="played", error=None, net="wifi",
           ttfb_ms=850, nbytes=8_000_000, load_ms=4000, stalls=1,
           rebuffer_ms=1200, band="480p", br=1_200_000):
    return {"sid": sid, "stream_id": stream_id, "band": band, "br": br,
            "bytes": nbytes, "load_ms": load_ms, "ttfb_ms": ttfb_ms,
            "stalls": stalls, "rebuffer_ms": rebuffer_ms,
            "outcome": outcome, "error": error, "net": net}


class TempLedger(unittest.TestCase):
    """A state DB plus a one-work catalog carrying two download_sources rows."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db = str(Path(self.tmp.name) / "user_state.db")
        with accounts.connect(self.db) as conn:
            conn.commit()
        self.catalog_path = str(Path(self.tmp.name) / "catalog.db")
        with sqlite3.connect(self.catalog_path) as conn:
            catalog_v2.ensure_schema(conn)
            conn.execute("INSERT INTO cat_works(work_key, title, updated_at,"
                         " is_18_plus) VALUES(?,?,?,0)",
                         (WORK_KEY, "Q3 Example Work", NOW))
            conn.execute("CREATE TABLE download_sources ("
                         "id INTEGER PRIMARY KEY, service TEXT)")
            conn.execute("INSERT INTO download_sources(id, service)"
                         " VALUES(42, 'filepress')")
            conn.execute("INSERT INTO download_sources(id, service)"
                         " VALUES(43, 'hubcloud')")
            conn.commit()
        self.catalog = sqlite3.connect(self.catalog_path)
        self.addCleanup(self.catalog.close)

    def token(self, row_id, *, ttl=86400, now=NOW):
        return redact.mint_token(row_id, SECRET, ttl_seconds=ttl, now=now)

    def record(self, payload, *, now=NOW, catalog="__default__",
               secret=SECRET):
        return usage_stats.record_playback_beacon(
            self.db, payload,
            catalog=self.catalog if catalog == "__default__" else catalog,
            secret=secret, now=now)

    def rows(self, sql, args=()):
        with sqlite3.connect(self.db) as conn:
            return conn.execute(sql, args).fetchall()

    def stored_bytes(self):
        return Path(self.db).read_bytes()


class BeaconIngestTests(TempLedger):
    """One valid session lands in the right (day, host, net) row; junk does not."""

    def test_a_played_beacon_lands_on_its_host_and_network(self):
        result = self.record({"playback_beacon": [
            beacon("sid-1", self.token(42))]})
        self.assertEqual(result, {"accepted": 1, "dropped": 0})
        rows = self.rows("SELECT day, host, net, plays, plays_ok, bytes_total,"
                         " load_ms_total, ttfb_500_1000 FROM usage_host_quality")
        self.assertEqual(rows, [(usage_stats.day_of(NOW), "filepress", "wifi",
                                 1, 1, 8_000_000, 4000, 1)])

    def test_a_second_beacon_upserts_into_the_same_row(self):
        self.record({"playback_beacon": [beacon("sid-1", self.token(42))]})
        self.record({"playback_beacon": [beacon("sid-2", self.token(42))]})
        rows = self.rows("SELECT plays, plays_ok FROM usage_host_quality")
        self.assertEqual(rows, [(2, 2)])

    def test_a_network_failure_counts_against_the_host(self):
        self.record({"playback_beacon": [
            beacon("sid-1", self.token(43), outcome="failed",
                   error="network", ttfb_ms=6000)]})
        rows = self.rows("SELECT plays, plays_failed, plays_failed_other,"
                         " ttfb_gt5000 FROM usage_host_quality")
        self.assertEqual(rows, [(1, 1, 0, 1)])

    def test_a_decode_failure_does_not_count_against_the_host(self):
        self.record({"playback_beacon": [
            beacon("sid-1", self.token(43), outcome="failed",
                   error="decode")]})
        rows = self.rows("SELECT plays, plays_failed, plays_failed_other"
                         " FROM usage_host_quality")
        self.assertEqual(rows, [(1, 0, 1)])

    def test_a_failed_beacon_with_no_error_reads_as_unknown(self):
        self.record({"playback_beacon": [
            beacon("sid-1", self.token(43), outcome="failed", error=None)]})
        rows = self.rows("SELECT plays_failed_other FROM usage_host_quality")
        self.assertEqual(rows, [(1,)])

    def test_an_abandoned_beacon_counts_separately(self):
        self.record({"playback_beacon": [
            beacon("sid-1", self.token(42), outcome="abandoned",
                   ttfb_ms=0)]})
        rows = self.rows("SELECT plays, plays_abandoned, ttfb_lt500,"
                         " ttfb_500_1000, ttfb_gt5000 FROM usage_host_quality")
        # A zero TTFB means no media load happened: it is the absence of a
        # measurement, and it must not land in the <500 ms bucket and flatter
        # the host.
        self.assertEqual(rows, [(1, 1, 0, 0, 0)])

    def test_a_forged_handle_is_dropped(self):
        result = self.record({"playback_beacon": [
            beacon("sid-1", "not-a-handle")]})
        self.assertEqual(result, {"accepted": 0, "dropped": 1})
        self.assertEqual(self.rows("SELECT COUNT(*) FROM usage_host_quality"),
                         [(0,)])

    def test_a_bare_row_id_is_not_a_handle(self):
        # The public port takes handles only; a plain integer must not resolve
        # a ledger row through the beacon.
        result = self.record({"playback_beacon": [beacon("sid-1", "42")]})
        self.assertEqual(result, {"accepted": 0, "dropped": 1})

    def test_a_handle_for_a_row_that_is_not_there_is_dropped(self):
        result = self.record({"playback_beacon": [
            beacon("sid-1", self.token(999))]})
        self.assertEqual(result, {"accepted": 0, "dropped": 1})

    def test_an_expired_handle_is_dropped(self):
        short = self.token(42, ttl=60)
        result = self.record({"playback_beacon": [beacon("sid-1", short)]},
                             now=NOW + 3600)
        self.assertEqual(result, {"accepted": 0, "dropped": 1})

    def test_no_catalog_or_no_secret_drops_every_beacon(self):
        good = {"playback_beacon": [beacon("sid-1", self.token(42))]}
        self.assertEqual(
            self.record(good, catalog=None, secret=SECRET),
            {"accepted": 0, "dropped": 1})
        self.assertEqual(
            self.record(good, secret=""),
            {"accepted": 0, "dropped": 1})

    def test_malformed_entries_are_dropped_not_stored(self):
        good = beacon("sid-1", self.token(42))
        bad = [
            dict(good, outcome="watched"),
            dict(good, outcome="played", net="ethernet"),
            dict(good, outcome="failed", error="exploded"),
            dict(good, sid=""),
            dict(good, sid="x" * 65),
            dict(good, sid="has a space"),
            dict(good, bytes=-5),
            dict(good, ttfb_ms=-1),
            dict(good, stalls="many"),
            dict(good, br=True),
            "not-a-dict",
        ]
        for entry in bad:
            result = self.record({"playback_beacon": [entry]})
            self.assertEqual(result, {"accepted": 0, "dropped": 1}, entry)
        self.assertEqual(self.rows("SELECT COUNT(*) FROM usage_host_quality"),
                         [(0,)])

    def test_a_block_that_is_not_a_list_counts_nothing(self):
        self.assertEqual(
            self.record({"playback_beacon": {"sid": "sid-1"}}),
            {"accepted": 0, "dropped": 0})
        self.assertEqual(self.record({}), {"accepted": 0, "dropped": 0})

    def test_the_submission_cap_keeps_the_first_twenty(self):
        block = [beacon("sid-%02d" % i, self.token(42)) for i in range(21)]
        result = self.record({"playback_beacon": block})
        self.assertEqual(result, {"accepted": 20, "dropped": 1})
        self.assertEqual(self.rows("SELECT plays FROM usage_host_quality"),
                         [(20,)])


class BeaconAggregatesOnlyTests(TempLedger):
    """The table cannot name a session, a handle, a title or a device."""

    def test_the_table_has_no_session_or_handle_column(self):
        self.record({"playback_beacon": [beacon("sid-1", self.token(42))]})
        columns = {row[1] for row in self.rows(
            "PRAGMA table_info(usage_host_quality)")}
        self.assertNotIn("sid", columns)
        self.assertNotIn("stream_id", columns)
        self.assertNotIn("device", columns)
        self.assertNotIn("br", columns)

    def test_the_stored_bytes_hold_no_handle_or_session_id(self):
        token = self.token(42)
        self.record({"playback_beacon": [beacon("sid-1", token)]})
        stored = self.stored_bytes()
        self.assertNotIn(token.encode(), stored)
        self.assertNotIn(b"sid-1", stored)


class BeaconSummaryTests(TempLedger):
    """The floor, the window and the decay are the gates — prove them, not a ranking."""

    def _plays(self, host_row, *, count, outcome="played", error=None,
               net="wifi", now=NOW, ttfb_ms=850):
        block = [beacon("sid-%s-%d" % (host_row, i), self.token(host_row),
                        outcome=outcome, error=error, net=net,
                        ttfb_ms=ttfb_ms)
                 for i in range(count)]
        return self.record({"playback_beacon": block}, now=now)

    def test_a_pair_under_the_floor_has_no_score(self):
        self._plays(42, count=19)
        summary = usage_stats.host_quality_summary(self.db, now=NOW)
        self.assertEqual(summary["hosts"], {})
        self.assertEqual(summary["below_floor"],
                         [{"host": "filepress", "net": "wifi", "plays": 19}])

    def test_the_twentieth_play_opens_the_score(self):
        self._plays(42, count=19)
        self._plays(42, count=1)
        summary = usage_stats.host_quality_summary(self.db, now=NOW)
        self.assertEqual(summary["hosts"]["filepress"]["wifi"]["plays"], 20)
        self.assertEqual(summary["hosts"]["filepress"]["wifi"]["ok_rate"], 1.0)
        self.assertEqual(summary["below_floor"], [])

    def test_rows_older_than_the_window_stop_counting(self):
        self._plays(42, count=20, now=NOW - 31 * DAY)
        summary = usage_stats.host_quality_summary(self.db, now=NOW)
        self.assertEqual(summary["hosts"], {})
        self.assertEqual(summary["below_floor"], [])

    def test_recent_days_weigh_more_than_old_ones(self):
        self._plays(42, count=10, outcome="played", now=NOW)
        self._plays(42, count=10, outcome="failed", error="network",
                    now=NOW - 10 * DAY)
        summary = usage_stats.host_quality_summary(self.db, now=NOW)
        ok_rate = summary["hosts"]["filepress"]["wifi"]["ok_rate"]
        # Ten fresh oks weigh 1.0; ten ten-day-old failures weigh 0.9**10.
        self.assertGreater(ok_rate, 0.7)
        self.assertLess(ok_rate, 0.8)

    def test_ttfb_buckets_give_a_mean_and_a_p50(self):
        for i, ttfb in enumerate((100, 400, 1500, 6000)):
            self._plays(42, count=1, now=NOW, ttfb_ms=ttfb)
        summary = usage_stats.host_quality_summary(
            self.db, now=NOW, min_plays=1)
        stats = summary["hosts"]["filepress"]["wifi"]
        self.assertEqual(stats["mean_ttfb_ms"], 2375.0)
        self.assertEqual(stats["p50_ttfb_bucket"], "ttfb_lt500")

    def test_mean_throughput_comes_from_the_sums_not_the_ratios(self):
        self._plays(42, count=2, now=NOW)
        summary = usage_stats.host_quality_summary(
            self.db, now=NOW, min_plays=1)
        stats = summary["hosts"]["filepress"]["wifi"]
        self.assertEqual(stats["mean_mtp_bps"], 16_000_000)

    def test_the_summary_reads_on_a_ledger_with_no_beacon_rows(self):
        summary = usage_stats.host_quality_summary(self.db, now=NOW)
        self.assertEqual(summary["hosts"], {})
        self.assertEqual(summary["below_floor"], [])


class BeaconAccountSurfaceTests(TempLedger):
    """The beacon rides the progress write the app already makes — no new route."""

    def setUp(self):
        super().setUp()
        self.key = sync_key("hook")
        accounts.create_account(self.db, key=self.key)
        self.handle = accounts_api._Minter(SECRET)(WORK_KEY)

    def post(self, path, payload, key=None, headers=None):
        sent = {"Authorization": "Bearer " + (self.key if key is None else key)}
        sent.update(headers or {})
        return accounts_api.route_post(path, payload, db_path=self.db,
                                       catalog_path=self.catalog_path,
                                       secret=SECRET, headers=sent)

    def progress(self, *, position=10, beacons=None):
        body = {"work_ref": self.handle, "position_seconds": position,
                "duration_seconds": 100}
        if beacons is not None:
            body["playback_beacon"] = beacons
        return self.post("/api/v2/account/progress", body)

    def test_the_beacon_block_rides_the_progress_write(self):
        answered = self.progress(beacons=[
            beacon("sid-1", self.token(42))])
        self.assertEqual(answered[0], 201)
        rows = self.rows("SELECT host, plays FROM usage_host_quality")
        self.assertEqual(rows, [("filepress", 1)])

    def test_a_progress_write_with_no_beacon_block_counts_nothing_new(self):
        self.progress()
        self.assertEqual(self.rows("SELECT COUNT(*) FROM usage_host_quality"),
                         [(0,)])

    def test_a_forged_beacon_on_a_real_progress_write_is_dropped(self):
        answered = self.progress(beacons=[
            beacon("sid-1", "not-a-handle")])
        self.assertEqual(answered[0], 201)
        self.assertEqual(self.rows("SELECT COUNT(*) FROM usage_host_quality"),
                         [(0,)])

    def test_an_unauthenticated_progress_write_stores_no_beacon(self):
        answered = self.post("/api/v2/account/progress",
                             {"work_ref": self.handle, "position_seconds": 1,
                              "playback_beacon": [
                                  beacon("sid-1", self.token(42))]},
                             key="")
        self.assertEqual(answered[0], 401)
        self.assertEqual(self.rows("SELECT COUNT(*) FROM usage_host_quality"),
                         [(0,)])


if __name__ == "__main__":
    unittest.main()
