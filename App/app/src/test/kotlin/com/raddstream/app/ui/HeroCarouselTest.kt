package com.raddstream.app.ui

import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.components.clampHeroPage
import com.raddstream.app.ui.components.heroTitle
import com.raddstream.app.ui.components.isKnownLanguage
import com.raddstream.app.ui.components.nextHeroPage
import com.raddstream.app.ui.components.shouldAdvanceHero
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero banner's pure logic, testable without a pager: the page step,
 * the auto-advance timing, and the clean-hero contract.
 *
 * The hero is CLEAN (user direction, 21:51 PKT 27 Nov 2026): artwork + a
 * simple animated dot indicator, nothing else. The title, meta line and
 * buttons are gone with the words — the meta-line tests, the seasons
 * label, the button-height / button-share tests and the #8 art-text
 * test went with them (there is no hero text left to mismatch, and no
 * button left to squeeze). The title survives only in the slide's spoken
 * sentence ([heroTitle]), and the languages survive only there too,
 * filtered by [isKnownLanguage].
 *
 * The language pills used to be tested here too (heroLanguagePills: split,
 * cap, dedupe, drop Unknown/N/A). They are gone with the pills — the hero
 * shows one meta line now, and the languages survive only in the slide's
 * spoken sentence, filtered by [isKnownLanguage] — so those tests went with
 * the pills, and the overlap tests went with the dots: the dots ride on the
 * art now, top-end (P1-5), where no band math can put them over the buttons.
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

    // Video-audit #1 (Nov 2026): the crash on Home followed a return from
    // Live TV and a hero auto-advance tick. A page the pager held before
    // the list shrank is stale — the step must wrap safely from it instead
    // of ever reading a slide that no longer exists.
    @Test
    fun nextPageWrapsSafelyFromAStalePage() {
        assertEquals(0, nextHeroPage(5, 3))
        assertEquals(2, nextHeroPage(7, 3))
        assertEquals(1, nextHeroPage(3, 3))
    }

    // The clamp the LaunchedEffect(posts.size) applies when the list
    // shrinks under a restored pager: back to the last valid page.
    @Test
    fun clampHeroPageHoldsTheLastValidPage() {
        assertEquals(2, clampHeroPage(5, 3))
        assertEquals(2, clampHeroPage(2, 3))
        assertEquals(0, clampHeroPage(0, 3))
        assertEquals(0, clampHeroPage(0, 1))
    }

    @Test
    fun clampHeroPageHandlesDegenerateAndNegativePages() {
        assertEquals(0, clampHeroPage(5, 0))
        assertEquals(0, clampHeroPage(0, 0))
        assertEquals(0, clampHeroPage(-1, 3))
    }

    // Samsung RTL (v1.1.2, Issue 3): the banner rotated every 5 s and a manual
    // swipe or dot tap did not restart the clock, so the slide a viewer had
    // just chosen could be yanked away a beat later. A full interval must
    // pass since the last change or interaction before the loop takes the
    // next slide.
    @Test
    fun advancesAfterAFullIntervalWhenIdle() {
        assertTrue(shouldAdvanceHero(nowMs = 8_000L, lastChangeMs = 0L, intervalMs = 8_000L, isScrolling = false))
        assertTrue(shouldAdvanceHero(nowMs = 9_000L, lastChangeMs = 0L, intervalMs = 8_000L, isScrolling = false))
    }

    @Test
    fun holdsBeforeTheIntervalHasPassed() {
        assertFalse(shouldAdvanceHero(nowMs = 7_999L, lastChangeMs = 0L, intervalMs = 8_000L, isScrolling = false))
        // A manual interaction 1 s ago restarts the clock: the slide just
        // chosen is never yanked away a beat later.
        assertFalse(shouldAdvanceHero(nowMs = 9_000L, lastChangeMs = 8_000L, intervalMs = 8_000L, isScrolling = false))
    }

    @Test
    fun holdsWhileScrolling() {
        assertFalse(shouldAdvanceHero(nowMs = 20_000L, lastChangeMs = 0L, intervalMs = 8_000L, isScrolling = true))
    }

    @Test
    fun neverAdvancesOnANonPositiveInterval() {
        assertFalse(shouldAdvanceHero(nowMs = 20_000L, lastChangeMs = 0L, intervalMs = 0L, isScrolling = false))
        assertFalse(shouldAdvanceHero(nowMs = 20_000L, lastChangeMs = 0L, intervalMs = -1L, isScrolling = false))
    }

    // Clean hero (user direction, 21:51 PKT 27 Nov 2026): the hero is
    // artwork + a dot indicator, nothing else — no title text, no meta
    // line, no Watch Now / My List buttons, no scrim, no pause control.
    // A text scan in the SkeletonParityTest style pins it: the file must
    // carry no Text() or Button() composable call at all, and the slide
    // must still be the tap target that opens the title (the whole slide
    // is clickable — everybody already knows tapping a poster opens it).
    @Test
    fun cleanHeroPaintsArtworkAndDotsOnly() {
        val source = File("src/main/kotlin/com/raddstream/app/ui/components/HeroCarousel.kt").readText()
        assertFalse("the clean hero paints no text", Regex("""\bText\(""").containsMatchIn(source))
        assertFalse("the clean hero paints no button", Regex("""\bButton\(""").containsMatchIn(source))
        assertTrue("the clean hero slide must still open the title on tap", "clickable(onClick = onClick)" in source)
    }
}
