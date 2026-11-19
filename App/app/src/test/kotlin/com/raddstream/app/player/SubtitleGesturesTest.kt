package com.raddstream.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleGesturesTest {

    // The pane is 1080x1920 in these tests — a phone in portrait; the player is usually
    // landscape, but the zone is defined in fractions, so the shape does not matter.
    private val width = 1080f
    private val height = 1920f

    // ── the strip ────────────────────────────────────────────────────────────────

    @Test
    fun zoneIsBottomThirtyPercentCentreThird() {
        // Bottom-centre: in. Top-centre: out. Bottom-left/right thirds: out.
        assertTrue(isInSubtitleZone(540f, 1800f, width, height))
        assertTrue(isInSubtitleZone(540f, 1344f, width, height)) // exactly the 70% line
        assertFalse(isInSubtitleZone(540f, 1343f, width, height))
        assertFalse(isInSubtitleZone(540f, 500f, width, height))
        assertFalse(isInSubtitleZone(100f, 1800f, width, height))
        assertFalse(isInSubtitleZone(1000f, 1800f, width, height))
        assertFalse(isInSubtitleZone(540f, 1800f, 0f, height))
    }

    // ── re-time ──────────────────────────────────────────────────────────────────

    @Test
    fun retimeIsOneStepPerTenPercentOfWidth() {
        assertEquals(1, subtitleRetimeSteps(108f, width)) // exactly 10%
        assertEquals(2, subtitleRetimeSteps(250f, width)) // 23% → 2 whole steps
        assertEquals(0, subtitleRetimeSteps(90f, width)) // under one step: nothing yet
        assertEquals(-1, subtitleRetimeSteps(-150f, width))
        assertEquals(0, subtitleRetimeSteps(108f, 0f))
    }

    // ── size ─────────────────────────────────────────────────────────────────────

    @Test
    fun sizeDeltaFollowsZoomFactor() {
        assertEquals(5, subtitleSizeDelta(1.05f))
        assertEquals(-5, subtitleSizeDelta(0.95f))
        assertEquals(0, subtitleSizeDelta(1.0f))
        // Clamped per callback: one wild frame cannot jump the size.
        assertEquals(10, subtitleSizeDelta(1.5f))
        assertEquals(-10, subtitleSizeDelta(0.5f))
        assertEquals(0, subtitleSizeDelta(Float.NaN))
        assertEquals(0, subtitleSizeDelta(0f))
    }

    // ── move ─────────────────────────────────────────────────────────────────────

    @Test
    fun moveFractionIsHeightFractionUpIsPositive() {
        // Dragging up 192 px on a 1920 px pane raises the seat by 10% of the pane.
        assertEquals(0.10f, subtitleMoveFraction(-192f, height), 0.001f)
        assertEquals(-0.10f, subtitleMoveFraction(192f, height), 0.001f)
        assertEquals(0f, subtitleMoveFraction(100f, 0f), 0.001f)
    }

    // ── fling vs drag ────────────────────────────────────────────────────────────

    @Test
    fun flingNeedsShortFarAndFast() {
        assertTrue(isSubtitleFling(150, 100f)) // 100 px in 150 ms = 667 px/s
        assertTrue(isSubtitleFling(100, -200f)) // direction does not matter here
        assertFalse(isSubtitleFling(150, 40f)) // too short a travel
        assertFalse(isSubtitleFling(500, 300f)) // too slow (600 px/s but over 300 ms)
        assertFalse(isSubtitleFling(1000, 150f)) // a slow drag: 150 px/s
        assertFalse(isSubtitleFling(0, 100f)) // no duration, no velocity
    }
}
