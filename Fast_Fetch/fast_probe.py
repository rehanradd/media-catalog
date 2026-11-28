#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fast_probe.py — host-partitioned, keep-alive link prober (pilot).

HEAD first, ranged-GET fallback for the hosts and statuses where HEAD lies.

Why this exists
---------------
The max-speed sweep (2026-11-18) verifies ~4.4M ``download_sources`` rows
through ``link_state.propagate`` + ``link_health.probe``. Measured on the
gate-backup copy (``sweep_analytics.py``): 4,388,744 unchecked rows, 3,642,559
distinct ``source_url`` (17% duplicates), 1,166 distinct hosts, 86% of rows
on 80 hosts with >10k rows each (vcloud.beer 236,650 biggest). Measured rate
~7.6 rows/sec, and 8 of 15 per-site lanes died on ``database is locked``
(``BEGIN IMMEDIATE``, 5s busy timeout) — a staggered relaunch does not fix
that, because the contention is concurrent writers, not launch timing.

The old prober's costs per probe (``link_health.py``): a FRESH TCP+TLS
connection every request, ``Connection: close``, 1.0s per-host spacing +
0.2s jitter, HEAD chain then a Range-GET of up to 512 KiB. Per-host ceiling
~0.77 rows/sec. Per-site lanes pile their workers onto the same few host
locks, so the sweep uses only ~10 hosts' worth of parallelism at once.

What this module changes (and what it deliberately does NOT change)
-------------------------------------------------------------------
* **Host-partitioned scheduling.** Candidate rows are grouped by host; ONE
  worker thread owns each host, probes strictly serially with >=1.0s spacing
  (``link_health.host_gate``, same ``RADD_HOST_SPACING_S`` default). Hosts
  with the most remaining rows go first — the biggest host sets the floor.
  Per-host politeness is unchanged: still 1-at-a-time, still 1.0s spacing,
  zero added 429 risk. The parallelism comes from probing *different* hosts
  at once, not from hammering one host harder.
* **Move learning.** A HEAD chain that lands on a different host is one
  observation of a domain move (``host_registry.record_redirect``): only
  a host-only redirect (same path + query) counts, and 20 distinct
  observations of one old->new pair update the registry itself. The
  observation is recorded IN ADDITION to the verdict — the verdict
  policy, the writer and the locking are untouched, and a registry
  failure never breaks a probe.
* **Parked verdict + HTML observations (self-healing Branch 3).** The
  ranged-GET fallback is the only place the sweep ever sees a body, so
  it is the only place a parking page can be proved: a 200 answer whose
  body carries a parking marker (``link_state.is_parked_body``) earns a
  ``parked`` verdict — never dead, never active — and a 200 HTML answer
  is one HTML-instead-of-media observation for the host
  (``host_registry.record_html_observation``); at 20, discovery runs
  (redirect target, registry history, sibling TLDs, DDG search) and the
  first candidate ``validate_host_move`` passes is applied with evidence
  ``parked-sweep``. Blocked (403/429/Cloudflare) and transport answers
  never record an observation, so a WAF refusal can never count toward
  the threshold. A parked domain that answers HEAD 200 cleanly is still
  missed — the sweep's HEAD chain cannot see a body, and a HEAD 200
  short-circuits before any GET — a known, honest limitation.
* **Site recheck queue (self-healing Branch 4).** After each apply
  batch, dead/parked verdicts on a catalog site's own active base are
  counted per site (in rows — every row sharing the dead URL), and a
  site at or above ``domain_resolver.SITE_ROT_THRESHOLD`` (10) is
  appended to the recheck queue (``recheck_queue``, a sidecar SQLite
  table next to the catalog DB). The recheck job
  (``domain_recheck.py``) drains the queue and runs the discovery
  chain per site; the sweep loop also runs it as a post-batch hook.
  Only "dead" and "parked" count — an "expired" (5xx/WAF) answer is a
  sick intermediary, not a moved site — and a file host can never queue
  a site, because only registry active-base hosts are counted. The
  queue is best-effort: a queue failure never fails the batch.
* **Dedup.** Rows are grouped by probe-target URL; each distinct URL is
  probed ONCE and the verdict is applied to every row sharing it (-17%).
* **Keep-alive.** One ``http.client`` connection per host for the whole run
  (``Connection: keep-alive``), reconnecting once when the server closes it.
  Removes DNS+TCP+TLS setup (~0.2-0.4s) from every probe after the first.
* **DNS cache.** Each host is resolved once per process; the cached address
  feeds ``socket.create_connection``. The SSRF guard
  (``link_health._block_reason``) still runs on EVERY URL — the guard is a
  safety check, not a hot path to optimise away, and DNS-rebinding must
  still be caught per URL.
* **HEAD-first verdict pass, ranged-GET fallback.** The verdict comes
  from the HEAD chain status, mapped through the SAME function the sweep
  uses (``link_state.classify`` on a ``ProbeResult``-shaped payload) — but
  HEAD lies on some hosts (it answers 403/404 on a live URL while a GET
  answers normally; measured 2026-11-19, vcloud.beer HEAD 403 vs ranged
  GET 301 -> vcloud.my 200). So when HEAD gives no usable verdict
  (400/405/501, the statuses the full prober falls through to GET on) or
  the probe target is a host on ``KNOWN_HEAD_LIARS``, the pass issues ONE
  ranged GET (``Range: bytes=0-0``, at most 1 KiB read) and classifies
  from that chain instead. Transport errors never trigger a GET — a GET
  would burn the same budget on the same failure. A HEAD 200 is active
  — except on a catalog site's own base host or a host with prior
  parked/HTML observations, where ONE ranged GET verifies the body is
  not a parking page (parked-HEAD-200, animahd.com 2026-11-28: a Sedo
  page answers HEAD 200 cleanly). That check can only reclassify the
  row to parked; any other GET answer keeps HEAD's active verdict.
  The pass still writes
  ONLY the five verdict columns (``status``, ``http_status``,
  ``last_checked_at``, ``checked_at``, ``probe_error``) and leaves
  ``is_resumable`` / ``file_size_bytes`` / ``mime_type`` / ``protocol`` /
  ``latency_ms`` / ``speed_mbps`` / ``cors_open`` exactly as they were.
  An enrichment pass (full probe for rows that came back active) is a
  separate later step.
* **Single writer.** Exactly ONE thread touches SQLite. Workers push verdicts
  into a queue; the writer batches 100 and commits under ``BEGIN IMMEDIATE``
  with a 30s busy timeout and up to 5 lock retries with backoff. Lock
  errors must be 0 in the pilot — the writer fails loudly otherwise.

What this module does NOT do (pilot scope, by design)
------------------------------------------------------
* No ``link_state_cursor`` / ``link_state_runs`` rows: the pilot is a bounded
  run (``--max-rows`` distinct URLs), not a resumable sweep. The swap step
  wires the design into a cursor.
* No ``cat_links`` projection sync and no ``host_health`` feed: the pilot
  writes ledger verdicts only, so the served projection does not move until
  a rebuild. For a 1,000-row pilot that is irrelevant; the swap decides it.

Row selection (documented; both passes stream, memory stays bounded)
---------------------------------------------------------------------
Apply mode — fresh unchecked rows, biggest hosts first::

    -- pass 1 (host counts, streaming):
    SELECT source_url, final_url FROM download_sources WHERE checked_at IS NULL
    -- pass 2 (rows, streaming, same WHERE):
    SELECT id, source_url, final_url FROM download_sources WHERE checked_at IS NULL

