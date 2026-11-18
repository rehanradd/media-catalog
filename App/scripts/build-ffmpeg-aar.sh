#!/bin/bash
#
# MX idea #1 (2026-11-18): build the Media3 FFmpeg software audio decoder
# AAR from source, for the Radd Stream app.
#
# The FFmpeg extension is NOT on Maven — its native libraries must be built
# (Media3 1.3.1 + FFmpeg 6.0, audio decoders AC-3 / E-AC-3 / DTS / TrueHD /
# MLP, arm64-v8a + armeabi-v7a only). This script clones both, builds the
# FFmpeg static libraries with the trimmed build-ffmpeg.sh next to it,
# patches the decoder_ffmpeg module to the two ARM ABIs, builds the AAR
# with Gradle, and drops it at App/app/libs/media3-ffmpeg-1.3.1.aar.
#
# It is idempotent: if a verified AAR already exists (restored from the CI
# cache, or built by an earlier run) it skips the whole build. It FAILS
# LOUDLY if it cannot produce a verified AAR — a missing AAR must never
# silently pass CI, because the app would then ship without the software
# decoder and AC-3/DTS titles would show the give-up card again.
#
# Requirements: git, java (JDK 17), unzip, ANDROID_HOME with
# platforms;android-34, cmake;3.22.1 and ndk;26.2.11394342 installed.
# The NDK path defaults to $ANDROID_HOME/ndk/26.2.11394342 and can be
# overridden with ANDROID_NDK_HOME.
#
# Usage: bash App/scripts/build-ffmpeg-aar.sh
# (from the repo root; paths are resolved from this script's location).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(dirname "$APP_DIR")"

MEDIA3_VERSION="${MEDIA3_VERSION:-1.3.1}"
FFMPEG_BRANCH="${FFMPEG_BRANCH:-release/6.0}"
NDK_VERSION="${NDK_VERSION:-26.2.11394342}"
ANDROID_ABI="${ANDROID_ABI:-21}"
HOST_PLATFORM="${HOST_PLATFORM:-linux-x86_64}"
# FFmpeg decoder names: DTS is "dca" (there is no "dts" decoder — a
# --enable-decoder=dts would fail configure). "mlp" is included even
# though FfmpegLibrary has no audio/mlp mapping in 1.3.1; it is tiny and
# harmless, and a future Media3 may claim it.
DECODERS=(ac3 eac3 dca truehd mlp)

AAR_NAME="media3-ffmpeg-1.3.1.aar"
AAR_OUT="${AAR_OUT:-$APP_DIR/app/libs/$AAR_NAME}"
WORK_DIR="${FFMPEG_BUILD_DIR:-$(mktemp -d)}"
MEDIA3_DIR="$WORK_DIR/media"
FFMPEG_DIR="$WORK_DIR/ffmpeg"

# The two .so files a valid AAR must contain — and the ones it must not.
EXPECTED_SOS=("jni/arm64-v8a/libffmpegJNI.so" "jni/armeabi-v7a/libffmpegJNI.so")
FORBIDDEN_SOS=("jni/x86/libffmpegJNI.so" "jni/x86_64/libffmpegJNI.so")

verify_aar() {
    local aar="$1"
    [ -f "$aar" ] || return 1
    local listing
    listing="$(unzip -l "$aar" 2>/dev/null)" || return 1
    local so
    for so in "${EXPECTED_SOS[@]}"; do
        echo "$listing" | grep -q "$so" || return 1
    done
    for so in "${FORBIDDEN_SOS[@]}"; do
        echo "$listing" | grep -q "$so" && return 1
    done
    return 0
}

if verify_aar "$AAR_OUT"; then
    echo "FFmpeg AAR already built and verified at $AAR_OUT — skipping."
    unzip -l "$AAR_OUT" | grep 'libffmpegJNI.so'
    exit 0
fi

# A stale or partial AAR must not survive: a later step would mistake it
# for a good one.
rm -f "$AAR_OUT"

if [ -z "${ANDROID_HOME:-}" ]; then
    echo "ANDROID_HOME is not set — the Media3 Gradle build needs the SDK." >&2
    exit 1
fi
# Prefer the pinned NDK (r26b — the NDK Media3 1.3.1 was tested with) when
# it is installed. setup-android sets ANDROID_NDK_HOME to the LATEST
# preinstalled NDK (27.x on the GitHub runner), which must not silently
# override the pin; ANDROID_NDK_HOME is only a fallback. (Proven by
# Android CI 44377426180: the first run used NDK 27.3.13750724 via
# ANDROID_NDK_HOME instead of the pinned r26b.)
if [ -d "$ANDROID_HOME/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/$HOST_PLATFORM/bin" ]; then
    NDK_PATH="$ANDROID_HOME/ndk/$NDK_VERSION"
