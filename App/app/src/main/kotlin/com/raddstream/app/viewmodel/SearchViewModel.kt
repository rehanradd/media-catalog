package com.raddstream.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.data.local.CatalogFtsDao
import com.raddstream.app.data.local.buildFtsPrefixQuery
import com.raddstream.app.data.normalizeSearchRecents
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.SuggestItem
import com.raddstream.app.network.models.WorkType
import com.raddstream.app.player.PastedText
import com.raddstream.app.player.classifyPastedText
import com.raddstream.app.repository.CATALOG_PAGING_CONFIG
import com.raddstream.app.repository.CatalogPageMeta
import com.raddstream.app.repository.CatalogPagingSource
import com.raddstream.app.repository.CatalogRepository
import com.raddstream.app.sync.SyncAccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Rich search built on what `GET /api/v1/posts` can do today.
 *
 *  - **Server-driven:** free-text `q` (debounced), `type`, and the industry /
 *    genre / quality / sort facets. The backend composes them into SQL before
 *    paging, so a chip narrows the whole catalog rather than only the rows this
 *    device has already loaded. The sort is sent with every page fetch — it
 *    orders the page the server returns.
 *  - **Client-driven over the accumulated page(s):** the year range and the
 *    final per-group ordering. Grouping by content type is presentation only,
 *    and the order you SEE inside each group is this file's re-sort, not the
 *    server's page order: the server sort decides which rows arrive, the
 *    client sort decides how they line up on screen.
 *
 * Quality facets are an existence test over `releases.quality` on the server
 * (`4K` also matches 2160p/UHD). That is why the chip works even though the v1
 * list row itself carries no quality column.
 *
 * The default sort is [SearchSort.NEWEST], which the server answers from its own
 * index (`p.id DESC`). The alternatives are all measured on the live catalog as
 * several seconds per page — `trending`/`rating`/`title` each force a scan over the
 * whole result set before paging — so they stay opt-in and the default never pays
 * for them.
 *
 * "Most versions" names what the sort actually measures: how many prints and mirrors
 * the catalog holds for a work. The ledger has no play-count column, so no label here
 * may promise popularity — never "Trending", never "Most watched".
 */
enum class SearchSort(val label: String, val wireValue: String) {
    NEWEST("Newest", "newest"),
    MOST_SOURCES("Most versions", "trending"),
    HIGHEST_IMDB("Highest IMDb", "rating"),
    TITLE("Title A–Z", "title");

    companion object {
        val DEFAULT = NEWEST
    }
}

/**
 * The v1 `industry=` value ([wireValue]) and the v2 `industry=` values
 * ([wireValues]). They differ for South Indian: v1 answers "south_indian"
 * from its own broad OR clause (`viewer.py` `INDUSTRY_FILTER_SQL`), but v2
 * exact-matches the industry column, which carries no such value — the
 * catalog's southern industries are Tollywood, Kollywood, Mollywood and
 * Sandalwood (measured live 2026-11-15: `industry=south_indian` answers 0
 * on v2, the four values answer 878 works). Every other filter's v2 values
 * are just its v1 value.
 */
enum class IndustryFilter(
    val label: String,
    val wireValue: String?,
    val wireValues: List<String> = listOfNotNull(wireValue),
) {
    ALL("All", null),
    BOLLYWOOD("Bollywood", "bollywood"),
    HOLLYWOOD("Hollywood", "hollywood"),
    PAKISTANI("Pakistani", "pakistani"),
    SOUTH_INDIAN(
        "South Indian",
        "south_indian",
        listOf("Tollywood", "Kollywood", "Mollywood", "Sandalwood"),
    ),
    // F10 (Batch B): NO Anime here. The server answers `type=anime` and
    // `industry=anime` with the identical fragment — `(is_animated = 1 OR
    // industry LIKE '%anime%')` (Fast_Fetch/viewer.py
    // `_facet_filter_clauses`) — so the two doors selected the same rows
    // and the sheet showed the same choice twice. Anime lives under Type,
    // where it belongs: it is a format, not an industry.
}

enum class GenreFilter(val label: String, val wireValue: String?) {
    ALL("All", null),
    ACTION("Action", "action"),
    ADVENTURE("Adventure", "adventure"),
    ANIMATION("Animation", "animation"),
    COMEDY("Comedy", "comedy"),
    CRIME("Crime", "crime"),
    DRAMA("Drama", "drama"),
    HORROR("Horror", "horror"),
    ROMANCE("Romance", "romance"),
    SCI_FI("Sci-Fi", "sci-fi"),
    THRILLER("Thriller", "thriller"),
}

enum class QualityFilter(val label: String, val wireValue: String?) {
    ALL("All", null),
    UHD_4K("4K", "4k"),
    FULL_HD("1080p", "1080p"),
    HD("720p", "720p"),
}

/**
 * UI-fix P1-H5/P1-S2: the language filter the server already answers
 * (`language=hindi` → 7 of 9 Salaar) but the app never exposed. Desi-first
 * first: Hindi, Urdu, Tamil, Telugu, Punjabi, Bengali, Marathi, Malayalam,
 * Kannada, Gujarati, then English. The v2 search route takes no language,
 * so a language search stays on the v1 route — like the free-only filter.
 */
enum class LanguageFilter(val label: String, val wireValue: String?) {
    ALL("All", null),
    HINDI("Hindi", "hindi"),
    URDU("Urdu", "urdu"),
    TAMIL("Tamil", "tamil"),
    TELUGU("Telugu", "telugu"),
    PUNJABI("Punjabi", "punjabi"),
    BENGALI("Bengali", "bengali"),
    MARATHI("Marathi", "marathi"),
    MALAYALAM("Malayalam", "malayalam"),
    KANNADA("Kannada", "kannada"),
    GUJARATI("Gujarati", "gujarati"),
    ENGLISH("English", "english"),
}

data class SearchFilters(
    val type: String? = null,
    val industry: IndustryFilter = IndustryFilter.ALL,
    val genre: GenreFilter = GenreFilter.ALL,
    val quality: QualityFilter = QualityFilter.ALL,
    val language: LanguageFilter = LanguageFilter.ALL,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    /**
     * "Included in my plan": only works with at least one free print. A
     * server filter, so it narrows the whole catalog — and a VIP viewer
     * never sees the chip at all, because for them everything is included.
     */
    val includedOnly: Boolean = false,
) {
    val anyServerFilterActive: Boolean
        get() = industry != IndustryFilter.ALL ||
            genre != GenreFilter.ALL ||
            quality != QualityFilter.ALL ||
            language != LanguageFilter.ALL ||
            includedOnly

    val anyClientFilterActive: Boolean
        get() = yearFrom != null || yearTo != null
}

