package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.player.LadderForScope
import com.raddstream.app.player.QualityOption
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.ResumePoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Detail redesign (Nov 2026): the SponsorCard and the VipSourcesCard are
 * deleted from Detail — the lock is a VIP badge on the rung itself, never
 * a second card. These tests pin that no upsell card can ever appear on
 * Detail, while the rung's own VIP tier pill still shows where a locked
 * rung can appear.
 */
@RunWith(RobolectricTestRunner::class)
class DetailPaywallTest {

    @get:Rule
    val compose = createComposeRule()

    // A playable title with no prints at all: the resume point alone makes
    // it playable, so the upsell question turns only on the ladder —
    // no print fixture is needed to judge it.
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
        tierLabel: (QualityOption) -> String,
        // The included pill the ViewModel would produce for this state:
        // "VIP" when a rung is locked, null when the gate is off and
        // nothing is locked (free launch).
        includedPill: String? = "VIP",
    ) {
        compose.setContent {
            RaddStreamTheme {
                DetailContent(
                    post = post,
                    seasons = emptyList(),
                    workPrints = emptyList(),
                    capabilities = emptyList(),
                    isSeries = false,
                    resume = ResumePoint(season = 1, episode = 3, positionMs = 1_000_000L, durationMs = 5_000_000L),
                    onPlayResume = {},
                    episodeProgress = emptyMap(),
                    includedPill = includedPill,
                    selectedSeason = null,
                    onSelectSeason = {},
                    onPlayPrint = {},
                    onOpenExternal = {},
                    onKeep = {},
                    rungs = RungActions(
                        ladder = LadderForScope(
                            options = listOf(option("720p", "HD - X264"), option("1080p", "Full HD · X264")),
                            autoBand = "720p",
                            downloadOnlyBands = emptyList(),
                        ),
                        selectedBand = null,
                        onPlay = {},
                        onKeep = {},
                        tierLabel = tierLabel,
                    ),
                    ladderScope = null,
                    onAskRungs = { _, _ -> },
                    hasRungs = { _, _ -> false },
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
    fun noVipUpsellCardEverShowsOnDetail() {
        // Even with a VIP-tiered rung in the ladder, neither upsell card
        // may appear: the rung's own pill is the lock now.
        content(tierLabel = { if (it.band == "1080p") "VIP" else "Standard" })
        compose.onNodeWithText("Go VIP").assertDoesNotExist()
        compose.onNodeWithText("4K and 1080p are part of VIP").assertDoesNotExist()
    }

    @Test
    fun rungVipTierPillStillShows() {
        content(tierLabel = { if (it.band == "1080p") "VIP" else "Standard" })
        compose.onAllNodes(hasContentDescription("VIP tier, 1080p Full HD"))
            .assertCountEquals(1)
        compose.onAllNodes(hasContentDescription("Standard tier, 720p HD"))
            .assertCountEquals(1)
    }

    @Test
    fun freeLaunchDetailShowsNoPremiumAtAll() {
        // Free launch: gate OFF means every rung is Standard — no VIP
        // text anywhere on the page, cards or pills. The ViewModel
        // produces no included pill either when the gate is off.
        content(tierLabel = { "Standard" }, includedPill = null)
        compose.onNodeWithText("Go VIP").assertDoesNotExist()
        compose.onNodeWithText("4K and 1080p are part of VIP").assertDoesNotExist()
        compose.onNodeWithText("VIP").assertDoesNotExist()
    }
}
