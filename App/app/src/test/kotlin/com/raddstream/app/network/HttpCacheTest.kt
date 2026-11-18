package com.raddstream.app.network

import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Feel-instant S1 (Nov 2026): the OkHttp disk cache's classification and
 * interceptors. The table is the contract: only the catalog read paths get
 * a forced freshness, search goes stale faster than a rail, and POST play,
 * auth and panel calls never touch the cache. The interceptor tests run
 * through a fake [Interceptor.Chain] — no MockWebServer in the test deps,
 * and the header logic is what needs pinning, not the socket.
 */
class HttpCacheTest {

    // ── classification ───────────────────────────────────────────────

    @Test
    fun `browse list gets 300 seconds`() {
        assertEquals(HttpCacheClass.BROWSE, httpCacheClassFor("https://api.example.test/api/v1/posts".toHttpUrl(), "GET"))
        assertEquals(HttpCacheClass.BROWSE, httpCacheClassFor("https://api.example.test/api/v1/posts?type=movie&sort=newest".toHttpUrl(), "GET"))
    }

    @Test
    fun `browse rails get 300 seconds`() {
        listOf(
            "/api/v1/trending",
            "/api/v1/related?work_id=12",
            "/api/v1/facets",
            "/api/v2/browse?industry=bollywood",
            "/api/v1/live-tv/channels",
        ).forEach { path ->
            assertEquals(
                HttpCacheClass.BROWSE,
                httpCacheClassFor("https://api.example.test$path".toHttpUrl(), "GET"),
            )
        }
    }

    @Test
    fun `detail bodies are browse reads`() {
        assertEquals(HttpCacheClass.BROWSE, httpCacheClassFor("https://api.example.test/api/v1/posts/70430".toHttpUrl(), "GET"))
        assertEquals(HttpCacheClass.BROWSE, httpCacheClassFor("https://api.example.test/api/v2/works/8521".toHttpUrl(), "GET"))
    }

    @Test
    fun `free-text search gets 60 seconds`() {
        assertEquals(HttpCacheClass.SEARCH, httpCacheClassFor("https://api.example.test/api/v1/posts?q=love".toHttpUrl(), "GET"))
        assertEquals(HttpCacheClass.SEARCH, httpCacheClassFor("https://api.example.test/api/v2/search?q=love".toHttpUrl(), "GET"))
        assertEquals(HttpCacheClass.SEARCH, httpCacheClassFor("https://api.example.test/api/v2/suggest?q=lo".toHttpUrl(), "GET"))
    }

    @Test
    fun `post play auth panel stream and stats never cache`() {
        listOf(
            "POST" to "/api/v2/play",
            "POST" to "/api/v1/contact",
            "POST" to "/api/v2/account",
            "POST" to "/api/v2/account/favorites",
            "GET" to "/api/v1/app/remote-config",
            "GET" to "/api/v2/account",
            "GET" to "/api/v2/account/entitlement",
            "GET" to "/api/v1/stream?id=abc",
            "GET" to "/api/v2/resolve/abc",
            "GET" to "/api/v1/stats",
            "POST" to "/api/v1/posts",
        ).forEach { (method, path) ->
            assertNull(
                "$method $path must not be cached",
                httpCacheClassFor("https://api.example.test$path".toHttpUrl(), method),
            )
        }
    }

    @Test
    fun `freshness numbers`() {
        assertEquals(300, httpCacheMaxAgeSeconds(HttpCacheClass.BROWSE))
        assertEquals(60, httpCacheMaxAgeSeconds(HttpCacheClass.SEARCH))
        assertEquals(86_400, httpOfflineMaxStaleSeconds(HttpCacheClass.BROWSE))
        assertEquals(3_600, httpOfflineMaxStaleSeconds(HttpCacheClass.SEARCH))
    }

    // ── network interceptor: force max-age when the server sent none ──