data class TypeGroup(val label: String, val typeKey: String?, val items: List<Post>)

/**
 * How many results to claim, in words the viewer can trust.
 *
 * [loaded] is what this device has paged in and passed the client-side filters;
 * [serverTotal] is what the server says the query matches. They differ whenever the
 * viewer has not scrolled to the end OR has set a year range, which the ledger has no
 * column for, so it is narrowed here. Saying "37 results" while the server holds 4,000
 * of them is the difference between a filtered catalog and a broken-looking one.
 */
/**
 * F5c (Batch A): what the "Filtered" pill actually names. The pill used to read the
 * bare word "Filtered" while its comment claimed it named the facets — a viewer
 * could not tell which filter was on. The summary lists the active filters in the
 * order the sheet shows them, so "Filtered: Movies · Hindi" is always true and
 * always checkable against the sheet.
 */
internal fun activeFilterSummary(filters: SearchFilters): List<String> = buildList {
    filters.type?.let { type ->
        add(
            when (type) {
                "movie" -> "Movies"
                "series" -> "Series"
                "anime" -> "Anime"
                "complete_season" -> "Complete series"
                "kpop" -> "K-pop"
                else -> type
            },
        )
    }
    if (filters.industry != IndustryFilter.ALL) add(filters.industry.label)
    if (filters.genre != GenreFilter.ALL) add(filters.genre.label)
    if (filters.quality != QualityFilter.ALL) add(filters.quality.label)
    if (filters.language != LanguageFilter.ALL) add(filters.language.label)
    val year = when {
        filters.yearFrom != null && filters.yearTo != null -> "${filters.yearFrom}–${filters.yearTo}"
        filters.yearFrom != null -> "From ${filters.yearFrom}"
        filters.yearTo != null -> "To ${filters.yearTo}"
        else -> null
    }
    year?.let { add(it) }
    if (filters.includedOnly) add("Included in my plan")
}

/** The "Filtered" pill's label: the active filters, named, first two in full. */
internal fun filteredPillLabel(filters: SearchFilters): String {
    val parts = activeFilterSummary(filters)
    return when {
        parts.isEmpty() -> "Filtered"
        parts.size <= 2 -> "Filtered: " + parts.joinToString(" · ")
        else -> "Filtered: " + parts.take(2).joinToString(" · ") + " +${parts.size - 2} more"
    }
}

internal fun resultsSummary(loaded: Int, serverTotal: Int, hasMore: Boolean, totalCapped: Boolean = false): String = when {
    // F5d (Batch A): relabel only — the two numbers count different populations
    // (loaded posts vs the server's total for the query), so the partial line now
    // says "results" like the complete line does. A4-F4: this is the ONE count
    // line on the results screen — the per-group subtitles ("18 titles") are
    // gone, because they counted a third population (loaded works in one
    // section) and read as a competing total next to this line. The numbers
    // here are NOT forced to agree; they never will.
    // 2026-11-15, search speed: a capped total prints with a "+" — the server
    // counts at most 1000 matches for a search, so the real total is larger.
    hasMore || loaded < serverTotal -> if (totalCapped) "Showing $loaded of $serverTotal+ results" else "Showing $loaded of $serverTotal results"
    else -> "$loaded result" + if (loaded == 1) "" else "s"
}

/**
 * A4-F4: the type chip the rail shows as active. The filter itself is the
 * authority whenever the viewer set one — but with no type filter, a search
 * that answers with a single section (one group) is that section's type, not
 * "All": "All" active over a lone Series rail claims a breadth the list
 * does not have (Robo OR-05: "All" filled while only Movies showed, next
 * to "Showing 34 of 52 results"). Display only — the filter stays unset
 * until the viewer taps the chip, so clearing the query never leaves a
 * stale type behind. A group with no rail chip (Seasons, Other) aligns to
 * nothing, so "All" stays the honest answer there.
 */
internal fun effectiveTypeChip(selectedType: String?, groups: List<TypeGroup>): String? {
    if (selectedType != null) return selectedType
    if (groups.size != 1) return null
    return when (groups.single().typeKey) {
        "movie", "series", "anime" -> groups.single().typeKey
        else -> null
    }
}

/**
 * Mockup (Nov 2026): the type the server actually receives. "kpop" is a
 * query preset, not a content_type — no post carries it, so sending it
 * would answer zero rows on both routes. The query ("kpop") carries the
 * pill; the type stays null. Every other type passes through untouched.
 * Pure so a test can pin it without a ViewModel.
 */
internal fun effectiveSearchType(type: String?): String? = if (type == "kpop") null else type

