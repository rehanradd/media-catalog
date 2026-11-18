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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.network.models.LiveTvChannel
import com.raddstream.app.ui.components.CatalogGridColumns
import com.raddstream.app.ui.components.CatalogImage
import com.raddstream.app.ui.components.EmptyState
import com.raddstream.app.ui.components.ErrorState
import com.raddstream.app.ui.components.LiveTvLoadingSkeleton
import com.raddstream.app.ui.components.channelMonogram
import com.raddstream.app.ui.components.isKnownLanguage
import com.raddstream.app.ui.components.raddFilterChipColors
import com.raddstream.app.ui.layout.CardGeometry
import com.raddstream.app.viewmodel.LiveTvViewModel
import com.raddstream.app.viewmodel.formatCacheAge
import kotlinx.coroutines.launch

/**
 * The Live TV tab: a category-first channel grid, in the same shape as the
 * catalog grids (category chips on top, a logo grid below, search inside the
 * tab). There is no EPG time-grid in v1 — no EPG data source is wired for
 * these channels, and a grid of invented times would be a lie — and no
 * favourites yet, because favourites are a posts system, not a channels one.
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
                        onClick = { viewModel.refresh() },
                        enabled = !state.isLoading,
                        modifier = Modifier.semantics { contentDescription = "Refresh channels" },
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null)
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
                onRetry = { viewModel.refresh() },
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
                        Icons.Default.Tv,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                },
                action = {
                    // Live TV review D6: the empty state's only way out
                    // sits on the 48dp floor with every other control.
                    Button(
                        onClick = { viewModel.refresh() },
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
                            onClick = { viewModel.refresh() },
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
                    onQueryChanged = viewModel::onQueryChanged,
                    onSubmit = {},
                    label = "Filter channels",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LiveTvCategoryRow(
                    categories = state.categories,
                    selectedCategory = state.selectedCategory,
                    onCategorySelected = viewModel::onCategorySelected,
                    // A4-F2: the row owns the "All"-while-searching state —
                    // the query rides here as a removable pill, and "All"
                    // dims while it is up (see LiveTvCategoryRow).
                    query = state.query,
                    onQueryCleared = { viewModel.onQueryChanged("") },
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
                        filtered = state.query.isNotBlank() || state.selectedCategory != null,
                    )
                }
                if (state.visibleChannels.isEmpty()) {
                    EmptyState(
                        title = "No channels match",
                        message = if (state.query.isNotBlank()) {
                            "Nothing matching \"${state.query}\" is on live TV right now."
                        } else {
                            "Nothing in this category is on live TV right now."
                        },
                        // Live TV review D4: a dead end one tap away from
                        // the full list gets the tap — "Clear search" when
                        // a query is set, "Show all channels" when a
                        // category is set.
                        action = {
                            if (state.query.isNotBlank()) {
                                Button(
                                    onClick = { viewModel.onQueryChanged("") },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Clear search") }
                            } else if (state.selectedCategory != null) {
                                Button(
                                    onClick = { viewModel.onCategorySelected(null) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Show all channels") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LiveTvChannelGrid(
                        channels = state.visibleChannels,
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
 */
@Composable
internal fun LiveTvChannelGrid(
    channels: List<LiveTvChannel>,
    onChannelClick: (LiveTvChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val showBackToTop by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = CatalogGridColumns,
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
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
            }
        }
    }
}

/**
 * The category chips, extracted so their order and selected state are
 * unit-testable (see LiveTvMockupTest): "All" first, then the server's
 * categories in the order they arrived. The row scrolls when the list is
 * long — the mockup's five chips happen to fit, but the registry can hold
 * more, and a clipped chip is worse than a scrollable row. The selected
 * chip is the shared jade fill ([raddFilterChipColors]); the unselected
 * chips keep the default thin border, as the mockup draws them. The row
 * shares Home's right-edge fade ([RowEndFade]) so the last chip fades out
 * instead of hard-clipping mid-word.
 *
 * A4-F2: while a text search is active the query is the filter, not "All"
 * — "All" loses its selected fill and dims to half alpha, and the query
 * itself rides first as a removable jade pill ("Discover Pakistan ×").
 * Tapping the pill clears the query; tapping "All" still clears only the
 * category, exactly as before.
 */
