package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings review fixes (Gemini 5.5/10, 2026-11-16): ChipRow — the
 * taller visible pill + the strengthened selected state. The pill height
 * itself is pinned by the Paparazzi golden (settingsChipRow): semantics
 * merge the label into the chip node, so a unit test cannot see the
 * 44dp pill — it only sees the 48dp touch target. These tests pin what
 * semantics can see: the selection state, the callback, and that the
 * FlowRow wrap (Audit #46) still shows every option on a narrow phone
 * instead of clipping "Battery saver".
 *
 * Requires an x86_64 runner. Robolectric's native runtime has no Linux/aarch64
 * build (robolectric/robolectric#9166) and it fails while the runner boots, so
 * the exclusion for ARM hosts lives in app/build.gradle.kts rather than here.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsReviewFixesTest {

    @get:Rule
    val compose = createComposeRule()

    private val qualityOptions = listOf("Auto", "High", "Medium", "Battery saver")

    @Test
    fun chipRowSelectedChipIsMarked() {
        compose.setContent {
            RaddStreamTheme {
                ChipRow(qualityOptions, 1) {}
            }
        }
        compose.onNode(hasText("High")).assertIsSelected()
        compose.onNode(hasText("Auto")).assertIsNotSelected()
        compose.onNode(hasText("Medium")).assertIsNotSelected()
        compose.onNode(hasText("Battery saver")).assertIsNotSelected()
    }

    @Test
    fun chipRowSelectCallsBackWithIndex() {
        var selected = -1
        compose.setContent {
            RaddStreamTheme {
                ChipRow(qualityOptions, 1) { selected = it }
            }
        }
        compose.onNode(hasText("Medium")).performClick()
        assertEquals(2, selected)
    }

    @Test
    fun chipRowWrapShowsEveryOptionOnANarrowPhone() {
        // 200dp forces the wrap: "Battery saver" cannot fit beside the
        // other three, so it must land on a second line — and still be
        // displayed, never clipped the way the old plain Row clipped it.
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(200.dp)) {
                    ChipRow(qualityOptions, 1) {}
                }
            }
        }
        qualityOptions.forEach { label ->
            compose.onNode(hasText(label)).assertIsDisplayed()
        }
    }
}
