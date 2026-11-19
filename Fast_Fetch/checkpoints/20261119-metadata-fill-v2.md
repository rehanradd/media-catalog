# Metadata backfill v2 — batched streaming + collision disambiguation [METADATA-FILL-V2]

2026-11-19 ~16:30 PKT. Branch `muse/metadata-fill-v2-20261119` from main `b13016a8`.
Files: `Fast_Fetch/metadata_backfill.py` (+687/−48), `Fast_Fetch/test_metadata_backfill.py`
(+358, 41 → 63 tests).

## Why

Run 1 (started 00:17 PKT 19 Nov, PID 32350 on the Oracle VM) scans everything and
writes ONE staging SQL file only at the end — after 15+ hours still no output, no
visible progress. User order (15:42–15:47 PKT 19 Nov, in MEMORY.md): fill LIVE,
movie by movie — as data is found, write it in batches of 100 or 1,000; NEVER scan
the whole database first and write everything only at the end. Same-title-same-year
collisions (Marco 2022 Indian vs Hollywood): disambiguate by site language/industry
first, then cast; if still tied, fill NOTHING and queue for review — blank beats wrong.

## What changed

1. **Batched streaming output** — new `BatchWriter`: every 100 filled posts (flag
   `--batch-size`, default 100) appends that batch to the staging SQL and rewrites a
   JSON progress file atomically (temp + rename), fsync per batch, one progress line
   per batch (`[·] batch: N posts (M fields) in Ts`). A crash loses at most one batch.
   A file without a closing `COMMIT;` is an interrupted run — resume it, never apply it.
2. **Resume** — `--resume` reads the existing staging SQL (provenance comments parsed
   back into post_id → {field: source}) + progress JSON (started_at, earlier review
   list) and skips already-filled posts. `Plan.seed()` / `seeded` dict: seeded fields
   are final (no pass re-fills them) and excluded from this run's counts; `Plan.dirty`
   tracks posts with new fills since the last flush. `--progress` flag (default
   `<out>.progress.json`). Dry-run still writes nothing.
3. **Collision disambiguation (the Marco fix)** — new `choose_candidate(query,
   candidates)`: title+year guard first; 2+ matches → (a) language/country score
   (language +2, country +1; unique max wins, shared max narrows); (b) cast/director
   overlap with the post's known cast/director (unique max wins; TMDb enriches tied
   candidates via cached `_detail`, so search results' missing cast is fetched once);
   (c) still tied → `(None, ambiguous=True)` + review-list entry, NOTHING filled.
   ID lookups (imdb_id/tmdb_id) bypass disambiguation entirely. Single-payload sources
   (OMDb, TVMaze, MDBList) get a language veto: fires only when the post affirmatively
   claims a specific non-English language (or non-USA country) and the payload is known
   and different; the post's bare 'English'/'USA' never vetoes (pre-migration defaults).
   TMDb, Kitsu, Jikan searches now collect-then-choose instead of first-match. Query
   dict gains post_id/language/country/industry/cast/director (from `_representatives`,
   reading row columns only if present); `language`/`country`/`industry` added to
   `ROW_COLUMNS` (filtered by `post_columns`, so DBs without them still work).
   Ambiguities land in `stats["ambiguous"]`, the report's `ambiguous_review`, and the
   progress file's `ambiguous` list.
4. **Kept**: read-only input DB, staging SQL + JSON only, `--apply-to` backup-API
   guard, fill-only-empty, skip `is_18_plus=1`, never invent data, rate limiting +
   429 backoff, OMDb/MDBList 950-call caps, 30-day cache, four passes in order.
   `write_staging_sql` now shares `_update_line` with the writer — byte-identical
   output (the existing test pins the exact UPDATE strings).

## Verification (local, offline)

- `test_metadata_backfill.py`: **63/63 OK** (41 original + 22 new:
  LanguageDisambiguationTests 8, TmdbDisambiguationTests 4, OmdbLanguageVetoTests 3,
  BatchWriterTests 5, ReportTests 1, ResumeIntegrationTests 1).
- `test_docs_invariants.py`: **22/22 OK**.
- CLI e2e on a temp 250-post fixture (125 works × 2 siblings, `--sources fanart`,
  `--batch-size 100`, no network): batch line at 100 posts (600 fields), finish at
  125 posts (750 fields), SQL ends `COMMIT;`, progress complete. `--resume` second
  run: 0 new UPDATEs, resumed_posts 125, posts_updated 0. Simulated kill (file
  truncated to 100 UPDATEs, no COMMIT;): resume re-filled exactly the 25 lost posts,
  125 total, no duplicates.

## Not done / known gaps

- NOT merged — parent merges when Backend CI is green on the tip. No live deploy,
  no Oracle VM writes, run-1 process (PID 32350) untouched.
- Live fill on detail open (the user-agreed better design) is NOT built here — this
  is the batched background fill; the detail-open hook is a follow-up.
- `_parse_resume_sql` trusts the provenance comment format; a hand-edited staging
  file with a mangled comment silently drops that post's resume record (it gets
  re-filled, not lost — first-writer-wins still holds).
- `choose_candidate`'s enrich step fetches a detail per tied candidate; a search
  returning many same-title-same-year rows costs one detail call each before refusing.
- The `language`/`country`/`industry` columns on live posts still carry migration
  defaults ('English'/'USA'/'Hollywood') for rows predating the migration — the veto
  and the language step treat bare 'English' as no evidence, but a wrongly-defaulted
  non-English film could still lose its tiebreak to the Hollywood candidate until
  its site metadata is corrected.
