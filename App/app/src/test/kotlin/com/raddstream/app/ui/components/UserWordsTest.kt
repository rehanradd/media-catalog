package com.raddstream.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * App-design audit (2026-11-12): "★ 100.0" on a 0–10 scale, a literal
 * "/PLOT" line on the detail screen, and the same titles repeated across
 * Home rails. Each rule in [UserWords] is pinned here so the bad input
 * can never render again.
 */
class UserWordsTest {

    @Test
    fun ratingKeepsAValidTenScaleValue() {
        assertEquals("8.6", UserWords.displayRating("8.6"))
        assertEquals("10.0", UserWords.displayRating("10"))
    }

    @Test
    fun ratingDividesAHundredScaleValueByTen() {
        // The audit's "★ 100.0": a 0–100 value on a 0–10 badge.
        assertEquals("10.0", UserWords.displayRating("100.0"))
        assertEquals("7.6", UserWords.displayRating("76"))
    }

    @Test
    fun ratingHidesUnparseableOrOutOfRangeValues() {
        assertNull(UserWords.displayRating(null))
        assertNull(UserWords.displayRating(""))
        assertNull(UserWords.displayRating("abc"))
        assertNull(UserWords.displayRating("-1"))
        assertNull(UserWords.displayRating("101"))
        assertNull(UserWords.displayRating("Unknown"))
    }

    @Test
    fun ratingZeroMeansNoRatingRecorded() {
        // Dev-cleanup (Nov 2026): a 0.0 is "no rating recorded", not "rated
        // zero" — it painted "★ 0.0" on tiles and "0.0 IMDb" on Detail.
        assertNull(UserWords.displayRating("0"))
        assertNull(UserWords.displayRating("0.0"))
        assertNull(UserWords.displayRating("0.00"))
    }

    @Test
    fun synopsisDropsSectionMarkerLines() {
        val text = "A thief steals a dream.\n/PLOT\nHe wakes up changed."
        assertEquals(
            "A thief steals a dream.\nHe wakes up changed.",
            UserWords.stripSectionMarkers(text),
        )
    }

    @Test
    fun synopsisKeepsSlashesInsideSentences() {
        val text = "A comedy/drama about two friends."
        assertEquals(text, UserWords.stripSectionMarkers(text))
    }

    @Test
    fun dataEstimateNamesTheSameCostsAsThePlayerSheet() {
        assertEquals("≈ 700 MB/hour", UserWords.dataEstimateForQuality("480p"))
        assertEquals("≈ 1.5 GB/hour", UserWords.dataEstimateForQuality("720p"))
        assertEquals("≈ 3 GB/hour", UserWords.dataEstimateForQuality("1080p"))
        assertNull(UserWords.dataEstimateForQuality("cam"))
    }

    @Test
    fun titleBreaksBeforeAtSignNeverInsideTheNumber() {
        // "Kesariya@100" wrapped as "Kesariya@1 / 00" in the audit.
        val broken = UserWords.breakableTitle("Kesariya@100")
        assertEquals("Kesariya\u200B@100", broken)
        // The number itself carries no break opportunity.
        assert(!broken.substringAfter("@").contains("\u200B"))
    }

    @Test
    fun workKeyPrefersTheWorkRef() {
        assertEquals("work:salaar-2023", UserWords.workKey("Salaar-2023", "Salaar", 2023))
        assertEquals(
            "title:salaar part 1 ceasefire:2023",
            UserWords.workKey(null, "Salaar: Part 1 – Ceasefire", 2023),
        )
        assertEquals(
            "title:salaar part 1 2023 multi audios:2023",
            UserWords.workKey("  ", "Salaar - Part 1 (2023) Multi Audios", 2023),
        )
    }

    @Test
    fun railDedupeKeepsFirstRailAndDropsRepeats() {
        val (first, seen) = UserWords.dedupeAcrossRails(
            listOf("a", "b", "c"), emptySet(), { it },
        )
        assertEquals(listOf("a", "b", "c"), first)
        val (second, _) = UserWords.dedupeAcrossRails(
            listOf("b", "c", "d"), seen, { it },
        )
        assertEquals(listOf("d"), second)
    }

    @Test
    fun trimToWordBoundaryBacksOffAMidWordCut() {
        // "Strange New W…" — the layout cut inside "World".
        assertEquals(
            "Strange New…",
            UserWords.trimToWordBoundary("Strange New World", 13),
        )
        assertEquals(
            "Accident Man: Hitman's…",
            UserWords.trimToWordBoundary("Accident Man: Hitman's Holiday", 24),
        )
    }

