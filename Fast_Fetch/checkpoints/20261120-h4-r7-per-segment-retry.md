# H4 R7 — per-segment retry: mid-playback failure feedback loop (2026-11-20)

Branch `muse/h4-r7-per-segment-retry-20261120`, base main `87f7ebef` (E2 For-you).
Builder: subagent. Main chat merges; do NOT merge here.

## Research (one line each)

- Transient errors (5xx, timeout) are retryable; permanent ones (404/410/401/403) must never be
  retried — the underlying issue will not resolve. Source: copyprogramming.com video-retry guide.
- HLS.js retries manifest 3x, segments 5x; exponential backoff with jitter is the industry
  standard; a circuit breaker stops cascading failures. Same source.
- Media3 `DefaultLoadErrorHandlingPolicy`: min retry count 3 (6 for progressive live), failing
  location excluded 300 s, fallback types are LOCATION (same resource, different URL) and TRACK
  (different rendition); `ParserException` / `FileNotFoundException` never retry.
  Source: developer.android.com `DefaultLoadErrorHandlingPolicy`.
- Proxy failover pattern (M3U Proxy): retry the same source up to 3x 1 s apart, then the next
  backup, give up when all fail; a stream counts as failed on connection error or 15 s with no
  data; total timeout 30 s. Source: m3ue.sparkison.dev failover docs.
- Server-assisted failover (US patent 10,972,515): the manifest lists redundant sources; the
  client switches on a 4xx/5xx to a segment or manifest request. Same idea as HLS Content
  Steering (player re-reads a steering manifest every 60–300 s).
- Multi-CDN rule of thumb: fail fast, recover slow — 3 consecutive failures to mark unhealthy,
  5 consecutive successes to fail back. Source: blog.blazingcdn.com multi-CDN failover.
- Retry only while nothing has been emitted: once the first chunk reaches the caller the turn is
  committed and a retry would replay output. Source: providerkit.dev retries guide.

## Audit — what exists, with file:line evidence

Press path (server): `/api/v1/stream` (`viewer.py` ~8400) — cache hit serves an instant 302 with
NO re-verification; cache miss runs `_resolve_coalesced` (`viewer.py:7037`), banks on success
(`record_resolved_cache`), records `note_failure_from_probe` on failure, else 404
`no_playable_source` — and the comment there says the 404 is the app's cue: "the client's
failover ladder moves to the next source".

Bundle path (server): `POST /api/v2/play` (`api_v2.py:2728` `route_play`) walks prints
best-first (Session 109/110/113), prefers a VERIFIED sibling, and returns a bundle whose tiers
carry winner + backups (`backup_handles`, `catalog_api.py:2929`).

