package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.raddstream.app.brand.Brand
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings review (Nov 2026): the Help answers that depend on the server
 * follow the server. The Watch cap follows the quality gate (D1), the VIP
 * prices are the panel's published ones and disappear when unpublished
 * (D8), and the "Need more help?" answer names the real email path instead
 * of the removed Contact form (D2). The shared version line (D9) is pinned
 * here too, so the two About surfaces can never drift again.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsHelpFixesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun help(
        gateOn: Boolean = true,
        monthlyPkr: Int = 0,
        yearlyPkr: Int = 0,
    ) {
        compose.setContent {
            RaddStreamTheme {
                HelpContent(
                    faqExpanded = true,
                    onFaqToggle = {},
                    onReportClick = {},
                    onContactClick = {},
                    onAboutClick = {},
                    reportMessage = null,
                    reportMessageIsError = false,
                    gateOn = gateOn,
                    monthlyPkr = monthlyPkr,
                    yearlyPkr = yearlyPkr,
                )
            }
        }
    }

    @Test
    fun `watch answer caps free viewers at 720p while the gate is on`() {
        help(gateOn = true)
        compose.onNodeWithText("Free viewers play up to 720p.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Every title plays up to 4K, free", substring = true).assertDoesNotExist()
    }

    @Test
    fun `watch answer plays everything free while the gate is off`() {
        help(gateOn = false)
        compose.onNodeWithText("Every title plays up to 4K, free", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Free viewers play up to 720p.", substring = true).assertDoesNotExist()
    }

    @Test
    fun `vip answer prints the published prices`() {
        help(monthlyPkr = 200, yearlyPkr = 1500)
        compose.onNodeWithText("PKR 200 a month", substring = true).assertIsDisplayed()
        compose.onNodeWithText("PKR 1,500 a year", substring = true).assertIsDisplayed()
    }

    @Test
    fun `vip answer drops the numbers when no price is published`() {
        help(monthlyPkr = 0, yearlyPkr = 0)
        compose.onNodeWithText("monthly or yearly membership", substring = true).assertIsDisplayed()
        compose.onNodeWithText("PKR", substring = true).assertDoesNotExist()
    }

    @Test
    fun `need more help names the email path not the removed form`() {
        help()
        compose.onNodeWithText("Write to us in Settings", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Contact form", substring = true).assertDoesNotExist()
    }

    @Test
    fun `about version line is the version name only`() {
        val line = aboutVersionLine()
        assertEquals("Version ${Brand.versionName}", line)
        assertFalse("no build number in the painted line, was: $line", line.contains(Brand.versionCode.toString()))
        assertFalse("no scheme name in the painted line, was: $line", line.contains(Brand.schemeName))
    }

    @Test
    fun `build details behind the tap still carry name code and scheme`() {
        val details = aboutBuildDetailsLine()
        assertTrue(
            "the details must start with the version name and code, was: $details",
            details.startsWith("Version ${Brand.versionName} (${Brand.versionCode})"),
        )
        assertTrue(
            "the details must end with the scheme name, was: $details",
            details.endsWith(Brand.schemeName),
        )
    }
}
