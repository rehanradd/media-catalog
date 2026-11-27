#!/usr/bin/env python3
"""
domain_recheck.py — the site recheck job (self-healing Branch 4).

Drains the domain recheck queue (recheck_queue.py): for each due site,
runs the discovery chain (domain_resolver.resolve_site) and logs the
outcome. With --probe-stale it also runs the daily periodic
re-validation — probe_all_sites(only_stale=True) with the 7-day
staleness window — and runs the full chain for any site that comes back
"down" (a site that fails its cached base goes through resolve_site;
a blocked/unverifiable site is left alone).

Two callers, both cheap when there is nothing to do:
  * the sweep loop's post-batch hook (fast-probe-sweep-loop.sh) runs
    ``domain_recheck.py --db <catalog> --limit 5`` after every batch;
  * the daily timer (radd-domain-probe.timer) runs
    ``domain_recheck.py --db <catalog> --probe-stale`` (full drain +
    stale probe) once a day at 10:30.

Exit code is 0 unless the arguments or the queue itself are unusable;
a per-site failure is logged as a JSON line, never fatal — the sweep
loop must never break because one site's chain errored.

100% Python Standard Library. Zero external dependencies.
"""

import argparse
import json
import sys
import time
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

try:
    from Fast_Fetch import domain_resolver, recheck_queue
except ImportError:  # running from inside Fast_Fetch/ (the documented way)
    import domain_resolver  # type: ignore
    import recheck_queue  # type: ignore


