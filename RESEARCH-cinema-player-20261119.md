# Cinema Player — research (2026-11-19)

User's rule (16:20 PKT 19 Nov, in MEMORY.md): the player must feel like a cinema —
video 95% of the screen, controls 5%; feedback elements very solid and slim like MX
Player; NO big volume/brightness cards (users already know the gestures); remove
borders from buttons, clean solid glyphs, no outlines. Keep only a tiny fast-fading
edge indicator for volume/brightness, and keep invisible 48dp touch targets while
visible glyphs shrink.

## What MX Player actually does (REA report + web, Nov 2026)

- **The screen IS the control.** One small control bar on tap (play/pause, seek bar,
  time, HW/SW button top-right, zoom, lock); power one level down in Settings.
  "No pop ups when you pause, no animated banners sitting over the controls… the
  frame is filled with the picture" (dlandroid MX Player Pro review).
- **Gesture feedback is a small edge HUD, not a card.** Left-half vertical drag =
  brightness, right-half = volume, with MX's own small HUD — a thin vertical bar
  with an icon at the screen edge — never the system volume UI and never a card
  over the middle of the picture. Horizontal drag = seek with a small time
  preview. The feedback is gone in well under a second.
- **Minimal players in general (Fabricator UI, Clapper, Screenbox):** controls fade
  ~2.5s after the pointer rests; the seek bar is a thin line that grows only on
  hover/drag; the compact variant keeps play + slim seek + time + mute +
  fullscreen on ONE row; touch targets stay 44px+ even when the visible glyph
  is 16–20px.
- **What MX deliberately does NOT show:** no poster/metadata catalogue on the
  player, no upsell inside playback, no editor. The player hides all chrome
  during playback except a minimal bar on tap.

## Our starting point (main 43799cff) — measured, not assumed

- The big centre volume/brightness card is **already gone**: commit 24de5e01
  ("Player redesign 1/7: edge feedback line replaces centre HUD card") replaced
  it with `EdgeFeedbackLine` — a 4dp × 120dp track + a 12sp "Volume 75%" text
  label, 800 ms lifetime (`LaunchedEffect(hud)`, `delay(800)`). This slice
  finishes that job: text label → tiny 16dp icon, track 3dp × 96dp.
- Borders live in exactly 10 sites in `PlayerScreen.kt` (grep `border(`/`BorderStroke`):
  QualityPill, scrubber thumb ring, UnlockFab pill, LockedTimeChip, LiveMark,
  start-notice card, give-up dialog, subtitle colour swatch rings (×2), ResumeChip.
  TopBar texts (speed pill, HW/SW chip), Skip pill, toasts and KeepChip are
  already borderless. More-sheet rows (`SheetRow`) have no border. The sheet
  container (`PlayerSheet.kt:79`, 1dp white 0.08) and the shared `RaddChip`
  (cast strip) are left alone — shared components, not player chrome.
- Chrome heights (layout constants, the honest measure): TopBar min 80dp +
  BottomControls min 136dp = **216dp**. Reference landscape screen (PIXEL_5,
  1080px / 2.625 = 411dp tall): chrome covers **52.6%** of the picture height
  while it is up; video is 47.4%. Cross-check from the goldens: the bottom bar's
  dark region in `playerBottomPlaying.png` is 134px of a 461px frame (~29%,
  under-reads because the gradient's transparent top is invisible to the
  threshold).

## The honest 95/5 note

Literal 95/5 while the chrome is showing is **not achievable with tappable
controls**: two bars with 48dp touch targets need ~160dp on a ~411dp-tall
landscape screen — a floor of ~39% coverage. What this slice does:

- TopBar 80 → **56dp** (48dp icon row + 6dp padding).
- BottomControls 136 → **104dp** (scrubber 48 + controls 48 + 8dp padding).
- Total chrome 216 → **160dp: 52.6% → 38.9% coverage, video 47.4% → 61.1%**
  while the chrome is up.
- The real 95%+ video time already comes from the 3.0s auto-hide: the chrome is
  gone for most of watching, and this slice does not touch that timer.
- Visible glyphs shrink (TopBar icons 24→20dp, control icons 26→22dp,
  play/pause 40→36dp, centre button 88→72dp, skip buttons 56→48dp) while every
  press target stays 48dp.

## What this slice changes (all in PlayerScreen.kt unless noted)

1. Borders: remove all 10 sites above. Scrubber thumb becomes a solid white dot
   (the 2dp white ring separated a jade dot from the jade track — white-on-jade
   reads without it). Subtitle colour swatch keeps its check + tinted halo as
   the selection signal; the ring goes.
2. Slim chrome: TopBar 80→56dp, BottomControls 136→104dp, controls-row vertical
   padding 2→0dp, keep-row top padding 8→4dp, scrubber track 4→3dp, thumb
   16→14dp (20→18dp dragging), time text labelMedium→labelSmall.
3. Edge indicator: text label → 16dp icon (BrightnessMedium / VolumeUp /
   FastForward for Boost), track 3dp × 96dp, 800ms lifetime kept. Subtitle
   toast + scrub toast: 14→12sp, padding 14×6→10×4dp, seat 130→116dp (the
   bottom bar is shorter now). Skip pill: padding 14×8→12×6dp, 14→12sp,
   icon 16→14dp, seat 130→116dp. Boost badge: top 92→64dp.
4. Pure rules (new, testable): `CinemaChrome` constants (top 56 / bottom 104 /
   HUD 800ms / touch 48) + `edgeIndicatorSpec(hud)` (Brightness→LEFT,
   Volume→RIGHT, Boost→RIGHT at full, Subtitle→null, fractions clamped) +
   `videoSharePercent(screenHeightDp)`. Tests in
   `App/app/src/test/kotlin/com/raddstream/app/ui/CinemaChromeTest.kt`.
5. Nothing disappears: HW/SW chip, speed pill, lock, subtitles + gestures, Skip
   pill, quality/audio/subtitle sheets, PiP, all More rows stay. Kids Lock chrome
   gate inherited. Auto-hide stays 3.0s.

## Goldens that WILL change (11 — record run after push)

PaparazziPreviewTest: playerTopBar, playerTopBarLive, playerCenterPaused,
playerCenterBuffering, playerEdgeFeedbackVolume, playerEdgeFeedbackBrightness,
playerGhost. PlayerLandscapePaparazziTest: playerBottomPlaying,
playerBottomPaused, playerBottomLive, playerBottomSingleRung. Record via the
Paparazzi workflow (numeric id 418582550), download the artifact, commit only
visibly-changed goldens after pixel-compare + visual read.

## What was NOT copied from MX

- The OTT clutter / ads in the free build (our player has no upsell surface).
- The codec-pack hunt (decoders are built in; see MX #1).
- Ghost mode (`EdgeHud`) already exists as the chrome-less option — this slice
  is about the normal chrome, not a second Ghost mode.
