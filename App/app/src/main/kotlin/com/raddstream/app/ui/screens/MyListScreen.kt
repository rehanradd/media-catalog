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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.raddstream.app.viewmodel.ListSort
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
    val snackbarHostState = remember { SnackbarHostState() }

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
            state.favorites.isEmpty() && state.hiddenCount > 0 -> EmptyState(
                // Nothing here is "empty" — the rows exist and this build may not show them.
                // Saying "your list is empty" would be a second loss on top of the first one.
                title = "Nothing to show from your list",
                message = state.hiddenNotice
                    ?: stringResource(R.string.mylist_empty_message),
                icon = { MyListEmptyHeartIcon() },
                modifier = Modifier.padding(contentPadding),
                action = {
                    OutlinedButton(onClick = onBrowseClick) { Text("Browse the catalog") }
                },
            )
            state.favorites.isEmpty() -> MyListEmptyContent(
                popular = state.popular,
                onPostClick = onPostClick,
                isFavorite = isFavorite,
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
            else -> Column(Modifier.fillMaxSize().padding(contentPadding)) {
                state.hiddenNotice?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                // The sync/status line is gone on purpose (mockup, Nov
                // 2026): syncing happens silently, and a line about it is
                // operator chrome on a screen about the viewer's list.
                // The sync itself is untouched — only its sentence is.
                MyListSortRow(
                    sort = state.sort,
                    onSort = { viewModel.setSort(it) },
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
                            onRemove = { viewModel.remove(post.id) },
                            // The bar paints only where a real watch row
                            // says the viewer stopped halfway — never a
                            // bar for a title with no position (see
                            // MyListViewModel.myListProgressByPostId).
                            progressFraction = state.progressByPostId[post.id],
                        )
                    }
                    // F12 & Small #9: place the starter shelf inside the scrolling grid as
                    // a full-span footer when the trending load produced titles, so compact
                    // or scaled screens never clip either the favorites or the suggestions.
                    // The load itself still only runs while <= 1 favorite is visible (see
                    // MyListViewModel) — this footer only decides where the titles it
                    // already has are shown.
                    if (state.popular.isNotEmpty()) {
                        item(
                            key = "my_list_starter_shelf",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            PopularRow(
                                popular = state.popular,
                                onPostClick = onPostClick,
                                isFavorite = isFavorite,
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                horizontalPadding = 0.dp,
                            )
                        }
                    }
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
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag(MyListSortRowTag),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ListSort.entries) { entry ->
            FilterChip(
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
                modifier = Modifier.height(48.dp).testTag("myListSortChip_${entry.name}"),
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
    isFavorite: (Post) -> Boolean = { false },
    onToggleFavorite: ((Post) -> Unit)? = null,
) {
    Column(modifier = modifier) {
        EmptyState(
            title = "Your list is empty",
            message = stringResource(R.string.mylist_empty_message),
            icon = { MyListEmptyHeartIcon() },
            fillScreen = false,
            modifier = Modifier.fillMaxWidth(),
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
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.mylist_popular_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.mylist_popular_subtitle),
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
                    // "★ 8.6 | 2024 | Series" line — it truncated to
                    // "S..." on every card. Rating + year only.
                    compactMeta = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
