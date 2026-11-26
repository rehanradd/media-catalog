package com.raddstream.app.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.delay
import com.raddstream.app.R
import com.raddstream.app.network.friendlyNetworkError
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.currentCalendarYear
import com.raddstream.app.network.models.displayYearLabel
import com.raddstream.app.player.PastedText
import com.raddstream.app.player.classifyPastedText
import com.raddstream.app.ui.components.CatalogImage
import com.raddstream.app.ui.components.EmptyState
import com.raddstream.app.ui.components.InlineLoadFailure
import com.raddstream.app.ui.components.ErrorState
import com.raddstream.app.ui.components.MediaRail
import com.raddstream.app.ui.components.PosterCard
import com.raddstream.app.ui.components.PosterCellSkeleton
import com.raddstream.app.ui.components.SearchResultsSkeleton
import com.raddstream.app.ui.components.SectionHeader
import com.raddstream.app.ui.components.SkeletonBox
import com.raddstream.app.ui.components.prefetchPosterArt
import com.raddstream.app.ui.components.rememberReduceMotion
import com.raddstream.app.ui.components.searchNextPrefetchUrls
import com.raddstream.app.ui.components.raddFilterChipColors
import com.raddstream.app.ui.components.RaddChip
import com.raddstream.app.ui.components.ScrollingChipRow
import com.raddstream.app.ui.components.UserWords
import com.raddstream.app.ui.layout.CardGeometry
import com.raddstream.app.ui.theme.RaddMotion
import com.raddstream.app.ui.theme.RaddTokens
import com.raddstream.app.ui.theme.posterSharedElement
import com.raddstream.app.viewmodel.GenreFilter
import com.raddstream.app.viewmodel.LanguageFilter
import com.raddstream.app.viewmodel.ResumeTile
import com.raddstream.app.viewmodel.IndustryFilter
import com.raddstream.app.viewmodel.LocalSearchResult
import com.raddstream.app.viewmodel.MaxSavedFilters
import com.raddstream.app.viewmodel.QualityFilter
import com.raddstream.app.viewmodel.SavedSearchFilter
import com.raddstream.app.viewmodel.SearchSort
import com.raddstream.app.viewmodel.SearchUiState
import com.raddstream.app.viewmodel.SearchViewModel
import com.raddstream.app.viewmodel.activeFilterSummary
import com.raddstream.app.viewmodel.displayLanguageName
import com.raddstream.app.viewmodel.effectiveTypeChip
import com.raddstream.app.viewmodel.filterByYearRange
import com.raddstream.app.viewmodel.groupSearchResults
import com.raddstream.app.viewmodel.normalizeTypeLabel
import com.raddstream.app.viewmodel.TypeGroup

private val typeChips = listOf(
    "All" to null,
    "Movies" to "movie",
    "Series" to "series",
    // Not a `content_type`: the server answers `type=anime` from the `is_animated` flag.
    "Anime" to "anime",
    // Audit Search #9 (Nov 2026): "Complete series" is a type value
    // (`complete_season`), not a fifth chip — it wrapped alone onto a
    // second row inside this grid. It lives below as a switch that
    // refines Series, not here.
)

/**
 * Mockup (Nov 2026): the rail's own chips — All / Movies / Series / Anime /
 * K-Pop, Title Case like the sheet's Type group (the old ALL-CAPS read as
 * shouting next to the pill search bar — Gemini review, Nov 2026). "Complete
 * series" lives in the sheet as a switch refining Series, not here: the rail
 * is the five most-reached-for choices, and the sheet owns the rest. KPOP is
 * a query preset, not a content_type — see [SearchViewModel.selectType].
 */
private val railTypeChips = listOf(
    "All" to null,
    "Movies" to "movie",
    "Series" to "series",
    "Anime" to "anime",
    "K-Pop" to "kpop",
)

/** Test tags on the discovery "See all" actions, so a test taps the button itself. */
internal const val SeeAllTrendingTag = "see-all-trending"
internal const val SeeAllRecommendedTag = "see-all-recommended"

/** Test tag on the filter rail's right-edge fade mask. */
internal const val FilterRailFadeTag = "filter-rail-fade"

/** Test tag on the quick-chips row's right-edge fade mask (S3, round 5). */
internal const val QuickChipsFadeTag = "quick-chips-fade"

/** Test tags for the single fuzzy banner (P1-11) and its inline exact-only link. */
internal const val FuzzyBannerTag = "searchFuzzyBanner"
internal const val FuzzyBannerExactLinkTag = "searchFuzzyBannerExactLink"

/**
 * P1-11 (Nov 2026): the one fuzzy-recovery banner. The server found
 * nothing exact and answered one edit away instead — the banner names the
 * query, so the viewer knows why "Avengers" is what came back for
 * "Avngers", and the exact-only view is an inline text link inside the
 * banner ("search exact?"), not a button. A FlowRow carries the two
 * Texts, so the link flows with the sentence and wraps like text instead
 * of sitting on its own row. The banner is a polite live region: it is
 * the announcement of what happened, now that the result count line that
 * used to carry the live region is gone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FuzzyBanner(
    query: String,
    onShowExactOnly: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(FuzzyBannerTag)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        FlowRow {
            Text(
                text = "No exact match for \"$query\". Showing close titles — ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "search exact?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onShowExactOnly)
                    .testTag(FuzzyBannerExactLinkTag)
                    .semantics {
                        contentDescription = "Search exact matches only"
                        onClick(label = "Search exact matches only") { onShowExactOnly(); true }
                    },
            )
        }
    }
}

/** Test tag on the results LazyColumn, so a test can scroll it to the append footer. */
internal const val SearchResultsListTag = "searchResultsList"

/**
 * The Browse route's type for a "See all" tap: the rail's type values are
 * the Browse route's, except "kpop" — the server has no kpop content_type
 * (SearchViewModel: content_type='kpop' answers zero rows), so K-pop
 * expands to the whole trending catalog rather than an empty one.
 * Browse serves sort=trending, so the expanded "For you" grid
 * is the same scope in trending order, not newest order — a sort argument
 * on the Browse route is a later slice, not this one.
 */
