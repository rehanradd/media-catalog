package com.raddstream.app.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * restructured row prints. The ContactDialog tests below pin the follow-up
 * restyle: the dialog shows the disclaimer's address (never the
 * support@raddstream.com the mockup invented), "Write email" opens the
 * mail app with it, the pill copies it, and "Close" dismisses. Runs on the
 * JVM under Robolectric because this project has no emulator image.
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

    @Test
    fun contactDialogShowsRealAddressNotTheInventedOne() {
        // The Stitch mockup invented support@raddstream.com. The dialog
        // must show the disclaimer's address — the one the app already
        // names for takedowns — and never the invented one.
        compose.setContent {
            RaddStreamTheme {
                ContactDialog(email = "radddao1@gmail.com", onDismiss = {})
            }
        }
        compose.onNodeWithText("Contact us").assertIsDisplayed()
        compose.onNodeWithText("radddao1@gmail.com", substring = true).assertIsDisplayed()
        compose.onNodeWithText("support@raddstream.com").assertDoesNotExist()
        compose.onNodeWithText("Write email").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy email address").assertIsDisplayed()
    }

    @Test
    fun contactDialogBlankEmailFallsBackToDefault() {
        // A panel disclaimer with no email must not render an empty pill:
        // the shipped default is the app's real contact address.
        compose.setContent {
            RaddStreamTheme {
                ContactDialog(email = "", onDismiss = {})
            }
        }
        compose.onNodeWithText("radddao1@gmail.com", substring = true).assertIsDisplayed()
    }

    @Test
    fun contactDialogWriteEmailOpensMailApp() {
        val opened = mutableListOf<String>()
        val fake = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        compose.setContent {
            RaddStreamTheme {
                CompositionLocalProvider(LocalUriHandler provides fake) {
                    ContactDialog(email = "radddao1@gmail.com", onDismiss = {})
                }
            }
        }
        compose.onNodeWithText("Write email").performClick()
        assertEquals(listOf("mailto:radddao1@gmail.com"), opened)
    }

    @Test
    fun contactDialogCloseDismisses() {
        var dismissed = false
        compose.setContent {
            RaddStreamTheme {
                ContactDialog(email = "radddao1@gmail.com", onDismiss = { dismissed = true })
            }
        }
        compose.onNodeWithText("Close").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun contactDialogCopyPutsAddressOnClipboard() {
        var clipboard: ClipboardManager? = null
        compose.setContent {
            RaddStreamTheme {
                clipboard = LocalClipboardManager.current
                ContactDialog(email = "radddao1@gmail.com", onDismiss = {})
            }
        }
        compose.onNodeWithContentDescription("Copy email address").performClick()
        assertEquals("radddao1@gmail.com", clipboard?.getText()?.text)
    }
}
