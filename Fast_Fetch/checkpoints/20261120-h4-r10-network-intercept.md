# 2026-11-20 — H4 R10 network-intercept fallback

Branch: `muse/h4-r10-network-intercept-20261120` (base main `44631e10`, H4 R9).
Goal: when a resolve/probe/serve request dies on the NETWORK (transport failure or
intermediary block), intercept it and retry through a bounded alternate route —
and only then let a dead verdict stand. A link must never be marked dead on a
network failure alone.

## 0. Rotation (done first, before any other edit)

CONTINUITY.md was at 11,992 B / 90 lines — 8 B under the 12,000 B ceiling.
Rotated out the 5 oldest "Rotated session history" entries (2026-11-16 telemetry
beacon; 2026-11-18 B4, H2 backfills, B1, R1+R2+R4) — all still indexed in
`checkpoints/README.md`, nothing deleted. Corrected the H4 R8/R9 entries from
"Backend CI pending" to their merged SHAs (8f7948d2 / 44631e10). Added this
lane's entry and bumped the archive count 123 → 124 (121 dated + 3 undated).
After rotation: 11,535 B / 86 lines.

## 1. Research (online, before building)

Six queries, incl. typo variants ("retrty fialover", "interecept"). What the
field agrees on — and what it warns against:

1. **Classify first, retry second.** Every fetch outcome falls into one of five
   buckets — success / soft block (200 + challenge page) / hard block (403/429/
   503 with a protection signature) / transient (timeout, DNS, TLS, 5xx with no
   protection signature) / permanent (404, 410, malformed URL). Recovery differs
   per bucket; conflating them causes retry storms or silent corruption.
   (thinkgenius.com, "Detecting and Recovering Failed Scraping Sessions")
2. **The single-retry rule for blocks.** At most ONE retry on a fresh identity
   per block; a second block on a fresh identity = mark error, no third attempt.
   Transient errors get up to two retries on the SAME route with backoff (10s,
   30s). Permanent errors get ZERO retries. (same source)
3. **Retryable set is narrow.** Retry: 429, 500, 502, 503, 504 + transport
   errors. Never retry: 404/410 (dead), 400/401 (invalid/auth). 403 is
   ambiguous — usually WAF/bot-check, often NOT dead; retry once with a
   CHANGED request (UA/headers), never the identical request.
   (simpleprog.com; dev.clauneck.workers.dev link-checker classification:
   broken / blocked / flaky as separate buckets; 403/429/999 almost never dead)
4. **Decide at the HTTP boundary.** An interceptor examines each request,
   rewrites only managed URLs, retries once against an alternate origin, closes
   the earlier response first, leaves cancellation alone. GET/HEAD/OPTIONS only
   (a timeout does not prove a write did not happen — RFC 9110 §9.2.2).
   (dev.to, "Designing Safer API Failover in an Android App"; OkHttp
   RetryAndFollowUpInterceptor: recoverable only if route exists + body repeatable)
5. **User-Agent is the cheapest alternate route.** Cloudflare error 1010 is a
   block on the browser signature: `Python-urllib/3.x` → 403, a browser UA →
   the server's own answer, same URL, same IP. (dev.to, "Cloudflare blocked
   urllib's default User-Agent"; already proven in our own CrowLLM work, where
   the fix was a browser-like UA.) A UA/headers variant is a legitimate
   alternate route; it is not a bypass of a JS challenge (urllib cannot run JS,
   so a real challenge still fails — that is H4 R8 clearance territory).
6. **Exit-IP rotation only when the block is IP-based.** Datacenter IPs are
   scored as bots; a fresh exit can fix a 403 — but only when the block comes
   from the IP. Rotating on a headers/TLS block burns the pool and changes
   nothing. Diagnose first. (megalania.io; novada.com) — and we have NO
   second egress today (single Oracle VM IP), so IP rotation is NOT an
   available route in this slice; the honest alternate routes here are:
   (a) UA/headers variant, (b) method variant (HEAD→ranged GET, already
   exists for HEAD liars), (c) a known-good alternate URL for the host family
   (e.g. vcloud.beer `api/index.php?link=` instead of the Turnstile-blocked
   short link), (d) the host-registry moved-domain rewrite.
