package com.raddstream.app.network

import android.content.Context
import com.raddstream.app.data.cache.ConnectivityProbe
import com.raddstream.app.data.cache.NetworkClass
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Feel-instant S1 (Nov 2026): the OkHttp disk cache for the catalog client,
 * plus the two interceptors that make it useful.
 *
 * The server sends no `Cache-Control` and no ETag on its GETs (measured live
 * 2026-11-18: `GET /api/v1/posts` answers 200 with Cloudflare headers only),
 * so without this layer every repeat visit, back-navigation and rotation
 * re-hits the network. The cache below is the cheap layer under Room: it
 * makes repeat reads ~0 ms of network, it is NOT a source of truth, and it
 * never changes what the server answers — the first fetch still goes out.
 *
 * Rules, all pinned by [HttpCacheTest]:
 * - Only GETs on the catalog read paths are cached, and only by positive
 *   allowlist. POST `/api/v2/play`, auth (`/api/v2/account*`,
 *   `/api/v1/contact`), the panel (`/api/v1/app/remote-config`), the
 *   redirectors (`/api/v1/stream`, `/api/v2/resolve`) and stats never get a
 *   forced header — and OkHttp never stores a POST response anyway.
 * - Browse/rails reads get `max-age=300`; free-text search gets `max-age=60`
 *   (a search answer goes stale faster than a rail).
 * - The server's own cache headers always win: the network interceptor only
 *   forces a `max-age` when the response carries neither `Cache-Control`
 *   nor `Expires`.
 * - Offline, the application interceptor rewrites a cacheable GET to
 *   `only-if-cached, max-stale=…` (24 h browse, 1 h search) so a stale copy
 *   serves instead of failing. No cached copy means the same failure as
 *   before — a 504 from OkHttp, never a fabricated answer.
 * - Online, the same interceptor adds a request-side `max-stale` to a
 *   cacheable GET (30 min browse, 5 min search), so a repeat visit inside
 *   the window serves from disk with no network round trip at all. This
 *   is the client half of stale-while-revalidate: OkHttp 4.12.0 does NOT
 *   honour a response `stale-while-revalidate` (its `CacheStrategy`
 *   reads `max-stale` only from the *request* — `CacheStrategy.kt`,
 *   `computeCandidate`, parent-4.12.0), so a header forced onto the
 *   response would do nothing. The request header is what makes a stale
 *   copy serve, and it is also what the offline rewrite already relies
 *   on. A request that already carries a `Cache-Control` of its own is
 *   left alone, so an explicit refresh still bypasses the cache.
 */

/** The disk cache budget for catalog GETs — 50 MB under `cacheDir/http`. */
const val HTTP_CACHE_MAX_BYTES: Long = 50L * 1024 * 1024

const val HTTP_CACHE_DIR_NAME: String = "http"

/** Browse/rails reads stay fresh for 5 minutes. */
const val BROWSE_CACHE_MAX_AGE_SECONDS: Int = 300

/** Free-text search answers stay fresh for 1 minute. */
const val SEARCH_CACHE_MAX_AGE_SECONDS: Int = 60

/** Offline, a browse/rails answer may serve stale for up to 24 hours. */
const val BROWSE_OFFLINE_MAX_STALE_SECONDS: Int = 86_400

/** Offline, a search answer may serve stale for up to 1 hour. */
const val SEARCH_OFFLINE_MAX_STALE_SECONDS: Int = 3_600

/**
 * Online, a browse/rails answer may serve stale for up to 30 minutes past
 * its 5-minute freshness — a repeat visit inside the window is a disk
 * read, not a network round trip. Rails change slowly (new titles land
 * over hours), so the staleness is bounded and the gain is a repeat open
 * with no wait.
 */
const val BROWSE_STALE_WINDOW_SECONDS: Int = 1_800

/** Online, a search answer may serve stale for up to 5 minutes past its 1-minute freshness. */
const val SEARCH_STALE_WINDOW_SECONDS: Int = 300

/** Which cache bucket a catalog read belongs to — the freshness it gets. */
internal enum class HttpCacheClass {
    BROWSE,
    SEARCH,
}

/**
 * The cache bucket for one request, or null when the request must never be
 * cached. Pure, so the table is assertable without a network call.
 *
 * `/api/v1/posts` is both routes: with a `q` query it is a free-text search
 * (60 s), without it a browse list (300 s). Facet/sort-only queries stay
 * browse — they narrow the catalog, they are not a search.
 */
