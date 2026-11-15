package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.HomeSection
import com.raddstream.app.viewmodel.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Home header (mockup, Nov 2026): one compact row pinned above the
 * scroll — check-in pill left, centred two-line logo, Coin Wallet pill
 * right — replacing the M12 banner. The claim flow and streak logic are
 * untouched (they live in the sheet now, pinned by the sheet-body test
 * below); what this pins is the header itself: the pills render, the gate
 * hides them, the logo stays, the pill opens the sheet, the wallet pill
 * opens the wallet — and the filter chips under it filter the rails in
 * place, with the hero card inset 16dp from the screen edges.
 */
@RunWith(RobolectricTestRunner::class)
class HomeHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    private val ladder = mapOf(1 to 5, 2 to 5, 3 to 10)

    @Test
    fun headerShowsCheckInPillWalletPillAndCentredLogo() {
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = HomeUiState(
                        streakLadder = ladder,
                        serverStreak = 2,
                        signedIn = true,
                        coinBalance = 25,
                        paywallVisible = true,
                    ),
                    heroAutoplay = false,
                )
            }
        }
        compose.onNodeWithTag(CheckInPillTag).assertIsDisplayed()
        compose.onNodeWithTag(CoinWalletPillTag).assertIsDisplayed()
        compose.onNodeWithTag(HomeLogoTag).assertIsDisplayed()
        compose.onNodeWithText("Check-in:").assertIsDisplayed()
        compose.onNodeWithText("Day 2 of 3").assertIsDisplayed()
        compose.onNodeWithText("COIN WALLET").assertIsDisplayed()
        compose.onNodeWithText("25 coins").assertIsDisplayed()
    }

    @Test
    fun headerGuestShowsSignIn() {
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = HomeUiState(
                        streakLadder = ladder,
                        signedIn = false,
                        paywallVisible = true,
                    ),
                    heroAutoplay = false,
                )
            }
        }
        compose.onNodeWithText("Sign in").assertIsDisplayed()
    }

    @Test
    fun headerClaimedShowsClaimed() {
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = HomeUiState(
                        streakLadder = ladder,
                        serverStreak = 2,
                        signedIn = true,
                        claimedToday = true,
                        paywallVisible = true,
                    ),
                    heroAutoplay = false,
                )
            }
        }
        compose.onNodeWithText("Claimed").assertIsDisplayed()
    }

    @Test
    fun headerHidesPillsWhileTheQualityGateIsOff() {
        // Free launch: gate OFF means every viewer is premium, so both
        // pills hide even though the server published a ladder — and the
        // centred logo stays on its own.
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = HomeUiState(streakLadder = ladder, paywallVisible = false),
                    heroAutoplay = false,
                )
            }
        }
        compose.onNodeWithTag(CheckInPillTag).assertDoesNotExist()
        compose.onNodeWithTag(CoinWalletPillTag).assertDoesNotExist()
        compose.onNodeWithTag(HomeLogoTag).assertIsDisplayed()
    }

    @Test
    fun tappingTheCheckInPillOpensTheSheet() {
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
        compose.onNodeWithTag(CheckInPillTag).performClick()
        compose.onNodeWithText("See all rewards").assertIsDisplayed()
        compose.onNodeWithText("+10 coins").assertIsDisplayed()
    }

    @Test
    fun tappingTheCoinPillOpensTheWallet() {
        var walletOpened = false
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = HomeUiState(streakLadder = ladder, paywallVisible = true),
                    onOpenWallet = { walletOpened = true },
                    heroAutoplay = false,
                )
            }
        }
        compose.onNodeWithTag(CoinWalletPillTag).performClick()
        assertTrue(walletOpened)
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

    // ── the filter chips ───────────────────────────────────────────────

    private fun fakePost(id: Int, title: String, contentType: String) = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = 2026,
        contentType = contentType,
        posterUrl = null,
        imdbRating = "7.0",
        genres = "Drama",
        industry = "Bollywood",
        language = "Hindi",
    )

    private fun filterState() = HomeUiState(
        sections = listOf(
            HomeSection(
                key = "series",
                title = "Series Rail",
                type = "series",
                items = (1..7).map { fakePost(100 + it, "Series Show $it", "series") },
            ),
            HomeSection(
                key = "movie",
                title = "Movies Rail",
                type = "movie",
                items = (1..7).map { fakePost(200 + it, "Movie Title $it", "movie") },
            ),
        ),
        streakLadder = ladder,
        paywallVisible = true,
    )

    @Test
    fun filterChipsShowAllFiveAndAre48dpTall() {
        compose.setContent {
            RaddStreamTheme { HomeContent(state = filterState(), heroAutoplay = false) }
        }
        compose.onNodeWithTag(HomeFilterRowTag).assertIsDisplayed()
        listOf("ALL", "MOVIES", "SERIES", "ANIME", "KPOP").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        val bounds = compose.onNodeWithTag("filterChip_ALL").fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue("ALL chip below 48dp touch height", bounds.height.toDp() >= 48.dp)
        }
    }

    @Test
    fun tappingAChipFiltersTheRailsInPlaceAndTappingAgainRestoresAll() {
        compose.setContent {
            RaddStreamTheme { HomeContent(state = filterState(), heroAutoplay = false) }
        }
        compose.onNodeWithText("Series Rail").assertIsDisplayed()
        compose.onNodeWithText("Movies Rail").assertIsDisplayed()
        compose.onNodeWithText("SERIES").performClick()
        compose.onNodeWithText("Series Rail").assertIsDisplayed()
        compose.onNodeWithText("Movies Rail").assertDoesNotExist()
        // Tapping the active chip again restores everything.
        compose.onNodeWithText("SERIES").performClick()
        compose.onNodeWithText("Movies Rail").assertIsDisplayed()
    }

    @Test
    fun filterEmptyStateOffersShowAll() {
        compose.setContent {
            RaddStreamTheme { HomeContent(state = filterState(), heroAutoplay = false) }
        }
        compose.onNodeWithText("ANIME").performClick()
        compose.onNodeWithText("Series Rail").assertDoesNotExist()
        compose.onNodeWithText("Movies Rail").assertDoesNotExist()
        compose.onNodeWithText("No anime here yet.").assertIsDisplayed()
        compose.onNodeWithText("Show all").performClick()
        compose.onNodeWithText("Series Rail").assertIsDisplayed()
        compose.onNodeWithText("Movies Rail").assertIsDisplayed()
    }

    // ── the hero card ──────────────────────────────────────────────────

    @Test
    fun heroCardIsInsetWithUppercaseButtons() {
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = HomeUiState(
                        sections = listOf(
                            HomeSection(
                                key = "recent",
                                title = "Recently added",
                                type = null,
                                items = listOf(fakePost(1, "Hero Title", "movie")),
                            ),
                        ),
                        streakLadder = ladder,
                        paywallVisible = true,
                    ),
                    heroAutoplay = false,
                )
            }
        }
        // The buttons read in ALL CAPS.
        compose.onNodeWithText("WATCH NOW").assertIsDisplayed()
        compose.onNodeWithText("MY LIST").assertIsDisplayed()
        // The inset card: the slide's title starts 16dp in from the screen
        // edge (the card inset) plus its own 16dp of text padding, so its
        // left edge clears 16dp with room to spare.
        val bounds = compose.onNodeWithText("Hero Title").fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue("hero title starts under 16dp from the edge", bounds.left.toDp() >= 16.dp)
        }
    }
}
