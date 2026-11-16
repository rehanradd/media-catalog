package com.raddstream.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raddstream.app.data.local.FavoritePost
import com.raddstream.app.data.local.WatchHistoryDao
import com.raddstream.app.data.local.WatchHistoryEntity
import com.raddstream.app.network.models.Post
import com.raddstream.app.repository.CatalogRepository
import com.raddstream.app.repository.FavoritesRepository
import com.raddstream.app.ui.components.UserWords
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ListSort(val label: String) {
    RECENT("Recently added"),
    TITLE("Title A–Z"),
    UNWATCHED("Unwatched first"),
}

data class MyListUiState(
    val favorites: List<Post> = emptyList(),
    val sort: ListSort = ListSort.RECENT,
    val isLoading: Boolean = false,
    /**
     * Rows this device still stores and this build may not draw. Counted, never listed: the
     * viewer's list did not shrink, the server's answer about it changed.
     */
    val hiddenCount: Int = 0,
    /** The three most-covered titles in the catalog, for the empty state. Empty when unloaded. */
    val popular: List<Post> = emptyList(),
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
) {
    /** [AdultPolicy.hiddenNotice] — a number, because a notice naming a hidden title is a list of one. */
    val hiddenNotice: String? get() = AdultPolicy.hiddenNotice(hiddenCount)
}

/**
 * The My List order, as one pure function: recently-added and title are the
 * old orders; unwatched-first puts titles with no finished watch row ahead
 * of finished ones, then recently-added inside each half. "Finished" is the
 * rail's own end-guard, via [watchedTitles] — a half-watched title still
 * counts as unwatched, and a series counts as watched when any episode of
 * it is finished.
 */
fun sortMyList(
    posts: List<FavoritePost>,
    sort: ListSort,
    watchedTitles: Set<String>,
): List<FavoritePost> {
    fun isWatched(post: FavoritePost) = (post.cleanTitle ?: post.title) in watchedTitles
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
 * The progress bar each saved tile paints, keyed by post id: the latest
 * watch row for that title, and only when it is resumable — started and
 * not within the end guard — so a finished title paints no bar and a row
 * with no duration paints none either (no honest fraction exists). The
 * mockup's bars under two posters are illustrative, not data: a bar
 * appears only where a real row says the viewer stopped halfway.
 */
internal fun myListProgressByPostId(history: List<WatchHistoryEntity>): Map<Int, Float> =
    history
        .groupBy { it.postId }
        .mapNotNull { (postId, rows) ->
            val latest = rows.maxByOrNull { it.updatedAt } ?: return@mapNotNull null
            if (!latest.resumable) return@mapNotNull null
            ContinueWatchingTile.progressFraction(latest.positionMs, latest.durationMs)
                ?.let { postId to it }
        }
        .toMap()

@HiltViewModel
class MyListViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val watchHistoryDao: WatchHistoryDao,
    private val catalogRepository: CatalogRepository,
) : ViewModel() {

    private val sortFlow = MutableStateFlow(ListSort.RECENT)
    private val popularFlow = MutableStateFlow<List<Post>>(emptyList())
    private val removedTitleFlow = MutableStateFlow<String?>(null)
    private var lastRemoved: Post? = null
    private var popularLoaded = false

    // Six flows, combined as two triples: the typed `combine` overloads this
    // codebase already uses stop at three, and a six-flow call falls back to
    // the vararg form whose array the lambda cannot destructure — CI proved
    // it (run 42155195902). The nesting changes nothing about the values:
    // each inner triple re-emits on its own flows, and the outer pair emits
    // on either half.
    val uiState: StateFlow<MyListUiState> = combine(
        combine(
            favoritesRepository.getAllFavorites(),
            favoritesRepository.observeRefusedIds(),
            sortFlow,
        ) { list, refused, sort -> Triple(list, refused, sort) },
        combine(
            watchHistoryDao.observeDurations(),
            popularFlow,
            removedTitleFlow,
        ) { history, popular, removedTitle -> Triple(history, popular, removedTitle) },
    ) { (list, refused, sort), (history, popular, removedTitle) ->
        // The stored rows are filtered here, before anything sorts or maps them, so no later step
        // can be handed the unfiltered list by accident. `is18Plus` is the strict half and is null
        // for every row on today's wire; the refusal ledger is the half that fires against a public
        // route that 404s a flagged id exactly as it 404s an absent one.
        val visible = AdultPolicy.listableRows(
            rows = list,
            flagOf = FavoritePost::is18Plus,
            refusedOf = { it.id in refused },
        )
        val sorted = sortMyList(visible, sort, watchedTitles(history))
        MyListUiState(
            favorites = sorted.map(::myListDisplayPost),
            sort = sort,
            isLoading = false,
            hiddenCount = list.size - visible.size,
            popular = starterShelf(popular),
            removedTitle = removedTitle,
            progressByPostId = myListProgressByPostId(history),
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
            // most prints and mirrors first. Loaded once when <= 1 favorite is visible,
            // fetching 6 candidates so excluding an already-saved favorite still leaves 3.
            uiState.collect { state ->
                if (!state.isLoading && state.favorites.size <= 1 && !popularLoaded) {
                    popularLoaded = true
                    popularFlow.value = runCatching {
                        catalogRepository.browseWorksV2(sort = "trending", limit = 6)?.posts
                            ?.takeIf { it.isNotEmpty() }
                            ?: catalogRepository.getPosts(sort = "trending", page = 1, limit = 6).results
                    }.getOrNull() ?: emptyList()
                }
            }
        }
    }

    fun setSort(sort: ListSort) {
        sortFlow.value = sort
    }

    /**
     * One tap removes the row at once — no held tile, no confirm dialog.
     * The row is only ever one Undo away: the removed post is kept in memory
     * and [undoRemoval] puts it back, and the snackbar is the way back.
     */
    fun remove(postId: Int) {
        val post = uiState.value.favorites.firstOrNull { it.id == postId } ?: return
        lastRemoved = post
        removedTitleFlow.value = post.displayTitle
        viewModelScope.launch { favoritesRepository.removeFavorite(postId) }
    }

    fun undoRemoval() {
        val post = lastRemoved ?: return
        lastRemoved = null
        removedTitleFlow.value = null
        viewModelScope.launch { favoritesRepository.addFavorite(post) }
    }

    fun clearRemovedNotice() {
        lastRemoved = null
        removedTitleFlow.value = null
    }

    /**
     * My List review (Nov 2026): the starter rail's save toggle — the same
     * add/remove Detail and Home use. A v2 row has no v1 post id (its `id`
     * is the negative work-id sentinel), so Room cannot key it: the toggle
     * first resolves the work to its v1 post through the `work_ref` bridge
     * and toggles THAT row — the same bridge as HomeViewModel's
     * `resolveV1Post`. An unresolvable work is a no-op here; Detail runs
     * the same resolution and is the place that can say why.
     */
    fun toggleFavorite(post: Post) {
        viewModelScope.launch {
            val target = resolveV1Post(post) ?: return@launch
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
