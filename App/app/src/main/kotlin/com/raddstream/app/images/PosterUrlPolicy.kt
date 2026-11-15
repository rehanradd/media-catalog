package com.raddstream.app.images

/**
 * B2 (home review, 2026-11-15): a TMDB poster served at `/t/p/original/` is
 * a full-size file — measured 453 KB for one title, 0.49 s from the VM —
 * that Coil downloads whole and downsamples locally. `.size()` shrinks the
 * decoded bitmap, never the download, so the bytes are pure waste for a
 * 180 dp tile. ~8% of the home feed's posters are originals (4 of the first
 * 50 browse items); the rest are already right-sized CDN images (w342/w500,
 * ~60–70 KB) or small Amazon thumbs.
 *
 * This rewrites only the `/original/` segment to the smallest TMDB width
 * that still covers the requested tile width (w92/w154/w185/w342/w500/w780).
 * A request wider than 780 px keeps `original` — nothing larger exists.
 * Sized variants, the `/api/v1/art` proxy URLs and every non-TMDB host pass
 * through untouched, so the Coil cache key for everything else is unchanged.
 * Pure string work, no Android types — assertable in a plain JVM test.
 */
object PosterUrlPolicy {

    private val TMDB_SIZES = listOf(92, 154, 185, 342, 500, 780)

    fun sizedForTile(url: String, widthPx: Int): String {
        val marker = "/t/p/original/"
        val markerAt = url.indexOf(marker)
        if (markerAt < 0) return url
        val host = url.substring(0, markerAt).substringAfter("://")
        if (host != "image.tmdb.org") return url
        val size = TMDB_SIZES.firstOrNull { it >= widthPx } ?: return url
        return url.replace(marker, "/t/p/w$size/")
    }
}
