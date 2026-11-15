package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.player.LadderForScope
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.ResumePoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetailPaywallTest {

    @get:Rule
    val compose = createComposeRule()

    // A playable title with no prints at all: the resume point alone makes
    // it playable, so the sponsor card's visibility turns only on the
    // paywall flag — no print or rung fixture is needed to judge it.
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

    private fun content(paywallVisible: Boolean) {
        compose.setContent {
            RaddStreamTheme {
                DetailContent(
                    post = post,
                    seasons = emptyList(),
                    workPrints = emptyList(),
                    capabilities = emptyList(),
                    officialPrintCount = 0,
                    camPrintCount = 0,
                    isSeries = false,
                    resume = ResumePoint(season = 1, episode = 3, positionMs = 1_000_000L, durationMs = 5_000_000L),
                    onPlayResume = {},
                    episodeProgress = emptyMap(),
                    includedLine = null,
                    selectedSeason = null,
                    onSelectSeason = {},
                    onPlayPrint = {},
                    onOpenExternal = {},
                    onKeep = {},
                    rungs = RungActions(
                        ladder = LadderForScope(options = emptyList(), autoBand = null, downloadOnlyBands = emptyList()),
                        selectedBand = null,
                        onPlay = {},
                        onKeep = {},
                        tierLabel = { "Standard" },
                    ),
                    ladderScope = null,
                    adFree = false,
                    premiumSourcesLocked = false,
                    paywallVisible = paywallVisible,
                    onAskRungs = { _, _ -> },
                    hasRungs = { _, _ -> false },
                    onOpenVipUpgrade = {},
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
    fun sponsorCardShowsWhileTheQualityGateIsOn() {
        content(paywallVisible = true)
        compose.onNodeWithText("Go VIP").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sponsorCardHidesWhileTheQualityGateIsOff() {
        // Free launch: gate OFF means every viewer is premium — the
        // sponsor card hides even though the title is playable and the
        // viewer is neither ad-free nor VIP.
        content(paywallVisible = false)
        compose.onNodeWithText("Go VIP").assertDoesNotExist()
    }
}
