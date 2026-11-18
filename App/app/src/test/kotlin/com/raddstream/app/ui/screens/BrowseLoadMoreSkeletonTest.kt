package com.raddstream.app.ui.screens

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.raddstream.app.ui.theme.RaddStreamTheme
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
        compose.setContent {
            RaddStreamTheme {
                BrowsePosterGrid(
                    items = emptyList(),
                    loadingMore = true,
                    error = null,
                    gridState = rememberLazyGridState(),
                    onPostClick = {},
                    onRetryLoadMore = {},
                )
            }
        }
        // Four placeholder cells stand in for the incoming page.
        compose.onAllNodesWithTag("browseLoadMoreSkeleton").assertCountEquals(4)
        // And no spinner anywhere in the grid.
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(0)
    }
}
