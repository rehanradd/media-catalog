package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.HomeSection
import com.raddstream.app.viewmodel.HomeUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Home header (2026-11-17, coins removal): the header is logo-only —
 * the Check-in pill and the Coin Wallet pill are gone, and with them the
 * check-in sheet, the streak ladder and the coin balance. What this pins
 * is the header itself: the centred logo stays, no pill exists in any
 * state, and the filter chips under it still filter the rails in place,
 * with the hero card inset 16dp from the screen edges.
 */
@RunWith(RobolectricTestRunner::class)
class HomeHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun headerIsLogoOnlyWithNoCoinOrCheckInSurface() {
        compose.setContent {
            RaddStreamTheme {
                HomeContent(state = HomeUiState(), heroAutoplay = false)
            }
        }
        compose.onNodeWithTag(HomeLogoTag).assertIsDisplayed()
        // The pills are gone, not hidden: no tag, no text, in any state.
        compose.onNodeWithTag("checkInPill").assertDoesNotExist()
        compose.onNodeWithTag("coinWalletPill").assertDoesNotExist()
        compose.onNodeWithText("Check-in:").assertDoesNotExist()
        compose.onNodeWithText("COIN WALLET").assertDoesNotExist()
        compose.onNodeWithText("coins").assertDoesNotExist()
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
        val bounds = compose.onNodeWithTag("filterChip_All").fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue("ALL chip below 48dp touch height", bounds.height.toDp() >= 48.dp)
        }
    }

    @Test
    fun tappingAChipFiltersTheRailsInPlace() {
        compose.setContent {
            RaddStreamTheme { HomeContent(state = filterState(), heroAutoplay = false) }
        }
        // Existence, not display: the rails sit below the hero, so their
        // headers are below the first fold — and nothing may scroll before
        // the chip tap below, or the chip leaves the viewport with it.
        compose.onNodeWithText("Series Rail").assertExists()
        compose.onNodeWithText("Movies Rail").assertExists()
        compose.onNodeWithText("SERIES").performClick()
        compose.onNodeWithText("Movies Rail").assertDoesNotExist()
        compose.onNodeWithText("Series Rail").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingTheActiveChipAgainRestoresAll() {
        compose.setContent {
            RaddStreamTheme { HomeContent(state = filterState(), heroAutoplay = false) }
        }
        // Two taps in a row, nothing scrolled in between: the chip stays in
        // view for both, so the restore is a tap, not a scroll-then-tap.
        compose.onNodeWithText("SERIES").performClick()
        compose.onNodeWithText("Movies Rail").assertDoesNotExist()
        compose.onNodeWithText("SERIES").performClick()
        compose.onNodeWithText("Movies Rail").assertExists()
        compose.onNodeWithText("Series Rail").assertExists()
    }

    @Test
    fun filterEmptyStateOffersShowAll() {
        compose.setContent {
            RaddStreamTheme { HomeContent(state = filterState(), heroAutoplay = false) }
        }
        compose.onNodeWithText("ANIME").performClick()
        compose.onNodeWithText("Series Rail").assertDoesNotExist()
        compose.onNodeWithText("Movies Rail").assertDoesNotExist()
        compose.onNodeWithText("No anime here yet.").performScrollTo().assertIsDisplayed()
        // A click on a node below the fold lands off-viewport and never
        // fires — scroll the button into view first.
        compose.onNodeWithText("Show all").performScrollTo().performClick()
        compose.onNodeWithText("Series Rail").assertExists()
        compose.onNodeWithText("Movies Rail").assertExists()
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
        // left edge clears 16dp with room to spare. The WATCH NOW button
        // pins the same inset directly: it sits in the slide's 16dp-padded
        // column inside the 16dp-inset card, so its left edge clears 16dp
        // even on the art-less slide, where the title rides the fallback
        // card's centred label instead.
        val buttonBounds = compose.onNodeWithText("WATCH NOW").fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue("WATCH NOW starts under 16dp from the edge", buttonBounds.left.toDp() >= 16.dp)
        }
        val bounds = compose.onNodeWithText("Hero Title").fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue("hero title starts under 16dp from the edge", bounds.left.toDp() >= 16.dp)
        }
    }
}