Pass 1 counts rows per host (probe target = ``final_url`` or ``source_url``).
Pass 2 keeps rows whose host is in the top set (cumulative *capped* host
count >= 2x ``--max-rows``), capped at 2x ``--max-rows`` rows — and each
host contributes at most ``max(4, max_rows // 24)`` rows, so a batch spans
~24 hosts instead of collapsing onto the biggest one. Kept rows are
deduped by exact target URL and taken in host-count-desc, id order until
``--max-rows`` distinct URLs.

Dry-run mode — same two passes, but::

    WHERE checked_at IS NOT NULL AND checked_at >= :since
    -- :since = now - 7 days; SELECT also returns status for the compare.

Safety while the live lanes run
-------------------------------
Run ``nice -n 19`` (the operator handles that). The writer waits on locks
(30s timeout + retry) instead of dying. Overlap with live lanes' rows is
acceptable: same verdict, last write wins — and this pass only ever writes
the five verdict columns, never enrichment.

Stopping a batch (INF-05)
--------------------------
A batch used to be unstoppable except by SIGTERM to the exact child PID:
the sweep loop only reads the STOP file between batches, and a batch runs
30 min to 14 h. Now a batch stops gracefully, within one in-flight URL
per host thread, on either a SIGTERM/SIGINT (the handler only sets the
stop event; a second signal still kills at once) or the STOP file itself
(``--stop-file``, default ``/tmp/fast-probe-sweep.stop``) — so even a
child orphaned by a dead wrapper sees the gate's stop file and exits on
its own. A stopped run flushes the verdicts it earned, reports
``"stopped": true``, and exits 0, so the wrapper counts it as a clean
batch end instead of a failure. Rows never reached stay unchecked.

Standard library only. Never imports ``config``.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import errno
import http.client
import json
import os
import queue
import re
import signal
import socket
import sqlite3
import ssl
import sys
import threading
import time
import urllib.parse
from pathlib import Path

try:  # normal package-style import when run as Fast_Fetch/fast_probe.py
    from Fast_Fetch import link_health, link_state
except ImportError:  # running from inside Fast_Fetch/ (the documented way)
    import os

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import link_health  # type: ignore
    import link_state  # type: ignore

try:  # package-style import when run as Fast_Fetch/fast_probe.py
    from Fast_Fetch import warp_proxy
except ImportError:  # running from inside Fast_Fetch/ (the documented way)
    import warp_proxy  # type: ignore

try:  # package-style import when run as Fast_Fetch/fast_probe.py
    from Fast_Fetch import db_pragmas
except ImportError:  # running from inside Fast_Fetch/ (the documented way)
    import db_pragmas  # type: ignore

try:
    from Fast_Fetch import host_registry
except ImportError:  # running from inside Fast_Fetch/ (the documented way)
    try:
        import host_registry  # type: ignore
    except ImportError:
        # The registry is optional: a checkout without it still probes,
        # it just cannot learn moves. The verdict path never depends on it.
        host_registry = None  # type: ignore

try:
    from Fast_Fetch import domain_resolver
except ImportError:  # running from inside Fast_Fetch/ (the documented way)
    try:
        import domain_resolver  # type: ignore
    except ImportError:
        # The resolver is optional: a checkout without it still probes,
        # it just cannot queue site rechecks. The verdict path never
        # depends on it.
        domain_resolver = None  # type: ignore

try:
    from Fast_Fetch import recheck_queue
except ImportError:  # running from inside Fast_Fetch/ (the documented way)
    try:
        import recheck_queue  # type: ignore
    except ImportError:
        recheck_queue = None  # type: ignore

__all__ = [
    "payload_for_head",
    "head_verdict",
    "chain_verdict",
    "get_chain",
    "is_known_head_liar",
    "KNOWN_HEAD_LIARS",
    "SWEEP_STOP_FILE",
    "install_stop_handlers",
    "request_stop",
    "stop_requested",
    "select_rows",
    "run_pilot",
    "run_compare",
    "VerdictWriter",
]

# ── knobs ────────────────────────────────────────────────────────────────────

DEFAULT_WORKERS = 24          # host threads; each host is still 1-at-a-time
DEFAULT_MAX_ROWS = 1000       # distinct URLs per pilot run
DEFAULT_TIMEOUT = 8.0         # per-socket budget, same as link_health
DEFAULT_MAX_REDIRECTS = 5     # same as link_health
WRITE_BATCH = 100             # verdicts per writer transaction
WRITER_BUSY_TIMEOUT_MS = 30000
WRITER_LOCK_RETRIES = 5
#: Pass 2 keeps at most this multiple of --max-rows rows (bounded memory).
SELECT_KEEP_MULTIPLIER = 2
#: Per-host cap divisor: a pilot batch spans ~24 hosts (``max_rows // 24``
#: rows per host), so the rate the pilot measures is the multi-host rate,
#: not one host's 1.0s-spacing ceiling. Measured 2026-11-18: without a cap
#: the first dry-run picked 1,000 streamtape rows from a single host and
#: measured 0.75 rows/s — the single-host ceiling, not the prober's rate.
PER_HOST_CAP_DIVISOR = 24
#: Per-host cap floor: below it a small batch could not even take the
#: biggest host's first rows (the 4-row selection test pins
#: biggest-host-first); at pilot scale the cap is ``max_rows // 24``.
PER_HOST_CAP_FLOOR = 4
#: Dry-run "recent verdict" window: re-probe verdicts from the last 7 days.
COMPARE_SINCE_SECONDS = 7 * 86400

#: HEAD statuses the current prober answers with a GET instead of a verdict
#: (``link_health.probe``: ``need_get`` when HEAD is 400/405/501). A
#: HEAD-only pass earns no verdict there — the full prober judges it later.
HEAD_UNSUPPORTED_STATUSES = (400, 405, 501)

#: Hosts whose HEAD answers cannot be trusted: they refuse or mis-answer
#: HEAD on a live URL while a ranged GET answers normally. Measured
#: 2026-11-19 from the Oracle VM (curl, Chrome UA):
#:   vcloud.beer — HEAD 403, ranged GET 301 -> vcloud.my 200. (21,853
#:   rows are probed AT this host; the other ~215k vcloud rows resolve to
#:   file hosts and are judged on those hosts' answers, not this list's.)
#:   rozgarlelo.modiplay.xyz — listed on the 2026-11-18 domain audit's
#:   HEAD-vs-GET false-dead evidence; the 2026-11-19 sample (7 URLs)
#:   agreed HEAD/GET, so this entry is insurance: one ranged GET per
#:   non-active row, which can only confirm or correct the HEAD verdict.
#: Matched exact-or-subdomain on the lowercased probe-target hostname:
#: the host actually knocked on, never the bridge that served the link.
KNOWN_HEAD_LIARS = frozenset({"vcloud.beer", "rozgarlelo.modiplay.xyz"})

#: The fallback GET asks for one byte only; a server that ignores Range
#: could send a whole file, so the body read is capped and the connection
#: is dropped afterwards (a half-read body cannot be reused).
GET_RANGE = "bytes=0-0"
GET_READ_CAP = 1024


# ── graceful stop (INF-05) ───────────────────────────────────────────────────
#
# Root cause, measured 2026-11-23/24 (see Fast_Fetch/checkpoints/
# 20261124-inf05-sweep-stuck-child.md): the sweep loop only reads the STOP
# file BETWEEN batches, and a batch runs 30 min to 14 h, so a gate stop
# could only ever land as a hand-sent SIGTERM to the exact child PID
# (every "batch exit 143" in /tmp/fast-probe-sweep.log). That SIGTERM
# killed the child mid-write (queued verdicts lost, writer never flushed)
# and counted as a wrapper failure — three stops in a row exited the loop
# for good. Worse, the wrapper runs the child in the foreground with no
# trap, so killing the WRAPPER orphans the child: it never reads the STOP
# file (only the wrapper does) and keeps writing through the backup
# window — greenmotors, 23 Nov: wrapper gone 18:54:17, a fast_probe.py
# child still matched pgrep until 19:09:22.
#
# The fix has three layers, all in this module (the wrapper's own trap is
# the shipped Fast_Fetch/fast-probe-sweep-loop.sh, installed at the next
# relaunch):
#   1. SIGTERM/SIGINT no longer kill the process: the handler only sets
#      the stop event. Workers finish their in-flight URL, then stop;
#      the writer flushes what was queued; the run exits 0 with
#      "stopped": true, so the wrapper counts it as a clean batch end,
#      not a failure. A SECOND signal restores the default disposition
#      and re-delivers, so a wedged shutdown can still be forced.
#   2. The batch itself watches the STOP file (``--stop-file``, default
#      /tmp/fast-probe-sweep.stop, env RADD_SWEEP_STOP_FILE): a child
#      orphaned by a dead wrapper still sees the gate's stop file and
#      exits on its own, within one in-flight URL per host thread.
#   3. Nothing here changes the verdict path: a stopped batch writes
#      exactly the verdicts it earned before the stop, and the rows it
#      never reached stay unchecked for the next batch.

#: Stop file the sweep loop and the gate touch to ask a running batch to
#: stop (same path the live ``fast-probe-sweep-loop.sh`` uses).
SWEEP_STOP_FILE = "/tmp/fast-probe-sweep.stop"
#: Env var overriding the stop-file path (tests / non-standard layouts).
SWEEP_STOP_FILE_ENV = "RADD_SWEEP_STOP_FILE"

_STOP_EVENT = threading.Event()
_STOP_SIGNAL_COUNT = [0]  # mutable cell: how many stop signals arrived


def request_stop() -> None:
    """Ask the running batch to stop after its in-flight URLs (INF-05)."""
    _STOP_EVENT.set()


def stop_requested() -> bool:
    """True once a stop was requested by signal (stop file: caller's check)."""
    return _STOP_EVENT.is_set()


def stop_file_path(explicit: str | None = None) -> str | None:
    """Effective stop-file path: explicit arg, else env, else the default.

    An explicit empty string disables the stop-file watch.
    """
    if explicit is not None:
        return explicit or None
    return os.environ.get(SWEEP_STOP_FILE_ENV) or SWEEP_STOP_FILE


def _stop_file_present(path: str | None) -> bool:
    if not path:
        return False
    try:
        return Path(path).exists()
    except OSError:
        return False


def _should_stop(stop_event: threading.Event | None,
                 stop_file: str | None) -> bool:
    """True when the batch must stop: signal event set, or stop file present."""
    if stop_event is not None and stop_event.is_set():
        return True
    return _stop_file_present(stop_file)


def _stop_signal_handler(signum, _frame) -> None:
    """First stop signal: request a graceful stop. Second: die at once."""
    _STOP_SIGNAL_COUNT[0] += 1
    if _STOP_SIGNAL_COUNT[0] == 1:
        _STOP_EVENT.set()
        return
    try:
        signal.signal(signum, signal.SIG_DFL)
    except (OSError, ValueError):
        pass
    os.kill(os.getpid(), signum)


def install_stop_handlers() -> threading.Event:
    """Install SIGTERM/SIGINT graceful-stop handlers; return the event.

    A no-op (returns the unset event) when the handlers cannot be
    installed — signal handlers only exist in the main thread, so library
    use from a worker thread keeps the old behaviour. Safe to call twice:
    re-installing the same handler is harmless.
    """
    for sig in (signal.SIGTERM, signal.SIGINT):
        try:
            signal.signal(sig, _stop_signal_handler)
        except (OSError, ValueError):
            pass
    return _STOP_EVENT


def is_known_head_liar(host: str) -> bool:
    """True when *host* (probe-target hostname) is on ``KNOWN_HEAD_LIARS``.

    Exact match or any subdomain of a listed host, lowercased. A merely
    similar name (``fakevcloud.beer``) is not a liar — the dot matters.
    """
    host = (host or "").lower().strip()
    if not host:
        return False
    return any(host == liar or host.endswith("." + liar)
               for liar in KNOWN_HEAD_LIARS)

_UPDATE_VERDICT_SQL = """
    UPDATE download_sources
       SET status = ?,
           http_status = ?,
           last_checked_at = ?,
           checked_at = ?,
           probe_error = ?
     WHERE id = ?
"""


# ── verdict mapping (delegated, never re-invented) ────────────────────────────

def payload_for_head(status: int, error: str, checked_at: float) -> dict | None:
    """Build the ``link_state.classify`` payload for one HEAD-chain outcome.

    Returns ``None`` when the HEAD chain earns no verdict: HEAD refused
    (400/405/501 — the current prober falls through to GET), or a redirect
    with no Location (nothing confirmed). Every other outcome maps exactly
    the way ``link_health.probe`` maps it: 2xx answers -> ok, >=400 (other
    than the three above) -> ``http_<status>``, transport errors pass
    through as their stable code.
    """
    status = int(status or 0)
    error = str(error or "")
    if error:
        return {"ok": False, "error": error, "http_status": status,
                "checked_at": float(checked_at)}
    if status in HEAD_UNSUPPORTED_STATUSES:
        return None
    if 200 <= status < 300:
        return {"ok": True, "error": "", "http_status": status,
                "checked_at": float(checked_at)}
    if 300 <= status < 400:
        return None  # redirect with no Location: nothing confirmed
    if status >= 400:
        return {"ok": False, "error": f"http_{status}", "http_status": status,
                "checked_at": float(checked_at)}
    return None


def chain_verdict(status: int, error: str, checked_at: float) -> str | None:
    """One redirect-chain outcome -> ledger status, or ``None`` (no verdict).

    Pure delegation: the payload goes through ``link_state.classify``, the
    same function the sweep uses, so a timeout can never be "dead" here and
    "not a verdict" there. ``STATUS_UNVERIFIED`` (transport errors,
    blocked targets) also returns ``None`` — the row stays eligible.
    Applies to a HEAD chain and to a ranged-GET chain alike: the mapping
    is about what the endpoint answered, not which method asked.
    """
    payload = payload_for_head(status, error, checked_at)
    if payload is None:
        return None
    verdict = link_state.classify(payload)
    if verdict == link_state.STATUS_UNVERIFIED:
        return None
    return verdict


def head_verdict(status: int, error: str, checked_at: float) -> str | None:
    """One HEAD-chain outcome -> ledger status, or ``None`` (no verdict).

    Kept name for the existing call sites and tests; the mapping lives in
    :func:`chain_verdict` and is shared with the ranged-GET fallback.
    """
    return chain_verdict(status, error, checked_at)


# ── DNS cache (connect path only; the SSRF guard still checks every URL) ─────

_DNS_CACHE: dict[tuple[str, int], tuple] = {}
_DNS_LOCK = threading.Lock()


def resolve_sockaddr(host: str, port: int) -> tuple:
    """Resolve *(host, port)* once per process; return the sockaddr to dial.

    The first ``getaddrinfo`` answer wins (resolver order). Callers dial the
    returned sockaddr directly, so no second lookup happens on the connect
    path. The SSRF guard (``link_health._block_reason``) is NOT cached: it
    runs per URL, because a host that was public at run start must still be
    re-checked if its DNS moves mid-run.
    """
    key = (host, int(port))
    with _DNS_LOCK:
        hit = _DNS_CACHE.get(key)
    if hit is not None:
        return hit
    infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM,
                               proto=socket.IPPROTO_TCP)
    sockaddr = infos[0][4]
    with _DNS_LOCK:
        _DNS_CACHE.setdefault(key, sockaddr)
    return _DNS_CACHE[key]


