package com.raddstream.app.ui.components

import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest
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
 * the tile's own ([CatalogImage]) — the same TMDB sizing rewrite, the same
 * host allowlist, the same metered gate — so prefetched bytes are the bytes
 * the tile will show, and a metered connection the viewer never granted
 * image loading to fetches nothing. Coil dedupes an in-flight request for
 * the same URL, so this never double-fetches a tile that is already
 * loading. Never throws: a prefetch is an optimisation, and a failed one
 * must leave the screen exactly as it was.
 */

/** Home rails: first two posters per visible rail, capped. */
internal const val HOME_RAIL_PREFETCH_MAX: Int = 20

/** Search: first page's posters, capped. */
internal const val SEARCH_PREFETCH_MAX: Int = 12

/** Detail: backdrop + similar-rail posters, capped. */
internal const val DETAIL_PREFETCH_MAX: Int = 8

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
            val sized = PosterUrlPolicy.sizedForTile(url, sizing.posterMaxWidthPx)
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
 * Search: the first page's posters across the result groups, in group
 * order. Pure, so the selection is assertable without Coil.
 */
internal fun searchPrefetchUrls(
    groups: List<TypeGroup>,
    max: Int = SEARCH_PREFETCH_MAX,
): List<String> =
    groups.flatMap { group ->
        group.items.mapNotNull { it.posterUrl?.takeIf { url -> url.isNotBlank() } }
    }
        .distinct()
        .take(max)

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
