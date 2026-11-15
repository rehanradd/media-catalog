package com.raddstream.app.ui

import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.components.heroMetaLine
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
    ) = Post(
        id = 1,
        title = "Test Title",
        cleanTitle = "Test Title",
        year = year,
        contentType = "series",
        posterUrl = null,
        genres = genres,
        seasons = seasons,
    )

    // Mockup (Nov 2026): one meta line — "seasons | genre | year" — under the
    // title, not four separate pills. The genre reads lowercase, as the
    // mockup shows it; the seasons text is the server's own, never
    // normalised here.
    @Test
    fun metaLineJoinsSeasonsGenreAndYear() {
        assertEquals("S1-S2 | reality | 2026", heroMetaLine(post(seasons = "S1-S2", genres = "Reality", year = 2026)))
    }

    @Test
    fun metaLineSkipsMissingParts() {
        assertEquals("action | 2023", heroMetaLine(post(genres = "Action", year = 2023)))
        assertEquals("2024", heroMetaLine(post(year = 2024)))
        assertEquals("S01–S03", heroMetaLine(post(seasons = "S01–S03", year = 0)))
    }

    @Test
    fun metaLineTakesTheFirstGenreOnly() {
        assertEquals("action | 2023", heroMetaLine(post(genres = "Action, Thriller", year = 2023)))
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
}
