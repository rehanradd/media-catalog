package com.raddstream.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raddstream.app.data.cache.CacheTaskEntity
import com.raddstream.app.data.cache.CacheTaskState
import com.raddstream.app.data.local.FavoritePost
import com.raddstream.app.data.local.WatchHistoryDao
import com.raddstream.app.data.local.WatchHistoryEntity
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.WorkType
import com.raddstream.app.repository.CacheRepository
import com.raddstream.app.repository.CatalogRepository
import com.raddstream.app.repository.FavoritesRepository
import com.raddstream.app.ui.components.UserWords
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ListSort(val label: String) {
    RECENT("Recently added"),
    TITLE("Title A–Z"),
    UNWATCHED("Unwatched first"),
}

/**
 * L1 (round 2): the filter chips that scope the list — All / Movies /
 * Series / Downloads. The brief's filters, not sorts: sort stays a
 * secondary control (see [MyListFilterRow]). Movies is the MOVIE work
 * type; Series is the whole series family (series + seasons + anime +
 * miniseries + TV specials — [WorkType.isSeriesFamily]); Downloads is
 * titles with a verified copy on the device (see [downloadedPostIdsFor]).
 * Anything the wire calls documentary, sport or music video fits neither
 * Movies nor Series and shows only under All — the chips name the brief's
 * four scopes, not every type the ledger holds.
 */
enum class MyListFilter(val label: String) {
    ALL("All"),
    MOVIES("Movies"),
    SERIES("Series"),
    DOWNLOADS("Downloads"),
}

/**
 * L1 (round 2): the filter as one pure function, so the filter + sort
 * composition is assertable without a layout. Identity is the post id
 * for Downloads (a title is downloaded when its post id is in the set —
 * the same id rule the sort's watched set uses, so a same-name remake
 * never cross-matches).
 */
fun filterMyList(
    posts: List<Post>,
    filter: MyListFilter,
    downloadedPostIds: Set<Int>,
): List<Post> = when (filter) {
    MyListFilter.ALL -> posts
    MyListFilter.MOVIES -> posts.filter {
        WorkType.fromContentType(it.contentType, it.isAnimated) == WorkType.MOVIE
    }
    MyListFilter.SERIES -> posts.filter {
        WorkType.fromContentType(it.contentType, it.isAnimated).isSeriesFamily
    }
    MyListFilter.DOWNLOADS -> posts.filter { it.id in downloadedPostIds }
}

/**
 * L2 (round 2): the post ids with a verified copy on the device — the
 * keeps whose ledger row says COMPLETED (100% of known size, file
 * verified). A queued, loading, paused or failed keep is not a copy yet;
 * an expired one is pending deletion. Device downloads (the direct-file
 * path) carry no post id — only a stream handle Detail matches at the
 * print level — so they cannot join to a tile and are not in this set;
 * the badge and the Downloads filter read the keep store, the same
 * store the Downloads screen's Keep rows come from.
 */
fun downloadedPostIdsFor(tasks: List<CacheTaskEntity>): Set<Int> =
    tasks.filter { it.stateEnum == CacheTaskState.COMPLETED }
        .mapNotNull { it.releaseId.toInt().takeIf { id -> id > 0 } }
        .toSet()

