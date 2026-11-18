#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_host_roots.py — daily root probe for the self-healing host registry.

For every host in ``host_registry.json`` (each old host, each current
domain, and each recorded-dead host), fetch ``https://<host>/`` once and
report what the root says:

* ``alive``      — the root answers 200 on the same host;
* ``moved``      — the root 301/302s to a DIFFERENT host: a move
  candidate. One observation is recorded for the pair (it feeds the
  prober's 20-observation threshold; a single root probe never applies
  a move on its own);
* ``blocked``    — 401/403/405/429: a WAF refusal, not a dead domain;
* ``down``       — DNS/connection failure; ``gone`` — the root 404s.

A move candidate is a flag for the operator (or the panel), not an
automatic rewrite: the registry only auto-applies through the prober's
threshold. Run daily (cron); exit code is always 0 — this is a report,
not a gate. Output is human lines, plus ``--json <path>`` for a machine
summary.

Standard library only. No database access.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

try:
    from Fast_Fetch import host_registry
except ImportError:  # running from inside Fast_Fetch/ (the documented way)
    import os

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import host_registry  # type: ignore

PROBE_DELAY_S = 0.1  # gentle: one root fetch per host, hosts are distinct


def check_all(registry_path=None, observations_path=None,
              fetcher=None, only: str | None = None) -> dict:
    """Probe every registry host's root. Returns the summary dict."""
    reg = host_registry.load_registry(registry_path)
    hosts = reg.get("hosts") or {}
    targets: list[tuple[str, str]] = []  # (host, role)
    seen: set[str] = set()
    for old_host in sorted(hosts):
        entry = hosts[old_host] or {}
        if only and only not in old_host:
            continue
        if old_host not in seen:
            seen.add(old_host)
            targets.append((old_host, "old"))
        current = str(entry.get("current_domain") or "").strip().lower()
        if current and current not in seen:
            seen.add(current)
            targets.append((current, "current"))
    results = []
    for host, role in targets:
        res = host_registry.check_host_root(host, fetcher=fetcher)
        res["role"] = role
        moved_to = res.get("moved_to")
        if moved_to:
            # One observation toward the threshold — never an auto-apply.
            try:
                obs = host_registry.record_redirect(
                    "https://%s/" % host, "https://%s/" % moved_to,
                    registry_path=registry_path,
                    observations_path=observations_path)
                res["observation_count"] = obs.get("count", 0)
            except Exception:
                res["observation_count"] = 0
        results.append(res)
        time.sleep(PROBE_DELAY_S)
    by_status: dict[str, int] = {}
    for res in results:
        if res.get("moved_to"):
            by_status["moved"] = by_status.get("moved", 0) + 1
        elif res.get("status") == 200:
            by_status["alive"] = by_status.get("alive", 0) + 1
        elif res.get("status") in (401, 403, 405, 429):
            by_status["blocked"] = by_status.get("blocked", 0) + 1
        elif res.get("status") == 404:
            by_status["gone"] = by_status.get("gone", 0) + 1
        elif (res.get("status") or 0) == 0:
            by_status["down"] = by_status.get("down", 0) + 1
        else:
            by_status["other"] = by_status.get("other", 0) + 1
    return {"checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "hosts_checked": len(results), "by_status": by_status,
            "results": results}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="check_host_roots.py",
        description="Probe every host-registry root once and flag moves.")
    parser.add_argument("--registry", default=None,
                        help="host_registry.json path (default: next to this file)")
    parser.add_argument("--observations", default=None,
                        help="observations file path (default: next to this file)")
    parser.add_argument("--json", default=None,
                        help="also write the summary JSON to this path")
    parser.add_argument("--only", default=None,
                        help="only probe hosts containing this substring")
    args = parser.parse_args(argv)
    summary = check_all(registry_path=args.registry,
                        observations_path=args.observations,
                        only=args.only)
    for res in summary["results"]:
        if res.get("moved_to"):
            print("MOVED    %-28s -> %s  (%s; observations=%s)"
                  % (res["host"], res["moved_to"], res["reason"],
                     res.get("observation_count", 0)))
        elif res.get("status") == 200:
            print("alive    %-28s %s" % (res["host"], res["reason"]))
        else:
            print("%-8s %-28s %s"
                  % ("blocked" if res.get("status") in (401, 403, 405, 429)
                     else "down" if (res.get("status") or 0) == 0
                     else "gone" if res.get("status") == 404 else "other",
                     res["host"], res["reason"]))
    print("checked=%d %s" % (summary["hosts_checked"], summary["by_status"]))
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(summary, fh, indent=2)
            fh.write("\n")
        print("summary: %s" % args.json)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