data class SearchUiState(
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    val sort: SearchSort = SearchSort.DEFAULT,
    /** What the server says the query matches, not merely what this page holds. */
    val serverTotal: Int = 0,
    /** True when serverTotal is the server's cap (1000), not the real total. */
    val totalCapped: Boolean = false,
    val recents: List<String> = emptyList(),
    val hasSearched: Boolean = false,
    /** A VIP viewer has everything included, so the chip never shows for them. */
    val isVip: Boolean = false,
    /**
     * App-design audit #37: the server's "did you mean" spelling for the
     * current query, or null when none was offered. The screen renders it
     * as one tappable line; a tap searches for the suggested spelling.
     */
    val didYouMean: String? = null,
    /** Type-ahead titles for the current query; empty when the route is blocked or the server is old. */
    val suggestions: List<SuggestItem> = emptyList(),
    /**
     * Feel-instant S3a/S3c: the titles this device already knows, matching
     * the current query from the local FTS index — painted the moment a
     * keystroke lands, while the server search is still debounced. A tap
     * runs the server search for that title (the index carries no id to
     * open). Cleared the moment a server search for the query answers:
     * the server list is the complete answer, and the local rows are its
     * instant preview, not a second list. Kept when the server search
     * fails, so a dead connection still answers from the device.
     */
    val localResults: List<LocalSearchResult> = emptyList(),
    /** The query [localResults] answers — the screen only shows them while it still matches. */
    val localResultsQuery: String = "",
    /**
     * F14 (Batch B): the trending row for the empty state — the server's own
     * "Most versions" order, loaded once per screen. Empty when the route is
     * blocked or the server is old; the empty state simply shows no row
     * then, never an error for a suggestion that failed to load.
     */
    val trending: List<Post> = emptyList(),
    /**
     * Mockup (Nov 2026): the "Recommended for you" grid on the discovery
     * page — the catalog's freshest titles, newest first (the same feed as
     * Home's "Recently added" rail). There is no personalised recommendation
     * endpoint, so the label names the freshest rows, not a per-viewer pick;
     * empty when the route fails, and the section simply hides then.
     */
    val recommended: List<Post> = emptyList(),
    /**
     * Search review (Nov 2026), fix 4: true until the first trending +
     * recommended loads settle (success or failure). The discovery page
     * shows section skeletons while this is true and no section has data
     * yet; the empty-state hint waits for it, so the hint only ever means
     * "the catalog really has nothing to show".
     */
    val discoveryLoading: Boolean = true,
    /** True when this page is the server's one-edit-away fallback, not an exact match. */
    val isFuzzy: Boolean = false,
    /**
     * True when the viewer asked to see exact matches only on a fuzzy page.
     * Every row on a fuzzy page is one-edit-away, so exact-only is the empty
     * state — not a second server call.
     */
    val showExactOnly: Boolean = false,
    /**
     * MX #7 — Kids Lock: when on, the screen reads through the `visible*`
     * getters below instead of the raw lists. The raw lists stay whole
     * underneath, so turning the mode off restores the full Search with no
     * re-fetch — only the Pager (the filtered results list itself) rebuilds
     * when the flag flips.
     */
    val kidsMode: Boolean = false,
) {
    /**
     * MX #7 — Kids Lock: recents, suggestions, local rows, trending and
     * recommended are all unvetted for a kid — a recent can name an adult
     * title, a suggestion chip can carry one, a local FTS row has no genre
     * to judge by, and the discovery rails are the raw catalog feed. In
     * kids mode every one of them reads empty, so the screen shows the
     * search box and the filtered results only. The typed results list is
     * filtered separately, in the Pager.
     */
    val visibleRecents: List<String> get() = if (!kidsMode) recents else emptyList()
    val visibleSuggestions: List<SuggestItem> get() = if (!kidsMode) suggestions else emptyList()
    val visibleLocalResults: List<LocalSearchResult> get() = if (!kidsMode) localResults else emptyList()
    val visibleTrending: List<Post> get() = if (!kidsMode) trending else emptyList()
    val visibleRecommended: List<Post> get() = if (!kidsMode) recommended else emptyList()
    /** A blank query is still a search when a server facet is selected. */
    val hasSearchIntent: Boolean
        get() = query.isNotBlank() || filters.anyServerFilterActive

    /**
     * Feel-instant S3a/S3c: the local rows are on screen only while they
     * still answer the query in the field — a keystroke cancels the job
     * that wrote them, and the query guard below is the second line, so a
     * stale list can never sit under a newer query.
     */
    val hasLocalResults: Boolean
        get() = visibleLocalResults.isNotEmpty() && localResultsQuery == query.trim()

    val hasActiveFilters: Boolean
        get() = filters.type != null || filters.anyServerFilterActive ||
            filters.anyClientFilterActive || sort != SearchSort.DEFAULT

    /**
     * The filters the "Filtered" pill names: facets and years, never the
     * sort — a different sort order is not a filter, and the pill must not
     * claim the catalog is narrowed when only the ordering changed.
     */
    val hasFacetFilters: Boolean
        get() = filters.type != null || filters.anyServerFilterActive ||
            filters.anyClientFilterActive
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val settingsRepository: ServerSettingsRepository,
    private val syncAccountRepository: SyncAccountRepository,
    /**
     * Feel-instant S3c: the on-device search index. Null in the tests that
     * build this ViewModel by hand — then search stays server-only, exactly
     * as before this slice.
     */
    private val catalogFtsDao: CatalogFtsDao? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var suggestJob: Job? = null
    private var localSearchJob: Job? = null

    /**
     * Feel-instant S6: the search the Pager is built for. A new value means
     * a new PagingSource — and `flatMapLatest` cancels the old source's
     * in-flight loads, so a superseded search can never append its rows
     * onto the new search's list (Issue 6, the class the old `loadMore`
     * guarded by hand). Null is "no search": a blank query, or a pasted
     * address the catalog has nothing to say about.
     */
    private val searchParams = MutableStateFlow<SearchParams?>(null)

    /**
     * Feel-instant S6: the results list is a Pager, not a hand-rolled page
     * loop. The screen collects this with `collectAsLazyPagingItems()` and
     * pages it with the S5 skeletons as placeholders; the ViewModel only
     * owns the search that built it and the totals the first page reports.
     * Each params value builds its own Pager (cached in this scope, so a
     * rotation resumes the loaded pages instead of re-fetching them).
     */
    val pagingData: Flow<PagingData<Post>> = combine(
        searchParams.filterNotNull(),
        // MX #7 — Kids Lock: the mode rides the Pager, so flipping it
        // rebuilds the results from page 1 with the filter on — a search
        // in progress never keeps serving unfiltered rows.
        settingsRepository.kidsModeActive,
    ) { params, kids -> params to kids }
        .flatMapLatest { (params, kids) ->
            Pager(
                config = CATALOG_PAGING_CONFIG,
                pagingSourceFactory = {
                    // The generation this source belongs to: a fresh search
                    // scheduled while its first page is in flight supersedes
                    // it, and the meta callback below refuses a superseded
                    // source's write (Issue 6, same stale-write class as the
                    // old scheduleSearch/loadMore guards).
                    val generation = searchGeneration
                    CatalogPagingSource(
                        // Phase 7 (M1): v2 search first — one row per work.
                        // The v2 side has no free-only filter and no
                        // language filter, so a free-only or language search
                        // stays on v1: no v2 loader at all, and the source
                        // goes straight to v1 without ever asking v2.
                        loadV2 = if (params.filters.includedOnly ||
                            params.filters.language != LanguageFilter.ALL
                        ) {
                            null
                        } else {
                            { cursor, limit ->
                                catalogRepository.searchWorksV2(
                                    query = params.query,
                                    sort = params.sort.wireValue,
                                    limit = limit,
                                    cursor = cursor,
                                    type = effectiveSearchType(params.filters.type),
                                    genres = params.filters.genre.wireValue?.let { listOf(it) },
                                    industries = params.filters.industry.wireValues.takeIf { it.isNotEmpty() },
                                    qualities = params.filters.quality.wireValue?.let { listOf(it) },
                                )
                            }
                        },
                        loadV1 = { page, limit ->
                            catalogRepository.getPosts(
                                query = params.query,
                                type = effectiveSearchType(params.filters.type),
                                industry = params.filters.industry.wireValue,
                                genre = params.filters.genre.wireValue,
                                language = params.filters.language.wireValue,
                                quality = params.filters.quality.wireValue,
                                sort = params.sort.wireValue,
                                freeOnly = params.filters.includedOnly.takeIf { it },
                                page = page,
                                limit = limit,
                            )
                        },
                        onPageMeta = { meta -> onPageMeta(params, generation, meta) },
                        onFirstPageError = { onFirstPageError(generation) },
                    )
                },
            ).flow
                // MX #7 — Kids Lock: the page filter. The source still pages
                // the server's list; only kid-appropriate rows reach the
                // screen. The server already hides flagged rows on fresh
                // reads, so this is the second layer — the genre rule.
                .map { data -> if (kids) data.filter { KidsLock.isKidAppropriate(it) } else data }
                .cachedIn(viewModelScope)
        }

    /**
     * Session 158 (S5): what the identical-search skip reads — whether the
     * current search's first page landed with rows, and whether it failed.
     * The meta/error callbacks maintain both; a failed search leaves
     * nothing to dedupe against, so a retry after an error always re-asks.
     */
    private var hasFirstPageResults: Boolean = false
    private var firstPageFailed: Boolean = false

    /**
     * Feel-instant S3b: the last server suggestions, kept so the local job
     * can merge them under the instant ones — and the query they answer,
     * so a stale server batch never rides under a newer query's locals.
     */
    private var lastServerSuggestions: List<SuggestItem> = emptyList()
    private var lastServerSuggestionsQuery: String = ""

    /** F14 (Batch B): the trending load runs once per ViewModel, not per recomposition. */
    private var trendingRequested = false

    /** Mockup (Nov 2026): the recommended load runs once per ViewModel, like trending. */
    private var recommendedRequested = false

    /** Search review (Nov 2026), fix 4: the two discovery loads, settled or not. */
    private var trendingSettled = false
    private var recommendedSettled = false

    /**
     * Search review (Nov 2026), fix 4: one discovery section has answered
     * (success or failure — a dead route settles too). The page stops
     * loading when both have answered.
     */
    private fun markDiscoverySettled(trending: Boolean) {
        if (trending) trendingSettled = true else recommendedSettled = true
        _uiState.update { it.copy(discoveryLoading = discoveryLoading(trendingSettled, recommendedSettled)) }
    }

    init {
        // The rail is a history, not a session scratch pad: hydrate from the persisted list
        // and keep following it, so a second device state (e.g. a reset) cannot leave the
        // screen showing queries the store no longer holds.
        viewModelScope.launch {
            settingsRepository.searchRecents.collect { stored ->
                _uiState.update { it.copy(recents = stored) }
            }
        }
        // MX #7 — Kids Lock: one flag from DataStore. Flipping it only
        // flips the flag — the raw lists stay whole underneath, so the
        // screen re-reads through the `visible*` getters with no re-fetch,
        // while the Pager above rebuilds with the filter on.
        viewModelScope.launch {
            settingsRepository.kidsModeActive.collect { active ->
                _uiState.update { it.copy(kidsMode = active) }
            }
        }
        viewModelScope.launch {
            // Null entitlement is "not loaded yet", not "free": the chip
            // shows until the account answers, because hiding it for a
            // free viewer would cost them the filter, while a VIP viewer
            // sees it vanish the moment their tier loads.
            syncAccountRepository.entitlement.collect { entitlement ->
                _uiState.update { it.copy(isVip = entitlement?.vip == true) }
            }
        }
        // F14 (Batch B): the empty state's trending row, loaded once. A
        // failure leaves the row empty — a suggestion that failed to load
        // is not a search that failed, and the screen must not say so.
        viewModelScope.launch {
            if (trendingRequested) return@launch
            trendingRequested = true
            runCatching { catalogRepository.getTrending(limit = 10) }
                .onSuccess { page -> _uiState.update { it.copy(trending = page.results) } }
            markDiscoverySettled(trending = true)
        }
        // Mockup (Nov 2026): the "Recommended for you" grid, loaded once.
        // The freshest titles, newest first — the same feed as Home's
        // "Recently added" rail. A failure leaves the grid empty and the
        // section hides; a recommendation that failed to load is not a
        // search that failed.
        viewModelScope.launch {
            if (recommendedRequested) return@launch
            recommendedRequested = true
            runCatching {
                catalogRepository.getPosts(sort = SearchSort.NEWEST.wireValue, page = 1, limit = 18)
            }.onSuccess { page -> _uiState.update { it.copy(recommended = page.results) } }
            markDiscoverySettled(trending = false)
        }
    }

    fun onQueryChanged(query: String) {
        // Mockup (Nov 2026): editing the query releases the KPOP preset —
        // the pill names a search for "kpop", not a filter that follows
        // the text. The preset only holds while the query still is "kpop".
        val type = if (!query.trim().equals("kpop", ignoreCase = true) &&
            _uiState.value.filters.type == "kpop"
        ) {
            null
        } else {
            _uiState.value.filters.type
        }
        _uiState.update { it.copy(query = query, filters = it.filters.copy(type = type)) }
        scheduleSearch()
        scheduleSuggest()
        // Feel-instant S3a/S3c: the local index answers at once, no
        // debounce — the server call above still waits out its own.
        scheduleLocalSearch()
    }

    /** Records the current query and forces an immediate search (IME "Search" key). */
    fun submitCurrentQuery() {
        _uiState.update { it.copy(suggestions = emptyList()) }
        // A pasted address is not a title: it never joins the recents rail.
        if (classifyPastedText(_uiState.value.query) == PastedText.NotAUrl) {
            recordRecent(_uiState.value.query)
        }
        scheduleSearch(immediate = true)
    }

    fun runRecent(query: String) {
        _uiState.update { it.copy(query = query, suggestions = emptyList()) }
        recordRecent(query)
        scheduleSearch(immediate = true)
    }

    /** A suggestion is a title to search for, not a page to open: the id it carries is a work id. */
    fun runSuggestion(title: String) {
        _uiState.update { it.copy(query = title, suggestions = emptyList()) }
        recordRecent(title)
        scheduleSearch(immediate = true)
    }

    /** Show only exact matches on a fuzzy page (the empty state — every fuzzy row is one-edit-away). */
    fun showExactOnly() {
        _uiState.update { it.copy(showExactOnly = true) }
    }

    /** Back to the close matches on a fuzzy page. */
    fun showCloseMatches() {
        _uiState.update { it.copy(showExactOnly = false) }
    }

    /**
     * Mockup (Nov 2026): one recent's × — drop that row from the history
     * and persist the shorter list; the store's flow echoes it back.
     */
    fun removeRecent(query: String) {
        val next = _uiState.value.recents.filterNot { it == query }
        if (next == _uiState.value.recents) return
        _uiState.update { it.copy(recents = next) }
        viewModelScope.launch { settingsRepository.saveSearchRecents(next) }
    }

    fun selectType(type: String?) {
        if (_uiState.value.filters.type == type) return
        // Mockup (Nov 2026): "kpop" is a query preset, not a content_type —
        // the server has no K-pop facet (no genre/music key, and an unknown
        // genre is silently ignored — proven live 2026-11-15: genre=music
        // answered 207,919 rows, the unfiltered total). The pill runs a real
        // title search for "kpop" (2 titles live), so the query becomes
        // "kpop" with the tap; the fetch sites send type=null for it via
        // [effectiveSearchType], because content_type='kpop' would answer
        // zero rows. Editing the query releases the preset ([onQueryChanged]).
        val query = if (type == "kpop") "kpop" else _uiState.value.query
        _uiState.update { it.copy(filters = it.filters.copy(type = type), query = query) }
        scheduleSearch()
    }

    fun selectIndustry(industry: IndustryFilter) {
        if (_uiState.value.filters.industry == industry) return
        _uiState.update { it.copy(filters = it.filters.copy(industry = industry)) }
        scheduleSearch()
    }

    fun selectGenre(genre: GenreFilter) {
        if (_uiState.value.filters.genre == genre) return
        _uiState.update { it.copy(filters = it.filters.copy(genre = genre)) }
        scheduleSearch()
    }

    fun selectQuality(quality: QualityFilter) {
        if (_uiState.value.filters.quality == quality) return
        _uiState.update { it.copy(filters = it.filters.copy(quality = quality)) }
        scheduleSearch()
    }

    fun selectLanguage(language: LanguageFilter) {
        if (_uiState.value.filters.language == language) return
        _uiState.update { it.copy(filters = it.filters.copy(language = language)) }
        scheduleSearch()
    }

    fun setIncludedOnly(includedOnly: Boolean) {
        if (_uiState.value.filters.includedOnly == includedOnly) return
        _uiState.update { it.copy(filters = it.filters.copy(includedOnly = includedOnly)) }
        scheduleSearch()
    }

    fun setSort(sort: SearchSort) {
        if (_uiState.value.sort == sort) return
        _uiState.update { it.copy(sort = sort) }
        // Sort is a server ordering, so re-fetch instead of reordering only the
        // rows this device happens to hold.
        scheduleSearch()
    }

    fun setYearRange(from: Int?, to: Int?) {
        // The year range is a client-side filter over the rows the Pager
        // already holds: the screen re-groups the loaded snapshot when the
        // range moves, so this is a state write only — no refetch, no
        // re-group here (the old `recompute()` did the grouping in the
        // ViewModel; S6 moved it to the screen, next to the snapshot).
        _uiState.update { it.copy(filters = it.filters.copy(yearFrom = from, yearTo = to)) }
    }

    /**
     * Clear every filter, including the Type chip: the empty state's "Clear
     * filters" button and the sheet's "Reset" both call this, and a reset
     * that keeps the type re-runs the same filtered search — the button
     * looks dead (Samsung RTL retest, Issue 6: Series stayed on, so the
     * list stayed at "No matches"). The sort resets too, for the same
     * reason: a "clear" that keeps a non-default sort is not a clear.
     */
    fun resetFilters() {
        _uiState.update {
            it.copy(
                filters = SearchFilters(),
                sort = SearchSort.DEFAULT,
            )
        }
        scheduleSearch()
    }

    /**
     * Monotonic id of the latest scheduled search. A search job that is no
     * longer the latest must never write results: `Job.cancel()` only takes
     * effect at the next suspension point, so a job whose network call
     * already returned keeps running through its (non-suspending) write —
     * and a slow earlier search then overwrites the newer one's list.
     * That is the Samsung RTL retest's Issue 6 flip: the same query with
     * the Series filter answered 1 result once and "No matches" on
     * repeats, and a tap on the result card was followed by the stale
     * job landing and the list going empty — the order the two in-flight
     * searches finished in decided what the viewer saw. Every write
     * below is gated on still being the latest generation — and so is
     * every write a PagingSource reports back ([onPageMeta],
     * [onFirstPageError]): a superseded source's first page must never
     * write totals over the newer search's list, the same stale-write
     * class as the old search job.
     */
    private var searchGeneration = 0

    /**
     * Session 158 (S5): the last search whose results are on screen, if
     * any — the dedupe key [scheduleSearch] compares a re-submit against,
     * so an identical search is never fetched twice. Cleared when a search
     * fails, so a retry after an error always re-asks.
     */
    private var lastCompletedSearch: CompletedSearchKey? = null

    /**
     * Feel-instant S6: what a loaded page reports back. The PagingSource
     * calls this on every page it serves; the first page also completes
     * the search (recent, dedupe key, local-rows clear). Generation-gated
     * (Issue 6): a superseded search's source must never write totals over
     * the newer search's list — `flatMapLatest` cancels its loads, but a
     * page whose network call already returned still runs its callback, so
     * the gate is the second line, same as the old search job's writes.
     *
     * The list error itself is NOT written here: the screen reads it from
     * the PagingData's `loadState` (refresh Error → the full error state,
     * append Error → the inline retry row), so there is no ViewModel error
     * flag to strand. This callback only maintains the totals and the
     * identical-search skip's inputs.
     */
    private fun onPageMeta(params: SearchParams, generation: Int, meta: CatalogPageMeta) {
        if (generation != searchGeneration) return
        if (meta.isFirstPage) {
            // The server answered: the local rows were its instant preview,
            // not a second list — and the search is the one on screen now.
            lastCompletedSearch = CompletedSearchKey(params.query, params.filters, params.sort)
            firstPageFailed = false
            hasFirstPageResults = meta.loadedCount > 0
            recordRecent(params.query)
        }
        _uiState.update {
            it.copy(
                // v2's totalEstimate can be null; then the total is however
                // many rows have loaded so far, which the meta reports on
                // every page — without the per-page report the count line
                // would freeze at the first page's size while the list kept
                // growing past it.
                serverTotal = meta.totalEstimate ?: meta.loadedCount,
                totalCapped = meta.totalCapped,
                // The fuzzy flag and the suggestion ride only on the first
                // page: an append's v1 page carries the same values, but the
                // first page is the one that defines the answer.
                isFuzzy = if (meta.isFirstPage) meta.isFuzzy else it.isFuzzy,
                showExactOnly = if (meta.isFirstPage) false else it.showExactOnly,
                didYouMean = if (meta.isFirstPage) {
                    // A suggestion that only repeats the query is no
                    // suggestion at all.
                    meta.suggestion?.trim()?.takeIf { suggestion ->
                        suggestion.isNotBlank() && !suggestion.equals(params.query, ignoreCase = true)
                    }
                } else {
                    it.didYouMean
                },
                hasSearched = true,
                localResults = if (meta.isFirstPage) emptyList() else it.localResults,
                localResultsQuery = if (meta.isFirstPage) "" else it.localResultsQuery,
            )
        }
    }

    /**
     * Feel-instant S6: the first page failed. The screen shows the failure
     * from the PagingData's `loadState`; this only clears the dedupe key
     * and arms the identical-search skip's failure input, so a retry after
     * an error always re-asks (Session 158, S5). Generation-gated like
     * [onPageMeta]: a superseded search's failure must not clear the newer
     * search's completed key.
     */
    private fun onFirstPageError(generation: Int) {
        if (generation != searchGeneration) return
        lastCompletedSearch = null
        firstPageFailed = true
        hasFirstPageResults = false
    }

    private fun scheduleSearch(immediate: Boolean = false) {
        // Session 158 (S5): an identical search whose results are already
        // on screen is a no-op, not a re-fetch. Typing fires a debounced
        // search and pressing Search (IME) fires an immediate one for the
        // SAME query — the S5 log shows "Tarzan" submitted twice 2 s apart
        // and "Tarzan's Revenge" twice the same way — and the second fetch
        // re-emits the whole list (fresh Post instances, regrouped) in
        // exactly the window the viewer taps a result in. A re-submit that
        // changes nothing must not cancel jobs, reset the page, or flash
        // loading, so the check runs before any of that happens. A new
        // query, new filters, a new sort, an error on screen, or no
        // results yet all still search.
        val current = _uiState.value
        if (shouldSkipIdenticalSearch(
                lastCompletedSearch,
                current.query.trim(),
                current.filters,
                current.sort,
                current.hasSearched && hasFirstPageResults,
                firstPageFailed,
            )
        ) return
        searchJob?.cancel()
        // This schedule is the latest search from here on; an older job
        // still in flight must not write anything when it lands.
        val generation = ++searchGeneration
        val state = _uiState.value
        val query = state.query.trim()
        val filters = state.filters
        if (query.isBlank() && !filters.anyServerFilterActive) {
            searchParams.value = null
            firstPageFailed = false
            hasFirstPageResults = false
            _uiState.update {
                it.copy(
                    serverTotal = 0, totalCapped = false, hasSearched = false,
                    isFuzzy = false, showExactOnly = false, didYouMean = null,
                    localResults = emptyList(), localResultsQuery = "",
                )
            }
            return
        }
        // A pasted address is not a title: the catalog has nothing to say
        // about it, and the screen already shows the play row (or the
        // YouTube note) for it — so no server call, no suggest, no recent.
        if (classifyPastedText(query) != PastedText.NotAUrl) {
            searchParams.value = null
            firstPageFailed = false
            hasFirstPageResults = false
            _uiState.update {
                it.copy(
                    serverTotal = 0, totalCapped = false, hasSearched = false,
                    isFuzzy = false, showExactOnly = false, suggestions = emptyList(),
                    didYouMean = null, localResults = emptyList(), localResultsQuery = "",
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(DEBOUNCE_MS)
            if (generation != searchGeneration) return@launch
            // Feel-instant S6: the search is a set of params, not a fetch.
            // Setting them builds a new Pager (and its PagingSource); a
            // newer search replaces them, `flatMapLatest` cancels the old
            // source's in-flight loads, and the meta callback is
            // generation-gated, so a superseded source can never write over
            // this search (Issue 6, the same stale-write class the old
            // scheduleSearch guarded with four generation checks around its
            // two network calls). There is no isLoading flag to strand
            // here: the screen reads the PagingData's `loadState`, and
            // Paging owns that state — a cancelled search leaves no
            // skeleton behind (Batch C, same class as the old `finally`).
            searchParams.value = SearchParams(query = query, filters = filters, sort = state.sort)
        }
    }

    /**
     * Type-ahead on the same debounce as the search, on its own job so a
     * slow suggest never delays the results. Two characters minimum — the
     * server answers nothing shorter — and a blank query clears the chips.
     * A blocked route or an old server just leaves the list empty.
     *
     * Feel-instant S3b: the chips are local first. The instant rows from
     * [scheduleLocalSearch] already sit above; the server batch merges
     * underneath them, deduped by title, so a title the device knows never
     * waits on the network to appear as a chip.
     */
    private fun scheduleSuggest() {
        suggestJob?.cancel()
        val query = _uiState.value.query.trim()
        if (query.length < 2) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }
        if (classifyPastedText(query) != PastedText.NotAUrl) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            // Null is "the suggest call itself failed" (blocked route, old
            // server, dead socket) — and the chips simply stay empty, per
            // Audit #34: the screen never apologises for a missing feature
            // inside the feature. The old `suggestionsUnavailable` flag
            // claimed the screen said so; nothing ever read it, so it is
            // gone and this comment says what the screen actually does.
            val items = catalogRepository.suggest(query)
            // Same stale-write class as the search job (Issue 6): a
            // cancelled suggest whose call already returned must not paint
            // chips for a query the viewer has already edited past.
            if (query != _uiState.value.query.trim()) return@launch
            lastServerSuggestions = items ?: emptyList()
            lastServerSuggestionsQuery = query
            _uiState.update {
                it.copy(
                    suggestions = mergeSuggestions(
                        localSuggestionsFor(it, query),
                        lastServerSuggestions,
                    ),
                )
            }
        }
    }

    /**
     * Feel-instant S3a/S3c: the local index answers a keystroke at once —
     * no debounce, no network. The query runs on the FTS table Room
     * already holds, and the rows paint as the instant preview while the
     * debounced server search is still out. A cancelled job whose query
     * already returned must not paint rows for a query the viewer has
     * already edited past (Issue 6, same stale-write class as the
     * search job) — and a null DAO means no index, so search stays
     * server-only instead of failing.
     */
    private fun scheduleLocalSearch() {
        localSearchJob?.cancel()
        val query = _uiState.value.query.trim()
        val matchQuery = buildFtsPrefixQuery(query)
        if (matchQuery == null) {
            _uiState.update { it.copy(localResults = emptyList(), localResultsQuery = "") }
            return
        }
        val dao = catalogFtsDao ?: return
        localSearchJob = viewModelScope.launch {
            val rows = runCatching { dao.search(matchQuery, LOCAL_SEARCH_LIMIT) }.getOrNull()
                ?: emptyList()
            if (query != _uiState.value.query.trim()) return@launch
            val results = rows.map {
                LocalSearchResult(title = it.title, year = it.year, type = it.type, posterUrl = it.posterUrl)
            }
            _uiState.update {
                it.copy(
                    localResults = results,
                    localResultsQuery = query,
                    suggestions = mergeSuggestions(
                        results.take(LOCAL_SUGGEST_LIMIT).map { result -> result.toSuggestItem() },
                        serverSuggestionsFor(query),
                    ),
                )
            }
        }
    }

    /**
     * Feel-instant S3b: the local rows for this query as suggestion chips,
     * or none when the rows in state answer an older query — the guard is
     * the same one [SearchUiState.hasLocalResults] carries.
     */
    private fun localSuggestionsFor(state: SearchUiState, query: String): List<SuggestItem> =
        if (state.localResultsQuery == query) {
            state.localResults.take(LOCAL_SUGGEST_LIMIT).map { it.toSuggestItem() }
        } else {
            emptyList()
        }

    /** Feel-instant S3b: the last server batch, only when it answers this query. */
    private fun serverSuggestionsFor(query: String): List<SuggestItem> =
        if (lastServerSuggestionsQuery == query) lastServerSuggestions else emptyList()

    private fun recordRecent(query: String) {
        // MX #7 — Kids Lock: a kid's searches never join the shared recents
        // rail — they would name titles for the next adult to read.
        if (_uiState.value.kidsMode) return
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        val next = normalizeSearchRecents(listOf(trimmed) + _uiState.value.recents)
        if (next == _uiState.value.recents) return
        // Show the new rail immediately, then persist the same list; the store's flow will
        // echo it back and settle the state.
        _uiState.update { it.copy(recents = next) }
        viewModelScope.launch { settingsRepository.saveSearchRecents(next) }
    }

    private companion object {
        /**
         * Feel-instant S3a: 150 ms, down from 300 ms — the network call no
         * longer carries the whole experience, because the local index
         * answers each keystroke at once ([scheduleLocalSearch] runs with
         * no debounce at all). 300 ms was the right wait while every
         * keystroke was a round trip; with instant locals it is just lag.
         * The suggest call rides the same debounce.
         */
        const val DEBOUNCE_MS = 150L
        /**
         * Feel-instant S3a/S3c: how many local rows a keystroke paints —
         * an instant preview, not a second results page.
         */
        const val LOCAL_SEARCH_LIMIT = 20
        /** Feel-instant S3b: how many local rows may lead the chip row. */
        const val LOCAL_SUGGEST_LIMIT = 5
    }
}

