package com.raddstream.app.ui.components

import com.raddstream.app.network.models.Post
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * My List review (Nov 2026): the rail meta line. The full form is
 * "★ 8.6 · 2024 · Series"; the compact form (the 3-up starter rail,
 * where three segments truncate to "S...") drops the type word — "★ 8.6 · 2024".
 * Both join with a middle dot (Gemini home review, Nov 2026: one separator
 * everywhere). The type stays in the tile's spoken
 * sentence and on Detail; only the painted rail line loses it.
 *
 * Pure string work, so the rule is assertable without a layout.
 */
class PosterCardMetaTest {

    private fun post(
        title: String = "The Penguin",
        year: Int? = 2024,
        contentType: String? = "series",
        rating: String? = "8.6",
    ) = Post(
        id = 1,
        title = title,
        cleanTitle = title,
        year = year,
        contentType = contentType,
        imdbRating = rating,
    )

    @Test
    fun `full meta line carries rating year and type`() {
        assertEquals("★ 8.6 · 2024 · Series", posterMetaLine(post(), compact = false))
    }

    @Test
    fun `compact meta line drops the type word`() {
        assertEquals("★ 8.6 · 2024", posterMetaLine(post(), compact = true))
    }

    @Test
    fun `compact meta line without a rating is just the year`() {
        assertEquals("2024", posterMetaLine(post(rating = null), compact = true))
    }

    @Test
    fun `full meta line without a rating keeps year and type`() {
        assertEquals("2024 · Series", posterMetaLine(post(rating = null), compact = false))
    }

    @Test
    fun `compact meta line without rating or year is empty`() {
        assertEquals("", posterMetaLine(post(year = null, rating = null), compact = true))
    }

    @Test
    fun `compact meta line never names the type`() {
        // A movie tile is the same shape: no "Movie" tail either.
        val movie = post(title = "Jawan", year = 2023, contentType = "movie", rating = "7.0")
        assertEquals("★ 7.0 · 2023", posterMetaLine(movie, compact = true))
    }
}
