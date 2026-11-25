package com.raddstream.app.repository

import com.raddstream.app.network.models.PostDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * App-speed (Nov 2026): the parsed-detail memory cache is a plain LRU
 * with a TTL and a key that names the server and the audio — no Android
 * framework, no repository, so this test drives it directly with a fake
 * clock.
 */
class DetailMemoryCacheTest {

    private fun detail(id: Int) = PostDetail(id = id, title = "Title $id", cleanTitle = "Title $id")

    @Test
    fun `a stored detail is returned for its key`() {
        val cache = DetailMemoryCache()
        val key = DetailCacheKey.V1(42, "https://raddstream.duckdns.org", null)
        val stored = detail(42)
        cache.put(key, stored)

        assertSame(stored, cache.get(key))
        assertEquals("Title 42", cache.get(key)!!.title)
    }

    @Test
    fun `an entry older than the TTL is dropped on read`() {
        var now = 0L
        val cache = DetailMemoryCache(nowMs = { now })
        val key = DetailCacheKey.V1(42, "https://raddstream.duckdns.org", null)
        cache.put(key, detail(42))

        now = DETAIL_MEMORY_CACHE_TTL_MS + 1
        assertNull(cache.get(key))
        assertEquals(0, cache.size())
    }

    @Test
    fun `an entry inside the TTL is still served`() {
        var now = 0L
        val cache = DetailMemoryCache(nowMs = { now })
        val key = DetailCacheKey.V2(7681, "https://raddstream.duckdns.org", "Hindi")
        cache.put(key, detail(7681))

        now = DETAIL_MEMORY_CACHE_TTL_MS - 1
        assertEquals("Title 7681", cache.get(key)!!.title)
    }

    @Test
    fun `the least-recently-used entry is evicted past the cap`() {
        val cache = DetailMemoryCache(maxEntries = 2)
        val first = DetailCacheKey.V1(1, "s", null)
        val second = DetailCacheKey.V1(2, "s", null)
        val third = DetailCacheKey.V1(3, "s", null)
        cache.put(first, detail(1))
        cache.put(second, detail(2))
        // Touch the first entry so the second is the least-recently-used.
        cache.get(first)
        cache.put(third, detail(3))

        assertEquals(2, cache.size())
        assertEquals("Title 1", cache.get(first)!!.title)
        assertNull(cache.get(second))
        assertEquals("Title 3", cache.get(third)!!.title)
    }

    @Test
    fun `the same id on another server or audio is a different entry`() {
        val cache = DetailMemoryCache()
        val key = DetailCacheKey.V1(42, "https://raddstream.duckdns.org", null)
        cache.put(key, detail(42))

        assertNull(cache.get(DetailCacheKey.V1(42, "https://other.example", null)))
        assertNull(cache.get(DetailCacheKey.V1(42, "https://raddstream.duckdns.org", "Hindi")))
        assertNull(cache.get(DetailCacheKey.V2(42, "https://raddstream.duckdns.org", null)))
        assertEquals("Title 42", cache.get(key)!!.title)
    }

    @Test
    fun `clear empties the cache`() {
        val cache = DetailMemoryCache()
        cache.put(DetailCacheKey.V1(42, "s", null), detail(42))
        cache.clear()

        assertEquals(0, cache.size())
        assertNull(cache.get(DetailCacheKey.V1(42, "s", null)))
    }
}