/** Feel-instant S3b: the merged chip row never runs longer than this. */
internal const val MAX_SUGGESTIONS = 8

/**
 * Feel-instant S3a/S3c: one row of the instant local answer — a title this
 * device already knows, with the year, type and poster the row paints.
 * There is deliberately no id: the index carries none, and a tap runs the
 * server search for the title instead of opening a page.
 */
data class LocalSearchResult(
    val title: String,
    val year: Int?,
    val type: String?,
    val posterUrl: String?,
)

/**
 * Feel-instant S3b: a local row as a suggestion chip. The id is a negative
 * hash of the title — the chips are keyed by id, and a server id is a
 * positive post id, so a local chip can never collide with a server one.
 * Two titles with the same hash are vanishingly rare, and the merge below
 * dedupes by title anyway.
 */
internal fun LocalSearchResult.toSuggestItem(): SuggestItem =
    SuggestItem(id = -title.hashCode(), title = title, year = year)

/**
 * Feel-instant S3b: one chip row from the two halves — the instant local
 * rows first, then the server batch underneath, deduped by title
 * (case-insensitive: the same title from both sides is one chip), capped
 * so the row never runs long. Pure so a test can pin it without a
 * ViewModel.
 */
internal fun mergeSuggestions(local: List<SuggestItem>, server: List<SuggestItem>): List<SuggestItem> {
    val seen = local.map { it.title.lowercase() }.toMutableSet()
    val merged = local.toMutableList()
    for (item in server) {
        if (merged.size >= MAX_SUGGESTIONS) break
        if (seen.add(item.title.lowercase())) merged.add(item)
    }
    return merged.take(MAX_SUGGESTIONS)
}

