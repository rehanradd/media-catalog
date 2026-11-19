package com.raddstream.app

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feel-instant S7 — Startup diet: the deferred half of `Application.onCreate`.
 *
 * The first frame is the budget. Everything the viewer must see is built
 * before it; everything else waits until after it. This class owns the
 * "after it" half: a list of start calls that run exactly once, in order,
 * the first time [start] is called — whether the call comes from
 * `MainActivity`'s first-frame signal or from the `Application`'s own
 * fallback timer (a process that never draws a frame still starts them).
 *
 * Exactly-once is the whole contract: the first-frame signal and the
 * fallback race, and whichever wins, the second call is a no-op — so a
 * component is never started twice, and a start that already happened is
 * never repeated by a later trigger.
 *
 * Fault isolation is the second half: a step that throws must not take the
 * rest down with it. `onCreate` used to call these starts in sequence with
 * no guard, so one throwing start could crash the launch and starve the
 * steps after it. Here a failing step is reported through [onStepFailure]
 * and the next one still runs — a cache engine that cannot start must not
 * also kill the LAN socket, the membership read, or the download resume.
 *
 * [onStepFailure] defaults to a logcat line; it is a constructor parameter
 * (not a direct `Log` call) so the unit tests stay plain JVM: `Log.w` is a
 * native stub on the JVM test classpath and throws `UnsatisfiedLinkError`
 * there (proven by Android CI 44426842277), so a test that exercises the
 * failure path must be able to pass its own handler.
 *
 * Unit-tested (see `DeferredStartupTest`): exactly-once, order, and the
 * throw-does-not-stop-the-rest rule. The wiring — who calls [start] and
 * when — lives in `RaddStreamApplication` and `MainActivity` and is
 * verified by CI compile + lint, not by a unit test: it needs a real
 * activity and a real first frame.
 */
class DeferredStartup(
    private val steps: List<() -> Unit>,
    private val onStepFailure: (Throwable) -> Unit = { error ->
        Log.w(TAG, "deferred startup step failed, continuing: ${error.message}")
    },
) {
    private val started = AtomicBoolean(false)

    /** Runs every step once, in order; later calls are no-ops. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        for (step in steps) {
            runCatching { step() }.onFailure { error -> onStepFailure(error) }
        }
    }

    companion object {
        private const val TAG = "DeferredStartup"
    }
}
