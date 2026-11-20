# 2026-11-20 — init_db SQLITE_BUSY race on the live-resolve hot path

## Incident

Gate 2026-11-20 (live 1edcf8cf): three times during live press probes
(08:03:01 / 08:04:48 / 08:12:15 PKT) the resolve hit
`sqlite3.OperationalError: database is locked` inside
`fast_fetch._init_db` (the domains_config INSERT). All three were gate
probes, none from real traffic, none after 08:12 — and the resolves
still worked after (302 in 11.52 s, then 0.02 s banked). Transient, but
a real defect on the press path: every press paid the full init.

## Root cause

`resolve_live_source` called `init_db()` on EVERY press, and `init_db()`
ran the full schema init on every call: a fresh connection, the whole
DDL `executescript`, the defaults INSERT OR IGNORE loop, the migration
pass, the post_sources backfill check, and two full-table scans
(unclassified posts, canonical_key backfill). All of it competes for
the SQLite write lock with the sweep (48 workers), the backfill and
other resolve threads — and the 30 s busy_timeout is the live value,
kept, so one statement waiting it out raises.

## The fix, and the design correction on the way

The first attempt was a blanket once-per-process guard inside
`init_db()` itself. It is WRONG for this codebase and was caught by
the suite before it shipped: `test_database.py` pins `init_db()` as a
REPAIR step — drop `post_sources` and re-init must backfill it, add a
column and re-init must mint the index, file a post without its
post_sources row and re-init must backfill incrementally. A blanket
guard breaks all three. So the fix is a deliberate split:

- `init_db_once()` (new): the hot-path form. Runs the real init once
  per database file per process (keyed by resolved path + device +
  inode, so a gate swap that renames a new file over the path
  re-initialises) and is a no-op afterwards. Used by the three
  per-press / per-save / per-redirect callers: `resolve_live_source`,
  `_save_scraped_data` (`ensure_db`), `set_domain`. A path with no
  file identity (`:memory:`, a missing file) is never cached.
- `init_db()` (unchanged contract): still runs the whole body on every
  call. Startup, CLI, maintenance and tests keep calling it.
- Both go through `_init_db_with_retry()`: a busy database is retried,
  bounded (3 attempts, 0.5 s / 1.0 s backoff) instead of failing the
  call. Only "database is locked" is retried; any other
  OperationalError raises at once. The body is idempotent (IF NOT
  EXISTS / INSERT OR IGNORE / conditional ALTERs / marker-based
  backfill), so re-running it is safe.

## Tests

New suite `Fast_Fetch/test_init_db_busy.py` (7 tests, offline,
temp-file SQLite only, ~2 s): guard no-op / per-path / gate-swap /
`:memory:`-always-inits / `init_db`-still-runs-every-time, busy retry
(write lock held 1.2 s from another thread, busy timeout patched to
100 ms), non-busy error raises without retry. Fails on unfixed code
(5 errors: `init_db_once` missing + the busy retry raising).
`backend-ci.yml`: run step after `test_database.py`.

## Local verification

- New suite 7/7. `test_database.py` 240 tests OK (skipped=1) — the
  repair tests pass, which is what killed the blanket-guard design.
- `test_ci_manifest.py` 10/10 once the files are committed (fails
  pre-commit by design: "present on disk but NOT tracked in HEAD").
- `~/workspace/pre-push-checks.py` passed (3 files, 2 Python).
- Branch `muse/fix-init-db-busy-20261120` from `b9b08862`, rebased
  onto `42e45312`, then onto `78dff25f` (gdflix merged 08:53); diff
  identical (3 files, +335/−4). NOT merged —
  parent merges; no live deploy (next gate carries it). Rebase note:
  the gdflix merge pushed `CONTINUITY.md` to 12,154 B, over the
  12,000 B ceiling, so the stale MX #4 Skip Intro branch pointer was
  rotated out of the list (its substance — TheIntroDB v3 segments —
  lives in `checkpoints/20261109-muse-session126-player-complete.md`);
  count reconciled to 141 (138 dated + 3 undated).
