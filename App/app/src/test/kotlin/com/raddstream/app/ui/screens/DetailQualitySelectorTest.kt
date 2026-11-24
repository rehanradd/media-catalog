package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.player.LadderForScope
import com.raddstream.app.player.QualityOption
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.QualitySheetMode
import com.raddstream.app.viewmodel.QualitySheetState
import com.raddstream.app.viewmodel.sheetRowsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Audit Detail #1 (Nov 2026): the Video row is the visible quality
 * control, and the sheet it opens picks a band without playing. The
 * row shows the pick ("Video: 720p") or what Auto reaches ("Video:
 * Auto · up to 720p"), hides when nothing plays, and a tap opens the
 * browse sheet — whose row taps select, never play.
 */
@RunWith(RobolectricTestRunner::class)
class DetailQualitySelectorTest {

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

    private fun content(
        videoQualityText: String? = null,
        onOpenQualitySheet: () -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                DetailContent(
                    post = post,
                    seasons = emptyList(),
                    workPrints = emptyList(),
                    capabilities = emptyList(),
                    isSeries = false,
                    resume = null,
                    includedPill = null,
                    playLabel = "Play",
                    playEnabled = true,
                    onPlayPrimary = {},
                    downloadLabel = "Download",
                    downloadEnabled = true,
                    videoQualityText = videoQualityText,
                    onOpenQualitySheet = onOpenQualitySheet,
                    onOpenSeason = {},
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

    @Test
    fun videoRowShowsAutoQualityAndOpensBrowseSheet() {
        var opened = false
        content(
            videoQualityText = "Auto · up to 720p",
            onOpenQualitySheet = { opened = true },
        )
        compose.onNodeWithText("Video: Auto · up to 720p").assertIsDisplayed().performClick()
        assertTrue(opened)
    }

    @Test
    fun videoRowShowsThePickedBand() {
        content(videoQualityText = "720p")
        compose.onNodeWithText("Video: 720p").assertIsDisplayed()
    }

    @Test
    fun videoRowHidesWhenNothingPlays() {
        content(videoQualityText = null)
        compose.onAllNodesWithText("Video:", substring = true).assertCountEquals(0)
    }

    private fun option(band: String, title: String = band) = QualityOption(
        band = band,
        title = title,
        streamId = "h-$band",
        playUrl = "http://example.invalid/play/$band",
        sizeBytes = null,
        backupHandles = emptyList(),
        playsHere = true,
        resumable = true,
        copies = 1,
        isProviderPage = false,
        streamCategory = null,
    )

    private fun ladder() = LadderForScope(
        options = listOf(
            option("1080p", "1080p Full HD"),
            option("720p", "720p HD"),
            option("480p", "480p"),
        ),
        autoBand = "720p",
        downloadOnlyBands = emptyList(),
    )

    private fun browseSheet(
        selectedBand: String?,
        onSelectOption: (QualityOption) -> Unit,
        onSelectAuto: () -> Unit,
        onPlayOption: (QualityOption) -> Unit = {},
    ) {
        val rows = sheetRowsFor(
            ladder(), QualitySheetMode.BROWSE, premiumAccess = false, freeQualityCap = "720p",
            selectedBand = selectedBand,
        )
        compose.setContent {
            RaddStreamTheme {
                // The rows without their PlayerSheet dialog window: a tap
                // inside a ModalBottomSheet never reaches its row under
                // Robolectric (found and displayed, click silently lost),
                // so the content is composed directly here. The sheet
                // itself is only ever shown through DetailQualitySheet.
                DetailQualitySheetContent(
                    sheet = QualitySheetState(
                        mode = QualitySheetMode.BROWSE,
                        ladder = ladder(),
                        recommendedBand = selectedBand ?: LadderForScope.AUTO,
                        rows = rows,
                    ),
                    onPlayOption = onPlayOption,
                    onDownloadOption = {},
                    onSeriesRow = {},
                    onBatchBand = {},
                    onLockedRow = {},
                    onSelectOption = onSelectOption,
                    onSelectAuto = onSelectAuto,
                )
            }
        }
    }

    @Test
    fun browseSheetRowTapSelectsInsteadOfPlaying() {
        var selected: String? = null
        var played = false
        browseSheet(
            selectedBand = "720p",
            onSelectOption = { selected = it.band },
            onSelectAuto = { selected = LadderForScope.AUTO },
            onPlayOption = { played = true },
        )
        compose.onNodeWithText("Choose quality").assertIsDisplayed()
        // The sheet opens partially expanded (shared PlayerSheet), so
        // lower rows sit below the fold until scrolled into view — the
        // sheet's column scrolls, so bring the row up before tapping.
        compose.onNodeWithText("720p HD").performScrollTo().assertIsDisplayed().performClick()
        assertEquals("720p", selected)
        assertFalse(played)
    }

    @Test
    fun browseSheetAutoRowSelectsAuto() {
        var selected: String? = null
        browseSheet(
            selectedBand = "720p",
            onSelectOption = { selected = it.band },
            onSelectAuto = { selected = LadderForScope.AUTO },
        )
        // The Auto row can straddle the partial-expansion fold — scroll
        // it fully into view so the tap lands on the row, not the fold.
        compose.onNodeWithText("Auto").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(LadderForScope.AUTO, selected)
    }
}
