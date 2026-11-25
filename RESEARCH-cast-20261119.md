# MX #9 — Cast (Chromecast): research (2026-11-19)

## Verdict

**Stay on Media3 1.3.1. Add `androidx.media3:media3-cast:1.3.1` and use the
pre-1.9 `CastPlayer` API (`CastPlayer(castContext, converter)` +
`setSessionAvailabilityListener`), with the GMS Cast framework (already in
the app at 21.4.0) for discovery and the picker. Build on the E2 spike
(`CastSpike.kt`) — do not replace it.**

## What the search showed (wide phrasings + typo variants)

- **Media3 1.9.0 (Dec 2025) rewrote `media3-cast`.** The new `CastPlayer`
  (`CastPlayer.Builder(context).setLocalPlayer(exoPlayer).build()`) wraps the
  local ExoPlayer and the remote player in one `Player` and transfers
  automatically; a Compose `MediaRouteButton` ships in `androidx.media3.cast`.
  Google's own Media3 Cast skill says new setups **must** use Media3 Cast,
  media3 ≥ 1.9.0, and must not use the legacy Cast SDK unless explicitly
  requested. The legacy GMS Cast SDK is in maintenance mode.
- **The rewrite is out of reach for this branch.** The app pins Media3
  **1.3.1**, and Jellyfin's prebuilt FFmpeg decoder — the MX #1 sound
  decoder the app depends on — publishes newest at **`1.9.0+1`** (tracks
  Media3 1.9.0, published 2025-12-29). No 1.10/1.11 decoder exists. A
  1.3.1 → 1.9.0 jump also churns the whole player stack (MX #1 decoder,
  MX #3 decoder switch, MX #8 DSP, `setCustomLayout` →
  `setMediaButtonPreferences` in 1.10). That upgrade is its own branch with
  its own CI rounds — not a rider on Cast. The old CastPlayer API is **not
  deprecated at 1.3.1** (the deprecation came with the 1.9 rewrite), and the
  manual-swap code is isolated in one class (`CastTransferManager`), so the
  future upgrade replaces it in one place with the Builder pattern.
- **The pre-1.9 pattern is documented and proven in the wild.** Niels
  Masdorp's `CompositePlayer` gist (the reference for pre-1.9 Media3 Cast):
  `CastPlayer(castContext, converter)`, `setSessionAvailabilityListener`,
  `onCastSessionAvailable() → switchToCast()`,
  `onCastSessionUnavailable() → switchToLocal()`. The current API reference
  still lists those constructors (marked deprecated) and
  `RemoteCastPlayer.isCastSessionAvailable()` /
  `setSessionAvailabilityListener` — the API existed at 1.3.1 in exactly
  this shape.
- **Why `CastPlayer` and not the spike's `loadOnReceiver`.** The spike loads
  once and observes nothing; `CastPlayer` *is* a `Player`, so the existing
  `MediaController` → `MediaSession` → notification / lock-screen / headset
  path drives the receiver with no UI rework, and session-availability
  events give clean connect/disconnect. The spike's foundation (provider,
  content-type mapping, `MediaInfo` builder) is reused by the converter.
- **The button.** `androidx.mediarouter.app.MediaRouteButton` +
  `CastButtonFactory.setUpMediaRouteButton` is the standard GMS approach:
  the framework manages discovery, the device picker dialog, and the
  button's own visibility (it shows only when a Cast route exists). No
  custom discovery code, no custom picker. `CastStateListener` on
  `CastContext` (`NO_DEVICES_AVAILABLE` vs `NOT_CONNECTED`) is the
  Compose-level signal for whether to compose the button at all.
- **No free receiver emulator.** Same finding as the spike: Google offers
  none; the npm `chromecast-device-emulator` emulates the receiver *page*,
  not the media pipeline. An Android TV / Google TV on the same Wi-Fi is
  the honest rig. Device proof is owed and is the point of this slice.

