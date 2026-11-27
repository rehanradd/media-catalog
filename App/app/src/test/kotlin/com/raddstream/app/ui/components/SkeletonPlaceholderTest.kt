package com.raddstream.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robo run 7: the Home empty-library skeleton read as low-contrast text
 * (1.09:1) in runs 6 and 7 — the run-6 "sampling artifact" reading changed
 * nothing, so the flag came back identical. The placeholder bars are
 * decorative: they carry no words, so they must not appear in the
 * accessibility tree as text (or anything a contrast check can sample).
 * Pins that: the hero skeleton and the poster-grid skeleton — the two
 * skeletons Home and My List show while loading — expose no text nodes.
 *
 * Requires an x86_64 runner. Robolectric's native runtime has no
 * Linux/aarch64 build (robolectric/robolectric#9166) and it fails while the
 * runner boots, so the exclusion for ARM hosts lives in
 * app/build.gradle.kts rather than here. The shimmer's infinite transition
 * is frozen for the test so the clock never advances past the first frame.
 */
@RunWith(RobolectricTestRunner::class)
class SkeletonPlaceholderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun skeletonsExposeNoTextNodes() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RaddStreamTheme {
                Column {
                    HeroSkeleton()
                    PosterGridSkeleton()
                }
            }
        }
        // B-42 (P2-11): the skeletons mount only after the 200 ms delay
        // gate — advance past it, so this pins the rendered skeletons and
        // not the empty gate window.
        compose.mainClock.advanceTimeBy(SKELETON_DELAY_MS + 50)
        // Every string contains "" — so this matches any node that carries
        // text at all, and nothing else. onAllNodes is a rule member (the
        // Session 131 trap — no import); hasText is a top-level matcher.
        compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    /**
     * B-42 (P2-11): the delay gate itself — a skeleton that would appear
     * for a load that lands in under 200 ms reads as a glitch, not as
     * progress, so [ShimmerBox] paints nothing until the gate opens. The
     * clock is frozen and advanced by hand (the DelayedVisibilityTest
     * pattern), so the delay is deterministic, not wall-clock.
     */
    @Test
    fun shimmerStaysHiddenUntilDelayElapses() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RaddStreamTheme {
                ShimmerBox(Modifier.size(48.dp).testTag("gatedShimmer"))
            }
        }
        // Nothing yet: the 200 ms delay has not elapsed.
        compose.onNodeWithTag("gatedShimmer").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("gatedShimmer").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithTag("gatedShimmer").assertExists()
    }

    /**
     * A rail cell must be the same height as the [PosterCard] that replaces
     * it, or the rail grows when its rows arrive. The card is a 100dp poster
     * (150dp tall at 2:3) over a 60dp text column — 6dp gap, a 16dp title
     * slot (labelMedium's own line height, one line), 2dp, a 16dp details
     * line (labelSmall, the language), 2dp, the always-24dp badge slot —
     * 216dp in all. The old skeleton drew two 16dp title bars for the
     * two-line title the F7 redesign removed, plus the retired footer box's
     * 8dp sides and 10dp bottom padding: 325dp against the card's 250dp, a
     * ~75dp jump per rail (and per grid row) when the cards landed.
     *
     * Video audit (Nov 2026): the rail card shrank 120dp → 100dp, and the
     * title rides labelMedium (16dp) instead of titleSmall (20dp), so the
     * cell is 216dp now; the text column under it did not move otherwise.
     * The skeleton reserves the badge slot, so it matches the badged card
     * exactly — an unbadged card is 24dp tighter, the same asymmetry the
     * 250dp cell already had.
     *
     * The assertion is on the first cell, not the rail Row: a rail rendered
     * as the test's root content is measured EXACTLY at window height, so
     * the Row reports the screen height (470dp) and not its content. The
     * cell is the unit that must match the card, and it is measured by the
     * Row with its own height, so it reads true either way.
     */
    @Test
    fun railCellsMatchPosterCardHeight() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RaddStreamTheme {
                PosterRailSkeleton()
            }
        }
        // B-42 (P2-11): the skeleton mounts only after the 200 ms delay
        // gate — advance past it before measuring.
        compose.mainClock.advanceTimeBy(SKELETON_DELAY_MS + 50)
        compose.onNodeWithTag("posterRailCell").assertHeightIsEqualTo(216.dp)
    }

    /**
     * The detail Play placeholder must be the real Play button's 48dp min
     * ([SkeletonParityTest] pins the two numbers together in source); the
     * old skeleton drew 44dp.
     */
    @Test
    fun detailPlaySkeletonMatchesRealButtonHeight() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RaddStreamTheme {
                DetailSkeleton()
            }
        }
        // B-42 (P2-11): the skeleton mounts only after the 200 ms delay
        // gate — advance past it before measuring.
        compose.mainClock.advanceTimeBy(SKELETON_DELAY_MS + 50)
        compose.onNodeWithTag("detailPlaySkeleton").assertHeightIsEqualTo(48.dp)
    }
}
