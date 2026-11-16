package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.player.LadderForScope
import com.raddstream.app.player.QualityOption
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.EpisodeGroup
import com.raddstream.app.viewmodel.SeasonGroup
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Detail redesign (Nov 2026): the synopsis moves directly under the Play
 * actions with the quality ladder below it, the version list and its
 * summary are gone, the similar rail is "More Like This", a series with
 * more than five seasons gets a dropdown instead of a chip strip, and a
 * title with nothing to play gets its reason in plain words plus a way
 * out. The overflow menu itself (Report moved out of the top bar) is
 * covered by the Paparazzi detailMovie golden, not here — the top bar
 * lives in DetailScreen, above the DetailContent this file can render.
 */
@RunWith(RobolectricTestRunner::class)
class DetailRedesignTest {

    @get:Rule
    val compose = createComposeRule()

    private val synopsis = "A man is driven by a personal vendetta."

    private val post = PostDetail(
        id = 1,
        title = "Jawan",
        cleanTitle = "Jawan",
        year = 2023,
        runtimeMinutes = 169,
        contentType = "movie",
        posterUrl = null,
        backdropUrls = null,
        imdbRating = "7.0",
        genres = "Action, Thriller",
        synopsis = synopsis,
        castNames = "Shah Rukh Khan, Nayanthara",
        industry = "Bollywood",
        language = "Hindi",
    )

    private fun option(band: String, title: String) = QualityOption(
        band = band,
        title = title,
        streamId = "h-$band",
        playUrl = "http://127.0.0.1:8080/api/v1/stream?id=h-$band",
        sizeBytes = null,
        backupHandles = emptyList(),
        playsHere = true,
        resumable = false,
        copies = 1,
        isProviderPage = false,
        audioLanguages = emptyList(),
    )

    private fun content(
        isSeries: Boolean = false,
        seasons: List<SeasonGroup> = emptyList(),
        selectedSeason: Int? = null,
        similarTitles: List<Post> = emptyList(),
        deadEndReason: String? = null,
    ) {
        compose.setContent {
            RaddStreamTheme {
                DetailContent(
                    post = post,
                    seasons = seasons,
                    workPrints = emptyList(),
                    capabilities = emptyList(),
                    isSeries = isSeries,
                    resume = null,
                    onPlayResume = {},
                    episodeProgress = emptyMap(),
                    includedLine = null,
                    deadEndReason = deadEndReason,
                    selectedSeason = selectedSeason,
                    onSelectSeason = {},
                    onPlayPrint = {},
                    onOpenExternal = {},
                    onKeep = {},
                    rungs = RungActions(
                        ladder = LadderForScope(
                            options = listOf(option("720p", "HD - X264")),
                            autoBand = "720p",
                            downloadOnlyBands = emptyList(),
                        ),
                        selectedBand = null,
                        onPlay = {},
                        onKeep = {},
                        tierLabel = { "Standard" },
                    ),
                    ladderScope = null,
                    onAskRungs = { _, _ -> },
                    hasRungs = { _, _ -> false },
                    onActorClick = {},
                    similarTitles = similarTitles,
                    onPostClick = {},
                    reloadError = null,
                    onRetryReload = {},
                    isFavorite = false,
                    onToggleFavorite = {},
                    onShare = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
    }

    @Test
    fun synopsisSitsAboveTheQualityLadder() {
        content()
        compose.onNodeWithText("Quality").performScrollTo()
        val synopsisTop = compose.onNodeWithText(synopsis)
            .fetchSemanticsNode().boundsInRoot.top
        val qualityTop = compose.onNodeWithText("Quality")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(synopsisTop < qualityTop)
    }

    @Test
    fun similarRailIsCalledMoreLikeThis() {
        content(similarTitles = listOf(Post(id = 2, title = "Jawan 2")))
        // The rail is the last item in a LazyColumn, so it is not even
        // composed until the list scrolls near it — a scroll-to on a
        // node that does not exist yet cannot find it. Swipe until it
        // appears, then bring it fully into view.
        for (i in 0 until 5) {
            if (compose.onAllNodesWithText("More Like This").fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeUp() }
        }
        compose.onNodeWithText("More Like This").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Similar titles").assertDoesNotExist()
    }

    @Test
    fun seasonDropdownReplacesChipsPastFiveSeasons() {
        val seasons = (1..6).map { number ->
            SeasonGroup(
                number = number,
                episodes = listOf(
                    EpisodeGroup(
                        season = number,
                        numberWithinSeason = 1,
                        absoluteNumber = number,
                        title = "Pilot",
                        prints = emptyList(),
                    ),
                ),
                packedPrints = emptyList(),
            )
        }
        content(isSeries = true, seasons = seasons, selectedSeason = 1)
        // The dropdown sits below the fold in the test window, so like
        // the similar rail it may not even be composed yet — swipe until
        // it appears, then bring it fully into view.
        for (i in 0 until 5) {
            if (compose.onAllNodesWithText("Season 1 · 1 episode").fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeUp() }
        }
        compose.onNodeWithText("Season 1 · 1 episode").performScrollTo().assertIsDisplayed()
        // The chip strip would paint a bare "Season 2" chip; the dropdown
        // keeps the other seasons behind its closed menu.
        compose.onNodeWithText("Season 2").assertDoesNotExist()
    }

    @Test
    fun deadEndShowsReasonWithoutStandaloneLink() {
        content(
            deadEndReason = "No sources for this title yet.",
            similarTitles = listOf(Post(id = 2, title = "Jawan 2")),
        )
        compose.onNodeWithText("No sources for this title yet.").performScrollTo().assertIsDisplayed()
        // Detail review (Nov 2026, P1): the standalone "Browse similar
        // titles" link is gone — the way out moved into the utility row
        // under the synopsis as the Similar action.
        compose.onNodeWithText("Browse similar titles").assertDoesNotExist()
        compose.onNodeWithText("Play").assertDoesNotExist()
        compose.onNodeWithText("Similar").assertIsDisplayed()
        compose.onNodeWithText("Similar").performClick()
        compose.onNodeWithText("More Like This").assertIsDisplayed()
    }
}