internal fun browseTypeForSeeAll(type: String?): String? = when (type) {
    "movie", "series", "anime" -> type
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
// Session 121 (P-30): Search is a bottom-nav tab root — there is nowhere to go back
// to from it, so the top bar carries no back arrow and takes no callback. (The Home
// search bar navigates to this same tab, not to a pushed copy.)
fun SearchScreen(
    onPostClick: (Post) -> Unit = {},
    onPlayDirectUrl: (url: String, title: String) -> Unit = { _, _ -> },
    /** A "See all" tap expands a discovery section in Browse, with the active type filter. */
    onBrowse: (String?) -> Unit = {},
    /** Audit Search #3 (Nov 2026): the error state offers "Open Settings" — the copy sends the viewer there, so the screen carries the door. NavGraph navigates to the Settings tab. */
    onOpenSettings: () -> Unit = {},
    /** A query to run at once — an actor tap on a detail card lands here with the name filled in. */
    initialQuery: String? = null,
    /** A genre filter to apply at once — a facet chip on Home lands here with the genre filled in. */
    initialGenre: String? = null,
    /** An industry filter to apply at once — a facet chip on Home lands here with the industry filled in. */
    initialIndustry: String? = null,
    /** A language filter to apply at once — a language chip on Home lands here with the language filled in. */
    initialLanguage: String? = null,
    viewModel: SearchViewModel = hiltViewModel(),
    /**
     * Round 6 motion infra: the shared-transition scopes the NavHost
     * passes in. Null outside navigation (Paparazzi previews, tests) —
     * sharedElement() calls no-op and the screen renders exactly as
     * before. Screen branches thread these into their poster/hero
     * sharedElement() calls; this branch only carries them.
     */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val state by viewModel.uiState.collectAsState()
    // Feel-instant S6: the results list is a Pager — the screen collects
    // it, and the groups are derived from its loaded snapshot (see
    // [rememberSearchGroups]). The ViewModel owns the search and the
    // totals; the list itself lives here.
    val pagingItems = viewModel.pagingData.collectAsLazyPagingItems()
    val groups = rememberSearchGroups(pagingItems, state)
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Session 121 (P-12): submitting a search, or scrolling the results, dismisses
    // the keyboard — an open keyboard over the results was the whole complaint.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // SE-3 (round 2): voice entry. The mic opens the platform speech
    // recognizer and the first heard phrase runs as a search at once
    // (submit, not debounce — a spoken phrase is a complete query, and it
    // joins recents like a typed submit). A device with no recognizer
    // never sees the mic: [isVoiceSearchAvailable] answers once per
    // composition, and the field hides the button on false instead of
    // offering a dead control.
    val voiceContext = LocalContext.current
    val voiceSearchAvailable = remember { isVoiceSearchAvailable(voiceContext.packageManager) }
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val heard = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim().orEmpty()
        if (heard.isNotBlank()) {
            focusManager.clearFocus()
            keyboardController?.hide()
            viewModel.onQueryChanged(heard)
            viewModel.submitCurrentQuery()
        }
    }
    val onVoiceSearch: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Search titles or people")
        }
        runCatching { voiceLauncher.launch(intent) }
    }

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

    // Feel-instant S2, extended (Nov 2026): warm the posters of the pages
    // as they land, not just the first page. The selector takes the next
    // page in load order that this query has not warmed yet, so a second
    // page arriving warms its own posters instead of re-warming the first
    // page's — the warm keeps pace with the data, one page per landing.
    // The set resets with the query: a new search warms its own first
    // page. Capped; the helper dedupes, and never runs on metered data the
    // viewer never granted.
    val prefetchContext = LocalContext.current
    val prefetchedUrls = remember(state.query) { mutableSetOf<String>() }
    LaunchedEffect(groups) {
        val urls = searchNextPrefetchUrls(groups, prefetchedUrls)
        if (urls.isNotEmpty()) {
            prefetchedUrls += urls
            prefetchPosterArt(prefetchContext, urls)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // P1-10 (Nov 2026): the field is the top bar — sticky while the
        // results scroll (the Scaffold bar never scrolls away), and the
        // in-content "Search" headline is gone: the bar + pill already say
        // what this surface is. The bar carries the one leading back
        // affordance; the field, mic and crossfade clear are unchanged.
        topBar = {
            SearchTopBar(
                query = state.query,
                onQueryChanged = viewModel::onQueryChanged,
                onSubmit = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    viewModel.submitCurrentQuery()
                },
                // SE-3 (round 2): voice entry — the mic shows only where a
                // recognizer exists, and speaks the query at once.
                onVoiceSearch = if (voiceSearchAvailable) onVoiceSearch else null,
                voiceSearchAvailable = voiceSearchAvailable,
                // Back is the one way out of a search: it only exists while
                // there is a search to leave (a query, or a filter that
                // makes a blank query a search). On bare discovery the
                // field takes the whole row.
                showBack = state.query.isNotBlank() || state.hasSearchIntent,
                onBack = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    viewModel.onQueryChanged("")
                    viewModel.resetFilters()
                },
            )
        },
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
            Spacer(Modifier.height(8.dp))
            // SE-2 (round 2): the saved-filter row — one tap re-applies a
            // filter combination this viewer kept. It sits above the rail,
            // and only while at least one filter is saved.
            if (state.savedFilters.isNotEmpty()) {
                SavedFilterRow(
                    savedFilters = state.savedFilters,
                    onApply = viewModel::applySavedFilter,
                    onRemove = viewModel::removeSavedFilter,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
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
                        text = "YouTube links can't play here. Search for it in the catalog.",
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
                        text = "Web links don't play. Search the catalog for the title instead.",
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
            FilterRail(state = state, groups = groups, onSelectType = viewModel::selectType, onOpenFilters = { showSheet = true })

            if (pasted is PastedText.NotAUrl) {
                // Type-ahead titles for what is being typed. A tap searches for
                // that title — it never opens a page, because a suggestion names
                // a work, not a post.
                if (state.visibleSuggestions.isNotEmpty()) {
                    // A4-F3: rides ScrollingChipRow now — the fade and the
                    // row are the shared component, not a hand-rolled copy.
                    ScrollingChipRow(
                        modifier = Modifier.padding(top = 4.dp),
                        // Session 121 (P-14): the last suggestion chip was clipped at the
                        // right edge — the row needs the same end padding the rail gets.
                        contentPadding = PaddingValues(start = 0.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        itemsIndexed(state.visibleSuggestions, key = { _, it -> it.id }) { index, suggestion ->
                            // Round 6 (motion): the suggestion chips stagger
                            // in on focus, on the expressive spatial spec —
                            // they animate in when the field gains focus and
                            // the suggestions populate. Under inspection mode
                            // (Paparazzi) they render settled, so goldens
                            // are unchanged; reduce-motion renders directly.
                            SearchStaggeredFade(delayMillis = searchEntranceDelayMs(index)) {
                                RaddChip(
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
                                )
                            }
                        }
                    }
                }
                // Audit #34: when the suggest call fails, the chips simply
                // stay empty until results arrive — the screen never
                // apologises for a missing feature inside the feature.

                SearchContent(
                    state = state,
                    pagingItems = pagingItems,
                    groups = groups,
                    onRunQuery = viewModel::runRecent,
                    onRemoveRecent = viewModel::removeRecent,
                    onResetFilters = viewModel::resetFilters,
                    onQueryChanged = viewModel::onQueryChanged,
                    onShowCloseMatches = viewModel::showCloseMatches,
                    onShowExactOnly = viewModel::showExactOnly,
                    onRunSuggestion = viewModel::runSuggestion,
                    onPostClick = onPostClick,
                    onOpenSettings = onOpenSettings,
                    onSeeAllTrending = { onBrowse(browseTypeForSeeAll(state.filters.type)) },
                    onSeeAllRecommended = { onBrowse(browseTypeForSeeAll(state.filters.type)) },
                    // SE-1 (round 2): the category tabs scope the results —
                    // genre, language and year, one tap each.
                    onSelectGenre = viewModel::selectGenre,
                    onSelectLanguage = viewModel::selectLanguage,
                    onSetYearRange = viewModel::setYearRange,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            } else if (state.visibleRecents.isNotEmpty()) {
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
                    // SE-2 (round 2): the sheet is where a filter combination
                    // gets saved — the Save action reads the live state.
                    savedFilters = state.savedFilters,
                    onSaveFilter = viewModel::saveCurrentFilters,
                )
            }
        }
    }
}

