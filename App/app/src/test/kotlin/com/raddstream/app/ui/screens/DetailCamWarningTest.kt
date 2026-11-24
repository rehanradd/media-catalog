package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.CoverageType
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.network.models.ReleaseLabelParsing
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.RankedPrint
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Audit Detail #3 (Nov 2026): the cam warning is a card directly
 * under the actions it qualifies — warning icon, tonal container, the
 * same words — with a "Report copy" button that opens the same report
 * dialog as the utility row's Report. No cam print, no card.
 */
@RunWith(RobolectricTestRunner::class)
class DetailCamWarningTest {

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

    private fun print(releaseTitle: String, quality: String) = RankedPrint(
        releaseId = 1,
        rawTitle = "$quality copy",
        descriptor = ReleaseLabelParsing.describe(releaseTitle = releaseTitle, quality = quality),
        languages = listOf("Hindi"),
        sizeBytes = 1_400_000_000L,
        coverage = CoverageType.WHOLE_WORK,
        season = null,
        episodeFrom = null,
        episodeTo = null,
        links = emptyList(),
    )

    private fun content(
        workPrints: List<RankedPrint>,
        onReport: () -> Unit = {},
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
                    includedPill = null,
                    playLabel = "Play",
                    playEnabled = true,
                    onPlayPrimary = {},
                    downloadLabel = "Download",
                    downloadEnabled = true,
                    onOpenSeason = {},
                    onActorClick = {},
                    reloadError = null,
                    onRetryReload = {},
                    isFavorite = false,
                    onToggleFavorite = {},
                    onShare = {},
                    onReport = onReport,
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
    }

    @Test
    fun camCardShowsWarningAndReportFires() {
        var reported = false
        content(
            workPrints = listOf(print("Jawan (2023) Hindi CAM 480p", "480p")),
            onReport = { reported = true },
        )
        compose.onNodeWithText("Some copies here are cam recordings — low quality.").assertIsDisplayed()
        compose.onNodeWithText("Report copy").assertIsDisplayed().performClick()
        assertTrue(reported)
    }

    @Test
    fun noCamPrintMeansNoCard() {
        content(workPrints = listOf(print("Jawan (2023) Hindi 720p WEB-DL", "720p")))
        compose.onNodeWithText("Report copy").assertDoesNotExist()
        compose.onNodeWithText("Some copies here are cam recordings — low quality.").assertDoesNotExist()
    }
}
