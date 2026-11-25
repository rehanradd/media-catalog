package com.raddstream.app.ui.screens

import androidx.compose.ui.graphics.Color
import com.raddstream.app.ui.theme.RaddChipBorderSoft
import com.raddstream.app.ui.theme.RaddInk750
import com.raddstream.app.ui.theme.RaddSettingsCard
import com.raddstream.app.ui.theme.contrastRatio
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI audit (Nov 2026, Settings #1): the unselected ChipRow border
 * (#333333) was 1.38:1 vs the settings card — below the M3 3:1 bar for
 * UI components, so the unselected pills dissolved into the background.
 * The border is now #787878 (raised from #6E6E6E: that cleared 3:1 on the
 * old flat card but measured 2.94:1 on the round-2 primaryContainer fill).
 * These tests pin the ratio, not just the hex, so a future softening fails
 * here first. Plain JUnit — the contrast math is pure Kotlin, no composition
 * needed. Follows the PlayerNoticeContrastTest pattern.
 */
class SettingsAuditFixesTest {

    @Test
    fun unselectedChipBorderClearsComponentBarOnCard() {
        val ratio = contrastRatio(RaddChipBorderSoft, RaddSettingsCard)
        assertTrue(
            "unselected chip border is only $ratio:1 vs the card, under the M3 3:1 component bar",
            ratio >= 3f,
        )
    }

    @Test
    fun unselectedChipBorderClearsComponentBarOnBlack() {
        // The Paparazzi canvas is black; the border must read there too.
        val ratio = contrastRatio(RaddChipBorderSoft, Color.Black)
        assertTrue(
            "unselected chip border is only $ratio:1 vs black, under the M3 3:1 component bar",
            ratio >= 3f,
        )
    }

    /**
     * Round 5 (ST1): the cards share ONE fill now — surfaceContainerHighest,
     * one tonal step above the group shell — so the border is measured on
     * that fill, not on the round-2 per-group tones. #7C7C7C clears 3:1 on
     * it (3.11:1); the old #787878 measured 2.94:1 there and failed.
     */
    @Test
    fun unselectedChipBorderClearsComponentBarOnEveryGroupFill() {
        val fills = mapOf(
            "surfaceContainerHighest" to RaddInk750,
        )
        for ((name, fill) in fills) {
            val ratio = contrastRatio(RaddChipBorderSoft, fill)
            assertTrue(
                "unselected chip border is only $ratio:1 vs $name, under the M3 3:1 component bar",
                ratio >= 3f,
            )
        }
    }
}
