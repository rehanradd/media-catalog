package com.raddstream.app.player

import com.raddstream.app.network.models.WireMediaSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Skip Intro / Skip Recap (MX #4): the pure segment rules — which window
 * the playhead is inside, and where a tap on the Skip button jumps to.
 * No Android, no player: the window math is the whole behaviour, and the
 * ViewModel only publishes what these functions return.
 */
class MediaSegmentsTest {

    private val intro = MediaSegment(MediaSegmentType.INTRO, 228665L, 246498L)
    private val recap = MediaSegment(MediaSegmentType.RECAP, 1000L, 20000L)
    private val credits = MediaSegment(MediaSegmentType.CREDITS, 3432884L, null)
    private val segments = listOf(recap, intro, credits)

    @Test
    fun `active segment is the window holding the position`() {
        assertEquals(intro, activeSegment(segments, 230000L))
        assertEquals(recap, activeSegment(segments, 5000L))
    }

    @Test
    fun `window edges are start-inclusive and end-exclusive`() {
        assertEquals(intro, activeSegment(segments, 228665L))
        assertNull(activeSegment(segments, 246498L))
        assertNull(activeSegment(segments, 228664L))
    }

    @Test
    fun `no segment is active outside every window`() {
        assertNull(activeSegment(segments, 100000L))
        assertNull(activeSegment(emptyList(), 230000L))
    }

    @Test
    fun `a null end stays active to the end of the file`() {
        assertEquals(credits, activeSegment(segments, 3500000L))
        assertEquals(credits, activeSegment(segments, Long.MAX_VALUE))
    }

    @Test
    fun `the earliest start wins when windows overlap`() {
        val overlapping = listOf(
            MediaSegment(MediaSegmentType.INTRO, 1000L, 9000L),
            MediaSegment(MediaSegmentType.RECAP, 500L, 8000L),
        )
        assertEquals(MediaSegmentType.RECAP, activeSegment(overlapping, 2000L)?.type)
    }

    @Test
    fun `skip target is the window end`() {
        assertEquals(246498L, skipTargetMs(intro, 3600000L))
        assertEquals(20000L, skipTargetMs(recap, 3600000L))
    }

    @Test
    fun `skip target for an open-ended window is the file end`() {
        assertEquals(3600000L, skipTargetMs(credits, 3600000L))
    }

    @Test
    fun `skip target is null when neither end nor duration is known`() {
        assertNull(skipTargetMs(credits, 0L))
    }

    @Test
    fun `wire mapper drops unknown types and bad windows`() {
        assertNull(WireMediaSegment(type = "commercial", startMs = 100L, endMs = 200L)
            .toMediaSegment())
        assertNull(WireMediaSegment(type = "intro", startMs = -5L, endMs = 200L)
            .toMediaSegment())
        assertNull(WireMediaSegment(type = "intro", startMs = 2000L, endMs = 2000L)
            .toMediaSegment())
        assertNull(WireMediaSegment(type = "intro", startMs = null, endMs = 200L)
            .toMediaSegment())
        assertEquals(
            MediaSegment(MediaSegmentType.INTRO, 1000L, 2000L),
            WireMediaSegment(type = "intro", startMs = 1000L, endMs = 2000L)
                .toMediaSegment(),
        )
        assertEquals(
            MediaSegment(MediaSegmentType.CREDITS, 5000L, null),
            WireMediaSegment(type = "credits", startMs = 5000L, endMs = null)
                .toMediaSegment(),
        )
    }
}
