package com.raddstream.app.ui.components

import com.raddstream.app.network.models.Post
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hero art prefetch (Nov 2026): the URLs the prefetch warms, in slide order.
 * The widest artwork each row carries — first backdrop, else poster — blanks
 * dropped, duplicates dropped. Pure, so no Coil and no clock is needed.
 */
class HeroArtPrefetchTest {

    private fun post(id: Int, posterUrl: String? = null, backdropUrls: String? = null) = Post(
        id = id,
        title = "Title $id",
        cleanTitle = "Title $id",
        posterUrl = posterUrl,
        backdropUrls = backdropUrls,
    )

    @Test
    fun `backdrop wins over poster`() {
        assertEquals(
            listOf("https://image.tmdb.org/t/p/original/back.jpg"),
            heroArtUrls(
                listOf(
                    post(
                        id = 1,
                        posterUrl = "https://image.tmdb.org/t/p/original/poster.jpg",
                        backdropUrls = "https://image.tmdb.org/t/p/original/back.jpg",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `poster is the fallback when there is no backdrop`() {
        assertEquals(
            listOf("https://image.tmdb.org/t/p/original/poster.jpg"),
            heroArtUrls(listOf(post(id = 1, posterUrl = "https://image.tmdb.org/t/p/original/poster.jpg"))),
        )
    }

    @Test
    fun `blank urls are dropped`() {
        assertEquals(
            emptyList<String>(),
            heroArtUrls(listOf(post(id = 1, posterUrl = "   "), post(id = 2))),
        )
    }

    @Test
    fun `duplicate art is warmed once`() {
        val url = "https://image.tmdb.org/t/p/original/same.jpg"
        assertEquals(
            listOf(url),
            heroArtUrls(listOf(post(id = 1, posterUrl = url), post(id = 2, posterUrl = url))),
        )
    }

    @Test
    fun `slide order is kept`() {
        assertEquals(
            listOf("https://example.test/a.jpg", "https://example.test/b.jpg"),
            heroArtUrls(
                listOf(
                    post(id = 1, posterUrl = "https://example.test/a.jpg"),
                    post(id = 2, posterUrl = "https://example.test/b.jpg"),
                ),
            ),
        )
    }
}