## Spike findings reused (CAST_SPIKE_20261118.md)

- Default Media Receiver `CC1AD845`, no console registration, no payment.
- The receiver fetches the media itself; our play URL is our own public
  redirector, so a 302 is fine — the verdict belongs to the **file host**.
- **CORS is the likely killer:** the Default Receiver refuses media whose
  final response lacks `Access-Control-Allow-Origin` (loads, then drops to
  idle with `idleReason: error`, silently). Our server sends no CORS
  headers; the server already records the answer per link as
  `PlaybackInfo.corsOpen` — cast `corsOpen` links first.
- **Presigned URLs work only while fresh** (FSL ~5h, FSLv2 ~5h — corrected
  2026-11-24: fresh FSL mints measured `X-Amz-Expires=18000`, not 28800).
  Never hand the receiver a stored presigned URL; `PlaybackInfo.isExpired` is the
  check. A kept (loopback) copy can never play on a receiver — the transfer
  must substitute the rendition's live redirector URL.
- play-services-cast-framework 21.4.0 fits minSdk 21; 22.x/23.x need
  minSdk 24 (owner decision, not this slice).

## Design (this branch)

- `player/CastTransfer.kt` — pure state machine (`Local` / `Casting`) +
  handoff math (resume position clamp, ended → 0, expiry refusal,
  kept-copy → live-URL substitution). JVM-tested, no SDK.
- `player/CastMediaItemConverter.kt` — `MediaItemConverter` reusing
  `castContentType` + `buildCastMediaInfo` from the spike. Subtitles are
  NOT carried in v1 (documented; receiver text-track support is a
  follow-up).
- `player/CastTransferManager.kt` — owns the `CastPlayer` (built lazily in
  `runCatching`: no Play Services = no casting, never a crash), observes
  session availability, exposes `isCasting` / `castDeviceName`.
- `PlaybackService` — `activePlayer()` (cast player when casting, else the
  ExoPlayer); the session player swaps via the existing
  `MediaSession.setPlayer` path (same as the MX #3 rebuild). Auto-quality,
  playhead pump and decoder rebuilds pause while casting and resume on
  return. The failover machine's local-only decision
  (`RetryWithOtherDecoderMode`) is converted to an honest handoff while
  casting; hops stay on the receiver.
- `PlayerSessionStore` — `castIsCasting`, `castDeviceName`, `castAllowed`
  flows + `announceCast*` (same raw-string banner pattern as
  `announceDecoderFallback`; the store is not scanned by the copy test).
- Server flag — `RemoteConfigWatcher` now mirrors `flags.chromecast` into
  `FEATURE_CAST_KEY` + `castFeatureEnabled` (the pureCinema pattern,
  default true). The button needs flag + devices + unlocked.
- Kids Lock — the button is not composed when `locked`; the ViewModel
  writes `store.setCastAllowed(!locked)` on every lock change; if the mode
  turns on mid-cast the manager ends the session (normal return-to-local
  path, then the lock applies).
- UI — `MediaRouteButton` (AndroidView + `CastButtonFactory`) in the
  TopBar, composed only when devices are available; the framework hides it
  otherwise, so the MX #6 slim bar stays slim when there is nothing to
  cast to. No new TopBar slot is taken permanently. Lock screen and
  notification come free from the `MediaSession` swap.
- `MediaTransferReceiver` (system output switcher) is deliberately NOT
  added — out of scope; the in-player button is the entry point.

## Open questions for the device test

1. Does the Default Receiver play our redirector URL (302 → host) for a
   `corsOpen=true` HLS link and a `corsOpen=true` MP4 link?
2. Does a `corsOpen=false` link fail exactly as the spike predicts
   (idle-error), proving the failure mode is CORS, not code?
3. Does a fresh FSL presigned link play, and does the same link fail after
   expiry?
4. Does disconnect resume locally at the same position without a restart,
   and does the next episode keep casting?
