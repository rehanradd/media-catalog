package com.raddstream.app.ui.screens

import androidx.compose.ui.graphics.Color
import com.raddstream.app.ui.theme.RaddChipBorderSoft
import com.raddstream.app.ui.theme.RaddInk850
import com.raddstream.app.ui.theme.RaddInk950
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

    /**
     * P1-14 (B-39): the mega-card is flattened — rows sit on the group's
     * single surfaceContainer surface now, so the border is measured on
     * that fill (#7C7C7C clears 3:1 on it, 4.10:1). The old flat #1A1A1A
     * card fill (RaddSettingsCard) is retired with the inner cards.
     */
    @Test
    fun unselectedChipBorderClearsComponentBarOnGroupSurface() {
        val ratio = contrastRatio(RaddChipBorderSoft, RaddInk850)
        assertTrue(
            "unselected chip border is only $ratio:1 vs the group surface, under the M3 3:1 component bar",
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
     * P1-14 (B-39): the groups are flat now — the fills are the group
     * surface (surfaceContainer, 4.10:1) and the bare background the
     * out-of-group rows sit on (4.49:1). No more surfaceContainerHighest.
     */
    @Test
    fun unselectedChipBorderClearsComponentBarOnEveryGroupFill() {
        val fills = mapOf(
            "surfaceContainer" to RaddInk850,
            "background" to RaddInk950,
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
