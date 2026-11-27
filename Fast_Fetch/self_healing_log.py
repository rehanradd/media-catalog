#!/usr/bin/env python3
"""self_healing_log.py — the self-healing audit log + registry reconciliation
(self-healing Branch 6, design doc §4 DURABILITY).

Every automatic promotion the self-healing system makes writes exactly
one append-only JSON line to ``self_healing.log`` in the data directory
(``/home/ubuntu/radd-stream-data/self_healing.log`` on the live box,
``RADD_DATA_DIR`` when set, the working directory elsewhere):

    {ts, kind, site, host, old, new, discovered_by, reason, verified_by}

``kind`` is one of ``site-move`` (a site's active base changed),
``host-move`` (a file host moved), ``parked-flag`` (a host was flagged
parked / parked-candidate), ``cache-refusal`` (a non-media URL was
refused at bank time) or ``expiry-banked`` (a measured expiry was
derived off a URL and banked). ``discovered_by`` is one of ``sweep``,
``recheck``, ``redirect``, ``search`` or ``tld`` — the channel that
found the new domain, mapped from each caller's own vocabulary by
:func:`discovered_by_for_site_method` /
:func:`discovered_by_for_host_evidence`. ``site`` is set for site
moves, ``host`` for everything else; the other key is "".

The log is the audit trail for every automatic promotion — an operator
can replay exactly why a domain changed. It is append-only, rotated
weekly (:func:`rotate_log`, called by the weekly job), and writing it
never raises: a log failure must never break a probe, a resolve, or a
registry write, so every public function here returns None (or a
counts dict) instead of raising.

:func:`reconcile_site_host` is the §4 merge rule: when a site's
``active_base_url`` host is marked dead or moved in ``host_registry``,
the host registry wins for URL rewriting at resolve time (it already
does — ``host_registry.rewrite_url``), and the disagreement is logged
(as a ``site-move`` with ``discovered_by=recheck``) + the site is
queued for ``resolve_site`` (a site-migration signal). Neither file
ever edits the other directly.

100% Python Standard Library. Zero external dependencies.
"""

from __future__ import annotations

import json
import os
import time
import urllib.parse
from pathlib import Path

#: The five auto-change kinds the log records (design §4.1).
LOG_KINDS = ("site-move", "host-move", "parked-flag", "cache-refusal",
             "expiry-banked")

#: The discovery channels the log records (design §4.1).
DISCOVERED_BY = ("sweep", "recheck", "redirect", "search", "tld")

#: The log file name inside the data directory.
LOG_FILENAME = "self_healing.log"

#: Safety valve: a log that grows past this without a weekly rotation
#: (the timer was never installed, the job never ran) is rotated to a
#: week-suffixed file on the next write instead of growing forever.
LOG_ROTATE_MAX_BYTES = 50 * 1024 * 1024

#: Observation retention for the weekly prune (design §4.3).
OBSERVATION_RETENTION_DAYS = 90

#: The live data directory (the doc's log path).
_ORACLE_DATA_DIR = Path("/home/ubuntu/radd-stream-data")

#: Discovery-chain step -> log vocabulary (design §4.1). A chain step
#: the map does not name is registry-driven work, so it reads "recheck".
_SITE_METHOD_TO_DISCOVERED_BY = {
    "cached": "recheck",
    "mirror": "recheck",
    "bookmark-follow": "redirect",
    "tld-probe": "tld",
    "numeric-probe": "tld",
    "search-ddg": "search",
    "sister": "search",
    "telegram": "search",
    "github-saurabh": "search",
    "github-phisher": "search",
}

#: Host-move evidence -> log vocabulary. "prober" is the redirect
#: observation channel, "parked-sweep" the sweep's HTML channel.
_HOST_EVIDENCE_TO_DISCOVERED_BY = {
    "prober": "redirect",
    "parked-sweep": "sweep",
}


def _now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _host_of(url) -> str:
    try:
        return (urllib.parse.urlsplit(str(url or "")).hostname or "").lower()
    except ValueError:
        return ""


def default_log_path() -> Path:
    """The log path for this box: ``RADD_SELF_HEALING_LOG`` when set
    (operators and tests redirect it without a code change), else the
    configured data dir (``config.DATA_DIR`` honours ``RADD_DATA_DIR``),
    else the live data directory when it exists, else the working
    directory. Tests always set the env var or pass an explicit path;
    this is the production default."""
    env = os.environ.get("RADD_SELF_HEALING_LOG")
    if env:
        return Path(env)
    try:
        import config  # type: ignore
        return Path(config.DATA_DIR) / LOG_FILENAME
    except Exception:
        pass
    try:
        from Fast_Fetch import config as _cfg  # type: ignore
        return Path(_cfg.DATA_DIR) / LOG_FILENAME
    except Exception:
        pass
    if _ORACLE_DATA_DIR.is_dir():
        return _ORACLE_DATA_DIR / LOG_FILENAME
    return Path.cwd() / LOG_FILENAME


