package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.player.LadderForScope
import com.raddstream.app.player.QualityOption
import com.raddstream.app.ui.theme.RaddStreamTheme
import androidx.compose.ui.test.performScrollTo
import com.raddstream.app.viewmodel.QualitySheetMode
import com.raddstream.app.viewmodel.QualitySheetState
import com.raddstream.app.viewmodel.sheetRowsFor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * APP-02 (Phase 4): the visible quality control on Detail — one chip per
 * playable band under the two buttons, and a "Video:" row that opens the
 * browse sheet. These tests pin what the page itself can pin: the chips
 * render one per playable band only, a locked band renders locked (not
 * hidden), a chip tap picks the band without starting playback, and the
 * "Video:" row opens the browse sheet. The locked/dead rules themselves
 * live in the pure layer ([qualityChipsFor], pinned by
 * TwoButtonDetailTest); the ViewModel's answer to a locked tap (the
 * upsell line, no selection change) is its own decision, not the page's.
 * The browse sheet's row dispatch (a row tap picks, the Auto row
 * picks Auto, nothing plays) is pinned by the two browse-sheet tests
 * below, carried from the Detail audit (their Video-row tests went
 * with DetailVideoRow, subsumed by this selector's own Video row).
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

    private fun option(
        band: String,
        playsHere: Boolean = true,
        isProviderPage: Boolean = false,
    ) = QualityOption(
        band = band,
        title = band,
        streamId = "h-$band",
        playUrl = "http://example.invalid/play/$band",
        sizeBytes = null,
        backupHandles = emptyList(),
        playsHere = playsHere,
        resumable = true,
        copies = 1,
        isProviderPage = isProviderPage,
    )

    private val playable = listOf(
        option("1080p"),
        option("720p"),
        option("480p"),
    )

    private fun content(
        qualityOptions: List<QualityOption> = playable,
        lockedQualityBands: Set<String> = emptySet(),
        selectedBand: String? = LadderForScope.AUTO,
        onSelectQualityBand: (String) -> Unit = {},
        onOpenQualitySheet: () -> Unit = {},
        onPlayPrimary: () -> Unit = {},
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
                    playLabel = "Play · Auto",
                    playEnabled = true,
                    onPlayPrimary = onPlayPrimary,
                    downloadLabel = "Download",
                    downloadEnabled = true,
                    onOpenSeason = {},
                    reloadError = null,
                    onRetryReload = {},
                    isFavorite = false,
                    onToggleFavorite = {},
                    onShare = {},
                    qualityOptions = qualityOptions,
                    lockedQualityBands = lockedQualityBands,
                    selectedBand = selectedBand,
                    onSelectQualityBand = onSelectQualityBand,
                    onOpenQualitySheet = onOpenQualitySheet,
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
    }

    @Test
    fun chipsRenderOnePerPlayableBandOnly() {
        content()
        compose.onNodeWithText("1080p Full HD").assertIsDisplayed()
        compose.onNodeWithText("720p HD").assertIsDisplayed()
        compose.onNodeWithText("480p").assertIsDisplayed()
    }

    @Test
    fun deadBandAndProviderPageRenderNothing() {
        // The selector re-checks the publisher's rule, so a stale list
        // can never paint a band that cannot play.
        content(
            qualityOptions = playable + listOf(
                option("sd", playsHere = false),
                option("2160p", isProviderPage = true),
            ),
        )
        compose.onNodeWithText("SD").assertDoesNotExist()
        compose.onNodeWithText("4K Ultra HD").assertDoesNotExist()
        compose.onNodeWithText("720p HD").assertIsDisplayed()
    }

    @Test
    fun lockedBandRendersLockedNotHidden() {
        content(lockedQualityBands = setOf("1080p"))
        // The locked band is still on the page, with its chip — never
        // hidden. The lock icon carries no text; the chip's presence is
        // what this test pins.
        compose.onNodeWithText("1080p Full HD").assertIsDisplayed()
    }

    @Test
    fun tappingAChipPicksTheBandAndDoesNotPlay() {
        var picked: String? = null
        var played = false
        content(
            onSelectQualityBand = { picked = it },
            onPlayPrimary = { played = true },
        )
        compose.onNodeWithText("720p HD").performClick()
        assertEquals("720p", picked)
        assertEquals(false, played)
    }

    @Test
    fun tappingALockedChipStillAnswersTheTap() {
        // The chip fires for a locked band too — the ViewModel decides
        // the lock (upsell line, no selection change); the page must
        // not swallow the tap.
        var picked: String? = null
        content(
            lockedQualityBands = setOf("1080p"),
            onSelectQualityBand = { picked = it },
        )
        compose.onNodeWithText("1080p Full HD").performClick()
        assertEquals("1080p", picked)
    }

    @Test
    fun videoRowNamesTheSelectedBand() {
        content(selectedBand = "720p")
        compose.onNodeWithText("Video: 720p HD").assertIsDisplayed()
    }

    @Test
    fun videoRowNamesAutoWhenNothingPicked() {
        content(selectedBand = null)
        compose.onNodeWithText("Video: Auto").assertIsDisplayed()
    }

    @Test
    fun videoRowOpensTheBrowseSheet() {
        var opened = false
        content(onOpenQualitySheet = { opened = true })
        compose.onNodeWithText("Video: Auto").performClick()
        assertEquals(true, opened)
    }

    @Test
    fun emptyOptionsHideTheSelectorEntirely() {
        content(qualityOptions = emptyList())
        compose.onNodeWithText("Video: Auto").assertDoesNotExist()
        compose.onNodeWithText("720p HD").assertDoesNotExist()
    }

    private fun sheetOption(band: String, title: String = band) = QualityOption(
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

    private fun sheetLadder() = LadderForScope(
        options = listOf(
            sheetOption("1080p", "1080p Full HD"),
            sheetOption("720p", "720p HD"),
            sheetOption("480p", "480p"),
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
            sheetLadder(), QualitySheetMode.BROWSE, premiumAccess = false, freeQualityCap = "720p",
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
                        ladder = sheetLadder(),
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
