# Live fill on detail open — session log (2026-11-19)

Branch `muse/live-fill-detail-20261119`, base main `5ebf1e8b` (metadata fill v2).
Worktree `~/workspace/live-fill-detail-20261119`. Never merge / push main / deploy.

## Research first (standing user order — searched wide, several phrasings + typos)

- **iptvnator TMDB enrichment** (`docs/architecture/tmdb-metadata-enrichment.md`):
  detail renders provider data immediately; enrichment runs async and patches
  the item once upstream responds; a **staleness guard drops responses that
  arrive after the user navigated away**; field-level merge, provider stays
  authoritative; all lookups cached in SQLite; match is conservative —
  **wrong metadata is worse than no metadata** (id corroborated by title/year).
- **M3U Editor**: "Auto-enrichment on request" — a title is enriched the first
  time someone opens it in a client instead of during sync; keeps syncs fast
  on large playlists while filling the titles people actually watch.
- **plekt media detail**: a **render token** — stale responses check the token
  and drop themselves; two-stage first paint (local shell first, enrichment
  patches afterward); enrichment patches sections, never replaces the whole
  detail root.
- **FreqBlog music API**: cache hits ~30 ms; a miss is queued in the
  background; two-layer lookup (own catalogue, then upstream).
- Pitfalls taken into the design: stale responses painting over a
  navigated-away screen (render-token guard on the app); wrong match worse
  than no match (reuse v2 `choose_candidate` exactly); stale provider ids
  (corroborate, never trust blindly); rate limits on the detail-open path
  (cache-first + per-route token bucket + persistent daily budget for the
  metered sources); never block first paint (fire-and-forget, silent).

## Design

### Server — `POST /api/v2/enrich`, body `{"work_id": N}` or `{"post_id": N}`

- One title, on demand. The app fires it in the background after detail
  lands; the screen never waits for it.
- **Write path (explicit):** the app NEVER writes the DB. The route runs
  inside `api_v2.route_post`'s `session(db_path)` — one read-write
  connection per request, committed on success, rolled back on error — and
  `fill_one` runs `UPDATE posts SET col = ? WHERE id = ? AND <empty-guard>`
  through that same connection. The fill-only-empty rule is enforced twice:
  in Python (`_field_missing` / `Plan`) and in SQL (the column must still
  be NULL or a placeholder when the UPDATE lands), so two detail opens
  racing each other cannot overwrite.
- **Reuse, not a second engine:** `fill_one` (new in `metadata_backfill.py`)
  runs the batch's four passes at group scope — site siblings, IMDb datasets
  (skipped live: loading the TSVs per request is too heavy; OMDb/TMDb cover
  ratings anyway), API via `_resolve_representative` (which calls the v2
  `choose_candidate` — language/industry first, cast/director overlap,
  still tied = fill NOTHING + review entry; ID lookups exact, bypass it) —
  then propagation to siblings. The site-pass donor rule is factored into
  `_donor_values` and shared with `plan_site_pass` (same rule, one place).
- **Guards kept:** fill only empty fields, skip `is_18_plus=1` (group-level:
  any flagged sibling skips the whole work — the flag belongs to the work,
  not one row), never invent data, per-source pacing + 429 backoff,
  ResponseCache (30-day, same shared file as the batch), OMDb/MDBList caps.
  The batch's cap is per-run; a live server has no "run", so a small
  `DailyBudget` sidecar next to the cache file persists per-day metered-call
  counts across requests and processes (date rollover resets it). Cache hits
  never consume budget.
- **Response:** `{"ok": true, "status", "filled": {field: display_value},
  "sources": {field: source}, "ambiguous": [...], "posts_updated": [ids],
  "fields_filled": n}` — `filled` is display-shaped (genres are ", "-joined,
  backdrop is the stored JSON-array string, rating one-decimal), so the app
  assigns it straight into the detail. `filled` also echoes donor values the
  batch already stored in a sibling post — the endpoint self-heals a stale
  projection instead of answering "already complete" while the screen still
  shows blanks. Statuses: `filled | already_complete | no_match |
  ambiguous | not_found | skipped_adult`.
- **Uniform denial (§13):** an adult work answers the same `not_found`
  problem as a missing one — no hint the flag caused it. The route checks
  the flag before any upstream call.
- **Cheap by construction:** work-level missing is computed from the stored
  group first; a complete title answers without any upstream call (the
  common case after the first fill). Live timeout 10 s per upstream request.
