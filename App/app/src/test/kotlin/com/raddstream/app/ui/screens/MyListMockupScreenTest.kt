package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.ListSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mockup (Nov 2026): the My List sort chips are 48dp tall, all three
 * fully visible in one row in mockup order, the active chip jade-filled
 * — and the "Most copies" rail sits below the grid as a full-span
 * footer whenever the trending load produced titles. Runs on the JVM
 * under Robolectric because this project has no emulator image.
 *
 * Requires an x86_64 runner. Robolectric's native runtime has no
 * Linux/aarch64 build (robolectric/robolectric#9166) and it fails while
 * the runner boots, so the exclusion for ARM hosts lives in
 * app/build.gradle.kts rather than here.
 */
@RunWith(RobolectricTestRunner::class)
class MyListMockupScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun post(id: Int, title: String) = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = 2024,
        contentType = "movie",
        // No poster URL: the card renders the branded fallback, so no image
        // load runs in the test.
        posterUrl = null,
    )

    private fun chipLeft(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left

    private fun chipHeightDp(tag: String) =
        with(compose.density) {
            compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height.toDp()
        }

    @Test
    fun sortChipsAreInMockupOrderWithRecentlyAddedSelected() {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(390.dp)) {
                    MyListSortRow(sort = ListSort.RECENT, onSort = {})
                }
            }
        }
        // All three are on screen, in mockup order, left to right.
        compose.onNodeWithTag("myListSortChip_RECENT").assertIsDisplayed()
        compose.onNodeWithTag("myListSortChip_TITLE").assertIsDisplayed()
        compose.onNodeWithTag("myListSortChip_UNWATCHED").assertIsDisplayed()
        val lefts = listOf(
            chipLeft("myListSortChip_RECENT"),
            chipLeft("myListSortChip_TITLE"),
            chipLeft("myListSortChip_UNWATCHED"),
        )
        assertTrue("sort chips read left-to-right as $lefts", lefts == lefts.sorted())
        // Only the active chip is selected.
        compose.onNodeWithTag("myListSortChip_RECENT").assertIsSelected()
        compose.onNodeWithTag("myListSortChip_TITLE").assertIsNotSelected()
        compose.onNodeWithTag("myListSortChip_UNWATCHED").assertIsNotSelected()
    }

    @Test
    fun sortChipsAre48dpTall() {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(390.dp)) {
                    MyListSortRow(sort = ListSort.RECENT, onSort = {})
                }
            }
        }
        // FilterChip's own visual height is 32dp — the mockup's chips are
        // 48dp, the touch-target floor, so the chip itself carries it.
        listOf("myListSortChip_RECENT", "myListSortChip_TITLE", "myListSortChip_UNWATCHED")
            .forEach { tag ->
                assertEquals("$tag is not 48dp tall", 48.dp, chipHeightDp(tag))
            }
    }

    @Test
    fun tappingASortChipSelectsIt() {
        var selected by mutableStateOf(ListSort.RECENT)
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(390.dp)) {
                    MyListSortRow(sort = selected, onSort = { selected = it })
                }
            }
        }
        compose.onNodeWithTag("myListSortChip_TITLE").performClick()
        compose.onNodeWithTag("myListSortChip_TITLE").assertIsSelected()
        compose.onNodeWithTag("myListSortChip_RECENT").assertIsNotSelected()
        assertEquals(ListSort.TITLE, selected)
    }

    @Test
    fun popularRowRendersTheMostCopiesRail() {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(390.dp)) {
                    PopularRow(
                        popular = listOf(
                            post(1, "Inception"),
                            post(2, "Dune"),
                            post(3, "McLintock!"),
                        ),
                        onPostClick = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Most copies").assertIsDisplayed()
        compose.onNodeWithText("Well-stocked catalog picks to start your list")
            .assertIsDisplayed()
        compose.onNodeWithText("Inception").assertIsDisplayed()
        compose.onNodeWithText("Dune").assertIsDisplayed()
        compose.onNodeWithText("McLintock!").assertIsDisplayed()
    }
}
