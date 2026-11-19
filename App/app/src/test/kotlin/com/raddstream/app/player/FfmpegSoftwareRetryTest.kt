package com.raddstream.app.player

import com.raddstream.app.network.models.Playability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MX idea #1 (2026-11-18): FFmpeg software audio decoder + automatic fallback.
 *
 * A decode failure (ERROR_CODE_DECODING_FAILED) is a decoder problem, not a dead
 * copy — AC-3 / E-AC-3 / DTS / TrueHD audio the chip cannot decode. The machine
 * retries the SAME rendition once with the software decoder before hopping to
 * another copy. It is the S2 exception: a start failure normally never retries
 * the same rendition, but a decode failure means the decoder failed, so the
 * software decoder gets one shot at the same file. One per rendition, and it
 * does not touch the retry_same budget.
 */
class FfmpegSoftwareRetryTest {

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

    @Test
    fun `a decode failure before the first frame retries with software instead of hopping`() {
        val subject = machine(
            rung(1, "h-a", backups = listOf("b-a1")),
            rung(2, "h-b"),
        )
        assertEquals(0, subject.selectInitial())

        val retry = subject.onFailure(
            "ERROR_CODE_DECODING_FAILED ...",
            beforeFirstFrame = true,
            isDecodingFailed = true,
            isSoftwareDecoderAvailable = true,
        )
        retry as FailoverDecision.RetryWithSoftwareDecoder
        assertEquals(0, retry.index)
        assertEquals("retried with the software decoder", retry.record.action)
    }

    @Test
    fun `a decode failure before the first frame falls back to the other decoder mode when no software decoder exists`() {
        val subject = machine(
            rung(1, "h-a", backups = listOf("b-a1")),
            rung(2, "h-b"),
        )
        assertEquals(0, subject.selectInitial())

        // MX idea #3: without the FFmpeg library #1 has nothing to retry
        // with — but the software MediaCodec decoders are a different axis
        // from the FFmpeg audio decoder, so the first decode failure falls
        // back to the other video decoder instead of hopping at once.
        val fallback = subject.onFailure(
            "ERROR_CODE_DECODING_FAILED ...",
            beforeFirstFrame = true,
            isDecodingFailed = true,
            isSoftwareDecoderAvailable = false,
        )
        fallback as FailoverDecision.RetryWithOtherDecoderMode
        assertEquals(DecoderMode.SOFTWARE, fallback.targetMode)

        // The other mode failed on this file too: one fallback was the whole
        // budget, so the machine hops instead of looping on the same
        // rendition forever.
        val hop = subject.onFailure(
            "ERROR_CODE_DECODING_FAILED ...",
            beforeFirstFrame = true,
            isDecodingFailed = true,
            isSoftwareDecoderAvailable = false,
        )
        hop as FailoverDecision.AdvanceBackup
        assertEquals("b-a1", hop.streamId)
    }

    @Test
    fun `a second decode failure after the software retry was spent falls back to the other decoder mode`() {
        val subject = machine(
            rung(1, "h-a", backups = listOf("b-a1")),
            rung(2, "h-b"),
        )
        assertEquals(0, subject.selectInitial())

        val first = subject.onFailure(
            "ERROR_CODE_DECODING_FAILED ...",
            beforeFirstFrame = true,
            isDecodingFailed = true,
            isSoftwareDecoderAvailable = true,
        )
        assertTrue(first is FailoverDecision.RetryWithSoftwareDecoder)

        // The software decoder also failed on this file: MX idea #3 gives
        // the other video decoder one shot before the machine hops.
        val second = subject.onFailure(
            "ERROR_CODE_DECODING_FAILED ...",
            beforeFirstFrame = true,
            isDecodingFailed = true,
            isSoftwareDecoderAvailable = true,
        )
        second as FailoverDecision.RetryWithOtherDecoderMode
        assertEquals(DecoderMode.SOFTWARE, second.targetMode)

        // Both decoder retries are spent: the machine hops instead of
        // looping on the same rendition forever.
        val hop = subject.onFailure(
            "ERROR_CODE_DECODING_FAILED ...",
            beforeFirstFrame = true,
            isDecodingFailed = true,
            isSoftwareDecoderAvailable = true,
        )
        hop as FailoverDecision.AdvanceBackup
        assertEquals("b-a1", hop.streamId)
    }

