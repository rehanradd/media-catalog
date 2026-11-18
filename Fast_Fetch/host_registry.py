#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
host_registry.py — self-healing registry for DOWNLOAD hosts.

Why this exists
---------------
Download hosts rotate domains every few weeks (hubcloud.ist -> hubcloud.mov,
hubdrive.bar -> hubdrive.blog, mixdrop.top -> mxdrop.click, ...). The 2026-11-18
audit found 27 old->new pairs covering 1,010,359 stored ``download_sources``
rows, plus 2 hosts dead with no successor. Every move used to need a manual
audit + a bulk-remap gate. This module makes the system adapt by itself:

* the registry (``host_registry.json``, same v2.0 shape as
  ``domain_registry.json``) maps each OLD host to its current domain, with
  evidence and rotation history;
* the serve-time rewrite (``rewrite_url``) swaps ONLY the host of a stored
  URL through the registry, keeping path/query/fragment identical, and
  falls back to the stored URL when the registry has nothing to say;
* the prober learns moves (``record_redirect``): a cross-host redirect that
  keeps the path is one observation of a move; when 20 distinct observations
  of the same old->new pair accumulate, the registry updates itself;
* the panel edits the registry by hand (AdminV2 host endpoints), validated
  the way the audit did it (``validate_host_move``).

What this module deliberately does NOT do
------------------------------------------
* It never touches the database. Stored rows change only through the
  one-time bulk-remap gate; this module rewrites in memory at serve time.
* It never guesses a domain. An entry is applied only when its status is
  ``active`` and it carries a non-empty ``current_domain``. A host the
  audit found dead (``status: "dead"``, no successor) is recorded so the
  code can degrade gracefully — never rewritten to a guess.
* It never changes a verdict. ``record_redirect`` is an observation
  channel only; ``fast_probe``'s verdict policy and writer are untouched.

Evidence values (audit trail, not a gate — ``status`` is the gate):
``audit-confirmed`` (audit rewrote a file URL and got 200),
``audit-unconfirmed`` (the move is real — the old root redirects — but the
file check was bot-blocked 403/429), ``audit-dead-sample`` (the move is
real, the sampled files are genuinely gone on the new domain too),
``audit-dead`` (no successor, recorded so callers can skip the host),
``prober`` (the 20-observation threshold fired), ``root-check``
(the daily root probe saw the old root redirect), ``manual`` (an operator
saved it through the panel after validation passed),
``manual-unverified`` (an operator force-saved it; recorded, NOT applied).

Registry shape (mirrors ``domain_registry.json`` v2.0, keyed by OLD host —
one service can split to two new domains, e.g. hubdrive.bar -> .blog
while hubdrive.space -> .tips, so a per-service key cannot represent it)::

    {"version": "2.0", "updated_at": "...",
     "hosts": {"hubcloud.ist": {
        "current_domain": "hubcloud.mov",
        "evidence": "audit-confirmed", "status": "active",
        "last_verified": "2026-11-18T13:17:00Z",
        "verified_by": "audit-20261118",
        "rows_affected": 167866, "dead_rows": 929,
        "known_mirrors": ["https://hubcloud.mov"],
        "rotation_history": [{"domain": "hubcloud.ist", "seen_until": "2026-11-18"},
                             {"domain": "hubcloud.mov", "seen_from": "2026-11-18"}]}}}

