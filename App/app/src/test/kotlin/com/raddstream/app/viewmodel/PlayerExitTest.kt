package com.raddstream.app.viewmodel

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Video-audit #25 (27 Nov 2026, CRITICAL): the crash that followed leaving
 * the player — live TV in video 1, a local video in video 2 — is the leaked
 * player. Releasing the MediaController does not stop the
 * MediaSessionService's ExoPlayer, so audio kept playing with no UI until
 * the app died ("RADD Stream has stopped" over Search, ~2 min later). The
 * fix is [PlayerViewModel.onCleared] stopping the session player before
 * releasing the controller — the release fix (issue 2) IS the crash fix.
 *
 * The ViewModel's twelve injected deps have no fakes anywhere in the repo
 * and its controller binds a real service, so no JVM test can play a title
 * and pop the screen for real. What a JVM test CAN pin is the wiring the
 * crash depends on: the stop must exist, it must run before the release,
 * the resume position must be banked before the stop resets it, and a cast
 * receiver must never be stopped. If any of those lines moves or is
 * deleted, this test goes red — that is the regression gate. End-to-end
 * audio-stop still needs a device run; this test is the guard that the fix
 * stays in the code.
 */
class PlayerExitTest {

    @Test
    fun `leaving the player stops the session before releasing it`() {
        val cleared = onClearedBody()
        val pauseAt = cleared.indexOf("controller?.pause()")
        val stopAt = cleared.indexOf("controller?.stop()")
        val releaseAt = cleared.indexOf("controller?.release()")
        assertTrue("onCleared must pause the session player", pauseAt >= 0)
        assertTrue("onCleared must stop the session player", stopAt >= 0)
        assertTrue("onCleared must release the controller", releaseAt >= 0)
        assertTrue(
            "the stop must run before the release — releasing the controller alone " +
                "leaves the service player playing with no UI",
            stopAt < releaseAt,
        )
        assertTrue("the pause must run before the stop", pauseAt < stopAt)
    }

    @Test
    fun `a casting exit never stops the TV`() {
        val cleared = onClearedBody()
        val guardAt = cleared.indexOf("if (!_ui.value.isCasting)")
        val stopAt = cleared.indexOf("controller?.stop()")
        assertTrue(
            "a casting exit must be guarded — MX #9 says the TV keeps playing " +
                "after the phone app dies",
            guardAt >= 0 && guardAt < stopAt,
        )
        assertTrue(
            "the stop must sit inside the cast guard, not beside it",
            cleared.substring(guardAt, cleared.indexOf("controller?.release()"))
                .contains("controller?.stop()"),
        )
    }

    @Test
    fun `the resume position is banked before the stop resets it`() {
        val cleared = onClearedBody()
        val resumeAt = cleared.indexOf("rememberResume()")
        val stopAt = cleared.indexOf("controller?.stop()")
        assertTrue("onCleared must bank the resume position", resumeAt >= 0)
        assertTrue(
            "the position must be banked before the stop — Player.stop() resets " +
                "the playhead to zero, so a stop first would resume at 0:00",
            resumeAt < stopAt,
        )
    }

    @Test
    fun `exactly one release path exists, and it is the guarded one`() {
        val source = File(VM_PATH).readText()
        assertEquals(
            "a second controller release anywhere in the file would be a second " +
                "exit path that skips the stop",
            1,
            Regex("""controller\?\.release\(\)""").findAll(source).count(),
        )
    }

    /** The order check needs its own guard: prove it sees a reversed body as broken. */
    @Test
    fun `the order check refuses a stop after the release`() {
        val reversed = "override fun onCleared() {\n" +
            "    controller?.release()\n" +
            "    controller?.stop()\n" +
            "}"
        assertTrue(
            "a body that releases before stopping must fail the order the real " +
                "body passes",
            reversed.indexOf("controller?.stop()") > reversed.indexOf("controller?.release()"),
        )
        val real = onClearedBody()
        assertTrue(
            "and the real body must pass that same order",
            real.indexOf("controller?.stop()") < real.indexOf("controller?.release()"),
        )
    }

    /**
     * The `onCleared` body, sliced between its own markers — a whole-file
     * `contains` would be satisfied by a stop anywhere else in the file, and
     * a stop in the wrong function is the regression this test exists to catch.
     */
    private fun onClearedBody(): String {
        val source = File(VM_PATH).readText()
        val start = source.indexOf("override fun onCleared()")
        assertTrue("onCleared must exist in $VM_PATH", start >= 0)
        val end = source.indexOf("super.onCleared()", startIndex = start)
        assertTrue("onCleared must call super.onCleared()", end > start)
        return source.substring(start, end)
    }

    private companion object {
        const val VM_PATH = "src/main/kotlin/com/raddstream/app/viewmodel/PlayerViewModel.kt"
    }
}
