# CONTINUITY.md rotation (phase2-host-verdicts lane) — 2026-11-28

The oldest session entry was rotated out of `Fast_Fetch/CONTINUITY.md`
verbatim to bring the index back under its 12,000-byte budget (11,994 bytes
before the phase2-host-verdicts entry). Count claim updated 183→185 /
180→182 dated (+1 verdict file, +1 rotation note).

## 2026-11-28 - sweep-load hardening (Phase 3 item 12)
- Two symptoms, two mechanisms: presses failed "database is locked" (sweep holds BEGIN IMMEDIATE while press banks had NO retry); posts 503 is NOT a lock, it is the 10 s query deadline (baseline crosses 10 s under sweep load). Fix: serve pragmas on the sweep writer (64 MiB cache, was 2 MiB) + bounded lock retry (3 attempts, 0.5/1.0 s, only "locked", fresh connection per attempt) in _bank_hubcloud_siblings and both viewer.py banks. 30 s busy_timeout and the deadline untouched. Tests: 7 new, all green; fast_probe 88/88; other modules green; registered in backend-ci.yml. Branch sweep-load-hardening (parent owns the gate/landing; live proof is a press under sweep load).

## 2026-11-28 - dead-host re-verification (Phase 2 item 8)
- Re-probe 18:50 (timer's command): 7 sites, all unresolved:down, 0 resolved. 7 of 8 still down day 2, but none is a dead server — all answer (thin 200s, 404s, redirects); likely bot-filtered. No site marked dead; no deletions. animeworld LIVE again (wp-json, 3 posts) — registry still down, tomorrow's probe fixes it. hdtvb redirects to hdhubs.in (top check); megacartoons WP REST works, no quality marker. Verdicts at checkpoints/20261128-dead-host-reverify.md.
