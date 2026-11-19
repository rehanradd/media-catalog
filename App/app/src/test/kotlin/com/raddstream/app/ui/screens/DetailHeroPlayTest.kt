package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.CoverageType
import com.raddstream.app.network.models.LinkState
import com.raddstream.app.network.models.Playability
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.network.models.ReleaseLabelParsing
import com.raddstream.app.player.LadderForScope
import com.raddstream.app.player.QualityOption
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.RankedLink
import com.raddstream.app.viewmodel.RankedPrint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Detail review (Nov 2026, P0): the hero's Play must be the best print
 * that can actually start here, not the best-ranked print. Before the
 * fix the hero hid Play whenever only the best print could not play
 * here — while the ladder below still offered Play on another rung, so
 * the two regions disagreed about the same title. These tests pin the
 * state matrix, including which print a hero Play press actually plays.
 */
@RunWith(RobolectricTestRunner::class)
class DetailHeroPlayTest {

    @get:Rule
    val compose = createComposeRule()

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
        synopsis = "A man is driven by a personal vendetta.",
        castNames = "Shah Rukh Khan, Nayanthara",
        industry = "Bollywood",
        language = "Hindi",
    )

    private fun print(releaseId: Int, quality: String, playable: Boolean) = RankedPrint(
        releaseId = releaseId,
        rawTitle = "$quality copy",
        descriptor = ReleaseLabelParsing.describe("Jawan $quality WEB-DL", quality),
        languages = listOf("Hindi"),
        sizeBytes = 1_400_000_000L,
        coverage = CoverageType.WHOLE_WORK,
        season = null,
        episodeFrom = null,
        episodeTo = null,
        // One healthy, directly playable link makes the print playable
        // here; no links makes it download-only.
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
        workPrints: List<RankedPrint>,
        ladder: LadderForScope,
        deadEndReason: String? = null,
        onPlayPrint: (RankedPrint) -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                DetailContent(
                    post = post,
                    seasons = emptyList(),
                    workPrints = workPrints,
                    capabilities = emptyList(),
                    isSeries = false,
                    resume = null,
                    onPlayResume = {},
                    includedPill = null,
                    deadEndReason = deadEndReason,
                    onOpenSeason = {},
                    onPlayPrint = onPlayPrint,
                    onOpenExternal = {},
                    onKeep = {},
                    rungs = RungActions(
                        ladder = ladder,
                        selectedBand = null,
                        onPlay = {},
                        onKeep = {},
                        tierLabel = { "Standard" },
                    ),
                    onActorClick = {},
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

    private fun ladder() = LadderForScope(
        options = listOf(option("720p", "720p HD")),
        autoBand = "720p",
        downloadOnlyBands = emptyList(),
    )

    @Test
    fun playableBestShowsPlayAndPlaysIt() {
        val played = mutableListOf<Int>()
        content(
            workPrints = listOf(print(releaseId = 2160, quality = "2160p", playable = true)),
            ladder = ladder(),
            onPlayPrint = { played += it.releaseId },
        )
        compose.onNodeWithText("Play").assertIsDisplayed().performClick()
        assertEquals(listOf(2160), played)
    }

    @Test
    fun playableNonBestShowsPlayAndPlaysIt() {
        // The best-ranked print (2160p) cannot play here; the 720p print
        // below it can. The hero used to hide Play entirely in this
        // state while the ladder still offered it — the contradiction.
        val played = mutableListOf<Int>()
        content(
            workPrints = listOf(
                print(releaseId = 2160, quality = "2160p", playable = false),
                print(releaseId = 720, quality = "720p", playable = true),
            ),
            ladder = ladder(),
            onPlayPrint = { played += it.releaseId },
        )
        compose.onNodeWithText("Play").assertIsDisplayed().performClick()
        assertEquals(listOf(720), played)
        compose.onNodeWithText("Not available to stream yet — download it instead.")
            .assertDoesNotExist()
    }

    @Test
    fun downloadOnlyShowsReasonNotPlay() {
        content(
            workPrints = listOf(print(releaseId = 2160, quality = "2160p", playable = false)),
            ladder = ladder(),
            deadEndReason = "No working sources right now — download is available.",
        )
        compose.onNodeWithText("No working sources right now — download is available.")
            .assertIsDisplayed()
        compose.onNodeWithText("Play").assertDoesNotExist()
        compose.onNodeWithText("Download").assertIsDisplayed()
    }

    @Test
    fun downloadOnlyFallsBackToNoteWhenReasonMissing() {
        content(
            workPrints = listOf(print(releaseId = 2160, quality = "2160p", playable = false)),
            ladder = ladder(),
            deadEndReason = null,
        )
        compose.onNodeWithText("Not available to stream yet — download it instead.")
            .assertIsDisplayed()
        compose.onNodeWithText("Play").assertDoesNotExist()
    }

    @Test
    fun noPrintsShowsDisabledDownloadNotPlay() {
        content(
            workPrints = emptyList(),
            ladder = LadderForScope(
                options = emptyList(),
                autoBand = null,
                downloadOnlyBands = emptyList(),
            ),
        )
        compose.onNodeWithText("Play").assertDoesNotExist()
        compose.onNodeWithText("Download").assertIsDisplayed().assertIsNotEnabled()
    }
}
