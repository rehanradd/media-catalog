package com.raddstream.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.data.normalizeSearchRecents
import com.raddstream.app.network.friendlyNetworkError
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.SuggestItem
import com.raddstream.app.network.models.WorkType
import com.raddstream.app.player.PastedText
import com.raddstream.app.player.classifyPastedText
import com.raddstream.app.repository.CatalogRepository
import com.raddstream.app.sync.SyncAccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * "Most copies" names what the sort actually measures: how many prints and mirrors
 * the catalog holds for a work. The ledger has no play-count column, so no label here
 * may promise popularity — never "Trending", never "Most watched".
 */
enum class SearchSort(val label: String, val wireValue: String) {
    NEWEST("Newest", "newest"),
    MOST_SOURCES("Most copies", "trending"),
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
 * A4-F4: the "Show all" affordance at the end of a capped list — one tap
 * loads every page the server will serve, so the count line above it stops
 * being a promise the list cannot keep without repeated "Load more" taps.
 * A capped total keeps its "+" (the server counts at most 1000 matches,
 * so the real total is larger — same "+" [resultsSummary] prints).
 */
internal fun showAllLabel(serverTotal: Int, totalCapped: Boolean = false): String =
    if (totalCapped) "Show all $serverTotal+ results"
    else "Show all $serverTotal result" + if (serverTotal == 1) "" else "s"

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
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val filters: SearchFilters = SearchFilters(),
    val sort: SearchSort = SearchSort.DEFAULT,
    val groups: List<TypeGroup> = emptyList(),
    val resultCount: Int = 0,
    /** What the server says the query matches, not merely what this page holds. */
    val serverTotal: Int = 0,
    /** True when serverTotal is the server's cap (1000), not the real total. */
    val totalCapped: Boolean = false,
    val recents: List<String> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
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
     * F14 (Batch B): the trending row for the empty state — the server's own
     * "Most copies" order, loaded once per screen. Empty when the route is
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
) {
    /** A blank query is still a search when a server facet is selected. */
    val hasSearchIntent: Boolean
        get() = query.isNotBlank() || filters.anyServerFilterActive

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
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var moreJob: Job? = null
    private var suggestJob: Job? = null
    private var rawResults: List<Post> = emptyList()

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
        _uiState.update { it.copy(query = query, error = null, filters = it.filters.copy(type = type)) }
        scheduleSearch(reset = true)
        scheduleSuggest()
    }

    /** Records the current query and forces an immediate search (IME "Search" key). */
    fun submitCurrentQuery() {
        _uiState.update { it.copy(suggestions = emptyList()) }
        // A pasted address is not a title: it never joins the recents rail.
        if (classifyPastedText(_uiState.value.query) == PastedText.NotAUrl) {
            recordRecent(_uiState.value.query)
        }
        scheduleSearch(reset = true, immediate = true)
    }

    fun runRecent(query: String) {
        _uiState.update { it.copy(query = query, suggestions = emptyList()) }
        recordRecent(query)
        scheduleSearch(reset = true, immediate = true)
    }

    /** A suggestion is a title to search for, not a page to open: the id it carries is a work id. */
    fun runSuggestion(title: String) {
        _uiState.update { it.copy(query = title, suggestions = emptyList()) }
        recordRecent(title)
        scheduleSearch(reset = true, immediate = true)
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
        scheduleSearch(reset = true)
    }

    fun selectIndustry(industry: IndustryFilter) {
        if (_uiState.value.filters.industry == industry) return
        _uiState.update { it.copy(filters = it.filters.copy(industry = industry)) }
        scheduleSearch(reset = true)
    }

    fun selectGenre(genre: GenreFilter) {
        if (_uiState.value.filters.genre == genre) return
        _uiState.update { it.copy(filters = it.filters.copy(genre = genre)) }
        scheduleSearch(reset = true)
    }

    fun selectQuality(quality: QualityFilter) {
        if (_uiState.value.filters.quality == quality) return
        _uiState.update { it.copy(filters = it.filters.copy(quality = quality)) }
        scheduleSearch(reset = true)
    }

    fun selectLanguage(language: LanguageFilter) {
        if (_uiState.value.filters.language == language) return
        _uiState.update { it.copy(filters = it.filters.copy(language = language)) }
        scheduleSearch(reset = true)
    }

    fun setIncludedOnly(includedOnly: Boolean) {
        if (_uiState.value.filters.includedOnly == includedOnly) return
        _uiState.update { it.copy(filters = it.filters.copy(includedOnly = includedOnly)) }
        scheduleSearch(reset = true)
    }

    fun setSort(sort: SearchSort) {
        if (_uiState.value.sort == sort) return
        _uiState.update { it.copy(sort = sort) }
        // Sort is a server ordering, so re-fetch instead of reordering only the
        // rows this device happens to hold.
        scheduleSearch(reset = true)
    }

    fun setYearRange(from: Int?, to: Int?) {
        _uiState.update { it.copy(filters = it.filters.copy(yearFrom = from, yearTo = to)) }
        recompute()
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
        scheduleSearch(reset = true)
    }

    /** The v2 keyset cursor for the next page, or null once the list is on v1 paging. */
    private var v2Cursor: String? = null
    /** True once a search has loaded from v2: the list must never mix sources. */
    private var onV2: Boolean = false

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
     * below is gated on still being the latest generation.
     */
    private var searchGeneration = 0

    /**
     * Session 158 (S5): the last search whose results are on screen, if
     * any — the dedupe key [scheduleSearch] compares a re-submit against,
     * so an identical search is never fetched twice. Cleared when a search
     * fails, so a retry after an error always re-asks.
     */
    private var lastCompletedSearch: CompletedSearchKey? = null

    fun loadMore() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoadingMore || !state.hasSearchIntent) return
        moreJob?.cancel()
        // The generation this page belongs to: a fresh search scheduled
        // while the page is in flight supersedes it, and the page must
        // never append its rows onto the new search's list (Issue 6).
        val generation = searchGeneration
        moreJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, error = null) }
            try {
                fetchNextPage(generation)
            } finally {
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    /**
     * A4-F4: load every remaining page in one tap — "Load more" pages 100
     * rows at a time, so a 52-result answer still takes repeated taps to
     * reach the count the summary line already promises. The server counts
     * at most 1000 matches for a search and a page holds 100, so 9 more
     * pages is every row the server will ever hand over; the loop cannot
     * run past the cap. A fresh search cancels the job (scheduleSearch
     * cancels moreJob) and the generation guard stops the loop, so a page
     * never lands on a newer search's list (Issue 6, same class as
     * loadMore). The button hides once hasMore clears, so this is a
     * no-op when the list is already whole.
     */
    fun showAll() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoadingMore || !state.hasSearchIntent) return
        moreJob?.cancel()
        val generation = searchGeneration
        moreJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, error = null) }
            try {
                var pagesFetched = 0
                while (_uiState.value.hasMore && pagesFetched < MAX_SHOW_ALL_EXTRA_PAGES &&
                    generation == searchGeneration
                ) {
                    if (!fetchNextPage(generation)) break
                    pagesFetched++
                }
            } finally {
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    /**
     * One more page of the current search, appended onto [rawResults] and
     * re-grouped — the fetch half of [loadMore], shared with [showAll] so
     * the two doors cannot drift apart. Reads the current page from state
     * on every call, so a loop sees each page land before asking for the
     * next. Returns whether more pages remain afterwards; false also
     * covers a superseded fetch (its rows must never land) and a failed
     * fetch (the error is on screen for the retry). The loading flag is
     * the caller's: loadMore owns one page, showAll owns the whole loop.
     */
    private suspend fun fetchNextPage(generation: Int): Boolean {
        val state = _uiState.value
        val next = state.page + 1
        try {
            if (onV2) {
                val page = catalogRepository.searchWorksV2(
                    query = state.query.trim(),
                    sort = state.sort.wireValue,
                    limit = PAGE_SIZE,
                    cursor = v2Cursor,
                    type = effectiveSearchType(state.filters.type),
                    genres = state.filters.genre.wireValue?.let { listOf(it) },
                    industries = state.filters.industry.wireValues.takeIf { it.isNotEmpty() },
                    qualities = state.filters.quality.wireValue?.let { listOf(it) },
                )
                if (generation != searchGeneration) return false
                if (page != null) {
                    v2Cursor = page.nextCursor
                    rawResults = (rawResults + page.posts).distinctById()
                    _uiState.update {
                        it.copy(
                            page = next,
                            serverTotal = page.totalEstimate ?: rawResults.size,
                            totalCapped = page.totalCapped,
                            hasMore = page.hasMore,
                            isFuzzy = false,
                        )
                    }
                    recompute()
                    return page.hasMore
                }
                throw IllegalStateException("The catalog could not serve the next page.")
            }
            val resp = catalogRepository.getPosts(
                query = state.query.trim(),
                type = effectiveSearchType(state.filters.type),
                industry = state.filters.industry.wireValue,
                genre = state.filters.genre.wireValue,
                language = state.filters.language.wireValue,
                quality = state.filters.quality.wireValue,
                sort = state.sort.wireValue,
                freeOnly = state.filters.includedOnly.takeIf { it },
                page = next,
                limit = PAGE_SIZE,
            )
            if (generation != searchGeneration) return false
            rawResults = (rawResults + resp.results).distinctById()
            val more = resp.results.size >= PAGE_SIZE && next < resp.totalPages
            _uiState.update {
                it.copy(
                    page = next,
                    serverTotal = resp.total,
                    totalCapped = false,
                    hasMore = more,
                    isFuzzy = resp.fuzzy,
                )
            }
            recompute()
            return more
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (generation == searchGeneration) {
                _uiState.update { it.copy(error = friendlyNetworkError(error, "Could not load more.")) }
            }
            return false
        }
    }

    private fun scheduleSearch(reset: Boolean, immediate: Boolean = false) {
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
                current.hasSearched && current.groups.isNotEmpty(),
                current.error != null,
            )
        ) return
        searchJob?.cancel()
        // A fresh search supersedes a page load too: the page would append
        // its rows onto the new search's list (Issue 6, same stale-write
        // class as the search job itself).
        moreJob?.cancel()
        // This schedule is the latest search from here on; an older job
        // still in flight must not write anything when it lands.
        val generation = ++searchGeneration
        val state = _uiState.value
        val query = state.query.trim()
        val filters = state.filters
        if (query.isBlank() && !filters.anyServerFilterActive) {
            rawResults = emptyList()
            _uiState.update {
                it.copy(
                    isLoading = false, error = null, groups = emptyList(), resultCount = 0,
                    serverTotal = 0, totalCapped = false, hasSearched = false, hasMore = false, page = 1,
                    isFuzzy = false, showExactOnly = false, didYouMean = null,
                )
            }
            return
        }
        // A pasted address is not a title: the catalog has nothing to say
        // about it, and the screen already shows the play row (or the
        // YouTube note) for it — so no server call, no suggest, no recent.
        if (classifyPastedText(query) != PastedText.NotAUrl) {
            rawResults = emptyList()
            _uiState.update {
                it.copy(
                    isLoading = false, error = null, groups = emptyList(), resultCount = 0,
                    serverTotal = 0, totalCapped = false, hasSearched = false, hasMore = false, page = 1,
                    isFuzzy = false, showExactOnly = false, suggestions = emptyList(),
                    didYouMean = null,
                )
            }
            return
        }
        if (reset) _uiState.update { it.copy(page = 1) }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(DEBOUNCE_MS)
            if (generation != searchGeneration) return@launch
            _uiState.update { it.copy(isLoading = true, error = null) }
            v2Cursor = null
            onV2 = false
            try {
                // Phase 7 (M1): v2 search first — one row per work — with v1 as
                // the fallback. The v2 side has no free-only filter and no
                // language filter, so a free-only or language search stays on
                // v1; and v2 matches titles only, so an EMPTY v2 page falls
                // back to v1, which is the route that matches cast names
                // (actor search, per the cast directive).
                val freeOnly = filters.includedOnly
                if (!freeOnly && filters.language == LanguageFilter.ALL) {
                    val page = catalogRepository.searchWorksV2(
                        query = query,
                        sort = state.sort.wireValue,
                        limit = PAGE_SIZE,
                        type = effectiveSearchType(filters.type),
                        genres = filters.genre.wireValue?.let { listOf(it) },
                        industries = filters.industry.wireValues.takeIf { it.isNotEmpty() },
                        qualities = filters.quality.wireValue?.let { listOf(it) },
                    )
                    if (generation != searchGeneration) return@launch
                    if (page != null && page.posts.isNotEmpty()) {
                        onV2 = true
                        v2Cursor = page.nextCursor
                        rawResults = page.posts.distinctById()
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                hasSearched = true,
                                page = 1,
                                serverTotal = page.totalEstimate ?: rawResults.size,
                                totalCapped = page.totalCapped,
                                hasMore = page.hasMore,
                                error = null,
                                isFuzzy = false,
                                showExactOnly = false,
                                // v2 carries no suggestion yet; the v1
                                // fallback below is the only source of one.
                                didYouMean = null,
                            )
                        }
                        recompute()
                        lastCompletedSearch = CompletedSearchKey(query, filters, state.sort)
                        recordRecent(query)
                        return@launch
                    }
                }
                val resp = catalogRepository.getPosts(
                    query = query,
                    type = effectiveSearchType(filters.type),
                    industry = filters.industry.wireValue,
                    genre = filters.genre.wireValue,
                    language = filters.language.wireValue,
                    quality = filters.quality.wireValue,
                    sort = state.sort.wireValue,
                    freeOnly = filters.includedOnly.takeIf { it },
                    page = 1,
                    limit = PAGE_SIZE,
                )
                if (generation != searchGeneration) return@launch
                rawResults = resp.results.distinctById()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasSearched = true,
                        page = 1,
                        serverTotal = resp.total,
                        totalCapped = false,
                        hasMore = resp.results.size >= PAGE_SIZE && resp.totalPages > 1,
                        error = null,
                        isFuzzy = resp.fuzzy,
                        showExactOnly = false,
                        // A suggestion that only repeats the query is no
                        // suggestion at all.
                        didYouMean = resp.suggestion?.trim()?.takeIf {
                            it.isNotBlank() && !it.equals(query, ignoreCase = true)
                        },
                    )
                }
                recompute()
                lastCompletedSearch = CompletedSearchKey(query, filters, state.sort)
                recordRecent(query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A superseded search must not paint its failure over the
                // newer search's list either (Issue 6, same stale-write
                // class as the success path above).
                if (generation != searchGeneration) return@launch
                // Session 158 (S5): a failed search leaves nothing on
                // screen to dedupe against — a retry must re-ask.
                lastCompletedSearch = null
                rawResults = emptyList()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = friendlyNetworkError(error, "Search failed. Check the server connection."),
                        groups = emptyList(),
                        resultCount = 0,
                    )
                }
            } finally {
                // Batch C: a search cancelled mid-flight — a newer filter
                // tap, keystroke or sort change while the network call is
                // still out — must not strand isLoading = true: the grid
                // skeleton would sit on screen forever, with no error and
                // no results, and no retry path out of it. The generation
                // guard keeps a stale job's finally from clearing a newer
                // job's loading flag (the same guard the writes above
                // carry). loadMore already clears its flag in a finally;
                // the search job is the one that did not.
                if (generation == searchGeneration) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    /**
     * Type-ahead on the same debounce as the search, on its own job so a
     * slow suggest never delays the results. Two characters minimum — the
     * server answers nothing shorter — and a blank query clears the chips.
     * A blocked route or an old server just leaves the list empty.
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
            _uiState.update {
                it.copy(
                    suggestions = items ?: emptyList(),
                )
            }
        }
    }

    private fun recompute() {
        val filters = _uiState.value.filters
        val sort = _uiState.value.sort
        val filtered = rawResults.filter { post ->
            // Post.year arrives as a JSON number (Int?), so no string parsing is needed.
            val year = post.year
            val yearOk = when {
                filters.yearFrom != null && (year == null || year < filters.yearFrom) -> false
                filters.yearTo != null && (year == null || year > filters.yearTo) -> false
                else -> true
            }
            yearOk
        }

        val groups = groupSearchResults(filtered, sort)

        _uiState.update {
            it.copy(
                groups = groups,
                resultCount = groups.sumOf { g -> g.items.size },
            )
        }
    }

    private fun recordRecent(query: String) {
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
        const val DEBOUNCE_MS = 300L
        const val PAGE_SIZE = 100
        /**
         * A4-F4: the server counts at most 1000 matches for a search and a
         * page holds 100, so a "Show all" from page 1 needs at most 9 more
         * pages to hold every row the server will ever serve.
         */
        const val MAX_SHOW_ALL_EXTRA_PAGES = 9
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
