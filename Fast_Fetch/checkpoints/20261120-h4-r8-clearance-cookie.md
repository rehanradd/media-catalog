# H4 R8 — clearance-cookie reuse — checkpoint (2026-11-20)

Builder lane `muse/h4-r8-clearance-cookie-20261120`, base main `78c66197` (H4 R7).
Worktree `~/workspace/h4-r8-clearance-cookie-20261120`. Main chat merges; do NOT merge here.

## Research (online first, wide phrasings + typo variants)

- `cf_clearance` is a receipt bound to IP + User-Agent + hostname (+ TLS fingerprint); changing any one invalidates it. Default lifetime 30 min (site-configurable 15 min–24 h); treat every clearance as short-lived and build for refresh. Sources: proxyhat.com Turnstile internals 2026, capskip.com cf-clearance-cookie, developers.cloudflare.com clearance concepts.
- Conservative reuse ceiling is 15 min (half the 30-min default); refreshing halfway costs one cheap solve rather than a broken batch. A dead clearance announces itself as a normal 403, a 200 carrying an HTML interstitial, or a `cf-mitigated: challenge` header — detect re-challenge by the RESPONSE, not a timer. Sources: capskip.com, dev.to bshahin cf_clearance loop.
- One jar per host/identity; sharing one jar across workers/IPs is a bot tell. Persist the jar as JSON records (name, value, domain, path, expires, secure) with mode 0600, never pickle a Session. Sources: python-web-scraping.com cookie/session guides, scrapingant.com.
- Identity = IP + cookie + User-Agent, stored and used together; an identity expires with whichever comes first, the IP lease or the cookie (PaperLite design notes, pypi.org/project/paperlite). One solve per egress, then high-speed reuse from that same egress until TTL, then re-solve.
- Cloudflare clearance has LEVELS (interactive > managed > non-interactive); a low-level clearance will not bypass a higher-level challenge; Precursor in Armed mode can invalidate early even before TTL (developers.cloudflare.com).
- vcloud search was noise (a malware report about the .beer TLD; solver-service ads). Nothing usable for the resolver; the `api/index.php?link=` route is NOT in our code (see audit).

## Audit (file:line evidence)

1. NO clearance is stored anywhere today. `fast_fetch.py` builds a fresh in-memory
   `http.cookiejar.CookieJar()` per unwrap call (lines 3588, 3650, 3982) and discards
   it; `link_health.probe()` sends no cookies; `fast_probe.head_chain` sends only
   `base_headers`. Nothing is ever solved today, so there is NO legitimate clearance
   to reuse — this slice builds the shared jar + capture, per the brief's fallback.
   Never solve a challenge on the user's behalf; only reuse clearances the system
   legitimately obtained (its own successful responses) or an operator placed.
2. Challenge detection already exists: `site_scout.CHALLENGE_MARKERS` (line 207) +
   `is_challenge_interstitial()` (line 795, "detection only, never bypassed");
   `current_hosts.classify_probe()` maps 403 + markers to failure `"challenge"`;
   `bridge_tracer` stops at `"blocked: challenge"`.
3. `unwrap_vcloud` (`fast_fetch.py:3129`) fetches the landing page via `http_get`;
   non-200 returns the original URL unresolved. No `api/index.php` route exists
   anywhere in the tree (grep: zero hits) — the brief's bypass-route claim is NOT
   wired in the resolver. The store does not depend on it.
4. The User-Agent is NOT pinned consistently: `link_health.USER_AGENT`
   (env `RADD_USER_AGENT` or default, line 109) vs `fast_fetch.USER_AGENT`
   (line 407) vs `host_registry.USER_AGENT`. Clearance is bound to the UA that
   earned it, so the store MUST record the UA with the cookie and replay with
   that exact UA.
5. Two probe implementations: `link_health.probe`/`probe_many` (via
   `link_state.default_prober` → `propagate`; `catalog_v2` samples; iptv importer)
   and `fast_probe.head_chain` + `HostSession` (the sweep loop, high volume).
   The play path is `viewer`/`api_v2` → `fast_fetch.resolve_live_source`
   (`viewer.py:7060`, `api_v2.py:1468`) → unwrap_* via `http_get` (130 call sites).
6. `ProbeResult` carries no headers/body — capture/invalidation signals must be
   computed inside `link_health._finalize` (it holds the final `_Exchange`:
   status/headers/body) and surfaced as new fields. `_raw_request` lowercases
   header names into a dict, so duplicate `Set-Cookie` headers collapse to the
   last one — documented limitation, acceptable (cf_clearance is one header).
