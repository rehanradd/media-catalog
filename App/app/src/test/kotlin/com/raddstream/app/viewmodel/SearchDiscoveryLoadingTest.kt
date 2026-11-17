package com.raddstream.app.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search review (Nov 2026), fix 4: the discovery page is loading until
 * BOTH first loads have settled — a success and a failure both settle, so
 * a dead route shows the hint instead of spinning forever. These tests pin
 * the rule itself, not the ViewModel wiring: the ViewModel's
 * markDiscoverySettled feeds it one settled section at a time.
 */
class SearchDiscoveryLoadingTest {

    @Test
    fun `discovery starts loading`() {
        assertTrue(SearchUiState().discoveryLoading)
    }

    @Test
    fun `loading until both sections settle`() {
        assertTrue(discoveryLoading(trendingSettled = false, recommendedSettled = false))
    }

    @Test
    fun `one settled section still loading`() {
        assertTrue(discoveryLoading(trendingSettled = true, recommendedSettled = false))
        assertTrue(discoveryLoading(trendingSettled = false, recommendedSettled = true))
    }

    @Test
    fun `both settled stops loading`() {
        assertFalse(discoveryLoading(trendingSettled = true, recommendedSettled = true))
    }
}
