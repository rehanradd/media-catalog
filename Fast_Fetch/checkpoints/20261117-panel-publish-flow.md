# 2026-11-17 — panel Live publish flow covers flags `[PANEL-PUBLISH-FLOW]`

Branch `muse/panel-publish-fix-20261117` from main `94a55b1f`. Owner order: 12h plan + full delegation 06:49 PKT 17 Nov. NOT merged — branch + backend CI only.

## Diagnosis (repro `/tmp/repro-publish-flow.py`, all 6 confirmed pre-fix on local copies)

- R1: Save draft on an old catalog (no `apk_update_url` column) + a real `apk_update_url` → 500 generic "Something went wrong" AFTER the draft row was already committed (half-saved).
- R2: JSON `brand_primary=""` → 400 "Brand colour must be a hex colour" (`""` means "clear it"); form bad region → 400 raw JSON.
- R3: Drafting `maintenance_mode=1` flipped the LIVE catalog row at once, panel version 1→1, snapshot stale — publish bypass proven.
- R4: Publish bumped panel version 1→2 but wrote NOTHING to the live catalog (`apk_update_url` stayed '').
- R5: Monetization flag save left the served row with NO `config_version` column — unversioned.
- R6: Failing form POST returned raw JSON 400, never a redirect with the real error.

## Fix

- `AdminV2/admin_v2/live_stores.py`: `save_remote_config(fields, config_version=None)` — generalized additive column ensure for EVERY field being written (was `coins_enabled` only), correct INTEGER/TEXT DDL via new `_REMOTE_CONFIG_INT_FIELDS`; stamps `config_version` in the same UPDATE (separate kwarg, never postable via fields); `read_remote_config` includes `config_version` when the column exists.
- `AdminV2/admin_v2/services.py`: `draft_remote_config` — `""` brand/region → NULL (validated only when non-None); **removed the live-catalog write at draft time** (draft = panel row only). `_do_publish` — pushes the draft's catalog-carried fields (`maintenance_mode`, `apk_update_url`, None-coerced) to the live catalog BEFORE the version bump (catalog-first §3.1), stamps the new version, translates non-ApiError failures to ApiError("internal", real message, 500) so scheduled fires mark blocked instead of crashing. `_publish_staged_monetization` stamps `expected_version` (row version + 1). `rollback_remote_config` stamps the new version. `get_live` adds `live_catalog_version` (None when unwired/un-stamped).
- `AdminV2/admin_v2/server.py`: `_wrap` catches ApiError on form writes → 303 back to `_next` with `?error=<urlencoded message>` (JSON callers unchanged). `_page_live` + `_page_monetization` read `query.get("error")` into `layout(error=...)` (dashboard pattern). Live page shows "· app serving vN" when the catalog version is known; copy updated to "App update, brand, region and maintenance mode."
- `Fast_Fetch/admin_panel.py`: `remote_config()` includes `"config_version"` via guarded SELECT (0 on rows predating stamping). Deliberately NOT added to v1 MONETIZATION_FIELDS (v1 card must not render it editable; the field spec drives `_ensure_monetization`). Explicit projection, not a provenance key — complies with Fast_Fetch AGENTS.md §6.10.

## Two pre-existing failures found while verifying (both fail identically on pristine 94a55b1f, proven in a scratch worktree)

- Monetization page 500: commit `b1eead3b` (coins removal) added `flag_field("coins_enabled", "Coins")` to the page tuple without adding the 6th `%s` to the Flags div — fixed (one placeholder). The live panel does not 500 because it runs older workspace code, not git main.
- Orphan audit: `POST /admin/api/v2/monetization/publish` (added by `1d4804ce`, Launch Phase 1) had no pairing-matrix control — added `publish_monetization` to `admin_v2/pairing.py` (matrix is only consumed by the audit).

## Tests

- New `AdminV2/tests/test_publish_flow.py`, 15 tests (draft `""`→NULL; draft doesn't touch live catalog; draft on old catalog can't half-save; publish pushes live fields + stamps version; publish without live catalog works; publish catalog failure bumps nothing + real message; monetization save stamps version; publish-monetization repair path; save_remote_config adds missing columns; rollback stamps version; form POST error → 303?error=; form POST success → clean 303; Live page renders error + serving version; JSON caller still gets JSON error).
- `Fast_Fetch/test_admin_panel.py` +1 test: public remote config carries `config_version` (0 default, 7 stamped).
- Local: AdminV2 full suite **258/258 OK** (one earlier run showed 164 errors in 46 s — flaky under load, two subsequent runs clean, one 0-fail grep + one full OK); `test_admin_panel.py` 115/115 OK; `test_redact` 114/114 OK. Backend CI is the real gate.
