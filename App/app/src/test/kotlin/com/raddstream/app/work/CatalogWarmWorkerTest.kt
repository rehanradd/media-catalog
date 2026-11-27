package com.raddstream.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.raddstream.app.network.models.Post
import com.raddstream.app.repository.V2BrowsePage
import com.raddstream.app.viewmodel.HomeSection
import java.io.IOException
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Feel-instant S8: the warm, pinned without a server. The fake catalog
 * answers rail pages from a map and can kill one rail; the prefetch is a
 * recording lambda, so no image ever loads. The chain tests pin the
 * v2-first fetch order Home's loadSection runs — a change here must be a
 * deliberate change to both.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogWarmWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class FakeCatalog(
        var railPages: Map<String, List<Post>> = emptyMap(),
        var failRails: Set<String> = emptySet(),
    ) : CatalogPreloadCatalog {
        val railCalls = mutableListOf<String>()
        override suspend fun railFirstPage(section: HomeSection): List<Post> {
            railCalls += section.key
            if (section.key in failRails) throw IOException("boom")
            return railPages[section.key] ?: emptyList()
        }
        override suspend fun trendingPage(cursor: String?): V2BrowsePage? = null
        override suspend fun trendingPostsPage(page: Int, limit: Int): List<Post> = emptyList()
    }

    private fun posts(prefix: String, count: Int): List<Post> =
        (1..count).map {
            Post(id = it, title = "$prefix $it", posterUrl = "https://example.com/$prefix/$it.jpg")
        }

    @Test
    fun `warm fetches every warmable rail and prefetches four posters per rail`() = runBlocking {
        val catalog = FakeCatalog(
            railPages = mapOf(
                "recent" to posts("recent", 18),
                "movie" to posts("movie", 18),
                "series" to posts("series", 18),
                "season" to posts("season", 18),
                "most_sources" to posts("most", 18),
                "bollywood" to posts("bollywood", 18),
                "south_indian" to posts("south", 18),
            ),
        )
        val prefetched = mutableListOf<List<String>>()
        val warmed = warmCatalog(catalog) { urls -> prefetched += urls }
        assertEquals(7 * 18, warmed)
        assertEquals(7, catalog.railCalls.size)
        assertEquals(1, prefetched.size)
        // Video audit issue 5 (Nov 2026): first four posters per rail — a
        // rail shows ~3 across, so first-two under-covered the visible
        // row — 7 rails → 28 urls, under the 32 cap.
        assertEquals(28, prefetched.single().size)
        assertTrue(prefetched.single().all { it.startsWith("https://example.com/") })
    }

    @Test
    fun `one dead rail never stops the others`() = runBlocking {
        val catalog = FakeCatalog(
            railPages = mapOf("recent" to posts("recent", 5), "movie" to posts("movie", 5)),
            failRails = setOf("series"),
        )
        val warmed = warmCatalog(catalog) { }
        assertEquals(10, warmed)
        assertTrue("series" in catalog.railCalls)
    }

    @Test
    fun `empty rails contribute nothing and skip prefetch`() = runBlocking {
        val catalog = FakeCatalog()
        var prefetched = false
        val warmed = warmCatalog(catalog) { prefetched = true }
        assertEquals(0, warmed)
        assertFalse(prefetched)
    }

    @Test
    fun `v2 hit answers without fallback`() = runBlocking {
        val section = HomeSection(key = "movie", title = "Movies", type = "movie")
        val page = V2BrowsePage(posts = posts("v2", 3), nextCursor = null, hasMore = false)
        var fallbackCalled = false
        val result = fetchRailFirstPage(
            section = section,
            browseV2 = { page },
            trending = { fallbackCalled = true; emptyList() },
            list = { fallbackCalled = true; emptyList() },
            related = { fallbackCalled = true; emptyList() },
        )
        assertEquals(3, result.size)
        assertFalse(fallbackCalled)
    }

    @Test
    fun `v2 failure falls back to the v1 list, like Home`() = runBlocking {
        val section = HomeSection(key = "movie", title = "Movies", type = "movie")
        var listCalled = false
        val result = fetchRailFirstPage(
            section = section,
            browseV2 = { throw IOException("boom") },
            trending = { emptyList() },
            list = { listCalled = true; posts("v1", 4) },
            related = { emptyList() },
        )
        assertEquals(4, result.size)
        assertTrue(listCalled)
    }

    @Test
    fun `empty v2 page falls back to the v1 list`() = runBlocking {
        val section = HomeSection(key = "movie", title = "Movies", type = "movie")
        var listCalled = false
        val result = fetchRailFirstPage(
            section = section,
            browseV2 = { V2BrowsePage(posts = emptyList(), nextCursor = null, hasMore = false) },
            trending = { emptyList() },
            list = { listCalled = true; posts("v1", 4) },
            related = { emptyList() },
        )
        assertEquals(4, result.size)
        assertTrue(listCalled)
    }

    @Test
    fun `trending rail tries the trending route before the v1 list`() = runBlocking {
        val section = HomeSection(
            key = "most_sources",
            title = "Most copies",
            type = null,
            sort = "trending",
        )
        val calls = mutableListOf<String>()
        val result = fetchRailFirstPage(
            section = section,
            browseV2 = { null },
            trending = { calls += "trending"; posts("trend", 2) },
            list = { calls += "list"; emptyList() },
            related = { emptyList() },
        )
        assertEquals(2, result.size)
        assertEquals(listOf("trending"), calls)
    }

    @Test
    fun `trending route failure falls through to the v1 list`() = runBlocking {
        val section = HomeSection(
            key = "most_sources",
            title = "Most copies",
            type = null,
            sort = "trending",
        )
        val result = fetchRailFirstPage(
            section = section,
            browseV2 = { null },
            trending = { throw IOException("boom") },
            list = { posts("v1", 1) },
            related = { emptyList() },
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `related rail asks its own route`() = runBlocking {
        val section = HomeSection(key = "because", title = "Because", type = null, relatedWorkId = 7)
        var relatedCalled = false
        val result = fetchRailFirstPage(
            section = section,
            browseV2 = { error("must not be called") },
            trending = { error("must not be called") },
            list = { error("must not be called") },
            related = { relatedCalled = true; posts("rel", 2) },
        )
        assertEquals(2, result.size)
        assertTrue(relatedCalled)
    }

    @Test
    fun `cancellation is rethrown, never swallowed into a fallback`() {
        val section = HomeSection(
            key = "most_sources",
            title = "Most copies",
            type = null,
            sort = "trending",
        )
        var listCalled = false
        val thrown = try {
            runBlocking {
                fetchRailFirstPage(
                    section = section,
                    browseV2 = { null },
                    trending = { throw CancellationException("cancelled") },
                    list = { listCalled = true; emptyList() },
                    related = { emptyList() },
                )
            }
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }
        assertNotNull(thrown)
        assertFalse(listCalled)
    }

    // The Hilt factory pin: the same shape CacheSweepWorkerTest pins. If
    // the kapt processor drops the generated factory, this is the test
    // that goes red — not a runtime crash on a viewer's phone. The empty
    // warm never touches Coil: the fake catalog returns no rows, so
    // prefetchPosterArt is never invoked.

    private fun hiltStyleFactory(
        assistedFactory: CatalogWarmWorker_AssistedFactory,
    ): WorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == CatalogWarmWorker::class.java.name) {
                assistedFactory.create(appContext, workerParameters)
            } else {
                null
            }
    }

    private fun buildWorker(catalog: CatalogPreloadCatalog): CatalogWarmWorker {
        val assistedFactory: CatalogWarmWorker_AssistedFactory =
            CatalogWarmWorker_AssistedFactory_Impl.create(
                CatalogWarmWorker_Factory.create(dagger.internal.Provider { catalog }),
            ).get()
        val worker = TestListenableWorkerBuilder.from(context, CatalogWarmWorker::class.java)
            .setWorkerFactory(hiltStyleFactory(assistedFactory))
            .build()
        assertNotNull("Hilt factory must build CatalogWarmWorker", worker)
        return worker
    }

    @Test
    fun `hilt factory builds the worker and an empty warm succeeds`() {
        val worker = buildWorker(FakeCatalog())
        val result = runBlocking { worker.doWork() }
        assertTrue("empty warm must succeed, was $result", result is ListenableWorker.Result.Success)
    }
}