7. `fast_probe.head_chain` returns only `(status, error, final_url)`; response
   headers die inside `HostSession.head`. The sweep wiring therefore attaches the
   stored Cookie and invalidates on 403/503, but capture is fed by the resolver /
   `probe_many` / operator placement (same DB). A 403/503 from a host holding a
   stored clearance means the clearance earned nothing, so dropping it costs at
   most one re-earn.
8. The final-file probe inside `resolve_live_source` (`_stage2_probe`) probes the
   resolved CDN/file URL, not the challenged bridge host — out of scope for this
   slice; a follow-up can thread it if a challenged host ever serves files directly.
9. Store placement: same DB as `resolved_cache` (the caller's conn). Pattern to
   mirror: `resolved_cache.py` `_DDL_STATEMENTS` tuple, `init_*` (no
   `executescript`, no commit), `_ensure_schema` with `_SCHEMA_OK` probe, never
   raise on concurrent create.
10. CONTINUITY.md ceilings: 11,920/12,000 bytes, 92/120 lines; checkpoints hold
    121 session files + README. This file makes 122 — rotate the oldest history
    line and update the count when logging, and keep the new entry tiny.

## Design

- New `Fast_Fetch/host_clearance.py` (stdlib only): table `host_clearance`
  `(host, cookie_name, cookie_value, user_agent, obtained_at, expires_at,
  source IN ('response','operator'), last_used_at, use_count)`,
  PK `(host, cookie_name)`. Exact hostname only (no www-stripping, no suffix match).
- Effective validity: `now < min(expires_at or +inf, obtained_at + 900)` — the
  900 s (15 min) conservative ceiling from research, even if the cookie says longer.
- Caps: max 64 hosts, max 8 cookies/host (prune least-recently-used). No
  user/device/URL columns — host + cookie + UA only.
- Capture allowlist (known clearance names): `cf_clearance`, `__cf_bm`,
  `datadome`, `_abck`. Operator placement (`place_clearance`) accepts any name.
- Functions (conn-based, never commit, capture/note paths never raise):
  `init_host_clearance`, `_ensure_schema`, `cookie_header_for`,
  `note_response` (capture on 200/206 without challenge markers; invalidate the
  host on a challenge response), `is_challenge_response` (lazy `site_scout`
  markers + `cf-mitigated: challenge` header + 403/503 + markers),
  `parse_set_cookie`, `extract_clearance_cookies`, `place_clearance`,
  `invalidate_host`, `prune`, `note_use`.
- Path adapter `HostClearance(db_path)`: short-lived connections per call
  (busy timeout + one locked-retry), never raises — a clearance failure must
  never break a probe or a resolve.
- Wiring (all additive, all optional params, defaults preserve old behaviour):
  - `link_health.probe(..., cookie_header=None, user_agent=None)`,
    `probe_many(..., cookie_headers=None)`; `ProbeResult` gains `challenge: bool`
    and `set_cookie: tuple` computed in `_finalize` (lazy import of the store's
    helpers, two-form like the rest of the tree).
  - `link_state.default_prober(..., clearance=None)`: per-item Cookie header +
    `note_probe` after the batch.
  - `fast_probe._probe_host(..., clearance=None)` (+ `run_apply`/`run_compare`
    build the adapter from `db_path`): attach per-host Cookie (+ pinned UA) to
    `base_headers`; invalidate the host on 403/503. `head_chain` signature
    untouched (its 3-tuple is pinned by `test_fast_probe.py`).
  - `fast_fetch.http_get(..., clearance=None)`: attach stored Cookie (+ pinned
    UA); capture/invalidate from the response (incl. `HTTPError` 403/503 path).
    `resolve_live_source` builds the adapter from `DB_PATH` and threads it to
    the protected-host unwraps only: `unwrap_vcloud`, `unwrap_hubcloud`,
    `unwrap_direct_cloud`, `unwrap_hubcdn` (incl. `_hubcdn_page` attach).
    `unwrap_greenmotors`/`unwrap_fastdl` untouched (no challenge evidence).
- Dropped: solving challenges (never — no JS engine, no solver service, no
  paid service); cross-IP replay (the binding forbids it; single egress);
  persisting a pickled session (fragile, executes on load); trusting the
  cookie's own Expires alone (capped at 15 min); a global/shared jar across
  hosts (one jar per host — cross-host leakage is a bot tell).

## Build log

- CORRECTION to the design above (found while wiring, 20 Nov): there is NO
  `run_apply` in `fast_probe.py` — the apply engine is `run_pilot`, and the
  dry-run engine is `run_compare`. The adapter is built in `run_pilot` only;
  `run_compare` gets none (a dry run writes nothing, and attaching a cookie
  would change what it measures). `head_chain`'s 3-tuple is untouched.
- New `Fast_Fetch/host_clearance.py` (stdlib only): `host_clearance` table
  `(host, cookie_name, cookie_value, user_agent, obtained_at, expires_at,
  source, last_used_at, use_count)`, PK `(host, cookie_name)`; 15-min reuse
  ceiling (`CLEARANCE_MAX_AGE_S = 900`, `min(expires_at, obtained_at + 900)`);
  capture allowlist `cf_clearance, __cf_bm, datadome, _abck` on 200/206 only,
  with the UA that earned it; challenge detection by response
  (`cf-mitigated: challenge` header or `site_scout` markers in the body —
  a bare 403/503 with no marker is NOT a challenge); caps 64 hosts / 8
  cookies per host (LRU); `HostClearance` path adapter (short-lived conns,
  one locked-retry). Every store function never raises and never commits.
  Two fixes found by the tests: `parse_set_cookie` takes `now` (a `Max-Age`
  is relative to when the response arrived, not to read time), and
  `normalize_host` validates the hostname (labels/IP literal only —
  `urlsplit` alone accepts spaces).
- `link_health.py`: `ProbeResult` gains `challenge: bool` + `set_cookie:
  tuple` (computed in `_finalize`, lazy two-form import, degrades to no
  signal); `probe(..., cookie_header, user_agent)` replays a stored
  clearance with its pinned UA; `probe_many(..., cookie_headers)` attaches
  per-host headers. `test_link_health.py` contract test updated for the two
  new fields (57 tests).
- `link_state.py`: `default_prober(..., clearance=None)` attaches the
  Cookie + pinned UA per item and records results back via `note_probe`;
  `propagate` builds the adapter from the conn's path (`_clearance_for_conn`,
  PRAGMA database_list; in-memory/absent module → None) for real runs only.
