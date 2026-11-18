package com.raddstream.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Dev-cleanup (Nov 2026, E1): the service-bind failure shows one fixed sentence.
 * The sentence is a ViewModel literal, not a player_* resource — the ChromeCopy
 * ratchet requires every player_* string to be painted from PlayerScreen.kt,
 * and this card is set in the ViewModel before the screen ever sees it.
 */
class PlayerViewModelErrorMessageTest {

    @Test
    fun `the service bind failure is one fixed sentence`() {
        assertEquals("Couldn't open the player. Try again.", PLAYER_SERVICE_UNAVAILABLE_MESSAGE)
    }

    @Test
    fun `the sentence carries no exception text`() {
        assertFalse(PLAYER_SERVICE_UNAVAILABLE_MESSAGE.contains("Playback service unavailable"))
        assertFalse(PLAYER_SERVICE_UNAVAILABLE_MESSAGE.contains(": "))
    }
}
