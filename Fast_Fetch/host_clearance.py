#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Per-host clearance-cookie store (H4 R8).

A host behind Cloudflare-style bot protection (``cf_clearance``,
``__cf_bm``, DataDome, Akamai) sometimes needs a solved clearance cookie
before it serves a file. Today every resolve/probe that hits such a host
pays the full clearance cost again (or fails), because no clearance is
stored anywhere: ``fast_fetch`` builds a fresh in-memory ``CookieJar``
per unwrap call and discards it, and the probers send no cookies at all.

This module is the shared jar the resolver and both probers read before
paying that cost again:

* one row per (host, cookie name) in the ``host_clearance`` table, in the
  SAME database the caller already holds (the one that holds
  ``resolved_cache``) — no new file, no new service;
* a stored cookie is sent only while it is effectively alive:
  ``now < min(expires_at or +inf, obtained_at + CLEARANCE_MAX_AGE_S)``.
  The 15-minute ceiling is the conservative reuse bound from the
  research (cf_clearance itself lives 30 min by default, site-configurable
  15 min–24 h, and Precursor in Armed mode can invalidate even earlier) —
  the cookie's own Expires is never trusted alone;
* the cookie is replayed with the EXACT User-Agent that earned it (a
  clearance is bound to IP + User-Agent + hostname), so every row records
  its UA and ``cookie_header_for`` returns the header together with that UA;
* the moment a host challenges again, the host's rows are deleted
  (``note_response`` / ``invalidate_host``) — a dead clearance is never
  re-sent, so a challenge can never loop on a stale cookie;
* bounded: at most ``MAX_HOSTS`` hosts and ``MAX_COOKIES_PER_HOST`` cookies
  per host, least-recently-used pruned first;
* aggregates only: host, cookie name/value, UA, timestamps, source. No
  user, no device, no URL, no link id. A clearance is never another
  user's session: only cookies the system itself legitimately obtained
  (``source='response'`` — a 200/206 that carried the cookie) or an
  operator placed by hand (``source='operator'``) are stored. Nothing
  here solves a challenge.

Stdlib only, like the rest of the tree. Every ``note_*`` / ``place_*`` /
``prune`` / read path never raises: a store failure must never break a
probe or a resolve — the caller simply proceeds without a cookie. The
caller owns the transaction (same contract as
``resolved_cache.note_cold_press``): these functions never commit.

