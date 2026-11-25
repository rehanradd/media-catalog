#!/usr/bin/env python3
"""Tests for Fast_Fetch/auto_crawl_cron.sh — the 6-hourly autocrawl cycle.

The script is driven with a stub python3 (a shell script) and AUTOCRAWL_*
env overrides, so no network, no catalog and no systemd are involved.

What is pinned here (audit W6, 2026-11-20):
- a failing step fails the whole cycle (non-zero exit, no success line);
- a step that runs without producing its summary line also fails the cycle
  (that is the "found nothing" vs "did nothing" distinction);
- there is no Hugging Face backup step any more (the full 61-DB backup it
  called refuses to start on this disk; the daily slim timer owns the
  off-VM copy), and the script never invokes it.

What is pinned here (freshness, 2026-11-23):
- the cycle has THREE steps — sync, crawl, merge. The crawl step is the
  missing bridge: the sync only fills sitemap_cache and the merge only
  folds staging DBs, so without a crawl step a firing timer merges 0 new
  posts. Every sitemap site gets one bounded crawl per cycle, newest-first;
- a step that fails with "database is locked" is retried ONCE (the
  fast-probe sweep can hold the live DB write lock for minutes); any other
  failure fails at once, and a second lock failure fails the cycle.
"""

from __future__ import annotations

import fcntl
import os
import re
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "auto_crawl_cron.sh"

SITES = [
    "rareanimes", "toonworld4all", "desicinemas", "hdtvb", "moviezguru",
    "hindilinks4u", "hdhub4u", "7starhd", "katmoviehd", "vegamovies",
    "multimovies", "rogmovies", "ssrmovies", "10xflix", "bolly4u",
    "filmyzilla", "9xmovies", "world4ufree", "mp4moviez", "humtv",
]

#: The sitemap sites the crawl step drains, in the script's default order.
CRAWL_SITES = [
    "vegamovies", "rogmovies", "ssrmovies", "7starhd", "katmoviehd",
    "multimovies", "10xflix", "hdhub4u", "filmyzilla",
]

SYNC_OK = (
    "[i] Fetching post sitemap for BOLLYSHARES: https://bollyshares.xyz/post-sitemap.xml\n"
    "[✓] [FILMYZILLA] Cached 5002 posts in database.\n"
    "\n[✓] Global sync complete! Total 38018 posts cached across configured sites.\n"
)

SYNC_LOCKED = (
    "Traceback (most recent call last):\n"
    '  File "/opt/radd-stream/Fast_Fetch/fast_fetch.py", line 10184, in <module>\n'
    "sqlite3.OperationalError: database is locked\n"
)

CRAWL_OK = (
    "[i] Crawling latest 25 posts from sitemap (VEGAMOVIES)...\n"
    "🎉 Crawl Complete! Indexed 3/25 posts\n"
    "🔁 Still queued: 28,375 | 🛑 Parked after 3 attempts: 0\n"
)

CRAWL_LOCKED = (
    "Traceback (most recent call last):\n"
    '  File "/opt/radd-stream/Fast_Fetch/fast_fetch.py", line 10184, in <module>\n'
    "sqlite3.OperationalError: database is locked\n"
)

MERGE_ALL_UNCHANGED = "".join(
    "[=] %s unchanged since last merge, skipping\n" % site for site in SITES
) + "[done] fleet merged in 49.8s -> /home/ubuntu/radd-stream-data/merged_catalog_20261025.db\n"

MERGE_ONE_MERGED = "".join(
    "[=] %s unchanged since last merge, skipping\n" % site
    for site in SITES if site != "katmoviehd"
) + (
    "[katmoviehd] {'posts_merged': 12, 'elapsed_seconds': 3.1}\n"
    "[done] fleet merged in 52.3s -> /home/ubuntu/radd-stream-data/merged_catalog_20261025.db\n"
)

