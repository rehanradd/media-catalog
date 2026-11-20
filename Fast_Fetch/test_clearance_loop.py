#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline tests for the clearance re-earning loop (Phase C gdflix).

The audit behind this slice: the gdflix resolver is built and wired to
the H4 R8 clearance store, but the store is empty — ``new10.gdflix.io``
answers the Oracle egress with a Cloudflare managed challenge, and a
one-off placement buys 15 minutes at most. What this pins:

* the loop skips a host with a fresh clearance, attempts one whose
  remaining life is under the re-earn lead, and never attempts inside
  a backoff window;
* a clean 200 that sets a clearance cookie earns (the cookie lands in
  the store, the next attempt is scheduled before expiry); a managed
  challenge backs off from 300s (not 60s); errors back off 60s first
  and grow to the 1h cap;
* hosts are isolated (a clearance for new10 does not satisfy new3);
* ``request_reearn`` marks a host due without network I/O, never
  moves a backoff window earlier, and never raises;
* the external earner plugs a real solver in (its cookies land via
  ``place_clearance``) and its failures are plain errors — and no
  cookie value ever appears in a summary, a detail, or a log line;
* the earn UA is the resolver's UA (a clearance is bound to IP + UA +
  hostname, so a different UA would earn a cookie the resolver cannot
  replay);
* the ``--once`` CLI exits 0 even when every earn fails (a failed earn
  is data, not a process failure).

