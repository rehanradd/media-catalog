# 2026-11-21 — One Piece identity cluster (branch `muse/onepiece-identity-20261121`)

## What was wrong

The same show was filed as four works, the anime as two (all measured live 21 Nov 2026, read-only, mode=ro):

- Live-action "One Piece" (2023, IMDb tt11737520, TMDB 111110, S1/S2 8 eps each): 69654 "One Piece Live Action" (2026, anime-typed, 114 prints, 16 eps, suspect=0 — survivor), 7344 "One Piece" (2026, series, 17 prints, 2 eps, suspect=1 — S02 files), 7345 "One Piece" (2023, series, 44 prints, 0 eps, suspect=1 — S01 files), 1242 "One Piece (2023)" (2023, anime-typed, 89 prints, 18 eps, suspect=1 — S02E01 files filed as anime via is_animated=1).
- Anime "One Piece" (1999, TMDB 37854, 23 seasons, 1,065 eps): 58940 (2025, 8,304 prints — survivor), 307 (year NULL, 1,291 prints — year-less anime posts; its season 1 is a flat 157-episode list).

Two mechanisms, both proven live:

1. **Misparsed coverage minted phantom seasons.** `catalog_api._episode_range_bounds` took the FIRST TWO numbers in the text, whatever they were. `_print_view` fell back to the label when episode_range was empty, inventing (1080, 2026) from resolution + year on release 172681 ("One Piece (2026) 1080P [3.9GB] WEB-DL", work 7344). `_print_scope` then used the poisoned bounds, so the 2-button merged ladder would show a phantom "episode 1080" rung. The stored projection was correct — the poison was introduced at READ time. The app parser is safe for this label (requires a hyphen), but the server scope is not.
2. **Search ranking was unstable for same-title works.** Step 1 cut the page by TRENDING before the Python relevance re-sort: with limit=5, 7344 (relevance #2, trending 236) missed the page by 1.4 trending points to "Lego One Piece". With limit=50 it appeared. The cursor was also minted from the pre-sort order, so pagination after a re-sort was already inconsistent.

## The code fix (in this branch)

- `release_descriptor.episode_range_bounds(text)` — the projector's own parse, in the same order: a bare "N-M" pair, an explicit range (SxxEyy-zz / Ep x-y / bracketed), or one episode marker. Resolution/year/size never match. Episodes ≥1000 never parse here — the 1..999 bound is shared with the projector and the app.
- `catalog_api._episode_range_bounds` now delegates to it (call sites unchanged: `_print_view`, episode matching, range/parts, episode_detail). Verified: the 172681 label → (0, 0) (was (1080, 2026)); "Flip S02E05 (2023) 1080p" → (5, 5).
- `catalog_api._relevance_expr` — SQL mirror of `_relevance()`; `_run_listing` orders by it when a text query is present (plain browse untouched). Cursors carry an expression version (`"e"`: 0 = trending order, 1 = relevance); a cursor from the other order is rejected as `cursor_invalid`. Old cursors read as 0, which is the order they were minted in.

## The data migration (gate-owned, NEVER run)

`Fast_Fetch/onepiece_identity_fold.sql` — folds 7344/7345/1242 → 69654 and 307 → 58940 (dedup_fold.py's apply_fold semantics: journal, collision-safe re-pointing, seasons/episodes merged by number, blank-metadata fill, roll-ups, bridge invalidate), plus Phase A ledger stamps on 21 posts so the next projection re-keys them to the survivor keys. Step-0 guard aborts on any drift from the recorded identities/counts; re-running is a safe no-op; rollback is the gate backup. Expected live end state: 69654 = 264 releases / 18 episodes, year 2023, imdb tt11737520; 58940 = 9,595 releases / 1,156 episodes. Survivors read identity_suspect=1 afterwards — the re-pointed review rows stay OPEN; no review row is fake-resolved. The fold must be re-applied after any full projection until the stamps have taken effect. NOT touched: post 130993 (ambiguous), work 59190 "One Piece Or" (1 print), works 121001/121002 (distinct titles), 58940's year 2025, the anime IMDb tt0388629 (unverified).

## Tests

- `test_release_descriptor.py` 25/25 (new `test_episode_range_bounds_agrees_with_describe`, incl. the "E1-E4000" → (1, 400) projector truncation, pinned).
- `test_catalog_api.py` 177/177 (new `OnePieceIdentityUnitTests`, 6 tests: stable top rows across limits, relevance cursor paging, old cursor rejected, 172681-shaped release → scope (None, None), genuine ranges still parse).
- `test_onepiece_identity_fold.py` 8/8 (new; fixture executes the .sql file: guard aborts on stale work count / stale post / missing member, fold + verify clean, Phase A stamps, controls untouched, re-run no-op, LIVE-VERIFY markers). CI step added in `backend-ci.yml`.
- Also green: test_cursor_pagination 13/13, test_api_v2 97/97, test_catalog_v2 113/113, test_detail_projection 4/4, test_tiers 57/57, test_dedup_fold 38 (2 skipped, pre-existing), test_docs_invariants 9/9, test_fts_search 25/25. Pre-push checks pass.

## Follow-ups (not in this branch)

Season 0 display (the projection's "unknown season" bucket, rendered as "Season 0"); the 1000+ episode blind spot (shared by projector + app + server); `title_clean._SERIES_TYPES` missing "anime" vs catalog_v2's (dedup family split — why 58940 never groups with 7344/7345); the Lego dedup over-merge (subset rule); 58940's year 2025; post 130993; tt0388629; bare-"E05" app/server divergence; v1 search first hit "ONE PIECE HEROINES".

## Rotated out of CONTINUITY.md (verbatim, oldest first)

- **2026-11-21 — Registry refresh (4 moved sites):** probed live from the Oracle VM — vegamovies.lease→vegamovies.latino 200, new1.ssrmovies.page→site4.ssrmovies.page 200 (wp-json live), new12.hdhub4u.cl→new15.hdhub4u.cl 200 (tracker agrees new15; hdhub4u.fr is a landing page), rogmovies.asia→rogmovies.beauty 200 (sitemap Nov 20 9:48 pm). domain_registry + host_registry updated (42 hosts, 38 live, rows unchanged — 0 rows on old hosts). Filepress drift UNRESOLVED (all 5 bases 403); retired guard added (hdtvb no longer served/ingested).
- **2026-11-21 — Hubdrive + megaup resolvers (nurlresolver ports):** `_unwrap_hubdrive` (page → ajax direct-download → .btn-primary link; cookie from RADD_HUBDRIVE_CRYPT) + `_unwrap_megaup` (btn-default href → 6 s wait → no-redirect Location; error.html = dead). Live: hubdrive page 200, ajax 408 without cookie → "hubdrive_needs_cookie"; megaup.net resets, .mobi TLS fails → "transport". Registered in STAGE1_BACKENDS, preference lists, classify_backend, fast_fetch tuples, host_classifier. default_fetch gained cookies/headers/follow_redirects + Set-Cookie capture. Tests: test_current_hosts 14→33, test_host_classifier 74→75. Unproven live: hubdrive final link (needs cookie); megaup chain.
- **2026-11-21 — Duration backfill newest-first:** select_batch orders (kind='playlist') DESC, resolved_at DESC, link_id (NULL resolved_at sorts last). Batch 1 probed oldest-first: 5% yield, 75/100 HTTP 410; prototype newest-first got 60%. Fixture + new test_newest_resolved_first_within_kind; two RunTests re-based, assertions unchanged. test_duration_backfill 67/67 green; pre-push pass. Batch 2 next on staging copy.
- **2026-11-21 — 1fichier TrueLink port (stdlib-only, fixture tests):** `truelink_1fichier.py` `_unwrap_1fichier` (GET page → POST hidden form a=1 → scrape `a.btn-general`; 404 = dead_file, 403 = blocked, warnings → wait/bad_password/premium/needs_password/needs_validation). Port of TrueLink 1.4.8 fichier.py + nurlresolver fichier.js. No registration edits (parent wires it after the bbupload lane lands). Tests: test_truelink_1fichier 25/25 offline; CI step added. Live proof waits on the WARP-in-production decision (Oracle IP locked #350, never probed).
