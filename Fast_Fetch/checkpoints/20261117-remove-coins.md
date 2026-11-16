# Coins + Daily Check-in Removal — 2026-11-17

Owner order 00:15 PKT 17 Nov 2026 ("decide everything yourself"): remove/turn off
coin and daily check-in from the app completely and carefully. Branch
`muse/remove-coins-checkin-20261117`, rebased onto main 62d5f018 (screenshot-fix
merged 00:21 PKT 17 Nov; header-pill collision cleared).

## Decisions (parent, binding)

- Turn OFF via remote-config flags (kept, reversible) + remove ALL coin/check-in
  UI. No user can see, earn, or spend coins anywhere after this.
- Do NOT delete server balances, `coin_ledger`/`wallet` tables, or coin
  endpoints (e.g. POST /api/v2/account/coins/redeem-download). They stay,
  unused by the app. No data deletion anywhere.
- Do NOT touch VIP itself (JazzCash/EasyPaisa/Raast TID flow stays).
- UNFUSE coins from the quality gate: `paywallVisible == qualityGateEnabled`
  today (HomeViewModel:641, AccountViewModel:125, SettingsViewModel:299 all copy
  it from `settingsRepository.qualityGateEnabled`), so the ONLY way to hide the
  coins UI today is gate ON (locks 4K). After this change the coins UI is gone
  unconditionally and the gate stays OFF (free month, all qualities free).
- Downloads AND play must work with zero coins (no spend, no block).
- Check-in is local DataStore day-tracking; server publishes only the plan
  (`VipRemoteStreak`), no claim/grant route — removal is UI + flag work, not a
  money migration. Do not invent one.

## The fusion (proven from the tree at 53700d7e, re-verified at 62d5f018)

- HomeScreen.kt HomeHeader doc comment: "Free launch: while the quality gate
  is OFF both pills hide — the same rule the banner lived by". Check-in pill
  shows only `if (paywallVisible && streakLadder.isNotEmpty())`; Coin Wallet
  pill only `if (paywallVisible)`. The same bit also gates VIP upsell surfaces
  (Dashboard/Profile/Settings VIP cards) — those STAY as-is; only coin/check-in
  UI is removed unconditionally, which unfuses coins from the gate.
- App defaults: `VipRemoteFeatureFlags.qualityGate = false`,
  `VipRemoteWallet.enabled = false`, `VipRemoteStreak.enabled = false`.
  Server canonical default `feature_quality_gate = 0` (admin_panel.py:1798).
  BUT the live catalog row still serves `quality_gate=true` (gate ON as-served);
  the flip to OFF is the OWNER's panel action, still owed — this branch does
  NOT flip it and must not need it to hide coins.
- ViewModel gate defaults are TRUE until the watcher delivers
  (DetailViewModel:500, PlayerViewModel:315); `paywallVisible` defaults true in
  all three ViewModels.

## Remote-config served body (verified by reading admin_panel.py:remote_config)

- Serves NO top-level `wallet` or `streak` keys — app's
  `VipRemoteConfig.wallet`/`streak` always decode to OFF defaults. The
  `engagement` block (built by `_engagement_public` over
  `engagement.remote_view()`) DOES publish coin numbers:
  `wallet.coin_per_rewarded_ad`, `rewarded_daily_cap`, `redeem_coin_costs`,
  `streak.grants` (day1/day3 coins, day7 pass seconds), `passes`. App decodes
  `engagement` only as cadence (`VipRemoteEngagement`).
- Server coin knobs in `MONETIZATION_FIELDS` (admin_panel.py:1824-1840):
  `coin_per_rewarded_ad` 5, `rewarded_daily_cap` 10, `coin_redeem_day` 500,
  `coin_redeem_weekend` 1200, `coin_redeem_monthly` 5000, `coin_redeem_download`
  100, `coin_share` 25, `coin_finish_watch` 15, `coin_redeem_request` 300,
  `coin_redeem_theme` 250, `streak_day1_coins` 10, `streak_day3_coins` 30,
  `streak_day7_pass_hours` 24, `streak_day_boundary_hour` 4. `engagement.py`
  constants: SHARE_COINS 25, FINISH_WATCH_COINS 15, REQUEST_MOVIE_COINS 300,
  THEME_COINS 250, DOWNLOAD_CREDIT_COINS 100.
