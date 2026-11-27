"""test_per_segment_retry.py — H4 R7: mid-playback failure feedback loop.

The server can never see a segment fetch (the 302 hands the player straight to
the host), so the loop is: the app reports a rendition it abandoned mid-play with
a failure class, and the server banks the dead winner so the next press does not
re-serve it.

What this pins:
- A `dead_handle` report (a 404/403/410 from OUR redirector) strikes BOTH failure
  ledgers — the `resolved_cache` circuit AND the `download_sources` negative cache —
  and the banked row is refused on the very next lookup, not after three strikes.
- Every other failure class (`network`, `decode`, `gate`, `rate_limited`) records
  evidence only and strikes NEITHER ledger; an unknown class is treated as no class.
- The report route resolves the handle in the LEDGER universe: the app sends the
  bundle's `stream_id`, minted from `ledger_link_id` — a `download_sources` id —
  not a `cat_links` id. The fixture deliberately sets cat id != ledger id
  (cat 10 ↔ ledger 7), so a route that read `l.id` would 404 or misattribute.
- `lookup_cache` refuses a row whose ledger row carries a fresh negative-cache
  record, and serves again once the record is stale or from an older resolver;
  it fails open when there is no `download_sources` table at all.
- The existing contracts still hold through the ledger handle: three plain
  `failed` reports retire a row, a `played` report never strikes, and a report
  for an adult-flagged work answers exactly like a missing link.
"""

import io
import json
import os
import sqlite3
import tempfile
import time
import unittest
import unittest.mock
from pathlib import Path

import api_v2
import catalog_v2
import fast_fetch
import redact
import resolved_cache


def setUpModule():
    """Branch 2: ``record_resolved_cache`` now probes the URL at bank
    time. This suite banks fixture URLs offline, so the probe is faked
    as passing — the refusal itself is proven by
    ``test_resolved_cache.TestBankRefusesNonMedia``."""
    global _probe_patcher
    _probe_patcher = unittest.mock.patch.object(
        resolved_cache, "_media_probe", return_value=(True, None))
    _probe_patcher.start()


def tearDownModule():
    _probe_patcher.stop()


HOST = "http://our.host"
SECRET = "test-secret-for-h4-r7"

#: The fixture's public link: cat_links id 10, ledger (download_sources) id 7.
CAT_ID = 10
LEDGER_ID = 7
WORK_ID = 1
ADULT_CAT_ID = 11
ADULT_LEDGER_ID = 8
ADULT_WORK_ID = 2


class FakeHandler:
    """Minimal recorder for the two reply primitives a route uses."""

    def __init__(self, headers=None, body=b""):
        self.headers = dict(headers or {})
        self.rfile = io.BytesIO(body)
        self.rate_headers = []
        self.replies = []

    def send_response(self, code):
        self.replies.append({"kind": "raw", "status": int(code), "headers": [],
                             "body": b"", "payload": None})

    def send_header(self, name, value):
        if not self.replies:
            self.send_response(200)
        self.replies[-1]["headers"].append((str(name), str(value)))

    def end_headers(self):
        pass

    def send_bytes(self, data, content_type="text/html; charset=utf-8", status=200,
                   extra_headers=None):
        headers = [("Content-Type", str(content_type)),
                   ("Content-Length", str(len(bytes(data)))),
                   ("Connection", "close")]
        headers.extend((str(k), str(v)) for k, v in (extra_headers or []))
        self.replies.append({"kind": "bytes", "status": int(status),
                             "headers": headers, "body": bytes(data),
                             "content_type": content_type, "payload": None})

    def send_json(self, data, status=200, extra_headers=None):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_bytes(body, content_type="application/json; charset=utf-8",
                        status=status)
        self.replies[-1]["payload"] = data

    def finish_reply(self):
        for reply in self.replies:
            if reply["kind"] == "bytes":
                reply["headers"].extend(self.rate_headers)
            if "content_type" not in reply:
                reply["content_type"] = ""


