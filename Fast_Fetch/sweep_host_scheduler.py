#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sweep_host_scheduler.py — the single-writer, host-partitioned link sweep.

Why this exists
---------------
The per-site lanes (``link_state.py --site <site>``, 15 writers behind
``run_lane.sh``) cannot hold: measured 2026-11-18, the 15-writer design
collapsed TWICE in one day on SQLite writer locks — 12 lanes dead 15:44,
7 more dead ~16:22, a third wave dead 16:36 (8 of 15) — and the rate fell
from 7.6 rows/s to ~0.5–0.7 rows/s. Every lane holds the same write lock
pattern (``BEGIN IMMEDIATE`` per 200-row batch) while 14 siblings queue
behind it, and ``run_lane.sh``'s 30 s retry only spaces the collisions out.
Relaunching the same way repeats the failure, so this scheduler replaces
the *shape*, not the verdicts:

1. **One writer.** A single process owns the only write connection. Probing
   is parallel; writing is serial, in the same bounded ``BEGIN IMMEDIATE``
   batches ``link_state._apply_batch`` already uses. No lock storm is
   possible by construction.
2. **Partition by host, not by site.** The site lanes queue on the same
   host locks (one site's links fan out over few hosts, and several sites
   share the same hosts). Work is grouped by the authority that answers —
   ``urlsplit(url).netloc`` — and one worker drains one host at a time, so
   N workers are always on N *different* hosts.
3. **Dedup before probing.** Measured 2026-11-18: 4,388,744 unchecked rows
   carry only 3,642,559 distinct probe targets (17% dupes, ~746k wasted
   probes). One probe per target URL; the verdict fans out to every row
   that carries it.
4. **Keep-alive + HEAD-only (Phase 1).** The old prober pays a fresh
   TCP+TLS handshake per request (``Connection: close``) and then a
   Range-GET of up to 512 KiB per row — but the verdict needs only the HEAD
   status. This tool probes HEAD over a persistent connection (httpx,
   already installed on the VM, HTTP/1.1 keep-alive; no ``h2`` module, so
   no HTTP/2) and never downloads a body. A HEAD refused with 400/405/501
   falls back to a 1-byte Range GET (``bytes=0-0``, streamed, body never
   read) instead of being misread as dead — a host that does not support
   HEAD is not a dead link.
5. **A DNS cache.** The guard resolves each host once per run instead of
   once per URL (millions of ``getaddrinfo`` calls become ~1,166), and a
   host that does not resolve at plan time is skipped with no verdict —
   it is re-planned on the next pass, never marked.

What this tool does NOT change
------------------------------
* **Verdict policy, verbatim.** Classification and column writes go through
  ``link_state.classify`` / ``link_state.verdict_for`` / ``_apply_batch`` —
  the same functions the lanes use. 401/403/429 → ``expired``, never dead;
  transport errors earn no verdict; only real HTTP errors mark dead. The
  probe *result* is folded by ``link_health._finalize`` itself (the httpx
  path fabricates the ``_Exchange`` the stdlib path already builds), so the
  HEAD-only verdicts are byte-identical to ``link_health.probe(...,
  allow_download=False)`` on the same response.
* **Phase 1 never marks a row dead for body reasons.** No body is ever
  read, so ``empty_body`` cannot be judged and is never emitted; dead comes
  only from an HTTP status. The Range-GET ranking-fields pass (file size,
  speed, body sniffing) stays a later phase — this tool writes the HEAD
  evidence (status, content-type, content-length, ranges, CORS, latency)
  and says so in every summary.
* **Per-host politeness.** One probe at a time per host, ``spacing_s``
  (default ``link_health.DEFAULT_HOST_SPACING_S``, 1.0 s) between probes to
  the same host, measured from the end of the previous probe — the same
  rule the old prober enforces. Per-host concurrency stays 1 in Phase 1;
  raising it needs a pilot that measures 429s first.
