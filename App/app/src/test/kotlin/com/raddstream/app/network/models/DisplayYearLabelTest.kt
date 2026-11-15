package com.raddstream.app.network.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M3 (home review, Nov 2026): the year a tile or header prints. Measured on
 * the live catalog: year >= 2027 is 53 rows, year < 1900 is 17, one 5-digit
 * year (20215) — a site mis-parse, e.g. "The Gentlemen (Season 1-2)" filed
 * as 2027. An impossible year is hidden instead of printed.
 *
 * Pure JVM: no Robolectric, no Android runtime.
 */
class DisplayYearLabelTest {

    @Test
    fun showsAnOrdinaryYear() {
        assertEquals("2024", displayYearLabel(2024, 2026))
        assertEquals("1900", displayYearLabel(1900, 2026))
    }

    @Test
    fun allowsNextYearsFilms() {
        assertEquals("2027", displayYearLabel(2027, 2026))
    }

    @Test
    fun hidesYearsBeyondNextYear() {
        assertNull(displayYearLabel(2028, 2026))
        assertNull(displayYearLabel(2099, 2026))
        assertNull(displayYearLabel(20215, 2026))
    }

    @Test
    fun hidesYearsBeforeCinema() {
        assertNull(displayYearLabel(1899, 2026))
        assertNull(displayYearLabel(0, 2026))
    }

    @Test
    fun hidesAMissingYear() {
        assertNull(displayYearLabel(null, 2026))
    }
}
