package com.raddstream.app.images

import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult

/**
 * wsrv.nl fallback (2026-11-20): poster loads go to the wsrv.nl resize
 * proxy first ([PosterUrlPolicy.wsrvFor]); when that fetch fails for any
 * reason — the proxy down, rate-limited, a bad response — this retries
 * exactly once with the direct URL the proxy URL itself carries
 * ([PosterUrlPolicy.directUrlOfWsrv]), so a poster still loads and the
 * failure is invisible to the viewer.
 *
 * Why an interceptor and not a timeout race: a race would download every
 * poster twice, which spends the bytes this slice exists to save. The
 * fallback costs nothing on the success path (the common one, 100/100 in
 * the probe) and exactly one extra fetch on the failure path. It covers
 * both load sites at once — [com.raddstream.app.ui.components.CatalogImage]
 * tiles and the [com.raddstream.app.ui.components.PosterPrefetch] warm —
 * because every request flows through the one [coil.ImageLoader].
 * [com.raddstream.app.ui.components.CatalogImage]'s own bounded
 * auto-retry still sits on top: if the direct retry also fails, the tile
 * retries the wsrv URL again (bounded), and each of those attempts falls
 * back to direct once more.
 *
 * The retry is a fresh request with the same size and crossfade, so the
 * direct URL gets its own memory/disk-cache lookup first — a poster
 * already cached direct loads instantly. The guard against a wsrv URL
 * wrapping another wsrv URL (which would loop) returns the error as-is:
 * [PosterUrlPolicy] never builds one, so reaching it means a row the
 * server did not vet, and looping on it would be worse than failing.
 */
class WsrvFallbackInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data
        if (data !is String || !PosterUrlPolicy.isWsrvUrl(data)) {
            return chain.proceed(request)
        }
        val result = chain.proceed(request)
        if (result is SuccessResult) return result
        val direct = PosterUrlPolicy.directUrlOfWsrv(data) ?: return result
        if (PosterUrlPolicy.isWsrvUrl(direct)) return result
        return chain.proceed(request.newBuilder().data(direct).build())
    }
}
