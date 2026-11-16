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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.raddstream.app.ui.components.isKnownLanguage
import com.raddstream.app.ui.components.raddFilterChipColors
import com.raddstream.app.ui.layout.CardGeometry
import com.raddstream.app.viewmodel.LiveTvViewModel

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
                                "Showing the last channel list — it may be out of date. ${state.error}"
                            } else {
                                state.error ?: "Could not load live channels."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.refresh() }) { Text("Retry") }
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
                )
                if (state.visibleChannels.isEmpty()) {
                    EmptyState(
                        title = "No channels match",
                        message = if (state.query.isNotBlank()) {
                            "Nothing named \"${state.query}\" is on live TV right now."
                        } else {
                            "Nothing in this category is on live TV right now."
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = CatalogGridColumns,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(state.visibleChannels, key = { it.id }) { channel ->
                            LiveTvChannelCard(
                                channel = channel,
                                // Batch C: warm the channel's link in parallel
                                // with the navigation, so the player 302s from
                                // the banked answer instead of paying the live
                                // resolve inside "Finding your video".
                                onClick = {
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
 */
@Composable
internal fun LiveTvCategoryRow(
    categories: List<String>,
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            item {
                FilterChip(
                    colors = raddFilterChipColors(),
                    selected = selectedCategory == null,
                    onClick = { onCategorySelected(null) },
                    label = { Text("All") },
                    // Live TV review (Nov 2026): the chip itself is 48dp
                    // tall, not just its touch target — the 32dp visible
                    // chip read as shallow next to the search pill, and
                    // Home's filter row is 48dp.
                    modifier = Modifier.height(48.dp).testTag("liveTv_chip_all"),
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
private fun categoryLabel(category: String): String =
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp).clearAndSetSemantics { },
            )
        }
    }
}
