package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pick 3 (home review, Nov 2026): the redesigned card is three poster
 * thumbnails in a row with a heart on each poster — the poster opens the
 * title, the heart saves it to My List — and the first display retires
 * the card, so it shows exactly once.
 */
@RunWith(RobolectricTestRunner::class)
class PickThreeCardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun post(id: Int) = Post(id = id, title = "Title $id", contentType = "movie")

    private fun setCard(
        toggled: MutableList<Int> = mutableListOf(),
        clicked: MutableList<Int> = mutableListOf(),
        onDismiss: () -> Unit = {},
        onShown: () -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                PickThreeCard(
                    candidates = listOf(post(7), post(8), post(9)),
                    isFavorite = { false },
                    onToggleFavorite = { toggled += it.id },
                    onPostClick = { clicked += it.id },
                    onDismiss = onDismiss,
                    onShown = onShown,
                )
            }
        }
    }

    @Test
    fun threePosterTilesWithAHeartOnEach() {
        setCard()

        compose.onNodeWithText("Pick 3 you like").assertIsDisplayed()
        compose.onNodeWithText("Tap the heart on three titles to start your My List.")
            .assertIsDisplayed()
        // One heart per poster, and no other "Save …" node on the card.
        compose.onAllNodesWithContentDescription("Save ", substring = true).assertCountEquals(3)
        compose.onNodeWithContentDescription("Title 7, Movie").assertIsDisplayed()
        compose.onNodeWithContentDescription("Title 8, Movie").assertIsDisplayed()
        compose.onNodeWithContentDescription("Title 9, Movie").assertIsDisplayed()
    }

    @Test
    fun heartTogglesTheFavouriteWithoutOpeningTheTitle() {
        val toggled = mutableListOf<Int>()
        val clicked = mutableListOf<Int>()
        setCard(toggled = toggled, clicked = clicked)

        compose.onNodeWithContentDescription("Save Title 7 to My List").performClick()

        assertEquals(listOf(7), toggled)
        assertTrue("heart tap must not also open the title", clicked.isEmpty())
    }

    @Test
    fun posterTapOpensTheTitle() {
        val clicked = mutableListOf<Int>()
        setCard(clicked = clicked)

        compose.onNodeWithContentDescription("Title 8, Movie").performClick()

        assertEquals(listOf(8), clicked)
    }

    @Test
    fun firstDisplayReportsShownOnce() {
        var shownCount = 0
        setCard(onShown = { shownCount++ })

        compose.waitForIdle()

        assertEquals(1, shownCount)
    }

    @Test
    fun heartHasA48dpTouchTarget() {
        setCard()

        val bounds = compose.onNodeWithContentDescription("Save Title 7 to My List")
            .fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue("heart below 48dp touch height", bounds.height.toDp() >= 48.dp)
            assertTrue("heart below 48dp touch width", bounds.width.toDp() >= 48.dp)
        }
    }

    @Test
    fun notNowDismissesTheCard() {
        var dismissed = false
        setCard(onDismiss = { dismissed = true })

        compose.onNodeWithText("Not now").performClick()

        assertTrue(dismissed)
    }
}