class Reply:
    """One answer: what the socket would have carried, plus JSON-shaped access."""

    def __init__(self, handler, path):
        handler.finish_reply()
        self.path = path
        self.replies = handler.replies
        reply = handler.replies[0]
        self.status = reply["status"]
        self.body = reply["body"]
        self.json = reply["payload"]
        if self.json is None and "json" in reply.get("content_type", "") and self.body:
            self.json = json.loads(self.body.decode("utf-8"))

    @property
    def code(self):
        return (self.json or {}).get("code")


class PerSegmentRetryFixture(unittest.TestCase):
    """One minimal corpus per test: cat id != ledger id, on purpose."""

    def setUp(self):
        os.environ["RADD_STREAM_SECRET"] = SECRET
        self.addCleanup(os.environ.pop, "RADD_STREAM_SECRET", None)
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp.name) / "wire.db"
        self._patches = [
            unittest.mock.patch.object(fast_fetch, "DB_PATH", self.db_path),
        ]
        for patcher in self._patches:
            patcher.start()
        fast_fetch.init_db(self.db_path)
        self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(catalog_v2.DDL)
        resolved_cache.init_resolved_cache(self.conn)
        self._build_corpus()
        self.conn.commit()
        self.secret = redact.stream_secret(self.db_path)

    def tearDown(self):
        self.conn.close()
        for patcher in self._patches:
            patcher.stop()
        self.tmp.cleanup()

    def _build_corpus(self):
        # Ledger rows first: ids 7 and 8, so the projected ids (10, 11) can
        # never coincide with them however the sequences advance.
        for ledger_id, service in ((LEDGER_ID, "pixeldrain"),
                                   (ADULT_LEDGER_ID, "pixeldrain")):
            self.conn.execute(
                "INSERT INTO download_sources (id, release_id, service,"
                " source_url, final_url, playback_type, status)"
                " VALUES (?, 1, ?, ?, ?, 'direct', 'active')",
                (ledger_id, service,
                 f"https://pixeldrain.com/api/file/{ledger_id}",
                 f"https://pixeldrain.com/api/file/{ledger_id}"))
        self.conn.execute(
            "INSERT INTO cat_works (id, work_key, title, is_18_plus, updated_at)"
            " VALUES (?, 'h4r7-work', 'H4 R7 Work', 0, ?)", (WORK_ID, time.time()))
        self.conn.execute(
            "INSERT INTO cat_works (id, work_key, title, is_18_plus, updated_at)"
            " VALUES (?, 'h4r7-adult', 'H4 R7 Adult', 1, ?)",
            (ADULT_WORK_ID, time.time()))
        self.conn.execute(
            "INSERT INTO cat_links (id, release_id, work_id, ledger_link_id,"
            " service, kind, url, final_url, playability, state)"
            " VALUES (?, 1, ?, ?, 'pixeldrain', 'file',"
            " 'https://pixeldrain.com/api/file/s2yMcLkb',"
            " 'https://pixeldrain.com/api/file/s2yMcLkb', 'stream', 'active')",
            (CAT_ID, WORK_ID, LEDGER_ID))
        self.conn.execute(
            "INSERT INTO cat_links (id, release_id, work_id, ledger_link_id,"
            " service, kind, url, final_url, playability, state)"
            " VALUES (?, 2, ?, ?, 'pixeldrain', 'file',"
            " 'https://pixeldrain.com/api/file/adult',"
            " 'https://pixeldrain.com/api/file/adult', 'stream', 'active')",
            (ADULT_CAT_ID, ADULT_WORK_ID, ADULT_LEDGER_ID))

    # ---- harness ----------------------------------------------------------
    def handle(self, ledger_id):
        return redact.mint_token(int(ledger_id), self.secret)

    def post(self, path, body=None, **kwargs):
        payload = json.dumps(body or {}, ensure_ascii=False).encode("utf-8")
        handler = FakeHandler({"Content-Length": str(len(payload))}, payload)
        answered = api_v2.route_post(handler, path, {}, db_path=self.db_path,
                                     operator=False, base_url=HOST,
                                     secret=self.secret)
        self.assertTrue(answered, f"api_v2 did not claim {path}")
        self.assertEqual(len(handler.replies), 1,
                         f"POST {path} wrote {len(handler.replies)} replies")
        return Reply(handler, path)

    def bank(self, ledger_id, *, now=None):
        now = time.time() if now is None else now
        resolved_cache.record_resolved_cache(
            self.conn, int(ledger_id),
            {"url": "https://pixeldrain.com/api/file/s2yMcLkb",
             "mime_type": "video/mp4", "service": "pixeldrain",
             "expires_in": 3600.0},
            now=now)
        self.conn.commit()
        return now

    def lookup(self, ledger_id, *, now=None):
        return resolved_cache.lookup_cache(
            self.conn, int(ledger_id), want="watch",
            now=time.time() if now is None else now)

    def fail_count(self, ledger_id):
        row = self.conn.execute(
            "SELECT fail_count FROM resolved_cache WHERE link_id = ?",
            (int(ledger_id),)).fetchone()
        return int(row[0]) if row is not None else 0

    def negative_record(self, ledger_id):
        return self.conn.execute(
            "SELECT last_failed_at, failure_class, failure_resolver_v"
            " FROM download_sources WHERE id = ?", (int(ledger_id),)).fetchone()

    def report_rows(self, *, link_id=None):
        sql = "SELECT COUNT(*) FROM client_link_reports"
        params: list = []
        if link_id is not None:
            sql += " WHERE link_id = ?"
            params.append(int(link_id))
        return int(self.conn.execute(sql, params).fetchone()[0])

    def evidence_rows(self, *, link_id=None, ok=None):
        sql = "SELECT COUNT(*) FROM cat_link_evidence"
        clauses, params = [], []
        if link_id is not None:
            clauses.append("link_id = ?")
            params.append(int(link_id))
        if ok is not None:
            clauses.append("ok = ?")
            params.append(int(ok))
        if clauses:
            sql += " WHERE " + " AND ".join(clauses)
        return int(self.conn.execute(sql, params).fetchone()[0])


