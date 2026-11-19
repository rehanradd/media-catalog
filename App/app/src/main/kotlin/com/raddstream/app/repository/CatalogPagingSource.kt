package com.raddstream.app.repository

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostsResponse
import kotlinx.coroutines.CancellationException

/**
 * Feel-instant S6: the one PagingConfig for the catalog's paged lists —
 * browse and search both page the network in 20s (the spec's 20–30), look
 * ten rows ahead, keep placeholders on so the S5 skeletons stand in for
 * rows not yet loaded, and fetch 50 on the first load so the first screen
 * is full. The 100-row server calls are gone from the UI: the paging layer
 * asks for `loadSize` rows per call and the server answers that many.
 *
 * The lookahead was five rows (Nov 2026): a normal scroller outran it and
 * hit placeholders on a slow page fetch, so it is ten now — half a page of
 * head start, inside the research's ~1/3-of-initial-load guidance. The
 * `maxSize` of 200 bounds the in-memory pages at ten pages: a long session
 * used to keep every loaded page forever, and a page dropped far behind
 * reloads from the S1 disk cache on scroll-back instead of living in RAM.
 *
 * RemoteMediator + Room as source of truth comes after S3c, NOT in this
 * slice: this is a network-only source.
 */
internal val CATALOG_PAGING_CONFIG = PagingConfig(
    pageSize = 20,
    prefetchDistance = 10,
    enablePlaceholders = true,
    initialLoadSize = 50,
    maxSize = 200,
)

/**
 * The key for one page of a catalog list. The server has two pagination
 * shapes, and the key names which one this page came from — a list must
 * never mix them (one row per work vs one row per site-post would show
 * the same title twice).
 *
 * [itemsBefore] is how many rows precede this page: the source reports it
 * (and `itemsAfter`) on every page, so `LazyPagingItems.itemCount` is the
 * server's total and the unloaded tail renders as placeholders instead of
 * a short list.
 */
sealed interface CatalogPageKey {
    /** The first load — try v2, fall back to v1. Also the initial key Paging passes (null). */
    data object First : CatalogPageKey

    /** A v2 keyset page: the cursor the server handed back, and the rows before it. */
    data class V2(val cursor: String, val itemsBefore: Int) : CatalogPageKey

    /** A v1 offset page: the 1-based page number, and the rows before it. */
    data class V1(val page: Int, val itemsBefore: Int) : CatalogPageKey
}

/**
 * What one loaded page reports back to the ViewModel that owns the list.
 * The totals ride on EVERY page, not just the first: v2's `totalEstimate`
 * can be null, and then the total is however many rows have loaded so far
 * — without the per-page report the count line would freeze at the first
 * page's size while the list kept growing past it. The fuzzy flag and the
 * suggestion only ever describe the first page, so the ViewModel applies
 * those on [isFirstPage] and ignores them on appends.
 */
data class CatalogPageMeta(
    val isFirstPage: Boolean,
    /** The server's total for this list, or null when v2 did not say. */
    val totalEstimate: Int?,
    /** True when the total is the server's cap (1000), not the real total. */
    val totalCapped: Boolean,
    /** True when this page is the server's one-edit-away fallback. */
    val isFuzzy: Boolean,
    /** The server's "did you mean" spelling, or null when none was offered. */
    val suggestion: String?,
    /** Rows loaded so far in this list (itemsBefore + this page), after dedupe. */
    val loadedCount: Int,
)

/**
 * Feel-instant S6: the network-only PagingSource behind browse and search.
 * One source serves both lists because the fetch rule is the same for both:
 * the first load tries v2 (one row per work) and falls back to v1 when v2
 * is empty or fails, and every later page continues on whichever route the
 * first page chose. The two routes are injected as lambdas so each list
 * binds its own query, filters and sort once, at construction.
 *
 * [loadV2] is null when v2 does not apply at all — a free-only or language
 * search, which v2 cannot serve — and the source goes straight to v1
 * without ever asking v2. [loadV1] throws like [CatalogRepository.getPosts];
 * [loadV2] answers null on any failure like `browseWorksV2`/`searchWorksV2`.
 *
 * Dedupe: the server orders `id DESC`, so a scrape landing between page
 * fetches shifts the v1 window and hands the same row back twice. An id
 * already served never shows twice in one list, first occurrence winning.
 */
