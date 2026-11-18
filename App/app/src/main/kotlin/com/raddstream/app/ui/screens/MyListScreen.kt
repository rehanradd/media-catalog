package com.raddstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.R
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.components.CatalogGridColumns
import com.raddstream.app.ui.components.ChipRowSkeleton
import com.raddstream.app.ui.components.EmptyState
import com.raddstream.app.ui.components.PosterCard
import com.raddstream.app.ui.components.PosterGridSkeleton
import com.raddstream.app.ui.components.PosterRailSkeleton
import com.raddstream.app.ui.components.RaddChip
import com.raddstream.app.ui.components.ScrollingChipRow
import com.raddstream.app.viewmodel.ListSort
import com.raddstream.app.viewmodel.MyListUiState
import com.raddstream.app.viewmodel.MyListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyListScreen(
    onPostClick: (Post) -> Unit,
    onOpenWatchHistory: () -> Unit = {},
    /** The empty list's way out: the catalog, not a sentence telling the user to go find it. */
    onBrowseClick: () -> Unit = {},
    viewModel: MyListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val popularSettled by viewModel.popularSettled.collectAsState()
    val saveFailed by viewModel.saveFailed.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailedMessage = stringResource(R.string.mylist_save_failed)

    // My List review (Nov 2026): the starter rail's saved state — a v1 id
    // match, or a work_ref match for a v2 rail row whose id is the negative
    // work-id sentinel (see MyListViewModel.toggleFavorite). The filled
    // heart on a rail card reads this; the rail keeps a just-saved title
    // instead of filtering it out.
    val isFavorite: (Post) -> Boolean = { post ->
        state.favorites.any { it.id == post.id || (post.workRef != null && it.workRef == post.workRef) }
    }

    // One tap removes the row; the way back is the snackbar, not a dialog
    // the viewer had to answer first. Dismissing the snackbar without
    // tapping Undo lets the removal stand.
    LaunchedEffect(state.removedTitle) {
        val title = state.removedTitle ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Removed $title from My List",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoRemoval()
        } else {
            viewModel.clearRemovedNotice()
        }
    }

    // My List review (Nov 2026): the rail heart's save failed to resolve
    // — a tap that silently does nothing reads as broken, so the screen
    // says so. One message for every failure here; Detail is the place
    // that can say why a particular work will not resolve.
    LaunchedEffect(saveFailed) {
        if (!saveFailed) return@LaunchedEffect
        snackbarHostState.showSnackbar(saveFailedMessage)
        viewModel.clearSaveFailed()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                // CL-7, same as Settings: the title says "My List" and so does
                // its nav tab — the title is paint, not a second announcement.
                title = { Text("My List", modifier = Modifier.clearAndSetSemantics { }) },
                actions = {
                    // Audit #45: the clock means nothing on its own — it now
                    // says "History" next to the icon, so the viewer knows
                    // where the tap goes. The history lives next to the list
                    // it belongs to: the same device, the same titles, one
                    // tap away in every list state.
                    TextButton(
                        onClick = onOpenWatchHistory,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("History")
                    }
                },
            )
        },
    ) { contentPadding ->
        when {
            state.isLoading -> Column(Modifier.fillMaxSize().padding(contentPadding)) {
                // The sort chips row sits above the grid when the list lands
                // (48dp + 4dp vertical padding) — the bare grid let it appear
                // late and push every row down.
                ChipRowSkeleton(widths = listOf(128, 80, 112))
                PosterGridSkeleton(modifier = Modifier.weight(1f))
            }
            state.favorites.isEmpty() && state.hiddenCount > 0 -> MyListHiddenContent(
                hiddenNotice = state.hiddenNotice,
                popular = state.popular,
                recent = state.recent,
                popularLoading = !popularSettled,
                onPostClick = onPostClick,
                isFavorite = isFavorite,
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                onBrowseClick = onBrowseClick,
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
            state.favorites.isEmpty() -> MyListEmptyContent(
                popular = state.popular,
                recent = state.recent,
                popularLoading = !popularSettled,
                onPostClick = onPostClick,
                isFavorite = isFavorite,
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                onBrowseClick = onBrowseClick,
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
            else -> MyListLoadedContent(
                state = state,
                onPostClick = onPostClick,
                onRemove = { viewModel.remove(it) },
                onSort = { viewModel.setSort(it) },
                isFavorite = isFavorite,
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
        }
    }
}

/**
 * My List review (Nov 2026): the hidden list — every saved row is one
 * this build may not draw — gets the same shape as the truly-empty state
 * instead of the barest screen in the app: the notice, the way out, and
 * the trending rail underneath it when the load produced titles. The
 * viewer who lost their whole list is the one who most needs a way back
 * into the catalog, and the rail was already loaded and held in state —
 * this branch just never read it. Internal so the mockup test can pin
 * the rail and the 48dp Browse button without the Hilt ViewModel.
 */
internal const val MyListBrowseButtonTag = "myListBrowseCatalog"

@Composable
internal fun MyListHiddenContent(
    hiddenNotice: String?,
    popular: List<Post>,
    onPostClick: (Post) -> Unit,
    onBrowseClick: () -> Unit,
    modifier: Modifier = Modifier,
    popularLoading: Boolean = false,
    isFavorite: (Post) -> Boolean = { false },
    onToggleFavorite: ((Post) -> Unit)? = null,
    /** A4-F5: the second discovery rail — same titles as the empty state's, below Trending. */
    recent: List<Post> = emptyList(),
) {
    // A4-F5 fixup: the empty state grew past one screen — message + Browse
    // button + two rails is ~800dp against ~700dp of usable height on a
    // Pixel 5, so the second rail's cards were cut off at the bottom with
    // no way to reach them. The Column scrolls; a screen that fits renders
    // identically, and a tall one scrolls to the rail instead of clipping it.
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        EmptyState(
            // Nothing here is "empty" — the rows exist and this build may not show them.
            // Saying "your list is empty" would be a second loss on top of the first one.
            title = "Nothing to show from your list",
            message = hiddenNotice
                ?: stringResource(R.string.mylist_empty_message),
            icon = { MyListEmptyHeartIcon() },
            fillScreen = false,
            modifier = Modifier.fillMaxWidth(),
            action = {
                OutlinedButton(
                    onClick = onBrowseClick,
                    // The empty state's only way out is the one button a
                    // new viewer is most likely to tap — it gets the 48dp
                    // floor every other control on this screen already has.
                    modifier = Modifier.testTag(MyListBrowseButtonTag).heightIn(min = 48.dp),
                ) { Text("Browse the catalog") }
            },
        )
        if (popular.isNotEmpty()) {
            PopularRow(
                popular = popular,
                onPostClick = onPostClick,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        } else if (popularLoading) {
            PosterRailSkeleton(cells = 3, modifier = Modifier.padding(bottom = 16.dp))
        }
        // A4-F5: the second rail, below Trending — the hidden list gets the
        // same discovery as the truly-empty one, so it never ends in a void either.
        if (recent.isNotEmpty()) {
            PopularRow(
                popular = recent,
                onPostClick = onPostClick,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                titleRes = R.string.mylist_recent_title,
                subtitleRes = R.string.mylist_recent_subtitle,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

/**
 * The loaded list: the saved count, the sort chips, and the grid — with
 * the starter shelf as a full-span footer while the list is still small.
 * Internal so the mockup test can pin the count, the progress words and
 * the shelf gate without the Hilt ViewModel.
 */
@Composable
internal fun MyListLoadedContent(
    state: MyListUiState,
    onPostClick: (Post) -> Unit,
    onRemove: (Int) -> Unit,
    onSort: (ListSort) -> Unit,
    isFavorite: (Post) -> Boolean,
    onToggleFavorite: (Post) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The count names every saved title, hidden ones included — a row
    // this build may not draw is still saved, and the notice below says
    // how many are hidden.
    val totalSaved = state.favorites.size + state.hiddenCount
    Column(modifier = modifier) {
        state.hiddenNotice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Text(
            text = if (totalSaved == 1) {
                stringResource(R.string.mylist_count_one)
            } else {
                stringResource(R.string.mylist_count_many, totalSaved)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        // The sync/status line is gone on purpose (mockup, Nov
        // 2026): syncing happens silently, and a line about it is
        // operator chrome on a screen about the viewer's list.
        // The sync itself is untouched — only its sentence is.
        MyListSortRow(
            sort = state.sort,
            onSort = onSort,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        LazyVerticalGrid(
            columns = CatalogGridColumns,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(state.favorites, key = { it.id }) { post ->
                PosterCard(
                    post = post,
                    onClick = { onPostClick(post) },
                    onRemove = { onRemove(post.id) },
                    // The bar paints only where a real watch row
                    // says the viewer stopped halfway — never a
                    // bar for a title with no position (see
                    // MyListViewModel.myListProgressByPostId) — and
                    // the words travel with it now ("S2:E5 · 23 min
                    // left"), so the tile speaks its progress
                    // instead of painting a silent grey bar.
                    progressFraction = state.progressByPostId[post.id],
                    progressLabel = state.progressLabelByPostId[post.id],
                )
            }
            // F12 & Small #9: place the starter shelf inside the scrolling grid as
            // a full-span footer when the trending load produced titles, so compact
            // or scaled screens never clip either the favorites or the suggestions.
            // The shelf is a *starter* shelf: it shows only while the list is
            // small (at most one visible favorite), so a two-save list shows
            // it the same way in every session instead of depending on
            // whether this ViewModel was born onto one save or two.
            if (state.popular.isNotEmpty() && state.favorites.size <= 1) {
                item(
                    key = "my_list_starter_shelf",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    PopularRow(
                        popular = state.popular,
                        onPostClick = onPostClick,
                        isFavorite = isFavorite,
                        onToggleFavorite = { onToggleFavorite(it) },
                        horizontalPadding = 0.dp,
                    )
                }
            }
        }
    }
}

/**
 * Mockup (Nov 2026): the sort chips — Recently added / Title A–Z /
 * Unwatched first — 48dp tall, all three fully visible in one row, the
 * active chip jade-filled with black text (the same shape as
 * [HomeFilterRow]: [FilterChip]'s own visual height is 32dp, so a
 * `heightIn(min = 48.dp)` chip used to be a 32dp pill on a 48dp hit
 * area — Robo run 2 measured it). Internal so the mockup test can pin
 * the order, the selected state and the 48dp height without the Hilt
 * ViewModel.
 */
internal const val MyListSortRowTag = "myListSortRow"

@Composable
internal fun MyListSortRow(
    sort: ListSort,
    onSort: (ListSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A4-F3: rides ScrollingChipRow + RaddChip now — the fade and the row
    // are the shared component, not a hand-rolled copy. The sort row gains
    // the fade the other rows already had.
    ScrollingChipRow(
        modifier = modifier,
        rowTestTag = MyListSortRowTag,
    ) {
        items(ListSort.entries) { entry ->
            RaddChip(
                selected = entry == sort,
                onClick = { onSort(entry) },
                label = {
                    Text(
                        entry.label,
                        fontWeight = if (entry == sort) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.testTag("myListSortChip_${entry.name}"),
            )
        }
    }
}

/**
 * My List review (Nov 2026): the empty state no longer claims the whole
 * screen. Robo run 8's shape — [EmptyState] with `weight(1f)` — centred
 * the heart and message in a tall void and pushed the rail to the bottom
 * edge; the empty state now wraps its own content (`fillScreen = false`,
 * no weight), so the rail pulls up directly underneath it. The remaining
 * space below the rail is the end of the content, not a void in the
 * middle of it. Internal so MyListEmptyContentTest can pin it without the
 * Hilt ViewModel.
 */
@Composable
internal fun MyListEmptyContent(
    popular: List<Post>,
    onPostClick: (Post) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The trending load is still in flight: the rail skeleton stands in
     * for the three tiles, so the empty message never names a heart that
     * is not on screen yet — and a failed load settles to no rail at all
     * instead of holding the skeleton forever.
     */
    popularLoading: Boolean = false,
    isFavorite: (Post) -> Boolean = { false },
    onToggleFavorite: ((Post) -> Unit)? = null,
    /**
     * A4-F5: the empty state's primary way out — the filled "Browse
     * Titles" button under the copy, to Search/Browse (wired in NavGraph).
     * The copy alone ("tap the heart…") sent the viewer hunting for a
     * title to save with no way to reach one.
     */
    onBrowseClick: () -> Unit = {},
    /** A4-F5: the second discovery rail — freshest titles, below Trending. */
    recent: List<Post> = emptyList(),
) {
    // A4-F5 fixup: same scroll as the hidden branch — the message, the
    // Browse button and two rails no longer fit one phone screen, and the
    // second rail is the point of the slice, so it must be reachable.
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        EmptyState(
            title = "Your list is empty",
            message = stringResource(R.string.mylist_empty_message),
            icon = { MyListEmptyHeartIcon() },
            fillScreen = false,
            modifier = Modifier.fillMaxWidth(),
            action = {
                Button(
                    onClick = onBrowseClick,
                    // The primary way out gets the 48dp floor every other
                    // control on this screen already has, and the shared
                    // browse tag the hidden state's button already carries
                    // — the two branches never render together.
                    modifier = Modifier.testTag(MyListBrowseButtonTag).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.mylist_browse_titles)) }
            },
        )
        if (popular.isNotEmpty()) {
            PopularRow(
                popular = popular,
                onPostClick = onPostClick,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                // The loaded grid's contentPadding already leaves 24dp
                // below its last row; the empty state's Column has no such
                // padding, so the rail carries its own — the cards sit
                // 24dp above the bottom nav instead of tight against it.
                modifier = Modifier.padding(bottom = 16.dp),
            )
        } else if (popularLoading) {
            PosterRailSkeleton(cells = 3, modifier = Modifier.padding(bottom = 16.dp))
        }
        // A4-F5: the second discovery rail, below Trending. The choice over
        // vertically centring the empty-state block: the Nov 2026 review
        // deliberately un-centred it (a weight(1f) EmptyState pushed the
        // rail to the bottom edge), and centring would only split the same
        // void top and bottom on a tall phone. A second rail fills the
        // space with something worth tapping instead, in the screen's own
        // rail pattern. It renders only when the load produced titles; a
        // failed load settles to no second rail, like the first.
        if (recent.isNotEmpty()) {
            PopularRow(
                popular = recent,
                onPostClick = onPostClick,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                titleRes = R.string.mylist_recent_title,
                subtitleRes = R.string.mylist_recent_subtitle,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

/**
 * My List review (Nov 2026): the empty-state heart, jade in a jade-tinted
 * rounded-square container (Stitch). The old heart was coral
 * (`colorScheme.secondary`) — an error colour next to the jade My List
 * tab, and the opposite of what "tap the heart to save" should look like.
 * Shared by both empty states (the hidden-notice variant and the empty
 * content) so the two never drift apart again.
 */
@Composable
private fun MyListEmptyHeartIcon() {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.FavoriteBorder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
    }
}

/**
 * Three titles for an empty list — the server's own trending order (most
 * prints and mirrors first), not this viewer's history and not a popularity
 * claim: the ledger has no play counts. A tap opens the title; the heart
 * on the poster saves it without leaving the screen (the same toggle
 * Detail and Home use — [PosterCard]'s TopStart heart, filled jade once
 * saved). The heart is the toggle the empty-state message names ("tap the
 * heart"), so the rail finally has the control its own copy promises.
 * The rail title is viewer copy, not ledger jargon: "Most copies" read
 * like warehouse inventory.
 */
@Composable
internal fun PopularRow(
    popular: List<Post>,
    onPostClick: (Post) -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
    isFavorite: (Post) -> Boolean = { false },
    onToggleFavorite: ((Post) -> Unit)? = null,
    modifier: Modifier = Modifier,
    // A4-F5: the rail's heading is a parameter now — the Trending rail
    // keeps its title, the second rail says "Recently Added". The default
    // is the Trending copy, so the loaded grid's starter shelf is unchanged.
    titleRes: Int = R.string.mylist_popular_title,
    subtitleRes: Int = R.string.mylist_popular_subtitle,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(subtitleRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            popular.take(3).forEach { post ->
                PosterCard(
                    post = post,
                    onClick = { onPostClick(post) },
                    isFavorite = isFavorite(post),
                    onToggleFavorite = onToggleFavorite?.let { toggle -> { toggle(post) } },
                    // The 3-up cells are too narrow for the full
                    // "★ 8.6 · 2024 · Series" line — it truncated to
                    // "S..." on every card. Rating + year only.
                    compactMeta = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
