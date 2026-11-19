package com.raddstream.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feel-instant S7: the deferred-startup contract — exactly-once, in order,
 * and a throwing step never stops the steps after it. Plain JVM test: no
 * Application, no Robolectric, just the guard.
 */
class DeferredStartupTest {

    @Test
    fun `runs every step once, in order`() {
        val seen = mutableListOf<String>()
        val deferred = DeferredStartup(
            listOf(
                { seen += "cache" },
                { seen += "lan" },
                { seen += "membership" },
            ),
        )

        deferred.start()

        assertEquals(listOf("cache", "lan", "membership"), seen)
    }

    @Test
    fun `second start is a no-op`() {
        var runs = 0
        val deferred = DeferredStartup(listOf({ runs++ }))

        deferred.start()
        deferred.start()
        deferred.start()

        assertEquals(1, runs)
    }

    @Test
    fun `a throwing step does not stop the steps after it`() {
        val seen = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val deferred = DeferredStartup(
            listOf(
                { seen += "before" },
                { throw IllegalStateException("boom") },
                { seen += "after" },
            ),
            // The handler is injected (not android.util.Log): Log.w is a
            // native stub on the JVM test classpath and throws
            // UnsatisfiedLinkError there (proven by CI 44426842277).
            onStepFailure = { failures += it },
        )

        // Must not throw: the failure is reported, the rest still runs.
        deferred.start()

        assertEquals(listOf("before", "after"), seen)
        assertEquals(listOf("boom"), failures.map { it.message })
    }

    @Test
    fun `a throwing step still counts as started`() {
        var runs = 0
        val failures = mutableListOf<Throwable>()
        val deferred = DeferredStartup(
            listOf(
                { throw IllegalStateException("boom") },
                { runs++ },
            ),
            onStepFailure = { failures += it },
        )

        deferred.start()
        deferred.start()

        // The second start is a no-op even though the first one failed a
        // step: "exactly once" is about the attempt, not about success.
        assertEquals(1, runs)
        assertEquals(listOf("boom"), failures.map { it.message })
    }

    @Test
    fun `empty step list starts cleanly`() {
        val deferred = DeferredStartup(emptyList())

        deferred.start()

        assertTrue(true)
    }
}
