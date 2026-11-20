# 2026-11-20 — Duration backfill job `[DURATION-BACKFILL]`

Branch `muse/duration-backfill-20261120` from `78dff25f`, fast-forwarded
to `2e2f6ec5` before commit. Builds the batch job from the proven EXTINF
prototype (`~/workspace/duration-probe-20261120/report.md`, 2026-11-20):
HLS 30/30 exact (EXTINF-sum = ffprobe to 0.000 s, median 149 ms / 205 KB
per link), direct files 14/14 exact (ffprobe-over-Range + stdlib header
parse agreed to 0.000 s). Code + tests only. The job was NEVER run
against the live DB or any staging DB; the parent/gate owns the first
run. NOT merged — parent merges; no live deploy.

## What was built

- `Fast_Fetch/duration_probe.py` — the prober library (stdlib only),
  ported from the prototype and refactored for reuse: `probe_hls`
  (EXTINF-sum, master-variant pick by highest BANDWIDTH, live flag),
  `probe_direct` (header-first: Range GET the first 2 MB, sniff MP4/MKV,
  parse the duration from the header; ffprobe fallback only when the
  header carries no duration), `probe_url` (dispatch on the
  `resolved_cache` kind, `.m3u8` fallback), `ffprobe_duration` /
  `ffprobe_full` (truth sampling). HTTP 429s carry `:retry-after=N`
  in the error string so the job can honour Retry-After.
- `Fast_Fetch/backfill_durations.py` — the job. Drives from
  `resolved_cache` (NOT the 4.4M stale `download_sources` rows), HLS
  first, batches of 100 with a JSONL journal, staging table
  `duration_probe_results` (created if missing). Probes only FRESH
  cache rows (`expires_at` in the future) — link rot dominates, so a
  stale URL is the resolver's job and a later batch picks it up.
  Report-only by default (journal only); `--apply` stages rows.
  10% ffprobe truth sampling per batch (every 10th success, only for
  methods ffprobe can check independently); agreement < 98% with
  ≥ 10 checks ALERTS (exit 2, data kept). Per-host spacing (0.5 s)
  + 429 park (Retry-After, capped 300 s). Work-disagreement check:
  a staged duration for the same work differing by > 60 s stages NO
  duration (`duration-disagreement`). Live playlists stage `live=1`
  with NO duration; durations outside 60 s – 6 h go to review
  (`duration-out-of-range`). Hard guards, exit 2 instead of waiting:
  load > 4.0, `radd-link-sweep` / `radd-prefetch` /
  `radd-backup-hf` services active (the same `systemctl is-active`
  mechanism `link_state_runner` uses), or a gate-backup copy visible
  in /proc (`sqlite3 .backup ... gate-backup-...`). There is no
  `/tmp/fast-probe-sweep` marker file in this repo — the systemd
  services are the source of truth.
- `Fast_Fetch/duration_apply.sql` — the gate's half. Fills
  `cat_works.runtime_minutes` (integer minutes) and `posts.duration`
  (display `H:MM:SS`, else `M:SS`) ONLY where NULL, ONLY for MOVIE
  works, only when a work/post's staged durations agree within 60 s.
  Journal table `duration_apply_journal` (kind/ref_id/value); re-run
  is a safe no-op (fills 0, journals 0). Step-0 guards abort the
  script (see the RAISE lesson below). Verify section: six queries,
  all zero. Series works are skipped — their per-episode fill into
  `cat_episodes` is follow-up work; the staged rows stay for it.
- `Fast_Fetch/test_duration_backfill.py` — 59 tests, offline, fake
  fetchers + temp-file DBs. Local: new module 59/59, ci manifest
  10/10, docs invariants OK (the manifest failure seen before the
  commit — "present on disk but NOT tracked in HEAD" — clears once
  the files are committed; that check is doing its job).
- Registered in `test_ci_manifest.py` MANDATORY_RUN + the
  backend-ci.yml run step (after `test_backfill_categories.py`).

## Uploadflix investigation (closed, from the saved results.jsonl)

9 uploadflix direct links return `ffprobe-no-duration` while 12
succeed — and the SAME title yields OK on one mirror and FAIL on
another (e.g. "The Great Indian Kapil Show" 8002368 OK / 8002396
FAIL). Per-link serving failure, not a host-level or container quirk:
no parser fix is justified from this data, and the host is NOT
excluded. The job's header-first probe improves on the prototype
here: the Range GET names the real HTTP status where ffprobe only
echoed the URL back as its stderr tail. The header check never ran
on the 9 failures (it only ran on ffprobe-OK links), so the first
real run's `header_note` on those links is the diagnosis.

## Two lessons from building it

1. `RAISE()` only works inside triggers. The first `duration_apply.sql`
   guarded with `SELECT RAISE(ABORT, ...)` and every apply test failed
   with "RAISE() may only be used within a trigger-program". The fix
   is the `dedup_fold.sql` pattern: temp guard tables with
   `CHECK (ok = 1)` + an INSERT that evaluates to 0 on a bad row —
   two tables (`_dur_guard_live`, `_dur_guard_range`) so the failing
   table name says which condition tripped.
2. MKV Duration is in TimestampScale units, not seconds. My first
   fixture wrote 6102.034 as the Duration value and the parse returned
   6.102 s — correct EBML behaviour (6102.034 ticks at a 1 ms scale =
   6102.034 ms). Real files carry ticks (6,102,034 at the default
   1,000,000 ns scale); the fixture now computes
   `ticks = seconds * 1e9 / timescale`, and the scale test reads the
   same wall duration at 1 ms and 1 us scales.

## The two parser bugs, pinned

The prototype's fixes are regression-tested to fail on the old parse
and pass on the new one: (a) the EBML vint length-marker bit belongs
in element IDs and is stripped only from sizes (Duration 0x4489 reads
as 0x0489 under the old reader — tested at the vint level); (b) the
Segment size vint must be consumed before its children (a walk that
starts inside the size bytes never finds Info — tested with a
correct-vint/old-walk variant, plus the full old walk vs the new one
on the same fixture).

## CONTINUITY rotation

`CONTINUITY.md` was at 11,991 B / 12,000 B ceiling on main `2e2f6ec5`,
so this lane rotated out the stale MX #4 Skip Intro pointer (same
move the hubcdn and _init_db lanes made on their branches): the full
record lives in `App/docs/SESSION_HISTORY.md:3291` (Backend CI
44467838435 SUCCESS, Android CI 44468545737 attempt 2 SUCCESS, tip
6afe9d9f ready to merge) — nothing was deleted, the pointer moved.
Count reconciled honestly: 140 → 141 session files (138 dated + 3
undated), verified against `ls Fast_Fetch/checkpoints/*.md`.

## Follow-ups (not this lane)

- First live run (gate-owned): one batch of 100 on a staging copy,
  watch the truth agreement and the uploadflix `header_note`s.
- `cat_episodes.runtime_minutes` fill from the staged series rows.
- Resolve-time use: the HLS EXTINF-sum is cheap enough (149 ms) to
  run at resolve time for playlist links with no banked duration.
