package com.raddstream.app.viewmodel

import com.raddstream.app.data.downloads.DeviceDownload
import com.raddstream.app.data.downloads.DeviceDownloadState
import com.raddstream.app.data.local.WatchHistoryEntity
import com.raddstream.app.ui.screens.deviceAttemptLine
import com.raddstream.app.ui.screens.keepActionLabel
import com.raddstream.app.ui.screens.keepAttemptLine
import com.raddstream.app.ui.screens.qualityBadgeParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Downloads rules: one list with files first, a keep-confirm size that
 * never guesses, and watched auto-delete that only ever names a finished
 * movie — never a half-watched film, never a series keep.
 */
class DownloadsRulesTest {

    private fun movieRow(
        title: String = "Example Film",
        positionMs: Long = 3_590_000L,
        durationMs: Long = 3_600_000L,
    ) = WatchHistoryEntity(
        workRef = "imdb:tt9999999",
        postId = 101,
        title = title,
        posterUrl = null,
        seasonNumber = 0,
        episodeNumber = 0,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = 1_000L,
    )

    @Test
    fun `watched ids match by post id, never by title`() {
        // My List review (Nov 2026): the same end-guard as the rail, but
        // the identity is the post id — two works sharing a clean
        // title must never cross-mark each other.
        val finished = movieRow(title = "Dune", positionMs = 3_600_000L)
        val half = WatchHistoryEntity(
            workRef = "work-2",
            postId = 102,
            title = "Dune",
            posterUrl = null,
            seasonNumber = 0,
            episodeNumber = 0,
            positionMs = 1_800_000L,
            durationMs = 3_600_000L,
            updatedAt = 1_000L,
        )
        assertEquals(setOf(101), watchedPostIds(listOf(finished, half)))
    }

    @Test
    fun `a finished episode marks its series watched by post id`() {
        val episode = WatchHistoryEntity(
            workRef = "work-9",
            postId = 9,
            title = "Gen V",
            posterUrl = null,
            seasonNumber = 2,
            episodeNumber = 5,
            positionMs = 3_600_000L,
            durationMs = 3_600_000L,
            updatedAt = 1_000L,
        )
        assertEquals(setOf(9), watchedPostIds(listOf(episode)))
    }

    @Test
    fun `an unroutable row is not a finished work`() {
        val row = movieRow(positionMs = 3_600_000L).copy(postId = 0)
        assertTrue(watchedPostIds(listOf(row)).isEmpty())
    }

    @Test
    fun `the keep-confirm size is a real size or an honest unknown`() {
        assertEquals("Size unknown", keepSizeLabel(null))
        assertEquals("Size unknown", keepSizeLabel(0L))
        assertEquals("2.5 MB", keepSizeLabel(2_500_000L))
        assertEquals("1.50 GB", keepSizeLabel(1_500_000_000L))
    }

    @Test
    fun `one list puts files first, then keeps`() {
        val state = DownloadsUiState(
            deviceDownloads = listOf(
                DeviceDownload(
                    id = "d1",
                    title = "A File",
                    printLabel = "720p",
                    state = DeviceDownloadState.READY,
                    bytesDownloaded = 10L,
                    bytesTotal = 10L,
                    localUri = null,
                    mimeType = "video/mp4",
                    fileName = "a.mp4",
                ),
            ),
            items = listOf(
                KeptItem(
                    id = "7",
                    title = "A Keep",
                    printLabel = "1080p",
                    bytesDownloaded = 10L,
                    bytesTotal = 10L,
                    state = KeepState.READY,
                    expiresAtMillis = null,
                ),
            ),
        )
        val rows = state.allDownloads
        assertEquals(2, rows.size)
        assertTrue(rows[0] is DownloadRow.Device)
        assertTrue(rows[1] is DownloadRow.Keep)
    }

    @Test
    fun `a print label identical to the title is not painted twice`() {
        // Robo run 9: a keep whose engine task has a blank title and a
        // "Rank #1" print label renders "Rank #1" twice on one card — once
        // as the title (the title-to-print-label fallback) and once as the
        // print label. The row paints the label only when it says something
        // the title does not already say.
        val keep = KeptItem(
            id = "7",
            title = "Rank #1",
            printLabel = "Rank #1",
            bytesDownloaded = 0L,
            bytesTotal = 0L,
            state = KeepState.FAILED,
            expiresAtMillis = null,
        )
        assertEquals(null, keep.shownPrintLabel())
        val file = DeviceDownload(
            id = "d7",
            title = "Rank #1",
            printLabel = "Rank #1",
            state = DeviceDownloadState.FAILED,
            bytesDownloaded = 0L,
            bytesTotal = 0L,
            localUri = null,
            mimeType = "video/mp4",
            fileName = "rank1.mp4",
        )
        assertEquals(null, file.shownPrintLabel())
    }

    @Test
    fun `a real quality label is still painted`() {
        val keep = KeptItem(
            id = "7",
            title = "Fall",
            printLabel = "1080p · High bitrate",
            bytesDownloaded = 0L,
            bytesTotal = 0L,
            state = KeepState.FAILED,
            expiresAtMillis = null,
        )
        assertEquals("1080p · High bitrate", keep.shownPrintLabel())
        assertEquals("Fall — 1080p · High bitrate", keep.printLabelDescription())
    }

