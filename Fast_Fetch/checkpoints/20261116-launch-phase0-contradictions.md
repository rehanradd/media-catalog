# Launch plan Phase 0 — fix the contradictions (2026-11-16 ~20:40 PKT)

Branch `muse/launch-phase0-20261116`. Design: `~/workspace/launch-plan-design/design.md`
§6.1 Phase 0. Backend-only slice; no new flags, no panel UI, no app wiring, no
gate flip, no behaviour change for users (the live values already match the
canonical ones — only fresh-row defaults change).

## What Phase 0 closed

- **G-1** — `admin_panel.py` `MONETIZATION_FIELDS`: `feature_quality_gate`
  default 1 → 0, and the "Default ON since the security release" comment
  replaced with the canonical-default rationale. The catalog DDL was the
  outlier against the live row, the panel v2 DDL, the published snapshot and
  the app default (all 0).
- **G-5** — `coin_redeem_weekend` 70 → 1200, `coin_redeem_monthly` 200 → 5000
  in `MONETIZATION_FIELDS`, matching the panel row and the live catalog.
- **G-7** — `store.py` `TUNING_DEFAULTS`: `warmup_count` 50 → 200 (canonical
  per the design's OQ-2 rule: the `panel_feed` 200 is live-tuned with a
  recorded reason — Player Fix v2, the prefetch selector picks the top 200
  titles and the old cap throttled the sweep to 50 links — so the store
  changes to 200); `tabs` gains `live_tv` (was dropping a live tab).
  `panel_feed.py` needed no edit — its `TUNING_KNOBS`/`TABS_DEFAULT` were
  already the canonical values.
- **G-8** — `free_quality_cap` is now a catalog column
  (`MONETIZATION_FIELDS`, `_TEXT`, default `"720p"`), served by
  `admin_panel.remote_config()` via `_resolve_free_quality_cap()` (only
  `"480p"`/`"720p"` pass through; `""`/typo/missing → `"720p"`), and the
  `viewer.py` overlay that replaced it with the `tuning_settings` mirror is
  deleted. A pre-existing catalog row gains the column via
  `_ensure_monetization`'s ALTER and reads back `""` until the operator sets
  it.

## What the design got wrong (found by reading the code, not the design)

- **G-5 has a fourth defaults source the design never names:**
  `engagement.py` `REDEEM_COIN_COSTS` (`{"weekend": 70, "monthly_vip": 200}`)
  is the fallback `resolve_ladder()` uses for a zeroed/missing knob. The
  catalog fix alone would have left a stored 0 repricing the weekend pass
  to 70 while a fresh row charges 1200. Fixed in this slice: 1200/5000, with
  the comment updated. T-21 asserts all four sources agree.
- **G-8 is half-fixed by design, and that is deliberate:** `viewer.py:3603`
  (`_facet_filter_clauses`, the `free_only` list filter) still reads the
  cap from `panel_feed`/`tuning_settings`. Threading the catalog value
  through the clause builder would loosen the filter for an operator who
  set tuning to `480p` (catalog default is `720p`, nothing writes the
  catalog column yet) — fail-open, and a behaviour change, which Phase 0
  forbids. The served value is unified now; the enforcement read moves to
  the catalog when the tuning mirror is removed (Phase 2/3). The residual
  disagreement only shows if an operator set tuning to `480p`, and then in
  the fail-closed direction (server enforces stricter than the app shows).
- **The live `tuning_settings` row still holds `warmup_count` 50** (seeded
  by the old store default at panel install). The store-default change only
  affects future fresh seeds; the live row changes only by an operator/panel
  action. Phase 0 does not touch live rows.

## Tests (T-21 + T-22, `Fast_Fetch/test_launch_phase0.py`, 9 tests)

- T-21 builds both databases fresh in memory and reads the seeded rows:
  catalog field defaults, fresh catalog row, panel store
  (`store.connect(":memory:")` + `migrate()`), `panel_feed` constants,
  `engagement` shipped fallback + `resolve_ladder()` with and without values.
- T-22: fresh row serves every `feature_flags.*` key as real booleans plus
  `free_quality_cap "720p"`; the cap follows the catalog row (`480p` →
  `480p`, `1080p`/"" → `720p`); the `settings_unavailable` fallback body
  (closed connection) still serves every key.
- Red-first verified: on the pre-fix code the module fails 7 + 1 error.
- Two existing tests pinned the old defaults and were updated, not deleted:
  `test_admin_panel.test_remote_controls_have_honest_defaults_before_any_save`
  (gate `True` → `False`, docstring rewritten to the G-1 rationale) and
  `test_engagement.test_the_weekend_pass_expires_at_the_next_local_monday_midnight`
  (granted a flat 100 coins, which no longer covers a 1200-coin weekend
  pass — it now grants `resolve_ladder()["weekend"]["coins"]`, the same
  price-agnostic pattern `test_coins_engine` uses).

## Verification (local, this worktree)

- New module: 9/9 OK. `test_admin_panel.py`: 113/113 OK.
  `test_engagement.py`: 38/38 OK. `test_coins_engine.py`: 91/91 OK.
  `test_panel_feed.py`: 98/98 OK. `test_series_filter.py`: 12/12 OK.
- Suite registered in `.github/workflows/backend-ci.yml` (105 → 106 suites).
- `AdminV2/tests/test_ui_overhaul.py` posts explicit form values
  (`warmup_count "50"`, tabs without `live_tv`) — operator-submitted values,
  not defaults — so the store-default change does not affect it. No AdminV2
  test runs in any CI workflow (verified by grep); the store change is
  covered by the new T-21 test instead.

## Not in this slice (per the design)

Phase 1 (panel versioning/audit), Phase 2 (serve missing keys + new flag
columns), Phase 3 (app wiring, F-A/F-B), Phase 4 (full matrix), Phase 5
(launch — the owner's panel action). No live deploy from this slice: the
gate deploy waits for the post-autocrawl window (~21:30 PKT).
