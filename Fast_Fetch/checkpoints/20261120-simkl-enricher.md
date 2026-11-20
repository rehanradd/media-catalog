# 2026-11-20 — Simkl Phase-2 metadata fallback enricher

Branch `muse/simkl-enricher-20261120` from main `b8882742`. NOT merged —
parent merges. No live DB touched; no live deploy.

## Verdict that drove this

Probe on 100 catalog works with an IMDb id but no rating (run 21:03–21:12
PKT 20 Nov, VM PID 721058; first run crashed on a list-shaped Simkl
response, probe fixed and re-run):

- Simkl found **86/100** — ratings on 95% of found, poster 98%,
  overview 99%, genres 100%, year 98%, runtime 92%, cast 0% (no cast
  in Simkl summaries, by design).
- TMDb found **26/68 completed calls (38%)** on the same 100. TMDb calls
  from the Oracle VM fail at the network level about half the time
  (status 0): first run 64/100 failed, retry at 1.0s pacing 32/64 still
  failed. That flakiness is a separate finding about TMDb-from-VM, not
  about TMDb's catalogue.

Decision (user's rule: add Simkl iff its hit rate >= TMDb's): Simkl is
the Phase-2 metadata fallback — site metadata first, TMDb next, Simkl
fills remaining gaps; cast stays TMDb-only.

## What was built

- `Fast_Fetch/simkl_enricher.py` (new, pure — no network, no key):
  `normalize_payload` (a list-shaped answer resolves to its first dict
  element), `extract_metadata` (title/year/overview/genres/rating/
  poster/fanart/runtime/tmdb_id/imdb_id — missing stays absent, never
  invented), `best_rating` (IMDb rating wins, Simkl score is the
  fallback), `poster_url` / `fanart_url`, `lookup_url`, `endpoint_for`.
- `Fast_Fetch/metadata_backfill.py`: `SimklSource` (NAME `simkl`,
  ENV_VAR `SIMKL_CLIENT_ID`, 0.35s pacing, IMDb-id lookup only — exact
  identity, no title guard; `/movies|/tv|/anime/:imdb` with the
  `client_id` query params + `simkl-api-key` header, mirroring the
  probe). `FIELD_SOURCES` now has `simkl` right after `tmdb` for
  rating, synopsis, poster, backdrop, year, genres and `tmdb_id`;
  cast/director untouched. `SOURCE_CLASSES` gains `SimklSource`.
  Module docstring updated (priority lists, sources table, keys line).
  Key comes from the environment only — never in a file, a URL log, or
  a commit. The live key lives only in `/home/ubuntu/.radd-metadata-keys`
  on the Oracle VM.
- `Fast_Fetch/test_simkl_enricher.py` (new): 23 tests, pure, no network.
- `Fast_Fetch/test_metadata_backfill.py`: `SimklClientTests` (8 tests —
  movie + series endpoints, list response, Simkl-only rating marked
  `simkl`, no-imdb-id no-call, 404 raises like every source, disabled
  without a key, wiring pinned after TMDb).
- `.github/workflows/backend-ci.yml`: 🧪 step for
  `test_simkl_enricher.py` (gate count 135 → 136).

## Image URLs — verified live, not assumed

- Poster: `https://simkl.in/posters/{poster}_m.jpg` → 200 image/jpeg
  (58 KB for The Matrix). `_l.jpg` / `_s.jpg` 404.
- Fanart: `https://simkl.in/fanart/{fanart}_medium.jpg` → 200
  image/jpeg (270 KB / 623 KB for the two probe titles). Fanart `_m.jpg`
  301s to an `update_m_alert.jpg` placeholder — the wrong suffix, found
  by probing, not by reading docs. (Simkl's own docs recommend serving
  these through the wsrv.nl proxy; the backfill stores the direct
  simkl.in URL, same as it stores direct TMDB URLs.)

## Verified but NOT wired (follow-ups, not oversights)

- The movie payload carries a `director` string (`"Lana Wachowski"`
  for The Matrix) — real, but the task's Simkl field list was
  title/year/overview/genres/rating/poster/fanart/runtime, so director
  is not wired. One-line follow-up: add `simkl` to the `director`
  chain after `tmdb` and map it in `SimklSource.lookup`.
- `runtime` is extracted (movie minutes, show per-episode minutes)
  but the backfill has no runtime column — the duration job owns it.
- Title search (`/search/...`) is not used: IMDb-id lookup covers the
  11,799 no-rating works that carry an IMDb id, which is the probe's
  population. A title search would need the disambiguation guard.

## Tests

Local: new module 23/23, `test_metadata_backfill.py` 90/90 (82 before +
8 Simkl), `test_docs_invariants.py` 22/22. CI pending on the branch push.