data class MyListUiState(
    val favorites: List<Post> = emptyList(),
    val sort: ListSort = ListSort.RECENT,
    /** L1 (round 2): the active filter chip — All / Movies / Series / Downloads. */
    val filter: MyListFilter = MyListFilter.ALL,
    val isLoading: Boolean = false,
    /**
     * L2 (round 2): the post ids with a verified copy on the device
     * (see [downloadedPostIdsFor]) — the download badge and the
     * Downloads filter read this, never a per-tile lookup.
     */
    val downloadedPostIds: Set<Int> = emptySet(),
    /** L3 (round 2): a pull-to-refresh is in flight — the Box's indicator. */
    val isRefreshing: Boolean = false,
    /**
     * L4 (round 2): the last trending/recent load failed on the transport
     * — the rails settled to nothing and the screen shows the inline retry
     * instead of silently dropping them. False when a load produced titles
     * or the server answered empty.
     */
    val railsLoadFailed: Boolean = false,
    /**
     * Rows this device still stores and this build may not draw. Counted, never listed: the
     * viewer's list did not shrink, the server's answer about it changed.
     */
    val hiddenCount: Int = 0,
    /** The three most-covered titles in the catalog, for the empty state. Empty when unloaded. */
    val popular: List<Post> = emptyList(),
    /**
     * A4-F5: the second discovery rail under Trending — the freshest
     * titles, for the empty state. Same shape as [popular]: at most three,
     * poster-bearing, empty when unloaded. A list that is only a Trending
     * rail ends in a ~40% black void on a tall phone; the second rail is
     * what fills it with something worth tapping.
     */
    val recent: List<Post> = emptyList(),
    /** The title just removed, so the screen can offer Undo. Null when nothing is waiting. */
    val removedTitle: String? = null,
    /**
     * The progress bar each saved tile paints, keyed by post id — the
     * latest resumable watch row for that title (see
     * [myListProgressByPostId]). Absent when the viewer never started
     * the title, finished it, or the row carries no duration: no bar
     * rather than an invented one.
     */
    val progressByPostId: Map<Int, Float> = emptyMap(),
    /**
     * The same fact in words, keyed the same way ("S2:E5 · 23 min left",
     * "23 min left" — see [myListProgressLabelByPostId]). The tile speaks
     * it; a bare bar never says which episode or how much is left.
     */
    val progressLabelByPostId: Map<Int, String> = emptyMap(),
) {
    /** [AdultPolicy.hiddenNotice] — a number, because a notice naming a hidden title is a list of one. */
    val hiddenNotice: String? get() = AdultPolicy.hiddenNotice(hiddenCount)
}

/**
 * The My List order, as one pure function: recently-added and title are the
 * old orders; unwatched-first puts titles with no finished watch row ahead
 * of finished ones, then recently-added inside each half. "Finished" is by
 * post id, via [watchedPostIds] — a half-watched title still counts as
 * unwatched, and a series counts as watched when any episode of it is
 * finished. Matching by id, not by title string: two works sharing a clean
 * title (a remake, a same-name series) must never cross-mark each other,
 * and the title itself is written in two shapes (Detail writes the clean
 * title, Home's undo-remove path the raw one), so a string match can miss
 * a work's own finished row.
 */
fun sortMyList(
    posts: List<FavoritePost>,
    sort: ListSort,
    watchedPostIds: Set<Int>,
): List<FavoritePost> {
    fun isWatched(post: FavoritePost) = post.id in watchedPostIds
    return when (sort) {
        ListSort.RECENT -> posts.sortedByDescending { it.addedAt }
        ListSort.TITLE -> posts.sortedBy { (it.cleanTitle ?: it.title).lowercase() }
        ListSort.UNWATCHED -> posts.sortedWith(
            compareBy<FavoritePost> { isWatched(it) }.thenByDescending { it.addedAt },
        )
    }
}

/**
 * The language words the catalog appends as a title suffix ("(2023)
 * English Movie", "Hindi Series"). A saved row stores no languages —
 * [FavoritePost] carries no language list — so the strip below can
 * only name these, the words this app itself offers as audio/subtitle
 * languages. A title that merely ends in one of them keeps it: the
 * language word is stripped only after a type or year suffix already
 * came off in the same chain (see [stripMyListTitleSuffixes]).
 */
internal val myListTitleLanguages: Set<String> = setOf(
    "english", "hindi", "urdu", "tamil", "telugu", "malayalam", "kannada", "bengali",
)

/**
 * The title My List paints for one saved row: the server's clean title
 * with the year/language/type suffixes the meta line already states
 * stripped ([UserWords.stripTitleSuffixes], the Detail rule), so "No
 * Hard Feelings (2023) English Movie" paints as "No Hard Feelings"
 * above a meta line that already reads "2023 · Movie". A strip that
 * would leave nothing keeps the row as saved — an empty painted title
 * is worse than a suffixed one.
 */
internal fun myListDisplayPost(favorite: FavoritePost): Post {
    val post = favorite.toPost()
    val base = post.cleanTitle?.takeIf { it.isNotBlank() } ?: post.title
    val stripped = stripMyListTitleSuffixes(base, post.year, post.contentType)
    if (stripped.isBlank()) return post
    return post.copy(cleanTitle = stripped)
}

/**
 * [UserWords.stripTitleSuffixes] with the languages My List can name.
 * The shared rule only strips a trailing language word the post itself
 * lists, and a saved row lists none — so the language word is stripped
 * only when a type ("Movie"/"Series") or year ("(2023)") suffix already
 * came off in the same chain: "No Hard Feelings (2023) English Movie"
 * loses all three, while "Broken English" (no type, no year) keeps its
 * English and "The English Patient" never reaches the rule at all.
 * Pure string work, so the rule is assertable without a layout.
 */
