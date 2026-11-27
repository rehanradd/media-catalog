package com.raddstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.R
import com.raddstream.app.network.models.LiveTvChannel
import com.raddstream.app.network.models.WireText
import com.raddstream.app.ui.components.CatalogImage
import com.raddstream.app.ui.components.EmptyState
import com.raddstream.app.ui.components.ErrorState
import com.raddstream.app.ui.components.LiveTvLoadingSkeleton
import com.raddstream.app.ui.components.RaddChip
import com.raddstream.app.ui.components.ScrollingChipRow
import com.raddstream.app.ui.components.channelMonogram
import com.raddstream.app.ui.components.isKnownLanguage
import com.raddstream.app.ui.components.liveTvLogoPrefetchUrls
import com.raddstream.app.ui.components.prefetchPosterArt
import com.raddstream.app.ui.components.rememberDelayedVisible
import com.raddstream.app.ui.components.raddFilterChipColors
import com.raddstream.app.ui.layout.CardGeometry
import com.raddstream.app.viewmodel.LiveTvViewModel
import com.raddstream.app.viewmodel.formatCacheAge
import kotlinx.coroutines.launch
import com.raddstream.app.ui.theme.RaddTokens

/**
 * The Live TV tab: a category-first channel grid, in the same shape as the
 * catalog grids (category chips on top, a logo grid below, search inside the
 * tab). There is no EPG time-grid in v1 — no EPG data source is wired for
 * these channels, and a grid of invented times would be a lie. Favourites
 * are the channels-side kind: a star on the card, a "Favourites" chip at
 * the head of the row, and a tiny Room table keyed by the registry id —
 * not the posts system, because a channel is not a post and has no handle
 * to sync.
 *
 * A tap plays the channel through the EXISTING player, addressed by the
 * server's opaque `stream_id` handle — the same route a movie uses. No raw
 * URL and no host name is ever shown, stored or routed here. A channel the
 * server reports dead answers the player's own unavailable state; nothing
 * on this screen ever fakes a play.
 *
 * Stitch mockup (Nov 2026): the search field is the app's shared filled
 * pill, the chips are the shared jade-selected filter chips, and the cards
 * are 16dp. The mockup's invented channel names
 * and ALL-CAPS nav labels were never copied — the data is the server's,
 * and the nav stays title case.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTvScreen(
    onPlayChannel: (streamId: String, title: String) -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LiveTvContent(
        state = state,
        onRefresh = { viewModel.refresh() },
        onQueryChanged = viewModel::onQueryChanged,
        onCategorySelected = viewModel::onCategorySelected,
        onFavouritesSelected = viewModel::onFavouritesSelected,
        onToggleFavorite = { channel -> viewModel.toggleFavorite(channel.id) },
        onChannelClick = { channel ->
            // Batch C: warm the channel's link in parallel
            // with the navigation, so the player 302s from
            // the banked answer instead of paying the live
            // resolve inside "Finding your video".
            viewModel.warmChannel(channel.streamId)
            // A channel is always HLS: stage the manifest MIME
            // so the player declares it before the first frame
            // instead of opening the playlist with the
            // progressive reader (Robo #4).
            viewModel.stageLiveChannel(channel.streamId)
            onPlayChannel(channel.streamId, channel.name)
        },
    )
}

/**
 * The Live TV body, split from the screen so tests can render it with a
 * fake state (the Home pattern: HomeScreen + HomeContent). No behaviour
 * change here — the ViewModel calls live in [LiveTvScreen]'s delegation
 * above, so this function is pure state in, taps out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveTvContent(
    state: LiveTvViewModel.UiState,
    onRefresh: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onFavouritesSelected: (Boolean) -> Unit,
    onToggleFavorite: (LiveTvChannel) -> Unit,
    onChannelClick: (LiveTvChannel) -> Unit,
) {

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                // CL-7, same as Settings: the title says "Live TV" and so does
                // its nav tab — the title is paint, not a second announcement.
                // Live TV review (Nov 2026): a bolder display weight — the
                // modest title read as a list heading, not a screen title.
                title = {
                    Text(
                        "Live TV",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                },
                actions = {
                    // Live TV review D3: a channel that dies mid-session
                    // needs a re-fetch without killing the app — the same
                    // pattern as Downloads. While a fetch runs the action
                    // is a spinner, not a dead tap: that is also the
                    // "refreshing…" affordance for the cache-first paint
                    // (D1), which puts yesterday's list on screen while
                    // the fresh one loads behind it.
                    IconButton(
                        onClick = { onRefresh() },
                        enabled = !state.isLoading,
                        modifier = Modifier.semantics { contentDescription = "Refresh channels" },
                    ) {
                        // S5: a refresh that lands in under 250ms never
                        // flashes a spinner in the button — the static icon
                        // stays until the wait proves long, and a spinner
                        // that does appear stays up 500ms so it never
                        // flickers off in the same beat.
                        val showSpinner = rememberDelayedVisible(state.isLoading)
                        if (showSpinner) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(painterResource(R.drawable.ic_action_refresh), contentDescription = null)
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        when {
            state.isLoading && state.channels.isEmpty() -> LiveTvLoadingSkeleton(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
            // Audit #13: a down server with a saved list still shows the
            // list (with a notice, below) — the error owns the screen only
            // when there is nothing at all to show.
            state.error != null && state.channels.isEmpty() -> ErrorState(
                title = "Live TV isn't available",
                message = state.error ?: "Could not load live channels.",
                onRetry = { onRefresh() },
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
            state.channels.isEmpty() -> EmptyState(
                title = "No live channels right now",
                message = "Live channels will appear here when they are available.",
                // Screenshot-fix D3 (2026-11-16): the empty tab photographed
                // as a bare black void — no message, no illustration. The
                // state gets a TV mark and a Retry, so an empty list reads
                // as a state, never as a broken screen.
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_action_tv),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                },
                action = {
                    // Live TV review D6: the empty state's only way out
                    // sits on the 48dp floor with every other control.
                    Button(
                        shape = RaddTokens.Shape.Pill,
                        onClick = { onRefresh() },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Retry") }
                },
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
            else -> Column(Modifier.fillMaxSize().padding(contentPadding)) {
                // Audit #13/#14: the cached list says so, in plain words,
                // with one tap to retry — never a raw code, never a dead tab.
                if (state.error != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (state.showingCached) {
                                // Live TV review D5: the notice says how
                                // old the saved list is — "Saved 3 hours
                                // ago" — instead of warning without
                                // informing. A cache written before the
                                // timestamp existed falls back to the
                                // wording it always had.
                                val age = state.cachedAtMillis?.let {
                                    formatCacheAge(it, System.currentTimeMillis())
                                }
                                if (age != null) {
                                    "Saved $age — it may be out of date. ${state.error}"
                                } else {
                                    "Showing the last channel list — it may be out of date. ${state.error}"
                                }
                            } else {
                                state.error ?: "Could not load live channels."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        // Live TV review D6: the cached-notice Retry sits
                        // on the 48dp floor with every other control.
                        TextButton(
                            onClick = { onRefresh() },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Retry") }
                    }
                }
                // The search field is the app's shared filled pill (SearchScreen):
                // Live TV reuses it so the two search fields can never drift
                // apart. The prompt is a PLACEHOLDER only (audit #33) and the
                // accessible name rides on semantics. Live TV has no submit
                // action — the list filters as you type — so the search key
                // only dismisses the keyboard.
                SearchField(
                    query = state.query,
                    onQueryChanged = onQueryChanged,
                    onSubmit = {},
                    label = "Filter channels",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LiveTvCategoryRow(
                    categories = state.categories,
                    selectedCategory = state.selectedCategory,
                    onCategorySelected = onCategorySelected,
                    // E2 slice 1: the Favourites chip owns the grid while
                    // selected — only starred channels show, in starred order.
                    showFavoritesOnly = state.showFavoritesOnly,
                    onFavouritesSelected = onFavouritesSelected,
                    // A4-F2: the row owns the "All"-while-searching state —
                    // the query rides here as a removable pill, and "All"
                    // dims while it is up (see LiveTvCategoryRow).
                    query = state.query,
                    onQueryCleared = { onQueryChanged("") },
                )
                // Live TV review D7: the biggest list in the app says how
                // big it is — "759 channels" unfiltered, "12 of 759
                // channels" once a filter cuts it. A4-F2: a section
                // subheading, not floating grey body text — the count is
                // the section's own header, so it reads at the same weight
                // as the category row above it.
                if (state.visibleChannels.isNotEmpty()) {
                    LiveTvChannelCountHeading(
                        visible = state.visibleChannels.size,
                        total = state.channels.size,
                        filtered = state.query.isNotBlank() || state.selectedCategory != null ||
                            state.showFavoritesOnly,
                    )
                }
                if (state.visibleChannels.isEmpty()) {
                    // E2 slice 1: "No favourites yet" is only the
                    // nothing-starred case — a starred list narrowed by a
                    // query that matches nothing is still "No channels
                    // match", with the search to clear.
                    val noFavouritesYet = state.showFavoritesOnly &&
                        state.favoriteChannelIds.isEmpty()
                    EmptyState(
                        title = if (noFavouritesYet) {
                            "No favourites yet"
                        } else {
                            "No channels match"
                        },
                        message = if (noFavouritesYet) {
                            "Tap the star on a channel to keep it here."
                        } else if (state.query.isNotBlank()) {
                            "Nothing matching \"${state.query}\" is on live TV right now."
                        } else if (state.showFavoritesOnly) {
                            // Starred ids exist but none is in today's list —
                            // the registry dropped them, not the viewer.
                            "Your starred channels aren't on live TV right now."
                        } else {
                            "Nothing in this category is on live TV right now."
                        },
                        // Live TV review D4: a dead end one tap away from
                        // the full list gets the tap — "Clear search" when
                        // a query is set, "Show all channels" when a
                        // category is set.
                        action = {
                            if (noFavouritesYet) {
                                Button(
                                    shape = RaddTokens.Shape.Pill,
                                    onClick = { onFavouritesSelected(false) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Show all channels") }
                            } else if (state.query.isNotBlank()) {
                                Button(
                                    shape = RaddTokens.Shape.Pill,
                                    onClick = { onQueryChanged("") },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Clear search") }
                            } else if (state.showFavoritesOnly) {
                                Button(
                                    shape = RaddTokens.Shape.Pill,
                                    onClick = { onFavouritesSelected(false) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Show all channels") }
                            } else if (state.selectedCategory != null) {
                                Button(
                                    shape = RaddTokens.Shape.Pill,
                                    onClick = { onCategorySelected(null) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Show all channels") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LiveTvChannelGrid(
                        channels = state.visibleChannels,
                        // E2 slice 1: the star state rides with the card,
                        // and the tap writes Room through the ViewModel.
                        favoriteChannelIds = state.favoriteChannelIds,
                        onFavoriteClick = { channel -> onToggleFavorite(channel) },
                        onChannelClick = onChannelClick,
                    )
                }
            }
        }
    }
}

/** "759 channels" unfiltered, "12 of 759 channels" once a filter cuts the list. */
internal fun liveTvChannelCountText(visible: Int, total: Int, filtered: Boolean): String {
    val totalText = if (total == 1) "1 channel" else "$total channels"
    if (!filtered) return totalText
    return "${if (visible == 1) "1" else "$visible"} of $totalText"
}

