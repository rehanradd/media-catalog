package com.raddstream.app.ui.screens

import com.raddstream.app.network.models.CoverageType
import com.raddstream.app.network.models.LinkState
import com.raddstream.app.network.models.Playability
import com.raddstream.app.network.models.ReleaseLabelParsing
import com.raddstream.app.viewmodel.EpisodeGroup
import com.raddstream.app.viewmodel.EpisodeProgress
import com.raddstream.app.viewmodel.RankedLink
import com.raddstream.app.viewmodel.RankedPrint
import com.raddstream.app.viewmodel.SeasonGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detail redesign (Nov 2026): a season's episodes are plain numbered
 * tiles. The tile model is pure — no screen, no ViewModel — so these
 * tests pin it directly: guide-only numbers stay on screen dimmed,
 * never hidden; watched is a tick; next is the first playable,
 * unwatched number; a whole-season file has no tiles at all.
 */
class SeasonTilesTest {

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
        playability: Playability = Playability.BOTH,
        kind: String = "direct",
    ) = RankedPrint(
        releaseId = releaseId,
        rawTitle = "copy",
        descriptor = ReleaseLabelParsing.describe(releaseTitle = "A Show", quality = "1080p"),
        languages = emptyList(),
        sizeBytes = null,
        coverage = CoverageType.SINGLE_EPISODE,
        season = null,
        episodeFrom = null,
        episodeTo = null,
        links = listOf(link("h-$releaseId", playability, kind)),
    )

    private fun episode(number: Int, prints: List<RankedPrint> = listOf(print(number))) =
        EpisodeGroup(
            season = 1,
            numberWithinSeason = number,
            absoluteNumber = number,
            title = "Episode $number",
            prints = prints,
        )

    private fun season(
        episodes: List<EpisodeGroup>,
        totalEpisodes: Int? = null,
        packedPrints: List<RankedPrint> = emptyList(),
        packOnly: Boolean = false,
    ) = SeasonGroup(
        number = 1,
        episodes = episodes,
        packedPrints = packedPrints,
        totalEpisodes = totalEpisodes,
        packOnly = packOnly,
    )

    private fun watched() =
        EpisodeProgress(fraction = 1.0f, watched = true, remainingLabel = null)

    @Test
    fun guideOnlyEpisodesAreDimmedNotHidden() {
        // B2: the guide says 5 episodes, the capped list only carries
        // 3 — tiles 4 and 5 stay on screen dimmed, or the numbers would
        // lie (tap 4, get episode 5).
        val tiles = seasonTiles(
            season(listOf(episode(1), episode(2), episode(3)), totalEpisodes = 5),
            emptyMap(),
        )
        assertEquals(listOf(1, 2, 3, 4, 5), tiles.map { it.number })
        assertTrue(tiles.take(3).all { it.playable })
        assertTrue(tiles.drop(3).all { !it.playable })
    }

    @Test
    fun watchedIsFlaggedAndNextIsFirstPlayableUnwatched() {
        val tiles = seasonTiles(
            season(listOf(episode(1), episode(2), episode(3))),
            mapOf(1 to 1 to watched()),
        )
        assertTrue(tiles[0].watched)
        assertFalse(tiles[0].isNext)
        assertTrue(tiles[1].isNext)
        assertFalse(tiles[2].isNext)
    }

    @Test
    fun noNextWhenAllWatched() {
        val tiles = seasonTiles(
            season(listOf(episode(1), episode(2))),
            mapOf(1 to 1 to watched(), 1 to 2 to watched()),
        )
        assertTrue(tiles.all { !it.isNext })
    }

    @Test
    fun unplayableFirstEpisodeIsNotNext() {
        // Episode 1 has no playable file — the next one to watch is 2,
        // not a dimmed tile that does nothing.
        val tiles = seasonTiles(
            season(
                listOf(
                    episode(1, prints = emptyList()),
                    episode(2),
                ),
            ),
            emptyMap(),
        )
        assertFalse(tiles[0].playable)
        assertFalse(tiles[0].isNext)
        assertTrue(tiles[1].isNext)
    }

    @Test
    fun providerPagePrintCountsAsPlayable() {
        // A print that is only a provider page still opens something —
        // the tile plays it through the provider's own player.
        val pagePrint = print(1, playability = Playability.WEBVIEW, kind = "external")
        val tiles = seasonTiles(season(listOf(episode(1, listOf(pagePrint)))), emptyMap())
        assertTrue(tiles[0].playable)
        assertTrue(tiles[0].isNext)
    }

    @Test
    fun gapInRowsDimsTheMissingNumber() {
        // No guide served: the total is the highest row on screen, and
        // a number with no row is dimmed, never skipped.
        val tiles = seasonTiles(
            season(listOf(episode(1), episode(3))),
            emptyMap(),
        )
        assertEquals(listOf(1, 2, 3), tiles.map { it.number })
        assertFalse(tiles[1].playable)
    }

    @Test
    fun packOnlySeasonHasNoTiles() {
        // A whole-season file has no numbered episodes — the screen
        // shows its one pack row instead, never fake numbers.
        val tiles = seasonTiles(
            season(
                episodes = emptyList(),
                totalEpisodes = 12,
                packedPrints = listOf(print(1, kind = "direct").copy(coverage = CoverageType.SEASON_PACK)),
                packOnly = true,
            ),
            emptyMap(),
        )
        assertTrue(tiles.isEmpty())
    }
}
