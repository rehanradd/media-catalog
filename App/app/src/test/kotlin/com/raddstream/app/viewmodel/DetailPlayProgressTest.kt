package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.CoverageType
import com.raddstream.app.network.models.LinkState
import com.raddstream.app.network.models.Playability
import com.raddstream.app.network.models.ReleaseLabelParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DT-1 + DT-3 (round 2, Nov 2026): the Play button's determinate
 * progress and the episode row's default season — both pure, so they
 * pin here without a ViewModel. The stage fractions are real pipeline
 * stages, never a timer: 0.20 the tap is accepted and local staging is
 * done, 0.55 the playV2 network call is in flight (the only slow
 * stage), 0.85 the bundle is staged and the player handoff is next.
 * The screen only paints what the ViewModel publishes, and the
 * ViewModel only calls these functions — so a fraction this file pins
 * cannot be re-decided somewhere else on the page.
 */
class DetailPlayProgressTest {

    // ── DT-1: the resolve stages ──

    @Test
    fun stagesAreInPipelineOrder() {
        assertEquals(
            listOf(PlayResolveStage.PREPARING, PlayResolveStage.FINDING_COPY, PlayResolveStage.STARTING_PLAYBACK),
            PlayResolveStage.entries,
        )
    }

    @Test
    fun progressIsMonotonicAndNeverReachesOne() {
        val fractions = PlayResolveStage.entries.map { playResolveProgress(it) }
        assertEquals(listOf(0.20f, 0.55f, 0.85f), fractions)
        // Strictly rising, and no stage claims the work is done — the
        // arc clears on navigation or failure, never by hitting 1.0.
        assertTrue(fractions.zipWithNext().all { (a, b) -> b > a })
        assertTrue(fractions.all { it in 0f..1f })
        assertTrue(fractions.last() < 1f)
    }

    @Test
    fun stageLabelsArePlainWords() {
        assertEquals("Preparing…", playResolveStageLabel(PlayResolveStage.PREPARING))
        assertEquals("Finding the best copy…", playResolveStageLabel(PlayResolveStage.FINDING_COPY))
        assertEquals("Starting playback…", playResolveStageLabel(PlayResolveStage.STARTING_PLAYBACK))
    }

    @Test
    fun everyStageHasAProgressAndALabel() {
        // A stage with no fraction would paint an arc with no fill;
        // a stage with no label would paint "Starting…" for every
        // stage. The two stay in lockstep by construction.
        PlayResolveStage.entries.forEach { stage ->
            assertTrue(playResolveProgress(stage) > 0f)
            assertTrue(playResolveStageLabel(stage).isNotBlank())
        }
    }

    // ── DT-3: the episode row's default season ──

    private fun print(releaseId: Int, playable: Boolean) = RankedPrint(
        releaseId = releaseId,
        rawTitle = "copy $releaseId",
        descriptor = ReleaseLabelParsing.describe("Title 720p WEB-DL", "720p"),
        languages = listOf("Hindi"),
        sizeBytes = 800_000_000L,
        coverage = CoverageType.WHOLE_WORK,
        season = null,
        episodeFrom = null,
        episodeTo = null,
        links = if (playable) {
            listOf(
                RankedLink(
                    streamId = "h-$releaseId",
                    linkId = releaseId,
                    health = LinkState.ACTIVE,
                    ephemeral = false,
                    resumable = true,
                    playUrl = "http://example.invalid/play/$releaseId",
                    label = "Fast direct",
                    kind = "direct",
                    playability = Playability.STREAM,
                    score = 100,
                ),
            )
        } else {
            emptyList()
        },
    )

    private fun layout(playable: Boolean = true): ReleaseLayout {
        val s1 = SeasonGroup(
            number = 1,
            episodes = listOf(
                EpisodeGroup(season = 1, numberWithinSeason = 1, absoluteNumber = 1, title = "Pilot", prints = listOf(print(1, playable))),
                EpisodeGroup(season = 1, numberWithinSeason = 2, absoluteNumber = 2, title = "Second", prints = listOf(print(2, playable))),
            ),
            packedPrints = emptyList(),
        )
        val s2 = SeasonGroup(
            number = 2,
            episodes = listOf(
                EpisodeGroup(season = 2, numberWithinSeason = 1, absoluteNumber = 3, title = "Return", prints = listOf(print(3, playable))),
            ),
            packedPrints = emptyList(),
        )
        return ReleaseLayout(
            isSeriesLike = true,
            seasons = listOf(s1, s2),
            workPrints = emptyList(),
            capabilities = emptyList(),
            officialPrintCount = 3,
            camPrintCount = 0,
            totalPrints = 3,
        )
    }

    private fun watched(vararg keys: Pair<Int, Int>) = keys.associateWith {
        EpisodeProgress(fraction = 1f, watched = true, remainingLabel = null)
    }

    @Test
    fun defaultRowSeasonIsTheNextUnwatchedSeason() {
        // Season 1 fully watched: the row opens on Season 2, where the
        // next episode lives — the row and the Play button agree.
        assertEquals(2, defaultEpisodeRowSeason(layout(), watched(1 to 1, 1 to 2), null))
        // Nothing watched yet: the row opens on Season 1.
        assertEquals(1, defaultEpisodeRowSeason(layout(), emptyMap(), null))
    }

    @Test
    fun defaultRowSeasonFallsBackToResumeWhenNothingCanPlay() {
        // No playable episode anywhere: there is no next, so the resume
        // season steers the row instead of the first season.
        val resume = ResumePoint(season = 2, episode = 1, positionMs = 1_000L, durationMs = 2_000L)
        assertEquals(2, defaultEpisodeRowSeason(layout(playable = false), emptyMap(), resume))
        // No resume either: the first season, honestly.
        assertEquals(1, defaultEpisodeRowSeason(layout(playable = false), emptyMap(), null))
    }

    @Test
    fun defaultRowSeasonIgnoresAResumeForASeasonThatIsNotThere() {
        val resume = ResumePoint(season = 9, episode = 1, positionMs = 1_000L, durationMs = 2_000L)
        assertEquals(1, defaultEpisodeRowSeason(layout(playable = false), emptyMap(), resume))
    }

    @Test
    fun defaultRowSeasonIsNullWithoutALayout() {
        assertNull(defaultEpisodeRowSeason(null, emptyMap(), null))
        assertNull(defaultEpisodeRowSeason(layout().copy(seasons = emptyList()), emptyMap(), null))
    }
}
