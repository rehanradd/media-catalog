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
gate step (see the runbook section appended 2026-11-24 below); the units
were NOT installed by this branch.

**`Fast_Fetch/LINK_SWEEP_RUNBOOK.md` (new — trashed 2026-11-17, content folded into the runbook section below).** Install, checks, and the
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

## Runbook — restored 2026-11-24 (DOC-06)

Restored from the trash copy `Fast_Fetch/LINK_SWEEP_RUNBOOK.md` (deleted by
the 2026-11-17 one-doc cleanup, commit `09083d43`) and folded into this
checkpoint instead of restored as a separate file: this checkpoint is the
runbook's live home, and the pointer in "What changed" above (which named
`LINK_SWEEP_RUNBOOK.md`) is fixed to point here. Every claim below was
re-verified 2026-11-24 against the code on main (`a6e27ad6`) and against the
live box (read-only). The "Not live" note above is the 2026-11-15 position;
the live state below supersedes it.

### Two runners — do not confuse them

The trash runbook names `link_state_runner.py` as "the runner". That was
true on 2026-11-15 and is still true of the timer lane — but it is NOT the
live sweep. Since the 18 Nov swap (user-approved 18:13 PKT 18 Nov) the
continuous sweep is `fast_probe.py`, driven by `fast-probe-sweep-loop.sh`.
Both run, on different lanes:

| | `link_state_runner.py` (timer lane) | `fast_probe.py` (live sweep) |
|---|---|---|
| Fired by | `radd-link-sweep.timer`, every 6 h at 02,08,14,20:30 | `fast-probe-sweep-loop.sh`, continuous loop |
| Batch | demand-first 200 pressed rows, then 500 background rows | 5,000 distinct URLs, 48 workers |
| Selection | `resolved_cache` presses (demand) + id-ordered pending scan (background) | biggest hosts first, deduped by URL, per-host cap `max(4, max_rows // 24)` |
| Guards | load gate 4.0 / 300 s, 1,500 MB floor, yields on a running prefetch sweep or backup, Nice=19 + idle IO | none in code — the operator runs it under `nice -n 19` (see the `fast_probe.py` header); no load gate, no memory floor, no neighbour check |
| State | `link_state_cursor` scopes `sweep` + `sweep-demand` | writes only the five verdict columns on `download_sources`; no `link_state_cursor` / `link_state_runs` rows |
| Stop | SIGTERM / systemd timeout: `propagate` commits per batch and parks its cursor, resumes next run | see "Stopping the live sweep" below — pre-INF-05 a SIGTERM kills it mid-write |

Before declaring the sweep stopped, check BOTH process names — plus the
wrapper (`pgrep -f '[f]ast-probe-sweep-loop'`):

```bash
pgrep -f '[f]ast_probe.py'
pgrep -f '[l]ink_state_runner.py'
```

### Install (timer lane — gate step, on the Oracle VM)

```bash
sudo cp Fast_Fetch/systemd/radd-link-sweep.service Fast_Fetch/systemd/radd-link-sweep.timer \
    /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now radd-link-sweep.timer
systemctl list-timers radd-link-sweep.timer   # next firing, last firing
```

Timer: every 6 hours at 02,08,14,20:30 (2 h offset from the prefetch
timer at 00,06,12,18:30, clear of the daily backup at 03:40), plus
`RandomizedDelaySec=600` (up to 10 min jitter — the runbook omitted this)
and `Persistent=true` (a missed firing catches up). Verified against the
unit files on main: `ExecStart` runs `link_state_runner.py --once
--max-rows 500 --demand-limit 200 --load-ceiling 4.0 --max-wait-seconds
300`, `Nice=19`, `IOSchedulingClass=idle`, `TimeoutStartSec=3600`. The
units ARE installed live (verified 24 Nov: the timer fired 02:30:55 PKT);
the commands above are the re-install procedure.

### Check it

```bash
journalctl -u radd-link-sweep.service --since "6 hours ago"   # per-run summary
sqlite3 'file:/home/ubuntu/radd-stream-data/merged_catalog_20261025.db?mode=ro' \
  "SELECT scope, last_id, rows_checked, state FROM link_state_cursor WHERE scope LIKE 'sweep%';"
```

The cursor query reflects the timer lane ONLY — `fast_probe.py` writes no
cursor rows. Per-site sweep scopes (`sweep-<site>`) also match the
`LIKE 'sweep%'` filter. Verified live 24 Nov: `sweep-demand|0|2759|
complete`, `sweep|912901|16800|running`, plus per-site rows. The 02:30 run
that day, from the journal: demand phase 30 pressed rows → 28 checked, 24
updated (`expired` 18, `active` 6); background phase 600 checked, 572
updated (`expired` 369, `active` 169, `dead` 34), `last_id` 912901; cache
prune 0 orphans, 94 expired; 32.2 s CPU, 47.3 MB peak.

### Guards (timer lane — verified against `link_state_runner.py` on main)

- Load gate: waits while 1-minute load is above 4.0 (max 300 s), then
  yields — exit 0, the next timer firing retries. (`LOAD_CEILING = 4.0`,
  `MAX_WAIT_SECONDS = 300`, via `prefetch_worker.wait_for_quiet_machine`.)
