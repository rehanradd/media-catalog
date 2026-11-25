# Chromecast spike — E2 slice 3 (2026-11-18)

Branch: `muse/e2-chromecast-spike-20261118`, from main `6c9527f2`.
E1 audit gap #3: no cast UI at all; the server already ships a `chromecast`
flag (default `true`) that the client deliberately does not mirror
(`RemoteConfigWatcher.kt` — "this build has no cast UI at all").

## Verdict

**NOT YET PROVEN — and this spike cannot prove it.** The wiring is in place
and CI is the only gate that can confirm it compiles: the dependency, the
`OptionsProvider`, the manifest entry, and a `MediaInfo` built from the same
`PlaybackInfo` the ExoPlayer path already uses. But **no receiver playback
has been observed anywhere** — there is no Cast device on this VM, no
emulator has a Cast receiver, and the receiver fetches the media itself, so
only a real device (or a real receiver) can say whether a resolved Radd
Stream URL plays. Do not build the cast button on this spike alone.

## What was built (spike only, App/ only)

- `App/gradle/libs.versions.toml` — `play-services-cast-framework:21.4.0`.
  **21.4.0, not the latest (23.0.0, Oct 2026):** Google's Cast integrate doc
  says the current SDK needs minSdk 24, and this app is minSdk 21
  (house rule, `App/AGENTS.md` §13.9). 21.4.0 is the line that fits minSdk 21
  (it ships in minSdk-21 apps in the wild). Bumping to 22.x/23.x means
  dropping Android 5/6/7 — an owner decision for the follow-up slice.
- `App/app/build.gradle.kts` — the dependency, nothing else.
- `App/app/src/main/kotlin/com/raddstream/app/player/CastSpike.kt`
  - `RaddCastOptionsProvider` — names the **Default Media Receiver**
    (`CC1AD845`, `CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`).
    No Cast console registration, no payment, no custom receiver.
  - `castContentType(playback)` — HLS → `application/x-mpegurl`,
    DASH → `application/dash+xml`, progressive → `video/mp4` (receiver sniffs).
    Same server knowledge as `PlaybackMediaItemFactory.manifestMimeType`, same
    reason: our play URLs are extensionless redirectors, so the receiver
    cannot infer the container.
  - `buildCastMediaInfo(playUrl, title, playback)` — the LOAD payload:
    redirector URL, buffered stream type, declared content type, title.
  - `CastSpike.loadOnReceiver(context, playback, title, positionMs)` —
    hands the `MediaInfo` to the session's `RemoteMediaClient` at the
    viewer's current position. Returns `Loaded` when the LOAD was **sent**,
    not when the receiver plays — playback state is async and unobserved
    here. `isReceiverConnected(context)` reports whether a session is live.
- `App/app/src/test/kotlin/com/raddstream/app/player/CastSpikeTest.kt` — 9 JVM
  tests: content-type mapping (protocol + extension fallback), the `CC1AD845`
  pin, and the `MediaInfo` payload round-trip.
- `App/app/src/main/AndroidManifest.xml` — the `OPTIONS_PROVIDER_CLASS_NAME`
  meta-data pointing at the provider.
- `App/app/proguard-rules.pro` — keeps the provider: it is referenced only
  by the manifest string, so R8 would otherwise strip it from release builds.

Deliberately NOT touched: `PlayerScreen` / `PlayerViewModel` (no button, no
wiring into the player), `RemoteConfigWatcher` (the server flag stays
unmirrored until the button exists), subtitles/DRM/queue (one video, watch
whether it plays first).

## Research findings (what the spike had to get right)

1. **The receiver fetches the media, not the phone.** The sender only sends
   a `MediaInfo` with a URL; the Default Media Receiver (Shaka-based) GETs
   that URL itself. A `localhost`/LAN-only URL can never play; our
   redirector is public, so that part is fine.
2. **302 redirects are followed by the receiver's player** — a redirector
   URL is a legitimate `contentId`. The open question is not the 302, it is
   what the final response carries (see 3 and 4).
3. **CORS is the likely killer.** The Default Media Receiver refuses media
   whose final response lacks `Access-Control-Allow-Origin`: it accepts the
   LOAD, reports `loading`, then drops to idle with `idleReason: error` and
   never mentions CORS. Our server sends **no** CORS headers
   (`CORS_ALLOW_ORIGINS` is an empty `frozenset` in `redact.py`), and the
   302 means the verdict belongs to the **file host**, not our server. The
   server already records the answer per link as `PlaybackInfo.corsOpen`
   (probed by `link_health.py`) — **the follow-up slice must cast
   `corsOpen` links first, and expect non-CORS hosts to fail on the
   Default Receiver**. A custom receiver could work around it; the Default
   Receiver cannot.
4. **Presigned URLs work only while fresh.** FSL-style R2 links carry their
   auth in query params, which travel inside the URL — but they expire
   (FSL ~5h, FSLv2 ~5h). Cast a just-resolved handle; never hand the
   receiver a stored presigned URL. `PlaybackInfo.isExpired` is the check.
   Correction 2026-11-24: FSL is ~5h — fresh mints measured
   `X-Amz-Expires=18000`, not 28800 (8h); the 8h rows came from a
   different minting path.
5. **No free way to verify receiver playback without a device.** Google
   offers no Cast receiver emulator. The community `chromecast-device-emulator`
   (npm) emulates the *receiver app* (the web page), not the media pipeline,
   so it cannot prove a video plays. An Android TV / Google TV device on the
   same Wi-Fi is the honest test rig; a second Android phone running a
   receiver app (e.g. YouMap) is an untested fallback, not a proof.
6. **The receiver app ID needs no registration.** `CC1AD845` is public and
   free; only a *custom* receiver needs the Cast Developer Console (free
   account, but a hosted HTTPS receiver URL + device registration).

## What remains unproven (real-device test — the exact next step)

On a phone with this branch's debug APK, on the same Wi-Fi as a
Chromecast / Google TV:

1. Open a title that plays on the phone (proves the handle resolves).
2. Pick the receiver from the system Cast picker (the framework's
   `MediaRouteButton` — the follow-up slice adds it to the player overflow
   at `PlayerScreen.kt:4244`; for the spike test, drive `CastSpike`
   from a temporary call site or the follow-up slice's button).
3. Call `CastSpike.loadOnReceiver` with the **same rendition the phone just
   played**, at the phone's current position.
4. Confirm, in order: receiver shows the title art → video plays with
   sound → pause/seek from the phone works → disconnect returns playback
   to the phone at the same position.
5. Repeat for each host family, and record per host: **plays / idle-error
   (CORS) / 404 / hangs**. Start with a `corsOpen=true` HLS link and a
   `corsOpen=true` MP4 link; then try a `corsOpen=false` link to confirm
   the CORS failure mode is real and not a code bug. Also cast one FSL
   presigned link fresh, and the same link after its expiry, to pin the
   freshness rule.

## Follow-up slice (only if the device test plays)

Cast button in the player overflow, mirror the server `chromecast` flag in
`RemoteConfigWatcher`, prefer `corsOpen` renditions for cast (fall back to
phone playback with an honest message when none is CORS-open), observe
media status for play/pause/seek, disconnect returns to phone. If the
device test fails on CORS for the hosts that matter, the slice is a
**custom receiver** (or no cast at all) — that is an owner decision, not
more sender code.
