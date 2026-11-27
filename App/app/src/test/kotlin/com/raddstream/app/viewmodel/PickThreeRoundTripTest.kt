package com.raddstream.app.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.network.models.Post
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pick 3 (video audit #11, Nov 2026): the card appeared mid-session —
 * only after the viewer came back from Live TV — because the old B5 gate
 * waited for the first browse or play. The gate is now the onboarding
 * window: the card shows on the first Home visit, and leaving Home closes
 * the window for good. These tests drive the REAL round-trip — a real
 * [ServerSettingsRepository] on a test DataStore, the write fired exactly
 * the way MainActivity's onLeaveHome fires it, the collect done exactly
 * the way HomeViewModel.observeOnboardingSeen does it — and assert the
 * real [HomeUiState.showPickThree] opens at onboarding and closes when
 * the viewer leaves Home.
 *
 * If the round-trip test fails, the failure names the bug: the write
 * never persisted, the flow never emitted, or the state transition never
 * applied. If it passes, the round-trip is exonerated and the card's
 * absence or presence on device is not a storage problem.
 */
@RunWith(RobolectricTestRunner::class)
class PickThreeRoundTripTest {

    private fun post(id: Int) = Post(id = id, title = "Title $id")

    /** Nine titles: the hero takes the first six, the card offers the next three. */
    private fun fullRail() = HomeSection(
        key = "recent",
        title = "Recently added",
        type = null,
        items = (1..9).map { post(it) },
    )

    private fun settings(): ServerSettingsRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ServerSettingsRepository(context)
    }

    /**
     * The round-trip, live: the collector runs the whole time (the Home
     * already on screen when the viewer leaves for Live TV), the write
     * fires mid-visit (MainActivity's onLeaveHome), and the gate must
     * close on the emission — no restart, no new ViewModel. The card
     * shows at onboarding first: a fresh install with loaded rails sees
     * it before any browse or play.
     */
    @Test
    fun `leaving Home write reaches Home and closes the card`() = runBlocking {
        val settings = settings()

        // Fresh install: onboarding open, rails loaded, card showing.
        // A previous test in this process may have left the flag set, so
        // clear it first — the flag is the test's own precondition, and
        // the shared test DataStore does not reset between tests.
        settings.setOnboardingSeen(false)
        assertFalse(settings.onboardingSeen.first())
        val state = MutableStateFlow(HomeUiState(sections = listOf(fullRail())))
        assertTrue(state.value.showPickThree)

        // HomeViewModel.observeOnboardingSeen, verbatim in shape: collect
        // the persisted flag into the state's copy.
        // MainActivity's onLeaveHome, verbatim in shape: fire-and-forget,
        // errors swallowed — leaving Home closes onboarding.
        //
        // Both are held as jobs and cancelled in the finally: the collector
        // never returns on its own (a DataStore flow is endless), and
        // runBlocking joins its children before it returns — so an
        // uncancelled collector wedges the test thread after the assertions
        // pass, and the whole suite hangs with no failure. (That is what
        // the first CI run of the old browse round-trip test did: 18
        // minutes in the Gradle step, run 43770475581, cancelled.)
        val collector = launch {
            settings.onboardingSeen.collect { seen ->
                state.update { it.copy(onboardingSeen = seen) }
            }
        }
        val writer = launch {
            runCatching { settings.setOnboardingSeen(true) }
        }
        try {
            withTimeout(10_000) { state.first { !it.showPickThree } }
        } finally {
            collector.cancel()
            writer.cancel()
        }

        assertFalse(state.value.showPickThree)
        assertEquals(listOf(7, 8, 9), state.value.pickThreeCandidates.map { it.id })
        // A fresh collection after the write still sees the flag — the
        // persisted value, not a live emission the collector happened to
        // catch.
        assertTrue(settings.onboardingSeen.first())
    }

    /**
     * The round-trip, late: onboarding already ended (flag persisted),
     * and only then does Home start collecting — the audit's video shape,
     * where the viewer returns to Home after Live TV and the
     * HomeViewModel collects the flag for the first time. The first
     * emission must already be true; the card must never appear, however
     * full the rails are.
     */
    @Test
    fun `a Home collecting after leaving never shows the card`() = runBlocking {
        val settings = settings()

        // The viewer left Home before this Home existed.
        settings.setOnboardingSeen(true)
        assertTrue(settings.onboardingSeen.first())

        val state = MutableStateFlow(HomeUiState(sections = listOf(fullRail())))
        // The state's initial value predates the collect — onboardingSeen
        // defaults false there, so the card reads open until the first
        // emission lands. Assert only after the collector applies the
        // persisted true.
        val collector = launch {
            settings.onboardingSeen.collect { seen ->
                state.update { it.copy(onboardingSeen = seen) }
            }
        }
        try {
            withTimeout(10_000) { state.first { it.onboardingSeen } }
        } finally {
            collector.cancel()
        }

        assertFalse(state.value.showPickThree)
        assertEquals(listOf(7, 8, 9), state.value.pickThreeCandidates.map { it.id })
        // Leave the flag set: the next test clears it as its precondition.
    }
}