def discovered_by_for_site_method(method: str) -> str:
    """Map a ``resolve_site`` chain step onto the log vocabulary."""
    return _SITE_METHOD_TO_DISCOVERED_BY.get(
        str(method or "").strip().lower(), "recheck")


def discovered_by_for_host_evidence(evidence: str) -> str:
    """Map a ``host_registry.apply_move`` evidence string onto the log
    vocabulary. An evidence the map does not name (a manual / panel
    move) reads "recheck": the recheck job is the channel that reviews
    it, and the enum has no manual value."""
    return _HOST_EVIDENCE_TO_DISCOVERED_BY.get(
        str(evidence or "").strip().lower(), "recheck")


def log_event(kind: str, *, site: str | None = None,
              host: str | None = None, old: str = "", new: str = "",
              discovered_by: str = "", reason: str = "",
              verified_by: str = "", log_path=None,
              ts: str | None = None) -> dict | None:
    """Append exactly one JSON line for one auto-change. Returns the
    record written, or None when the kind / channel is not in the
    doc's vocabulary or the write failed. Never raises — a log
    failure must never break a probe, a resolve, or a registry
    write. ``site`` is set for site moves, ``host`` for everything
    else; the other key is written as "" so every line has the same
    six fields plus ``ts``."""
    if kind not in LOG_KINDS:
        return None
    if discovered_by not in DISCOVERED_BY:
        return None
    record = {
        "ts": ts or _now_iso(),
        "kind": kind,
        "site": str(site or ""),
        "host": str(host or ""),
        "old": str(old or ""),
        "new": str(new or ""),
        "discovered_by": discovered_by,
        "reason": str(reason or ""),
        "verified_by": str(verified_by or ""),
    }
    try:
        path = Path(log_path) if log_path else default_log_path()
        if path.parent and str(path.parent):
            path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists() and path.stat().st_size > LOG_ROTATE_MAX_BYTES:
            rotate_log(path)
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(record, sort_keys=True) + "\n")
            fh.flush()
        return record
    except Exception:
        return None


def rotate_log(log_path=None) -> str | None:
    """Rotate the log to a week-suffixed file when its last write is
    from an earlier ISO week than now (the weekly rotation). Returns
    the rotated-to path, or None when there was nothing to rotate or
    the rotation failed. Never raises. A name collision (two
    rotations in one week) appends .1, .2, ..."""
    try:
        path = Path(log_path) if log_path else default_log_path()
        if not path.exists():
            return None
        mtime = path.stat().st_mtime
        m_week = time.strftime("%G-W%V", time.gmtime(mtime))
        now_week = time.strftime("%G-W%V", time.gmtime())
        if m_week == now_week:
            return None
        target = path.with_name("%s.%s" % (path.name, m_week))
        n = 1
        while target.exists():
            n += 1
            target = path.with_name("%s.%s.%d" % (path.name, m_week, n))
        os.replace(path, target)
        return str(target)
    except Exception:
        return None


def reconcile_site_host(site_name: str, *, queue_path=None,
                        domain_registry_path=None,
                        host_registry_path=None,
                        log_path=None) -> dict | None:
    """The §4 merge rule, checked for one site.

    Loads both registries: when the site's ``active_base_url`` host is
    marked dead (no successor) or moved (a different current domain)
    in ``host_registry``, the disagreement is logged (kind
    ``site-move``, ``discovered_by=recheck``) and the site is queued
    for ``resolve_site`` (a site-migration signal) via
    ``recheck_queue.enqueue`` when a queue path is given. Returns the
    disagreement dict, or None when the registries agree, the site is
    unknown, or anything failed. Never raises. Neither registry is
    written by this function — the recheck job owns the site's next
    move, and ``host_registry`` owns the host's.
    """
    try:
        try:
            from Fast_Fetch import domain_resolver, host_registry, recheck_queue
        except ImportError:
            import domain_resolver  # type: ignore
            import host_registry  # type: ignore
            import recheck_queue  # type: ignore
        site = (site_name or "").strip()
        if not site:
            return None
        reg = domain_resolver.load_registry(domain_registry_path)
        entry = (reg.get("sites") or {}).get(site)
        if not isinstance(entry, dict):
            return None
        host = _host_of(entry.get("active_base_url") or "")
        if not host:
            return None
        if host_registry.is_dead_host(host, host_registry_path):
            status, current = "dead", ""
        else:
            current = host_registry.current_domain_for(
                host, host_registry_path) or ""
            if not current:
                return None
            status = "moved"
        reason = ("registry-disagreement: site base host %s is %s in "
                  "host_registry%s" % (
                      host, status,
                      (" to %s" % current) if current else
                      " with no successor"))
        log_event("site-move", site=site, host=host, old=host, new=current,
                  discovered_by="recheck", reason=reason,
                  verified_by="host-registry", log_path=log_path)
        queued = False
        if queue_path is not None and recheck_queue is not None:
            try:
                recheck_queue.enqueue(queue_path, site, 0, reason)
                queued = True
            except Exception:
                queued = False
        return {"site": site, "host": host, "status": status,
                "current": current, "queued": queued}
    except Exception:
        return None
