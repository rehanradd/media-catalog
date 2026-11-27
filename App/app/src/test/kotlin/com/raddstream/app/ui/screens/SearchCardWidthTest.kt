package com.raddstream.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Design direction (Nov 2026): one poster-card size on the Search screen —
 * the rail and the 3-up grids share a width, so a card never changes size
 * between rows. The width is the 3-up grid's cell: the screen minus the
 * outer 16dp gutters and the two 12dp gaps, in three. Pure, so these run
 * on plain JUnit with no composition.
 */
class SearchCardWidthTest {

    @Test
    fun `three cards plus gaps fit a 360dp phone`() {
        val width = searchPosterCardWidthDp(360)
        assertEquals(101, width)
        // Integer division leaves at most 2dp of slack at the row's end.
        val used = 3 * width + 2 * 12
        assertEquals(1, 360 - 32 - used)
    }

    @Test
    fun `three cards plus gaps fit a Pixel 5`() {
        val width = searchPosterCardWidthDp(393)
        assertEquals(112, width)
        val used = 3 * width + 2 * 12
        assertEquals(1, 393 - 32 - used)
    }

    @Test
    fun `three cards fill a narrow 320dp phone exactly`() {
        val width = searchPosterCardWidthDp(320)
        assertEquals(88, width)
        assertEquals(320 - 32, 3 * width + 2 * 12)
    }
}
