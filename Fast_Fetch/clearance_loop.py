#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Clearance re-earning loop (Phase C gdflix, owner-approved 2026-11-20).

The gdflix resolver (``fast_fetch.unwrap_gdflix``) is built and wired to
the H4 R8 clearance store, but it cannot run from the Oracle VM egress:
``new10.gdflix.io`` answers with a Cloudflare managed challenge (403) to
every server-side request, and the store has nothing to replay. A one-off
``place_clearance`` buys one short window — the store's reuse ceiling is
15 minutes — so a durable crack needs a loop that earns a clearance and
re-earns it before it expires. This module is that loop.

What it can and cannot do, stated plainly (the Phase C research is
explicit, and this module does not pretend otherwise):

* Stdlib Python cannot execute the JavaScript a Cloudflare managed
  challenge demands. The built-in earner is therefore an *attempt*, not
  a solver: one browser-UA GET through the same capture path the
  resolver uses (``host_clearance.note_response``). When the edge
  answers clean (200/206) and sets a clearance cookie — a lenient
  edge, a challenge already passed for this IP+UA, a relaxed site
  config — the cookie is captured into the store and the loop records
  ``earned``. When the edge answers with a managed challenge, the
  attempt records ``challenge`` and backs off. It never hammers: one
  attempt per host per pass, backoff after every failure, a challenge
  earning a longer floor.
* A real clearance is earned by a real browser (or a FlareSolverr-style
  solver) on the Oracle egress. That half is the operator's install,
  not this module — but the loop is the plug for it: ``--earner-cmd``
  runs an operator-provided command that prints ``name=value`` lines
  for the cookies it solved, and the loop stores them through
  ``place_clearance`` (validated names/values, 15-minute TTL). With a
  solver plugged in, the loop keeps its clearance warm on schedule;
  without one, the loop keeps attempting cheaply and honestly reports
  that nothing was earned.
* ``--place`` is the manual half of the same story: solve once in a
  browser, place the cookie here, and the loop re-earns before it
  expires (via the earner) instead of letting it lapse.

How it is driven (the timer is the loop; the runner is ``--once`` only,
same shape as the link-sweep and prefetch units):

* systemd timer every 5 minutes runs ``clearance_loop.py --once``. A
  pass costs two sqlite reads per host when nothing is due, and one
  bounded GET per due host. The 15-minute ceiling plus the 3-minute
  re-earn lead means a pass every 5 minutes bounds the stale gap.
* ``--once`` is also the manual mode (run one pass, print one JSON
  line per host, exit 0 — a failed earn is data, not a process
  failure).
* The resolve path triggers a re-earn without blocking a press:
  ``fast_fetch.unwrap_gdflix`` calls :func:`request_reearn` when the
  file page answers with a challenge (or fails outright). That only
  marks the host due — it does no network I/O, never moves a backoff
  window earlier, and never raises — so a press costs one upsert, and
  the next timer pass does the earning.

State lives in the SAME database as the clearance store (the one that
holds ``resolved_cache``): ``clearance_loop_attempts`` holds one row
per host (last attempt, outcome, consecutive failures, next attempt,
last earn). Nothing here writes the live catalog, touches a service,
or restarts anything. Every store call never raises: on any failure
the caller proceeds without a cookie, exactly like ``host_clearance``.

Stdlib only. No network except the earn attempt itself.
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

try:
    import host_clearance as _hc
except ImportError:  # pragma: no cover - legacy tree without the store
    try:
        from Fast_Fetch import host_clearance as _hc  # type: ignore
    except ImportError:
        _hc = None  # type: ignore

__all__ = [
    "DEFAULT_HOSTS",
    "EARN_USER_AGENT",
    "REEARN_LEAD_S",
    "BACKOFF_S",
    "EARN_TIMEOUT_S",
    "OUTCOMES",
    "EarnResult",
    "ClearanceLoop",
    "request_reearn",
    "default_db_path",
    "main",
]

