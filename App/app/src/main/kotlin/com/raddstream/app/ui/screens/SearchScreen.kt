package com.raddstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.R
import com.raddstream.app.network.models.Post
import com.raddstream.app.player.PastedText
import com.raddstream.app.player.classifyPastedText
import com.raddstream.app.ui.components.EmptyState
import com.raddstream.app.ui.components.InlineLoadFailure
import com.raddstream.app.ui.components.ErrorState
import com.raddstream.app.ui.components.MediaRail
import com.raddstream.app.ui.components.PosterCard
import com.raddstream.app.ui.components.SearchResultsSkeleton
import com.raddstream.app.ui.components.SectionHeader
import com.raddstream.app.ui.components.raddFilterChipColors
import com.raddstream.app.viewmodel.GenreFilter
import com.raddstream.app.viewmodel.LanguageFilter
import com.raddstream.app.viewmodel.IndustryFilter
import com.raddstream.app.viewmodel.QualityFilter
import com.raddstream.app.viewmodel.SearchSort
import com.raddstream.app.viewmodel.SearchUiState
import com.raddstream.app.viewmodel.SearchViewModel
import com.raddstream.app.viewmodel.displayLanguageName
import com.raddstream.app.viewmodel.resultsSummary

private val typeChips = listOf(
    "All" to null,
    "Movies" to "movie",
    "Series" to "series",
    // Not a `content_type`: the server answers `type=anime` from the `is_animated` flag.
    "Anime" to "anime",
    "Complete series" to "complete_season",
)

/**
 * Mockup (Nov 2026): the rail's own chips — ALL / MOVIES / SERIES / ANIME /
 * KPOP, uppercase like Home's rail. "Complete series" lives in the sheet's
 * Type group, not here: the rail is the five most-reached-for choices, and
 * the sheet owns the rest. KPOP is a query preset, not a content_type — see
 * [SearchViewModel.selectType].
 */
