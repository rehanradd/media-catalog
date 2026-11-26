package com.raddstream.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-1: the tab set is locked in code — Home · Search · Downloads ·
 * Profile, in that order — and the operator `tabs` key is gone entirely.
 * [PanelTabs.resolve] takes no remote list any more: there is nothing
 * left for an operator to reorder or resurrect. Only the Search feature
 * flag still removes a tab.
 */
class PanelTabsTest {

    private val lockedFour = listOf("home", "search", "downloads", "profile")

    @Test
    fun `search on keeps the locked four`() {
        assertEquals(
            lockedFour,
            PanelTabs.resolve(searchEnabled = true),
        )
    }

    @Test
    fun `search drops out when its feature flag is off`() {
        assertEquals(
            listOf("home", "downloads", "profile"),
            PanelTabs.resolve(searchEnabled = false),
        )
    }

    @Test
    fun `home stays first in the locked set`() {
        assertEquals("home", PanelTabs.resolve(searchEnabled = true).first())
        assertEquals("home", PanelTabs.resolve(searchEnabled = false).first())
    }

    @Test
    fun `the locked set never changes between calls`() {
        // No input varies any more — the same call always answers the
        // same set, so no operator value can ever move the bar.
        assertEquals(
            PanelTabs.resolve(searchEnabled = true),
            PanelTabs.resolve(searchEnabled = true),
        )
    }
}
