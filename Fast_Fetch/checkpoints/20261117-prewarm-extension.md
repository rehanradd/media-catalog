# 2026-11-17 — pre-warm extension: 21:00 top-up 50 → 100 titles `[PREWARM-EXTENSION]`

## What was measured (live, 20:24–20:25 PKT 17 Nov, load 0.66)

Phase 0 today: 6-hourly sweep warms the top `prefetch_queue` titles (live
panel knob: 100, cap `warmup_count` 50/sweep); 18:00 fill warms the top 200
with start rung (480p) + auto rung (≤2 links/title, cap 400); 21:00 top-up
re-warms the top 50 (cap 100). Live `resolved_cache`: pool 308 rows, jit
2,233. Warmth at 20:25 PKT — 2.4 h after the fill, mid-peak — was poor:
only **66/200** top titles warm (≥1 pool row with horizon >1 h), 94/200
hold any pool row, **106/200 hold none**; ranks **201–400: 0/200 warm,
0/200 with any pool row**. Pool horizon buckets: 223 expired, 1 warm <1 h,
84 warm >1 h. Most banked rows carry ~2 h host horizons, so the 18:00
rows for ranks 51–100 are largely cold by 21:00 and the old top-50 top-up
left them to rot until the 00:30 sweep. Ranks 201+ were never warmed at all.

## Change (branch `muse/prewarm-extension-20261117`, from main `dc2193d9`)

Small-first, one step: `FILL_TOPUP_LIMIT` 50 → **100** in
`Fast_Fetch/prefetch_worker.py` (+ rationale comment with the numbers
above). The top-up service passes no `--limit`, so the constant is what
the live 21:00 run actually warms; the warmup cap follows automatically
(2 links × 100 titles = 200) and only cold rows resolve, under the run's
own load gate (4.0, 300 s), flock and 45-min autocrawl-lock wait. Added
wall time is minutes, not a second fill. Docstring, usage, `--topup`
help, top-up service Description/comment and timer comment all say
top-100 now. New test `test_topup_dry_run_covers_top_100` (120-post
scratch DB, `--topup --dry-run` plans "selected 100 priority titles",
banks nothing). The 18:00 fill stays top 200 (its `--limit 200` is
pinned in the service file) and ranks 201+ stay cold — the next step
after this one is measured, not this one.

NOT built, per the standing owner decision (19:50 PKT 16 Nov): crowd
link-warming Phases 1–3, any crowd/user warm-up, any client-resolver
build beyond the authorised field-test harness. Press-banking and the
120 s reverify floor are untouched (`resolved_cache.py` not modified);
no Play 302 is cached; no `expires_at` trust added.

## Verification

Local: `test_prefetch_worker.py` 27/27 OK (26 + 1 new). Full backend
gate (`run-backend-gate.py`) + importer tests solo: see the branch
report (run after the commit). NOT live — gate deploy is the
supervisor's call; the top-up service file needs no reinstall (no
`--limit` in its ExecStart), only the code deploy + the next 21:00
firing. Next measurement after it lands: re-run the coverage probe at
~21:30 PKT and compare top-100 warm share against today's 66/200.