@Composable
internal fun LiveTvCategoryRow(
    categories: List<String>,
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    query: String = "",
    onQueryCleared: () -> Unit = {},
) {
    val searchActive = query.isNotBlank()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 16.dp),
        ) {
            if (searchActive) {
                item {
                    FilterChip(
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
                                    Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                        // The chip itself is 48dp tall, like "All" below.
                        modifier = Modifier.height(48.dp).testTag("liveTv_queryPill")
                            .semantics { contentDescription = "Clear search for ${query.trim()}" },
                    )
                }
            }
            item {
                FilterChip(
                    colors = raddFilterChipColors(),
                    selected = selectedCategory == null && !searchActive,
                    onClick = { onCategorySelected(null) },
                    label = { Text("All") },
                    // Live TV review (Nov 2026): the chip itself is 48dp
                    // tall, not just its touch target — the 32dp visible
                    // chip read as shallow next to the search pill, and
                    // Home's filter row is 48dp. A4-F2: half alpha while
                    // a text search is active, so the jade fill never
                    // claims a filter the query already owns.
                    modifier = Modifier.height(48.dp)
                        .alpha(if (searchActive) 0.5f else 1f)
                        .testTag("liveTv_chip_all"),
                )
            }
            items(categories) { category ->
                FilterChip(
                    colors = raddFilterChipColors(),
                    selected = selectedCategory == category,
                    onClick = { onCategorySelected(category) },
                    label = { Text(categoryLabel(category)) },
                    // Live TV review (Nov 2026): same 48dp visible height
                    // as the "All" chip above.
                    modifier = Modifier.height(48.dp).testTag("liveTv_chip_$category"),
                )
            }
        }
        RowEndFade(Modifier.align(Alignment.CenterEnd))
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
 * pass through untouched, never guessed at.
 */
internal fun countryLabel(code: String?): String? {
    if (code.isNullOrBlank()) return null
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
) {
    val metaLine = channelMetaLine(channel)
    // Robo run 2: every card announced its name three times (logo description,
    // name text, and the "LIVE" badge on every card) — the same words on all
    // ~18 channels. One sentence per card; the logo, badge and texts are paint.
    val a11y = listOfNotNull(
        "${channel.name}, live",
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
                    contentScale = ContentScale.Fit,
                    // Live TV review (Nov 2026): the logo lives in a centred
                    // safe zone, 60% of the plate in each dimension — a wide
                    // logo can no longer reach the corner where the LIVE
                    // badge sits, and a white-background asset reads as a
                    // badge, not a pasted rectangle.
                    fallbackLarge = true,
                    // A4-F1: the short monogram beside the mark — the full
                    // name under a 48dp mark overflowed the ~64dp-tall
                    // plate, so the Robo capture showed the mark with no
                    // text at all. The card below already prints the name.
                    monogram = channelMonogram(channel.name),
                    modifier = Modifier.fillMaxSize(0.6f),
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
            }
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleSmall,
                // Mockup: the channel name is bold, not medium.
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                // F11 (Batch B): one line, not two — a wrapping name was half
                // the uneven card heights, and a channel name that needs two
                // lines in a 120dp column is a truncated name anyway.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).clearAndSetSemantics { },
            )
            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // A4-F2: two lines, not one — a one-line ellipsis cut the
                // meta mid-segment ("Religious · Pakistan · Ur…"), so the
                // language the viewer filters by was the part that vanished.
                // Two lines hold the full "Category · Country · Language"
                // triple on a 2-column card; anything longer still ellipsizes.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp).clearAndSetSemantics { }
                    .testTag("liveTv_channelMeta"),
            )
        }
    }
}
