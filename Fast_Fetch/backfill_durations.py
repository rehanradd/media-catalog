#!/usr/bin/env python3
"""Fast_Fetch/backfill_durations.py — mint durations for the catalog, one batch.

The catalog carries almost no durations (2026-11-20: 28/197,084 works have
``runtime_minutes``, 1,259/225,593 posts have ``duration``), so every
duration this mints is new information. The prober is
``duration_probe.py`` (EXTINF-sum for HLS, header parse + ffprobe fallback
for direct files — prototype-proven 30/30 HLS and 14/14 direct exact).

What this job does, per batch of 100 (default):

  * drives from ``resolved_cache`` — NOT the 4.4M stale ``download_sources``
    rows — HLS (``kind='playlist'``) first, then direct files; ``viewer``
    links are skipped (they need unwrapping, not duration-probing);
  * probes only FRESH cache rows (``expires_at`` still in the future):
    link rot dominates this catalog, so a stale URL is left for the
    resolver to refresh and a later batch picks it up — probing it now
    would burn a fetch on a dead presigned link;
  * journals every probed link as one JSON line (incremental, so a killed
    run keeps its data) and, with ``--apply``, writes the results to the
    staging table ``duration_probe_results`` (created if missing);
  * truth-samples 10% of the successes with an INDEPENDENT ffprobe call
    (HLS: ``ffprobe_duration``; direct: ``ffprobe_full``) and ALERTS —
    exit code 2, data kept — when agreement drops below 98% in a batch
    with at least 10 truth checks;
  * backs off per host on 429s (honours ``Retry-After``) and spaces
    requests to one host, mirroring ``link_health.host_gate``'s one
    in-flight per host rule.

Hard guards (the job REFUSES to run, exit code 2, instead of waiting):
a gate backup copy in progress, a running link-health sweep
(``radd-link-sweep.service``), a running prefetch sweep
(``radd-prefetch.service``), the daily HF backup (``radd-backup-hf.service``),
or 1-minute load above 4.0. The neighbour check is the same
``systemctl is-active`` mechanism ``link_state_runner`` uses; the gate
backup has no service, so it is detected by scanning /proc for a
``sqlite3 .backup ... gate-backup-...`` command line. There is no
``/tmp/fast-probe-sweep`` marker file in this repo — the systemd services
are the source of truth.

Safety: report-only is the default (journal only, no DB write).
``--apply`` writes staging rows only — it never touches
``cat_works``/``posts``; that is the gate's job
(``Fast_Fetch/duration_apply.sql``, NULL-only, movie-only). A re-run
selects zero rows: probed link_ids are already staged. Live playlists
are staged with ``live=1`` and NO duration, and durations outside
60 s – 6 h go to a review queue (``error='duration-out-of-range'``),
never into the staging duration. NEVER run against the live database.

Stdlib only, like everything else in Fast_Fetch.

Usage:
    python3 -S Fast_Fetch/backfill_durations.py --db <catalog.db>
    python3 -S Fast_Fetch/backfill_durations.py --db <catalog.db> --apply
    python3 -S Fast_Fetch/backfill_durations.py --db <catalog.db> --apply --limit 500
"""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlparse

import duration_probe

#: Batch size: the report's plan. One batch is ~100 fetches at a median
#: 149 ms (HLS) — minutes, not hours — so a killed run loses little.
BATCH_SIZE = 100
#: Fraction of successes truth-checked with an independent ffprobe call.
TRUTH_SAMPLE = 0.10
#: Truth agreement is judged only with at least this many checks: below
#: it the percentage is noise, and the run reports the count instead.
TRUTH_MIN_JUDGE = 10
#: Alert when fewer than this fraction of truth checks agree (±2 s).
TRUTH_AGREE_MIN = 0.98
#: A real title is minutes to hours long. Shorter is a clip or a bad
#: parse; longer is a live stream misread as VOD. Both go to review.
MIN_DURATION_S = 60.0
MAX_DURATION_S = 6 * 3600.0
#: Two staged durations for one work agree when they are within this.
WORK_AGREE_S = 60.0
#: The fleet guardrail's heavy-work ceiling (AGENTS.md §Fleet).
LOAD_CEILING = 4.0
#: Neighbour systemd services whose activity means "yield, do not run".
NEIGHBOUR_SERVICES = ("radd-link-sweep.service",
                      "radd-prefetch.service",
                      "radd-backup-hf.service")
