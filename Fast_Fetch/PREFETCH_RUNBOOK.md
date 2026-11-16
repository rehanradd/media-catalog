# Prefetch runbook — Gap A7

The phase-1 resolved cache (`Fast_Fetch/resolved_cache.py`) answers stream
requests from `resolved_cache` — but only the request path ever wrote to it.
The prefetch worker (`prefetch_worker.py`) existed in main and never ran: its
default load ceiling (2.0) parked it permanently while the box idled at load
6–8 under the five qoder/agy agent lanes. The live table proves it — 1 JIT
row, 0 pool rows (measured 2026-11-11).

This is the run mode that fixes it: **a systemd timer + oneshot service**,
the same scheduling mechanism the box already uses (the daily 03:40
Hugging Face backup timer is active; cron is not installed here).

## What runs, and when

- **Timer** `radd-prefetch.timer` — a `--once` sweep every 6 hours at
  00:30, 06:30, 12:30, 18:30 PKT (`RandomizedDelaySec=600`,
  `Persistent=true`). 6h is the shortest cache horizon (temporary rows), so
  each sweep re-warms rows before they go cold. The times are offset from the
  03:40 backup and the 02/08/14/20:00 autocrawl so no two heavy jobs share a
  slot.
- **Service** `radd-prefetch.service` — `Type=oneshot`, runs
  `prefetch_worker.py --once --limit 200 --load-ceiling 4.0
  --max-wait-seconds 300` as `ubuntu` from `/opt/radd-stream`, with
  `RADD_DB_PATH` set to the live catalog (the same value
  `radd-stream.service` carries).

## Why this mode, and why these numbers

- **Ceiling 4.0** — measured 2026-11-11 the box idles at load ~0.1 (the
  6–8 idle of 2026-11-04 was the agent lanes, retired since). On this
  1-vCPU box 4.0 parks the worker only under genuine saturation, and the
  timer re-fires a yielded sweep six hours later. If the box's idle load
  changes materially, retune `--load-ceiling` in the service file — the
  worker's own default (`LOAD_CEILING`) carries the same value.
- **Max wait 300s** — a sweep that finds the box busy waits at most 5 minutes
  for a quiet slot, then yields (`gated` in the stats) instead of hanging the
  oneshot until systemd kills it. The per-resolve gate still applies inside
  a running sweep, so a box that gets busy mid-sweep is left alone too.
- **Nice=19 + idle IO class** — the same deprioritisation as the backup
  unit, so the worker cannot steal CPU or disk from the live API even while
  it is resolving.
- **TimeoutStartSec=7200** — a full cold sweep (200 links, one slow unwrap
  each) can legitimately run over an hour; 2h matches the backup unit.

## Install (landing step — not done by the branch)

```bash
sudo cp Fast_Fetch/radd-prefetch.service Fast_Fetch/radd-prefetch.timer \
    /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now radd-prefetch.timer
```

No service restart, no backup, no live-tree change beyond the two unit
files. The timer starts the worker; nothing else runs it.

## Verify it works

```bash
systemctl list-timers radd-prefetch.timer   # next firing, last firing
journalctl -u radd-prefetch.service --since "6 hours ago"
# each sweep logs one line: sweep: posts=… tier1=… warm=… resolved=… failed=… gated=…
```

Check the pool itself (read-only) — pool rows should grow after the first
firing, and stay warm:

```sql
SELECT source, COUNT(*) FROM resolved_cache GROUP BY source;
SELECT COUNT(*) FROM resolved_cache WHERE source = 'pool'
  AND reverify_after > strftime('%s', 'now');
```

A sweep that finds everything warm logs `warm=N resolved=0` and finishes
in seconds. A first sweep on an empty pool resolves up to 200 cold links
and takes a while — that is the bootstrap, not a problem.

## Safety properties (pinned by `test_prefetch_worker.py`)

- The worker only writes `resolved_cache` rows, via
  `record_resolved_cache(..., source='pool')`, inside the success branch of
  a v2 resolve. Failed resolves bank nothing.
- Adult law: the priority query excludes `is_18_plus=1` posts **and** any
  post sharing a `canonical_key` with a flagged one — the worker never even
  resolves a link for a flagged work.
- The load gate is checked before **every** resolve, not just at sweep
  start; a sweep yields after 300s of busy box.
- Dry-run (`--dry-run`) prints the plan and touches nothing remote.

## Pre-peak fill (play-fast §2 (j), 2026-11-16)