7. **HTTPS→HTTP downgrade is REJECTED as a route.** Mozilla's try-https-first
   work warns a MITM can force the downgrade; for serving media links it
   would downgrade the user's stream. Not built. (bugzilla 1658924)
8. **Dead needs more than one failure.** Link checkers: 404 needs 2 failing
   runs, timeouts/5xx need 3, on separate days, before "dead" — a site down
   for an afternoon never qualifies. (blog.zyvop.com) Our two-strike
   soft-delete already embodies this; the gap this slice closes is that a
   single network failure can still count as a strike today.

Conclusion from research: build a small, pure classifier + a bounded
intercept wrapper around the existing single HTTP call site: network failure
→ one alternate-route retry (UA variant, then host-family alternate URL where
registered) → only then return the failure to the existing dead-verdict logic.
No proxy pool, no scheme downgrade, no JS challenge solving.

## 2. Audit (file:line evidence)

**A. The sweep verdict — one 5xx = DEAD (the core gap).**
- `link_state.classify()` (`link_state.py:290-320`): 401/403/429 → EXPIRED;
  404/410/451 → DEAD; the catch-all `400 <= status < 600` → DEAD. So one
  probe answering 500/502/503/504/522/524 writes `status='dead'`.
- `fast_probe.py:873` writes that verdict straight to the ledger
  (`_UPDATE_VERDICT_SQL`, executemany) — no strikes, no second opinion.
- Consequence: ledger dead → projected `cat_links.state='dead'` →
  `tiers.score()` 0 (not a candidate) + `tiers.playable()` False, and a
  quarantine candidate (`serve_verify.quarantine_and_delete_dead_links`)
  once its strike journal qualifies. A Cloudflare 522 or a 503 during a
  host deploy kills the row on one probe.
- The SERVE side already disagrees: `serve_verify.classify_failure()`
  (`serve_verify.py:228`) calls 429 or >=500 "flaky", never hard, and
  quarantine needs 2 hard or 3 flaky failures on separate days. Transport
  errors are already no-verdict on both sides (`link_state._TRANSPORT_ERRORS`
  == `resolved_cache.TRANSPORT_CAUSES`, pinned by test; measured 2026-11-15:
  transport killed 46% of dead rows before that fix). The 5xx case is the
  leftover half of that same bug: an intermediary's answer is treated as
  the link's death.