internal fun stripMyListTitleSuffixes(title: String, year: Int?, contentType: String?): String {
    val once = UserWords.stripTitleSuffixes(title, year, contentType, emptyList())
    if (once == title) return once
    val lastWord = once.substringAfterLast(' ', "").lowercase()
    if (lastWord !in myListTitleLanguages) return once
    val withoutLanguage = once.dropLast(lastWord.length).trim()
    if (withoutLanguage.isBlank()) return once
    return UserWords.stripTitleSuffixes(withoutLanguage, year, contentType, emptyList())
}

/**
 * The starter shelf for the empty state (and the loaded grid's footer):
 * only titles with real poster art, at most three, in the server's
 * trending order. A posterless title paints a grey tile that makes the
 * catalog look broken, so it never reaches the rail — and the shelf is
 * drawn from all six loaded candidates, so dropping one still leaves
 * three. Saved titles stay on the shelf with a filled heart (the toggle's
 * saved state) instead of being filtered out: the rail is the place the
 * viewer saves from, so hiding a just-saved title would hide the very
 * feedback the toggle promises.
 */
internal fun starterShelf(popular: List<Post>): List<Post> =
    popular.filter { !it.posterUrl.isNullOrBlank() }.take(3)

/**
 * The latest resumable watch row per title: the newest row for each post
 * id, kept only when it is resumable — started and not within the end
 * guard — so a finished title and a row with no duration both drop out
 * here, once, instead of each consumer re-deciding it. The mockup's bars
 * under two posters are illustrative, not data: a bar appears only where
 * a real row says the viewer stopped halfway.
 */
internal fun latestResumableRowsByPostId(
    history: List<WatchHistoryEntity>,
): Map<Int, WatchHistoryEntity> =
    history
        .groupBy { it.postId }
        .mapNotNull { (postId, rows) ->
            val latest = rows.maxByOrNull { it.updatedAt } ?: return@mapNotNull null
            if (!latest.resumable) return@mapNotNull null
            postId to latest
        }
        .toMap()

/**
 * The progress bar each saved tile paints, keyed by post id: the latest
 * watch row for that title, and only when it is resumable — started and
 * not within the end guard — so a finished title paints no bar and a row
 * with no duration paints none either (no honest fraction exists).
 */
internal fun myListProgressByPostId(history: List<WatchHistoryEntity>): Map<Int, Float> =
    latestResumableRowsByPostId(history)
        .mapNotNull { (postId, row) ->
            ContinueWatchingTile.progressFraction(row.positionMs, row.durationMs)
                ?.let { postId to it }
        }
        .toMap()

/**
 * The same fact in words, keyed the same way: the episode scope when the
 * row has one ("S2:E5") plus what remains ("23 min left"), from the same
 * [ContinueWatchingTile] helpers Home's Continue Watching rail already
 * speaks. A movie row has no scope, so it says only what remains. Absent
 * exactly where the bar is absent — the two maps agree on which titles
 * have anything honest to say.
 */
internal fun myListProgressLabelByPostId(
    history: List<WatchHistoryEntity>,
): Map<Int, String> =
    latestResumableRowsByPostId(history)
        .mapNotNull { (postId, row) ->
            listOfNotNull(
                ContinueWatchingTile.scopeLabel(row.seasonNumber, row.episodeNumber),
                ContinueWatchingTile.progressLabel(row.positionMs, row.durationMs),
            ).joinToString(" · ").takeIf { it.isNotBlank() }?.let { postId to it }
        }
        .toMap()

/**
 * One removed row, kept verbatim so Undo restores the title to the place
 * it was taken from instead of re-inserting it as "just added" at the
 * top. The row carries its own `addedAt`; the title is the display title
 * the snackbar already showed, kept beside it so the stack never has to
 * re-derive what the viewer read.
 */
internal data class RemovedFavorite(val row: FavoritePost, val title: String)

/**
 * The removals waiting on their snackbar, newest last. One tap removes
 * the row at once; the way back is the snackbar, not a dialog — and a
 * second remove while the first snackbar is still up must not eat the
 * first undo: each removal keeps its own entry, and the screen works
 * through them one snackbar at a time, newest first. The top title is
 * what the screen's snackbar shows; popping it (Undo or dismiss) surfaces
 * the next entry, or nothing when the stack is empty.
 */
internal class MyListUndoStack {
    private val entries = ArrayDeque<RemovedFavorite>()

    val topTitle: String? get() = entries.lastOrNull()?.title

