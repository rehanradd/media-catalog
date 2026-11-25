package com.raddstream.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.raddstream.app.ui.theme.RaddMotion
import com.raddstream.app.ui.theme.RaddTokens
import com.raddstream.app.viewmodel.ListSort
import com.raddstream.app.viewmodel.MyListFilter
import com.raddstream.app.viewmodel.MyListUiState
import com.raddstream.app.viewmodel.MyListViewModel
import com.raddstream.app.viewmodel.filterMyList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyListScreen(
    onPostClick: (Post) -> Unit,
    onOpenWatchHistory: () -> Unit = {},
    /** The empty list's way out: the catalog, not a sentence telling the user to go find it. */
    onBrowseClick: () -> Unit = {},
    /**
     * APP-06 (Phase 4): one-tap play for saved tiles — the same instant-play
     * path Home's rail tiles use (Detail with autoplay, [DetailScreen] fires
     * playPrimary once on open). The tile tap still opens the title; only
     * the play button on the tile starts playback.
     */
    onPlayClick: (Post) -> Unit = {},
    viewModel: MyListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val popularSettled by viewModel.popularSettled.collectAsState()
    val saveFailed by viewModel.saveFailed.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailedMessage = stringResource(R.string.mylist_save_failed)

    // L3 (round 2): every tap answers — the save toggle and the play
    // button tick the haptic, the same TextHandleMove tick Settings and
    // the Player sheets use for their toggles. The tile's own press
    // already ticks through PressHaptics; these are the controls the tile
    // does not own.
    val haptics = LocalHapticFeedback.current
    val onToggleFavoriteWithTick: (Post) -> Unit = { post ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        viewModel.toggleFavorite(post)
    }
    val onPlayClickWithTick: (Post) -> Unit = { post ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onPlayClick(post)
    }

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
                        // S8 (round 2): the last Icons.Default on this
                        // screen — the clock is the house history glyph
                        // (ic_action_history), the same stroke style as
                        // the tab icons. The "History" text beside it
                        // names the tap, so the icon needs no label.
                        Icon(
                            painter = painterResource(R.drawable.ic_action_history),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("History")
                    }
                },
            )
        },
    ) { contentPadding ->
        // L3 (round 2): pull-to-refresh — the Box drives the indicator
        // from the ViewModel's isRefreshing and fires onRefresh on the
        // pull gesture, the same shape Home's feed uses. The pull
        // revalidates the saved rows and reloads the rails; the list
        // itself is local Room data, so the refresh is a re-check, not a
        // re-fetch of the list.
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(contentPadding),
        ) {
            when {
            state.isLoading -> Column(Modifier.fillMaxSize()) {
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
                onToggleFavorite = onToggleFavoriteWithTick,
                onBrowseClick = onBrowseClick,
                railsLoadFailed = state.railsLoadFailed,
                onRetryRails = { viewModel.retryRails() },
                downloadedPostIds = state.downloadedPostIds,
                modifier = Modifier.fillMaxSize(),
            )
            state.favorites.isEmpty() -> MyListEmptyContent(
                popular = state.popular,
                recent = state.recent,
                popularLoading = !popularSettled,
                onPostClick = onPostClick,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavoriteWithTick,
                onBrowseClick = onBrowseClick,
                railsLoadFailed = state.railsLoadFailed,
                onRetryRails = { viewModel.retryRails() },
                downloadedPostIds = state.downloadedPostIds,
                modifier = Modifier.fillMaxSize(),
            )
            else -> MyListLoadedContent(
                state = state,
                onPostClick = onPostClick,
                onPlayClick = onPlayClickWithTick,
                onRemove = { viewModel.remove(it) },
                onSort = { viewModel.setSort(it) },
                onFilter = { viewModel.setFilter(it) },
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavoriteWithTick,
                onRetryRails = { viewModel.retryRails() },
                onBrowseClick = onBrowseClick,
                modifier = Modifier.fillMaxSize(),
            )
            }
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
    /** L4 (round 2): the rail load failed — show the inline retry. */
    railsLoadFailed: Boolean = false,
    /** L4 (round 2): the inline retry tap. */
    onRetryRails: () -> Unit = {},
    /** L2 (round 2): the downloaded-copy ids, for the rail badges. */
    downloadedPostIds: Set<Int> = emptySet(),
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
                downloadedPostIds = downloadedPostIds,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        } else if (popularLoading) {
            PosterRailSkeleton(cells = 3, modifier = Modifier.padding(bottom = 16.dp))
        } else if (railsLoadFailed) {
            // L4 (round 2): the rails failed and there is nothing to show
            // — the retry card stands where the rail would have been.
            MyListRailsErrorCard(
                onRetry = onRetryRails,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        // A4-F5: the second rail, below Trending — the hidden list gets the
        // same discovery as the truly-empty one, so it never ends in a void either.
        if (recent.isNotEmpty()) {
            PopularRow(
                popular = recent,
                onPostClick = onPostClick,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                downloadedPostIds = downloadedPostIds,
                titleRes = R.string.mylist_recent_title,
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
    /**
     * APP-06 (Phase 4): one-tap play for saved tiles. Wired to the grid
     * tiles only — the starter shelf below stays a doorway, like the
     * discovery rails in the empty states. Defaults to no-op so existing
     * callers (and the mockup tests) keep compiling; NavGraph passes the
     * autoplay navigation.
     */
    onPlayClick: (Post) -> Unit = {},
    /** L1 (round 2): the filter chip tap. Defaults to no-op like onSort. */
    onFilter: (MyListFilter) -> Unit = {},
    /** L4 (round 2): the inline rails-retry tap. Defaults to no-op. */
    onRetryRails: () -> Unit = {},
    /**
     * Round 3: the filtered-empty state's way out — the same Browse
     * button the full empty state has. Defaults to no-op so existing
     * callers (and the mockup tests) keep compiling.
     */
    onBrowseClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // L3 (round 2): the staggered entrance runs once, when the grid first
    // lands — a per-item delay, capped, on the shipped emphasized spring.
    // Inspection mode (Paparazzi, previews) renders the final state with
    // no animation: a golden must pin the settled grid, and no golden
    // proves motion anyway.
    val inPreview = LocalInspectionMode.current
    var entered by remember { mutableStateOf(inPreview) }
    LaunchedEffect(Unit) { entered = true }
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
        // L5 (round 2): the count line is a designed line now — title
        // weight with tabular numerals, so "12 titles" never jitters when
        // a save lands and the hierarchy reads at a glance. The text is
        // unchanged (it names the whole list, hidden rows included — the
        // filter scopes the view, not the count).
        Text(
            text = if (totalSaved == 1) {
                stringResource(R.string.mylist_count_one)
            } else {
                stringResource(R.string.mylist_count_many, totalSaved)
            },
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        // The sync/status line is gone on purpose (mockup, Nov
        // 2026): syncing happens silently, and a line about it is
        // operator chrome on a screen about the viewer's list.
        // The sync itself is untouched — only its sentence is.
        MyListFilterRow(
            filter = state.filter,
            sort = state.sort,
            onFilter = onFilter,
            onSort = onSort,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        // L1 (round 2): the grid cross-fades to the filtered set instead
        // of snapping — the filter's award lift, on the shipped default
        // spring. The switch also resets the scroll, as a new list should.
        AnimatedContent(
            targetState = state.filter,
            label = "myListFilter",
            modifier = Modifier.weight(1f),
        ) { filter ->
            val visible = remember(state.favorites, filter, state.downloadedPostIds) {
                filterMyList(state.favorites, filter, state.downloadedPostIds)
            }
            LazyVerticalGrid(
                columns = CatalogGridColumns,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = RaddTokens.Space.Sm,
                    bottom = RaddTokens.Space.BottomClearance,
                    start = RaddTokens.Space.Gutter,
                    end = RaddTokens.Space.Gutter,
                ),
                horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.RailGap),
                // L5 (round 2): the 14dp vertical gaps were off the 8dp
                // grid — 16dp now, the Lg token.
                verticalArrangement = Arrangement.spacedBy(RaddTokens.Space.Lg),
            ) {
                itemsIndexed(visible, key = { _, post -> post.id }) { index, post ->
                    AnimatedVisibility(
                        visible = entered,
                        enter = fadeIn(
                            animationSpec = tween(
                                delayMillis = minOf(index * 40, 400),
                                durationMillis = RaddMotion.DurationEmphasized,
                                easing = RaddMotion.EmphasizedDecelerate,
                            ),
                        ) + scaleIn(
                            animationSpec = tween(
                                delayMillis = minOf(index * 40, 400),
                                durationMillis = RaddMotion.DurationEmphasized,
                                easing = RaddMotion.EmphasizedDecelerate,
                            ),
                            initialScale = 0.96f,
                        ),
                        exit = fadeOut(animationSpec = RaddMotion.quick()),
                        modifier = Modifier.animateItem(),
                    ) {
                        PosterCard(
                            post = post,
                            onClick = { onPostClick(post) },
                            onRemove = { onRemove(post.id) },
                            // APP-06 (Phase 4): the saved tile is the strongest
                            // resume case — it grows the same play button Home's
                            // rails have, opening Detail with autoplay. The tile
                            // tap still opens the title; the × beside the title
                            // still removes it. Detail decides what "play" means
                            // for the title (resume, HLS, quality sheet, or the
                            // "no working link" line) — the tile never guesses.
                            onPlayClick = { onPlayClick(post) },
                            // L2 (round 2): the download badge — a verified
                            // copy on the device, from the keep store.
                            isDownloaded = state.downloadedPostIds.contains(post.id),
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
                }
                // L1 (round 2): a filter that matches nothing says so in
                // the grid instead of leaving a blank screen — the count
                // above still names the whole list, so the line names the
                // filter, not the list.
                if (visible.isEmpty() && state.favorites.isNotEmpty()) {
                    item(
                        key = "my_list_filter_empty",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        // Round 3: the filtered-empty state was a bare line
                        // over a void — it gets the same way out the full
                        // empty state has (the Browse button), not the
                        // starter rail: under a filter the rail would show
                        // titles the filter just hid (L1).
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(
                                    when (filter) {
                                        MyListFilter.MOVIES -> R.string.mylist_filter_empty_movies
                                        MyListFilter.SERIES -> R.string.mylist_filter_empty_series
                                        MyListFilter.DOWNLOADS -> R.string.mylist_filter_empty_downloads
                                        MyListFilter.ALL -> R.string.mylist_filter_empty_movies
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = RaddTokens.Space.Step48),
                            )
                            OutlinedButton(
                                onClick = onBrowseClick,
                                modifier = Modifier
                                    .testTag(MyListBrowseButtonTag)
                                    .heightIn(min = 48.dp)
                                    .padding(top = RaddTokens.Space.Sm),
                            ) { Text("Browse the catalog") }
                        }
                    }
                }
            // F12 & Small #9: place the starter shelf inside the scrolling grid as
            // a full-span footer when the trending load produced titles, so compact
            // or scaled screens never clip either the favorites or the suggestions.
            // The shelf is a *starter* shelf: it shows only while the list is
            // small (at most five visible favorites), so a small list shows
            // it the same way in every session instead of depending on
            // whether this ViewModel was born onto one save or two.
            // UI/UX audit Library #5 (Nov 2026): at one save the shelf
            // vanished the moment a second title was saved — a 2-save list
            // lost all suggestions. Five keeps discovery for small lists;
            // the bound stays fixed, so the session-consistency rule above
            // still holds.
            // L1 (round 2): the shelf is a discovery footer for the whole
            // list — under a filter it would show titles the filter just
            // hid, so it stays on All only.
            if (state.filter == MyListFilter.ALL &&
                state.popular.isNotEmpty() && state.favorites.size <= 5
            ) {
                item(
                    key = "my_list_starter_shelf",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    PopularRow(
                        popular = state.popular,
                        onPostClick = onPostClick,
                        isFavorite = isFavorite,
                        onToggleFavorite = { onToggleFavorite(it) },
                        downloadedPostIds = state.downloadedPostIds,
                        horizontalPadding = 0.dp,
                    )
                }
            }
            // L4 (round 2): a failed rail load leaves the inline retry in
            // the footer's place — the shelf needs popular titles to show,
            // and a failure has none, so the card never competes with it.
            if (state.railsLoadFailed && state.popular.isEmpty()) {
                item(
                    key = "my_list_rails_error",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    MyListRailsErrorCard(onRetry = onRetryRails)
                }
            }
            }
        }
    }
}

/**
 * L1 (round 2): the filter chips — All / Movies / Series / Downloads —
 * the brief's filters, on top, with the sort as a visible secondary
 * control at the row's right end: one tap cycles Recently added →
 * Title A–Z → Unwatched first, no sheet. The chips keep the mockup's
 * shape (48dp tall, jade-filled when active, the same shape as
 * [HomeFilterRow]); the sort button names the order in force, so the
 * control shows its state instead of hiding it behind an icon alone.
 * Internal so the mockup test can pin the order, the selected state and
 * the 48dp height without the Hilt ViewModel.
 */
internal const val MyListFilterRowTag = "myListFilterRow"
internal const val MyListSortButtonTag = "myListSortButton"

@Composable
internal fun MyListFilterRow(
    filter: MyListFilter,
    sort: ListSort,
    onFilter: (MyListFilter) -> Unit,
    onSort: (ListSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A4-F3: rides ScrollingChipRow + RaddChip — the fade and the row are
    // the shared component, not a hand-rolled copy. The fade is the scroll
    // cue for the chip strip (S3, verified from the myListPopulated
    // golden: the last chip dims into the edge instead of hard-clipping).
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScrollingChipRow(
            modifier = Modifier.weight(1f),
            rowTestTag = MyListFilterRowTag,
        ) {
            items(MyListFilter.entries) { entry ->
                RaddChip(
                    selected = entry == filter,
                    onClick = { onFilter(entry) },
                    label = {
                        Text(
                            entry.label,
                            fontWeight = if (entry == filter) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.testTag("myListFilterChip_${entry.name}"),
                )
            }
        }
        OutlinedButton(
            onClick = { onSort(nextSort(sort)) },
            modifier = Modifier
                .testTag(MyListSortButtonTag)
                .heightIn(min = 48.dp)
                .padding(start = RaddTokens.Space.Sm)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Sort by ${sort.label}, tap to change"
                },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_action_sort),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(sort.label, maxLines = 1)
        }
    }
}

/** L1 (round 2): the sort button cycles the order — one tap, no sheet. */
fun nextSort(sort: ListSort): ListSort = when (sort) {
    ListSort.RECENT -> ListSort.TITLE
    ListSort.TITLE -> ListSort.UNWATCHED
    ListSort.UNWATCHED -> ListSort.RECENT
}

/**
 * L4 (round 2): the inline rails-retry card — a failed trending/recent
 * load never strands the viewer with a silently missing rail. The card
 * says what failed and offers the retry, on the same tonal surface the
 * designed empty states use. Internal so the mockup test can pin the
 * retry without the Hilt ViewModel.
 */
internal const val MyListRailsRetryTag = "myListRailsRetry"

@Composable
internal fun MyListRailsErrorCard(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = RaddTokens.Space.Gutter,
                vertical = RaddTokens.Space.Sm,
            )
            .clip(RaddTokens.Shape.Card)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(RaddTokens.Space.Lg),
        verticalArrangement = Arrangement.spacedBy(RaddTokens.Space.Sm),
    ) {
        Text(
            text = stringResource(R.string.mylist_rails_failed_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.mylist_rails_failed_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.testTag(MyListRailsRetryTag).heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.mylist_rails_retry)) }
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
     * Titles" button under the copy, to Home (wired in NavGraph, round 2:
     * the brief's "Browse" goes to Home, not Search). The copy alone
     * ("tap the heart…") sent the viewer hunting for a title to save
     * with no way to reach one.
     */
    onBrowseClick: () -> Unit = {},
    /** A4-F5: the second discovery rail — freshest titles, below Trending. */
    recent: List<Post> = emptyList(),
    /** L4 (round 2): the rail load failed — show the inline retry. */
    railsLoadFailed: Boolean = false,
    /** L4 (round 2): the inline retry tap. */
    onRetryRails: () -> Unit = {},
    /** L2 (round 2): the downloaded-copy ids, for the rail badges. */
    downloadedPostIds: Set<Int> = emptySet(),
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
                downloadedPostIds = downloadedPostIds,
                // The loaded grid's contentPadding already leaves 34dp
                // below its last row; the empty state's Column has no such
                // padding, so the rail carries its own — the cards sit
                // clear of the bottom nav instead of tight against it.
                modifier = Modifier.padding(bottom = 16.dp),
            )
        } else if (popularLoading) {
            PosterRailSkeleton(cells = 3, modifier = Modifier.padding(bottom = 16.dp))
        } else if (railsLoadFailed) {
            // L4 (round 2): the rails failed and there is nothing to show
            // — the retry card stands where the rail would have been.
            MyListRailsErrorCard(
                onRetry = onRetryRails,
                modifier = Modifier.padding(bottom = 16.dp),
            )
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
                downloadedPostIds = downloadedPostIds,
                titleRes = R.string.mylist_recent_title,
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
        // S8 (round 2): the last Icons.Default on this screen — the
        // heart outline is the house tab glyph (ic_tab_my_list), the
        // same stroke style as the tab icons. The tint recolors the
        // stroke; the shape is the Material heart path.
        Icon(
            painter = painterResource(R.drawable.ic_tab_my_list),
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
    /** L2 (round 2): the downloaded-copy ids, for the rail badges. */
    downloadedPostIds: Set<Int> = emptySet(),
    modifier: Modifier = Modifier,
    // A4-F5: the rail's heading is a parameter now — the Trending rail
    // keeps its title, the second rail says "Recently Added". The default
    // is the Trending copy, so the loaded grid's starter shelf is unchanged.
    // Screen audit #5 (Nov 2026): neither rail carries a subtitle any
    // more — the Trending one was already dropped in dev-cleanup, and
    // "New titles in the catalog" restated "Recently Added" the same way.
    // A rail heading names the rail; a second line under it says nothing
    // the title does not.
    titleRes: Int = R.string.mylist_popular_title,
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
                    // L2 (round 2): a downloaded rail title wears the
                    // badge too — the rail is where the viewer saves
                    // from, so a copy already on the device says so here.
                    isDownloaded = downloadedPostIds.contains(post.id),
                    // The 3-up cells are too narrow for the full
                    // "★ 8.6 · 2024 · Series" line — it truncated to
                    // "S..." on every card. Rating + year only.
                    compactMeta = true,
                    // UI/UX audit Library #2 (Nov 2026): the caption is
                    // one line, so "Salaar: Part 1 – Ceasefire" truncated
                    // mid-word to "Salaar: Part 1 – …" while the in-card
                    // title already ran to two lines. The search grid
                    // already passes 2 for the same reason (PosterCard
                    // titleMaxLines) — the 3-up rail does the same, so
                    // the caption reads whole and every rail card stays
                    // the same height as its neighbours.
                    titleMaxLines = 2,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