    @Test
    fun `network interceptor forces max-age on an unannotated browse response`() {
        val chain = FakeChain("https://api.example.test/api/v1/posts")
        val response = CacheControlNetworkInterceptor().intercept(chain)
        assertEquals("public, max-age=300", response.header("Cache-Control"))
    }

    @Test
    fun `network interceptor forces 60 seconds on a search response`() {
        val chain = FakeChain("https://api.example.test/api/v2/search?q=love")
        val response = CacheControlNetworkInterceptor().intercept(chain)
        assertEquals("public, max-age=60", response.header("Cache-Control"))
    }

    @Test
    fun `network interceptor leaves the server cache headers alone`() {
        val chain = FakeChain(
            "https://api.example.test/api/v1/posts",
            responseHeaders = mapOf("Cache-Control" to "no-store"),
        )
        val response = CacheControlNetworkInterceptor().intercept(chain)
        assertEquals("no-store", response.header("Cache-Control"))
    }

    @Test
    fun `network interceptor leaves posts and panel calls alone`() {
        val play = FakeChain("https://api.example.test/api/v2/play", method = "POST")
        assertNull(CacheControlNetworkInterceptor().intercept(play).header("Cache-Control"))
        val config = FakeChain("https://api.example.test/api/v1/app/remote-config")
        assertNull(CacheControlNetworkInterceptor().intercept(config).header("Cache-Control"))
    }

    // ── offline interceptor: serve stale instead of failing ──────────

    @Test
    fun `offline interceptor rewrites a browse get to only-if-cached`() {
        val chain = FakeChain("https://api.example.test/api/v1/posts")
        OfflineCacheInterceptor(isOffline = { true }).intercept(chain)
        assertEquals(
            "only-if-cached, max-stale=86400",
            chain.capturedRequest?.header("Cache-Control"),
        )
    }

    @Test
    fun `offline interceptor rewrites a search get with the shorter stale window`() {
        val chain = FakeChain("https://api.example.test/api/v2/search?q=love")
        OfflineCacheInterceptor(isOffline = { true }).intercept(chain)
        assertEquals(
            "only-if-cached, max-stale=3600",
            chain.capturedRequest?.header("Cache-Control"),
        )
    }

    @Test
    fun `online requests pass through untouched`() {
        val chain = FakeChain("https://api.example.test/api/v1/posts")
        OfflineCacheInterceptor(isOffline = { false }).intercept(chain)
        assertNull(chain.capturedRequest?.header("Cache-Control"))
    }

    @Test
    fun `offline posts and panel calls pass through untouched`() {
        val play = FakeChain("https://api.example.test/api/v2/play", method = "POST")
        OfflineCacheInterceptor(isOffline = { true }).intercept(play)
        assertNull(play.capturedRequest?.header("Cache-Control"))
        val config = FakeChain("https://api.example.test/api/v1/app/remote-config")
        OfflineCacheInterceptor(isOffline = { true }).intercept(config)
        assertNull(config.capturedRequest?.header("Cache-Control"))
    }

    /**
     * A minimal [Interceptor.Chain]: captures the request [proceed] receives
     * and answers a canned 200 with the given headers. The timeout and
     * connection methods are never exercised by these interceptors.
     */
    private class FakeChain(
        url: String,
        private val method: String = "GET",
        private val responseHeaders: Map<String, String> = emptyMap(),
    ) : Interceptor.Chain {

        private val request = Request.Builder().url(url).apply {
            if (method == "POST") post("".toRequestBody()) else get()
        }.build()
        var capturedRequest: Request? = null
            private set

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            capturedRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .apply { responseHeaders.forEach { (name, value) -> header(name, value) } }
                .build()
        }

        override fun connection(): Connection? = null

        override fun call(): Call = throw UnsupportedOperationException()

        override fun connectTimeoutMillis(): Int = 0

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun readTimeoutMillis(): Int = 0

        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun writeTimeoutMillis(): Int = 0

        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