def _log(record: dict) -> None:
    record["ts"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    print(json.dumps(record, sort_keys=True), flush=True)


def drain_queue(queue_path, registry_path=None, limit=None,
                cooldown_s: float = domain_resolver.RECHECK_COOLDOWN_SECONDS,
                fetcher=None, resolver=None) -> list:
    """Drain due queue entries, one discovery chain per site.

    ``resolver`` defaults to ``domain_resolver.resolve_site``; tests
    inject a stub. Returns a list of outcome dicts
    (``{site, queued_at, dead_rows, result, base}``). A per-site
    exception is caught, logged as ``error:``, and left pending for
    the next drain (after the cooldown).
    """
    resolve = resolver or (lambda site: domain_resolver.resolve_site(
        site, registry_path=registry_path, fetcher=fetcher))
    outcomes = []
    for entry in recheck_queue.pending(queue_path, limit=limit,
                                       cooldown_s=cooldown_s):
        site = entry["site"]
        t0 = time.time()
        outcome = {"site": site, "queued_at": entry["queued_at"],
                   "dead_rows": entry["dead_rows"], "result": "",
                   "base": "", "secs": 0.0}
        try:
            base = resolve(site) or ""
            if base:
                outcome["result"] = f"resolved:{base}"
                outcome["base"] = base
                recheck_queue.mark_attempt(queue_path, site,
                                           outcome["result"], done=True)
            else:
                status = ""
                try:
                    reg = domain_resolver.load_registry(registry_path)
                    status = str((reg.get("sites") or {}).get(site, {})
                                 .get("status") or "")
                except Exception:
                    status = ""
                outcome["result"] = f"unresolved:{status or 'down'}"
                recheck_queue.mark_attempt(queue_path, site,
                                           outcome["result"], done=False)
        except Exception as exc:  # noqa: BLE001 - one bad site, not a dead job
            outcome["result"] = f"error:{type(exc).__name__}:{exc}"
            try:
                recheck_queue.mark_attempt(queue_path, site,
                                           outcome["result"], done=False)
            except Exception:
                pass
        outcome["secs"] = round(time.time() - t0, 2)
        outcomes.append(outcome)
        _log({"job": "drain", **outcome})
    return outcomes


def probe_stale_sites(registry_path=None,
                      stale_days: float = domain_resolver.STALE_RECHECK_DAYS,
                      fetcher=None, resolver=None) -> list:
    """Periodic re-validation (design §1a step 3).

    Runs ``probe_all_sites(only_stale=True)`` with the staleness window
    (a site is stale when its last_verified is older than ``stale_days``)
    and runs the full discovery chain for every site that comes back
    "down". Blocked / unverifiable / paused / skipped sites are left
    alone — a WAF refusal is not a death and never triggers the chain.
    Returns a list of outcome dicts.
    """
    resolve = resolver or (lambda site: domain_resolver.resolve_site(
        site, registry_path=registry_path, fetcher=fetcher))
    outcomes = []
    results = domain_resolver.probe_all_sites(
        only_stale=True, registry_path=registry_path, fetcher=fetcher,
        stale_after_seconds=float(stale_days) * 86400.0)
    for result in results:
        if result.get("status") != "down" or result.get("skipped"):
            continue
        site = result["site"]
        t0 = time.time()
        outcome = {"site": site, "result": "", "base": "", "secs": 0.0}
        try:
            base = resolve(site) or ""
            outcome["base"] = base
            outcome["result"] = f"resolved:{base}" if base else "unresolved:down"
        except Exception as exc:  # noqa: BLE001 - one bad site, not a dead job
            outcome["result"] = f"error:{type(exc).__name__}:{exc}"
        outcome["secs"] = round(time.time() - t0, 2)
        outcomes.append(outcome)
        _log({"job": "probe-stale", **outcome})
    return outcomes


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        prog="domain_recheck.py",
        description="Drain the domain recheck queue (one discovery chain "
                    "per queued site) and, with --probe-stale, run the "
                    "daily periodic re-validation.")
    parser.add_argument("--db", default=None,
                        help="catalog SQLite file; the queue sidecar "
                             "domain_recheck_queue.db is derived from it")
    parser.add_argument("--queue", default=None,
                        help="explicit queue sidecar path (overrides --db)")
    parser.add_argument("--registry", default=None,
                        help="explicit domain_registry.json path")
    parser.add_argument("--limit", type=int, default=None,
                        help="max queued sites to drain this run "
                             "(default: all due)")
    parser.add_argument("--probe-stale", action="store_true",
                        help="also run the periodic stale-site probe and "
                             "resolve every site it finds down")
    parser.add_argument("--stale-days", type=float, default=None,
                        help="staleness window in days for --probe-stale "
                             "(default: 7)")
    parser.add_argument("--cooldown-hours", type=float, default=None,
                        help="retry cooldown in hours after a failed "
                             "attempt (default: 6)")
    parser.add_argument("--dry-run", action="store_true",
                        help="list what would be drained, resolve nothing")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    if args.queue:
        queue_path = Path(args.queue)
    elif args.db:
        queue_path = recheck_queue.queue_path_for_db(args.db)
    else:
        print("[!] pass --db (catalog) or --queue (sidecar path)",
              file=sys.stderr)
        return 2
    cooldown_s = (float(args.cooldown_hours) * 3600.0
                  if args.cooldown_hours is not None
                  else domain_resolver.RECHECK_COOLDOWN_SECONDS)
    if args.dry_run:
        for entry in recheck_queue.pending(queue_path, limit=args.limit,
                                           cooldown_s=cooldown_s):
            _log({"job": "dry-run", "site": entry["site"],
                  "queued_at": entry["queued_at"],
                  "dead_rows": entry["dead_rows"],
                  "reason": entry["reason"], "attempts": entry["attempts"]})
        return 0
    drained = drain_queue(queue_path, registry_path=args.registry,
                          limit=args.limit, cooldown_s=cooldown_s)
    probed = []
    if args.probe_stale:
        probed = probe_stale_sites(
            registry_path=args.registry,
            stale_days=(args.stale_days
                        if args.stale_days is not None
                        else domain_resolver.STALE_RECHECK_DAYS))
    _log({"job": "summary", "drained": len(drained),
          "resolved": sum(1 for o in drained if o["result"].startswith("resolved:")),
          "probe_stale": len(probed),
          "stale_resolved": sum(1 for o in probed
                                if o["result"].startswith("resolved:")),
          "queue_pending": recheck_queue.count_pending(queue_path)})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
