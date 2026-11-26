package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
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
 * "More Like This", and a title with nothing to play gets its reason in
 * plain words plus a way out. P1-8 (Nov 2026): a series shows one
 * season dropdown + an inline episode list — no season rows, no
 * sub-page — and the genres live in a Details block, not the hero.
 * 2-button detail (Nov 2026): the page shows exactly two
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
        onPlayEpisode: (Int, Int) -> Unit = { _, _ -> },
        onDownloadEpisode: (Int, Int) -> Unit = { _, _ -> },
        // P1-8 fixup 2: the dropdown is stateless — the selection
        // lives in the ViewModel (DetailContent's episodeRowSeason).
        // The helper takes the selection as STATE and reads it inside
        // setContent: passing a plain Int? evaluates it once at the
        // call, so a later menu pick never recomposes the list (the
        // fixup-2 shape — Android 45810913696 failed the same way).
        episodeRowSeason: State<Int?>? = null,
        onSelectEpisodeRowSeason: (Int) -> Unit = {},
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
                    onPlayEpisode = onPlayEpisode,
                    onDownloadEpisode = onDownloadEpisode,
                    episodeRowSeason = episodeRowSeason?.value,
                    onSelectEpisodeRowSeason = onSelectEpisodeRowSeason,
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
    fun seasonsAreOneDropdownPlusInlineEpisodeList() {
        // P1-8 (Nov 2026): one path to the episodes — a season
        // dropdown + an inline episode list on this page. No season
        // rows, no sub-page: six seasons read as one dropdown, and
        // picking one lists its episodes below it.
        val seasons = (1..6).map { number ->
            SeasonGroup(
                number = number,
                episodes = listOf(
                    EpisodeGroup(
                        season = number,
                        numberWithinSeason = 1,
                        absoluteNumber = number,
                        title = if (number == 2) "Second" else "Pilot",
                        prints = emptyList(),
                    ),
                ),
                packedPrints = emptyList(),
            )
        }
        var opened: Int? = null
        var played: Pair<Int, Int>? = null
        // P1-8 fixup 2: the dropdown only paints the selection — the
        // test holds it in state, the way the ViewModel does in
        // production, and the helper reads that state inside
        // setContent, so the menu pick recomposes the list.
        val selectedSeason = mutableStateOf<Int?>(null)
        content(
            isSeries = true,
            seasons = seasons,
            episodeRowSeason = selectedSeason,
            onSelectEpisodeRowSeason = { selectedSeason.value = it },
            onOpenSeason = { opened = it },
            onPlayEpisode = { season, episode -> played = season to episode },
        )
        // The dropdown sits below the fold in the test window, so like
        // the similar rail it may not even be composed yet — swipe until
        // it appears, then bring it fully into view.
        for (i in 0 until 5) {
            if (compose.onAllNodesWithText("Season 1").fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeUp() }
        }
        // The first season's episode is listed inline, under the dropdown.
        compose.onNodeWithText("Season 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pilot").performScrollTo().assertIsDisplayed()
        // Picking Season 2 in the dropdown swaps the list to its episode.
        compose.onNodeWithText("Season 1").performScrollTo().performClick()
        // The menu item is already visible in its Popup — no scroll-to.
        compose.onNodeWithText("Season 2 · 1 episode").performClick()
        compose.onNodeWithText("Season 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Second").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pilot").assertDoesNotExist()
        // Nothing here opens the season sub-page any more.
        assertEquals(null, opened)
        // Tapping an episode row plays it.
        compose.onNodeWithText("Second").performScrollTo().performClick()
        assertEquals(2 to 1, played)
    }

    @Test
    fun genresLiveInTheDetailsBlockNotTheHero() {
        // P1-8 (Nov 2026): the genre chips left the hero — chips on
        // a backdrop read as a filter affordance. They live in the
        // Details block now, as wrapping chips under a "Details"
        // header, and the hero carries no chip fade any more.
        content()
        // P1-8 fixup 4: the swipe loop never reliably composes the
        // Details item — the block sits below the fold and five swipes
        // do not deterministically bring it into the LazyColumn's
        // composition range, so performScrollTo cannot find it at all
        // (Android 45814176899 attempt 2: third failure on this test).
        // Scroll by index instead — the repo's own deterministic
        // pattern (PickThreeVisitTest: "performScrollToIndex is
        // deterministic"). This fixture's page is hero(0), actions(1),
        // quality selector(2, empty), synopsis(3), utility(4),
        // cast(5), details(6).
        // P1-8 resume: scroll the LIST, not the root — onRoot() is a
        // 320×470 wrapper with 1 child, not the LazyColumn, so the
        // index scroll could never find the item (Android 45810913696,
        // 45814176899, 45822497145: "Failed to scroll to index 6, the
        // node is missing"). The tag is DetailContentListTag.
        compose.onNodeWithTag(DetailContentListTag).performScrollToIndex(6)
        // The rows still ride DetailEntrance (index 7) — settle it
        // before asserting the chips; the header sits outside the
        // entrance since fixup 3, so it is placed as soon as the item
        // composes.
        compose.mainClock.advanceTimeBy(2000L)
        compose.onNodeWithText("Details").assertIsDisplayed()
        compose.onNodeWithText("Action").assertIsDisplayed()
        compose.onNodeWithText("Thriller").assertIsDisplayed()
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
        //
        // P2-iconfirst (Nov 2026): "appears" must mean it has SIZE, not
        // merely that the tag exists. The Row carries its testTag from
        // the first frame, but its ShimmerBox children paint nothing
        // until the 200 ms delay gate opens (CommonUi ShimmerBox) — a
        // gated Row is zero-height, so it exists without being
        // displayed, and performScrollTo + assertIsDisplayed fails on
        // it. That is exactly what Android 45860497163 hit (1/3253):
        // the loop broke on existence before the gate opened. Swipe
        // until the Row has height (gate open), then scroll it into
        // view; the swipes' own idle-waits advance the gate.
        for (i in 0 until 10) {
            val nodes = compose.onAllNodesWithTag("posterRailSkeleton").fetchSemanticsNodes()
            if (nodes.isNotEmpty() && nodes[0].boundsInRoot.height > 0f) break
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
        // P1-7 (Nov 2026): two "Download" actions on the page now — the
        // primary button above and the utility row's mirror below, which
        // is the same tap. The query scopes to the pill by tag.
        compose.onNodeWithTag(PrimaryDownloadPillTag).performScrollTo().assertIsEnabled().performClick()
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
        // P1-7 (Nov 2026): two "Download" actions on the page now — the
        // primary button above and the utility row's mirror below, which
        // mirrors the same disabled state. The query scopes to the pill
        // by tag.
        compose.onNodeWithTag(PrimaryDownloadPillTag).performScrollTo().assertIsNotEnabled()
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
        // P1-7 (Nov 2026): the Similar action lives in the utility
        // row's More overflow now. Its scroll-to-rail is the same
        // bringIntoView the link used — and it cannot be proven from
        // the top of the page here: the rail is the last LazyColumn
        // item, uncomposed until the list scrolls near it, so the
        // requester has no node to attach to yet (the same shape as
        // similarRailIsCalledMoreLikeThis). Making that scroll work
        // from anywhere needs a list-state scroll by index — flagged
        // as a follow-up, not this slice. The row itself sits below
        // the taller stacked buttons, outside the first composition
        // — swipe until it composes, open the overflow, then the
        // Similar item is on the page.
        for (i in 0 until 5) {
            // P2-iconfirst (Nov 2026): the row is icon-only — the "More"
            // label is gone, so the row is found by its spoken name.
            if (compose.onAllNodesWithContentDescription("More actions").fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeUp() }
        }
        compose.onNodeWithContentDescription("More actions").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Similar").assertIsDisplayed()
    }

}
