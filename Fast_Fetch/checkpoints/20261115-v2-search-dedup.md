# 2026-11-15 — v2 search-time work dedup (Session 152)

## What

`catalog_api.search()` now returns one row per film on the page. The same
film filed by several sites was up to 8 rows for one film (Salaar: 8 works
in `cat_works`, v2 search returned 7). Measured 11:11 PKT (read-only live
catalog): movies-only 159,472 works carry 17.5% extra works by exact
normalized title (27,971) / 19.5% by base title (31,021).

## How

- New `_dedupe_search_items(items)` in `catalog_api.py`, wired into
  `search()` after the mood/fuzzy fallback resolution and before the
  `fields` filter (which strips the prints/year/type the grouping reads).
  `count` becomes the deduped page size; `total_estimate` still counts
  works (v1's rule — the count counts works, the page shows films), so a
  deduped page may be shorter than `limit`. Cross-page repeats are out of
  scope, same as v1.
- The grouping is `title_clean.group_work_rows` — the SAME grouping v1's
  `_dedupe_search_page` uses, so v1 and v2 agree on what "the same film"
  means. `catalog_api` gains a module-level `import title_clean`
  (stdlib-only, no repo imports — no cycle). Scope is search ONLY:
  `browse()` is untouched (the "Most versions" rail ranks by version
  count).
- A shape surprise returns the page unchanged — dedup must never drop a
  row it cannot read.

## The remake fix (found by the tests, not the design)

`group_work_rows` promised "a remake twenty years later keeps its own
group" but only checked years on the subset merge between DIFFERENT
cores. Identical titles ("Dune" 1984 / 2021 / 2024) land in the SAME core
bucket and folded into one group — the user could never pick the 1984
film. Fixed in `title_clean.py`: each core bucket is split by year first
(union-find, years link transitively within ±1; a row with no year joins
any cluster), then the subset merge runs over the split entries. This
changes v1 behaviour too, in the direction its own docstring already
promised. New suite `test_title_clean.py` (6 tests) pins the contract;
registered in backend-ci.yml (suite count 102 → 103).

## Episode posts are NOT a dedup case

Two episode posts of one series mint ONE work_key at projection time
(`normalize_title` strips episode markers), so search already returns one
work — verified by the test
`test_episode_posts_are_one_work_before_search_sees_them`. The dedup
never sees two episode rows.

## Tests

- `test_catalog_api` 142 OK (+4 new in `SearchDedupTests`: split film
  searches as one row with the most-printed representative,
  `total_estimate` still 3; remake pair keeps 3 rows; episode posts are
  one work; the helper is pure and fail-open).
- `test_title_clean` 6 OK (new).
- Safety suites all OK: test_redact, test_serve_verify,
  test_search_forgiving, test_viewer_search_forgiving, test_api_v2,
  test_home_rails, test_catalog_v2, test_docs_invariants.

## Verification still owed (after the gate deploy)

Live, in-process, read-only: `search("salaar")` returns 1 film (was 7);
`search("love")` page has no repeated film; a remake pair still shows 2.
No live press test needed (read path only).

## Out of scope

Mint-time re-key (option b) — a re-key migration in the projection.
Bigger, riskier, changes favorites/progress/CW identity. Only after this
ships and the remaining split is re-measured. No live fold without the
owner's verbatim go-ahead.