    @Test
    fun `two rows sharing a rank label speak differently`() {
        val fall = KeptItem(
            id = "7",
            title = "Fall",
            printLabel = "Rank #1",
            bytesDownloaded = 0L,
            bytesTotal = 0L,
            state = KeepState.FAILED,
            expiresAtMillis = null,
        )
        val dune = KeptItem(
            id = "8",
            title = "Dune",
            printLabel = "Rank #1",
            bytesDownloaded = 0L,
            bytesTotal = 0L,
            state = KeepState.FAILED,
            expiresAtMillis = null,
        )
        assertEquals("Fall — Rank #1", fall.printLabelDescription())
        assertEquals("Dune — Rank #1", dune.printLabelDescription())
        assertNotEquals(fall.printLabelDescription(), dune.printLabelDescription())
    }

    @Test
    fun `keep rows are labelled Delete when ready or expiring and Cancel when in-flight`() {
        assertEquals("Delete", keepActionLabel(KeepState.READY))
        assertEquals("Delete", keepActionLabel(KeepState.EXPIRING))
        assertEquals("Cancel", keepActionLabel(KeepState.DOWNLOADING))
        assertEquals("Cancel", keepActionLabel(KeepState.QUEUED))
        assertEquals("Cancel", keepActionLabel(KeepState.PAUSED))
        assertEquals("Retry", keepActionLabel(KeepState.FAILED))
        assertEquals("Retry", keepActionLabel(KeepState.NO_SPACE))
        // Phase 4 (Downloads #4): a gated row has no Retry to label — its
        // × dismisses the row.
        assertEquals("Dismiss", keepActionLabel(KeepState.GATED))
    }

    @Test
    fun `failure tokens map to viewer words, unknown tokens map to nothing`() {
        assertEquals("The source stopped responding.", failureReasonFor("ephemeral_dead"))
        assertEquals("The link expired and could not be refreshed.", failureReasonFor("no_fresh_url"))
        assertEquals("A network error interrupted the download.", failureReasonFor("transient"))
        assertEquals("The download could not be completed.", failureReasonFor("sequential_failed"))
        assertNull(failureReasonFor("something_new"))
        assertNull(failureReasonFor(null))
        // The park reasons are states, not failures: they never reach this mapping.
        assertNull(failureReasonFor("gated"))
        assertNull(failureReasonFor("no_space"))
    }

    @Test
    fun `quality badge splits the label, rank labels stay whole`() {
        // D21: the badge takes the quality token; the rest stays a label.
        // "Rank #1" is not a quality, so it stays whole (run-9 rule).
        assertEquals("1080p" to "High bitrate", qualityBadgeParts("1080p · High bitrate"))
        assertEquals("720p" to null, qualityBadgeParts("720p"))
        assertEquals("4K" to "HDR", qualityBadgeParts("4K · HDR"))
        assertEquals(null to "Rank #1", qualityBadgeParts("Rank #1"))
        assertEquals(null to null, qualityBadgeParts(null))
        assertEquals(null to null, qualityBadgeParts(""))
        assertEquals(null to null, qualityBadgeParts("   "))
    }

    @Test
    fun `attempt lines count tries, unrecorded counts say nothing`() {
        // D12: a retry is a counted try; a count that was never recorded
        // renders nothing rather than inventing "Attempt 1".
        assertEquals("Attempt 2 of 6", keepAttemptLine(2))
        assertEquals("Attempt 1 of 6", keepAttemptLine(1))
        assertNull(keepAttemptLine(0))
        assertEquals("Attempt 2", deviceAttemptLine(2))
        assertEquals("Attempt 1", deviceAttemptLine(1))
        assertNull(deviceAttemptLine(0))
    }

    @Test
    fun `device failure kinds map to viewer words, unknown kinds map to nothing`() {
        // Phase 4 (Downloads #5): the device side's mirror of the keep
        // mapping — no raw HTTP text ever reaches the screen.
        assertEquals(
            "This copy is no longer available. Retry to get a fresh link.",
            deviceFailureReasonFor("link_expired"),
        )
        assertEquals(
            "The host refused this download. Try another version.",
            deviceFailureReasonFor("host_refused"),
        )
        assertEquals(
            "Too many requests at once. Wait a minute, then retry.",
            deviceFailureReasonFor("rate_limited"),
        )
        assertEquals(
            "The connection dropped. Retry to resume where it stopped.",
            deviceFailureReasonFor("transfer_failed"),
        )
        assertEquals(
            "The download stopped before it finished.",
            deviceFailureReasonFor("incomplete_file"),
        )
        assertEquals(
            "Could not save the file on this device. Check storage and retry.",
            deviceFailureReasonFor("save_failed"),
        )
        assertNull(deviceFailureReasonFor("something_new"))
        assertNull(deviceFailureReasonFor(null))
        // A raw backend string is not a kind: it maps to nothing, so the
        // generic line stands alone rather than leaking it.
        assertNull(deviceFailureReasonFor("HTTP 404"))
        assertNull(deviceFailureReasonFor("Unexpected content type: text/html"))
    }
}