- **Knobs cannot turn grants off:** `_resolve_coin_knob` (engagement.py:719) —
  "a stored 0 or an unparseable paste is 'use the shipped number', never
  'free'". So setting a grant knob to 0 restores the shipped default. A code
  guard is required.
- **Grant call sites (all in Fast_Fetch/accounts_api.py):** progress route
  `:972` `engagement.grant_finish_watch(...)`; claim route (`coins/earn`,
  `:1034`) `engagement.record_engagement_day(...)`; share route
  (`coins/share`, `:1141`) `engagement.grant_share(...)`; redeem routes
  (`coins/redeem`, `redeem-download`, `redeem-theme`, `request-movie`) spend
  via `engagement.redeem_pass` / `redeem_download_credit` / `redeem_theme` /
  `request_movie`.
- **Planned server change:** new master flag `coins_enabled`, default 0, in
  `MONETIZATION_FIELDS` (v1 admin_panel.py) + AdminV2 `store.py:46` list +
  DDL (`store.py:117`) + `fixture_app.py` + `live_stores.py:86` +
  `services.py:1287` validation + `server.py:1476` flag field; publish as
  `"coins": flag("coins_enabled")` in remote-config `feature_flags`; guard the
  three grant sites + claim + redeem routes in `accounts_api.py` (403 when
  off). Reversible by flipping the flag to 1. No balances/tables/endpoints
  deleted. Live-row values persist through code deploys (`_ensure_monetization`
  only adds a missing row) — the live-row update is a gate step.
- App side for the flag: add `coins: Boolean = false` to
  `VipRemoteFeatureFlags` + test (documents the served-OFF contract; nothing
  in the app will read it after UI deletion). Delete `VipRemoteWallet` /
  `VipRemoteStreak` model classes from `VipRemoteConfig.kt` (dead after UI
  removal) and update `VipRemoteConfigTest` wallet/streak tests.

## Downloads / play with zero coins (verified)

- Free downloads use `downloadRedirectUrl` (ApiProvider.kt:247) =
  `/api/v1/download?id=<handle>` with NO credit param, via
  `DeviceDownloadsRepository` / `DownloadService`. Server viewer.py:7705-7716:
  credit is optional — "a bad token costs a direct link, never the download
  itself"; app native downloads never carry `?via=web`, so no shortener. Free
  downloads already work with zero coins.
- Only coin-charging download path: the "buy a direct link" button →
  `DetailViewModel.directDownloadUrlFor` →
  `SyncAccountRepository.redeemDownloadCredit` → `creditDownloadUrl`
  (`&credit=<token>`). The dialog row is ALREADY gone from DetailScreen.kt:486
  (comment: "UI-fix P1-D9: the coins Direct Link row is gone");
  `directDownloadUrlFor` has NO callers (dead code) and `loadDownloadCreditPrice`
  is called only from DetailViewModel:1080. Remove the dead ViewModel function
  + `downloadCreditPrice`/`coinBalance`/`directLinkMessage` state +
  `CreditDownloadUrl.kt` + the repository/API chain for it.
- Finish-watch grant rides the progress endpoint the app MUST keep calling for
  resume (`PlayerViewModel.reportResume` → `SyncAccountRepository.reportProgress`
  → `coins_earned`; toast `"+$coinsEarned coins for finishing this title"` at
  PlayerViewModel:1406). App fix: remove the toast; server fix: the
  `coins_enabled` guard above (progress still returns `coins_earned: 0`).
- Share claim (`DetailViewModel.reportShare` →
  `SyncAccountRepository.reportShare` → POST `coins/share`, pays 25 once/day)
  must stop being called; the Android share chooser itself stays.
  `reportProgress` return value also used by HomeViewModel:1020 and tests
  (`NewPhoneProgressPullTest`).

## UI surfaces removed (file + what)

- **Delete files:** `App/app/src/main/kotlin/com/raddstream/app/ui/screens/CoinsScreen.kt`
  (balance, pass catalog redeem, history; `humanCoinReason`, `formatCoinDay`
  defined here — `formatCoinDay` is also used by ProfileScreen.kt:136,143 for
  account created/last-seen dates, so move it to a neutral home before
  deleting), `CheckInScreen.kt` (7-day ladder + Check in button),
  `App/app/src/main/kotlin/com/raddstream/app/player/LoginStreak.kt` (local
  day-tracking; `LoginStreakTest` deleted with it).
