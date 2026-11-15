package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.HomeUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeCheckInNudgeTest {

    @get:Rule
    val compose = createComposeRule()

    private val ladder = mapOf(1 to 5, 2 to 5, 3 to 10)

    @Test
    fun checkInNudgeRendersGuestAndClaimedActionsCleanly() {
        compose.setContent {
            RaddStreamTheme {
                CheckInNudge(
                    streakCount = 1,
                    serverStreak = null,
                    streakLadder = ladder,
                    coinBalance = 0,
                    signedIn = false,
                    claimedToday = false,
                    isClaiming = false,
                    claimMessage = null,
                    onClaim = {},
                )
            }
        }
        compose.onNodeWithText("Daily check-in").assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertIsDisplayed()
        val bounds = compose.onNodeWithText("Sign in").fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue("Sign in below 48dp touch height", bounds.height.toDp() >= 48.dp)
        }
    }

    @Test
    fun checkInNudgeRendersClaimButtonFromLadder() {
        compose.setContent {
            RaddStreamTheme {
                CheckInNudge(
                    streakCount = 2,
                    serverStreak = 2,
                    streakLadder = ladder,
                    coinBalance = 25,
                    signedIn = true,
                    claimedToday = false,
                    isClaiming = false,
                    claimMessage = null,
                    onClaim = {},
                )
            }
        }
        compose.onNodeWithText("Day 2 of 3 · 25 coins").assertIsDisplayed()
        compose.onNodeWithText("Claim 10 coins for Day 3").assertIsDisplayed()
    }

    @Test
    fun checkInNudgeShowsWhileTheQualityGateIsOn() {
        // Positive control for the free-launch hiding below: with the gate
        // on (the default), the ladder renders the strip.
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = HomeUiState(streakLadder = ladder, paywallVisible = true),
                    heroAutoplay = false,
                )
            }
        }
        compose.onNodeWithText("Daily check-in").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun checkInNudgeHidesWhileTheQualityGateIsOff() {
        // Free launch: gate OFF means every viewer is premium, so the
        // check-in strip hides even though the server published a ladder.
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = HomeUiState(streakLadder = ladder, paywallVisible = false),
                    heroAutoplay = false,
                )
            }
        }
        compose.onNodeWithText("Daily check-in").assertDoesNotExist()
    }
}