/**
 * A4-F2: the channel count as the grid's section subheading — titleSmall,
 * semibold, on-surface — not floating grey body text. It carries the
 * heading role so a screen reader lands on it as the section's header.
 * Extracted so the style is pinned without the Hilt screen around it.
 */
@Composable
internal fun LiveTvChannelCountHeading(
    visible: Int,
    total: Int,
    filtered: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = liveTvChannelCountText(visible, total, filtered),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("liveTv_channelCount")
            .semantics { heading() },
    )
}

/**
 * The channel grid with its back-to-top affordance, extracted so the
 * scroll behaviour is unit-testable without the Hilt screen around it
 * (see LiveTvMockupTest). Live TV review D8: once the first row has
 * scrolled off, a "Back to top" button appears bottom-end; tapping it
 * animates back to the first row. The grid itself is lazy with stable
 * keys — 759 rows do not all compose — the button only shortens the
 * drag home.
 *
 * P2-9 (B-40): there is no end-of-results footer — the old A4-F5 footer
 * ("End of results · 1 of 759 channels") duplicated the count heading
 * above the grid, so it is gone and the list just ends. The way back to
 * the full list is the chip row (query pill ×, "All", Favourites
 * toggle), not a footer button.
 */
@Composable
internal fun LiveTvChannelGrid(
    channels: List<LiveTvChannel>,
    onChannelClick: (LiveTvChannel) -> Unit,
    modifier: Modifier = Modifier,
    /** Starred channel ids — a card draws its star filled when its id is in here. */
    favoriteChannelIds: Set<Int> = emptySet(),
    /** The star tap — never the card tap: starring must not play the channel. */
    onFavoriteClick: (LiveTvChannel) -> Unit = {},
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // Video audit issue 10 (Nov 2026): warm the grid's logos the moment
    // the channel list is known — a logo that 429s on the wsrv proxy used
    // to arrive ~10 s late, after the grid had already painted its
    // fallback. The prefetch bypasses the proxy, like the tile itself.
    val prefetchContext = LocalContext.current
    LaunchedEffect(channels) {
        val urls = liveTvLogoPrefetchUrls(channels)
        if (urls.isNotEmpty()) prefetchPosterArt(prefetchContext, urls, bypassResizeProxy = true)
    }
    val showBackToTop by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            // Video-audit #24/#36 (Nov 2026): Live TV gets its own column
            // rule, not the shared 100dp one — on a 360dp phone the shared
            // rule laid three ~101dp columns, and names cut mid-word after
            // ~10 characters ("Discover …", "Al Jazeer…"). At 160dp a
            // narrow phone gets two ~158dp columns, so names + the star fit;
            // wider screens still gain columns. Browse / My List keep the
            // shared rule — this is a Live TV fix, not a grid redesign.
            columns = GridCells.Adaptive(minSize = 160.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().testTag("liveTv_grid"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(channels, key = { it.id }) { channel ->
                LiveTvChannelCard(
                    channel = channel,
                    onClick = { onChannelClick(channel) },
                    isFavorite = channel.id in favoriteChannelIds,
                    onFavoriteClick = { onFavoriteClick(channel) },
                )
            }
        }
        if (showBackToTop) {
            IconButton(
                onClick = { scope.launch { gridState.animateScrollToItem(0) } },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .testTag("liveTv_backToTop")
                    .semantics { contentDescription = "Back to top" },
            ) {
                Icon(painterResource(R.drawable.ic_action_keyboard_arrow_up), contentDescription = null)
            }
        }
    }
}