def clear_dns_cache() -> None:
    """Test hook: forget every cached address."""
    with _DNS_LOCK:
        _DNS_CACHE.clear()


class _CachedHTTPConnection(http.client.HTTPConnection):
    """HTTPConnection that dials a once-resolved address (DNS cache).

    Falls back to the stock connect when a proxy tunnel is configured
    (not used by the prober).
    """

    def connect(self):  # noqa: D102 - mirrors http.client
        if self._tunnel_host:
            return super().connect()
        sockaddr = resolve_sockaddr(self.host, self.port)
        # Never dial with no timeout: the global-default sentinel (or an
        # explicit None) would make create_connection block forever on a
        # blackhole host (mixdrop.co 104.247.81.99 silently drops SYNs and
        # stalled whole sweep batches). Fall back to the prober budget.
        timeout = self.timeout
        if timeout is None or timeout is socket._GLOBAL_DEFAULT_TIMEOUT:
            timeout = DEFAULT_TIMEOUT
        self.sock = socket.create_connection(sockaddr, timeout, self.source_address)
        # Stock connect() sets TCP_NODELAY best-effort; mirror it.
        try:
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError as exc:
            if exc.errno != errno.ENOPROTOOPT:
                raise


class _CachedHTTPSConnection(_CachedHTTPConnection, http.client.HTTPSConnection):
    """HTTPSConnection with the same DNS cache, SNI kept intact.

    ``server_hostname`` stays the original hostname (SNI), while the socket
    dials the cached address. Certificate policy is the project's own
    (``link_health.SSL_CONTEXT`` — verification off by default, exactly as
    the current prober).
    """

    def connect(self):  # noqa: D102 - mirrors http.client
        if self._tunnel_host:
            return http.client.HTTPSConnection.connect(self)
        _CachedHTTPConnection.connect(self)
        context = getattr(self, "_context", None) or getattr(self, "context", None)
        self.sock = context.wrap_socket(self.sock, server_hostname=self.host)


def default_connection_factory(host: str, port: int, https: bool,
                               timeout: float):
    """One keep-alive connection for *(host, port, scheme)*."""
    proxy = warp_proxy.proxy_url()
    if proxy:
        # WARP proxy (RADD_WARP_PROXY): dial the proxy and CONNECT-tunnel
        # to the real host. The _Cached* connect() already falls back to
        # the stock connect (which issues the CONNECT) when a tunnel is
        # set, so the DNS cache is bypassed — the proxy resolves the host.
        phost, pport = warp_proxy.proxy_host_port(proxy)
        if https:
            conn = _CachedHTTPSConnection(phost, pport, timeout=timeout,
                                          context=link_health.SSL_CONTEXT)
        else:
            conn = _CachedHTTPConnection(phost, pport, timeout=timeout)
        conn.set_tunnel(host, port)
        return conn
    if https:
        return _CachedHTTPSConnection(host, port, timeout=timeout,
                                      context=link_health.SSL_CONTEXT)
    return _CachedHTTPConnection(host, port, timeout=timeout)


# ── keep-alive HEAD session (one per worker thread, never shared) ─────────────

class HostSession:
    """Keep-alive HEAD requests for the hosts one worker thread touches.

    Holds one connection per (host, port, scheme), created lazily. A dead
    keep-alive connection (server closed it, reset, broken pipe, bad status
    line) is dropped and the request retried ONCE on a fresh connection —
    the connection was dead, not the server. A genuine timeout is reported
    immediately, never retried: retrying a slow server doubles its load.
    """

    def __init__(self, connection_factory=None, timeout: float = DEFAULT_TIMEOUT):
        self._factory = connection_factory or default_connection_factory
        self._timeout = float(timeout)
        self._conns: dict[tuple[str, int, bool], object] = {}
        self.reconnects = 0
        #: Body bytes read by the most recent :meth:`get` call (at most
        #: ``GET_READ_CAP``), or ``b""`` when the last call failed before
        #: reading. The ranged-GET fallback reads a body the caller may
        #: need to inspect (parking markers, HTML-instead-of-media) without
        #: paying for a second request; ``get_chain`` leaves it at the final
        #: hop's body. Reset at the start of every :meth:`get` call.

    def _conn_for(self, host: str, port: int, https: bool):
        key = (host, port, https)
        conn = self._conns.get(key)
        if conn is None:
            conn = self._factory(host, port, https, self._timeout)
            self._conns[key] = conn
        return conn

    def _drop(self, host: str, port: int, https: bool) -> None:
        conn = self._conns.pop((host, port, https), None)
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass

    def head(self, url: str, headers: dict, timeout: float | None = None):
        """One HEAD request. Returns ``(status, headers, error, header_ms)``.

        ``headers`` is a lowercase-name dict (``{}`` on transport failure).
        ``error`` is a stable code (``""`` on any HTTP answer, redirect
        included — the caller decides what a status means).
        """
        parts = urllib.parse.urlsplit(url)
        host = parts.hostname or ""
        try:
            port = parts.port or (443 if parts.scheme == "https" else 80)
        except ValueError:
            # Unparseable port (e.g. :99999): never attempted, no verdict.
            return 0, {}, "conn_error", 0
        https = parts.scheme == "https"
        # Percent-encode the request target: a stored URL may carry raw
        # non-ASCII (a filename with a curly quote, U+2019) and http.client
        # encodes the request line as ASCII — an unencoded target raised
        # UnicodeEncodeError and killed the whole sweep batch (19:36 PKT).
        # safe="/%" keeps separators and already-encoded %XX intact.
        target = urllib.parse.quote(parts.path or "/", safe="/%")
        if parts.query:
            target += "?" + urllib.parse.quote(parts.query, safe="=&%")
        budget = float(self._timeout if timeout is None else timeout)
        for attempt in (0, 1):
            conn = self._conn_for(host, port, https)
            t0 = time.monotonic()
            try:
                conn.request("HEAD", target, headers=headers)
                resp = conn.getresponse()
                header_ms = int((time.monotonic() - t0) * 1000)
                raw = [(k.lower(), v) for k, v in resp.getheaders()]
                try:
                    resp.read()  # HEAD has no body; completes the response
                except Exception:
                    pass
                if getattr(resp, "will_close", False):
                    self._drop(host, port, https)
                return resp.status, dict(raw), "", header_ms
            except (http.client.RemoteDisconnected, ConnectionResetError,
                    BrokenPipeError, http.client.BadStatusLine):
                self._drop(host, port, https)
                self.reconnects += 1
                if attempt == 1:
                    return 0, {}, "conn_reset", int((time.monotonic() - t0) * 1000)
                continue
            except (socket.timeout, TimeoutError):
                return 0, {}, "timeout", int((time.monotonic() - t0) * 1000)
            except socket.gaierror:
                return 0, {}, "dns", int((time.monotonic() - t0) * 1000)
            except ssl.SSLError:
                return 0, {}, "tls", int((time.monotonic() - t0) * 1000)
            except (http.client.HTTPException, OSError) as exc:
                code = "tls" if "ssl" in str(exc).lower() else "conn_error"
                return 0, {}, code, int((time.monotonic() - t0) * 1000)
            except (UnicodeEncodeError, ValueError):
                # Unencodable request line/header (e.g. a non-ASCII Origin
                # from a unicode hostname): our side could not attempt the
                # URL, so it earns no verdict and must not kill the batch.
                return 0, {}, "conn_error", int((time.monotonic() - t0) * 1000)
        return 0, {}, "conn_error", 0  # pragma: no cover - loop guard

    def get(self, url: str, headers: dict, timeout: float | None = None):
        """One ranged GET (``Range: bytes=0-0``). Returns as :meth:`head`.

        The fallback GET asks for one byte only: a server that honours
        Range answers 206 with one byte; one that ignores it answers 200
        with the whole file. Either way the body read is capped at
        ``GET_READ_CAP`` and the connection is dropped afterwards — a
        half-read body cannot be reused by keep-alive, and the fallback
        is rare enough that the reconnect costs nothing measurable.
        Error codes mirror :meth:`head` exactly, so a GET transport
        failure earns no verdict just like a HEAD one.
        """
        self.last_get_body = b""
        parts = urllib.parse.urlsplit(url)
        host = parts.hostname or ""
        try:
            port = parts.port or (443 if parts.scheme == "https" else 80)
        except ValueError:
            # Unparseable port (e.g. :99999): never attempted, no verdict.
            return 0, {}, "conn_error", 0
        https = parts.scheme == "https"
        target = urllib.parse.quote(parts.path or "/", safe="/%")
        if parts.query:
            target += "?" + urllib.parse.quote(parts.query, safe="=&%")
        send_headers = dict(headers)
        send_headers["Range"] = GET_RANGE
        budget = float(self._timeout if timeout is None else timeout)
        for attempt in (0, 1):
            conn = self._conn_for(host, port, https)
            t0 = time.monotonic()
            try:
                conn.request("GET", target, headers=send_headers)
                resp = conn.getresponse()
                header_ms = int((time.monotonic() - t0) * 1000)
                raw = [(k.lower(), v) for k, v in resp.getheaders()]
                try:
                    self.last_get_body = resp.read(GET_READ_CAP) or b""
                except Exception:
                    self.last_get_body = b""
                self._drop(host, port, https)
                return resp.status, dict(raw), "", header_ms
            except (http.client.RemoteDisconnected, ConnectionResetError,
                    BrokenPipeError, http.client.BadStatusLine):
                self._drop(host, port, https)
                self.reconnects += 1
                if attempt == 1:
                    return 0, {}, "conn_reset", int((time.monotonic() - t0) * 1000)
                continue
            except (socket.timeout, TimeoutError):
                return 0, {}, "timeout", int((time.monotonic() - t0) * 1000)
            except socket.gaierror:
                return 0, {}, "dns", int((time.monotonic() - t0) * 1000)
            except ssl.SSLError:
                return 0, {}, "tls", int((time.monotonic() - t0) * 1000)
            except (http.client.HTTPException, OSError) as exc:
                code = "tls" if "ssl" in str(exc).lower() else "conn_error"
                return 0, {}, code, int((time.monotonic() - t0) * 1000)
            except (UnicodeEncodeError, ValueError):
                # Unencodable request line/header: our side could not
                # attempt the URL, so it earns no verdict and must not
                # kill the batch (same backstop as head()).
                return 0, {}, "conn_error", int((time.monotonic() - t0) * 1000)
        return 0, {}, "conn_error", 0  # pragma: no cover - loop guard


