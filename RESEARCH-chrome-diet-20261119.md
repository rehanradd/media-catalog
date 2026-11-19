# MX #6 — Chrome Diet: research + plan

**Date:** 2026-11-19 · **Branch:** `muse/mx-chrome-diet-20261119` · **Base:** main `07a8a315`
**Scope:** `App/app/src/main/kotlin/com/raddstream/app/ui/screens/PlayerScreen.kt` (5,226 lines) — the TopBar + BottomControls bars and the More sheet. No playback logic, no resolve path, no wire changes.
(Note: repo-root `RESEARCH.md` is MX #4's committed file — this slice uses its own name rather than overwriting it.)

## 1. What the best players do (researched first)

- **Eleken, "Video Player UI: Best Examples, Patterns & UX Tips" (2026)** — the biggest shift vs older players is what *isn't* visible: early interfaces showed every control all the time; modern players hide most controls until users need them. Mobile relies on gestures (double-tap skip, pinch zoom, swipe seek, tap to reveal); the interface appears only when users ask for it. Netflix is the benchmark: large hit targets, subtle controls, advanced controls intentionally limited. YouTube's Oct 2025 redesign: semi-transparent pill-shaped controls grouped in a pill container; accessibility setting hides controls after 3s / 30s / never. Key principle: **predictability — users should never wonder how to bring controls back.** A player is a state machine; the best playback UI is often almost invisible.
- **MX Player (REA report, `~/workspace/mx-player-rea-20261118/`)** — (b) UI principles: (1) *the screen IS the control* — invisible controls beat visible ones; (2) one gesture map everywhere (left vertical = brightness, right vertical = volume, horizontal drag = seek with preview, pinch = zoom, double-tap = play/pause); (4) *minimal player chrome; power one level down* — tap for a small control bar (play/pause, seek bar, elapsed/remaining time, HW/SW decoder button top-right, zoom, lock, subtitle/audio menus); long-press shortcuts (play = background play, zoom = video zoom, lock = lock-target); (5) what it deliberately does NOT show — the player hides all chrome during playback except a minimal control bar on tap.
- **Our standing rules** — player identity (2026-11-06): exactly 6 primaries, auto-hide 3.0s, jade accent, start 2s hint / 8s retry / 30s give-up. Our auto-hide is already 3.0s (`PlayerScreen.kt:493,519`) — matches YouTube's default; no change.

## 2. Audit of our chrome today (main `07a8a315`)

| Zone | Contents | Height |
|---|---|---|
| TopBar | Back · title + subtitle (year · quality · Auto) · sleep icon (countdown only) · speed pill (≠1× only) · lock · print-switch (source) · HW/SW decoder chip | min **104dp** + 3-stop gradient |
| BottomControls | cast strip (paused only) · Keep chip row (when keep available) · scrubber + time row (position · status word · duration) · transport row of exactly 6 primaries (Play/Pause 40dp, Volume + long-press dialog, Subtitles, Quality pill, Fullscreen, More) | min **136dp** + 3-stop gradient |
| Centre | CenterPlayPause (paused only) + two ±10s skip buttons, 56dp frosted circles (VOD only) · CenterSpinner | over picture |
| Transient | Skip pill (MX #4, bottom-right, transient layer) · ScrubToast · SubtitleToast · BoostBadge · EdgeFeedbackLine · EdgeHud (Ghost) · UnlockFab · LockedTimeChip · narration/settled banner · GiveUp/Connection/Offline cards · AutoplayCountdownCard | as needed |
| Sheets | Speed, Quality, Sleep, SubtitleSettings (+offset), Tracks, **More (30+ rows, 4 sections)**, PrintLog (debug, long-press), PlayFrom dialog, volume dialog | one level down |

Honest note: the chrome is already fairly disciplined — six-primaries rule, auto-hide 3s, centre button paused-only, Ghost mode. The fat is concentrated in the two bars: the TopBar forces ~24dp of dead gradient (min 104dp vs ~56dp content + status-bar padding) and carries a rarely-used source-switch shortcut that duplicates a More-sheet row; the bottom bar spends a whole text row on times that can flank the scrubber.

## 3. The diet (what changes)

- **D1 — TopBar: drop the print-switch (source) button.** It opens the print sheet, and the More sheet already has the identical "Prints" row (same action). A source switch mid-play is rare; one extra tap via More is the MX "power one level down" rule. Saves one 48dp slot; the title gains room on narrow phones (the P-35 keep-chip squeeze proved the title is the tightest thing in this bar). No test pins the TopBar button — tests only pass the `onShowPrintSheet` lambda. Goldens `playerTopBar`, `playerTopBarLive` change → record run.
- **D2 — TopBar: min height 104dp → 80dp.** Content is title (~28dp) + subtitle (~16dp) + 12dp padding ≈ 56dp; `statusBarsPadding()` adds the system bar on top. The 104dp floor forces ~24dp of empty gradient on every title, live or VOD. 80dp keeps the bar roomy while cutting the dead space. Goldens change → record.
- **D3 — BottomControls: merge the time row into the scrubber row.** Position (+ status word, as `0:00 · Paused`) flanks the scrubber on the left, duration on the right — the YouTube pattern. Removes one full text row (~20dp + spacing) from the bottom bar. All six primaries, the scrubber (with chapter ticks + preview), the times and the status word stay. Needs a `modifier: Modifier = Modifier` parameter on `Scrubber` (it hardcodes `fillMaxWidth()` today). Goldens `playerBottomPaused/Playing/Live/SingleRung` change → record. Existing tests (`vod bottom row shows current and total time`, six primaries) still pass — the texts are still present.
- **D4 — More sheet: hide "Share a moment" for local bundles (player quick-win F3).** The VM's own `shareMoment()` doc calls the row-hiding "the first door" — it was never wired, so the row renders unconditionally and taps are a no-op for local playback. Gate the row on a pure predicate `canShareMoment(state)` (streamId non-blank and ≠ `"local"`, mirroring the VM guard). Unit-tested directly.

## 4. Considered and rejected (documented, not forgotten)

- **Centre ±10s skip buttons** — hate-audit H5/H13: the hidden double-tap alone was undiscoverable and accident-prone; the buttons are the discoverable form. The gesture still seeks; the buttons stay.
- **Keep chip row** — a keep can be set mid-play; hiding it while playing would break a real workflow. Stays.
- **Speed pill (TopBar)** — review D5: a 1.5× viewer otherwise opens More → Speed every title. The pill is the shortcut that earns its place. Stays.
- **HW/SW decoder chip (TopBar)** — MX #3 signature, and MX itself puts it top-right. Stays.
- **Sleep icon** — transient, only while a countdown runs. Stays.
- **Auto-hide 3.0s** — matches YouTube's default; shortening it hurts discoverability. Stays.
- **Ghost EdgeHud** — already minimal (one edge strip). Stays.

## 5. Verification plan

- Update `TopBar` call sites (main + tests + previews) for the dropped `onShowPrintSheet` parameter.
- New tests: TopBar carries no source-switch button; `canShareMoment` predicate (local / blank / remote streamId); existing time + six-primary tests keep passing.
- Paparazzi record run (workflow 418582550) for the changed goldens; commit only visibly-changed goldens.
- Android CI green on the branch tip; Backend CI untouched (App-only diff).
- Owed after merge: device verify (bars on a real phone, narrow portrait title, landscape bottom bar).
