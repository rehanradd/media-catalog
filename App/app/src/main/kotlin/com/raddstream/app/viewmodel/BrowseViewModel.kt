package com.raddstream.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.network.models.Post
import com.raddstream.app.player.AdCadenceGovernor
import com.raddstream.app.player.AdSurface
import com.raddstream.app.repository.CATALOG_PAGING_CONFIG
import com.raddstream.app.repository.CatalogPagingSource
import com.raddstream.app.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Paging guard for every grid/rail keyed on `post.id`. The server orders `id DESC`, so a
 * scrape landing between page fetches shifts the window and hands the same row back twice;
 * a duplicate key in `LazyVerticalGrid` (or a keyed `MediaRail`) throws. De-duplicating at
 * the paging source keeps one id exactly once per list, first occurrence winning.
 */
internal fun List<Post>.distinctById(): List<Post> = distinctBy { post -> post.id }

/** A paged, single-content-type grid — the destination behind Home's rails and
 *  type jump-off tiles. Feel-instant S6: the list is a Pager, not a hand-rolled
 *  page loop. The screen collects it with `collectAsLazyPagingItems()` and
 *  pages it with the S5 skeletons as placeholders; the ViewModel only owns
 *  the title and the ad-cadence reporting. Phase 7 (M1): the v2 list first,
 *  the v1 list as the fallback — but only on the FIRST page, so a v2 page
 *  and a v1 page can never mix in one list (one row per work vs one row
 *  per site-post would show the same title twice). A later v2 page that
 *  fails keeps the load error instead of silently switching sources
 *  mid-scroll — that rule lives in [CatalogPagingSource] now. */
data class BrowseUiState(
    val title: String = "Catalog",
)

@HiltViewModel
class BrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalogRepository: CatalogRepository,
    private val adCadence: AdCadenceGovernor,
    private val adFreeReader: AdFreeReader,
    private val settingsRepository: ServerSettingsRepository,
) : ViewModel() {

    private val type: String? = (savedStateHandle.get<String>(ARG_TYPE))?.takeIf { it.isNotBlank() && it != "all" }
    /** H10: the sort the rail was ordered by — null means no sort (the old behaviour). */
    private val sort: String? = (savedStateHandle.get<String>(ARG_SORT))?.takeIf { it.isNotBlank() }
    private val _uiState = MutableStateFlow(BrowseUiState(title = titleFor(type, sort)))
    val uiState = _uiState.asStateFlow()

    /**
     * Feel-instant S6: the category grid as a Pager. The type never changes
     * for one Browse screen, so one Pager built at construction serves the
     * whole visit (cached in this scope, so a rotation resumes the loaded
     * pages instead of re-fetching them). The screen retries through the
     * PagingData (`retry()` / `refresh()`); there is no `load()` to call.
     * MX #7 — Kids Lock: the mode rides the Pager — flipping it rebuilds
     * the grid from page 1 with the filter on, exactly as Search does.
     */
    val pagingData: Flow<PagingData<Post>> = settingsRepository.kidsModeActive
        .flatMapLatest { kids ->
            Pager(
                config = CATALOG_PAGING_CONFIG,
                pagingSourceFactory = {
                    CatalogPagingSource(
                        loadV2 = { cursor, limit ->
                            // H10: the sort rides through — null keeps the
                            // old "trending" default, "trending" is the
                            // same order the rail showed.
                            catalogRepository.browseWorksV2(type = type, sort = sort ?: "trending", limit = limit, cursor = cursor)
                        },
                        loadV1 = { page, limit ->
                            // H10: the v1 fallback gets the sort too — a
                            // trending Browse must not silently re-sort to
                            // freshest-first when the server answers v1.
                            catalogRepository.getPosts(type = type, sort = sort, page = page, limit = limit)
                        },
                    )
                },
            ).flow
                .map { data -> if (kids) data.filter { KidsLock.isKidAppropriate(it) } else data }
                .cachedIn(viewModelScope)
        }

    /**
     * How many posters the viewer has passed since last time. The grid's scroll index is the only
     * honest measure of that, and it is reported rather than sampled because the cap is one card
     * per twelve posters: the count has to survive the grid recomposing, paging, and the process
     * being killed with the viewer mid-scroll, so it lands in Room and not in a composable.
     *
     * Crossing the cap asks the governor for the card itself. There is no `loading` state and no
     * slot reserved for an answer: a surface nothing can fill shows nothing, and the viewer scrolls
     * through the space where a card would have been exactly as if there had never been one.
     */
    fun onPostersScrolled(count: Int) {
        if (count <= 0) return
        viewModelScope.launch {
            val adFree = adFreeReader.isAdFree()
            adCadence.onPostersScrolled(count, adFree = adFree)
            adCadence.offer(AdSurface.FEED_CARD, adFree = adFree)
        }
    }

    private companion object {
        const val ARG_TYPE = "type"
        const val ARG_SORT = "sort"
        // H10: a trending Browse is the "Most copies" rail in full — the
        // rail's own title, not "Recently added", which is the unsorted
        // freshest-first list. A typed trending list keeps its type title.
        fun titleFor(type: String?, sort: String?): String = when {
            sort == "trending" && type == null -> "Most copies"
            else -> when (type) {
                null -> "Recently added"
                "movie" -> "Movies"
                "series" -> "Series"
                // `anime` is a filter, not a stored `content_type`: animation is a flag on
                // the post, and the server answers the chip from that flag.
                "anime" -> "Anime"
                "complete_season" -> "Complete seasons"
                "ongoing_season" -> "Ongoing seasons"
                else -> "Catalog"
            }
        }
    }
}
