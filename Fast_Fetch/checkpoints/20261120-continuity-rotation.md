# CONTINUITY.md rotation — 2026-11-20 (Phase C GDFlix lane)

The four oldest bullets from the "Rotated session history" index in
`Fast_Fetch/CONTINUITY.md`, moved verbatim on 2026-11-20 to bring the index
back under its 12,000-byte budget (11,992 bytes before the rotation; the
Phase C GDFlix bullet is 343 bytes). Nothing reworded, nothing deleted —
the files they name are already indexed in `checkpoints/README.md`.

- [Session 2026-11-16 — playback telemetry beacon](checkpoints/20261116-playback-telemetry-beacon.md)
- [2026-11-18 — B4 trending warm-failure loudness](checkpoints/20261118-trending-warm-loud-b4.md)
- 2026-11-18 — H2 backfills #6/#8/#9: quality normalization, resolved_cache prune, desicinemas year.
- 2026-11-18 — B1 cursor pagination + band-aware play walk (`muse/cursor-pagination-20261118`): keyset cursors on v1 posts/trending, v2 walk verified, play best-band-first; 13 tests green locally.

A fifth bullet left the index in the H4 R10 rebase (their lane rotated it
without writing a rotation file); it is preserved here verbatim so the
pointer text is not lost:

- 2026-11-18 — R1+R2+R4 + checkup #3 host ranking: decayed score + tiers + verified prints + slice; 24 tests green; checkpoints/20261118-host-ranking-r1r2r4.md.

Two more bullets left the index in the SexTB rebase (2026-11-20), moved verbatim to bring the index back under its 12,000-byte budget (12,261 bytes before the rotation):

- [2026-11-20 — H4 R9 resolver probes](checkpoints/20261120-h4-r9-resolver-probes.md): serve-time re-probe of an aging banked row before it is 302'd — fresh rows serve unprobed, a verifying probe refreshes the row, an endpoint-answered failure reads as a cache miss (live resolver fails over), a no-answer probe serves fail-open; 18 tests, merged 44631e10.
- [2026-11-20 — H4 R8 clearance-cookie reuse](checkpoints/20261120-h4-r8-clearance-cookie.md): per-host clearance store + probe/resolver wiring; 43 tests, merged 8f7948d2.

One more bullet left the index in the H4 R10 fixup rebase (2026-11-20),
moved verbatim (12,262 bytes before that rotation). Nothing reworded,
nothing deleted — the file it names is already indexed in
`checkpoints/README.md`. (The MX #4 bullet rotated in that same rebase is
NOT listed here: it never left main's index, so it stays in CONTINUITY.md.)

- [Session 2026-11-12 — self-check `[LIVE-TV-503]`](checkpoints/20261112-selfcheck-live-tv-503-logos.md)

One more bullet left the index in the second H4 R10 fixup rebase
(2026-11-20), moved verbatim to bring CONTINUITY.md under its 12,000-byte
budget (12,081 bytes before this rotation).

- [2026-11-19 — H4 R5 shared streaming cache](checkpoints/20261119-h4-shared-cache.md): demand ledger + sibling-band warm + prefetch --demand; 33 tests green locally, Backend CI pending.

- [B2 episode_guide — 2026-11-19](checkpoints/20261119-b2-episode-guide.md)
- [2026-11-19 — H4 R6 direct-file preference](checkpoints/20261119-h4-direct-file.md): measured mime corrects the ingest label in tiers.score, candidate_order_sql, and the verify pass (+0.10 fresh banked-file nudge); 17 tests green locally, Backend CI pending.

Two more bullets left the index in the scale real-IP lane (2026-11-20),
moved verbatim to bring CONTINUITY.md under its 12,000-byte budget
(11,892 bytes before this rotation, 446-byte new bullet).
