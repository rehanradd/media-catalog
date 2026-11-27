package com.raddstream.app.ui.components

import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.Post
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Video audit (Nov 2026): the rail meta line. The line is "2024 · ★ 8.6" —
 * year and rating only, no type word: the card shows poster + title +
 * rating only. The type stays in the tile's spoken sentence and on Detail;
 * only the painted rail line loses it. Both join with a middle dot (Gemini
 * home review, Nov 2026: one separator everywhere).
 *
 * S2 (2027 Home fix, round 2): the year leads — the brief's
 * year•duration•quality as far as the list wire honestly reaches (no
 * duration or quality field on the list row, so those segments are
 * omitted, never invented).
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
    fun `meta line carries year and rating only`() {
        assertEquals("2024 · ★ 8.6", posterMetaLine(post()))
    }

    @Test
    fun `meta line never names the type`() {
        // A movie tile is the same shape: no "Movie" tail either.
        val movie = post(title = "Jawan", year = 2023, contentType = "movie", rating = "7.0")
        assertEquals("2023 · ★ 7.0", posterMetaLine(movie))
        assertEquals("2024 · ★ 8.6", posterMetaLine(post()))
    }

    @Test
    fun `meta line without a rating is just the year`() {
        assertEquals("2024", posterMetaLine(post(rating = null)))
    }

    @Test
    fun `meta line without rating or year is empty`() {
        assertEquals("", posterMetaLine(post(year = null, rating = null)))
    }

    @Test
    fun `match percent leads the meta line`() {
        assertEquals("97% Match · 2024 · ★ 8.6", posterMetaLine(post(), matchPercent = 97))
    }

    @Test
    fun `match percent alone never leaves a dangling separator`() {
        assertEquals("97% Match", posterMetaLine(post(year = null, rating = null), matchPercent = 97))
        assertEquals("97% Match · 2024", posterMetaLine(post(rating = null), matchPercent = 97))
    }

    @Test
    fun `no match percent means the line is exactly as before`() {
        assertEquals("2024 · ★ 8.6", posterMetaLine(post(), matchPercent = null))
    }

    // ── S2 (2027 Home fix): the width-aware meta line ───────────────────

    @Test
    fun `wide tile keeps year and rating`() {
        assertEquals("2024 · ★ 8.6", metaLineForWidth(post(), width = 200.dp))
    }

    @Test
    fun `minimal tile drops the year but never the rating`() {
        assertEquals("★ 8.6", metaLineForWidth(post(), width = 99.dp))
        assertEquals("2024 · ★ 8.6", metaLineForWidth(post(), width = 100.dp))
    }

    @Test
    fun `match percent never drops even on a minimal tile`() {
        assertEquals("97% Match · 2024 · ★ 8.6", metaLineForWidth(post(), matchPercent = 97, width = 139.dp))
        assertEquals("97% Match · ★ 8.6", metaLineForWidth(post(), matchPercent = 97, width = 99.dp))
    }

    @Test
    fun `word-safe trim never leaves a dangling separator`() {
        // Cut inside "Movie": the boundary falls just after the separator,
        // so the "·" goes with it — never "2023 · ★ 7.0 · …".
        assertEquals("2023 · ★ 7.0…", trimMetaLineToWordBoundary("2023 · ★ 7.0 · Movie", 17))
        assertEquals("2024 · ★ 8.6…", trimMetaLineToWordBoundary("2024 · ★ 8.6 · Series", 16))
        // Cut inside the rating: the rating number goes, the year stays.
        assertEquals("2024 · ★…", trimMetaLineToWordBoundary("2024 · ★ 8.6 · Series", 10))
    }

    // ── Round 3 (Library): the painted progress line at large font ────

    @Test
    fun `progress label keeps left at default font scale`() {
        assertEquals("S2:E5 · 23 min left", progressLabelForFontScale("S2:E5 · 23 min left", 1.0f))
    }

    @Test
    fun `progress label drops left at large font scale`() {
        assertEquals("S2:E5 · 23 min", progressLabelForFontScale("S2:E5 · 23 min left", 1.3f))
        assertEquals("23 min", progressLabelForFontScale("23 min left", 1.15f))
    }

    @Test
    fun `progress label without left is unchanged at large font scale`() {
        assertEquals("S2:E5 · 23 min", progressLabelForFontScale("S2:E5 · 23 min", 1.3f))
    }
}
