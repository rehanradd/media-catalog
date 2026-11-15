package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.CoverageType
import com.raddstream.app.network.models.LinkState
import com.raddstream.app.network.models.Playability
import com.raddstream.app.network.models.ReleaseLabelParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Detail redesign (Nov 2026): "Download season" plans one best keepable
 * copy per episode before it asks. These tests pin the plan — the
 * selection, the locked count, and the size — without a repository.
 */
class DetailSeasonKeepPlanTest {

    private fun link(handle: String) = RankedLink(
        streamId = handle,
        linkId = null,
        health = LinkState.ACTIVE,
        ephemeral = false,
        resumable = true,
        playUrl = "http://127.0.0.1:8080/api/v1/stream?id=$handle",
        label = "Standard",
        kind = "direct",
        playability = Playability.BOTH,
        score = 100,
    )

    private fun print(
        releaseId: Int,
        quality: String?,
        sizeBytes: Long? = null,
        handle: String? = "h-$releaseId",
        coverage: CoverageType = CoverageType.SINGLE_EPISODE,
    ) = RankedPrint(
        releaseId = releaseId,
        rawTitle = "$quality copy",
        descriptor = ReleaseLabelParsing.describe(releaseTitle = "A Show", quality = quality),
        languages = emptyList(),
        sizeBytes = sizeBytes,
        coverage = coverage,
        season = null,
        episodeFrom = null,
        episodeTo = null,
        links = if (handle == null) emptyList() else listOf(link(handle)),
    )

    private fun episode(number: Int, prints: List<RankedPrint>) = EpisodeGroup(
        season = 1,
        numberWithinSeason = number,
        absoluteNumber = number,
        title = "Episode $number",
        prints = prints,
    )

    private fun season(episodes: List<EpisodeGroup>) = SeasonGroup(
        number = 1,
        episodes = episodes,
        packedPrints = emptyList(),
    )

    @Test
    fun freeViewerPlansOneCopyPerEpisodeWithSizesSummed() {
        val plan = seasonKeepPlan(
            season = season(
                listOf(
                    episode(1, listOf(print(11, "720p", sizeBytes = 500_000_000L))),
                    episode(2, listOf(print(21, "720p", sizeBytes = 600_000_000L))),
                ),
            ),
            premiumAccess = false,
            freeQualityCap = "720p",
        )
        assertEquals(2, plan.items.size)
        assertEquals(listOf("h-11", "h-21"), plan.items.map { it.handle })
        assertEquals(0, plan.lockedCount)
        assertEquals(1_100_000_000L, plan.totalSizeBytes)
    }

    @Test
    fun vipLockedEpisodeCountsAsLockedNeverAsDownloadable() {
        val plan = seasonKeepPlan(
            season = season(
                listOf(
                    episode(1, listOf(print(11, "720p"))),
                    episode(2, listOf(print(21, "1080p"))),
                ),
            ),
            premiumAccess = false,
            freeQualityCap = "720p",
        )
        assertEquals(listOf("h-11"), plan.items.map { it.handle })
        assertEquals(1, plan.lockedCount)
    }

    @Test
    fun vipViewerPlansThePremiumEpisodeToo() {
        val plan = seasonKeepPlan(
            season = season(listOf(episode(1, listOf(print(11, "1080p"))))),
            premiumAccess = true,
            freeQualityCap = "720p",
        )
        assertEquals(listOf("h-11"), plan.items.map { it.handle })
        assertEquals(0, plan.lockedCount)
    }

    @Test
    fun packedOnlyEpisodeIsSkipped() {
        val plan = seasonKeepPlan(
            season = season(
                listOf(
                    episode(1, listOf(print(11, "720p", coverage = CoverageType.SEASON_PACK))),
                    episode(2, listOf(print(21, "720p"))),
                ),
            ),
            premiumAccess = false,
            freeQualityCap = "720p",
        )
        assertEquals(listOf("h-21"), plan.items.map { it.handle })
        assertEquals(0, plan.lockedCount)
    }

    @Test
    fun episodeWithNoLinkIsSkippedSilently() {
        val plan = seasonKeepPlan(
            season = season(
                listOf(
                    episode(1, listOf(print(11, "720p", handle = null))),
                    episode(2, listOf(print(21, "720p"))),
                ),
            ),
            premiumAccess = false,
            freeQualityCap = "720p",
        )
        assertEquals(listOf("h-21"), plan.items.map { it.handle })
        assertEquals(0, plan.lockedCount)
    }

    @Test
    fun unknownSizeStaysUnknown() {
        val plan = seasonKeepPlan(
            season = season(listOf(episode(1, listOf(print(11, "720p", sizeBytes = null))))),
            premiumAccess = false,
            freeQualityCap = "720p",
        )
        assertEquals(1, plan.items.size)
        assertNull(plan.totalSizeBytes)
    }
}
