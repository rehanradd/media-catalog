# 2026-11-16 — Series filter fix (P0 HTTP alias + P1 reclassification)

## P0 — `content_type=` ignored at the HTTP layer (FIXED, tested)

Live symptom (self-check 13:31 PKT 16 Nov): `GET /api/v1/posts?content_type=series`
returned total 208,176 — every unflagged post. Root cause: the handler in
`viewer.py` read only the `type` query param and never looked at
`content_type`, so the param died between the URL and the call. The function
layer (`api_v1_get_posts`, `_facet_filter_clauses`, `catalog_api.browse`)
filters correctly, and the function-level tests in `test_database.py` call it
directly — that is why the drop was invisible. The app sends `type=` today
(`RaddStreamApi.getPosts`, `browseWorks`), so the app's own filter worked;
only the `content_type=` spelling was dropped.

Fix (alias, not rename — `content_type` wins when both are sent, `type` keeps
working): `viewer.py` `/api/v1/posts`, `/api/v1/works`, `/api/v1/trending`
(identical pattern, same defect class) and `api_v2.py::listing_kwargs`
(which read `type`/`work_type` only). The trending cache path was verified
clean: it keys on `content_type` and builds from the same `where_sql`.

Tests: new `Fast_Fetch/test_series_filter.py` (12 tests, HTTP-level through
`viewer.make_server` + `listing_kwargs` unit tests). Reproduced first: 8 of
12 failed pre-fix (every `content_type` case; the 4 `type=`/unfiltered
controls passed). Post-fix 12/12. Registered in `backend-ci.yml` after
`test_database.py` (now 105 suites).

Neighbours green locally: test_database.py 236 (skipped=1), test_catalog_api.py
151, test_api_v2.py 83, test_redact.py 114, test_docs_invariants.py OK,
test_ci_manifest.py OK after commit (it failed pre-commit only because the new
module was untracked in HEAD — its own enforcement, not a code problem).
`pre-push-checks.py` passed on the 5 staged files.

## P1 — series filed as movies (migration SQL, DRY-RUN ONLY, not applied)

Root cause: classification is per-row, per-importer, and several importers
never test for season markers — `save_scraped_data` defaults a missing
`content_type` to "movie", and `merge_catalogs._collect_staging_post`
defaults a missing staging `media_type`/`content_type` to "movie". Same title
filed both ways: post 14691 "…S04E[01-03]…" is a movie, post 82844 "…S03
Complete" is a series.

Migration: `Fast_Fetch/migrations/20261116-series-reclass.sql` (forward-only,
idempotent, one transaction). Conservative positive-evidence rule: S-token
(S01..S99, S1..S9) as a standalone token, "Season N", populated `seasons`
column, a release with an EPISODE value, or an "Ep N" marker. Never genre,
never year, never the bare words "season"/"episode" (sampled and rejected:
"Season of the Witch" is a movie; "Episode" fires on Star Wars films and on
"…Episode N" films; the S-token needs a word boundary both sides — loose
pattern matched "Vous Play S3XUS").

NO reverse update: absence of a marker is not movie evidence — 5,691
`series` rows carry no marker but 10/12 sampled are genuine series; the only
positive movie signal tried ("movie" in the title) is 90% noise from the
"Rogmovies" site suffix. Reverse = 0 rows.

Dry-run (2026-11-16, `.backup` copy of the live DB, transaction + rollback,
copy deleted after): **5,305 rows movie→series (2.35% of posts, under the 5%
threshold); CHANGED == PREDICATE_MATCHES; known ids 14691/45528/48275 flip,
126011/88029 do not; 10 random samples all genuine series; rollback restored
counts exactly.** Two file bugs caught by the dry-run and fixed: a missing ")"
in the F4 EXISTS, and LIKE used where GLOB was needed for "[0-9]"
(LIKE has no character classes — F2 matched nothing until fixed). F4 was
tightened after sampling: season-only releases are ~67% movies (Star Wars
Episodes III/IV carry a spurious season="1", likely from a "[1GB]" parse),
while episode-set rows sampled 11/12 genuine series — so F4 tests `episode`
only, dropping 109 net rows.

NOT done (parent's call): applying the migration to live (gate only),
per-importer season detection (rows filed after this migration still depend
on each importer), the `/api/v1/trending` route is included in the P0 fix
same as posts/works.
