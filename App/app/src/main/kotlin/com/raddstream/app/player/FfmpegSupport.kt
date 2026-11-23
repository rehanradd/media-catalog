package com.raddstream.app.player

/**
 * MX idea #1 (2026-11-18): the FFmpeg software audio decoder.
 *
 * The FFmpeg extension is NextLib's prebuilt `nextlib-media3ext`
 * (Media3 1.11.1 + FFmpeg 9.0.1, audio decoders AC-3 / E-AC-3 / DTS /
 * TrueHD / MLP) since the 2026-11-23 library bump — it replaced
 * Jellyfin's `media3-ffmpeg-decoder`, which caps at Media3 1.9.0+1.
 * The player itself needs no compile-time reference to it:
 * `NextRenderersFactory` loads `FfmpegAudioRenderer` by reflection,
 * and this object answers the one question the failover machine
 * needs — "is the software decoder actually there?" — by reflection
 * too, so the app compiles, runs and plays exactly as before on a
 * build without the AAR. With the AAR present but the native library
 * unloadable (an ABI the build did not ship), the renderer simply
 * never claims a format and MediaCodec handles everything, as before.
 *
 * GPL-3.0: the NextLib artifact is GPL-3.0 (FFmpeg itself LGPL 2.1+);
 * see `App/ffmpeg/NOTICE.md` — the repo has no license file, so this
 * is flagged for the owner, not a blocker.
 */
object FfmpegSupport {

    private const val FFMPEG_LIBRARY_CLASS =
        "io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary"

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