- `fast_probe.py`: `_probe_host(..., clearance=None)` reads the host's
  clearance once per host, attaches it (+ pinned UA) to `head_chain` and
  the GET fallback, invalidates on a 403/503 verdict; `run_pilot` builds
  `HostClearance(db_path)` (guarded); `run_compare` untouched.
- `fast_fetch.py`: guarded `_hclear` import + `_clearance_store()`; `http_get
  (..., clearance=None)` attaches on the request, captures from a clean 200
  (noted against the FINAL url — the cookie belongs to the host that set
  it), invalidates on a challenge HTTPError on the final attempt only
  (retries keep the same cookie); the mirror-probe retry forwards the
  store. `resolve_live_source` builds one store and threads it to
  `unwrap_vcloud`, `unwrap_hubcloud`, `unwrap_direct_cloud`, `unwrap_hubcdn`
  (+ `_hubcdn_page`: attach, merge the stored header back into the returned
  cookie string so the button hop keeps it, capture from the jar).
  Out of scope, unchanged: `extract_post_data` / scraper unwrap calls,
  `unwrap_greenmotors` / `unwrap_fastdl`, the importers-module
  `hr.unwrap_hubcloud`, `link_sweep.py` (a different tool, not the live
  sweep — the live sweep is `link_state_runner` → `propagate`).
- New `Fast_Fetch/test_host_clearance.py` (43 tests): store rules, adapter,
  probe/probe_many/default_prober/fast_probe/http_get/unwrap wiring
  (loopback stub server + stubbed `urlopen`/`http_get`/opener, no network),
  press-banking coexistence (`resolved_cache` table in the same DB).
- CI: gate step added after `test_per_segment_retry` (124 🧪 suites) +
  `test_ci_manifest.py` MANDATORY_RUN. The manifest test fails locally
  until the new file is committed (it requires the module tracked in HEAD)
  — expected; it goes green on the pushed branch.
- Local: new module 43/43 OK; `test_link_health` 57/57, `test_link_state`
  35/35, `test_fast_probe` 36/36, `test_resolved_cache` 93/93,
  `test_shared_cache` 33/33, `test_direct_file` 17/17,
  `test_per_segment_retry` 13/13, `test_hubcdn_unwrap` OK,
  `test_host_classifier` OK, `test_fast_fetch_domains` OK,
  `test_serve_verify` OK, `test_tiers` OK, `test_edge_phase1` OK,
  `test_link_state_runner` OK, `test_evidence_writeback` OK,
  `test_prefetch_worker` OK. NOT merged — parent merges; no live deploy.
  Owed: live verify at gate time — a protected-host title resolves without
  re-paying clearance (press a vcloud/hubcloud title; second resolve must
  reuse the stored cookie).
