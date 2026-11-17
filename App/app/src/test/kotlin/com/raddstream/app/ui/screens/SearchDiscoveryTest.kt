package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.SearchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mockup (Nov 2026): the discovery page is three sections, top to bottom —
 * "Trending now" rail, "Recent searches" list, "Recommended for you"
 * 3-column grid. The old "Trending searches" chips and "Browse by genre"
 * are gone, and recents are rows with a clock icon and a ×, not chips.
 *
 * Runs on the JVM under Robolectric because this project has no emulator
 * image. Requires an x86_64 runner (robolectric/robolectric#9166).
 */
@RunWith(RobolectricTestRunner::class)
class SearchDiscoveryTest {

    @get:Rule
    val compose = createComposeRule()

    private fun samplePost(id: Int, title: String) = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = 2024,
        contentType = "movie",
        posterUrl = null,
    )

    private fun setDiscovery(
        recents: List<String> = listOf("Classic Cinema"),
        trending: List<Post> = listOf(samplePost(1, "Dune"), samplePost(2, "Inception")),
        recommended: List<Post> = listOf(samplePost(3, "Oppenheimer")),
        // Search review (Nov 2026), fix 4: the settled state is the
        // default here, so the existing tests keep pinning the settled
        // page; the loading tests pass true.
        discoveryLoading: Boolean = false,
        onRunQuery: (String) -> Unit = {},
        onRemoveRecent: (String) -> Unit = {},
        onPostClick: (Post) -> Unit = {},
        onSeeAllTrending: () -> Unit = {},
        onSeeAllRecommended: () -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                SearchDiscoveryContent(
                    state = SearchUiState(
                        recents = recents,
                        trending = trending,
                        recommended = recommended,
                        discoveryLoading = discoveryLoading,
                    ),
                    onRunQuery = onRunQuery,
                    onRemoveRecent = onRemoveRecent,
                    onPostClick = onPostClick,
                    onSeeAllTrending = onSeeAllTrending,
                    onSeeAllRecommended = onSeeAllRecommended,
                )
            }
        }
    }

    @Test
    fun discoverySectionsInMockupOrder() {
        setDiscovery()

        compose.onNodeWithText("Trending now").assertIsDisplayed()
        compose.onNodeWithText("Recent searches").assertIsDisplayed()
        // The old discovery had neither of these — a regression to the
        // chip/genre layout would show them again.
        compose.onNodeWithText("Trending searches").assertDoesNotExist()
        compose.onNodeWithText("Browse by genre").assertDoesNotExist()

        // Order first, at rest: scrolling moves every bound, so the tops
        // are only comparable before anything scrolls. positionInRoot, not
        // boundsInRoot: boundsInRoot CLIPS to the visible window, so a
        // below-fold header reports the window's bottom edge instead of
        // its layout position (CI 43740958870: recentsTop < recommendedTop
        // failed on the clip, not on the layout). positionInRoot is the
        // unclipped layout position, so the order holds at any scroll.
        val trendingTop = compose.onNodeWithText("Trending now").fetchSemanticsNode().positionInRoot.y
        val recentsTop = compose.onNodeWithText("Recent searches").fetchSemanticsNode().positionInRoot.y
        val recommendedTop = compose.onNodeWithText("Recommended for you").fetchSemanticsNode().positionInRoot.y
        assertTrue("Trending now must sit above Recent searches", trendingTop < recentsTop)
        assertTrue("Recent searches must sit above Recommended for you", recentsTop < recommendedTop)

        // "Recommended for you" sits below the fold in the short
        // Robolectric window — scroll it into view before asserting it is
        // displayed, the way HomeHeaderTest scrolls to its "Show all".
        compose.onNodeWithText("Recommended for you").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingTrendingPosterCallsOnPostClick() {
        var clicked: Post? = null
        setDiscovery(onPostClick = { clicked = it })

        compose.onAllNodesWithText("Dune")[0].performClick()
        assertEquals(1, clicked?.id)
    }

    @Test
    fun tappingRecentRowRunsIt() {
        var clickedQuery: String? = null
        setDiscovery(onRunQuery = { clickedQuery = it })

        // The row sits below the fold in the short Robolectric window, and
        // on Android performClick is a real tap at the node's centre — a
        // tap below the fold lands on nothing and fails silently. Scroll
        // first, then tap the row's own tag (the clickable owner, not its
        // child text).
        compose.onNodeWithTag("${RecentRowTagPrefix}Classic Cinema")
            .performScrollTo().performClick()
        assertEquals("Classic Cinema", clickedQuery)
    }

    @Test
    fun tappingRecentRowXRemovesIt() {
        var removed: String? = null
        setDiscovery(onRemoveRecent = { removed = it })

        // Same fold rule as the row tap: scroll first, then tap the ×
        // button's own tag.
        compose.onNodeWithTag("${RemoveRecentTagPrefix}Classic Cinema")
            .performScrollTo().performClick()
        assertEquals("Classic Cinema", removed)
    }

    @Test
    fun recommendedGridIsThreeColumns() {
        // Four recommendations: the first three share a row (same top),
        // the fourth starts the next row below them.
        setDiscovery(
            trending = emptyList(),
            recents = emptyList(),
            recommended = listOf(
                samplePost(10, "Alpha"),
                samplePost(11, "Beta"),
                samplePost(12, "Gamma"),
                samplePost(13, "Delta"),
            ),
        )
        val tops = listOf("Alpha", "Beta", "Gamma", "Delta").map {
            // positionInRoot, not boundsInRoot: the latter clips to the
            // visible window, so a below-fold row reports the window's edge
            // instead of its layout position (see the order test above).
            compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y
        }
        assertEquals("First grid row must share a top", tops[0], tops[1])
        assertEquals("First grid row must share a top", tops[0], tops[2])
        assertTrue("Fourth recommendation must start a second row", tops[3] > tops[0])
    }

    @Test
    fun emptyDiscoveryShowsHintOnly() {
        // The settled empty state: both discovery loads answered and the
        // catalog has nothing to show, so the hint — and only the hint —
        // is the page.
        setDiscovery(recents = emptyList(), trending = emptyList(), recommended = emptyList())

        compose.onNodeWithText("Search your catalog").assertIsDisplayed()
        compose.onNodeWithText("Trending now").assertDoesNotExist()
        compose.onNodeWithText("Recent searches").assertDoesNotExist()
        compose.onNodeWithText("Recommended for you").assertDoesNotExist()
        compose.onNodeWithTag(SearchDiscoverySkeletonTag).assertDoesNotExist()
    }

    @Test
    fun loadingDiscoveryShowsSkeletonNotHint() {
        // Search review (Nov 2026), fix 4: while the first trending +
        // recommended loads are still in flight and no section has data,
        // the page shows section skeletons — the hint waits for the
        // settle, so it never reads as "the catalog is empty" on a slow
        // first load.
        setDiscovery(
            recents = emptyList(),
            trending = emptyList(),
            recommended = emptyList(),
            discoveryLoading = true,
        )

        compose.onNodeWithTag(SearchDiscoverySkeletonTag).assertIsDisplayed()
        compose.onNodeWithText("Search your catalog").assertDoesNotExist()
        compose.onNodeWithText("Trending now").assertDoesNotExist()
        compose.onNodeWithText("Recent searches").assertDoesNotExist()
        compose.onNodeWithText("Recommended for you").assertDoesNotExist()
    }

    @Test
    fun loadingDiscoveryWithDataShowsSectionsNotSkeleton() {
        // A section that already has data (recents are local and land
        // first) shows its data while the rest still loads — the skeleton
        // is only the all-empty loading state, never a cover over data.
        setDiscovery(discoveryLoading = true)

        compose.onNodeWithText("Trending now").assertIsDisplayed()
        compose.onNodeWithTag(SearchDiscoverySkeletonTag).assertDoesNotExist()
    }

    @Test
    fun seeAllButtonsPresentOnBothSections() {
        setDiscovery()

        // The two "See all" buttons share their text, so the test taps
        // the tags, not the text — the text alone is ambiguous.
        compose.onNodeWithTag(SeeAllTrendingTag).assertIsDisplayed()
        // "Recommended for you" sits below the fold in the short
        // Robolectric window — scroll first, the way the order test does.
        compose.onNodeWithTag(SeeAllRecommendedTag).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingTrendingSeeAllCallsCallback() {
        var called = false
        setDiscovery(onSeeAllTrending = { called = true })

        compose.onNodeWithTag(SeeAllTrendingTag).performClick()
        assertTrue(called)
    }

    @Test
    fun tappingRecommendedSeeAllCallsCallback() {
        var called = false
        setDiscovery(onSeeAllRecommended = { called = true })

        compose.onNodeWithTag(SeeAllRecommendedTag).performScrollTo().performClick()
        assertTrue(called)
    }

    @Test
    fun seeAllHiddenWhenSectionsEmpty() {
        setDiscovery(recents = emptyList(), trending = emptyList(), recommended = emptyList())

        compose.onNodeWithTag(SeeAllTrendingTag).assertDoesNotExist()
        compose.onNodeWithTag(SeeAllRecommendedTag).assertDoesNotExist()
    }

    @Test
    fun browseTypeForSeeAllMapsRailTypes() {
        // The rail's types are the Browse route's — except "kpop", which
        // the server has no content_type for, so it expands to the whole
        // catalog instead of an empty one.
        assertEquals("movie", browseTypeForSeeAll("movie"))
        assertEquals("series", browseTypeForSeeAll("series"))
        assertEquals("anime", browseTypeForSeeAll("anime"))
        assertEquals(null, browseTypeForSeeAll("kpop"))
        assertEquals(null, browseTypeForSeeAll(null))
        assertEquals(null, browseTypeForSeeAll("complete_season"))
    }
}
