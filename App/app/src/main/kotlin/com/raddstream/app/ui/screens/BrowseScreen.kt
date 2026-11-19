package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.raddstream.app.network.friendlyNetworkError
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.components.CatalogGridColumns
import com.raddstream.app.ui.components.EmptyState
import com.raddstream.app.ui.components.ErrorState
import com.raddstream.app.ui.components.InlineLoadFailure
import com.raddstream.app.ui.components.PosterCard
import com.raddstream.app.ui.components.PosterCellSkeleton
import com.raddstream.app.ui.components.PosterGridSkeleton
import com.raddstream.app.ui.components.prefetchPosterArt
import com.raddstream.app.ui.components.scrollAheadPrefetchUrls
import com.raddstream.app.viewmodel.BrowseViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onBackClick: () -> Unit,
    onPostClick: (Post) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    // Feel-instant S6: the grid pages itself now — Paging prefetches the
    // next page five rows before the end, so the old scroll-end trigger
    // (`rememberGridEndlessPaging`) and its `loadMore()` are gone.
    val pagingItems = viewModel.pagingData.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()

    // The native feed card is capped at one per twelve posters, and the grid's own scroll index is
    // the only honest measure of how many the viewer has passed. Reporting the delta rather than
    // the index is what keeps a re-composition from double-counting the same row, and nothing on
    // screen depends on the answer: the governor holds the count, and a card no network filled
    // renders no row at all.
    val firstVisibleItem = gridState.firstVisibleItemIndex
    var lastReportedItem by remember { mutableStateOf(0) }
    // Feel-instant S6: the list states come from the PagingData's loadState
    // now, not a ViewModel list. `itemCount` counts the placeholders too,
    // so the loaded count reads the loaded snapshot.
    val loadedCount = pagingItems.itemSnapshotList.count { it != null }
    // Preload + caches (Nov 2026): scroll-ahead poster prefetch. Browse was
    // the one paged list that never warmed anything — Home warms its rails
    // on open, Search warms its pages as they land — so every Browse poster
    // downloaded only when its tile composed. The same scroll tick that
    // reports to the feed-card governor now warms the visible screen plus
    // one screen ahead of it, from the loaded snapshot; a remembered set
    // keeps a URL from being enqueued twice, and the helper's metered gate
    // still applies. Keyed on the scroll index and the loaded count, so the
    // first page warms when it lands and a new page warms as it arrives.
    val prefetchedUrls = remember { mutableSetOf<String>() }
    val prefetchContext = LocalContext.current
    LaunchedEffect(firstVisibleItem, loadedCount) {
        val delta = firstVisibleItem - lastReportedItem
        if (delta > 0) {
            lastReportedItem = firstVisibleItem
            viewModel.onPostersScrolled(delta)
        }
        val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisibleItem
        val ahead = scrollAheadPrefetchUrls(
            pagingItems.itemSnapshotList.items,
            firstVisibleIndex = firstVisibleItem,
            lastVisibleIndex = lastVisible,
        ).filter { it !in prefetchedUrls }
        if (ahead.isNotEmpty()) {
            prefetchedUrls += ahead
            prefetchPosterArt(prefetchContext, ahead)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                // Flow mockup (Nov 2026): the bar is the same near-black as
                // the canvas — the default surface band would draw a stripe
                // across the top — and the category name is bold, as drawn.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Text(
                        state.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { contentPadding ->
        // A category with rows on the way still has an empty snapshot
        // while the first page is out.
        val refreshState = pagingItems.loadState.refresh
        when {
            refreshState is LoadState.Loading && loadedCount == 0 -> PosterGridSkeleton(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                // The real grid's bottom breathing is 32dp, not the
                // skeleton's 24dp default — a short category would jump
                // 8dp at the bottom when its rows arrived.
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp, start = 16.dp, end = 16.dp),
            )
            refreshState is LoadState.Error && loadedCount == 0 ->
                ErrorState(
                    title = "Couldn't load this category",
                    message = friendlyNetworkError(refreshState.error, "Could not load."),
                    onRetry = { pagingItems.retry() },
                    modifier = Modifier.padding(contentPadding),
                )
            loadedCount == 0 && refreshState is LoadState.NotLoading -> EmptyState(
                title = "Nothing here yet",
                message = "This category has no titles here yet.",
                modifier = Modifier.padding(contentPadding),
                action = {
                    // The category is empty, not the app. The way forward is back to the picker.
                    OutlinedButton(onClick = onBackClick) { Text("Browse all") }
                },
            )
            else -> BrowsePosterGrid(
                pagingItems = pagingItems,
                gridState = gridState,
                onPostClick = onPostClick,
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
        }
    }
}

/**
 * Flow mockup (Nov 2026): the category grid itself — two columns of the
 * canonical [PosterCard] on a phone, bold title + "year · type" meta, no
 * bottom nav. Extracted from [BrowseScreen] so the grid is testable
 * without the ViewModel: the screen keeps the Scaffold, the paging and
 * the state machine, and this renders only the rows.
 *
 * Feel-instant S6: the grid reads a [LazyPagingItems] now. Placeholders
 * are on, so a position Paging has not loaded yet reads as `null` — and
 * a null never reaches a card: it renders the S5 geometry-matched
 * [PosterCellSkeleton] instead (the crash the research warned about is
 * exactly a null item handed to a card).
 */
@Composable
internal fun BrowsePosterGrid(
    pagingItems: LazyPagingItems<Post>,
    gridState: LazyGridState,
    onPostClick: (Post) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        state = gridState,
        columns = CatalogGridColumns,
        modifier = modifier,
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp, start = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(
            count = pagingItems.itemCount,
            // A loaded row is keyed by its post id; a placeholder has no
            // post, so it is keyed by its position — and `peek` reads the
            // row without triggering a load for it.
            key = { index -> pagingItems.peek(index)?.id ?: "placeholder-$index" },
        ) { index ->
            val post = pagingItems[index]
            if (post == null) {
                PosterCellSkeleton(Modifier.testTag("browsePlaceholderSkeleton"))
            } else {
                PosterCard(post = post, onClick = { onPostClick(post) })
            }
        }
        val appendState = pagingItems.loadState.append
        // S5: placeholder rows, not a spinner — the incoming page is
        // poster cards, so the footer stands in with the same cells (the
        // grid's own spacing lays them out in the right columns), and a
        // spinner in a single 60dp cell both jumped the layout and
        // flashed on fast pages. Four cells is two rows on a phone. The
        // footer only shows when no placeholder rows are already standing
        // in for the page: when the server gave no total, Paging has no
        // placeholders to show, and without the footer the bottom of the
        // list would sit silent while the next page loads.
        val hasPlaceholders = pagingItems.itemSnapshotList.any { it == null }
        if (appendState is LoadState.Loading && !hasPlaceholders) {
            items(LOAD_MORE_SKELETON_CELLS) {
                PosterCellSkeleton(Modifier.testTag("browseLoadMoreSkeleton"))
            }
        }
        // The grid is what the viewer can see; a page that failed to arrive is not.
        // Without this a dead next page just looks like a shorter category.
        // The refresh error rides here too when rows are already on screen:
        // a refresh that fails with a loaded grid is a page that failed to
        // arrive, not a screen that failed to load — the full error state
        // above owns only the empty grid.
        val refreshState = pagingItems.loadState.refresh
        val loadError = (appendState as? LoadState.Error)?.error
            ?: (refreshState as? LoadState.Error)?.error
        if (loadError != null) {
            item(contentType = "failure") {
                InlineLoadFailure(
                    message = friendlyNetworkError(loadError, "Could not load more."),
                    onRetry = { pagingItems.retry() },
                )
            }
        }
    }
}

/** Poster cells the load-more footer stands in with — two rows on a phone. */
private const val LOAD_MORE_SKELETON_CELLS = 4
