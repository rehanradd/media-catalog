package com.raddstream.app.network.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M11 (home review, Nov 2026): the NEW badge's year rule. Same plausibility
 * bound as [displayYearLabel] — a mis-parsed 2027 or 20215 can never earn
 * the badge — and recent means this year or last. Pure JVM: no Robolectric,
 * no Android runtime.
 */
class IsNewReleaseYearTest {

    @Test
    fun thisYearAndLastYearAreNew() {
        assertTrue(isNewReleaseYear(2026, 2026))
        assertTrue(isNewReleaseYear(2025, 2026))
    }

    @Test
    fun twoYearsAgoIsNotNew() {
        assertFalse(isNewReleaseYear(2024, 2026))
        assertFalse(isNewReleaseYear(2023, 2026))
    }

    @Test
    fun nextYearsFilmIsNotNewYet() {
        assertFalse(isNewReleaseYear(2027, 2026))
    }

    @Test
    fun impossibleYearsNeverEarnTheBadge() {
        assertFalse(isNewReleaseYear(2028, 2026))
        assertFalse(isNewReleaseYear(20215, 2026))
        assertFalse(isNewReleaseYear(1899, 2026))
        assertFalse(isNewReleaseYear(null, 2026))
    }
}