* **Resume semantics.** The slice is id-ordered over the same
  ``only_unchecked`` predicate the lanes use, under its own cursor scope
  (``sweep-host``), so rows the surviving lanes already banked are never
  re-probed, and a kill resumes after the last committed batch.

Sweep-only dependency note: the backend is stdlib-only, and this module
stays importable and fully testable without httpx — the stdlib fallback
prober is ``link_health.probe(allow_download=False)`` and the test suite
injects fake probers. httpx is used only when installed (it is, on the
Oracle VM, 0.28.1); it never enters the live server code.

Usage (dry-run first, always)::

    python3 sweep_host_scheduler.py --db /path/to/catalog.db --dry-run \\
        --max-rows 300 --hosts vcloud.beer,hubcloud.ist --workers 5
    python3 sweep_host_scheduler.py --db /path/to/catalog.db --apply \\
        --max-rows 20000 --workers 8

Stdlib only except the optional httpx fast path. No network in ``--plan``.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import ipaddress
import json
import socket
import sqlite3
import sys
import threading
import time
import urllib.parse
from pathlib import Path

try:  # normal package-style import when run as Fast_Fetch/sweep_host_scheduler.py
    from Fast_Fetch import link_health, link_state
except ImportError:  # running from inside Fast_Fetch/ (the documented way)
    import os

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import link_health  # type: ignore
    import link_state  # type: ignore

try:
    import httpx
except ImportError:  # pragma: no cover - exercised on hosts without httpx
    httpx = None  # type: ignore

__all__ = [
    "SCOPE", "DEFAULT_WORKERS", "DEFAULT_SLICE_ROWS", "DEFAULT_TIMEOUT",
    "host_of_url", "probe_target", "DnsCache", "plan_slice",
    "HttpxHeadProber", "StdlibHeadProber", "make_prober", "run_scheduler",
]

SCOPE = "sweep-host"
DEFAULT_WORKERS = 8
DEFAULT_SLICE_ROWS = 20000
DEFAULT_TIMEOUT = link_health.DEFAULT_TIMEOUT  # 8.0 s per request
#: Rows per write batch — the same bound the lanes use, so one batch never
#: holds the write lock long enough to starve the live service.
DEFAULT_BATCH_SIZE = link_state.DEFAULT_BATCH_SIZE
#: HEAD refused: the statuses that mean "this host does not do HEAD", not
#: "this link is dead". Mirrors link_health.probe's own ``need_get`` set.
HEAD_UNSUPPORTED = (400, 405, 501)

# ── URL helpers ──────────────────────────────────────────────────────────────

def host_of_url(url: str) -> str:
    """Authority that answers, lowercased — the partition key.

    Identical to ``link_state._host_of_url`` (netloc, not hostname) so the
    scheduler, the sweep and the serve path key the same host the same way.
    """
    try:
        return urllib.parse.urlsplit(str(url or "")).netloc.lower()
    except ValueError:
        return ""


def probe_target(row: dict) -> str:
    """The URL a probe actually knocks on: ``final_url`` when the ledger has
    one, else ``source_url``. This is the dedup key — two rows sharing a
    target share one probe. (Unchecked rows almost always carry only
    ``source_url``; the ``final_url`` arm exists so a re-probe pass dedups
    on the same key the prober used.)"""
    return str(row.get("final_url") or row.get("source_url") or "").strip()


# ── DNS cache + SSRF guard ───────────────────────────────────────────────────