- Memory floor: yields below 1,500 MB MemAvailable. (`MEMORY_FLOOR_MB =
  1500`.)
- Neighbour check: yields while `radd-prefetch.service` or
  `radd-backup-hf.service` is active (read via `systemctl is-active`).
- Nice=19 + idle IO class (from the unit file).
- A yield returns exit 0 with `yielded` set — the timer retries next
  firing.
- `--max-rows` is a page-boundary stop (200-row pages), so a run can
  overshoot by up to one page: the 02:30 run checked 600 background rows
  against `--max-rows 500`.
- Cache hygiene (not in the trash runbook): every non-dry-run ends with a
  bounded `resolved_cache` prune (orphans + expired DELETEs); it never
  fails the sweep. Live: 0 orphans, 94 expired on the 02:30 run.

### Stopping the live sweep (INF-05 — target, NOT live yet)

Post-INF-05 behaviour (branch
`muse/phase4-inf05-sweep-stuck-child-20261124`, tip `1ca54327`, CI-green,
NOT merged): SIGTERM/SIGINT only set the stop event (a second signal still
kills at once); workers stop after their in-flight URL; the writer flushes
earned verdicts; the run reports `"stopped": true` and exits 0. The batch
watches the STOP file (`--stop-file`, default
`/tmp/fast-probe-sweep.stop`, env `RADD_SWEEP_STOP_FILE`). The wrapper trap
forwards TERM/INT to the child. INF-05 also commits the wrapper to the repo
as `Fast_Fetch/fast-probe-sweep-loop.sh`.

LIVE TODAY (24 Nov, verified read-only): the wrapper on the VM is still
the OLD Nov-18 copy (1,511 bytes, `forward_term` count 0, no trap, child
in the foreground). Until INF-05 lands and the wrapper is installed at the
next natural relaunch, the OLD behaviour applies: SIGTERM to the child is
exit 143 mid-write (queued verdicts lost, writer never flushed); killing
the wrapper orphans the child, which never reads the STOP file mid-batch
and keeps writing through the backup window. Do NOT describe INF-05
behaviour as live. Observed 08:38 PKT 24 Nov: `/tmp/fast-probe-sweep.stop`
present since 06:45 while a `fast_probe.py` child (PID 2727160,
`--apply --max-rows 5000 --workers 48`, wrapper PID 2727157) was still
probing — exactly the old-behaviour signature.

Gate check until the wrapper is installed: after touching STOP,
`pgrep -f '[f]ast_probe.py'` must clear AND the wrapper must be gone — a
child with no wrapper is the stuck child; SIGTERM the exact child PID once
(graceful once INF-05 lands; exit 143 until then). After the wrapper
install, the pgrep check alone suffices. Install (gate owner, at the next
natural relaunch — do NOT install mid-gate, do NOT restart a running loop
to pick it up): `cp Fast_Fetch/fast-probe-sweep-loop.sh
/home/ubuntu/fast-probe-sweep-loop.sh`, verify `grep -c forward_term`
is >= 2, relaunch ONLY via the wrapper, never `fast_probe.py` directly
(it runs one batch and exits).

Relaunch (live): `nohup /home/ubuntu/fast-probe-sweep-loop.sh >/dev/null
2>&1 &`, then verify BOTH the wrapper process and a `fast_probe.py`
child are running, plus a fresh "sweep loop started" line in
`/tmp/fast-probe-sweep.log`. The loop runs 5,000-row batches until a batch
probes 0 rows; a batch runs 30 min to 14 h (INF-05 measurement) — the stop
can never wait that out, which is why the graceful stop exists.

### Honest arithmetic (kept from the runbook, timer lane)

A probe costs ~1–2 s of worker time (host-dependent; presigned hosts
~4.4 s/row). Four runs a day at 500 rows is ~2,000 rows/day from this
runner plus ~1,600/day from the tick — full coverage of 4.43M rows still
takes years. What the runner fixes is the ORDER: pressed rows get
verdicts within a day. Raising `--max-rows` needs a quiet-window
measurement first, not optimism — and never while a gate backup or a
prefetch sweep is running.

### What changed in this restore

1. Runner identity: the runbook's `link_state_runner.py` is the timer
   lane, which still runs; the live sweep is `fast_probe.py` (wrapper
   `fast-probe-sweep-loop.sh`). The install/guards/verify sections above
   describe the timer lane; the live sweep gets its own rows in the table
   and the stopping section.
2. Added the INF-05 stopping section with the honest live-vs-target
   transition — INF-05 is NOT live until it lands and the wrapper is
   installed at the next natural relaunch.
3. Added the cache-prune step (runbook omission) and the page-boundary
   `--max-rows` overshoot (600 observed vs 500).
4. Added `RandomizedDelaySec=600` + `Persistent=true` (timer details the
   runbook omitted).
5. Scoped the "interruption is safe" claim to the timer lane; the live
   sweep's interruption story is the INF-05 section.
6. Clarified the cursor check reflects the timer lane only
   (`fast_probe.py` writes no cursor rows).
