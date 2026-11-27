package com.raddstream.app.ui.components

import com.raddstream.app.network.models.Post
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Video audit (Nov 2026), refined direction 21:51 PKT 27 Nov: the rail
 * meta line is the LANGUAGE only — the card shows poster + title +
 * language. Year and rating are OFF home cards (they live on Detail,
 * rating also in Search); the type word never paints either. Both join
 * with a middle dot when the match percent leads (Gemini home review,
 * Nov 2026: one separator everywhere).
 *
 * Pure string work, so the rule is assertable without a layout.
 */
class PosterCardMetaTest {

    private fun post(
        title: String = "The Penguin",
        year: Int? = 2024,
        contentType: String? = "series",
        rating: String? = "8.6",
        language: String? = "Hindi",
    ) = Post(
        id = 1,
        title = title,
        cleanTitle = title,
        year = year,
        contentType = contentType,
        imdbRating = rating,
        language = language,
    )

    @Test
    fun `meta line carries the language only`() {
        assertEquals("Hindi", posterMetaLine(post()))
    }

    @Test
    fun `meta line never names the year rating or type`() {
        // A movie tile is the same shape: no year, no star, no "Movie" tail.
        val movie = post(title = "Jawan", year = 2023, contentType = "movie", rating = "7.0", language = "Hindi")
        assertEquals("Hindi", posterMetaLine(movie))
        assertEquals("Hindi", posterMetaLine(post()))
        val english = post(language = "English")
        assertEquals("English", posterMetaLine(english))
    }

    @Test
    fun `meta line without a language is empty`() {
        assertEquals("", posterMetaLine(post(language = null)))
    }

    @Test
    fun `meta line paints the first language when the wire carries several`() {
        // The wire's language column holds a list as text ("Hindi +
        // English + Japanese") — the card gets one glance, the first.
        assertEquals("Hindi", posterMetaLine(post(language = "Hindi + English + Japanese")))
    }

    @Test
    fun `match percent leads the meta line`() {
        assertEquals("97% Match · Hindi", posterMetaLine(post(), matchPercent = 97))
    }

    @Test
    fun `match percent alone never leaves a dangling separator`() {
        assertEquals("97% Match", posterMetaLine(post(language = null), matchPercent = 97))
        assertEquals("97% Match · Hindi", posterMetaLine(post(), matchPercent = 97))
    }

    @Test
    fun `no match percent means the line is exactly as before`() {
        assertEquals("Hindi", posterMetaLine(post(), matchPercent = null))
    }

    // ── Word-safe trim for the meta line ───────────────────────────────

    @Test
    fun `word-safe trim never leaves a dangling separator`() {
        // Cut inside "Hindi": the boundary falls just after the separator,
        // so the "·" goes with it — never "97% Match · …".
        assertEquals("97% Match…", trimMetaLineToWordBoundary("97% Match · Hindi", 14))
        // Cut inside "Match": the boundary falls after "97%".
        assertEquals("97%…", trimMetaLineToWordBoundary("97% Match · Hindi", 6))
        // A single word has no earlier boundary: the line is unchanged and
        // the ellipsis does the cutting.
        assertEquals("Hindi", trimMetaLineToWordBoundary("Hindi", 3))
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