#: The live front for the gdflix family: every gdflix host 302s here, so
#: this is the hostname the challenge binds to and the clearance must be
#: earned under. Other clearance-blocked hosts join by config (``--host``
#: / ``RADD_CLEARANCE_LOOP_HOSTS``), never by editing this tuple.
DEFAULT_HOSTS = ("new10.gdflix.io",)
#: The User-Agent the clearance is earned under. It MUST stay identical
#: to ``fast_fetch.USER_AGENT``: the vendor binds a clearance to IP + UA
#: + hostname, and the resolver replays with the UA that earned it — a
#: different UA here would earn a cookie the resolver cannot replay.
#: Pinned by ``test_clearance_loop.py::test_earn_user_agent_matches_resolver``.
EARN_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)
#: Re-earn when the remaining effective life drops under 3 minutes, so a
#: clearance is refreshed before the resolver can observe it stale.
REEARN_LEAD_S = 180
#: Backoff after a failed attempt, indexed by consecutive failures and
#: capped at the last entry: 1m, 5m, 15m, 1h. A challenge starts at
#: index 1 (5m) — a managed challenge is a "do not come back soon"
#: signal, and a tight retry loop risks a longer block.
BACKOFF_S = (60, 300, 900, 3600)
#: One earn attempt's network budget: a single GET, no retries. The
#: attempt is never on the press path, but a challenge solve is not
#: going to arrive by waiting longer — 10s is the resolver's own
#: unwrap budget, and it bounds one pass to seconds per host.
EARN_TIMEOUT_S = 10
#: Attempt outcomes. ``skipped-*`` are not attempts (nothing was tried,
#: nothing is recorded); the rest are written to the attempts table.
OUTCOMES = (
    "earned", "challenge", "blocked", "error",
    "skipped-fresh", "skipped-backoff",
)
#: Detail strings are operator-visible (stdout, journal). They never
#: carry a cookie value — a value must not land in a log.
_DETAIL_NO_VALUE = "cookie values are never logged"

_ATTEMPTS_DDL = (
    """CREATE TABLE IF NOT EXISTS clearance_loop_attempts (
        host TEXT NOT NULL PRIMARY KEY,
        last_attempt_at REAL,
        last_outcome TEXT,
        last_detail TEXT,
        consecutive_failures INTEGER NOT NULL DEFAULT 0,
        next_attempt_at REAL,
        last_earned_at REAL,
        earned_count INTEGER NOT NULL DEFAULT 0
    )""",
)


# ── paths ────────────────────────────────────────────────────────────────────

def default_db_path():
    """The database file the loop works on: the server's catalog DB.

    ``RADD_DB_PATH`` wins (same variable ``config.py`` reads), then the
    config module's frozen ``DB_PATH``, then ``fast_fetch.db`` beside
    this file. Never raises.
    """
    env = os.environ.get("RADD_DB_PATH")
    if env:
        return env
    try:
        from config import DB_PATH  # noqa: PLC0415
        return str(DB_PATH)
    except Exception:
        pass
    return str(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "fast_fetch.db"))


# ── attempt state ────────────────────────────────────────────────────────────

def _ensure_schema(conn: sqlite3.Connection) -> None:
    conn.execute(_ATTEMPTS_DDL[0])
    if _hc is not None:
        _hc.init_host_clearance(conn)


def _fresh_until(conn, host: str, *, now: float) -> float | None:
    """The last instant the host's stored clearance may be sent, or None.

    The effective window is ``min(expires_at, obtained_at + 900)`` —
    the store's own ceiling (``CLEARANCE_MAX_AGE_S``), never the
    cookie's own Expires alone. Reads the newest row only: one UA per
    replay, so the newest row is the identity in force.
    """
    if _hc is None:
        return None
    host = _hc.normalize_host(host)
    if not host:
        return None
    try:
        _ensure_schema(conn)
        row = conn.execute(
            "SELECT obtained_at, expires_at FROM host_clearance"
            " WHERE host = ? ORDER BY obtained_at DESC LIMIT 1",
            (host,)).fetchone()
    except Exception:
        return None
    if row is None:
        return None
    obtained_at, expires_at = float(row[0]), row[1]
    ceiling = obtained_at + _hc.CLEARANCE_MAX_AGE_S
    if expires_at is None:
        return ceiling
    return min(float(expires_at), ceiling)


