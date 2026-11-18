package com.raddstream.app.data.downloads

import com.raddstream.app.network.models.CoverageType
import com.raddstream.app.network.models.LinkState
import com.raddstream.app.network.models.Playability
import com.raddstream.app.network.models.ReleaseLabelParsing
import com.raddstream.app.viewmodel.EpisodeGroup
import com.raddstream.app.viewmodel.RankedLink
import com.raddstream.app.viewmodel.RankedPrint
import com.raddstream.app.viewmodel.SeasonGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Smart Downloads, plan E2: the planner picks the first unwatched,
 * unkept, keepable episode — and nothing else. These tests pin the
 * rule without a repository, a network, or a disk.
 */
class SmartDownloadsPlannerTest {

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

    private fun episode(season: Int, number: Int, prints: List<RankedPrint>) = EpisodeGroup(
        season = season,
        numberWithinSeason = number,
        absoluteNumber = number,
        title = "Episode $number",
        prints = prints,
    )

    private fun season(number: Int, episodes: List<EpisodeGroup>) = SeasonGroup(
        number = number,
        episodes = episodes,
        packedPrints = emptyList(),
    )

    @Test
    fun picksFirstUnwatchedEpisode() {
        val next = SmartDownloadsPlanner.nextEpisode(
            seasons = listOf(
                season(1, listOf(
                    episode(1, 1, listOf(print(11, "720p"))),
                    episode(1, 2, listOf(print(21, "720p"))),
                )),
            ),
            watched = emptySet(),
            keptHandles = emptySet(),
            premiumAccess = false,
            freeQualityCap = "720p",
        )
        assertEquals("h-11", next?.handle)
        assertEquals(1, next?.episode)
        assertEquals("720p", next?.printLabel)
    }

    @Test
    fun skipsWatchedEpisodes() {
        val next = SmartDownloadsPlanner.nextEpisode(
            seasons = listOf(
                season(1, listOf(
                    episode(1, 1, listOf(print(11, "720p"))),
                    episode(1, 2, listOf(print(21, "720p"))),
                    episode(1, 3, listOf(print(31, "720p"))),
                )),
            ),
            watched = setOf(1 to 1, 1 to 2),
            keptHandles = emptySet(),
            premiumAccess = false,
            freeQualityCap = "720p",
        )
        assertEquals("h-31", next?.handle)
        assertEquals(3, next?.episode)
    }

    @Test
    fun skipsAlreadyKeptEpisodes() {
        val next = SmartDownloadsPlanner.nextEpisode(
            seasons = listOf(
                season(1, listOf(
                    episode(1, 1, listOf(print(11, "720p"))),
                    episode(1, 2, listOf(print(21, "720p"))),
                )),
            ),
            watched = emptySet(),
            keptHandles = setOf("h-11"),
            premiumAccess = false,
            freeQualityCap = "720p",
        )
        assertEquals("h-21", next?.handle)
    }

    @Test
    fun skipsPackedOnlyEpisodes() {
        val next = SmartDownloadsPlanner.nextEpisode(
            seasons = listOf(
                season(1, listOf(
                    episode(1, 1, listOf(print(11, "720p", coverage = CoverageType.SEASON_PACK))),
                    episode(1, 2, listOf(print(21, "720p"))),
                )),
            ),
            watched = emptySet(),
            keptHandles = emptySet(),
            premiumAccess = false,
            freeQualityCap = "720p",
        )
        assertEquals("h-21", next?.handle)
    }

    @Test
    fun vipLockedBestPrintIsSkippedNeverDownloaded() {
        val next = SmartDownloadsPlanner.nextEpisode(
            seasons = listOf(
                season(1, listOf(
                    episode(1, 1, listOf(print(11, "1080p"))),
                    episode(1, 2, listOf(print(21, "720p"))),
                )),
            ),
            watched = emptySet(),
            keptHandles = emptySet(),
            premiumAccess = false,
            freeQualityCap = "720p",
        )
        assertEquals("h-21", next?.handle)
    }

    @Test
    fun vipViewerGetsThePremiumEpisodeToo() {
        val next = SmartDownloadsPlanner.nextEpisode(
            seasons = listOf(
                season(1, listOf(episode(1, 1, listOf(print(11, "1080p"))))),
            ),
            watched = emptySet(),
            keptHandles = emptySet(),
            premiumAccess = true,
            freeQualityCap = "720p",
        )
        assertEquals("h-11", next?.handle)
    }

    @Test
    fun afterCursorPicksTheNextEpisodeAfterTheFinishedOne() {
        val next = SmartDownloadsPlanner.nextEpisode(
            seasons = listOf(
                season(1, listOf(
                    episode(1, 1, listOf(print(11, "720p"))),
                    episode(1, 2, listOf(print(21, "720p"))),
                )),
            ),
            watched = emptySet(),
            keptHandles = emptySet(),
            premiumAccess = false,
            freeQualityCap = "720p",
            after = 1 to 1,
        )
        assertEquals("h-21", next?.handle)
    }

    @Test
    fun afterCursorAtSeasonEndCrossesIntoTheNextSeason() {
        val next = SmartDownloadsPlanner.nextEpisode(
            seasons = listOf(
                season(1, listOf(episode(1, 1, listOf(print(11, "720p"))))),
                season(2, listOf(episode(2, 1, listOf(print(21, "720p"))))),
            ),
            watched = emptySet(),
            keptHandles = emptySet(),
            premiumAccess = false,
            freeQualityCap = "720p",
            after = 1 to 1,
        )
        assertEquals("h-21", next?.handle)
        assertEquals(2, next?.season)
    }

    @Test
    fun returnsNullWhenEveryEpisodeIsWatched() {
        val next = SmartDownloadsPlanner.nextEpisode(
            seasons = listOf(
                season(1, listOf(
                    episode(1, 1, listOf(print(11, "720p"))),
                    episode(1, 2, listOf(print(21, "720p"))),
                )),
            ),
            watched = setOf(1 to 1, 1 to 2),
            keptHandles = emptySet(),
            premiumAccess = false,
            freeQualityCap = "720p",
        )
        assertNull(next)
    }

    @Test
    fun episodeWithNoLinkIsSkippedSilently() {
        val next = SmartDownloadsPlanner.nextEpisode(
            seasons = listOf(
                season(1, listOf(
                    episode(1, 1, listOf(print(11, "720p", handle = null))),
                    episode(1, 2, listOf(print(21, "720p"))),
                )),
            ),
            watched = emptySet(),
            keptHandles = emptySet(),
            premiumAccess = false,
            freeQualityCap = "720p",
        )
        assertEquals("h-21", next?.handle)
    }

    @Test
    fun returnsNullWhenNothingIsKeepable() {
        val next = SmartDownloadsPlanner.nextEpisode(
            seasons = listOf(
                season(1, listOf(episode(1, 1, listOf(print(11, "720p", handle = null))))),
            ),
            watched = emptySet(),
            keptHandles = emptySet(),
            premiumAccess = false,
            freeQualityCap = "720p",
        )
        assertNull(next)
    }
}
