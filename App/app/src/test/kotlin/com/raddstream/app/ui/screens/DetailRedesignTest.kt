package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.network.models.ReleaseDetail
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.EpisodeGroup
import com.raddstream.app.viewmodel.SeasonGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Detail redesign (Nov 2026): the synopsis moves directly under the Play
 * actions, the version list and its summary are gone, the similar rail is
 * "More Like This", a series shows one simple row per season ("Season 1
 * — 12 episodes ›") that opens the season's own numbered-tile sub-page,
 * and a title with nothing to play gets its reason in plain words plus a
 * way out. 2-button detail (Nov 2026): the page shows exactly two
 * buttons — Play, then Download — and no quality ladder, no file cards,
 * no "Show all files" expander. The overflow menu itself (Report moved
 * out of the top bar) is covered by the Paparazzi detailMovie golden,
 * not here — the top bar lives in DetailScreen, above the DetailContent
 * this file can render.
 */
@RunWith(RobolectricTestRunner::class)
class DetailRedesignTest {

    @get:Rule
    val compose = createComposeRule()

    private val synopsis = "A man is driven by a personal vendetta."

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
        synopsis = synopsis,
        castNames = "Shah Rukh Khan, Nayanthara",
        industry = "Bollywood",
        language = "Hindi",
    )

    private fun content(
        isSeries: Boolean = false,
        seasons: List<SeasonGroup> = emptyList(),
        similarTitles: List<Post> = emptyList(),
        deadEndReason: String? = null,
        postDetail: PostDetail = post,
        similarLoading: Boolean = false,
        onOpenSeason: (Int) -> Unit = {},
        // 2-button detail (Nov 2026): the two buttons' state, as the
        // ViewModel would compute it — the label names the quality, the
        // enabled flag says whether the tap can start.
        playLabel: String = "Play",
        playEnabled: Boolean = true,
        onPlayPrimary: () -> Unit = {},
        downloadLabel: String = "Download",
        downloadEnabled: Boolean = true,
        onDownloadPrimary: () -> Unit = {},
        playHint: String? = null,
        downloadHint: String? = null,
        onTryAgain: () -> Unit = {},
        onShowAllVersions: () -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                DetailContent(
                    post = postDetail,
                    seasons = seasons,
                    workPrints = emptyList(),
                    capabilities = emptyList(),
                    isSeries = isSeries,
                    resume = null,
                    includedPill = null,
                    deadEndReason = deadEndReason,
                    playLabel = playLabel,
                    playEnabled = playEnabled,
                    onPlayPrimary = onPlayPrimary,
                    downloadLabel = downloadLabel,
                    downloadEnabled = downloadEnabled,
                    onDownloadPrimary = onDownloadPrimary,
                    playHint = playHint,
                    downloadHint = downloadHint,
                    onTryAgain = onTryAgain,
                    onShowAllVersions = onShowAllVersions,
                    onOpenSeason = onOpenSeason,
                    onActorClick = {},
                    similarTitles = similarTitles,
                    similarLoading = similarLoading,
                    onPostClick = {},
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
    fun playButtonsSitAboveTheSynopsis() {
        // 2-button detail (Nov 2026): the two buttons come first — Play,
        // then Download — and the synopsis sits directly under them. The
        // quality ladder the old test pinned here is gone with its cards.
        content()
        compose.onNodeWithText("Play").performScrollTo()
        val playTop = compose.onNodeWithText("Play")
            .fetchSemanticsNode().boundsInRoot.top
        val synopsisTop = compose.onNodeWithText(synopsis)
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(playTop < synopsisTop)
    }

    @Test
    fun similarRailIsCalledMoreLikeThis() {
        content(similarTitles = listOf(Post(id = 2, title = "Jawan 2")))
        // The rail is the last item in a LazyColumn, so it is not even
        // composed until the list scrolls near it — a scroll-to on a
        // node that does not exist yet cannot find it. Swipe until it
        // appears, then bring it fully into view.
        for (i in 0 until 5) {
            if (compose.onAllNodesWithText("More Like This").fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeUp() }
        }
        compose.onNodeWithText("More Like This").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Similar titles").assertDoesNotExist()
    }

    @Test
    fun seasonsAreSimpleRowsThatOpenTheirSubPage() {
        // Detail redesign (Nov 2026): a series shows one simple row per
        // season — "Season 1 — 1 episode ›" — and tapping a row opens
        // that season's sub-page. No chips, no dropdown, no episode list
        // on this page: six seasons read as six rows.
        val seasons = (1..6).map { number ->
            SeasonGroup(
                number = number,
                episodes = listOf(
                    EpisodeGroup(
                        season = number,
                        numberWithinSeason = 1,
                        absoluteNumber = number,
                        title = "Pilot",
                        prints = emptyList(),
                    ),
                ),
                packedPrints = emptyList(),
            )
        }
        var opened: Int? = null
        content(isSeries = true, seasons = seasons, onOpenSeason = { opened = it })
        // The rows sit below the fold in the test window, so like the
        // similar rail they may not even be composed yet — swipe until
        // they appear, then bring them fully into view.
        for (i in 0 until 5) {
            if (compose.onAllNodesWithText("Season 6").fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeUp() }
        }
        compose.onNodeWithText("Season 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Season 6").performScrollTo().assertIsDisplayed()
        // The old dropdown's combined label is gone with it.
        compose.onNodeWithText("Season 1 · 1 episode").assertDoesNotExist()
        compose.onNodeWithText("Season 2").performScrollTo().performClick()
        assertEquals(2, opened)
    }

    @Test
    fun genreChipsFadeIsTwentyTwoDp() {
        // Detail review (Nov 2026, P1): the genre row shares the
        // ScrollingChipRow fade at the chips' own height — 22dp for a Pill, not
        // the shared 48dp, and not the invisible local fade it replaces.
        content()
        compose.onNodeWithTag(GenreChipsFadeTag).assertHeightIsEqualTo(22.dp)
    }

    @Test
    fun truncatedDetailOffersShowAllVersions() {
        // P0-9 (Nov 2026): the server caps the detail at 1,000
        // releases — but the cap is the app's problem, not the
        // viewer's, so the engineer-facing note ("Showing the first 2
        // of 8,316 releases") is gone. Progressive disclosure instead:
        // a truncated detail offers "Show all versions (1,000+)".
        // The two stacked buttons are taller than the old side-by-side
        // row, so the affordance sits below the first viewport in this
        // test — swipe until it composes, the same pattern the
        // similar-rail test uses.
        val truncated = post.copy(
            releases = listOf(ReleaseDetail(id = 1), ReleaseDetail(id = 2)),
            releaseTotal = 8316,
            releasesTruncated = true,
        )
        var shown = false
        content(postDetail = truncated, onShowAllVersions = { shown = true })
        for (i in 0 until 5) {
            if (compose.onAllNodesWithText("Show all versions (1,000+)").fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeUp() }
        }
        compose.onNodeWithText("Show all versions (1,000+)")
            .performScrollTo().assertIsDisplayed().performClick()
        assertTrue(shown)
        compose.onNodeWithText("Showing the first 2 of 8,316 releases").assertDoesNotExist()
    }

    @Test
    fun similarSkeletonStandsInWhileTheRailLoads() {
        // Detail review (Nov 2026, P1): the similar rail loads after the
        // page — while it does, a rail skeleton stands in its place, so
        // the page does not end in a bare gap that reads as "no similar
        // titles".
        content(similarLoading = true)
        // The skeleton is the last item in a LazyColumn, so it is not
        // even composed until the list scrolls near it — a scroll-to on
        // a node that does not exist yet cannot find it. Swipe until it
        // appears, then bring it fully into view.
        for (i in 0 until 5) {
            if (compose.onAllNodesWithTag("posterRailSkeleton").fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeUp() }
        }
        compose.onNodeWithTag("posterRailSkeleton").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("More Like This").assertIsDisplayed()
    }

    @Test
    fun deadEndDownloadStartsTheDownload() {
        // 2-button detail (Nov 2026): the dead end's Download stays
        // enabled while any copy is keepable, and the tap starts the
        // download — which copy, and at which quality, is the
        // ViewModel's decision, not this composable's, so the tap is
        // what this test pins.
        var started = false
        content(
            deadEndReason = "No working sources right now — download is available.",
            onDownloadPrimary = { started = true },
        )
        compose.onNodeWithText("Download").performScrollTo().assertIsEnabled().performClick()
        assertTrue(started)
    }

    @Test
    fun deadEndDownloadDisablesWhenNothingIsKeepable() {
        // 2-button detail (Nov 2026): a title with nothing to save
        // shows a greyed Download — the tap is refused by the button,
        // not by a dialog after it.
        content(
            deadEndReason = "No working sources right now.",
            downloadEnabled = false,
            downloadHint = "Nothing to save for this title.",
        )
        compose.onNodeWithText("Download").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Nothing to save for this title.").assertIsDisplayed()
    }

    @Test
    fun deadEndShowsReasonWithoutStandaloneLink() {
        // 2-button detail (Nov 2026): the dead-end reason reaches the
        // page as the Play button's hint — the ViewModel puts it there
        // when Play is disabled — so the test models the ViewModel and
        // passes the reason as playHint, not as a standalone text.
        content(
            deadEndReason = "No sources for this title yet.",
            similarTitles = listOf(Post(id = 2, title = "Jawan 2")),
            playEnabled = false,
            playHint = "No sources for this title yet.",
        )
        compose.onNodeWithText("No sources for this title yet.").performScrollTo().assertIsDisplayed()
        // Detail review (Nov 2026, P1): the standalone "Browse similar
        // titles" link is gone — the way out moved into the utility row
        // under the synopsis as the Similar action.
        compose.onNodeWithText("Browse similar titles").assertDoesNotExist()
        // 2-button detail (Nov 2026): the dead end still shows the Play
        // button — greyed, with its reason — plus a [Try again] that
        // reloads the title. It is never hidden. The fallback hint ("No
        // working link right now…") is NOT shown here: the ViewModel
        // puts the reason itself in the hint when it has one.
        compose.onNodeWithText("Play").assertIsNotEnabled()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        // Detail review (Nov 2026, P1): the Similar action lives in the
        // utility row now. Its scroll-to-rail is the same bringIntoView
        // the link used — and it cannot be proven from the top of the
        // page here: the rail is the last LazyColumn item, uncomposed
        // until the list scrolls near it, so the requester has no node
        // to attach to yet (the same shape as
        // similarRailIsCalledMoreLikeThis). Making that scroll work
        // from anywhere needs a list-state scroll by index — flagged
        // as a follow-up, not this slice. The row itself sits below
        // the taller stacked buttons, outside the first composition
        // — swipe until it composes, then bring it into view.
        for (i in 0 until 5) {
            if (compose.onAllNodesWithText("Similar").fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeUp() }
        }
        compose.onNodeWithText("Similar").performScrollTo().assertIsDisplayed()
    }

}