class DnsCache:
    """One ``getaddrinfo`` per host per run, instead of one per URL.

    The cache is consulted ONLY for the guard decision (is this host safe to
    knock on?) — the HTTP client still resolves at connect time, so a stale
    entry can never steer a connection. A host that does not resolve at plan
    time is skipped with no verdict and re-planned on the next pass.
    """

    def __init__(self):
        self._cache: dict[str, tuple[str, ...] | None] = {}
        self.resolved = 0
        self.failed = 0

    def resolve(self, host: str) -> tuple[str, ...] | None:
        """Resolved addresses for *host*, or None when it does not resolve."""
        key = (host or "").strip().lower().rstrip(".")
        if key in self._cache:
            return self._cache[key]
        try:
            infos = socket.getaddrinfo(key, None, proto=socket.IPPROTO_TCP)
            addrs = tuple(dict.fromkeys(info[4][0] for info in infos if info[4]))
        except (socket.gaierror, UnicodeError, OSError):
            addrs = None
        self._cache[key] = addrs
        if addrs:
            self.resolved += 1
        else:
            self.failed += 1
        return addrs

    def blocked_reason(self, host: str) -> str:
        """Why *host* must not be probed; ``""`` means allowed.

        *host* is a netloc or a bare hostname — a port (``127.0.0.1:9``)
        and IPv6 brackets (``[::1]:8080``) are stripped before the check,
        because ``ipaddress`` rejects both and an unparsed literal would
        slip through as "not private".

        Mirrors ``link_health._blocked_host_reason`` rule for rule: localhost,
        literal private/loopback/link-local/multicast/reserved/unspecified
        addresses, and names that resolve to one. Unresolvable names return
        ``""`` — the connect attempt reports ``dns`` there, exactly as the
        stdlib prober does.
        """
        h = (host or "").strip().lower().rstrip(".")
        if h.startswith("["):  # IPv6 netloc form: [::1] or [::1]:8080
            h = h[1:].split("]", 1)[0]
        elif h.count(":") == 1:  # hostname:port — strip the port
            h = h.split(":", 1)[0]
        if not h:
            return "empty host"
        if h == "localhost" or h.endswith(".localhost"):
            return "localhost"
        try:
            addr = ipaddress.ip_address(h)
        except ValueError:
            addr = None
        if addr is not None:
            if (addr.is_private or addr.is_loopback or addr.is_link_local
                    or addr.is_multicast or addr.is_reserved or addr.is_unspecified):
                return f"private address {addr}"
            return ""
        addrs = self.resolve(h)
        if addrs is None:
            return ""
        for raw in addrs:
            try:
                addr = ipaddress.ip_address(raw)
            except ValueError:
                continue
            if (addr.is_loopback or addr.is_private or addr.is_link_local
                    or addr.is_multicast or addr.is_reserved or addr.is_unspecified):
                return f"{h} resolves to private address {addr}"
        return ""


# ── planning: slice, dedup, partition ────────────────────────────────────────