/**
 * Feel-instant S6: the search a Pager is built for — the trimmed query
 * plus the filters and sort that shaped the list. A new value builds a new
 * PagingSource; two searches with the same value answer the same list.
 * Private: the screen never names it, it only collects the Pager the
 * ViewModel builds from it.
 */
private data class SearchParams(
    val query: String,
    val filters: SearchFilters,
    val sort: SearchSort,
)

/**
 * Feel-instant S6: the year range is a client-side filter over the rows
 * the Pager already holds (the old `recompute()` did this filtering plus
 * the grouping in the ViewModel, over the accumulated rows). The screen
 * calls this on the loaded snapshot before grouping, so moving the range
 * re-groups what is on screen without a refetch. Pure so the screen —
 * and a test — can call it without a ViewModel.
 */
internal fun filterByYearRange(posts: List<Post>, filters: SearchFilters): List<Post> =
    posts.filter { post ->
        // Post.year arrives as a JSON number (Int?), so no string parsing is needed.
        val year = post.year
        when {
            filters.yearFrom != null && (year == null || year < filters.yearFrom) -> false
            filters.yearTo != null && (year == null || year > filters.yearTo) -> false
            else -> true
        }
    }

/**
 * Session 158 (S5): the search whose results are on screen, as the
 * dedupe key — the trimmed query plus the filters and sort that shaped
 * the list. Two searches with the same key answer the same list, so the
 * second one is a re-submit, not a new question.
 */
