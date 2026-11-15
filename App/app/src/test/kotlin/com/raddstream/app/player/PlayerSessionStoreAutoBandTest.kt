package com.raddstream.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hate-audit H9: when Auto moves the band mid-title the viewer hears about
 * it once, in the settled banner's own 5s transient. A hand-picked switch
 * never calls [PlayerSessionStore.announceQualityChange], so the banner is
 * Auto's voice only.
 */
class PlayerSessionStoreAutoBandTest {

    @Test
    fun `announcing a quality change writes the settled banner`() {
        val store = PlayerSessionStore()
        store.announceQualityChange("720p")

        assertEquals("Quality changed to 720p", store.settledBanner.value)
    }

    @Test
    fun `clearing the settled banner returns null`() {
        val store = PlayerSessionStore()
        store.announceQualityChange("720p")
        store.clearSettledBanner()

        assertNull(store.settledBanner.value)
    }

    @Test
    fun `no banner at rest`() {
        assertNull(PlayerSessionStore().settledBanner.value)
    }
}