def plan_slice(conn: sqlite3.Connection, *, scope: str = SCOPE,
               max_rows: int = DEFAULT_SLICE_ROWS, hosts: set[str] | None = None,
               site: str | None = None, dns: DnsCache | None = None) -> dict:
    """Collect one id-ordered slice of rows that need a verdict.

    Walks the same ``only_unchecked`` predicate the lanes use (via
    ``link_state.iter_pending``), so rows the surviving lanes already banked
    are excluded and the slice cursor resumes where the last run stopped.
    Returns the plan: representative rows grouped by host, sibling ids per
    target URL, the slice-end id, and dedup statistics.

    ``hosts`` restricts the slice to those authorities (pilot mode). A host
    that fails DNS at plan time is skipped with no verdict — its rows stay
    unverified and are re-planned on the next pass.
    """
    dns = dns or DnsCache()
    evidence = link_state._available_evidence(conn)
    cursor_row = link_state._cursor_row(conn, scope) if link_state._has_table(
        conn, "link_state_cursor") else None
    cursor = int(cursor_row["last_id"] or 0) if cursor_row is not None else 0
    wanted = {h.strip().lower() for h in hosts or () if h and h.strip()}
    collected: list[dict] = []
    exhausted = False
    scanned = 0
    # The slice cursor advances ONLY past rows this slice consumed. The
    # pending scan returns whole pages; advancing past an unconsumed page
    # would strand its rows below the cursor for this scope forever.
    consumed_end = 0
    while len(collected) < int(max_rows):
        rows, page_end = link_state.iter_pending(
            conn, after_id=cursor, batch_size=1000, only_unchecked=True,
            stale_after_seconds=None, site=site, evidence=evidence)
        if not rows:
            exhausted = True
            cursor = int(page_end)
            break
        scanned += len(rows)
        cursor = int(page_end)
        for row in rows:
            if len(collected) >= int(max_rows):
                break
            target = probe_target(row)
            if not target:
                continue
            host = host_of_url(target)
            if wanted and host not in wanted:
                continue
            collected.append(row)
            consumed_end = max(consumed_end, int(row["id"]))
    # Dedup on the probe target: the lowest id is the representative, the
    # rest are siblings that receive the same verdict without a probe.
    by_target: dict[str, list[dict]] = {}
    for row in collected:
        by_target.setdefault(probe_target(row), []).append(row)
    representatives: dict[str, list[dict]] = {}
    siblings: dict[str, list[int]] = {}
    dns_skipped: dict[str, int] = {}
    for target, rows_for_target in by_target.items():
        ordered = sorted(rows_for_target, key=lambda r: int(r["id"]))
        host = host_of_url(target)
        reason = dns.blocked_reason(host) if host else "empty host"
        if reason:
            dns_skipped[host or "(no host)"] = dns_skipped.get(
                host or "(no host)", 0) + len(ordered)
            continue
        representatives.setdefault(host, []).append(ordered[0])
        if len(ordered) > 1:
            siblings[target] = [int(r["id"]) for r in ordered[1:]]
    rows_by_id = {int(r["id"]): r for r in collected}
    return {
        "scope": scope,
        "representatives": representatives,   # host -> [representative rows]
        "siblings": siblings,                 # target url -> [sibling row ids]
        "rows_by_id": rows_by_id,
        # Highest id actually consumed (0 when the slice is empty): the next
        # run resumes after it, so unconsumed rows in a scanned page are
        # re-planned instead of stranded. When the slice is empty the scan
        # end is the honest resume point.
        "slice_end_id": consumed_end if consumed_end else cursor,
        "exhausted": exhausted,
        "rows_scanned": scanned,
        "rows_collected": len(collected),
        "rows_planned": sum(len(v) for v in representatives.values()),
        "dupes_saved": sum(len(v) for v in siblings.values()),
        "dns_skipped": dns_skipped,
        "hosts": sorted(representatives),
    }


# ── probers: HEAD-only, keep-alive when httpx is available ──────────────────

def _now() -> float:
    return time.time()


def _transport_code(exc: Exception) -> str:
    """httpx failure -> the stable error code the verdict policy reads.

    Every code here is a transport cause (``link_state._TRANSPORT_ERRORS``
    family): no verdict is earned, the row stays unverified.
    """
    if httpx is not None and isinstance(exc, httpx.TimeoutException):
        return "timeout"
    text = str(exc).lower()
    if ("name or service not known" in text or "temporary failure in name resolution" in text
            or "nodename nor servname" in text or "dns" in text and "resolve" in text):
        return "dns"
    if "ssl" in text or "certificate" in text or "tls" in text:
        return "tls"
    if "connection reset" in text:
        return "conn_reset"
    return "conn_error"


