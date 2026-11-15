package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings mockup slice (branch settings-mockup-20261115): the Stitch
 * Settings mockup shows no premium anywhere — no VIP card, no coins, no
 * Dashboard/Profile doors — while the quality gate is OFF (free launch).
 * The VIP card was already gated on `paywallVisible`; the Account card's
 * Dashboard / Profile / Coins row was not, so it showed under free launch.
 * These tests pin the row's gate, and the audio-level percent the mockup's
 * restructured row prints. Runs on the JVM under Robolectric because this
 * project has no emulator image.
 *
 * Requires an x86_64 runner. Robolectric's native runtime has no Linux/aarch64
 * build (robolectric/robolectric#9166) and it fails while the runner boots, so
 * the exclusion for ARM hosts lives in app/build.gradle.kts rather than here.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsMockupTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun accountNavRowHidesWhenGateOff() {
        // Free launch: gate OFF means every viewer is premium — the
        // Dashboard / Profile / Coins doors must not show at all.
        compose.setContent {
            RaddStreamTheme {
                AccountNavRow(
                    paywallVisible = false,
                    onOpenDashboard = {},
                    onOpenProfile = {},
                    onOpenCoins = {},
                )
            }
        }
        compose.onNodeWithText("Dashboard").assertDoesNotExist()
        compose.onNodeWithText("Profile").assertDoesNotExist()
        compose.onNodeWithText("Coins").assertDoesNotExist()
    }

    @Test
    fun accountNavRowShowsWhenGateOn() {
        var coinsOpened = false
        compose.setContent {
            RaddStreamTheme {
                AccountNavRow(
                    paywallVisible = true,
                    onOpenDashboard = {},
                    onOpenProfile = {},
                    onOpenCoins = { coinsOpened = true },
                )
            }
        }
        compose.onNodeWithText("Dashboard").assertIsDisplayed()
        compose.onNodeWithText("Profile").assertIsDisplayed()
        compose.onNodeWithText("Coins").assertIsDisplayed()
        compose.onNodeWithText("Coins").performClick()
        assertTrue(coinsOpened)
    }

    @Test
    fun audioLevelPercentMatchesLabelFor() {
        // The restructured Audio level row prints the bare percent ("82%");
        // it must be the same number AudioBoost.labelFor prints after
        // "Volume ", so the two can never disagree.
        assertEquals("82%", audioLevelPercent(0.82f))
        assertEquals("150%", audioLevelPercent(1.5f))
        assertEquals("0%", audioLevelPercent(0f))
        // A non-finite level collapses to the baseline, never NaN%.
        assertEquals("100%", audioLevelPercent(Float.NaN))
    }
}
