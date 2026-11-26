package com.raddstream.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
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
import com.raddstream.app.ui.theme.RaddTokens
import com.raddstream.app.viewmodel.ListSort
import com.raddstream.app.viewmodel.MyListFilter
import com.raddstream.app.viewmodel.MyListUiState
import com.raddstream.app.viewmodel.MyListViewModel
import com.raddstream.app.viewmodel.filterMyList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MyListScreen(
    onPostClick: (Post) -> Unit,
    onOpenWatchHistory: () -> Unit = {},
    /** The empty list's way out: the catalog, not a sentence telling the user to go find it. */
    onBrowseClick: () -> Unit = {},
    viewModel: MyListViewModel = hiltViewModel(),
    /**
     * Round 6 motion infra: the shared-transition scopes the NavHost
     * passes in from its SharedTransitionLayout. Null outside navigation
     * (Paparazzi previews, tests) — sharedElement() calls no-op and the
     * screen renders exactly as before. Every PosterCard on this screen
     * carries sharedElement(key = "poster-<workId>").
     */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val popularSettled by viewModel.popularSettled.collectAsState()
    val saveFailed by viewModel.saveFailed.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailedMessage = stringResource(R.string.mylist_save_failed)

    // L3 (round 2): every tap answers — the save toggle ticks the
    // haptic, the same TextHandleMove tick Settings and the Player
    // sheets use for their toggles. The tile's own press already ticks
    // through PressHaptics; this is the control the tile does not own.
    val haptics = LocalHapticFeedback.current
    val onToggleFavoriteWithTick: (Post) -> Unit = { post ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        viewModel.toggleFavorite(post)
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
                    // P2-7 (B-36): History leaves the top bar — a text
                    // button with a clock icon read as a second title
                    // next to "My List". The bar carries one icon-only
                    // action now (the house overflow glyph, the same one
                    // Detail's top bar uses) and History lives one tap
                    // inside the menu. The destination is unchanged —
                    // the same device, the same titles, one tap away in
                    // every list state.
                    var showOverflowMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier
                                .testTag(MyListOverflowButtonTag)
                                .heightIn(min = 48.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_player_more),
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("History") },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_action_history),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onOpenWatchHistory()
                                },
                                modifier = Modifier.testTag(MyListHistoryMenuItemTag),
                            )
                        }
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
        // Round 6 (§4): the pull-to-refresh spring — the list follows the
        // pull with the expressive spatial spring (visible overshoot +
        // settle) instead of staying rigid while the indicator spins. The
        // translation tracks the pull distance through the spring; on
        // release it springs back to rest (the spring overshoots
        // slightly, damping < 1) while the indicator spins. During
        // refresh the content is back at rest. In Paparazzi there is no
        // pull and no refresh, so the translation is 0 and the golden is
        // the settled list.
        val pullState = rememberPullToRefreshState()
        val pullDistancePx = with(LocalDensity.current) { 96.dp.toPx() }
        val pullTranslation by animateFloatAsState(
            targetValue = if (state.isRefreshing) 0f else pullState.distanceFraction * pullDistancePx,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "myListPullTranslation",
        )
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            state = pullState,
            modifier = Modifier.fillMaxSize().padding(contentPadding),
        ) {
            // The spring translation lives on this Box, not on the
            // PullToRefreshBox — the indicator stays pinned at the top
            // while the list below it follows the pull.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = pullTranslation },
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
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
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
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                modifier = Modifier.fillMaxSize(),
            )
            else -> MyListLoadedContent(
                state = state,
                onPostClick = onPostClick,
                onRemove = { viewModel.remove(it) },
                onSort = { viewModel.setSort(it) },
                onFilter = { viewModel.setFilter(it) },
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavoriteWithTick,
                onRetryRails = { viewModel.retryRails() },
                onBrowseClick = onBrowseClick,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                modifier = Modifier.fillMaxSize(),
            )
            }
            } // the pull-to-refresh spring Box
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

/** P2-7 (B-36): the top-bar overflow that carries History now. */
internal const val MyListOverflowButtonTag = "myListOverflowButton"
internal const val MyListHistoryMenuItemTag = "myListHistoryMenuItem"

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
    /**
     * Round 6 (§4): the shared-element scopes — passed to the rails'
     * PosterCards so a rail poster can fly to the Detail hero. Null
     * until the infra wires the SharedTransitionLayout.
     */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
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
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
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
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

