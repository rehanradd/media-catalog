package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.AccountOverviewUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfilePaywallTest {

    @get:Rule
    val compose = createComposeRule()

    private val signedIn = AccountOverviewUiState(
        signedIn = true,
        coinBalance = 25,
        currentStreak = 3,
        bestStreak = 5,
        isVip = false,
        paywallVisible = true,
    )

    @Test
    fun paywallLinesShowWhileTheQualityGateIsOn() {
        compose.setContent {
            RaddStreamTheme {
                ProfileContent(
                    state = signedIn,
                    onBackClick = {},
                    onOpenDashboard = {},
                    onOpenCoins = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("Free account").assertIsDisplayed()
        compose.onNodeWithText("Check-in streak: 3 days (best 5).").assertIsDisplayed()
        compose.onNodeWithText("Coins: 25.").assertIsDisplayed()
        compose.onNodeWithText("Coins and history").assertIsDisplayed()
        compose.onNodeWithText("Open dashboard").assertIsDisplayed()
        compose.onNodeWithText("Sign out").assertIsDisplayed()
    }

    @Test
    fun paywallLinesHideWhileTheQualityGateIsOff() {
        // Free launch: gate OFF means every viewer is premium — the streak
        // and coins lines and the coins door hide, the membership line says
        // Premium member, and the dashboard and sign-out doors stay.
        compose.setContent {
            RaddStreamTheme {
                ProfileContent(
                    state = signedIn.copy(paywallVisible = false),
                    onBackClick = {},
                    onOpenDashboard = {},
                    onOpenCoins = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("Free account").assertDoesNotExist()
        compose.onNodeWithText("Check-in streak: 3 days (best 5).").assertDoesNotExist()
        compose.onNodeWithText("Coins: 25.").assertDoesNotExist()
        compose.onNodeWithText("Coins and history").assertDoesNotExist()
        compose.onNodeWithText("Premium member").assertIsDisplayed()
        compose.onNodeWithText("Open dashboard").assertIsDisplayed()
        compose.onNodeWithText("Sign out").assertIsDisplayed()
    }
}
