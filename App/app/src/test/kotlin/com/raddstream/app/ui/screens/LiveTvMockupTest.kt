package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.LiveTvChannel
import com.raddstream.app.ui.navigation.BottomNavTab
import com.raddstream.app.ui.navigation.RaddBottomBar
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Stitch Live TV mockup (Nov 2026): the search field is the app's shared
 * filled pill, the chips render "All" first with the jade-selected state,
 * the cards show name + "category · country · language" + the LIVE badge,
 * and the bottom nav keeps its title-case labels — the mockup drew them
 * ALL-CAPS, which is Stitch's invention and is never copied.
 */
@RunWith(RobolectricTestRunner::class)
class LiveTvMockupTest {

    @get:Rule
    val compose = createComposeRule()

    private fun channel(name: String = "Geo News") = LiveTvChannel(
        id = 1,
        name = name,
        category = "news",
        country = "PK",
        language = "urdu",
        logoUrl = "",
        streamId = "stream-1",
    )

    @Test
    fun categoryRowRendersAllFirstThenCategoriesInOrder() {
        compose.setContent {
            RaddStreamTheme {
                LiveTvCategoryRow(
                    categories = listOf("news", "sports"),
                    selectedCategory = null,
                    onCategorySelected = {},
                )
            }
        }
        // The row lays its chips out left to right in list order: "All"
        // first, then the server's categories as they arrived.
        val allLeft = compose.onNodeWithTag("liveTv_chip_all")
            .fetchSemanticsNode().boundsInRoot.left
        val newsLeft = compose.onNodeWithTag("liveTv_chip_news")
            .fetchSemanticsNode().boundsInRoot.left
        val sportsLeft = compose.onNodeWithTag("liveTv_chip_sports")
            .fetchSemanticsNode().boundsInRoot.left
        assertTrue("All chip is not first", allLeft < newsLeft)
        assertTrue("News chip is not before Sports", newsLeft < sportsLeft)
    }

    @Test
    fun categoryRowMarksOnlyTheSelectedChip() {
        compose.setContent {
            RaddStreamTheme {
                LiveTvCategoryRow(
                    categories = listOf("news", "sports"),
                    selectedCategory = "news",
                    onCategorySelected = {},
                )
            }
        }
        compose.onNodeWithText("All").assertIsNotSelected()
        compose.onNodeWithText("News").assertIsSelected()
        compose.onNodeWithText("Sports").assertIsNotSelected()
    }

    @Test
    fun categoryRowReportsTheTappedCategory() {
        var tapped: String? = "unset"
        compose.setContent {
            RaddStreamTheme {
                LiveTvCategoryRow(
                    categories = listOf("news", "sports"),
                    selectedCategory = null,
                    onCategorySelected = { tapped = it },
                )
            }
        }
        compose.onNodeWithText("Sports").performClick()
        assertEquals("sports", tapped)
        compose.onNodeWithText("All").performClick()
        assertEquals(null, tapped)
    }

    @Test
    fun liveTvSearchFieldShowsPlaceholderAndClears() {
        var query by mutableStateOf("")
        compose.setContent {
            RaddStreamTheme {
                SearchField(
                    query = query,
                    onQueryChanged = { query = it },
                    onSubmit = {},
                    label = "Search channels",
                )
            }
        }
        // Empty: the prompt is a placeholder, not a floating label.
        compose.onNodeWithText("Search channels").assertIsDisplayed()
        // Type into the field itself (found by its accessible name).
        compose.onNodeWithContentDescription("Search channels").performTextInput("geo")
        assertEquals("geo", query)
        compose.onNodeWithText("geo").assertIsDisplayed()
        compose.onNodeWithText("Search channels").assertDoesNotExist()

        // Non-empty: the clear button empties it again, and the prompt returns.
        compose.onNodeWithContentDescription("Clear search").performClick()
        assertEquals("", query)
        compose.onNodeWithText("Search channels").assertIsDisplayed()
    }

    @Test
    fun channelCardAnnouncesNameMetaLineAndLive() {
        compose.setContent {
            RaddStreamTheme {
                LiveTvChannelCard(
                    channel = channel(),
                    onClick = {},
                    modifier = Modifier.width(180.dp),
                )
            }
        }
        // The name, meta line and badge are paint (Robo run 2: one spoken
        // sentence per card), so the card's own sentence is the pin: name,
        // ", live" from the badge, then the meta line.
        compose.onNode(
            hasContentDescription("Geo News, live · News · Pakistan · Urdu"),
        ).assertExists()
    }

    @Test
    fun bottomNavLabelsStayTitleCase() {
        // Built the way NavGraph does: the same bar, the same tab. The
        // mockup drew the labels ALL-CAPS; the app ships title case, and
        // this pins that no mockup pass ever uppercases them.
        val labels = listOf("Home", "Search", "My List", "Live TV", "Settings")
        val icons = listOf(
            Icons.Default.Home,
            Icons.Default.Search,
            Icons.Default.Favorite,
            Icons.Default.Tv,
            Icons.Default.Settings,
        )
        compose.setContent {
            RaddStreamTheme {
                RaddBottomBar(modifier = Modifier.windowInsetsPadding(WindowInsets(bottom = 0.dp))) {
                    labels.forEachIndexed { index, label ->
                        BottomNavTab(
                            label = label,
                            icon = icons[index],
                            selected = label == "Live TV",
                            onClick = {},
                        )
                    }
                }
            }
        }
        for (label in labels) {
            compose.onNode(hasContentDescription(label)).assertExists()
            compose.onNode(hasContentDescription(label.uppercase())).assertDoesNotExist()
        }
    }
}