- **Throttling:** new head `enrich` in `ROUTE_ARITY` (POST `(1,)`), bucket
  `"reports"` (10/min per client — the same class as the other client-write
  POSTs). 429 is fail-open on the app side: no fill, screen unchanged.
- **Kill-switch:** `RADD_SKIP_LIVE_ENRICH=1` (already set by every test
  module, previously unread) disables the route with `unavailable`. The
  operator can stop live enrichment without a deploy.
- **Gate-time items (NOT in this branch):** nginx must allow-list
  `POST /api/v2/enrich` (the public edge 403s unlisted v2 routes; the app
  treats 403 as "no fill", so the feature degrades silently until the gate
  adds it); the live server process env needs the metadata keys
  (`TMDB_API_KEY`/`TMDB_BEARER_TOKEN`, `OMDB_API_KEY`, `MDBLIST_API_KEY`,
  `TRAKT_CLIENT_ID`, `FANART_API_KEY`) — sources with missing keys report
  disabled and are never called. Cache dir override: `RADD_METADATA_CACHE_DIR`
  (default `~/.cache/radd-stream-metadata`, same as the batch).
- **Projection note:** the fill writes `posts`; `cat_works` picks the values
  up on the next projection run (first non-empty per field). The response
  carries the values so the current screen does not wait for that.

### App — DetailViewModel + CatalogRepository (Kotlin)

- `CatalogRepository.enrichWorkDetail(workId, postId): Map<String, String>?`
  — one Retrofit call, fail-open null on ANY failure (403/404/429/dead
  socket), `CancellationException` rethrown like every other repository call.
- `DetailViewModel` triggers it once per detail load, only when displayable
  fields are missing (`missingMetadataFields`: rating, synopsis, poster,
  backdrop, cast, genres, year — director/tmdb_id/imdb_id are persisted by
  the server but never shown, so they do not trigger it), only after the
  Kids Lock gate passes, in a background launch — no spinner, no new state.
- Staleness guard (plekt render token, one line): the fill applies only if
  `_uiState.value.post` is still the exact `PostDetail` instance the fill
  launched for (`!==` drops a response that arrived after a reload).
- Merge is pure: `PostDetail.withLiveFill(filled)` fills only blank fields,
  never overwrites a present value, ignores unknown keys, parses year.
- No new strings: the fill is silent by design — fields appear, nothing
  announces it (and the 16:20 cinema rule says no extra feedback).

### Tests

- Python (`test_metadata_backfill.py`, `FillOneTests` + `DailyBudgetTests`):
  fill-one happy path, already-complete, not-found, adult skip (post and
  flagged sibling), tie → nothing + ambiguous, language-win, ID-bypass,
  empty-only, donor echo without network, budget rollover/seed/record.
- Route (`test_api_v2.py`, `V2LiveFillTests` + registry/bucket updates):
  enrich fills + persists, adult id answers like a missing id, bad body is
  `invalid_params`, kill-switch answers `unavailable`, bucket is `reports`.
  The structural tests (`post_heads - get_heads`, `ROUTE_TESTS`) are updated
  for the new POST-only head — they fail by design on a new route.
- App (`DetailLiveFillTest.kt`, `LiveFillRepositoryTest.kt`): pure
  missing/merge functions; repository with a proxy-fake `RaddStreamApi`
  (403/IO → null, ok → filled map).
- Gate: `python3 -S -m unittest test_redact` + the real-data sweep per
  Fast_Fetch/AGENTS.md §10 rule 10 (API-shape change).

## Status

- [x] Research (above), worktree + branch at 5ebf1e8b
- [x] Server: `fill_one` + `DailyBudget` + `_donor_values` in metadata_backfill.py
- [x] Server tests in test_metadata_backfill.py (local green, 82/82 incl. 19 new)
- [x] Route: `route_enrich` in api_v2.py + arity/bucket
- [x] Route tests in test_api_v2.py (local green, 90/90 incl. 7 new, incl. structural tests)
- [x] `test_redact` local green (116/116)
- [x] App: wire models + repository + ViewModel trigger/merge
- [x] App tests (CI is the gate — never `./gradlew` locally)
- [ ] Session logs (this file, CONTINUITY count bump, App SESSION_HISTORY)
- [ ] Commit + push branch, CI green (fix + re-push; re-run flakes once)
- [ ] Report tip + files + test counts + CI run id + verdict

## Hard safety (standing)

- Never touch run 1 (PID 32350), `/home/ubuntu/metadata-staging.db`,
  `/home/ubuntu/metadata-backfill.sql`, or any live DB — fixture DBs only.