    @Test
    fun trimToWordBoundaryLeavesABoundaryCutAlone() {
        assertEquals("Strange New World", UserWords.trimToWordBoundary("Strange New World", 11))
        assertEquals("Strange New World", UserWords.trimToWordBoundary("Strange New World", 12))
        assertEquals("Strange New World", UserWords.trimToWordBoundary("Strange New World", 17))
        assertEquals("Strange New World", UserWords.trimToWordBoundary("Strange New World", 0))
    }

    @Test
    fun detailTitleStripsYearLanguageAndTypeSuffix() {
        // The live post 21013 shape: the clean title carries all three
        // suffixes the meta line already states.
        assertEquals(
            "No Hard Feelings",
            UserWords.detailTitle(
                displayTitle = null,
                cleanTitle = "No Hard Feelings (2023) English Movie",
                title = "No Hard Feelings 2023 AMZN Dual Audio Hindi (ORG 5.1) 1080p 720p 480p WEB-DL x264 ESubs",
                year = 2023,
                contentType = "movie",
                languages = listOf("English"),
            ),
        )
    }

    @Test
    fun detailTitlePrefersTheServerDisplayTitle() {
        // Live post 225039: the server's display title is already clean,
        // so it wins over the suffixed clean title — and the mid-title
        // "(2023)" inside it survives the strip, which only touches the tail.
        assertEquals(
            "No Hard Feelings v2 – HDCAMRip",
            UserWords.detailTitle(
                displayTitle = "No Hard Feelings v2 – HDCAMRip",
                cleanTitle = "No Hard Feelings (2023) v2 – HDCAMRip [HINDI HQ DUBBED] Full Movie",
                title = "No Hard Feelings (2023) v2 – HDCAMRip [HINDI HQ DUBBED] Full Movie",
                year = 2023,
                contentType = "movie",
                languages = listOf("Hindi"),
            ),
        )
    }

    @Test
    fun detailTitleFallsBackToTheRawTitle() {
        assertEquals(
            "Raw",
            UserWords.detailTitle(
                displayTitle = null,
                cleanTitle = null,
                title = "Raw (2023) Movie",
                year = 2023,
                contentType = "movie",
                languages = emptyList(),
            ),
        )
    }

    @Test
    fun stripTitleSuffixesKeepsWordsThatAreNotSuffixes() {
        // Only the tail is ever touched.
        assertEquals("The English Patient", UserWords.stripTitleSuffixes("The English Patient", 1996, "movie", listOf("English")))
        assertEquals("1917", UserWords.stripTitleSuffixes("1917", 2019, "movie", emptyList()))
        assertEquals(
            "No Hard Feelings (2023) v2 – HDCAMRip",
            UserWords.stripTitleSuffixes("No Hard Feelings (2023) v2 – HDCAMRip", 2023, "movie", listOf("Hindi")),
        )
    }

    @Test
    fun stripTitleSuffixesNeedsThePostsOwnLanguageAndYear() {
        // A trailing language word the post never listed is part of the
        // title, not a suffix.
        assertEquals("Salaar Hindi", UserWords.stripTitleSuffixes("Salaar Hindi", 2023, "movie", listOf("Telugu")))
        // A year that is not the post's year stays.
        assertEquals("Jawan (2022)", UserWords.stripTitleSuffixes("Jawan (2022)", 2023, "movie", emptyList()))
        // …unless the post carries no year at all, when any year goes.
        assertEquals("Jawan", UserWords.stripTitleSuffixes("Jawan (2023)", null, "movie", emptyList()))
    }

    @Test
    fun stripTitleSuffixesStripsTheSeriesTypeWord() {
        assertEquals("Breaking Bad", UserWords.stripTitleSuffixes("Breaking Bad TV Series", null, "series", emptyList()))
        assertEquals("Dark", UserWords.stripTitleSuffixes("Dark Series", null, "tv_series", emptyList()))
    }

    @Test
    fun stripTitleSuffixesStripsSeasonSuffixes() {
        // Home review (Nov 2026): the hero and Detail title must never
        // carry the season the meta line already states.
        assertEquals("It's Showtime", UserWords.stripTitleSuffixes("It's Showtime (2026) S1-S2", 2026, "series", emptyList()))
        assertEquals("Show", UserWords.stripTitleSuffixes("Show S01–S03", null, "series", emptyList()))
        assertEquals("Show", UserWords.stripTitleSuffixes("Show S1", null, "series", emptyList()))
        assertEquals("Show", UserWords.stripTitleSuffixes("Show Season 2", null, "series", emptyList()))
        // A title that IS a season token keeps it — the strip never
        // empties the title.
        assertEquals("S1", UserWords.stripTitleSuffixes("S1", null, "series", emptyList()))
    }
}