class DeadHandleReportTests(PerSegmentRetryFixture):
    def test_dead_handle_report_strikes_both_ledgers_and_retires_the_banked_row(self):
        """One dead-handle report retires the banked winner at once: the next
        press misses the cache and re-resolves onto the next candidate."""
        now = self.bank(LEDGER_ID)
        self.assertIsNotNone(self.lookup(LEDGER_ID, now=now))
        self.assertTrue(api_v2._release_has_unfailed_candidate(self.conn, 1),
                        "fixture row must count as unfailed before the report")
        reply = self.post(
            f"/api/v2/links/{self.handle(LEDGER_ID)}/report",
            {"event": "failed", "failure_class": "dead_handle", "ms": 900})
        self.assertEqual(reply.status, 200)
        self.assertTrue(reply.json.get("recorded"))
        # Attribution lands on the PROJECTED row (cat id), not the ledger id.
        self.assertEqual(self.report_rows(link_id=CAT_ID), 1)
        self.assertEqual(self.report_rows(link_id=LEDGER_ID), 0)
        self.assertEqual(self.evidence_rows(link_id=CAT_ID, ok=0), 1)
        # Both failure ledgers struck.
        self.assertEqual(self.fail_count(LEDGER_ID), 1)
        failed_at, failure_class, failure_v = self.negative_record(LEDGER_ID)
        self.assertIsNotNone(failed_at, "dead_handle must write last_failed_at")
        self.assertEqual(failure_class, "probe_failed")
        self.assertEqual(failure_v, fast_fetch.RESOLVER_VERSION)
        # The banked row is refused on the very next lookup — not after 3 strikes.
        self.assertIsNone(self.lookup(LEDGER_ID, now=now),
                          "one dead-handle report must retire the banked row")
        # And the /play walk no longer counts this print as unfailed.
        self.assertFalse(api_v2._release_has_unfailed_candidate(self.conn, 1))

    def test_other_failure_classes_record_evidence_but_strike_nothing(self):
        """network / decode / gate / rate_limited: the player tried and the link
        failed, but none of them is evidence ABOUT the link — so neither ledger
        moves and the banked row keeps serving."""
        for failure_class in ("network", "decode", "gate", "rate_limited"):
            with self.subTest(failure_class=failure_class):
                self.conn.execute("DELETE FROM resolved_cache")
                self.conn.execute(
                    "UPDATE download_sources SET last_failed_at = NULL,"
                    " failure_class = NULL, failure_resolver_v = NULL"
                    " WHERE id = ?", (LEDGER_ID,))
                self.conn.commit()
                now = self.bank(LEDGER_ID)
                evidence_before = self.evidence_rows(link_id=CAT_ID, ok=0)
                reply = self.post(
                    f"/api/v2/links/{self.handle(LEDGER_ID)}/report",
                    {"event": "failed", "failure_class": failure_class,
                     "ms": 900})
                self.assertEqual(reply.status, 200)
                self.assertEqual(self.evidence_rows(link_id=CAT_ID, ok=0),
                                 evidence_before + 1)
                # The §6 fix 2 circuit still strikes once (today's contract for
                # any "failed"), but the negative cache — the one that retires
                # the row at once — is untouched.
                self.assertEqual(self.fail_count(LEDGER_ID), 1)
                failed_at, _, _ = self.negative_record(LEDGER_ID)
                self.assertIsNone(failed_at,
                                  f"{failure_class} must not write last_failed_at")
                self.assertIsNotNone(self.lookup(LEDGER_ID, now=now),
                                     f"{failure_class} must not retire the row")

    def test_unknown_failure_class_is_treated_as_no_class(self):
        now = self.bank(LEDGER_ID)
        reply = self.post(
            f"/api/v2/links/{self.handle(LEDGER_ID)}/report",
            {"event": "failed", "failure_class": "bogus", "ms": 900})
        self.assertEqual(reply.status, 200)
        self.assertEqual(self.fail_count(LEDGER_ID), 1)
        failed_at, _, _ = self.negative_record(LEDGER_ID)
        self.assertIsNone(failed_at)
        self.assertIsNotNone(self.lookup(LEDGER_ID, now=now))

    def test_three_plain_failed_reports_still_retire_the_row(self):
        """The old contract, re-proven through the ledger handle: three
        class-less "failed" reports close the circuit."""
        now = self.bank(LEDGER_ID)
        for _ in range(resolved_cache.MAX_FAIL_COUNT):
            reply = self.post(
                f"/api/v2/links/{self.handle(LEDGER_ID)}/report",
                {"event": "failed", "ms": 900})
            self.assertEqual(reply.status, 200)
        self.assertIsNone(self.lookup(LEDGER_ID, now=now))

    def test_played_report_never_strikes(self):
        now = self.bank(LEDGER_ID)
        reply = self.post(
            f"/api/v2/links/{self.handle(LEDGER_ID)}/report",
            {"event": "played", "ms": 1200})
        self.assertEqual(reply.status, 200)
        self.assertEqual(self.fail_count(LEDGER_ID), 0)
        failed_at, _, _ = self.negative_record(LEDGER_ID)
        self.assertIsNone(failed_at)
        self.assertIsNotNone(self.lookup(LEDGER_ID, now=now))


