package com.raddstream.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Popup cleanup (26 Nov 2026): the Setup gate is gone. A dead, slow, or
 * never-probed shipped default no longer routes anywhere but Home (or
 * Region, when the region question is still unanswered) — Home's own
 * catalog load retries in the background, and if the server stays down
 * Home's ErrorState offers Retry and "Change server address". There is
 * no server input to this rule any more, so the dead-server first run is
 * a routing test, not a probe test.
 */
class StartupRoutingTest {

    @Test
    fun `fresh install whose shipped server did not answer goes to Region when unanswered`() {
        assertEquals(
            StartDestination.Region,
            resolveStartDestination(regionUnanswered = true),
        )
    }

    @Test
    fun `fresh install whose shipped server did not answer goes to Home when region answered`() {
        assertEquals(
            StartDestination.Home,
            resolveStartDestination(regionUnanswered = false),
        )
    }

    @Test
    fun `fresh install whose shipped server was never probed goes to Home when region answered`() {
        assertEquals(
            StartDestination.Home,
            resolveStartDestination(regionUnanswered = false),
        )
    }

    @Test
    fun `install with no server anywhere goes to Home when region answered`() {
        assertEquals(
            StartDestination.Home,
            resolveStartDestination(regionUnanswered = false),
        )
    }

    @Test
    fun `install with no server anywhere goes to Region when unanswered`() {
        assertEquals(
            StartDestination.Region,
            resolveStartDestination(regionUnanswered = true),
        )
    }

    @Test
    fun `returning install goes to Region when unanswered`() {
        assertEquals(
            StartDestination.Region,
            resolveStartDestination(regionUnanswered = true),
        )
    }

    @Test
    fun `returning install goes to Home when region answered`() {
        assertEquals(
            StartDestination.Home,
            resolveStartDestination(regionUnanswered = false),
        )
    }
}
