package com.raddstream.app.repository

import androidx.paging.PagingSource
import androidx.paging.testing.TestPager
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostsResponse
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feel-instant S6: the network-only PagingSource behind browse/search.
 * Driven through [TestPager] (the Paging test fake) so the loads run the
 * way a real Pager would drive them — refresh first, then appends — with
 * scripted v2/v1 answers instead of a server.
 */
class CatalogPagingSourceTest {

    private fun post(id: Int) = Post(
        id = id,
        title = "Title $id",
        cleanTitle = "Title $id",
        year = 2024,
        contentType = "movie",
    )

    private fun posts(ids: IntRange) = ids.map { post(it) }

    private fun v2Page(
        ids: IntRange,
        nextCursor: String? = null,
        hasMore: Boolean = false,
        totalEstimate: Int? = null,
        totalCapped: Boolean = false,
    ) = V2BrowsePage(
        posts = posts(ids),
        nextCursor = nextCursor,
        hasMore = hasMore,
        totalEstimate = totalEstimate,
        totalCapped = totalCapped,
    )

    private fun v1Page(
        page: Int,
        ids: IntRange,
        total: Int,
        totalPages: Int,
        fuzzy: Boolean = false,
        suggestion: String? = null,
    ) = PostsResponse(
        page = page,
        limit = 50,
        total = total,
        totalPages = totalPages,
        results = posts(ids),
        fuzzy = fuzzy,
        suggestion = suggestion,
    )

    @Test
    fun `first load serves the v2 page when v2 answers`() = runTest {
        val metas = mutableListOf<CatalogPageMeta>()
        val source = CatalogPagingSource(
            loadV2 = { _, _ -> v2Page(1..50, nextCursor = "c2", hasMore = true, totalEstimate = 100) },
            loadV1 = { _, _ -> error("v1 must not be asked when v2 answers") },
            onPageMeta = { metas += it },
        )
        val pager = TestPager(CATALOG_PAGING_CONFIG, source)

        val result = pager.refresh() as PagingSource.LoadResult.Page

        assertEquals(posts(1..50), result.data)
        assertNull("the first page has nothing before it", result.prevKey)
        assertEquals(CatalogPageKey.V2("c2", 50), result.nextKey)
        assertEquals(0, result.itemsBefore)
        assertEquals("100 total, 50 loaded — 50 placeholders tail", 50, result.itemsAfter)
        assertEquals(
            listOf(
                CatalogPageMeta(
                    isFirstPage = true, totalEstimate = 100, totalCapped = false,
                    isFuzzy = false, suggestion = null, loadedCount = 50,
                ),
            ),
            metas,
        )
    }

    @Test
    fun `first load falls back to v1 when v2 is empty`() = runTest {
        val metas = mutableListOf<CatalogPageMeta>()
        var v1PageAsked = 0
        val source = CatalogPagingSource(
            // v2 answered, but with no rows: it matches titles only, so an
            // actor query falls through to v1, which matches cast.
            loadV2 = { _, _ -> v2Page(1..0) },
            loadV1 = { page, _ ->
                v1PageAsked = page
                v1Page(1, 1..50, total = 120, totalPages = 3, fuzzy = true, suggestion = "avatar")
            },
            onPageMeta = { metas += it },
        )
        val pager = TestPager(CATALOG_PAGING_CONFIG, source)

        val result = pager.refresh() as PagingSource.LoadResult.Page

        assertEquals(1, v1PageAsked)
        assertEquals(posts(1..50), result.data)
        assertEquals(CatalogPageKey.V1(2, 50), result.nextKey)
        assertEquals("120 total, 50 loaded", 70, result.itemsAfter)
        assertEquals(
            listOf(
                CatalogPageMeta(
                    isFirstPage = true, totalEstimate = 120, totalCapped = false,
                    isFuzzy = true, suggestion = "avatar", loadedCount = 50,
                ),
            ),
            metas,
        )
    }

    @Test
    fun `first load falls back to v1 when v2 fails`() = runTest {
        val source = CatalogPagingSource(
            loadV2 = { _, _ -> null },
            loadV1 = { page, _ -> v1Page(page, 1..50, total = 50, totalPages = 1) },
        )
        val pager = TestPager(CATALOG_PAGING_CONFIG, source)

        val result = pager.refresh() as PagingSource.LoadResult.Page

        assertEquals(posts(1..50), result.data)
        assertNull("a one-page v1 list has no next page", result.nextKey)
        assertEquals(0, result.itemsAfter)
    }

