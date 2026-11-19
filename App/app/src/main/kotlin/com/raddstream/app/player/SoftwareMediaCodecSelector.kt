@file:OptIn(UnstableApi::class)
// Kotlin's `@file:OptIn` above satisfies the *compiler*; Media3's opt-in rule is enforced a
// second time by the lint check `UnsafeOptInUsageError`, which only reads
// `androidx.annotation.OptIn`. Both are required, and the class-level one below is what clears
// the `MediaCodecSelector` / `MediaCodecInfo` references in this file.
package com.raddstream.app.player

import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * MX idea #3 (2026-11-19): the software-only MediaCodec selector the
 * player is built with in [DecoderMode.SOFTWARE].
 *
 * It delegates to [MediaCodecSelector.DEFAULT] and keeps only the software
 * decoders, in the delegate's own preference order — so a title the
 * hardware decoder cannot decode gets the platform software decoder for
 * the same format, not a different format or a different container reader.
 * The FFmpeg audio extension is untouched by this: it is selected by the
 * extension renderer mode (PREFER), not by this selector.
 *
 * A format with no software decoder on the device falls back to the full
 * list rather than making the title unplayable: the chip still says SW —
 * the mode — and the decoder used is the best the device has for that
 * format. The alternative (an empty list) would fail every title whose
 * format the device only ships in hardware, which is the opposite of what
 * a fallback switch is for.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SoftwareMediaCodecSelector(
    private val delegate: MediaCodecSelector = MediaCodecSelector.DEFAULT,
) : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val all = delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        val software = all.filter { isSoftwareDecoder(it.name, it.softwareOnly) }
        return software.ifEmpty { all }
    }

    companion object {
        /**
         * Whether one decoder entry is a software decoder. On API 29+
         * Media3 fills [MediaCodecInfo.softwareOnly] from the platform
         * flag, so it is authoritative. Below 29 the flag is never set
         * (the platform cannot report it), so the name is the signal:
         * the platform software decoders are `OMX.google.*` (OMX) and
         * `c2.android.*` (Codec2).
         */
        internal fun isSoftwareDecoder(
            name: String,
            softwareOnly: Boolean,
            sdkInt: Int = Build.VERSION.SDK_INT,
        ): Boolean {
            if (sdkInt >= Build.VERSION_CODES.Q) return softwareOnly
            return name.startsWith("OMX.google.") || name.startsWith("c2.android.")
        }
    }
}

/**
 * The MediaCodecSelector a player build in [mode] uses. Hardware is
 * Media3's own default — nothing to override — and software is the
 * selector above. A pure function so the mode → factory mapping is a JVM
 * test, not a player-build test.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun mediaCodecSelectorFor(mode: DecoderMode): MediaCodecSelector =
    when (mode) {
        DecoderMode.HARDWARE -> MediaCodecSelector.DEFAULT
        DecoderMode.SOFTWARE -> SoftwareMediaCodecSelector()
    }
