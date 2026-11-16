package com.raddstream.app.resolve

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wilson math, the scorer and the stats-listener counter mapping — plain JVM, no network. */
class PressStatsTest {

    // ── Wilson interval ──────────────────────────────────────────────────

    @Test
    fun `wilson 19 of 20 matches the known interval`() {
        val (low, high) = wilsonInterval(19, 20)
        assertEquals(0.7639, low, 0.0005)
        assertEquals(0.9911, high, 0.0005)
    }

    @Test
    fun `wilson 0 of 20 is zero to 16 percent`() {
        val (low, high) = wilsonInterval(0, 20)
        assertEquals(0.0, low, 0.0005)
        assertEquals(0.1611, high, 0.0005)
    }

    @Test
    fun `wilson 20 of 20 is 84 percent to one`() {
        val (low, high) = wilsonInterval(20, 20)
        assertEquals(0.8389, low, 0.0005)
        assertEquals(1.0, high, 0.0005)
    }

    @Test
    fun `wilson 10 of 20 is symmetric around a half`() {
        val (low, high) = wilsonInterval(10, 20)
        assertEquals(0.2993, low, 0.0005)
        assertEquals(0.7007, high, 0.0005)
    }

    @Test
    fun `wilson with no trials is zero to zero`() {
        assertEquals(0.0 to 0.0, wilsonInterval(0, 0))
    }

    // ── Stats-listener counter mapping ───────────────────────────────────

    @Test
    fun `first frame is the elapsed time since the press and fires once`() {
        val collector = PressStatsCollector(pressStartedElapsedMs = 1_000L)
        collector.recordFirstFrame(1_500L)
        collector.recordFirstFrame(1_800L)
        assertEquals(500L, collector.firstFrameElapsedMs)
    }

    @Test
    fun `buffering before first ready is startup not a rebuffer`() {
        val collector = PressStatsCollector(pressStartedElapsedMs = 0L)
        collector.recordPlaybackState(Player.STATE_BUFFERING)
        collector.recordPlaybackState(Player.STATE_BUFFERING)
        assertEquals(0, collector.rebufferCount)
        collector.recordPlaybackState(Player.STATE_READY)
        collector.recordPlaybackState(Player.STATE_BUFFERING)
        collector.recordPlaybackState(Player.STATE_BUFFERING)
        assertEquals(2, collector.rebufferCount)
    }

    @Test
    fun `dropped frames and bytes accumulate`() {
        val collector = PressStatsCollector(pressStartedElapsedMs = 0L)
        collector.recordDroppedFrames(3)
        collector.recordDroppedFrames(2)
        collector.recordBytesLoaded(100L)
        collector.recordBytesLoaded(50L)
        assertEquals(5, collector.droppedFrames)
        assertEquals(150L, collector.bytesRead)
        assertNull(collector.statsTotalPlayTimeMs)
        assertNull(collector.statsRebufferCount)
    }

    @Test
    fun `playback stats totals land on the snapshot`() {
        val collector = PressStatsCollector(pressStartedElapsedMs = 0L)
        collector.recordFirstFrame(700L)
        collector.applyPlaybackStats(totalPlayTimeMs = 4_000L, totalRebufferCount = 2)
        val snapshot = collector.snapshot()
        assertEquals(700L, snapshot.firstFrameElapsedMs)
        assertEquals(4_000L, snapshot.statsTotalPlayTimeMs)
        assertEquals(2, snapshot.statsRebufferCount)
    }

    // ── Scorer + repeat eligibility ──────────────────────────────────────

    private fun press(
        service: String,
        t1: Long?,
        t2: Long?,
        t3: Long?,
        kind: AttemptKind = AttemptKind.NORMAL,
    ): PressResult = PressResult(
        linkId = 1L,
        postId = 1L,
        service = service,
        attemptKind = kind,
        pressStartedAtMs = 1_700_000_000_000L,
        mintStartedAtMs = 1_700_000_000_100L,
        mintFinishedAtMs = 1_700_000_000_150L,
        mintTo302Ms = t1,
        mintHttpStatus = 302,
        mintedUrl = "https://cdn.example.com/film.mp4",
        pressToFirstFrameMs = t2,
        rebufferCount = 0,
        droppedFrames = 0,
        bytesRead = 0L,
        statsTotalPlayTimeMs = null,
        statsRebufferCount = null,
        playError = null,
        downloadTapStartedAtMs = 1_700_000_000_200L,
        tapToFirstByteMs = t3,
        downloadCancelled = true,
        downloadError = null,
    )

    @Test
    fun `scorer counts passes per bar and overall`() {
        val results = List(20) { press("filepress", 50L, 900L, if (it < 15) 400L else null) } +
            List(20) { press("1fichier", 60L, 1_100L, 500L) }
        val scores = scorePressResults(results, AttemptKind.NORMAL)
        val filepress = scores.first { it.group == "filepress" }
        assertEquals(20, filepress.t1.passes)
        assertEquals(20, filepress.t1.trials)
        assertEquals(20, filepress.t2.passes)
        assertEquals(15, filepress.t3.passes)
        val overall = scores.first { it.group == "overall" }
        assertEquals(40, overall.t1.trials)
        assertEquals(35, overall.t3.passes)
    }

    @Test
    fun `a failed press counts as a miss on every bar`() {
        val failed = press("filepress", null, null, null).copy(playError = "no_first_frame")
        val scores = scorePressResults(listOf(failed), AttemptKind.NORMAL)
        val filepress = scores.first { it.group == "filepress" }
        assertEquals(0, filepress.t1.passes)
        assertEquals(1, filepress.t1.trials)
    }

    @Test
    fun `15 to 17 of 20 earns one repeat, 14 and 18 do not`() {
        val fifteen = List(20) { press("filepress", if (it < 15) 50L else null, 900L, 400L) }
        assertEquals(listOf("filepress"), repeatEligibleGroups(fifteen, emptySet()))
        val fourteen = List(20) { press("filepress", if (it < 14) 50L else null, 900L, 400L) }
        assertTrue(repeatEligibleGroups(fourteen, emptySet()).isEmpty())
        val eighteen = List(20) { press("filepress", if (it < 18) 50L else null, 900L, 400L) }
        assertTrue(repeatEligibleGroups(eighteen, emptySet()).isEmpty())
        assertTrue(repeatEligibleGroups(fifteen, setOf("filepress")).isEmpty())
    }
}
