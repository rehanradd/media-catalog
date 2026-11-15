package com.raddstream.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Detail redesign (Nov 2026): a title with nothing to play gets its
 * reason in plain words, not a bare sentence and not silence. Every
 * branch of [deadEndReasonFor] is pinned here.
 */
class DetailDeadEndReasonTest {

    @Test
    fun noReleasesAtAll() {
        assertEquals(
            "No sources for this title yet.",
            deadEndReasonFor(
                hasReleases = false,
                gatedHasPrints = false,
                gatedPlayable = false,
                gatedKeepable = false,
                premiumLocked = false,
            ),
        )
    }

    @Test
    fun onlyVipCopiesExist() {
        assertEquals(
            "Only VIP copies exist for this title.",
            deadEndReasonFor(
                hasReleases = true,
                gatedHasPrints = false,
                gatedPlayable = false,
                gatedKeepable = false,
                premiumLocked = true,
            ),
        )
    }

    @Test
    fun noCopiesForThisViewer() {
        assertEquals(
            "No copies available for this title right now.",
            deadEndReasonFor(
                hasReleases = true,
                gatedHasPrints = false,
                gatedPlayable = false,
                gatedKeepable = false,
                premiumLocked = false,
            ),
        )
    }

    @Test
    fun unplayableButDownloadable() {
        assertEquals(
            "No working sources right now — download is available.",
            deadEndReasonFor(
                hasReleases = true,
                gatedHasPrints = true,
                gatedPlayable = false,
                gatedKeepable = true,
                premiumLocked = false,
            ),
        )
    }

    @Test
    fun unplayableAndNotDownloadable() {
        assertEquals(
            "No working sources right now.",
            deadEndReasonFor(
                hasReleases = true,
                gatedHasPrints = true,
                gatedPlayable = false,
                gatedKeepable = false,
                premiumLocked = false,
            ),
        )
    }

    @Test
    fun playableTitleHasNoReason() {
        assertNull(
            deadEndReasonFor(
                hasReleases = true,
                gatedHasPrints = true,
                gatedPlayable = true,
                gatedKeepable = true,
                premiumLocked = false,
            ),
        )
    }
}
