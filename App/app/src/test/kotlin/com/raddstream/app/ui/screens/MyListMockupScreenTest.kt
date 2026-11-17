package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.ListSort
import com.raddstream.app.viewmodel.MyListUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mockup (Nov 2026): the My List sort chips are 48dp tall, all three
 * fully visible in one row in mockup order, the active chip jade-filled
 * — and the "Trending to Start Your List" rail sits below the grid as a full-span
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

    // fetchSemanticsNodes() is a member of SemanticsNodeInteractionCollection,
    // not a top-level function — it needs no import (BottomNavTouchTargetTest
    // uses it the same way). .single() because each tag is unique.
    private fun chipNode(tag: String) =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().single()

    private fun chipLeft(tag: String): Float = chipNode(tag).boundsInRoot.left

    private fun chipHeightDp(tag: String) =
        with(compose.density) { chipNode(tag).boundsInRoot.height.toDp() }

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
    fun popularRowRendersTheTrendingRail() {
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
        // My List review (Nov 2026): the rail's old "Most copies" title
        // read like warehouse inventory; the shelf now says what it is.
        compose.onNodeWithText("Trending to Start Your List").assertIsDisplayed()
        compose.onNodeWithText("Trending titles to start your list")
            .assertIsDisplayed()
        compose.onNodeWithText("Inception").assertIsDisplayed()
        compose.onNodeWithText("Dune").assertIsDisplayed()
        compose.onNodeWithText("McLintock!").assertIsDisplayed()
    }

    // ── My List review fixes (Nov 2026) ──────────────────────────────

    private fun loadedState(
        favorites: List<Post>,
        hiddenCount: Int = 0,
        popular: List<Post> = emptyList(),
        progressByPostId: Map<Int, Float> = emptyMap(),
        progressLabelByPostId: Map<Int, String> = emptyMap(),
    ) = MyListUiState(
        favorites = favorites,
        sort = ListSort.RECENT,
        hiddenCount = hiddenCount,
        popular = popular,
        progressByPostId = progressByPostId,
        progressLabelByPostId = progressLabelByPostId,
    )

    private fun loadedContent(state: MyListUiState) {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(390.dp).fillMaxSize()) {
                    MyListLoadedContent(
                        state = state,
                        onPostClick = {},
                        onRemove = {},
                        onSort = {},
                        isFavorite = { false },
                        onToggleFavorite = {},
                    )
                }
            }
        }
    }

    @Test
    fun savedCountNamesEverySavedTitle() {
        loadedContent(loadedState(listOf(post(1, "Inception"), post(2, "Dune"))))
        compose.onNodeWithText("2 titles").assertIsDisplayed()
    }

    @Test
    fun savedCountIsSingularForOneTitle() {
        loadedContent(loadedState(listOf(post(1, "Inception"))))
        compose.onNodeWithText("1 title").assertIsDisplayed()
    }

    @Test
    fun savedCountIncludesHiddenTitles() {
        // A row this build may not draw is still saved: one visible
        // title plus one hidden one is two titles, not one.
        loadedContent(loadedState(listOf(post(1, "Inception")), hiddenCount = 1))
        compose.onNodeWithText("2 titles").assertIsDisplayed()
    }

    @Test
    fun halfWatchedTileSpeaksItsProgress() {
        loadedContent(
            loadedState(
                favorites = listOf(post(1, "Inception")),
                progressByPostId = mapOf(1 to 0.5f),
                progressLabelByPostId = mapOf(1 to "S2:E5 · 23 min left"),
            ),
        )
        // The tile's one spoken sentence carries the words now — the
        // review's mute grey bar said nothing about which episode or
        // how much was left.
        compose.onNodeWithContentDescription("Inception, 2024 · Movie, S2:E5 · 23 min left")
            .assertIsDisplayed()
    }

    @Test
    fun starterShelfShowsWhileTheListIsSmall() {
        loadedContent(
            loadedState(
                favorites = listOf(post(1, "Inception")),
                popular = listOf(post(2, "Dune"), post(3, "McLintock!")),
            ),
        )
        compose.onNodeWithText("Trending to Start Your List").assertIsDisplayed()
    }

    @Test
    fun starterShelfHidesOnceTheListGrows() {
        // My List review (Nov 2026): the shelf is a *starter* shelf —
        // a two-save list shows it the same way in every session,
        // whether the ViewModel was born onto one save or two.
        loadedContent(
            loadedState(
                favorites = listOf(post(1, "Inception"), post(2, "Dune")),
                popular = listOf(post(3, "McLintock!"), post(4, "Gen V")),
            ),
        )
        compose.onNodeWithText("Trending to Start Your List").assertDoesNotExist()
    }

    // ── Hidden list ───────────────────────────────────────────────────

    private fun hiddenContent(
        popular: List<Post> = emptyList(),
        popularLoading: Boolean = false,
    ) {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(390.dp)) {
                    MyListHiddenContent(
                        hiddenNotice = "2 saved titles are no longer offered by this server",
                        popular = popular,
                        popularLoading = popularLoading,
                        onPostClick = {},
                        onBrowseClick = {},
                    )
                }
            }
        }
    }

    @Test
    fun hiddenBranchShowsTheNoticeAndTheWayOut() {
        hiddenContent()
        compose.onNodeWithText("Nothing to show from your list").assertIsDisplayed()
        compose.onNodeWithText("2 saved titles are no longer offered by this server")
            .assertIsDisplayed()
        compose.onNodeWithText("Browse the catalog").assertIsDisplayed()
    }

    @Test
    fun browseButtonIs48dpTall() {
        hiddenContent()
        val height = with(compose.density) {
            compose.onAllNodesWithTag(MyListBrowseButtonTag)
                .fetchSemanticsNodes().single().boundsInRoot.height.toDp()
        }
        assertEquals("Browse button is not 48dp tall", 48.dp, height)
    }

    @Test
    fun hiddenBranchShowsTheTrendingRailWhenLoaded() {
        hiddenContent(popular = listOf(post(1, "Inception"), post(2, "Dune")))
        compose.onNodeWithText("Trending to Start Your List").assertIsDisplayed()
        compose.onNodeWithText("Inception").assertIsDisplayed()
    }

    @Test
    fun hiddenBranchShowsARailSkeletonWhileLoading() {
        hiddenContent(popularLoading = true)
        compose.onNodeWithTag("posterRailSkeleton").assertIsDisplayed()
        compose.onNodeWithText("Trending to Start Your List").assertDoesNotExist()
    }
}
