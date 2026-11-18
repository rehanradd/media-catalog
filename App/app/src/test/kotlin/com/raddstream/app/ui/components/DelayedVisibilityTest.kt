package com.raddstream.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S5: a spinner that appears only for a wait that lasts — the
 * delay-then-minimum state machine behind [rememberDelayedVisible].
 * Under 250ms a wait shows nothing at all; once the spinner has
 * appeared it stays up at least 500ms, so a load that lands just after
 * the delay does not flash it on and off in the same beat.
 *
 * The clock is frozen (the SkeletonPlaceholderTest pattern) and advanced
 * by hand, so the delay is deterministic, not wall-clock.
 *
 * Requires an x86_64 runner. Robolectric's native runtime has no
 * Linux/aarch64 build (robolectric/robolectric#9166) and it fails while the
 * runner boots, so the exclusion for ARM hosts lives in
 * app/build.gradle.kts rather than here.
 */
@RunWith(RobolectricTestRunner::class)
class DelayedVisibilityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun spinnerStaysHiddenForFastWaits() {
        compose.mainClock.autoAdvance = false
        var active by mutableStateOf(true)
        compose.setContent {
            RaddStreamTheme {
                if (rememberDelayedVisible(active)) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp).testTag("delayedSpinner"),
                    )
                }
            }
        }
        // Nothing yet: the 250ms delay has not elapsed.
        compose.onNodeWithTag("delayedSpinner").assertDoesNotExist()
        // The wait ends before the delay — no spinner ever appears.
        active = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("delayedSpinner").assertDoesNotExist()
    }

    @Test
    fun spinnerAppearsAfterDelayAndStaysForMinimum() {
        compose.mainClock.autoAdvance = false
        var active by mutableStateOf(true)
        compose.setContent {
            RaddStreamTheme {
                if (rememberDelayedVisible(active)) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp).testTag("delayedSpinner"),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("delayedSpinner").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("delayedSpinner").assertExists()
        // The wait ends: the spinner stays up for its 500ms minimum.
        active = false
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("delayedSpinner").assertExists()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("delayedSpinner").assertDoesNotExist()
    }
}
