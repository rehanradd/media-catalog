# MX #8 — 200% volume + equalizer: research (2026-11-19)

## The ask

MX Player parity: volume past 100% to 200%, plus an in-player equalizer with
presets. The codebase already models the 0..2 level (`AudioBoost.plan`, 0..2,
`gainDb` excess) and already names the missing piece: `AudioBoostUi` says the
ceiling is the player's own "because this build has no software gain stage",
and `SOFTWARE_GAIN_AVAILABLE = false` is "the one value" a future stage flips.
MX #8 builds that stage.

## What MX Player actually does

- Settings > Audio > "Volume boost" raises the ceiling from 15 steps (100%)
  to 30 steps (200%). It works only with the H/W+ or S/W decoder — i.e. in
  the software audio path, where the player itself amplifies decoded samples.
  (easycodeway, tomsguide/tomshardware threads, TechSpot listing.)
- MX's own guidance: keep it below 26/30 — at full boost already-loud
  masters distort. A boost is a gain, not headroom: it cannot raise the
  hardware ceiling, it amplifies the decoded signal and clips at the DAC.
- MX's EQ is a standard band equalizer with presets; nothing exotic.

## What Media3 1.3.1 offers (the version this app pins)

- **No built-in gain/EQ `AudioProcessor`.** The built-ins are
  `SonicAudioProcessor` (speed/pitch; its volume scaling hard-clips at
  ±32767), `ChannelMixingAudioProcessor`, `TeeAudioProcessor`, and
  `GainProcessor` (gain *automation* for Transformer, not a live control).
- **There is no `ExoPlayer.Builder.setAudioProcessors` in Media3.** The
  injection point is `DefaultRenderersFactory.buildAudioSink`, overridden to
  return `DefaultAudioSink.Builder(...).setAudioProcessors(...)`. Verified
  against the 1.3.1 tag source (`androidx/media@1.3.1`):
  `buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)`
  — 3 args, no `enableOffload` in 1.3.1. The official Transformer docs show
  the same override shape.
- `BaseAudioProcessor` (verified from source): subclass implements
  `onConfigure` (returns the output `AudioFormat`, or `NOT_SET` for
  passthrough) and `queueInput` (writes via `replaceOutputBuffer`, then
  `flip()`); `onFlush`/`onReset` clear per-stream state (seek = flush, so
  filter state must reset there). `configure()` returns `NOT_SET` when the
  processor reports inactive.
- `DefaultAudioSink` applies user processors in order, before its own
  conversion. A processor that always outputs `PCM_FLOAT` gives the chain a
  fixed format, so gain/EQ changes apply live on the next buffer with no
  reconfigure. Offload is disabled automatically when processors are present;
  this factory never enables it anyway.

## Rejected alternatives

- **`android.media.audiofx.LoudnessEnhancer`** (target gain in mB, attaches
  to the audio session, compresses out-of-range samples): device-dependent
  implementation, whole-session scope, needs the session id, no EQ. It is
  what the Play Store "volume booster" apps use — and why their results vary
  by phone. Not deterministic enough for a player feature.
- **`android.media.audiofx.DynamicsProcessing`** (the Wavelet / Poweramp EQ /
  Equalizer314 route, system-wide EQ via AudioEffect, API 28+): same
  objections — session-scoped, device-dependent, needs
  `MODIFY_AUDIO_SETTINGS`, and it would colour every app on the phone, not
  this player. Equalizer314 (GPL-3.0, F-Droid) confirms the API works, but it
  is a system-wide tool, not an in-player one.
- **`android.media.audiofx.Equalizer`**: same session-scoped objections, and
  its band count/frequencies are device-defined (5 bands on some phones,
  different on others) — presets would not be portable.
- **Prebuilt library**: no maintained pure-Kotlin EQ library exists; the
  maintained EQ apps are system-wide tools (Wavelet, Poweramp EQ) or
  GPL-3.0. Nothing fits an in-player, per-playback EQ. Build custom.

## Decision: custom in-pipeline `AudioProcessor`

- One `BaseAudioProcessor` subclass, owned by `PlaybackService` as a single
  instance, injected in `buildPlayer`'s renderers factory via a
  `buildAudioSink` override. The same instance is re-injected on every live
  rebuild (HW/SW switch), and its settings live in `PlayerSessionStore`
  (the decoder-mode pattern), so a rebuild never loses the mix and never
  needs a restart to apply one.