def _next_attempt_at(conn, host: str) -> float | None:
    """The attempts row's next-attempt instant, or None (never attempted)."""
    if _hc is None:
        return None
    host = _hc.normalize_host(host)
    if not host:
        return None
    try:
        _ensure_schema(conn)
        row = conn.execute(
            "SELECT next_attempt_at FROM clearance_loop_attempts"
            " WHERE host = ?",
            (host,)).fetchone()
    except Exception:
        return None
    if row is None or row[0] is None:
        return None
    return float(row[0])


def _backoff_delay(outcome: str, consecutive_failures: int) -> int:
    """Seconds to wait after a failed attempt.

    Errors start at 60s; a challenge starts at 300s (the vendor said
    no — coming back in a minute is hammering). Both grow to the 1h
    cap. ``consecutive_failures`` is the count AFTER this attempt.
    """
    idx = max(0, min(int(consecutive_failures) - 1, len(BACKOFF_S) - 1))
    if outcome == "challenge":
        idx = max(1, idx)
    return BACKOFF_S[idx]


class EarnResult:
    """One earn attempt's verdict. ``detail`` never carries a cookie value."""

    def __init__(self, outcome, host, earned_host=None, detail=""):
        self.outcome = outcome
        self.host = host
        self.earned_host = earned_host
        self.detail = detail

    def as_dict(self):
        return {
            "host": self.host,
            "outcome": self.outcome,
            "earned_host": self.earned_host,
            "detail": self.detail,
        }


# ── earners ──────────────────────────────────────────────────────────────────

def _browser_headers(user_agent: str) -> dict:
    """The headers the resolver sends (``fast_fetch.http_get``), so an
    earned clearance is earned under the identity the resolver replays."""
    return {
        "User-Agent": user_agent,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
    }


def _attempt_stdlib(conn, host: str, *, user_agent: str,
                    timeout: int = EARN_TIMEOUT_S, opener=None,
                    now: float | None = None) -> EarnResult:
    """One stdlib attempt to earn a clearance for ``host``.

    A browser-UA GET to ``https://<host>/`` (the root is the lightest
    challenge surface; the clearance binds to the hostname, not the
    path). A clean 200/206 that sets a clearance cookie is captured
    through ``note_response`` — the same path the resolver's own
    fetches use — and the attempt reports ``earned`` only when the
    store then holds a live row for the host that served it. A managed
    challenge reports ``challenge``; a bare 403/503 with no challenge
    signal reports ``blocked`` (it is a block of another kind, and the
    store must not treat it as a dead clearance); anything else is
    ``error``. Never raises; never logs a cookie value.
    """
    host = _hc.normalize_host(host) if _hc is not None else ""
    now = time.time() if now is None else float(now)
    if not host or _hc is None:
        return EarnResult("error", host, detail="unusable host or no store")
    url = "https://" + host + "/"
    req = urllib.request.Request(url, headers=_browser_headers(user_agent))
    open_url = opener or urllib.request.urlopen
    try:
        resp = open_url(req, timeout=timeout)
        status = int(getattr(resp, "status", 200) or 200)
        final_url = getattr(resp, "geturl", lambda: url)() or url
        headers = getattr(resp, "headers", {}) or {}
        try:
            body = resp.read(1_000_000)
        except Exception:
            body = b""
        if status not in (200, 206):
            return EarnResult("error", host,
                              detail=f"unexpected status {status}")
        _hc.note_response(conn, final_url, status=status, headers=headers,
                          body=body, user_agent=user_agent, now=now)
        served_host = _hc.normalize_host(final_url)
        until = _fresh_until(conn, served_host, now=now)
        if until is not None and now < until:
            return EarnResult("earned", host, earned_host=served_host,
                              detail="clean response set a clearance cookie")
        return EarnResult("error", host,
                          detail="clean response set no clearance cookie")
    except urllib.error.HTTPError as exc:
        try:
            body = exc.read(1_000_000)
        except Exception:
            body = b""
        try:
            err_url = exc.geturl()
        except Exception:
            err_url = None
        if _hc.is_challenge_response(exc.code,
                                     headers=getattr(exc, "headers", None),
                                     body=body):
            return EarnResult("challenge", host,
                              detail=f"managed challenge (HTTP {exc.code})")
        if exc.code in (403, 503):
            return EarnResult("blocked", host,
                              detail=f"HTTP {exc.code} without challenge signal")
        return EarnResult("error", host, detail=f"HTTP {exc.code}")
    except Exception as exc:  # noqa: BLE001 - attempt verdict, never raise
        return EarnResult("error", host,
                          detail=f"{type(exc).__name__}")


