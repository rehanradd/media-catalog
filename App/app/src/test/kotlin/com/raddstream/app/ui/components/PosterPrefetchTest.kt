package com.raddstream.app.ui.components

import com.raddstream.app.network.models.LiveTvChannel
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.viewmodel.HomeSection
import com.raddstream.app.viewmodel.TypeGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Feel-instant S2 (Nov 2026): the poster URLs each prefetch call warms.
 * Pure selection, so no Coil and no clock is needed — the metered gate and
 * the sizing live in [prefetchPosterArt] itself.
 */
class PosterPrefetchTest {

    private fun post(id: Int, posterUrl: String? = null) = Post(
        id = id,
        title = "Title $id",
        cleanTitle = "Title $id",
        posterUrl = posterUrl,
    )

    private fun section(key: String, vararg posts: Post, error: String? = null) = HomeSection(
        key = key,
        title = key,
        type = null,
        items = posts.toList(),
        error = error,
    )

    // ── Home rails ───────────────────────────────────────────────────

    @Test
    fun `home takes the first four posters per visible rail in order`() {
        assertEquals(
            listOf(
                "https://example.test/a.jpg",
                "https://example.test/b.jpg",
                "https://example.test/d.jpg",
                "https://example.test/c.jpg",
            ),
            homeRailPrefetchUrls(
                listOf(
                    section("recent", post(1, "https://example.test/a.jpg"), post(2, "https://example.test/b.jpg"), post(3, "https://example.test/d.jpg")),
                    section("movie", post(4, "https://example.test/c.jpg")),
                ),
            ),
        )
    }

    @Test
    fun `home caps at thirty-two`() {
        val sections = (1..9).map { n ->
            section(
                "rail$n",
                post(n * 4 - 3, "https://example.test/$n-a.jpg"),
                post(n * 4 - 2, "https://example.test/$n-b.jpg"),
                post(n * 4 - 1, "https://example.test/$n-c.jpg"),
                post(n * 4, "https://example.test/$n-d.jpg"),
            )
        }
        assertEquals(32, homeRailPrefetchUrls(sections).size)
    }

    @Test
    fun `home skips failed rails and blank or duplicate posters`() {
        assertEquals(
            listOf("https://example.test/a.jpg"),
            homeRailPrefetchUrls(
                listOf(
                    section("recent", post(1, "https://example.test/a.jpg"), post(2, "   "), post(3)),
                    section("broken", post(4, "https://example.test/b.jpg"), error = "boom"),
                    section("movie", post(5, "https://example.test/a.jpg")),
                ),
            ),
        )
    }

    // ── Search ───────────────────────────────────────────────────────

    @Test
    fun `search flattens groups in order and caps at twenty`() {
        val groups = (1..3).map { g ->
            TypeGroup(
                label = "Group $g",
                typeKey = null,
                items = (1..8).map { n -> post(g * 10 + n, "https://example.test/g$g-$n.jpg") },
            )
        }
        val urls = searchNextPrefetchUrls(groups, emptySet())
        assertEquals(20, urls.size)
        assertEquals("https://example.test/g1-1.jpg", urls.first())
        assertEquals("https://example.test/g3-4.jpg", urls.last())
    }

    @Test
    fun `search drops blanks and duplicates`() {
        assertEquals(
            listOf("https://example.test/a.jpg"),
            searchNextPrefetchUrls(
                listOf(
                    TypeGroup("Movies", "movie", listOf(post(1, "https://example.test/a.jpg"), post(2, "  "))),
                    TypeGroup("Series", "series", listOf(post(3, "https://example.test/a.jpg"))),
                ),
                emptySet(),
            ),
        )
    }

    @Test
    fun `search skips posters already warmed`() {
        val groups = listOf(
            TypeGroup(
                "Movies",
                "movie",
                (1..5).map { n -> post(n, "https://example.test/p$n.jpg") },
            ),
        )
        val urls = searchNextPrefetchUrls(
            groups,
            setOf("https://example.test/p1.jpg", "https://example.test/p2.jpg"),
        )
        assertEquals(
            listOf(
                "https://example.test/p3.jpg",
                "https://example.test/p4.jpg",
                "https://example.test/p5.jpg",
            ),
            urls,
        )
    }

    @Test
    fun `search warms one page per landing`() {
        val groups = (1..3).map { g ->
            TypeGroup(
                label = "Group $g",
                typeKey = null,
                items = (1..15).map { n -> post(g * 100 + n, "https://example.test/g$g-$n.jpg") },
            )
        }
        val first = searchNextPrefetchUrls(groups, emptySet())
        assertEquals(20, first.size)
        assertEquals("https://example.test/g1-1.jpg", first.first())
        assertEquals("https://example.test/g2-5.jpg", first.last())
        val second = searchNextPrefetchUrls(groups, first.toSet())
        assertEquals(20, second.size)
        assertEquals("https://example.test/g2-6.jpg", second.first())
        assertEquals("https://example.test/g3-10.jpg", second.last())
    }

    // ── Scroll-ahead (paged grids) ───────────────────────────────────

    private fun gridPosts(count: Int): List<Post?> =
        (1..count).map { post(it, "https://example.test/p$it.jpg") }

