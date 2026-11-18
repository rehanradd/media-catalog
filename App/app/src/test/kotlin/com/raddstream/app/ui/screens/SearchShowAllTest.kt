package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.SearchUiState
import com.raddstream.app.viewmodel.TypeGroup
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A4-F4 (Robo OR-05 + Groq): the search screen showed "Showing 34 of 52
 * results" with no way to reach the 52 — no "Load more" / "See all 52"
 * affordance was visible — while the section headers counted a second
 * population ("Movies / 18 titles") next to it. These tests pin the fix:
 * one count line (the summary), no per-group count, and a "Show all N
 * results" affordance at the end of a capped list.
 *
 * Runs on the JVM under Robolectric because this project has no emulator
 * image. Requires an x86_64 runner (robolectric/robolectric#9166).
 */
@RunWith(RobolectricTestRunner::class)
class SearchShowAllTest {

    @get:Rule
    val compose = createComposeRule()

    private fun samplePost(id: Int, title: String) = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = 2024,
        contentType = "series",
        posterUrl = null,
    )

    private fun setResults(
        serverTotal: Int = 52,
        hasMore: Boolean = true,
        onShowAll: () -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                SearchContent(
                    state = SearchUiState(
                        query = "one piece",
                        groups = listOf(
                            TypeGroup(
                                label = "Series",
                                typeKey = "series",
                                items = listOf(samplePost(1, "One Piece")),
                            ),
                        ),
                        resultCount = 1,
                        serverTotal = serverTotal,
                        hasMore = hasMore,
                        hasSearched = true,
                    ),
                    onRunQuery = {},
                    onRemoveRecent = {},
                    onSubmitCurrentQuery = {},
                    onResetFilters = {},
                    onQueryChanged = {},
                    onShowCloseMatches = {},
                    onShowExactOnly = {},
                    onRunSuggestion = {},
                    onLoadMore = {},
                    onShowAll = onShowAll,
                    onPostClick = {},
                )
            }
        }
    }

    @Test
    fun cappedListOffersShowAllWithTheFullCount() {
        setResults()
        val showAll = compose.onNodeWithTag(ShowAllResultsTag)
        showAll.performScrollTo()
        showAll.assertIsDisplayed()
        compose.onNodeWithText("Show all 52 results").assertIsDisplayed()
    }

    @Test
    fun showAllTapReachesTheCallback() {
        var shown = false
        setResults(onShowAll = { shown = true })
        compose.onNodeWithTag(ShowAllResultsTag).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("Show all tap never reached onShowAll", shown)
    }

    @Test
    fun wholeListHasNoShowAll() {
        // hasMore false is the whole answer — there is nothing left to expand to.
        setResults(hasMore = false)
        compose.onNodeWithTag(ShowAllResultsTag).assertDoesNotExist()
        compose.onNodeWithText("Show all 52 results").assertDoesNotExist()
    }

    @Test
    fun theSummaryIsTheOnlyCountLine() {
        // The summary counts loaded works against the server total; the old
        // per-group "1 title" subtitle counted a third population and read
        // as a competing total next to it, so it is gone.
        setResults()
        compose.onNodeWithText("Showing 1 of 52 results").assertIsDisplayed()
        compose.onNodeWithText("1 title").assertDoesNotExist()
        compose.onNodeWithText("1 titles").assertDoesNotExist()
    }
}
