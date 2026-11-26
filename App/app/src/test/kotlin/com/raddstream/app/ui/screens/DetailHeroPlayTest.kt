package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Detail review (Nov 2026, P0): the hero's Play must be the best print
 * that can actually start here, not the best-ranked print. Before the
 * fix the hero hid Play whenever only the best print could not play
 * here — while the ladder below still offered Play on another rung, so
 * the two regions disagreed about the same title.
 *
 * 2-button detail (Nov 2026): that disagreement is now structurally
 * impossible — there is ONE Play button, and its label, enabled state
 * and hint all come from the ViewModel's single decision, which print
 * to play. These tests pin what the page itself can pin: the button is
 * plain Play / Resume — the quality lives in the Video row below it —
 * the tap fires the one primary action, and a title with nothing
 * playable shows a greyed Play with its reason in plain words — never
 * a hidden Play, never a second Play somewhere else on the page.
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

    private fun content(
        playLabel: String = "Play",
        playEnabled: Boolean = true,
        onPlayPrimary: () -> Unit = {},
        downloadLabel: String = "Download",
        downloadEnabled: Boolean = true,
        playHint: String? = null,
        downloadHint: String? = null,
        deadEndReason: String? = null,
        onTryAgain: () -> Unit = {},
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
                    deadEndReason = deadEndReason,
                    playLabel = playLabel,
                    playEnabled = playEnabled,
                    onPlayPrimary = onPlayPrimary,
                    downloadLabel = downloadLabel,
                    downloadEnabled = downloadEnabled,
                    playHint = playHint,
                    downloadHint = downloadHint,
                    onTryAgain = onTryAgain,
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
    fun playButtonIsPlainPlayAndFiresPrimary() {
        // Audit Detail #1 (Nov 2026): Play names no quality — the
        // Video row below it carries the quality, so the button is
        // just "Play" and the tap fires the one primary action.
        var fired = false
        content(
            playLabel = "Play",
            onPlayPrimary = { fired = true },
        )
        compose.onNodeWithText("Play").assertIsDisplayed().assertIsEnabled().performClick()
        assertTrue(fired)
    }

    @Test
    fun playButtonNamesTheResumePoint() {
        // Audit Detail #1: resume names the episode and the time left,
        // never the band.
        content(playLabel = "Resume · 23 min left")
        compose.onNodeWithText("Resume · 23 min left").assertIsDisplayed()
    }

    @Test
    fun downloadOnlyShowsReasonAndGreyedPlay() {
        val reason = "No working sources right now — download is available."
        content(
            playEnabled = false,
            playHint = reason,
            deadEndReason = reason,
            downloadEnabled = true,
        )
        compose.onNodeWithText(reason).assertIsDisplayed()
        compose.onNodeWithText("Play").assertIsNotEnabled()
        // P1-7 (Nov 2026): the page now carries two "Download" actions —
        // the primary button above and the utility row's mirror below —
        // so the bare text query is ambiguous once the row composes. The
        // primary is first in tree order (page order is protected), so
        // [0] is the button this test pins.
        compose.onAllNodesWithText("Download")[0].assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun downloadOnlyFallsBackToNoteWhenReasonMissing() {
        content(
            playEnabled = false,
            playHint = "No working link right now. Try again later.",
            deadEndReason = null,
        )
        compose.onNodeWithText("No working link right now. Try again later.")
            .assertIsDisplayed()
        compose.onNodeWithText("Play").assertIsNotEnabled()
        // No reason, no way out on this page — [Try again] only appears
        // when the page knows a reason to retry from.
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun noPrintsShowsDisabledDownloadAndGreyedPlay() {
        content(
            playEnabled = false,
            downloadEnabled = false,
            downloadHint = "Nothing to save for this title.",
        )
        compose.onNodeWithText("Play").assertIsNotEnabled()
        // P1-7 (Nov 2026): two "Download" actions on the page now — the
        // primary button above and the utility row's mirror below. [0]
        // is the primary, first in tree order.
        compose.onAllNodesWithText("Download")[0].assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Nothing to save for this title.").assertIsDisplayed()
    }
}