No network, no production DB: every test runs against an in-memory
SQLite fixture and a stubbed opener. The stdlib earner is an attempt,
not a solver — these tests pin the attempt, the schedule, and the
backoff, never a solved challenge.
"""

from __future__ import annotations

import io
import json
import os
import sqlite3
import sys
import time
import unittest
import urllib.error
from contextlib import redirect_stdout
from unittest import mock

os.environ.setdefault("RADD_SKIP_LIVE_ENRICH", "1")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import clearance_loop as cl  # noqa: E402
import fast_fetch  # noqa: E402
import host_clearance as hc  # noqa: E402

HOST = "new10.gdflix.io"
OTHER = "new3.gdflix.io"
NOW = 1_800_000_000.0
UA = cl.EARN_USER_AGENT


class _FakeHeaders(dict):
    """Minimal headers object: ``get_all`` for Set-Cookie, ``get`` else."""

    def get_all(self, name):
        if name.lower() == "set-cookie":
            return self.get("_set_cookie", [])
        return []


class _FakeResponse:
    def __init__(self, status=200, url="", headers=None, body=b""):
        self.status = status
        self._url = url
        self.headers = headers or _FakeHeaders()
        self._body = body

    def geturl(self):
        return self._url

    def read(self, n=-1):
        return self._body if n < 0 else self._body[:n]


class _FakeHTTPError(urllib.error.HTTPError):
    """A stand-in HTTPError (code/headers/read/geturl) for the earner."""

    def __init__(self, code, url="", headers=None, body=b""):
        super().__init__(url, code, f"HTTP Error {code}", headers,
                         io.BytesIO(body))
        self._body = body
        self._url = url

    def read(self, n=-1):
        return self._body if n < 0 else self._body[:n]

    def geturl(self):
        return self._url


def _challenge_body():
    return (b"<html><head><title>Just a moment...</title></head><body>"
            b"challenge-platform _cf_chl_opt cType: 'managed' "
            b"checking your browser</body></html>")


def _opener_for(responses):
    """An opener returning the queued responses (or raising them)."""
    calls = []

    def opener(req, timeout=None, **kwargs):
        calls.append(req)
        item = responses.pop(0)
        if isinstance(item, Exception):
            raise item
        return item

    opener.calls = calls
    return opener


class ClearanceLoopTestBase(unittest.TestCase):
    def setUp(self):
        self.conn = sqlite3.connect(":memory:")
        cl._ensure_schema(self.conn)
        self.conn.commit()
        self.loop = cl.ClearanceLoop(conn=self.conn)

    def tearDown(self):
        self.conn.close()

    def place(self, host, name="cf_clearance", value="tok-1",
              obtained_at=NOW, ttl=900, ua=UA):
        self.assertTrue(hc.place_clearance(
            self.conn, host, name, value, user_agent=ua, ttl_s=ttl,
            now=obtained_at))
        self.conn.commit()

    def attempts_row(self, host):
        return self.conn.execute(
            "SELECT last_attempt_at, last_outcome, last_detail,"
            " consecutive_failures, next_attempt_at, last_earned_at,"
            " earned_count FROM clearance_loop_attempts WHERE host = ?",
            (host,)).fetchone()


class SkipAndDueTests(ClearanceLoopTestBase):
    def test_skip_when_fresh_clearance_stored(self):
        self.place(HOST, obtained_at=NOW)
        opener = _opener_for([_FakeResponse(200, "https://" + HOST + "/")])
        summaries = self.loop.run_once([HOST], opener=opener, now=NOW)
        self.assertEqual(len(opener.calls), 0)
        self.assertEqual(summaries[0]["outcome"], "skipped-fresh")
        self.assertIsNone(self.attempts_row(HOST))

    def test_attempt_when_remaining_life_under_lead(self):
        # Obtained 750s ago: 150s of effective life left, under the 180s lead.
        self.place(HOST, obtained_at=NOW - 750)
        resp = _FakeResponse(
            200, "https://" + HOST + "/",
            headers=_FakeHeaders({"_set_cookie": [
                "cf_clearance=fresh-tok; Expires=Wed, 01 Jan 2031 00:00:00 GMT; Path=/"]}),
            body=b"<html>ok</html>")
        opener = _opener_for([resp])
        summaries = self.loop.run_once([HOST], opener=opener, now=NOW)
        self.assertEqual(len(opener.calls), 1)
        self.assertEqual(summaries[0]["outcome"], "earned")

    def test_no_attempt_while_backoff_runs(self):
        self.conn.execute(
            "INSERT INTO clearance_loop_attempts (host, last_attempt_at,"
            " last_outcome, consecutive_failures, next_attempt_at)"
            " VALUES (?, ?, 'challenge', 1, ?)",
            (HOST, NOW - 10, NOW + 600))
        self.conn.commit()
        opener = _opener_for([_FakeResponse(200, "https://" + HOST + "/")])
        summaries = self.loop.run_once([HOST], opener=opener, now=NOW)
        self.assertEqual(len(opener.calls), 0)
        self.assertEqual(summaries[0]["outcome"], "skipped-backoff")

    def test_exact_hostname_isolation(self):
        # A clearance for new10 does not satisfy new3: only new10 skips.
        self.place(HOST, obtained_at=NOW)
        opener = _opener_for([_FakeResponse(
            200, "https://" + OTHER + "/",
            headers=_FakeHeaders({"_set_cookie": [
                "cf_clearance=other-tok; Path=/"]}),
            body=b"<html>ok</html>")])
        summaries = self.loop.run_once([OTHER, HOST], opener=opener, now=NOW)
        by_host = {s["host"]: s["outcome"] for s in summaries}
        self.assertEqual(by_host[HOST], "skipped-fresh")
        self.assertEqual(by_host[OTHER], "earned")
        self.assertEqual(len(opener.calls), 1)


class EarnTests(ClearanceLoopTestBase):
    def test_earn_on_clean_200_with_clearance_cookie(self):
        resp = _FakeResponse(
            200, "https://" + HOST + "/",
            headers=_FakeHeaders({"_set_cookie": [
                "cf_clearance=earned-tok; Expires=Wed, 01 Jan 2031 00:00:00 GMT; Path=/",
                "__cf_bm=bm-tok; Path=/"]}),
            body=b"<html>ok</html>")
        summaries = self.loop.run_once(
            [HOST], opener=_opener_for([resp]), now=NOW)
        self.assertEqual(summaries[0]["outcome"], "earned")
        self.assertEqual(summaries[0]["earned_host"], HOST)
        header, ua = hc.cookie_header_for(self.conn, HOST, now=NOW)
        self.assertIn("cf_clearance=earned-tok", header)
        self.assertIn("__cf_bm=bm-tok", header)
        self.assertEqual(ua, UA)
        row = self.attempts_row(HOST)
        self.assertEqual(row[1], "earned")
        self.assertEqual(row[3], 0)  # consecutive_failures reset
        self.assertEqual(row[6], 1)  # earned_count
        # Next attempt is scheduled before expiry: obtained+900-180.
        self.assertEqual(row[4], NOW + 900 - cl.REEARN_LEAD_S)

    def test_clean_200_without_cookie_is_error_not_earned(self):
        resp = _FakeResponse(200, "https://" + HOST + "/",
                             body=b"<html>ok</html>")
        summaries = self.loop.run_once(
            [HOST], opener=_opener_for([resp]), now=NOW)
        self.assertEqual(summaries[0]["outcome"], "error")
        header, _ua = hc.cookie_header_for(self.conn, HOST, now=NOW)
        self.assertIsNone(header)
        row = self.attempts_row(HOST)
        self.assertEqual(row[3], 1)
        self.assertEqual(row[4], NOW + 60)  # first error backs off 60s

    def test_challenge_backs_off_from_300s(self):
        err = _FakeHTTPError(403, "https://" + HOST + "/",
                             body=_challenge_body())
        summaries = self.loop.run_once(
            [HOST], opener=_opener_for([err]), now=NOW)
        self.assertEqual(summaries[0]["outcome"], "challenge")
        self.assertIn("managed challenge", summaries[0]["detail"])
        header, _ua = hc.cookie_header_for(self.conn, HOST, now=NOW)
        self.assertIsNone(header)
        row = self.attempts_row(HOST)
        self.assertEqual(row[1], "challenge")
        self.assertEqual(row[3], 1)
        self.assertEqual(row[4], NOW + 300)

    def test_bare_403_without_challenge_signal_is_blocked(self):
        err = _FakeHTTPError(403, "https://" + HOST + "/",
                             body=b"<html>Forbidden</html>")
        summaries = self.loop.run_once(
            [HOST], opener=_opener_for([err]), now=NOW)
        self.assertEqual(summaries[0]["outcome"], "blocked")
        row = self.attempts_row(HOST)
        self.assertEqual(row[4], NOW + 60)  # blocked starts at the error floor

    def test_backoff_grows_and_caps_at_one_hour(self):
        # Drive consecutive failures through the recorder: error, error,
        # challenge, challenge, error — delays 60, 300, 900, 3600, 3600.
        t = NOW
        for outcome, want in zip(
                ("error", "error", "challenge", "challenge", "error"),
                (60, 300, 900, 3600, 3600)):
            result = cl.EarnResult(outcome, HOST, detail="t")
            self.loop._record_attempt(self.conn, HOST, result, now=t)
            row = self.attempts_row(HOST)
            self.assertEqual(row[4] - t, want)
            t = row[4]

    def test_earn_follows_redirect_and_files_under_serving_host(self):
        # The entry host 302s to the live front: the cookie is filed
        # under the host that served it, and the outcome names it.
        resp = _FakeResponse(
            200, "https://" + HOST + "/",
            headers=_FakeHeaders({"_set_cookie": [
                "cf_clearance=via-redirect; Path=/"]}),
            body=b"<html>ok</html>")
        summaries = self.loop.run_once(
            [OTHER], opener=_opener_for([resp]), now=NOW)
        self.assertEqual(summaries[0]["outcome"], "earned")
        self.assertEqual(summaries[0]["earned_host"], HOST)
        header, _ua = hc.cookie_header_for(self.conn, HOST, now=NOW)
        self.assertIn("cf_clearance=via-redirect", header)


class RequestReearnTests(ClearanceLoopTestBase):
    def test_marks_due_without_network(self):
        self.assertTrue(cl.request_reearn(self.conn, "https://" + HOST + "/file/x"))
        row = self.attempts_row(HOST)
        self.assertIsNotNone(row)
        self.assertLessEqual(row[4], time.time())
        # ... and the next pass really attempts.
        opener = _opener_for([_FakeResponse(
            200, "https://" + HOST + "/",
            headers=_FakeHeaders({"_set_cookie": ["cf_clearance=t; Path=/"]}),
            body=b"ok")])
        summaries = self.loop.run_once([HOST], opener=opener, now=NOW)
        self.assertEqual(summaries[0]["outcome"], "earned")

    def test_unconfigured_host_is_ignored(self):
        self.assertFalse(cl.request_reearn(self.conn, "https://example.com/x"))
        self.assertIsNone(self.attempts_row("example.com"))

    def test_never_moves_a_backoff_window_earlier(self):
        self.conn.execute(
            "INSERT INTO clearance_loop_attempts (host, last_attempt_at,"
            " last_outcome, consecutive_failures, next_attempt_at)"
            " VALUES (?, ?, 'challenge', 2, ?)",
            (HOST, NOW - 10, NOW + 900))
        self.conn.commit()
        self.assertFalse(cl.request_reearn(self.conn, HOST, now=NOW))
        row = self.attempts_row(HOST)
        self.assertEqual(row[4], NOW + 900)

    def test_noop_when_fresh_clearance_stored(self):
        self.place(HOST, obtained_at=NOW)
        self.assertFalse(cl.request_reearn(self.conn, HOST, now=NOW))
        self.assertIsNone(self.attempts_row(HOST))

    def test_never_raises_on_a_broken_store(self):
        bad = mock.Mock()
        bad.execute.side_effect = sqlite3.OperationalError("boom")
        self.assertFalse(cl.request_reearn(bad, HOST, now=NOW))
        loop = cl.ClearanceLoop(conn=bad)
        self.assertEqual(loop.run_once([HOST], now=NOW)[0]["outcome"], "error")


class ExternalEarnerTests(ClearanceLoopTestBase):
    def test_external_earner_places_its_cookie(self):
        cmd = [sys.executable, "-c",
               "print('cf_clearance=solved-tok')"]
        summaries = self.loop.run_once([HOST], earner_cmd=cmd, now=NOW)
        self.assertEqual(summaries[0]["outcome"], "earned")
        self.assertEqual(summaries[0]["earned_host"], HOST)
        header, ua = hc.cookie_header_for(self.conn, HOST, now=NOW)
        self.assertIn("cf_clearance=solved-tok", header)
        self.assertEqual(ua, UA)
        self.assertNotIn("solved-tok", summaries[0]["detail"])

    def test_external_earner_failure_is_plain_error(self):
        cmd = [sys.executable, "-c", "import sys; sys.exit(3)"]
        summaries = self.loop.run_once([HOST], earner_cmd=cmd, now=NOW)
        self.assertEqual(summaries[0]["outcome"], "error")
        self.assertIn("exit 3", summaries[0]["detail"])
        header, _ua = hc.cookie_header_for(self.conn, HOST, now=NOW)
        self.assertIsNone(header)

    def test_external_earner_cannot_place_a_session_cookie(self):
        # place_clearance is the only path that accepts other names — but
        # the loop only ever asks it for clearance names: a solver that
        # prints a session id must not land it in the store.
        cmd = [sys.executable, "-c",
               "print('cf_clearance=ok-tok')\nprint('sessionid=abc')"]
        summaries = self.loop.run_once([HOST], earner_cmd=cmd, now=NOW)
        self.assertEqual(summaries[0]["outcome"], "earned")
        header, _ua = hc.cookie_header_for(self.conn, HOST, now=NOW)
        self.assertIn("cf_clearance=ok-tok", header)
        self.assertNotIn("sessionid", header or "")


class IdentityAndLeakTests(ClearanceLoopTestBase):
    def test_earn_user_agent_matches_resolver(self):
        self.assertEqual(cl.EARN_USER_AGENT, fast_fetch.USER_AGENT)

    def test_no_cookie_value_in_any_summary_or_detail(self):
        secret = "tok-VERY-SECRET-123"
        resp = _FakeResponse(
            200, "https://" + HOST + "/",
            headers=_FakeHeaders({"_set_cookie": [
                f"cf_clearance={secret}; Path=/"]}),
            body=b"<html>ok</html>")
        summaries = self.loop.run_once(
            [HOST], opener=_opener_for([resp]), now=NOW)
        blob = json.dumps(summaries)
        self.assertNotIn(secret, blob)
        header, _ua = hc.cookie_header_for(self.conn, HOST, now=NOW)
        self.assertIn(secret, header)  # the store holds it; the log must not
        row = self.attempts_row(HOST)
        self.assertNotIn(secret, (row[2] or ""))


class CliTests(ClearanceLoopTestBase):
    def test_once_exits_zero_when_every_earn_fails(self):
        err = _FakeHTTPError(403, "https://" + HOST + "/",
                             body=_challenge_body())
        opener = _opener_for([err])
        out = io.StringIO()
        # --db :memory: keeps the CLI hermetic: no file is created, and
        # the exit code is judged from the attempt, not from persistence.
        with redirect_stdout(out):
            code = cl.main(["--once", "--db", ":memory:", "--host", HOST],
                           opener=opener)
        self.assertEqual(code, 0)
        line = json.loads(out.getvalue().strip().splitlines()[-1])
        self.assertEqual(line["host"], HOST)
        self.assertEqual(line["outcome"], "challenge")


class ResolveHookTests(ClearanceLoopTestBase):
    def test_unwrap_failure_marks_host_due(self):
        # The resolve path's trigger: a failed file-page fetch asks the
        # loop to re-earn, without doing network I/O itself.
        import tempfile
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as tf:
            db_path = tf.name
        try:
            store = hc.HostClearance(db_path)
            with mock.patch.object(fast_fetch, "http_get",
                                   return_value=(0, "https://" + HOST + "/file/x",
                                                 "ERROR: boom")):
                with mock.patch.object(fast_fetch, "DB_PATH", db_path):
                    url, _res_id, temp = fast_fetch.unwrap_gdflix(
                        "https://" + HOST + "/file/abc123", clearance=store)
            self.assertEqual(url, "https://" + HOST + "/file/abc123")
            self.assertEqual(temp, 0)
            loop = cl.ClearanceLoop(db_path=db_path)
            self.assertTrue(loop.due(HOST, now=NOW))
        finally:
            try:
                os.unlink(db_path)
            except OSError:
                pass

    def test_unwrap_challenge_page_marks_host_due(self):
        import tempfile
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as tf:
            db_path = tf.name
        try:
            store = hc.HostClearance(db_path)
            with mock.patch.object(fast_fetch, "http_get",
                                   return_value=(200, "https://" + HOST + "/file/x",
                                                 _challenge_body().decode("utf-8"))):
                with mock.patch.object(fast_fetch, "DB_PATH", db_path):
                    url, _res_id, temp = fast_fetch.unwrap_gdflix(
                        "https://" + HOST + "/file/abc123", clearance=store)
            self.assertEqual(temp, 0)
            loop = cl.ClearanceLoop(db_path=db_path)
            self.assertTrue(loop.due(HOST, now=NOW))
        finally:
            try:
                os.unlink(db_path)
            except OSError:
                pass

    def test_unwrap_clean_no_button_page_does_not_mark_due(self):
        import tempfile
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as tf:
            db_path = tf.name
        try:
            store = hc.HostClearance(db_path)
            page = "<html><head><title>Some File</title></head><body></body></html>"
            with mock.patch.object(fast_fetch, "http_get",
                                   return_value=(200, "https://" + HOST + "/file/x",
                                                 page)):
                with mock.patch.object(fast_fetch, "DB_PATH", db_path):
                    url, _res_id, temp = fast_fetch.unwrap_gdflix(
                        "https://" + HOST + "/file/abc123", clearance=store)
            self.assertEqual(temp, 0)
            # due() is True for a never-seen host BY DESIGN (no clearance
            # stored yet), so it cannot prove "not marked". The observable
            # mark is a row in clearance_loop_attempts: none must exist.
            check = sqlite3.connect(db_path)
            cl._ensure_schema(check)
            row = check.execute(
                "SELECT host FROM clearance_loop_attempts WHERE host = ?",
                (HOST,)).fetchone()
            check.close()
            self.assertIsNone(row)
        finally:
            try:
                os.unlink(db_path)
            except OSError:
                pass


if __name__ == "__main__":
    unittest.main(verbosity=2)
