package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.SearchUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

    @Test
    fun trendingSearchesAndDiscoveryStayVisibleWithRecents() {
        var clickedQuery: String? = null
        compose.setContent {
            RaddStreamTheme {
                SearchDiscoveryContent(
                    state = SearchUiState(
                        recents = listOf("Classic Cinema"),
                        trending = listOf(
                            samplePost(1, "Dune"),
                            samplePost(2, "Inception"),
                        ),
                    ),
                    onRunQuery = { clickedQuery = it },
                    onClearRecents = {},
                    onSelectGenre = {},
                    onPostClick = {},
                )
            }
        }

        compose.onNodeWithText("Recent searches").assertIsDisplayed()
        compose.onNodeWithText("Trending searches").assertIsDisplayed()
        compose.onAllNodesWithText("Dune")[0].performClick()
        assertEquals("Dune", clickedQuery)
    }
}