- Never `./gradlew` locally. Never merge, push main, deploy, tag.
- Commit author: `rehanradd <199005610+rehanradd@users.noreply.github.com>`.

## Implementation notes (19 Nov, while building — corrections to the design above)

- `cat_works.work_key` is COMPUTED (`work_key_for`: imdb:/tmdb:/family:title:year),
  not `posts.canonical_key`, so a `work_id` cannot name a key. The route
  resolves `work_id` → posts through `cat_work_sites` (DISTINCT post_id),
  and `fill_one` gained a third identity, `post_ids` — the union of the
  canonical_key groups of the given posts (one work can span several keys).
  `plan.post_key` is per row now (`_row_key`), not one key for the group.
- Wire response drops `posts_updated` and strips `post_id` from `ambiguous`
  entries: a ledger row id must never reach a public client (opaque-handle
  rule). `fill_one` still returns both, for tests/logs. `filled_post`
  (field → post id that supplied the value) is new in the result, so the
  route can mint artwork handles against the post that holds the art.
- Artwork is shaped in the route through `redact.public_art_value` against
  `filled_post` — exactly like the v1 detail: a CDN poster passes through,
  a site-hosted one becomes an `/api/v1/art` handle, an unshapable one is
  dropped from `filled`, never leaked raw. Backdrop: parse the stored JSON
  array, shape each item, re-serialize (same as viewer.py).
- `build_sources()` is the public wrapper the route calls; the batch keeps
  `_build_sources` with its own pacing. Route sources: timeout 10 s,
  min_interval 0.
- `DailyBudget` is built per request in the route (date fixed at
  construction, so a process living past midnight rolls over on the next
  request). Cache flush happens in `fill_one` when a cache is passed.
- `fill_one`'s "skipped_adult" and "not_found" both answer 404 `not_found`
  at the route; the work-level `cat_works.is_18_plus` check happens first,
  before any network.
- Known limit (not fixed): worst-case upstream time is bounded by
  ~3 candidates × ~10 sources × 10 s timeout; healthy upstreams answer in
  <1 s and the app never waits on the call. Revisit only if a slow-upstream
  incident shows up in the logs.

## Implementation notes — app side (19 Nov, while building)

- `PostDetail` has no director field (director is persisted, never
  displayed), so `withLiveFill` ignores the `director` key — it still
  lands in the DB for the next projection. The merge is blanks-only on
  every other field, so a value already on screen is never overwritten.
- The server's `filled` map carries ALL work-level values, not just the
  fields that were blank before — the stale-projection echo (app asks
  because `cat_works` is blank, posts already hold the value) would
  otherwise never reach the screen. The blanks-only merge makes the
  extra values harmless.
- Exactly-one-id is enforced twice: the ViewModel sends only the work
  id on the v2 path (a v2 detail whose bridge found no v1 post still
  has its work id) or only the post id on the v1 path, and the
  repository prefers the work id if both are ever passed. The server
  400s a body with both — the first draft sent both on the v2 path and
  would have failed every v2 fill.
- The ViewModel fires once per load, in the background, after the Kids
  Lock gate, only when `hasMissingDisplayFields()` is true. The
  staleness guard is reference equality (`current !== detail`): only
  the exact instance this load showed may be patched. `rawDetail` is
  patched too, so a later Kids Lock re-check reads the patched detail.
- The hero reads state `post` (`DetailScreen` reads `post.backdropList`
  / `post.posterUrl`), not the layout's artwork pool, so patching
  `post` updates the hero with no layout rebuild.
- No new strings: the fill is silent by design (no spinner, no toast).
  No Paparazzi goldens: no visible UI change — fields just appear.
- App AGENTS.md rule 7 (test every network model): `ModelsTest` gains
  a decode test for `LiveFillResponse` (incl. the `ambiguous` list the
  model deliberately omits — Gson ignores the unknown key) plus a
  `LiveFillRequest` serialisation test (`{"work_id":42}` — Gson omits
  the null id; the server treats null and missing the same anyway).
- Gate-time items (not run in this lane): the real-data sweep
  (`sweep_public_edge.py --db <snapshot> --probe`, Fast_Fetch/AGENTS.md
  §6 rule 10) needs a catalog snapshot; nginx must allow-list
  `POST /api/v2/enrich` (public edge 403s unlisted v2 routes — the app
  treats 403 as "no fill"); the live server env needs the metadata
  keys; cache dir override `RADD_METADATA_CACHE_DIR`.
