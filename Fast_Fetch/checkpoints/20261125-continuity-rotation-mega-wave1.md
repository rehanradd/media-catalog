# CONTINUITY.md rotation — 2026-11-25 (mega wave-1 lane)

The two oldest session-history bullets rotated out of
`Fast_Fetch/CONTINUITY.md` verbatim, to bring the index back under
its 12 KB budget. Nothing was reworded or summarized.

- **2026-11-23 — Catalog pipeline fixes (drought root cause):** branch `muse/catalog-pipeline-fixes-20261123`. Fleet SITES 20→26 (xprimehub, themoviebox, javmost, animeworld, dramadog, megacartoons); `sync_sitemaps` reports every feed loudly + `[!] Sync problems` summary; 7starhd regex now host-derived, katmoviehd homepage-link fallback; 10xflix /m drift and bollyshares down noted, not papered over. Backlog: javmost 234,057 all-adult, themoviebox 22 new by key, dramadog 109, megacartoons 31, animeworld 0. Full note: `20261123-catalog-pipeline-fixes.md`. The 2026-11-21 9xmovies bullet rotated out verbatim into the same file.
- **2026-11-21 — 1fichier resolver wiring (TrueLink port):** `_unwrap_1fichier` (file page → hidden-form POST → btn-general link; no password plumbing anywhere, so password=None at dispatch; 404 = dead_file, 403 = blocked) wired into STAGE1_BACKENDS, preference lists, classify_backend (exact + dotted-suffix match on 1fichier.com; 1fichier.com.evil.com / not1fichier.com stay unknown), resolve_copy dispatch, fast_fetch stage-1 tuples. Tests: test_current_hosts 51→56, test_truelink_1fichier 25/25, test_host_classifier 77/77. Fixture-only: Oracle IP locked (error #350), never live-probed; live proof waits on the WARP-in-production decision (owner-only). NOT merged — parent merges after the gate.
