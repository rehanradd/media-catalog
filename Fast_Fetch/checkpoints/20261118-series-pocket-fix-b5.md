# 2026-11-18 — B5 series-pocket fix `[SERIES-POCKET-B5]`

Branch `muse/series-pocket-fix-20261118` from `3e7e2cf8`. Worktree
`~/workspace/series-pocket-fix-20261118`. Diagnosis: `~/workspace/series-pocket-diagnosis-20261118.md`.

## The pocket, re-measured live (mode=ro, 2026-11-18 ~05:10 PKT)

- Servable series gauge: `content_type='series' AND is_18_plus=0` = **43,302**
  (reproduces the 17 Nov figure exactly; series family total is 49,371).
- Loose pocket (17 Nov definition): `clean_title LIKE '%season%' OR
  clean_title LIKE '%web series%'` over movie-classed rows = **243**,
  reproduced exactly (124 season + 119 web-series, overlap 0 — "Seaosn"
  is not "Season" to LIKE).
- **The 243 is NOT all misclassified.** ~118 of those rows are real movies
  whose titles merely contain the word: "Open Season", "Season of the
  Witch", "Killing Season", "Wedding Season", "Happiest Season", "Love
  Seasons" (125 bare-season rows measured). A bare "season" word must never
  be a marker — flipping the loose 243 would misfile real movies as series,
  the same error in reverse (would move 43,302 -> 43,541).
- **Strict, evidence-backed pocket: 150 rows (143 servable).** Rule
  (`import_filmyzilla.detect_series_markers`): season + number ("Season 3",
  "Season - 3", "season1"), the "Seaosn" typo (substring — "HeistSeaosn"
  glues it), "Web Series" suffix, "Complete Season" (+ "COMPLETE MERGED /
  FINAL SEASON", "COMPLET SEASON" typo), S-token ("S01"/"S2"), SxxExx
  ("S01E01"). Every one of the 150 was inspected by title+site; all carry
  real series evidence. By site: filmyzilla 128, themoviebox 12, 7starhd 5,
  moviesmela 2, mp4moviez 1, ssrmovies 1, 9xmovies 1. 18 carry a season
  number (seasons populated); 132 are base "Web Series" rows with none.
- The task's "verify the pocket lands on 243" does NOT hold for the flip:
  243 is the loose count, 150 is the flippable count. Series count moves
  43,302 -> **43,445** (+143), not 43,545. This is reported, not hidden —
  the parent/merger decides the gate with the numbers in front of them.

## Code (importer/classification only)

- `Fast_Fetch/import_filmyzilla.py`: new `detect_series_markers(raw, clean)
  -> (is_series, season_number)`; `parse_filmyzilla_movie_page` sets
  `media_type` ("series" when detected) + `seasons` ("Season N") +
  `season_number`; both release paths carry `season_number` (was None);
  staging `posts` gains a `seasons TEXT` column (CREATE + auto-migration,
  same pattern as cast_names/director/languages) and `save_post_to_staging_db`
  persists it (`COALESCE(excluded.seasons, posts.seasons)` on re-scrape).
  The merge already carries `seasons` (`merge_catalogs._collect_staging_post`
  `_pick(post, "seasons")` -> `save_scraped_data` writes it) — no merge change.
- `Fast_Fetch/backfill_series_pocket.py` (new): dry-run by default, `--apply`
  to write. Uses the importer's detector (single source of truth, no
  regex drift). Flips only rows still classed 'movie' (idempotent),
  fills `seasons` only where NULL, prints loose pocket / strict flips /
  by-site / servable series before+after / loose-minus-strict sample.
  `--site` scopes it. Verified on a side copy (see below).
- `Fast_Fetch/test_filmyzilla_import.py`: new `SeriesPocketB5Tests`, 10 tests
  — Seaosn Money Heist (series, Season 3), Web Series without number
  (series, seasons None), plain movie stays movie, bare-"season"-word guard
  (Season of the Witch / Open Season / Killing Season stay movies),
  dash-season ("Panchayat Season - 3"), extract carries season_number onto
  releases, staging save persists media_type+seasons, staging migration
  adds the column, backfill plan+apply on a fixture (5 exemplars flip,
  witch/open/plain untouched, second run flips 0), bare-S-token guard
  ("S4: The Bob Lazar Story" stays a movie; "S01" still claims a season).

## Verification

- Local: `test_filmyzilla_import.py` 20/20 OK (10 pre-existing + 10 new);
  `test_merge_catalogs.py` 18/18 OK; `test_docs_invariants.py` 22/22 OK.
- Side copy: `sqlite3 .backup` of the live DB to
  `/home/ubuntu/b5-series-pocket-copy.db` (never plain cp on a WAL DB);
  copy verified exact (pages 2,576,540 = live, posts 225,517 = live,
  series 43,302 = live, loose 243 = live). Backfill dry-run then --apply
  on the copy: **BACKFILL-APPLIED flipped=150, series 43,302 -> 43,445
  (+143)**. Re-run: 0 flips (idempotent), loose pocket now 105 (the
  bare-season movies that correctly stay movies). Exemplars verified:
  101820 series/Season 3, 101821 series/Season 4, 101822 series/Season 5,
  101823 series/Season 2, 101828 series/seasons NULL; 222787 (S4 film),
  32053 (Season of the Witch), 886 (Open Season) still movie. Posts count
  unchanged (225,517). Live application is the gate's job, not this lane's
  — no live writes, no service/panel restarts.
- Copy verification CAUGHT one false positive before anything was applied:
  the first dry-run flipped 151 rows — the extra one was live row 222787,
  "S4: The Bob Lazar Story" (themoviebox), where "S4" is the Area 51
  facility the film is named after, not a season. The bare-S-token marker
  (`\bs0?\d{1,2}\b`) was tightened to exactly the S0[0-9] shape the task
  specifies, the S4 case pinned by a test, and the dry-run re-run to confirm
  150 flips / 43,302 -> 43,445 before applying.
- Backend CI on the pushed tip: (filled after push).

## Follow-ups (not this lane)

- Other importers file the same way: 7starhd (5), themoviebox (12),
  moviesmela (2), mp4moviez (1), ssrmovies (1), 9xmovies (1) strict hits
  exist in the catalog — their importers' series detection is a separate
  slice; the backfill already fixes the existing rows.
- Residual: 1 row "Attack on Titan Final Season the Final Chapters"
  (193949) is a real series the rule leaves as movie — "Final Season"
  with no number is also a movie title ("The Final Season", 2007), so the
  rule deliberately does not claim it.
- `test_filmyzilla_import.py` is EXCLUDED from backend-ci.yml (per-site
  parser, drift-guarded exclusion block) — the 9 new tests run locally
  only, per that exclusion's own rationale.
