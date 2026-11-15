# Link-health sweep runner — runbook

The in-process maintenance tick probes 400 rows every 6 hours. At that rate
the 4.43M unchecked rows take years (live cursor `serve`: id 17,605 on
2026-11-14 → 18,005 a day later). The runner
(`Fast_Fetch/link_state_runner.py`) is the rate fix: an out-of-process
oneshot, fired by a systemd timer, that probes **demand-first** — the rows
users actually pressed — then grinds the background scan on its own cursor.

## What a run does

1. **Demand phase** (scope `sweep-demand`): up to `--demand-limit` (default
   200) pressed rows from `resolved_cache` whose ledger verdict is missing
   or older than 30 days, most-recent press first. When measured 2026-11-15,
   1,505 of 1,508 pressed rows needed a verdict.
2. **Background phase** (scope `sweep`): up to `--max-rows` (default 500)
   rows from the id-ordered pending scan, resumed from the `sweep` cursor —
   never the serve tick's `serve` cursor.

Both phases go through `link_state.propagate`, so verdicts, the runs
ledger, the `cat_links` projection sync and the transport-no-verdict rule
are the same machinery the tick uses. Probing writes verdicts to the
ledger only; it never displays anything — a flagged (18+) row keeps its
flag and stays hidden from every public surface by the adult clause at
serve time.

## Guards (why it cannot hurt the live service)

- Load gate: waits while 1-minute load is above 4.0 (max 300 s), then
  yields — exit 0, the next timer firing retries.
- Memory floor: yields below 1,500 MB MemAvailable.
- Neighbour check: yields while `radd-prefetch.service` or
  `radd-backup-hf.service` is active — a sweep never overlaps a prefetch
  sweep or a backup.
- Nice=19 + idle IO class (from the unit file).
- Interruption is safe: `propagate` commits per batch and parks its
  cursor; a SIGTERM or a systemd timeout resumes cleanly next run.

## Install (gate step, on the Oracle VM)

```bash
sudo cp Fast_Fetch/systemd/radd-link-sweep.service Fast_Fetch/systemd/radd-link-sweep.timer \
    /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now radd-link-sweep.timer
systemctl list-timers radd-link-sweep.timer   # next firing, last firing
```

Timer: every 6 hours at 02,08,14,20:30 (2 h offset from the prefetch
timer at 00,06,12,18:30, clear of the daily backup at 03:40).

## Check it

```bash
journalctl -u radd-link-sweep.service --since "6 hours ago"   # per-run summary
sqlite3 'file:/home/ubuntu/radd-stream-data/merged_catalog_20261025.db?mode=ro' \
  "SELECT scope, last_id, rows_checked, state FROM link_state_cursor WHERE scope LIKE 'sweep%';"
```

## Honest arithmetic

A probe costs ~1–2 s of worker time (host-dependent; presigned hosts
~4.4 s/row). Four runs a day at 500 rows is ~2,000 rows/day from this
runner plus ~1,600/day from the tick — full coverage of 4.43M rows still
takes years. What the runner fixes is the ORDER: pressed rows get
verdicts within a day. Raising `--max-rows` needs a quiet-window
measurement first, not optimism — and never while a gate backup or a
prefetch sweep is running.
