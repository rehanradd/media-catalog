package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.SearchUiState
import com.raddstream.app.viewmodel.TypeGroup
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.compose.ui.test.junit4.createComposeRule

/**
 * Design direction REFINED (Nov 2026): search results are rich rows, not
 * poster cards — a YouTube-style row: a bigger thumb, the title, the rating
 * and more info. These tests pin the row itself (title + rating + meta
 * present, tap opens the title) and that [SearchContent] renders results
 * as rich rows (one [SearchResultRowTag] per result) instead of the old
 * 3-up poster-card grid.
 *
 * Runs on the JVM under Robolectric because this project has no emulator
 * image. Requires an x86_64 runner (robolectric/robolectric#9166).
 */
@RunWith(RobolectricTestRunner::class)
class SearchResultRowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun samplePost(
        id: Int,
        title: String,
        year: Int? = 2023,
        contentType: String = "movie",
        rating: String? = "7.0",
        genres: String? = "Action, Thriller",
        language: String? = "Hindi",
    ) = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = year,
        contentType = contentType,
        posterUrl = null,
        imdbRating = rating,
        genres = genres,
        language = language,
    )

    @Test
    fun `detail line joins genres and language`() {
        assertEquals(
            "Action, Thriller · Hindi",
            searchResultDetailLine(samplePost(1, "Jawan")),
        )
    }

    @Test
    fun `detail line drops to genres alone with no language`() {
        assertEquals(
            "Action, Thriller",
            searchResultDetailLine(samplePost(1, "Jawan", language = null)),
        )
    }

    @Test
    fun `detail line is blank with neither genres nor language`() {
        assertEquals(
            "",
            searchResultDetailLine(samplePost(1, "Jawan", genres = null, language = null)),
        )
    }

    @Test
    fun rowShowsTitleRatingAndMeta() {
        var clicked: Post? = null
        val post = samplePost(1, "Jawan")
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 360.dp, height = 400.dp)) {
                    SearchResultRow(post = post, onClick = { clicked = post })
                }
            }
        }
        compose.onNodeWithTag(SearchResultRowTag).assertIsDisplayed()
        compose.onNodeWithText("Jawan").assertIsDisplayed()
        // The full meta line, not the compact card line: year · ★ rating ·
        // type — the rating is the point of the row.
        compose.onNodeWithText("2023 · ★ 7.0 · Movie").assertIsDisplayed()
        compose.onNodeWithText("Action, Thriller · Hindi").assertIsDisplayed()
        compose.onNodeWithTag(SearchResultRowTag).performClick()
        assertEquals(post, clicked)
    }

    @Test
    fun rowWithoutRatingShowsNoStar() {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 360.dp, height = 400.dp)) {
                    SearchResultRow(post = samplePost(1, "Jawan", rating = null), onClick = {})
                }
            }
        }
        compose.onNodeWithText("Jawan").assertIsDisplayed()
        // Year and type stay; an unrated title never reads "★ 0.0".
        compose.onNodeWithText("2023 · Movie").assertIsDisplayed()
        compose.onNodeWithText("★ 0.0").assertDoesNotExist()
    }

    @Test
    fun searchContentRendersResultsAsRichRows() {
        val idle = LoadState.NotLoading(endOfPaginationReached = false)
        val posts = listOf(
            samplePost(1, "Jawan"),
            samplePost(2, "Salaar: Part 1 – Ceasefire", rating = "6.5", genres = "Action", language = "Telugu"),
            samplePost(5, "Money Heist", year = 2017, contentType = "series", rating = "8.2", genres = "Crime, Drama", language = "English"),
        )
        val flow = MutableStateFlow(
            PagingData.from(
                data = posts,
                sourceLoadStates = LoadStates(refresh = idle, prepend = idle, append = idle),
            ),
        )
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 360.dp, height = 1000.dp)) {
                    val pagingItems = flow.collectAsLazyPagingItems()
                    SearchContent(
                        state = SearchUiState(query = "jwan", serverTotal = 3, hasSearched = true),
                        pagingItems = pagingItems,
                        groups = listOf(
                            TypeGroup(label = "Movies", typeKey = "movie", items = posts.take(2)),
                            TypeGroup(label = "Series", typeKey = "series", items = posts.takeLast(1)),
                        ),
                        onRunQuery = {},
                        onRemoveRecent = {},
                        onResetFilters = {},
                        onQueryChanged = {},
                        onShowCloseMatches = {},
                        onShowExactOnly = {},
                        onRunSuggestion = {},
                        onPostClick = {},
                    )
                }
            }
        }
        // The first result is a rich row, not a poster card: the row tag is
        // on screen with its title and rating. Two rows are visible at rest
        // (both movie rows), so the tag matches more than one node — the
        // first one is the assertion.
        compose.onAllNodesWithTag(SearchResultRowTag)[0].assertIsDisplayed()
        compose.onNodeWithText("Jawan").assertIsDisplayed()
        compose.onNodeWithText("2023 · ★ 7.0 · Movie").assertIsDisplayed()
        // The last result scrolls into its own rich row too — the grid's
        // 3-up trio is gone, so every result gets its own row. Item 5 is
        // the Money Heist row (tabs, Movies header, 2 movie rows, Series
        // header, then this row). The row staggers in on mount (up to
        // 300ms), so the assertion waits for the entrance to settle
        // instead of catching the row mid-fade.
        compose.onNodeWithTag(SearchResultsListTag).performScrollToIndex(5)
        compose.waitForIdle()
        compose.onNodeWithText("Money Heist").assertIsDisplayed()
        compose.onNodeWithText("2017 · ★ 8.2 · Series").assertIsDisplayed()
    }
}
