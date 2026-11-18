package com.raddstream.app.ui.components

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
    fun `home takes the first two posters per visible rail in order`() {
        assertEquals(
            listOf("https://example.test/a.jpg", "https://example.test/b.jpg", "https://example.test/c.jpg"),
            homeRailPrefetchUrls(
                listOf(
                    section("recent", post(1, "https://example.test/a.jpg"), post(2, "https://example.test/b.jpg"), post(3, "https://example.test/d.jpg")),
                    section("movie", post(4, "https://example.test/c.jpg")),
                ),
            ),
        )
    }

    @Test
    fun `home caps at twenty`() {
        val sections = (1..11).map { n ->
            section("rail$n", post(n * 2 - 1, "https://example.test/$n-a.jpg"), post(n * 2, "https://example.test/$n-b.jpg"))
        }
        assertEquals(20, homeRailPrefetchUrls(sections).size)
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
    fun `search flattens groups in order and caps at twelve`() {
        val groups = (1..3).map { g ->
            TypeGroup(
                label = "Group $g",
                typeKey = null,
                items = (1..5).map { n -> post(g * 10 + n, "https://example.test/g$g-$n.jpg") },
            )
        }
        val urls = searchPrefetchUrls(groups)
        assertEquals(12, urls.size)
        assertEquals("https://example.test/g1-1.jpg", urls.first())
        assertEquals("https://example.test/g3-2.jpg", urls.last())
    }

    @Test
    fun `search drops blanks and duplicates`() {
        assertEquals(
            listOf("https://example.test/a.jpg"),
            searchPrefetchUrls(
                listOf(
                    TypeGroup("Movies", "movie", listOf(post(1, "https://example.test/a.jpg"), post(2, "  "))),
                    TypeGroup("Series", "series", listOf(post(3, "https://example.test/a.jpg"))),
                ),
            ),
        )
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
}
