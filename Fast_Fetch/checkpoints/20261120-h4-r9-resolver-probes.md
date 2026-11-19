# H4 R9 — resolver probes (2026-11-20)

Branch `muse/h4-r9-resolver-probes-20261120`, base main `8f7948d2` (H4 R8 merged).

## Research (before building, wide phrasings + typos)

1. Surge downloader `ProbeServer` (deepwiki.com/surge-downloader/Surge/5.6-state-persistence):
   probe is a `GET Range: bytes=0-0`, up to 3 attempts; 403/405 retry without
   Range; mirrors probed concurrently, only range-supporting mirrors valid.
   Lesson: a ranged read is the liveness proof, and a 403/405 is a reason to
   change the request shape, not to declare death.
2. Media URL Checker (toolblink.pro/media-url-checker): HEAD first with a GET
   fallback for servers that refuse HEAD; every candidate address is requested
   before it is offered, so the result is what is genuinely there.
   Lesson: never offer an address you have not knocked on.
3. MnogoReels `check_video_source` (glama.ai): a usability check is cached
   10 minutes; typical check 2–15 s. Lesson: a probe verdict has a short
   freshness window of its own — minutes, not hours.
4. Range-contract write-ups (nikodev1.medium.com, featherdrive.app,
   media-processing-workflows.com): HEAD for `Accept-Ranges`, then a small
   ranged slice; 206 proves liveness, a 200 that ships the whole file means
   ranges were ignored. Lesson: the probe must read the status + protocol,
   not just "did it answer".

No library fits: this is stdlib-only code, and the prober already exists
(`link_health.probe`, `serve_verify.verify_final_url`). The slice reuses it.

## Audit — every place a resolved URL is banked or served

A1. **No dead resolution is banked — verified, not assumed.** Every bank path
    goes through a probe: `resolve_live_source` Stage-1 cached branch
    (`fast_fetch.py:7555` `_ch_probe`), Stage-2 cached branch (`:7613`
    `_stage2_probe`), fresh unwrap (`:8461` — or the unwrapper's own
    Range-probe via `_verdict_from_unwrap` `:7228`, which is a real probe:
    the unwrapper read the first 512 bytes with the host's required
    headers, so re-probing bare would only fail a proven link), stale
    fall-through (`:8953`); `verify_candidate` (`serve_verify.py:1107,1131`)
    probes before banking; the pool fill resolves through the same gate
    (`prefetch_worker.py:554`); `record_resolved_cache`
    (`resolved_cache.py:334`) refuses stale envelopes.
A2. **The gap is at SERVE time, not bank time.** The stream route
    (`viewer.py:8476`) and the download route (`viewer.py:8666`) 302 a
    banked URL with zero liveness evidence for up to `reverify_after`:
    6 h for temporaries, **24 h for permanent direct files**
    (`HEURISTIC_CAP_PERMANENT_SECONDS`, `resolved_cache.py:82`), minus the
    60 s margin. The banked row's only freshness is a clock horizon, not a
    probe verdict. Meanwhile the tier-verify path already treats a banked
    verdict older than `VERIFIED_FRESHNESS_S = 600` s as un-fresh
    (`serve_verify.py:290,1051`). The two laws disagree by up to 144x.
    A banked winner that dies inside its window keeps being served until
    `reverify_after`, unless 3 client reports (`MAX_FAIL_COUNT`) or a fresh
    negative-cache record intervene.
A3. Checked, not a gap: `verify_candidate` step 1 already uses the 600 s
    fresh law; `api_v2.resolve_target` (`api_v2.py:1468`) serves a
    `static_url` only for non-ephemeral Layer-1-classified live links —
    projection-layer decision, out of the resolver's scope, not built here.
A4. `_preresolve_is_warm` (`viewer.py:6940`) only decides whether to spawn
    a warm thread; a warm row older than 600 s reads "warm" and skips
    warming, which is fine — the press re-probe below is the safety net.

## Build

`serve_verify.recheck_banked(hit, *, want="watch", conn=None, now=None,
prober=None)` — one capped re-probe of an aging banked row before it is
served, reusing the existing prober (`verify_final_url`: `PROBE_TIMEOUT_S`
5 s, ranged GET ≤ 1 KB, HEAD-liar fallback, H4 R8 clearance-aware):

- row age ≤ 600 s (`VERIFIED_FRESHNESS_S`): serve at once, no probe —
  the instant 302 for repeat presses is preserved;
- older + probe verifies (playable protocol, non-HTML, non-empty; download
  additionally requires a file, not a playlist): serve, and refresh the
  row (`resolved_at = now`, `reverify_after` recomputed from the same
  measured expiry / category law) so the next press is instant again;
- older + probe fails with endpoint-answered evidence (HTTP status ≠ 0,
  expired URL, webview, empty file): do NOT serve — the caller treats it
  as a cache miss and the live resolver re-resolves, probes, and fails
  over to the next verified candidate (H4 R7 machinery). The recheck
  records nothing itself; the resolver's own evidence path records once;
- older + probe fails with no HTTP answer (transport / timeout / DNS /
  TLS): serve the banked row anyway (fail-open, Session-107 principle —
  a network blip must not break playback) and do NOT refresh it, so the
  next press re-probes.

Wired into the stream route and the download route, inside the same
`with get_db()` block as the lookup, so the refresh rides the lookup
connection. `verify_candidate` needs no change (already 600 s).
`_preresolve_is_warm` unchanged.

Budget: at most one 5 s capped probe per press, only on a row older than
600 s. Cold first press unchanged (6–13 s resolve path untouched).

## Tests

`Fast_Fetch/test_resolver_probes.py` (offline, in-memory SQLite, injected
prober — no network): fresh row served without a probe; aged row + probe
OK served + `resolved_at`/`reverify_after` refreshed; aged row + 404 not
served; aged row + transport failure served but not refreshed; aged
playlist on download refused even when the probe passes; aged file on
download served; second press inside 600 s makes no probe; the resolver
still never banks a dead resolution (existing gate behaviour pinned).
Registered in `backend-ci.yml` (125 🧪 steps) + `test_ci_manifest.py`
`MANDATORY_RUN`.

## Owed

Live verification at gate time: press a title whose banked winner is dead
at resolve time (banked > 10 min ago, file since pulled); the served
handle must be a verified live one, and a repeat press inside 10 min must
still 302 instantly.
