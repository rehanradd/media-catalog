package com.raddstream.app.ui

import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.components.heroMetaLine
import com.raddstream.app.ui.components.heroSeasonsLabel
import com.raddstream.app.ui.components.heroTitle
import com.raddstream.app.ui.components.isKnownLanguage
import com.raddstream.app.ui.components.nextHeroPage
import com.raddstream.app.ui.components.shouldAdvanceHero
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero banner's pure logic, testable without a pager: the one meta line
 * under the title, the page step, and the auto-advance timing.
 *
 * The language pills used to be tested here too (heroLanguagePills: split,
 * cap, dedupe, drop Unknown/N/A). They are gone with the pills — the hero
 * shows one meta line now, and the languages survive only in the slide's
 * spoken sentence, filtered by [isKnownLanguage] — so those tests went with
 * the pills, and the overlap tests went with the dots: the dots ride below
 * the card now, where no band math can put them over the buttons.
 */
class HeroCarouselTest {

    private fun post(
        seasons: String? = null,
        genres: String? = null,
        year: Int = 2026,
        imdbRating: String? = null,
    ) = Post(
        id = 1,
        title = "Test Title",
        cleanTitle = "Test Title",
        year = year,
        contentType = "series",
        posterUrl = null,
        genres = genres,
        seasons = seasons,
        imdbRating = imdbRating,
    )

    // Home review (Nov 2026): one meta line — "2026 · Season 1–2 ·
    // Reality" — under the title, not four separate pills. The seasons are
    // normalised to a range, the genre reads capitalised, and the parts
    // join with " · " — never a raw list like "[1, 2] | reality | 2026".
    @Test
    fun metaLineJoinsSeasonsGenreAndYear() {
        assertEquals("2026 · Season 1–2 · Reality", heroMetaLine(post(seasons = "S1-S2", genres = "Reality", year = 2026)))
    }

    @Test
    fun metaLineSkipsMissingParts() {
        assertEquals("2023 · Action", heroMetaLine(post(genres = "Action", year = 2023)))
        assertEquals("2024", heroMetaLine(post(year = 2024)))
        assertEquals("Season 1–3", heroMetaLine(post(seasons = "S01–S03", year = 0)))
    }

    @Test
    fun metaLineDropsAnUnknownFirstGenre() {
        // The catalog writes the literal word "Unknown" where a work's
        // genres were never recorded — a missing value, not a genre — so the
        // hero's meta line skips it and reads the next real genre, or the
        // year alone when there is none.
        assertEquals("Action · 2026", heroMetaLine(post(genres = "Unknown, Action", year = 2026)))
        assertEquals("2026", heroMetaLine(post(genres = "Unknown", year = 2026)))
        assertEquals("2026", heroMetaLine(post(genres = "N/A", year = 2026)))
    }

    @Test
    fun metaLineTakesTheFirstGenreOnly() {
        assertEquals("2023 · Action", heroMetaLine(post(genres = "Action, Thriller", year = 2023)))
    }

    // E2 Ratings (Nov 2026): the hero is the one big surface that never
    // painted a rating. The year leads the meta line (S2 round 2), the
    // rating rides after it like the rail meta line, and only when the
    // catalog really has one — a missing,
    // zero or unparseable value leaves the slot out, never a 0.0.
    @Test
    fun metaLineLeadsWithTheYearAndCarriesTheRating() {
        assertEquals(
            "2026 · ★ 8.6 · Season 1–2 · Reality",
            heroMetaLine(post(seasons = "S1-S2", genres = "Reality", year = 2026, imdbRating = "8.6")),
        )
        assertEquals("2023 · ★ 7.0 · Action", heroMetaLine(post(genres = "Action", year = 2023, imdbRating = "7")))
    }

    @Test
    fun metaLineSkipsAnAbsentOrDishonestRating() {
        assertEquals("2023 · Action", heroMetaLine(post(genres = "Action", year = 2023, imdbRating = null)))
        assertEquals("2023 · Action", heroMetaLine(post(genres = "Action", year = 2023, imdbRating = "")))
        assertEquals("2023 · Action", heroMetaLine(post(genres = "Action", year = 2023, imdbRating = "0")))
        assertEquals("2023 · Action", heroMetaLine(post(genres = "Action", year = 2023, imdbRating = "abc")))
        assertEquals("2023 · Action", heroMetaLine(post(genres = "Action", year = 2023, imdbRating = "101")))
    }

    @Test
    fun metaLineDividesTheOldHundredScale() {
        assertEquals("2023 · ★ 8.6", heroMetaLine(post(year = 2023, imdbRating = "86")))
    }