def _head_result(url: str, *, status: int, headers: dict, latency_ms: int,
                 final_url: str, now: float) -> "link_health.ProbeResult":
    """Fold one HEAD (or 1-byte Range GET) into a ``ProbeResult``.

    The folding is ``link_health._finalize`` itself, called exactly as the
    stdlib prober calls it for ``allow_download=False`` (``head_only=True``,
    the response as both the exchange and the head) — so a HEAD-only verdict
    here is identical to the stdlib HEAD-only verdict on the same response.
    No body is ever read, so ``empty_body`` cannot be judged and is never
    emitted: dead comes only from an HTTP status.
    """
    exch = link_health._Exchange(  # noqa: SLF001 — same-package reuse, pinned by test
        status=int(status),
        headers={str(k).lower(): str(v) for k, v in dict(headers).items()},
        body=b"", error="", header_ms=int(latency_ms), body_ms=0)
    return link_health._finalize(  # noqa: SLF001 — same-package reuse, pinned by test
        url, exch, final_url, b"",
        error=link_health._status_error(status) if status >= 400 else "",
        latency_ms=int(latency_ms), now=float(now),
        origin=f"{urllib.parse.urlsplit(url).scheme.lower()}://"
               f"{urllib.parse.urlsplit(url).netloc.lower()}",
        redirects_to=final_url if final_url != url else "",
        head=exch, head_only=True, hint_ext="")


class HttpxHeadProber:
    """HEAD over one persistent connection per worker thread.

    ``httpx`` keeps the connection alive across the host's queue, so the
    per-request cost is one HEAD on a warm connection instead of a fresh
    TCP+TLS handshake. A HEAD refused with 400/405/501 falls back to a
    1-byte Range GET (streamed, body never read) on the same client.
    """

    def __init__(self, *, timeout: float = DEFAULT_TIMEOUT,
                 dns: DnsCache | None = None):
        if httpx is None:
            raise RuntimeError("httpx is not installed; use prober='stdlib'")
        self._timeout = float(timeout)
        self._dns = dns or DnsCache()
        self._local = threading.local()

    def _client(self) -> "httpx.Client":
        client = getattr(self._local, "client", None)
        if client is None:
            client = httpx.Client(
                timeout=httpx.Timeout(self._timeout),
                limits=httpx.Limits(max_connections=4, max_keepalive_connections=2),
                follow_redirects=True, http2=False)
            self._local.client = client
        return client

    def probe(self, url: str) -> "link_health.ProbeResult":
        now = _now()
        parts = urllib.parse.urlsplit(str(url or ""))
        if parts.scheme.lower() not in ("http", "https"):
            return link_health.ProbeResult(url=url, ok=False, http_status=0,
                                           protocol="unusable", error="invalid_url",
                                           checked_at=now)
        reason = self._dns.blocked_reason(parts.netloc)
        if reason:
            return link_health.ProbeResult(url=url, ok=False, http_status=0,
                                           protocol="unusable", error="blocked_target",
                                           checked_at=now)
        client = self._client()
        started = time.monotonic()
        try:
            resp = client.head(url)
            latency_ms = int((time.monotonic() - started) * 1000)
            if resp.status_code in HEAD_UNSUPPORTED:
                # HEAD is refused, not dead: one 1-byte Range GET decides.
                started = time.monotonic()
                with client.stream("GET", url,
                                   headers={"Range": "bytes=0-0"}) as ranged:
                    latency_ms = int((time.monotonic() - started) * 1000)
                    return _head_result(url, status=ranged.status_code,
                                        headers=dict(ranged.headers),
                                        latency_ms=latency_ms,
                                        final_url=str(ranged.url), now=now)
            return _head_result(url, status=resp.status_code,
                                headers=dict(resp.headers),
                                latency_ms=latency_ms,
                                final_url=str(resp.url), now=now)
        except Exception as exc:  # noqa: BLE001 — one URL never fails the run
            code = "redirect_limit" if (httpx is not None and isinstance(
                exc, httpx.TooManyRedirects)) else _transport_code(exc)
            return link_health.ProbeResult(url=url, ok=False, http_status=0,
                                           protocol="unusable", error=code,
                                           checked_at=now)


