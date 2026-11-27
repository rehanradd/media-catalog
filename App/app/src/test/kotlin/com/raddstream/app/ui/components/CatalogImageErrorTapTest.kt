package com.raddstream.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertFalse
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
 * The test renders [CatalogImageErrorSlot] directly, inside a clickable
 * parent tile — no Coil, no network, no waiting. Rounds 1–4 tried to reach
 * the slot through a real [CatalogImage] load instead and never got there:
 * round 1 tapped after a bare waitForIdle() and passed on unfixed code in
 * 0.087 s (the tap landed on the loading shimmer, which has no clickable);
 * rounds 2–3 waited with waitUntil for the error slot's click action and
 * died in the wait at exactly 30.092 s and 90.089 s (under Robolectric the
 * real fetch never fails at all); round 4 failed the load in-process with
 * a fake Coil loader and still died in the wait at 30.089 s (the fake
 * fetcher never ran, or the label never surfaced — either way the slot
 * stayed unobservable through the load). Rendering the slot directly is
 * what makes the tap deterministic: the slot IS the thing under the finger.
 *
 * The tap below is a real touch event at the tile centre (performTouchInput),
 * not performClick on the parent node: performClick invokes the parent's own
 * click action directly and would pass even with the bug, while a touch
 * event goes through hit-testing and lands on the innermost clickable —
 * exactly the Robo tap.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogImageErrorTapTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun centreTapOnFailedImageReachesParentOnClick() {
        var clicked = false
        compose.setContent {
            RaddStreamTheme {
                Box(
                    Modifier
                        .size(200.dp)
                        .testTag("tile")
                        .clickable { clicked = true },
                ) {
                    CatalogImageErrorSlot(
                        fallbackLabel = "DP",
                        monogram = null,
                        tone = FallbackTone.Tile,
                        large = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.onNodeWithTag("tile").assertExists()
        compose.onNodeWithTag("tile").performTouchInput { click() }
        // The tap must reach the parent card: the slot has no clickable
        // of its own, so there is nothing between the finger and the tile.
        assertTrue(
            "centre tap on a failed-image tile did not reach the parent onClick",
            clicked,
        )
    }

    // ── Video audit issue 22 (Nov 2026): the corner retry button ───────
    //
    // The small 40dp TopEnd button is the exception that proves A3 Proof
    // 3: it re-issues the load without stealing the card tap, because it
    // is a corner control, not a full-size clickable. Logo plates are too
    // small for it and carry none.

    private fun showSlotWithRetry(
        onTileClick: () -> Unit,
        onRetry: (() -> Unit)?,
        large: Boolean = false,
    ) {
        compose.setContent {
            RaddStreamTheme {
                Box(
                    Modifier
                        .size(200.dp)
                        .testTag("tile")
                        .clickable { onTileClick() },
                ) {
                    CatalogImageErrorSlot(
                        fallbackLabel = "DP",
                        monogram = null,
                        tone = FallbackTone.Tile,
                        large = large,
                        modifier = Modifier.fillMaxSize(),
                        onRetry = onRetry,
                    )
                }
            }
        }
    }

    @Test
    fun retryButtonTapCallsOnRetryAndNotTheParent() {
        var clicked = false
        var retried = false
        showSlotWithRetry(onTileClick = { clicked = true }, onRetry = { retried = true })
        compose.onNodeWithContentDescription("Retry loading image")
            .assertExists()
            .performClick()
        assertTrue("retry button tap did not call onRetry", retried)
        assertFalse("retry button tap reached the parent onClick", clicked)
    }

    @Test
    fun centreTapStillReachesParentWhenRetryButtonIsPresent() {
        var clicked = false
        showSlotWithRetry(onTileClick = { clicked = true }, onRetry = {})
        compose.onNodeWithTag("tile").assertExists()
        compose.onNodeWithTag("tile").performTouchInput { click() }
        assertTrue(
            "centre tap on a failed-image tile with a retry button did not reach the parent onClick",
            clicked,
        )
    }

    @Test
    fun logoPlatesCarryNoRetryButton() {
        var retried = false
        showSlotWithRetry(onTileClick = {}, onRetry = { retried = true }, large = true)
        compose.onNodeWithContentDescription("Retry loading image").assertDoesNotExist()
        assertFalse("a logo plate must not offer a retry button", retried)
    }

    @Test
    fun slotWithoutRetryCarriesNoRetryButton() {
        showSlotWithRetry(onTileClick = {}, onRetry = null)
        compose.onNodeWithContentDescription("Retry loading image").assertDoesNotExist()
    }
}