- **Nav:** `Screen.kt:78-79` `CheckIn`/`Coins` objects; `NavGraph.kt`
  destinations `:734-739` + call sites `:556` (Home onOpenCheckIn),
  `:667,717,728,746` (onOpenCoins).
- **Home header:** `HomeScreen.kt` `HomeHeader` check-in pill (`:692`, tag
  `checkInPill`) + Coin Wallet pill (`:750`, tag `coinWalletPill`,
  `CoinWalletPillTag`), `CheckInSheet` (`:1288-1480`), `HomeContent` params
  (`:161,316,352,638,644`), `onOpenCheckIn`/`onOpenWallet` params. (Line
  numbers pre-screenshot-fix; re-verify at 62d5f018 — D8 compacted the header.)
- **VIP screen** (`VipUpgradeScreen.kt`): "Coins and history" TextButton
  (`:179`, `onOpenCoins:102`); `StreakCard` (`:182`, gated on
  `state.streak.isConfigured`); `RequestMovieCard` (300-coin requests);
  `ThemeCard` (coin-priced themes; `buyTheme` + `selectTheme` go with it —
  theme selection has no other UI); `"Wallet: $balance coins."` (`:664`).
  KEEP: `RewardedVipSection` (`:173`, `grantRewardedVip`), pricing/payment/TID
  sections, `EntitlementCard`, cancel/resume.
