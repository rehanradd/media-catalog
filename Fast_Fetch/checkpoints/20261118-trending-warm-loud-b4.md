# Session 2026-11-18 — B4 trending warm-failure loudness `[TRENDING-WARM-LOUD]`

- Branch: `muse/trending-warm-loud-20261118` (from main `7eec5a00`, rebased onto `7dacfe1c` 04:5x PKT 18 Nov after the Settings merge).
- Fixup 04:5x PKT 18 Nov: backend CI run 44199678272 FAILED on `test_docs_invariants` only — CONTINUITY claimed 108 session files, checkpoints held 109, and CONTINUITY was 12,061 B over the 12,000 B ceiling. Count fixed to 109 (106 dated + 3 undated); index trimmed to 11,957 B (99 lines) by compressing the B4 bullet and the three 2026-11-02 lane lines (none of them trunk-added lines, verified against the `dc73ac7`→`4fe6f65` set). Local re-check: `test_docs_invariants` OK, `test_home_rails` 61/61.
- Item: 15h-plan B4 — "Trending warm-failure loudness — the residual silent failure; make it loud."
- Finding: `_trending_warm()` (2026-11-15) was best-effort with one stderr line per failed shape, and a total failure (all three shapes) printed nothing at all — no status, no retry, no surface the operator or the self-check loop could see. The first trending hit for an unwarmed shape then cold-builds under the 10 s deadline and may 503, exactly the failure the warm exists to prevent.
- Fix (`Fast_Fetch/viewer.py`):
  - `_TRENDING_WARM_STATUS` module dict — `warmed`, `failed` (shape → error), `attempts`, `at` — rewritten on every warm run.
  - Per-shape failure logs `TRENDING-WARM-FAILED shape=... error=...`; a summary line `TRENDING-WARM-FAILED n/3 shapes unwarmed: ...` states the consequence (first hit cold-builds, may 503).
  - `_trending_warm(content_types=...)` narrows a run to a subset, for the retry.
  - `_trending_warm_retry(delay_s=60)` — one background retry of the failed shapes only; no-op when the warm already succeeded (attempts > 0, no failures); retries all three only when no warm ever recorded (wholesale failure). Logs `TRENDING-WARM-RETRY-OK` / `TRENDING-WARM-RETRY-FAILED`. Never raises.
  - `start_server` prints the loud `[!] TRENDING WARM FAILED: ...` banner line and spawns the retry on a daemon thread when any shape failed; also spawns it on a wholesale warm failure. Serving still starts either way; the request path's on-demand cold-build fallback is unchanged.
- Tests (`Fast_Fetch/test_home_rails.py`, +4 in `TrendingTests`):
  - `test_warm_failure_is_recorded_and_loud` — one shape fails, others warm, status + marker pinned.
  - `test_warm_total_failure_returns_empty_and_stays_loud` — all three fail, `[]` returned, marker present (the old code printed nothing).
  - `test_warm_retry_recovers_failed_shapes` — transient failure, retry with delay 0 recovers, cache ends up with all three shapes.
  - `test_warm_retry_is_a_noop_when_nothing_failed` — no rebuild when the warm already succeeded.
- Local gate: `python3 -S test_home_rails.py` — 61 tests, OK (7.8 s). Import + py_compile clean.
- Gate deploy: OWED after merge — slim backup (`backup_manager.py`, since 03:41) still running at branch time, so no gate backup copy was started concurrently. Deploy when the backup finishes, no sweep running, disk checked.