/**
 * The category chips, extracted so their order and selected state are
 * unit-testable (see LiveTvMockupTest): "Favourites" first, then "All",
 * then the server's categories in the order they arrived. The row scrolls
 * when the list is long — the mockup's five chips happen to fit, but the
 * registry can hold more, and a clipped chip is worse than a scrollable
 * row. The selected chip is the shared jade fill ([raddFilterChipColors]);
 * the unselected chips keep the default thin border, as the mockup draws
 * them. The row shares the [ScrollingChipRow] right-edge fade so the last
 * chip fades out instead of hard-clipping mid-word.
 *
 * A4-F2: while a text search is active the query is the filter, not "All"
 * — "All" loses its selected fill and dims to half alpha, and the query
 * itself rides first as a removable jade pill ("Discover Pakistan ×").
 * Tapping the pill clears the query; tapping "All" still clears only the
 * category, exactly as before.
 *
 * E2 slice 1: "Favourites" is its own view, not a category — it sits at
 * the head of the row, and while it is selected "All" and the categories
 * lose their fill (a category tap leaves the favourites view, see
 * [LiveTvViewModel.onCategorySelected]).
 */
@Composable
internal fun LiveTvCategoryRow(
    categories: List<String>,
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    query: String = "",
    onQueryCleared: () -> Unit = {},
    showFavoritesOnly: Boolean = false,
    onFavouritesSelected: (Boolean) -> Unit = {},
) {
    val searchActive = query.isNotBlank()
    // A4-F3: rides ScrollingChipRow now — the fade and the row are the
    // shared component, not a hand-rolled copy. The row is full-width
    // with the default horizontal-Gutter content padding, so the first
    // chip still starts 16dp in (the same start the old outer padding
    // gave it) — but the clip and the fade now sit at the screen edge.
    // Re-rate 10 residual defect: the old outer horizontal padding
    // clipped the row 16dp early, so the next chip's glyph landed
    // exactly on the clip edge and only its container showed through
    // the fade (+4, invisible to the eye). With the clip at the edge
    // the glyph peeks through like Home's KPOP (+13). RowEndFade
    // itself is untouched — the 92% cap and the pinned 48dp stay.
    ScrollingChipRow(
        modifier = modifier.padding(vertical = 4.dp),
    ) {
        if (searchActive) {
            item {
                RaddChip(
                    colors = raddFilterChipColors(),
                    selected = true,
                    onClick = onQueryCleared,
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = query.trim(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                painterResource(R.drawable.ic_action_close),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    // P2-chip2027: no selected check here — this chip is a
                    // clear action wearing the selected fill, not a filter
                    // selection; a check beside its × would fight it.
                    showSelectedCheck = false,
                    // The chip itself is 48dp tall, like "All" below.
                    modifier = Modifier.testTag("liveTv_queryPill")
                        .semantics { contentDescription = "Clear search for ${query.trim()}" },
                )
            }
        }
        item {
            RaddChip(
                colors = raddFilterChipColors(),
                selected = showFavoritesOnly,
                onClick = { onFavouritesSelected(!showFavoritesOnly) },
                label = { Text("Favourites") },
                // Live TV review (Nov 2026): the chip itself is 48dp
                // tall, like "All" below.
                modifier = Modifier.testTag("liveTv_chip_favourites"),
            )
        }
        item {
            RaddChip(
                colors = raddFilterChipColors(),
                selected = selectedCategory == null && !searchActive && !showFavoritesOnly,
                onClick = { onCategorySelected(null) },
                label = { Text("All") },
                // Live TV review (Nov 2026): the chip itself is 48dp
                // tall, not just its touch target — the 32dp visible
                // chip read as shallow next to the search pill, and
                // Home's filter row is 48dp. A4-F2: half alpha while
                // a text search is active, so the jade fill never
                // claims a filter the query already owns.
                modifier = Modifier
                    .alpha(if (searchActive) 0.5f else 1f)
                    .testTag("liveTv_chip_all"),
            )
        }
        items(categories) { category ->
            RaddChip(
                colors = raddFilterChipColors(),
                selected = selectedCategory == category,
                onClick = { onCategorySelected(category) },
                label = { Text(categoryLabel(category)) },
                // Live TV review (Nov 2026): same 48dp visible height
                // as the "All" chip above.
                modifier = Modifier.testTag("liveTv_chip_$category"),
            )
        }
    }
}

/** The server's category id ("news") as a chip label ("News"). */
internal fun categoryLabel(category: String): String =
    category.replaceFirstChar { it.uppercase() }

/**
 * The server's country code as a readable name. The registry writes ISO
 * codes ("PK", "IN") and "INTL" for the international set — two-letter
 * codes on a card are alphabet soup, and a viewer should not need a
 * geography lesson to know which country's channel this is. Unknown codes
 * pass through untouched, never guessed at — except the literal words
 * "Unknown" / "N/A", which mean the country was never recorded: those
 * return null so the meta line drops the segment instead of printing
 * "· Unknown" on the card.
 */
internal fun countryLabel(code: String?): String? {
    if (code.isNullOrBlank()) return null
    if (WireText.isPlaceholder(code)) return null
    return when (code.trim().uppercase()) {
        "PK" -> "Pakistan"
        "IN" -> "India"
        "INTL" -> "International"
        "US" -> "USA"
        "GB", "UK" -> "UK"
        "AE" -> "UAE"
        "SA" -> "Saudi Arabia"
        "BD" -> "Bangladesh"
        "LK" -> "Sri Lanka"
        "NP" -> "Nepal"
        "AF" -> "Afghanistan"
        "TR" -> "Turkey"
        "IR" -> "Iran"
        "EG" -> "Egypt"
        else -> code.trim()
    }
}

/**
 * The server's language code as a readable name. The registry writes ISO
 * 639 codes ("ur", "hi") and sometimes the full word ("urdu") — two-letter
 * codes on a card are alphabet soup, and a viewer should not need a
 * language lesson to know which language a channel speaks. Mirrors
 * [countryLabel]: known codes map to names, unknown codes pass through
 * untouched, never guessed at.
 */
internal fun languageLabel(code: String?): String? {
    if (code.isNullOrBlank()) return null
    return when (code.trim().lowercase()) {
        "ur", "urdu" -> "Urdu"
        "hi", "hindi" -> "Hindi"
        "en", "english" -> "English"
        "pa", "punjabi" -> "Punjabi"
        "ta", "tamil" -> "Tamil"
        "te", "telugu" -> "Telugu"
        "bn", "bengali", "bangla" -> "Bengali"
        "ml", "malayalam" -> "Malayalam"
        "kn", "kannada" -> "Kannada"
        "mr", "marathi" -> "Marathi"
        "gu", "gujarati" -> "Gujarati"
        "as", "assamese" -> "Assamese"
        "or", "odia", "oriya" -> "Odia"
        "ar", "arabic" -> "Arabic"
        "zh", "chinese", "mandarin" -> "Chinese"
        "ne", "nepali" -> "Nepali"
        "it", "italian" -> "Italian"
        "pt", "portuguese" -> "Portuguese"
        "ru", "russian" -> "Russian"
        "es", "spanish" -> "Spanish"
        "fr", "french" -> "French"
        "tl", "tagalog" -> "Tagalog"
        "sd", "sindhi" -> "Sindhi"
        else -> code.trim()
    }
}

/**
 * Video-audit #36 (Nov 2026): the tile's ONE displayed meta line — category
 * + language only ("News · Urdu"). The full triple (category · country ·
 * language) is what [channelMetaLine] builds for the card's spoken
 * sentence, but on the tile it wrapped to 2–3 lines and the language — the
 * segment that tells two same-name channels apart — was the part that
 * vanished under the ellipsis. The tile shows the short line; the spoken
 * sentence keeps the full one. Pure, so the shapes are pinned in a unit test.
 */
internal fun channelShortMetaLine(channel: LiveTvChannel): String =
    listOfNotNull(
        categoryLabel(channel.category).takeIf { it.isNotBlank() },
        channel.language.trim()
            .takeIf(::isKnownLanguage)
            ?.let(::languageLabel),
    )
        .distinctBy { it.lowercase() }
        .joinToString(" · ")

internal fun channelMetaLine(channel: LiveTvChannel): String =
    listOfNotNull(
        categoryLabel(channel.category).takeIf { it.isNotBlank() },
        countryLabel(channel.country),
        channel.language.trim()
            .takeIf(::isKnownLanguage)
            ?.let(::languageLabel),
    )
        .distinctBy { it.lowercase() }
        .joinToString(" · ")

@Composable
internal fun LiveTvChannelCard(
    channel: LiveTvChannel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** True when this channel is starred — the star draws filled. */
    isFavorite: Boolean = false,
    /** The star tap — a nested click, so starring never plays the channel. */
    onFavoriteClick: () -> Unit = {},
) {
    val metaLine = channelMetaLine(channel)
    // Robo run 2: every card announced its name three times (logo description,
    // name text, and the "LIVE" badge on every card) — the same words on all
    // ~18 channels. One sentence per card; the logo, badge and texts are paint.
    // E2 slice 1: a starred card says so in its sentence, so a screen-reader
    // viewer hears the star without hunting for it.
    val a11y = listOfNotNull(
        "${channel.name}, live",
        "starred".takeIf { isFavorite },
        metaLine.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    Card(
        onClick = onClick,
        // Mockup (Stitch, Nov 2026): 16dp corners, no elevation — the card
        // is one surface tier above the background, not a shadow. Live TV
        // review (Nov 2026): the 1dp hairline outline is gone — the
        // wireframe look it gave the grid is fixed by tonal elevation
        // alone, the same call as the Home PosterCard rework. The logo
        // plate stays surfaceVariant, one tier above the card fill, so the
        // logo reads as a plate, not a hole.
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.semantics { contentDescription = a11y },
    ) {
        Column {
            Box(
                // F11 (Batch B): logos are letterboxed, not cropped. The old
                // 16:9 Crop box cut square logos down to a strip and stretched
                // wide ones, so no two cards' logos were the same size. Fit
                // shows every logo whole on one neutral plate.
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(CardGeometry.WIDE_ART_ASPECT)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                CatalogImage(
                    url = channel.logoUrl,
                    contentDescription = null, // the card sentence says the name
                    fallbackLabel = channel.name,
                    // Phase 7 (M4): channel logos come from the operator-curated
                    // channel registry, not the catalog — their hosts are the
                    // registry's own, and the art policy does not vet them.
                    allowAnyHost = true,
                    // Video audit issue 10 (Nov 2026): bypass the wsrv.nl
                    // resize proxy for logos — imgur rate-limits wsrv's
                    // fetcher (429), so a proxied logo paid a ~10 s dead
                    // wait before the fallback interceptor retried it
                    // direct. The prefetch warms this same direct URL.
                    bypassResizeProxy = true,
                    contentScale = ContentScale.Fit,
                    // Live TV review (Nov 2026): the logo lives in a centred
                    // safe zone — a wide logo can no longer reach the corner
                    // where the LIVE badge sits, and a white-background asset
                    // reads as a badge, not a pasted rectangle.
                    // Video audit issue 10: the zone shrank to 50% and
                    // anchors to the plate's bottom, so the logo's top
                    // edge clears the LIVE badge (edge-to-edge art like
                    // ABC News had its worm under the badge, and the
                    // wordmark's tail under the star). The 48dp star disc
                    // still overlaps the logo's top-right corner — full
                    // clearance needs the star moved or a taller plate,
                    // which belong to the Live TV tile batch, not this one.
                    fallbackLarge = true,
                    // A4-F1: the short monogram beside the mark — the full
                    // name under a 48dp mark overflowed the ~64dp-tall
                    // plate, so the Robo capture showed the mark with no
                    // text at all. The card below already prints the name.
                    monogram = channelMonogram(channel.name),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxSize(0.5f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            // Mockup: the LIVE badge is a pill, not a square.
                            RoundedCornerShape(50),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .clearAndSetSemantics { },
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                MaterialTheme.colorScheme.error,
                                CircleShape,
                            ),
                    )
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // Video-audit #15 (Nov 2026): the star lived here, top-end
                // of the logo plate — and covered the logo's corner on every
                // wide logo (the plate is ~101dp wide at three columns, the
                // logo safe zone reaches 80% of it, the 48dp star spans the
                // corner). It now rides the name row below: same nested
                // click, same 48dp target, no logo behind it, so it can
                // cover nothing.
            }
            // Video-audit #15/#24/#36 (Nov 2026): the name rides with the
            // star in one row, off the logo plate. The name is smaller
            // text (12sp, the user's direction) and wraps to two lines, so
            // "Al Jazeera English" and "Al Jazeera Arabic" read as two
            // channels instead of one duplicated "Al Jazeer…" card.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodySmall,
                    // Mockup: the channel name is bold, not medium.
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp).clearAndSetSemantics { },
                )
                // E2 slice 1: the star is a nested click inside the card:
                // tapping it stars the channel and never plays it. The
                // filled star takes the Detail favourite's secondary tint,
                // so a starred card reads starred at a glance without a
                // second colour language. No disc background here — the
                // disc existed only to keep the mark legible over logo art,
                // and the card surface behind it needs none.
                IconButton(
                    onClick = onFavoriteClick,
                    modifier = Modifier.size(48.dp)
                        .testTag("liveTv_favorite_${channel.id}")
                        .semantics {
                            contentDescription = if (isFavorite) {
                                "Unstar ${channel.name}"
                            } else {
                                "Star ${channel.name}"
                            }
                        },
                ) {
                    Icon(
                        painter = painterResource(if (isFavorite) R.drawable.ic_action_star_filled else R.drawable.ic_action_star),
                        contentDescription = null,
                        tint = if (isFavorite) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            Text(
                text = channelShortMetaLine(channel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Video-audit #36: ONE short meta line, not two — the tile
                // carries name + one line only, smaller text, uniform size.
                // The full triple survives in the card's spoken sentence.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The tag MUST sit before clearAndSetSemantics: the clear
                // wipes every semantics set after it in the chain, so a tag
                // after it is never on the node at all — the wrap test
                // failed twice on "could not find any node" (CI 44236763707,
                // 44238820887) before this order was fixed. Before the
                // clear, the tag survives and the text stays paint-only.
                modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp)
                    .testTag("liveTv_channelMeta").clearAndSetSemantics { },
            )
        }
    }
}