def _attempt_external(conn, host: str, *, earner_cmd, user_agent: str,
                      timeout: int = EARN_TIMEOUT_S,
                      now: float | None = None) -> EarnResult:
    """Earn through an operator-provided solver command.

    The command runs with the host as its final argument (and in
    ``CLEARANCE_LOOP_HOST``); it prints one ``name=value`` line per
    cookie it solved. Each line is stored through ``place_clearance``
    (which validates the name/value and the TTL), so a solver can never
    smuggle a session cookie or a malformed value into the store. A
    non-zero exit, a timeout, or no placeable line reports ``error``.
    The command runs without a shell. Never raises; never logs a value.
    """
    host = _hc.normalize_host(host) if _hc is not None else ""
    now = time.time() if now is None else float(now)
    if not host or _hc is None or not earner_cmd:
        return EarnResult("error", host, detail="no external earner configured")
    argv = list(earner_cmd) + [host]
    env = dict(os.environ)
    env["CLEARANCE_LOOP_HOST"] = host
    try:
        proc = subprocess.run(argv, capture_output=True, text=True,
                              timeout=timeout, env=env)
    except subprocess.TimeoutExpired:
        return EarnResult("error", host, detail="external earner timed out")
    except Exception as exc:  # noqa: BLE001 - attempt verdict, never raise
        return EarnResult("error", host,
                          detail=f"external earner failed: {type(exc).__name__}")
    placed = 0
    for line in (proc.stdout or "").splitlines():
        line = line.strip()
        if not line or "=" not in line or line.startswith("#"):
            continue
        name, value = line.split("=", 1)
        # The solver's output is untrusted machine output, not an
        # operator's deliberate placement: only clearance names may
        # land in the store (``place_clearance`` itself accepts any
        # name — that is the human ``--place`` path, not this one).
        # A solver that prints a session id must never store it.
        if name.strip() not in _hc.CLEARANCE_COOKIE_NAMES:
            continue
        try:
            if _hc.place_clearance(conn, host, name.strip(), value.strip(),
                                   user_agent=user_agent,
                                   ttl_s=_hc.CLEARANCE_MAX_AGE_S, now=now):
                placed += 1
        except Exception:
            continue
    if placed:
        return EarnResult("earned", host, earned_host=host,
                          detail=f"external earner placed {placed} cookie(s)")
    detail = f"external earner exit {proc.returncode}, no cookie placed"
    return EarnResult("error", host, detail=detail)


# ── the loop ─────────────────────────────────────────────────────────────────