def head_chain(url: str, session: HostSession, *, base_headers: dict,
               timeout: float = DEFAULT_TIMEOUT,
               max_redirects: int = DEFAULT_MAX_REDIRECTS,
               spacing_s: float | None = None):
    """Follow a HEAD redirect chain manually. Returns ``(status, error, final_url)``.

    Every hop is issued inside ``link_health.host_gate`` for *its own* host,
    so a redirect from A to B releases A before B is knocked on, and no two
    threads in this process ever knock on one host at once. ``spacing_s``
    defaults to ``link_health.DEFAULT_HOST_SPACING_S`` (the ``RADD_HOST_SPACING_S``
    env default, 1.0s) — the same politeness the sweep already owes.
    """
    if spacing_s is None:
        spacing_s = link_health.DEFAULT_HOST_SPACING_S
    current = url
    seen = {current}
    for hop in range(max_redirects + 1):
        parts = urllib.parse.urlsplit(current)
        headers = dict(base_headers)
        headers["Origin"] = f"{parts.scheme.lower()}://{(parts.netloc or '').lower()}"
        with link_health.host_gate(current, spacing_s=spacing_s,
                                   jitter_s=link_health.DEFAULT_HOST_JITTER_S):
            status, resp_headers, error, _ms = session.head(current, headers, timeout)
        if error:
            return status, error, current
        if 300 <= status < 400:
            loc = resp_headers.get("location", "")
            if not loc:
                return status, "", current
            nxt = urllib.parse.urljoin(current, loc)
            if nxt in seen:
                return status, "redirect_loop", nxt
            if hop == max_redirects:
                return status, "redirect_limit", nxt
            seen.add(nxt)
            current = nxt
            continue
        return status, "", current
    return 0, "redirect_limit", current  # pragma: no cover - loop guard


def get_chain(url: str, session: HostSession, *, base_headers: dict,
              timeout: float = DEFAULT_TIMEOUT,
              max_redirects: int = DEFAULT_MAX_REDIRECTS,
              spacing_s: float | None = None):
    """Follow a ranged-GET redirect chain manually. Returns ``(status, error, final_url)``.

    Mirror of :func:`head_chain` with :meth:`HostSession.get` in place of
    ``head``: every hop inside ``link_health.host_gate`` for *its own*
    host, same redirect budget, same loop/limit codes. The Range header
    is added by ``session.get`` itself, so every hop asks for one byte.
    """
    if spacing_s is None:
        spacing_s = link_health.DEFAULT_HOST_SPACING_S
    current = url
    seen = {current}
    for hop in range(max_redirects + 1):
        parts = urllib.parse.urlsplit(current)
        headers = dict(base_headers)
        headers["Origin"] = f"{parts.scheme.lower()}://{(parts.netloc or '').lower()}"
        with link_health.host_gate(current, spacing_s=spacing_s,
                                   jitter_s=link_health.DEFAULT_HOST_JITTER_S):
            status, resp_headers, error, _ms = session.get(current, headers, timeout)
        if error:
            return status, error, current
        if 300 <= status < 400:
            loc = resp_headers.get("location", "")
            if not loc:
                return status, "", current
            nxt = urllib.parse.urljoin(current, loc)
            if nxt in seen:
                return status, "redirect_loop", nxt
            if hop == max_redirects:
                return status, "redirect_limit", nxt
            seen.add(nxt)
            current = nxt
            continue
        return status, "", current
    return 0, "redirect_limit", current  # pragma: no cover - loop guard


def _needs_get_fallback(host: str, status: int, error: str, verdict: str | None) -> bool:
    """True when a ranged GET may say something HEAD could not.

    Three triggers, and only these:
    * HEAD answered but refused the method (400/405/501): the full
      prober falls through to GET there (``link_health.probe``:
      ``need_get``), so a HEAD-only pass would earn no verdict and the
      row would wait for the full prober. Any host.
    * HEAD was refused by an intermediary (403/429/5xx): the status is
      not a verdict about the link (H4 R10 — the full prober applies the
      same rule), so the GET chain judges instead. A HEAD 404 stays
      immediate: the resource is gone. Any host.
    * The probe target is a known HEAD liar (``KNOWN_HEAD_LIARS``) and
      HEAD did not already prove the link active: the liar's HEAD answer
      (403/404 on a live URL) is not evidence about the link, so the GET
      chain judges instead.
    Transport errors never trigger a GET: a GET would burn the same
    budget on the same failure, and the full prober reports the transport
    truth instead. A 2xx HEAD never triggers the *fallback* either —
    active is active, whatever the host. The parked-HEAD-200 check
    (:func:`_needs_head200_parked_check`) is a separate, narrower path:
    it GETs a HEAD 200 only on a site base or a suspect host, and only
    a parking marker reclassifies the row.
    """
    if error:
        return False
    if int(status or 0) in HEAD_UNSUPPORTED_STATUSES:
        return True
    if int(status or 0) in (403, 429) or int(status or 0) >= 500:
        return True
    if verdict != link_state.STATUS_ACTIVE and is_known_head_liar(host):
        return True
    return False


def _needs_head200_parked_check(host: str, site_hosts, suspect_hosts) -> bool:
    """True when a HEAD-200 answer earns one verifying ranged GET.

    Only a catalog site's own base host (``site_hosts``, the Branch 4
    map) or a host the sweep already suspects (``suspect_hosts``: hosts
    with prior parked/HTML observations in the host registry, loaded
    once per batch). A plain file host never earns one: a parking page
    lives at a domain root / site base, and GETting every HEAD 200
    would roughly double the sweep's requests for HTML-serving hosts.
    The GET itself is narrow — see :func:`_probe_host`: only a parking
    marker reclassifies the row; any other answer keeps HEAD's active
    verdict.
    """
    if site_hosts is not None and host in site_hosts:
        return True
    if suspect_hosts is not None and host in suspect_hosts:
        return True
    return False


# ── mixdrop resolver fallback ──────────────────────────────────────────────────
#
# Mixdrop's file page answers a bare HEAD with 302 to a survey site
# (survey-twist.com) and its embed page answers a bare GET with a JS
# challenge ("Loading..." + a JWT redirect) — the same challenge for a live
# file and for a file that does not exist (measured 2026-11-22 on the Oracle
# VM: a fabricated id gets the identical challenge page). So no bare probe
# can verdict a mixdrop link: HEAD 302 earns no verdict
# (``payload_for_head`` returns None for any 3xx) and the ranged-GET
# fallback never fires (302 is not in ``HEAD_UNSUPPORTED_STATUSES``).
# The only thing that can tell a live mixdrop file from a dead one is the
# resolver (``importers/host_resolvers.py::unwrap_mxdrop``), which completes
# the challenge, unpacks MDCore.wurl and Range-probes the byte endpoint.
# So when the bare probe earns no verdict for a mixdrop URL, the sweep
# routes it through the resolver instead of leaving it unverified.
# The resolver is loaded lazily (same pattern as fast_fetch._embed_resolvers):
# importing it at module top level would couple the sweep to the importers
# package graph. Deliberately NOT done by extending the ranged-GET fallback
# to 3xx: the ranged GET to mixdrop returns 200 survey-page HTML, so a
# naive "200 = active" would mark dead mixdrop links active.
_MIXDROP_HOST_RE = re.compile(r"""(?:^|\.)(?:mxdrop|mixdrop)\.[a-z]{2,}$""",
                              re.I)
_MIXDROP_FILE_PAGE_RE = re.compile(
    r"""^(https?://[^/]+)/f/([A-Za-z0-9_-]+)/?$""")
_MIXDROP_EMBED_PATH_RE = re.compile(
    r"""^/(?:e|embed)/[A-Za-z0-9_-]+/?$""", re.I)

_HOST_RESOLVERS = None  # None = not loaded yet; False = load failed


def _load_host_resolvers():
    """Lazily import ``importers/host_resolvers.py`` (or None if unavailable)."""
    global _HOST_RESOLVERS
    if _HOST_RESOLVERS is None:
        directory = str(Path(__file__).resolve().parent / "importers")
        if directory not in sys.path:
            sys.path.insert(0, directory)
        try:
            import host_resolvers
        except (ImportError, OSError, SyntaxError):
            _HOST_RESOLVERS = False
            return None
        _HOST_RESOLVERS = host_resolvers
    return _HOST_RESOLVERS or None


def _is_mixdrop_url(url: str) -> bool:
    """True when *url* is on a mixdrop/mxdrop host (any TLD).

    A hostname is a mixdrop host only when it IS mixdrop.<tld> /
    mxdrop.<tld> or a subdomain of one — a bare substring match would
    also catch lookalikes like notmixdrop.example or mixdrop.evil.example.
    """
    host = target_host(url or "")
    return bool(_MIXDROP_HOST_RE.search(host))