internal data class CompletedSearchKey(
    val query: String,
    val filters: SearchFilters,
    val sort: SearchSort,
)

/**
 * Search review (Nov 2026), fix 4: the discovery page is loading until
 * BOTH first loads have settled — a success and a failure both settle, so
 * a dead route shows the hint instead of spinning forever.
 */
internal fun discoveryLoading(trendingSettled: Boolean, recommendedSettled: Boolean): Boolean =
    !(trendingSettled && recommendedSettled)

/**
 * Session 158 (S5): may this search be skipped because its answer is
 * already on screen? Only when the key matches the last completed
 * search exactly, results are showing, and no error is on screen — a
 * different query, filter or sort, an empty list, or a failed search
 * all still fetch.
 */
internal fun shouldSkipIdenticalSearch(
    lastCompleted: CompletedSearchKey?,
    query: String,
    filters: SearchFilters,
    sort: SearchSort,
    hasResults: Boolean,
    hasError: Boolean,
): Boolean =
    lastCompleted != null &&
        lastCompleted.query == query &&
        lastCompleted.filters == filters &&
        lastCompleted.sort == sort &&
        hasResults &&
        !hasError

internal fun parseYear(year: String?): Int? {
    if (year.isNullOrBlank()) return null
    Regex("""(19|20)\d{2}""").find(year)?.value?.toIntOrNull()?.let { return it }
    return null
}

