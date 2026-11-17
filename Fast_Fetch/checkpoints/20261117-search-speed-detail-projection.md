# 2026-11-17 — search speed: detail column projection [SEARCH-SPEED-COLPROJ]

Branch `muse/search-speed-colproj-20261117` from `156909b2`.

Picked the SMALLER slice of the two search-speed leftovers: (a) column
projection for the detail endpoints, not (b) cursor pagination for the
posts list — (b) changes the public list contract (new params, envelope,
every sort order, client contract), while (a) is contained to five
`SELECT *` sites whose emitted JSON is already fixed by the redact views.

What changed (`Fast_Fetch/viewer.py`):
- New explicit column lists `_POST_DETAIL_COLS` (19), `_RELEASE_DETAIL_COLS`
  (10), `_SOURCE_DETAIL_COLS` (11), next to `_WORKS_GROUP_COLS`.
- `api_v1_get_post_detail`, `api_v1_get_work_detail` (seed + rep_full) and
  the shared `_fetch_releases_with_sources` use them on the public path;
  the operator path (`full=True`) still reads `*` — its answer is the raw
  ledger row.
- Dropped from the read: `posts.url/slug/duration/director/release_date/
  raw_meta/lastmod/created_at/updated_at/source_site/canonical_key`,
  `releases.intermediate_url/source_site/origin_url`,
  `download_sources.source_name/resource_id/source_url/final_url/
  last_failed_at/failure_class/failure_resolver_v/created_at/updated_at/
  ttl_source`. Kept deliberately: `expires_at` (a `link`-view key) and
  `last_checked_at` (the ladder's rank-0 branch) — see the two misses.

Measured on a 3-release x 5-source fixture with 400-char marker values in
every dropped column: `SELECT *` fetched 32,027 bytes on the post-detail
path vs 679 projected — 98% of the read was columns the client never
sees. The wire payload is UNCHANGED by construction (the views already
define it): public post detail 8,445 bytes and work detail 8,620 bytes
before and after, full payloads 40,260 / 40,435 bytes before and after.
The win is server-side read + materialisation, not wire bytes. (The 223 KB
live figure is dominated by projected link handles across many releases;
shrinking the wire would mean changing the views, not the SQL — follow-up,
not this slice.)

Two misses the measurement caught before they shipped:
1. `download_sources.expires_at` is a migration-added column in the
   `link` view — the first cut lost the `"expires_at": null` key and the
   payload shrank 8,445 -> 8,085 bytes. Added to the list; payload
   byte-identical again (normalised comparison, tokens decoded to row ids).
2. `_source_ladder_key` reads `s["last_checked_at"]` on the active branch
   (IndexError, 5 errors in `test_works_api`) — my first fixture was all
   `unverified`, so it never touched that branch. Added the column and
   made one fixture source probe-confirmed live (active + last_checked_at
   + http_status 200) so the rank-0 branch is exercised.

New tests `Fast_Fetch/test_detail_projection.py` (4 tests):
- public post detail equals a hand-built `SELECT *` reference (tokens
  normalised to row ids) and carries no dropped-column marker;
- SQL trace of both detail calls: no `SELECT *`, and the 7 detail
  SELECTs name exactly the projected columns (3 post + 2 release +
  2 source);
- `full=True` still returns the raw row (all markers present), both
  endpoints;
- work detail episode guide + `latest_episode_added_at` survive
  (`created_at` is only needed by the work path's `build_seasons`).
New gate step in `.github/workflows/backend-ci.yml` (109 suites now).

Local: detail_projection 4/4, tiers OK, works_api 52/52, catalog_api OK,
redact 116/116 solo (two earlier runs failed 3 then 1 — the known
test_redact HTTP-timeout load flake; solo re-run green, not fixed).
Full gate (run-backend-gate.py, 92 modules): 91/92 PASS — the one
failure is `test_download_endpoint.py::test_json_descriptor_has_a_handle_
and_no_upstream_address`, the known token-minting expiry-second flake
(server re-mints the handle one second later); it passes 3/3 solo and
the download path never touches the detail loader. New module passed in
the gate (4 tests in 0.205 s).