    @Test
    fun `first load goes straight to v1 when v2 does not apply`() = runTest {
        var v2Asked = false
        val source = CatalogPagingSource(
            loadV2 = null,
            loadV1 = { page, _ ->
                assertEquals(1, page)
                v1Page(page, 1..50, total = 50, totalPages = 1)
            },
        )
        val pager = TestPager(CATALOG_PAGING_CONFIG, source)

        pager.refresh()

        assertTrue("v2 must never be asked when the caller passes no v2 loader", !v2Asked)
    }

    @Test
    fun `append continues on v2 with the cursor`() = runTest {
        val metas = mutableListOf<CatalogPageMeta>()
        val v2Answers = mutableListOf(
            v2Page(1..50, nextCursor = "c2", hasMore = true, totalEstimate = 100),
            v2Page(51..70, nextCursor = "c3", hasMore = true, totalEstimate = 100),
            v2Page(71..90, nextCursor = null, hasMore = false, totalEstimate = 100),
        )
        val source = CatalogPagingSource(
            loadV2 = { _, _ -> v2Answers.removeAt(0) },
            loadV1 = { _, _ -> error("v1 must not be asked on a v2 list") },
            onPageMeta = { metas += it },
        )
        val pager = TestPager(CATALOG_PAGING_CONFIG, source)

        pager.refresh()
        val second = pager.append() as PagingSource.LoadResult.Page
        assertEquals(posts(51..70), second.data)
        assertEquals("the second page starts after the first 50", 50, second.itemsBefore)
        assertEquals("100 total, 70 loaded", 30, second.itemsAfter)
        assertEquals(CatalogPageKey.V2("c3", 70), second.nextKey)
        assertEquals("the page before the second is the first", CatalogPageKey.First, second.prevKey)
        assertEquals("the append reports the growing loaded count", 70, metas.last().loadedCount)
        assertTrue("only the first page is a first page", metas.drop(1).all { !it.isFirstPage })

        val third = pager.append() as PagingSource.LoadResult.Page
        assertEquals(posts(71..90), third.data)
        assertNull("the last v2 page has no cursor to continue from", third.nextKey)
        assertNull("nothing left to append", pager.append())
    }

    @Test
    fun `append continues on v1 by page number`() = runTest {
        val v1Answers = mutableListOf(
            v1Page(1, 1..50, total = 120, totalPages = 3),
            v1Page(2, 51..70, total = 120, totalPages = 3),
            v1Page(3, 71..90, total = 120, totalPages = 3),
        )
        val source = CatalogPagingSource(
            loadV2 = { _, _ -> null },
            loadV1 = { _, _ -> v1Answers.removeAt(0) },
        )
        val pager = TestPager(CATALOG_PAGING_CONFIG, source)

        pager.refresh()
        val second = pager.append() as PagingSource.LoadResult.Page
        assertEquals(posts(51..70), second.data)
        assertEquals(50, second.itemsBefore)
        assertEquals("120 total, 70 loaded", 50, second.itemsAfter)
        assertEquals(CatalogPageKey.V1(3, 70), second.nextKey)
        assertEquals(CatalogPageKey.First, second.prevKey)

        val third = pager.append() as PagingSource.LoadResult.Page
        assertEquals(posts(71..90), third.data)
        assertNull("page 3 of 3 has no next page", third.nextKey)
        assertNull(pager.append())
    }

    @Test
    fun `v2 append ends when the server withholds the cursor`() = runTest {
        val source = CatalogPagingSource(
            // hasMore true but no cursor: the server withheld it and the
            // caller must stop (V2BrowsePage's contract) — the list ends
            // here rather than looping on the same page.
            loadV2 = { _, _ -> v2Page(1..50, nextCursor = null, hasMore = true, totalEstimate = 100) },
            loadV1 = { _, _ -> error("v1 must not be asked on a v2 list") },
        )
        val pager = TestPager(CATALOG_PAGING_CONFIG, source)

        val result = pager.refresh() as PagingSource.LoadResult.Page

        assertNull(result.nextKey)
        assertNull(pager.append())
    }

    @Test
    fun `v1 append ends on a short page`() = runTest {
        val v1Answers = mutableListOf(
            v1Page(1, 1..50, total = 60, totalPages = 2),
            v1Page(2, 51..60, total = 60, totalPages = 2),
        )
        val source = CatalogPagingSource(
            loadV2 = { _, _ -> null },
            loadV1 = { _, _ -> v1Answers.removeAt(0) },
        )
        val pager = TestPager(CATALOG_PAGING_CONFIG, source)

        pager.refresh()
        val second = pager.append() as PagingSource.LoadResult.Page

        assertEquals(posts(51..60), second.data)
        assertNull("10 rows on a 20-row page is the last page", second.nextKey)
        assertEquals("60 total, 60 loaded — no tail", 0, second.itemsAfter)
        assertNull(pager.append())
    }

