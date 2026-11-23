package com.raddstream.app.ui.components

import android.content.Context
import coil3.imageLoader
import coil3.request.ImageRequest
import com.raddstream.app.images.ArtUrlPolicy
import com.raddstream.app.images.ImageSizingPolicy
import com.raddstream.app.images.PosterUrlPolicy
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.viewmodel.HomeSection
import com.raddstream.app.viewmodel.TypeGroup
import kotlinx.coroutines.flow.first

/**
 * Feel-instant S2 (Nov 2026): poster-art prefetch for what's on screen and
 * what's next — the generalisation of [prefetchHeroArt], which was the only
 * prefetch in the app.
 *
 * The rule is the hero's rule, unchanged: the request is built exactly like
 * the tile's own ([CatalogImage]) — the same TMDB sizing + wsrv proxy
 * rewrite, the same host allowlist, the same metered gate — so prefetched
 * bytes are the bytes the tile will show, and a metered connection the
 * viewer never granted image loading to fetches nothing. Coil dedupes an
 * in-flight request for the same URL, so this never double-fetches a tile
 * that is already loading. Never throws: a prefetch is an optimisation,
 * and a failed one must leave the screen exactly as it was.
 */

/** Home rails: first two posters per visible rail, capped. */
internal const val HOME_RAIL_PREFETCH_MAX: Int = 20

/** Search: one page's posters per results landing, capped. */
internal const val SEARCH_PREFETCH_MAX: Int = 20

/** Detail: backdrop + similar-rail posters, capped. */
internal const val DETAIL_PREFETCH_MAX: Int = 8

/**
 * Paged grids (Browse, Search): how far past the last visible row the
 * scroll-ahead prefetch reaches — one screen of a two-column grid.
 */
internal const val SCROLL_AHEAD_PREFETCH_LOOKAHEAD: Int = 12

/** Paged grids: the most posters one scroll tick warms — visible + ahead. */
internal const val SCROLL_AHEAD_PREFETCH_MAX: Int = 24

/**
 * Warm up to [max] poster URLs with the hero's guards. Blanks dropped,
 * duplicates warmed once, order kept. The default cap (20) is the Home
 * rails' budget; callers with a smaller budget pass their own.
 */
internal suspend fun prefetchPosterArt(context: Context, urls: List<String>, max: Int = 20) {
    val clean = urls.mapNotNull { it.takeIf { url -> url.isNotBlank() } }.distinct().take(max)
    if (clean.isEmpty()) return
    runCatching {
        val holder = imageSizingHolder(context)
        val sizing = holder.sizing.value
        if (!ImageSizingPolicy.shouldLoad(sizing.imagesAllowedOnMobileData, holder.isMetered())) return
        val serverHost = ArtUrlPolicy.hostOf(serverSettingsRepository(context).serverUrl.first())
        val loader = context.imageLoader
        clean.forEach { url ->
            val sized = PosterUrlPolicy.displayUrl(url, sizing.posterMaxWidthPx)
            if (!ArtUrlPolicy.isAllowed(sized, serverHost)) return@forEach
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(sized)
                    .size(sizing.posterMaxWidthPx, sizing.posterMaxWidthPx)
                    .build(),
            )
        }
    }
}

/**
 * Home rails: the first two posters of every visible rail, in rail order —
 * the rows the viewer is about to scroll into, plus the next rail's first
 * row, which is just the next rail's first two. A rail with no rows, or one
 * that failed, contributes nothing. Pure, so the selection is assertable
 * without Coil.
 */
internal fun homeRailPrefetchUrls(
    sections: List<HomeSection>,
    max: Int = HOME_RAIL_PREFETCH_MAX,
): List<String> =
    sections.filter { it.visible }
        .flatMap { section ->
            section.items.take(2).mapNotNull { it.posterUrl?.takeIf { url -> url.isNotBlank() } }
        }
        .distinct()
        .take(max)

/**
 * Search: the next posters in load order that this query has not warmed
 * yet — the first page on the first landing, then one page per landing
 * after that, so the warm keeps pace with the data instead of re-warming
 * the first page on every page. The caller keeps [alreadyPrefetched] (one
 * set per query) and adds what comes back. Pure, so the selection is
 * assertable without Coil.
 */
internal fun searchNextPrefetchUrls(
    groups: List<TypeGroup>,
    alreadyPrefetched: Set<String>,
    max: Int = SEARCH_PREFETCH_MAX,
): List<String> =
    groups.flatMap { group ->
        group.items.mapNotNull { it.posterUrl?.takeIf { url -> url.isNotBlank() } }
    }
        .distinct()
        .filter { it !in alreadyPrefetched }
        .take(max)

/**
 * Paged grids: the posters for the rows from [firstVisibleIndex] through
 * [lookahead] rows past [lastVisibleIndex] — the visible screen plus one
 * screen ahead of it — in list order. Placeholders (`null`) have no art
 * yet, blanks and duplicates drop out, and the window is clamped to the
 * loaded snapshot, so a footer index or a short tail can never overrun
 * it. Pure, so the selection is assertable without Coil.
 */
internal fun scrollAheadPrefetchUrls(
    items: List<Post?>,
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    lookahead: Int = SCROLL_AHEAD_PREFETCH_LOOKAHEAD,
    max: Int = SCROLL_AHEAD_PREFETCH_MAX,
): List<String> {
    if (items.isEmpty() || firstVisibleIndex >= items.size) return emptyList()
    val start = firstVisibleIndex.coerceAtLeast(0)
    val end = minOf(lastVisibleIndex + lookahead, items.size - 1)
    if (end < start) return emptyList()
    return items.subList(start, end + 1)
        .mapNotNull { it?.posterUrl?.takeIf { url -> url.isNotBlank() } }
        .distinct()
        .take(max)
}

/**
 * Detail: the title's own art first — widest backdrop, else poster — then
 * the similar rail's posters. Pure, so the selection is assertable
 * without Coil.
 */
internal fun detailPrefetchUrls(
    post: PostDetail?,
    similarTitles: List<Post>,
    max: Int = DETAIL_PREFETCH_MAX,
): List<String> {
    val hero = post?.let {
        it.backdropList.firstOrNull() ?: it.posterUrl?.takeIf { url -> url.isNotBlank() }
    }
    return (
        listOfNotNull(hero) +
            similarTitles.mapNotNull { it.posterUrl?.takeIf { url -> url.isNotBlank() } }
        )
        .distinct()
        .take(max)
}