The 6-hourly sweep warms the Tier-1 winner of the top 200 — but the
player's first press is the **start rung** (480p unless the viewer set a
default), not the highest band, and the app's Detail pre-resolve warms the
**auto rung** (highest playable band). A fill that warms only the Tier-1
winner leaves the 480p first press cold. The fill warms both rungs, ≤2
links per title, before the evening peak.

### What runs, and when

- **Timer** `radd-prefetch-fill.timer` — a `--fill` run every evening at
  18:00 PKT (`RandomizedDelaySec=300`, `Persistent=true`).
- **Service** `radd-prefetch-fill.service` — `Type=oneshot`, runs
  `prefetch_worker.py --once --fill --limit 200 --workers 2
  --load-ceiling 4.0 --max-wait-seconds 300` as `ubuntu` from
  `/opt/radd-stream`, with `RADD_DB_PATH` set to the live catalog.
- **Timer** `radd-prefetch-topup.timer` — a `--topup` run every evening at
  21:00 PKT (`RandomizedDelaySec=300`, `Persistent=true`).
- **Service** `radd-prefetch-topup.service` — the same fill on the top 50,
  with `--max-autocrawl-wait-seconds 2700`: the 20:01 autocrawl merge runs
  until ~21:30, so the top-up waits up to 45 min for the merge's lock
  instead of yielding every night.

18:00 is one hour before the 19:00–23:00 peak — late enough that the 5 h
FSLv2 URLs minted here still live at the peak's tail (~23:00), early
enough that a ~30 min fill finishes before the peak. The sweep units stay
unchanged (00:30/06:30/12:30/18:30); the fill and the sweep never merge
into one unit.

### What the fill warms

- **Start rung + auto rung** (`fill_links_for_post`): the playable 480p
  tier if the title has one, else the auto tier; plus the auto tier when
  it differs — deduped, 0–2 links per title. A download-only title warms
  nothing. The fill does **not** warm the Tier-1 winner alone.
- **Pins first**: `--pins 12,45,78` (max 20, in order, bad ids dropped)
  resolves the pinned titles before the priority pick. A pin is an
  operator's wish, never a bypass of the adult law — a flagged pin is
  dropped by the same exclusion as every other title. There is no
  curated pin list to read today: the hero is rule-based ("latest"), so
  pins come from the operator's `--pins` argument (or the timer's
  ExecStart) until a panel pin slot exists.
- **Hubcloud family last**: links whose service is `fsl_r2`,
  `hubcloud_10g` or `hubcloud` (or whose stored URL contains "hubcloud")
  resolve last in the run — the FSL/FSLv2 variant is only known after
  the unwrap, so the whole family goes last and its ~5 h clock starts
  as late as possible.
- **Warmup cap**: the fill does **not** consult the panel knobs — its cap
  is `2 × limit` (400 for the top-200 fill, 100 for the top-up), because
  it resolves up to two links per title. Explicit `--limit` /
  `--warmup-count` still wins. The sweep keeps its knob behaviour.

### The warmup_count correction (read before touching the knob)

The play-fast report said "warmup_count 50 throttles the top-200 to 50
links/sweep". That was stale twice over. `panel_feed.TUNING_KNOBS`
already carried `(200, 0, 200)` with the comment "Player Fix v2: 200,
not 50", and the Launch Plan Phase 0 slice (main `dca9002c`, 2026-11-16)
made 200 the canonical default in `AdminV2/admin_v2/store.py` too
(OQ-2: the prefetch selector picks the top 200 and the old cap throttled
the sweep to 50). **Do not re-change the store default.**

The **live** `tuning_settings` row still holds 50 (seeded at panel
install; it changes only by an operator/panel action, which Phase 0
deliberately did not touch). **The fill needs no operator action on
that row**: it bypasses the panel knobs entirely, and the running sweep
is likewise unwired from the panel today — the worker never reads the
panel DB (`panel_db_path()` returns None unless `RADD_ADMIN_V2_DB` or
`PANEL_DB_PATH` is set, and the prefetch service sets neither), so the
live row is inert for the running worker. If the worker is ever wired
to the panel, raise the row to 200 through the panel — never by
editing the live DB by hand.

The 18:30 sweep measured 2026-11-16 banked ~68 links (`warm=0`) despite
the cap being effectively none — `warm=0` is the steady state, not a
bug: no-TTL rows die in ~2 min (the reverify floor), and 5 h FSLv2 rows
banked at 12:30 expire ~17:30, before the 18:30 sweep. Only ~22 pool
rows were still warm at ~20:30 that evening.

