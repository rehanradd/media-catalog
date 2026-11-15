package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        onRunQuery: (String) -> Unit = {},
        onRemoveRecent: (String) -> Unit = {},
        onPostClick: (Post) -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                SearchDiscoveryContent(
                    state = SearchUiState(
                        recents = recents,
                        trending = trending,
                        recommended = recommended,
                    ),
                    onRunQuery = onRunQuery,
                    onRemoveRecent = onRemoveRecent,
                    onPostClick = onPostClick,
                )
            }
        }
    }

    @Test
    fun discoverySectionsInMockupOrder() {
        setDiscovery()

        compose.onNodeWithText("Trending now").assertIsDisplayed()
        compose.onNodeWithText("Recent searches").assertIsDisplayed()
        compose.onNodeWithText("Recommended for you").assertIsDisplayed()
        // The old discovery had neither of these — a regression to the
        // chip/genre layout would show them again.
        compose.onNodeWithText("Trending searches").assertDoesNotExist()
        compose.onNodeWithText("Browse by genre").assertDoesNotExist()

        val trendingTop = compose.onNodeWithText("Trending now").fetchSemanticsNode().boundsInRoot.top
        val recentsTop = compose.onNodeWithText("Recent searches").fetchSemanticsNode().boundsInRoot.top
        val recommendedTop = compose.onNodeWithText("Recommended for you").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Trending now must sit above Recent searches", trendingTop < recentsTop)
        assertTrue("Recent searches must sit above Recommended for you", recentsTop < recommendedTop)
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

        compose.onNodeWithText("Classic Cinema").performClick()
        assertEquals("Classic Cinema", clickedQuery)
    }

    @Test
    fun tappingRecentRowXRemovesIt() {
        var removed: String? = null
        setDiscovery(onRemoveRecent = { removed = it })

        compose.onNodeWithContentDescription("Remove Classic Cinema").performClick()
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
            compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals("First grid row must share a top", tops[0], tops[1])
        assertEquals("First grid row must share a top", tops[0], tops[2])
        assertTrue("Fourth recommendation must start a second row", tops[3] > tops[0])
    }

    @Test
    fun emptyDiscoveryShowsHintOnly() {
        setDiscovery(recents = emptyList(), trending = emptyList(), recommended = emptyList())

        compose.onNodeWithText("Search your catalog").assertIsDisplayed()
        compose.onNodeWithText("Trending now").assertDoesNotExist()
        compose.onNodeWithText("Recent searches").assertDoesNotExist()
        compose.onNodeWithText("Recommended for you").assertDoesNotExist()
    }
}