``HostClearance`` is the path adapter for callers that do not hold a
connection (the sweep engines): short-lived connections per call, one
locked-retry, never raises.
"""

from __future__ import annotations

import email.utils
import re
import sqlite3
import time
import urllib.parse

__all__ = [
    "CLEARANCE_MAX_AGE_S",
    "MAX_HOSTS",
    "MAX_COOKIES_PER_HOST",
    "CLEARANCE_COOKIE_NAMES",
    "HostClearance",
    "init_host_clearance",
    "normalize_host",
    "parse_set_cookie",
    "extract_clearance_cookies",
    "is_challenge_response",
    "cookie_header_for",
    "note_response",
    "place_clearance",
    "invalidate_host",
    "note_use",
    "prune",
]

#: Conservative reuse ceiling: a stored clearance is never sent more than
#: 15 minutes after it was obtained, whatever its own Expires says. The
#: vendor default is 30 minutes; the ceiling halves it, because a
#: clearance can die early (Precursor in Armed mode, a rotated secret).
CLEARANCE_MAX_AGE_S = 900
#: Hard bounds, so one chatty host can never grow the table without limit.
MAX_HOSTS = 64
MAX_COOKIES_PER_HOST = 8
#: The only cookie names a response may deposit. Anything else a host sets
#: (session ids, preferences, analytics) is never a clearance and is never
#: stored. Operator placement (``place_clearance``) accepts any name: the
#: operator names the cookie they placed.
CLEARANCE_COOKIE_NAMES = ("cf_clearance", "__cf_bm", "datadome", "_abck")
#: Sources a stored row may claim. ``response`` = the system itself got the
#: cookie on a clean 200/206; ``operator`` = placed by hand.
_SOURCES = ("response", "operator")
#: Challenge markers, used only when ``site_scout`` cannot be imported
#: (same fallback posture as the rest of the tree: refuse to trust what
#: cannot be classified). The module is the authority when present.
_FALLBACK_CHALLENGE_MARKERS = (
    "cf-turnstile",
    "challenge-platform",
    "Attention Required",
    "cf_chl",
    "cf-challenge",
    "datadome",
    "__cf_chl",
    "turnstile",
    "checking your browser",
    "verify you are human",
    "are you a robot",
    "jschl",
)
#: A cookie name/value may not carry a separator or a control character —
#: one smuggled ``;`` would let a placed value rewrite the header.
_NAME_RE = re.compile(r"^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$")
_VALUE_RE = re.compile(r"^[^\x00-\x1f\x7f;,]*$")

_DDL_STATEMENTS = (
    """CREATE TABLE IF NOT EXISTS host_clearance (
        host TEXT NOT NULL,
        cookie_name TEXT NOT NULL,
        cookie_value TEXT NOT NULL,
        user_agent TEXT NOT NULL,
        obtained_at REAL NOT NULL,
        expires_at REAL,
        source TEXT NOT NULL CHECK (source IN ('response', 'operator')),
        last_used_at REAL,
        use_count INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (host, cookie_name)
    )""",
    "CREATE INDEX IF NOT EXISTS idx_hc_host ON host_clearance(host)",
)

#: Module-level schema probe, mirroring ``resolved_cache._SCHEMA_OK``:
#: ``None`` = never checked in this process, a string = table known
#: present. ``_ensure_schema`` never raises; the worst case is a caller
#: that proceeds without a cookie.
_SCHEMA_OK = None


# ── host normalisation ───────────────────────────────────────────────────────

def normalize_host(value: str) -> str:
    """Lowercase hostname of a URL or a bare host, or ``""`` when unusable.

    Exact hostname only: a clearance earned on ``vcloud.beer`` is never
    sent to ``api.vcloud.beer`` — the vendor binds it to the hostname
    that set it, and a subdomain is a different identity.
    """
    if not value:
        return ""
    text = str(value).strip()
    if not text:
        return ""
    try:
        host = urllib.parse.urlsplit(text).hostname
    except ValueError:
        return ""
    if host is None:
        # No scheme: treat the whole value as a bare host (``urlsplit``
        # puts a scheme-less ``vcloud.beer/x`` in ``path``).
        try:
            host = urllib.parse.urlsplit("//" + text).hostname
        except ValueError:
            return ""
    host = (host or "").strip().lower().rstrip(".")
    if not host:
        return ""
    # A hostname is labels of letters/digits/hyphens, or an IP literal.
    # Anything else (spaces, underscores, ``!!!``) is not a host the store
    # may file a cookie under — ``urlsplit`` alone does not validate.
    if ":" in host:
        try:
            import ipaddress
            ipaddress.ip_address(host)
        except ValueError:
            return ""
        return host
    if not re.fullmatch(r"(?!-)[a-z0-9-]{1,63}(?<!-)"
                        r"(\.(?!-)[a-z0-9-]{1,63}(?<!-))*", host):
        return ""
    return host


# ── Set-Cookie parsing ───────────────────────────────────────────────────────

def parse_set_cookie(value: str, *, now: float | None = None):
    """Parse one raw ``Set-Cookie`` header.

    Returns ``(name, value, expires_epoch_or_None)``. ``Expires`` is
    parsed with :func:`email.utils.parsedate_to_datetime` (a naive date
    is read as UTC); ``Max-Age`` wins over ``Expires`` when both are
    present, and it is relative to ``now`` (the moment the response was
    received — default ``time.time()``), not to whenever the caller
    happens to read the row. Returns ``(None, None, None)`` for an
    unparseable header — a cookie the store cannot read is never stored.
    """
    if not value:
        return (None, None, None)
    parts = str(value).split(";")
    first = parts[0].strip()
    if "=" not in first:
        return (None, None, None)
    name, cookie_value = first.split("=", 1)
    name = name.strip()
    cookie_value = cookie_value.strip()
    if cookie_value.startswith('"') and cookie_value.endswith('"') and len(cookie_value) >= 2:
        cookie_value = cookie_value[1:-1]
    if not _NAME_RE.match(name) or not _VALUE_RE.match(cookie_value):
        return (None, None, None)
    expires = None
    max_age = None
    for attr in parts[1:]:
        attr = attr.strip()
        if not attr or "=" not in attr:
            continue
        key, val = attr.split("=", 1)
        key = key.strip().lower()
        val = val.strip()
        if key == "max-age":
            try:
                max_age = int(val)
            except ValueError:
                max_age = None
        elif key == "expires" and expires is None:
            try:
                dt = email.utils.parsedate_to_datetime(val)
            except (TypeError, ValueError):
                dt = None
            if dt is not None:
                if dt.tzinfo is None:
                    dt = dt.replace(tzinfo=email.utils.datetime.timezone.utc)
                expires = dt.timestamp()
    if max_age is not None:
        base = time.time() if now is None else float(now)
        expires = base + max_age
    return (name, cookie_value, expires)


def extract_clearance_cookies(set_cookie_values, *, now: float | None = None):
    """Allowlisted clearance cookies from raw ``Set-Cookie`` headers.

    ``set_cookie_values`` is one raw header string or an iterable of
    them. Returns a list of ``(name, value, expires_epoch_or_None)`` —
    only names in ``CLEARANCE_COOKIE_NAMES``, first occurrence wins when
    a header repeats a name. Anything else a host sets is dropped here,
    so a session cookie can never enter the store through this path.
    ``now`` is the moment the response was received (for ``Max-Age``).
    """
    if isinstance(set_cookie_values, str):
        values = [set_cookie_values]
    else:
        try:
            values = list(set_cookie_values or [])
        except TypeError:
            return []
    found = {}
    for raw in values:
        name, cookie_value, expires = parse_set_cookie(raw, now=now)
        if name is None:
            continue
        if name not in CLEARANCE_COOKIE_NAMES:
            continue
        if name not in found:
            found[name] = (name, cookie_value, expires)
    return list(found.values())


# ── challenge detection ──────────────────────────────────────────────────────

def _challenge_markers():
    """The challenge vocabulary, from ``site_scout`` when importable."""
    try:
        from Fast_Fetch.site_scout import CHALLENGE_MARKERS
    except ImportError:
        try:
            from site_scout import CHALLENGE_MARKERS  # type: ignore
        except ImportError:
            return _FALLBACK_CHALLENGE_MARKERS
    return tuple(CHALLENGE_MARKERS) or _FALLBACK_CHALLENGE_MARKERS


def is_challenge_response(status, headers=None, body=b"") -> bool:
    """True when a response IS a bot challenge, not a served page.

    A challenge is detected by the RESPONSE, never by a timer: the
    ``cf-mitigated: challenge`` header, or a challenge marker anywhere
    in the body (a 200 carrying an interstitial is still a challenge).
    A bare 403/503 with no marker and no header is NOT a challenge —
    it is a block of another kind, and it must not delete a clearance
    that may still be good.
    """
    headers = headers or {}
    try:
        if hasattr(headers, "get"):
            mitigated = headers.get("cf-mitigated") or headers.get("Cf-Mitigated")
            if mitigated and str(mitigated).strip().lower() == "challenge":
                return True
    except Exception:
        pass
    if body:
        if isinstance(body, (bytes, bytearray)):
            text = bytes(body).decode("utf-8", errors="ignore")
        else:
            text = str(body)
        if text:
            lowered = text.lower()
            for marker in _challenge_markers():
                if marker.lower() in lowered:
                    return True
    return False


# ── schema ───────────────────────────────────────────────────────────────────

def init_host_clearance(conn: sqlite3.Connection) -> None:
    """Create ``host_clearance`` (and its index) if absent. No commit."""
    for stmt in _DDL_STATEMENTS:
        conn.execute(stmt)


def _ensure_schema(conn: sqlite3.Connection) -> None:
    global _SCHEMA_OK
    if _SCHEMA_OK is None:
        init_host_clearance(conn)
        _SCHEMA_OK = "host_clearance"
        return
    try:
        conn.execute("SELECT 1 FROM host_clearance LIMIT 0")
    except sqlite3.OperationalError:
        init_host_clearance(conn)


def _effective_until(row) -> float:
    """The last instant a stored row may be sent."""
    obtained_at, expires_at = row[0], row[1]
    ceiling = obtained_at + CLEARANCE_MAX_AGE_S
    if expires_at is None:
        return ceiling
    return min(float(expires_at), ceiling)


# ── reads ────────────────────────────────────────────────────────────────────

def cookie_header_for(conn, host: str, *, now: float | None = None):
    """The stored clearance for ``host`` as ``(cookie_header, user_agent)``.

    Returns ``(None, None)`` when the host has no live clearance. Only
    rows still inside their effective window are sent, and only rows
    earned under ONE User-Agent — the newest row's — because a clearance
    is bound to the UA that earned it and mixing UAs in one request
    breaks the binding. Never raises: on any store failure the caller
    proceeds without a cookie.
    """
    host = normalize_host(host)
    if not host:
        return (None, None)
    now = time.time() if now is None else float(now)
    try:
        _ensure_schema(conn)
        rows = conn.execute(
            "SELECT cookie_name, cookie_value, user_agent, obtained_at,"
            " expires_at FROM host_clearance WHERE host = ?"
            " ORDER BY obtained_at DESC",
            (host,)).fetchall()
    except Exception:
        return (None, None)
    live = [r for r in rows if now < _effective_until((r[3], r[4]))]
    if not live:
        return (None, None)
    ua = live[0][2]
    same_ua = [r for r in live if r[2] == ua]
    header = "; ".join(f"{r[0]}={r[1]}" for r in same_ua)
    return (header, ua)


# ── writes (never raise, never commit — the caller owns the transaction) ─────

def note_response(conn, host: str, *, status, headers=None, body=b"",
                  set_cookie_values=(), user_agent: str = "", now: float | None = None):
    """Record one response against ``host``. Never raises.

    * Challenge (see :func:`is_challenge_response`): the host's rows are
      deleted at once — a dead clearance is never re-sent — and the
      result reports ``invalidated=True``.
    * Clean 200/206: allowlisted clearance cookies from the response
      (``Set-Cookie`` in ``headers`` — an ``http.client.HTTPMessage`` or
      a dict — plus any ``set_cookie_values``) are upserted with
      ``source='response'``. Anything else (session cookies, other
      statuses) is ignored.
    * A host with no live clearance and no new cookie is a no-op.

    Returns ``{"captured": int, "invalidated": bool, "kept": int}`` —
    ``kept`` is the host's live-cookie count afterwards. ``user_agent``
    is the UA that earned the cookie; an empty UA stores nothing,
    because a clearance without its UA cannot be replayed.
    """
    host = normalize_host(host)
    if not host:
        return {"captured": 0, "invalidated": False, "kept": 0}
    now = time.time() if now is None else float(now)
    try:
        _ensure_schema(conn)
        if is_challenge_response(status, headers, body):
            deleted = invalidate_host(conn, host)
            return {"captured": 0, "invalidated": True, "kept": 0}
        try:
            status_int = int(status)
        except (TypeError, ValueError):
            return {"captured": 0, "invalidated": False, "kept": 0}
        if status_int not in (200, 206):
            return {"captured": 0, "invalidated": False, "kept": 0}
        raw_values = list(set_cookie_values) if not isinstance(set_cookie_values, str) else [set_cookie_values]
        if headers is not None:
            try:
                if hasattr(headers, "get_all"):
                    raw_values.extend(headers.get_all("Set-Cookie") or [])
                elif hasattr(headers, "get"):
                    single = headers.get("set-cookie") or headers.get("Set-Cookie")
                    if single:
                        raw_values.append(single)
            except Exception:
                pass
        ua = str(user_agent or "").strip()
        if not ua:
            return {"captured": 0, "invalidated": False, "kept": 0}
        captured = 0
        for name, value, expires in extract_clearance_cookies(raw_values, now=now):
            conn.execute(
                "INSERT INTO host_clearance (host, cookie_name, cookie_value,"
                " user_agent, obtained_at, expires_at, source, last_used_at,"
                " use_count) VALUES (?, ?, ?, ?, ?, ?, 'response', NULL, 0)"
                " ON CONFLICT(host, cookie_name) DO UPDATE SET"
                " cookie_value = excluded.cookie_value,"
                " user_agent = excluded.user_agent,"
                " obtained_at = excluded.obtained_at,"
                " expires_at = excluded.expires_at,"
                " source = 'response'",
                (host, name, value, ua, now, expires))
            captured += 1
        if captured:
            _prune_host(conn, host, now=now)
        kept = conn.execute(
            "SELECT COUNT(*) FROM host_clearance WHERE host = ?",
            (host,)).fetchone()[0]
        return {"captured": captured, "invalidated": False, "kept": int(kept)}
    except Exception:
        return {"captured": 0, "invalidated": False, "kept": 0}


def place_clearance(conn, host: str, cookie_name: str, cookie_value: str, *,
                    user_agent: str, ttl_s: float = CLEARANCE_MAX_AGE_S,
                    now: float | None = None) -> bool:
    """Store one operator-placed clearance. Never raises.

    The operator names the cookie they placed (any name — this is the
    one path that accepts a name outside the response allowlist), the UA
    it was earned under, and a TTL (60 s–24 h; anything else is refused).
    Returns True on a stored row, False on a refused or failed write.
    """
    host = normalize_host(host)
    name = str(cookie_name or "").strip()
    value = str(cookie_value or "")
    ua = str(user_agent or "").strip()
    if not host or not _NAME_RE.match(name) or not _VALUE_RE.match(value):
        return False
    if not ua:
        return False
    try:
        ttl = float(ttl_s)
    except (TypeError, ValueError):
        return False
    if ttl < 60 or ttl > 86400:
        return False
    now = time.time() if now is None else float(now)
    try:
        _ensure_schema(conn)
        conn.execute(
            "INSERT INTO host_clearance (host, cookie_name, cookie_value,"
            " user_agent, obtained_at, expires_at, source, last_used_at,"
            " use_count) VALUES (?, ?, ?, ?, ?, ?, 'operator', NULL, 0)"
            " ON CONFLICT(host, cookie_name) DO UPDATE SET"
            " cookie_value = excluded.cookie_value,"
            " user_agent = excluded.user_agent,"
            " obtained_at = excluded.obtained_at,"
            " expires_at = excluded.expires_at,"
            " source = 'operator'",
            (host, name, value, ua, now, now + ttl))
        _prune_host(conn, host, now=now)
        return True
    except Exception:
        return False


def invalidate_host(conn, host: str) -> int:
    """Delete every stored clearance for ``host``. Never raises.

    Returns the number of rows deleted (0 for an unknown or unusable
    host). This is the invalidation half of the contract: called the
    moment a host challenges again, so a dead clearance is never re-sent.
    """
    host = normalize_host(host)
    if not host:
        return 0
    try:
        _ensure_schema(conn)
        cur = conn.execute(
            "DELETE FROM host_clearance WHERE host = ?", (host,))
        return int(cur.rowcount or 0)
    except Exception:
        return 0


def note_use(conn, host: str, cookie_name: str, *, now: float | None = None) -> bool:
    """Record one replay of a stored cookie (LRU + use count). Never raises."""
    host = normalize_host(host)
    name = str(cookie_name or "").strip()
    if not host or not name:
        return False
    now = time.time() if now is None else float(now)
    try:
        _ensure_schema(conn)
        cur = conn.execute(
            "UPDATE host_clearance SET last_used_at = ?,"
            " use_count = use_count + 1 WHERE host = ? AND cookie_name = ?",
            (now, host, name))
        return bool(cur.rowcount)
    except Exception:
        return False


def _prune_host(conn, host: str, *, now: float | None = None) -> int:
    """Drop a host's expired rows and anything past the per-host cap."""
    now = time.time() if now is None else float(now)
    try:
        rows = conn.execute(
            "SELECT cookie_name, obtained_at, expires_at, last_used_at"
            " FROM host_clearance WHERE host = ?",
            (host,)).fetchall()
    except Exception:
        return 0
    dead = [r[0] for r in rows if now >= _effective_until((r[1], r[2]))]
    deleted = 0
    for name in dead:
        cur = conn.execute(
            "DELETE FROM host_clearance WHERE host = ? AND cookie_name = ?",
            (host, name))
        deleted += int(cur.rowcount or 0)
    live = [r for r in rows if r[0] not in dead]
    if len(live) > MAX_COOKIES_PER_HOST:
        # Least recently used first: never-used rows sort by obtained_at.
        live.sort(key=lambda r: (r[3] if r[3] is not None else r[1]))
        for row in live[:len(live) - MAX_COOKIES_PER_HOST]:
            cur = conn.execute(
                "DELETE FROM host_clearance WHERE host = ? AND cookie_name = ?",
                (host, row[0]))
            deleted += int(cur.rowcount or 0)
    return deleted