def _mixdrop_embed_url(url: str) -> str:
    """A mixdrop file page -> its embed URL; an embed URL passes through.

    Returns "" when *url* is not a mixdrop file-page or embed URL.
    """
    if not _is_mixdrop_url(url):
        return ""
    parts = urllib.parse.urlsplit(url)
    if _MIXDROP_EMBED_PATH_RE.match(parts.path or "/"):
        return url
    match = _MIXDROP_FILE_PAGE_RE.match(url)
    if match:
        return f"{match.group(1)}/e/{match.group(2)}"
    return ""


def _mixdrop_verdict(url: str, *, resolvers=None,
                     spacing_s: float | None = None):
    """A mixdrop URL -> (verdict, http_status, error) via the resolver.

    Returns (None, 0, "") when the resolver cannot determine liveness
    (not a mixdrop URL, no embed form, resolver unavailable, unwrap raised,
    or the unwrap status is "unknown"/"unverified"/"error"). Only a resolver
    status of "active" / "dead" / "blocked" becomes a verdict; "blocked"
    maps to EXPIRED (a host refusal, not the link's death — the same mapping
    ``link_state.classify`` applies to 401/403/429).

    The unwrap runs inside ``link_health.host_gate`` so consecutive mixdrop
    unwraps keep the sweep's per-host spacing (mixdrop rate-limits rapid
    requests) instead of hammering the host.
    """
    embed = _mixdrop_embed_url(url)
    if not embed:
        return None, 0, ""
    if resolvers is None:
        resolvers = _load_host_resolvers()
    if resolvers is None:
        return None, 0, ""
    if spacing_s is None:
        spacing_s = link_health.DEFAULT_HOST_SPACING_S
    try:
        with link_health.host_gate(
                embed, spacing_s=spacing_s,
                jitter_s=link_health.DEFAULT_HOST_JITTER_S):
            result = resolvers.unwrap_mxdrop(embed, verify=True)
    except Exception:
        return None, 0, ""
    if not isinstance(result, dict):
        return None, 0, ""
    status = str(result.get("status") or "")
    http_status = int(result.get("http_status") or 0)
    if status == "active":
        return link_state.STATUS_ACTIVE, http_status, ""
    if status == "dead":
        return link_state.STATUS_DEAD, http_status, ""
    if status == "blocked":
        return link_state.STATUS_EXPIRED, http_status, ""
    return None, 0, ""


def _record_move(url: str, final_url: str, stats: dict) -> None:
    """One host-only redirect observation for the self-healing registry.

    The observation is recorded IN ADDITION to the verdict — the verdict
    policy is untouched, and a registry failure never breaks a probe
    (``record_redirect`` never raises; this try is belt-and-braces).
    """
    if host_registry is None or not final_url or final_url == url:
        return
    try:
        obs = host_registry.record_redirect(url, final_url)
        if obs.get("count"):
            stats["redirects_seen"] = stats.get("redirects_seen", 0) + 1
        if obs.get("applied"):
            stats["moves_applied"] = stats.get("moves_applied", 0) + 1
            # Branch 5 (§3 row 5): the batch driver clears the moved
            # host's negative-cache records after the batch — the pair
            # rides in stats so the DB-free prober never touches the
            # catalog itself.
            hosts = stats.setdefault("moves_applied_hosts", [])
            pair = (obs.get("old_host"), obs.get("new_host"))
            if pair[0] and pair[1] and pair not in hosts:
                hosts.append(pair)
    except Exception:
        pass


#: Body heads that prove a fetched body is an HTML page (not the media the
#: ledger expects). Same markers Branch 1's ``verify_media_bytes`` uses for
#: the HTML test, minus the content-type arm — the sweep's GET fallback
#: reads the body, not the header. A Cloudflare-challenge page IS HTML by
#: this test, so the caller must only record observations for a real 200
#: answer (a 403/429 never reaches here), never for a blocked one.
_HTML_BODY_HEADS = (b"<!doctype html", b"<html", b"<head", b"<body")


def _is_html_body(body) -> bool:
    """True when ``body`` (bytes) is an HTML page, not media.

    Pure: no network, no clock. The head is stripped of leading whitespace
    and checked against the HTML openers; a ``<title>`` in the first 512
    bytes also counts (a parking page's title is its marker). An empty or
    binary body is not HTML.
    """
    if not body:
        return False
    if isinstance(body, str):
        body = body.encode("utf-8", "ignore")
    head = bytes(body).lstrip()[:512].lower()
    if head.startswith(_HTML_BODY_HEADS):
        return True
    return b"<title>" in head


def _record_html(url: str, host: str, final_url: str, stats: dict) -> None:
    """One HTML-instead-of-media observation for the self-healing registry.

    Called only for a real 200 HTML answer from the ranged-GET fallback —
    never for a blocked (403/429/Cloudflare) or transport answer, so a WAF
    refusal can never count toward the parked threshold. Recorded IN
    ADDITION to the verdict, like :func:`_record_move`; a registry failure
    never breaks a probe. The redirect target (when the GET chain landed
    on a different host) rides along so the threshold action can validate
    it as the move candidate.
    """
    if host_registry is None or not url:
        return
    try:
        target = None
        try:
            target = urllib.parse.urlsplit(final_url or "").hostname or None
        except ValueError:
            target = None
        if target and target.lower() == (host or "").lower():
            target = None
        obs = host_registry.record_html_observation(
            host, url, redirect_target=target)
        if obs.get("count"):
            stats["html_observations"] = stats.get("html_observations", 0) + 1
        if obs.get("applied"):
            stats["moves_applied"] = stats.get("moves_applied", 0) + 1
            # Branch 5 (§3 row 5): same pair-riding as _record_move —
            # record_html_observation names the pair as host/moved_to.
            hosts = stats.setdefault("moves_applied_hosts", [])
            pair = (obs.get("host"), obs.get("moved_to"))
            if pair[0] and pair[1] and pair not in hosts:
                hosts.append(pair)
    except Exception:
        pass


def probe_headers() -> dict:
    """Request headers for every HEAD hop: the sweep's UA, keep-alive."""
    return {
        "User-Agent": link_health.USER_AGENT,
        "Accept": "*/*",
        "Accept-Language": "en-US,en;q=0.9",
        "Connection": "keep-alive",
    }


# ── row selection (two streaming passes, bounded memory) ──────────────────────

def probe_target(row: dict) -> str:
    """The URL a row is probed at — same choice as ``link_state.default_prober``."""
    return str(row.get("final_url") or row.get("source_url") or "").strip()


def target_host(target: str) -> str:
    """Lowercased hostname of an http(s) probe target, ``""`` when unprobeable."""
    try:
        parts = urllib.parse.urlsplit(target)
    except ValueError:
        return ""
    if parts.scheme.lower() not in ("http", "https"):
        return ""
    return (parts.hostname or "").lower()


def select_rows(conn: sqlite3.Connection, *, max_rows: int = DEFAULT_MAX_ROWS,
                dry_run: bool = False,
                since_seconds: float = COMPARE_SINCE_SECONDS) -> dict:
    """Pick the pilot's row set: biggest hosts first, deduped by target URL.

    Returns ``{"hosts": [(host, [{"url": str, "row_ids": [int], "stored": str|None}])],
    "scanned": int, "probeable": int, "distinct_urls": int, "saved_by_dedup": int}``.

    Pass 1 streams the candidate set once and counts rows per host. Pass 2
    streams it again and keeps rows whose host is in the top set (cumulative
    *capped* host count >= 2x ``max_rows``), capped at 2x ``max_rows`` rows.
    Each host contributes at most ``max(4, max_rows // 24)`` kept rows, so a
    batch spans ~24 hosts instead of collapsing onto the biggest one. Kept
    rows are deduped by exact target URL and taken in host-count-desc, id
    order until ``max_rows`` distinct URLs. ``stored`` is the row's current
    status (dry-run compare only; ``None`` in apply mode).
    """
    max_rows = max(1, int(max_rows))
    keep_cap = max_rows * SELECT_KEEP_MULTIPLIER
    per_host_cap = max(PER_HOST_CAP_FLOOR, max_rows // PER_HOST_CAP_DIVISOR)
    if dry_run:
        since = time.time() - float(since_seconds)
        where = "WHERE checked_at IS NOT NULL AND checked_at >= ?"
        params: tuple = (since,)
        extra = ", status"
    else:
        where = "WHERE checked_at IS NULL"
        params = ()
        extra = ""
    # Pass 1 — host counts.
    counts: dict[str, int] = {}
    scanned = 0
    probeable = 0
    cur = conn.execute(
        f"SELECT source_url, final_url FROM download_sources {where}", params)
    for source_url, final_url in cur:
        scanned += 1
        host = target_host(probe_target({"source_url": source_url,
                                         "final_url": final_url}))
        if not host:
            continue
        probeable += 1
        counts[host] = counts.get(host, 0) + 1
    cur.close()
    # Top hosts: biggest first, until their CAPPED rows cover 2x the pilot.
    # (Counting capped rows, not raw counts: one giant host alone exceeds
    # 2x max_rows, and without the cap the whole batch would be that host.)
    ranked = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))
    top: set[str] = set()
    covered = 0
    for host, count in ranked:
        top.add(host)
        covered += min(count, per_host_cap)
        if covered >= keep_cap:
            break
    # Pass 2 — keep rows of the top hosts, bounded, per-host capped.
    kept: list[dict] = []
    kept_per_host: dict[str, int] = {}
    cur = conn.execute(
        f"SELECT id, source_url, final_url{extra} FROM download_sources {where}",
        params)
    for row in cur:
        if len(kept) >= keep_cap:
            break
        rid, source_url, final_url = row[0], row[1], row[2]
        stored = row[3] if dry_run else None
        target = probe_target({"source_url": source_url, "final_url": final_url})
        host = target_host(target)
        if not host or host not in top:
            continue
        if kept_per_host.get(host, 0) >= per_host_cap:
            continue
        kept_per_host[host] = kept_per_host.get(host, 0) + 1
        kept.append({"id": int(rid), "url": target, "host": host, "stored": stored})
    cur.close()
    # Dedup by exact target URL, host-rank order, then id order.
    rank = {host: i for i, (host, _count) in enumerate(ranked)}
    kept.sort(key=lambda r: (rank.get(r["host"], len(rank)), r["id"]))
    by_url: dict[str, dict] = {}
    order: list[str] = []
    saved = 0
    for item in kept:
        group = by_url.get(item["url"])
        if group is None:
            if len(order) >= max_rows:
                continue
            group = {"url": item["url"], "host": item["host"],
                     "row_ids": [], "stored": item["stored"]}
            by_url[item["url"]] = group
            order.append(item["url"])
        else:
            saved += 1
        group["row_ids"].append(item["id"])
    hosts: list[tuple[str, list[dict]]] = []
    for host, _count in ranked:
        groups = [by_url[u] for u in order if by_url[u]["host"] == host]
        if groups:
            hosts.append((host, groups))
    return {"hosts": hosts, "scanned": scanned, "probeable": probeable,
            "distinct_urls": len(order), "saved_by_dedup": saved}


