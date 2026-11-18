package com.raddstream.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A3 Proof 3 (Nov 2026): Discover Pakistan's logo 429s, so its tile sat in
 * CatalogImage's error slot — and the error slot's full-size tap-to-retry
 * clickable swallowed the card's own tap. Robo tapped the card centre twice
 * (both SUCCESS) and nothing navigated; each tap only re-fetched the failing
 * logo. In Compose the innermost clickable consumes the tap, so a tile whose
 * image fails was un-openable by a centre tap — the same error slot serves
 * every poster, hero and detail image, so any failed image made its card
 * dead. The fix removes the full-size clickable from the error slot, so the
 * tap falls through to the parent card.
 *
 * The tap below is a real touch event at the tile centre (performTouchInput),
 * not performClick on the parent node: performClick invokes the parent's own
 * click action directly and would pass even with the bug, while a touch
 * event goes through hit-testing and lands on the innermost clickable —
 * exactly the Robo tap. Under Robolectric the TMDB URL cannot fetch (no
 * real network), so the load errors and the bounded auto-retries settle
 * into the error slot — but only after the test waits for it: the test
 * waits until the error slot's "Retry loading image" click action is
 * present before tapping, so the tap is guaranteed to land on the error
 * slot rather than the loading shimmer, which has no clickable and would
 * let the tap through to the parent vacuously. (Round 1 of this test
 * tapped after a bare waitForIdle() and passed on unfixed code in 0.087s
 * — Coil fetches on real threads invisible to the compose clock, so idle
 * returned while the load was still in flight.)
 */
@RunWith(RobolectricTestRunner::class)
class CatalogImageErrorTapTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun centreTapOnFailedImageReachesParentOnClick() {
        compose.mainClock.autoAdvance = false
        var clicked = false
        compose.setContent {
            RaddStreamTheme {
                Box(
                    Modifier
                        .size(200.dp)
                        .testTag("tile")
                        .clickable { clicked = true },
                ) {
                    CatalogImage(
                        url = "https://image.tmdb.org/t/p/w500/abc123.jpg",
                        contentDescription = "Poster",
                        fallbackLabel = "DP",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        // Pin the error slot before tapping: wait until the node carrying
        // the "Retry loading image" click action exists. That label lives
        // only on the error slot's full-size retry clickable — the loading
        // slot (fallback card + shimmer) has no clickable at all, and the
        // parent tile's own click carries no label. A bare waitForIdle()
        // cannot pin this: Coil fetches on real threads invisible to the
        // compose clock, so idle can return while the load is still in
        // flight. waitUntil re-checks after each clock advance (pumping
        // the main looper, so the failed fetch and the bounded immediate
        // auto-retries can settle) until the error slot is the thing under
        // the finger. The bound is 90 s, not 30 s: under Robolectric there
        // is no real network, so each fetch hangs to OkHttp's 10 s connect
        // timeout — and the bounded auto-retry re-issues twice, so the
        // error slot only appears after ~30 s of failed fetches. Round 2
        // waited 30 s and died in waitUntil at exactly 30.092 s, just as
        // the last retry was timing out.
        compose.waitUntil(timeoutMillis = 90_000) {
            compose.onAllNodes(hasClickActionLabel("Retry loading image"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNode(hasClickActionLabel("Retry loading image")).assertExists()
        compose.onNodeWithTag("tile").performTouchInput { click() }
        assertTrue(
            "centre tap on a failed-image tile did not reach the parent onClick",
            clicked,
        )
    }
}

/**
 * Matches a node whose click action carries the given onClickLabel. The
 * label is not a content description, so onNodeWithContentDescription
 * cannot find it — the match reads the OnClick action straight off the
 * node's semantics (the AccountLabelFieldTest pattern).
 */
private fun hasClickActionLabel(label: String) =
    SemanticsMatcher("click action labelled \"$label\"") { node ->
        node.config.contains(SemanticsActions.OnClick) &&
            node.config[SemanticsActions.OnClick].label == label
    }