    @Test
    fun seasonsLabelNormalisesEveryServerShape() {
        assertEquals("Season 1–2", heroSeasonsLabel("[1, 2]"))
        assertEquals("Season 1–2", heroSeasonsLabel("2"))
        assertEquals("Season 1", heroSeasonsLabel("1"))
        assertEquals("Season 1", heroSeasonsLabel("S1"))
        assertEquals("Season 1–3", heroSeasonsLabel("S01–S03"))
        assertNull(heroSeasonsLabel(null))
        assertNull(heroSeasonsLabel("   "))
        assertNull(heroSeasonsLabel("unknown"))
    }

    @Test
    fun heroTitleUsesTheCleanTitleOnly() {
        val post = Post(
            id = 1,
            title = "It's Showtime (2026) S1-S2",
            cleanTitle = "It's Showtime (2026) S1-S2",
            year = 2026,
            contentType = "series",
            posterUrl = null,
        )
        assertEquals("It's Showtime", heroTitle(post))
    }

    @Test
    fun metaLineIsNullWhenNothingToSay() {
        assertNull(heroMetaLine(post(year = 0)))
        assertNull(heroMetaLine(post(genres = "  ", year = 0)))
    }

    @Test
    fun knownLanguageKnowsWhatCounts() {
        assertTrue(isKnownLanguage("Hindi"))
        assertFalse(isKnownLanguage("Unknown"))
        assertFalse(isKnownLanguage("UNKNOWN"))
        assertFalse(isKnownLanguage("N/A"))
        assertFalse(isKnownLanguage("n/a"))
        assertFalse(isKnownLanguage(""))
        assertFalse(isKnownLanguage("   "))
    }

    // The auto-advance step: forward one page, wrapping from the last back to 0.
    @Test
    fun nextPageAdvancesAndWraps() {
        assertEquals(1, nextHeroPage(0, 6))
        assertEquals(5, nextHeroPage(4, 6))
        assertEquals(0, nextHeroPage(5, 6))
        assertEquals(0, nextHeroPage(0, 1))
    }

    @Test
    fun nextPageNowhereOnDegenerateCount() {
        assertEquals(0, nextHeroPage(0, 0))
        assertEquals(0, nextHeroPage(3, 0))
    }

    // Samsung RTL (v1.1.2, Issue 3): the banner rotated every 5 s and a manual
    // swipe or dot tap did not restart the clock, so Watch Now was a moving
    // target. A full interval must pass since the last change or interaction
    // before the loop takes the next slide.
    @Test
    fun advancesAfterAFullIntervalWhenIdle() {
        assertTrue(shouldAdvanceHero(nowMs = 8_000L, lastChangeMs = 0L, intervalMs = 8_000L, isScrolling = false, watchNowPressed = false))
        assertTrue(shouldAdvanceHero(nowMs = 9_000L, lastChangeMs = 0L, intervalMs = 8_000L, isScrolling = false, watchNowPressed = false))
    }

    @Test
    fun holdsBeforeTheIntervalHasPassed() {
        assertFalse(shouldAdvanceHero(nowMs = 7_999L, lastChangeMs = 0L, intervalMs = 8_000L, isScrolling = false, watchNowPressed = false))
        // A manual interaction 1 s ago restarts the clock: the slide just
        // chosen is never yanked away a beat later.
        assertFalse(shouldAdvanceHero(nowMs = 9_000L, lastChangeMs = 8_000L, intervalMs = 8_000L, isScrolling = false, watchNowPressed = false))
    }

    @Test
    fun holdsWhileScrollingOrPressingWatchNow() {
        assertFalse(shouldAdvanceHero(nowMs = 20_000L, lastChangeMs = 0L, intervalMs = 8_000L, isScrolling = true, watchNowPressed = false))
        assertFalse(shouldAdvanceHero(nowMs = 20_000L, lastChangeMs = 0L, intervalMs = 8_000L, isScrolling = false, watchNowPressed = true))
    }

    @Test
    fun neverAdvancesOnANonPositiveInterval() {
        assertFalse(shouldAdvanceHero(nowMs = 20_000L, lastChangeMs = 0L, intervalMs = 0L, isScrolling = false, watchNowPressed = false))
        assertFalse(shouldAdvanceHero(nowMs = 20_000L, lastChangeMs = 0L, intervalMs = -1L, isScrolling = false, watchNowPressed = false))
    }

    // Home fix (Nov 2026): the viewer's pause holds the banner even after a
    // full interval — the only way to stop a rotating banner was to leave Home.
    @Test
    fun holdsWhilePaused() {
        assertFalse(shouldAdvanceHero(nowMs = 20_000L, lastChangeMs = 0L, intervalMs = 8_000L, isScrolling = false, watchNowPressed = false, paused = true))
    }
}
