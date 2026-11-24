package com.raddstream.app.ui.components

import androidx.compose.ui.unit.dp
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

    @Test
    fun `match percent leads the full meta line`() {
        assertEquals("97% Match · ★ 8.6 · 2024 · Series", posterMetaLine(post(), compact = false, matchPercent = 97))
    }

    @Test
    fun `match percent leads the compact meta line too`() {
        assertEquals("97% Match · ★ 8.6 · 2024", posterMetaLine(post(), compact = true, matchPercent = 97))
    }

    @Test
    fun `match percent alone never leaves a dangling separator`() {
        assertEquals("97% Match", posterMetaLine(post(year = null, rating = null), compact = true, matchPercent = 97))
        assertEquals("97% Match · 2024 · Series", posterMetaLine(post(rating = null), compact = false, matchPercent = 97))
    }

    @Test
    fun `no match percent means the line is exactly as before`() {
        assertEquals("★ 8.6 · 2024 · Series", posterMetaLine(post(), compact = false, matchPercent = null))
    }

    // ── S2 (2027 Home fix): the width-aware meta line ───────────────────

    @Test
    fun `wide tile keeps the full meta line`() {
        assertEquals("★ 8.6 · 2024 · Series", metaLineForWidth(post(), compact = false, width = 200.dp))
    }

    @Test
    fun `narrow tile drops the type word first`() {
        assertEquals("★ 8.6 · 2024", metaLineForWidth(post(), compact = false, width = 139.dp))
        // The threshold itself keeps the type: below 140dp drops it.
        assertEquals("★ 8.6 · 2024 · Series", metaLineForWidth(post(), compact = false, width = 140.dp))
    }

    @Test
    fun `minimal tile drops the year too but never the rating`() {
        assertEquals("★ 8.6", metaLineForWidth(post(), compact = false, width = 99.dp))
        assertEquals("★ 8.6 · 2024", metaLineForWidth(post(), compact = false, width = 100.dp))
    }

    @Test
    fun `compact always drops the type whatever the width`() {
        assertEquals("★ 8.6 · 2024", metaLineForWidth(post(), compact = true, width = 200.dp))
    }

    @Test
    fun `match percent never drops even on a minimal tile`() {
        assertEquals("97% Match · ★ 8.6 · 2024", metaLineForWidth(post(), compact = false, matchPercent = 97, width = 139.dp))
        assertEquals("97% Match · ★ 8.6", metaLineForWidth(post(), compact = false, matchPercent = 97, width = 99.dp))
    }

    @Test
    fun `word-safe trim never leaves a dangling separator`() {
        // Cut inside "Movie": the boundary falls just after the separator,
        // so the "·" goes with it — never "★ 7.0 · …".
        assertEquals("★ 7.0 · 2023…", trimMetaLineToWordBoundary("★ 7.0 · 2023 · Movie", 17))
        assertEquals("★ 8.6 · 2024…", trimMetaLineToWordBoundary("★ 8.6 · 2024 · Series", 16))
        // Cut inside the year: the year goes, the rating stays.
        assertEquals("★ 7.0…", trimMetaLineToWordBoundary("★ 7.0 · 2023", 9))
    }
}