/**
 * Rail order, by the LABEL the rail is headed with — Movies, Series, Seasons,
 * Anime, then anything else. Session 120: this used to live behind a
 * `compareBy { typeOrder(it) }` fed the raw wire keys ("movie"), which it never
 * matched, so every key ordered equal (see [groupSearchResults]).
 */
internal fun typeOrder(label: String): Int = when (label) {
    "Movies" -> 0
    "Series" -> 1
    "Seasons" -> 2
    "Anime" -> 3
    else -> 4
}

/**
 * Group search rows into labelled rails, in rail order.
 *
 * Session 120: this used to be
 * `groupBy { typeGroupKey(it) }.toSortedMap(typeComparator)`, which collapsed
 * every group into ONE. `toSortedMap` is a TreeMap, and a TreeMap treats two keys
 * as the SAME key when the comparator returns 0 — and `typeOrder` only ever
 * matched the plural labels ("Movies"), never the raw wire keys it was actually
 * handed ("movie"), so every key compared equal. The map kept the FIRST key and
 * the LAST value: a "Series" header, the Movies count, and film cards
 * underneath — exactly what the TestingBot retest showed ("Series — 4" over
 * "2026 · Movie" cards). Sorting the built list instead keeps every group,
 * and the header is the label of the rows it actually heads.
 */
