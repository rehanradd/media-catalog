# 2026-11-24 — Bank defects (a) + (b) from the resolver latency audit

Branch `resolver-bank-defects` from `db753c56` (vidmoly live).
Agent: bank-defects worker (ba6818da, main chat). Task: fix the two bank
defects in the latency audit (`~/workspace/resolver-latency-20261123.md`,
"Banked press times"). No other branch touched; moviehax → kmhd →
hubcloud → FIX1 → FIX2 queue untouched.

## What the audit measured (live, Oracle VM egress)

- (a) fsl_r2 release 3122427: press 1 = 404 in 1,262 ms, press 2 = 404 in
  25 ms — the dead banked URL was served fast instead of re-resolving.
- (b) mxdrop release 2221661: press 1 = 302 in 8,404 ms, press 2 = 302 in
  3,755 ms — the 2nd press still re-resolved/re-probed instead of serving
  the banked row.

## Root causes (proven, not guessed)

- (a) The drive re-mints fine RIGHT NOW: a read-only live call
  `unwrap_hubcloud('https://hubcloud.mov/drive/nalzrhjt7y7bqlj',
  verify=True)` returns status=active with a fresh R2 URL. Press 1's 404
  was a code-path failure, not a dead drive. The mechanism: a fresh
  negative-cache record (`last_failed_at` < 12 h at the current resolver
  version) makes `resolve_live_source` skip the copy's own unwrap entirely
  (`if _negative_cache_hit: skip straight to sibling failover`) — and the
  record most often names an expired token, while the re-mint is the cure.
  The probe_failed → unwrap_failed overwrite in the fall-through was
  checked and is NOT the cause: a successful resolve clears the record
  and a failed unwrap overwrites it anyway, so the recording is not the
  poisoning — the skip is.
- (b1) Stillborn banked row: the pkembed cache row for link 4526226 has
  `expires_at` 1795161909, ~3.5 days BEFORE its `resolved_at` 1795463603,
  so `_compute_reverify_after` floors the horizon to resolved_at + 120 s.
  Mechanism: the fresh mint measured no window — the verdict's
  `expires_in` was None and the bare `probe_url_ttl` 403s on mxcontent
  (reason "unreachable", not "no_directive") — so no expiry branch ran
  and the stale ledger `expires_at` rode into the answer.
- (b2) The bare re-probe 403s on mxcontent (proven by the mxdrop branch
  comment in `fast_fetch.py`), so an aging mxdrop row fails
  `recheck_banked` on every press and falls through to a full cold
  resolve. The 4526228 row was 5 h old at press time, so press 2 could
  never serve it fresh.

## Fixes (narrow, press-path only)

- (a) `fast_fetch.py` `resolve_live_source`: a negative-cache hit on a
  TEMPORARY copy with a bridge to mint from still runs the fresh unwrap
  (cached probes stay skipped — `needs_refresh` is already True). Bridge
  = a recognised bridge URL (`is_bridge_url`) or a `source_url` distinct
  from the stored `final_url`. A permanent copy, and a temporary copy with
  no bridge (the stored URL is the token itself), keep the skip. If the
  re-mint fails, the old record stands (the fall-through does not refresh
  it) and sibling failover still runs. Cost, documented in the comment:
  a truly-dead temporary copy pays one unwrap per press for the 12 h TTL.
- (b1) `fast_fetch.py` fresh-url success branch: after the expiry block,
  if the answer's `expires_at` is already in the past, clear it
  (`record_link_expiry(..., clear=True, observed_via="stale_expiry")`).
  A successful resolve just proved the URL plays NOW (Stage-2 gate
  passed), so a past expiry is a stale parse, not a dead token. The
  answer falls back to the documented 6 h temporary ceiling; the
  serve-time re-probe catches the URL when it actually dies.
- (b2) `serve_verify.py` `recheck_banked`: a 403 on a signed URL whose
  own window is still open is a header refusal, not a dead file — serve
  the row (reason "transport") and do NOT refresh, so the next press
  re-probes. A 403 with no window, or a window already closed, is still
  refused; a 404 is refused whatever the window says. Docstring updated.

## Tests

- New `Fast_Fetch/test_bank_defects.py` (9 tests, offline): 3 fix tests
  (re-mint on negative hit; stale expiry cleared; 403 + open window
  serves) + 6 guard/control tests (permanent copy still skips; temporary
  without bridge still skips; fresh signed expiry kept; 403 + closed/no
  window refused; 404 + open window refused). Regression check: with the
  two code files stashed, exactly the 3 fix tests fail and the 6 guards
  pass; with the fixes, 9/9 pass.
- CI step added to `.github/workflows/backend-ci.yml` after
  `test_resolver_probes.py` (gate 160 → 161 suites).
- Local: bank_defects 9/9, resolver_probes 18/18, serve_verify 68/68,
  resolved_cache 95/95, streamwish_dispatch 8/8, per_segment_retry 13/13,
  host_ranking 24/24, direct_file 17/17, download_endpoint 31/31,
  link_health 57/57. api_v2 / database / redact batch was running at log
  time (see parent for the result).

## Status at log time

- Fixes + tests + CI step written; branch + push + Backend/Android CI
  still owed (parent owns the push route). Pre-push checks run after the
  commit (the script diffs against origin/main, so it needs the commit).
- Known limitation, NOT fixed here: a TRANSIENT unwrap failure still
  writes `unwrap_failed` and the copy is re-minted on every press for
  the 12 h TTL (no retry cooldown — a cooldown would make an immediate
  re-press 404 again, which is what the audit asked to stop). If the
  re-mint cost on dead temporary copies ever shows up in press latency,
  that is the follow-up.
- Do NOT hammer HubCDN l.php (rate-limited). No live presses were made
  during this work (read-only unwrap call + read-only DB queries only),
  and none were made during the moviehax gate.
