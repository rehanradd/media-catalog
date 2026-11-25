package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.LanguageFilter
import com.raddstream.app.viewmodel.SearchFilters
import com.raddstream.app.viewmodel.SearchUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * APP-07 (Phase 4): a completed search that finds nothing is a way forward,
 * not a dead end. These tests pin the no-results state: a spelling tip and a
 * fresh start, the server's did-you-mean as a real action, the active
 * filters named with a Clear action, and the catalog itself — the "Trending
 * now" rail, or a "Browse trending" button when the discovery load came
 * back empty.
 *
 * Runs on the JVM under Robolectric because this project has no emulator
 * image. Requires an x86_64 runner (robolectric/robolectric#9166).
 */
@RunWith(RobolectricTestRunner::class)
class SearchNoResultsTest {

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

    private val idle = LoadState.NotLoading(endOfPaginationReached = false)

    private fun setNoResults(
        state: SearchUiState,
        onQueryChanged: (String) -> Unit = {},
        onResetFilters: () -> Unit = {},
        onRunSuggestion: (String) -> Unit = {},
        onPostClick: (Post) -> Unit = {},
        onSeeAllTrending: () -> Unit = {},
    ) {
        val flow = MutableStateFlow(
            PagingData.empty<Post>(
                sourceLoadStates = LoadStates(refresh = idle, prepend = idle, append = idle),
            ),
        )
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 360.dp, height = 1000.dp)) {
                    val pagingItems = flow.collectAsLazyPagingItems()
                    SearchContent(
                        state = state,
                        pagingItems = pagingItems,
                        groups = emptyList(),
                        onRunQuery = {},
                        onRemoveRecent = {},
                        onResetFilters = onResetFilters,
                        onQueryChanged = onQueryChanged,
                        onShowCloseMatches = {},
                        onShowExactOnly = {},
                        onRunSuggestion = onRunSuggestion,
                        onPostClick = onPostClick,
                        onSeeAllTrending = onSeeAllTrending,
                    )
                }
            }
        }
    }

    @Test
    fun noResultsOffersSpellingTipAndFreshStart() {
        var clearedTo: String? = null
        var browseTaps = 0
        setNoResults(
            state = SearchUiState(query = "avngers", hasSearched = true),
            onQueryChanged = { clearedTo = it },
            onSeeAllTrending = { browseTaps++ },
        )

        compose.onNodeWithText("No matches").assertIsDisplayed()
        compose.onNodeWithText("No titles matched \"avngers\".").assertIsDisplayed()
        compose.onNodeWithText("Check the spelling, or try fewer words.").assertIsDisplayed()
        // No trending loaded: the rail is absent, but the Browse door stays.
        compose.onNodeWithText("Browse trending").assertIsDisplayed()
        compose.onNodeWithText("Search another title").assertIsDisplayed()

        compose.onNodeWithText("Search another title").performClick()
        assertEquals("", clearedTo)
        compose.onNodeWithText("Browse trending").performClick()
        assertEquals(1, browseTaps)
    }

    @Test
    fun filteredEmptyNamesTheFiltersAndOffersClear() {
        var resetTaps = 0
        setNoResults(
            state = SearchUiState(
                query = "jwan",
                hasSearched = true,
                filters = SearchFilters(type = "movie", language = LanguageFilter.HINDI),
            ),
            onResetFilters = { resetTaps++ },
        )

        // Audit Search #6: the message names the filters instead of saying
        // "with the current filters".
        compose.onNodeWithText("No titles matched \"jwan\" with Movies · Hindi filters.")
            .assertIsDisplayed()
        compose.onNodeWithText("Clear filters").assertIsDisplayed()
        // Both doors stay: clearing the filters is one tap, starting over is another.
        compose.onNodeWithText("Search another title").assertIsDisplayed()

        compose.onNodeWithText("Clear filters").performClick()
        assertEquals(1, resetTaps)
    }

    @Test
    fun didYouMeanRunsAsASearch() {
        var ran: String? = null
        setNoResults(
            state = SearchUiState(
                query = "avngers",
                hasSearched = true,
                didYouMean = "avengers",
            ),
            onRunSuggestion = { ran = it },
        )

        compose.onNodeWithText("Did you mean avengers?").assertIsDisplayed()
        compose.onNodeWithTag(NoResultsDidYouMeanTag).performClick()
        assertEquals("avengers", ran)
    }

    @Test
    fun trendingRailOpensTitlesAndSeeAllBrowses() {
        var clicked: Post? = null
        var browseTaps = 0
        setNoResults(
            state = SearchUiState(
                query = "avngers",
                hasSearched = true,
                trending = listOf(samplePost(1, "Dune"), samplePost(2, "Inception")),
            ),
            onPostClick = { clicked = it },
            onSeeAllTrending = { browseTaps++ },
        )

        compose.onNodeWithText("Trending now").assertIsDisplayed()
        // APP-07 (round 2) illustrated the empty state, so the rail sits
        // lower than it used to: the viewer scrolls to the titles, and so
        // does this test, before asserting the first title is visible.
        // useUnmergedTree: the rail card merges its descendants, so the
        // title text lives only in the unmerged tree.
        compose.onNodeWithText("Dune", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // The rail replaces the fallback button: one Browse door, not two.
        compose.onAllNodesWithText("Browse trending").assertCountEquals(0)

        compose.onAllNodesWithText("Dune", useUnmergedTree = true)[0].performScrollTo().performClick()
        assertEquals(1, clicked?.id)
        compose.onNodeWithTag(SeeAllTrendingTag).performClick()
        assertEquals(1, browseTaps)
    }
}