class CatalogPagingSource(
    private val loadV2: (suspend (cursor: String?, limit: Int) -> V2BrowsePage?)?,
    private val loadV1: suspend (page: Int, limit: Int) -> PostsResponse,
    private val onPageMeta: ((CatalogPageMeta) -> Unit)? = null,
    private val onFirstPageError: ((Throwable) -> Unit)? = null,
) : PagingSource<CatalogPageKey, Post>() {

    /** Ids already served in this list, so a repeated row never shows twice. */
    private val seenIds = mutableSetOf<Int>()

    /**
     * The key that loads the page before each nextKey this source has
     * handed out. A page's `prevKey` is the key for the page before it, and
     * a cursor is opaque — the only place that key exists is the load that
     * produced the nextKey — so the source records it as it goes. Without
     * the chain, a refresh could never page back up past the page it
     * restarted from.
     */
    private val prevKeyByNextKey = mutableMapOf<CatalogPageKey, CatalogPageKey>()

    override suspend fun load(params: LoadParams<CatalogPageKey>): LoadResult<CatalogPageKey, Post> {
        val key = params.key
        return try {
            when (key) {
                null, CatalogPageKey.First -> loadFirstPage(params.loadSize)
                is CatalogPageKey.V2 -> loadV2Page(key, params.loadSize)
                is CatalogPageKey.V1 -> loadV1Page(key, params.loadSize)
            }
        } catch (cancelled: CancellationException) {
            // A cancelled load is not a failed load: rethrow so the
            // coroutine ends cancelled instead of painting an error over a
            // list the viewer already left (Session 155/156, same rule as
            // the repository's own catches).
            throw cancelled
        } catch (error: Exception) {
            if (key == null || key == CatalogPageKey.First) onFirstPageError?.invoke(error)
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<CatalogPageKey, Post>): CatalogPageKey? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        // The refresh key is the page that holds the anchor: Paging loads
        // it, then pages around it until the anchor is back in view. The
        // page's own load key is what the map recorded against its nextKey
        // (the load that returned the nextKey is the load of this page). A
        // last page has no nextKey, so fall back to its prevKey — one page
        // above the anchor, and the append after it restores the anchor.
        // A first page has neither, so the refresh starts from the top.
        return page.nextKey?.let { prevKeyByNextKey[it] } ?: page.prevKey
    }

    private suspend fun loadFirstPage(loadSize: Int): LoadResult<CatalogPageKey, Post> {
        // Phase 7 (M1): v2 first — one row per work — with v1 as the
        // fallback. An EMPTY v2 page falls back too, because v2 matches
        // titles only and only v1 matches cast names.
        val v2 = loadV2?.invoke(null, loadSize)
        if (v2 != null && v2.posts.isNotEmpty()) {
            val posts = dedupe(v2.posts)
            onPageMeta?.invoke(
                CatalogPageMeta(
                    isFirstPage = true,
                    totalEstimate = v2.totalEstimate,
                    totalCapped = v2.totalCapped,
                    isFuzzy = false,
                    suggestion = null,
                    loadedCount = posts.size,
                ),
            )
            val itemsAfter = v2.totalEstimate
                ?.let { (it - posts.size).coerceAtLeast(0) }
                ?: LoadResult.Page.COUNT_UNDEFINED
            val nextKey =
                if (v2.hasMore && v2.nextCursor != null) CatalogPageKey.V2(v2.nextCursor, posts.size) else null
            return page(
                loadKey = CatalogPageKey.First,
                posts = posts,
                prevKey = null,
                nextKey = nextKey,
                itemsBefore = 0,
                itemsAfter = itemsAfter,
            )
        }
        val resp = loadV1(1, loadSize)
        val posts = dedupe(resp.results)
        onPageMeta?.invoke(
            CatalogPageMeta(
                isFirstPage = true,
                totalEstimate = resp.total,
                totalCapped = false,
                isFuzzy = resp.fuzzy,
                suggestion = resp.suggestion,
                loadedCount = posts.size,
            ),
        )
        val itemsAfter = (resp.total - posts.size).coerceAtLeast(0)
        val more = resp.results.size >= loadSize && 1 < resp.totalPages
        val nextKey = if (more) CatalogPageKey.V1(2, posts.size) else null
        return page(
            loadKey = CatalogPageKey.First,
            posts = posts,
            prevKey = null,
            nextKey = nextKey,
            itemsBefore = 0,
            itemsAfter = itemsAfter,
        )
    }

    private suspend fun loadV2Page(
        key: CatalogPageKey.V2,
        loadSize: Int,
    ): LoadResult<CatalogPageKey, Post> {
        // A null here is the route failing on a later page: the first page
        // already committed this list to v2, so a later v2 page that fails
        // keeps the load error instead of silently switching sources
        // mid-scroll (the BrowseViewModel rule, kept).
        val page = loadV2?.invoke(key.cursor, loadSize)
            ?: throw IllegalStateException("The catalog could not serve the next page.")
        val posts = dedupe(page.posts)
        val loadedCount = key.itemsBefore + posts.size
        onPageMeta?.invoke(
            CatalogPageMeta(
                isFirstPage = false,
                totalEstimate = page.totalEstimate,
                totalCapped = page.totalCapped,
                isFuzzy = false,
                suggestion = null,
                loadedCount = loadedCount,
            ),
        )
        val itemsAfter = page.totalEstimate
            ?.let { (it - loadedCount).coerceAtLeast(0) }
            ?: LoadResult.Page.COUNT_UNDEFINED
        // A null cursor with hasMore true means the server withheld the
        // cursor and the caller must stop (V2BrowsePage's contract) — the
        // list ends here rather than looping on the same page.
        val nextKey =
            if (page.hasMore && page.nextCursor != null) CatalogPageKey.V2(page.nextCursor, loadedCount) else null
        return page(
            loadKey = key,
            posts = posts,
            prevKey = prevKeyByNextKey[key],
            nextKey = nextKey,
            itemsBefore = key.itemsBefore,
            itemsAfter = itemsAfter,
        )
    }

    private suspend fun loadV1Page(
        key: CatalogPageKey.V1,
        loadSize: Int,
    ): LoadResult<CatalogPageKey, Post> {
        val resp = loadV1(key.page, loadSize)
        val posts = dedupe(resp.results)
        val loadedCount = key.itemsBefore + posts.size
        onPageMeta?.invoke(
            CatalogPageMeta(
                isFirstPage = false,
                totalEstimate = resp.total,
                totalCapped = false,
                isFuzzy = resp.fuzzy,
                suggestion = resp.suggestion,
                loadedCount = loadedCount,
            ),
        )
        val itemsAfter = (resp.total - loadedCount).coerceAtLeast(0)
        val more = resp.results.size >= loadSize && key.page < resp.totalPages
        val nextKey = if (more) CatalogPageKey.V1(key.page + 1, loadedCount) else null
        return page(
            loadKey = key,
            posts = posts,
            prevKey = prevKeyByNextKey[key],
            nextKey = nextKey,
            itemsBefore = key.itemsBefore,
            itemsAfter = itemsAfter,
        )
    }

    /**
     * One loaded page, with the prevKey chain recorded: the nextKey this
     * page hands out maps back to the key that loaded this page, so the
     * next page's `prevKey` — and [getRefreshKey] — can find it.
     */
    private fun page(
        loadKey: CatalogPageKey,
        posts: List<Post>,
        prevKey: CatalogPageKey?,
        nextKey: CatalogPageKey?,
        itemsBefore: Int,
        itemsAfter: Int,
    ): LoadResult.Page<CatalogPageKey, Post> {
        if (nextKey != null) prevKeyByNextKey[nextKey] = loadKey
        return LoadResult.Page(
            data = posts,
            prevKey = prevKey,
            nextKey = nextKey,
            itemsBefore = itemsBefore,
            itemsAfter = itemsAfter,
        )
    }

    /** First occurrence of an id wins, in this list and within one page. */
    private fun dedupe(posts: List<Post>): List<Post> = posts.filter { seenIds.add(it.id) }
}