private val railTypeChips = listOf(
    "ALL" to null,
    "MOVIES" to "movie",
    "SERIES" to "series",
    "ANIME" to "anime",
    "KPOP" to "kpop",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// Session 121 (P-30): Search is a bottom-nav tab root — there is nowhere to go back
// to from it, so the top bar carries no back arrow and takes no callback. (The Home
// search bar navigates to this same tab, not to a pushed copy.)
fun SearchScreen(
    onPostClick: (Post) -> Unit = {},
    onPlayDirectUrl: (url: String, title: String) -> Unit = { _, _ -> },
    /** A query to run at once — an actor tap on a detail card lands here with the name filled in. */
    initialQuery: String? = null,
    /** A genre filter to apply at once — a facet chip on Home lands here with the genre filled in. */
    initialGenre: String? = null,
    /** An industry filter to apply at once — a facet chip on Home lands here with the industry filled in. */
    initialIndustry: String? = null,
    /** A language filter to apply at once — a language chip on Home lands here with the language filled in. */
    initialLanguage: String? = null,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Session 121 (P-12): submitting a search, or scrolling the results, dismisses
    // the keyboard — an open keyboard over the results was the whole complaint.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // An actor tap runs as a search at once — and is recorded as a recent,
    // like any search the viewer ran. Keyed on the argument, so a rotation
    // or a later edit never re-runs it.
    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) viewModel.runRecent(initialQuery)
    }

    // A facet chip applies its filter at once — and runs the search, like
    // any filter the viewer set. Keyed on the arguments, so a rotation or
    // a later edit never re-applies them. An unknown wire value is ignored,
    // never guessed at.
    LaunchedEffect(initialGenre, initialIndustry, initialLanguage) {
        initialGenre?.let { wire ->
            GenreFilter.entries.firstOrNull { it.wireValue == wire }?.let { viewModel.selectGenre(it) }
        }
        initialIndustry?.let { wire ->
            IndustryFilter.entries.firstOrNull { it.wireValue == wire }?.let { viewModel.selectIndustry(it) }
        }
        initialLanguage?.let { wire ->
            LanguageFilter.entries.firstOrNull { it.wireValue == wire }?.let { viewModel.selectLanguage(it) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { contentPadding ->
        // Session 120: the open keyboard covered the top row of results and the
        // empty-state hint hid behind its clipboard bar — the content never
        // accounted for the IME. imePadding lifts the whole column above the
        // keyboard (with adjustResize in the manifest) so results stay
        // reachable while typing.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            // Mockup (Nov 2026): the title is a heading in the content, not
            // a top bar. The bar's Tune icon and "Filtered" pill are gone
            // with it — the rail's Filters chip is the one entry to the
            // sheet, and it already shows the active count.
            Text(
                text = "Search",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
            SearchField(
                query = state.query,
                onQueryChanged = viewModel::onQueryChanged,
                onSubmit = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    viewModel.submitCurrentQuery()
                },
            )

            Spacer(Modifier.height(8.dp))
            val pasted = classifyPastedText(state.query)
            when (pasted) {
                is PastedText.DirectVideo -> {
                    DirectVideoRow(
                        url = pasted.url,
                        title = pasted.title,
                        onPlay = { onPlayDirectUrl(pasted.url, pasted.title) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                PastedText.YouTube -> {
                    Text(
                        text = "YouTube links can't play in Radd.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                is PastedText.PageLink -> {
                    // A web page is not a title and not a video: this app
                    // plays catalog titles and direct video files, it opens
                    // no web page and hands nothing to a browser. Say so
                    // plainly instead of offering an Open button.
                    Text(
                        text = "Web links don't play in Radd. Search the catalog for the title instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                PastedText.NotAUrl -> {
                    // A plain title: no hint here. The search bar searches
                    // the catalog only — no address behaviour is advertised.
                }
            }
            // The rail stays up whatever is pasted: a link replaces the
            // results, never the whole search UI.
            FilterRail(state = state, onSelectType = viewModel::selectType, onOpenFilters = { showSheet = true })

            if (pasted is PastedText.NotAUrl) {
                // Type-ahead titles for what is being typed. A tap searches for
                // that title — it never opens a page, because a suggestion names
                // a work, not a post.
                if (state.suggestions.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        // Session 121 (P-14): the last suggestion chip was clipped at the
                        // right edge — the row needs the same end padding the rail gets.
                        contentPadding = PaddingValues(start = 0.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        items(state.suggestions, key = { it.id }) { suggestion ->
                            FilterChip(
                                colors = raddFilterChipColors(),
                                selected = false,
                                onClick = { viewModel.runSuggestion(suggestion.title) },
                                label = {
                                    Text(
                                        // M3 (home review, Nov 2026): the year
                                        // comes from the guarded yearLabel, so
                                        // an impossible year never prints here.
                                        suggestion.yearLabel?.let {
                                            "${suggestion.title} ($it)"
                                        } ?: suggestion.title,
                                    )
                                },
                                // Audit #54: a FilterChip is 32 dp tall — the touch-target floor is 48 dp, so the chip itself carries it.
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
                // Audit #34: when the suggest call fails, the chips simply
                // stay empty until results arrive — the screen never
                // apologises for a missing feature inside the feature.

                SearchContent(
                    state = state,
                    onRunQuery = viewModel::runRecent,
                    onRemoveRecent = viewModel::removeRecent,
                    onSubmitCurrentQuery = viewModel::submitCurrentQuery,
                    onResetFilters = viewModel::resetFilters,
                    onQueryChanged = viewModel::onQueryChanged,
                    onShowCloseMatches = viewModel::showCloseMatches,
                    onShowExactOnly = viewModel::showExactOnly,
                    onRunSuggestion = viewModel::runSuggestion,
                    onLoadMore = viewModel::loadMore,
                    onPostClick = onPostClick,
                )
            } else if (state.recents.isNotEmpty()) {
                // A pasted link replaces the results, not the history: the
                // recents stay reachable so the viewer is never stranded on
                // a link row with nowhere to go.
                RecentsSection(state = state, onRunRecent = viewModel::runRecent, onRemoveRecent = viewModel::removeRecent)
            }
        }

        if (showSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                // F10 (Batch B): the sheet's grab handle, drawn instead of
                // assumed — the re-review called the sheet hard to dismiss
                // because nothing on screen said where to grab it.
                dragHandle = {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(width = 32.dp, height = 4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                },
            ) {
                FilterSheetContent(
                    state = state,
                    viewModel = viewModel,
                    onDone = { showSheet = false },
                )
            }
        }
    }
}

/**
 * The search field, extracted so its hint behaviour is unit-testable
 * (see SearchFieldHintTest): a filled rounded pill whose prompt is a
 * PLACEHOLDER only. A label with the same text used to float above the
 * typed text (audit #33) — the placeholder vanishes the moment the first
 * character is typed, and the accessible name rides on semantics instead.
 * The field searches titles and cast names; only the prompt is unified
 * with Home's bar, not the capability.
 * Mockup (Nov 2026): one rounded field, 52dp tall, 16dp corners, prompt
 * "Search movies, shows, anime".
 */
@Composable
internal fun SearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    // Audit #17: the Home search bar names what it takes — titles and
    // shows — while Search keeps the shorter hint. The mockup's prompt
    // names all three: movies, shows, anime.
    label: String = "Search movies, shows, anime",
) {
    TextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier.fillMaxWidth().height(52.dp)
            .semantics { contentDescription = label },
        singleLine = true,
        placeholder = { Text(label) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChanged("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // Session 121 (P-12): searching dismisses the keyboard so the
        // results are visible instead of sitting under it.
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * One pasted address, one play button: the row says what will play — the file
 * name, with the pasted address underneath — and nothing about where it came
 * from beyond the address itself. The viewer typed it, so showing it back is
 * showing their own text, not a catalog link. One Play control, not two:
 * the icon button and the text button used to do the same thing.
 */
@Composable
private fun DirectVideoRow(url: String, title: String, onPlay: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(onClick = onPlay) {
            Text("Play")
        }
    }
}

/**
 * The rail in one scrolled line: the Type chips the viewer reaches for
 * most, then the Filters button for everything else (industry, genre,
 * quality, plan, sort, years). One ungrouped ~30-chip scroll buried the
 * type choice behind captions; the sheet owns the rest.
 * Mockup (Nov 2026): ALL / MOVIES / SERIES / ANIME / KPOP — 48dp tall,
 * active chip jade-filled with black text, inactive chips dark-filled
 * with a thin border and white text. The selected-chip scroll (merged
 * 3bcd64de) stays: the rail scrolls just enough to bring its own
 * selection fully into view.
 */
@Composable
internal fun FilterRail(state: SearchUiState, onSelectType: (String?) -> Unit, onOpenFilters: () -> Unit) {
    // Robo #7: the last chip ("Complete series") was cut off at the right edge — and when it
    // was the SELECTED chip it stayed cut off ("Comp…"), because nothing ever scrolled the
    // rail to it. The rail already scrolls (LazyRow) with end padding (Session 121 P-29);
    // what it never did was reveal its own selection. When the selected type changes, scroll
    // just enough to bring that chip fully into view — nothing when it is already visible,
    // so tapping a visible chip never moves the row.
    val listState = rememberLazyListState()
    LaunchedEffect(state.filters.type) {
        val selectedIndex = railTypeChips.indexOfFirst { it.second == state.filters.type }
        if (selectedIndex < 0) return@LaunchedEffect
        // The effect launches before the row's first layout pass, so wait a frame:
        // layoutInfo is empty until the row is measured.
        withFrameNanos { }
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return@LaunchedEffect
        val item = visible.firstOrNull { it.index == selectedIndex }
        if (item == null) {
            // Not composed yet (far off-screen) — bring it to the start.
            listState.animateScrollToItem(selectedIndex)
            return@LaunchedEffect
        }
        val start = info.viewportStartOffset
        val end = info.viewportEndOffset
        val itemStart = item.offset
        val itemEnd = item.offset + item.size
        when {
            itemStart < start -> listState.animateScrollBy((itemStart - start).toFloat())
            itemEnd > end -> listState.animateScrollBy((itemEnd - end).toFloat())
        }
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        // Session 121 (P-29): the last chip ("Anime") was clipped at the right edge —
        // the row needs end padding; start stays 0 because the outer column already
        // pads 16 dp.
        contentPadding = PaddingValues(start = 0.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    ) {
        items(railTypeChips) { (label, value) ->
            val selected = state.filters.type == value
            FilterChip(
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                ),
                selected = selected,
                onClick = { onSelectType(value) },
                label = {
                    Text(
                        label,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                // CL-3, same as the sheet: the rail's "ALL" chip is the Type
                // group's "All", and it must say so — bare "ALL" is the bug.
                // Audit #54: a FilterChip is 32 dp tall — the touch-target floor is 48 dp, so the chip itself carries it.
                modifier = if (label == "ALL") {
                    Modifier.heightIn(min = 48.dp).semantics { contentDescription = "All types" }
                } else {
                    Modifier.heightIn(min = 48.dp)
                },
            )
        }
        item {
            val activeFilterCount = listOf(
                state.filters.industry != IndustryFilter.ALL,
                state.filters.genre != GenreFilter.ALL,
                state.filters.quality != QualityFilter.ALL,
                state.filters.language != LanguageFilter.ALL,
                state.filters.yearFrom != null || state.filters.yearTo != null,
                state.filters.includedOnly,
                state.sort != SearchSort.DEFAULT,
            ).count { it }
            val filtersLabel = if (activeFilterCount > 0) "Filters ($activeFilterCount)" else "Filters"
            val filtersA11y = if (activeFilterCount > 0) "Filters, $activeFilterCount active" else "Filters"
            FilterChip(
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    leadingIconColor = MaterialTheme.colorScheme.onSurface,
                ),
                selected = activeFilterCount > 0,
                onClick = onOpenFilters,
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(filtersLabel)
                    }
                },
                // Audit #54: a FilterChip is 32 dp tall — the touch-target floor is 48 dp, so the chip itself carries it.
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = filtersA11y },
            )
        }
    }
}

/**
 * Mockup (Nov 2026): recent searches as a list, not a chip rail — one row
 * per search, clock icon, tap to run it, × to drop that row. Used on the
 * discovery page and under a pasted link, where it is the way back.
 */
@Composable
internal fun RecentsSection(state: SearchUiState, onRunRecent: (String) -> Unit, onRemoveRecent: (String) -> Unit) {
    RecentsList(
        recents = state.recents,
        onRunRecent = onRunRecent,
        onRemoveRecent = onRemoveRecent,
    )
}

@Composable
internal fun RecentsList(
    recents: List<String>,
    onRunRecent: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Recent searches",
            modifier = Modifier.padding(top = 8.dp),
        )
        recents.forEach { recent ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onRunRecent(recent) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    recent,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemoveRecent(recent) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove $recent")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchDiscoveryContent(
    state: SearchUiState,
    onRunQuery: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onPostClick: (Post) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        // Mockup (Nov 2026): the discovery page is three sections, top to
        // bottom — "Trending now" rail, "Recent searches" list,
        // "Recommended for you" 3-column grid. The old "Trending searches"
        // chips and "Browse by genre" are gone: the mockup has neither, and
        // the genre filter still lives in the sheet. Each section hides
        // when its data is empty, so a fresh install shows the hint only.
        if (state.trending.isNotEmpty()) {
            SectionHeader(
                title = "Trending now",
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            // The outer column already pads 16dp: the rail starts at 0 and
            // keeps 16dp at the end, so the last card never sits flush
            // against the screen edge when fully scrolled.
            MediaRail(items = state.trending, key = { it.id }, contentPadding = PaddingValues(start = 0.dp, end = 16.dp)) { post ->
                PosterCard(post = post, onClick = { onPostClick(post) })
            }
        }
        if (state.recents.isNotEmpty()) {
            RecentsList(
                recents = state.recents,
                onRunRecent = onRunQuery,
                onRemoveRecent = onRemoveRecent,
            )
        }
        if (state.recommended.isNotEmpty()) {
            SectionHeader(
                title = "Recommended for you",
                modifier = Modifier.padding(top = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            state.recommended.chunked(3).forEach { trio ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    trio.forEach { post ->
                        PosterCard(post = post, onClick = { onPostClick(post) }, modifier = Modifier.weight(1f))
                    }
                    // An incomplete last row keeps the grid's columns —
                    // spacers hold the width the missing cards would take.
                    repeat(3 - trio.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (state.trending.isEmpty() && state.recents.isEmpty() && state.recommended.isEmpty()) {
            Spacer(Modifier.height(28.dp))
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Search your catalog",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Search titles and cast names to find movies, series, and anime",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchContent(
    state: SearchUiState,
    onRunQuery: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onSubmitCurrentQuery: () -> Unit,
    onResetFilters: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onShowCloseMatches: () -> Unit,
    onShowExactOnly: () -> Unit,
    onRunSuggestion: (String) -> Unit,
    onLoadMore: () -> Unit,
    onPostClick: (Post) -> Unit,
) {
    // Session 121 (P-12): scrolling the results dismisses the keyboard — the open
    // keyboard covering the top row was the other half of the complaint (the first
    // half was submit, handled at the field). Hoisted here so the list state is
    // remembered unconditionally, not inside the branch that renders the list.
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            focusManager.clearFocus()
        }
    }
    when {
        !state.hasSearchIntent -> {
            SearchDiscoveryContent(
                state = state,
                onRunQuery = onRunQuery,
                onRemoveRecent = onRemoveRecent,
                onPostClick = onPostClick,
            )
        }
        state.isLoading && state.groups.isEmpty() -> SearchResultsSkeleton(
            // The results arrive as a summary line, a group header and a
            // 2-wide grid — the bare grid skeleton let the summary and the
            // header appear out of nowhere and push every row down.
        )
        state.error != null && state.groups.isEmpty() -> ErrorState(title = "Couldn't search", message = state.error ?: "Search failed.", onRetry = onSubmitCurrentQuery)
        // Audit #36: "No matches" only after a completed search returns zero
        // rows — never while a search is still in flight (the skeleton owns
        // that window), and never before the first search.
        state.groups.isEmpty() && state.hasSearched && !state.isLoading -> EmptyState(
            title = "No matches",
            message = when {
                state.query.isNotBlank() && state.hasActiveFilters ->
                    "No titles matched \"${state.query}\" with the current filters."
                state.query.isNotBlank() -> "No titles matched \"${state.query}\"."
                else -> "Nothing in the catalog matched these filters."
            },
            action = {
                if (state.hasActiveFilters) {
                    OutlinedButton(onClick = onResetFilters) { Text("Clear filters") }
                } else {
                    // An empty result screen with no way out is a dead end: the viewer either
                    // loosens a filter or starts from a different word. Both are one tap.
                    OutlinedButton(onClick = { onQueryChanged("") }) {
                        Text("Search another title")
                    }
                }
            },
        )
        state.isFuzzy && state.showExactOnly -> EmptyState(
            // Exact-only on a fuzzy page is the empty state: every row the
            // server sent is one-edit-away, so there is nothing exact to
            // list — and the way back is right here.
            title = "No exact matches",
            message = "No titles exactly match \"${state.query}\".",
            icon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp)) },
            action = {
                OutlinedButton(onClick = onShowCloseMatches) { Text("Show close matches") }
            },
        )
        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                // P1-S3: one filter entry point — the top bar's Tune icon (and
                // the "Filtered" pill) opens the sheet. The header keeps only
                // the count; a second "Sort" door to the same sheet is gone.
                Text(
                    text = resultsSummary(state.resultCount, state.serverTotal, state.hasMore, state.totalCapped),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }

            // Audit #37: the server's "did you mean" spelling, one tap to
            // search for it — shown only when it differs from the query.
            state.didYouMean?.let { suggestion ->
                item {
                    TextButton(
                        onClick = { onRunSuggestion(suggestion) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Did you mean $suggestion?")
                    }
                }
            }

            // The server found nothing exact and answered one edit away
            // instead. Name the query, so the viewer knows why "Avengers"
            // is what came back for "Avngers" — and offer the exact-only
            // view, which is the empty state above.
            if (state.isFuzzy) {
                item {
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Text(
                            text = "No exact match for \"${state.query}\" — showing the closest titles.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onShowExactOnly) { Text("Show exact matches only") }
                    }
                }
            }

            state.groups.forEach { group ->
                item {
                    SectionHeader(
                        title = group.label,
                        subtitle = "${group.items.size} ${if (group.items.size == 1) "title" else "titles"}",
                        modifier = Modifier.padding(horizontal = 0.dp),
                    )
                }
                // P1-S1: results are a scannable vertical grid, two posters
                // wide — rails are for Home. Scanning 20 results no longer
                // means swiping every rail sideways.
                // Session 158 (S5): a row is keyed by what it holds, not
                // where it sits — a re-emitted list (the same search
                // landing twice, a second identical submit) rebuilds the
                // groups, and an index key lets the rows shift under a
                // press in flight. A content key keeps the row — and the
                // press on it — alive across the re-emission.
                group.items.chunked(2).forEach { pair ->
                    item(key = "${group.typeKey}-${pair.joinToString(separator = ",") { it.id.toString() }}") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            pair.forEach { post ->
                                PosterCard(
                                    post = post,
                                    onClick = { onPostClick(post) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            if (state.hasMore) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        OutlinedButton(onClick = onLoadMore, enabled = !state.isLoadingMore) {
                            Text(if (state.isLoadingMore) "Loading…" else "Load more")
                        }
                    }
                }
            }

            // A failed next page used to be invisible: the retry path was reachable only when
            // every group was empty, so a search that died on page 3 looked like a catalog that
            // simply had three pages.
            state.error?.let { failure ->
                item { InlineLoadFailure(message = failure, onRetry = onLoadMore) }
            }
        }
    }
}

@Composable
private fun FilterSheetContent(
    state: SearchUiState,
    viewModel: SearchViewModel,
    onDone: () -> Unit,
) {
    FilterSheetBody(
        state = state,
        onSelectType = viewModel::selectType,
        onSelectIndustry = viewModel::selectIndustry,
        onSelectGenre = viewModel::selectGenre,
        onSelectLanguage = viewModel::selectLanguage,
        onSelectQuality = viewModel::selectQuality,
        onSetIncludedOnly = viewModel::setIncludedOnly,
        onSelectSort = viewModel::setSort,
        onSetYearRange = viewModel::setYearRange,
        onResetFilters = viewModel::resetFilters,
        onDone = onDone,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FilterSheetBody(
    state: SearchUiState,
    onSelectType: (String?) -> Unit,
    onSelectIndustry: (IndustryFilter) -> Unit,
    onSelectGenre: (GenreFilter) -> Unit,
    onSelectLanguage: (LanguageFilter) -> Unit,
    onSelectQuality: (QualityFilter) -> Unit,
    onSetIncludedOnly: (Boolean) -> Unit,
    onSelectSort: (SearchSort) -> Unit,
    onSetYearRange: (Int?, Int?) -> Unit,
    onResetFilters: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // F10 (Batch B): the sheet is two parts — a scrolling list of sections
    // and a footer that never scrolls away. The old sheet scrolled the
    // Apply button off the bottom on a small phone, so the only way to
    // leave was the swipe-down the re-review called out as hard to find.
    // The footer sits under a hairline, always in reach.
    // The Language list is the long one (11 languages); it starts folded
    // so the sheet opens on Type, not on a wall of language chips.
    var languageExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Column(
            modifier = Modifier
                // Robo #6 (APK 571): opening the filter sheet crashed with
                // "Vertically scrollable component was measured with an
                // infinity maximum height constraints". The scrolling middle
                // is weight(1f) inside a wrap-content Column, so when the
                // sheet hands it unbounded height the scrollable is measured
                // with infinite constraints and Compose throws. The cap sits
                // above any phone screen, so it only binds on that unbounded
                // path — the bounded path (and the goldens) are untouched.
                .heightIn(max = 1200.dp)
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Type moved here from the rail: the rail now carries only the Type
            // chips and the Filters button, so the sheet is the one place where
            // every facet lives together.
            FacetChips(
                title = "Type",
                options = typeChips,
                label = { it.first },
                isSelected = { state.filters.type == it.second },
                onSelect = { onSelectType(it.second) },
                allDescription = "All types",
                icon = Icons.Default.Category,
            )
            FacetChips(
                title = "Industry",
                options = IndustryFilter.entries,
                label = { it.label },
                isSelected = { state.filters.industry == it },
                onSelect = onSelectIndustry,
                allDescription = "All industries",
                icon = Icons.Default.Movie,
            )
            FacetChips(
                title = "Genre",
                options = GenreFilter.entries,
                label = { it.label },
                isSelected = { state.filters.genre == it },
                onSelect = onSelectGenre,
                allDescription = "All genres",
                icon = Icons.Default.Theaters,
            )
            // Language, folded by default: eleven chips is the longest group
            // in the sheet, and the header always shows what is picked, so
            // a folded section still says what it holds.
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { languageExpanded = !languageExpanded }
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Language: ${state.filters.language.label}"
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Language", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text(
                        displayLanguageName(state.filters.language.label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (languageExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (languageExpanded) {
                    Spacer(Modifier.height(8.dp))
                    FacetChips(
                        title = "Language",
                        options = LanguageFilter.entries,
                        // F23 (Batch B): the chip paints both scripts —
                        // "Hindi · हिन्दी" — so the language is findable by
                        // a reader of either. The stored value stays the
                        // English name; only the paint carries both.
                        label = { displayLanguageName(it.label) },
                        isSelected = { state.filters.language == it },
                        onSelect = onSelectLanguage,
                        allDescription = "All languages",
                        showHeader = false,
                    )
                } else {
                    HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            FacetChips(
                title = "Quality",
                options = QualityFilter.entries,
                label = { it.label },
                isSelected = { state.filters.quality == it },
                onSelect = onSelectQuality,
                allDescription = "All qualities",
                icon = Icons.Default.HighQuality,
            )
            if (!state.isVip) {
                FacetHeader(title = "Plan", icon = Icons.Default.CardMembership)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        colors = raddFilterChipColors(),
                        selected = state.filters.includedOnly,
                        onClick = { onSetIncludedOnly(!state.filters.includedOnly) },
                        label = { Text("Included in my plan") },
                        // Audit #54: a FilterChip is 32 dp tall — the touch-target floor is 48 dp, so the chip itself carries it.
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }

            FacetHeader(title = "Sort results", icon = Icons.Default.Sort)
            SearchSort.entries.forEach { sort ->
                SortOptionRow(
                    sort = sort,
                    selected = state.sort == sort,
                    onSelect = onSelectSort,
                )
            }
            HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)

            FacetHeader(title = "Year range", icon = Icons.Default.DateRange)
            Text(
                stringResource(R.string.search_filters_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                YearField(
                    label = "From",
                    value = state.filters.yearFrom?.toString() ?: "",
                    onSet = { it?.let { n -> onSetYearRange(n, state.filters.yearTo) } ?: onSetYearRange(null, state.filters.yearTo) },
                    modifier = Modifier.weight(1f),
                )
                YearField(
                    label = "To",
                    value = state.filters.yearTo?.toString() ?: "",
                    onSet = { it?.let { n -> onSetYearRange(state.filters.yearFrom, n) } ?: onSetYearRange(state.filters.yearFrom, null) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onResetFilters,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Reset")
            }
            Button(
                onClick = onDone,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Apply")
            }
        }
    }
}

/**
 * One sort option: the row, its radio and its label read as a single option.
 * The spoken name is the label only — "Sort by Newest" — and the row carries
 * its selected state itself, which is what a screen reader announces as
 * selected or not selected. Robo run 3 embedded the state in the description
 * ("Sort by Newest, selected"); Robo run 4 flagged that as an item-type
 * label, because a description must not carry state words. The RadioButton's
 * own `selected` does not merge into the row (proven by the run-4 test), so
 * the row sets `selected` explicitly — the RadioButton is paint-only. Internal
 * so the a11y test can pin the spoken name and the selected state directly.
 */
@Composable
internal fun SortOptionRow(
    sort: SearchSort,
    selected: Boolean,
    onSelect: (SearchSort) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onSelect(sort) }
            .semantics(mergeDescendants = true) {
                contentDescription = "Sort by ${sort.label}"
                // The RadioButton's own `selected` state does not merge into
                // this row (proven by the run-4 test: the merged node carried
                // the description and the text but no Selected property), so
                // the row — which is the option, and whose tap is the control
                // — carries its selected state itself. That is what a screen
                // reader announces as selected / not selected. `this.selected`
                // is the semantics property; the bare `selected` on the right
                // is the row's parameter — the two share a name, so the
                // receiver must be named or the compiler reads it as
                // reassigning the parameter.
                this.selected = selected
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(sort.label)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> FacetChips(
    title: String,
    options: List<T>,
    label: (T) -> String,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    // CL-3: the spoken name for the "All" option in this group, so four rows
    // of "All" do not all announce identically. The visible label stays "All".
    // Robo run 3: the same label repeats across groups too — "Anime" under
    // Type and under Industry announced identically — so every chip carries
    // its group in its spoken name ("Type: Anime" vs "Industry: Anime").
    allDescription: String,
    // F10 (Batch B): the section's icon, painted beside the title so the
    // groups read apart at a glance instead of five bare headings in a
    // row. The Language accordion passes its own header and hides this one.
    icon: ImageVector? = null,
    showHeader: Boolean = true,
) {
    if (showHeader) {
        FacetHeader(title = title, icon = icon)
    }
    // F10 (Batch B): 8dp gaps read as five separate rows of buttons; 6dp
    // reads as one group. The 48dp touch floor stays — density is the
    // gaps, never the targets.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                colors = raddFilterChipColors(),
                selected = isSelected(option),
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                // Audit #54: a FilterChip is 32 dp tall — the touch-target floor is 48 dp, so the chip itself carries it.
                modifier = if (label(option) == "All") {
                    Modifier.heightIn(min = 48.dp).semantics { contentDescription = allDescription }
                } else {
                    Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$title: ${label(option)}" }
                },
            )
        }
    }
    // F10 (Batch B): a hairline under each group, so the sheet reads as
    // sections instead of one long run of chips.
    HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * F10 (Batch B): one section heading for the filter sheet — an icon and a
 * title on one row, shared by the chip groups and by the Plan, Sort and
 * Year blocks so every section opens the same way.
 */
@Composable
private fun FacetHeader(title: String, icon: ImageVector?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun YearField(
    label: String,
    value: String,
    onSet: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onSet(raw.filter(Char::isDigit).take(4).takeIf { it.isNotBlank() }?.toIntOrNull()) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier,
    )
}
