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
 * Pick 3 (Nov 2026): the card never appeared in Robo crawls #8–#11, and a
 * read-only investigation exonerated every gate in code — the candidate
 * scan, the gate formula, the stay-fix latch, the screen wiring, and the
 * DataStore keys are all correct by reading. The one link nobody had
 * proven end to end is this round-trip: the write from Detail (or Player)
 * landing in DataStore, and Home's collector picking it up so the gate
 * opens. These tests drive the REAL round-trip — a real
 * [ServerSettingsRepository] on a test DataStore, the write fired exactly
 * the way DetailViewModel.init fires it, the collect done exactly the way
 * HomeViewModel.observeFirstBrowseDone does it — and assert the real
 * [HomeUiState.showPickThree] turns true with loaded sections.
 *
 * If the round-trip test fails, the failure names the bug: the write
 * never persisted, the flow never emitted, or the state transition never
 * applied. If it passes, the round-trip is exonerated and the card's
 * absence on device is not a storage problem.
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
     * already on screen when the viewer opens a Detail), the write fires
     * mid-visit (DetailViewModel.init), and the gate must open on the
     * emission — no restart, no new ViewModel.
     */
    @Test
    fun `first browse write reaches Home and opens the card`() = runBlocking {
        val settings = settings()

        // Fresh install: nothing browsed yet, rails loaded, card gated.
        assertFalse(settings.firstBrowseDone.first())
        val state = MutableStateFlow(HomeUiState(sections = listOf(fullRail())))
        assertFalse(state.value.showPickThree)

        // HomeViewModel.observeFirstBrowseDone, verbatim in shape: collect
        // the persisted flag into the state's copy.
        // DetailViewModel.init, verbatim in shape: fire-and-forget, errors
        // swallowed — opening any title is the first browse.
        //
        // Both are held as jobs and cancelled in the finally: the collector
        // never returns on its own (a DataStore flow is endless), and
        // runBlocking joins its children before it returns — so an
        // uncancelled collector wedges the test thread after the assertions
        // pass, and the whole suite hangs with no failure. (That is what
        // the first CI run of this test did: 18 minutes in the Gradle step,
        // run 43770475581, cancelled.)
        val collector = launch {
            settings.firstBrowseDone.collect { done ->
                state.update { it.copy(firstBrowseDone = done) }
            }
        }
        val writer = launch {
            runCatching { settings.setFirstBrowseDone(true) }
        }
        try {
            withTimeout(10_000) { state.first { it.showPickThree } }
        } finally {
            collector.cancel()
            writer.cancel()
        }

        assertTrue(state.value.showPickThree)
        assertEquals(listOf(7, 8, 9), state.value.pickThreeCandidates.map { it.id })
        // A fresh collection after the write still sees the flag — the
        // persisted value, not a live emission the collector happened to
        // catch.
        assertTrue(settings.firstBrowseDone.first())
    }

    /**
     * The round-trip, late: the browse already happened (flag persisted),
     * and only then does Home start collecting — the Robo #11 shape, where
     * the viewer returns to Home after the Detail and the HomeViewModel
     * collects the flag for the first time. The first emission must
     * already be true; the card must not need a second browse.
     */
    @Test
    fun `a Home collecting after the browse still unlocks the card`() = runBlocking {
        val settings = settings()

        // The browse happened before Home existed.
        settings.setFirstBrowseDone(true)
        assertTrue(settings.firstBrowseDone.first())

        val state = MutableStateFlow(HomeUiState(sections = listOf(fullRail())))
        assertFalse(state.value.showPickThree)

        // Same collector as the live test, same cancel-in-finally: the
        // DataStore flow never ends, so runBlocking would join it forever.
        val collector = launch {
            settings.firstBrowseDone.collect { done ->
                state.update { it.copy(firstBrowseDone = done) }
            }
        }
        try {
            withTimeout(10_000) { state.first { it.showPickThree } }
        } finally {
            collector.cancel()
        }

        assertTrue(state.value.showPickThree)
        assertEquals(listOf(7, 8, 9), state.value.pickThreeCandidates.map { it.id })
    }
}
