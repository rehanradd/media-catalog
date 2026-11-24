package com.raddstream.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.raddstream.app.player.clampVideoPan
import com.raddstream.app.player.isStuckBuffering
import com.raddstream.app.ui.screens.chapterMarkerColor
import com.raddstream.app.ui.screens.xrayContextLine
import com.raddstream.app.ui.theme.RaddCurrent500
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 2 (player fixes) — the pure rules behind the re-rate gaps, each
 * provable on a JVM without a frame: the two-tone chapter markers, the
 * X-Ray context line, the stuck-rebuffer threshold, and the zoom-pan
 * clamp. The renders themselves are Paparazzi goldens
 * (playerBottomChapters, playerCenterStuckBuffer, playerBottomPaused).
 */
class PlayerRound2Test {

    // ── Gap 1: the chapter-marker tone rule ──────────────────────────

    @Test
    fun `a tick behind the playhead is white on the jade played track`() {
        assertEquals(Color.White, chapterMarkerColor(tickFraction = 0.19f, posFraction = 0.29f))
    }

    @Test
    fun `a tick ahead of the playhead is jade on the white unplayed track`() {
        assertEquals(RaddCurrent500, chapterMarkerColor(tickFraction = 0.38f, posFraction = 0.29f))
        assertEquals(RaddCurrent500, chapterMarkerColor(tickFraction = 0.57f, posFraction = 0.29f))
    }

    @Test
    fun `a tick exactly on the playhead counts as played`() {
        assertEquals(Color.White, chapterMarkerColor(tickFraction = 0.29f, posFraction = 0.29f))
    }

    // ── Gap 6: the X-Ray context line ─────────────────────────────────

    @Test
    fun `the full line names episode rung audio and subtitles`() {
        assertEquals(
            "S1 E2 · 1080P · Audio: Hindi · Subtitles: English",
            xrayContextLine(
                episodeLabel = "S1 E2",
                quality = "1080P",
                audio = "Audio: Hindi",
                subtitle = "Subtitles: English",
            ),
        )
    }

    @Test
    fun `a movie never prints an episode`() {
        assertEquals(
            "720P · Subtitles: Off",
            xrayContextLine(
                episodeLabel = null,
                quality = "720P",
                audio = null,
                subtitle = "Subtitles: Off",
            ),
        )
    }

    @Test
    fun `blank parts are omitted, never printed as blanks`() {
        assertEquals(
            "S1 E1",
            xrayContextLine(
                episodeLabel = "S1 E1",
                quality = "  ",
                audio = "",
                subtitle = null,
            ),
        )
    }

    @Test
    fun `with nothing to say the line is empty`() {
        assertEquals(
            "",
            xrayContextLine(episodeLabel = null, quality = null, audio = null, subtitle = null),
        )
    }

    // ── Gap 5: the stuck-rebuffer threshold ───────────────────────────

    @Test
    fun `a stall under five seconds is not stuck`() {
        assertFalse(isStuckBuffering(0L))
        assertFalse(isStuckBuffering(4_999L))
    }

    @Test
    fun `a stall at five seconds is stuck`() {
        assertTrue(isStuckBuffering(5_000L))
        assertTrue(isStuckBuffering(30_000L))
    }

    // ── Gap 7: the zoom-pan clamp ─────────────────────────────────────

    @Test
    fun `a pan inside the limit passes through`() {
        val pan = clampVideoPan(Offset(0.1f, -0.1f))
        assertEquals(0.1f, pan.x, 0.001f)
        assertEquals(-0.1f, pan.y, 0.001f)
    }

    @Test
    fun `a pan past the limit clamps per axis`() {
        val pan = clampVideoPan(Offset(0.5f, -1f))
        assertEquals(0.25f, pan.x, 0.001f)
        assertEquals(-0.25f, pan.y, 0.001f)
    }

    @Test
    fun `no pan stays no pan`() {
        val pan = clampVideoPan(Offset.Zero)
        assertEquals(0f, pan.x, 0.001f)
        assertEquals(0f, pan.y, 0.001f)
    }
}
