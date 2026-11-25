package com.raddstream.app.ui.screens

import androidx.compose.ui.graphics.Color
import com.raddstream.app.ui.theme.RaddChipBorderSoft
import com.raddstream.app.ui.theme.RaddCurrentContainerDark
import com.raddstream.app.ui.theme.RaddInk850
import com.raddstream.app.ui.theme.RaddInk900
import com.raddstream.app.ui.theme.RaddSettingsCard
import com.raddstream.app.ui.theme.RaddTideContainerDark
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
     * Round 2 (ST1): the cards stopped sharing one flat fill — each group
     * has its own tone now, and the border must clear 3:1 on every one of
     * them, not just the old card. surfaceContainerHigh is excluded because
     * no group uses it as a chip-bearing fill (with the #787878 border it
     * would clear 3:1 too).
     */
    @Test
    fun unselectedChipBorderClearsComponentBarOnEveryGroupFill() {
        val fills = mapOf(
            "primaryContainer" to RaddCurrentContainerDark,
            "tertiaryContainer" to RaddTideContainerDark,
            "surfaceContainerLow" to RaddInk900,
            "surfaceContainer" to RaddInk850,
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
