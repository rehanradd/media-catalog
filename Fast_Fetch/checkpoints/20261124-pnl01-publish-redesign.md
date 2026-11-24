# PNL-01 publish redesign (narrowed §0A) — 2026-11-24

Branch `muse/phase4-pnl01-publish-redesign-20261124`, base main `2849f5a6`
(FIX2 tip). REBASED onto current main `08a808e6` (bank-defects landed
11:15 PKT 24 Nov) BEFORE the gate — Backend CI on the first tip
(`ea55722e`) failed on exactly one step, `test_ci_manifest.py`
(`test_manifest_is_never_a_subset_of_trunks`: the stale-base workflow
lacked bank-defects' `test_bank_defects.py` step; the panel job was
green). The rebase is a true merged tree via the Git Data API: the
only 3-way merges are backend-ci.yml (bank-defects step kept +
redesign step kept, panel count 312 = 278 trunk + 33 redesign + 1
publish_flow), CONTINUITY.md (count 152 = 149 dated + 3 undated —
both lanes added one file), checkpoints/README.md (both 2026-11-24
entries kept, PNL-01 first). All other 15 files are taken verbatim
from the first tip (no overlap with bank-defects' fast_fetch.py /
serve_verify.py / test_bank_defects.py). NOT merged — parent/gate owns
the landing; no live tree, DB, service, or panel touched.

## Scope change (parent-verified 11:30 PKT 24 Nov)

The first review's broader shape (unified 28-field publish, intent
journal + reconciler, blanket fail-closed, pause-flag rewriting) is
SUPERSEDED by the design doc's §0A amendment (second-opinion report
`~/workspace/phase4/pnl01-second-opinion-20261124.md`). Three BLOCKERs
overturned: feature flags ARE covered (in MONETIZATION_FIELDS); rollback
DOES write live at a new higher version; the watcher NEVER compares
versions (zero `config_version` matches in App Kotlin; `refreshOnce()`
overwrites unconditionally; poll is 5 min, not 15). Build scope is ONLY
N1–N4 + process guards + six pinning tests.

## What was built

- N1 field→route map: `DRAFT_REMOTE_CONFIG_FIELDS` (12) is the single
  canonical allow-list — 3 live-row fields (`maintenance_mode`,
  `apk_update_url`, `free_quality_cap`, each in both v2
  `MONETIZATION_FIELDS` and `REMOTE_CONFIG_FIELDS`) + 9 panel-feed fields
  (`V2_ONLY_SNAPSHOT_FIELDS`). The APK trio + `feature_link_shorteners`
  are v1-owned: not draftable, not in `REMOTE_CONFIG_FIELDS`, still
  written by v1 `/admin` and read by the app. Live row: 43 = 24 + 4 + 15.
  A generic publish writes only the 3 draft live-row fields — it does
  NOT clobber a v1 edit to any other field (pinned by the reworked
  get_live test); a monetization publish writes all 24 from the draft row.
- N2: version = max(live, panel)+1 computed INSIDE `WRITE_LOCK` from
  fresh reads, held through the panel commit. Verified in the build that
  no second process publishes (only `save_remote_config`, via the single
  `_live_write` funnel; v1 writes the live row directly but never
  publishes). Seed version 0 (first publish = v1, was v2 at main).
- N3: fixture branch (no live store configured) still publishes,
  labelled `"live": "not_configured"`; configured-but-unavailable
  (`has_catalog` False) refuses 503 `live_unavailable`, bumps nothing.
  `run.py` aborts only when `RADD_CATALOG_DB` is unset; a set-but-missing
  file starts the panel.
- N4: rollback writes live BEFORE the panel commit; a live-write failure
  aborts before any panel mutation and is recorded (`rollback.refused`).
  Rollback live push = `MONETIZATION_FIELDS` (incl. the cap).
  `is_paused()` is consulted ONLY to annotate the audit
  (`paused_active`) — never to rewrite flags; pause/resume write NO live
  row (pause→live is PNL-11's).
- Process guards kept: PNL-08 BackupGuard pre-flight at the live-write
  boundary (409; `BackupGuard=None` no-op until PNL-08 lands); startup
  migration adds all 43 live-row columns + `config_version`
  (`save_remote_config` raises `schema_drift`, never ALTERs mid-request);
  startup SHA-256 drift guard for the two panel code copies in `run.py`;
  audit field diff + snapshot SHA-256; F8 live-row diff in `get_live`.
- F6 (decision owed, implemented the safer option): v1 `_save_monetization`
  reads the live row's `config_version` fresh and stamps +1 in the SAME
  UPDATE, no explicit COMMIT (the route's `_session` owns durability).
  v1 editing stays enabled. Residual: a v1 edit has no history row, so
  the next v2 publish allocates max()+1 over it; `get_live` surfaces the
  edit in `changed_keys` until then.

## Dropped (superseded, removed — not silently shipped)

`_publish_all`, `_journal_intent` / publish_intent table + migration v6
(chain ends at 5 again), `reconcile_publish_intents`, `_unified_live_fields`,
`_paused_feature_overlay` / pause-flag rewriting, blanket fail-closed D3,
`fixture_mode` / `--fixture`.

## Tests (local, TMPDIR=~/workspace/phase4/tmp-scratch)

- New `AdminV2/tests/test_publish_redesign.py`: 33 tests, the six
  pinning tests (a)–(f) + drift/backup/audit/staleness/get_live — 33/33.
- `test_publish_flow.py` 16/16, `test_admin_panel.py` 115/115,
  `test_launch_phase1.py` 10/10, `test_unit.py` 46/46,
  `test_a10a16.py` 16/16, `test_api_v2.py` 97/97, `test_live_real.py`
  67/67. Full rest-suites sweep green (see the branch report).
- Backend CI count updated: AdminV2 suites 315 → 312 tests (redesign
  suite 36 → 33 under the §0A rewrite).
- Repair-contract change (from main): after a snapshot failure the next
  publish mints N+2 — the failed version stays a gap in history, values
  re-published. Main's close-the-gap is intentionally NOT preserved.
- Second opinion on the narrowed diff: NOT obtained — TokenFactory 502
  (egress proxy down, twice) + AnyAPI 402 (payment required). Per the
  brief, the parent-verified §0A is the governing review.

## Gate-time notes (driver's job)

Fresh Backend + Android CI on the rebased tip (the rebase already
happened — see the header); sync root-level `admin_v2/` + `run.py` +
`tests/` alongside `AdminV2/`; full gate (backup integrity + counts
BEFORE ff, HEAD asserted, live tests, sweep relaunched).
