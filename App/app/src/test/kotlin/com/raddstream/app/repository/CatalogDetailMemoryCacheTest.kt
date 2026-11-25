package com.raddstream.app.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.network.ApiProvider
import com.raddstream.app.network.RaddStreamApi
import com.raddstream.app.network.buildRaddGson
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.network.models.PostDetailResponse
import com.raddstream.app.network.models.PostsResponse
import com.raddstream.app.network.models.WireWorkDetailResponse
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * App-speed (Nov 2026): the repository's parsed-detail memory cache,
 * driven through the REAL `CatalogRepository` with a fake transport —
 * the same proxy pattern as [V2DetailRepositoryTest], plus a call
 * counter on each detail route so a repeat open can prove it cost zero
 * API calls. The fake answers `currentBaseUrl()` with a fixed URL, the
 * way the real provider does once its in-memory URL is populated after
 * the first call — the cache key names the server, so the key must be
 * stable across the calls in one test.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogDetailMemoryCacheTest {

    private fun loadResponse(name: String): WireWorkDetailResponse {
        val json = javaClass.classLoader!!.getResourceAsStream("v2detail/$name")!!
            .bufferedReader().readText()
        return buildRaddGson().fromJson(json, WireWorkDetailResponse::class.java)
    }

    private class FakeApiProvider(
        settingsRepository: ServerSettingsRepository,
        private val workDetail: WireWorkDetailResponse? = null,
        private val postDetail: PostDetail? = null,
        private val postDetailSuccess: Boolean = true,
    ) : ApiProvider(settingsRepository) {
        var workDetailCalls = 0
        var postDetailCalls = 0

        override suspend fun currentBaseUrl(): String = "https://raddstream.duckdns.org"

        override suspend fun current(): RaddStreamApi {
            @Suppress("UNCHECKED_CAST")
            return Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(RaddStreamApi::class.java),
            ) { _, method, args ->
                val continuation = args!!.last() as Continuation<Any?>
                when (method.name) {
                    "getWorkDetail" -> {
                        workDetailCalls++
                        continuation.resume(workDetail!!)
                    }
                    "getPostDetail" -> {
                        postDetailCalls++
                        continuation.resume(
                            PostDetailResponse(success = postDetailSuccess, data = postDetail),
                        )
                    }
                    "getPosts" -> continuation.resume(PostsResponse(results = emptyList()))
                    else -> continuation.resumeWithException(
                        UnsupportedOperationException("unused in this test: ${method.name}"),
                    )
                }
                COROUTINE_SUSPENDED
            } as RaddStreamApi
        }
    }

    private fun repositoryFor(
        workDetail: WireWorkDetailResponse? = null,
        postDetail: PostDetail? = null,
        postDetailSuccess: Boolean = true,
    ): Pair<CatalogRepository, FakeApiProvider> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = ServerSettingsRepository(context)
        runBlocking { settings.saveServerUrl("https://raddstream.duckdns.org") }
        val provider = FakeApiProvider(settings, workDetail, postDetail, postDetailSuccess)
        return CatalogRepository(provider, settings) to provider
    }

    @Test
    fun `a repeat v2 detail open costs zero API calls`() = runBlocking {
        val (repository, provider) = repositoryFor(
            workDetail = loadResponse("work-7681-scarpetta.json"),
        )
        val first = repository.getWorkDetailV2(7681)
        assertNotNull(first)
        assertEquals("Scarpetta", first!!.title)
        assertEquals(82, first.releases.size)

        val second = repository.getWorkDetailV2(7681)
        assertNotNull(second)
        assertEquals("Scarpetta", second!!.title)
        assertEquals(1, provider.workDetailCalls)
    }

    @Test
    fun `a repeat v1 detail open costs zero API calls`() = runBlocking {
        val detail = PostDetail(id = 42, title = "Jawan", cleanTitle = "Jawan", year = 2023)
        val (repository, provider) = repositoryFor(postDetail = detail)
        assertEquals("Jawan", repository.getPostDetail(42)!!.title)
        assertEquals("Jawan", repository.getPostDetail(42)!!.title)
        assertEquals(1, provider.postDetailCalls)
    }

    @Test
    fun `a warm call bypasses the read but populates the cache`() = runBlocking {
        // The warm call's job is the server-side pre-resolve of the auto
        // tier — serving it from memory would skip that work. It still
        // stores its answer, so the player-side re-read right after the
        // open costs nothing.
        val detail = PostDetail(id = 42, title = "Jawan", cleanTitle = "Jawan", year = 2023)
        val (repository, provider) = repositoryFor(postDetail = detail)
        assertEquals("Jawan", repository.getPostDetail(42, warm = true)!!.title)
        assertEquals(1, provider.postDetailCalls)

        assertEquals("Jawan", repository.getPostDetail(42)!!.title)
        assertEquals(1, provider.postDetailCalls)
    }

    @Test
    fun `a failed detail is not cached`() = runBlocking {
        // A failure must never poison a later open: the first call answers
        // no detail and the second re-asks the network instead of
        // re-answering the failure.
        val (repository, provider) = repositoryFor(postDetail = null, postDetailSuccess = false)
        assertNull(repository.getPostDetail(42))
        assertNull(repository.getPostDetail(42))
        assertEquals(2, provider.postDetailCalls)
    }

    @Test
    fun `the same title in another audio is a separate cache entry`() = runBlocking {
        val detail = PostDetail(id = 42, title = "Jawan", cleanTitle = "Jawan", year = 2023)
        val (repository, provider) = repositoryFor(postDetail = detail)
        assertEquals("Jawan", repository.getPostDetail(42, audio = "Hindi")!!.title)
        assertEquals("Jawan", repository.getPostDetail(42, audio = "English")!!.title)
        assertEquals(2, provider.postDetailCalls)

        // And the Hindi answer is still cached — a repeat costs nothing.
        assertEquals("Jawan", repository.getPostDetail(42, audio = "Hindi")!!.title)
        assertEquals(2, provider.postDetailCalls)
    }

    @Test
    fun `a cached read is measured in microseconds not a network round trip`() = runBlocking {
        val (repository, _) = repositoryFor(
            workDetail = loadResponse("work-7681-scarpetta.json"),
        )
        assertNotNull(repository.getWorkDetailV2(7681))

        val startNs = System.nanoTime()
        val cached = repository.getWorkDetailV2(7681)
        val cachedNs = System.nanoTime() - startNs
        assertNotNull(cached)
        // The cache answer is a synchronized map lookup: microseconds on
        // any runner. The bound is deliberately generous (50 ms) so a slow
        // CI runner cannot flake it — the point is the order of magnitude,
        // not the exact number. The measured value is printed for the
        // record.
        println("cached v2 detail read: ${cachedNs}ns (${cachedNs / 1_000}us)")
        assertTrue("cached read took ${cachedNs}ns, expected under 50ms", cachedNs < 50_000_000L)
    }
}
