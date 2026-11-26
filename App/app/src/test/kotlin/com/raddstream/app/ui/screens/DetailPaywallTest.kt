package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.ResumePoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Detail redesign (Nov 2026): the SponsorCard and the VipSourcesCard are
 * deleted from Detail — the lock is a VIP badge on the rung itself, never
 * a second card.
 *
 * 2-button detail (Nov 2026): the rung cards are gone too, so the lock
 * moved again — into the Quality Sheet, where a locked row shows greyed
 * with a lock and "VIP only" (pinned by TwoButtonDetailTest's sheet-row
 * tests, which build the rows the sheet renders). What this file still
 * pins: no upsell card, and no VIP upsell copy, can ever appear on the
 * detail page itself.
 *
 * P1-6 (Nov 2026): the one exception the audit now mandates — a single
 * upgrade affordance ("4K — Upgrade") on the page, naming the top locked
 * band. It is the only upsell the page may carry: no "Go VIP" card, no
 * locked-message sentence, no chip per locked band (pinned by
 * DetailQualitySelectorTest).
 */
@RunWith(RobolectricTestRunner::class)
class DetailPaywallTest {

    @get:Rule
    val compose = createComposeRule()

    // A playable title with no prints at all: the resume point alone makes
    // it playable, so the upsell question turns only on the page — no
    // print fixture is needed to judge it.
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
        // The included pill the ViewModel would produce for this state:
        // "VIP" when a locked quality exists, null when the gate is off
        // and nothing is locked (free launch).
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
                    includedPill = includedPill,
                    playLabel = "Resume S1:E3 · 720p",
                    playEnabled = true,
                    downloadLabel = "Download",
                    downloadEnabled = true,
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
    fun noVipUpsellCardEverShowsOnDetail() {
        // Even with a VIP pill on the title, neither upsell card may
        // appear: the lock lives in the Quality Sheet now, not on
        // this page.
        content()
        compose.onNodeWithText("Go VIP").assertDoesNotExist()
        compose.onNodeWithText("4K and 1080p are part of VIP").assertDoesNotExist()
    }

    @Test
    fun freeLaunchDetailShowsNoPremiumAtAll() {
        // Free launch: gate OFF means nothing is locked — no VIP text
        // anywhere on the page, cards or pills. The ViewModel produces
        // no included pill either when the gate is off.
        content(includedPill = null)
        compose.onNodeWithText("Go VIP").assertDoesNotExist()
        compose.onNodeWithText("4K and 1080p are part of VIP").assertDoesNotExist()
        compose.onNodeWithText("VIP").assertDoesNotExist()
    }
}
