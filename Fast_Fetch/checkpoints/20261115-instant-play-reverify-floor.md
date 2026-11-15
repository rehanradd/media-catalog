# 2026-11-15 — instant play: the press banks, but the row is born expired

## Probe (06:05–06:08 PKT, Oracle VM, read-only + two real presses)

The audit's "two presses, zero new rows" was a measurement artifact: the
play bundle's displayed `link_id` (3070098) and the handle's source_id are
different id spaces. The handle decodes (`redact.parse_token`, in-process)
to **2030343**, and press 1 DID bank it — jit, category A, kind file,
resolved 01:05:10 UTC. The real defect is the row's horizon:
`expires_at == resolved_at` (01:05:10) and `reverify_after ==
resolved_at − 60 s`. `_compute_reverify_after` takes
`min(expires_at, resolved_at + cap)` minus `REVERIFY_MARGIN_SECONDS` (60 s),
so a URL whose measured expiry is already at/past the resolve instant banks
a stillborn row the cache can never serve (`now < reverify_after` never
holds). Press 2, same handle, re-resolved live in 9.49 s because the row
banked 11 s earlier was already expired. The other recent jit rows are
healthy (~6 h horizons); only the googleusercontent row is stillborn. The
running process (PID 2423393, started 13:32:45 Nov 14, 7 s after
viewer.py's mtime) already runs the banking code — no restart is the fix;
the math is.

## Fix (this branch)

`resolved_cache._compute_reverify_after` floors the result at
`resolved_at + MIN_USABLE_REVERIFY_SECONDS` (120 s): a resolve that just
proved the URL plays earns a short usable window instead of a row dead on
arrival. 120 s, not 300: the measured-expiry test pins reverify at
`expires_at − 60 s` for a 300 s expiry (240 s), and a 300 s floor would
break its "must not serve inside the 60 s death margin" assertion. New
test `test_expiry_at_resolve_time_still_banks_a_usable_row` covers
`expires_at == now` and `expires_at` an hour in the past.

## Verification

`python3 -S Fast_Fetch/test_resolved_cache.py`: **85 tests, OK** (84 on
main 3909e457 + the new one; the new test confirmed present by `-v`).
Import check clean. Full backend gate is CI's job on the branch.

## Also measured in the same probe (not fixed here)

- v1 vs v2 recall for "Salaar": v2 `total_estimate` 7 in 0.067 s; v1
  `total` 1 in 3.10 s cold. v1 recall is genuinely broken — find why v1
  loses the other 6 rows. Recorded in
  `~/workspace/instant-play-audit-20261115.md` Correction 3.
- The bare `{"release_id": ...}` play call silently translates through the
  legacy map (cat 1318132 → 1406771 download-only); `legacy_release_id:
  false` is mandatory for v2 ids.

## NOT live

Merged to main (rebased onto 6969fda0, CI green on the branch); NOT
deployed. The live tree moves only through the gate (backup + live
test); the slim backup was running when this was written.
