package com.raddstream.app.data.cache

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The live rate behind the Downloads keep row and the player's keep chip:
 * bytes moved between samples, over the same last-five-samples window the
 * device downloads run — 0 while calculating or stalled, never a guess.
 */
class TransferRateTrackerTest {

    @Test
    fun `one sample is calculating, two samples give the rate`() {
        val tracker = TransferRateTracker()
        tracker.sample(taskId = 1L, downloadedBytes = 0L, nowElapsedMs = 0L)
        assertEquals(0L, tracker.bytesPerSec(1L))
        tracker.sample(taskId = 1L, downloadedBytes = 6_000_000L, nowElapsedMs = 3_000L)
        assertEquals(2_000_000L, tracker.bytesPerSec(1L))
    }

    @Test
    fun `a stalled task falls to zero instead of freezing on an old speed`() {
        val tracker = TransferRateTracker()
        tracker.sample(taskId = 2L, downloadedBytes = 0L, nowElapsedMs = 0L)
        tracker.sample(taskId = 2L, downloadedBytes = 6_000_000L, nowElapsedMs = 3_000L)
        assertEquals(2_000_000L, tracker.bytesPerSec(2L))
        // Three more ticks, no bytes moved: the window is all stall.
        tracker.sample(taskId = 2L, downloadedBytes = 6_000_000L, nowElapsedMs = 6_000L)
        tracker.sample(taskId = 2L, downloadedBytes = 6_000_000L, nowElapsedMs = 9_000L)
        tracker.sample(taskId = 2L, downloadedBytes = 6_000_000L, nowElapsedMs = 12_000L)
        tracker.sample(taskId = 2L, downloadedBytes = 6_000_000L, nowElapsedMs = 15_000L)
        tracker.sample(taskId = 2L, downloadedBytes = 6_000_000L, nowElapsedMs = 18_000L)
        assertEquals(0L, tracker.bytesPerSec(2L))
    }

    @Test
    fun `tasks are tracked independently`() {
        val tracker = TransferRateTracker()
        tracker.sample(taskId = 1L, downloadedBytes = 0L, nowElapsedMs = 0L)
        tracker.sample(taskId = 1L, downloadedBytes = 3_000_000L, nowElapsedMs = 3_000L)
        tracker.sample(taskId = 2L, downloadedBytes = 0L, nowElapsedMs = 0L)
        tracker.sample(taskId = 2L, downloadedBytes = 9_000_000L, nowElapsedMs = 3_000L)
        assertEquals(1_000_000L, tracker.bytesPerSec(1L))
        assertEquals(3_000_000L, tracker.bytesPerSec(2L))
    }

    @Test
    fun `forget drops the rate when the task finishes`() {
        val tracker = TransferRateTracker()
        tracker.sample(taskId = 1L, downloadedBytes = 0L, nowElapsedMs = 0L)
        tracker.sample(taskId = 1L, downloadedBytes = 3_000_000L, nowElapsedMs = 3_000L)
        assertEquals(1_000_000L, tracker.bytesPerSec(1L))
        tracker.forget(1L)
        assertEquals(0L, tracker.bytesPerSec(1L))
    }

    @Test
    fun `an unknown task reads zero`() {
        assertEquals(0L, TransferRateTracker().bytesPerSec(99L))
    }
}
