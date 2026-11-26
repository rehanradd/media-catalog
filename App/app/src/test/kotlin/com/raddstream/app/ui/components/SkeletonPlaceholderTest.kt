package com.raddstream.app.ui.components

import androidx.compose.foundation.layout.Column
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
        // Every string contains "" — so this matches any node that carries
        // text at all, and nothing else. onAllNodes is a rule member (the
        // Session 131 trap — no import); hasText is a top-level matcher.
        compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    /**
     * A rail cell must be the same height as the [PosterCard] that replaces
     * it, or the rail grows when its rows arrive. The card is a 120dp poster
     * (180dp tall at 2:3) over a 64dp text column — 6dp gap, a 20dp title
     * slot (titleSmall's own line height, one line), 2dp, a 16dp details
     * line (labelSmall), 2dp, the always-24dp badge slot — 250dp in all.
     * The old skeleton drew two 16dp title bars for the two-line title the
     * F7 redesign removed, plus the retired footer box's 8dp sides and
     * 10dp bottom padding: 325dp against the card's 250dp, a ~75dp jump
     * per rail (and per grid row) when the cards landed.
     *
     * P0-6 (Nov 2026): the rail card shrank 150dp → 120dp, so the cell is
     * 250dp now; the text column under it did not move.
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
        compose.onNodeWithTag("posterRailCell").assertHeightIsEqualTo(250.dp)
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
        compose.onNodeWithTag("detailPlaySkeleton").assertHeightIsEqualTo(48.dp)
    }

    /**
     * The hero title placeholder must be the hero title's own 32dp line
     * height (the 28sp copy), not displaySmall's 44dp token.
     */
    @Test
    fun heroTitleSkeletonMatchesHeroTitleLineHeight() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RaddStreamTheme {
                HeroSkeleton()
            }
        }
        compose.onNodeWithTag("heroTitleSkeleton").assertHeightIsEqualTo(32.dp)
    }
}
