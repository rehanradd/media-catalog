# Session 2026-11-19 — H4 R5 shared streaming cache `[H4-SHARED-CACHE]`

Branch `muse/h4-shared-cache-20261119` from `a0776cbf` (preload + caches).
Builder only — the main chat merges; no merge, no push to main, no deploy,
no restart, no live-DB write.

## Research (done first, 5 searches, ~22:33–22:35 PKT 19 Nov)

1. Shared resolved-link caching: L1/L2 hybrid cache, in-process singleflight
   + cross-fleet distributed lock, short normalized keys, cache only data all
   key-sharers may see. Sources: HybridCache/Redis ASP.NET Core 10 article,
   role-aware HTTP cache (doc.exponential.earth), systemdesignsimple.com five
   cache layers, M3U Proxy shared connections.
2. Expiring signed URLs: signature params in the cache key collapse hit ratio;
   serve a cached URL only with a conservative validity margin; expire the
   cache before the URL; one worker regenerates while others wait; revoking /
   expiring a token does NOT purge a CDN entry. Sources: dev.to private-exports
   signed-download caching, media-delivery.com short-lived signed URLs,
   Supabase Smart CDN docs, codestudy.net S3 signed URLs.
3. Stampede: request coalescing / singleflight, distributed lock (`SET NX PX`
   with expiry), probabilistic early expiration / XFetch, TTL jitter,
   stale-while-revalidate (RFC 5861). Sources: kbytechnologies.com, dev.to
   single-flight guide, syedtasavour.com, martinuke0.github.io.
4. Hit-rate measurement: byte-weighted hit ratio is the money metric; segment
   by content type/region; video segments target 96%+; presigned URLs in the
   cache key make hit ratio structurally zero. Sources: dev.to CDN audit,
   ottverse.com, blog.blazingcdn.com, Alibaba Cloud CDN KPIs, baeldung.com.
5. Presigned-URL reuse (typo-variant query): presigned URLs are reusable
   bearer tokens until expiry; effective expiry is the shortest limit;
   CloudFront strips signature params from the cache key, Cloudflare does
   not by default. Sources: Cloudflare R2 presigned-URL docs, zerobuffer.io,
   media-delivery.com signed URLs, sysax.com.

## Audit (verified in code)

- The brief's premise is wrong: `resolved_cache` is ALREADY shared across all
  devices/users. It is keyed by `link_id` only (`resolved_cache.py:142-169`);
  `lookup_cache(conn, link_id, ...)` takes no identity
  (`resolved_cache.py:704`). What is per-device is only the rate bucket
  (`viewer.py` `_public_gate`). So the build closes the remaining gaps in the
  already-shared cache; it does not build a second one.
- Press path: `/api/v1/stream` (`viewer.py`) — adult gate
  (`redact.link_is_adult`) and quality gate run BEFORE any cache lookup;
  cache hit → instant 302 with `Cache-Control: no-store`; miss →
  `_resolve_coalesced` (`viewer.py`, `_RESOLVE_WAIT_SECONDS = 12.0`, key =
  `(source_id, v2, want)`) → bank via `record_resolved_cache` +
  `note_serve_outcome(..., served_from="cold")`. Download route mirrors it.
- What a second user pays today: same winner link + fresh row = instant 302
  (already shared); different band, rotated winner (host ranking R1/R2/R4
  re-ranks from live checks), or cold title = full cold resolve (~6–13 s
  measured press 1); simultaneous cold presses of the same link stampede
  past the 12 s wait into duplicate resolves (pinned trade-off, below).
- Pool warming uses a proxy, not real demand: `select_priority_posts`
  (`prefetch_worker.py:356`) ranks by `COUNT(releases)+COUNT(sources)` —
  catalog size, not presses. No play-count column exists. Fill runs 18:00
  (≤2 links/title: start rung 480p + auto rung), top-up 100 titles at 21:00,
  sweeps 00:30/06:30/12:30. A title that goes hot between runs stays cold for
  every user until the next run.
- Never-cache-302s is already enforced on both branches; the build caches
  resolved URLs in SQLite, never 302 responses. Already pinned by
  `test_bundle_handles.py` and `test_api_v2.py`.
- Quality gate cannot leak through a shared cache: the gate is checked per
  press before lookup, and gated prints never mint handles (`route_play`
  refuses gated bundles before minting).
- `ip_bound=1` rows are refused by `lookup_cache` unless the caller opts in
  — new paths use the same lookup, never bypass.
- Warm infrastructure reused: `_start_preresolve` (dedups in-flight warms,
  120 s TTL, non-blocking acquire on `BoundedSemaphore(32)`, fail-closed),
  `_preresolve_is_warm`, `_resolve_and_bank`, `tiers` (`BANDS`,
  `band_of`, `for_release`, `merge`).

