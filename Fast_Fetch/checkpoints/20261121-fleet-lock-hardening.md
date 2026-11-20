# 2026-11-21 — Fleet worker SQLite lock hardening

## Incident

2026-11-13 03:39 PKT: the scheduled fleet run died at its very first
INSERT — `sqlite3.OperationalError: database is locked` in
`fleet_worker.record_run_start` (audit row in the live catalog's
`admin_fleet_audit`), before crawling anything. No audit row, no log
file, no `last_sync_at` update; the site (7starhd, by rotation) was
retried and succeeded the next day, so the failure cost one day,
nothing more. Observed rate: 1 in 9 runs (Nov 12–20). The likely lock
holder is the continuous fast-probe sweep, which writes `checked_at`
to the catalog around the clock — unproven, and the fix matters more
than the attribution. Full diagnosis:
`~/workspace/fleet-worker-20261121/report.md` §3.

## Root cause

`run_one()` opened the catalog with plain `sqlite3.connect()` — the
default 5-second busy timeout, no retry. The Nov 20 `_init_db` fix
(main `10d70879`: `init_db_once()` + bounded busy retry) does NOT
cover this path; `record_run_start` never calls it.

## The fix

Smallest change, in `fleet_worker.py` only — routing through
`init_db_once` does not fit (that helper initialises the fast_fetch
schema, and importing fast_fetch would drag the whole engine into
the worker):

- `_catalog_connect()`: opens the catalog with `timeout=30` — the
  live value (`fast_fetch.get_db` uses the same 30 s). All three
  catalog connects in `run_one()` (start / pid / finish) go through
  it. The panel connects and the `--stalest` read-only connect are
  untouched: the panel DB is not the contested one, and a `mode=ro`
  read cannot take the write lock.
- `_audit_write()`: executes one audit write, retrying a locked
  database a bounded number of times (3 attempts, 0.5 s / 1.0 s
  delays — the same shape as `fast_fetch._init_db_with_retry`).
  Only "database is locked" is retried; any other failure raises at
  once, and the failed attempt is rolled back before the next try.
  `record_run_start` / `record_run_pid` / `record_run_finish` all
  use it — the finish write faces the same lock.

## Tests

New `LockedCatalogTests` in `test_fleet_worker.py` (5 tests, offline,
temp-file SQLite only, ~2.6 s for the whole module):

- `_catalog_connect` yields `PRAGMA busy_timeout` 30000.
- `record_run_start` retries a locked database: exclusive lock held
  by a second connection, released after 0.3 s, called on a
  timeout=0 connection so the first attempt really raises — attempt 2
  succeeds after the 0.5 s delay; row present.
- A lock held past all 3 attempts still raises (bounded, ~1.5 s of
  delays), never swallowed.
- A non-lock error (missing table) raises at once, no retry sleeps.
- `run_one` end to end survives a ~0.4 s lock held while it starts
  (status done, audit 1).

Negative control: against unfixed code 3 of the 5 fail (the two
`_catalog_connect` / retry tests error — the helper does not exist —
and the never-clears test fails on elapsed time). The other two pass
on unfixed code too (the default 5 s timeout also waits out a 0.4 s
lock); they guard the path against regression.

## Local verification

- New module 21/21 OK. `~/workspace/pre-push-checks.py` passed.
- Branch `muse/fleet-lock-hardening-20261121` from main `9ef7c978`.
  NOT pushed, NOT merged — parent merges; no scraper run, no DB
  touched, no service/timer touched.
- CONTINUITY.md: pointer added at the top of the rotated list, count
  reconciled to 147 (144 dated + 3 undated); the oldest pointer (H4
  R10 fixup, 2026-11-20) rotated out to stay under the 12,000 B
  ceiling — its substance lives in
  `checkpoints/20261120-h4-r10-fixup.md`, still indexed by
  `checkpoints/README.md`.
