package com.raddstream.app.images

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

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

    private val TMDB_SIZE_REGEX = Regex("/t/p/w(\\d+)/")

    /** The free resize proxy poster loads route through (see [wsrvFor]). */
    const val WSRV_HOST = "wsrv.nl"

    /** WebP quality the proxy encodes at — the measured 46.8% saving. */
    const val WSRV_QUALITY = 72

    fun sizedForTile(url: String, widthPx: Int): String {
        val marker = "/t/p/original/"
        val markerAt = url.indexOf(marker)
        if (markerAt < 0) return url
        val host = url.substring(0, markerAt).substringAfter("://")
        if (host != "image.tmdb.org") return url
        val size = TMDB_SIZES.firstOrNull { it >= widthPx } ?: return url
        return url.replace(marker, "/t/p/w$size/")
    }

    /**
     * wsrv.nl resize proxy (2026-11-20): a TMDB poster at display size as
     * WebP, from Cloudflare's edge — `https://wsrv.nl/?url=<poster>&w=<px>&output=webp&q=72`.
     * Free, no key, no signup, running since 2007. Measured on 100 real
     * catalog posters from the Oracle VM (AUTO width 640): 46.8% fewer
     * bytes (mean 67,438 → 35,869) and ~30% faster (0.377 s → 0.264 s);
     * originals save 64.7%. TMDB posters plus the neutral upload hosts
     * ([ArtUrlPolicy.NEUTRAL_UPLOAD_HOSTS]) go through it: those hosts
     * are the 19 poster-URL leaks the gate's step-11 sweep found
     * (i.imageflix.cam ×16, i.imgur.com ×1) — routing them through the
     * proxy means the upstream host never learns a viewer opened a
     * title. A signed `/api/v1/art` handle must never be handed to a
     * third party, and every other host keeps its direct fetch. The width
     * is capped at the TMDB size embedded in the URL, so a w342 source
     * is never upscaled to a 640 px tile — the resize only ever shrinks.
     * A non-proxied URL, a blank URL, or a non-positive width passes
     * through untouched, and wrapping a wsrv URL again is a no-op (the
     * encoded source carries no literal `/t/p/` for the host check to
     * match).
     *
     * Fair use: the public instance rate-limits uncached images
     * (2,500/10 min/IP). If traffic ever outgrows it, the fallback is
     * the self-hosted weserv/images module behind our own nginx — not
     * built here, noted in the session log. If wsrv.nl is ever down,
     * [WsrvFallbackInterceptor] retries once with the direct URL.
     */
    fun wsrvFor(url: String, widthPx: Int): String {
        if (url.isBlank() || widthPx <= 0) return url
        if (!isProxiedHost(url)) return url
        val sizedWidth = TMDB_SIZE_REGEX.find(url)?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
        val effective = if (sizedWidth != null) minOf(widthPx, sizedWidth) else widthPx
        if (effective <= 0) return url
        val encoded = URLEncoder.encode(url, Charsets.UTF_8.name())
        return "https://$WSRV_HOST/?url=$encoded&w=$effective&output=webp&q=$WSRV_QUALITY"
    }

    /**
     * [sizedForTile] then [wsrvFor]: the one composition both load sites use.
     * A non-positive width leaves the URL untouched — a caller that does not
     * know the tile size must not get a resized (or proxied) URL back.
     *
     * Video audit issue 10 (Nov 2026): [bypassProxy] skips the wsrv.nl
     * resize proxy and returns the sized direct URL. Live-TV channel logos
     * pass true — imgur rate-limits wsrv's fetcher (429), so every imgur
     * logo paid a ~10 s dead wait on the proxy before the fallback
     * interceptor retried it direct. A bypassed URL is still the same
     * sized URL the tile and its prefetch both use, so the prefetch warms
     * exactly the bytes the tile shows.
     */
    fun displayUrl(url: String, widthPx: Int, bypassProxy: Boolean = false): String {
        if (widthPx <= 0) return url
        val sized = sizedForTile(url, widthPx)
        return if (bypassProxy) sized else wsrvFor(sized, widthPx)
    }

    /** True when [url] is a wsrv.nl proxy URL this policy built. */
    fun isWsrvUrl(url: String): Boolean =
        runCatching { URI(url).host?.lowercase() }.getOrNull() == WSRV_HOST

    /**
     * The direct URL a wsrv.nl URL wraps (its `url` query param), or null
     * when [url] is not a wsrv.nl URL or carries no `url` param. The
     * fallback interceptor recovers the direct fetch from the proxy URL
     * itself, so no call site has to remember what it rewrote.
     */
    fun directUrlOfWsrv(url: String): String? {
        if (!isWsrvUrl(url)) return null
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        val param = query.split("&").firstOrNull { it.startsWith("url=") } ?: return null
        return runCatching {
            URLDecoder.decode(param.substringAfter("="), Charsets.UTF_8.name())
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * True when [url]'s host is one the proxy may fetch: TMDB, or a neutral
     * upload host. The host is read with the same substring rule
     * [sizedForTile] uses, so a lookalike path on another host never
     * matches — and a wsrv URL itself never matches, because its source
     * sits percent-encoded in the query, not in the path.
     */
    private fun isProxiedHost(url: String): Boolean {
        val schemeAt = url.indexOf("://")
        if (schemeAt < 0) return false
        val host = url.substring(schemeAt + 3).substringBefore("/").lowercase().trimEnd('.')
        if (host == "image.tmdb.org") return true
        return host in ArtUrlPolicy.NEUTRAL_UPLOAD_HOSTS
    }
}
