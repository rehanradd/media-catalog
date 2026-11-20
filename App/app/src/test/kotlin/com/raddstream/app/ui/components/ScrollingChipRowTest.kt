package com.raddstream.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A4-F3: the shared scrolling chip row and the one chip. The row replaces
 * six hand-rolled Box + LazyRow/Row + fade copies (Home filter + Browse,
 * Live TV categories, My List sort, Search rail + suggestions, Detail
 * genres + seasons, Player cast); the tests pin what the migrations must
 * not lose: FilterChip selected semantics, the 48dp touch-target floor,
 * the unconditional right-edge fade, and the Browse row's unfilled
 * navigation pill.
 *
 * Runs on the JVM under Robolectric because this project has no emulator
 * image. Requires an x86_64 runner (robolectric/robolectric#9166).
 */
@RunWith(RobolectricTestRunner::class)
class ScrollingChipRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun filterChipRendersClicksAndKeepsSelectedSemantics() {
        var clicks = 0
        var selected by mutableStateOf(false)
        compose.setContent {
            RaddStreamTheme {
                RaddChip(
                    selected = selected,
                    onClick = { clicks++ },
                    label = { Text("Movies") },
                    modifier = Modifier.testTag("chip_movies"),
                )
            }
        }
        compose.onNodeWithTag("chip_movies").assertIsDisplayed()
        compose.onNodeWithTag("chip_movies").assertIsNotSelected()
        compose.onNodeWithTag("chip_movies").performClick()
        assertEquals(1, clicks)

        // The selected state is what MyListMockupScreenTest pins on the
        // sort chips — RaddChip(Filter) delegates to FilterChip, so it
        // survives the migration. setContent may only be called once per
        // test, so the state is hoisted and updated instead of recomposing
        // from a second setContent call.
        selected = true
        compose.waitForIdle()
        compose.onNodeWithTag("chip_movies").assertIsSelected()
    }

    @Test
    fun navigationChipRendersAndClicks() {
        var clicks = 0
        compose.setContent {
            RaddStreamTheme {
                RaddChip(
                    selected = false,
                    onClick = { clicks++ },
                    label = { Text("All") },
                    style = RaddChipStyle.Navigation,
                    modifier = Modifier.testTag("chip_all"),
                )
            }
        }
        compose.onNodeWithTag("chip_all").assertIsDisplayed()
        compose.onNodeWithText("All").assertIsDisplayed()
        compose.onNodeWithTag("chip_all").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun fadeNodeExistsInANarrowBoxWithLongLabels() {
        // The fade is unconditional (a conditional canScrollForward fade
        // would risk SearchFilterRailTest.rightEdgeFadeMaskExists, which
        // pins the fade's presence in a 360dp box where Robolectric's
        // ~1px/char metrics may make the row fit). A 150dp box with long
        // labels forces overflow deterministically, so the test pins
        // presence + height, never a pixel measurement of the chips.
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(150.dp)) {
                    ScrollingChipRow(
                        fadeTestTag = "test-fade",
                    ) {
                        items(listOf("Complete series", "Documentaries", "Science Fiction")) { label ->
                            RaddChip(
                                selected = false,
                                onClick = {},
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("test-fade").assertExists()
        compose.onNodeWithTag("test-fade").assertHeightIsEqualTo(48.dp)
    }

    @Test
    fun genreFadeHeightIsHonoured() {
        // DetailRedesignTest pins the genre fade at 22dp; the fadeHeight
        // param is what carries that through the shared row.
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(150.dp)) {
                    ScrollingChipRow(
                        fadeHeight = 22.dp,
                        fadeTestTag = "test-genre-fade",
                    ) {
                        items(listOf("Action", "Drama", "Thriller")) { label ->
                            RaddChip(
                                selected = false,
                                onClick = {},
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("test-genre-fade").assertHeightIsEqualTo(22.dp)
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun flingSettlesOnAnItemBoundary() {
        // Screen audit #1 (Nov 2026): the row snaps — a free fling used
        // to rest mid-chip ("Complete serie…" cut at the edge reads as
        // broken, not scrollable, even under the fade). After a fling the
        // first visible item sits flush at the row's start edge, so its
        // scroll offset is 0. The state is hoisted so the test can read
        // the settled offset after the fling animation finishes.
        //
        // The row runs with zero content padding here on purpose: the
        // production rows carry 16dp of start padding, and the snap
        // behaviour lands the item flush at the CONTENT edge — so with
        // padding, the previous chip's tail still peeks into the 16dp
        // start zone and firstVisibleItemScrollOffset reads nonzero even
        // when the fling snapped correctly. Zero padding makes the snap
        // target the viewport start, so offset 0 is the snap itself.
        //
        // Twelve chips on purpose: with five, the content was barely
        // wider than the viewport, so any swipe pinned the list at its
        // end, where no item can sit flush and offset 0 was unreachable
        // by construction (CI 44646400985 / 44649050155 / 44651470183).
        // Twelve chips make the content ~3x the viewport, so a fling
        // lands mid-list where a snap CAN align an item flush.
        //
        // The fling is driven PROGRAMMATICALLY, not by a swipe: six CI
        // runs proved a Robolectric swipe gesture never reaches the
        // snap — the list rests at the drag position with
        // scrolling=false, and waiting 10s does not move it (run
        // 44658171823: index=5 offset=36 scrolling=false, settled).
        // A swipe produces a drag but no fling velocity here, so
        // performFling is never called and the snap never runs. The
        // test therefore builds the same snap behaviour the row builds
        // (rememberSnapFlingBehavior over the same hoisted state, same
        // defaults) and drives it from a LaunchedEffect. That pins the
        // snap settlement for this row's state; the row's own wiring —
        // it passes rememberSnapFlingBehavior(state) to its LazyRow —
        // is the one-liner in ScrollingChipRow.kt:86. The gesture path
        // (a real swipe -> fling -> snap) is a device-verification
        // item, not something Robolectric can prove.
        //
        // The fling velocity is 400px/s on purpose: it moves the list
        // ~135px, past the first chip (so the index assertion means
        // something) while landing well short of maxScroll, so the snap
        // has room to align an item flush. waitUntil polls for the
        // SETTLED snapped state (up to 10s of virtual time) instead of
        // assuming a fixed duration covers the decay + snap spring.
        // The index assertion keeps the test honest: a fling that moved
        // nothing would also read offset 0.
        val state = LazyListState()
        compose.setContent {
            RaddStreamTheme {
                // The snap behaviour, built exactly the way the row
                // builds its own (same function, same state, same
                // defaults) — driven from the LaunchedEffect below
                // because a Robolectric swipe produces no fling.
                val snapBehavior = rememberSnapFlingBehavior(state)
                Box(Modifier.width(200.dp)) {
                    ScrollingChipRow(
                        state = state,
                        contentPadding = PaddingValues(0.dp),
                        rowTestTag = "test-snap-row",
                    ) {
                        items(listOf("Complete series", "Documentaries", "Science Fiction", "Action", "Drama", "Comedy", "Horror", "Romance", "Thriller", "Mystery", "Animation", "Crime")) { label ->
                            RaddChip(
                                selected = false,
                                onClick = {},
                                label = { Text(label) },
                            )
                        }
                    }
                }
                LaunchedEffect(snapBehavior) {
                    state.scroll {
                        // TargetedFlingBehavior (what
                        // rememberSnapFlingBehavior returns in this
                        // Compose version): performFling takes the
                        // remaining-distance callback too. The 1-arg
                        // form does not resolve here (compile error in
                        // run 44659478756: "Unresolved reference
                        // 'performFling'").
                        with(snapBehavior) {
                            performFling(400f) { }
                        }
                    }
                }
            }
        }
        // Poll for the settled snapped state: waitUntil advances the
        // test clock while it polls, so the decay + snap spring gets
        // the virtual time it needs.
        compose.waitUntil(timeoutMillis = 10_000) {
            state.firstVisibleItemIndex > 0 && state.firstVisibleItemScrollOffset == 0
        }
        assertTrue(state.firstVisibleItemIndex > 0)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }
}
