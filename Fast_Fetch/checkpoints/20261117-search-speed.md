# 2026-11-17 — search speed: residual search cost + the `_ensure_schema` first-request 504 `[SEARCH-SPEED]`

Branch `muse/search-speed-20261117` from main `94a55b1f`. Owner's 12h plan + full delegation (06:49 PKT 17 Nov). NOT merged — the merge queue owns landing; the activation step (below) is a separate operator action.

## What was wrong (measured on the live catalog, 17 Nov)

1. **Cold `_ensure_schema` ~15 s → first request 504.** Two scans ran on every process start: `redact.site_needles()` still ran a live `SELECT DISTINCT source_site FROM cat_work_sites` (5.83 s) even though the projected text snapshot already holds the same vocabulary, and `fast_fetch._init_db` ran the `post_sources` backfill (`INSERT...SELECT` over 225k posts, ~2.8 s warm / ~15 s cold) on every start although both writers already upsert `post_sources` per post.
2. **Search `q=love` 1.26 s live.** Two full LIKE scans (COUNT 0.40 s + page ids ~0.40 s) because the trigram candidate pass truncates at the 900 cap (9,621 gram-matching rows) and returns None. The trigram-subquery shape is 13–20x faster (0.03 s) but drops 65 slug-only rows: the live trigram index covers only `clean_title` + `cast_names`, so the superset claim in `_posts_search_candidate_ids` was FALSE for slug-only (and title-only) rows.

## The fix (3 changes, all guarded — behaviour is byte-identical until the migration runs)

1. `redact.site_needles()` reads the source_site vocabulary from the projected text snapshot first (`_snapshot_text_needle_values`), falling back to the live DISTINCT only when the snapshot is absent/stale. No UNATTRIBUTED filter here — `site_needles` is detection and `vegamovies` is a site we scrape; only `site_text_needles` (rewriting) excludes it.
2. `fast_fetch._ensure_post_sources_backfill()` + marker table `schema_backfill_state (key, value)`, marker `post_sources = "max_posts_id:post_sources_count"`. Skip when the marker matches; incremental backfill (`id > marker`) when max grew; full backfill when counts diverged (preserves `test_init_db_backfills_first_seen_page_for_existing_databases`); marker absent → one-time anti-join proof (~1 s) then skip or full backfill.
3. `fts_posts.TRI_COLUMNS` `("clean_title", "cast_names")` → `("clean_title", "cast_names", "slug", "title")` + new `trigram_covers_search()` shape guard + new `viewer._posts_search_trigram_match()`; `api_v1_get_posts` takes the uncapped subquery `p.id IN (SELECT rowid FROM fts_posts_tri WHERE fts_posts_tri MATCH ?)` when the bounded pass truncates AND the live index covers all four LIKE columns. Until the index is rebuilt, the guard is False and the LIKE scan runs exactly as before.

## Rejected by measurement (do not build)

Covering index (plan still `SCAN posts`), window-count merge (1.25 s, slower), startup cache warm-up (scans are CPU-bound), did_you_mean gating (0.04–0.07 s, keep), cursor pagination (API contract change).

## Verification (all on the Oracle VM, real catalog data, scratch DB built by read-only ATTACH + schema copy)

- `site_needles` on the LIVE DB, mode=ro, fresh process: **5.83 s → 0.010 s**, 1,385 needles, `vegamovies` present. Scratch snapshot-dropped fallback still works (0.26 s).
- COUNT `love` on real data: LIKE **0.477 s → subquery 0.049 s**, totals identical **6,220**. `api_v1_get_posts(q=love)` after warm ensure: **0.315 s**, total 6,220 = LIKE; trigram-dropped LIKE fallback also 6,220.
- Old per-start backfill alone (warm, same data): **1.732 s**; new `init_db` first run 0.516 s (proof + marker `225484:420762`), steady run 0.253 s total. `_ensure_schema` fresh process on scratch: **0.981 s** (was ~15 s cold — the 504 cause).
- Trigram rebuild (new 4-col shape, 225,484 rows): 38–51 s, one-time operator migration, not a serve path.

## Tests (local, all green)

`test_fts_posts.py` 39/39 (3 new: uncapped-pass superset incl. a slug-only row, old-shape guard, end-to-end subquery through `api_v1_get_posts` with the limit patched to 2), `test_redact.py` 116/116 (2 new text-snapshot source_site tests), `test_database.py` 237/237 (1 new marker test), `test_posts_query_plan` 8, `test_did_you_mean` 13, `test_viewer_search_forgiving` 20, `test_search_forgiving` 11, `test_fts_search` 9, `test_media_search` 44, `test_migrate_search_indexes` 20. Total 517. One near-miss caught by the test-count diff: an edit briefly absorbed the curly-apostrophe test's body into the new test — restored as its own method, diff now exactly +3 tests, no deletions.

## Activation step (owed, operator action, NOT in this branch)

After this branch lands AND goes live, run `migrate_search_indexes.py apply --with-fts-posts --execute` in a quiet window (~40–50 s on live, service stopped per that script's own Step 3 note) to rebuild the trigram index with `slug` + `title`. Only then does the fast path engage. Until then the code is a no-op: same rows, same order, just the same speed as today.

## What this branch does NOT do

No service restart, no live-tree / panel / catalog-DB / backup touch (verification used a read-only ATTACH copy + a scratch DB, both deleted from the VM after). No projection re-run. No VM-clone touch. v2 search COUNT is a separate follow-up.