class ReportUniverseTests(PerSegmentRetryFixture):
    def test_unknown_ledger_handle_404s_and_writes_nothing(self):
        reply = self.post(
            "/api/v2/links/%s/report" % self.handle(9999),
            {"event": "failed", "failure_class": "dead_handle", "ms": 900})
        self.assertEqual(reply.status, 404)
        self.assertEqual(reply.code, "not_found")
        self.assertEqual(self.report_rows(), 0)
        self.assertEqual(self.evidence_rows(), 0)
        failed_at, _, _ = self.negative_record(LEDGER_ID)
        self.assertIsNone(failed_at)

    def test_adult_work_report_404s_and_writes_nothing(self):
        """A flagged work answers exactly like a missing link — and writes
        nothing anywhere, not even the circuit."""
        now = self.bank(ADULT_LEDGER_ID)
        reply = self.post(
            f"/api/v2/links/{self.handle(ADULT_LEDGER_ID)}/report",
            {"event": "failed", "failure_class": "dead_handle", "ms": 900})
        self.assertEqual(reply.status, 404)
        self.assertEqual(reply.code, "not_found")
        self.assertEqual(self.report_rows(), 0)
        failed_at, _, _ = self.negative_record(ADULT_LEDGER_ID)
        self.assertIsNone(failed_at)
        self.assertEqual(self.fail_count(ADULT_LEDGER_ID), 0)
        self.assertIsNotNone(self.lookup(ADULT_LEDGER_ID, now=now))


