# 2026-11-20 — Server data-cleanup slices S1/S2/S3 + genre curation

Branch `muse/server-slices-s1s2s3-20261120` (base main 6199929e). Build only;
do NOT deploy, do NOT apply the backfill to the live DB — main chat merges,
the gate deploys/applies.

## What was already built (verified before writing a line)

The 18 Nov server slice (018719a0) had already built the v2 serve-time
helpers, `print_label` episode fallback, release audio/subtitle cleaning,
the facets placeholder filter, merge "no Unknown default", year clamp
(`validate_year`, wired at `_normalize_scraped_data`) and the facets
15-min cache. Live One Piece work 112505 serves 'Episode 1'–'Episode 8' —
the "Unknown print" leak is GONE live. The real remaining gaps, confirmed
empirically (probe + live API GET):

- v1 `post_brief` served junk genres `["Animes", "COMPLETED", "Hindi Dub",
  "NETFLIX", "Action"]` untouched and `language: "Unknown"` (plain, comma
  `"Hindi, Unknown"` and JSON forms).
- `_pick` in the merge passes staging "Unknown" literals verbatim (merge
  only stopped inventing them as defaults); importers write staging DBs
  directly, so the merge's `save_scraped_data` call is the single choke
  point.
- `print_label` returned "" for a print whose stored label is a release
  title rather than an episode identifier.

## What this lane built

- `redact.py` is the single shared source: public `PLACEHOLDER_VALUES`,
  `is_placeholder_value()`, `GENRE_DENYLIST`, `GENRE_NORMALISATIONS`,
  `clean_text_value()`, `clean_text_list()`, `curate_genres()`,
  `null_placeholders()` (shape-preserving; junk genre tokens NEVER
  deleted at write time — Zero Data Loss). `_category_token` applies the
  genre denylist + "adapted from" drop + "animes"→"Anime", diacritic
  folding for the shape check only. `public_list` non-JSON branch
  comma/semicolon-splits through `_clean_tokens`. `_project_post_derived`
  nulls `language` too when placeholder/empty/`"[]"`.
- `catalog_api.py` vocabulary + helpers replaced by aliases to `redact`'s
  (verified identical behaviour).
- `print_label(release, title=None)`: parts → clean episode identifier →
  scrubbed work title → ""; wired at all 6 call sites. Pinned rule kept:
  no title passed → "" (a scrape fragment never becomes the label).
- `fast_fetch.py` imports `redact`; `_normalize_scraped_data` nulls
  placeholders for `language`/`languages`/`country`/`industry`/`genres`
  + `releases[].languages` (covers importer staging writes and the merge).
- Gate-time backfill artifacts (do NOT apply yet):
  `Fast_Fetch/backfill_slices_s1s2s3.sql` (scalar NULL-ing UPDATEs for
  posts/cat_works language/country/industry + year rule 1900..current
  year; year=1900 NOT blanket-nulled — post 67796 "Paris Police 1900"
  is a setting, needs a human look) and
  `Fast_Fetch/backfill_slices_s1s2s3_genres.py` (list-field cleaning
  via `redact.null_placeholders`, `--dry-run` default, `--apply` at gate
  time; covers posts.language/genres, cat_works.languages/genres,
  releases.languages, cat_releases.audio/subtitle_languages).
  Smoke-tested on the VM against a scratch DB (never live): dry-run
  reports 10 rows, --apply cleans 10, junk "NETFLIX"/"Hindi Dub" KEPT,
  future years 2067/2029 nulled, year 1900 kept, pre-existing NULLs
  untouched. The first smoke run caught a real bug in the SQL's
  verification queries — `TRIM(COALESCE(col, '')) = ''` counts NULL
  rows as remaining placeholders (and the UPDATEs no-op-rewrite every
  NULL row). Fixed to NULL-safe `TRIM(col) = ''`; re-run verifies all
  counts 0 except year-1900 = 1 (human review).

## Tests

