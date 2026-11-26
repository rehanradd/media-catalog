package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P1-7 (Nov 2026): the utility row is four visible icon + label actions
 * — Save · Rate (thumbs) · Share · Download — plus a More overflow
 * [Follow, Report, Similar]. These tests pin what the page itself can
 * pin: only the four are visible with the menu closed, the overflow
 * carries the rest and fires each one, Download mirrors the primary
 * Download (same tap, same enabled state, same spoken name), every
 * target is 48dp, and the labels hold at 200% font scale. The scroll to
 * the "More Like This" rail behind Similar is the dead end's
 * bringIntoView, owned by DetailContent — this file pins the tap reaches
 * it (the menu closes), not the scroll itself.
 */
@RunWith(RobolectricTestRunner::class)
class DetailUtilityRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val post = PostDetail(
        id = 1,
        title = "Jawan",
        cleanTitle = "Jawan",
        year = 2023,
        runtimeMinutes = 169,
        contentType = "series",
        posterUrl = null,
        backdropUrls = null,
        imdbRating = "7.0",
        genres = "Action, Thriller",
        synopsis = "A man is driven by a personal vendetta.",
        castNames = "Shah Rukh Khan, Nayanthara",
        industry = "Bollywood",
        language = "Hindi",
        workRef = "jawan-2023",
    )

    private val moviePost = post.copy(contentType = "movie", workRef = null)

    private fun content(
        postDetail: PostDetail = post,
        isSeries: Boolean = true,
        similarTitles: List<Post> = listOf(Post(id = 2, title = "Jawan 2")),
        thumb: Int = 0,
        onThumb: (Int) -> Unit = {},
        onToggleFollow: () -> Unit = {},
        onReport: () -> Unit = {},
        downloadLabel: String = "Download · 720p · 1.3 GB",
        downloadEnabled: Boolean = true,
        onDownloadPrimary: () -> Unit = {},
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale),
            ) {
                RaddStreamTheme {
                    DetailContent(
                        post = postDetail,
                        seasons = emptyList(),
                        workPrints = emptyList(),
                        capabilities = emptyList(),
                        isSeries = isSeries,
                        resume = null,
                        includedPill = null,
                        playLabel = "Play",
                        playEnabled = true,
                        onPlayPrimary = {},
                        downloadLabel = downloadLabel,
                        downloadEnabled = downloadEnabled,
                        onDownloadPrimary = onDownloadPrimary,
                        onOpenSeason = {},
                        similarTitles = similarTitles,
                        reloadError = null,
                        onRetryReload = {},
                        isFavorite = false,
                        onToggleFavorite = {},
                        thumb = thumb,
                        onThumb = onThumb,
                        onToggleFollow = onToggleFollow,
                        onShare = {},
                        onReport = onReport,
                        contentPadding = PaddingValues(0.dp),
                    )
                }
            }
        }
    }

    private fun openOverflow() {
        compose.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .performClick()
    }

    @Test
    fun fourVisibleActionsOnly() {
        content()
        compose.onNodeWithText("Save").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rate").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Share").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Download").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("More").performScrollTo().assertIsDisplayed()
        // The overflow's actions are not on the page while the menu is
        // closed — Report never at icon parity with Save, Similar never
        // a visible icon at all, Follow never a sixth identical icon.
        compose.onNodeWithText("Report").assertDoesNotExist()
        compose.onNodeWithText("Similar").assertDoesNotExist()
        compose.onNodeWithText("Notify me").assertDoesNotExist()
        // The thumbs carry no text labels of their own — the pair is
        // one "Rate" group.
        compose.onNodeWithText("Like").assertDoesNotExist()
        compose.onNodeWithText("Dislike").assertDoesNotExist()
    }

    @Test
    fun overflowCarriesFollowReportSimilar() {
        content()
        openOverflow()
        compose.onNodeWithText("Notify me").assertIsDisplayed()
        compose.onNodeWithText("Report").assertIsDisplayed()
        compose.onNodeWithText("Similar").assertIsDisplayed()
    }

    @Test
    fun overflowReportFires() {
        var reported = false
        content(onReport = { reported = true })
        openOverflow()
        compose.onNodeWithContentDescription("Report a broken stream").performClick()
        assertEquals(true, reported)
    }

    @Test
    fun overflowSimilarFiresAndClosesTheMenu() {
        content()
        openOverflow()
        compose.onNodeWithContentDescription("Show similar titles").performClick()
        // The tap reaches the scroll-to-rail action and the menu closes
        // behind it — the rail scroll itself is DetailContent's
        // bringIntoView, not this row's to prove.
        compose.onNodeWithText("Report").assertDoesNotExist()
    }

    @Test
    fun overflowFollowFires() {
        var followed = false
        content(onToggleFollow = { followed = true })
        openOverflow()
        compose.onNodeWithContentDescription("Notify me about new episodes").performClick()
        assertEquals(true, followed)
    }

    @Test
    fun thumbsVote() {
        var voted: Int? = null
        content(onThumb = { voted = it })
        compose.onNodeWithContentDescription("Like this title")
            .performScrollTo()
            .performClick()
        assertEquals(1, voted)
        compose.onNodeWithContentDescription("Dislike this title")
            .performScrollTo()
            .performClick()
        assertEquals(-1, voted)
    }

    @Test
    fun downloadMirrorsThePrimaryDownload() {
        var downloaded = false
        content(onDownloadPrimary = { downloaded = true })
        // The utility action's spoken name is the primary's full label
        // — the cost, not just the verb.
        compose.onNodeWithContentDescription("Download · 720p · 1.3 GB")
            .performScrollTo()
            .performClick()
        assertEquals(true, downloaded)
    }

    @Test
    fun disabledDownloadIsNotEnabled() {
        content(downloadEnabled = false)
        compose.onNodeWithContentDescription("Download · 720p · 1.3 GB")
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun targetsAre48dp() {
        content()
        compose.onNodeWithContentDescription("Save to My List")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Like this title")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Dislike this title")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Share")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Download · 720p · 1.3 GB")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun labelsHoldAt200PercentFontScale() {
        // The round-6 clip: at 1.3x "Similar" silently dropped out of
        // the old row. At 2.0x every visible action is still on the
        // page, still labelled, still 48dp — the labels shrink instead
        // of truncating, and TalkBack still hears the full names.
        content(fontScale = 2f)
        compose.onNodeWithText("Save").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rate").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Share").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Download").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("More").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Save to My List")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Download · 720p · 1.3 GB")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun noHandleMeansNoRateGroup() {
        // A title the catalog never named with a handle has nothing a
        // vote could name the work by — the Rate group is gone, and the
        // row is Save · Share · Download + More.
        content(postDetail = moviePost, isSeries = false, similarTitles = emptyList())
        compose.onNodeWithText("Save").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rate").assertDoesNotExist()
        compose.onNodeWithText("Share").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Download").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("More").performScrollTo().assertIsDisplayed()
    }
}
