package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.PendingSeasonKeep
import com.raddstream.app.viewmodel.SeasonKeepItem
import com.raddstream.app.viewmodel.seasonKeepConfirmLine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Stitch sheets/dialogs mockups (dialogs-mockup-20261115): the
 * consent card, the report rows and the update dialog — each pinned
 * against its mockup, with real data from the code, never the mockup's
 * example numbers. The check-in circles and sheet tests were removed
 * 2026-11-17 with the coins/check-in removal.
 *
 * 2-button detail (Nov 2026): the two keep confirms are gone — the
 * movie confirm and the season confirm both died with their dialogs,
 * because the sheet row IS the confirmation now. What survives is the
 * season line itself: the whole-season sheet row still reads "12
 * episodes · 4.20 GB · Wi-Fi only", so its test stays.
 */
@RunWith(RobolectricTestRunner::class)
class DialogsMockupTest {

    @get:Rule
    val compose = createComposeRule()

    // ── consent card (the welcome + analytics mockups, one sheet) ──

    @Test
    fun consentDialogShowsAllowDeclineAndSkipWithoutChips() {
        compose.setContent {
            RaddStreamTheme {
                AnalyticsConsentDialog(onAccept = {}, onDecline = {}, onSkip = {})
            }
        }
        compose.onNodeWithText("Help improve Radd Stream").assertIsDisplayed()
        compose.onNodeWithText("Share anonymous usage data. Nothing personal is ever sent.").assertIsDisplayed()
        compose.onNodeWithText("Allow").assertIsDisplayed()
        compose.onNodeWithText("Don't allow").assertIsDisplayed()
        compose.onNodeWithText("Skip for now").assertIsDisplayed()
        compose.onNodeWithText("4K Ultra HD").assertDoesNotExist()
        compose.onNodeWithText("Dolby Atmos").assertDoesNotExist()
        compose.onNodeWithText("Share totals").assertDoesNotExist()
    }

    @Test
    fun consentAllowCallsAcceptAndSkipCallsSkip() {
        var accepted = false
        var skipped = false
        compose.setContent {
            RaddStreamTheme {
                AnalyticsConsentDialog(
                    onAccept = { accepted = true },
                    onDecline = {},
                    onSkip = { skipped = true },
                )
            }
        }
        compose.onNodeWithText("Allow").performClick()
        assertEquals(true, accepted)
        compose.onNodeWithText("Skip for now").performClick()
        assertEquals(true, skipped)
    }

    // ── season line (the whole-season sheet row) ──

    private fun pendingSeason(lockedCount: Int = 0) = PendingSeasonKeep(
        seasonNumber = 2,
        items = List(12) {
            SeasonKeepItem(
                handle = "handle-$it",
                printLabel = "1080p",
                title = "Episode ${it + 1}",
                sizeBytes = 350_000_000L,
            )
        },
        lockedCount = lockedCount,
        totalSizeBytes = 4_200_000_000L,
    )

    @Test
    fun seasonKeepConfirmLineJoinsCountSizeAndWifi() {
        assertEquals(
            "12 episodes · 4.20 GB · Wi-Fi only",
            seasonKeepConfirmLine(pendingSeason(), wifiOnly = true),
        )
        assertEquals(
            "1 episode · 350.0 MB",
            seasonKeepConfirmLine(
                pendingSeason().copy(
                    items = listOf(
                        SeasonKeepItem("h", "1080p", "Episode 1", 350_000_000L),
                    ),
                    totalSizeBytes = 350_000_000L,
                ),
                wifiOnly = false,
            ),
        )
    }

    // ── update dialog ──

    @Test
    fun updateDialogShowsLaterAndUpdate() {
        compose.setContent {
            RaddStreamTheme {
                AppUpdateDialog(required = false, onUpdateNow = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("A new version is ready").assertIsDisplayed()
        compose.onNodeWithText("Later").assertIsDisplayed()
        compose.onNodeWithText("Update").assertIsDisplayed()
    }

    @Test
    fun requiredUpdateHasNoLater() {
        compose.setContent {
            RaddStreamTheme {
                AppUpdateDialog(required = true, onUpdateNow = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("Update required").assertIsDisplayed()
        compose.onNodeWithText("Update").assertIsDisplayed()
        compose.onNodeWithText("Later").assertDoesNotExist()
    }
}
