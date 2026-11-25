package com.raddstream.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Player resilience (2026-11-25): the coverage arithmetic behind the device proof number. */
class PlayerCoverageTest {

    // A 2 h title of 1_440_000_000 bytes: mean bitrate exactly 200_000 B/s, so every
    // conversion below is exact integer arithmetic, not a rounding judgement.
    private val totalBytes = 1_440_000_000L
    private val durationMs = 7_200_000L

    @Test
    fun `the RAM buffer is the buffered position minus the playhead`() {
        val coverage = coverageAheadMs(
            playheadMs = 3_600_000L,
            durationMs = durationMs,
            totalBytes = totalBytes,
            bytesSaved = 720_000_000L,
            bufferedPositionMs = 3_660_000L,
        )
        assertEquals(60_000L, coverage.ramAheadMs)
        assertEquals(0L, coverage.diskAheadMs)
        assertEquals(60_000L, coverage.totalAheadMs)
    }

    @Test
    fun `disk ahead converts the fetched bytes past the playhead to time`() {
        // 1 h in, 960 MB fetched: 240 MB past the 720 MB playhead byte is 20 min at 200 kB/s.
        val coverage = coverageAheadMs(
            playheadMs = 3_600_000L,
            durationMs = durationMs,
            totalBytes = totalBytes,
            bytesSaved = 960_000_000L,
            bufferedPositionMs = 3_600_000L,
        )
        assertEquals(0L, coverage.ramAheadMs)
        assertEquals(1_200_000L, coverage.diskAheadMs)
        assertEquals(1_200_000L, coverage.totalAheadMs)
    }

    @Test
    fun `the total is the longer cover, never the sum`() {
        // The RAM buffer sits inside the downloaded prefix when the player reads from the
        // preview file, so adding them would count the same 60 s twice.
        val coverage = coverageAheadMs(
            playheadMs = 3_600_000L,
            durationMs = durationMs,
            totalBytes = totalBytes,
            bytesSaved = 960_000_000L,
            bufferedPositionMs = 3_660_000L,
        )
        assertEquals(60_000L, coverage.ramAheadMs)
        assertEquals(1_200_000L, coverage.diskAheadMs)
        assertEquals(1_200_000L, coverage.totalAheadMs)
    }

    @Test
    fun `an unknown total means the disk cover is zero, not a guessed bitrate`() {
        val coverage = coverageAheadMs(
            playheadMs = 3_600_000L,
            durationMs = durationMs,
            totalBytes = null,
            bytesSaved = 960_000_000L,
            bufferedPositionMs = 3_660_000L,
        )
        assertEquals(60_000L, coverage.ramAheadMs)
        assertEquals(0L, coverage.diskAheadMs)
        assertEquals(60_000L, coverage.totalAheadMs)
    }

    @Test
    fun `bytes behind the playhead are no cover at all`() {
        // The fetcher is behind the playhead (a seek past what is on disk): disk 0.
        val coverage = coverageAheadMs(
            playheadMs = 3_600_000L,
            durationMs = durationMs,
            totalBytes = totalBytes,
            bytesSaved = 100_000_000L,
            bufferedPositionMs = 3_600_000L,
        )
        assertEquals(0L, coverage.diskAheadMs)
        assertEquals(0L, coverage.totalAheadMs)
    }

    @Test
    fun `no cover exceeds what is left to play`() {
        // 10 s from the end with the whole file on disk: the cover is 10 s, not 2 h.
        val coverage = coverageAheadMs(
            playheadMs = 7_190_000L,
            durationMs = durationMs,
            totalBytes = totalBytes,
            bytesSaved = totalBytes,
            bufferedPositionMs = 7_200_000L,
        )
        assertEquals(10_000L, coverage.ramAheadMs)
        assertEquals(10_000L, coverage.diskAheadMs)
        assertEquals(10_000L, coverage.totalAheadMs)
    }

    @Test
    fun `a stale buffered position is zero RAM cover`() {
        val coverage = coverageAheadMs(
            playheadMs = 3_600_000L,
            durationMs = durationMs,
            totalBytes = totalBytes,
            bytesSaved = 720_000_000L,
            bufferedPositionMs = 3_000_000L,
        )
        assertEquals(0L, coverage.ramAheadMs)
        assertEquals(0L, coverage.totalAheadMs)
    }
}