/** Round 6 (§4): the remove-item exit duration — the ViewModel removal
 * follows the exit by this long, so the row is seen leaving (fade +
 * scale on the expressive effects spec) instead of vanishing instantly.
 * It covers the effects spring's settle; the snackbar (with Undo)
 * follows the removal, as before. */
private const val MyListRemoveExitDelayMs = 300L

/**
 * The loaded list: the saved count, the sort chips, and the grid.
 * Internal so the mockup test can pin the count and the progress words
 * without the Hilt ViewModel.
 */
@Composable
internal fun MyListLoadedContent(
    state: MyListUiState,
    onPostClick: (Post) -> Unit,
    onRemove: (Int) -> Unit,
    onSort: (ListSort) -> Unit,
    isFavorite: (Post) -> Boolean,
    onToggleFavorite: (Post) -> Unit,
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
    /**
     * Round 6 (§4): the shared-element scopes — passed to the grid's
     * PosterCards and the starter shelf's rail, so a poster can fly to
     * the Detail hero. Null until the infra wires the
     * SharedTransitionLayout.
     */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    // Round 6 (§4): the staggered entrance — each tile enters with the
    // expressive spatial spring from MaterialTheme.motionScheme (fade +
    // scale), staggered by position and capped. The per-item visibility
    // lives in the grid item below, keyed on the filter, so the stagger
    // re-runs on a filter change, not just on first load. Inspection
    // mode (Paparazzi, previews) renders the final state with no
    // animation: a golden must pin the settled grid, and no golden
    // proves motion anyway.
    val inPreview = LocalInspectionMode.current
    // Round 6 (§4): the ids that have completed their entrance. The set
    // lives here (not in the item), keyed on the filter, so scrolling
    // away and back does not replay an entrance — the id is already in
    // the set — and a filter change resets it, so the stagger re-runs
    // for the new set.
    var enteredIds by remember(state.filter) { mutableStateOf(setOf<Int>()) }
    // Round 6 (§4): the remove-item exit — the ids currently animating
    // out. Tapping × marks the row removing (it fades + scales out on
    // the expressive effects spec) and the ViewModel removal follows
    // after the exit, so the row is seen leaving instead of vanishing.
    // The grid reflows with the spring placement in animateItem below.
    var removingIds by remember { mutableStateOf(setOf<Int>()) }
    val removalScope = rememberCoroutineScope()
    val onRemoveWithExit: (Int) -> Unit = { id ->
        if (id !in removingIds) {
            removingIds = removingIds + id
            removalScope.launch {
                delay(MyListRemoveExitDelayMs)
                onRemove(id)
                removingIds = removingIds - id
            }
        }
    }
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
                    // Round 6 (§4): the grid stagger — each tile enters
                    // with the expressive spatial spring (fade + scale),
                    // staggered by position and capped at 400ms. The
                    // delay lives in this effect (a spring spec carries
                    // no delay), and the entered set lives at the parent
                    // level, so scrolling away and back does not replay
                    // the entrance — the id is already in the set. In
                    // Paparazzi the tile renders settled (no animation).
                    val isEntered = post.id in enteredIds
                    LaunchedEffect(filter) {
                        if (inPreview) return@LaunchedEffect
                        if (post.id in enteredIds) return@LaunchedEffect
                        delay(minOf(index * 40, 400).toLong())
                        enteredIds = enteredIds + post.id
                    }
                    AnimatedVisibility(
                        visible = (isEntered || inPreview) && post.id !in removingIds,
                        enter = fadeIn(
                            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        ) + scaleIn(
                            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            initialScale = 0.96f,
                        ),
                        exit = fadeOut(
                            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        ) + scaleOut(
                            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            targetScale = 0.9f,
                        ),
                        // Round 6 (§4): the grid reflows with the spring
                        // placement when a row leaves (or a filter
                        // changes) — the remaining tiles glide to their
                        // new positions on the expressive spatial spec
                        // instead of snapping.
                        modifier = Modifier.animateItem(
                            fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        ),
                    ) {
                        PosterCard(
                            post = post,
                            onClick = { onPostClick(post) },
                            onRemove = { onRemoveWithExit(post.id) },
                            // P2-7 (B-36): the tile is a doorway again —
                            // no play button on it. The tile carried play
                            // + remove × + download badge + progress at
                            // once; the tap opens the title and Play lives
                            // on Detail, so the button bought one saved
                            // tap at the cost of the busiest tile in the
                            // app. Home's rail tiles keep theirs.
                            // L2 (round 2): the download badge — a verified
                            // copy on the device, from the keep store.
                            isDownloaded = state.downloadedPostIds.contains(post.id),
                            // P1-3 (B-10): a downloaded badge may land on any
                            // tile in this grid, so the slot stays reserved
                            // and the grid keeps uniform heights.
                            reserveBadgeSlot = true,
                            // The bar paints only where a real watch row
                            // says the viewer stopped halfway — never a
                            // bar for a title with no position (see
                            // MyListViewModel.myListProgressByPostId) — and
                            // the words travel with it now ("S2:E5 · 23 min
                            // left"), so the tile speaks its progress
                            // instead of painting a silent grey bar.
                            progressFraction = state.progressByPostId[post.id],
                            progressLabel = state.progressLabelByPostId[post.id],
                            // Round 5: at large font scale a one-line title
                            // degrades to a single word ("Salaar:…") — the
                            // grid tiles take a third title line, so the
                            // full title reads; the slot grows with it, so
                            // cards stay uniform with each other. At 1.0x
                            // and 1.15x the one-line slot is unchanged.
                            // Two lines are not enough here: the × beside
                            // the title leaves a ~110dp text column, and at
                            // 1.3x "Salaar: Part 1 – Ceasefire" needs three
                            // ("Salaar: Part" / "1 –" / "Ceasefire") — the
                            // two-line attempt still cut "Ceasefire" (record
                            // 45605943675). The Search grid's wider tiles do
                            // read the full title on two lines.
                            // (Round 3 tried maxLines = 2 alone and it did
                            // nothing: the exact-height slot in PosterCard
                            // capped the layout to one line — fixed there
                            // first, with heightIn(min = …), so the extra
                            // lines can actually lay out.)
                            titleMaxLines = if (LocalDensity.current.fontScale > 1.15f) 3 else 1,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
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
                        // Round 6 (§4): the filtered-empty entrance — it
                        // fades/scales in on the expressive effects spec
                        // instead of appearing instantly. Keyed on the
                        // filter, so it re-enters on each filter change.
                        // In Paparazzi it renders settled (no animation).
                        var emptyVisible by remember(filter) { mutableStateOf(inPreview) }
                        LaunchedEffect(filter) {
                            if (inPreview) return@LaunchedEffect
                            emptyVisible = true
                        }
                        AnimatedVisibility(
                            visible = emptyVisible,
                            enter = fadeIn(
                                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            ) + scaleIn(
                                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                                initialScale = 0.96f,
                            ),
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
                }
            // P2-8 (B-37): no starter shelf in the loaded grid. The
            // full-span PopularRow footer dominated a nearly-empty list
            // (at most five saves it was often taller than the list
            // itself). Discovery at zero saves lives where it belongs —
            // the empty state's Popular + Recent rails, which stay
            // exactly as they are — and a list with saves in it is just
            // the list. The rails-error card below is the only footer
            // left, and only when the rail load failed with no titles.
            // L4 (round 2): a failed rail load leaves the inline retry as
            // the grid's footer — the retry needs no popular titles, and
            // a failure has none, so the card never competes with a rail.
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
    /**
     * Round 6 (§4): the shared-element scopes — passed to the rails'
     * PosterCards so a rail poster can fly to the Detail hero. Null
     * until the infra wires the SharedTransitionLayout.
     */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
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
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
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
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
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
        // S8 (round 2): the last Material default icon on this screen — the
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
    /**
     * Round 6 (§4): the shared-element scopes — passed to each rail
     * PosterCard so a rail poster can fly to the Detail hero. Null
     * until the infra wires the SharedTransitionLayout.
     */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
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
                    // P1-3 (B-10): same reservation as the grid above —
                    // a badge may land on any tile in this rail.
                    reserveBadgeSlot = true,
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
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