internal fun groupSearchResults(posts: List<Post>, sort: SearchSort): List<TypeGroup> =
    posts
        .groupBy { typeGroupKey(it) }
        .map { (key, grouped) ->
            TypeGroup(
                label = normalizeTypeLabel(key),
                typeKey = key,
                // Session 93: one entry per work per rail — the same title
                // filed twice (two prints, two mirrors) shows once, keeping
                // the highest-sources copy the sort put first.
                items = when (sort) {
                    SearchSort.TITLE -> grouped.sortedWith(
                        compareBy<Post> { (it.cleanTitle ?: it.title).lowercase() }
                            .thenByDescending { (it.releaseCount ?: 0) + (it.sourceCount ?: 0) }
                            .thenByDescending { it.id },
                    )
                    SearchSort.HIGHEST_IMDB -> grouped.sortedWith(
                        compareByDescending<Post> { it.imdbRating?.toDoubleOrNull() ?: -1.0 }
                            .thenByDescending { (it.releaseCount ?: 0) + (it.sourceCount ?: 0) }
                            .thenByDescending { it.id },
                    )
                    SearchSort.NEWEST -> grouped.sortedWith(
                        compareByDescending<Post> { it.year ?: 0 }
                            .thenByDescending { (it.releaseCount ?: 0) + (it.sourceCount ?: 0) }
                            .thenByDescending { it.id },
                    )
                    // Mirrors the server's coverage ranking: no play counts exist.
                    SearchSort.MOST_SOURCES -> grouped.sortedWith(
                        compareByDescending<Post> { (it.releaseCount ?: 0) + (it.sourceCount ?: 0) }
                            .thenByDescending { it.id },
                    )
                }.distinctBy { postTitleDedupKey(it) },
            )
        }
        .sortedBy { typeOrder(it.label) }

internal fun normalizeTypeLabel(contentType: String?): String = when (contentType?.lowercase()?.trim()) {
    "movie" -> "Movies"
    "series" -> "Series"
    "complete_season", "ongoing_season" -> "Seasons"
    "anime" -> "Anime"
    // Session 120: these used to fall into "Other" while the tile painted the
    // capitalised wire word ("Documentary", "Sport_event"), so the header
    // described no card under it. A rail is headed with the word its cards
    // paint — see WorkTypeLabel.of, which answers the same word per row.
    "documentary" -> "Documentary"
    "sport_event" -> "Sport event"
    "music_video" -> "Music video"
    "tv_special" -> "TV special"
    "miniseries" -> "Miniseries"
    null, "" -> "Other"
    else -> "Other"
}

/**
 * Which rail a row belongs to. Animation is a flag on the post, never a `content_type`, so a
 * row the ledger flags as animated keys as the server's own `anime` filter word instead of
 * sitting in the Series rail — the same [WorkType] answer the tile paints through
 * [com.raddstream.app.network.models.WorkTypeLabel]. A row from a server that sends no flag
 * keeps its plain `content_type`: unknown is never read as "not animated".
 */
internal fun typeGroupKey(post: Post): String =
    if (WorkType.fromContentType(post.contentType, post.isAnimated) == WorkType.ANIME) {
        WorkType.ANIME.wire
    } else {
        post.contentType.orEmpty()
    }

/**
 * Normalizes title string for search rail deduplication: lowercases and strips punctuation.
 */
internal fun normalizeDedupTitle(title: String): String =
    title.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

/**
 * Deduplication key for search results: collapses duplicate releases or scraper rows
 * of the exact same work title within a category rail.
 */
internal fun postTitleDedupKey(post: Post): String {
    val work = post.workRef?.trim()?.lowercase()
    if (!work.isNullOrBlank()) {
        return "work:$work"
    }
    val raw = post.cleanTitle?.takeIf { it.isNotBlank() } ?: post.title
    val norm = normalizeDedupTitle(raw)
    val yr = post.year?.toString().orEmpty()
    return "title:$norm:$yr"
}
