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
import com.raddstream.app.network.models.CoverageType
import com.raddstream.app.network.models.LinkState
import com.raddstream.app.network.models.Playability
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.network.models.ReleaseDetail
import com.raddstream.app.network.models.ReleaseLabelParsing
import com.raddstream.app.player.LadderForScope
import com.raddstream.app.player.QualityOption
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.EpisodeGroup
import com.raddstream.app.viewmodel.RankedLink
import com.raddstream.app.viewmodel.RankedPrint
import com.raddstream.app.viewmodel.SeasonGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Detail redesign (Nov 2026): the synopsis moves directly under the Play
 * actions with the quality ladder below it, the version list and its
 * summary are gone, the similar rail is "More Like This", a series shows
 * one simple row per season ("Season 1 — 12 episodes ›") that opens the
 * season's own numbered-tile sub-page, and a title with nothing to play
 * gets its reason in plain words plus a way out. The overflow menu itself
 * (Report moved out of the top bar) is covered by the Paparazzi
 * detailMovie golden, not here — the top bar lives in DetailScreen,
 * above the DetailContent this file can render.
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

    private fun fakePrint(
        releaseId: Int,
        quality: String,
        playability: Playability = Playability.STREAM,
    ) = RankedPrint(
        releaseId = releaseId,
        rawTitle = "$quality copy",
        descriptor = ReleaseLabelParsing.describe(
            releaseTitle = "Jawan (2023) Hindi $quality WEB-DL",
            quality = quality,
        ),
        languages = listOf("Hindi"),
        sizeBytes = 1_400_000_000L,
        coverage = CoverageType.WHOLE_WORK,
        season = null,
        episodeFrom = null,
        episodeTo = null,
        links = listOf(
            RankedLink(
                streamId = "h-$releaseId",
                linkId = releaseId,
                health = LinkState.ACTIVE,
                ephemeral = false,
                resumable = true,
                playUrl = "http://example.invalid/play/$releaseId",
                label = "Fast direct",
                kind = "direct",
                playability = playability,
                score = 100,
            ),
        ),
    )

    private fun content(
        isSeries: Boolean = false,
        seasons: List<SeasonGroup> = emptyList(),
        similarTitles: List<Post> = emptyList(),
        deadEndReason: String? = null,
        postDetail: PostDetail = post,
        workPrints: List<RankedPrint> = emptyList(),
        ladder: LadderForScope? = null,
        similarLoading: Boolean = false,
        deadEndKeepPrint: RankedPrint? = null,
        onKeep: (RankedPrint) -> Unit = {},
        onOpenSeason: (Int) -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                DetailContent(
                    post = postDetail,
                    seasons = seasons,
                    workPrints = workPrints,
                    capabilities = emptyList(),
                    isSeries = isSeries,
                    resume = null,
                    onPlayResume = {},
                    includedPill = null,
                    deadEndReason = deadEndReason,
                    deadEndKeepPrint = deadEndKeepPrint,
                    onOpenSeason = onOpenSeason,
                    onPlayPrint = {},
                    onOpenExternal = {},
                    onKeep = onKeep,
                    rungs = RungActions(
                        ladder = ladder ?: LadderForScope(
                            options = listOf(option("720p", "HD - X264")),
                            autoBand = "720p",
                            downloadOnlyBands = emptyList(),
                        ),
                        selectedBand = null,
                        onPlay = {},
                        onKeep = {},
                        tierLabel = { "Standard" },
                    ),
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
    fun synopsisSitsAboveTheQualityLadder() {
        content()
        compose.onNodeWithText("Quality").performScrollTo()
        val synopsisTop = compose.onNodeWithText(synopsis)
            .fetchSemanticsNode().boundsInRoot.top
        val qualityTop = compose.onNodeWithText("Quality")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(synopsisTop < qualityTop)
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
    fun truncatedDetailNamesTheTrueTotal() {
        // Detail review (Nov 2026, P1): the server caps the detail at
        // 1,000 releases — the page says so in plain words before the
        // viewer assumes the list is complete.
        val truncated = post.copy(
            releases = listOf(ReleaseDetail(id = 1), ReleaseDetail(id = 2)),
            releaseTotal = 8316,
            releasesTruncated = true,
        )
        content(postDetail = truncated)
        compose.onNodeWithText("Showing the first 2 of 8,316 releases")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun truncatedFileExpanderShowsTheTrueTotal() {
        // Detail review (Nov 2026, P1): the file count reads the work's
        // true total, not the capped list's length.
        val truncated = post.copy(
            releases = listOf(ReleaseDetail(id = 1), ReleaseDetail(id = 2)),
            releaseTotal = 8316,
            releasesTruncated = true,
        )
        content(
            postDetail = truncated,
            workPrints = listOf(
                fakePrint(101, "2160p"),
                fakePrint(102, "1080p"),
                fakePrint(103, "720p"),
                fakePrint(104, "480p"),
            ),
            ladder = LadderForScope.EMPTY,
        )
        // The expander is the last item of the file list, below three
        // file cards — it is not composed until the list scrolls near
        // it. Swipe until it appears, then bring it fully into view.
        for (i in 0 until 5) {
            if (compose.onAllNodesWithText("Show all 8,316 files").fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeUp() }
        }
        compose.onNodeWithText("Show all 8,316 files")
            .performScrollTo().assertIsDisplayed()
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
    fun deadEndDownloadTargetsTheBestKeepablePrint() {
        // Detail review (Nov 2026, P1): the dead end's Download targets
        // the 720p copy this viewer may keep, not the best-ranked 4K
        // copy — and it stays enabled while any copy is keepable.
        var kept: RankedPrint? = null
        val prints = listOf(
            fakePrint(1, "2160p", Playability.DOWNLOAD),
            fakePrint(2, "720p", Playability.DOWNLOAD),
        )
        content(
            deadEndReason = "No working sources right now — download is available.",
            workPrints = prints,
            ladder = LadderForScope.EMPTY,
            deadEndKeepPrint = prints[1],
            onKeep = { kept = it },
        )
        compose.onNodeWithText("Download").performScrollTo().assertIsEnabled().performClick()
        assertEquals(2, kept?.releaseId)
    }

    @Test
    fun deadEndDownloadDisablesWhenNothingIsKeepable() {
        // Detail review (Nov 2026, P1): a title with only a linkless
        // print has no Download target — the button disables instead of
        // offering a download the tap would refuse.
        val noLinkPrint = fakePrint(1, "720p", Playability.DOWNLOAD).copy(links = emptyList())
        content(
            deadEndReason = "No working sources right now.",
            workPrints = listOf(noLinkPrint),
            ladder = LadderForScope.EMPTY,
        )
        compose.onNodeWithText("Download").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun deadEndShowsReasonWithoutStandaloneLink() {
        content(
            deadEndReason = "No sources for this title yet.",
            similarTitles = listOf(Post(id = 2, title = "Jawan 2")),
        )
        compose.onNodeWithText("No sources for this title yet.").performScrollTo().assertIsDisplayed()
        // Detail review (Nov 2026, P1): the standalone "Browse similar
        // titles" link is gone — the way out moved into the utility row
        // under the synopsis as the Similar action.
        compose.onNodeWithText("Browse similar titles").assertDoesNotExist()
        compose.onNodeWithText("Play").assertDoesNotExist()
        // Detail review (Nov 2026, P1): the Similar action lives in the
        // utility row now. Its scroll-to-rail is the same bringIntoView
        // the link used — and it cannot be proven from the top of the
        // page here: the rail is the last LazyColumn item, uncomposed
        // until the list scrolls near it, so the requester has no node
        // to attach to yet (the same shape as
        // similarRailIsCalledMoreLikeThis). Making that scroll work
        // from anywhere needs a list-state scroll by index — flagged
        // as a follow-up, not this slice.
        compose.onNodeWithText("Similar").assertIsDisplayed()
    }

}