    @Test
    fun `a repeated id across v1 pages shows once`() = runTest {
        // A scrape landing between page fetches shifts the v1 window and
        // hands the same row back twice; the list must hold it once.
        val v1Answers = mutableListOf(
            v1Page(1, 1..50, total = 120, totalPages = 3),
            v1Page(2, 50..69, total = 120, totalPages = 3),
        )
        val source = CatalogPagingSource(
            loadV2 = { _, _ -> null },
            loadV1 = { _, _ -> v1Answers.removeAt(0) },
        )
        val pager = TestPager(CATALOG_PAGING_CONFIG, source)

        pager.refresh()
        val second = pager.append() as PagingSource.LoadResult.Page

        assertEquals("id 50 was already served on page 1", posts(51..69), second.data)
        assertEquals(69, second.itemsBefore + second.data.size)
    }

    @Test
    fun `first load error reports through the error callback`() = runTest {
        val failures = mutableListOf<Throwable>()
        val failure = IOException("dead socket")
        val source = CatalogPagingSource(
            loadV2 = { _, _ -> null },
            loadV1 = { _, _ -> throw failure },
            onFirstPageError = { failures += it },
        )
        val pager = TestPager(CATALOG_PAGING_CONFIG, source)

        val result = pager.refresh()

        assertTrue(result is PagingSource.LoadResult.Error)
        assertEquals(failure, (result as PagingSource.LoadResult.Error).throwable)
        assertEquals(listOf(failure), failures)
    }

    @Test
    fun `a later v2 page failing is an error, not a source switch`() = runTest {
        val v2Answers = mutableListOf<V2BrowsePage?>(
            v2Page(1..50, nextCursor = "c2", hasMore = true, totalEstimate = 100),
            null,
        )
        val source = CatalogPagingSource(
            loadV2 = { _, _ -> v2Answers.removeAt(0) },
            loadV1 = { _, _ -> error("v1 must not be asked on a v2 list") },
        )
        val pager = TestPager(CATALOG_PAGING_CONFIG, source)

        pager.refresh()
        val result = pager.append()

        assertTrue(result is PagingSource.LoadResult.Error)
    }

    @Test
    fun `a v2 total the server did not give leaves the tail undefined`() = runTest {
        val source = CatalogPagingSource(
            loadV2 = { _, _ -> v2Page(1..50, nextCursor = "c2", hasMore = true, totalEstimate = null) },
            loadV1 = { _, _ -> error("v1 must not be asked on a v2 list") },
        )
        val pager = TestPager(CATALOG_PAGING_CONFIG, source)

        val result = pager.refresh() as PagingSource.LoadResult.Page

        assertEquals(
            "no total, no placeholder tail — the list just ends when the cursor does",
            PagingSource.LoadResult.Page.COUNT_UNDEFINED,
            result.itemsAfter,
        )
    }

    @Test
    fun `getRefreshKey returns the anchor page's own key`() = runTest {
        val v2Answers = mutableListOf(
            v2Page(1..50, nextCursor = "c2", hasMore = true, totalEstimate = 200),
            v2Page(51..70, nextCursor = "c3", hasMore = true, totalEstimate = 200),
            v2Page(71..90, nextCursor = null, hasMore = false, totalEstimate = 200),
        )
        val source = CatalogPagingSource(
            loadV2 = { _, _ -> v2Answers.removeAt(0) },
            loadV1 = { _, _ -> error("v1 must not be asked on a v2 list") },
        )
        val pager = TestPager(CATALOG_PAGING_CONFIG, source)

        pager.refresh()
        pager.append()
        // The anchor sits in the second page (rows 50..69).
        val state = pager.getPagingState(anchorPosition = 55)
        val refreshKey = source.getRefreshKey(state)

        assertEquals(
            "a refresh resumes from the page holding the anchor, not from the top",
            CatalogPageKey.V2("c2", 50),
            refreshKey,
        )
    }

    @Test
    fun `getRefreshKey on the first page restarts from the top`() = runTest {
        val source = CatalogPagingSource(
            loadV2 = { _, _ -> v2Page(1..50, nextCursor = "c2", hasMore = true, totalEstimate = 200) },
            loadV1 = { _, _ -> error("v1 must not be asked on a v2 list") },
        )
        val pager = TestPager(CATALOG_PAGING_CONFIG, source)

        pager.refresh()
        val state = pager.getPagingState(anchorPosition = 10)

        assertEquals(
            "the anchor is in the first page, so the refresh restarts from it",
            CatalogPageKey.First,
            source.getRefreshKey(state),
        )
    }
}
