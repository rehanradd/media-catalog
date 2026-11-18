package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.WorkRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feel-instant S4: one list row as the detail it will become — the header
 * fields only. Pure, so it is pinned here without a ViewModel: what it
 * copies, what it leaves empty, and what it never invents.
 */
class OptimisticDetailTest {

    private fun post() = Post(
        id = 13724,
        title = "Sonu Ke Titu Ki Sweety",
        year = 2018,
        contentType = "movie",
        posterUrl = "https://cdn.example/poster.jpg",
        backdropUrls = listOf("https://cdn.example/back.jpg"),
        imdbRating = 7.2,
        genres = listOf("Comedy", "Romance"),
        industry = "bollywood",
        language = "hindi",
        workRef = WorkRef(987, "Work"),
    )

    @Test
    fun `the header fields are the row's own`() {
        val detail = optimisticDetailFromPost(post())
        assertEquals(13724, detail.id)
        assertEquals("Sonu Ke Titu Ki Sweety", detail.title)
        assertEquals(2018, detail.year)
        assertEquals("movie", detail.contentType)
        assertEquals("https://cdn.example/poster.jpg", detail.posterUrl)
        assertEquals(listOf("https://cdn.example/back.jpg"), detail.backdropUrls)
        assertEquals(7.2, detail.imdbRating!!, 0.001)
        assertEquals(listOf("Comedy", "Romance"), detail.genres)
        assertEquals("bollywood", detail.industry)
        assertEquals("hindi", detail.language)
        assertEquals(WorkRef(987, "Work"), detail.workRef)
    }

    @Test
    fun `the body stays empty until the fetch lands`() {
        val detail = optimisticDetailFromPost(post())
        assertTrue(detail.releases.isEmpty())
        assertNull(detail.description)
        assertNull(detail.cast)
        assertNull(detail.runtimeMinutes)
        assertFalse(detail.isAnimated)
    }
}
