# CONTINUITY.md rotation — 2026-11-28 (dead-host rebase)

The oldest session entry (2026-11-28 probe schedule + domain map)
rotated out of `Fast_Fetch/CONTINUITY.md` verbatim during the dead-host rebase onto the sweep-hardening tip, to bring the index
back under its 12,000-byte budget (11,989 bytes before the dead-host entry landed). Nothing deleted, only moved.

## 2026-11-28 - probe schedule + domain map (Phase 1 items 3 + 5)
- Item 3 ALREADY DONE, no code: radd-domain-probe.timer (daily 10:30) → domain_recheck.py --probe-stale → probe_all_sites + resolve_site chain, live since Branch 4. Fired today 10:40: 8 down, 1 resolved (world4ufree). The plan premise was stale.
- Item 5: domain map at checkpoints/20261128-domain-map.md — 33 sites (23 active, 8 down, 2 unverifiable), verified vs live registry + posts (461,611); 6 moves since the repo copy froze 23 Nov; down sites hold 79,093 posts (themoviebox first). Follow-ups: re-verify the 8 down (item 8); repo copy 5 days stale. Branch probe-all-sites-schedule (docs only — parent owns the landing).
