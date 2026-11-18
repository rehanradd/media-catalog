package com.raddstream.app.player

/**
 * MX idea #1 (2026-11-18): the FFmpeg software audio decoder.
 *
 * The FFmpeg extension (`androidx.media3.decoder.ffmpeg`) is built from source
 * (Media3 1.3.1 + FFmpeg 6.0, audio decoders AC-3 / E-AC-3 / DTS / TrueHD /
 * MLP) and bundled as a local AAR — it is not on Maven, and the native
 * libraries must be built (see `App/scripts/build-ffmpeg-aar.sh`). The player
 * itself needs no compile-time reference to it: `DefaultRenderersFactory`
 * loads `FfmpegAudioRenderer` by reflection, and this object answers the one
 * question the failover machine needs — "is the software decoder actually
 * there?" — by reflection too, so the app compiles, runs and plays exactly
 * as before on a build without the AAR. With the AAR present but the native
 * library unloadable (an ABI the build did not ship), the renderer simply
 * never claims a format and MediaCodec handles everything, as before.
 *
 * LGPL: the FFmpeg libraries (libavcodec, libavutil, libswresample) are
 * LGPL-2.1. The build enables only LGPL decoders (no GPL components), and
 * the source + build configuration are in the repo (`App/ffmpeg/NOTICE.md`,
 * `App/scripts/build-ffmpeg-aar.sh`) so the .so can be rebuilt and swapped.
 */
object FfmpegSupport {

    private const val FFMPEG_LIBRARY_CLASS = "androidx.media3.decoder.ffmpeg.FfmpegLibrary"

    /**
     * Whether the FFmpeg software audio decoder is available on this device:
     * the extension classes are in the build AND the native `ffmpegJNI`
     * library loaded. False on a build without the AAR, false on an ABI the
     * build did not ship, false on the JVM (unit tests — there is no native
     * library there). Computed once per process; the .so is either in the
     * APK or it is not.
     */
    val isSoftwareAudioAvailable: Boolean by lazy {
        runCatching {
            val clazz = Class.forName(FFMPEG_LIBRARY_CLASS)
            val isAvailable = clazz.getMethod("isAvailable")
            isAvailable.invoke(null) as? Boolean ?: false
        }.getOrDefault(false)
    }
}