class LookupNegativeCacheTests(PerSegmentRetryFixture):
    def test_fresh_negative_record_refuses_the_banked_row(self):
        now = self.bank(LEDGER_ID)
        fast_fetch._record_resolve_failure_on(self.conn, LEDGER_ID, "probe_failed")
        self.conn.commit()
        self.assertIsNone(self.lookup(LEDGER_ID, now=now))

    def test_stale_negative_record_serves_again(self):
        now = self.bank(LEDGER_ID)
        stale_at = now - float(fast_fetch.NEGATIVE_CACHE_TTL_SECONDS) - 1
        self.conn.execute(
            "UPDATE download_sources SET last_failed_at = ?,"
            " failure_class = 'probe_failed', failure_resolver_v = ?"
            " WHERE id = ?", (stale_at, fast_fetch.RESOLVER_VERSION, LEDGER_ID))
        self.conn.commit()
        self.assertIsNotNone(self.lookup(LEDGER_ID, now=now))

    def test_old_version_negative_record_serves_again(self):
        """A record from an older resolver is stale the moment the version
        moves — a new unwrapper may have landed since."""
        now = self.bank(LEDGER_ID)
        self.conn.execute(
            "UPDATE download_sources SET last_failed_at = ?,"
            " failure_class = 'probe_failed', failure_resolver_v = ?"
            " WHERE id = ?",
            (now, fast_fetch.RESOLVER_VERSION - 1, LEDGER_ID))
        self.conn.commit()
        self.assertIsNotNone(self.lookup(LEDGER_ID, now=now))

    def test_null_version_negative_record_serves_again(self):
        """NULL version counts as 0, exactly as the /play walk reads it."""
        now = self.bank(LEDGER_ID)
        self.conn.execute(
            "UPDATE download_sources SET last_failed_at = ?,"
            " failure_class = 'probe_failed', failure_resolver_v = NULL"
            " WHERE id = ?", (now, LEDGER_ID))
        self.conn.commit()
        self.assertIsNotNone(self.lookup(LEDGER_ID, now=now))

    def test_no_ledger_table_serves_as_before(self):
        """Fail-open: a bare-cache DB with no download_sources table serves
        the banked row exactly as it did before H4 R7."""
        conn = sqlite3.connect(":memory:")
        conn.row_factory = sqlite3.Row
        resolved_cache.init_resolved_cache(conn)
        now = time.time()
        resolved_cache.record_resolved_cache(
            conn, LEDGER_ID,
            {"url": "https://pixeldrain.com/api/file/s2yMcLkb",
             "mime_type": "video/mp4", "service": "pixeldrain",
             "expires_in": 3600.0},
            now=now)
        conn.commit()
        self.assertIsNotNone(
            resolved_cache.lookup_cache(conn, LEDGER_ID, want="watch", now=now))
        conn.close()

    def test_negative_cache_terms_match_fast_fetch(self):
        self.assertEqual(
            resolved_cache._negative_cache_terms(),
            (float(fast_fetch.NEGATIVE_CACHE_TTL_SECONDS),
             int(fast_fetch.RESOLVER_VERSION)))


if __name__ == "__main__":
    unittest.main(verbosity=2)