internal fun httpCacheClassFor(url: HttpUrl, method: String): HttpCacheClass? {
    if (!method.equals("GET", ignoreCase = true)) return null
    val path = url.encodedPath
    return when {
        path == "/api/v1/posts" && url.queryParameter("q") != null -> HttpCacheClass.SEARCH
        path == "/api/v2/search" || path == "/api/v2/suggest" -> HttpCacheClass.SEARCH
        path == "/api/v1/posts" ||
            path == "/api/v1/trending" ||
            path == "/api/v1/related" ||
            path == "/api/v1/facets" ||
            path == "/api/v2/browse" ||
            path == "/api/v1/live-tv/channels" ||
            DETAIL_PATH_REGEX.matches(path) ||
            path.startsWith("/api/v2/works/") -> HttpCacheClass.BROWSE
        else -> null
    }
}

/** The forced `max-age` for a bucket, in seconds. */
internal fun httpCacheMaxAgeSeconds(cacheClass: HttpCacheClass): Int = when (cacheClass) {
    HttpCacheClass.BROWSE -> BROWSE_CACHE_MAX_AGE_SECONDS
    HttpCacheClass.SEARCH -> SEARCH_CACHE_MAX_AGE_SECONDS
}

/** The offline `max-stale` for a bucket, in seconds. */
internal fun httpOfflineMaxStaleSeconds(cacheClass: HttpCacheClass): Int = when (cacheClass) {
    HttpCacheClass.BROWSE -> BROWSE_OFFLINE_MAX_STALE_SECONDS
    HttpCacheClass.SEARCH -> SEARCH_OFFLINE_MAX_STALE_SECONDS
}

/** The online `max-stale` window for a bucket, in seconds. */
internal fun httpStaleWindowSeconds(cacheClass: HttpCacheClass): Int = when (cacheClass) {
    HttpCacheClass.BROWSE -> BROWSE_STALE_WINDOW_SECONDS
    HttpCacheClass.SEARCH -> SEARCH_STALE_WINDOW_SECONDS
}

/**
 * Network interceptor: force a `Cache-Control: public, max-age=…` onto a
 * cacheable GET response that carries no cache headers of its own, so the
 * disk cache stores it. A response the server already annotated is left
 * untouched — the server's word wins.
 */
class CacheControlNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cacheClass = httpCacheClassFor(request.url, request.method)
            ?: return chain.proceed(request)
        val response = chain.proceed(request)
        if (response.header("Cache-Control") != null || response.header("Expires") != null) {
            return response
        }
        return response.newBuilder()
            .header("Cache-Control", "public, max-age=${httpCacheMaxAgeSeconds(cacheClass)}")
            .build()
    }
}

/**
 * Application interceptor: the request-side half of the cache. When there
 * is no connectivity it rewrites a cacheable GET to `only-if-cached,
 * max-stale=…`, so OkHttp answers from the disk cache without touching
 * the network — and 504s when nothing is cached, exactly as an offline
 * request without a cache would fail. When there IS connectivity it adds
 * `max-stale=…` to a cacheable GET that carries no `Cache-Control` of its
 * own, so a repeat visit inside the stale window serves from disk with no
 * round trip — the client half of stale-while-revalidate (see the file
 * KDoc for why the response header would not work on OkHttp 4.12.0). A
 * request that already carries a `Cache-Control` is left alone, so an
 * explicit refresh still bypasses the cache.
 */
class CacheFreshnessInterceptor(
    private val isOffline: () -> Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cacheClass = httpCacheClassFor(request.url, request.method)
            ?: return chain.proceed(request)
        if (isOffline()) {
            val staleRequest = request.newBuilder()
                .header(
                    "Cache-Control",
                    "only-if-cached, max-stale=${httpOfflineMaxStaleSeconds(cacheClass)}",
                )
                .build()
            return chain.proceed(staleRequest)
        }
        if (request.header("Cache-Control") != null) return chain.proceed(request)
        val freshRequest = request.newBuilder()
            .header("Cache-Control", "max-stale=${httpStaleWindowSeconds(cacheClass)}")
            .build()
        return chain.proceed(freshRequest)
    }
}

/** The app's HTTP disk cache: 50 MB under `<cacheDir>/http`. */
fun httpCache(context: Context): Cache =
    Cache(File(context.cacheDir, HTTP_CACHE_DIR_NAME), HTTP_CACHE_MAX_BYTES)

@Module
@InstallIn(SingletonComponent::class)
object HttpCacheModule {

    @Provides
    @Singleton
    fun provideHttpCache(@ApplicationContext context: Context): Cache = httpCache(context)

    /**
     * The cache-freshness interceptor, wired to the platform's own connectivity
     * verdict. [ConnectivityProbe.current] fails OPEN to MOBILE (not NONE)
     * when the platform refuses the query, so a probe failure means a
     * normal network attempt, never a stale serve on a live connection.
     */
    @Provides
    @Singleton
    fun provideCacheFreshnessInterceptor(
        @ApplicationContext context: Context,
    ): CacheFreshnessInterceptor = CacheFreshnessInterceptor(
        isOffline = { ConnectivityProbe.current(context) == NetworkClass.NONE },
    )
}
