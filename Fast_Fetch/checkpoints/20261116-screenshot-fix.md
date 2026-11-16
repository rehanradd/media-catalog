# Screenshot fixes — checkpoint (2026-11-16 ~23:45 PKT)

Branch: `muse/screenshot-fix-20261116`, worktree `~/workspace/screenshot-fix-work`.
Base: 76db046f (STALE). Main is now 53700d7e (harness-fix merged 23:31, Android CI 43928114590 SUCCESS + Backend 43928114614 SUCCESS). Rebase owed before push. Do NOT merge — report to parent.

## What was fixed (all in the worktree, uncommitted at checkpoint time)

### App
- `network/ApiProvider.kt` — per-request timeout interceptor (`readTimeoutSecondsForPath`): works 60s, live-tv 45s, v1 detail 30s, else 15s. Root cause of the detail failures: v2 detail payload is 440,859 B on the wire vs the 15s read timeout at 2.6–25 KB/s on the owner's phone.
- `viewmodel/LiveTvViewModel.kt` — `fetchChannelsWithRetry()`: one silent retry on IOException (1s delay, max 2 attempts). The 759-channel void was NOT reproducible from code; fetch-side (timeout + gzip + retry) is the remedy.
- `ui/screens/LiveTvScreen.kt` — empty state gains TV icon (48dp) + Retry button (D3).
- `ui/screens/DetailScreen.kt` — D1: no-backdrop fallback starts below status bar + 64dp top bar; hero `heightIn(min = 296.dp)`; title caps at 2 lines in hero (3 on fallback); VIP pill + IMDb badge share one row. D6: error block centres in the space below the header + hint line about weak signal.
- `ui/screens/SearchScreen.kt` — placeholder "Search titles, genres, or cast" → "Search titles or cast" (D2). Chips-floating-mid-screen NOT reproducible from current code (rail anchored under the field since Nov 15).
- `ui/screens/HomeScreen.kt` — D7: spacer after hero 12→6dp, placeholder padding 12→8dp. D8: header row padding 8→4dp, pill padding 8→5dp, check-in icon 18→16dp, coin circle 20→18dp, logo titleLarge→titleMedium / labelLarge→labelMedium, spacer under chips 8→4dp. Chips stay 48dp (touch floor).
- `ui/components/HeroCarousel.kt` — dots top padding 10→4dp (D7). Dots row stays 48dp (HeroIndicatorTest).
- `ui/components/CatalogImage.kt` — fallback mark 30→40dp, title maxLines 2→3 (D4). Gentlemen poster URL EXISTS (post 72545) — slow-connection load failure, not a missing URL.
- `player/CacheAffordance.kt` + `viewmodel/PlayerViewModel.kt` — both "Keep interrupted" messages → "Keep interrupted — will resume" (30 chars, D5).

### Tests
- NEW `viewmodel/LiveTvRealPayloadTest.kt` + resource `test/resources/live-tv-channels-759.json` (real 167,364 B payload): 759 channels, 9 categories, first Dunya News id 1, 759 unique ids, filter + cache round-trip.
- NEW `network/ApiProviderTimeoutTest.kt` — pins the timeout map.
- Updated `player/CacheAffordanceTest.kt` (INTERRUPTED message exact + ≤30 chars), `ui/screens/SearchFieldHintTest.kt` (placeholder string).

### Nginx (APPLIED LIVE 23:30, ENABLED file only, backup /home/ubuntu/radd-stream.screenshot-fix-20261116.before)
- Stream burst 30→60 in both API server blocks; gzip directives added to both API blocks (`gzip on; gzip_proxied any; gzip_comp_level 6; gzip_types application/json; gzip_vary on;`).
- Verified from the VM: works detail wire 440,859→19,870 B; channels 167,364→29,157 B; 20 rapid stream presses → 0×429.
- Zone rate 10r/s NOT changed (lives in conf.d, outside the brief's ENABLED-file-only instruction) — follow-up lever.

## Follow-ups (NOT in this branch)
- Player ladder treats a 429 as a failed handle, tries the next one immediately (no Retry-After honoring) — retry-storm follow-up.
- Data note: "Kesariya@100 ★ 10.0" looks fake — flag for the owner, not fixed here.
- Session log: App/docs/SESSION_HISTORY.md (appended). Next: stage reviewed paths only, run ~/workspace/pre-push-checks.py, rebase onto 53700d7e, commit, push via bundle + Oracle VM askpass route. Watch for the mass Robolectric flake on branch CI (re-run, do NOT "fix" those tests).

## D9 — disclaimer testTag for the Robo script (2026-11-16 ~23:40 PKT)
- Robo run 2 (matrix-3p2ysll1q0ed7) failed on action 2 of 12: WAIT found "I Understand" but VIEW_CLICKED on that text failed — text tap matched a non-clickable Text node; dialog stayed up, resolve test never ran.
- Fix (second commit on this branch, on top of a6d83fa5): `testTag("disclaimer_confirm")` on the first-run confirm Button + `testTag("disclaimer_decline")` on Decline in DisclaimerDialog.kt (no visual change); robo-resolve-test.json action 2 matches by `resourceId: "disclaimer_confirm"` (action 1 text wait unchanged, 12 actions total).
- DisclaimerDialogTest asserts by text only — tags cannot break it. Push re-runs CI on the new tip; the CI-green debug APK from that tip is the one for Robo run 3 (harness + screenshot fixes + testTag).
