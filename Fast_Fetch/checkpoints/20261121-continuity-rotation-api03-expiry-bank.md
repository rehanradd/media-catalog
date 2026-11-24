# CONTINUITY.md rotation — 2026-11-24 (API-03 expiry-bank lane)

The two oldest session-history bullets rotated out of
`Fast_Fetch/CONTINUITY.md` verbatim, to bring the index back under
its 12 KB budget. Nothing was reworded or summarized.

- **2026-11-21 — One Piece identity cluster:** catalog_api read "One Piece (2026) 1080P" as eps 1080–2026 and _print_scope minted phantom seasons; search cut the page by trending before relevance re-sort (7344 missed a 5-row page). Fix: release_descriptor.episode_range_bounds (projector's parse) + SQL relevance ordering with a versioned cursor. Migration onepiece_identity_fold.sql (gate-owned, unrun): 7344/7345/1242 → 69654, 307 → 58940; 21 posts. Tests: catalog_api 177/177, onepiece_identity_fold 8/8 (new) + CI step. Full note: `20261121-onepiece-identity.md`.
- **2026-11-21 — Registry §2 (MovieBox + wp_family):** probed live from the Oracle VM: themoviebox.xyz 200, aoneroom POST /subject/filter 200, moviehax/animeworld/hindidubbedmovies/hindianimeszone 200 + sitemaps/wp-json 200, thebollyflix.gen.in -> io.in (403 Cloudflare, unverifiable). Registries 27->33 sites, 42->49 hosts (45 live, rows 1,415,996). New radd-domain-probe timer daily 10:30, own timer. Nginx DRAFT header removed (lines 1-32), no reload. Panel: 9xmovies REAL move (to 9xmovie.cologne); other 4 stale/false positives. Importer registry wiring still open.