def prune(conn, *, now: float | None = None) -> int:
    """Drop expired rows everywhere and prune past the host cap. Never raises.

    Returns the number of rows deleted. Expired rows go first; then, if
    more than ``MAX_HOSTS`` hosts hold rows, the least-recently-used
    hosts (by their newest activity) lose their rows whole.
    """
    now = time.time() if now is None else float(now)
    try:
        _ensure_schema(conn)
        hosts = [r[0] for r in conn.execute(
            "SELECT DISTINCT host FROM host_clearance").fetchall()]
        deleted = 0
        for host in hosts:
            deleted += _prune_host(conn, host, now=now)
        hosts = [r[0] for r in conn.execute(
            "SELECT DISTINCT host FROM host_clearance").fetchall()]
        if len(hosts) > MAX_HOSTS:
            activity = {}
            for host in hosts:
                row = conn.execute(
                    "SELECT MAX(COALESCE(last_used_at, obtained_at))"
                    " FROM host_clearance WHERE host = ?",
                    (host,)).fetchone()
                activity[host] = row[0] if row and row[0] is not None else 0.0
            hosts.sort(key=lambda h: activity[h])
            for host in hosts[:len(hosts) - MAX_HOSTS]:
                deleted += invalidate_host(conn, host)
        return deleted
    except Exception:
        return 0


