package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.CoverageType
import com.raddstream.app.network.models.LinkState
import com.raddstream.app.network.models.Playability
import com.raddstream.app.network.models.ReleaseLabelParsing
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
    fun noVersionsForThisTitleYet() {
        assertEquals(
            "No versions for this title yet.",
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
    fun onlyVipVersionsExist() {
        assertEquals(
            "Only VIP versions exist for this title.",
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
    fun noVersionsForThisViewer() {
        assertEquals(
            "No versions available for this title right now.",
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
            "No working versions right now — download is available.",
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
            "No working versions right now.",
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

    // Detail review (Nov 2026, P1): the dead end's Download target —
    // [deadEndKeepPrint] — the best-ranked print this viewer may
    // actually keep, never the best-ranked print outright.

    private fun keepablePrint(
        releaseId: Int,
        quality: String,
        playability: Playability = Playability.STREAM,
    ) = RankedPrint(
        releaseId = releaseId,
        rawTitle = "$quality copy",
        descriptor = ReleaseLabelParsing.describe(
            releaseTitle = "Film $quality",
            quality = quality,
        ),
        languages = listOf("Hindi"),
        sizeBytes = 1_400_000_000L,
        coverage = CoverageType.WHOLE_WORK,
        season = null,
        episodeFrom = null,
        episodeTo = null,
        links = listOf(
            RankedLink(
                streamId = "h-$releaseId",
                linkId = releaseId,
                health = LinkState.ACTIVE,
                ephemeral = false,
                resumable = true,
                playUrl = "http://example.invalid/play/$releaseId",
                label = "Fast direct",
                kind = "direct",
                playability = playability,
                score = 100,
            ),
        ),
    )

    @Test
    fun `best keepable wins over the best-ranked locked print`() {
        // A free viewer may not keep the 4K copy, so the dead end's
        // Download targets the 720p copy below it — even though the 4K
        // copy outranks it. The 720p print is listed first, so the
        // answer proves rank order, not list order.
        val prints = listOf(keepablePrint(2, "720p"), keepablePrint(1, "2160p"))
        val target = deadEndKeepPrint(prints, premiumAccess = false, freeQualityCap = "720p")
        assertEquals(2, target?.releaseId)
    }

    @Test
    fun `a VIP viewer keeps the best-ranked print`() {
        val prints = listOf(keepablePrint(2, "720p"), keepablePrint(1, "2160p"))
        val target = deadEndKeepPrint(prints, premiumAccess = true, freeQualityCap = "720p")
        assertEquals(1, target?.releaseId)
    }

    @Test
    fun `no keepable print at all answers null`() {
        // Only a locked 4K copy: nothing this viewer may keep, so the
        // dead-end Download disables instead of offering it.
        val prints = listOf(keepablePrint(1, "2160p"))
        assertNull(deadEndKeepPrint(prints, premiumAccess = false, freeQualityCap = "720p"))
    }

    @Test
    fun `a provider page is not a keepable print`() {
        // A WEBVIEW link is a page, not a file — keepable refuses it, so
        // a title with only a page has no Download target either.
        val prints = listOf(keepablePrint(2, "720p", playability = Playability.WEBVIEW))
        assertNull(deadEndKeepPrint(prints, premiumAccess = false, freeQualityCap = "720p"))
    }
}