Mid-playback (app): `FailoverStateMachine` (`RenditionFailover.kt:249`) already hops within the
bundle — RetrySame once (after first frame only), RetryWithSoftwareDecoder (MX #1), RetryWithOtherDecoderMode
(MX #3), AdvanceBackup (runner-up handle of the same rung), Advance (next rank), Handoff
(give-up card). Bounded: per-index attempt counts, one software retry and one mode fallback per
rendition. `PlaybackService.onPlayerError` (`PlaybackService.kt:1336`) drives it, with
classified flags: `isDeadHandleError` (404/403/410 from OUR redirector), `isGateRefusalError`
(402), `isRateLimitedError` (429), decode failure.

Reporting (app→server): `POST /api/v2/links/{id}/report` (`api_v2.py:1960`,
`REPORT_EVENTS = ("played", "stalled", "failed", "resolved")`). The app reports `played` once per
handle on first frame and `failed` once per attempt when the START deadline passes with no first
frame (`PlayerViewModel.kt:916,933,977,997`). A `failed` report strikes the resolved_cache circuit
(`api_v2.py:1994` → `note_failure`; `MAX_FAIL_COUNT = 3`, `resolved_cache.py:82`).

Failure ledgers (server) — TWO, and they do not agree:
1. `resolved_cache.fail_count` — read by `lookup_cache` (`resolved_cache.py:704`, refuses rows with
   `fail_count >= MAX_FAIL_COUNT` or past `reverify_after`). Struck by probe failures AND by
   client `failed` reports.
2. `download_sources.last_failed_at` (+ `failure_class`, `failure_resolver_v`) — the NEGATIVE
   CACHE, TTL 12 h (`fast_fetch.py:6920`), version 5 (`fast_fetch.py:6954`). Read by
   `_release_has_unfailed_candidate` (`api_v2.py:2170`, the /play walk) and by the resolver's own
   candidate filter (`fast_fetch.py:7302`). Written ONLY by the resolver itself
   (`_record_resolve_failure`, `fast_fetch.py:7125`, called at 7436/7494/7978) — never by a client
   report.

## Audit — the three gaps (decisive)

- **G1 — mid-playback failure is never reported.** `reportLinkFailedOnce` fires ONLY on start
  give-up (no first frame). After the first frame, `onPlayerError` hops/hands off and sends
  NOTHING. No `"stalled"` event is ever sent by any client (zero hits in `App/`). So a link that
  dies 10 minutes in leaves no evidence anywhere.
- **G2 — the report route parses the handle in the wrong universe (real bug, Session 111's
  distinction).** The app's `streamId` is minted from the LEDGER id (`catalog_api.py:977`,
  `redact.mint_token(int(ledger_id), ...)`; `_ledger_stream_handle`, `api_v2.py:1567`:
  "`stream_id` ... is parsed as a `download_sources` id"). But the report route resolves it as a
  CAT id: `link_row` does `cat_links WHERE l.id = :id` (`api_v2.py:1368`). In production the
  lookup returns the WRONG row (id collision) or 404s — so `record_link_report` /
  `cat_link_evidence` attribute the event to the wrong link/work. `note_failure` accidentally
  addresses the right row (resolved_cache IS keyed by ledger id), which is why the existing test
  passes: `test_api_v2.py:1421` banks the cache row under the CAT id and reports with a CAT-id
  handle, so one namespace is used throughout and the mismatch is invisible. The test must be
  re-proven with cat id != ledger id.
- **G3 — the stream hit path never consults the negative cache.** `lookup_cache` refuses only
  on horizon / fail_count / IP-bound. One dead-handle report (fail_count 1 of 3) leaves the banked
  row servable, so the NEXT press serves the same dead link instantly from cache — the exact loop
  the brief names. And the /play walk reads only the negative cache, so even the start-failure
  reports (which strike fail_count only) never move the next /play off the dead print; it
  self-heals only after 3 strikes force a re-resolve that probes the dead link and THEN writes
  `last_failed_at`.

## Audit — what is NOT missing (do not rebuild)

- In-session hop on segment failure: exists in the app (FailoverStateMachine, bounded).
- Press-time candidate walk: exists (`_verify_option_tiers`, `_resolve_coalesced`, /play walk).
- The server can NEVER see a segment fetch (302 → player fetches directly from the host), so a
  pure server-side "retry on segment fetch" is impossible without the app telling it. The app
  piece is therefore REQUIRED, and small: report the abandonment; the server decides what strikes.
- `segments` (MX #4, intro/recap windows) are NOT media segments — they are skip windows, and
  play no role here.

## Design (what this slice builds)

Server (`Fast_Fetch`, backend-only core):
1. `api_v2.py` report route — fix the universe + accept a failure class. Parse the handle as a
   LEDGER id; resolve the cat row by `ledger_link_id` (adult-scoped, uniform not_found for
   hidden/missing, first row wins); record evidence under the resolved CAT id; strike circuits
   under the ledger id. New optional body field `failure_class` ∈
   (`dead_handle`, `network`, `decode`, `gate`, `rate_limited`); unknown values ignored
   (fail-open = no class). On event `failed`: keep the existing `note_failure` strike (old
   clients without a class behave exactly as today); when class == `dead_handle` ALSO write the
   negative cache via `fast_fetch._record_resolve_failure_on(conn, ledger_id, "probe_failed")`
   on the same connection — a 404/403/410 from OUR redirector is the resolver's own verdict that
   the source is gone, the same evidence class as a probe failure. Other classes record evidence
   only (`cat_link_evidence ok=0` already happens); never strike on network/decode/gate/rate_limited
   (transport-evidence crown order: our side failing is not evidence about their link; a decode
   failure is a decoder problem, not a dead link).
2. `resolved_cache.py` `lookup_cache` — refuse a row whose ledger row carries a FRESH negative-cache
   record (age < `NEGATIVE_CACHE_TTL_SECONDS`, version >= `RESOLVER_VERSION`, NULL version = 0),
   mirroring `_release_has_unfailed_candidate` exactly. Fail-open: no `download_sources` table →
   skip the check; no row / stale / old-version → serve as today. Lazy `import fast_fetch` inside
   the function (the `api_v2` precedent) with literal fallbacks. Effect: ONE dead-handle report
   retires the banked winner on the very next press — the press misses the cache, re-resolves, and
   the resolver's candidate filter (which already reads `last_failed_at`) skips the dead winner and
   walks to the next verified candidate. Also corrects `_release_has_verified_candidate`: a
   recently-failed link no longer counts as "verified".
3. Bounds: one report = one strike; circuit still closes at 3; negative cache TTL 12 h bounds each
   record; report route stays in its rate-limit bucket; a client can only report handles it was
   given (signed tokens). No per-user/device columns anywhere (aggregates only).

App (small, required — see audit):
4. `RenditionFailover.kt` — pure `midPlaybackReportClass(decision, flags): String?`: returns the
   failure class when the machine ABANDONS the rendition after the first frame (Advance /
   AdvanceBackup / Handoff), null otherwise (RetrySame / decoder retries give the rendition another
   chance — reporting it dead would be false). Class mapping: deadHandle → `dead_handle`,
   gateRefusal → `gate`, rateLimited → `rate_limited`, decoding → `decode`, else `network`.
5. `PlaybackService.onPlayerError` — after `machine.onFailure`, when the pure function returns a
   class and the failure is after the first frame, record it in `PlayerSessionStore` (one-shot slot,
   same pattern as `lastStartError`): handle + class + summary.
6. `PlayerViewModel` ticker — consume the slot once (best-effort, once per handle+attempt, like the
   start-failure path) via `CatalogRepository.reportLink` with `failure_class`.
7. `LinkReportRequest` gains optional `failure_class`; `CatalogRepository.reportLink` gains an
   optional failureClass param. No new permissions/deps/screens/strings.

Out of scope: the `"stalled"` event (a stall that recovers is not a dead link); re-verifying every
cache hit (would kill press-banking — the miss path already re-resolves); changing the start-failure
report path (it keeps today's contract).

## Tests

New module `Fast_Fetch/test_per_segment_retry.py` (stdlib only), registered in `backend-ci.yml`
gate step + `test_ci_manifest.py` MANDATORY_RUN. Fixture: minimal temp DB with cat id != ledger id
(insert ledger rows first so ids diverge), proving the universe fix.
- dead_handle report: strikes fail_count AND writes last_failed_at (fresh, version = RESOLVER_VERSION);
  next lookup_cache refuses the row; /play walk treats the print as failed.
- network/decode/gate/rate_limited report: records evidence (ok=0) but strikes NEITHER ledger.
- unknown failure_class: treated as no class — fail_count strike, no negative-cache write.
- ledger handle with no cat row carrying it: 404, nothing written.
- adult-flagged work via ledger handle: 404, nothing written (same as missing).
- lookup_cache: refuses fresh negative-cache record; serves stale (> TTL) and old-version records;
  serves when download_sources is absent (fail-open).
- press-banking unchanged: fresh row with no failure record still serves.
App: pure tests for `midPlaybackReportClass` (abandon → class; retry/decoder → null; each flag maps
to its class) + a serialization assertion for the new `failure_class` field.

## Still owed (after merge)

- Live verification at gate time: press a title whose winner dies mid-play and confirm the next
  press serves the next candidate (not the dead winner), and the next /play names a different print.
- Device verify of the mid-playback report on a real phone.

## Status — 2026-11-20 ~02:05 PKT (builder, pre-push)

All code + tests written; nothing pushed yet, no CI run yet.
- Server: `Fast_Fetch/test_per_segment_retry.py` — 13 tests, all green locally
  (`python3 -S Fast_Fetch/test_per_segment_retry.py`, 0.43s). Fixture uses
  `catalog_v2.DDL` + `fast_fetch.init_db` + `resolved_cache.init_resolved_cache`;
  cat id 10 ↔ ledger id 7 (adult twin 11 ↔ 8), so the universe fix is proven, not
  assumed. Registered in `backend-ci.yml` (123 🧪 steps now) + `test_ci_manifest.py`
  MANDATORY_RUN. NOTE: `test_ci_manifest.py` fails locally until the new module is
  COMMITTED — its `test_every_run_module_is_tracked_and_resolvable` requires the file
  tracked in HEAD; that failure is expected pre-commit, not a defect.
- Regressions checked locally: `test_api_v2.py` 90/90 (one run-1 flake in
  `test_etag_moves_with_the_revision_not_with_the_query_shape` — two browse GETs
  straddling a wall-clock second so signed art tokens differed; 5/5 standalone OK,
  unrelated to this change), `test_resolved_cache`, `test_evidence_writeback`,
  `test_shared_cache`, `test_direct_file`, `test_resolved_link_cache` all OK.
- App: NEW `MidPlaybackReportTest.kt` (13 pure tests) + 2 new `V2PlayWireTest` tests
  (`failure_class` encodes on a mid-play report, omitted on a start-failure report).
  No local Gradle — CI is the gate.
- Session logs: `Fast_Fetch/CONTINUITY.md` 94 lines (under the 120 ceiling, no rotation
  needed) + `App/docs/SESSION_HISTORY.md` entry appended.
- Next: commit (author rehanradd <199005610+rehanradd@users.noreply.github.com>, stage
  only reviewed paths), push branch `muse/h4-r7-per-segment-retry-20261120`, report
  Backend CI + Android CI run ids. Do NOT merge — main chat merges.
