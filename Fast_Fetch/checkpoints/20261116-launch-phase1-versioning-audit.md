# Launch plan Phase 1 — panel versioning + audit for flag edits (2026-11-16 ~21:20 PKT)

Branch `muse/launch-phase1-20261116`. Design: `~/workspace/launch-plan-design/design.md`
§3.1, §3.4, §6.1 Phase 1. Backend/panel-only slice; no new flags, no app wiring,
no gate flip, no behaviour change for users (a publish writes the same values
the old direct write wrote — now versioned, snapshotted and audited).

## What Phase 1 closes

- **G-2** — a flag edit is now a publish. `put_monetization()` stages the edit
  in the draft row, then publishes in the design's order: (1) the live catalog
  row first, (2) the draft row + `config_version` bump + published snapshot
  (`app_remote_config_history`), (3) the audit row (`monetization.save`,
  before/after now carry `config_version`). Rollback can restore a flag flip
  again, because a snapshot exists for every publish.
- **G-3** — the audit row is written by the publish (it already was by the
  code — see "design got wrong" below; what Phase 1 adds is the version +
  snapshot that make the audit row mean something).
- **Failure semantics (§3.1)** — a failure at step 2 or 3 leaves the catalog
  published but not snapshotted: the catalog is never rolled back silently,
  the publish returns `publish_status: "published_but_not_snapshotted"`, the
  state is recorded in a one-row `monetization_publish_state` table (created
  lazily, like idempotency keys), `get_monetization()` surfaces it, and the
  next publish repairs it (re-snapshot + re-audit). New endpoint
  `POST /admin/api/v2/monetization/publish` (`publish_monetization`, confirm
  required) is the explicit repair path.
- **D-4** — creating or clearing a `feature` pause bumps `config_version` and
  writes a history snapshot (audit rows `pause.create`/`pause.resume` already
  existed). A non-feature pause does not bump the version.
- **D-6** — the v1 `/admin` flag toggles are read-only (disabled inputs + a
  pointer to the v2 panel), and the v1 save path refuses any payload carrying
  a flag key. The v1 process cannot version, snapshot or audit a flip, and
  routing it through the v2 service is not clean (separate processes; the
  version bump would race) — so the design's fallback applies: v2 is the
  only flag editor, v1 keeps read-only display. Non-flag v1 saves still work.

## What the design got wrong (found by reading the code, not the design)

- **G-3 is half-stale:** the code already wrote a `monetization.save` audit
  row in `put_monetization()` — the design's "zero rows" is a live-DB
  observation about usage (operators saved through v1, or never saved), not
  a code gap. Phase 1 keeps the action name and adds the version to
  before/after; the real gap was G-2 (no version, no snapshot), which is
  what made the audit row useless for rollback.
- **The snapshot shape already existed** (`_do_publish` / `rollback_remote_config`
  + `app_remote_config_history`): Phase 1 reuses it via one shared helper
  (`_record_versioned_snapshot`) for the monetization publish and for
  feature pause/resume, instead of inventing a second history. The helper
  deliberately does NOT apply `_do_publish`'s guards (adult blockers,
  empty-home refusal) — a flag flip must never be blocked by home-section
  state.
- **Rollback already pushed restored flags to the live catalog** (it was not
  broken, it was starved): with no monetization snapshot ever written, there
  was nothing to roll back to. Phase 1 feeds it snapshots; T-19's rollback
  half now passes.
- **`free_quality_cap` is not a `MONETIZATION_FIELDS` (v2) member**, so the
  staged publish does not carry it to the catalog — same as before (nothing
  writes the catalog column yet; Phase 2's job). Phase 1 changes nothing
  there.

## Tests (T-19, D-4, D-6 — `Fast_Fetch/test_launch_phase1.py`, 10 tests)

- T-19 (a/b): one flip → version +1, snapshot flag set == live catalog row,
  one audit row with before → after + versions.
- T-19 (c) three shapes: clean snapshot failure, partial failure (bump
  lands, snapshot insert fails — history gains a version gap, which is
  honest: history rows are never edited; the repair bumps again and
  snapshots), audit failure. Each reports `published_but_not_snapshotted`
  while the catalog already carries the new value, and the next publish
  repairs it.
- T-19 rollback: two publishes, rollback to the first restores the flag on
  the live row as a new version, with a rollback audit row.
- D-4: feature pause/resume bump the version + snapshot + audit; a source
  pause does not.
- D-6: v1 flag save refused (400 path) and writes nothing; non-flag keys
  still save; the card renders disabled toggles + the v2 pointer.
- Panel-only mode (no live catalog): the publish still versions, snapshots
  and audits.
- Red-first verified: 10/10 fail against the pre-fix code (9 fail + 1
  vacuous pass fixed by asserting the ApiError type).
- Three existing `test_admin_panel.py` tests used `maintenance_mode` as
  their save canary; rewritten to non-flag canaries (URL / price) plus the
  round-trip test split into non-flag round-trip + flag refusal.
- Suite registered in `.github/workflows/backend-ci.yml` (106 → 107 suites).

## Verification (local, this worktree)

- New module: 10/10 OK. `test_admin_panel.py`: 114/114 OK.
  `test_launch_phase0.py`: 9/9 OK. `test_engagement.py`: 38/38 OK.
  `test_coins_engine.py`: 91/91 OK. `test_panel_feed.py`: 98/98 OK.
  `test_series_filter.py`: 12/12 OK. `test_download_endpoint.py`: 26/26 OK.
  AdminV2 `test_unit.py`: 46/46 OK. AdminV2 `test_live_real.py`: 67/67 OK.
- `test_redact.py`: FAILS locally under load (1F+3E, then 1F+4E, then 3F+2E
  — all `PublicEdgeHttpTests`/`AdultDenialOnBothPortsTests` 10s HTTP timeouts
  at load 3.3–3.7). The clean base tree (`dca9002c` worktree) ALSO failed
  once (1 error) under the same load and passed once (114/114) — the
  documented load flake (AGENTS.md: re-run when 1-min load < 2.0, never "fix"
  code for it). The failing tests never touch the changed code (`_save_monetization`
  / `_config_card` / services publish path); they hit `viewer.py` routes
  through local servers. Verdict: flake, not a regression — the branch CI
  run on GitHub (separate runner) is the real gate for this suite.

## Not in this slice (per the design)

Phase 2 (serve missing keys + new flag columns, incl. the catalog
`free_quality_cap` write path and the pause → served-flag-OFF wiring in
`viewer.py`), Phase 3 (app wiring, F-A/F-B), Phase 4 (full matrix), Phase 5
(launch — the owner's panel action). No live deploy from this slice: the gate
deploy waits for the post-autocrawl window.
