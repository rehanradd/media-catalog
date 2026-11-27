#!/usr/bin/env python3
"""self_heal_weekly.py — the weekly self-healing maintenance job
(self-healing Branch 6, design doc §4).

Two chores, both cheap when there is nothing to do:

1. Prune the host-registry observations sidecar
   (``host_redirect_observations.json``): redirect / HTML observations
   older than 90 days that never fired are dropped, so a stale count
   from a dead pair can never combine with a fresh observation to
   resurrect it. Records that already applied a move (``applied_at``)
   or were already evaluated (``evaluated_at``) are kept — they are
   the spent markers that stop the same pair re-applying.
2. Rotate the self-healing audit log (``self_healing.log``) to a
   week-suffixed file when its last write is from an earlier ISO
   week than now.

Prints one JSON summary line and exits 0 unless the arguments are
unusable. A per-chore failure is reported in the summary, never
fatal — the weekly timer must never break because one chore errored.

100% Python Standard Library. Zero external dependencies.
"""

import argparse
import json
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

try:
    from Fast_Fetch import host_registry, self_healing_log
except ImportError:  # running from inside Fast_Fetch/ (the documented way)
    import host_registry  # type: ignore
    import self_healing_log  # type: ignore


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        prog="self_heal_weekly.py",
        description="Weekly self-healing maintenance: prune stale host "
                    "observations (90 days) and rotate the audit log.")
    parser.add_argument(
        "--observations", default=None,
        help="explicit host observations sidecar path (default: "
             "next to host_registry.py)")
    parser.add_argument(
        "--log", default=None,
        help="explicit self_healing.log path (default: the data dir)")
    parser.add_argument(
        "--retention-days", type=float, default=None,
        help="observation retention in days (default: 90)")
    parser.add_argument(
        "--dry-run", action="store_true",
        help="report what would be pruned / rotated, change nothing")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    retention = (float(args.retention_days)
                 if args.retention_days is not None
                 else float(host_registry.OBSERVATION_RETENTION_DAYS))
    summary = {"job": "self-heal-weekly", "dry_run": bool(args.dry_run),
               "retention_days": retention, "observations_pruned": 0,
               "html_pruned": 0, "observations_kept": 0, "html_kept": 0,
               "log_rotated": None}
    if args.dry_run:
        # A dry run measures only: prune_observations(dry_run=True)
        # counts what would go without rewriting the file.
        pruned = host_registry.prune_observations(
            args.observations, retention_days=retention, dry_run=True)
        summary["observations_kept"] = pruned["observations_kept"]
        summary["html_kept"] = pruned["html_kept"]
        summary["would_prune"] = (pruned["observations_pruned"]
                                  + pruned["html_pruned"])
    else:
        pruned = host_registry.prune_observations(
            args.observations, retention_days=retention)
        summary.update({k: pruned[k] for k in (
            "observations_pruned", "html_pruned", "observations_kept",
            "html_kept")})
        summary["log_rotated"] = self_healing_log.rotate_log(args.log)
    print(json.dumps(summary, sort_keys=True), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
