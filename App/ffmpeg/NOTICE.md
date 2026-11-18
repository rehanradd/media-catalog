# FFmpeg — software audio decoder (MX idea #1, 2026-11-18)

The Radd Stream app bundles FFmpeg libraries so titles whose audio
(AC-3, E-AC-3, DTS, TrueHD, MLP) the device's hardware decoder cannot
decode still play, through Media3's FFmpeg decoder extension
(`androidx.media3.decoder.ffmpeg`).

## What is bundled

- **Jellyfin's prebuilt `media3-ffmpeg-decoder` 1.3.1+2**
  (`org.jellyfin.media3:media3-ffmpeg-decoder:1.3.1+2` on Maven
  Central) — a prebuilt build of Media3 1.3.1's `lib-decoder-ffmpeg`
  module, carrying the same `androidx.media3.decoder.ffmpeg` classes
  (identical `classes.jar` and `proguard.txt`) and the JNI wrapper
  (`libffmpegJNI.so`) plus the FFmpeg native libraries. It tracks
  Media3 1.3.1 exactly, which is the version this app uses; the `+2`
  is Jellyfin's build number, not a version wildcard. Declared as a
  version-catalog entry (`media3-ffmpeg-decoder` in
  `App/gradle/libs.versions.toml`) and wired in `App/app/build.gradle.kts`.
- Only **audio decoders** are enabled in the Jellyfin build's FFmpeg
  configuration (the same five as the original source build: `ac3`,
  `eac3`, `dca` (DTS), `truehd`, `mlp`). No video decoders, no
  encoders, no muxers.
- ABIs shipped: whatever the Jellyfin artifact carries (arm64-v8a,
  armeabi-v7a, x86, x86_64 — the CI build summary reports the
  `libffmpegJNI.so` size per ABI in the APK, so the real set is
  measured on every run). The earlier source build shipped ARM only;
  the prebuilt artifact may add the x86 ABIs, which grows the APK.

## Why a prebuilt artifact (2026-11-19)

The first implementation built the AAR from source in CI (Media3
1.3.1 + FFmpeg 6.0, `App/scripts/build-ffmpeg-aar.sh` +
`App/scripts/build-ffmpeg.sh`). It failed four times in CI — missing
nasm, a blank line breaking the `./configure` invocation, a Maven
Central 429 on the Media3 Gradle build, and the built AAR landing at
an unexpected path. The user's standing order (2026-11-19) is to
search online first on repeated failures instead of fixing blindly:
the search found the prebuilt Jellyfin decoder on Maven Central (and
the NextLib FFmpeg extension as the alternative), which other
projects (siskin, Mica Music, CinefinTV) already use as a drop-in
replacement. The native-build scripts and the CI native-build step
were deleted in the same change. The source-build history is in git
(`App/docs/SESSION_HISTORY.md`).

## License — FLAGGED FOR THE OWNER, not a blocker

- **The Jellyfin artifact is GPL-3.0 licensed.** FFmpeg itself is
  LGPL 2.1+, but Jellyfin's build is distributed under GPL-3.0 (its
  FFmpeg configuration enables GPL components). Depending on a
  GPL-3.0 library has copyleft consequences for the app as a whole:
  distributing the app with this artifact in it generally means the
  app must be distributed under GPL-3.0-compatible terms, with
  source offered.
- **This repository currently has no license file.** That is the
  point to settle before a public release that bundles this
  artifact: either add a GPL-3.0-compatible license and the
  required source offer, or switch to a build that stays LGPL-only
  (the deleted source build enabled only LGPL components — no
  `--enable-gpl`, `--enable-nonfree` or GPL libraries — and its
  scripts are recoverable from git history if the LGPL-only route
  is needed again).
- The full GPL-3.0 text is at https://www.gnu.org/licenses/gpl-3.0.html
  and the LGPL-2.1 text at
  https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html.
- This is not legal advice — the owner may want a legal review
  before shipping a release that bundles this artifact.

## How the app uses it

The app loads the FFmpeg extension by reflection
(`DefaultRenderersFactory` with
`EXTENSION_RENDERER_MODE_PREFER`; `FfmpegSupport` checks
`androidx.media3.decoder.ffmpeg.FfmpegLibrary` availability) and
degrades gracefully if the artifact is ever removed: playback falls
back to MediaCodec-only and the decode-failure retry logic in
`RenditionFailover` simply has no software decoder to try.
