# FFmpeg — software audio decoder (MX idea #1, 2026-11-18)

The Radd Stream app bundles FFmpeg libraries so titles whose audio
(AC-3, E-AC-3, DTS, TrueHD, MLP) the device's hardware decoder cannot
decode still play, through Media3's FFmpeg decoder extension
(`androidx.media3.decoder.ffmpeg`).

## What is bundled

- **FFmpeg 6.0** (`git clone https://github.com/FFmpeg/FFmpeg.git`,
  branch `release/6.0`) — libraries `libavcodec`, `libavutil`,
  `libswresample`, built from source.
- **Media3 1.3.1** (`https://github.com/androidx/media`, tag `1.3.1`) —
  the `lib-decoder-ffmpeg` module, which provides the JNI wrapper
  (`libffmpegJNI.so`) and the `FfmpegAudioRenderer`.
- Only **audio decoders** are enabled: `ac3`, `eac3`, `dca` (DTS),
  `truehd`, `mlp`. No video decoders, no encoders, no muxers.
- ABIs shipped: `arm64-v8a`, `armeabi-v7a` only.

## License

FFmpeg is licensed under the **GNU Lesser General Public License
version 2.1** (LGPL-2.1). The build enables only LGPL components — no
GPL-licensed decoders, encoders, filters or libraries are included
(`--disable-everything` plus the five decoders listed above; no
`--enable-gpl`, `--enable-nonfree` or `--enable-libx264`/`libx265`/etc.).

The full LGPL-2.1 text is at
https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html and in the
FFmpeg source tree (`COPYING.LGPLv2.1`).

## How to rebuild the bundled libraries (source + configuration)

The exact build is reproducible from this repository:

1. `App/scripts/build-ffmpeg-aar.sh` — clones Media3 1.3.1 and
   FFmpeg `release/6.0`, builds the FFmpeg static libraries and the
   `lib-decoder-ffmpeg` AAR, and drops it at
   `App/app/libs/media3-ffmpeg-1.3.1.aar`.
2. `App/scripts/build-ffmpeg.sh` — the trimmed FFmpeg configure/build
   script (a trimmed copy of Media3 1.3.1's `build_ffmpeg.sh`, arm64-v8a
   + armeabi-v7a only, audio decoders only).

Rebuilding from those two scripts with the versions above produces
object-identical libraries (modulo build timestamps). The .so files
in the APK can be replaced with a user's own build of the same
version without rebuilding the app: the app loads the FFmpeg
extension by reflection and degrades gracefully if it is absent.

## Legal note

The LGPL obligations (providing the library source, allowing the
library to be replaced, and shipping the license text) are met by
this file and the build scripts above. This is not legal advice — the
owner may want a legal review before shipping a release that bundles
these libraries.