# ── path adapter ─────────────────────────────────────────────────────────────

class HostClearance:
    """A ``host_clearance`` store bound to one database file.

    Short-lived connections per call (busy timeout + one locked-retry),
    so a sweep worker and the API server can share the same file without
    holding a connection open. Every method never raises: on any failure
    it returns the empty result (``(None, None)``, ``False``, ``0`` or
    the zeroed dict), and the caller proceeds without a cookie.
    """

    def __init__(self, db_path):
        self._db_path = str(db_path)

    def _connect(self):
        conn = sqlite3.connect(self._db_path, timeout=5.0)
        try:
            conn.execute("PRAGMA busy_timeout = 5000")
        except sqlite3.Error:
            pass
        return conn

    def _run(self, fn, default, *args, **kwargs):
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
            except Exception as exc:  # noqa: BLE001 — never raise
                last = exc
                break
            finally:
                if conn is not None:
                    try:
                        conn.close()
                    except Exception:
                        pass
        return default

    def header_for(self, host, *, now=None):
        """``(cookie_header, user_agent)`` for ``host``, or ``(None, None)``."""
        return self._run(cookie_header_for, (None, None), host, now=now)

    def note_response(self, host, *, status, headers=None, body=b"",
                      set_cookie_values=(), user_agent="", now=None):
        """Record one response; see :func:`note_response`."""
        return self._run(
            note_response,
            {"captured": 0, "invalidated": False, "kept": 0},
            host, status=status, headers=headers, body=body,
            set_cookie_values=set_cookie_values, user_agent=user_agent,
            now=now)

    def note_probe(self, url, result, *, user_agent="", now=None):
        """Record one probe result against the URL's host. Never raises.

        A challenged probe (``result.challenge`` or a 403/503 status)
        invalidates the host at once; a clean 200/206 with clearance
        cookies in ``result.set_cookie`` captures them. A probe result
        with no UA earns no capture (a clearance without its UA cannot
        be replayed), but the invalidation half still runs.
        """
        host = normalize_host(url)
        if not host:
            return {"captured": 0, "invalidated": False, "kept": 0}
        status = getattr(result, "http_status", 0) or 0
        challenge = bool(getattr(result, "challenge", False))
        if challenge or status in (403, 503):
            deleted = self._run(invalidate_host, 0, host)
            return {"captured": 0, "invalidated": bool(deleted or challenge),
                    "kept": 0}
        set_cookie = getattr(result, "set_cookie", ()) or ()
        if status in (200, 206) and set_cookie:
            return self.note_response(
                host, status=status, set_cookie_values=tuple(set_cookie),
                user_agent=user_agent, now=now)
        return {"captured": 0, "invalidated": False, "kept": 0}

    def note_challenge(self, host):
        """Invalidate ``host`` now; returns rows deleted."""
        return self._run(invalidate_host, 0, host)

    def place(self, host, cookie_name, cookie_value, *, user_agent,
              ttl_s=CLEARANCE_MAX_AGE_S, now=None):
        """Store one operator-placed clearance; see :func:`place_clearance`."""
        return self._run(
            place_clearance, False, host, cookie_name, cookie_value,
            user_agent=user_agent, ttl_s=ttl_s, now=now)

    def prune(self, *, now=None):
        """Drop expired rows and prune past the caps; returns rows deleted."""
        return self._run(prune, 0, now=now)
