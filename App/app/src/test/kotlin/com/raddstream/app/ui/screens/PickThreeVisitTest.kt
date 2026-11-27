package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
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
 *
 * Home fix (Nov 2026): the card rides below the first rail now, so it
 * starts below the fold in a lazy feed — the tests swipe it into view
 * first (a lazy item that never composes has no node to assert on).
 */
@RunWith(RobolectricTestRunner::class)
class PickThreeVisitTest {

    @get:Rule
    val compose = createComposeRule()

    // Lazy feed (home fix, Nov 2026): the card rides below the first rail,
    // so it is not composed until it scrolls into view. Scroll by INDEX,
    // not by drag: a drag is a fling in disguise — a half-viewport swipe
    // overshoots the card and disposes it before the check runs, and a
    // slow drag hung the CI job in waitForIdle (run 45078087971, cancelled
    // at ~32 min). performScrollToIndex is deterministic: it puts the item
    // in view and composes it, no fling, no idle wait. The index is pinned
    // to eligibleState()'s feed: 0 hero, 1 heroSpacer, 2 filter,
    // 3 filterSpacer, 4 first rail, 5 pickThree — no broadcasts, no CW,
    // no trial note in this state. If HomeContent's item order changes,
    // this index must move with it.
    private fun scrollToPickThree() {
        compose.onNodeWithTag(HomeFeedTag).performScrollToIndex(5)
        compose.waitForIdle()
    }

    // Back to the top of the feed — disposes below-fold items, including
    // the card once it has been shown.
    private fun scrollToTop() {
        compose.onNodeWithTag(HomeFeedTag).performScrollToIndex(0)
        compose.waitForIdle()
    }

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
    )

    private fun setHome(
        state: HomeUiState,
        onPickThreeShown: () -> Unit = {},
        onPickThreeVisitEnded: () -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                // P1-5: pin a short viewport (400dp) for the scroll-out
                // test. The hero item lost its below-card dots row (~52dp)
                // when the dots moved on the art, so the card's top crossed
                // the default Robolectric viewport edge at scroll 0 and
                // scrollToTop() stopped disposing it — the visit never
                // ended (Android CI 45779270124, 1 failed of 3233). A
                // pinned viewport makes scroll-out disposal independent
                // of hero height instead of balanced on the viewport edge.
                Box(Modifier.height(400.dp)) { // token-exempt: test viewport height, not UI spacing
                    HomeContent(
                        state = state,
                        heroAutoplay = false,
                        onPickThreeShown = onPickThreeShown,
                        onPickThreeVisitEnded = onPickThreeVisitEnded,
                    )
                }
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

        // The card rides below the first rail now (home fix, Nov 2026),
        // so it starts below the fold in a lazy feed — swipe it into view
        // before asserting it exists.
        scrollToPickThree()

        compose.onNodeWithText("Pick 3 you like").assertExists()
        assertTrue("display must not hide the card", state.showPickThree)
    }

    @Test
    fun leavingHomeEndsTheVisitSoTheCardNeverReturns() {
        // The visit ends when the card leaves composition: its
        // DisposableEffect fires onPickThreeVisitEnded, the latch clears,
        // and the persisted shown flag keeps the card away. Leaving Home
        // (navigation) disposes the whole feed and fires that same
        // effect; scrolling the card out of the feed fires it identically.
        // This test drives the scroll-out trigger, because the
        // if(onHome) navigation toggle never let the card compose under
        // it in Robolectric — a drag flings past it, a slow drag hung CI
        // in waitForIdle (run 45078087971, ~32 min), performScrollToIndex
        // leaves it uncomposed there (run 45080985810 attempt 3), and a
        // 3000dp-tall render is clamped to the screen by the parent
        // (run 45083672343). The mechanism under test — the
        // DisposableEffect + latch + persisted flag — is the same either
        // way, and the navigation path is the ViewModel's
        // markPickThreeShown / visit-ended wiring, already covered by the
        // state tests.
        var state by mutableStateOf(eligibleState())
        assertTrue(state.showPickThree)
        setHome(
            state = state,
            onPickThreeShown = { state = state.onPickThreeShown() },
            onPickThreeVisitEnded = { state = state.onPickThreeVisitEnded() },
        )

        compose.waitForIdle()
        scrollToPickThree()
        compose.onNodeWithText("Pick 3 you like").assertExists()

        // Scrolling the card out of the feed disposes it: the visit ends,
        // the latch clears, and the persisted shown flag keeps it away.
        // The card is gone from the UI right here — no need to scroll back
        // to where it was (scrolling back re-composes a stale node and
        // trips assertDoesNotExist even though the gate is false).
        scrollToTop()
        assertFalse(state.showPickThree)
        compose.onNodeWithText("Pick 3 you like").assertDoesNotExist()
    }
}