STUB = """#!/bin/sh
echo "$@" >> "$STUB_INVOCATIONS"
case "$1" in
  *fast_fetch.py)
    case "$2" in
      sync)
        if [ -n "${STUB_SYNC_FAIL_ONCE:-}" ] && [ ! -f "$STUB_SYNC_MARKER" ]; then
          touch "$STUB_SYNC_MARKER"
          printf '%s' "$STUB_SYNC_LOCKED_OUTPUT"
          exit 1
        fi
        printf '%s' "$STUB_SYNC_OUTPUT"
        exit "${STUB_SYNC_EXIT:-0}"
        ;;
      crawl)
        if [ -n "${STUB_CRAWL_FAIL_ONCE:-}" ] && [ ! -f "$STUB_CRAWL_MARKER" ]; then
          touch "$STUB_CRAWL_MARKER"
          printf '%s' "$STUB_CRAWL_LOCKED_OUTPUT"
          exit 1
        fi
        printf '%s' "$STUB_CRAWL_OUTPUT"
        exit "${STUB_CRAWL_EXIT:-0}"
        ;;
      *)
        exit 99
        ;;
    esac
    ;;
  *merge_fleet.py)
    printf '%s' "$STUB_MERGE_OUTPUT"
    exit "${STUB_MERGE_EXIT:-0}"
    ;;
  *)
    exit 99
    ;;
esac
"""


class AutoCrawlCronTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.stub = self.dir / "python3"
        self.stub.write_text(STUB)
        self.stub.chmod(0o755)
        self.invocations = self.dir / "invocations.txt"
        self.invocations.write_text("")
        self.logfile = self.dir / "autocrawl.log"
        self.logfile.write_text("")
        self.lockfile = self.dir / "autocrawl.lock"
        self.lockfile.write_text("")
        self.env = dict(os.environ)
        self.env.update({
            "AUTOCRAWL_LOCKFILE": str(self.lockfile),
            "AUTOCRAWL_LOGFILE": str(self.logfile),
            "AUTOCRAWL_BASE_DIR": str(self.dir),
            "AUTOCRAWL_DATA_DIR": str(self.dir),
            "AUTOCRAWL_PYTHON_BIN": str(self.stub),
            "AUTOCRAWL_SKIP_CRAWLER_CHECK": "1",
            "AUTOCRAWL_LOCK_RETRY_SLEEP": "0",
            "STUB_INVOCATIONS": str(self.invocations),
            "STUB_SYNC_OUTPUT": SYNC_OK,
            "STUB_SYNC_LOCKED_OUTPUT": SYNC_LOCKED,
            "STUB_CRAWL_OUTPUT": CRAWL_OK,
            "STUB_CRAWL_LOCKED_OUTPUT": CRAWL_LOCKED,
            "STUB_MERGE_OUTPUT": MERGE_ALL_UNCHANGED,
            "STUB_SYNC_EXIT": "0",
            "STUB_CRAWL_EXIT": "0",
            "STUB_MERGE_EXIT": "0",
            "STUB_SYNC_MARKER": str(self.dir / "sync-failed-once"),
            "STUB_CRAWL_MARKER": str(self.dir / "crawl-failed-once"),
        })

    def tearDown(self):
        self.tmp.cleanup()

    def run_cycle(self, extra_env=None):
        env = dict(self.env)
        if extra_env:
            env.update(extra_env)
        return subprocess.run(
            ["bash", str(SCRIPT)], env=env, capture_output=True,
            text=True, timeout=60,
        )

    def log(self):
        return self.logfile.read_text()

    def invoked(self):
        return self.invocations.read_text()

    def test_success_all_three_steps_reports_success_with_counts(self):
        res = self.run_cycle()
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        log = self.log()
        self.assertIn("AUTONOMOUS PIPELINE CYCLE COMPLETED SUCCESSFULLY", log)
        self.assertIn("Step 1 done: sitemap sync ran — Total 38018 posts cached.", log)
        self.assertIn(
            "Step 2 done: crawled 27 new post(s) across 9 site(s).", log,
        )
        self.assertIn(
            "Step 3 done: fleet merged in 49.8s — 0 site(s) merged, "
            "20 unchanged (skipped), 0 missing, 0 errored.",
            log,
        )
        invoked = self.invoked()
        self.assertIn("fast_fetch.py sync --site all", invoked)
        for site in CRAWL_SITES:
            self.assertIn(
                "fast_fetch.py crawl --site %s --limit 25" % site, invoked,
            )
        self.assertIn("merge_fleet.py", invoked)

    def test_success_with_one_merged_site_counts_it(self):
        res = self.run_cycle({"STUB_MERGE_OUTPUT": MERGE_ONE_MERGED})
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        self.assertIn(
            "Step 3 done: fleet merged in 52.3s — 1 site(s) merged, "
            "19 unchanged (skipped), 0 missing, 0 errored.",
            self.log(),
        )

    def test_crawl_sites_and_limit_overrides_are_honoured(self):
        res = self.run_cycle({
            "AUTOCRAWL_CRAWL_SITES": "vegamovies rogmovies",
            "AUTOCRAWL_CRAWL_LIMIT": "5",
        })
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        invoked = self.invoked()
        self.assertIn("fast_fetch.py crawl --site vegamovies --limit 5", invoked)
        self.assertIn("fast_fetch.py crawl --site rogmovies --limit 5", invoked)
        self.assertNotIn("crawl --site ssrmovies", invoked)
        self.assertIn(
            "Step 2 done: crawled 6 new post(s) across 2 site(s).", self.log(),
        )

    def test_sync_failure_fails_cycle_and_skips_crawl_and_merge(self):
        res = self.run_cycle({"STUB_SYNC_EXIT": "1"})
        self.assertNotEqual(res.returncode, 0)
        log = self.log()
        self.assertIn("Step 1 FAILED", log)
        self.assertNotIn("COMPLETED SUCCESSFULLY", log)
        invoked = self.invoked()
        self.assertNotIn("crawl", invoked)
        self.assertNotIn("merge_fleet.py", invoked)

    def test_crawl_failure_fails_cycle_and_skips_merge(self):
        res = self.run_cycle({"STUB_CRAWL_EXIT": "1"})
        self.assertNotEqual(res.returncode, 0)
        log = self.log()
        self.assertIn("Step 2 FAILED", log)
        self.assertNotIn("COMPLETED SUCCESSFULLY", log)
        self.assertNotIn("merge_fleet.py", self.invoked())

    def test_merge_failure_fails_cycle(self):
        res = self.run_cycle({"STUB_MERGE_EXIT": "1"})
        self.assertNotEqual(res.returncode, 0)
        log = self.log()
        self.assertIn("Step 3 FAILED", log)
        self.assertNotIn("COMPLETED SUCCESSFULLY", log)

    def test_sync_success_without_summary_fails_cycle(self):
        # Exit 0 but no "Global sync complete!" line: cannot tell "found
        # nothing" from "did nothing", so the cycle must fail loud.
        res = self.run_cycle({"STUB_SYNC_OUTPUT": "[i] nothing printed\n"})
        self.assertNotEqual(res.returncode, 0)
        log = self.log()
        self.assertIn("produced no sync summary", log)
        self.assertNotIn("COMPLETED SUCCESSFULLY", log)

    def test_crawl_success_without_summary_fails_cycle(self):
        # Exit 0 but no "Crawl Complete! Indexed" line: same distinction as
        # the sync step, per site.
        res = self.run_cycle({"STUB_CRAWL_OUTPUT": "[i] nothing printed\n"})
        self.assertNotEqual(res.returncode, 0)
        log = self.log()
        self.assertIn("produced no crawl summary for vegamovies", log)
        self.assertNotIn("COMPLETED SUCCESSFULLY", log)
        self.assertNotIn("merge_fleet.py", self.invoked())

    def test_merge_success_without_done_line_fails_cycle(self):
        res = self.run_cycle({"STUB_MERGE_OUTPUT": "[=] x unchanged\n"})
        self.assertNotEqual(res.returncode, 0)
        log = self.log()
        self.assertIn("produced no merge summary", log)
        self.assertNotIn("COMPLETED SUCCESSFULLY", log)

    def test_sync_locked_once_retries_and_succeeds(self):
        res = self.run_cycle({"STUB_SYNC_FAIL_ONCE": "1"})
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        self.assertIn("COMPLETED SUCCESSFULLY", self.log())
        self.assertIn("locked database — retrying once", self.log())
        self.assertEqual(
            self.invoked().count("fast_fetch.py sync --site all"), 2,
        )

    def test_sync_locked_twice_fails_cycle(self):
        res = self.run_cycle({
            "STUB_SYNC_OUTPUT": SYNC_LOCKED,
            "STUB_SYNC_EXIT": "1",
        })
        self.assertNotEqual(res.returncode, 0)
        log = self.log()
        self.assertIn("Step 1 FAILED", log)
        self.assertNotIn("COMPLETED SUCCESSFULLY", log)
        self.assertEqual(
            self.invoked().count("fast_fetch.py sync --site all"), 2,
        )
        self.assertNotIn("merge_fleet.py", self.invoked())

    def test_crawl_locked_once_retries_and_succeeds(self):
        res = self.run_cycle({"STUB_CRAWL_FAIL_ONCE": "1"})
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        self.assertIn("COMPLETED SUCCESSFULLY", self.log())
        self.assertIn("locked database — retrying once", self.log())
        self.assertEqual(
            self.invoked().count("fast_fetch.py crawl --site vegamovies"), 2,
        )

    def test_crawl_locked_twice_fails_cycle(self):
        res = self.run_cycle({
            "STUB_CRAWL_OUTPUT": CRAWL_LOCKED,
            "STUB_CRAWL_EXIT": "1",
        })
        self.assertNotEqual(res.returncode, 0)
        log = self.log()
        self.assertIn("Step 2 FAILED", log)
        self.assertNotIn("COMPLETED SUCCESSFULLY", log)
        self.assertNotIn("merge_fleet.py", self.invoked())

    def test_no_backup_step_is_ever_invoked(self):
        res = self.run_cycle()
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        invoked = self.invoked()
        self.assertNotIn("backup", invoked)
        self.assertNotIn("huggingface", invoked.lower())
        self.assertIn("No backup step in this cycle", self.log())

    def test_script_source_has_no_backup_invocation(self):
        # Guard: no code line may invoke a backup binary. The header comment
        # documents why the step is gone and the final log line tells the
        # operator the same, so those may name it; a call may not. (An
        # earlier version of this guard asserted on the bare substring and
        # failed on its own documentation — the behaviour test above is the
        # real proof; this one only catches a re-added call.)
        invocation = re.compile(r"""(^|\s)(/usr/local/bin/backup-hf|backup-hf-slim)\b""")
        for line in SCRIPT.read_text().splitlines():
            if line.lstrip().startswith("#"):
                continue
            self.assertIsNone(
                invocation.search(line),
                "a code line invokes a backup binary: %r" % line,
            )
        source = SCRIPT.read_text()
        self.assertNotIn("HF_TOKEN", source)
        self.assertNotIn("HF_DATASET_REPO", source)

    def test_lock_held_exits_zero_without_running_steps(self):
        with open(self.lockfile, "w") as fh:
            fcntl.flock(fh, fcntl.LOCK_EX | fcntl.LOCK_NB)
            res = self.run_cycle()
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        self.assertIn("already in progress", self.log())
        self.assertNotIn("COMPLETED SUCCESSFULLY", self.log())
        self.assertEqual(self.invoked(), "")


if __name__ == "__main__":
    print("fixture: %s (offline, stub python3 — no network, no catalog)"
          % Path(__file__).name)
    unittest.main(verbosity=2)
