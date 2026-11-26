package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * P1-6 (Nov 2026): the quality selector is a "Video:" row plus ONE
 * upgrade affordance — the chip rail is gone. These tests pin what the
 * page itself can pin: no band ever renders as a chip, the Video row
 * still names the pick and opens the browse sheet, a locked band shows
 * exactly one "— Upgrade" affordance naming the top locked band, its tap
 * answers with the upsell (never a selection change), and a locked band
 * that cannot play names no upgrade. The browse sheet's row dispatch is
 * pinned by the two browse-sheet tests below, carried from the Detail
 * audit (their Video-row tests went with DetailVideoRow, subsumed by this
 * selector's own Video row). The locked/dead rules themselves live in
 * the pure layer ([qualityChipsFor], pinned by TwoButtonDetailTest); the
 * ViewModel's answer to a locked tap (the upsell line, no selection
 * change) is its own decision, not the page's.
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
        onLockedQualityBand: () -> Unit = {},
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
                    onLockedQualityBand = onLockedQualityBand,
                    onOpenQualitySheet = onOpenQualitySheet,
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
    }

    @Test
    fun noBandRendersAsAChip() {
        content()
        // The rail is gone: no band name renders as a standalone chip
        // label — the only band text on the page is the Video row's pick.
        compose.onNodeWithText("1080p Full HD").assertDoesNotExist()
        compose.onNodeWithText("720p HD").assertDoesNotExist()
        compose.onNodeWithText("480p").assertDoesNotExist()
        compose.onNodeWithText("Video: Auto").assertIsDisplayed()
    }

    @Test
    fun deadBandAndProviderPageRenderNothing() {
        // The selector re-checks the publisher's rule, so a stale list
        // can never paint a band that cannot play — and a locked band
        // that cannot play names no upgrade either.
        content(
            qualityOptions = playable + listOf(
                option("sd", playsHere = false),
                option("2160p", isProviderPage = true),
            ),
            lockedQualityBands = setOf("2160p"),
        )
        compose.onNodeWithText("SD").assertDoesNotExist()
        compose.onNodeWithText("4K Ultra HD").assertDoesNotExist()
        compose.onNodeWithText("4K — Upgrade").assertDoesNotExist()
        compose.onNodeWithText("720p HD").assertDoesNotExist()
    }

    @Test
    fun lockedBandShowsOneUpgradeAffordanceNotAChip() {
        content(lockedQualityBands = setOf("1080p"))
        // The locked band is still on the page — as the one upgrade
        // affordance, never as a greyed chip. The lock icon carries no
        // text; the affordance's presence is what this test pins.
        compose.onNodeWithText("1080p — Upgrade").assertIsDisplayed()
        compose.onNodeWithText("1080p Full HD").assertDoesNotExist()
    }

    @Test
    fun upgradeAffordanceNamesTheTopLockedBand() {
        content(
            qualityOptions = listOf(option("2160p"), option("1080p"), option("720p")),
            lockedQualityBands = setOf("1080p", "2160p"),
        )
        // One affordance, naming the highest locked band — not one per
        // locked band.
        compose.onNodeWithText("4K — Upgrade").assertIsDisplayed()
        compose.onNodeWithText("1080p — Upgrade").assertDoesNotExist()
    }

    @Test
    fun noLockedBandsMeansNoUpgradeAffordance() {
        content()
        compose.onNodeWithText("Upgrade", substring = true).assertDoesNotExist()
    }

    @Test
    fun tappingUpgradeAffordanceAnswersWithoutPlaying() {
        // The affordance fires the upsell — the ViewModel decides the
        // lock (upsell line, no selection change); the page must not
        // swallow the tap, and nothing starts.
        var upsold = false
        var played = false
        content(
            lockedQualityBands = setOf("1080p"),
            onLockedQualityBand = { upsold = true },
            onPlayPrimary = { played = true },
        )
        compose.onNodeWithContentDescription("Upgrade to unlock 1080p").performClick()
        assertEquals(true, upsold)
        assertEquals(false, played)
    }

    @Test
    fun upgradeAffordanceIs48dp() {
        content(lockedQualityBands = setOf("1080p"))
        compose.onNodeWithContentDescription("Upgrade to unlock 1080p")
            .assertHeightIsAtLeast(48.dp)
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
