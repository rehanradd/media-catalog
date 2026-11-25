# DOC-05 — Pre-peak prefetch-fill: CLOSED, already live (2026-11-24)

## Verdict

**CLOSED — the design was already built, landed, installed and running.**
The TODO's two frames ("install it" vs "record 'fill design never installed'")
both rest on the trash-read F-3 premise that no fill/topup units exist on
main and install status is unknown. Both halves of that premise are wrong:

- The units **are on main** — `Fast_Fetch/radd-prefetch-fill.{service,timer}`
  and `Fast_Fetch/radd-prefetch-topup.{service,timer}` (branch
  `muse/prepeak-fill-20261116`, live checkpoint `20261116-pre-peak-fill.md`).
  F-3 looked under `Fast_Fetch/systemd/`; the units live directly under
  `Fast_Fetch/`. (`Fast_Fetch/systemd/` on main holds only clearance-loop,
  domain-probe and link-sweep units.)
- The units **are installed on the VM** — enabled, active, firing nightly
  (fill 18:01 + top-up 21:04 on 23 Nov 2026).

No trash file was restored. No install, timer enable, restart, live-tree or
DB touch was performed for this item.

## Evidence (measured 24 Nov 2026, read-only)

- Main HEAD `a6e27ad6` (fresh `gh api`): fill.service `96857cb27036` (1,491 B),
  fill.timer `aeb5df951761` (542 B), topup.service `20060f6b47f0` (1,331 B),
  topup.timer `ace8c59eca53` (477 B), `prefetch_worker.py` `8a4c07d59b95`
  (45,319 B), checkpoint `20261116-pre-peak-fill.md` `acc318c0c4f5` (3,547 B).
  All six SHAs match the VM live tree (`/opt/radd-stream` at `a6e27ad6`,
  clean `git status`); the VM worker is byte-identical to main.
- VM timers: fill last fired Mon 23 Nov 18:01:21 PKT (next Tue 18:02:45),
  top-up last fired Mon 23 Nov 21:04:22 PKT (next Tue 21:04:56), sweep last
  fired Tue 24 Nov 06:37:43 PKT. All three enabled + active.
- Last fill (23 Nov 18:01): `starting (fill): top 200, workers 2, warmup cap
  400, pins 0, ... load ceiling 4.0` — then **aborted 18:22:18 `sweep failed:
  OperationalError: database is locked`** after ~21 min; systemd
  `Result=success` (worker catches + logs, exits 0).
- Last top-up (23 Nov 21:04): **completed** — `posts=100 pins=0 tier1=63
  links=71 warm=20 resolved=71 failed=24 skipped_no_link=37 gated=0` in
  756.4 s.
- Pool (read-only, ~07:55 PKT 24 Nov): `resolved_cache` pool 1,051 rows, 161
  still warm; jit 637 rows. Most FSL rows had expired overnight, as the
  design predicts — judge the fill by 19:00–23:00 pool-hit share
  (`fill_serve_stats` / `serve_share`, both on main), not morning row counts.

## Premises (each checked against main `a6e27ad6`)

- FSL ~5 h window: HOLDS (measured 23 Nov; API-03 now banks the true
  per-URL presigned `expires_at`, so the fill no longer depends on the
  assumed 5 h). Start rung 480p: HOLDS (`fill_links_for_post`,
  `prefetch_worker.py:423` — "the shipped default start"); PNL-02/03
  (branch `8da9b230`, CI-green, awaiting gate, NOT live yet) aligns
  panel/server defaults to 480p and does not change the fill. ≤2
  links/title: HOLDS (at most two links, one per band; warm-up cap
  2×limit). Load guards (ceiling 4.0, 300 s wait before every resolve,
  shared flock `/tmp/radd-prefetch.lock`, autocrawl-lock wait, Nice=19 +
  idle IO): HOLDS, with one observed gap — they guard CPU load, not
  SQLite write contention (see the 23 Nov DB-lock abort below).

## Drifts found (record only)

1. `/etc` top-up units are one revision behind the repo (cosmetic).
   Installed `/etc/systemd/system/radd-prefetch-topup.{service,timer}` say
   "top-50"; the repo tree (Nov 17) says "top-100" and the worker default
   is `FILL_TOPUP_LIMIT = 100`. Behaviour is top-100 either way (ExecStart
   carries no `--limit`). Re-copy the four unit files at the next gate.
   (The 16 Nov checkpoint still says "top 50" too.)
2. 23 Nov fill aborted on a DB lock (operational follow-up, not a design
   flaw). The fleet worker got the same class of fix on 20 Nov (30 s busy
   timeout + bounded retry, `20261120-fleet-lock-hardening.md`). Whether
   the prefetch worker deserves the same hardening is a lane decision.

## Design as found (trash)

Source: trash `Fast_Fetch/PREFETCH_RUNBOOK.md` lines 90–258 (fuller copy;
worktree copy trimmed to 89 lines). Fill 18:00 PKT top 200 + top-up 21:00
PKT top 50-as-written; start rung 480p + auto rung, ≤2 links/title, pins
first (max 20), HubCloud family last, FSLv2 ~5 h reasoning, shared flock +
autocrawl-lock wait + load gate 4.0, `fill_serve_stats` measurement.