13 pass locally (no tempfile): `test_redact.ServeTimeCleanupTests` (5),
`test_catalog_api.PrintLabelFallbackTests` (3 new + 5 existing),
plus the two new tests named below run in their modules. Year rule
already built — `validate_year`, tested by `ValidateYearTests` in
`test_backfill_year_from_title.py`.
- `test_database`: `test_ingest_files_missing_language_country_industry_as_null`
- `test_merge_catalogs`: `test_merge_files_unknown_placeholders_as_null`
  (direct-INSERT staging row with literal "Unknown" values).

## Gate verdict (VM scratch dir, 2026-11-20 ~05:20–05:45 PKT)

Full backend gate (`run-backend-gate.py`, 111 modules) ran on the Oracle
VM because the hatch sandbox blocked writes (see below): **107 PASS**.
The 4 FAIL modules were all scratch-dir artifacts, each proven to pass
in a real repo (hatch worktree, writes briefly recovered):
- test_launch_phase0 / test_launch_phase1: `ModuleNotFoundError` for
  `store` / `admin_v2` — the scratch dir lacked `AdminV2/`; with it
  copied in, both pass (10 tests each, OK).
- test_ci_manifest (4 failures): `git ls-tree HEAD` / `git show
  origin/main:...` — the scratch dir has no `.git`; hatch-locally it
  passes (10 tests, OK). Manual check: no workflow entry points at a
  missing file; the 24 test files not in the workflow are pre-existing
  importer/site tests, none of them mine.
- test_docs_invariants: 9 failures + 6 errors in the scratch dir (no
  README.md / docs/ at the fake root); hatch-locally only ONE failure
  remained — MY bug: CONTINUITY.md still claimed 133 archive files
  after I added the 134th checkpoint file. Fixed the count to 134
  (131 dated + 3 undated); the module now passes (22 tests, OK).
Targeted modules on the VM: test_redact 121 OK (first run had 1 error,
the known load flake; clean re-run OK), test_catalog_api 168 OK,
test_database 240 OK, test_merge_catalogs 20 OK,
test_backfill_year_from_title OK. The new workflow module in ce5dd227
(test_auto_crawl_cron.py) is untouched by this lane and green on main
(Backend CI 44643395432); CI on the pushed branch is the authoritative
re-check.

## BLOCKER (environment, not code) — hatch sandbox write block

From ~05:0x PKT 20 Nov the hatch VM sandbox intermittently returns
`EPERM (Operation not permitted)` on `write(2)` from every child process
(python, git, cp) — `open` succeeds, the write fails, files stay 0 bytes.
The shell's own builtins/redirections still write, and the file tools
still write. It lifted once after a 45 s wait, then returned, then
lifted again ~05:40 (commit + rebase + the 4 local module runs above
all succeeded in that window). Effect while blocked:
`tempfile.gettempdir()` fails (its probe writes), so every test using
`TemporaryDirectory` errors in `setUp`, sqlite3 gets "disk I/O error",
and `git hash-object -w` / `git commit` fail. The 3 tempfile-dependent
tests were therefore executed on the VM instead (see above — all pass).
AGENTS.md now records the block and the GitHub-API ref-update merge
workaround (proven for the autocrawl fix, main 6199929e → ce5dd227);
main has since moved to ce5dd227 and this branch was rebased onto it
(clean, no conflicts, diff identical: 11 files, +845/−120).

## Still to do (main chat / next lane)

1. Push the branch, verify Backend CI green on the new tip (check the
   workflow NAME). The VM gate above is the local verification; CI is
   authoritative (it runs in the real repo, so the 4 scratch-dir
   artifact FAILs cannot recur there).
2. Gate-time: run the backfill SQL + the genres script `--apply` after
   the code lands (backup first), verify the trailing SELECTs are 0.
3. `sweep_public_edge.py --probe` real-data sweep at gate time (API-shape
   change: v1 genres/language + print_label).
4. Open follow-ups: 265 malformed remap rows (unitemized); `quality_label`
   "Unknown" possibility unchecked (projector writes
   `desc.get("quality_label") or ""`, catalog_v2.py:1321).
