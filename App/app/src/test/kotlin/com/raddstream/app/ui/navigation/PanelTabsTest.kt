package com.raddstream.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0-1: the tab set is locked in code — Home · Search · Downloads ·
 * Profile, in that order. The operator `tabs` key can no longer reorder
 * the bar or resurrect a removed tab, so every remote list resolves to
 * the same locked set; only the Search feature flag still removes a tab.
 */
class PanelTabsTest {

    private val lockedFour = listOf("home", "search", "downloads", "profile")

    @Test
    fun `empty remote list falls back to the locked four`() {
        assertEquals(
            lockedFour,
            PanelTabs.resolve(emptyList(), searchEnabled = true),
        )
    }

    @Test
    fun `operator list naming removed tabs changes nothing`() {
        // The old tab set — settings, live_tv, my_list — is gone. An
        // operator list still naming them must not resurrect any of them.
        assertEquals(
            lockedFour,
            PanelTabs.resolve(listOf("live_tv", "settings", "my_list"), searchEnabled = true),
        )
        assertEquals(
            lockedFour,
            PanelTabs.resolve(listOf("settings"), searchEnabled = true),
        )
    }

    @Test
    fun `operator order cannot reorder the locked set`() {
        assertEquals(
            lockedFour,
            PanelTabs.resolve(listOf("profile", "downloads", "search", "home"), searchEnabled = true),
        )
        assertEquals(
            lockedFour,
            PanelTabs.resolve(listOf("downloads", "home"), searchEnabled = true),
        )
    }

    @Test
    fun `duplicates and casing in the operator list change nothing`() {
        assertEquals(
            lockedFour,
            PanelTabs.resolve(listOf(" Home ", "SEARCH", "home", "Downloads"), searchEnabled = true),
        )
    }

    @Test
    fun `all-unknown remote list falls back to the locked four`() {
        assertEquals(
            lockedFour,
            PanelTabs.resolve(listOf("nope", "alsono"), searchEnabled = true),
        )
    }

    @Test
    fun `search drops out when its feature flag is off`() {
        assertEquals(
            listOf("home", "downloads", "profile"),
            PanelTabs.resolve(emptyList(), searchEnabled = false),
        )
        assertEquals(
            listOf("home", "downloads", "profile"),
            PanelTabs.resolve(listOf("search", "settings", "live_tv"), searchEnabled = false),
        )
    }

    @Test
    fun `home stays first in the locked set`() {
        assertEquals("home", PanelTabs.resolve(emptyList(), searchEnabled = true).first())
        assertEquals("home", PanelTabs.resolve(emptyList(), searchEnabled = false).first())
    }
}
