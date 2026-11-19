"""Backfill Skip Intro / Skip Recap segments from TheIntroDB (MX #4).

Queries TheIntroDB v3 (``https://api.theintrodb.org/v3/media``) for every
work in the catalog that carries a ``tmdb_id`` and writes the community-
verified intro / recap / credits / preview windows into a staging SQL
file, which is applied to a database copy — never to the live database
directly. The app serves the stored windows from ``play_bundle``; a title
with no data gets no Skip button, never a guessed time.

TheIntroDB is a free, community-verified timestamp database keyed by
TMDb id (+ season + episode for shows), used by P-Stream, Youplex,
Infuse and the Jellyfin intro-skipper plugin. Reads need no API key
(a key is only for submissions, which this script never makes). The
v3 ``/media`` endpoint answers 200 with ``{intro, recap, credits,
preview}`` arrays of ``{start_ms, end_ms}`` (``end_ms`` may be null:
credits to the end of the file); **404 means no segment data for this
title**. The old v1 path is Cloudflare-blocked from datacenter IPs and
is never used. Verified live 2026-11-19 from the Oracle VM
(Breaking Bad S1E1: intro 228665-246498 ms, credits from 3432884 ms).

Safety, mirroring ``metadata_backfill.py``:

* stdlib only (``urllib``), polite rate (default 1 req/s), 429 backs off
  and retries once, 15 s timeout.
* ``is_18_plus=1`` works are skipped entirely — segments for adult
  titles are never fetched, stored, or served.
* A lookup row (``cat_media_segment_lookups``) is recorded per
  (tmdb_id, season, episode) asked about, so a 404 is not re-queried
  on every run — only re-checked after ``--recheck-days`` (default 30).
* The script only READS ``--db``. Output is a staging SQL file
  (``INSERT OR REPLACE`` into ``cat_media_segments`` +
  ``cat_media_segment_lookups``) + a JSON report. ``--apply-to`` writes
  the staging SQL into a COPY made with the sqlite3 backup API — and
  refuses when the copy is the source database.

Usage::

    # Plan only: staging SQL + JSON report, no database is touched.
    python3 -S Fast_Fetch/backfill_segments.py --db /path/to/catalog.db

    # Small run first: 20 titles, then inspect the staging SQL.
    python3 -S Fast_Fetch/backfill_segments.py --db catalog.db --limit 20

    # Materialise a copy with the staging SQL applied (gate-time shape):
    python3 -S Fast_Fetch/backfill_segments.py --db catalog.db \\
        --apply-to /tmp/catalog-with-segments.db

    # See what would happen without writing anything:
    python3 -S Fast_Fetch/backfill_segments.py --db catalog.db --dry-run
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE_URL = "https://api.theintrodb.org/v3"
USER_AGENT = "RaddStream/1.0 (segments backfill; +https://radd-stream.ccwu.cc)"

# The segment types TheIntroDB serves, and the only ones ever stored or
# served. Must stay in sync with ``catalog_api._SEGMENT_TYPES`` — the
# test module pins the two together.
SEGMENT_TYPES = ("intro", "recap", "credits", "preview")

# Movies have no season/episode; both tables store them under this
# sentinel (see the `cat_media_segments` DDL in catalog_v2.py) because
# NULL would defeat the UNIQUE constraint and 0 is a real season.
MOVIE_SEASON = -1
MOVIE_EPISODE = -1

# A fresh `not_found` is not re-asked for this long; a `found` row is a
# receipt for segments that already exist, so it is never re-asked
# unless the segments were deleted.
DEFAULT_RECHECK_DAYS = 30
DEFAULT_RATE = 1.0
DEFAULT_TIMEOUT = 15


class RateLimited(Exception):
    """TheIntroDB answered 429; `retry_after` seconds were honoured."""

    def __init__(self, retry_after: float):
        super().__init__(f"rate limited, retry after {retry_after:g}s")
        self.retry_after = retry_after


def parse_segments(payload) -> list:
    """TheIntroDB v3 ``/media`` payload -> stored segment dicts.

    Unknown types are dropped, a non-integer or negative start is
    dropped, an end at or before its start is dropped (a zero-width
    window can never contain a playback position). ``end_ms`` None
    survives as None: credits usually run to the end of the file.
    A payload with no usable segment in any array is an empty list,
    not an error — the title simply has no windows.
    """
    out = []
    if not isinstance(payload, dict):
        return out
    for seg_type in SEGMENT_TYPES:
        for item in payload.get(seg_type) or []:
            if not isinstance(item, dict):
                continue
            start = item.get("start_ms")
            end = item.get("end_ms")
            if not isinstance(start, int) or isinstance(start, bool) or start < 0:
                continue
            if end is not None:
                if not isinstance(end, int) or isinstance(end, bool) or end <= start:
                    continue
            out.append({"type": seg_type, "start_ms": start, "end_ms": end})
    return out


class TheIntroDBClient:
    """Minimal TheIntroDB v3 reader (stdlib urllib, no key needed).

    `opener` is injectable for tests: a callable ``(url, headers)`` that
    returns an object with ``.status``, ``.read()`` and ``.headers`` (a
    mapping with ``.get``), or raises ``urllib.error.HTTPError``.
    """

    def __init__(self, base_url: str = BASE_URL, timeout: float = DEFAULT_TIMEOUT,
                 opener=None):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._opener = opener or self._open

    def _open(self, url, headers):
        request = urllib.request.Request(url, headers=headers)
        return urllib.request.urlopen(request, timeout=self.timeout)

    def fetch(self, tmdb_id: int, season=None, episode=None,
              duration_ms=None):
        """One title (or episode) -> ("found", segments) or ("not_found", []).

        Raises ``RateLimited`` on 429 (after reading ``Retry-After``) and
        ``urllib.error.URLError`` / ``RuntimeError`` on anything else that
        is not a clean 200/404, so the caller can count it as an error and
        move on — one failed title never aborts the run.
        """
        params = [f"tmdb_id={int(tmdb_id)}"]
        # A movie carries the -1 sentinel (see MOVIE_SEASON); it is looked
        # up by tmdb_id alone, with no season/episode on the wire.
        if (season is not None and episode is not None
                and int(season) >= 0 and int(episode) >= 0):
            params.append(f"season={int(season)}")
            params.append(f"episode={int(episode)}")
        if duration_ms is not None:
            params.append(f"duration_ms={int(duration_ms)}")
        url = f"{self.base_url}/media?{'&'.join(params)}"
        try:
            with self._opener(url, {"User-Agent": USER_AGENT,
                                    "Accept": "application/json"}) as resp:
                body = resp.read()
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return "not_found", []
            if exc.code == 429:
                retry_after = 0.0
                try:
                    retry_after = float(exc.headers.get("Retry-After") or 0)
                except (TypeError, ValueError):
                    retry_after = 0.0
                raise RateLimited(max(retry_after, 1.0))
            raise RuntimeError(f"TheIntroDB HTTP {exc.code} for tmdb_id={tmdb_id}")
        try:
            payload = json.loads(body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as exc:
            raise RuntimeError(f"TheIntroDB bad JSON for tmdb_id={tmdb_id}: {exc}")
        return "found", parse_segments(payload)

# ── targets ──────────────────────────────────────────────────────────────

def iter_targets(conn) -> list:
    """Every (tmdb_id, season, episode) the backfill can ask about.

    One target per movie (season/episode ``MOVIE_*`` sentinel) and one
    per enumerated episode of a show. Works without a ``tmdb_id`` cannot
    be keyed, and ``is_18_plus=1`` works are skipped entirely — segments
    for adult titles are never fetched, stored, or served. ``duration_ms``
    is the work's ``runtime_minutes`` when known, so TheIntroDB can
    disambiguate cuts of the same episode.
    """
    targets = []
    rows = conn.execute(
        "SELECT id, tmdb_id, work_type, runtime_minutes FROM cat_works"
        " WHERE tmdb_id IS NOT NULL AND COALESCE(is_18_plus, 0) = 0"
        " ORDER BY id").fetchall()
    for row in rows:
        duration_ms = None
        if row["runtime_minutes"] is not None:
            duration_ms = int(row["runtime_minutes"]) * 60000
        if str(row["work_type"] or "").lower() == "movie":
            targets.append({"work_id": int(row["id"]),
                            "tmdb_id": int(row["tmdb_id"]),
                            "media_type": "movie",
                            "season": MOVIE_SEASON, "episode": MOVIE_EPISODE,
                            "duration_ms": duration_ms})
            continue
        episodes = conn.execute(
            "SELECT s.number AS season, e.number AS episode"
            " FROM cat_episodes e JOIN cat_seasons s ON s.id = e.season_id"
            " WHERE e.work_id = ? ORDER BY s.number, e.number",
            (int(row["id"]),)).fetchall()
        for ep in episodes:
            targets.append({"work_id": int(row["id"]),
                            "tmdb_id": int(row["tmdb_id"]),
                            "media_type": "tv",
                            "season": int(ep["season"]),
                            "episode": int(ep["episode"]),
                            "duration_ms": duration_ms})
    return targets


def _fresh_lookup_keys(conn, recheck_days: float, now: float) -> set:
    """(tmdb_id, season, episode) keys a lookup row already covers.

    A `found` receipt never expires — the segments it vouches for are
    in the table, and re-asking would only re-store them. A `not_found`
    expires after ``recheck_days`` so the community's coverage can
    catch up. An `error` is never a receipt: it is not recorded at all
    (see `run_backfill`), so it is always re-asked.
    """
    cutoff = now - float(recheck_days) * 86400.0
    keys = set()
    for row in conn.execute(
            "SELECT tmdb_id, season_number, episode_number, status, fetched_at"
            " FROM cat_media_segment_lookups").fetchall():
        if row["status"] == "found":
            keys.add((int(row["tmdb_id"]), row["season_number"], row["episode_number"]))
        elif row["status"] == "not_found" and float(row["fetched_at"]) >= cutoff:
            keys.add((int(row["tmdb_id"]), row["season_number"], row["episode_number"]))
    return keys


def _existing_segment_keys(conn) -> set:
    """(tmdb_id, season, episode) keys that already hold segments."""
    keys = set()
    for row in conn.execute(
            "SELECT tmdb_id, season_number, episode_number FROM cat_media_segments"
            " WHERE tmdb_id IS NOT NULL").fetchall():
        keys.add((int(row["tmdb_id"]), row["season_number"], row["episode_number"]))
    return keys


def plan_targets(conn, *, recheck_days: float, now: float) -> list:
    """The targets still to ask about: enumerated minus covered."""
    covered = _fresh_lookup_keys(conn, recheck_days, now) | _existing_segment_keys(conn)
    return [t for t in iter_targets(conn)
            if (t["tmdb_id"], t["season"], t["episode"]) not in covered]


# ── run ──────────────────────────────────────────────────────────────────

def run_backfill(conn, client, *, limit=None, rate: float = DEFAULT_RATE,
                 dry_run: bool = False, recheck_days: float = DEFAULT_RECHECK_DAYS,
                 now: float | None = None, sleep=None) -> tuple:
    """Ask TheIntroDB for every planned target.

    Returns ``(segment_rows, lookup_rows, summary)``: rows are tuples
    ready for the staging SQL writer, and the summary counts what
    happened. A 429 sleeps ``Retry-After`` and retries the same target
    once; any other failure counts as an error and the run moves on.
    Nothing is written to the database — the caller writes the staging
    SQL (or nothing, on ``--dry-run``).
    """
    moment = float(now if now is not None else time.time())
    do_sleep = sleep or time.sleep
    targets = plan_targets(conn, recheck_days=recheck_days, now=moment)
    if limit is not None:
        targets = targets[:int(limit)]
    segment_rows: list = []
    lookup_rows: list = []
    summary = {"targets": len(targets), "found": 0, "not_found": 0,
               "errors": 0, "segments": 0, "rate_limited_retries": 0}
    gap = (1.0 / float(rate)) if rate and float(rate) > 0 else 0.0
    for index, target in enumerate(targets):
        if index and gap:
            do_sleep(gap)
        try:
            status, segments = client.fetch(
                target["tmdb_id"], season=target["season"],
                episode=target["episode"], duration_ms=target["duration_ms"])
        except RateLimited as exc:
            summary["rate_limited_retries"] += 1
            do_sleep(exc.retry_after)
            try:
                status, segments = client.fetch(
                    target["tmdb_id"], season=target["season"],
                    episode=target["episode"], duration_ms=target["duration_ms"])
            except Exception:
                summary["errors"] += 1
                continue
        except Exception:
            summary["errors"] += 1
            continue
        if status == "found":
            summary["found"] += 1
            for seg in segments:
                segment_rows.append((target["work_id"], target["tmdb_id"],
                                     target["media_type"], target["season"],
                                     target["episode"], seg["type"],
                                     seg["start_ms"], seg["end_ms"],
                                     "theintrodb", moment))
            summary["segments"] += len(segments)
        else:
            summary["not_found"] += 1
        lookup_rows.append((target["tmdb_id"], target["season"],
                            target["episode"], status, moment))
    if dry_run:
        segment_rows = []
        lookup_rows = []
    return segment_rows, lookup_rows, summary


# ── staging output ───────────────────────────────────────────────────────

def _sql_lit(value) -> str:
    """A SQL literal for the staging file: ints bare, strings quoted."""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float)):
        return repr(value)
    return "'" + str(value).replace("'", "''") + "'"


def write_staging_sql(path: Path, segment_rows, lookup_rows, meta: dict) -> int:
    """The staging SQL file: one INSERT OR REPLACE per segment + lookup.

    Values are literals, so the file applies with a plain
    ``sqlite3 db < file`` or ``executescript`` — no parameters, no
    second program. Returns the number of statements written.
    """
    lines = [
        f"-- backfill_segments staging SQL — generated {meta.get('generated_at', '')}",
        "-- Apply to a COPY of the catalog, never to the live database:",
        "--   sqlite3 <copy.db> < this file",
        "-- Source: TheIntroDB v3 (https://api.theintrodb.org/v3/media),",
        "-- community-verified intro/recap/credits/preview windows.",
        "BEGIN;",
    ]
    count = 0
    for row in segment_rows:
        (work_id, tmdb_id, media_type, season, episode, seg_type,
         start_ms, end_ms, source, fetched_at) = row
        lines.append(
            "INSERT OR REPLACE INTO cat_media_segments (work_id, tmdb_id,"
            " media_type, season_number, episode_number, segment_type,"
            " start_ms, end_ms, source, fetched_at) VALUES (%s);" % ", ".join([
                _sql_lit(work_id), _sql_lit(tmdb_id), _sql_lit(media_type),
                _sql_lit(season), _sql_lit(episode), _sql_lit(seg_type),
                _sql_lit(start_ms), _sql_lit(end_ms), _sql_lit(source),
                _sql_lit(fetched_at)]))
        count += 1
    for row in lookup_rows:
        tmdb_id, season, episode, status, fetched_at = row
        lines.append(
            "INSERT OR REPLACE INTO cat_media_segment_lookups (tmdb_id,"
            " season_number, episode_number, status, fetched_at) VALUES (%s);"
            % ", ".join([_sql_lit(tmdb_id), _sql_lit(season), _sql_lit(episode),
                         _sql_lit(status), _sql_lit(fetched_at)]))
        count += 1
    lines.append("COMMIT;")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return count


def write_report(path: Path, summary: dict) -> None:
    path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n",
                    encoding="utf-8")


def apply_to_copy(db_path: Path, sql_path: Path, out_path: Path) -> None:
    """Copy ``db_path`` with the sqlite3 backup API and apply ``sql_path``.

    Refuses when the copy is the source database — the script never
    writes the database it read. The copy carries the same schema the
    source had, so the staging INSERTs land on tables `ensure_schema`
    created; a source that predates the segments tables fails loudly
    here instead of silently dropping rows.
    """
    if out_path.resolve() == db_path.resolve():
        raise ValueError("--apply-to must name a different file than --db")
    src = sqlite3.connect(str(db_path))
    try:
        dst = sqlite3.connect(str(out_path))
        try:
            src.backup(dst)
        finally:
            dst.close()
    finally:
        src.close()
    dst = sqlite3.connect(str(out_path))
    try:
        dst.executescript(sql_path.read_text(encoding="utf-8"))
        dst.commit()
    finally:
        dst.close()


# ── CLI ──────────────────────────────────────────────────────────────────

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Backfill Skip Intro / Skip Recap segments from TheIntroDB.")
    parser.add_argument("--db", type=Path, required=True,
                        help="Catalog database to READ (never written).")
    parser.add_argument("--out", type=Path, default=Path("segments-backfill.sql"),
                        help="Staging SQL output (default: segments-backfill.sql).")
    parser.add_argument("--report", type=Path, default=None,
                        help="JSON report output (default: <out> with .json suffix).")
    parser.add_argument("--apply-to", type=Path, default=None,
                        help="Copy --db here (backup API) and apply the staging SQL.")
    parser.add_argument("--limit", type=int, default=None,
                        help="Ask about at most N targets.")
    parser.add_argument("--rate", type=float, default=DEFAULT_RATE,
                        help="Requests per second (default: 1.0).")
    parser.add_argument("--recheck-days", type=float, default=DEFAULT_RECHECK_DAYS,
                        help="Re-ask a not_found after this many days (default: 30).")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT,
                        help="HTTP timeout in seconds (default: 15).")
    parser.add_argument("--dry-run", action="store_true",
                        help="Fetch and report, but write no staging SQL.")
    return parser


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    if not args.db.exists():
        print(f"error: --db not found: {args.db}", file=sys.stderr)
        return 2
    conn = sqlite3.connect(str(args.db))
    conn.row_factory = sqlite3.Row
    try:
        # The segments tables must exist to plan (lookups + stored rows
        # are the skip set). A catalog that predates them fails here with
        # a clear message, not mid-run with a bare OperationalError.
        for table in ("cat_media_segments", "cat_media_segment_lookups"):
            exists = conn.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                (table,)).fetchone()
            if exists is None:
                print(f"error: {args.db} has no {table} — run a catalog built"
                      " from a projection that includes the MX #4 schema",
                      file=sys.stderr)
                return 2
        client = TheIntroDBClient(timeout=args.timeout)
        segment_rows, lookup_rows, summary = run_backfill(
            conn, client, limit=args.limit, rate=args.rate,
            dry_run=args.dry_run, recheck_days=args.recheck_days)
    finally:
        conn.close()
    meta = {"generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "db": str(args.db)}
    statements = 0
    if not args.dry_run:
        statements = write_staging_sql(args.out, segment_rows, lookup_rows, meta)
    report_path = args.report or args.out.with_suffix(".json")
    summary.update({"statements": statements, "out": str(args.out),
                    "report": str(report_path), "dry_run": bool(args.dry_run)})
    write_report(report_path, summary)
    if args.apply_to is not None:
        apply_to_copy(args.db, args.out, args.apply_to)
        summary["applied_to"] = str(args.apply_to)
        write_report(report_path, summary)
    print(f"segments-backfill: {summary['found']} found,"
          f" {summary['not_found']} not_found, {summary['errors']} errors,"
          f" {summary['segments']} segments, {statements} statements"
          f" -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