class StdlibHeadProber:
    """The stdlib fallback: ``link_health.probe(allow_download=False)``.

    Same HEAD-only verdicts, no keep-alive (fresh connection per request),
    and its own per-host spacing gate — so the scheduler must NOT add its
    own spacing on top (``owns_spacing`` is False; see ``make_prober``).
    """

    owns_spacing = False

    def __init__(self, *, timeout: float = DEFAULT_TIMEOUT):
        self._timeout = float(timeout)

    def probe(self, url: str) -> "link_health.ProbeResult":
        return link_health.probe(url, allow_download=False,
                                 timeout=self._timeout)


def make_prober(kind: str = "auto", *, timeout: float = DEFAULT_TIMEOUT,
                dns: DnsCache | None = None):
    """(prober, owns_spacing): the fast path when httpx exists, else stdlib.

    ``owns_spacing`` tells the scheduler whether the prober already enforces
    per-host spacing itself (stdlib does, via its host gate) — the scheduler
    only spaces when the prober does not.
    """
    kind = (kind or "auto").strip().lower()
    if kind == "stdlib":
        return StdlibHeadProber(timeout=timeout), False
    if kind == "httpx":
        return HttpxHeadProber(timeout=timeout, dns=dns), True
    if httpx is not None:
        return HttpxHeadProber(timeout=timeout, dns=dns), True
    return StdlibHeadProber(timeout=timeout), False


# ── the run ──────────────────────────────────────────────────────────────────

