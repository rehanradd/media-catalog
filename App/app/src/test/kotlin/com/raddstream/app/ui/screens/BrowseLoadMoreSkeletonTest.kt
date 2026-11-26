package com.raddstream.app.ui.screens

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.components.SKELETON_DELAY_MS
import com.raddstream.app.ui.theme.RaddStreamTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S5: Browse's "Load more" footer stands in with placeholder rows, not a
 * spinner — the incoming page is poster cards, so the footer is the same
 * cells, and a spinner in a single 60dp cell both jumped the layout and
 * flashed on fast pages. Pins that the footer is four skeleton cells and
 * no indeterminate progress indicator anywhere in the grid.
 *
 * Feel-instant S6: the footer is driven by the PagingData's append
 * loadState now — it shows while the next page loads and the server gave
 * no total to placeholder against — so the test feeds the grid a
 * PagingData whose append is Loading, through a MutableStateFlow exactly
 * the way the Android Paging test guide prescribes.
 *
 * Requires an x86_64 runner. Robolectric's native runtime has no
 * Linux/aarch64 build (robolectric/robolectric#9166) and it fails while the
 * runner boots, so the exclusion for ARM hosts lives in
 * app/build.gradle.kts rather than here.
 */
@RunWith(RobolectricTestRunner::class)
class BrowseLoadMoreSkeletonTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loadMoreShowsSkeletonCellsNotASpinner() {
        compose.mainClock.autoAdvance = false
        // An empty page: the load-more footer is independent of the page
        // contents, and a lazy grid only composes the rows intersecting
        // the ~470dp test window — with posts present the third row
        // (skeletons 3–4) would fall below the fold and never compose,
        // so the count would read 2 for a 4-cell footer that is really
        // there. Two rows of skeleton cells fit the window, so all four
        // exist.
        val idle = LoadState.NotLoading(endOfPaginationReached = false)
        val pagingDataFlow = MutableStateFlow(
            PagingData.from(
                data = emptyList<Post>(),
                sourceLoadStates = LoadStates(
                    refresh = idle,
                    prepend = idle,
                    append = LoadState.Loading,
                ),
            ),
        )
        compose.setContent {
            RaddStreamTheme {
                val pagingItems = pagingDataFlow.collectAsLazyPagingItems()
                BrowsePosterGrid(
                    pagingItems = pagingItems,
                    gridState = rememberLazyGridState(),
                    onPostClick = {},
                )
            }
        }
        // B-42 (P2-11): the placeholder cells mount only after the 200 ms
        // delay gate — advance past it before counting.
        compose.mainClock.advanceTimeBy(SKELETON_DELAY_MS + 50)
        // Four placeholder cells stand in for the incoming page.
        compose.onAllNodesWithTag("browseLoadMoreSkeleton").assertCountEquals(4)
        // And no spinner anywhere in the grid.
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(0)
    }
}
