package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.CoverageType
import com.raddstream.app.network.models.LinkState
import com.raddstream.app.network.models.Playability
import com.raddstream.app.network.models.ReleaseLabelParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detail redesign (Nov 2026): Cast moves to the Detail page, and a
 * season-page tile plays its episode's best print. Both decisions are
 * pure functions, tested here without the ViewModel — the ViewModel
 * itself is never constructed in tests (its constructor needs the
 * whole app).
 */
class DetailCastGateTest {

    private fun link(
        handle: String,
        playability: Playability = Playability.BOTH,
        kind: String = "direct",
    ) = RankedLink(
        streamId = handle,
        linkId = null,
        health = LinkState.ACTIVE,
        ephemeral = false,
        resumable = true,
        playUrl = "http://127.0.0.1:8080/api/v1/stream?id=$handle",
        label = "Standard",
        kind = kind,
        playability = playability,
        score = 100,
    )

    private fun print(
        releaseId: Int,
        quality: String,
        playability: Playability = Playability.BOTH,
        kind: String = "direct",
    ) = RankedPrint(
        releaseId = releaseId,
        rawTitle = "$quality copy",
        descriptor = ReleaseLabelParsing.describe(releaseTitle = "A Show", quality = quality),
        languages = emptyList(),
        sizeBytes = null,
        coverage = CoverageType.SINGLE_EPISODE,
        season = null,
        episodeFrom = null,
        episodeTo = null,
        links = listOf(link("h-$releaseId", playability, kind)),
    )

    private fun layoutWithEpisode(episode: Int, prints: List<RankedPrint>) = ReleaseLayout(
        isSeriesLike = true,
        seasons = listOf(
            SeasonGroup(
                number = 1,
                episodes = listOf(
                    EpisodeGroup(
                        season = 1,
                        numberWithinSeason = episode,
                        absoluteNumber = episode,
                        title = "Episode $episode",
                        prints = prints,
                    ),
                ),
                packedPrints = emptyList(),
            ),
        ),
        workPrints = emptyList(),
        capabilities = emptyList(),
        officialPrintCount = 0,
        camPrintCount = 0,
        totalPrints = prints.size,
    )

    @Test
    fun castNeedsBothAReceiverAndTheServerFlag() {
        assertTrue(castAvailableFor(devicesAvailable = true, featureEnabled = true))
        assertFalse(castAvailableFor(devicesAvailable = false, featureEnabled = true))
        assertFalse(castAvailableFor(devicesAvailable = true, featureEnabled = false))
        assertFalse(castAvailableFor(devicesAvailable = false, featureEnabled = false))
    }

    @Test
    fun tilePlaysTheBestPlayablePrint() {
        val layout = layoutWithEpisode(
            episode = 2,
            prints = listOf(print(1, "720p"), print(2, "1080p")),
        )
        assertEquals(2, episodePlayPrint(layout, season = 1, episode = 2)?.releaseId)
    }

    @Test
    fun tileWithNothingPlayablePlaysNothing() {
        val layout = layoutWithEpisode(episode = 2, prints = emptyList())
        assertNull(episodePlayPrint(layout, season = 1, episode = 2))
    }

    @Test
    fun tileSkipsADeadPrintForAWorkingOne() {
        // A dead link's print cannot start here — the tile plays the
        // working copy, not the best-ranked dead one.
        val dead = print(1, "2160p").copy(
            links = listOf(link("h-1").copy(health = LinkState.DEAD)),
        )
        val layout = layoutWithEpisode(episode = 2, prints = listOf(dead, print(2, "720p")))
        assertEquals(2, episodePlayPrint(layout, season = 1, episode = 2)?.releaseId)
    }

    @Test
    fun tilePlaysAPagePrintWhenThatIsAllThereIs() {
        val page = print(1, "1080p", playability = Playability.WEBVIEW, kind = "external")
        val layout = layoutWithEpisode(episode = 2, prints = listOf(page))
        assertEquals(1, episodePlayPrint(layout, season = 1, episode = 2)?.releaseId)
    }

    @Test
    fun missingSeasonOrEpisodePlaysNothing() {
        val layout = layoutWithEpisode(episode = 2, prints = listOf(print(1, "720p")))
        assertNull(episodePlayPrint(layout, season = 9, episode = 2))
        assertNull(episodePlayPrint(layout, season = 1, episode = 9))
        assertNull(episodePlayPrint(null, season = 1, episode = 2))
    }
}