def run_scheduler(conn: sqlite3.Connection, *, scope: str = SCOPE,
                  max_rows: int = DEFAULT_SLICE_ROWS,
                  workers: int = DEFAULT_WORKERS,
                  spacing_s: float | None = None,
                  hosts: set[str] | None = None,
                  site: str | None = None,
                  prober=None, prober_kind: str = "auto",
                  timeout: float = DEFAULT_TIMEOUT,
                  dry_run: bool = True, plan_only: bool = False,
                  batch_size: int = DEFAULT_BATCH_SIZE,
                  dns: DnsCache | None = None,
                  progress=None) -> dict:
    """Plan, probe and (unless dry-run) write one host-partitioned slice.

    One writer: only this function touches the write connection, and it
    writes in bounded batches through ``link_state._apply_batch`` — the
    probing threads never touch the database. ``dry_run`` probes for real
    but writes nothing and moves no cursor (the pilot mode); ``plan_only``
    counts without touching the network at all.
    """
    started = time.time()
    run_id = f"{int(started * 1000)}-{scope}"
    dns = dns or DnsCache()
    plan = plan_slice(conn, scope=scope, max_rows=max_rows, hosts=hosts,
                      site=site, dns=dns)
    summary: dict = {
        "scope": scope, "run_id": run_id, "dry_run": bool(dry_run),
        "plan_only": bool(plan_only), "prober": prober_kind,
        "hosts": plan["hosts"], "rows_scanned": plan["rows_scanned"],
        "rows_collected": plan["rows_collected"],
        "rows_planned": plan["rows_planned"],
        "dupes_saved": plan["dupes_saved"],
        "dns_skipped": plan["dns_skipped"],
        "slice_end_id": plan["slice_end_id"],
        "state": "complete" if plan["exhausted"] else "running",
        "rows_probed": 0, "rows_updated": 0, "by_status": {},
        "http_429": 0, "probe_exceptions": 0, "duration_s": 0.0,
        "rows_per_sec": 0.0,
    }
    if plan_only:
        summary["note"] = ("plan: counted the slice, made no request, wrote "
                           "nothing. Pass --dry-run to probe it, --apply to write it.")
        summary["duration_s"] = round(time.time() - started, 3)
        return summary
    if not plan["representatives"]:
        summary["duration_s"] = round(time.time() - started, 3)
        return summary

    if prober is None:
        prober, owns_spacing = make_prober(prober_kind, timeout=timeout, dns=dns)
    else:
        # Injected probers (tests, custom engines) are assumed NOT to gate:
        # the scheduler owns per-host spacing, exactly as for httpx.
        owns_spacing = True
    spacing = link_health.DEFAULT_HOST_SPACING_S if spacing_s is None else float(spacing_s)
    if not owns_spacing:
        spacing = 0.0  # the stdlib prober's host gate owns politeness

    # Probe: one task per host; each worker drains its host's queue
    # sequentially with per-host spacing. Results keyed by representative id.
    results: dict[int, object] = {}
    errors: dict[int, str] = {}

    def _drain(host: str, rows_for_host: list[dict]) -> None:
        next_allowed = 0.0
        for row in rows_for_host:
            if spacing > 0:
                wait = next_allowed - time.monotonic()
                if wait > 0:
                    time.sleep(wait)
            target = probe_target(row)
            try:
                payload = prober.probe(target)
            except Exception as exc:  # noqa: BLE001 — one URL never fails the run
                payload = link_health.ProbeResult(
                    url=target, ok=False, http_status=0, protocol="unusable",
                    error="conn_error", checked_at=_now())
                errors[int(row["id"])] = f"{type(exc).__name__}: {exc}"[:200]
            results[int(row["id"])] = payload
            next_allowed = time.monotonic() + spacing

    probe_started = time.monotonic()
    pool_size = max(1, min(int(workers), len(plan["representatives"])))
    with concurrent.futures.ThreadPoolExecutor(max_workers=pool_size) as pool:
        futures = [pool.submit(_drain, host, rows_for_host)
                   for host, rows_for_host in plan["representatives"].items()]
        for future in concurrent.futures.as_completed(futures):
            future.result()  # _drain never raises; this surfaces a bug loudly
    probe_seconds = max(time.monotonic() - probe_started, 1e-6)

    # Fan out: every sibling row gets its representative's verdict.
    updates: list[dict] = []
    by_status: dict[str, int] = {}
    http_429 = 0
    for host, rows_for_host in plan["representatives"].items():
        for row in rows_for_host:
            payload = results.get(int(row["id"]))
            item = link_state.verdict_for(row, payload)
            if item is None:
                continue  # transport / refused: no verdict, row stays pending
            updates.append(item)
            by_status[item["status"]] = by_status.get(item["status"], 0) + 1
            if int(item["http_status"] or 0) == 429:
                http_429 += 1
            for sibling_id in plan["siblings"].get(probe_target(row), []):
                sibling = plan["rows_by_id"].get(int(sibling_id))
                if sibling is None:
                    continue
                sibling_item = link_state.verdict_for(sibling, payload)
                if sibling_item is None:
                    continue
                updates.append(sibling_item)
                by_status[sibling_item["status"]] = \
                    by_status.get(sibling_item["status"], 0) + 1
                if int(sibling_item["http_status"] or 0) == 429:
                    http_429 += 1
    updates.sort(key=lambda item: int(item["id"]))
    summary["rows_probed"] = len(results)
    summary["by_status"] = by_status
    summary["http_429"] = http_429
    summary["probe_exceptions"] = len(errors)
    summary["duration_s"] = round(time.time() - started, 3)
    summary["rows_per_sec"] = round(len(results) / probe_seconds, 2)
    summary["probe_seconds"] = round(probe_seconds, 3)

    if dry_run:
        summary["rows_updated"] = 0
        summary["would_update"] = len(updates)
        return summary

    # Write: bounded batches, cursor per batch, host health per batch.
    link_state.ensure_schema(conn)
    sync_proj = link_state._has_table(conn, "cat_links")
    if sync_proj:
        link_state.ensure_projection_schema(conn)
    applied = 0
    for offset in range(0, len(updates), max(1, int(batch_size))):
        batch = updates[offset:offset + int(batch_size)]
        cursor_to = int(batch[-1]["id"])
        applied += link_state._apply_batch(
            conn, scope, run_id, started, batch, cursor_to,
            sync_proj=sync_proj, evidence=True, batch_rows=len(batch))
        # Its own transaction: an open implicit transaction here would make
        # the NEXT batch's BEGIN IMMEDIATE fail ("cannot start a transaction
        # within a transaction").
        with conn:
            link_state._record_host_health(conn, link_state._host_outcomes(batch))
        if progress:
            progress({"applied": applied, "of": len(updates),
                      "last_id": cursor_to})
    with conn:
        link_state._write_cursor(
            conn, scope, last_id=0 if plan["exhausted"] else plan["slice_end_id"],
            rows_checked=0, rows_updated=0, rows_probed=0,
            state=summary["state"], run_id=run_id, started_at=started,
            detail=json.dumps(by_status, sort_keys=True))
    summary["rows_updated"] = applied
    return summary


