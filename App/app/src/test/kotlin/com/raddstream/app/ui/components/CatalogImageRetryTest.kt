package com.raddstream.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Samsung RTL (v1.1.2, Issue 5): Salaar's poster URL is alive, yet the
 * phone showed the dark "SA" fallback — one transient fetch failure, and
 * Coil's single attempt left the tile failed for the whole visit. A failed
 * load now re-requests, bounded, and the fallback is tappable to try again.
 * The bound is what this pins: the first load plus two re-requests, then
 * no more automatic attempts — a permanently dead URL must not
 * re-request forever, and a tap (which is deliberate) is never bounded.
 */
class CatalogImageRetryTest {

    @Test
    fun retriesTheFirstTwoFailures() {
        assertTrue(shouldAutoRetryImage(attempt = 0))
        assertTrue(shouldAutoRetryImage(attempt = 1))
    }

    @Test
    fun stopsAutomaticRetriesAfterTheBound() {
        assertFalse(shouldAutoRetryImage(attempt = 2))
        assertFalse(shouldAutoRetryImage(attempt = 3))
        assertFalse(shouldAutoRetryImage(attempt = 99))
    }

    @Test
    fun respectsAnExplicitBound() {
        assertTrue(shouldAutoRetryImage(attempt = 0, maxAutoRetries = 1))
        assertFalse(shouldAutoRetryImage(attempt = 1, maxAutoRetries = 1))
        assertFalse(shouldAutoRetryImage(attempt = 0, maxAutoRetries = 0))
    }

    // ── Video audit issue 22 (Nov 2026): the delayed retry ────────────

    @Test
    fun delayedRetryFiresOnce() {
        assertTrue(shouldDelayedRetryImage(delayedAttempts = 0))
    }

    @Test
    fun delayedRetryStopsAfterTheOne() {
        assertFalse(shouldDelayedRetryImage(delayedAttempts = 1))
        assertFalse(shouldDelayedRetryImage(delayedAttempts = 99))
    }
}
