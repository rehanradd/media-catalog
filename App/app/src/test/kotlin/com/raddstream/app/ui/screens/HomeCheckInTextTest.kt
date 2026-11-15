package com.raddstream.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The check-in card's wording, pinned (Session 123, HO-6/HO-8): the day
 * number is the server's claimed count, never the device's open count, and
 * the claim button never names an amount — the sheet shows the streak,
 * not the price, so there is nothing to keep in step with the ladder.
 */
class HomeCheckInTextTest {

    @Test
    fun `signed in with a server streak shows the server day`() {
        assertEquals(
            "Day 2 of 7 — keep the streak going.",
            checkInDayLine(signedIn = true, serverStreak = 2, streakCount = 9, targetDays = 7),
        )
    }

    @Test
    fun `signed in before the overview answers prints no day number`() {
        assertEquals(
            "Check in to keep your streak going.",
            checkInDayLine(signedIn = true, serverStreak = null, streakCount = 9, targetDays = 7),
        )
    }

    @Test
    fun `a guest reads the honest local wording`() {
        assertEquals(
            "You've opened the app 9 days in a row.",
            checkInDayLine(signedIn = false, serverStreak = null, streakCount = 9, targetDays = 7),
        )
    }

    @Test
    fun `a streak past the ladder reads complete`() {
        assertEquals(
            "Day 7 of 7 — keep the streak going.",
            checkInDayLine(signedIn = true, serverStreak = 9, streakCount = 9, targetDays = 7),
        )
    }

    @Test
    fun `the claim button never names an amount`() {
        assertEquals("Check in", claimButtonLabel(isClaiming = false))
    }

    @Test
    fun `claiming overrides the label`() {
        assertEquals("Claiming…", claimButtonLabel(isClaiming = true))
    }
}