class ClearanceLoop:
    """The re-earning loop bound to one database file (or one connection).

    ``db_path`` is the normal construction (short-lived connections per
    operation, one locked-retry, mirroring ``HostClearance``); ``conn``
    is the test seam (a single connection the caller owns — in-memory
    fixtures need it, since ``:memory:`` is per-connection). Every
    method never raises: on any failure it returns the empty result and
    the caller proceeds without a cookie.
    """

    def __init__(self, db_path=None, conn=None):
        if isinstance(db_path, (str, bytes, os.PathLike)):
            self._db_path = os.fspath(db_path) if isinstance(db_path, os.PathLike) else db_path
        else:
            # Never stringify a non-path (a Mock in tests, a caller bug)
            # into a filename: sqlite3.connect would happily create a
            # junk file literally named after it and the mark would
            # "succeed". No path means "no store" — _run then returns
            # the default.
            self._db_path = None
        self._conn = conn

    def _connect(self):
        conn = sqlite3.connect(self._db_path, timeout=5.0)
        try:
            conn.execute("PRAGMA busy_timeout = 5000")
        except sqlite3.Error:
            pass
        return conn

    def _run(self, fn, default, *args, **kwargs):
        if self._conn is not None:
            try:
                result = fn(self._conn, *args, **kwargs)
                self._conn.commit()
                return result
            except Exception:
                return default
        last = None
        for _attempt in range(2):
            conn = None
            try:
                conn = self._connect()
                result = fn(conn, *args, **kwargs)
                conn.commit()
                return result
            except sqlite3.OperationalError as exc:
                last = exc
                if "locked" not in str(exc).lower():
                    break
                time.sleep(0.05)
            except Exception as exc:  # noqa: BLE001 - never raise
                last = exc
                break
            finally:
                if conn is not None:
                    try:
                        conn.close()
                    except Exception:
                        pass
        return default

    # -- scheduling --

    def due(self, host: str, *, now: float | None = None) -> bool:
        """True when ``host`` needs an earn attempt right now.

        Not due when a fresh clearance is stored, and not due while a
        backoff window from a failed attempt is still running. Due when
        there is no clearance, when the remaining life is under the
        re-earn lead, or when the backoff has expired.
        """
        now = time.time() if now is None else float(now)
        host = _hc.normalize_host(host) if _hc is not None else ""
        if not host or _hc is None:
            return False

        def _due(conn):
            _ensure_schema(conn)
            until = _fresh_until(conn, host, now=now)
            if until is not None and now < until - REEARN_LEAD_S:
                return False
            nxt = _next_attempt_at(conn, host)
            if nxt is not None and now < nxt:
                return False
            return True

        return bool(self._run(_due, False))

    def status(self, host: str, *, now: float | None = None) -> dict:
        """One host's loop state (read-only report, no attempt)."""
        now = time.time() if now is None else float(now)
        host = _hc.normalize_host(host) if _hc is not None else ""

        def _status(conn):
            _ensure_schema(conn)
            until = _fresh_until(conn, host, now=now)
            row = conn.execute(
                "SELECT last_attempt_at, last_outcome, last_detail,"
                " consecutive_failures, next_attempt_at, last_earned_at,"
                " earned_count FROM clearance_loop_attempts WHERE host = ?",
                (host,)).fetchone()
            return {
                "host": host,
                "fresh": bool(until is not None and now < until),
                "fresh_until": until,
                "last_attempt_at": row[0] if row else None,
                "last_outcome": row[1] if row else None,
                "last_detail": row[2] if row else None,
                "consecutive_failures": row[3] if row else 0,
                "next_attempt_at": row[4] if row else None,
                "last_earned_at": row[5] if row else None,
                "earned_count": row[6] if row else 0,
            }

        if not host or _hc is None:
            return {"host": host, "fresh": False, "fresh_until": None,
                    "last_attempt_at": None, "last_outcome": None,
                    "last_detail": None, "consecutive_failures": 0,
                    "next_attempt_at": None, "last_earned_at": None,
                    "earned_count": 0}
        return self._run(_status, {"host": host, "fresh": False,
                                   "fresh_until": None, "last_attempt_at": None,
                                   "last_outcome": None, "last_detail": None,
                                   "consecutive_failures": 0,
                                   "next_attempt_at": None,
                                   "last_earned_at": None, "earned_count": 0})

    # -- attempts --

    def _record_attempt(self, conn, host: str, result: EarnResult,
                        *, now: float) -> None:
        """Write one attempt's outcome. Caller owns the connection."""
        _ensure_schema(conn)
        row = conn.execute(
            "SELECT consecutive_failures, earned_count"
            " FROM clearance_loop_attempts WHERE host = ?",
            (host,)).fetchone()
        prev_failures = int(row[0]) if row else 0
        prev_earned = int(row[1]) if row else 0
        if result.outcome == "earned":
            failures = 0
            earned_count = prev_earned + 1
            earned_at = now
            until = _fresh_until(conn, result.earned_host or host, now=now)
            nxt = (until - REEARN_LEAD_S) if until is not None else None
            if nxt is not None and nxt <= now:
                nxt = now + 60
        else:
            failures = prev_failures + 1
            earned_count = prev_earned
            earned_at = None
            nxt = now + _backoff_delay(result.outcome, failures)
        conn.execute(
            "INSERT INTO clearance_loop_attempts (host, last_attempt_at,"
            " last_outcome, last_detail, consecutive_failures,"
            " next_attempt_at, last_earned_at, earned_count)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            " ON CONFLICT(host) DO UPDATE SET"
            " last_attempt_at = excluded.last_attempt_at,"
            " last_outcome = excluded.last_outcome,"
            " last_detail = excluded.last_detail,"
            " consecutive_failures = excluded.consecutive_failures,"
            " next_attempt_at = excluded.next_attempt_at,"
            " last_earned_at = COALESCE(excluded.last_earned_at,"
            " clearance_loop_attempts.last_earned_at),"
            " earned_count = excluded.earned_count",
            (host, now, result.outcome, result.detail, failures, nxt,
             earned_at, earned_count))

    def attempt(self, host: str, *, earner_cmd=None, opener=None,
                now: float | None = None) -> EarnResult:
        """One earn attempt for ``host`` (no freshness/backoff check —
        the caller decides whether it is due). Records the outcome."""
        now = time.time() if now is None else float(now)
        host = _hc.normalize_host(host) if _hc is not None else ""
        if not host or _hc is None:
            return EarnResult("error", host, detail="unusable host or no store")

        def _attempt(conn):
            _ensure_schema(conn)
            if earner_cmd:
                result = _attempt_external(
                    conn, host, earner_cmd=earner_cmd,
                    user_agent=EARN_USER_AGENT, timeout=EARN_TIMEOUT_S, now=now)
            else:
                result = _attempt_stdlib(
                    conn, host, user_agent=EARN_USER_AGENT,
                    timeout=EARN_TIMEOUT_S, opener=opener, now=now)
            self._record_attempt(conn, host, result, now=now)
            return result

        return self._run(_attempt,
                         EarnResult("error", host, detail="store unavailable"))

    def run_once(self, hosts, *, earner_cmd=None, opener=None,
                 now: float | None = None) -> list:
        """One pass over ``hosts``: attempt each due host once, skip the
        rest, and return one summary dict per host. Never raises."""
        now = time.time() if now is None else float(now)
        seen = []
        for raw in hosts or []:
            host = _hc.normalize_host(raw) if _hc is not None else ""
            if host and host not in seen:
                seen.append(host)
        if _hc is None or not self._run(
                lambda conn: (_ensure_schema(conn), True)[1], False):
            return [{"host": host, "outcome": "error", "earned_host": None,
                     "detail": "store unavailable", "next_attempt_at": None,
                     "fresh_until": None} for host in seen]
        summaries = []
        for host in seen:
            try:
                if not self.due(host, now=now):
                    st = self.status(host, now=now)
                    outcome = ("skipped-fresh" if st["fresh"]
                               else "skipped-backoff")
                    summaries.append({
                        "host": host, "outcome": outcome,
                        "earned_host": None,
                        "detail": ("fresh clearance stored"
                                   if outcome == "skipped-fresh"
                                   else "backoff window still running"),
                        "next_attempt_at": st["next_attempt_at"],
                        "fresh_until": st["fresh_until"],
                    })
                    continue
                result = self.attempt(host, earner_cmd=earner_cmd,
                                      opener=opener, now=now)
                st = self.status(host, now=now)
                summaries.append({
                    "host": host, "outcome": result.outcome,
                    "earned_host": result.earned_host,
                    "detail": result.detail,
                    "next_attempt_at": st["next_attempt_at"],
                    "fresh_until": st["fresh_until"],
                })
            except Exception as exc:  # noqa: BLE001 - one host never kills a pass
                summaries.append({
                    "host": host, "outcome": "error", "earned_host": None,
                    "detail": f"{type(exc).__name__}",
                    "next_attempt_at": None, "fresh_until": None,
                })
        return summaries


