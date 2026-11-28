# CONTINUITY.md rotation — 2026-11-28 (probe-schedule lane)

The oldest session entry (2026-11-28 http_get keep-alive) rotated out of
`Fast_Fetch/CONTINUITY.md` verbatim to bring the index back under its
12,000-byte budget (11,416 bytes at rotation time, before the
probe_all_sites schedule + domain map entry landed). Nothing deleted,
only moved.

## 2026-11-28 - http_get keep-alive (backend loop Phase 3 item 11)
- Direct fetches reuse one http.client connection per (scheme, host, port); pool cap 16, stale socket drops + retries once. WARP path unchanged (urllib). Tests: 6 new KeepAliveTests (reuse, redirect, 404, stale retry, cookie drop, pool bound); 6 wiring tests re-pointed to _fetch_once. Bench: 50 GETs 0.08s → 0.02s, 50 → 1 conns. Branch http-keep-alive.