elif [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_PLATFORM/bin" ]; then
    echo "Pinned NDK $NDK_VERSION not installed; falling back to ANDROID_NDK_HOME ($ANDROID_NDK_HOME)."
    NDK_PATH="$ANDROID_NDK_HOME"
else
    echo "No usable NDK found: install ndk;$NDK_VERSION or set ANDROID_NDK_HOME." >&2
    exit 1
fi

echo "Work dir: $WORK_DIR"
mkdir -p "$WORK_DIR" "$(dirname "$AAR_OUT")"

echo "Cloning Media3 $MEDIA3_VERSION ..."
git clone --depth 1 --branch "$MEDIA3_VERSION" \
    https://github.com/androidx/media.git "$MEDIA3_DIR"

echo "Cloning FFmpeg $FFMPEG_BRANCH (GitHub mirror) ..."
git clone --depth 1 --branch "$FFMPEG_BRANCH" \
    https://github.com/FFmpeg/FFmpeg.git "$FFMPEG_DIR"

MODULE_MAIN="$MEDIA3_DIR/libraries/decoder_ffmpeg/src/main"
echo "Linking FFmpeg source into the decoder module ..."
ln -s "$FFMPEG_DIR" "$MODULE_MAIN/jni/ffmpeg"

echo "Building the FFmpeg static libraries (arm64-v8a + armeabi-v7a, decoders: ${DECODERS[*]}) ..."
bash "$SCRIPT_DIR/build-ffmpeg.sh" \
    "$MODULE_MAIN" "$NDK_PATH" "$HOST_PLATFORM" "$ANDROID_ABI" "${DECODERS[@]}"

# The module's Gradle build defaults to four ABIs (arm64-v8a, armeabi-v7a,
# x86, x86_64 — from the shared library config); the FFmpeg libraries were
# only built for the two ARM ones, so the CMake build for x86/x86_64 would
# fail on the missing .a files. Patch the module to the two shipped ABIs.
# The marker keeps the patch idempotent across re-runs.
MODULE_BUILD_GRADLE="$MEDIA3_DIR/libraries/decoder_ffmpeg/build.gradle"
ABI_PATCH_MARKER="// RADD: ship arm64-v8a + armeabi-v7a only (MX idea #1)"
if ! grep -q "RADD: ship arm64-v8a" "$MODULE_BUILD_GRADLE"; then
    echo "Patching the decoder module to arm64-v8a + armeabi-v7a ..."
    cat >> "$MODULE_BUILD_GRADLE" <<'EOF'

// RADD: ship arm64-v8a + armeabi-v7a only (MX idea #1) — the FFmpeg
// libraries were built for these two ABIs only, and the app ships these
// two. Applied after the shared library config, so it replaces its
// four-ABI list. Without it the CMake build fails on the missing x86
// .a files, and the AAR would carry ABIs the app never ships.
android {
    defaultConfig {
        ndk {
            abiFilters 'arm64-v8a', 'armeabi-v7a'
        }
    }
}
EOF
fi

echo "Building the decoder AAR with Gradle ..."
cd "$MEDIA3_DIR"
./gradlew :lib-decoder-ffmpeg:assembleRelease --no-daemon --console=plain

BUILT_AAR="$MEDIA3_DIR/libraries/decoder_ffmpeg/build/outputs/aar/lib-decoder-ffmpeg-release.aar"
if [ ! -f "$BUILT_AAR" ]; then
    echo "Gradle finished but the AAR is missing at $BUILT_AAR." >&2
    exit 1
fi

echo "Verifying the built AAR ..."
if ! verify_aar "$BUILT_AAR"; then
    echo "The built AAR does not contain exactly the two ARM libffmpegJNI.so files:" >&2
    unzip -l "$BUILT_AAR" | grep -i 'libffmpegJNI\|jni/' >&2 || true
    exit 1
fi

cp "$BUILT_AAR" "$AAR_OUT"
echo "FFmpeg AAR built: $AAR_OUT ($(du -h "$AAR_OUT" | cut -f1))"
unzip -l "$AAR_OUT" | grep 'libffmpegJNI.so'
echo "Work dir kept at $WORK_DIR (ephemeral on CI runners)."