- Input: PCM 16/24/32-bit int and float, any channel count/sample rate the
  decoder emits. Output: always `PCM_FLOAT`, same channels/rate. The float
  round-trip is exact at unity gain (×1.0) and flat EQ (0 dB biquads reduce
  to the identity in exact float arithmetic — verified by unit test).
- Gain: a single linear multiplier 1.0–2.0 for the boost excess, i.e. the
  part of `level × userVolume` above 1.0. Split rule (pure, tested):
  `total = clamp(level, 0..2) × userVolume`; `playerGain = min(total, 1)`;
  `dspGain = total / playerGain` (1 when total is 0). `player.volume` keeps
  the 0..1 contract; the product is exactly the requested level.
- Soft clip, not hard clip: samples past ±1.0 after gain follow
  `sign × (1 + e / (1 + e))` with `e = |x| − 1` — continuous at 1.0 with
  slope 1, monotonic, approaching 2.0 asymptotically. Loud masters still
  compress at 200%, but without the harsh digital clipping Sonic's
  `scaleSamples` produces. Documented in the file header.
- EQ: 5 RBJ Audio-EQ-Cookbook biquads (public-domain formulas, same as
  FFmpeg `af_biquads`, Web Audio, JUCE): 60 Hz low shelf, 230 Hz / 910 Hz /
  3.6 kHz peaking (Q 1.0), 14 kHz high shelf. Gains −12..+12 dB.
  Presets: Flat (all 0), Normal (gentle +1/+1 smile), Bass Boost
  (+6/+4/+1/0/0), Treble Boost (0/0/+1/+4/+6), Vocal (−3/0/+4/+3/−2).
  Any slider move off a preset becomes Custom. EQ on/off is a toggle; off
  means flat (the processor stays in the chain, identity).
- Per-channel biquad state, rebuilt when the sample rate or the gains
  change; state resets on flush (seek) and on reset.
- Persistence: DataStore, next to `audio_boost_level` (`player_eq_state`,
  one string: `on|off:preset|custom:g1,g2,g3,g4,g5`). Loaded by the
  ViewModel at start, pushed to the store, applied live.
- UI: one row in the player's More sheet (Audio section) opening an EQ
  sheet — no new TopBar button (chrome diet). The audio-level slider in
  the Tracks sheet widens to 200% automatically once
  `SOFTWARE_GAIN_AVAILABLE` flips to true (`MAX_OFFERED_LEVEL` becomes 2.0).
  All new strings are `player_*` resources (PlayerChromeCopyTest ratchet).
- Kids Lock: the EQ row and sheet live behind the same lock gate as the
  other player controls — opening them while locked requests the PIN, the
  way `setLocked(false)` already does for the rest of the chrome.
- Cost: 5 biquads × 2 channels ≈ 10 multiply-adds per sample — negligible
  next to decode. No new dependency, no new permission, no new endpoint.

## Known limits (documented, not hidden)

- 200% on an already-loud master still compresses (soft clip tames it, it
  does not create headroom that is not there). MX says the same thing.
- The float round-trip is exact at unity, but the chain now always runs
  through float even when the feature is off — a 1-LSB-level change at most,
  inaudible; the alternative (flipping the chain format at runtime) cannot
  apply live without a flush.
- EQ shapes tone only; it cannot fix a bad source, and it does not touch
  the video path.
- Device verification (real phone: boost audibly louder, EQ presets audible,
  rebuild keeps the mix) is owed — CI cannot hear.

## Sources

- MX Player volume boost (HW+/SW only, keep below 26/30):
  https://www.easycodeway.com/2016/08/bypass-default-volume-limit-in-android.html
- LoudnessEnhancer API reference:
  https://developer.android.com/reference/android/media/audiofx/LoudnessEnhancer
- DynamicsProcessing route (Wavelet/Poweramp EQ/Equalizer314):
  https://f-droid.org/packages/com.bearinmind.equalizer314/
- Media3 Transformer docs (buildAudioSink override shape):
  https://developer.android.com/media/implement/editing-app
- `BaseAudioProcessor` source (release branch):
  https://raw.githubusercontent.com/androidx/media/release/libraries/common/src/main/java/androidx/media3/common/audio/BaseAudioProcessor.java
- `DefaultRenderersFactory.buildAudioSink`, 1.3.1 tag (3-arg form):
  https://raw.githubusercontent.com/androidx/media/1.3.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultRenderersFactory.java
- RBJ Audio-EQ-Cookbook formulae:
  https://modlfo.github.io/vult/pages/audio-eq-cookbook.html
