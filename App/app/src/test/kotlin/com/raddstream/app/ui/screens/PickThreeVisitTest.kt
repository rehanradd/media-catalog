package com.raddstream.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.HomeSection
import com.raddstream.app.viewmodel.HomeUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pick 3 (fixed 2026-11-15): the card used to retire itself the frame it
 * appeared — its LaunchedEffect called the shown callback, the callback
 * set the persisted shown flag, and the gate required that flag false —
 * so no user or Robo run ever saw it, across two full crawls (123 + 100
 * actions) where every other gate was met. These tests drive the real
 * HomeContent gate with the real HomeUiState transitions, the way the
 * ViewModel wires them: display must keep the card up for the whole
 * visit, and leaving Home must end the visit so it never returns.
 *
 * Existence (not displayed-ness) is the assertion: the bug removed the
 * node from composition entirely, and the Robolectric viewport is not
 * the device's — what matters here is that the card survives the shown
 * callback and the recomposition it triggers.
 */
@RunWith(RobolectricTestRunner::class)
class PickThreeVisitTest {

    @get:Rule
    val compose = createComposeRule()

    /** Nine titles: the hero takes the first six, the card offers the next three. */
    private fun eligibleState() = HomeUiState(
        sections = listOf(
            HomeSection(
                key = "recent",
                title = "Recently added",
                type = null,
                items = (1..9).map { Post(id = it, title = "Title $it") },
            ),
        ),
        firstBrowseDone = true,
    )

    private fun setHome(
        state: HomeUiState,
        onPickThreeShown: () -> Unit = {},
        onPickThreeVisitEnded: () -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = state,
                    heroAutoplay = false,
                    onPickThreeShown = onPickThreeShown,
                    onPickThreeVisitEnded = onPickThreeVisitEnded,
                )
            }
        }
    }

    @Test
    fun cardStaysVisibleForTheWholeVisitAfterItReportsShown() {
        // State held the way the ViewModel holds it: the shown callback
        // applies the real HomeUiState transition, exactly like
        // HomeViewModel.markPickThreeShown does. Under the old transition
        // (shown flag only) the card vanished on the recomposition the
        // callback triggered, and this assertion failed.
        var state by mutableStateOf(eligibleState())
        assertTrue(state.showPickThree)
        setHome(
            state = state,
            onPickThreeShown = { state = state.onPickThreeShown() },
            onPickThreeVisitEnded = { state = state.onPickThreeVisitEnded() },
        )

        compose.waitForIdle()

        compose.onNodeWithText("Pick 3 you like").assertExists()
        assertTrue("display must not hide the card", state.showPickThree)
    }

    @Test
    fun leavingHomeEndsTheVisitSoTheCardNeverReturns() {
        var state by mutableStateOf(eligibleState())
        setHome(
            state = state,
            onPickThreeShown = { state = state.onPickThreeShown() },
            onPickThreeVisitEnded = { state = state.onPickThreeVisitEnded() },
        )
        compose.waitForIdle()
        compose.onNodeWithText("Pick 3 you like").assertExists()

        // Leaving Home disposes the feed: the visit ends, the latch
        // clears, and the persisted shown flag keeps the card away.
        compose.setContent { }
        compose.waitForIdle()

        assertFalse(state.showPickThree)

        // Coming back: still gone.
        setHome(state = state)
        compose.waitForIdle()
        compose.onNodeWithText("Pick 3 you like").assertDoesNotExist()
    }
}
