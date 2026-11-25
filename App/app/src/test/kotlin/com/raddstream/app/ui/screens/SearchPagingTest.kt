package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.SearchUiState
import com.raddstream.app.viewmodel.TypeGroup
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.compose.ui.test.junit4.createComposeRule

/**
 * Feel-instant S6: the search screen pages itself now — the "Load more" /
 * "Show all N results" footer is gone (A4-F4's affordance, retired: the
 * list loads the next page as the viewer scrolls), and the count line is
 * still the one count on the screen. These tests pin the new behaviour:
 * the summary line, the skeleton rows while the next page loads, and the
 * inline retry when a page fails — all driven by the PagingData's
 * loadState, which the tests feed through a MutableStateFlow exactly the
 * way the Android Paging test guide prescribes.
 *
 * Runs on the JVM under Robolectric because this project has no emulator
 * image. Requires an x86_64 runner (robolectric/robolectric#9166).
 */
@RunWith(RobolectricTestRunner::class)
class SearchPagingTest {

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

    private val idle = LoadState.NotLoading(endOfPaginationReached = false)

    private fun setResults(
        pagingData: PagingData<Post>,
        serverTotal: Int = 52,
        groups: List<TypeGroup> = listOf(
            TypeGroup(
                label = "Series",
                typeKey = "series",
                items = listOf(samplePost(1, "One Piece")),
            ),
        ),
    ) {
        val flow = MutableStateFlow(pagingData)
        compose.setContent {
            RaddStreamTheme {
                // A tall viewport: SearchContent is a LazyColumn, and a lazy
                // item below the fold is never composed — the append footer
                // must exist as a node before the test can see it. 1200dp,
                // not 1000dp: the round-2 category tabs add ~112dp above the
                // results, which pushed the footer out of the old viewport.
                // 1200dp reaches the first skeleton row (and the inline
                // retry, which sits in its place) but not the second row,
                // so the two-cell pin below still holds.
                Box(Modifier.size(width = 360.dp, height = 1200.dp)) {
                    val pagingItems = flow.collectAsLazyPagingItems()
                    SearchContent(
                        state = SearchUiState(
                            query = "one piece",
                            serverTotal = serverTotal,
                            hasSearched = true,
                        ),
                        pagingItems = pagingItems,
                        groups = groups,
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
    }

    @Test
    fun theSummaryIsTheOnlyCountLine() {
        // The summary counts loaded works against the server total; the old
        // per-group "1 title" subtitle counted a third population and read
        // as a competing total next to it, so it is gone.
        setResults(
            PagingData.from(
                data = listOf(samplePost(1, "One Piece")),
                sourceLoadStates = LoadStates(refresh = idle, prepend = idle, append = idle),
            ),
        )
        compose.onNodeWithText("Showing 1 of 52 results").assertIsDisplayed()
        compose.onNodeWithText("1 title").assertDoesNotExist()
        compose.onNodeWithText("1 titles").assertDoesNotExist()
    }

    @Test
    fun cappedListHasNoShowAllOrLoadMore() {
        // S6 retires the footer: a capped list pages itself as the viewer
        // scrolls, so there is no "Show all 52 results" and no "Load more"
        // to tap — the count line is the only thing at the end of the list
        // until the next page arrives.
        setResults(
            PagingData.from(
                data = listOf(samplePost(1, "One Piece")),
                sourceLoadStates = LoadStates(refresh = idle, prepend = idle, append = idle),
            ),
        )
        compose.onNodeWithText("Show all 52 results").assertDoesNotExist()
        compose.onNodeWithText("Load more").assertDoesNotExist()
    }

    @Test
    fun appendLoadingShowsSkeletonRowsNotASpinner() {
        // While the next page is out, the tail of the list is two rows of
        // the S5 geometry-matched skeleton cells — four cells, no spinner.
        // The test viewport only composes the first row: a LazyColumn never
        // lays out the second row while it sits below the fold, so the pin
        // is the two visible cells, not all four.
        setResults(
            PagingData.from(
                data = listOf(samplePost(1, "One Piece")),
                sourceLoadStates = LoadStates(
                    refresh = idle,
                    prepend = idle,
                    append = LoadState.Loading,
                ),
            ),
        )
        compose.onAllNodesWithTag("searchAppendSkeleton").assertCountEquals(2)
    }

    @Test
    fun appendErrorShowsTheInlineRetry() {
        // A failed next page is a retry row at the end of the list, not a
        // silent short list and not a full-screen error.
        setResults(
            PagingData.from(
                data = listOf(samplePost(1, "One Piece")),
                sourceLoadStates = LoadStates(
                    // refresh endOfPaginationReached = true, local to this
                    // test: with the shared idle (false) the pager
                    // auto-appends as soon as the PagingData is processed;
                    // the static source returns an empty page, so append
                    // becomes NotLoading and OVERWRITES the fixture Error
                    // before any recomposition renders it — that is why
                    // this test read "not displayed" in all three CI runs
                    // (44646400985 / 44649050155 / 44651470183) while the
                    // Loading sibling passes (Loading is the initial
                    // state, rendered before any processing). Marking the
                    // refresh as the end of pagination stops the
                    // auto-append, so the fixture Error persists and the
                    // retry row renders. The other tests keep idle.
                    refresh = LoadState.NotLoading(endOfPaginationReached = true),
                    prepend = idle,
                    append = LoadState.Error(IOException("dead socket")),
                ),
            ),
        )
        // The append Error only arrives once the PagingData is processed
        // through collectAsLazyPagingItems — unlike the Loading sibling,
        // which passes on the initial all-Loading state before any
        // processing runs, this footer does not exist on the first
        // composition. waitUntil polls for it instead of asserting
        // against a state that may not have landed yet. It still fails
        // if the footer never appears — a missing retry row is not
        // papered over, it is waited for.
        //
        // The footer is composed but sits below the visible area: the
        // LazyColumn composes a buffer beyond its viewport, so the node
        // exists (waitUntil passes — fixup 3's endOfPagination refresh
        // made the Error persist) while assertIsDisplayed fails (the
        // row is clipped out of view). Scroll it into view first, the
        // way a viewer scrolls to reach the retry row; if the node is
        // already visible this is a no-op.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Could not load more.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Could not load more.").performScrollTo()
        compose.onNodeWithText("Could not load more.").assertIsDisplayed()
    }

    @Test
    fun refreshErrorShowsTheFullErrorState() {
        // A failed first page is the full error state, with the search's
        // own retry — the list is empty, so there is nothing to page.
        setResults(
            pagingData = PagingData.empty(
                sourceLoadStates = LoadStates(
                    refresh = LoadState.Error(IOException("dead socket")),
                    prepend = idle,
                    append = idle,
                ),
            ),
            groups = emptyList(),
        )
        compose.onNodeWithText("Couldn't search").assertIsDisplayed()
    }
}
