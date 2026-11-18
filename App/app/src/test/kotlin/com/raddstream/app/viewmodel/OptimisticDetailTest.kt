package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feel-instant S4: one list row as the detail it will become — the header
 * fields only. Pure, so it is pinned here without a ViewModel: what it
 * copies, what it leaves empty, and what it never invents.
 *
 * The wire types are the model's own: ratings, genres and backdrops arrive
 * as TEXT (`imdbRating` "7.2", `genres` CSV, `backdropUrls` a JSON-array
 * string), and the work handle is a plain string — the mapping copies
 * them, it does not parse them.
 */
class OptimisticDetailTest {

    private fun post() = Post(
        id = 13724,
        title = "Sonu Ke Titu Ki Sweety",
        year = 2018,
        contentType = "movie",
        posterUrl = "https://cdn.example/poster.jpg",
        backdropUrls = "[\"https://cdn.example/back.jpg\"]",
        imdbRating = "7.2",
        genres = "Comedy, Romance",
        industry = "bollywood",
        language = "hindi",
        workRef = "w987",
    )

    @Test
    fun `the header fields are the row's own`() {
        val detail = optimisticDetailFromPost(post())
        assertEquals(13724, detail.id)
        assertEquals("Sonu Ke Titu Ki Sweety", detail.title)
        assertEquals(2018, detail.year)
        assertEquals("movie", detail.contentType)
        assertEquals("https://cdn.example/poster.jpg", detail.posterUrl)
        assertEquals("[\"https://cdn.example/back.jpg\"]", detail.backdropUrls)
        assertEquals("7.2", detail.imdbRating)
        assertEquals("Comedy, Romance", detail.genres)
        assertEquals("bollywood", detail.industry)
        assertEquals("hindi", detail.language)
        assertEquals("w987", detail.workRef)
    }

    @Test
    fun `the body stays empty until the fetch lands`() {
        val detail = optimisticDetailFromPost(post())
        assertTrue(detail.releases.isEmpty())
        assertNull(detail.synopsis)
        assertNull(detail.castNames)
        assertNull(detail.runtimeMinutes)
        assertNull(detail.isAnimated)
    }
}