    @Test
    fun `scroll ahead warms the visible screen plus one screen ahead`() {
        val urls = scrollAheadPrefetchUrls(gridPosts(50), firstVisibleIndex = 0, lastVisibleIndex = 11)
        assertEquals(24, urls.size)
        assertEquals("https://example.test/p1.jpg", urls.first())
        assertEquals("https://example.test/p24.jpg", urls.last())
    }

    @Test
    fun `scroll ahead slides with the viewport`() {
        val urls = scrollAheadPrefetchUrls(gridPosts(50), firstVisibleIndex = 12, lastVisibleIndex = 23)
        assertEquals("https://example.test/p13.jpg", urls.first())
        assertEquals("https://example.test/p36.jpg", urls.last())
    }

    @Test
    fun `scroll ahead skips placeholders and blanks`() {
        val items: List<Post?> = listOf(
            post(1, "https://example.test/a.jpg"),
            null,
            post(2, "  "),
            post(3, "https://example.test/a.jpg"),
            post(4, "https://example.test/b.jpg"),
        )
        assertEquals(
            listOf("https://example.test/a.jpg", "https://example.test/b.jpg"),
            scrollAheadPrefetchUrls(items, firstVisibleIndex = 0, lastVisibleIndex = 2),
        )
    }

    @Test
    fun `scroll ahead clamps at the tail`() {
        val urls = scrollAheadPrefetchUrls(gridPosts(10), firstVisibleIndex = 8, lastVisibleIndex = 9)
        assertEquals(
            listOf("https://example.test/p9.jpg", "https://example.test/p10.jpg"),
            urls,
        )
    }

    @Test
    fun `scroll ahead returns nothing past the end`() {
        assertEquals(
            emptyList<String>(),
            scrollAheadPrefetchUrls(gridPosts(50), firstVisibleIndex = 50, lastVisibleIndex = 60),
        )
        assertEquals(
            emptyList<String>(),
            scrollAheadPrefetchUrls(emptyList(), firstVisibleIndex = 0, lastVisibleIndex = 11),
        )
    }

    @Test
    fun `scroll ahead caps at twenty-four`() {
        val urls = scrollAheadPrefetchUrls(gridPosts(100), firstVisibleIndex = 0, lastVisibleIndex = 50)
        assertEquals(24, urls.size)
        assertEquals("https://example.test/p24.jpg", urls.last())
    }

    // ── Detail ───────────────────────────────────────────────────────

    @Test
    fun `detail warms the backdrop first then the similar rail`() {
        val detail = PostDetail(
            id = 1,
            title = "Title",
            posterUrl = "https://example.test/poster.jpg",
            backdropUrls = "https://example.test/back.jpg",
        )
        assertEquals(
            listOf(
                "https://example.test/back.jpg",
                "https://example.test/s1.jpg",
                "https://example.test/s2.jpg",
            ),
            detailPrefetchUrls(detail, listOf(post(2, "https://example.test/s1.jpg"), post(3, "https://example.test/s2.jpg"))),
        )
    }

    @Test
    fun `detail falls back to the poster when there is no backdrop`() {
        val detail = PostDetail(id = 1, title = "Title", posterUrl = "https://example.test/poster.jpg")
        assertEquals(
            listOf("https://example.test/poster.jpg", "https://example.test/s1.jpg"),
            detailPrefetchUrls(detail, listOf(post(2, "https://example.test/s1.jpg"))),
        )
    }

    @Test
    fun `detail caps at eight and warms each url once`() {
        val detail = PostDetail(id = 1, title = "Title", posterUrl = "https://example.test/hero.jpg")
        val similar = (2..12).map { n -> post(n, "https://example.test/s$n.jpg") } +
            listOf(post(99, "https://example.test/hero.jpg"))
        val urls = detailPrefetchUrls(detail, similar)
        assertEquals(8, urls.size)
        assertEquals("https://example.test/hero.jpg", urls.first())
        assertEquals(1, urls.count { it == "https://example.test/hero.jpg" })
    }

    @Test
    fun `detail with no title warms only the similar rail`() {
        assertEquals(
            listOf("https://example.test/s1.jpg"),
            detailPrefetchUrls(null, listOf(post(2, "https://example.test/s1.jpg"))),
        )
    }

    // ── Live TV logos (video audit issue 10) ─────────────────────────

    private fun channel(id: Int, logoUrl: String) = LiveTvChannel(
        id = id,
        name = "Channel $id",
        category = "News",
        country = "Pakistan",
        language = "Urdu",
        logoUrl = logoUrl,
        streamId = "s$id",
    )

    @Test
    fun `live tv warms the first logos in grid order and caps at twenty-four`() {
        val channels = (1..30).map { channel(it, "https://example.test/logo$it.png") }
        val urls = liveTvLogoPrefetchUrls(channels)
        assertEquals(24, urls.size)
        assertEquals("https://example.test/logo1.png", urls.first())
        assertEquals("https://example.test/logo24.png", urls.last())
    }

    @Test
    fun `live tv drops blanks and duplicates`() {
        assertEquals(
            listOf("https://example.test/a.png"),
            liveTvLogoPrefetchUrls(
                listOf(
                    channel(1, "https://example.test/a.png"),
                    channel(2, "   "),
                    channel(3, "https://example.test/a.png"),
                ),
            ),
        )
    }
}
