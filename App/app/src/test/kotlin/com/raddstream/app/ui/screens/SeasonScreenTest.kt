package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.CoverageType
import com.raddstream.app.network.models.LinkState
import com.raddstream.app.network.models.Playability
import com.raddstream.app.network.models.ReleaseLabelParsing
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.RankedLink
import com.raddstream.app.viewmodel.RankedPrint
import com.raddstream.app.viewmodel.SeasonGroup
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Detail redesign (Nov 2026): the season sub-page is plain numbered
 * tiles. The screen itself needs the shared DetailViewModel, which
 * tests never construct — so this file renders the tile and pack-row
 * pieces the page is made of: the grid renders, a dimmed tile does
 * nothing when tapped, a playable tile plays, and the pack row says
 * what it is and offers its own Play.
 */
@RunWith(RobolectricTestRunner::class)
class SeasonScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun tileRow(onClick: (Int) -> Unit = {}) {
        compose.setContent {
            RaddStreamTheme {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EpisodeTile(
                        tile = SeasonTile(number = 1, playable = true, watched = true, isNext = false),
                        onClick = { onClick(1) },
                        modifier = Modifier.weight(1f),
                    )
                    EpisodeTile(
                        tile = SeasonTile(number = 2, playable = true, watched = false, isNext = true),
                        onClick = { onClick(2) },
                        modifier = Modifier.weight(1f),
                    )
                    EpisodeTile(
                        tile = SeasonTile(number = 3, playable = false, watched = false, isNext = false),
                        onClick = { onClick(3) },
                        modifier = Modifier.weight(1f),
                    )
                    EpisodeTile(
                        tile = SeasonTile(number = 4, playable = true, watched = false, isNext = false),
                        onClick = { onClick(4) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    private fun packPrint() = RankedPrint(
        releaseId = 1,
        rawTitle = "Complete season",
        descriptor = ReleaseLabelParsing.describe(releaseTitle = "A Show S01 Complete", quality = "1080p"),
        languages = emptyList(),
        sizeBytes = null,
        coverage = CoverageType.SEASON_PACK,
        season = null,
        episodeFrom = null,
        episodeTo = null,
        links = listOf(
            RankedLink(
                streamId = "h-1",
                linkId = null,
                health = LinkState.ACTIVE,
                ephemeral = false,
                resumable = true,
                playUrl = "http://127.0.0.1:8080/api/v1/stream?id=h-1",
                label = "Standard",
                kind = "direct",
                playability = Playability.BOTH,
                score = 100,
            ),
        ),
    )

    @Test
    fun tilesRenderWithTheirNumbers() {
        tileRow()
        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("4").assertIsDisplayed()
    }

    @Test
    fun dimmedTileHasNoTapAction() {
        // A number with no playable file stays on screen dimmed — and
        // tapping it must do nothing: its click is disabled, so the
        // node reads as not enabled.
        tileRow()
        compose.onNodeWithText("3").assertIsNotEnabled()
    }

    @Test
    fun playableTileTapPlaysThatEpisode() {
        var played: Int? = null
        tileRow(onClick = { played = it })
        compose.onNodeWithText("2").performClick()
        assertEquals(2, played)
    }

    @Test
    fun packRowSaysItIsOneFileAndOffersPlay() {
        var played = false
        val season = SeasonGroup(
            number = 1,
            episodes = emptyList(),
            packedPrints = listOf(packPrint()),
            packOnly = true,
        )
        compose.setContent {
            RaddStreamTheme {
                Column(modifier = Modifier.padding(16.dp)) {
                    SeasonPackRow(
                        season = season,
                        onPlay = { played = true },
                        onKeep = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Season 1 — Full season").assertIsDisplayed()
        compose.onNodeWithText("This season is offered as one file — playing it starts the whole season.")
            .assertIsDisplayed()
        compose.onNodeWithText("Play season").performClick()
        assertEquals(true, played)
    }
}