/**
 * P1-10 (Nov 2026): the search field as the top bar — a 52 dp pill that
 * never scrolls away, with a single leading back affordance. The back only
 * exists while there is a search to leave; on bare discovery the field
 * takes the whole row. The field itself is the unchanged [SearchField]:
 * pill, voice mic, crossfade clear.
 */
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onVoiceSearch: (() -> Unit)?,
    voiceSearchAvailable: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showBack) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    // S8 (round 2): the house back glyph, not the Material default ArrowBack icon.
                    painterResource(R.drawable.ic_action_arrow_left),
                    contentDescription = "Back",
                )
            }
        }
        SearchField(
            query = query,
            onQueryChanged = onQueryChanged,
            onSubmit = onSubmit,
            onVoiceSearch = onVoiceSearch,
            voiceSearchAvailable = voiceSearchAvailable,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The search field, extracted so its hint behaviour is unit-testable
 * (see SearchFieldHintTest): a filled rounded pill whose prompt is a
 * PLACEHOLDER only. A label with the same text used to float above the
 * typed text (audit #33) — the placeholder vanishes the moment the first
 * character is typed, and the accessible name rides on semantics instead.
 * The field searches titles and people; only the prompt is unified
 * with Home's bar, not the capability.
 * Mockup (Nov 2026): one rounded field, 52dp tall, prompt
 * "Search titles or people" (screenshot-fix D2, 2026-11-16: the old
 * "Search titles, genres, or cast" truncated mid-word — "Search titles,
 * genres, or" — on a narrow phone, because the field is too narrow for
 * 30 characters next to the search icon. The shorter prompt names what
 * the field takes and fits). P1-12 (Nov 2026): "cast" is dated and
 * ambiguous — "people" names what the field takes.
 * SE-3 (round 2): the field is a full pill now (Shape.Pill), with a mic
 * beside the clear action — voice entry, TalkBack "Voice search". The mic
 * only exists when [voiceSearchAvailable] is true; a device with no
 * speech recognizer never sees it. The trailing actions crossfade
 * (AnimatedContent) instead of swapping, so the field never jumps.
 */
@Composable
internal fun SearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    // Audit #17: the Home search bar names what it takes — titles and
    // shows — while Search keeps the shorter hint. The review's prompt
    // names all three: titles, genres, or cast.
    label: String = "Search titles or people",
    /** SE-3: voice entry — null means no mic (no recognizer on this device). */
    onVoiceSearch: (() -> Unit)? = null,
    voiceSearchAvailable: Boolean = false,
) {
    TextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier.fillMaxWidth().height(52.dp)
            .semantics { contentDescription = label },
        singleLine = true,
        placeholder = { Text(label) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_tab_search), contentDescription = null) },
        trailingIcon = {
            AnimatedContent(
                targetState = query.isNotEmpty(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "searchFieldTrailing",
            ) { hasText ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (voiceSearchAvailable && onVoiceSearch != null) {
                        IconButton(onClick = onVoiceSearch) {
                            Icon(
                                painterResource(R.drawable.ic_action_mic),
                                contentDescription = "Voice search",
                            )
                        }
                    }
                    if (hasText) {
                        IconButton(onClick = { onQueryChanged("") }) {
                            Icon(
                                painterResource(R.drawable.ic_action_close),
                                contentDescription = "Clear search",
                            )
                        }
                    }
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // Session 121 (P-12): searching dismisses the keyboard so the
        // results are visible instead of sitting under it.
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        shape = RaddTokens.Shape.Pill,
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
 * SE-3 (round 2): whether this device can answer a voice search — a speech
 * recognizer must be installed. Pure over the [PackageManager] so a test can
 * pin it without an activity. A missing recognizer hides the mic entirely
 * (never a dead button), per the SE-3 decision.
 */
internal fun isVoiceSearchAvailable(packageManager: PackageManager): Boolean {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(0),
        ).isNotEmpty()
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }
}

/**
 * Motion (round 2): the entrance delay for one result row, in ms — 40ms per
 * row, capped at 300ms so a long list never waits on the animation. Pure
 * over the row's position, so it is stable across recompositions and
 * unit-testable without a composition (see SearchMotionTest).
 */
internal fun searchEntranceDelayMs(index: Int): Int = (index * 40).coerceIn(0, 300)

/**
 * Motion (round 2): one result row's entrance — a fade on the effects
 * spring plus a 24dp rise on the spatial spring, behind its delay. The
 * delay is a coroutine delay before the flip, so the springs themselves
 * stay the RaddMotion tokens. Reduce-motion renders the content directly:
 * no movement, no wait.
 */
@Composable
internal fun SearchStaggeredFade(
    delayMillis: Int,
    modifier: Modifier = Modifier,
    // Round 6 (motion): the stagger re-runs on every new query, not just on
    // entry. The key identifies the search whose results are on screen — the
    // first result's id. It changes when a new query lands with different
    // results (every row re-staggers), and stays put on appends and while
    // typing (settled rows are never re-animated by a keystroke or a page).
    // Null keeps the old entry-only behaviour (a single entrance).
    staggerKey: Any? = null,
    content: @Composable () -> Unit,
) {
    if (rememberReduceMotion()) {
        Box(modifier) { content() }
        return
    }
    // Home's rail pattern, copied exactly: under inspection mode
    // (Paparazzi) the content starts visible, so the golden shows what the
    // phone shows after the entrance, not the pre-entrance empty list.
    // Paparazzi does not set inspection mode itself — the snapshot test
    // provides it (shotSearch wraps in LocalInspectionMode provides true).
    val inInspectionMode = LocalInspectionMode.current
    var visible by remember(staggerKey) { mutableStateOf(inInspectionMode) }
    LaunchedEffect(staggerKey) {
        if (inInspectionMode) return@LaunchedEffect
        if (delayMillis > 0) delay(delayMillis.toLong())
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = RaddMotion.defaultEffects()) +
            slideInVertically(animationSpec = RaddMotion.defaultSpatial()) { it / 4 },
        exit = fadeOut(),
        modifier = modifier,
    ) { content() }
}

/** The three scopes the category tabs offer above the results (SE-1). */
internal enum class SearchCategoryTab { GENRE, LANGUAGE, YEAR }

/** Test tags for the category tabs and their quick chips (SE-1). */
internal const val CategoryTabGenreTag = "searchCategoryTabGenre"
internal const val CategoryTabLanguageTag = "searchCategoryTabLanguage"
internal const val CategoryTabYearTag = "searchCategoryTabYear"

/**
 * SE-1 (round 2): the category tabs above the results — a tab strip
 * (Genre / Language / Year) with quick chips under it, so the viewer
 * scopes the list without opening the sheet. The chips are the most-picked
 * values in each facet; the sheet still holds the full list. A tap applies
 * at once (the ViewModel re-runs the search), and the selected chip is the
 * live filter value — tapping the selected chip again clears it back to All.
 */
/**
 * Round 6 (motion): the quick chip — an M3 FilterChip that scales to 0.96
 * while pressed, on the expressive effects spring, and springs back on
 * release. The press comes from the chip's own interactionSource, so a
 * scroll swipe never triggers it. Reduce-motion holds the scale at 1 (the
 * haptic tick still confirms the press). Scope: the quick chips only — the
 * RaddChip-based chips (filter rail, suggestions) get press scale from the
 * Home branch's RaddChip change, which the mega-merge takes once.
 */
@Composable
private fun QuickFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = rememberReduceMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.96f else 1f,
        animationSpec = RaddMotion.defaultEffects(),
        label = "quickChipPressScale",
    )
    FilterChip(
        colors = raddFilterChipColors(),
        selected = selected,
        onClick = onClick,
        label = label,
        interactionSource = interactionSource,
        modifier = modifier
            .heightIn(min = 48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    )
}