- Safe to fix: an EXPIRED row is never a quarantine candidate ("Expired
  links are never candidates: an expired signature is re-resolved, not
  removed"), is demoted in score but not zeroed (`tiers.py:411-418`), and
  the sweep re-probes it — the runner selects by staleness only
  (`link_state_runner.py:129`: `checked_at IS NULL OR checked_at < ?`,
  no status filter). A later 200 revives it; a later 404 still kills it.

**B. The prober — a HEAD verdict stands without a second opinion.**
- `link_health.probe()` (`link_health.py:596`): phase-1 HEAD answering
  >=400 (except 400/405/501) returns at once with `error=http_<status>` —
  no GET attempt. A HEAD transport error also returns at once.
- `fast_probe._needs_get_fallback()` (`fast_probe.py:616`): the ranged GET
  fires only for HEAD 400/405/501 or a `KNOWN_HEAD_LIARS` host (2 hosts).
  A 403/429/5xx HEAD from any other host ends the probe.
- Proven it matters: vcloud.beer HEAD 403 → ranged GET 301 → vcloud.my
  GET 200 (the GET-fallback slice's live proof). The liar list is a
  two-host allowlist for a general truth: HEAD lies about more than two
  hosts. Generalise the fallback to HEAD 403/429/5xx in both the full
  prober and the sweep's HEAD-only chain, so the two stay in agreement.
  Transport errors still never trigger a GET (same failure, same budget).

**C. Investigated and NOT built (with evidence).**
- `unwrap_vcloud` + the brief's `api/index.php?link=` route: VERIFIED LIVE
  from the Oracle VM 2026-11-20 and REJECTED. Real short link
  `vcloud.beer/gakagn8nc1xoog-` → 301 → `vcloud.my/...` → 200 in 8.8 s,
  28.5 KB, contains the `atob` token the unwrapper parses. The API route
  → 301 → `vcloud.my/api/index.php?link=...` → 200 in 2.7 s, 10.3 KB,
  titled "VegasCloud API" — but it contains NO atob token and NO
  r2.dev/hubcloud server links. It is a docs page, not a substitute
  landing. Not built.
- `fast_fetch.http_get` (`fast_fetch.py:2038`) retries the SAME request
  (same UA/URL) up to 2x — not an alternate route, but deliberately
  untouched: H4 R8 pins the clearance to the UA that earned it, so a UA
  variant on retry would break the binding. Its candidate-TLD probe for
  vegamovies/rogmovies/nexdrive already exists.
- No second egress exists (single Oracle VM IP): exit-IP rotation is
  unavailable. HTTPS→HTTP downgrade rejected (MITM risk, bugzilla 1658924).
- `serve_verify` side already correct: flaky-for-5xx, strikes, fail-open
  on no-answer (H4 R9), re-resolve-once on 403 (`verify_candidate`).
  `domain_resolver.fetch_url` (single GET, no retry) is the importer
  crawl-start path, not resolve/probe/serve — out of scope.

**D. The build (two small, pure-adjacent changes).**
1. `link_state.classify()`: 5xx (500/502/503/504/522/524, any 5xx) →
   STATUS_EXPIRED, before the generic 400–599 → DEAD branch. Docstring
   mapping table (`link_state.py:58-61`) updated to match. 404/410/451
   stay DEAD; 401/403/429 stay EXPIRED; transport stays UNVERIFIED.
2. `link_health.probe()`: HEAD 403/429/5xx falls through to the bounded
   ranged GET (same headers/UA/timeout/sample cap) instead of returning
   at once; the GET verdict replaces HEAD's when it earns one. HEAD 404
   stays immediate (a dead verdict needs no second opinion; the liar list
   still covers 404-liars). HEAD 2xx stays immediate. Transport still
   returns at once.
3. `fast_probe._needs_get_fallback()`: same rule in the sweep's HEAD-only
   chain — HEAD 403/429/5xx → ranged GET. Docstring updated.

## 3. Build

All three edits from §2D landed (see the git diff); no new mechanism, no
proxy pool, no scheme downgrade, no JS challenge solving.

- `link_state.py`: `classify()` gains `status >= 500 → STATUS_EXPIRED`
  before the generic 400–599 → DEAD branch; docstring mapping table
  updated. Safe because EXPIRED is never a quarantine candidate, is
  demoted in score but not zeroed (`tiers.py:411-418`), and the sweep
  re-probes expired rows (runner selects by staleness only).
- `link_health.py` `probe()`: HEAD 403/429/5xx falls through to the
  bounded ranged GET (same headers/UA/timeout/sample cap); the GET
  verdict replaces HEAD's when it earns one. HEAD 404/410/451 and HEAD
  2xx stay immediate; HEAD transport error still returns at once.
  Head-only mode note: a 403/429/5xx HEAD with `allow_download=False`
  now returns `head_unsupported` instead of `http_<status>`, but
  `classify()` maps those statuses to EXPIRED regardless, so the ledger
  verdict is unchanged.
- `fast_probe.py` `_needs_get_fallback()`: third trigger added — HEAD
  403/429/5xx → ranged GET; docstring "Three triggers, and only these".
  Transport errors still never trigger a GET; 2xx never triggers one;
  HEAD 404 stays immediate.
- Existing tests updated to pin the new rule (each cites H4 R10):
  `test_link_state.py` (classify table 500 → expired + new 502/522
  rows; `test_completed_run_feeds_host_health` 5/2 → 6/1, `late2.test`
  moved to the answered group; `test_after_a_sweep...` dead 2 → 1,
  expired 1 → 2), `test_fast_probe.py` CASES table 500/502/503 →
  expired (501 stays None — `payload_for_head` returns None for
  HEAD-unsupported statuses before classify is reached).

## 4. Tests

New module `Fast_Fetch/test_network_intercept.py` — 22 tests, green
locally (`Ran 22 tests ... OK`, 1.1s). Four classes:

- `ClassifyTests`: 500/502/503/504/522/524 → expired; 404/410/451 →
  dead; 400/405/409 → dead; 401/403/429 → expired; transport
  (timeout/dns/tls/conn_reset/conn_error) → unverified; cross-surface
  pin — `classify(5xx)` = expired AND
  `serve_verify.classify_failure(5xx)` = "flaky"; `state_for("expired")`
  == "expired".
- `NeedsGetFallbackTests`: 403/429/500/502/503/524 → True on a plain
  host; 404/410/200 → False; transport → False; 405 → True (unchanged).
- `ProbeInterceptTests` (loopback `ThreadingHTTPServer`,
  `allow_private=True`): HEAD 403/429/503 → GET 200 → ok; HEAD 503 →
  GET 503 → `http_503` (GET verdict stands); HEAD 404 → no GET (hit
  count 0); HEAD timeout → no GET; budget — every probe ≤ 2 requests.
  Two corrections learned while writing: `probe()` ALWAYS samples the
  body when `allow_download` is on, so HEAD 200 legitimately also does
  one GET (the measurement, not a retry) — the test pins (1, 1), not
  (1, 0); and the sandbox resolver answers every name with a CGNAT
  address, so sweep tests stub `socket.getaddrinfo` to a real public IP
  (93.184.216.34 — the 203.0.113.7 doc range counts as private in
  `ipaddress`), the same stub `test_fast_probe.py` uses.
- `SweepInterceptTests` (fake connection driving `_probe_host`):
  HEAD 503 → GET 200 → ("active", 200, ""); HEAD 503 → GET 503 →
  ("expired", 503, "") — NOT dead; HEAD 404 → ("dead", 404, "") with
  zero GETs. (The sweep stores the chain error, "" for any answered
  status — the verdict + http_status carry the meaning.)

Neighbour-suite fallout, fixed as part of this slice:

- `test_host_clearance.py::test_probe_host_attaches_clearance_and_invalidates_on_403`
  failed after the change: it stubbed only `head_chain` (403), and the
  new fallback ran the REAL `get_chain`, whose verdict replaced the
  403 — so `note_challenge` never fired. This is the intended H4 R10
  semantics (a GET 200 proves the clearance still works), not a
  regression: the test now stubs `get_chain` → 403 too (challenge still
  there → clearance dies), and a new test pins the companion rule —
  HEAD 403 + GET 200 → clearance KEPT, verdict active,
  `get_fallbacks` == 1. Class comment updated (the fallback now fires
  on any host, not just liars).
- `test_ci_manifest.py::test_every_run_module_is_tracked_and_resolvable`
  fails pre-commit only ("present on disk but NOT tracked in HEAD") —
  it passes once the new module is committed; re-run after commit.

Local gate-neighbour results (all `python3 -S`, this box):

- `test_network_intercept` OK (22), `test_link_state` OK (35),
  `test_link_health` OK (57), `test_fast_probe` OK (36),
  `test_docs_invariants` OK (22), `test_serve_verify` OK,
  `test_resolver_probes` OK (18), `test_host_clearance` OK (44, was
  43 + 1 new), `test_resolved_cache` OK (93),
  `test_per_segment_retry` OK (13).
- Registration: new step `🧪 test_network_intercept.py` in
  `.github/workflows/backend-ci.yml` (125 → 126 suites by
  `grep -c 'name: 🧪'`), and `"test_network_intercept.py"` added to
  `MANDATORY_RUN` in `test_ci_manifest.py`.

## 5. CI

(TBD — branch push + Backend CI run id + verdict.)

## 6. Owed (live verification at gate time)

Press a title whose winner fails on the network, not on the link; the served
handle must come through the alternate route.
