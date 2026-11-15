package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertDoesNotExist
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
class DashboardPaywallTest {

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
    fun paywallSurfacesShowWhileTheQualityGateIsOn() {
        compose.setContent {
            RaddStreamTheme {
                DashboardContent(
                    state = signedIn,
                    onBackClick = {},
                    onOpenCheckIn = {},
                    onOpenCoins = {},
                    onOpenProfile = {},
                    onOpenVipUpgrade = {},
                )
            }
        }
        compose.onNodeWithText("Coins").assertIsDisplayed()
        compose.onNodeWithText("25 coins").assertIsDisplayed()
        compose.onNodeWithText("Check-in streak").assertIsDisplayed()
        compose.onNodeWithText("See VIP options").assertIsDisplayed()
        compose.onNodeWithText("Coins and history").assertIsDisplayed()
        compose.onNodeWithText("Your profile").assertIsDisplayed()
    }

    @Test
    fun paywallSurfacesHideWhileTheQualityGateIsOff() {
        // Free launch: gate OFF means every viewer is premium — the coins
        // card, the streak card and the coins door hide, the membership
        // card says Premium with no upsell, and the profile door stays.
        compose.setContent {
            RaddStreamTheme {
                DashboardContent(
                    state = signedIn.copy(paywallVisible = false),
                    onBackClick = {},
                    onOpenCheckIn = {},
                    onOpenCoins = {},
                    onOpenProfile = {},
                    onOpenVipUpgrade = {},
                )
            }
        }
        compose.onNodeWithText("Coins").assertDoesNotExist()
        compose.onNodeWithText("25 coins").assertDoesNotExist()
        compose.onNodeWithText("Check-in streak").assertDoesNotExist()
        compose.onNodeWithText("See VIP options").assertDoesNotExist()
        compose.onNodeWithText("Coins and history").assertDoesNotExist()
        compose.onNodeWithText("Premium").assertIsDisplayed()
        compose.onNodeWithText("All qualities and downloads included.").assertIsDisplayed()
        compose.onNodeWithText("Your profile").assertIsDisplayed()
    }

    @Test
    fun guestLineHidesWhileTheQualityGateIsOff() {
        compose.setContent {
            RaddStreamTheme {
                DashboardContent(
                    state = AccountOverviewUiState(signedIn = false, paywallVisible = false),
                    onBackClick = {},
                    onOpenCheckIn = {},
                    onOpenCoins = {},
                    onOpenProfile = {},
                    onOpenVipUpgrade = {},
                )
            }
        }
        compose.onNodeWithText("Sign in from Settings to see your coins, streak and membership here.").assertDoesNotExist()
        compose.onNodeWithText("Premium").assertIsDisplayed()
    }
}
