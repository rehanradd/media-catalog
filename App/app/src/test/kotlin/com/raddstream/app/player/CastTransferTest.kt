package com.raddstream.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MX #9 — Cast: the transfer rules, as pure JVM tests. No Cast SDK, no
 * player: the gate order, the refusal reasons, and the resume math are
 * the whole behaviour, and the service only does what these functions
 * return.
 */
class CastTransferTest {

    private val live = PlaybackInfo(
        playUrl = "https://raddstream.duckdns.org/api/v1/stream?id=abc123",
        protocol = "hls",
        expiresAt = 9_999_999_999L, // epoch seconds; far in the future
    )
    private val nowMs = 1_700_000_000_000L

    @Test
    fun `a live link with a session and permission proceeds`() {
        val decision = decideCastStart(live, allowed = true, sessionAvailable = true, nowEpochMillis = nowMs)
        assertEquals(CastTransferDecision.Proceed(live.playUrl!!), decision)
    }

    @Test
    fun `a locked player refuses first, before any other check`() {
        val decision = decideCastStart(live, allowed = false, sessionAvailable = false, nowEpochMillis = nowMs)
        assertEquals(CastTransferDecision.Refuse(CastRefusalReason.NOT_ALLOWED), decision)
    }

    @Test
    fun `no session refuses even when everything else is fine`() {
        val decision = decideCastStart(live, allowed = true, sessionAvailable = false, nowEpochMillis = nowMs)
        assertEquals(CastTransferDecision.Refuse(CastRefusalReason.NO_SESSION), decision)
    }

    @Test
    fun `a rendition with no address refuses`() {
        val noUrl = live.copy(playUrl = null)
        val decision = decideCastStart(noUrl, allowed = true, sessionAvailable = true, nowEpochMillis = nowMs)
        assertEquals(CastTransferDecision.Refuse(CastRefusalReason.NO_PLAY_URL), decision)
        val blankUrl = live.copy(playUrl = "   ")
        val blankDecision = decideCastStart(blankUrl, allowed = true, sessionAvailable = true, nowEpochMillis = nowMs)
        assertEquals(CastTransferDecision.Refuse(CastRefusalReason.NO_PLAY_URL), blankDecision)
    }

    @Test
    fun `an expired link refuses — a rotted URL never reaches the receiver`() {
        val expired = live.copy(expiresAt = 1_000L) // epoch seconds; long past
        val decision = decideCastStart(expired, allowed = true, sessionAvailable = true, nowEpochMillis = nowMs)
        assertEquals(CastTransferDecision.Refuse(CastRefusalReason.EXPIRED_LINK), decision)
    }

    @Test
    fun `a mid-title position survives a hop unchanged`() {
        assertEquals(123_456L, castResumePosition(123_456L, 600_000L))
    }

    @Test
    fun `an ended player restarts instead of resuming at its own end`() {
        assertEquals(0L, castResumePosition(600_000L, 600_000L))
        assertEquals(0L, castResumePosition(601_000L, 600_000L))
    }

    @Test
    fun `a position past the end clamps to one second before it`() {
        assertEquals(599_000L, castResumePosition(599_500L, 600_000L))
    }

    @Test
    fun `an unknown duration keeps the position as-is`() {
        assertEquals(123_456L, castResumePosition(123_456L, 0L))
        assertEquals(123_456L, castResumePosition(123_456L, -1L))
    }

    @Test
    fun `a negative position floors at zero`() {
        assertEquals(0L, castResumePosition(-500L, 600_000L))
    }
}
