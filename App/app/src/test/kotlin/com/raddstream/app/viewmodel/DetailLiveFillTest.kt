package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.PostDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live fill (detail open): the trigger and the merge are pure functions
 * on [PostDetail], so they are tested here without the ViewModel — the
 * ViewModel itself is never constructed in tests (its constructor needs
 * the whole app). The server side of the fill is covered by
 * `test_api_v2.py` (`V2LiveFillTests`) and `test_metadata_backfill.py`
 * (`FillOneTests`).
 */
class DetailLiveFillTest {

    private fun blankDetail() = PostDetail(id = 7, title = "Blank Title")

    private fun fullDetail() = PostDetail(
        id = 7,
        title = "Full Title",
        year = 2024,
        posterUrl = "https://cdn.example/poster.jpg",
        backdropUrls = "[\"https://cdn.example/backdrop.jpg\"]",
        imdbRating = "8.1",
        genres = "Action, Drama",
        synopsis = "A story.",
        castNames = "Actor One, Actor Two",
    )

    private fun fullFill() = mapOf(
        "rating" to "8.1",
        "genres" to "Action, Drama",
        "synopsis" to "A story.",
        "cast" to "Actor One, Actor Two",
        "director" to "Director Name",
        "poster" to "https://cdn.example/poster.jpg",
        "backdrop" to "[\"https://cdn.example/backdrop.jpg\"]",
        "year" to "2024",
        "imdb_id" to "tt1234567",
        "tmdb_id" to "12345",
    )

    @Test
    fun `a detail with every field blank has missing fields`() {
        assertTrue(blankDetail().hasMissingDisplayFields())
    }

    @Test
    fun `a detail with every field filled has nothing missing`() {
        assertFalse(fullDetail().hasMissingDisplayFields())
    }

    @Test
    fun `one blank field is enough to trigger`() {
        assertTrue(fullDetail().copy(synopsis = null).hasMissingDisplayFields())
        assertTrue(fullDetail().copy(imdbRating = "").hasMissingDisplayFields())
        assertTrue(fullDetail().copy(year = null).hasMissingDisplayFields())
        assertTrue(fullDetail().copy(backdropUrls = null).hasMissingDisplayFields())
        assertTrue(fullDetail().copy(posterUrl = "   ").hasMissingDisplayFields())
    }

    @Test
    fun `merge fills only the blanks`() {
        val patched = blankDetail().withLiveFill(fullFill())

        assertEquals("8.1", patched.imdbRating)
        assertEquals("Action, Drama", patched.genres)
        assertEquals("A story.", patched.synopsis)
        assertEquals("Actor One, Actor Two", patched.castNames)
        assertEquals("https://cdn.example/poster.jpg", patched.posterUrl)
        assertEquals("[\"https://cdn.example/backdrop.jpg\"]", patched.backdropUrls)
        assertEquals(2024, patched.year)
        assertEquals("tt1234567", patched.imdbId)
        assertEquals(12345, patched.tmdbId)
    }

    @Test
    fun `merge never overwrites a value already on screen`() {
        val patched = fullDetail().withLiveFill(
            mapOf(
                "rating" to "1.0",
                "synopsis" to "A different story.",
                "cast" to "Someone Else",
                "poster" to "https://cdn.example/other.jpg",
                "year" to "1999",
            ),
        )

        assertEquals(fullDetail(), patched)
    }

    @Test
    fun `merge ignores blank fill values and a non-numeric year`() {
        val patched = blankDetail().withLiveFill(
            mapOf(
                "rating" to "   ",
                "synopsis" to "",
                "year" to "soon",
                "tmdb_id" to "not-a-number",
            ),
        )

        assertNull(patched.imdbRating)
        assertNull(patched.synopsis)
        assertNull(patched.year)
        assertNull(patched.tmdbId)
    }

    @Test
    fun `merge ignores unknown keys and the director`() {
        val patched = blankDetail().withLiveFill(
            mapOf(
                "rating" to "8.1",
                "director" to "Director Name",
                "something_new" to "whatever",
            ),
        )

        assertEquals("8.1", patched.imdbRating)
    }
}