# ── resolve-path trigger ─────────────────────────────────────────────────────

def request_reearn(db_path_or_conn, host_or_url, *,
                   hosts=DEFAULT_HOSTS, now: float | None = None) -> bool:
    """Mark ``host_or_url``'s host due for the next loop pass.

    The resolve path calls this when a clearance-blocked host answers
    with a challenge: it is a signal, not an attempt — one upsert, no
    network I/O, no blocking a press. It only fires for a host the
    loop is configured for (anything else is the timer's business, not
    a resolve's), only when no fresh clearance is stored, and it never
    moves a backoff window earlier. Returns True when the host is now
    due. Never raises. ``db_path_or_conn`` is a path or a live
    connection (the test seam).
    """
    now = time.time() if now is None else float(now)
    if _hc is None:
        return False
    host = _hc.normalize_host(host_or_url)
    if not host:
        return False
    configured = {_hc.normalize_host(h) for h in (hosts or [])}
    if host not in configured:
        return False
    loop = ClearanceLoop(conn=db_path_or_conn
                         if isinstance(db_path_or_conn, sqlite3.Connection)
                         else None,
                         db_path=None if isinstance(db_path_or_conn,
                                                    sqlite3.Connection)
                         else db_path_or_conn)

    def _mark(conn):
        _ensure_schema(conn)
        until = _fresh_until(conn, host, now=now)
        if until is not None and now < until:
            return False
        nxt = _next_attempt_at(conn, host)
        if nxt is not None and now < nxt:
            return False
        conn.execute(
            "INSERT INTO clearance_loop_attempts (host, next_attempt_at)"
            " VALUES (?, ?)"
            " ON CONFLICT(host) DO UPDATE SET next_attempt_at = ?",
            (host, now, now))
        return True

    return bool(loop._run(_mark, False))