# ── single writer (the ONLY thread that touches SQLite) ───────────────────────

class VerdictWriter(threading.Thread):
    """Serialise verdicts into the ledger, one transaction per batch.

    Workers submit ``{"row_ids": [...], "status": str, "http_status": int,
    "probe_error": str, "checked_at": float}`` via :meth:`submit`; this
    thread is the only one that executes SQL. A batch commits under
    ``BEGIN IMMEDIATE`` with a 30s busy timeout; a lock raises a retry
    (up to 5, backoff 1s doubling to 16s) instead of dying — the failure
    mode that killed 8 of 15 sweep lanes. ``stats["lock_errors"]`` must be
    0 in the pilot; :meth:`finish` re-raises a lock error that exhausted
    its retries so the run fails loudly instead of silently dropping rows.
    """

    def __init__(self, conn: sqlite3.Connection, *,
                 batch_size: int = WRITE_BATCH,
                 busy_timeout_ms: int = WRITER_BUSY_TIMEOUT_MS,
                 lock_retries: int = WRITER_LOCK_RETRIES):
        super().__init__(name="fast-probe-writer", daemon=True)
        self._conn = conn
        self._batch_size = max(1, int(batch_size))
        self._lock_retries = max(0, int(lock_retries))
        self.queue: queue.Queue = queue.Queue()
        self.stats = {"batches": 0, "rows_written": 0,
                      "lock_retries": 0, "lock_errors": 0}
        self._error: BaseException | None = None
        try:
            conn.execute(f"PRAGMA busy_timeout = {int(busy_timeout_ms)}")
        except sqlite3.Error:
            pass

    def submit(self, item: dict) -> None:
        self.queue.put(item)

    def finish(self) -> dict:
        """Drain the queue, stop the thread, return stats (raise on lock error)."""
        self.queue.put(None)
        self.join()
        if self._error is not None:
            raise self._error
        return dict(self.stats)

    def run(self) -> None:  # noqa: D102 - thread body
        batch: list[dict] = []
        while True:
            item = self.queue.get()
            if item is None:
                if batch:
                    self._flush_guarded(batch)
                return
            batch.append(item)
            if len(batch) >= self._batch_size:
                self._flush_guarded(batch)
                if self._error is not None:
                    return
                batch = []

    def _flush_guarded(self, batch: list[dict]) -> None:
        """Flush one batch; a failure parks the error and stops the thread.

        A silent writer death would drop verdicts without a trace, so any
        unrecoverable error is stored for :meth:`finish` to raise.
        """
        try:
            self._flush(batch)
        except BaseException as exc:  # noqa: BLE001 - fail loud, by design
            self._error = exc

    def _flush(self, batch: list[dict]) -> None:
        params: list[tuple] = []
        for item in batch:
            checked_at = float(item["checked_at"])
            stamp = time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(checked_at))
            for rid in item["row_ids"]:
                params.append((item["status"], int(item["http_status"]), stamp,
                               checked_at, str(item["probe_error"] or ""), int(rid)))
        delay = 1.0
        for attempt in range(self._lock_retries + 1):
            try:
                self._conn.execute("BEGIN IMMEDIATE")
                try:
                    self._conn.executemany(_UPDATE_VERDICT_SQL, params)
                    self._conn.execute("COMMIT")
                except Exception:
                    try:
                        self._conn.execute("ROLLBACK")
                    except sqlite3.Error:
                        pass
                    raise
                self.stats["batches"] += 1
                self.stats["rows_written"] += len(params)
                return
            except sqlite3.OperationalError as exc:
                locked = "locked" in str(exc).lower()
                if locked and attempt < self._lock_retries:
                    self.stats["lock_retries"] += 1
                    time.sleep(delay)
                    delay = min(delay * 2.0, 16.0)
                    continue
                if locked:
                    self.stats["lock_errors"] += 1
                raise


# ── per-host worker ──────────────────────────────────────────────────────────

def _probe_host(host: str, groups: list[dict], *, session_factory=None,
                timeout: float, spacing_s: float | None,
                headers: dict, out_queue=None, clearance=None,
                resolvers=None, stop_event=None, stop_file=None,
                site_hosts: dict | None = None,
                suspect_hosts: set | frozenset | None = None) -> dict:
    """Probe one host's distinct URLs, strictly serially, in id order.

    Returns per-host stats. When ``out_queue`` is given, verdicts are
    submitted to it (apply mode); when it is ``None``, verdicts are returned
    in ``stats["verdicts"]`` as ``{url: (status, http_status, error)}``
    (dry-run compare mode). A URL the SSRF guard refuses, or a HEAD chain
    that earns no verdict, counts as ``no_verdict`` — the row is left alone.

    ``clearance`` (H4 R8) is a ``host_clearance.HostClearance``: the host's
    stored clearance rides on every hop (with the UA that earned it), and
    a 403/503 verdict invalidates the host at once, so a dead clearance
    is never re-sent on the next run. ``None`` probes exactly as before.

    ``site_hosts`` (self-healing Branch 4) maps a catalog site's active
    base host to its site name. When given, a dead or parked verdict on
    a host in that map counts one rotation signal for the site in
    ``stats["site_signals"]`` — the batch-level trigger that queues the
    site for a discovery-chain recheck. A file host is never in the map,
    so its dead rows never queue a site. ``None`` disables the counting.

    ``suspect_hosts`` (parked-HEAD-200, Option A) is the set of hosts
    with prior parked/HTML observations in the host registry, loaded
    once per batch by the driver. Together with ``site_hosts`` it gates
    the HEAD-200 verifying GET: a HEAD 200 on a host in either set earns
    one ranged GET whose body is checked for a parking marker (see the
    check in the walk below). ``None`` disables the suspect half of the
    gate; a plain host earns no GET either way.

    ``stop_event`` / ``stop_file`` (INF-05) are checked before every URL:
    when either asks, the walk stops at once, ``stats["stopped"]`` is True,
    and the verdicts earned so far are already queued. The in-flight URL
    (at most one per host thread) still finishes first.
    """
    session = HostSession(connection_factory=session_factory, timeout=timeout)
    stats: dict = {"host": host, "probed": 0, "verdicts": 0, "by_status": {},
                   "no_verdict": 0, "blocked": 0, "reconnects": 0,
                   "redirects_seen": 0, "moves_applied": 0,
                   "get_fallbacks": 0, "head200_checks": 0,
                   "html_observations": 0,
                   "site_signals": {},
                   "stopped": False, "results": {} if out_queue is None else None}
    # H4 R8: one stored clearance per host, read once per host (not once
    # per URL — the store is the same for the whole serial walk).
    hop_headers = dict(headers)
    if clearance is not None:
        try:
            c_header, c_ua = clearance.header_for(host)
        except Exception:
            c_header, c_ua = None, None
        if c_header:
            hop_headers["Cookie"] = c_header
        if c_ua:
            hop_headers["User-Agent"] = c_ua
    for group in groups:
        if _should_stop(stop_event, stop_file):
            stats["stopped"] = True
            break
        url = group["url"]
        blocked = link_health._block_reason(url, allow_private=False)
        if blocked:
            stats["blocked"] += 1
            stats["no_verdict"] += 1
            continue
        status, error, _final = head_chain(url, session, base_headers=hop_headers,
                                           timeout=timeout, spacing_s=spacing_s)
        # Self-healing host registry (2026-11-18): a HEAD chain that lands
        # on a different host is one observation of a domain move. The
        # observation is recorded IN ADDITION to the verdict below — the
        # verdict policy is untouched, and a registry failure never breaks
        # a probe.
        if not error:
            _record_move(url, _final, stats)
        checked_at = time.time()
        stats["probed"] += 1
        verdict = head_verdict(status, error, checked_at)
        if _needs_get_fallback(host, status, error, verdict):
            # HEAD lied or refused: one ranged GET judges instead. When
            # the GET chain earns a verdict it replaces HEAD's; when it
            # earns none either, the row stays eligible (no verdict is
            # better than a verdict from a method this host mis-answers).
            stats["get_fallbacks"] += 1
            g_status, g_error, g_final = get_chain(
                url, session, base_headers=hop_headers, timeout=timeout,
                spacing_s=spacing_s)
            g_body = getattr(session, "last_get_body", b"") or b""
            g_verdict = chain_verdict(g_status, g_error, checked_at)
            if not g_error and 200 <= int(g_status or 0) < 300:
                # Self-healing Branch 3: the GET fallback is the only place
                # the sweep ever sees a body, so it is the only place a
                # parking page can be proved. A 200 HTML answer is one
                # HTML-instead-of-media observation for the host (never for
                # a blocked/transport answer — a WAF refusal must never
                # count toward the parked threshold); a body carrying a
                # parking marker is a parked verdict, not active.
                if _is_html_body(g_body):
                    _record_html(url, host, g_final, stats)
                if link_state.is_parked_body(g_body):
                    g_verdict = link_state.STATUS_PARKED
                    g_error = "parked"
            if g_verdict is not None:
                status, error, _final = g_status, g_error, g_final
                verdict = g_verdict
                if not g_error:
                    _record_move(url, g_final, stats)
            else:
                verdict = None
        if (verdict == link_state.STATUS_ACTIVE and not error
                and int(status or 0) == 200
                and _needs_head200_parked_check(host, site_hosts, suspect_hosts)):
            # Parked-HEAD-200 (Option A, audit §6): a parking page answers
            # HEAD 200 cleanly, so HEAD alone can never prove it parked —
            # but only a site base or a host the sweep already suspects
            # earns the verifying GET. The GET is narrow on purpose: only
            # a parking marker reclassifies the row (to parked, error
            # "parked" — ``link_state.classify`` already handles that
            # error at :364, so no change there). Any other GET answer —
            # a live page, a blocked answer, a transport failure — keeps
            # HEAD's active verdict. A 200 HTML answer is still one HTML
            # observation for the host, exactly as in the fallback block
            # above (never for a blocked/transport answer, so a WAF
            # refusal can never count toward the parked threshold). The
            # body read is the same 1 KiB cap the fallback uses
            # (``GET_READ_CAP``); a Range-honouring media host answers
            # 206 with one byte, which is neither HTML nor parked.
            stats["head200_checks"] += 1
            g_status, g_error, g_final = get_chain(
                url, session, base_headers=hop_headers, timeout=timeout,
                spacing_s=spacing_s)
            g_body = getattr(session, "last_get_body", b"") or b""
            if not g_error and 200 <= int(g_status or 0) < 300:
                if _is_html_body(g_body):
                    _record_html(url, host, g_final, stats)
                if link_state.is_parked_body(g_body):
                    status, error, _final = g_status, "parked", g_final
                    verdict = link_state.STATUS_PARKED
        if verdict is None and _is_mixdrop_url(url):
            # Mixdrop: the bare probe cannot verdict (file page 302s to a
            # survey; the embed page is a JS challenge that a dead file
            # answers identically). Route through the resolver, which
            # completes the challenge and Range-probes the byte endpoint.
            # A resolver verdict replaces no_verdict; a resolver that
            # cannot determine leaves the row alone.
            r_verdict, r_status, r_error = _mixdrop_verdict(
                url, resolvers=resolvers, spacing_s=spacing_s)
            if r_verdict is not None:
                verdict, status, error = r_verdict, r_status, r_error
        if verdict is None:
            stats["no_verdict"] += 1
            continue
        # H4 R8: a challenged verdict kills the host's stored clearance at
        # once — the next run re-earns it instead of re-sending a dead one.
        # (The capture half lives in the resolver / probe_many: this HEAD
        # chain never sees a Set-Cookie worth keeping.)
        if clearance is not None and status in (403, 503):
            try:
                clearance.note_challenge(host)
            except Exception:
                pass
        stats["verdicts"] += 1
        stats["by_status"][verdict] = stats["by_status"].get(verdict, 0) + 1
        # Self-healing Branch 4: a dead or parked verdict on a catalog
        # site's own base is a rotation signal for that site, counted in
        # ROWS (every row sharing the dead URL), because the queue
        # threshold is a dead-row count. The verdict is final here (HEAD,
        # GET fallback and mixdrop all settled); the host is the row's
        # probe-target host, which is the row's final-URL host in the
        # common case. Only "dead" and "parked" count — an "expired"
        # (5xx/WAF) answer is a sick intermediary, not a moved site,
        # and "active" is no signal at all.
        if (site_hosts is not None
                and verdict in (link_state.STATUS_DEAD, link_state.STATUS_PARKED)
                and host in site_hosts):
            site = site_hosts[host]
            signals = stats["site_signals"]
            signals[site] = signals.get(site, 0) + len(group["row_ids"])
        item = {"row_ids": group["row_ids"], "status": verdict,
                "http_status": int(status or 0),
                "probe_error": error, "checked_at": checked_at}
        if out_queue is not None:
            out_queue.put(item)
        else:
            stats["results"][url] = (verdict, int(status or 0), error)
    stats["reconnects"] = session.reconnects
    return stats


