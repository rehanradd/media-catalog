package com.raddstream.app.ui

import com.raddstream.app.ui.screens.CinemaChrome
import com.raddstream.app.ui.screens.EdgeKind
import com.raddstream.app.ui.screens.EdgeSide
import com.raddstream.app.ui.screens.Hud
import com.raddstream.app.ui.screens.SubtitleHudValue
import com.raddstream.app.ui.screens.edgeIndicatorSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cinema player (Nov 2026): the chrome budget and the edge-indicator rules are
 * pure, so they are pinned here — the composables only paint what these
 * functions decide. Video 95% / controls 5% is the direction; the numbers
 * below are what 48dp press targets actually allow on a landscape phone.
 */
class CinemaChromeTest {

    // ── chrome budget ────────────────────────────────────────────────────────

    @Test
    fun chromeBudgetIs152dp() {
        // P1 (player fixes): the bottom bar shrank 104 → 96dp by removing the
        // scrubber-to-controls gap (targets stay 48dp) — the budget follows it.
        assertEquals(56, CinemaChrome.TOP_BAR_MIN_DP)
        assertEquals(96, CinemaChrome.BOTTOM_BAR_MIN_DP)
        assertEquals(152, CinemaChrome.TOP_BAR_MIN_DP + CinemaChrome.BOTTOM_BAR_MIN_DP)
    }

    @Test
    fun pressTargetNeverShrinksBelow48dp() {
        assertEquals(48, CinemaChrome.MIN_TOUCH_TARGET_DP)
    }

    @Test
    fun hudLifetimeIsFastFading() {
        // ~1s: the edge indicator is a glance, not a card.
        assertEquals(800L, CinemaChrome.HUD_LIFETIME_MS)
    }

    @Test
    fun videoShareOnLandscapePhone() {
        // PIXEL_5 landscape is 411dp tall: 152dp of chrome leaves 63% video
        // while the chrome is up (was 47% at the old 216dp budget).
        assertEquals(0.630f, CinemaChrome.videoSharePercent(411), 0.001f)
    }

    @Test
    fun videoShareNeverGoesNegative() {
        assertEquals(0f, CinemaChrome.videoSharePercent(100), 0.001f)
        assertEquals(0f, CinemaChrome.videoSharePercent(0), 0.001f)
        assertEquals(0f, CinemaChrome.videoSharePercent(-10), 0.001f)
    }

    // ── edge indicator ───────────────────────────────────────────────────────

    @Test
    fun brightnessRidesTheLeftEdge() {
        val spec = edgeIndicatorSpec(Hud.Brightness(0.4f))
        assertEquals(EdgeSide.LEFT, spec?.side)
        assertEquals(EdgeKind.BRIGHTNESS, spec?.kind)
        assertEquals(0.4f, spec?.fraction ?: -1f, 0.001f)
    }

    @Test
    fun volumeRidesTheRightEdge() {
        val spec = edgeIndicatorSpec(Hud.Volume(0.6f))
        assertEquals(EdgeSide.RIGHT, spec?.side)
        assertEquals(EdgeKind.VOLUME, spec?.kind)
        assertEquals(0.6f, spec?.fraction ?: -1f, 0.001f)
    }

    @Test
    fun boostRidesTheRightEdgeAtFull() {
        val spec = edgeIndicatorSpec(Hud.Boost)
        assertEquals(EdgeSide.RIGHT, spec?.side)
        assertEquals(EdgeKind.BOOST, spec?.kind)
        assertEquals(1f, spec?.fraction ?: -1f, 0.001f)
    }

    @Test
    fun fractionsAreClampedToTheTrack() {
        assertEquals(1f, edgeIndicatorSpec(Hud.Brightness(1.5f))?.fraction ?: -1f, 0.001f)
        assertEquals(0f, edgeIndicatorSpec(Hud.Volume(-0.2f))?.fraction ?: -1f, 0.001f)
    }

    @Test
    fun subtitleIsAPillNotAnEdgeLine() {
        assertNull(edgeIndicatorSpec(Hud.Subtitle(SubtitleHudValue.Delay(500L))))
        assertNull(edgeIndicatorSpec(Hud.Subtitle(SubtitleHudValue.Size(120))))
        assertNull(edgeIndicatorSpec(Hud.Subtitle(SubtitleHudValue.Position(80))))
        assertNull(edgeIndicatorSpec(Hud.Subtitle(SubtitleHudValue.LineJump(next = true))))
    }
}
