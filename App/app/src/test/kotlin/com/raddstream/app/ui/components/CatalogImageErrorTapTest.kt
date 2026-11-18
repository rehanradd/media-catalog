package com.raddstream.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
 * real network), so by the time the first query runs the load has errored
 * and the bounded auto-retries have settled (the CatalogImageLoadingTest
 * pattern) — the error slot is what is under the finger.
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
        // Let the load fail and the bounded auto-retries settle, so the
        // error slot — not the loading shimmer — is under the finger.
        compose.onNodeWithTag("tile").assertExists()
        compose.waitForIdle()
        compose.onNodeWithTag("tile").performTouchInput { click() }
        assertTrue(
            "centre tap on a failed-image tile did not reach the parent onClick",
            clicked,
        )
    }
}
