# Link-health sweep runner — out-of-process, demand-first (2026-11-15)

## The measured problem

- The in-process maintenance tick probes **400 rows every 6 hours**
  (`PROPAGATE_BATCHES_PER_TICK = 2` × 200-row pages, scope `serve`).
  Live cursor, measured 2026-11-15: `serve` at id **17,605** on 2026-11-14,
  **18,005** a day later — ~1,600 rows/day. At that rate the **4,434,074
  unchecked rows take ~7.6 years**.
- Raising the in-process slice is not the answer: a 200-link warm once took
  ~17 minutes at ~46% sustained CPU and starved a real Play resolve for
  35 seconds (Robo 501 re-test, 2026-11-14). The rate fix has to live OUT
  of the serve process.
- The pressed rows are almost entirely unverified too: **1,505 of 1,508**
  `resolved_cache` rows carried no fresh verdict (measured 2026-11-15,
  read-only live catalog) — the rows users actually play are the ones
  that matter most, and they are the ones the tick reaches last.

## What changed

**`Fast_Fetch/link_state.py` — `ids` mode for `propagate()`.** A new
keyword `ids: list[int] | None`. When given, the walk covers exactly that
list (deduped, sorted) instead of the id-ordered pending scan; the stored
cursor is ignored — the list IS the work — and an exhausted list reports
`complete`. Pages of only un-probeable ids (magnet:, empty) advance the
offset without counting a batch. Everything else — verdicts, the runs
ledger, the cursor write, the `cat_links` projection sync, the
transport-no-verdict rule — is the shared path. New helper
`_iter_ids_page` mirrors `iter_pending` (same column list, same
`should_probe` filter).

**`Fast_Fetch/link_state_runner.py` (new, stdlib only).** The runner the
timer fires, `--once` only (the timer is the loop):

1. **Demand phase** (scope `sweep-demand`): pressed rows from
   `resolved_cache` whose ledger verdict is missing or older than 30 days,
   most-recent press first, bounded by `--demand-limit` (default 200).
2. **Background phase** (scope `sweep`): the id-ordered pending scan on its
   own cursor — it never fights the serve tick's `serve` cursor — bounded
   by `--max-rows` (default 500).

Guards, all before any probe: memory floor 1,500 MB; load gate
(`wait_for_quiet_machine`, ceiling 4.0, max wait 300 s, then yield);
yield while `radd-prefetch.service` or `radd-backup-hf.service` is
active. Nice=19 + idle IO come from the systemd unit. A yield returns
exit 0 with `yielded` set — the next timer firing retries. Interruption
is safe: `propagate` commits per batch and parks its cursor.

**`Fast_Fetch/systemd/radd-link-sweep.service` + `.timer` (new).** Oneshot,
`--once --max-rows 500 --demand-limit 200`, `TimeoutStartSec=3600`.
Timer: every 6 hours at 02,08,14,20:30 — 2 h offset from the prefetch
timer (00,06,12,18:30), clear of the daily backup at 03:40. Install is a
gate step (see `LINK_SWEEP_RUNBOOK.md`); the units are NOT installed by
this branch.

**`Fast_Fetch/LINK_SWEEP_RUNBOOK.md` (new).** Install, checks, and the
honest arithmetic (below).

**`Fast_Fetch/test_link_state_runner.py` (new, 12 tests).** `ids` mode:
exact list walked in sorted order, stored cursor ignored, un-probeable
ids advance free, empty list completes. `demand_ids`: most-recent first,
fresh verdicts and missing ledger rows skipped, no `resolved_cache`
table is empty not an error. `run_once` with injected fakes: demand
before background with the right scopes, background still runs with no
demand rows, yields on a running prefetch sweep / a load that never
quietens / the memory floor. CLI: `--once` required, args reach
`run_once`.

**Backend gate:** `test_link_state_runner.py` registered in
`backend-ci.yml` (101 → **102** suites).

## Name note

`Fast_Fetch/link_sweep.py` already exists and is a different tool — a
stratified probability sample of the projected links for a population
estimate, merged by operator action. This runner drives
`link_state.propagate` and writes ledger verdicts directly, so it is
`link_state_runner.py`, not a second `link_sweep`.

## Honest arithmetic (measured 2026-11-15)

A probe costs ~1–2 s of worker time (host-dependent; presigned hosts
~4.4 s/row — batch-cost measurement 08:33–08:40 PKT). Four runs a day at
500 rows is ~2,000 rows/day from this runner plus ~1,600/day from the
tick — full coverage of 4.43M rows still takes years. What this fixes is
the ORDER: pressed rows get verdicts within a day, and the background
cursor grinds steadily instead of never moving. Raising `--max-rows`
needs a quiet-window measurement first, not optimism.

## Adult law

Probing writes verdicts to the ledger only; it never displays anything.
A flagged (18+) row keeps its flag and stays hidden from every public
surface by the adult clause at serve time. The demand phase only probes
rows users pressed, and a flagged link can never be pressed — stream and
download deny it on both ports — so the demand set is public rows by
construction. The background scan walks the same `propagate` machinery
the serve tick already runs (its selection carries no adult exclusion
today, and this change does not alter that: a verdict on a flagged row
is ledger-internal, the row stays unservable).

## Tests (local, this VM)

- `test_link_state_runner.py`: **12 OK** (new).
- `test_link_state.py`: **34 OK** (ids mode added, no regressions).
- `test_maintenance.py`: 16 OK. `test_prefetch_worker.py`: 12 OK.
  `test_link_sweep.py`: 21 OK. `test_link_sweep_budget.py`: 15 OK.
  `test_catalog_api.py`: 135 OK (this branch is off main 39cf960c, so
  the search branch's +3 tests are not here — expected).
- Full backend gate (102 suites) runs in CI on the branch push.

## Not live

The code is in the branch; the timer is installed only at the gate
(supervisor's call, with the backup + live test). The serve tick keeps
running meanwhile — the two cursors (`serve`, `sweep`,
`sweep-demand`) are independent.