@Composable
internal fun CategoryTabs(
    state: SearchUiState,
    onSelectGenre: (GenreFilter) -> Unit,
    onSelectLanguage: (LanguageFilter) -> Unit,
    onSetYearRange: (Int?, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(SearchCategoryTab.GENRE) }
    Column(modifier = modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = selectedTab.ordinal) {
            Tab(
                selected = selectedTab == SearchCategoryTab.GENRE,
                onClick = { selectedTab = SearchCategoryTab.GENRE },
                text = { Text("Genre") },
                modifier = Modifier.testTag(CategoryTabGenreTag),
            )
            Tab(
                selected = selectedTab == SearchCategoryTab.LANGUAGE,
                onClick = { selectedTab = SearchCategoryTab.LANGUAGE },
                text = { Text("Language") },
                modifier = Modifier.testTag(CategoryTabLanguageTag),
            )
            Tab(
                selected = selectedTab == SearchCategoryTab.YEAR,
                onClick = { selectedTab = SearchCategoryTab.YEAR },
                text = { Text("Year") },
                modifier = Modifier.testTag(CategoryTabYearTag),
            )
        }
        Spacer(Modifier.height(8.dp))
        // S3 (round 5): the last quick chip hard-clipped at the right edge
        // ("Thrill…") — the row rides ScrollingChipRow now, so the shared
        // RowEndFade replaces the hard clip. Start stays 0 because the
        // outer column already pads 16dp (same as the filter rail); end is
        // 16dp so the last chip never rests flush against the edge.
        // Round 6 (motion): switching tabs animates the rail — the chip row
        // cross-fades and rises on the expressive specs instead of swapping
        // instantly. AnimatedContent wraps the whole row; each tab's chips
        // stay individual Lazy items inside their own ScrollingChipRow.
        // Under inspection mode (Paparazzi) the target state shows settled,
        // so goldens are unchanged; reduce-motion cross-fades with no rise.
        val reduceMotion = rememberReduceMotion()
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(tween(RaddMotion.FadeWhenReduced)) togetherWith
                        fadeOut(tween(RaddMotion.FadeWhenReduced))
                } else {
                    (fadeIn(animationSpec = RaddMotion.defaultEffects()) +
                        slideInVertically(
                            animationSpec = RaddMotion.defaultSpatial(),
                        ) { it / 4 }) togetherWith
                        fadeOut(animationSpec = RaddMotion.defaultEffects())
                }
            },
            label = "categoryTabChips",
        ) { tab ->
            ScrollingChipRow(
                contentPadding = PaddingValues(start = 0.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                fadeTestTag = QuickChipsFadeTag,
            ) {
                when (tab) {
                    SearchCategoryTab.GENRE -> {
                        listOf(
                            GenreFilter.ACTION, GenreFilter.COMEDY, GenreFilter.DRAMA,
                            GenreFilter.HORROR, GenreFilter.THRILLER,
                        ).forEach { genre ->
                            item {
                                QuickFilterChip(
                                    selected = state.filters.genre == genre,
                                    onClick = {
                                        onSelectGenre(if (state.filters.genre == genre) GenreFilter.ALL else genre)
                                    },
                                    label = { Text(genre.label) },
                                )
                            }
                        }
                    }
                    SearchCategoryTab.LANGUAGE -> {
                        listOf(
                            LanguageFilter.HINDI, LanguageFilter.URDU, LanguageFilter.TAMIL,
                            LanguageFilter.TELUGU, LanguageFilter.PUNJABI, LanguageFilter.ENGLISH,
                        ).forEach { language ->
                            item {
                                QuickFilterChip(
                                    selected = state.filters.language == language,
                                    onClick = {
                                        onSelectLanguage(if (state.filters.language == language) LanguageFilter.ALL else language)
                                    },
                                    label = { Text(displayLanguageName(language.label)) },
                                )
                            }
                        }
                    }
                    SearchCategoryTab.YEAR -> {
                        listOf(
                            "2025" to (2025 to 2025),
                            "2024" to (2024 to 2024),
                            "2023" to (2023 to 2023),
                            "2020s" to (2020 to 2029),
                        ).forEach { (label, range) ->
                            val selected = state.filters.yearFrom == range.first &&
                                state.filters.yearTo == range.second
                            item {
                                QuickFilterChip(
                                    selected = selected,
                                    onClick = {
                                        if (selected) onSetYearRange(null, null)
                                        else onSetYearRange(range.first, range.second)
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * SE-2 (round 2): the saved-filter row above the rail — one chip per kept
 * combination, newest last. A tap re-applies the combination (the ViewModel
 * re-runs the search); the X removes it. The row only exists while at
 * least one filter is saved — the caller hides it otherwise.
 */
@Composable
internal fun SavedFilterRow(
    savedFilters: List<SavedSearchFilter>,
    onApply: (SavedSearchFilter) -> Unit,
    onRemove: (SavedSearchFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        savedFilters.forEach { saved ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(top = 4.dp, bottom = 4.dp)
                    .heightIn(min = 40.dp),
            ) {
                // The label is the apply control: its spoken name and its
                // click live on the same node, so a TalkBack double-tap on
                // the announced name applies the filter. The X is a sibling
                // of this box, not a child of the clickable — as a child,
                // the X's remove action answered the chip's own tap instead
                // of the apply action (SavedFilterRowTest, round 2).
                // No weight here: the row sits in a horizontally scrolling
                // row, whose width is unbounded, so weight(1f) measures
                // this box at zero width and the label vanishes.
                Box(
                    modifier = Modifier
                        .clickable { onApply(saved) }
                        .semantics {
                            contentDescription = "Apply saved filter ${saved.label}"
                            onClick(label = "Apply saved filter ${saved.label}") { onApply(saved); true }
                        }
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = saved.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { onRemove(saved) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_action_close),
                        contentDescription = "Remove saved filter ${saved.label}",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
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
 * The rail in one line: the Filters button first, then the Type chips the
 * viewer reaches for most (industry, genre, quality, plan, sort, years
 * live in the sheet the Filters button opens). One ungrouped ~30-chip
 * scroll buried the type choice behind captions; the sheet owns the rest.
 * Mockup (Nov 2026): All / Movies / Series / Anime / K-Pop — 48dp tall,
 * pill-shaped, Title Case like the sheet's Type group. The selected chip
 * wears the nav pill's tokens (bright-teal text on a dark-teal pill)
 * instead of the old jade fill with black text, so the active state
 * reads the same as the selected tab below it (Gemini review, Nov 2026).
 * The selected-chip scroll (merged 3bcd64de) stays: the rail scrolls just
 * enough to bring its own selection fully into view.
 * Search review (Nov 2026): the Filters button used to be the 6th item
 * in the scrolling row — fully off-screen on a 360dp phone, so the sheet
 * was undiscoverable without a swipe. It is fixed at the row's left edge
 * now, so it can never clip; the type chips scroll in the space that is
 * left, under the shared [ScrollingChipRow] fade.
 */
@Composable
internal fun FilterRail(
    state: SearchUiState,
    groups: List<TypeGroup> = emptyList(),
    onSelectType: (String?) -> Unit,
    onOpenFilters: () -> Unit,
) {
    // Robo #7: the last chip ("Complete series") was cut off at the right edge — and when it
    // was the SELECTED chip it stayed cut off ("Comp…"), because nothing ever scrolled the
    // rail to it. The rail already scrolls (LazyRow) with end padding (Session 121 P-29);
    // what it never did was reveal its own selection. When the selected type changes, scroll
    // just enough to bring that chip fully into view — nothing when it is already visible,
    // so tapping a visible chip never moves the row.
    // A4-F4: the rail shows the section that is actually on screen. With no
    // type filter set, a search that answers with a single section is that
    // section's type — "All" stays selected only while more than one section
    // (or none yet) is showing. The reveal scroll follows the same chip, so
    // an aligned selection is never itself clipped off-screen. The groups
    // arrive as a parameter now (S6): the ViewModel no longer owns the list,
    // so the screen derives them from the PagingData's snapshot and hands
    // them down.
    val effectiveType = effectiveTypeChip(state.filters.type, groups)
    val listState = rememberLazyListState()
    LaunchedEffect(effectiveType) {
        val selectedIndex = railTypeChips.indexOfFirst { it.second == effectiveType }
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
    // Motion (round 2): the rail's taps tick — the same light haptic the
    // press system uses (PressHaptics), so a chip tap feels answered.
    val haptics = LocalHapticFeedback.current
    val chipTick: () -> Unit = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Audit Search #7 (Nov 2026): this chip opens the filter sheet —
        // it is an action, not a selection — so it never wears the filled
        // selected style the "Movies" chip wears. Tonal fill + outline,
        // and the count in the label is the only active signal. The
        // spoken name still carries the count ("Filters, 3 active").
        FilterChip(
            colors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                labelColor = MaterialTheme.colorScheme.onSurface,
                // This Compose version names the unselected icon colour
                // `iconColor`, not `leadingIconColor` — the latter does
                // not exist and fails compileDebugKotlin (CI 43738577241).
                iconColor = MaterialTheme.colorScheme.onSurface,
            ),
            selected = false,
            onClick = { chipTick(); onOpenFilters() },
            label = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // S8 (round 2): the house sliders glyph, not the Material default Tune icon.
                    Icon(painterResource(R.drawable.ic_action_sliders), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(filtersLabel)
                }
            },
            shape = RoundedCornerShape(24.dp),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = false,
                borderColor = MaterialTheme.colorScheme.outlineVariant,
                selectedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                borderWidth = 1.dp,
                selectedBorderWidth = 1.dp,
            ),
            // Audit #54: a FilterChip is 32 dp tall — the touch-target floor is 48 dp, so the chip itself carries it.
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = filtersA11y },
        )
        // A4-F3: the scrolling row is ScrollingChipRow now — Home's
        // pattern, shared: the row is fillMaxWidth inside the Box and the
        // fade is an overlay aligned to its end — the LazyRow's own height
        // is its content, so the Box stays 48dp.
        ScrollingChipRow(
            modifier = Modifier.weight(1f),
            state = listState,
            // Session 121 (P-29): the last chip ("Anime") was clipped at the right edge —
            // the row needs end padding; start stays 0 because the outer column already
            // pads 16 dp.
            contentPadding = PaddingValues(start = 0.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            fadeTestTag = FilterRailFadeTag,
        ) {
            items(railTypeChips) { (label, value) ->
                val selected = effectiveType == value
                RaddChip(
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    selected = selected,
                    onClick = { chipTick(); onSelectType(value) },
                    label = {
                        Text(
                            label,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    shape = RoundedCornerShape(24.dp),
                    // CL-3, same as the sheet: the rail's "All" chip is the Type
                    // group's "All", and it must SAY so — the spoken name is
                    // "All types", not the bare label.
                    modifier = if (label == "All") {
                        Modifier.semantics { contentDescription = "All types" }
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

/**
 * Mockup (Nov 2026): recent searches as a list, not a chip rail — one row
 * per search, clock icon, tap to run it, × to drop that row. Used on the
 * discovery page and under a pasted link, where it is the way back.
 *
 * The tags let a test tap the clickable owners themselves — the row and
 * its × — instead of a child Text/Icon whose tap has to resolve upward
 * (see SearchDiscoveryTest: the row sits below the fold in the short
 * Robolectric window, so the test scrolls first, then taps the tag).
 */
internal const val RecentRowTagPrefix = "recent:"
internal const val RemoveRecentTagPrefix = "remove-recent:"

@Composable
internal fun RecentsSection(state: SearchUiState, onRunRecent: (String) -> Unit, onRemoveRecent: (String) -> Unit) {
    RecentsList(
        recents = state.visibleRecents,
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
                    .testTag("$RecentRowTagPrefix$recent")
                    .clickable { onRunRecent(recent) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    // S8 (round 2): the house history glyph, not the Material default History icon.
                    painterResource(R.drawable.ic_action_history),
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
                IconButton(
                    onClick = { onRemoveRecent(recent) },
                    modifier = Modifier.testTag("$RemoveRecentTagPrefix$recent"),
                ) {
                    Icon(
                        // S8 (round 2): the house close glyph — and SE-7: the
                        // TalkBack label says what removing removes.
                        painterResource(R.drawable.ic_action_close),
                        contentDescription = "Remove $recent",
                    )
                }
            }
        }
    }
}

/** Test tag prefix for one instant local row, so a test can tap the row itself. */
internal const val LocalResultRowTagPrefix = "local-result:"

/**
 * Feel-instant S3a/S3c: the instant local answer — titles this device
 * already knows, painted while the server search is still out (or after
 * it failed). One row per title: a small poster, the title, and the year
 * + type line the rail already paints. A tap runs the server search for
 * that title — the index carries no id to open, so the row is a question
 * restated, not a page.
 */
@Composable
internal fun LocalSearchResults(
    results: List<LocalSearchResult>,
    onRunSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader(
            title = "On this device",
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
        results.forEach { result ->
            val metaLine = remember(result) {
                listOfNotNull(
                    displayYearLabel(result.year, currentCalendarYear()),
                    normalizeTypeLabel(result.type).takeIf { it != "Other" },
                ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .testTag("$LocalResultRowTagPrefix${result.title}")
                    .clickable { onRunSuggestion(result.title) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CatalogImage(
                    url = result.posterUrl,
                    contentDescription = null, // the title beside it says which title this is
                    modifier = Modifier
                        .width(44.dp)
                        .aspectRatio(CardGeometry.POSTER_ASPECT)
                        .clip(RoundedCornerShape(6.dp)),
                    fallbackLabel = result.title,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        result.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    metaLine?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Test tag on the discovery page's loading skeleton, so a test can tell loading from the settled hint. */
internal const val SearchDiscoverySkeletonTag = "search-discovery-skeleton"

/**
 * Search review (Nov 2026), fix 4: the discovery page's loading stand-in —
 * a Continue Watching row skeleton, a "Trending now" rail skeleton and a
 * "For you" grid skeleton in the same slots the real sections take, so
 * nothing reflows when the data lands. Hand-built rows, not
 * PosterRailSkeleton/PosterGridSkeleton: those are lazy layouts and this
 * column scrolls — a lazy child inside it measures with infinite height
 * (Robo #6, the same crash the sheet's heightIn(max = 1200.dp) cap fixed).
 * The cells themselves are the shared [PosterCellSkeleton], so a discovery
 * cell and a rail cell are the same height by construction, not by a second
 * set of numbers.
 */
@Composable
internal fun SearchDiscoverySkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag(SearchDiscoverySkeletonTag),
    ) {
        // P1-12: the Continue Watching slot sits first — a header bar and
        // one thumb row — so the skeleton keeps the section order the
        // settled page shows.
        Box(Modifier.fillMaxWidth().padding(top = 16.dp).padding(vertical = 8.dp)) {
            SkeletonBox(
                Modifier.width(160.dp).height(24.dp),
                shape = RoundedCornerShape(4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonBox(
                Modifier.width(96.dp).height(144.dp),
                shape = RoundedCornerShape(8.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.height(8.dp))
                SkeletonBox(
                    Modifier.fillMaxWidth(0.6f).height(16.dp),
                    shape = RoundedCornerShape(4.dp),
                )
                SkeletonBox(
                    Modifier.fillMaxWidth(0.4f).height(12.dp),
                    shape = RoundedCornerShape(4.dp),
                )
                SkeletonBox(
                    Modifier.fillMaxWidth(0.8f).height(4.dp),
                    shape = RoundedCornerShape(2.dp),
                )
            }
        }
        // Header slots: shimmer bars where "Trending now" and
        // "For you" will sit. A SectionHeader is its 24dp
        // title with 8dp top and bottom padding, under the section's own
        // top padding — the old 20dp bars left each header 12dp short.
        // The bar carries the header's own padding, then the 8dp gap to
        // the section below it.
        Box(Modifier.fillMaxWidth().padding(top = 16.dp).padding(vertical = 8.dp)) {
            SkeletonBox(
                Modifier.width(120.dp).height(24.dp),
                shape = RoundedCornerShape(4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) {
                PosterCellSkeleton(Modifier.width(CardGeometry.RAIL_CARD_WIDTH_DP.dp))
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 20.dp).padding(vertical = 8.dp)) {
            SkeletonBox(
                Modifier.width(170.dp).height(24.dp),
                shape = RoundedCornerShape(4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        repeat(2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(3) {
                    PosterCellSkeleton(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchDiscoveryContent(
    state: SearchUiState,
    onRunQuery: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onPostClick: (Post) -> Unit,
    onSeeAllTrending: () -> Unit = {},
    onSeeAllRecommended: () -> Unit = {},
    // P1-12: the empty state's action chips apply a genre at once — the
    // same callback the results list's category tabs already take.
    onSelectGenre: (GenreFilter) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Search review (Nov 2026), fix 4: trending + recommended load once in
    // init and fail silent — on a slow or failed load the page used to show
    // the bare hint at once, as if the catalog were empty. Until the first
    // load settles (success or failure) the page shows section skeletons
    // instead; the hint waits for the settle, so it only ever means "the
    // catalog really has nothing to show". A section that already has data
    // (recents are local and land first) shows its data, never the skeleton.
    if (state.discoveryLoading && state.visibleContinueWatching.isEmpty() && state.visibleTrending.isEmpty() && state.visibleRecents.isEmpty() && state.visibleRecommended.isEmpty()) {
        SearchDiscoverySkeleton(modifier)
        return
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        // P1-12 (Nov 2026): the discovery page is four sections, top to
        // bottom — "Continue watching", "Trending now" rail, "For you"
        // 3-column grid, "Recent searches" list. Continue Watching is the
        // highest-CTR module in streaming, so it leads; Recent searches is
        // a utility, not merchandising, so it sits last. The old "Trending
        // searches" chips and "Browse by genre" are gone: the mockup has
        // neither, and the genre filter still lives in the sheet. Each
        // section hides when its data is empty, so a fresh install shows
        // the hint only.
        if (state.visibleContinueWatching.isNotEmpty()) {
            SearchContinueWatchingSection(
                tiles = state.visibleContinueWatching,
                onPostClick = onPostClick,
            )
        }
        if (state.visibleTrending.isNotEmpty()) {
            SectionHeader(
                title = "Trending now",
                modifier = Modifier.padding(top = 16.dp),
                action = { SeeAllButton(onClick = onSeeAllTrending, tag = SeeAllTrendingTag) },
            )
            Spacer(Modifier.height(8.dp))
            // The outer column already pads 16dp: the rail starts at 0 and
            // keeps 16dp at the end, so the last card never sits flush
            // against the screen edge when fully scrolled.
            MediaRail(items = state.visibleTrending, key = { it.id }, contentPadding = PaddingValues(start = 0.dp, end = 16.dp)) { post ->
                PosterCard(post = post, onClick = { onPostClick(post) })
            }
        }
        if (state.visibleRecommended.isNotEmpty()) {
            SectionHeader(
                title = "For you",
                modifier = Modifier.padding(top = 20.dp),
                action = { SeeAllButton(onClick = onSeeAllRecommended, tag = SeeAllRecommendedTag) },
            )
            Spacer(Modifier.height(8.dp))
            state.visibleRecommended.chunked(3).forEach { trio ->
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
        if (state.visibleRecents.isNotEmpty()) {
            RecentsList(
                recents = state.visibleRecents,
                onRunRecent = onRunQuery,
                onRemoveRecent = onRemoveRecent,
            )
        }
        if (state.visibleContinueWatching.isEmpty() && state.visibleTrending.isEmpty() && state.visibleRecents.isEmpty() && state.visibleRecommended.isEmpty()) {
            Spacer(Modifier.height(28.dp))
            // P1-12: the empty discovery state carries an action, not a
            // decorative mark — the jade ring + 64dp search glyph is gone.
            // The four most-picked genres are chips: a tap applies the
            // genre and runs the search, so the page never dead-ends on a
            // bare hint.
            Text(
                text = "Search for a show, movie, or genre",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Search titles and people — or start from a genre, a language, or a year",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(GenreFilter.ACTION, GenreFilter.COMEDY, GenreFilter.DRAMA, GenreFilter.THRILLER).forEach { genre ->
                    RaddChip(
                        colors = raddFilterChipColors(),
                        selected = false,
                        onClick = { onSelectGenre(genre) },
                        label = { Text(genre.label) },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * P1-12 (Nov 2026): Continue Watching leads the discovery page — the same
 * resumable titles Home's rail shows, in the same order, capped at the
 * same three rows. A tap opens the title; there is no remove × here —
 * removal (with Undo) lives on Home's rail, and this section is a way back
 * into a title, not a second ledger.
 */
@Composable
internal fun SearchContinueWatchingSection(
    tiles: List<ResumeTile>,
    onPostClick: (Post) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionHeader(
            title = "Continue watching",
            modifier = Modifier.padding(top = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        tiles.take(3).forEach { tile ->
            SearchContinueWatchingRow(
                tile = tile,
                onClick = { onPostClick(tile.post) },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * P1-12: one Continue Watching row — Home's row without the remove ×:
 * thumb, title, rating, resume label, jade progress bar. A row the app
 * cannot route to a work never reaches here ([ResumeTile.of] drops it),
 * so the tap always has somewhere to go.
 */
@Composable
internal fun SearchContinueWatchingRow(
    tile: ResumeTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RaddTokens.Shape.Card)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(RaddTokens.Space.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbModifier = Modifier
            .width(CardGeometry.CONTINUE_WATCHING_ROW_THUMB_WIDTH_DP.dp)
            .aspectRatio(CardGeometry.POSTER_ASPECT)
            .clip(RaddTokens.Shape.Input)
        if (tile.post.posterUrl != null) {
            CatalogImage(
                url = tile.post.posterUrl,
                contentDescription = null, // the title beside it says which title this is
                fallbackLabel = tile.post.displayTitle,
                modifier = thumbModifier,
            )
        } else {
            // No art: a labelled plate in the thumb's place, never a void.
            Box(
                modifier = thumbModifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tile.post.displayTitle.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f).padding(start = RaddTokens.Space.Md),
            verticalArrangement = Arrangement.spacedBy(RaddTokens.Space.Xs),
        ) {
            Text(
                text = tile.post.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // E2 Ratings (Nov 2026): the rating rides one small line under
            // the title — and only when the row actually carries one.
            UserWords.displayRating(tile.post.imdbRating)?.let { rating ->
                Text(
                    text = "★ $rating",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            tile.resumeLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            tile.progressFraction?.let { fraction ->
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .semantics {
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                fraction,
                                0f..1f,
                            )
                            contentDescription = tile.resumeLabel
                                ?: "Continue watching progress"
                        },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface,
                )
            }
        }
    }
}

/**
 * Search review (Nov 2026): "See all" on a discovery section header —
 * muted text + chevron, the Stitch redesign's treatment. A TextButton
 * with the 48dp touch floor; the tap expands the section in Browse.
 */
@Composable
private fun SeeAllButton(onClick: () -> Unit, tag: String) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.testTag(tag).heightIn(min = 48.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 0.dp, top = 0.dp, bottom = 0.dp),
    ) {
        Text(
            text = "See all",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            // S8 (round 2): the house chevron glyph, not the Material default ChevronRight icon.
            painterResource(R.drawable.ic_action_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Feel-instant S6: the results groups, derived from the PagingData's loaded
 * snapshot. The ViewModel no longer owns the list, so the grouping lives
 * here, next to the snapshot: the year range filters the loaded rows, then
 * [groupSearchResults] files them into type rails — the same two steps the
 * old `recompute()` ran over the accumulated rows. Moving the range
 * re-groups what is on screen without a refetch, exactly as before.
 *
 * The last loaded rows stay on screen while a fresh search refreshes
 * (S3a): a new PagingData starts empty, and without the hold the previous
 * results would vanish into the full skeleton on every keystroke and
 * filter tap. The hold releases the moment the refresh lands — with rows,
 * or with none (then the empty state shows, not the stale list).
 */
@Composable
private fun rememberSearchGroups(
    pagingItems: LazyPagingItems<Post>,
    state: SearchUiState,
): List<TypeGroup> {
    val snapshotItems = pagingItems.itemSnapshotList.items.filterNotNull()
    var lastLoaded by remember { mutableStateOf<List<Post>>(emptyList()) }
    if (snapshotItems.isNotEmpty()) lastLoaded = snapshotItems
    val isRefreshing = pagingItems.loadState.refresh is LoadState.Loading
    val displayItems = when {
        snapshotItems.isNotEmpty() -> snapshotItems
        isRefreshing -> lastLoaded
        else -> emptyList()
    }
    return remember(displayItems, state.sort, state.filters) {
        groupSearchResults(filterByYearRange(displayItems, state.filters), state.sort)
    }
}

/**
 * APP-07 (Phase 4): the no-results state is a way forward, not a dead end.
 * A completed search that finds nothing offers, in order: a spelling tip
 * plus the server's did-you-mean as a real action (painted primary, so it
 * reads as a tap, not a label — the results list's plain-text did-you-mean
 * is audit Search #4 and stays as it is); a fresh start ("Search another
 * title") and, when filters are on, "Clear filters"; and the catalog itself
 * — the "Trending now" rail the viewer already knows from discovery, or a
 * "Browse trending" button when the discovery load came back empty (Browse
 * is a fresh load, so it still works). The message names the active filters
 * (audit Search #6) instead of "with the current filters".
 *
 * Deliberately absent: "Request this title". There is no reachable request
 * flow to wire it to — the app has no request API method, screen, or
 * navigation for one, and the server route (`POST .../coins/request-movie`,
 * `accounts_api.py`) costs 300 coins and answers coins-off with no request
 * queued, while coins were removed from the app 2026-11-17. A button with
 * nowhere to go would be a second dead end. Wiring a request action needs
 * an owner decision first: a coin-free request route server-side plus an
 * app flow for it (DB-02 baseline note: 0 movie requests recorded).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchNoResultsContent(
    state: SearchUiState,
    onQueryChanged: (String) -> Unit,
    onResetFilters: () -> Unit,
    onRunSuggestion: (String) -> Unit,
    onPostClick: (Post) -> Unit,
    onSeeAllTrending: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val filterNames = activeFilterSummary(state.filters)
    Column(
        // SE-5 (round 2): the no-results root is a polite live region — a
        // TalkBack viewer hears "No matches" + the explanation when the
        // search lands empty, without the focus being stolen.
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .testTag(NoResultsTag)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        // APP-07 (round 2): the no-results state is illustrated, not a bare
        // icon — the H6 pattern: a 120dp jade-ringed circle holding a 64dp
        // house search mark. The same art as the discovery empty state,
        // so the two empty states read as one family.
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(118.dp)
                    .clip(CircleShape)
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_tab_search),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No matches",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                state.query.isNotBlank() && filterNames.isNotEmpty() ->
                    "No titles matched \"${state.query}\" with ${filterNames.joinToString(" · ")} filters."
                state.query.isNotBlank() -> "No titles matched \"${state.query}\"."
                filterNames.isNotEmpty() ->
                    "Nothing in the catalog matched ${filterNames.joinToString(" · ")}."
                else -> "Nothing in the catalog matched these filters."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.query.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Check the spelling, or try fewer words.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.didYouMean?.let { suggestion ->
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { onRunSuggestion(suggestion) },
                modifier = Modifier.heightIn(min = 48.dp).testTag(NoResultsDidYouMeanTag),
            ) {
                Text(
                    text = "Did you mean $suggestion?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { onQueryChanged("") },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Search another title")
            }
            if (state.hasActiveFilters) {
                OutlinedButton(
                    onClick = onResetFilters,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Clear filters")
                }
            }
        }
        if (state.visibleTrending.isNotEmpty()) {
            SectionHeader(
                title = "Trending now",
                modifier = Modifier.padding(top = 24.dp),
                action = { SeeAllButton(onClick = onSeeAllTrending, tag = SeeAllTrendingTag) },
            )
            MediaRail(items = state.visibleTrending, key = { it.id }, contentPadding = PaddingValues(start = 0.dp, end = 16.dp)) { post ->
                PosterCard(post = post, onClick = { onPostClick(post) })
            }
        } else {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSeeAllTrending,
                modifier = Modifier.heightIn(min = 48.dp).testTag(NoResultsBrowseTrendingTag),
            ) {
                Text("Browse trending")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Test tags for the no-results state (APP-07). */
internal const val NoResultsTag = "searchNoResults"
internal const val NoResultsDidYouMeanTag = "searchNoResultsDidYouMean"
internal const val NoResultsBrowseTrendingTag = "searchNoResultsBrowseTrending"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchContent(
    state: SearchUiState,
    pagingItems: LazyPagingItems<Post>,
    groups: List<TypeGroup>,
    onRunQuery: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onResetFilters: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onShowCloseMatches: () -> Unit,
    onShowExactOnly: () -> Unit,
    onRunSuggestion: (String) -> Unit,
    onPostClick: (Post) -> Unit,
    onOpenSettings: () -> Unit = {},
    onSeeAllTrending: () -> Unit = {},
    onSeeAllRecommended: () -> Unit = {},
    // SE-1 (round 2): the category tabs scope the results — genre,
    // language and year, one tap each, from the results list itself.
    onSelectGenre: (GenreFilter) -> Unit = {},
    onSelectLanguage: (LanguageFilter) -> Unit = {},
    onSetYearRange: (Int?, Int?) -> Unit = { _, _ -> },
    // Round 6 motion infra: the shared-transition scopes the NavHost
    // passes in (via SearchScreen). Null outside navigation (Paparazzi
    // previews, tests) — the poster shared element no-ops then.
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
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
    // Feel-instant S6: the list states come from the PagingData's loadState
    // now, not a ViewModel list. The "Load more" / "Show all" footer is
    // gone — the list pages itself as the viewer scrolls, and the tail of
    // the list is skeleton rows while the next page loads, with an inline
    // retry when a page fails.
    val refreshState = pagingItems.loadState.refresh
    val appendState = pagingItems.loadState.append
    val isRefreshing = refreshState is LoadState.Loading
    // Round 6 (motion): the stagger key — the first result's id. It changes
    // when a new query lands with different results (re-staggering every
    // row) and stays put on appends and while typing (settled rows stay
    // settled). See SearchStaggeredFade.
    val staggerKey = groups.firstOrNull()?.items?.firstOrNull()?.id
    when {
        !state.hasSearchIntent -> {
            SearchDiscoveryContent(
                state = state,
                onRunQuery = onRunQuery,
                onRemoveRecent = onRemoveRecent,
                onPostClick = onPostClick,
                onSeeAllTrending = onSeeAllTrending,
                onSeeAllRecommended = onSeeAllRecommended,
                onSelectGenre = onSelectGenre,
            )
        }
        isRefreshing && groups.isEmpty() && !state.hasLocalResults -> SearchResultsSkeleton(
            // The results arrive as a summary line, a group header and a
            // 2-wide grid — the bare grid skeleton let the summary and the
            // header appear out of nowhere and push every row down.
            // Feel-instant S3a/S3c: when the device already knows titles
            // for this query, those rows are the loading state — a skeleton
            // over them would hide the instant answer.
        )
        refreshState is LoadState.Error && groups.isEmpty() && !state.hasLocalResults -> ErrorState(
            title = "Couldn't search",
            message = friendlyNetworkError(refreshState.error, "Search failed. Check the server connection."),
            onRetry = { pagingItems.retry() },
            // Audit Search #3: the message sends the viewer to Settings —
            // the screen offers the door, not just the direction.
            secondaryLabel = "Open Settings",
            onSecondary = onOpenSettings,
        )
        // Feel-instant S3a/S3c: the instant local answer — titles this
        // device already knows, painted while the server search is still
        // out (or after it failed: a dead connection still answers from
        // the device). A tap runs the server search for that title; the
        // index carries no id to open.
        state.hasLocalResults && groups.isEmpty() -> LocalSearchResults(
            results = state.visibleLocalResults,
            onRunSuggestion = onRunSuggestion,
        )
        // Audit #36: "No matches" only after a completed search returns zero
        // rows — never while a search is still in flight (the skeleton owns
        // that window), and never before the first search.
        groups.isEmpty() && state.hasSearched && !isRefreshing -> SearchNoResultsContent(
            state = state,
            onQueryChanged = onQueryChanged,
            onResetFilters = onResetFilters,
            onRunSuggestion = onRunSuggestion,
            onPostClick = onPostClick,
            onSeeAllTrending = onSeeAllTrending,
        )
        state.isFuzzy && state.showExactOnly -> EmptyState(
            // Exact-only on a fuzzy page is the empty state: every row the
            // server sent is one-edit-away, so there is nothing exact to
            // list — and the way back is right here.
            title = "No exact matches",
            message = "No titles exactly match \"${state.query}\".",
            // S8 (round 2): the house search glyph, not the Material default Search icon.
            icon = { Icon(painterResource(R.drawable.ic_tab_search), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp)) },
            action = {
                OutlinedButton(onClick = onShowCloseMatches) { Text("Show close matches") }
            },
        )
        else -> {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag(SearchResultsListTag),
            ) {
                // SE-1 (round 2): the category tabs sit above the results — a
                // tab strip (Genre / Language / Year) with quick chips under it,
                // so the viewer scopes the list without opening the sheet.
                item {
                    CategoryTabs(
                        state = state,
                        onSelectGenre = onSelectGenre,
                        onSelectLanguage = onSelectLanguage,
                        onSetYearRange = onSetYearRange,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                }
                // Feel-instant S3a: the previous results stay on screen while a
                // fresh search runs — and this one skeleton row on top is the
                // signal that they are refreshing, so the stale list below it
                // is never mistaken for the new answer.
                if (isRefreshing) {
                    item {
                        SkeletonBox(
                            Modifier.fillMaxWidth(0.55f).height(20.dp).padding(vertical = 8.dp)
                                .testTag("searchRefreshingRow"),
                            shape = RoundedCornerShape(8.dp),
                        )
                    }
                }

                // P1-11 (Nov 2026): one inline fuzzy banner — the single
                // recovery path for a one-edit-away answer. It replaces all
                // three old paths at once: the fuzzy notice, the
                // did-you-mean chip and the "Show exact matches only"
                // OutlinedButton (an outlined button is high visual weight
                // for a rare toggle). The exact-only action is an inline
                // text link inside the banner, not a button. The result
                // count ("Showing 34 of 52 results") is gone entirely —
                // the filter rail carries count context, and the count
                // broke the moment pagination appended. The banner is the
                // page's polite live region now: a TalkBack viewer hears
                // what happened when the page lands, without the focus
                // being stolen. A did-you-mean spelling with no fuzzy
                // answer has no banner — the results themselves are the
                // answer then — and the no-results state's own
                // "Did you mean …?" action is untouched.
                if (state.isFuzzy) {
                    item {
                        FuzzyBanner(
                            query = state.query,
                            onShowExactOnly = onShowExactOnly,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }

                groups.forEachIndexed { groupIndex, group ->
                    item {
                        // A4-F4: the header names the section, nothing more.
                        // The old "N titles" subtitle counted a third
                        // population (loaded works in this section) and read
                        // as a competing total next to the result count —
                        // both are gone now (P1-11 deletes the count line),
                        // so the header is the only label a section carries.
                        SectionHeader(
                            title = group.label,
                            modifier = Modifier.padding(horizontal = 0.dp),
                        )
                    }
                    // P0-6 (2027 Home fix): results are a scannable vertical grid, three posters
                    // wide — rails are for Home, and one density rules everywhere (the
                    // discovery "For you" grid was already 3-wide). Scanning 20
                    // results no longer means swiping every rail sideways.
                    // Session 158 (S5): a row is keyed by what it holds, not
                    // where it sits — a re-emitted list (the same search
                    // landing twice, a second identical submit) rebuilds the
                    // groups, and an index key lets the rows shift under a
                    // press in flight. A content key keeps the row — and the
                    // press on it — alive across the re-emission.
                    // Motion (round 2): the rows stagger in — each trio fades
                    // and rises 24dp behind the one above it, on the emphasized
                    // spring, capped at 300ms so a long list never waits on the
                    // animation. The delay is pure over the row's position, so
                    // it is stable across recompositions.
                    group.items.chunked(3).forEachIndexed { rowIndex, trio ->
                        item(key = "${group.typeKey}-${trio.joinToString(separator = ",") { it.id.toString() }}") {
                            SearchStaggeredFade(
                                delayMillis = searchEntranceDelayMs(groupIndex * 10 + rowIndex),
                                // Round 6 (motion): re-staggers on every
                                // new query — see the staggerKey above.
                                staggerKey = staggerKey,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    trio.forEach { post ->
                                        // Screen audit #7 (Nov 2026): result tiles
                                        // get two title lines and no bare-type meta
                                        // — a long title reads whole under the
                                        // "Series" header instead of truncating to
                                        // "One Piece ·…", and the "Series" line
                                        // under it no longer restates the header.
                                        // The discovery rails above keep the
                                        // one-line tile and the full meta line.
                                        // Round 6 (motion): the poster carries the
                                        // shared element to the Detail hero — key
                                        // "poster-<workId>", one key per work,
                                        // shared by source and destination. The
                                        // scopes come from the NavHost (via
                                        // SearchContent params); the infra helper
                                        // no-ops when either is null.
                                        val posterModifier = Modifier.weight(1f).posterSharedElement(
                                            key = "poster-${post.workId ?: post.id}",
                                            sharedTransitionScope = sharedTransitionScope,
                                            animatedVisibilityScope = animatedVisibilityScope,
                                        )
                                        PosterCard(
                                            post = post,
                                            onClick = { onPostClick(post) },
                                            modifier = posterModifier,
                                            titleMaxLines = 2,
                                            suppressBareTypeMeta = true,
                                        )
                                    }
                                    // A short last row holds its width: empty
                                    // cells keep the row's posters at grid
                                    // width instead of stretching to fill it.
                                    repeat(3 - trio.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }

                // Feel-instant S6: the next page arrives as the viewer scrolls,
                // and while it is out the tail of the list is skeleton rows —
                // the S5 geometry-matched cells, two rows of two, so the grid
                // never jumps when the page lands. A failed page used to be
                // invisible: the retry path was reachable only when every group
                // was empty, so a search that died on page 3 looked like a
                // catalog that simply had three pages. The retry is inline now.
                when (appendState) {
                    is LoadState.Loading -> {
                        repeat(SEARCH_APPEND_SKELETON_ROWS) { row ->
                            item(key = "search-append-skeleton-$row") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    PosterCellSkeleton(
                                        Modifier.weight(1f).testTag("searchAppendSkeleton"),
                                    )
                                    PosterCellSkeleton(
                                        Modifier.weight(1f).testTag("searchAppendSkeleton"),
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                    is LoadState.Error -> {
                        item {
                            InlineLoadFailure(
                                message = friendlyNetworkError(appendState.error, "Could not load more."),
                                onRetry = { pagingItems.retry() },
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

/** Skeleton rows the search list stands in with while the next page loads — two rows of two cells. */
private const val SEARCH_APPEND_SKELETON_ROWS = 2

@Composable
private fun FilterSheetContent(
    state: SearchUiState,
    viewModel: SearchViewModel,
    onDone: () -> Unit,
    // SE-2 (round 2): the saved-filter state the footer Save action reads.
    savedFilters: List<SavedSearchFilter> = emptyList(),
    onSaveFilter: () -> Unit = {},
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
        savedFilters = savedFilters,
        onSaveFilter = onSaveFilter,
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
    // SE-2 (round 2): the saved-filter state the footer Save action reads.
    savedFilters: List<SavedSearchFilter> = emptyList(),
    onSaveFilter: () -> Unit = {},
) {
    // F10 (Batch B): the sheet is two parts — a scrolling list of sections
    // and a footer that never scrolls away. The old sheet scrolled the
    // Apply button off the bottom on a small phone, so the only way to
    // leave was the swipe-down the re-review called out as hard to find.
    // The footer sits under a hairline, always in reach.
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
                isSelected = { option ->
                    // Audit Search #9: "Complete series" is a switch below
                    // now, and complete series are series — the Series chip
                    // reads selected while the switch is on, so the sheet
                    // never shows no Type active with a type filter applied.
                    if (option.second == "series") {
                        state.filters.type == "series" || state.filters.type == "complete_season"
                    } else {
                        state.filters.type == option.second
                    }
                },
                onSelect = { onSelectType(it.second) },
                allDescription = "All types",
                icon = R.drawable.ic_action_grid,
            )
            // Audit Search #9: "Complete series" is a type value
            // (content_type complete_season), not a boolean — but it sat
            // as a lone wrapped row inside the Type chip grid. It is a
            // switch now, refining Series: on selects complete_season,
            // off returns to plain Series. The Switch itself carries the
            // toggle (Settings' SwitchRow pattern: the painted labels are
            // paint-only, the control announces label + state).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Complete series only",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Text(
                        "Only series with every episode available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
                Switch(
                    checked = state.filters.type == "complete_season",
                    onCheckedChange = { checked ->
                        onSelectType(if (checked) "complete_season" else "series")
                    },
                    modifier = Modifier.semantics { contentDescription = "Complete series only" },
                )
            }
            HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            FacetChips(
                title = "Industry",
                options = IndustryFilter.entries,
                label = { it.label },
                isSelected = { state.filters.industry == it },
                onSelect = onSelectIndustry,
                allDescription = "All industries",
                icon = R.drawable.ic_action_film,
            )
            FacetChips(
                title = "Genre",
                options = GenreFilter.entries,
                label = { it.label },
                isSelected = { state.filters.genre == it },
                onSelect = onSelectGenre,
                allDescription = "All genres",
                icon = R.drawable.ic_action_tv,
            )
            // Audit Search #10 (Nov 2026): Language was a collapsed "All ⌄"
            // dropdown while every other section shows chips. The chips are
            // visible now, like the rest — eleven chips in a FlowRow, the
            // longest group in the sheet, but the sheet scrolls and the fold
            // hid the section whose value the viewer picks most.
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
                icon = R.drawable.ic_action_globe,
            )
            FacetChips(
                title = "Quality",
                options = QualityFilter.entries,
                label = { it.label },
                isSelected = { state.filters.quality == it },
                onSelect = onSelectQuality,
                allDescription = "All qualities",
                icon = R.drawable.ic_action_award,
            )
            if (!state.isVip) {
                FacetHeader(title = "Plan", icon = R.drawable.ic_action_credit_card)
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

            FacetHeader(title = "Sort results", icon = R.drawable.ic_action_sort)
            SearchSort.entries.forEach { sort ->
                SortOptionRow(
                    sort = sort,
                    selected = state.sort == sort,
                    onSelect = onSelectSort,
                )
            }
            HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)

            FacetHeader(title = "Year range", icon = R.drawable.ic_action_calendar)
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
        // SE-2 (round 2): "Save this filter" — keeps the current combination
        // as a one-tap row above the results. Disabled while nothing is
        // active (there is nothing to keep) and at the cap of 8 saved.
        OutlinedButton(
            onClick = onSaveFilter,
            enabled = state.hasActiveFilters && savedFilters.size < MaxSavedFilters,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
        ) {
            Text("Save this filter")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Audit Search #11 (Nov 2026): Reset was tiny plain text next
            // to the large filled Apply pill. It is a real button now —
            // outlined, 48dp, the same footer weight as Apply.
            OutlinedButton(
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
    // row. S8 (round 2): a house drawable resource, not a Material
    // default vector — the sheet is part of the icon system now.
    @DrawableRes icon: Int? = null,
) {
    FacetHeader(title = title, icon = icon)
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
private fun FacetHeader(title: String, @DrawableRes icon: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(
                painterResource(icon),
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