#: A gate backup copy is a sqlite3 .backup into a gate-backup-*.db file.
GATE_BACKUP_TOKENS = (".backup", "gate-backup")
#: Minimum gap between two requests to the same host.
HOST_SPACING_S = 0.5
#: Retry-After longer than this is capped: a host that asks for an hour
#: is parked for the rest of the batch, not waited on.
RETRY_AFTER_CAP_S = 300.0
#: Default park when a 429 carries no Retry-After.
RETRY_AFTER_DEFAULT_S = 60.0
#: ffprobe truth agreement window (seconds).
TRUTH_TOLERANCE_S = 2.0

STAGING_DDL = """CREATE TABLE IF NOT EXISTS duration_probe_results (
    link_id INTEGER PRIMARY KEY,
    url TEXT NOT NULL,
    kind TEXT,
    duration_s REAL,
    method TEXT,
    video_codec TEXT,
    width INTEGER,
    height INTEGER,
    live INTEGER NOT NULL DEFAULT 0,
    error TEXT,
    probed_at REAL NOT NULL,
    truth_checked INTEGER NOT NULL DEFAULT 0,
    truth_duration_s REAL,
    truth_agrees INTEGER
)"""

_RETRY_AFTER_RE = re.compile(r"http-429(?::retry-after=([0-9]+))?")


# ---------------------------------------------------------------- guards

def current_load():
    """1-minute load average, or 0.0 where /proc/loadavg does not exist."""
    try:
        with open("/proc/loadavg", encoding="utf-8") as handle:
            return float(handle.read().split()[0])
    except (OSError, ValueError, IndexError):
        return 0.0


def service_active(name):
    """True when a systemd service is active; False anywhere else.

    A box without systemctl (a dev machine, a container) answers False —
    the job simply has no neighbour to yield to there.
    """
    try:
        return subprocess.run(
            ["systemctl", "is-active", "--quiet", name],
            check=False, timeout=10).returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def _proc_cmdlines():
    """Command lines of all processes, best effort (empty on failure)."""
    cmdlines = []
    try:
        proc = Path("/proc")
        for entry in proc.iterdir():
            if not entry.name.isdigit():
                continue
            try:
                raw = (entry / "cmdline").read_bytes()
            except OSError:
                continue
            if raw:
                cmdlines.append(raw.replace(b"\x00", b" ").decode(
                    "utf-8", "replace"))
    except OSError:
        pass
    return cmdlines


def gate_backup_running(cmdlines=None):
    """True when a gate backup copy (sqlite3 .backup to gate-backup-*.db)
    is visible in the process list."""
    if cmdlines is None:
        cmdlines = _proc_cmdlines()
    return any(all(tok in line for tok in GATE_BACKUP_TOKENS)
               for line in cmdlines)


def check_guards(*, load=None, check_service=None, cmdlines=None):
    """Return the list of refusal reasons; empty means clear to run.

    ``load``, ``check_service`` and ``cmdlines`` are injectable for
    tests; when omitted they are the real /proc/loadavg reading, the
    real ``systemctl is-active`` check and the real /proc scan.
    """
    reasons = []
    measured = current_load() if load is None else load
    if measured > LOAD_CEILING:
        reasons.append("load %.2f > %.1f" % (measured, LOAD_CEILING))
    service_check = service_active if check_service is None else check_service
    for neighbour in NEIGHBOUR_SERVICES:
        if service_check(neighbour):
            reasons.append("%s is running" % neighbour)
    if gate_backup_running(cmdlines):
        reasons.append("gate backup copy is running")
    return reasons


# ---------------------------------------------------------------- selection

