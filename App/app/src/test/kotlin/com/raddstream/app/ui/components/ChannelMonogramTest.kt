package com.raddstream.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A4-F1: the logo plate is a wide, short surface — the full channel name
 * plus a 48dp mark never fit it, so the Robo capture showed the mark with
 * no text at all. The plate paints a two-letter monogram beside the mark
 * instead. The derivation is pure, so it is pinned here without Compose.
 */
class ChannelMonogramTest {

    @Test
    fun twoWordsGiveFirstLetters() {
        assertEquals("DP", channelMonogram("Discover Pakistan"))
        assertEquals("AT", channelMonogram("Aaj Tak HD"))
    }

    @Test
    fun oneWordGivesFirstTwoLetters() {
        assertEquals("AR", channelMonogram("ARY"))
        assertEquals("DU", channelMonogram("Dunya"))
    }

    @Test
    fun singleLetterNameGivesThatLetter() {
        assertEquals("A", channelMonogram("A"))
    }

    @Test
    fun extraWhitespaceIsIgnored() {
        assertEquals("DP", channelMonogram("  Discover   Pakistan  "))
    }

    @Test
    fun punctuationOnlyGivesNull() {
        assertNull(channelMonogram("???"))
        assertNull(channelMonogram(" - "))
    }

    @Test
    fun blankOrNullGivesNull() {
        assertNull(channelMonogram(null))
        assertNull(channelMonogram(""))
        assertNull(channelMonogram("   "))
    }

    @Test
    fun nonAlphanumericWordsAreSkipped() {
        assertEquals("DP", channelMonogram("Discover * Pakistan"))
    }
}
