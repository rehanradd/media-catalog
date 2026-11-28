# CONTINUITY.md rotation — 2026-11-28 (hdr-banding lane)

The three oldest session entries (2026-11-26 P2 cast backend; 2026-11-27
self-healing Branch 5 + Branch 6) rotated out of `Fast_Fetch/CONTINUITY.md`
verbatim to bring the index back under its 12,000-byte budget (12,172 bytes
at rotation time, after the HDR banding fix entry landed). Nothing deleted,
only moved.

## 2026-11-26 22:23 PKT - P2 cast BACKEND - GET /api/v1/person
- TMDb person lookup: photo/bio/birthday/known_for; 24 h cache; 404/503. Tests: test_person_endpoint.py (19, offline). CI 224.
- nginx location beside facets; WARP required for TMDb from Oracle VM. U2 49/50, U3 5/5. Detail: cast-backend-patch/.

## 2026-11-27 - Self-healing Branch 5: expiry everywhere + negative-cache clear on host move
- `record_resolved_cache` derives expiry off the banked URL when the answer carries none (R2 signed, Google 900 s; stillborn dropped, permanent NULL); failover winner in `resolve_live_source` banks its own derived expiry on the winner row. `resolved_cache.clear_host_negative_cache` clears the moved host's ledger negative-cache + fail_count; sweep driver clears after a batch with applied moves. Tests: test_resolved_cache +8, test_fast_fetch +4 (_winner_expiry), test_fast_probe +4 (moves_applied_hosts). Branch selfheal-b5-expiry-everywhere, base 6e383b76.

## 2026-11-27 - Self-healing Branch 6: durability (log + reconciliation + weekly prune)
- New `self_healing_log.py` (stdlib-only): append-only JSON-lines audit log (`self_healing.log` in the data dir, `RADD_SELF_HEALING_LOG` override, weekly rotation); every auto-change writes exactly one line — site-move via `_record_verified`, host-move via `apply_move`, parked-flag via `_mark_host_parked`, cache-refusal + derived-expiry-banked via `record_resolved_cache`. Merge rule: `reconcile_site_host` logs a registry disagreement + queues the site for `resolve_site`; wired into `resolve_live_source` (memoised once per site per hour). `host_registry.prune_observations` (90-day retention, spent markers kept) + new `self_heal_weekly.py` CLI + `radd-self-heal-weekly.service/.timer` (Sun 07:00). Tests: new `test_self_healing_log.py` 29/29 + CI step. Full note: `checkpoints/20261127-selfheal-b6-durability.md`. Branch selfheal-b6-durability, base cbcc000c.
