package com.raddstream.app.network.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `WireText.listOf` is the only reader for the three v1 columns that are TEXT but hold a
 * list, so every presentation surface (genres chips, language pills, backdrop art) stands
 * or falls on it. These cases are the wire shapes verified against the live ledger, plus
 * the one that a self-referencing `listOf` call used to turn into a StackOverflowError on
 * any value that was not a JSON array.
 */
class WireTextTest {

    @Test
    fun readsCsvColumns() {
        assertEquals(
            listOf("Action", "Adventure", "Drama", "Fantasy"),
            WireText.listOf("Action, Adventure, Drama, Fantasy"),
        )
        assertEquals(listOf("Drama"), WireText.listOf("Drama"))
    }

    @Test
    fun readsTheOtherSeparatorsTheScrapersLeaveBehind() {
        assertEquals(listOf("Hindi", "English", "Japanese"), WireText.listOf("Hindi + English + Japanese"))
        assertEquals(listOf("Tamil", "Telugu"), WireText.listOf("Tamil / Telugu"))
        assertEquals(listOf("A", "B"), WireText.listOf("A;B"))
        assertEquals(listOf("A", "B"), WireText.listOf("A|B"))
    }

    @Test
    fun readsJsonArrayStringsAndBareUrls() {
        assertEquals(
            listOf("https://a.jpg", "https://b.jpg"),
            WireText.listOf("[\"https://a.jpg\", \"https://b.jpg\"]"),
        )
        assertEquals(listOf("https://a.jpg"), WireText.listOf("https://a.jpg"))
        // A half-written array still has to read as items, not as one broken string.
        assertEquals(listOf("Action", "Drama"), WireText.listOf("[Action, Drama"))
        assertEquals(listOf("Sci-Fi", "Noir"), WireText.listOf("Sci-Fi / Noir"))
    }

    @Test
    fun answersAnEmptyListForNothingAndForMalformedJson() {
        assertTrue(WireText.listOf(null).isEmpty())
        assertTrue(WireText.listOf("").isEmpty())
        assertTrue(WireText.listOf("   ").isEmpty())
        assertTrue(WireText.listOf("[]").isEmpty())
        assertTrue(WireText.listOf("[null]").isEmpty())
        assertEquals(listOf("a", "b"), WireText.listOf("[\"a\", \"\", \"b\"]"))
    }

    @Test
    fun dropsBlanksAndDuplicatesFromAnyShape() {
        assertEquals(listOf("English", "Hindi"), WireText.listOf("English,,  , Hindi, English"))
    }

    @Test
    fun splitOnAnySplitsEveryGivenSeparatorAtOnce() {
        assertEquals(listOf("a", "b", "c", "d"), WireText.splitOnAny("a,b c;d", ",", " ", ";"))
        assertTrue(WireText.splitOnAny(null, ",").isEmpty())
        assertTrue(WireText.splitOnAny("  ", ",").isEmpty())
    }

    // "Unknown" labels (Nov 2026): the catalog writes the literal words
    // "Unknown" and "N/A" where a value was never recorded — a missing
    // value, not a value — so every render site drops them through these
    // predicates instead of re-deciding what counts as unknown.

    @Test
    fun placeholderIsBlankUnknownOrNA() {
        assertTrue(WireText.isPlaceholder(null))
        assertTrue(WireText.isPlaceholder(""))
        assertTrue(WireText.isPlaceholder("   "))
        assertTrue(WireText.isPlaceholder("Unknown"))
        assertTrue(WireText.isPlaceholder("unknown"))
        assertTrue(WireText.isPlaceholder(" UNKNOWN "))
        assertTrue(WireText.isPlaceholder("N/A"))
        assertTrue(WireText.isPlaceholder("n/a"))
        assertTrue(WireText.isKnownValue("Hindi"))
        assertTrue(WireText.isKnownValue("Action"))
        assertFalse(WireText.isKnownValue("Unknown"))
        assertFalse(WireText.isKnownValue("N/A"))
    }

    @Test
    fun knownLanguageAndGenreShareThePlaceholderRule() {
        assertTrue(WireText.isKnownLanguage("Hindi"))
        assertFalse(WireText.isKnownLanguage("Unknown"))
        assertFalse(WireText.isKnownLanguage("N/A"))
        assertFalse(WireText.isKnownLanguage("  "))
        assertTrue(WireText.isKnownGenre("Action"))
        assertFalse(WireText.isKnownGenre("Unknown"))
        assertFalse(WireText.isKnownGenre("N/A"))
    }

    @Test
    fun multiIsAMultiAudioMarkerNotALanguage() {
        // Dev-cleanup (Nov 2026): the server writes language "Multi" on
        // multi-audio works (One Piece post 1459) — it painted as
        // "Series · Multi · 1 season" on the detail meta line. "Multi" is
        // not a language name, so it is dropped wherever a language is shown.
        // A genre may still be called anything — only languages drop it.
        assertFalse(WireText.isKnownLanguage("Multi"))
        assertFalse(WireText.isKnownLanguage("multi"))
        assertFalse(WireText.isKnownLanguage(" MULTI "))
        assertTrue(WireText.isKnownGenre("Multi"))
    }

    @Test
    fun knownPrintLabelAlsoDropsTheServersUnknownPrintFallback() {
        // catalog_api.py:1043 labels a print with no describable parts
        // "Unknown print" — the episode title and the quality pill must
        // never paint it.
        assertFalse(WireText.isKnownPrintLabel("Unknown print"))
        assertFalse(WireText.isKnownPrintLabel("unknown print"))
        assertFalse(WireText.isKnownPrintLabel(" Unknown Print "))
        assertFalse(WireText.isKnownPrintLabel("Unknown"))
        assertFalse(WireText.isKnownPrintLabel(null))
        assertTrue(WireText.isKnownPrintLabel("One Piece S01 E01"))
        assertTrue(WireText.isKnownPrintLabel("1080p"))
    }
}
