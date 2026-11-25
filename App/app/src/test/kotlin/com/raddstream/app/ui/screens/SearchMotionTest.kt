package com.raddstream.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Motion (round 2): the result-row entrance delay is 40ms per row, capped
 * at 300ms so a long list never waits on the animation. Pure, so these run
 * on plain JUnit with no composition — the stagger is a function of the
 * row's position, never of wall-clock timing.
 */
class SearchMotionTest {

    @Test
    fun `the first row enters at once`() {
        assertEquals(0, searchEntranceDelayMs(0))
    }

    @Test
    fun `each row waits 40ms more than the one above it`() {
        assertEquals(40, searchEntranceDelayMs(1))
        assertEquals(200, searchEntranceDelayMs(5))
    }

    @Test
    fun `the delay caps at 300ms`() {
        assertEquals(300, searchEntranceDelayMs(8))
        assertEquals(300, searchEntranceDelayMs(100))
    }

    @Test
    fun `a negative index never delays backwards`() {
        assertEquals(0, searchEntranceDelayMs(-1))
    }
}