Standard library only. No network at import time.
"""

from __future__ import annotations

import contextlib
import fcntl
import http.client
import ipaddress
import json
import os
import re
import socket
import ssl
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
REGISTRY_PATH = BASE_DIR / "host_registry.json"
OBSERVATIONS_PATH = BASE_DIR / "host_redirect_observations.json"

#: Cross-host, path-preserving redirects that must accumulate for the same
#: old->new pair before the registry updates itself.
#:
#: Why 20: one redirect proves nothing — a file can move to a CDN, a
#: short-link can hop, a parked page can bounce. Twenty DISTINCT URLs all
#: landing host-only on the SAME new host is, with overwhelming
#: likelihood, the service itself on a new domain, not a per-file quirk.
#: It is low enough that a big host crosses it inside one 5,000-row sweep
#: batch (the per-host cap is ~208 rows), and high enough that a small
#: host needs several batches — counts persist in the observations file
#: across runs, so small hosts still get there, just slower.
REDIRECT_MOVE_THRESHOLD = 20
#: Sample URLs kept per observed pair (evidence, not a log).
OBSERVATION_SAMPLE_URLS = 3
#: Per-probe socket budget for the validator / root check.
VALIDATE_TIMEOUT = 10
#: A rewrite only ever applies to these evidence-independent gates.
_APPLY_STATUSES = ("active",)

USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

__all__ = [
    "REDIRECT_MOVE_THRESHOLD",
    "load_registry",
    "save_registry",
    "get_registry",
    "rewrite_url",
    "current_domain_for",
    "is_dead_host",
    "live_domains",
    "apply_move",
    "record_redirect",
    "validate_host_move",
    "check_host_root",
]


def _now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _host_of(url: str) -> str:
    try:
        return (urllib.parse.urlsplit(str(url or "")).hostname or "").lower()
    except ValueError:
        return ""


def _is_ip_literal(host: str) -> bool:
    try:
        ipaddress.ip_address(host)
        return True
    except ValueError:
        return False


# ── registry load / save (mirror domain_resolver's pattern) ─────────────────

def load_registry(registry_path: str | Path | None = None) -> dict:
    """Tolerant read: a missing or corrupt file is an empty registry, never
    an exception — a broken registry must degrade to the stored URL, not
    break the serve path."""
    path = Path(registry_path) if registry_path else REGISTRY_PATH
    if not path.exists():
        return {"version": "2.0", "hosts": {}}
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        if not isinstance(data, dict) or not isinstance(data.get("hosts"), dict):
            return {"version": "2.0", "hosts": {}}
        return data
    except Exception:
        return {"version": "2.0", "hosts": {}}


def save_registry(data: dict, registry_path: str | Path | None = None) -> None:
    """Atomic write: temp file in the target directory + fsync + os.replace.

    json.dump runs BEFORE the replace, so a serialisation crash — or a cut
    mid-write — leaves the previous good file untouched. The registry is
    read by the running service, so a truncated file is a live defect, not
    a cosmetic one. Mirrors ``domain_resolver.save_registry``.
    """
    path = Path(registry_path) if registry_path else REGISTRY_PATH
    data["updated_at"] = _now_iso()
    tmp = None
    try:
        with tempfile.NamedTemporaryFile(
                "w", encoding="utf-8", dir=str(path.parent),
                prefix=path.name + ".", suffix=".tmp", delete=False) as fh:
            tmp = Path(fh.name)
            json.dump(data, fh, indent=2)
            fh.write("\n")
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, path)
    except BaseException:
        if tmp is not None:
            with contextlib.suppress(OSError):
                tmp.unlink()
        raise


@contextlib.contextmanager
def _registry_lock(registry_path: str | Path | None = None):
    """Advisory exclusive flock on a sidecar ``<registry>.lock`` file.

    Never locks the registry itself: the replace in save_registry swaps
    the inode, which silently detaches any lock held on it. Mirrors
    ``domain_resolver._registry_lock``.
    """
    path = Path(registry_path) if registry_path else REGISTRY_PATH
    lock_path = Path(str(path) + ".lock")
    fh = open(lock_path, "w")
    try:
        fcntl.flock(fh.fileno(), fcntl.LOCK_EX)
        yield
    finally:
        with contextlib.suppress(OSError):
            fcntl.flock(fh.fileno(), fcntl.LOCK_UN)
        fh.close()


# ── cached read (serve path: reload only when the file changed) ─────────────

_cache_lock = threading.Lock()
_cache: dict[str, dict] = {}


def get_registry(registry_path: str | Path | None = None) -> dict:
    """The registry, cached in-process and reloaded when the file's mtime
    or size changes — so a prober or panel write takes effect without a
    restart, and a hot serve path does not re-parse JSON per request."""
    path = Path(registry_path) if registry_path else REGISTRY_PATH
    key = str(path)
    try:
        stat = path.stat()
        stamp = (stat.st_mtime_ns, stat.st_size)
    except OSError:
        stamp = None
    with _cache_lock:
        hit = _cache.get(key)
        if hit is not None and hit["stamp"] == stamp:
            return hit["data"]
        data = load_registry(path)
        _cache[key] = {"stamp": stamp, "data": data}
        return data


def _drop_cache(registry_path: str | Path | None = None) -> None:
    """Test hook: forget the cached registry for one path (or all)."""
    with _cache_lock:
        if registry_path is None:
            _cache.clear()
        else:
            _cache.pop(str(Path(registry_path)), None)


# ── lookups ──────────────────────────────────────────────────────────────────

def _entry_for(host: str, registry_path: str | Path | None = None) -> dict:
    """The registry entry for ``host`` (exact match, then a www-stripped
    fallback: a stored ``www.hubcloud.ist`` URL is the same service as the
    ``hubcloud.ist`` entry). Returns {} when nothing matches."""
    host = (host or "").lower().strip()
    if not host:
        return {}
    hosts = get_registry(registry_path).get("hosts") or {}
    entry = hosts.get(host)
    if isinstance(entry, dict):
        return entry
    if host.startswith("www."):
        entry = hosts.get(host[4:])
        if isinstance(entry, dict):
            return entry
    return {}


def current_domain_for(host: str,
                       registry_path: str | Path | None = None) -> str:
    """The current domain for an old host, or "" when the registry has
    nothing applicable (unknown host, dead host, unverified entry)."""
    entry = _entry_for(host, registry_path)
    if entry.get("status") not in _APPLY_STATUSES:
        return ""
    current = str(entry.get("current_domain") or "").strip().lower()
    if not current or current == (host or "").lower().strip():
        return ""
    return current


def is_dead_host(host: str, registry_path: str | Path | None = None) -> bool:
    """True when the registry records this host dead with no successor
    (mobilejsr.com, uploadhub.dad). Callers skip the host instead of
    paying timeouts against it — and never rewrite it to a guess."""
    entry = _entry_for(host, registry_path)
    return str(entry.get("status") or "").lower() == "dead"


def live_domains(registry_path: str | Path | None = None) -> dict:
    """old_host -> current_domain for every applicable entry. The
    ``get_domains()`` hook in fast_fetch.py applies this last, like
    ``registry_live_domains()`` does for scraper sites."""
    out = {}
    for host, entry in (get_registry(registry_path).get("hosts") or {}).items():
        if not isinstance(entry, dict):
            continue
        if str(entry.get("status") or "") not in _APPLY_STATUSES:
            continue
        current = str(entry.get("current_domain") or "").strip().lower()
        if current and current != str(host).lower():
            out[str(host).lower()] = current
    return out


def rewrite_url(url: str, registry_path: str | Path | None = None) -> str:
    """Swap ONLY the host of ``url`` through the registry.

    Scheme, port, path, query and fragment are preserved byte-for-byte;
    the result is the same file on the service's current domain. Returns
    the input unchanged when: it is not an http(s) URL, its host has no
    applicable registry entry, or the entry already names the same host.
    Never raises — a lookup failure degrades to the stored URL.
    """
    try:
        if not url or not str(url).startswith(("http://", "https://")):
            return url
        parts = urllib.parse.urlsplit(str(url))
        host = (parts.hostname or "").lower()
        if not host:
            return url
        current = current_domain_for(host, registry_path)
        if not current:
            return url
        netloc = current
        if parts.port:
            netloc = "%s:%d" % (current, parts.port)
        if parts.username:
            auth = parts.username
            if parts.password:
                auth += ":%s" % parts.password
            netloc = "%s@%s" % (auth, netloc)
        return urllib.parse.urlunsplit(
            (parts.scheme, netloc, parts.path, parts.query, parts.fragment))
    except Exception:
        return url


# ── mutation (the ONLY sanctioned write path) ────────────────────────────────

def apply_move(old_host: str, new_host: str, evidence: str,
               registry_path: str | Path | None = None,
               verified_by: str | None = None,
               rows_affected: int | None = None) -> dict:
    """Record that ``old_host`` now lives at ``new_host``.

    Reloads under the registry lock, merges into that host's record,
    atomic save. Rotation history appends the old domain's ``seen_until``
    and the new domain's ``seen_from``; ``known_mirrors`` keeps the new
    domain first. Returns the merged entry. Mirrors
    ``domain_resolver.update_site_registry``.
    """
    old_host = (old_host or "").lower().strip()
    new_host = (new_host or "").lower().strip()
    if not old_host or not new_host or old_host == new_host:
        raise ValueError("apply_move needs two different hosts")
    now = _now_iso()
    with _registry_lock(registry_path):
        reg = load_registry(registry_path)
        hosts = reg.setdefault("hosts", {})
        entry = hosts.setdefault(old_host, {})
        old_current = str(entry.get("current_domain") or "").strip().lower()
        entry["current_domain"] = new_host
        entry["evidence"] = evidence
        entry["status"] = "active"
        entry["last_verified"] = now
        entry["verified_by"] = verified_by or evidence
        if rows_affected is not None:
            entry["rows_affected"] = int(rows_affected)
        mirrors = entry.setdefault("known_mirrors", [])
        new_base = "https://%s" % new_host
        if new_base in mirrors:
            mirrors.remove(new_base)
        mirrors.insert(0, new_base)
        del mirrors[8:]
        if old_current and old_current != new_host:
            history = entry.setdefault("rotation_history", [])
            history.append({"domain": old_current, "seen_until": now})
            history.append({"domain": new_host, "seen_from": now})
        elif not entry.get("rotation_history"):
            entry["rotation_history"] = [
                {"domain": old_host, "seen_until": now},
                {"domain": new_host, "seen_from": now}]
        save_registry(reg, registry_path)
        _drop_cache(registry_path)
        return dict(entry)


# ── redirect observations (the prober's learning channel) ───────────────────

_obs_lock = threading.Lock()


def _load_observations(observations_path: str | Path | None = None) -> dict:
    path = Path(observations_path) if observations_path else OBSERVATIONS_PATH
    if not path.exists():
        return {"version": 1, "observations": {}}
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        if not isinstance(data, dict) or not isinstance(data.get("observations"), dict):
            return {"version": 1, "observations": {}}
        return data
    except Exception:
        return {"version": 1, "observations": {}}


def _save_observations(data: dict,
                       observations_path: str | Path | None = None) -> None:
    path = Path(observations_path) if observations_path else OBSERVATIONS_PATH
    tmp = None
    try:
        with tempfile.NamedTemporaryFile(
                "w", encoding="utf-8", dir=str(path.parent),
                prefix=path.name + ".", suffix=".tmp", delete=False) as fh:
            tmp = Path(fh.name)
            json.dump(data, fh, indent=2)
            fh.write("\n")
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, path)
    except BaseException:
        if tmp is not None:
            with contextlib.suppress(OSError):
                tmp.unlink()
        raise


def _same_path_query(old_url: str, new_url: str) -> bool:
    """True when the redirect changed ONLY the host (scheme may flip
    http->https). A redirect that also changes the path is a file moving
    to a CDN / short-link hop, not a domain move — it must never count."""
    try:
        a = urllib.parse.urlsplit(old_url)
        b = urllib.parse.urlsplit(new_url)
        return a.path == b.path and a.query == b.query
    except ValueError:
        return False


def record_redirect(old_url: str, new_url: str,
                    registry_path: str | Path | None = None,
                    observations_path: str | Path | None = None) -> dict:
    """Record one cross-host redirect as a move observation.

    Called by the prober after a HEAD chain lands on a different host.
    Only a HOST-ONLY redirect counts (same path + query); redirects to a
    bare IP never count (a CDN edge, not a domain move). Counts persist
    in the observations sidecar across runs, so a small host reaches the
    threshold over several batches. When the count for a pair reaches
    ``REDIRECT_MOVE_THRESHOLD`` and the registry does not already name
    that new domain, the registry updates itself (evidence ``prober``)
    and the pair is marked applied so it never re-applies.

    Returns ``{"old_host", "new_host", "count", "threshold_reached",
    "applied"}``. Never raises — a registry failure must never break a
    probe; the observation is then simply lost.
    """
    try:
        old_host = _host_of(old_url)
        new_host = _host_of(new_url)
        result = {"old_host": old_host, "new_host": new_host, "count": 0,
                  "threshold_reached": False, "applied": False}
        if not old_host or not new_host or old_host == new_host:
            return result
        if _is_ip_literal(new_host):
            return result
        if not _same_path_query(old_url, new_url):
            return result
        key = "%s|%s" % (old_host, new_host)
        with _obs_lock:
            data = _load_observations(observations_path)
            obs = data.setdefault("observations", {})
            rec = obs.setdefault(key, {"count": 0, "first_seen": _now_iso(),
                                       "last_seen": _now_iso(),
                                       "sample_urls": [], "applied_at": None})
            rec["count"] = int(rec.get("count") or 0) + 1
            rec["last_seen"] = _now_iso()
            samples = rec.setdefault("sample_urls", [])
            if new_url not in samples and len(samples) < OBSERVATION_SAMPLE_URLS:
                samples.append(new_url)
            result["count"] = rec["count"]
            already = current_domain_for(old_host, registry_path)
            if rec["count"] >= REDIRECT_MOVE_THRESHOLD and not rec.get("applied_at"):
                result["threshold_reached"] = True
                if already != new_host:
                    apply_move(old_host, new_host, "prober",
                               registry_path=registry_path)
                    rec["applied_at"] = _now_iso()
                    result["applied"] = True
                else:
                    # The registry already names this domain (seeded or
                    # applied by another run): mark applied so the pair
                    # stops being evaluated on every later redirect.
                    rec["applied_at"] = _now_iso()
            _save_observations(data, observations_path)
        return result
    except Exception:
        return {"old_host": _host_of(old_url), "new_host": _host_of(new_url),
                "count": 0, "threshold_reached": False, "applied": False}


# ── validation (the audit's standard, reusable by the panel) ────────────────

def _tls_context():
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def _default_fetch(url: str, timeout: float = VALIDATE_TIMEOUT):
    """One GET following redirects. Returns (status, final_url, error).

    TLS verification is off, exactly like the prober's own context:
    these hosts rotate certificates as often as domains, and a cert
    failure must not read as a dead domain. A transport failure returns
    status 0 with the error string. Never raises.
    """
    req = urllib.request.Request(url, headers={
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout,
                                    context=_tls_context()) as resp:
            try:
                resp.read(4096)
            except Exception:
                pass
            return resp.status, resp.geturl(), None
    except urllib.error.HTTPError as exc:
        try:
            exc.read(4096)
        except Exception:
            pass
        return exc.code, exc.geturl() if hasattr(exc, "geturl") else url, None
    except Exception as exc:
        return 0, url, "%s: %s" % (type(exc).__name__, exc)


def validate_host_move(old_host: str, new_host: str, sample_path: str = "/",
                       fetcher=None) -> dict:
    """Validate a claimed move the way the 2026-11-18 audit did it.

    ``fetcher`` is ``fetch(url) -> (status, final_url, error)`` (injected
    in tests; the default is :func:`_default_fetch`). Checks, cheapest
    first, and stops at the first pass:

    1. ``root-redirect`` — the old root 301/302s to the new host. This
       is the strongest signal: the whole domain moved, not one file.
    2. ``sample-200`` — a rewritten file URL (``sample_path`` on the new
       host) answers 200: the files live there.
    3. ``root-200`` — the new root answers 200: the domain is alive
       (weaker — it does not prove the files moved — but enough for an
       operator-confirmed mapping).

    A 403/429 anywhere is ``blocked``, not dead: a WAF refusal must never
    read as a missing domain. Returns ``{"ok", "method", "detail",
    "checks"}``; never raises.
    """
    old_host = (old_host or "").lower().strip()
    new_host = (new_host or "").lower().strip()
    if not old_host or not new_host or old_host == new_host:
        return {"ok": False, "method": "", "detail": "two different hosts are required",
                "checks": {}}
    if _is_ip_literal(new_host):
        return {"ok": False, "method": "", "detail": "the new domain must be a hostname, not an IP",
                "checks": {}}
    fetch = fetcher or _default_fetch
    checks: dict = {}

    def _blocked(status: int) -> bool:
        return status in (401, 403, 405, 429)

    # 1. Old root -> new host?
    try:
        status, final_url, err = fetch("https://%s/" % old_host)
    except Exception as exc:  # a fetcher that raises instead of returning
        status, final_url, err = 0, "https://%s/" % old_host, str(exc)
    checks["old_root"] = {"status": status, "final_url": final_url, "error": err}
    if not err and status in (301, 302, 303, 307, 308) and _host_of(final_url) == new_host:
        return {"ok": True, "method": "root-redirect",
                "detail": "old root redirects to %s" % new_host, "checks": checks}
    if not err and status == 200 and _host_of(final_url) == new_host:
        # The old host already answers as the new one (alias / shared root).
        return {"ok": True, "method": "root-redirect",
                "detail": "old root serves the new host", "checks": checks}

    # 2. Rewritten file URL -> 200?
    path = sample_path or "/"
    if not path.startswith("/"):
        path = "/" + path
    try:
        status, final_url, err = fetch("https://%s%s" % (new_host, path))
    except Exception as exc:
        status, final_url, err = 0, "https://%s%s" % (new_host, path), str(exc)
    checks["sample"] = {"status": status, "final_url": final_url, "error": err}
    if not err and status == 200:
        return {"ok": True, "method": "sample-200",
                "detail": "rewritten file URL answers 200 on %s" % new_host,
                "checks": checks}

    # 3. New root -> 200?
    if path != "/":
        try:
            status, final_url, err = fetch("https://%s/" % new_host)
        except Exception as exc:
            status, final_url, err = 0, "https://%s/" % new_host, str(exc)
        checks["new_root"] = {"status": status, "final_url": final_url, "error": err}
        if not err and status == 200:
            return {"ok": True, "method": "root-200",
                    "detail": "new root answers 200", "checks": checks}

    blocked = any(_blocked((c or {}).get("status") or 0) for c in checks.values())
    reason = "blocked by the host (WAF/rate-limit), not proven dead" if blocked \
        else "no check passed: old root does not redirect, sample is not 200, new root is not 200"
    return {"ok": False, "method": "", "detail": reason, "checks": checks}


def check_host_root(host: str, fetcher=None) -> dict:
    """Probe one registry host's root: the daily move detector.

    Returns ``{"host", "status", "final_host", "moved_to", "reason"}``.
    ``moved_to`` is set only when the root 301/302s to a DIFFERENT host
    (a move candidate — the caller records one observation, it never
    applies a move on a single root probe). 403/429 is ``blocked``,
    DNS/connection failure is ``down``, a 404 root is ``gone``.
    Never raises.
    """
    host = (host or "").lower().strip()
    out = {"host": host, "status": 0, "final_host": host, "moved_to": None,
           "reason": ""}
    if not host:
        out["reason"] = "empty host"
        return out
    fetch = fetcher or _default_fetch
    try:
        status, final_url, err = fetch("https://%s/" % host)
    except Exception as exc:
        status, final_url, err = 0, "https://%s/" % host, str(exc)
    out["status"] = int(status or 0)
    out["final_host"] = _host_of(final_url) or host
    if err:
        out["reason"] = "down: %s" % err
        return out
    if status in (301, 302, 303, 307, 308):
        if out["final_host"] != host:
            out["moved_to"] = out["final_host"]
            out["reason"] = "root redirects to %s" % out["final_host"]
        else:
            out["reason"] = "root redirects within the same host"
        return out
    if status == 200:
        out["reason"] = "root answers 200"
        return out
    if status in (401, 403, 405, 429):
        out["reason"] = "blocked http%d" % status
        return out
    if status == 404:
        out["reason"] = "gone http404"
        return out
    out["reason"] = "http%d" % status
    return out
