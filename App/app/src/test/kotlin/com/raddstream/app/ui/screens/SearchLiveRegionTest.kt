package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
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

/**
 * SE-5 (round 2): the results summary and the no-results state are polite
 * live regions — a TalkBack viewer hears the count (or "No matches") when
 * the search lands, without the focus being stolen. A live region is a
 * semantics property, so these tests read it off the node instead of
 * trusting the modifier by eye.
 *
 * Runs on the JVM under Robolectric because this project has no emulator
 * image. Requires an x86_64 runner (robolectric/robolectric#9166).
 */
@RunWith(RobolectricTestRunner::class)
class SearchLiveRegionTest {

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

    // SemanticsConfiguration has no getOrNull in this project's Compose
    // version (BOM 2025.11.01) — the contains + get pair is the
    // version-proof way to read an optional semantics property.
    private fun liveRegionMode(node: SemanticsNodeInteraction): LiveRegionMode? {
        val config = node.fetchSemanticsNode().config
        return if (config.contains(SemanticsProperties.LiveRegion)) {
            config[SemanticsProperties.LiveRegion]
        } else {
            null
        }
    }

    @Test
    fun resultsSummaryIsAPoliteLiveRegion() {
        val pagingData = PagingData.from(
            listOf(samplePost(1, "One Piece")),
            sourceLoadStates = androidx.paging.LoadStates(
                refresh = LoadState.NotLoading(endOfPaginationReached = true),
                prepend = LoadState.NotLoading(endOfPaginationReached = true),
                append = LoadState.NotLoading(endOfPaginationReached = true),
            ),
        )
        val flow = MutableStateFlow(pagingData)
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 360.dp, height = 1000.dp)) {
                    val pagingItems = flow.collectAsLazyPagingItems()
                    SearchContent(
                        state = SearchUiState(
                            query = "one piece",
                            hasSearched = true,
                            serverTotal = 52,
                        ),
                        pagingItems = pagingItems,
                        groups = listOf(
                            TypeGroup(
                                label = "Series",
                                typeKey = "series",
                                items = listOf(samplePost(1, "One Piece")),
                            ),
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
        val summary = compose.onNodeWithText("Showing 1 of 52 results")
        summary.assertIsDisplayed()
        assertEquals(
            "The results summary must announce politely when the page lands",
            LiveRegionMode.Polite,
            liveRegionMode(summary),
        )
    }

    @Test
    fun noResultsRootIsAPoliteLiveRegion() {
        compose.setContent {
            RaddStreamTheme {
                SearchNoResultsContent(
                    state = SearchUiState(query = "zzzz", hasSearched = true),
                    onQueryChanged = {},
                    onResetFilters = {},
                    onRunSuggestion = {},
                    onPostClick = {},
                )
            }
        }
        val root = compose.onNodeWithTag(NoResultsTag)
        root.assertIsDisplayed()
        assertEquals(
            "The no-results root must announce politely when the search lands empty",
            LiveRegionMode.Polite,
            liveRegionMode(root),
        )
    }
}
