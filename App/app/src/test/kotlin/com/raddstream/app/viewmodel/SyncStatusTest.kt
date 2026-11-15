package com.raddstream.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Gap A5: the Home sync/status line says what is true — how fresh the
 * rows are — and nothing when there is nothing to date. The My List
 * line is gone (mockup, Nov 2026): syncing happens silently, so its
 * tests went with it.
 */
class SyncStatusTest {

    @Test
    fun `relative time buckets`() {
        assertEquals("just now", relativeTimeAgo(epochMs = 1_000L, nowMs = 30_000L))
        assertEquals("1 min ago", relativeTimeAgo(epochMs = 1_000L, nowMs = 61_000L))
        assertEquals("2 min ago", relativeTimeAgo(epochMs = 1_000L, nowMs = 121_000L))
        assertEquals("1 hour ago", relativeTimeAgo(epochMs = 0L, nowMs = 3_600_000L))
        assertEquals("3 hours ago", relativeTimeAgo(epochMs = 0L, nowMs = 3 * 3_600_000L))
        assertEquals("1 day ago", relativeTimeAgo(epochMs = 0L, nowMs = 86_400_000L))
        assertEquals("2 days ago", relativeTimeAgo(epochMs = 0L, nowMs = 2 * 86_400_000L))
        assertEquals("just now", relativeTimeAgo(epochMs = 9_999L, nowMs = 1_000L))
    }

    @Test
    fun `home line is null until home has loaded`() {
        assertNull(homeSyncLine(lastUpdatedAtMs = null, isOffline = false, nowMs = 1_000L))
    }

    @Test
    fun `home line names the age, and offline says so`() {
        assertEquals("Updated 2 min ago", homeSyncLine(1_000L, isOffline = false, nowMs = 121_000L))
        assertEquals(
            "Offline — showing saved Home from 2 min ago",
            homeSyncLine(1_000L, isOffline = true, nowMs = 121_000L),
        )
    }
}
