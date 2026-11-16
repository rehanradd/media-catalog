package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.AccountOverviewUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Profile paywall (2026-11-17, coins removal): the streak line, the
 * coins line and the "Coins and history" door are gone unconditionally.
 * What stays is the membership line — "Free account" / "VIP member" while
 * the quality gate is ON, "Premium member" while it is OFF — and the
 * dashboard and sign-out doors.
 */
@RunWith(RobolectricTestRunner::class)
class ProfilePaywallTest {

    @get:Rule
    val compose = createComposeRule()

    private val signedIn = AccountOverviewUiState(
        signedIn = true,
        isVip = false,
        paywallVisible = true,
    )

    @Test
    fun paywallLinesShowWhileTheQualityGateIsOnAndCoinsAreGone() {
        compose.setContent {
            RaddStreamTheme {
                ProfileContent(
                    state = signedIn,
                    onBackClick = {},
                    onOpenDashboard = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("Free account").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open dashboard").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sign out").performScrollTo().assertIsDisplayed()
        // Removed, not hidden: no streak line, no coins line, no coins door.
        compose.onNodeWithText("Check-in streak: 3 days (best 5).").assertDoesNotExist()
        compose.onNodeWithText("Coins: 25.").assertDoesNotExist()
        compose.onNodeWithText("Coins and history").assertDoesNotExist()
    }

    @Test
    fun paywallLinesHideWhileTheQualityGateIsOff() {
        // Free launch: gate OFF means every viewer is premium — the
        // membership line says Premium member, and the dashboard and
        // sign-out doors stay.
        compose.setContent {
            RaddStreamTheme {
                ProfileContent(
                    state = signedIn.copy(paywallVisible = false),
                    onBackClick = {},
                    onOpenDashboard = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("Free account").assertDoesNotExist()
        compose.onNodeWithText("Check-in streak: 3 days (best 5).").assertDoesNotExist()
        compose.onNodeWithText("Coins: 25.").assertDoesNotExist()
        compose.onNodeWithText("Coins and history").assertDoesNotExist()
        compose.onNodeWithText("Premium member").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open dashboard").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sign out").performScrollTo().assertIsDisplayed()
    }
}
