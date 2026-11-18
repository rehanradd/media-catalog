#!/bin/bash

#
# Copyright (C) 2019 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
#
# Trimmed copy of Media3 1.3.1's
# libraries/decoder_ffmpeg/src/main/jni/build_ffmpeg.sh
# (https://github.com/androidx/media, tag 1.3.1).
#
# MX idea #1 (2026-11-18): the Radd Stream app needs only the FFmpeg
# SOFTWARE AUDIO decoders (AC-3, E-AC-3, DTS, TrueHD, MLP), and ships only
# arm64-v8a + armeabi-v7a. The upstream script builds four ABIs (armv7a,
# arm64, x86, x86_64); this copy keeps the two ARM blocks and drops the
# x86/x86_64 blocks, so the build is half the work and the AAR carries only
# the two ABIs the app ships. Everything else — the common configure
# options, the toolchain checks, the decoder loop — is upstream, except
# for the two deliberate deviations below.
#
# DEVIATION 1 — --disable-x86asm (added to COMMON_OPTIONS): FFmpeg 6.0's
# configure probes for nasm/yasm even for ARM targets (x86asm is enabled
# by default) and DIES if neither is installed — the GitHub ubuntu-24.04
# runner ships no nasm. For an ARM-only build this is not a cripple at
# all: there is no x86 assembly in an ARM library, and the ARM (NEON)
# assembly is unaffected. Upstream's script does not pass it because
# Media3's environment has nasm; ours does not.
#
# DEVIATION 2 — no blank lines inside the ./configure invocations: the
# upstream file is rendered with blank lines between the \-continued
# option lines, but a truly blank line after a \ continuation TERMINATES
# the command in bash (proven: a ./configure followed by a blank line
# runs with NO arguments). With no arguments configure defaults to the
# HOST arch (x86_64), enables x86, and dies on the nasm probe — which is
# exactly what Android CI 44377426180 and 44380041485 showed (the
# "nasm/yasm not found" failure was never about nasm at all; the
# configure never received --arch=arm). So the two configure blocks here
# are reflowed with no blank lines. The blank lines elsewhere in this
# file (inside the quoted COMMON_OPTIONS string, the for loop, the if
# blocks) are harmless and kept.
#
# Usage (called by build-ffmpeg-aar.sh, not by hand):
#   build-ffmpeg.sh FFMPEG_MODULE_PATH NDK_PATH HOST_PLATFORM ANDROID_ABI ENABLED_DECODERS...
# where FFMPEG_MODULE_PATH is the decoder_ffmpeg module's src/main dir and
# the FFmpeg source is symlinked at $FFMPEG_MODULE_PATH/jni/ffmpeg.
# The static libraries land in jni/ffmpeg/android-libs/<abi>/, where the
# module's CMakeLists.txt expects them.

set -eu

FFMPEG_MODULE_PATH="$1"

echo "FFMPEG_MODULE_PATH is ${FFMPEG_MODULE_PATH}"

NDK_PATH="$2"

echo "NDK path is ${NDK_PATH}"

HOST_PLATFORM="$3"

echo "Host platform is ${HOST_PLATFORM}"

ANDROID_ABI="$4"

echo "ANDROID_ABI is ${ANDROID_ABI}"

ENABLED_DECODERS=("${@:5}")

echo "Enabled decoders are ${ENABLED_DECODERS[@]}"

JOBS="$(nproc 2> /dev/null || sysctl -n hw.ncpu 2> /dev/null || echo 4)"

echo "Using $JOBS jobs for make"

COMMON_OPTIONS="

    --target-os=android

    --enable-static

    --disable-shared

    --disable-doc

    --disable-programs

    --disable-everything

    --disable-avdevice

    --disable-avformat

    --disable-swscale

    --disable-postproc

    --disable-avfilter

    --disable-symver

    --enable-swresample

    --extra-ldexeflags=-pie

    --disable-v4l2-m2m

    --disable-vulkan

    --disable-x86asm

    "

TOOLCHAIN_PREFIX="${NDK_PATH}/toolchains/llvm/prebuilt/${HOST_PLATFORM}/bin"

if [[ ! -d "${TOOLCHAIN_PREFIX}" ]]
then

    echo "Please set correct NDK_PATH, $NDK_PATH is incorrect"

    exit 1

fi


for decoder in "${ENABLED_DECODERS[@]}"

do

    COMMON_OPTIONS="${COMMON_OPTIONS} --enable-decoder=${decoder}"

done


ARMV7_CLANG="${TOOLCHAIN_PREFIX}/armv7a-linux-androideabi${ANDROID_ABI}-clang"

if [[ ! -e "$ARMV7_CLANG" ]]
then

    echo "AVMv7 Clang compiler with path $ARMV7_CLANG does not exist"

    echo "It's likely your NDK version doesn't support ANDROID_ABI $ANDROID_ABI"

    echo "Either use older version of NDK or raise ANDROID_ABI (be aware that ANDROID_ABI must not be greater than your application's minSdk)"

    exit 1

fi

ANDROID_ABI_64BIT="$ANDROID_ABI"

if [[ "$ANDROID_ABI_64BIT" -lt 21 ]]
then

    echo "Using ANDROID_ABI 21 for 64-bit architectures"

    ANDROID_ABI_64BIT=21

fi


cd "${FFMPEG_MODULE_PATH}/jni/ffmpeg"

./configure \
    --libdir=android-libs/armeabi-v7a \
    --arch=arm \
    --cpu=armv7-a \
    --cross-prefix="${TOOLCHAIN_PREFIX}/armv7a-linux-androideabi${ANDROID_ABI}-" \
    --nm="${TOOLCHAIN_PREFIX}/llvm-nm" \
    --ar="${TOOLCHAIN_PREFIX}/llvm-ar" \
    --ranlib="${TOOLCHAIN_PREFIX}/llvm-ranlib" \
    --strip="${TOOLCHAIN_PREFIX}/llvm-strip" \
    --extra-cflags="-march=armv7-a -mfloat-abi=softfp" \
    --extra-ldflags="-Wl,--fix-cortex-a8" \
    ${COMMON_OPTIONS}

make -j$JOBS

make install-libs

make clean

./configure \
    --libdir=android-libs/arm64-v8a \
    --arch=aarch64 \
    --cpu=armv8-a \
    --cross-prefix="${TOOLCHAIN_PREFIX}/aarch64-linux-android${ANDROID_ABI_64BIT}-" \
    --nm="${TOOLCHAIN_PREFIX}/llvm-nm" \
    --ar="${TOOLCHAIN_PREFIX}/llvm-ar" \
    --ranlib="${TOOLCHAIN_PREFIX}/llvm-ranlib" \
    --strip="${TOOLCHAIN_PREFIX}/llvm-strip" \
    ${COMMON_OPTIONS}

make -j$JOBS

make install-libs

make clean

# x86 and x86_64 blocks removed (see the header): this app ships
# arm64-v8a + armeabi-v7a only, and the module's Gradle build is patched
# to those two ABIs by build-ffmpeg-aar.sh.
