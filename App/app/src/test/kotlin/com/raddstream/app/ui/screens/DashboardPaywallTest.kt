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
 * The Dashboard paywall (2026-11-17, coins removal): the coins card, the
 * check-in streak card and the "Coins and history" door are gone
 * unconditionally — they are not gated, they do not exist. What stays
 * gated on the quality gate is the VIP upsell: "See VIP options" shows
 * only while the gate is ON, and the membership card says Premium with no
 * upsell while it is OFF.
 */
@RunWith(RobolectricTestRunner::class)
class DashboardPaywallTest {

    @get:Rule
    val compose = createComposeRule()

    private val signedIn = AccountOverviewUiState(
        signedIn = true,
        isVip = false,
        paywallVisible = true,
    )

    @Test
    fun vipUpsellShowsWhileTheQualityGateIsOnAndCoinsAreGone() {
        compose.setContent {
            RaddStreamTheme {
                DashboardContent(
                    state = signedIn,
                    onBackClick = {},
                    onOpenProfile = {},
                    onOpenVipUpgrade = {},
                )
            }
        }
        compose.onNodeWithText("See VIP options").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your profile").performScrollTo().assertIsDisplayed()
        // Removed, not hidden: no coins or check-in surface exists even
        // with the gate ON.
        compose.onNodeWithText("Coins").assertDoesNotExist()
        compose.onNodeWithText("Check-in streak").assertDoesNotExist()
        compose.onNodeWithText("Coins and history").assertDoesNotExist()
    }

    @Test
    fun paywallSurfacesHideWhileTheQualityGateIsOff() {
        // Free launch: gate OFF means every viewer is premium — the VIP
        // upsell hides, the membership card says Premium with no upsell,
        // and the profile door stays.
        compose.setContent {
            RaddStreamTheme {
                DashboardContent(
                    state = signedIn.copy(paywallVisible = false),
                    onBackClick = {},
                    onOpenProfile = {},
                    onOpenVipUpgrade = {},
                )
            }
        }
        compose.onNodeWithText("See VIP options").assertDoesNotExist()
        compose.onNodeWithText("Coins").assertDoesNotExist()
        compose.onNodeWithText("Check-in streak").assertDoesNotExist()
        compose.onNodeWithText("Coins and history").assertDoesNotExist()
        compose.onNodeWithText("Premium").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("All qualities and downloads included.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your profile").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun guestHintHidesWhileTheQualityGateIsOff() {
        // The signed-out hint only renders while the gate is ON; with the
        // gate OFF a guest sees the Premium card and no hint at all.
        compose.setContent {
            RaddStreamTheme {
                DashboardContent(
                    state = AccountOverviewUiState(signedIn = false, paywallVisible = false),
                    onBackClick = {},
                    onOpenProfile = {},
                    onOpenVipUpgrade = {},
                )
            }
        }
        compose.onNodeWithText("Sign in from Settings to see your coins, streak and membership here.").assertDoesNotExist()
        compose.onNodeWithText("Sign in from Settings to see your membership here.").assertDoesNotExist()
        compose.onNodeWithText("Premium").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun guestHintNamesMembershipNotCoinsWhileTheQualityGateIsOn() {
        compose.setContent {
            RaddStreamTheme {
                DashboardContent(
                    state = AccountOverviewUiState(signedIn = false, paywallVisible = true),
                    onBackClick = {},
                    onOpenProfile = {},
                    onOpenVipUpgrade = {},
                )
            }
        }
        compose.onNodeWithText("Sign in from Settings to see your coins, streak and membership here.").assertDoesNotExist()
        compose.onNodeWithText("Sign in from Settings to see your membership here.").performScrollTo().assertIsDisplayed()
    }
}
