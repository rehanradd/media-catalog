# Dev-cleanup server lane — 2026-11-18

Branch `muse/dev-cleanup-server-20261118`, base main `9f4eb547`.
Fixes the server-side root causes from `dev-cleanup-db-audit-20261118.md`
(C1–C7, C13, C15). Stop at Backend CI green — do NOT merge (merge + gate
deploy is the main chat's job).

## What changed

- `catalog_api.py` — one shared placeholder rule (`is_placeholder_value`,
  `clean_text_value`, `clean_text_list`) + `curate_genres` (denylist:
  platforms, status words, dub markers; "Animes" → "Anime"). Applied in
  the browse item, the detail payload, and the release view's audio /
  subtitle languages. `print_label()` never emits "Unknown print": an
  empty core returns "" (app falls back to "Episode N") or the stored
  label when it is a clean episode identifier ("Episode 1", "S02E03").
  Wire note: the public edge's scrub coerces a None country/industry to
  "" (they sit in `redact.LIST_KEYS`) — "" is the established wire
  encoding for "missing" on these fields, which the app already drops.
  The word "Unknown" is what must never ship.
- `viewer.py` — `api_v1_get_facets` drops placeholder language tokens
  (C6) and is now served from a 15-minute stale-while-revalidate cache
  (C15: facets 503'd at 13.17 s under sweep load while posts answered in
  2.88 s). Same trade as the trending order: fresh served as-is, expired
  served stale + background rebuild on its own connection with no
  progress deadline, cold start builds synchronously. Fingerprint-scoped
  to one DB file. Deep copies out so callers can't mutate the cache.
- `merge_catalogs.py` + `catalog_v2.py` — the merge / projection no
  longer write the literal "Unknown" for a missing language / country /
  industry; they write NULL. (Root cause of the stored 62–103k rows.)
- `fast_fetch.py` + `backfill_year_from_title.py` — new `validate_year`
  (1900..current year, else first safe year from the raw title, else
  NULL) applied in `_normalize_scraped_data`, the choke point every
  importer and the merge funnel through. C7: "(2020) (2067)" → 2020.
- `backfill_dev_cleanup_20261118.sql` — gate-time only, year fixes only
  (year > 2026 → NULL; the single year-1900 "Paris Police 1900" row).
  Placeholders / junk genres are INTENTIONALLY left in stored rows
  (zero data loss — redaction is a serialization decision at the edge;
  serve-time fixes cover every client; the merge now writes NULL).
- Tests: 17 new (helpers, print_label fallback, view emission, facets
  placeholder + cache, validate_year, merge NULLs, save-path year
  clamp). Two Session 115 sort tests now seed junk years out-of-band
  (ingest refuses 2099/0, as the zero-to-null backfill already does for
  0) — they pin the sort's handling of the legacy rows still live.

## Local verification (2026-11-18, this worktree)

10 modules, 866 tests, all green: test_catalog_api 162, test_home_rails
64, test_backfill_year_from_title 18, test_merge_catalogs 19,
test_database 239 (1 pre-existing skip), test_redact 116,
test_catalog_v2 109, test_detail_projection 4, test_works_api 52,
test_api_v2 83. `pre-push-checks.py` passed.

## Notes for the main chat

- The background label-sample probe (live `cat_releases.label` GROUP BY)
  died on the ~280 s SSH ceiling; not needed — the print_label fallback
  only trusts clean episode identifiers, so unmeasured label shapes
  cannot leak. Batch2/Batch4 scan outputs were not re-read here; the
  summary's batch4 numbers stand (unknown-print count undercounts — it
  checked NULL/empty only, missing the literal strings).
- C11 (26,718 series / 195,348 seasons with zero episodes) is a
  projection/B2 data gap, out of scope here. "Dual"/"Multi"/"Original"
  language values and stale poster hosts (301s) are follow-ups, not
  fixed here.
- After merge, the remap gate is still the next step (stop sweep, gate
  backup, remap 27 host pairs, relaunch sweep).
