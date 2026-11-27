# 2026-11-27 — Self-healing Branch 6: durability (log + reconciliation + weekly prune)

Branch `selfheal-b6-durability`, base `cbcc000c` (Branch 5 re-pin). Design doc
§4 DURABILITY — the last self-healing branch; it only observes Branches 1–5.

## What changed

- **New `Fast_Fetch/self_healing_log.py`** (stdlib-only): the audit log.
  `log_event(kind, ...)` appends exactly one JSON line to
  `self_healing.log` in the data dir (`/home/ubuntu/radd-stream-data/`
  on the live box, `RADD_DATA_DIR` when set, `RADD_SELF_HEALING_LOG`
  override for operators/tests). Line shape: `{ts, kind, site, host,
  old, new, discovered_by, reason, verified_by}`. Kinds:
  `site-move | host-move | parked-flag | cache-refusal | expiry-banked`;
  channels: `sweep | recheck | redirect | search | tld` (mapped from
  each caller's own vocabulary). Writing never raises; a 50 MB safety
  valve rotates an unrotated log. `rotate_log()` moves a log whose
  last write is from an earlier ISO week to a week-suffixed file.
- **Log wiring (one line per auto-change, behaviour unchanged):**
  site-move in `domain_resolver._record_verified` (only when the base
  actually changes, after the registry lock is released; method mapped
  mirror/cached→recheck, tld-probe/numeric→tld, search-ddg/sister/
  telegram/github→search, bookmark-follow→redirect); host-move in
  `host_registry.apply_move` (evidence prober→redirect,
  parked-sweep→sweep, anything else→recheck); parked-flag in
  `host_registry._mark_host_parked` (new = `parked` /
  `parked_candidate`, channel sweep); cache-refusal + derived-expiry
  in `resolved_cache.record_resolved_cache` (refusal channel =
  pool→sweep, jit→recheck; expiry-banked only for a DERIVED expiry —
  an explicit resolver expiry is the resolver's own data and is not
  logged).
- **Merge rule (§4.2):** `self_healing_log.reconcile_site_host(site,
  queue_path=...)` loads both registries; when the site's
  active-base host is dead (no successor) or moved in `host_registry`,
  it logs the disagreement (kind site-move, discovered_by recheck,
  verified_by host-registry) + enqueues the site for `resolve_site`
  via `recheck_queue.enqueue` (dead_rows 0). Neither registry is
  written by it. Wired into `fast_fetch.resolve_live_source` right
  after the row load (`releases.source_site` is read in the existing
  adult-check join; `download_sources` has no `source_site` column),
  memoised once per site per hour per process
  (`_maybe_reconcile_site_host`, `_SITE_HOST_RECONCILE_TTL` 3600 s).
  The host registry already wins the rewrite (`_rewrite_host`); this
  is the signal that the SITE record needs a discovery chain.
- **Single source of truth audit (§4.2, first half):** verified by
  grep — the only writers of `domain_registry.json` /
  `host_registry.json` are `domain_resolver` /
  `host_registry` (lock + atomic replace); `redact._registry_needles`,
  `restamp_provenance`, `site_scout`, `fast_fetch.registry_live_domains`
  read only. No code path writes either registry file directly.
- **Observations prune (§4.3):** `host_registry.prune_observations`
  (90-day retention, `dry_run` supported): drops stale records whose
  `last_seen`/`first_seen` is older than the window AND that never
  fired; records with `applied_at` / `evaluated_at` are KEPT (spent
  markers — pruning them would let a dead pair re-accumulate from
  zero); undated records are kept.
- **Weekly job:** new `Fast_Fetch/self_heal_weekly.py` CLI (prune +
  rotate, one JSON summary line, `--dry-run`) + new
  `Fast_Fetch/systemd/radd-self-heal-weekly.service` / `.timer`
  (Sundays 07:00, clear of sweep 02/08/14/20:30, prefetch
  00/06/12/18:30, backup ~03:40–06:30, fleet 03:30, autocrawl 02:00,
  host-roots Sun 01:33, themoviebox-sync 05:00, prefetch-fill 18:00,
  topup 21:03, domain-probe 10:30). The gate installs the units.
- **No second copy (§4.4):** verified by grep — no backend module
  references `movie-downloader-state.json`; pinned by a test.

## Tests

- New `Fast_Fetch/test_self_healing_log.py`: **29/29** — log shape +
  invalid kind/channel, rotation, prune (old/applied/evaluated/
  recent/undated/dry-run/missing), reconciliation (moved rewrites +
  queues, dead queues with no rewrite, agreement queues nothing,
  unknown site), one-line-per-kind coverage through the real call
  sites, resolve-hook memo, weekly-job dry-run/apply, no-second-copy
  scan. Registered as a Backend CI step (231 → 232 🧪 steps).
- Neighbours green locally: host_registry 44/44, domain_resolver
  81/81, resolved_cache 124/124, fast_fetch 33/33, fast_probe 81/81,
  domain_recheck 8/8, recheck_queue 8/8, expiry_bank 36/36,
  docs_invariants 22/22.

## Gate note

- Full gate per the brief (backup + counts exact before ff, integrity
  to `/tmp/b6-integrity.out`, service restart, live tests, sweep
  relaunch, re-pin, panel-push sync, branch delete). The weekly
  timer/service units are installed at gate time from the repo copy
  (diff-verified) and the timer is enabled + the service run once to
  prove it.

## Rotated out of CONTINUITY.md

The seven oldest "Rotated session history" bullets, verbatim, moved
here to bring `CONTINUITY.md` back under its 12 KB budget:

- **2026-11-24 — DOC-05 pre-peak fill: CLOSED, already live.** On main + installed; ran 23 Nov. F-3 used the wrong path. Note: `20261124-doc05-prefetch-fill-closure.md`.
- **2026-11-24 — PNL-02/03:** 480p default; coins surface removed from panel; v6 migration; branch `muse/phase4-pnl02-03-quality-coins-20261124`.
- **2026-11-24 - INF-05 sweep stuck-child fix:** graceful stop in `fast_probe.py`; loop trap in `fast-probe-sweep-loop.sh`. Note: `20261124-inf05-sweep-stuck-child.md`.
- **2026-11-24 — API-03 expiry banked at resolve time (checkup P0):** branch `muse/phase4-api03-expiry-bank-20261124`. New `expiry_bank.py` (stdlib-only, pure): signed R2/s+e/absolute-epoch parse + 900 s Google usercontent window; wired into `resolve_live_source` + `_bank_hubcloud_siblings`. Tests: `test_expiry_bank.py` 36/36. Full note: `checkpoints/20261124-api03-expiry-bank.md`.
- **2026-11-24 — API-14 retry/timeout caps:** muse/phase4-api14-retry-caps-20261124; test_retry_caps.py 36/36 + CI step.
- **2026-11-23 — rotated out:** catalog pipeline fixes — verbatim in
  `checkpoints/20261126-wave6-continuity-rotation.md`.
- **2026-11-21 — rotated out:** 1fichier resolver wiring, One Piece identity
  cluster, Registry §2 — verbatim in `checkpoints/20261126-wave6-continuity-rotation.md`.
