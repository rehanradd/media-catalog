# 2026-11-27 — Self-healing Branch 2: hook the media-bytes checker in

Branch `selfheal-b2-hook-checker` from main `64fbc289` (Branch 1 landed).
Design: `~/workspace/backend-self-healing-design-20261127.md` §2 (Branch 2 line).
Parent task: hook `verify_media_bytes` / `probe_media_bytes` (Branch 1 checker,
unused) into the stage-2 probe, `record_resolved_cache`, and pool banking.

## What changed (production, 3 files)

- `Fast_Fetch/fast_fetch.py`
  - `_verdict_from_unwrap`: refuse when the unwrapper's verdict
    `content_type` is `text/*` or `application/xhtml+xml` (failure
    `html_shell`), before the playlist test so an HTML error page at a
    `.m3u8` URL is refused too. Zero extra requests, zero auth risk
    (the unwrapper already fetched the final URL; a bare re-probe would
    break gofile 302-without-Auth and streamwish 403-without-Referer).
  - `unwrap_filemoon` verify probe: `Range: bytes=0-0` → `bytes=0-4095`
    and gate acceptance on `verify_media_bytes(body, ctype)` instead of
    the content-type-only `is_master` / `is_file` test. An HTML shell
    served as `application/octet-stream` is now refused; real filemoon
    media (video/*, HLS master) still passes.
- `Fast_Fetch/resolved_cache.py`
  - `record_resolved_cache`: bank-time backstop — probe the (fragment-
    stripped) URL via new module-level `_media_probe` (lazy
    `fast_fetch.probe_media_bytes`, timeout 10) and refuse to bank on
    failure (return `False`, log to stderr). Always probed, no skip
    flag (serve_verify re-banks under a different `final_url`, so a
    resolve-step flag cannot be trusted). A transport failure also
    refuses (unproven, not proven-dead); refusing only skips the cache.
  - Return type widened to `dict | bool | None` (False = refused bank).
- `Fast_Fetch/prefetch_worker.py` (`_resolve_one`)
  - A bank refusal (`stored is False`) counts as a failed row
    (`"failed"` / `"failed_gated"`, backoff set), same as
    `no_playable_source`. The `None` path (no url / mega / stale) is
    unchanged. This covers pool banking (sweep + fill) and, via
    `record_resolved_cache`, the standalone `reresolve_pool.py` ops
    script (not in the repo).

## What did NOT change

- Resolver behaviour for real media; Branch 3 (parked verdict) and
  Branch 4 (site recheck) untouched; no scraper-lane or panel-lane work.
- `_bank_hubcloud_siblings` still writes the LEDGER directly (not via
  `record_resolved_cache`) — residual: sibling URLs come from the
  gamerxyt page, the winner is probed via `probe_range`, siblings are
  not individually probed. Out of Branch 2 scope; noted, not fixed.
- No `RESOLVER_VERSION` bump: the change only ADDS refusals for HTML,
  it does not change what passes.

## Tests (new + fakes)

- `test_fast_fetch.py::TestStage2MediaBytesGate` (7 tests): the named
  `test_html_as_mkv_regression` (v2.0.0 incident) + text/plain, xhtml,
  HTML-at-.m3u8 refusal; real mp4 / HLS / octet-stream acceptance.
- `test_filemoon_unwrap.py`: `test_html_master_as_octet_stream_is_not_active`
  (HTML body as octet-stream → not active). Fix found while writing it:
  assigning to a fake instance's `__call__` does not override the class
  method — the wrapper must be a plain function, not an instance attr.
- `test_resolver_stage2.py::MediaBytesGateIntegrationTests`: fake gofile
  unwrapper returns status active + text/html at a .mkv URL →
  `resolve_live_source` answers `no_playable_source` (v2). Verified the
  test exercises the new gate: with the pre-Branch-2 gate restored the
  same fixture resolves success with the HTML URL.
- `test_resolved_cache.py::TestBankRefusesNonMedia` (3 tests): HTML-as-
  .mkv refused (returns False, no row), transport failure refuses, real
  media banks.
- `test_prefetch_worker.py::test_refused_bank_counts_as_failed_row`:
  refused bank → failed=1, no row, backoff holds the link.
- `resolved_cache._media_probe` is the seam: 11 test files fake it as
  passing in `setUpModule` (`test_resolved_cache`,
  `test_prefetch_worker`, `test_api_v2`, `test_bank_defects`,
  `test_cursor_pagination`, `test_direct_file`, `test_host_classifier`,
  `test_host_ranking`, `test_per_segment_retry`, `test_resolver_probes`,
  `test_serve_verify`, `test_redact`) so the offline suite never does
  live network. Without the fake, the bank probe made `test_redact`
  82 s with 4 timeout errors (base 68 s, clean) — the probe inside the
  request handler starved the 10 s test timeouts; with the fake it is
  66 s, all OK. Same cause for the hubcloud `expires_at` drift failures
  in the combined run (module-level R2 URLs fixed at import, 120 s
  delta) — fixed by the faster redact suite, trio 178/178 OK.

## Local verification (this clone, before push)

- `test_fast_fetch` + `test_filemoon_unwrap` + `test_resolver_stage2`:
  77/77 OK. `test_resolved_cache`: 116/116 OK. `test_prefetch_worker`:
  28/28 OK. `test_bank_defects` + `test_direct_file` +
  `test_cursor_pagination` + `test_host_classifier`: 124/124 OK.
  `test_api_v2` + `test_host_ranking` + `test_per_segment_retry` +
  `test_resolver_probes` + `test_serve_verify`: 248/248 OK.
  `test_redact`: 127/127 OK. Trio
  (`test_redact` + `test_shared_cache` + `test_hubcloud_oneround`):
  178/178 OK. Full 229-suite gate running in background
  (`/tmp/b2-gate-run.sh`, log `/tmp/b2-gate-run.log`) at commit time.
- `CONTINUITY.md` NOT edited: it is at 11,980 / 12,000 bytes, so any
  addition breaks `test_docs_invariants.py`. This file is the log.

## Status

NOT merged, NOT landed. Next: commit on `selfheal-b2-hook-checker`,
push via the bundle + VM-clone route, Backend + Android CI green on
the exact tip, then the full gate (sweep stop → merge check + stable
counts → `.backup` → counts exact → integrity → `gh api` PATCH main →
live-tree ff + HEAD assert → service restart → live tests from the
Oracle VM on 8080 → sweep relaunch via wrapper → panel-push clone
sync → branch deleted).