# ── CLI ──────────────────────────────────────────────────────────────────────

def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        prog="sweep_host_scheduler.py",
        description="Host-partitioned link sweep: one writer, dedup, HEAD-only "
                    "keep-alive probes. Dry-run by default; --apply writes.")
    parser.add_argument("--db", required=True, help="catalog SQLite file to work on")
    parser.add_argument("--scope", default=SCOPE,
                        help="cursor name (default %(default)s)")
    parser.add_argument("--apply", action="store_true",
                        help="actually write verdicts; without it this is a dry run")
    parser.add_argument("--plan", action="store_true",
                        help="count the slice only: no requests, no writes")
    parser.add_argument("--max-rows", type=int, default=DEFAULT_SLICE_ROWS,
                        help="rows per slice (default %(default)s)")
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS,
                        help="concurrent hosts (default %(default)s)")
    parser.add_argument("--hosts", default=None,
                        help="comma-separated authorities to probe (pilot mode)."
                             " A filtered --apply run MUST use its own --scope:"
                             " the production cursor would otherwise skip the"
                             " filtered hosts' rows. Dry runs never move a cursor.")
    parser.add_argument("--site", default=None,
                        help="only links under one source_site")
    parser.add_argument("--spacing-s", type=float, default=None,
                        help="seconds between probes to the same host "
                             "(default: link_health's 1.0 s)")
    parser.add_argument("--prober", default="auto",
                        choices=["auto", "httpx", "stdlib"],
                        help="probe engine (default auto: httpx when installed)")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT)
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--json", action="store_true")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    path = Path(args.db)
    if not path.exists():
        print(f"[!] no such database: {path}", file=sys.stderr)
        return 2
    hosts = {h.strip() for h in (args.hosts or "").split(",") if h.strip()} or None
    if args.plan:
        conn = link_state.connect(path, read_only=True)
        try:
            summary = run_scheduler(conn, scope=args.scope, max_rows=args.max_rows,
                                    hosts=hosts, site=args.site, plan_only=True)
        finally:
            conn.close()
    elif not args.apply:
        print("[i] dry run: probing for real, writing nothing, moving no cursor",
              file=sys.stderr)
        conn = link_state.connect(path, read_only=True)
        try:
            summary = run_scheduler(conn, scope=args.scope, max_rows=args.max_rows,
                                    workers=args.workers, spacing_s=args.spacing_s,
                                    hosts=hosts, site=args.site,
                                    prober_kind=args.prober, timeout=args.timeout,
                                    dry_run=True)
        finally:
            conn.close()
    else:
        conn = link_state.connect(path)
        try:
            summary = run_scheduler(
                conn, scope=args.scope, max_rows=args.max_rows,
                workers=args.workers, spacing_s=args.spacing_s,
                hosts=hosts, site=args.site, prober_kind=args.prober,
                timeout=args.timeout, dry_run=False, batch_size=args.batch_size,
                progress=lambda done: print(
                    f"  [wrote {done['applied']}/{done['of']} last_id={done['last_id']}]",
                    flush=True))
        finally:
            conn.close()
    print(json.dumps(summary, indent=2, sort_keys=True) if args.json
          else json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