    @Test
    fun `a decode failure on the last rank retries with software, then the other decoder mode, then hands off`() {
        val subject = machine(rung(1, "h-a"))
        assertEquals(0, subject.selectInitial())

        val first = subject.onFailure(
            "ERROR_CODE_DECODING_FAILED ...",
            beforeFirstFrame = true,
            isDecodingFailed = true,
            isSoftwareDecoderAvailable = true,
        )
        assertTrue(first is FailoverDecision.RetryWithSoftwareDecoder)

        // MX idea #3: the other video decoder gets its one shot before the
        // machine gives up on the only rank.
        val second = subject.onFailure(
            "ERROR_CODE_DECODING_FAILED ...",
            beforeFirstFrame = true,
            isDecodingFailed = true,
            isSoftwareDecoderAvailable = true,
        )
        assertTrue(second is FailoverDecision.RetryWithOtherDecoderMode)

        val third = subject.onFailure(
            "ERROR_CODE_DECODING_FAILED ...",
            beforeFirstFrame = true,
            isDecodingFailed = true,
            isSoftwareDecoderAvailable = true,
        )
        third as FailoverDecision.Handoff
        assertEquals(HandoffWhy.NO_CANDIDATE_LEFT, third.why)
    }

    @Test
    fun `a decode failure after the first frame retries with software before the same-rendition retry`() {
        val subject = machine(
            rung(1, "h-a", backups = listOf("b-a1")),
            rung(2, "h-b"),
        )
        assertEquals(0, subject.selectInitial())

        // After the first frame the ordinary path would be RetrySame; a
        // decode failure takes the software retry first instead.
        val retry = subject.onFailure(
            "ERROR_CODE_DECODING_FAILED ...",
            beforeFirstFrame = false,
            isDecodingFailed = true,
            isSoftwareDecoderAvailable = true,
        )
        assertTrue(retry is FailoverDecision.RetryWithSoftwareDecoder)
    }

    @Test
    fun `a non-decode failure does not spend the software retry`() {
        val subject = machine(
            rung(1, "h-a", backups = listOf("b-a1")),
            rung(2, "h-b"),
        )
        assertEquals(0, subject.selectInitial())

        // A network timeout before the first frame hops as S2 always did —
        // the software retry is decode-only, so it stays unspent.
        val hop = subject.onFailure(
            "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ...",
            beforeFirstFrame = true,
        )
        assertTrue(hop is FailoverDecision.AdvanceBackup)

        // The backup then fails to decode: the software retry is still
        // available for it.
        val retry = subject.onFailure(
            "ERROR_CODE_DECODING_FAILED ...",
            beforeFirstFrame = true,
            isDecodingFailed = true,
            isSoftwareDecoderAvailable = true,
        )
        assertTrue(retry is FailoverDecision.RetryWithSoftwareDecoder)
    }

    @Test
    fun `the software retry does not consume the same-rendition retry budget`() {
        val subject = machine(rung(1, "h-a"), rung(2, "h-b"))
        assertEquals(0, subject.selectInitial())

        val sw = subject.onFailure(
            "ERROR_CODE_DECODING_FAILED ...",
            beforeFirstFrame = false,
            isDecodingFailed = true,
            isSoftwareDecoderAvailable = true,
        )
        assertTrue(sw is FailoverDecision.RetryWithSoftwareDecoder)

        // A later network blip after the first frame still gets its own
        // same-rendition retry: the decoder retry and the network retry are
        // different kinds of second chance.
        val retry = subject.onFailure("HTTP 403", beforeFirstFrame = false)
        assertTrue(retry is FailoverDecision.RetrySame)
    }

    @Test
    fun `a decode failure reads as a device problem, not the generic playback failure`() {
        assertEquals(
            "This device could not play this video.",
            humanizeErrorSummary("ERROR_CODE_DECODING_FAILED ..."),
        )
    }
}