- **Dashboard** (`DashboardScreen.kt`): Coins card (`:118`), Check-in streak
  card + button (`:140-158`), "Coins and history" button (`:204`); reword
  signed-out hint `:110` to drop coins/streak ("Sign in from Settings to see
  your membership here."). KEEP Membership card (VIP).
- **Profile** (`ProfileScreen.kt`): streak/coins Texts (`:124-133`, inside
  `paywallVisible` block — remove whole block), "Coins and history" button
  (`:159`).
- **Settings** (`SettingsScreen.kt`): `AccountNavRow` Coins door
  (`:1300-1324`, `onOpenCoins:169,685`); help text `:923` ("How to watch,
  download, and use VIP and coins."); comment `:166,677-678` mentions coins.
  KEEP VIP Membership card (`:617`, gated on `paywallVisible`).
- **Help** (`HelpScreen.kt:203`): "VIP and coins" FAQ row — "Coins come from
  checking in every day, sharing a title, and finishing one. Coins buy a
  24-hour VIP pass, a direct download, a player theme, or a movie request" —
  remove/reword the coins FAQ.

## ViewModels

- `VipViewModel` — remove `loadWallet`, `claimDailyLogin`, `redeemPass`,
  `requestMovie`, `buyTheme`, `observeStreak`, state fields `streak`,
  `streakCount`, `coinBalance`, `streakLadder`, `passCatalog`, `themeCatalog`,
  `requestPrice`, `ownedThemes`, `isClaiming/claimedToday/claimMessage`,
  `isRedeeming/redeemMessage`, `isRequesting/requestMessage`,
  `isBuyingTheme/themeMessage`; remove `streak = config.streak` in
  `withConfig`; KEEP `grantRewardedVip`, `RewardedVipPass`, TID/pricing/
  cancel/resume, `selectTheme` only if a caller remains (else remove).
- `AccountViewModel` — remove `coinBalance`, `currentStreak`, `bestStreak`,
  `lastCountedDay`, `streakLadder`, `passCatalog`, `history`,
  `claimMessage/isClaiming/claimedToday`, `redeemMessage/redeemingPlan`,
  `claimDailyLogin()`, `redeemPass()`, `claimedTodayFor()`; `refresh()` drops
  ladder/catalog/history/overview coin reads; KEEP `paywallVisible`,
  `signedIn`, `isVip`, `vipUntil`, `createdAt`, `lastSeenAt`, `signOut()`.
- `HomeViewModel` — remove `streakCount`, `serverStreak`, `streakLadder`,
  `coinBalance`, `claimedToday/isClaiming/claimMessage`, `observeDailyCheckIn`
  (loginStreakCount part + `loadCheckInWallet`), `claimDailyLogin()`; KEEP
  `paywallVisible`.
- `DetailViewModel` — remove `downloadCreditPrice`, `coinBalance`,
  `directLinkMessage`, `directDownloadUrlFor`, the dialog price/balance read
  (`:1080-1086`), `reportShare` coin-grant call (keep share chooser),
  share-balance update (`:1131`); KEEP free-download flow.
- `PlayerViewModel` — remove the `+N coins` toast (`:1406-1407`); keep
  `reportProgress` call.
- `MainActivity.kt:133-136` — remove login-streak recording at launch.
  `ServerSettingsRepository.loginStreakCount` flow — remove.
  `Telemetry.kt:33,173` `recordCoinsGranted` / `coins_granted` counter —
  remove (rewarded-completions counter stays; `recordRewardedCompletion`
  stays).

## Repository / API (app)

- Remove coin-only `SyncAccountRepository` methods with no remaining callers:
  `loadCoinBalance`, `loadCoinHistory`, `loadStreakLadder`, `loadPassCatalog`,
  `loadThemeCatalog`, `loadRequestPrice`, `loadCoinThemes`, `claimDailyLogin`,
  `redeemPass`, `redeemDownloadCredit`, `redeemTheme`, `requestMovie`,
  `loadDownloadCreditPrice`, `reportShare`; keep `reportProgress` (resume),
  `loadAccountOverview` (minus coin fields — check `AccountWire` overview
  shape), entitlement/payment methods.
- `RaddStreamApi.kt` coin endpoints (`redeemPass:567`,
  `redeemDownloadCredit:580`, `redeemTheme:621`, `reportShare:592`,
  `getCoinCaps`) — remove declarations for routes with no callers; KEEP wire
  model classes (`CoinRedeemed`, `CoinDownloadCredit`, `CoinEarned`,
  `CoinShare`, `CoinLedgerEntry`, `PassTier`, `DownloadCreditPrice`, etc. in
  `AccountWire.kt`) so `ModelsTest` keeps passing. Server endpoints themselves
  are NOT deleted.

## Rewarded-VIP pass — KEEP (decided, with evidence)

- `grantRewardedVip` comment: "The server grants no coins on this (that needs
  a network postback, which Monetag has not confirmed for native Android); it
  only counts the completion." `coinsPerPreRoll` is read nowhere in app code
  except `VipRemoteWallet.hasAnyEarningRule` (unused) and tests. The pass is
  ad → 4h ad-free, device-local, not a coin flow. Keep `RewardedVipSection`,
  `RewardedVipPass`, `AdFreeReader`, `rewardedVipExpiresAt`,
  `RewardedVipPassTest`.

## Clean (verified by grep)

- `SetupScreen`/`SetupViewModel` (hits were "check your connection" false
  positives). `DeviceTrial`, `SyncContract`, `PlayBundle`, `CatalogEnums`.
  `AudioBadgeChips.kt:45` "coin flip" is idiom, not coins.

## Tests to update / remove

- `HomeHeaderTest`, `HomeCheckInTextTest`, `DashboardPaywallTest`,
  `ProfilePaywallTest`, `SettingsMockupTest`, `DialogsMockupTest`,
  `NestedScrollCrashTest`, `PaparazziPreviewTest`, `SubScreensMockupTest`,
  `VipRemoteConfigTest` (wallet/streak default tests → replace with `coins`
  flag test), `LoginStreakTest` (delete), `AccountStreakTest` (check),
  `SearchFilterStateTest` (1 hit, check), `ProgressUsageWireTest` (2 hits,
  check), `ModelsTest` (94 hits — coin wire models, KEEP),
  `RewardedVipPassTest` (KEEP). Add a test proving coins/check-in UI is gone
  and flags read OFF.

## Gate-OFF + zero-coin proof (to be completed before report)

- Coins UI gone unconditionally (no `paywallVisible` gate on any coin surface
  — the surfaces are deleted, not hidden behind the gate bit).
- Gate stays OFF: `VipRemoteFeatureFlags.qualityGate` default false, server
  canonical default `feature_quality_gate = 0`; no code in this branch sets
  either to true; live as-served flip remains the owner's panel action.
- Zero-coin download: free path is `/api/v1/download?id=<handle>` with no
  credit param (server: credit optional, never blocks); the only
  coin-charging path (`directDownloadUrlFor` → `redeemDownloadCredit`) is
  deleted.
- Zero-coin play: progress endpoint still called for resume; finish-watch
  grant guarded server-side by `coins_enabled=0` (returns `coins_earned: 0`);
  no coin toast remains.

## What happened after the code edits (17 Nov, pre-push)

- **Redeem-theme guard anchor (fixed).** The first guard edit matched two
  identical `values = _settings(catalog)` blocks and landed on the GET
  `coins/themes` read route instead of POST `coins/redeem-theme`. Reverted
  the read-route guard (reads stay open by design) and placed the guard in
  the redeem-theme route. All 7 guard points verified by grep:
  progress grant (992), earn (1054), redeem (1083), redeem-download (1131),
  share (1172), request-movie (1200), redeem-theme (1233).
- **`_problem` closed-code trap (fixed, found by the new tests).**
  `_coins_off` first used the code `coins_disabled`, which is not in
  `api_v2.PROBLEM_CODES` — `_problem` answers any undeclared code as
  `not_found` 404, so the guard 404'd instead of 403'ing. The refusal now
  uses the closed list's own 403, `forbidden`, with detail "coins are
  turned off". Lesson: never invent a problem code; use the closed list.
- **v2 live-store migration (fixed, found by `test_live_real`).** The v2
  `save_remote_config` wrote `coins_enabled` into the v1 table, but a
  catalog created before the flag existed has no such column — the first
  monetization save 500'd `no such column: coins_enabled`. The save now
  adds the column (INTEGER NOT NULL DEFAULT 0) when missing, mirroring
  v1 `_ensure_monetization`. The live catalog has no such column yet, so
  this fix is load-bearing for the first panel save after deploy.
- **Tests.** App: `HomeHeaderTest` rewritten (logo-only header, no pills in
  any state; filter chips + hero kept), `HomeCheckInTextTest` +
  `AccountStreakTest` deleted, `DashboardPaywallTest`/`ProfilePaywallTest`
  rewritten (coins/streak gone unconditionally; VIP upsell still gated),
  `SettingsMockupTest` (Coins door gone even with gate ON),
  `DialogsMockupTest` (check-in circles + sheet tests deleted),
  `NestedScrollCrashTest` (fields/callbacks dropped, sheet test deleted),
  `PaparazziPreviewTest` (removed HomeUiState fields dropped),
  `SubScreensMockupTest` (Help FAQ "VIP and coins" → "VIP"),
  `VipRemoteConfigTest` (wallet/streak tests replaced by the `coins` flag
  tests + unknown-key tolerance). Server: `test_coins_engine` gained a
  `CoinsOff` class (5 tests: all six mint/spend routes 403 `forbidden`
  with no ledger move; explicit-zero row is off; finish-watch earns 0
  while the progress write still lands; reads stay open; flipping the
  flag back on restores the grant) and the `Wallet` fixture enables coins
  (`coins_enabled=1`) so the 91 engine tests keep exercising grant/spend
  rules; `test_admin_panel` + `test_launch_phase0` pin `"coins": False`
  in the served `feature_flags` (+ a follows-the-row test);
  `AdminV2/tests/test_unit.py` pins the fresh v2 row `coins_enabled = 0`.
  `ModelsTest` (coin wire models), `RewardedVipPassTest`,
  `ProgressUsageWireTest` (`coins_granted` still in `USAGE_FIELDS` /
  server vocabulary) untouched.
- **Local suite results (CI is the gate).** `test_coins_engine` 96/96,
  `test_admin_panel` 114/114, `test_launch_phase0` 10/10,
  `test_launch_phase1` 10/10, `test_accounts_api` 46/46,
  `test_engagement` OK, `test_docs_invariants` OK,
  `AdminV2 tests.test_unit` 46/46, `tests.test_live_real` 67/67.
- **Follow-up left in the tree, out of scope:** the Help "Watch" FAQ still
  says "Free viewers play up to 720p. 1080p and 4K need VIP." — gate-ON
  copy while the gate is OFF. Flagged, not changed.