def _merge_stats(stats_list: list[dict]) -> dict:
    merged: dict = {"hosts": 0, "probed": 0, "verdicts": 0, "by_status": {},
                    "no_verdict": 0, "blocked": 0, "reconnects": 0,
                    "redirects_seen": 0, "moves_applied": 0, "get_fallbacks": 0,
                    "head200_checks": 0,
                    "html_observations": 0, "site_signals": {},
                    "moves_applied_hosts": [],
                    "stopped": False}
    for stats in stats_list:
        merged["hosts"] += 1
        merged["probed"] += stats["probed"]
        merged["verdicts"] += stats["verdicts"]
        merged["no_verdict"] += stats["no_verdict"]
        merged["blocked"] += stats["blocked"]
        merged["reconnects"] += stats["reconnects"]
        merged["redirects_seen"] += stats.get("redirects_seen", 0)
        merged["moves_applied"] += stats.get("moves_applied", 0)
        merged["get_fallbacks"] += stats.get("get_fallbacks", 0)
        merged["head200_checks"] += stats.get("head200_checks", 0)
        merged["html_observations"] += stats.get("html_observations", 0)
        for pair in stats.get("moves_applied_hosts") or []:
            if pair not in merged["moves_applied_hosts"]:
                merged["moves_applied_hosts"].append(pair)
        for site, count in (stats.get("site_signals") or {}).items():
            merged["site_signals"][site] = merged["site_signals"].get(site, 0) + count
        merged["stopped"] = merged["stopped"] or bool(stats.get("stopped"))
        for status, count in stats["by_status"].items():
            merged["by_status"][status] = merged["by_status"].get(status, 0) + count
    return merged


def queue_site_rechecks(site_signals: dict, queue_path,
                        registry_path=None) -> list:
    """Queue every site whose batch dead/parked row count crossed the
    threshold (self-healing Branch 4, design §1a step 1).

    ``site_signals`` is the merged per-site row count from one batch
    (``_merge_stats``). Each site at or above
    ``domain_resolver.SITE_ROT_THRESHOLD`` is appended to the recheck
    queue with reason ``"batch-dead-rows:<n>"``; sites below it are
    left alone. Returns the list of sites queued, in signal order.
    A queue failure never raises — the sweep batch must not fail
    because the queue is unavailable; the caller logs the outcome.
    """
    queued = []
    if domain_resolver is None or recheck_queue is None or not site_signals:
        return queued
    for site, count in sorted(site_signals.items(), key=lambda kv: (-kv[1], kv[0])):
        try:
            if domain_resolver.maybe_queue_site_recheck(
                    site, int(count), f"batch-dead-rows:{int(count)}",
                    queue_path, registry_path=registry_path):
                queued.append(site)
        except Exception:
            continue
    return queued


# ── engines ──────────────────────────────────────────────────────────────────

def run_pilot(conn: sqlite3.Connection, *, max_rows: int = DEFAULT_MAX_ROWS,
              workers: int = DEFAULT_WORKERS,
              timeout: float = DEFAULT_TIMEOUT,
              spacing_s: float | None = None,
              session_factory=None, progress=None,
              stop_event=None, stop_file=None,
              registry_path=None) -> dict:
    """Apply mode: probe ``max_rows`` fresh unchecked distinct URLs, write verdicts.

    ``conn`` is the read-only selection connection (closed by the caller);
    the writer opens its own connection on the same path. Returns the run
    summary (JSON-serialisable). Raises ``sqlite3.OperationalError`` when
    the writer exhausts its lock retries — a lock error must be 0, and a
    silent drop would be worse than a loud failure.

    ``stop_event`` / ``stop_file`` (INF-05): when either asks, the run
    stops after each host thread's in-flight URL, the writer flushes the
    verdicts earned so far, and the summary carries ``"stopped": True``
    (``distinct_urls`` still counts the selected batch; unreached rows
    stay unchecked for the next batch). ``stop_event=None`` uses the
    module event set by the SIGTERM/SIGINT handler; ``stop_file=None``
    uses ``stop_file_path()`` (env ``RADD_SWEEP_STOP_FILE``, else
    ``/tmp/fast-probe-sweep.stop``); ``stop_file=""`` disables the file
    watch.
    """
    db_path = _db_path_of(conn)
    selection = select_rows(conn, max_rows=max_rows, dry_run=False)
    event = stop_event if stop_event is not None else _STOP_EVENT
    stop_path = stop_file_path(stop_file)
    if _should_stop(event, stop_path):
        # Stop asked before the pool started: probe nothing, keep the
        # summary shape (writer zeros, selection counts, stopped True).
        selection["hosts"] = []
    # Self-healing Branch 4: the active-base host -> site map for the
    # per-site rotation signal. A registry failure means no counting,
    # never a failed batch.
    site_hosts = None
    if domain_resolver is not None:
        try:
            site_hosts = domain_resolver.site_hosts_map(registry_path)
        except Exception:
            site_hosts = None
    # Parked-HEAD-200 (Option A): hosts with prior parked/HTML
    # observations earn a verifying ranged GET on a HEAD-200 answer.
    # Loaded once per batch, like site_hosts; a registry failure means
    # no suspect set, never a failed batch.
    suspect_hosts = None
    if host_registry is not None:
        try:
            suspect_hosts = host_registry.html_observation_hosts()
        except Exception:
            suspect_hosts = None
    headers = probe_headers()
    # H4 R8: the apply sweep reads the shared clearance store (and
    # invalidates a host the moment it challenges again). An absent
    # module or a failed build means "no store" — the sweep probes
    # exactly as before. Compare mode never gets one: a dry run writes
    # nothing, and attaching a cookie would change what it measures.
    clearance = None
    try:
        try:
            from Fast_Fetch.host_clearance import HostClearance
        except ImportError:
            from host_clearance import HostClearance  # type: ignore
        clearance = HostClearance(db_path)
    except Exception:
        clearance = None
    writer_conn = sqlite3.connect(db_path, timeout=WRITER_BUSY_TIMEOUT_MS / 1000.0,
                                  check_same_thread=False)
    # Serve pragmas on the writer (64 MiB cache, wal_autocheckpoint 1000):
    # the raw connect ran on SQLite's 2 MiB default cache, so each 100-row
    # batch over a 4.7M-row table did more disk I/O and held the write lock
    # longer than it needs to — starving press-path writes (which have no
    # lock retry) and slowing the reads behind the posts 10 s deadline.
    # busy_timeout stays 30 s: apply_serve_pragmas uses the same value.
    db_pragmas.apply_serve_pragmas(writer_conn)
    try:
        link_state.ensure_schema(writer_conn)
        writer = VerdictWriter(writer_conn)
        writer.start()
        started = time.monotonic()
        stats_list: list[dict] = []
        pool_size = max(1, min(int(workers), len(selection["hosts"]) or 1))
        with concurrent.futures.ThreadPoolExecutor(max_workers=pool_size) as pool:
            futures = [pool.submit(_probe_host, host, groups,
                                   session_factory=session_factory,
                                   timeout=timeout, spacing_s=spacing_s,
                                   headers=headers, out_queue=writer.queue,
                                   clearance=clearance,
                                   stop_event=event, stop_file=stop_path,
                                   site_hosts=site_hosts,
                                   suspect_hosts=suspect_hosts)
                       for host, groups in selection["hosts"]]
            for future in concurrent.futures.as_completed(futures):
                stats = future.result()
                stats_list.append(stats)
                if progress:
                    progress(stats)
        elapsed = time.monotonic() - started
        write_stats = writer.finish()
    finally:
        writer_conn.close()
    merged = _merge_stats(stats_list)
    merged["stopped"] = merged["stopped"] or _should_stop(event, stop_path)
    # Self-healing Branch 4: queue the sites whose batch dead/parked
    # row count crossed the threshold. Best-effort — a queue failure
    # must never fail the batch; the summary records what was queued.
    queued_sites: list = []
    if merged.get("site_signals") and recheck_queue is not None:
        try:
            queued_sites = queue_site_rechecks(
                merged["site_signals"],
                recheck_queue.queue_path_for_db(db_path),
                registry_path=registry_path)
        except Exception as exc:  # noqa: BLE001 - queue is best-effort
            print(f"[!] site recheck queue failed: {exc}", file=sys.stderr)
    merged["site_rechecks_queued"] = queued_sites
    # Self-healing Branch 5 (§3 row 5): a host move applied by this
    # batch earns the moved host a fresh verdict — clear its
    # negative-cache records (ledger last_failed_at + resolved_cache
    # fail_count) so the new domain is not refused for the old
    # domain's failures. Best-effort, on its own short-lived
    # connection (the writer is already closed): a clearing failure
    # must never fail the batch; the summary records what was cleared.
    cleared: dict = {}
    if merged.get("moves_applied_hosts"):
        try:
            try:
                from Fast_Fetch import resolved_cache
            except ImportError:
                import resolved_cache  # type: ignore
            _clear_conn = sqlite3.connect(
                db_path, timeout=WRITER_BUSY_TIMEOUT_MS / 1000.0)
            try:
                for _old_host, _new_host in merged["moves_applied_hosts"]:
                    try:
                        cleared[_old_host] = (
                            resolved_cache.clear_host_negative_cache(
                                _clear_conn, _old_host))
                    except Exception:
                        continue
                _clear_conn.commit()
            finally:
                _clear_conn.close()
        except Exception as exc:  # noqa: BLE001 - clearing is best-effort
            print(f"[!] negative-cache clear failed: {exc}", file=sys.stderr)
    merged["negative_cache_cleared"] = cleared
    merged.update({
        "mode": "apply",
        "distinct_urls": selection["distinct_urls"],
        "saved_by_dedup": selection["saved_by_dedup"],
        "scanned": selection["scanned"],
        "probeable": selection["probeable"],
        "hosts_used": len(selection["hosts"]),
        "elapsed_s": round(elapsed, 2),
        "urls_per_sec": round(selection["distinct_urls"] / elapsed, 2) if elapsed > 0 else 0.0,
        "writer": write_stats,
    })
    return merged


