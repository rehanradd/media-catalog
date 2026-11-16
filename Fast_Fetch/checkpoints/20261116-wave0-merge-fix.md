# 2026-11-16 — Wave 0 merge fix (duration / director / release_date / raw_meta)

## What was wrong

`merge_catalogs.py` never picked `duration`, `director` or `release_date`
and never carried `raw_meta` (zero references in the file) — and the live
`posts` table had none of the four columns at all, so even the shared save
path could not have stored them. Staging metadata in those columns could
never reach the live catalog. That alone explains the 99.99% runtime gap
while 8 of 10 Group C importers capture a duration at staging.

## Fix (3 files, +258/−2; branch `wave0-merge-fix-20261116`)

- `Fast_Fetch/fast_fetch.py`
  - `_init_db`: `duration TEXT, director TEXT, release_date TEXT,
    raw_meta TEXT` added to the posts CREATE TABLE **and** to the `p_cols`
    migration dict, so a fresh DB gets them at creation and an existing
    DB (the live catalog) gets them via ALTER TABLE.
  - `_save_scraped_data`: INSERT + UPDATE both write the four. UPDATE
    uses incoming-non-empty-wins (`COALESCE(NULLIF(?, ''), col)`, the
    same rule as `cast_names`): a re-merge carrying no duration never
    blanks one an earlier page stored.
- `Fast_Fetch/merge_catalogs.py`
  - `_collect_staging_post`: picks the four (`duration` first, `runtime`
    as the fallback spelling — the shared parser/Cinemeta spell it
    `runtime`, Group C staging spells it `duration`).
  - `_collect_unified_post`: same four, with the file's own
    `in post.keys()` guard for old unified sources.
- `Fast_Fetch/test_merge_catalogs.py`: new class `Wave0MergeMetadataTests`
  (6 tests): staging survival, `runtime` alias, re-merge never blanks,
  re-merge replaces, unified source, and the `init_db` ALTER migration
  on a legacy-schema DB.

## Decisions (deliberate, record them)

- **Stored verbatim as TEXT, no normalisation.** Sites spell a runtime
  "2h 15m", "PT2H8M", "7200", "01:18:02" and a date "2025-01-31" or
  "31 January 2020"; inventing a normaliser is separate work, and
  guessing wrong ("X min per ep" is per-episode, not a film runtime)
  is worse than storing the page's own words.
- **Not published to any API view in this slice.** Public paths project
  through `redact.VIEWS` allowlists (`post_detail` is a fixed frozenset
  without these keys; `dict(row)` happens only in `full`/operator mode),
  and the v2 projection reads named columns only — verified by reading
  `viewer.py`, `redact.py` and `catalog_v2.py`. So `raw_meta` (which can
  hold source URLs / loc) stays stored-only and cannot leak to the
  public edge. Publishing duration/director/release_date is a follow-up.
- **Projection untouched.** `catalog_v2._derive_work_runtimes` still
  derives `runtime_minutes` from release labels (`120min`); wiring it
  to prefer `posts.duration` needs the duration normaliser above, so it
  waits with it.

## Measured (not claimed)

- New tests failed 5/5 pre-fix (`no such column: duration`), pass 6/6
  post-fix; full suite 18/18 (12 existing + 6 new).
- Neighbours green locally: test_database.py 236 (skipped=1),
  test_merge_fleet.py 14, test_catalog_v2.py 109, test_redact.py 114.
- 50-row Group C-shaped synthetic merge (`/tmp/wave0-measure.py`,
  throwaway): 50/50 merged; duration 40/40, director 35/35,
  release_date 30/30, raw_meta 45/45 non-NULL in the target — 100%
  recovery on every field. The audit's ~21k staged posts is the same
  shape at real scale; the next real merge is what proves it on live data.
- `pre-push-checks.py` passed on the 3 staged files.

## Follow-ups (not this slice)

1. Publish duration/director/release_date in `redact.VIEWS`
   (`post_detail`, and the v2 work view) once the app is ready to show
   them; keep `raw_meta` out of every public view.
2. Duration normaliser (all site spellings → minutes) + wire
   `catalog_v2._derive_work_runtimes` to prefer `posts.duration`.
3. The next real merge re-verifies the recovery on live staging data
   (counts of non-NULL duration/director/release_date before/after).

## Merge-order note

The series-filter branch (`series-filter-fix-20261116`, unmerged at the
time of writing) also bumped this file's count line 157 → 158 in its own
worktree. Whoever merges second must re-count with
`ls Fast_Fetch/checkpoints/*.md | grep -v README | wc -l` and fix the
header — the two branches each add one dated file, so after both land
the count is 159 (157 dated + 2 undated).
