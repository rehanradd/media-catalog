# 2026-11-17 — sweep staleness: host_health 103.5h old, sweep healthy `[SWEEP-STALE-HOST-HEALTH]`

Watchdog dry-run 06:51 PKT 17 Nov flagged B10 (`host_health` 103.5h old) +
C3data (last `link_state_runs` row 22.1h old) while all 5 sweep timers fire on
schedule. Investigation (read-only, SSH probes + journal + code) splits it:

**C3data is a watchdog measurement bug, not a sweep failure.** The check reads
`MAX(started_at)` from `link_state_runs`, but `started_at` is inherited from
the `link_state_cursor` row (`link_state.py: propagate`, `started =
float(existing["started_at"] ...)`) — the walk's start, sticky across runs.
Measured 06:59: `MAX(started_at)` = 2026-11-16 08:45:29 (22.2h old) while
`MAX(finished_at)` = 2026-11-17 04:28:38 (2.5h old). The sweep itself ran
fine at 02:38–03:02 today (exit 0, demand 200/200, background 600 checked /
513 updated, rows 68/69). Fix: the staged watchdog's `check_link_state_recency`
now reads `MAX(finished_at)` — install must use the copy with that line
(`grep MAX(finished_at) ~/workspace/radd_watchdog.py`); the dry-run report's
finding 2 is half wrong (B10 real, C3data false positive — 7th watchdog bug).

**B10 is real: nothing in production writes host_health any more.** Its only
writers are `serve_verify.record_host_success/failure`, called only from
`serve_verify.verify_candidate`'s live-probe path. That path is unreachable in
production: banked presses short-circuit before any probe, and the live
`viewer._verify_tier_link` caller passes `live=False` (its only call site is
`_verify_tier_link_cached`). All 41 rows share `measured_at` 2026-11-12
23:24:40 — one bulk verification pass, nothing since. Consequence: the
circuit breaker and `host_rank_score` read 4-day-old host data.

**Fix (branch `muse/sweep-stale-fix-20261117`, NOT merged):** `link_state.propagate`
now feeds every completed (or interrupted, for committed batches) run's verdicts
into `host_health` via `serve_verify`'s own recorders (lazy import, best-effort,
same transaction as the run's final cursor write). Keyed by the link's own host
(`source_url`, matching `circuit_open`/`host_rank_score` and the existing rows);
`active`/`expired` count as answered (the endpoint replied), `dead` as a
failure; failures replay before successes so a host that answered once ends
with a closed circuit. Dry runs write nothing. New test
`test_completed_run_feeds_host_health` (7 hosts, 5 successes, 2 failures).
Local: `test_link_state.py` 35/35, `test_link_state_runner.py` 12/12,
`test_serve_verify.py` 62/62. CI is the gate; no gate deploy owed for a
branch. Next sweep after the gate (08:30 slot) refreshes all hosts it touches.