    val size: Int get() = entries.size

    fun push(entry: RemovedFavorite) {
        entries.addLast(entry)
    }

    fun pop(): RemovedFavorite? = entries.removeLastOrNull()

    fun clear() {
        entries.clear()
    }
}

@HiltViewModel
class MyListViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val watchHistoryDao: WatchHistoryDao,
    private val catalogRepository: CatalogRepository,
    /** L2 (round 2): the keep store — the badge and the Downloads filter read it. */
    private val cacheRepository: CacheRepository,
) : ViewModel() {

    private val sortFlow = MutableStateFlow(ListSort.RECENT)
    /** L1 (round 2): the active filter chip. */
    private val filterFlow = MutableStateFlow(MyListFilter.ALL)
    /** L3 (round 2): a pull-to-refresh is in flight. */
    private val isRefreshingFlow = MutableStateFlow(false)
    /** L4 (round 2): the last rail load failed on the transport. */
    private val railsFailedFlow = MutableStateFlow(false)
    /** L2 (round 2): the downloaded-copy ids, from the keep store. */
    private val downloadedIdsFlow: Flow<Set<Int>> =
        cacheRepository.tasksFlow().map(::downloadedPostIdsFor)
    private val popularFlow = MutableStateFlow<List<Post>>(emptyList())
    /** A4-F5: the second rail's titles — the freshest-first load, beside [popularFlow]. */
    private val recentFlow = MutableStateFlow<List<Post>>(emptyList())
    private val removedTitleFlow = MutableStateFlow<String?>(null)
    /**
     * Whether the trending load has settled — success or failure, either
     * is an answer. The empty state shows a rail skeleton while this is
     * false, so the rail never pops in underneath a message that names a
     * heart that is not on screen yet. A4-F5: the same flag gates the
     * second (Recently Added) rail — both rails load in the same pass, so
     * one settled flag covers both.
     */
    private val popularSettledFlow = MutableStateFlow(false)
    val popularSettled: StateFlow<Boolean> = popularSettledFlow
    /**
     * The rail heart's save failed to resolve — the tap must never
     * silently do nothing. The screen shows the snackbar and clears this;
     * the ViewModel carries only the signal, the message lives in strings.
     */
    private val saveFailedFlow = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = saveFailedFlow
    private val undoStack = MyListUndoStack()
    /**
     * The visible rows as the combine last sorted them, so [remove] can
     * keep the raw row (with its `addedAt`) instead of the display Post
     * that carries none. Updated on every emission, on the same scope
     * that runs the combine; [remove] runs on Main, where the screen
     * called it from.
     */
    private var latestVisibleRows: List<FavoritePost> = emptyList()
    private var popularLoaded = false
    /** A4-F5: the second rail's latch — same rule as [popularLoaded]: only a load that produced titles latches, so a failed newest load retries on the next emission instead of going quiet. */
    private var recentLoaded = false

    // Nine flows, combined as three triples: the typed `combine` overloads this
    // codebase already uses stop at three, and a six-flow call falls back to
    // the vararg form whose array the lambda cannot destructure — CI proved
    // it (run 42155195902). The nesting changes nothing about the values:
    // each inner triple re-emits on its own flows, and the outer triple emits
    // on either third. (Round 2: the sort + filter pair and the downloads +
    // refreshing + rails-failed triple joined the two triples the screen
    // already had.)
    val uiState: StateFlow<MyListUiState> = combine(
        combine(
            favoritesRepository.getAllFavorites(),
            favoritesRepository.observeRefusedIds(),
            combine(sortFlow, filterFlow) { sort, filter -> sort to filter },
        ) { list, refused, sortAndFilter -> Triple(list, refused, sortAndFilter) },
        combine(
            watchHistoryDao.observeDurations(),
            // A4-F5: the second rail rides beside the trending load — the
            // typed combine overloads stop at three, so the two rails pair
            // up first (same nesting the six-flow combine already uses).
            combine(popularFlow, recentFlow) { popular, recent -> popular to recent },
            removedTitleFlow,
        ) { history, popularAndRecent, removedTitle -> Triple(history, popularAndRecent, removedTitle) },
        combine(
            downloadedIdsFlow,
            isRefreshingFlow,
            railsFailedFlow,
        ) { downloadedIds, isRefreshing, railsFailed ->
            Triple(downloadedIds, isRefreshing, railsFailed)
        },
    ) { (list, refused, sortAndFilter), (history, popularAndRecent, removedTitle), (downloadedIds, isRefreshing, railsFailed) ->
        val (sort, filter) = sortAndFilter
        val (popular, recent) = popularAndRecent
        // The stored rows are filtered here, before anything sorts or maps them, so no later step
        // can be handed the unfiltered list by accident. `is18Plus` is the strict half and is null
        // for every row on today's wire; the refusal ledger is the half that fires against a public
        // route that 404s a flagged id exactly as it 404s an absent one.
        val visible = AdultPolicy.listableRows(
            rows = list,
            flagOf = FavoritePost::is18Plus,
            refusedOf = { it.id in refused },
        )
        val sorted = sortMyList(visible, sort, watchedPostIds(history))
        latestVisibleRows = sorted
        MyListUiState(
            favorites = sorted.map(::myListDisplayPost),
            sort = sort,
            filter = filter,
            isLoading = false,
            hiddenCount = list.size - visible.size,
            popular = starterShelf(popular),
            recent = starterShelf(recent),
            removedTitle = removedTitle,
            progressByPostId = myListProgressByPostId(history),
            progressLabelByPostId = myListProgressLabelByPostId(history),
            downloadedPostIds = downloadedIds,
            isRefreshing = isRefreshing,
            railsLoadFailed = railsFailed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MyListUiState(isLoading = true),
    )

    init {
        viewModelScope.launch {
            // One bounded pass per visit: at most
            // [AdultPolicy.MAX_REVALIDATION_CHECKS_PER_PASS] rows, and only rows whose last answer
            // has aged out, so opening My List a second time the same day asks nothing. What it
            // learns lands on the ledger, which re-emits [uiState] through the combine above — a
            // row the server has since refused leaves the grid while the viewer is looking at it.
            //
            // A pass that cannot finish may not cost the viewer the list they came for: the rows on
            // screen are already filtered by whatever the ledger holds, and the next visit tries
            // again. [com.raddstream.app.repository.CatalogRepository.probePost] reports a transport
            // failure as an absence of an answer rather than as a refusal, so the ordinary outage
            // never reaches this catch; the catch is for a database or wiring fault.
            try {
                favoritesRepository.revalidateSavedRows()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The ledger keeps what it held. Nothing here may hide or un-hide a row.
            }
        }
        viewModelScope.launch {
            // The empty/starter list needs something to point at, and the only honest
            // order this app can name is the server's own trending order —
            // most prints and mirrors first. Loaded while the list is small
            // (at most one visible favorite), fetching 6 candidates so
            // excluding an already-saved favorite still leaves 3. The gate
            // is the list's size, not the session's history: a ViewModel
            // born onto two saves never loads, and one born onto one save
            // does — the same list shows the shelf the same way in every
            // session. The latch is set only by a load that produced
            // titles: an empty answer (offline, or a server that sent
            // nothing) retries on the next emission instead of going quiet
            // for this ViewModel's life, and the settled flag tells the
            // empty state the rail is done arriving either way.
            // Both rails load through [loadRails] now, so the init collector,
            // pull-to-refresh and the inline retry all run the same load.
            uiState.collect { state ->
                if (!state.isLoading && state.favorites.size <= 1 &&
                    (!popularLoaded || !recentLoaded)
                ) {
                    loadRails()
                }
            }
        }
    }

    fun setSort(sort: ListSort) {
        sortFlow.value = sort
    }

    /** L1 (round 2): the active filter chip. */
    fun setFilter(filter: MyListFilter) {
        filterFlow.value = filter
    }

    /**
     * L3 (round 2): pull-to-refresh — revalidate the saved rows (the server
     * may have refused one since the last visit) and reload the rails. The
     * rails reload even for a list too big to show the shelf: the load is
     * bounded (two calls, six rows each) and the shelf gate still decides
     * what renders.
     */
    fun refresh() {
        viewModelScope.launch {
            isRefreshingFlow.value = true
            try {
                favoritesRepository.revalidateSavedRows()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The ledger keeps what it held — same rule as init.
            }
            loadRails()
            isRefreshingFlow.value = false
        }
    }

    /** L4 (round 2): the inline retry — reload the rails, nothing else. */
    fun retryRails() {
        viewModelScope.launch { loadRails() }
    }

    /** Whether a rail load is already running — the init collector and a retry must never double-load. Both run on Main. */
    private var railsLoading = false

    /**
     * L4 (round 2): the two rail loads as one call — trending, then newest —
     * so the init collector, pull-to-refresh and the inline retry all run
     * the same load. A rail that already latched is not re-fetched. A load
     * that fails on the transport sets the failed flag (the screen shows
     * the retry card); a load the server answers empty settles to no rail,
     * not an error. Only a load that produced titles latches, so a failed
     * or empty load retries on the next trigger instead of going quiet for
     * this ViewModel's life.
     */
    private suspend fun loadRails() {
        if (railsLoading) return
        railsLoading = true
        try {
            popularSettledFlow.value = false
            val trending = if (popularLoaded) popularFlow.value else loadRail("trending")
            val fresh = if (recentLoaded) recentFlow.value else loadRail("newest")
            railsFailedFlow.value = trending == null || fresh == null
            popularFlow.value = trending ?: emptyList()
            recentFlow.value = fresh ?: emptyList()
            if (trending?.isNotEmpty() == true) popularLoaded = true
            if (fresh?.isNotEmpty() == true) recentLoaded = true
            popularSettledFlow.value = true
        } finally {
            railsLoading = false
        }
    }

    /**
     * One rail load. Null when the transport failed (the caller marks the
     * rails failed); an empty list when the server answered with nothing.
     * The v2-then-v1 fallback is the same one the init collector used —
     * only the failure now survives as a null instead of collapsing to an
     * empty list.
     */
    private suspend fun loadRail(sort: String): List<Post>? = runCatching {
        catalogRepository.browseWorksV2(sort = sort, limit = 6)?.posts
            ?.takeIf { it.isNotEmpty() }
            ?: catalogRepository.getPosts(sort = sort, page = 1, limit = 6).results
    }.getOrNull()

    /**
     * One tap removes the row at once — no held tile, no confirm dialog.
     * The row is only ever one Undo away: the removed row is kept verbatim
     * (with its `addedAt`) on the undo stack, and the snackbar is the way
     * back. A second remove while the first snackbar is still up pushes a
     * second entry instead of overwriting the first — the screen works
     * through the stack one snackbar at a time, newest first.
     */
    fun remove(postId: Int) {
        val display = uiState.value.favorites.firstOrNull { it.id == postId } ?: return
        // The display Post carries no `addedAt`; the raw row does. The
        // fallback (a row the snapshot missed) rebuilds from the display
        // post and loses the original position — better than losing the
        // undo entirely, and unreachable for a row the screen just drew.
        val row = latestVisibleRows.firstOrNull { it.id == postId }
            ?: FavoritePost.fromPost(display)
        undoStack.push(RemovedFavorite(row, display.displayTitle))
        removedTitleFlow.value = undoStack.topTitle
        viewModelScope.launch { favoritesRepository.removeFavorite(postId) }
    }

    fun undoRemoval() {
        val entry = undoStack.pop() ?: return
        removedTitleFlow.value = undoStack.topTitle
        viewModelScope.launch { favoritesRepository.restoreFavorite(entry.row) }
    }

    fun clearRemovedNotice() {
        // The snackbar went away without Undo: that removal stands, and
        // the next queued removal — if a second tap stacked one behind
        // it — surfaces its own snackbar.
        undoStack.pop()
        removedTitleFlow.value = undoStack.topTitle
    }

    fun clearSaveFailed() {
        saveFailedFlow.value = false
    }

    /**
     * My List review (Nov 2026): the starter rail's save toggle — the same
     * add/remove Detail and Home use. A v2 row has no v1 post id (its `id`
     * is the negative work-id sentinel), so Room cannot key it: the toggle
     * first resolves the work to its v1 post through the `work_ref` bridge
     * and toggles THAT row — the same bridge as HomeViewModel's
     * `resolveV1Post`. An unresolvable work is not a silent no-op any
     * more: the screen says the save failed, because a heart that ignores
     * a tap reads as broken whatever the resolution design behind it.
     * Detail runs the same resolution and is the place that can say why.
     */
    fun toggleFavorite(post: Post) {
        viewModelScope.launch {
            val target = resolveV1Post(post)
            if (target == null) {
                saveFailedFlow.value = true
                return@launch
            }
            if (favoritesRepository.isFavorite(target.id).first()) {
                favoritesRepository.removeFavorite(target.id)
            } else {
                favoritesRepository.addFavorite(target)
            }
        }
    }

    /** The v1 post for a row, or the row itself when it already is one. Null when unresolvable. */
    private suspend fun resolveV1Post(post: Post): Post? {
        if (post.workId == null) return post
        val ref = post.workRef?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            catalogRepository.getPosts(workRef = ref, page = 1, limit = 1).results.firstOrNull()
        }.getOrNull()
    }
}