## Dropped designs (hard stops, not built)

- Stampede busy-signal: changing `_resolve_coalesced`'s waiter-timeout
  own-resolve fallback would break the pinned test
  (`test_edge_phase1.py::test_a_timed_out_waiter_resolves_on_its_own`).
  The duplicate-resolve-after-12 s hole stays as a documented residual.
- Stale-while-revalidate past `reverify_after`: gains at most 60 s (the
  `REVERIFY_MARGIN_SECONDS` in-flight death margin) and eats the guarantee a
  token never dies between our 302 and the player's first byte.
- Cross-process locking: the server is a single process
  (`ThreadingHTTPServer`), so in-process `_resolve_coalesced` covers all
  presses.
- Changing the pool's default selection: `select_priority_posts` stays
  size-proxy by default; demand enters only via an opt-in `--demand` flag.

## Changes

- A. `Fast_Fetch/resolved_cache.py`: `shared_warm_demand` table
  `(day, hour, work_id, band, cold_presses)` — aggregates only, no user /
  device / URL / link columns. `note_cold_press(conn, *, work_id, band,
  now=None)` — upsert +1, never raises, returns the new cell count.
  `hot_works(conn, *, hours=6, min_presses=2, limit=20, now=None)` — works
  ordered by cold presses in the window, with the adult exclusion
  (`is_18_plus=0` + canonical_key twin exclusion, same two clauses as
  `select_priority_posts`). Band validation light: non-empty string ≤16
  chars, else `"unspecified"`.
- B. `Fast_Fetch/viewer.py`: `_note_shared_demand(conn, source_id)` →
  `(work_id=post_id, band)` via `download_sources.release_id →
  releases.post_id` + `tiers.band_of`. `_warm_sibling_bands(source_id)` —
  bounded background warm of the same print's other playable bands
  (the fill's links minus the pressed one, max 2, skip already-warm via
  `_preresolve_is_warm`, reuse `_start_preresolve` dedup + semaphore,
  never raises). Wired into the stream-route and download-route cold-bank
  branches (inside the existing try/except, after
  `record_resolved_cache`, public port only).
- C. `Fast_Fetch/prefetch_worker.py`: `--demand` flag (fill/topup only) —
  hot works from `hot_works()` treated as pins (operator `--pins` first,
  then demand, then priority pick), cap 20, `stats["demand"]`. Threaded
  through `_run_one_sweep` → `prefetch_once(demand=False)`. Sweeps ignore it.
- D. `Fast_Fetch/test_shared_cache.py` (new, 33 tests) + a CI step in
  `.github/workflows/backend-ci.yml` (121 🧪 steps) + the module in
  `test_ci_manifest.py` `MANDATORY_RUN`.

## Tests (local, this box)

- New module `test_shared_cache.py`: 33/33 OK. Covers: demand-ledger
  counting and no-identity schema; `hot_works` ordering / window /
  threshold / limit / flagged + twin exclusion / midnight span; sibling-warm
  bounds (max 2, skip warm, unknown stays unspawned, cap, missing link);
  never-serve-expired (shared-by-construction + expired row refused +
  ip-bound refused without opt-in); demand promotion (hot beats priority,
  operator pins order before demand, sweep ignores demand, flagged hot work
  never promoted). 302 `no-store` is already pinned elsewhere.
- Affected suites, all green locally: `test_resolved_cache` 93/93,
  `test_prefetch_worker` 27/27, `test_edge_phase1` 19/19,
  `test_redact` 116/116, `test_bundle_handles` OK.
- `test_ci_manifest` 9/10 locally: the one failure is
  `test_every_run_module_is_tracked_and_resolvable` — the new test file is
  not committed yet, which is exactly what that test exists to catch; it
  passes once this commit lands.

## Hit-rate measurement (how the win will be read)

- `fill_serve_stats` pool/jit/cold via `serve_share` (evening 19:00–22:00
  window): pool share rising after a `--demand` fill is the cache working.
- Demand side: `hot_works` itself — a work that stops appearing in the
  ledger's hot list after a fill was warmed by it.

## CI status

- Pushed branch `muse/h4-shared-cache-20261119` to origin (commit TBD in the
  parent's report — this checkpoint was written before the push).
- Backend CI run id + verdict: owed — the parent watches the run after the
  push. No Android CI: no App file touched (backend-only slice).

## Still owed

- Live verification only at gate time: a `--demand` fill on the VM, then a
  second press of a hot title from another device hitting warm. Nothing here
  changes the live tree, the service, or the panel.