def _tables(conn):
    return {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table'").fetchall()}


def ensure_staging_table(conn):
    conn.execute(STAGING_DDL)


def select_batch(conn, limit, now=None):
    """Fresh, unprobed, resolvable cache rows: HLS first, then direct.

    Fresh means the cached URL is still inside its verified window —
    a stale row is the resolver's job, not this job's. Orphan cache rows
    (link gone from download_sources) are excluded, as are ``viewer``
    links and rows already staged (a re-run selects zero).
    """
    now = time.time() if now is None else now
    tables = _tables(conn)
    staged = (" AND NOT EXISTS (SELECT 1 FROM duration_probe_results d"
              " WHERE d.link_id = rc.link_id)" if "duration_probe_results"
              in tables else "")
    rows = conn.execute(
        "SELECT rc.link_id, rc.url, rc.kind FROM resolved_cache rc"
        " JOIN download_sources ds ON ds.id = rc.link_id"
        " WHERE rc.kind IN ('file', 'playlist')"
        " AND (rc.expires_at IS NULL OR rc.expires_at > ?)"
        + staged +
        " ORDER BY (rc.kind = 'playlist') DESC, rc.link_id LIMIT ?",
        (now, limit)).fetchall()
    return [{"link_id": r[0], "url": r[1], "kind": r[2]} for r in rows]


def batch_counts(conn, now=None):
    """(fresh candidates, stale skipped, already probed) for the summary."""
    now = time.time() if now is None else now
    tables = _tables(conn)
    if "resolved_cache" not in tables:
        return 0, 0, 0
    base = (" FROM resolved_cache rc JOIN download_sources ds"
            " ON ds.id = rc.link_id WHERE rc.kind IN ('file', 'playlist')")
    fresh = conn.execute(
        "SELECT COUNT(*)" + base +
        " AND (rc.expires_at IS NULL OR rc.expires_at > ?)", (now,)).fetchone()[0]
    stale = conn.execute(
        "SELECT COUNT(*)" + base +
        " AND rc.expires_at IS NOT NULL AND rc.expires_at <= ?",
        (now,)).fetchone()[0]
    probed = 0
    if "duration_probe_results" in tables:
        probed = conn.execute(
            "SELECT COUNT(*) FROM duration_probe_results").fetchone()[0]
    return fresh, stale, probed


def lookup_work_id(conn, link_id):
    """The work a link belongs to, via download_sources -> releases ->
    posts -> cat_work_sites; None when the post is not projected yet
    (or the projection tables are absent — an unprojected DB)."""
    try:
        row = conn.execute(
            "SELECT s.work_id FROM download_sources ds"
            " JOIN releases r ON r.id = ds.release_id"
            " JOIN cat_work_sites s ON s.post_id = r.post_id"
            " WHERE ds.id = ? LIMIT 1", (link_id,)).fetchone()
    except sqlite3.Error:
        return None
    return row[0] if row else None


def _staged_durations_for_work(conn, work_id):
    """Durations already staged for one work (NULLs excluded)."""
    try:
        return [r[0] for r in conn.execute(
            "SELECT d.duration_s FROM duration_probe_results d"
            " JOIN download_sources ds ON ds.id = d.link_id"
            " JOIN releases r ON r.id = ds.release_id"
            " JOIN cat_work_sites s ON s.post_id = r.post_id"
            " WHERE s.work_id = ? AND d.duration_s IS NOT NULL",
            (work_id,)).fetchall()]
    except sqlite3.Error:
        return []


def work_agrees(conn, work_id, duration_s, run_durations):
    """True when no staged duration for the work (earlier batches, or
    this run so far) disagrees with ``duration_s`` by more than a
    minute. Prints of one movie agree; episodes of a series do not —
    a disagreement means the link is probably not the movie, so the
    duration is NOT staged and the link goes to review instead."""
    if work_id is None:
        return True
    others = _staged_durations_for_work(conn, work_id)
    others.extend(run_durations.get(work_id, []))
    return all(abs(o - duration_s) <= WORK_AGREE_S for o in others)


# ---------------------------------------------------------------- throttling

class _HostThrottle:
    """One in-flight request per host, plus a 429 park.

    ``sleep``/``now`` are injectable so tests never wait on a wall clock.
    """

    def __init__(self, spacing_s=HOST_SPACING_S, sleep=time.sleep,
                 now=time.time):
        self.spacing_s = spacing_s
        self._sleep = sleep
        self._now = now
        self._last = {}
        self._parked_until = {}

    def wait(self, host):
        """Sleep until the host's spacing has elapsed; True when the host
        is parked (the caller must skip the link, not fetch it)."""
        if host is None:
            return False
        until = self._parked_until.get(host)
        if until is not None and self._now() < until:
            return True
        last = self._last.get(host)
        if last is not None:
            gap = self.spacing_s - (self._now() - last)
            if gap > 0:
                self._sleep(gap)
        self._last[host] = self._now()
        return False

    def park(self, host, retry_after):
        """Park the host after a 429: Retry-After when the host sent one
        (capped), else the default. Returns the park duration."""
        if host is None:
            return 0.0
        seconds = RETRY_AFTER_DEFAULT_S
        if retry_after is not None:
            try:
                seconds = min(float(retry_after), RETRY_AFTER_CAP_S)
            except (TypeError, ValueError):
                pass
        self._parked_until[host] = self._now() + seconds
        return seconds


def _retry_after_of(error):
    """Retry-After seconds carried in an 'http-429:retry-after=N' error,
    else None (including a bare 'http-429')."""
    if not error:
        return None
    m = _RETRY_AFTER_RE.match(error)
    if not m:
        return None
    return m.group(1)


# ---------------------------------------------------------------- probing

def _default_truth(url, kind):
    """Independent ffprobe truth for one probed link: duration-only for
    HLS, full stream info for direct files."""
    if kind == "playlist":
        return duration_probe.ffprobe_duration(url)
    return duration_probe.ffprobe_full(url)


def probe_one(row, *, probe=None, truth=None, throttle=None,
              truth_due=False):
    """Probe one batch row. Returns the staging/journal record.

    ``probe`` defaults to :func:`duration_probe.probe_url`; ``truth`` to
    the independent ffprobe call. A live HLS playlist, an out-of-range
    duration, or a work disagreement stages NO duration — the error
    names the reason and the link goes to review.
    """
    probe = duration_probe.probe_url if probe is None else probe
    truth = _default_truth if truth is None else truth
    throttle = _HostThrottle() if throttle is None else throttle
    link_id, url, kind = row["link_id"], row["url"], row["kind"]
    host = urlparse(url).hostname
    record = {"link_id": link_id, "url": url, "kind": kind,
              "duration_s": None, "method": None, "video_codec": None,
              "width": None, "height": None, "live": 0, "error": None,
              "probed_at": time.time(), "truth_checked": 0,
              "truth_duration_s": None, "truth_agrees": None,
              "work_id": None}
    if throttle.wait(host):
        record["error"] = "host-parked"
        return record
    result = probe(url, kind=kind)
    record["method"] = result.get("method")
    record["video_codec"] = result.get("video_codec")
    record["width"] = result.get("width")
    record["height"] = result.get("height")
    if not result.get("ok"):
        error = result.get("error") or "probe-failed"
        record["error"] = error
        retry_after = _retry_after_of(error)
        if retry_after is not None or error.startswith("http-429"):
            throttle.park(host, retry_after)
        return record
    duration = result.get("duration")
    if result.get("live"):
        record["live"] = 1
        record["error"] = "live-playlist"
        return record
    if duration is None:
        record["error"] = "probe-no-duration"
        return record
    if duration < MIN_DURATION_S or duration > MAX_DURATION_S:
        record["error"] = "duration-out-of-range"
        record["raw_duration_s"] = duration
        return record
    record["duration_s"] = round(float(duration), 3)
    if truth_due and record["method"] in ("hls-extinf", "mp4-mvhd",
                                          "mkv-ebml"):
        # Truth-sample only the methods ffprobe can check independently;
        # an ffprobe-fallback success would agree with itself.
        check = truth(url, kind)
        if check.get("ok"):
            record["truth_checked"] = 1
            record["truth_duration_s"] = check.get("duration")
            record["truth_agrees"] = (
                1 if abs(check["duration"] - record["duration_s"])
                <= TRUTH_TOLERANCE_S else 0)
        # ffprobe missing / failing is not a disagreement: the probe
        # stands, and the batch reports truth_checked = 0 for it.
    return record


def _stage_row(conn, record):
    conn.execute(
        "INSERT OR REPLACE INTO duration_probe_results"
        " (link_id, url, kind, duration_s, method, video_codec, width,"
        " height, live, error, probed_at, truth_checked, truth_duration_s,"
        " truth_agrees) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (record["link_id"], record["url"], record["kind"],
         record["duration_s"], record["method"], record["video_codec"],
         record["width"], record["height"], record["live"],
         record["error"], record["probed_at"], record["truth_checked"],
         record["truth_duration_s"], record["truth_agrees"]))


