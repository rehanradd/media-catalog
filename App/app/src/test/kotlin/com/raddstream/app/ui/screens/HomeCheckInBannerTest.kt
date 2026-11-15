package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeCheckInBannerTest {

    @get:Rule
    val compose = createComposeRule()

    private val ladder = mapOf(1 to 5, 2 to 5, 3 to 10)

    @Test
    fun checkInBannerRendersGuestAndClaimedActionsCleanly() {
        compose.setContent {
            RaddStreamTheme {
                CheckInBanner(
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
    fun checkInBannerRendersClaimButtonFromLadder() {
        compose.setContent {
            RaddStreamTheme {
                CheckInBanner(
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
    fun checkInBannerShowsWhileTheQualityGateIsOn() {
        // Positive control for the free-launch hiding below: with the gate
        // on (the default), the ladder renders the banner.
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = HomeUiState(streakLadder = ladder, paywallVisible = true),
                    heroAutoplay = false,
                )
            }
        }
        compose.onNodeWithText("Daily check-in").assertIsDisplayed()
    }

    @Test
    fun checkInBannerHidesWhileTheQualityGateIsOff() {
        // Free launch: gate OFF means every viewer is premium, so the
        // check-in banner hides even though the server published a ladder.
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

    @Test
    fun streakProgressCountsClaimedDaysAgainstTheLadder() {
        assertEquals(2f / 7f, streakProgress(serverStreak = 2, streakCount = 2, targetDays = 7), 0.001f)
        // A guest has no server streak: the local open count stands in.
        assertEquals(3f / 7f, streakProgress(serverStreak = null, streakCount = 3, targetDays = 7), 0.001f)
        // A streak past the ladder reads complete, never overfull.
        assertEquals(1f, streakProgress(serverStreak = 9, streakCount = 9, targetDays = 7), 0.001f)
        assertEquals(0f, streakProgress(serverStreak = 0, streakCount = 0, targetDays = 7), 0.001f)
    }

    @Test
    fun checkInSheetBodyShowsLadderAndClaim() {
        compose.setContent {
            RaddStreamTheme {
                CheckInSheetBody(
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
        compose.onNodeWithText("Day 1").assertIsDisplayed()
        // Days 1 and 2 both pay 5 in this ladder, so the text matches twice.
        compose.onAllNodesWithText("+5 coins").assertCountEquals(2)
        compose.onNodeWithText("Day 3").assertIsDisplayed()
        compose.onNodeWithText("Claim 10 coins for Day 3").assertIsDisplayed()
        compose.onNodeWithText("See all rewards").assertIsDisplayed()
    }

    @Test
    fun tappingTheBannerOpensTheSheet() {
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = HomeUiState(
                        streakLadder = ladder,
                        serverStreak = 2,
                        signedIn = true,
                        paywallVisible = true,
                    ),
                    heroAutoplay = false,
                )
            }
        }
        compose.onNodeWithTag(CheckInBannerTag).performClick()
        compose.onNodeWithText("See all rewards").assertIsDisplayed()
        compose.onNodeWithText("+10 coins").assertIsDisplayed()
    }
}
