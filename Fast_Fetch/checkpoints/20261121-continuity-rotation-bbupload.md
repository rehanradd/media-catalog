# CONTINUITY.md rotation — 2026-11-21 (bbupload lane)

The two oldest session-history bullets rotated out of
`Fast_Fetch/CONTINUITY.md` verbatim, to bring the index back under
its 12 KB budget. Nothing was reworded or summarized.

- **2026-11-21 — Hubdrive + megaup resolvers (nurlresolver ports):** `_unwrap_hubdrive` (page → ajax direct-download → .btn-primary link; cookie from RADD_HUBDRIVE_CRYPT) + `_unwrap_megaup` (btn-default href → 6 s wait → no-redirect Location; error.html = dead). Live: hubdrive page 200, ajax 408 without cookie → "hubdrive_needs_cookie"; megaup.net resets, .mobi TLS fails → "transport". Registered in STAGE1_BACKENDS, preference lists, classify_backend, fast_fetch tuples, host_classifier. default_fetch gained cookies/headers/follow_redirects + Set-Cookie capture. Tests: test_current_hosts 14→33, test_host_classifier 74→75. Unproven live: hubdrive final link (needs cookie); megaup chain.
- **2026-11-21 — Registry refresh (4 moved sites):** probed live from the Oracle VM — vegamovies.lease→vegamovies.latino 200, new1.ssrmovies.page→site4.ssrmovies.page 200 (wp-json live), new12.hdhub4u.cl→new15.hdhub4u.cl 200 (tracker agrees new15; hdhub4u.fr is a landing page), rogmovies.asia→rogmovies.beauty 200 (sitemap Nov 20 9:48 pm). domain_registry + host_registry updated (42 hosts, 38 live, rows unchanged — 0 rows on old hosts). Filepress drift UNRESOLVED (all 5 bases 403); retired guard added (hdtvb no longer served/ingested).

## Second rotation — rebase onto main c18c7008 (parent, 2026-11-21)

The rebase onto the 1fichier merge put the index 191 B over the 12,000 B
guard, so the oldest remaining bullet (2-button Phase 2) rotated out
verbatim below. Nothing was reworded or summarized.

- **2026-11-21 — 2-button Phase 2 server (merged ladders on v2 /play + detail):** catalog_api gained merged_ladder_for_prints / merged_ladders_by_scope (+ _print_scope, _fresh_verified_at, _annotate_tier_verified_at): tiers.merge per scope exactly as v1; each tier gains gated, alternates_count, download_url (/api/v1/download?id=...), verified_at (fresh banked resolution or None); auto = best playable non-gated tier. work_detail(gated_bands=()) serves quality_ladder + option_groups (same list) and derived work.best_quality. route_play merges the walk candidates / named print + siblings (cap 5) after the mint refusal — a refused mint still answers tiers [] with no handle; detail marks gate bands for free callers only, vip bit in the memo etag seed. Tests: test_api_v2 97/97, test_catalog_v2 113/113, test_tiers 57/57; test_redact 118/124 locally is the documented load flake, CI to confirm.