def run_compare(conn: sqlite3.Connection, *, max_rows: int = DEFAULT_MAX_ROWS,
                workers: int = DEFAULT_WORKERS,
                timeout: float = DEFAULT_TIMEOUT,
                spacing_s: float | None = None,
                session_factory=None, progress=None,
                stop_event=None, stop_file=None) -> dict:
    """Dry-run compare mode: re-probe ``max_rows`` already-verdict rows, write NOTHING.

    Compares the HEAD-only verdict against the stored status the full prober
    wrote. Agreement is measured over rows where this pass produced a
    verdict; rows with no verdict are reported separately (HEAD refused, or
    a transport error — the row stays eligible either way). Disagreements
    are broken down by (stored, new) pair with up to 25 examples.

    ``stop_event`` / ``stop_file`` behave as in :func:`run_pilot` (INF-05):
    a stop ends the walk after each host thread's in-flight URL and the
    summary carries ``"stopped": True``.
    """
    selection = select_rows(conn, max_rows=max_rows, dry_run=True)
    event = stop_event if stop_event is not None else _STOP_EVENT
    stop_path = stop_file_path(stop_file)
    if _should_stop(event, stop_path):
        selection["hosts"] = []
    headers = probe_headers()
    started = time.monotonic()
    stats_list: list[dict] = []
    pool_size = max(1, min(int(workers), len(selection["hosts"]) or 1))
    with concurrent.futures.ThreadPoolExecutor(max_workers=pool_size) as pool:
        futures = [pool.submit(_probe_host, host, groups,
                               session_factory=session_factory,
                               timeout=timeout, spacing_s=spacing_s,
                               headers=headers, out_queue=None,
                               stop_event=event, stop_file=stop_path)
                   for host, groups in selection["hosts"]]
        for future in concurrent.futures.as_completed(futures):
            stats = future.result()
            stats_list.append(stats)
            if progress:
                progress(stats)
    elapsed = time.monotonic() - started
    merged = _merge_stats(stats_list)
    merged["stopped"] = merged["stopped"] or _should_stop(event, stop_path)
    # Per-row agreement: every row sharing a URL compares against that URL's
    # new verdict (or no-verdict).
    by_url: dict[str, dict] = {}
    for _host, groups in selection["hosts"]:
        for group in groups:
            by_url[group["url"]] = group
    rows_total = 0
    rows_with_verdict = 0
    agreed = 0
    disagreements: dict[tuple[str, str], int] = {}
    examples: list[dict] = []
    for stats in stats_list:
        for url, (verdict, http_status, error) in (stats["results"] or {}).items():
            group = by_url[url]
            for _rid in group["row_ids"]:
                rows_total += 1
                rows_with_verdict += 1
                stored = group["stored"] or ""
                if stored == verdict:
                    agreed += 1
                else:
                    key = (stored, verdict)
                    disagreements[key] = disagreements.get(key, 0) + 1
                    if len(examples) < 25:
                        examples.append({"url": url, "stored": stored,
                                         "new": verdict, "http_status": http_status,
                                         "error": error})
    # Rows whose URL earned no verdict at all.
    for _host, groups in selection["hosts"]:
        for group in groups:
            if group["url"] not in {u for s in stats_list for u in (s["results"] or {})}:
                rows_total += len(group["row_ids"])
    merged.update({
        "mode": "dry-run",
        "distinct_urls": selection["distinct_urls"],
        "saved_by_dedup": selection["saved_by_dedup"],
        "scanned": selection["scanned"],
        "probeable": selection["probeable"],
        "hosts_used": len(selection["hosts"]),
        "elapsed_s": round(elapsed, 2),
        "urls_per_sec": round(selection["distinct_urls"] / elapsed, 2) if elapsed > 0 else 0.0,
        "rows_total": rows_total,
        "rows_with_verdict": rows_with_verdict,
        "rows_no_verdict": rows_total - rows_with_verdict,
        "agreed": agreed,
        "agreement": round(agreed / rows_with_verdict, 4) if rows_with_verdict else 0.0,
        "disagreements": {f"{old}->{new}": count
                          for (old, new), count in sorted(disagreements.items())},
        "examples": examples,
    })
    return merged


def _db_path_of(conn: sqlite3.Connection) -> str:
    """Filesystem path of an open connection (needed for the writer's own)."""
    row = conn.execute("PRAGMA database_list").fetchone()
    if row is None or not row[2]:
        raise sqlite3.OperationalError("fast_probe: cannot find the DB path")
    return str(row[2])


# ── CLI ──────────────────────────────────────────────────────────────────────

def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        prog="fast_probe.py",
        description="Host-partitioned, keep-alive link prober (pilot). "
                    "HEAD first, with a ranged-GET fallback where HEAD lies "
                    "(HEAD 400/405/501, or a host on KNOWN_HEAD_LIARS). "
                    "Probes --max-rows distinct unchecked URLs (biggest hosts "
                    "first) and writes verdicts. --dry-run re-probes "
                    "already-verdict rows and compares, writing nothing.")
    parser.add_argument("--db", required=True, help="catalog SQLite file to work on")
    parser.add_argument("--max-rows", type=int, default=DEFAULT_MAX_ROWS,
                        help="distinct URLs to probe (default 1000)")
    parser.add_argument("--dry-run", action="store_true",
                        help="compare mode: re-probe already-verdict rows, write nothing")
    parser.add_argument("--apply", action="store_true",
                        help="write mode: probe fresh unchecked rows, write verdicts")
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS,
                        help="host threads (default 24; one host is still 1-at-a-time)")
    parser.add_argument("--host-spacing", type=float, default=None,
                        help="seconds between probes to one host (default: "
                             "link_health DEFAULT_HOST_SPACING_S / RADD_HOST_SPACING_S)")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT,
                        help="per-socket seconds (default 8.0)")
    parser.add_argument("--stop-file", type=str, default=None,
                        help="path whose presence asks a running batch to "
                             "stop gracefully (default: $RADD_SWEEP_STOP_FILE "
                             "or " + SWEEP_STOP_FILE + "; empty string "
                             "disables the watch)")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    if bool(args.dry_run) == bool(args.apply):
        print("[!] pass exactly one of --dry-run (compare, writes nothing) "
              "or --apply (probe + write)", file=sys.stderr)
        return 2
    path = Path(args.db)
    if not path.exists():
        print(f"[!] no such database: {path}", file=sys.stderr)
        return 2
    spacing = args.host_spacing if args.host_spacing is not None else None
    # INF-05: a SIGTERM/SIGINT now asks for a graceful stop (flush + exit 0
    # with "stopped": true) instead of killing the process mid-write; a
    # second signal still kills at once. The batch also watches the stop
    # file, so an orphaned child exits on its own when the gate stops it.
    stop_event = install_stop_handlers()
    if args.dry_run:
        conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True,
                                timeout=WRITER_BUSY_TIMEOUT_MS / 1000.0)
        try:
            summary = run_compare(
                conn, max_rows=args.max_rows, workers=args.workers,
                timeout=args.timeout, spacing_s=spacing,
                stop_event=stop_event, stop_file=args.stop_file,
                progress=lambda s: print(
                    f"  [{s['host']}] probed={s['probed']} verdicts={s['verdicts']} "
                    f"{s['by_status']} no_verdict={s['no_verdict']}", flush=True))
        finally:
            conn.close()
        print(json.dumps(summary, indent=2, sort_keys=True))
        if summary.get("stopped"):
            print("[i] stopped early (signal or stop file); "
                  "nothing was written (dry-run)", file=sys.stderr)
        return 0
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True,
                            timeout=WRITER_BUSY_TIMEOUT_MS / 1000.0)
    try:
        summary = run_pilot(
            conn, max_rows=args.max_rows, workers=args.workers,
            timeout=args.timeout, spacing_s=spacing,
            stop_event=stop_event, stop_file=args.stop_file,
            progress=lambda s: print(
                f"  [{s['host']}] probed={s['probed']} verdicts={s['verdicts']} "
                f"{s['by_status']} no_verdict={s['no_verdict']}", flush=True))
    finally:
        conn.close()
    print(json.dumps(summary, indent=2, sort_keys=True))
    if summary.get("stopped"):
        print("[i] stopped early (signal or stop file); verdicts earned "
              "so far are flushed", file=sys.stderr)
    if summary["writer"]["lock_errors"] > 0:
        print("[!] writer lock errors > 0 — the pilot FAILED its lock criterion",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