# ── CLI ──────────────────────────────────────────────────────────────────────

def _parse_hosts(args_hosts, env_hosts):
    """Configured hosts: CLI flags win, then the env list, then the default."""
    hosts = []
    for raw in (args_hosts or []):
        for part in str(raw).split(","):
            host = _hc.normalize_host(part) if _hc is not None else ""
            if host and host not in hosts:
                hosts.append(host)
    if not hosts and env_hosts:
        for part in str(env_hosts).split(","):
            host = _hc.normalize_host(part) if _hc is not None else ""
            if host and host not in hosts:
                hosts.append(host)
    return hosts or list(DEFAULT_HOSTS)


def main(argv=None, opener=None) -> int:
    """CLI entry. Exit 0 on a completed pass (even when every earn
    failed — a failed earn is data, not a process failure), 2 on a
    usage error, 1 on an unexpected internal error."""
    parser = argparse.ArgumentParser(
        prog="clearance_loop.py",
        description="Keep a cf_clearance warm for clearance-blocked hosts"
                    " (gdflix first). Timer runs --once; --place stores a"
                    " browser-harvested cookie by hand.")
    parser.add_argument("--db", default=None,
                        help="database file (default: RADD_DB_PATH or the"
                             " server catalog DB)")
    parser.add_argument("--host", action="append", default=None,
                        help="host to keep warm (repeatable; default:"
                             " new10.gdflix.io). Also RADD_CLEARANCE_LOOP_HOSTS.")
    parser.add_argument("--earner-cmd", default=None,
                        help="external solver command (quoted argv; the host"
                             " is appended as its final argument; it prints"
                             " name=value lines). Default: stdlib attempt.")
    parser.add_argument("--timeout", type=int, default=EARN_TIMEOUT_S,
                        help="seconds per earn attempt (default 10)")
    parser.add_argument("--once", action="store_true",
                        help="run one pass over the configured hosts")
    parser.add_argument("--interval-s", type=int, default=0,
                        help="repeat passes every N seconds (daemon mode)")
    parser.add_argument("--max-seconds", type=int, default=3600,
                        help="daemon mode runtime cap (default 3600)")
    parser.add_argument("--status", action="store_true",
                        help="print each host's loop state, attempt nothing")
    parser.add_argument("--place", action="store_true",
                        help="store one operator-placed cookie and exit")
    parser.add_argument("--cookie", default=None,
                        help="with --place: 'name=value' to store")
    parser.add_argument("--ua", default=None,
                        help="with --place: the User-Agent the cookie was"
                             " earned under (default: the loop UA)")
    args = parser.parse_args(argv)
    if _hc is None:
        print("host_clearance is unavailable", file=sys.stderr)
        return 1
    db_path = args.db or default_db_path()
    hosts = _parse_hosts(args.host, os.environ.get("RADD_CLEARANCE_LOOP_HOSTS"))
    loop = ClearanceLoop(db_path=db_path)
    earner_cmd = None
    if args.earner_cmd:
        try:
            earner_cmd = shlex.split(args.earner_cmd)
        except ValueError as exc:
            print(f"bad --earner-cmd: {exc}", file=sys.stderr)
            return 2
        if not earner_cmd:
            print("empty --earner-cmd", file=sys.stderr)
            return 2

    if args.place:
        if len(hosts) != 1:
            print("--place needs exactly one --host", file=sys.stderr)
            return 2
        if not args.cookie or "=" not in args.cookie:
            print("--place needs --cookie 'name=value'", file=sys.stderr)
            return 2
        name, value = args.cookie.split("=", 1)
        placed = loop._run(
            lambda conn: _hc.place_clearance(
                conn, hosts[0], name.strip(), value.strip(),
                user_agent=(args.ua or EARN_USER_AGENT).strip(),
                ttl_s=_hc.CLEARANCE_MAX_AGE_S),
            False)
        print(json.dumps({"host": hosts[0], "cookie": name.strip(),
                          "placed": bool(placed)}))
        return 0 if placed else 1

    if args.status:
        for host in hosts:
            print(json.dumps(loop.status(host)))
        return 0

    if args.interval_s > 0:
        deadline = time.time() + max(0, args.max_seconds)
        while True:
            for summary in loop.run_once(hosts, earner_cmd=earner_cmd,
                                         opener=opener):
                print(json.dumps(summary), flush=True)
            if time.time() + args.interval_s >= deadline:
                break
            time.sleep(args.interval_s)
        return 0

    # Default (and --once): one pass. A failed earn is data, not a
    # process failure, so the exit code stays 0 when the pass ran.
    for summary in loop.run_once(hosts, earner_cmd=earner_cmd, opener=opener):
        print(json.dumps(summary), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
