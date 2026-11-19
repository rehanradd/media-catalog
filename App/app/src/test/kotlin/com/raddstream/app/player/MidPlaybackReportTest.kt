package com.raddstream.app.player

import com.raddstream.app.network.models.Playability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H4 R7: the mid-playback report classifier. The server can never see a segment
 * fetch (the 302 hands the player straight to the host), so the only way it learns
 * a link died mid-play is the app telling it — and only when the machine ABANDONS
 * the rendition. A retry is not a death: reporting a rendition the machine is about
 * to retry would strike a live source. An expired token is not a death either: the
 * token rotted, not the source, and a 12-hour negative-cache strike would wrongly
 * bury a live copy.
 */
class MidPlaybackReportTest {

    private fun record(reason: String = "HTTP 404") = FailoverRecord(
        rank = 1,
        releaseId = 7,
        label = "Title",
        reason = reason,
        action = "skipped to next rank",
    )

    private fun rung(
        rank: Int,
        handle: String,
        backups: List<String> = emptyList(),
    ) = PlaybackRendition(
        rank = rank,
        releaseId = 7,
        print = PrintInfo(label = "Title"),
        playback = PlaybackInfo(
            playUrl = "http://127.0.0.1:8080/api/v1/stream?id=$handle",
            streamId = handle,
            label = "Fast direct",
            kind = "direct",
            playabilityWire = Playability.STREAM.wire,
            resumable = true,
            backupStreamIds = backups,
        ),
    )

    private fun machine(vararg renditions: PlaybackRendition) = FailoverStateMachine(
        renditions.toList(), PlaybackFailurePolicy(retrySame = 1, maxRanks = 3, backoffMs = 500),
    )

    // ---- abandonment reports the death -------------------------------------

    @Test
    fun `an abandoned rendition with a dead handle reports dead_handle`() {
        val decision = FailoverDecision.Advance(2, "1080p", record())
        assertEquals("dead_handle", midPlaybackReportClass(decision, isDeadHandle = true))
    }

    @Test
    fun `a backup hop with a dead handle reports dead_handle`() {
        val decision = FailoverDecision.AdvanceBackup(1, "b-a1", "http://127.0.0.1/x", record())
        assertEquals("dead_handle", midPlaybackReportClass(decision, isDeadHandle = true))
    }

    @Test
    fun `a handoff with a dead handle reports dead_handle`() {
        val decision = FailoverDecision.Handoff(HandoffWhy.NO_CANDIDATE_LEFT, null, record())
        assertEquals("dead_handle", midPlaybackReportClass(decision, isDeadHandle = true))
    }

    @Test
    fun `an unclassified abandonment reports network, never nothing`() {
        // Transport died mid-segment with no 404/403/410 from our redirector: the
        // server records the evidence and strikes nothing — but it still learns
        // the rendition was abandoned, so the class is "network", not null.
        val decision = FailoverDecision.Advance(2, "1080p", record("timeout"))
        assertEquals("network", midPlaybackReportClass(decision))
    }

    @Test
    fun `a gate refusal reports gate`() {
        val decision = FailoverDecision.Handoff(HandoffWhy.GATE_REFUSED, null, record("HTTP 402"))
        assertEquals("gate", midPlaybackReportClass(decision, isGateRefusal = true))
    }

    @Test
    fun `a rate limit reports rate_limited`() {
        val decision = FailoverDecision.Handoff(HandoffWhy.RATE_LIMITED, null, record("HTTP 429"))
        assertEquals("rate_limited", midPlaybackReportClass(decision, isRateLimited = true))
    }

    @Test
    fun `a decode failure reports decode`() {
        val decision = FailoverDecision.Advance(2, "1080p", record("Decoder failed"))
        assertEquals("decode", midPlaybackReportClass(decision, isDecodingFailed = true))
    }

    @Test
    fun `dead_handle wins over every other flag`() {
        // Our redirector's 404 is the only fact that strikes the negative cache;
        // a gate or rate flag on the same error must not rename it.
        val decision = FailoverDecision.Advance(2, "1080p", record())
        assertEquals(
            "dead_handle",
            midPlaybackReportClass(
                decision,
                isDeadHandle = true,
                isGateRefusal = true,
                isRateLimited = true,
                isDecodingFailed = true,
            ),
        )
    }

    // ---- retries report nothing ---------------------------------------------

    @Test
    fun `a same-rendition retry reports nothing, even for a dead handle`() {
        val decision = FailoverDecision.RetrySame(1, 500, record())
        assertNull(midPlaybackReportClass(decision, isDeadHandle = true))
    }

    @Test
    fun `a software-decoder retry reports nothing`() {
        val decision = FailoverDecision.RetryWithSoftwareDecoder(1, record("Decoder failed"))
        assertNull(midPlaybackReportClass(decision, isDecodingFailed = true))
    }

    @Test
    fun `an other-decoder-mode retry reports nothing`() {
        val decision = FailoverDecision.RetryWithOtherDecoderMode(
            1, DecoderMode.SOFTWARE, record("Decoder failed"),
        )
        assertNull(midPlaybackReportClass(decision, isDecodingFailed = true))
    }

    // ---- an expired token is not a dead source -------------------------------

    @Test
    fun `an expired-link handoff reports nothing, even for a dead handle`() {
        val decision = FailoverDecision.Handoff(HandoffWhy.EXPIRED_LINK, null, record("expired"))
        assertNull(midPlaybackReportClass(decision, isDeadHandle = true))
        assertNull(midPlaybackReportClass(decision))
    }

    // ---- the decisions the machine actually produces -------------------------

    @Test
    fun `the machine's backup hop classifies as dead_handle`() {
        val subject = machine(
            rung(1, "h-a", backups = listOf("b-a1")),
            rung(2, "h-b"),
        )
        assertEquals(0, subject.selectInitial())
        assertTrue(subject.onFailure("HTTP 404") is FailoverDecision.RetrySame)
        val hop = subject.onFailure("HTTP 404")
        hop as FailoverDecision.AdvanceBackup
        assertEquals("dead_handle", midPlaybackReportClass(hop, isDeadHandle = true))
    }

    @Test
    fun `the machine's expired-link handoff classifies as nothing`() {
        val subject = machine(rung(1, "h-a"), rung(2, "h-b"))
        assertEquals(0, subject.selectInitial())
        val decision = subject.onFailure("link expired before load", isExpiry = true)
        decision as FailoverDecision.Handoff
        assertEquals(HandoffWhy.EXPIRED_LINK, decision.why)
        assertNull(midPlaybackReportClass(decision, isDeadHandle = true))
    }
}
