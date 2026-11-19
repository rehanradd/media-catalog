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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Feel-instant S8: the index sync, pinned without a server. The fake
 * catalog answers v2 trending pages from a list (cursor `c1`, `c2`, …
 * chains them) and v1 pages from a map, and can kill v2 or withhold it
 * entirely. No page is ever written anywhere: the fake returns rows, the
 * sync counts them.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogIndexSyncWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class FakeCatalog(
        var v2Pages: List<List<Post>> = emptyList(),
        var v2Available: Boolean = true,
        var v1Pages: Map<Int, List<Post>> = emptyMap(),
        var failV2At: Int? = null,
    ) : CatalogPreloadCatalog {
        var trendingCalls = 0
        val v1Calls = mutableListOf<Int>()
        val cursorsSeen = mutableListOf<String?>()
        override suspend fun railFirstPage(section: HomeSection): List<Post> = emptyList()
        override suspend fun trendingPage(cursor: String?): V2BrowsePage? {
            trendingCalls++
            cursorsSeen += cursor
            if (!v2Available) return null
            if (failV2At == trendingCalls) throw IOException("boom")
            val index = cursor?.removePrefix("c")?.toIntOrNull() ?: 0
            val posts = v2Pages.getOrNull(index)
                ?: return V2BrowsePage(posts = emptyList(), nextCursor = null, hasMore = false)
            val hasMore = index + 1 < v2Pages.size
            return V2BrowsePage(
                posts = posts,
                nextCursor = if (hasMore) "c${index + 1}" else null,
                hasMore = hasMore,
            )
        }
        override suspend fun trendingPostsPage(page: Int, limit: Int): List<Post> {
            v1Calls += page
            return v1Pages[page] ?: emptyList()
        }
    }

    private fun posts(prefix: String, count: Int, startId: Int = 0): List<Post> =
        (1..count).map { Post(id = startId + it, title = "$prefix $it") }

    private fun posters(prefix: String, count: Int, startId: Int = 0): List<Post> =
        (1..count).map {
            Post(
                id = startId + it,
                title = "$prefix $it",
                posterUrl = "https://example.test/$prefix-$it.jpg",
            )
        }

    @Test
    fun `sync pages the v2 list and stops on the last page`() = runBlocking {
        val catalog = FakeCatalog(
            v2Pages = listOf(posts("a", 20), posts("b", 20), posts("c", 5)),
        )
        val banked = syncCatalogIndex(catalog)
        assertEquals(45, banked)
        assertEquals(listOf(null, "c1", "c2"), catalog.cursorsSeen)
    }

    @Test
    fun `sync stops at the per-run cap`() = runBlocking {
        // 300 pages of 20 = 6,000 titles; the run banks 5,000 and stops.
        val catalog = FakeCatalog(v2Pages = List(300) { posts("p$it", 20, startId = it * 20) })
        val banked = syncCatalogIndex(catalog)
        assertEquals(5_000, banked)
        assertEquals(250, catalog.trendingCalls)
    }

    @Test
    fun `sync stops when v2 dies mid-run, keeping what it banked`() = runBlocking {
        val catalog = FakeCatalog(
            v2Pages = listOf(posts("a", 20), posts("b", 20), posts("c", 20)),
            failV2At = 3,
        )
        val banked = syncCatalogIndex(catalog)
        assertEquals(40, banked)
    }

    @Test
    fun `v2 unavailable on the first page falls back to v1 paging`() = runBlocking {
        val catalog = FakeCatalog(
            v2Available = false,
            v1Pages = mapOf(1 to posts("a", 20), 2 to posts("b", 20), 3 to posts("c", 5)),
        )
        val banked = syncCatalogIndex(catalog)
        assertEquals(45, banked)
        assertEquals(listOf(1, 2, 3), catalog.v1Calls)
    }

    @Test
    fun `a withheld cursor stops the run after its page`() = runBlocking {
        // v2 says hasMore but gives no cursor: the caller must stop, not
        // re-fetch the same page forever.
        val catalog = object : CatalogPreloadCatalog {
            var calls = 0
            override suspend fun railFirstPage(section: HomeSection): List<Post> = emptyList()
            override suspend fun trendingPage(cursor: String?): V2BrowsePage? {
                calls++
                return V2BrowsePage(posts = posts("a", 20), nextCursor = null, hasMore = true)
            }
            override suspend fun trendingPostsPage(page: Int, limit: Int): List<Post> = emptyList()
        }
        val banked = syncCatalogIndex(catalog)
        assertEquals(20, banked)
        assertEquals(1, catalog.calls)
    }

    @Test
    fun `sync warms the posters of the banked titles in popularity order`() = runBlocking {
        val catalog = FakeCatalog(
            v2Pages = listOf(posters("a", 20), posters("b", 20), posters("c", 5)),
        )
        val warmed = mutableListOf<List<String>>()
        val banked = syncCatalogIndex(catalog, prefetchPosters = { warmed += it })
        assertEquals(45, banked)
        assertEquals(1, warmed.size)
        assertEquals(45, warmed.single().size)
        assertEquals("https://example.test/a-1.jpg", warmed.single().first())
        assertEquals("https://example.test/c-5.jpg", warmed.single().last())
    }

    @Test
    fun `sync warms at most one hundred posters`() = runBlocking {
        val catalog = FakeCatalog(v2Pages = List(10) { posters("p$it", 20, startId = it * 20) })
        val warmed = mutableListOf<List<String>>()
        val banked = syncCatalogIndex(catalog, prefetchPosters = { warmed += it })
        assertEquals(200, banked)
        assertEquals(100, warmed.single().size)
    }

    @Test
    fun `sync warms nothing when nothing is banked`() = runBlocking {
        var calls = 0
        val banked = syncCatalogIndex(FakeCatalog(), prefetchPosters = { calls++ })
        assertEquals(0, banked)
        assertEquals(0, calls)
    }

    @Test
    fun `sync warms what it banked when v2 dies mid-run`() = runBlocking {
        val catalog = FakeCatalog(
            v2Pages = listOf(posters("a", 20), posters("b", 20), posters("c", 20)),
            failV2At = 3,
        )
        val warmed = mutableListOf<List<String>>()
        val banked = syncCatalogIndex(catalog, prefetchPosters = { warmed += it })
        assertEquals(40, banked)
        assertEquals(40, warmed.single().size)
        assertEquals("https://example.test/b-20.jpg", warmed.single().last())
    }

    // The Hilt factory pin: the same shape CacheSweepWorkerTest pins. If
    // the kapt processor drops the generated factory, this is the test
    // that goes red — not a runtime crash on a viewer's phone. The empty
    // sync never touches the network: the fake catalog returns no rows.

    private fun hiltStyleFactory(
        assistedFactory: CatalogIndexSyncWorker_AssistedFactory,
    ): WorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == CatalogIndexSyncWorker::class.java.name) {
                assistedFactory.create(appContext, workerParameters)
            } else {
                null
            }
    }

    private fun buildWorker(catalog: CatalogPreloadCatalog): CatalogIndexSyncWorker {
        val assistedFactory: CatalogIndexSyncWorker_AssistedFactory =
            CatalogIndexSyncWorker_AssistedFactory_Impl.create(
                CatalogIndexSyncWorker_Factory(Provider { catalog }),
            ).get()
        val worker = TestListenableWorkerBuilder.from(context, CatalogIndexSyncWorker::class.java)
            .setWorkerFactory(hiltStyleFactory(assistedFactory))
            .build()
        assertNotNull("Hilt factory must build CatalogIndexSyncWorker", worker)
        return worker
    }

    @Test
    fun `hilt factory builds the worker and an empty sync succeeds`() {
        val worker = buildWorker(FakeCatalog())
        val result = runBlocking { worker.doWork() }
        assertTrue("empty sync must succeed, was $result", result is ListenableWorker.Result.Success)
    }
}
