package com.raddstream.app.player

import com.raddstream.app.network.models.Playability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MX idea #3 (2026-11-19): the graceful failure — when the chosen decoder
 * cannot decode a rendition, the machine tries the OTHER decoder once
 * before hopping to another copy. It sits between #1's automatic software
 * retry and the ordinary hop: the audio retry first (when it exists), the
 * mode fallback second, the hop third. One per rendition, and it does not
 * touch the retry_same budget.
 */
class DecoderModeFallbackTest {

    private fun rung(
        rank: Int,
        handle: String,
        backups: List<String> = emptyList(),
        playability: Playability = Playability.STREAM,
        url: String? = "http://127.0.0.1:8080/api/v1/stream?id=$handle",
    ) = PlaybackRendition(
        rank = rank,
        releaseId = 7,
        print = PrintInfo(label = "Title"),
        playback = PlaybackInfo(
            playUrl = url,
            streamId = handle,
            label = "Fast direct",
            kind = "direct",
            playabilityWire = playability.wire,
            resumable = true,
            backupStreamIds = backups,
        ),
    )

    private fun machine(vararg renditions: PlaybackRendition) = FailoverStateMachine(
        renditions.toList(), PlaybackFailurePolicy(retrySame = 1, maxRanks = 3, backoffMs = 500),
    )

    private fun decodeFailure(
        machine: FailoverStateMachine,
        softwareAvailable: Boolean = true,
        mode: DecoderMode = DecoderMode.HARDWARE,
        beforeFirstFrame: Boolean = true,
    ) = machine.onFailure(
        "ERROR_CODE_DECODING_FAILED ...",
        beforeFirstFrame = beforeFirstFrame,
        isDecodingFailed = true,
        isSoftwareDecoderAvailable = softwareAvailable,
        currentDecoderMode = mode,
    )

    @Test
    fun `a second decode failure falls back to the other decoder mode before hopping`() {
        val subject = machine(
            rung(1, "h-a", backups = listOf("b-a1")),
            rung(2, "h-b"),
        )
        assertEquals(0, subject.selectInitial())

        val first = decodeFailure(subject)
        assertTrue(first is FailoverDecision.RetryWithSoftwareDecoder)

        // The software (FFmpeg) decoder also failed on this file: the video
        // decoder gets one shot in the other mode before the machine hops.
        val second = decodeFailure(subject)
        second as FailoverDecision.RetryWithOtherDecoderMode
        assertEquals(0, second.index)
        assertEquals(DecoderMode.SOFTWARE, second.targetMode)
        assertEquals("switched to the software decoder and retried", second.record.action)
    }

    @Test
    fun `a third decode failure hops once the mode fallback is spent`() {
        val subject = machine(
            rung(1, "h-a", backups = listOf("b-a1")),
            rung(2, "h-b"),
        )
        assertEquals(0, subject.selectInitial())

        decodeFailure(subject)
        decodeFailure(subject)
        val hop = decodeFailure(subject)
        hop as FailoverDecision.AdvanceBackup
        assertEquals("b-a1", hop.streamId)
    }

    @Test
    fun `the mode fallback runs from software back to hardware`() {
        val subject = machine(
            rung(1, "h-a", backups = listOf("b-a1")),
            rung(2, "h-b"),
        )
        assertEquals(0, subject.selectInitial())

        val first = decodeFailure(subject, mode = DecoderMode.SOFTWARE)
        assertTrue(first is FailoverDecision.RetryWithSoftwareDecoder)

        val second = decodeFailure(subject, mode = DecoderMode.SOFTWARE)
        second as FailoverDecision.RetryWithOtherDecoderMode
        assertEquals(DecoderMode.HARDWARE, second.targetMode)
        assertEquals("switched to the hardware decoder and retried", second.record.action)
    }

    @Test
    fun `a decode failure with no software audio decoder falls back to the other mode at once`() {
        val subject = machine(
            rung(1, "h-a", backups = listOf("b-a1")),
            rung(2, "h-b"),
        )
        assertEquals(0, subject.selectInitial())

        // No FFmpeg AAR, so #1 has nothing to retry with — but the software
        // MediaCodec decoders are a different axis from the FFmpeg audio
        // decoder, and a video the hardware decoder chokes on may still
        // play in software.
        val first = decodeFailure(subject, softwareAvailable = false)
        first as FailoverDecision.RetryWithOtherDecoderMode
        assertEquals(DecoderMode.SOFTWARE, first.targetMode)

        val hop = decodeFailure(subject, softwareAvailable = false)
        assertTrue(hop is FailoverDecision.AdvanceBackup)
    }

    @Test
    fun `a decode failure on the last rank falls back to the other mode, then hands off`() {
        val subject = machine(rung(1, "h-a"))
        assertEquals(0, subject.selectInitial())

        assertTrue(decodeFailure(subject) is FailoverDecision.RetryWithSoftwareDecoder)
        assertTrue(decodeFailure(subject) is FailoverDecision.RetryWithOtherDecoderMode)

        val third = decodeFailure(subject)
        third as FailoverDecision.Handoff
        assertEquals(HandoffWhy.NO_CANDIDATE_LEFT, third.why)
    }

    @Test
    fun `the mode fallback does not consume the same-rendition retry budget`() {
        val subject = machine(rung(1, "h-a"), rung(2, "h-b"))
        assertEquals(0, subject.selectInitial())

        assertTrue(decodeFailure(subject, beforeFirstFrame = false) is FailoverDecision.RetryWithSoftwareDecoder)
        assertTrue(decodeFailure(subject, beforeFirstFrame = false) is FailoverDecision.RetryWithOtherDecoderMode)

        // A later network blip after the first frame still gets its own
        // same-rendition retry: a decoder retry and a network retry are
        // different kinds of second chance.
        val retry = subject.onFailure("HTTP 403", beforeFirstFrame = false)
        assertTrue(retry is FailoverDecision.RetrySame)
    }

    @Test
    fun `a non-decode failure does not spend the mode fallback`() {
        val subject = machine(
            rung(1, "h-a", backups = listOf("b-a1")),
            rung(2, "h-b"),
        )
        assertEquals(0, subject.selectInitial())

        // A network timeout before the first frame hops as S2 always did —
        // the mode fallback is decode-only, so it stays unspent.
        val hop = subject.onFailure(
            "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ...",
            beforeFirstFrame = true,
        )
        assertTrue(hop is FailoverDecision.AdvanceBackup)

        // The backup then fails to decode: both decoder retries are still
        // available for it, in order.
        assertTrue(decodeFailure(subject) is FailoverDecision.RetryWithSoftwareDecoder)
        assertTrue(decodeFailure(subject) is FailoverDecision.RetryWithOtherDecoderMode)
    }
}