### The TTL limitation (honest)

The fill's evening value concentrates in hosts that declare a TTL.
A row banked from a no-TTL host is unservable ~2 min after the fill
(the 120 s reverify floor), so the 18:00 fill cannot carry a no-TTL
title to the 19:00 peak — only the sweep's re-resolution at press time
can. What the fill buys is the titles whose hosts declare a TTL
(FSL/FSLv2 ~5–8 h, permanent direct files up to 24 h). Do not judge the
fill by its row count at 19:00; judge it by the fill-hit share below.

### Overlap guard (critical on this 1-vCPU box)

- **Shared flock** `/tmp/radd-prefetch.lock`: the fill, the top-up and
  the sweeps are one family — a run that finds the lock held yields at
  once (`another prefetch job holds the lock; yielding this run`). The
  18:00 fill sits 30 min before the 18:30 sweep; if the fill is still
  running at 18:30, the sweep yields and retries at 00:30.
- **Autocrawl lock** `/tmp/radd_autocrawl.lock`: the fill waits up to
  `--max-autocrawl-wait-seconds` (300 s; 2700 s for the top-up) for the
  merge's lock, then yields instead of resolving into a merge.
- **Load gate**: the same `--load-ceiling 4.0` / `--max-wait-seconds 300`
  as the sweep, checked before every resolve.
- **Gate backup has no machine-readable marker** — the load gate is the
  backstop, plus the operator's gate procedure ("check no sweep
  running" before starting a backup). Never start a gate backup while a
  fill is running; check `systemctl status radd-prefetch-fill.service`
  first.
- Dry-run skips both locks (it resolves nothing, so there is nothing to
  guard).

### Install (landing step — not done by the branch)

```bash
sudo cp Fast_Fetch/radd-prefetch-fill.service Fast_Fetch/radd-prefetch-fill.timer \
    Fast_Fetch/radd-prefetch-topup.service Fast_Fetch/radd-prefetch-topup.timer \
    /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now radd-prefetch-fill.timer radd-prefetch-topup.timer
```

No service restart, no backup, no live-tree change beyond the four unit
files. The sweep timer/service stay exactly as they are.

### Verify it works

```bash
systemctl list-timers radd-prefetch-fill.timer radd-prefetch-topup.timer
journalctl -u radd-prefetch-fill.service --since "24 hours ago"
# each fill logs one line: starting (fill): top 200, workers 2, warmup cap 400, pins 0, ...
# then: sweep: posts=… tier1=… warm=… resolved=… failed=… gated=… pins=… links=…
```

The startup log now reads `starting (fill|sweep): ...` — the mode word is
new; update any log parser that matched the old `starting: top …` line.

**Fill-hit measurement** — share of 19:00–23:00 presses served from
fill-warmed rows. The stream and download routes count every serve into
`fill_serve_stats` (one row per day/hour/route: `pool_hits`,
`jit_hits`, `cold_resolves` — aggregates only, no per-press and no
per-user records). Every note reuses a connection the route already
holds (stream hit: the lookup block; stream cold: the bank block;
download hit: the lookup block; download cold: the bank block) — never
a new one: the download hit path's connection count is pinned at 3
(`test_host_classifier.py::TestViewerShapeGate`, see
`HITPATH_CONNECTIONS.md`), and a standalone counting block broke that
pin once before the notes moved into the lookup/bank blocks. Read it after a week:

```sql
SELECT day, hour, route, pool_hits, jit_hits, cold_resolves
FROM fill_serve_stats WHERE day = '2026-11-17' AND hour BETWEEN 19 AND 22
ORDER BY hour, route;
```

Or from Python (same numbers, one call):

```python
from resolved_cache import serve_share
serve_share(conn, day="2026-11-17")          # 19:00-22:00, both routes
serve_share(conn, day="2026-11-17", route="stream")
```

The 19:00–23:00 pool share **is** the fill's measured effect, with one
documented over-count: a 12:30 sweep row on a permanent direct file
(24 h cap) can still serve at 19:00. In practice the evening pool is
FSL/FSLv2/temporary rows, dead long before then — the over-count is
small. A pool share that does not rise week over week means the fill is
banking rows the peak never presses, or the rows are dying before the
peak (see the TTL limitation above).
