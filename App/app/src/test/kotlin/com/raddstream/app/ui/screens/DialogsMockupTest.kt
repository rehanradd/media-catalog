package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.KeepTarget
import com.raddstream.app.viewmodel.PendingKeep
import com.raddstream.app.viewmodel.PendingSeasonKeep
import com.raddstream.app.viewmodel.SeasonKeepItem
import com.raddstream.app.viewmodel.keepConfirmLine
import com.raddstream.app.viewmodel.seasonKeepConfirmLine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Stitch sheets/dialogs mockups (dialogs-mockup-20261115): the
 * consent card, the two keep confirms, the report rows and the update
 * dialog — each pinned against its mockup, with real data from the code,
 * never the mockup's example numbers. The check-in circles and sheet
 * tests were removed 2026-11-17 with the coins/check-in removal.
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

    // ── keep confirms ──

    private fun pendingKeep(sizeBytes: Long? = 1_300_000_000L) = PendingKeep(
        handle = "handle",
        printLabel = "1080p",
        title = "Salaar",
        sizeBytes = sizeBytes,
        resumable = true,
        target = KeepTarget.DeviceFile,
    )

    @Test
    fun keepConfirmLineJoinsTitleQualitySizeAndWifi() {
        assertEquals(
            "Salaar · 1080p · 1.30 GB · Wi-Fi only",
            keepConfirmLine(pendingKeep(), wifiOnly = true),
        )
        assertEquals(
            "Salaar · 1080p · 1.30 GB",
            keepConfirmLine(pendingKeep(), wifiOnly = false),
        )
        assertEquals(
            "Salaar · 1080p · Size unknown · Wi-Fi only",
            keepConfirmLine(pendingKeep(sizeBytes = null), wifiOnly = true),
        )
    }

    @Test
    fun keepConfirmLineDropsABlankQualityLabel() {
        // Dev-cleanup (Nov 2026): a print with no quality label leaves no
        // dangling separator in the confirm line.
        assertEquals(
            "Salaar · 1.30 GB · Wi-Fi only",
            keepConfirmLine(pendingKeep().copy(printLabel = ""), wifiOnly = true),
        )
    }

    @Test
    fun keepConfirmShowsOneLineAndDownloadButton() {
        var confirmed = false
        compose.setContent {
            RaddStreamTheme {
                KeepConfirmDialog(
                    pending = pendingKeep(),
                    wifiOnly = true,
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Download this title?").assertIsDisplayed()
        compose.onNodeWithText("Salaar · 1080p · 1.30 GB · Wi-Fi only").assertIsDisplayed()
        compose.onNodeWithText("Download", substring = false).performClick()
        assertEquals(true, confirmed)
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun keepConfirmOmitsWifiOnlyWhenSwitchIsOff() {
        compose.setContent {
            RaddStreamTheme {
                KeepConfirmDialog(
                    pending = pendingKeep(),
                    wifiOnly = false,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Salaar · 1080p · 1.30 GB").assertIsDisplayed()
        compose.onNodeWithText("Wi-Fi only").assertDoesNotExist()
    }

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

    @Test
    fun seasonKeepConfirmShowsCountLineAndButtons() {
        var confirmed = false
        compose.setContent {
            RaddStreamTheme {
                SeasonKeepConfirmDialog(
                    pending = pendingSeason(),
                    wifiOnly = true,
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Download season 2?").assertIsDisplayed()
        compose.onNodeWithText("12 episodes · 4.20 GB · Wi-Fi only").assertIsDisplayed()
        compose.onNodeWithText("Download", substring = false).performClick()
        assertEquals(true, confirmed)
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun seasonKeepConfirmNamesLockedEpisodes() {
        compose.setContent {
            RaddStreamTheme {
                SeasonKeepConfirmDialog(
                    pending = pendingSeason(lockedCount = 2),
                    wifiOnly = true,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("2 episodes need VIP and won't download.").assertIsDisplayed()
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
