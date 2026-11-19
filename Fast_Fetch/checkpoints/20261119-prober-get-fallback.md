# Prober GET fallback — 2026-11-19

Branch: `muse/prober-get-fallback-20261119` (base main `71d1b91f`).
Lane: parallel, App untouched, live DB untouched.

## Measured (Oracle VM, curl, Chrome UA)
- `https://vcloud.beer/gakagn8nc1xoog-`: HEAD **403**, ranged GET **301** ->
  `https://vcloud.my/...` GET **200**. HEAD lies; GET tells the truth.
  Also a domain move the registry can learn (beer -> my).
- rozgarlelo.modiplay.xyz: 7 sampled URLs agreed HEAD/GET (200/200 live,
  404/404 + 410/410 genuinely dead) — kept on the liar list on the
  2026-11-18 audit's false-dead evidence; the GET costs one ranged
  request and can only confirm or correct.
- Scale: vcloud.beer 237,014 rows by source host, but only 21,853 are
  probed AT vcloud.beer (rest resolve to file hosts); rozgarlelo 73,777
  rows, all probed at the host. Liar match is on the probe-target host.

## Change (`Fast_Fetch/fast_probe.py`, `test_fast_probe.py`)
- HEAD first, always. Ranged GET (`Range: bytes=0-0`, <=1 KiB read,
  connection dropped) only when HEAD is 400/405/501 or the target host
  is on `KNOWN_HEAD_LIARS` and HEAD did not already prove active.
  Transport errors never trigger a GET; non-liar HEAD 404 stays dead.
- New: `HostSession.get`, `get_chain`, `chain_verdict` (head_verdict
  delegates), `is_known_head_liar`, `_needs_get_fallback`, `_record_move`
  (registry observation now shared by HEAD + GET chains); stats gain
  `get_fallbacks`. CLI contract unchanged.
- Tests: 12 new in `GetFallbackTests` (+ chain_verdict pinned to the
  mapping table); fake connection is now method-aware. Local:
  test_fast_probe 36/36, test_link_state 35/35, test_link_health 57/57.

## Live validation (VM, scratch DB only, real network)
- `run_pilot` on 2 rows: vcloud.beer short link -> **active, 200**
  (get_fallbacks=1); rozgarlelo control -> active, 200 via HEAD.
  One true registry observation recorded (beer -> my); threshold is 20.

## Note
- No `CONTINUITY.md` entry: the file is at 11,962/12,000 B, so any
  append breaches its ceiling. The trim is owed separately.
- Do NOT merge from this lane; parent merges on Backend CI green.