# ---------------------------------------------------------------- the run

def run(db_path, *, apply=False, limit=BATCH_SIZE, journal_path=None,
        truth_sample=TRUTH_SAMPLE, probe=None, truth=None,
        guard_reasons=None):
    """Run one batch. Returns (stats, exit_code).

    exit 0: batch done (including "nothing to probe"). exit 2: a hard
    guard refused the run, or the truth agreement alert fired. The
    journal is written in both modes; the staging table is written
    only with ``apply``.
    """
    db_path = Path(db_path)
    if guard_reasons is None:
        guard_reasons = check_guards()
    if guard_reasons:
        print("REFUSED: " + "; ".join(guard_reasons))
        return {"refused": guard_reasons}, 2
    if journal_path is None:
        stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime())
        journal_path = Path("duration-journal-%s.jsonl" % stamp)
    else:
        journal_path = Path(journal_path)

    if apply:
        conn = sqlite3.connect(str(db_path))
    else:
        conn = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
    stats = {"selected": 0, "probed": 0, "ok": 0, "failed": 0,
             "live": 0, "out_of_range": 0, "disagreements": 0,
             "host_parked": 0, "fresh_candidates": 0,
             "stale_skipped": 0, "already_probed": 0,
             "truth_checked": 0, "truth_agrees": 0, "truth_alert": False}
    try:
        fresh, stale, probed = batch_counts(conn)
        stats["fresh_candidates"] = fresh
        stats["stale_skipped"] = stale
        stats["already_probed"] = probed
        batch = select_batch(conn, limit)
        stats["selected"] = len(batch)
        if not batch:
            print("nothing to probe: %d fresh candidates, %d stale"
                  " (resolver's job), %d already probed"
                  % (fresh, stale, probed))
            return stats, 0
        if apply:
            ensure_staging_table(conn)
            # CREATE TABLE above opens sqlite3's implicit transaction;
            # close it before the explicit one, or BEGIN IMMEDIATE
            # fails with "cannot start a transaction within a transaction".
            conn.commit()
            conn.execute("BEGIN IMMEDIATE")
        throttle = _HostThrottle()
        run_durations = {}
        successes_so_far = 0
        truth_every = int(round(1.0 / truth_sample)) if truth_sample else 0
        journal = open(journal_path, "a", encoding="utf-8")
        try:
            for row in batch:
                stats["probed"] += 1
                # Truth-check every Nth success: the counter advances
                # only on a success, so the sample is 1/N of successes
                # even when half the batch fails.
                truth_due = (truth_every
                             and (successes_so_far + 1) % truth_every == 0)
                record = probe_one(row, probe=probe, truth=truth,
                                   throttle=throttle, truth_due=truth_due)
                if record["duration_s"] is not None:
                    successes_so_far += 1
                if record["error"] == "host-parked":
                    stats["host_parked"] += 1
                elif record["duration_s"] is not None:
                    work_id = lookup_work_id(conn, row["link_id"])
                    record["work_id"] = work_id
                    if not work_agrees(conn, work_id,
                                       record["duration_s"], run_durations):
                        record["raw_duration_s"] = record["duration_s"]
                        record["duration_s"] = None
                        record["error"] = "duration-disagreement"
                        stats["disagreements"] += 1
                    else:
                        if work_id is not None:
                            run_durations.setdefault(work_id, []).append(
                                record["duration_s"])
                        stats["ok"] += 1
                elif record["error"] == "live-playlist":
                    stats["live"] += 1
                elif record["error"] == "duration-out-of-range":
                    stats["out_of_range"] += 1
                else:
                    stats["failed"] += 1
                if record["truth_checked"]:
                    stats["truth_checked"] += 1
                    stats["truth_agrees"] += record["truth_agrees"] or 0
                journal.write(json.dumps(record) + "\n")
                journal.flush()
                if apply:
                    _stage_row(conn, record)
        finally:
            journal.close()
        if apply:
            conn.execute("COMMIT")
    except Exception:
        if apply:
            try:
                conn.execute("ROLLBACK")
            except sqlite3.Error:
                pass
        raise
    finally:
        conn.close()

    print("batch: %d selected, %d probed, %d ok, %d failed, %d live,"
          " %d out-of-range, %d disagreements, %d host-parked"
          % (stats["selected"], stats["probed"], stats["ok"],
             stats["failed"], stats["live"], stats["out_of_range"],
             stats["disagreements"], stats["host_parked"]))
    print("pool: %d fresh candidates, %d stale (resolver's job),"
          " %d already probed; journal: %s"
          % (stats["fresh_candidates"], stats["stale_skipped"],
             stats["already_probed"], journal_path))
    if stats["truth_checked"]:
        print("truth: %d checked, %d agree (%.1f%%)"
              % (stats["truth_checked"], stats["truth_agrees"],
                 100.0 * stats["truth_agrees"] / stats["truth_checked"]))
    else:
        print("truth: 0 checked (ffprobe missing, or no sampled success)")
    if (stats["truth_checked"] >= TRUTH_MIN_JUDGE
            and stats["truth_agrees"] / stats["truth_checked"]
            < TRUTH_AGREE_MIN):
        stats["truth_alert"] = True
        print("ALERT: truth agreement %.1f%% < %.0f%% on %d checks —"
              " the probe method is failing on this batch; the staged"
              " rows are kept, investigate before the next batch"
              % (100.0 * stats["truth_agrees"] / stats["truth_checked"],
                 100.0 * TRUTH_AGREE_MIN, stats["truth_checked"]))
        return stats, 2
    if not apply:
        print("report-only: journal written, nothing staged"
              " (pass --apply to stage)")
    else:
        print("applied: %d rows staged" % stats["probed"])
    return stats, 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, required=True,
                        help="catalog database path (staging copy —"
                             " NEVER the live database)")
    parser.add_argument("--apply", action="store_true",
                        help="stage results in duration_probe_results"
                             " (default is report-only: journal only)")
    parser.add_argument("--limit", type=int, default=BATCH_SIZE,
                        help="links to probe this batch (default %d)"
                             % BATCH_SIZE)
    parser.add_argument("--journal", type=Path, default=None,
                        help="JSONL journal path (default:"
                             " duration-journal-<stamp>.jsonl)")
    parser.add_argument("--truth-sample", type=float, default=TRUTH_SAMPLE,
                        help="fraction of successes truth-checked"
                             " (default %.2f)" % TRUTH_SAMPLE)
    parser.add_argument("--no-truth", action="store_true",
                        help="skip ffprobe truth sampling")
    args = parser.parse_args(argv)

    _stats, code = run(
        args.db, apply=args.apply, limit=args.limit,
        journal_path=args.journal,
        truth_sample=0.0 if args.no_truth else args.truth_sample)
    return code


if __name__ == "__main__":
    sys.exit(main())
