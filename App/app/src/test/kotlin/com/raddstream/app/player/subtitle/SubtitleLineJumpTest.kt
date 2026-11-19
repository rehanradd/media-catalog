package com.raddstream.app.player.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleLineJumpTest {

    private val boundaries = listOf(1_000L to 3_000L, 5_000L to 7_000L, 9_000L to 11_000L)

    // ── next ─────────────────────────────────────────────────────────────────────

    @Test
    fun nextFromInsideALineGoesToTheFollowingLine() {
        assertEquals(5_000L, SubtitleLineJump.nextStart(boundaries, 1_500))
    }

    @Test
    fun nextFromAGapGoesToTheLineAboutToStart() {
        // 100 ms before the 5000 line: that line IS the next line, not one to skip.
        assertEquals(5_000L, SubtitleLineJump.nextStart(boundaries, 4_900))
    }

    @Test
    fun nextFromExactlyALineStartGoesPastIt() {
        assertEquals(9_000L, SubtitleLineJump.nextStart(boundaries, 5_000))
    }

    @Test
    fun nextAfterTheLastLineIsNull() {
        assertNull(SubtitleLineJump.nextStart(boundaries, 9_500))
        assertNull(SubtitleLineJump.nextStart(boundaries, 12_000))
    }

    @Test
    fun nextWithNoLinesIsNull() {
        assertNull(SubtitleLineJump.nextStart(emptyList(), 1_000))
    }

    // ── previous ─────────────────────────────────────────────────────────────────

    @Test
    fun prevWithinTheFirstSecondGoesToTheLineBefore() {
        // 100 ms into the 5000 line: previous is the 1000 line.
        assertEquals(1_000L, SubtitleLineJump.prevStart(boundaries, 5_100))
    }

    @Test
    fun prevPastTheFirstSecondRestartsTheCurrentLine() {
        assertEquals(5_000L, SubtitleLineJump.prevStart(boundaries, 6_500))
    }

    @Test
    fun prevFromAGapGoesToTheLineJustShown() {
        // 8000 ms: between the 5000 and 9000 lines — the line just shown is the previous one.
        assertEquals(5_000L, SubtitleLineJump.prevStart(boundaries, 8_000))
    }

    @Test
    fun prevBeforeTheFirstLineIsNull() {
        assertNull(SubtitleLineJump.prevStart(boundaries, 500))
    }

    @Test
    fun prevFromInsideTheFirstLineIsNull() {
        assertNull(SubtitleLineJump.prevStart(boundaries, 1_500))
    }

    @Test
    fun prevWithNoLinesIsNull() {
        assertNull(SubtitleLineJump.prevStart(emptyList(), 1_000))
    }
}
