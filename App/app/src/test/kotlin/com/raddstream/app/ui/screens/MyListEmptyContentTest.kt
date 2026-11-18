package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.components.EmptyState
import com.raddstream.app.ui.components.PosterCard
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robo run 8: the My List empty state never showed its "Most copies"
 * row — and Robo measured three 32dp clickables just above the nav bar.
 * Both are the same layout bug: [EmptyState] is a fillMaxSize Box, so inside
 * the empty-state Column it took the whole height and the PopularRow below it
 * was laid out with no height left — off-screen, its three poster cards
 * collapsed to a strip at the content's bottom edge, one per column. The
 * message now takes the remaining space (weight 1f) and the row takes its
 * natural height. The My List review (Nov 2026) went further and stopped
 * the empty state claiming the space at all (`fillScreen = false`, no
 * weight), so the rail pulls up directly underneath it. These tests pin
 * both halves: the new layout's cards are full-height tiles, and the old
 * shape (reproduced here) really did starve them under the 48dp floor.
 *
 * Runs on the JVM under Robolectric because this project has no emulator image.
 *
 * Requires an x86_64 runner. Robolectric's native runtime has no Linux/aarch64
 * build (robolectric/robolectric#9166) and it fails while the runner boots, so
 * the exclusion for ARM hosts lives in app/build.gradle.kts rather than here.
 */
@RunWith(RobolectricTestRunner::class)
class MyListEmptyContentTest {

    @get:Rule
    val compose = createComposeRule()

    private fun post(id: Int, title: String) = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = 2024,
        contentType = "movie",
        // No poster URL: the card renders the branded fallback, so no image
        // load runs in the test — the measured height is the tile's own.
        posterUrl = null,
    )

    private val popular = listOf(
        post(1, "Inception"),
        post(2, "Dune"),
        post(3, "McLintock!"),
    )

    private fun clickableHeights(): List<Dp> {
        // My List review (Nov 2026): each rail card now carries two click
        // targets — the tile body and the save heart — so the heights here
        // are the tile bodies only. The hearts are 48dp by construction and
        // would fail the poster's >100dp floor; they announce themselves as
        // "Save <title> to My List" / "Remove <title> from My List".
        // A4-F5: the "Browse Titles" button is a third clickable on the
        // screen — it is not a tile, so the tag filter keeps it out here.
        val tiles = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
            // SemanticsConfiguration has no getOrNull in this Compose
            // version — getOrElse with an empty default is the read.
            val description = node.config
                .getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                .joinToString(" ")
            val tag = node.config.getOrElse(SemanticsProperties.TestTag) { "" }
            tag != MyListBrowseButtonTag &&
                !description.startsWith("Save ") && !description.startsWith("Remove ")
        }
        assertEquals(popular.size, tiles.size)
        return tiles.map {
            with(compose.density) { it.boundsInRoot.height.toDp() }
        }
    }

    @Test
    fun popularRowCardsAreFullHeightClickables() {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    MyListEmptyContent(
                        popular = popular,
                        onPostClick = {},
                        onToggleFavorite = {},
                        modifier = Modifier.width(360.dp),
                    )
                }
            }
        }
        // The row is visible: its heading is on screen, not pushed below the fold.
        compose.onNodeWithText("Trending to Start Your List").assertIsDisplayed()
        // The only tile clickables are the three poster cards — one per title.
        val heights = clickableHeights()
        assertTrue(
            "PopularRow cards measure $heights, expected every card a full-height tile (>= 48dp)",
            heights.all { it >= 48.dp },
        )
        assertTrue(
            "PopularRow cards measure $heights, expected poster height, not a collapsed strip",
            heights.all { it > 100.dp },
        )
    }

    @Test
    fun saveToggleCallsOnToggleFavoriteAndNotOnPostClick() {
        val toggled = mutableListOf<Post>()
        val opened = mutableListOf<Post>()
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    MyListEmptyContent(
                        popular = popular,
                        onPostClick = { opened += it },
                        onToggleFavorite = { toggled += it },
                        modifier = Modifier.width(360.dp),
                    )
                }
            }
        }
        // My List review (Nov 2026): the rail finally has the heart its own
        // empty-state copy promises ("tap the heart") — one per card.
        compose.onNodeWithContentDescription("Save Inception to My List").assertIsDisplayed()
        compose.onNodeWithContentDescription("Save Inception to My List").performClick()
        assertEquals(listOf(1), toggled.map { it.id })
        assertTrue("tapping the heart must not open the title", opened.isEmpty())
    }

    @Test
    fun savedToggleAnnouncesRemoval() {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    MyListEmptyContent(
                        popular = popular,
                        onPostClick = {},
                        isFavorite = { it.id == 1 },
                        onToggleFavorite = {},
                        modifier = Modifier.width(360.dp),
                    )
                }
            }
        }
        // A saved card's heart is the filled state: it announces removal,
        // and no "Save" toggle for that title exists any more.
        compose.onNodeWithContentDescription("Remove Inception from My List").assertIsDisplayed()
        compose.onNodeWithContentDescription("Save Inception to My List").assertDoesNotExist()
        compose.onNodeWithContentDescription("Save Dune to My List").assertIsDisplayed()
    }

    @Test
    fun tappingTheCardBodyStillOpensDetail() {
        val toggled = mutableListOf<Post>()
        val opened = mutableListOf<Post>()
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    MyListEmptyContent(
                        popular = popular,
                        onPostClick = { opened += it },
                        onToggleFavorite = { toggled += it },
                        modifier = Modifier.width(360.dp),
                    )
                }
            }
        }
        // The tile's own sentence is its click target; the heart eats only
        // its own tap, so opening the title never also saves it.
        compose.onNodeWithContentDescription("Inception, 2024 · Movie").performClick()
        assertEquals(listOf(1), opened.map { it.id })
        assertTrue("opening the title must not save it", toggled.isEmpty())
    }

    @Test
    fun railSkeletonStandsInWhileTrendingLoads() {
        // My List review (Nov 2026): the empty state used to show its
        // message naming a heart that was not on screen yet — the rail
        // pops in underneath later, or never. While the load is in
        // flight the skeleton stands in for the three tiles.
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    MyListEmptyContent(
                        popular = emptyList(),
                        popularLoading = true,
                        onPostClick = {},
                        onToggleFavorite = {},
                        modifier = Modifier.width(360.dp),
                    )
                }
            }
        }
        compose.onNodeWithTag("posterRailSkeleton").assertIsDisplayed()
        compose.onNodeWithText("Trending to Start Your List").assertDoesNotExist()
    }

    @Test
    fun settledEmptyLoadShowsNoSkeletonAndNoRail() {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    MyListEmptyContent(
                        popular = emptyList(),
                        popularLoading = false,
                        onPostClick = {},
                        onToggleFavorite = {},
                        modifier = Modifier.width(360.dp),
                    )
                }
            }
        }
        compose.onNodeWithTag("posterRailSkeleton").assertDoesNotExist()
        compose.onNodeWithText("Trending to Start Your List").assertDoesNotExist()
        compose.onNodeWithText("Your list is empty").assertIsDisplayed()
    }

    @Test
    fun browseTitlesButtonCallsOnBrowseClick() {
        // A4-F5: the empty state's primary way out — a filled "Browse
        // Titles" button under the copy, on the 48dp floor, that hands the
        // tap to the screen's onBrowseClick (Search/Browse in NavGraph).
        var browsed = 0
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    MyListEmptyContent(
                        popular = popular,
                        onPostClick = {},
                        onToggleFavorite = {},
                        onBrowseClick = { browsed++ },
                        modifier = Modifier.width(360.dp),
                    )
                }
            }
        }
        compose.onNodeWithText("Browse Titles").assertIsDisplayed()
        val height = with(compose.density) {
            compose.onNodeWithTag(MyListBrowseButtonTag)
                .fetchSemanticsNode().boundsInRoot.height.toDp()
        }
        assertTrue("Browse Titles is $height tall, expected at least 48dp", height >= 48.dp)
        compose.onNodeWithTag(MyListBrowseButtonTag).performClick()
        assertEquals(1, browsed)
    }

    @Test
    fun secondRailShowsWhenRecentLoaded() {
        // A4-F5: the second discovery rail, below Trending — freshest
        // titles, with its own heading, rendered from the recent feed.
        val recent = listOf(post(4, "Oppenheimer"), post(5, "Barbie"), post(6, "Poor Things"))
        compose.setContent {
            RaddStreamTheme {
                // Window-sized host, scroll-into-view for the second rail:
                // the message, the Browse button and two rails are taller
                // than the Robolectric window, so the second rail sits
                // below the fold. A 1000dp Box does NOT help — it makes
                // the Column's viewport taller than its content, so there
                // is nothing to scroll and the rail stays outside the
                // window. With a window-sized host the Column has a real
                // scroll range and performScrollTo brings the rail in.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    MyListEmptyContent(
                        popular = popular,
                        recent = recent,
                        onPostClick = {},
                        onToggleFavorite = {},
                        modifier = Modifier.width(360.dp),
                    )
                }
            }
        }
        compose.onNodeWithText("Trending to Start Your List").assertIsDisplayed()
        compose.onNodeWithText("Recently Added").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Oppenheimer").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun secondRailAbsentWhenRecentEmpty() {
        // A4-F5: a failed newest load settles to no second rail — the
        // screen never paints an empty "Recently Added" heading.
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    MyListEmptyContent(
                        popular = popular,
                        recent = emptyList(),
                        onPostClick = {},
                        onToggleFavorite = {},
                        modifier = Modifier.width(360.dp),
                    )
                }
            }
        }
        compose.onNodeWithText("Trending to Start Your List").assertIsDisplayed()
        compose.onNodeWithText("Recently Added").assertDoesNotExist()
    }

    @Test
    fun preFixLayoutStarvesPopularRow() {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    // The pre-fix empty state, verbatim in structure: a
                    // fillMaxSize EmptyState takes the whole Column and the
                    // PopularRow below it is laid out with no height left.
                    // Kept only to prove the bug — the production code no
                    // longer builds this shape.
                    Column(Modifier.width(360.dp)) {
                        EmptyState(
                            title = "Your list is empty",
                            message = "Tap the heart on any movie or series to save it here for quick access.",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            popular.forEach { p ->
                                PosterCard(
                                    post = p,
                                    onClick = {},
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
        val heights = clickableHeights()
        assertTrue(
            "pre-fix PopularRow cards measure $heights, expected every card collapsed under 48dp",
            heights.all { it < 48.dp },
        )
    }
}
