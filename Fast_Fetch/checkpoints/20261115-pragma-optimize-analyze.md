# PRAGMA optimize on close + ANALYZE after the projection — 2026-11-15

## Why
SQLite report #4 (research-sqlite-scale-20261115.md): the planner's stats go stale
after every bulk load, and stale stats turn an indexed lookup into a full scan.
Two halves, both tiny and near-zero risk:
1. `PRAGMA optimize` on connection close (viewer.py `get_db()`) — SQLite designed
   it for exactly this; it only does work when stats are stale (microseconds when
   there is nothing to do). Guarded so a failed optimize can never break the
   close path.
2. `ANALYZE` at the end of `catalog_v2._finalize()` — after every write in the
   projection, before the final commit, so `sqlite_stat1` reflects the tables
   the projection just rebuilt (FTS / needle / bridge rebuilds and the roll-ups
   all change row counts). A failure is a warning, never a gate (same pattern
   as the FTS / needle / bridge blocks).

## Measured cost (live catalog, 2026-11-15)
ANALYZE on a `.backup` copy of the live catalog (9,826,123,776 bytes):
**248.2 s (~4.1 min), 80 `sqlite_stat1` rows.** The copy was removed after.
The first post-gate projection pays ~4 extra minutes; the projection already
takes minutes, so this is acceptable. `PRAGMA optimize` costs nothing
measurable per request when stats are fresh.

## Changes
- `Fast_Fetch/viewer.py`: `get_db()` finally-block runs `PRAGMA optimize`
  (try/except, best-effort) before `conn.close()`.
- `Fast_Fetch/catalog_v2.py`: `_finalize()` runs `ANALYZE` + stages the
  `sqlite_stat1` row count before the final `conn.commit()`.
- Tests: `AnalyzeProjectionTests` in `test_catalog_v2.py` (projection writes
  planner stats); `GetDbOptimizeTests` in `test_serve_verify.py` (close stays
  clean, connection reopens, optimize runs on a real connection).

## Local verification
test_catalog_v2 + test_serve_verify + test_catalog_api + test_redact +
test_api_v2 = **506 tests OK**. (test_title_clean belongs to the unmerged
v2-search-dedup branch and is not in this tree.)
