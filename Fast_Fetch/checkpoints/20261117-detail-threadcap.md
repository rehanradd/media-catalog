# 2026-11-17 — One Piece detail 504 + preresolve thread storm `[DETAIL-THREADCAP]`

Branch `muse/detail-threadcap-20261117` from `198666d6` (column-projection slice).
Two verified-live defects in `viewer.py`, both fixed on this branch, NOT live —
merge + gate deploy is the supervisor's call.

## 1. Thread storm (safety fix, first)

`_preresolve_for_detail` spawned one daemon thread per cold tier, no cap, no
dedup: One Piece carries 2,372 tiers, so ~5 detail opens started 12,617
threads and the process died with "can't start new thread" (22:46–22:49 PKT,
restart 22:54 restored it). Now bounded three ways, all fail-closed (a
skipped warm only means the Play press pays the live resolve):

- Per request: at most `_PRERESOLVE_MAX_TIERS = 12` cold tiers, best-first
  (auto tier, then free tiers, then best band). The rest stay cold, listed
  under `cold`; the press re-verifies them live.
- Per spawn: module-level in-flight dict (120 s TTL, self-heals a dead
  thread) dedups warms for the same source, and a global
  `threading.BoundedSemaphore(32)` acquired non-blocking refuses when the
  process-wide warm budget is full — never blocks the request thread.
  `thread.start()` failing degrades to a skipped warm instead of an
  exception on the request path.
- `_preresolve_is_warm` now returns `None` on a DB error (unknown, never
  cold): a cache fault that reads as cold was the false-cold amplifier.
  Lookup failures are listed under `skipped` and never spawn.

## 2. `_shared_warm_conn` generator bug

The `except: yield None` fallback covered connection acquisition AND the
body, so a body exception surfaced as the misleading "generator didn't
stop after throw()". Restructured: acquisition-only try, body runs
outside it, a body exception propagates unchanged (regression test
included); only the open can fall back to None.

## 3. Detail payload cap

A big title's detail shipped every copy — v1 post 61197: 7.2 MB in 3.95 s
at best, typically 13–34 s, past nginx's 15 s proxy_read_timeout; v1 work
10.1 MB, v2 work 26.2 MB. The wire is mostly signed link handles, and
the ladder verifies every one (profile: `_option_groups` 3.52 s of a
6.17 s call, `_fetch_releases_with_sources` 2.12 s, `parse_token` 26,400
calls). Cap: at most 1,000 releases per v1 detail (each with at most 8
sources, ladder-best first) and 1,000 prints per v2 work detail
(`catalog_api.DETAIL_MAX_PRINTS`, passed by the api_v2 route; internal
callers like `season_detail` stay uncapped). The cap binds the wire AND
the work — the ladder and the preresolve only ever see the kept releases.
Counts stay true totals (`release_count` / `source_count` /
`release_total`, v2 `print_count` / `link_total`); the work's
`episode_guide` is still built from the full release set; the operator
path (`full=True`) is never capped. New wire keys, all additive:
`release_total` + `releases_truncated` (v1), `print_total` +
`prints_truncated` (v2).

App contract checked (read-only): DetailViewModel/DetailScreen build the
episode tree and the per-episode file lists from `releases[].sources[]`,
and the quality switch + download card read `option_groups`. A capped
series therefore shows its first releases in loader order (v1) / its
best prints first (v2, quality_score DESC) and `releases_truncated` /
`prints_truncated` says so. No app change needed for the cap to be safe;
season paging is the follow-up if the tail must stay reachable.
Pre-existing note found while testing: `works.parse_episode` caps at
999, so a series past 999 episodes already loses them in the guide —
not this slice's bug, not changed here.

## Measured (One-Piece-scale fixture: 5,120 releases, 12,440 sources)

Bench scripts: `~/workspace/bench-detail-threadcap/bench_detail.py`,
`bench_v2.py` (the /tmp copies died in the hatch reboot).

| call | before | after |
|---|---|---|
| v1 post detail (verify=True) | 4.36 s / 6.58 MB, 4,400 preresolve spawns | 0.87 s / 1.42 MB, 12 spawns |
| v1 work detail (verify=True) | 4.45 s / 6.84 MB, 4,000 spawns | 0.50 s / 1.46 MB, 12 spawns |
| v2 work detail | 1.98 s / 22.29 MB (4,500 prints) | 0.44 s / 4.99 MB (1,000 prints, counts true) |

## Tests

- New: 6 preresolve tests in `test_serve_verify.py` (cap at 12 on a
  40-tier fixture, in-flight dedup, lookup-error skip, `is_warm` None on
  DB error, shared-conn body-exception propagation + open-failure
  fallback); 4 cap tests in `test_home_rails.py` (1,200-release title
  capped at 1,000 with true totals, small title untouched, 8-source cap,
  work counts true + guide full incl. episode 999 living only past the
  cap); 1 v2 cap test in `test_catalog_api.py` (1,200-print work capped
  at 1,000, counts true, every episode print reference resolves inside
  the kept set).
- Updated (same assertions, new wire): `test_detail_projection.py`
  reference builder carries `release_total` / `releases_truncated`;
  `test_home_rails.py` loader unpack is now 4-tuple.
- One pre-existing test's mock had to be accommodated, not changed: the
  cap counts start *attempts* (a `_start_preresolve` returning None
  still means the band is warming — deduped elsewhere — or the budget
  is full), because `test_warm_starts_one_thread_per_band` mocks the
  starter with `side_effect=started.append` (returns None) and pins
  "called = warming". One latent arity bug fixed along the way: the
  loader's empty-ids early return was still a 2-tuple.
- Local: serve_verify 68/68, home_rails 57/57, detail_projection 4/4,
  catalog_api 152/152, api_v2 83/83, redact 116/116 in the gate.
- Full backend gate (run-backend-gate.py, 92 modules): **92/92 PASS,
  3,210 tests, 0 failed** — no flakes, no solo re-runs needed.
  (First gate attempt died in the hatch VM reboot, which wiped /tmp
  including the gate log; re-ran with the log in ~/workspace.)

## Not done

- No live deploy (supervisor's call). The live service still serves
  uncapped detail until this merges + deploys; the thread storm can
  recur on a many-tier title until then.
- Season paging for the capped tail (app follow-up, sibling lane owns
  App/).
