package com.raddstream.app.player

import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MX idea #3 (2026-11-19): the software-only MediaCodec selector — which
 * decoders it keeps, which it drops, and what it does when a format has no
 * software decoder at all. The predicate is the unit under test; the
 * selector is exercised through a fake delegate, because the real delegate
 * reads the device's codec list.
 */
@OptIn(UnstableApi::class)
class SoftwareMediaCodecSelectorTest {

    private fun info(name: String, softwareOnly: Boolean, hardwareAccelerated: Boolean = !softwareOnly) =
        MediaCodecInfo.newInstance(name, "video/avc", "video/avc", null, hardwareAccelerated, softwareOnly, false)

    private class FixedSelector(private val infos: List<MediaCodecInfo>) : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean,
        ): List<MediaCodecInfo> = infos
    }

    // ── the predicate ────────────────────────────────────────────────────

    @Test
    fun `on API 29 and above the platform flag is authoritative`() {
        assertTrue(SoftwareMediaCodecSelector.isSoftwareDecoder("OMX.google.h264.decoder", true, Build.VERSION_CODES.Q))
        assertEquals(
            false,
            SoftwareMediaCodecSelector.isSoftwareDecoder("OMX.google.h264.decoder", false, Build.VERSION_CODES.Q),
        )
        assertEquals(
            false,
            SoftwareMediaCodecSelector.isSoftwareDecoder("OMX.qcom.video.decoder.avc", false, Build.VERSION_CODES.Q),
        )
    }

    @Test
    fun `below API 29 the platform software decoders are named, not flagged`() {
        assertTrue(
            SoftwareMediaCodecSelector.isSoftwareDecoder(
                "OMX.google.h264.decoder", false, Build.VERSION_CODES.P,
            ),
        )
        assertTrue(
            SoftwareMediaCodecSelector.isSoftwareDecoder(
                "c2.android.avc.decoder", false, Build.VERSION_CODES.P,
            ),
        )
        assertEquals(
            false,
            SoftwareMediaCodecSelector.isSoftwareDecoder(
                "OMX.qcom.video.decoder.avc", false, Build.VERSION_CODES.P,
            ),
        )
        assertEquals(
            false,
            SoftwareMediaCodecSelector.isSoftwareDecoder(
                "OMX.MTK.VIDEO.DECODER.AVC", false, Build.VERSION_CODES.P,
            ),
        )
    }

    // ── the selector ─────────────────────────────────────────────────────

    @Test
    fun `the selector keeps only the software decoders, in the delegate order`() {
        val subject = SoftwareMediaCodecSelector(
            FixedSelector(
                listOf(
                    info("OMX.qcom.video.decoder.avc", softwareOnly = false),
                    info("OMX.google.h264.decoder", softwareOnly = true),
                    info("c2.android.avc.decoder", softwareOnly = true),
                ),
            ),
        )
        val kept = subject.getDecoderInfos("video/avc", false, false)
        assertEquals(
            listOf("OMX.google.h264.decoder", "c2.android.avc.decoder"),
            kept.map { it.name },
        )
    }

    @Test
    fun `a format with no software decoder falls back to the full list`() {
        // A title whose format the device only ships in hardware must still
        // play — the chip says SW (the mode), and the decoder used is the
        // best the device has for that format.
        val all = listOf(info("OMX.qcom.video.decoder.avc", softwareOnly = false))
        val subject = SoftwareMediaCodecSelector(FixedSelector(all))
        assertEquals(all, subject.getDecoderInfos("video/avc", false, false))
    }

    @Test
    fun `an empty delegate list stays empty`() {
        val subject = SoftwareMediaCodecSelector(FixedSelector(emptyList()))
        assertTrue(subject.getDecoderInfos("video/avc", false, false).isEmpty())
    }

    // ── the mode-to-factory mapping ──────────────────────────────────────

    @Test
    fun `hardware is Media3's own default selector`() {
        assertSame(MediaCodecSelector.DEFAULT, mediaCodecSelectorFor(DecoderMode.HARDWARE))
    }

    @Test
    fun `software is the software-only selector`() {
        assertTrue(mediaCodecSelectorFor(DecoderMode.SOFTWARE) is SoftwareMediaCodecSelector)
    }
}
