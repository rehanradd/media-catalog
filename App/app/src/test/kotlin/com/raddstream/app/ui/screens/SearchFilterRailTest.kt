package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.SearchFilters
import com.raddstream.app.viewmodel.SearchUiState
import com.raddstream.app.viewmodel.TypeGroup
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robo #7: the last Search filter chip ("Complete series") was cut off at the right edge
 * ("Comp…") — and when it was the SELECTED chip it stayed cut off, because nothing ever
 * scrolled the rail to it. The rail already scrolls (LazyRow) with end padding (Session 121
 * P-29); what it never did was reveal its own selection. These tests pin both halves: the
 * last chip is reachable by scrolling, and selecting a chip scrolls it fully into view.
 * Mockup (Nov 2026): the rail is All / Movies / Series / Anime / K-Pop — "Complete series"
 * lives in the sheet's Type group, not the rail, so the last chip is K-Pop now.
 * Search review (Nov 2026): the Filters button used to be the 6th item in the scrolling
 * row — fully off-screen on a 360dp phone, so the sheet was undiscoverable without a
 * swipe. It is fixed at the row's left edge now, so it can never clip; these tests pin
 * that it is displayed at rest, without scrolling, while the type chips still scroll.
 *
 * NOTE: `assertIsDisplayed` alone cannot catch a partial clip — a half-visible node still
 * counts as displayed — so the reveal tests assert the chip's bounds sit fully inside the
 * root, the way SearchFilterSheetTest pins its footer.
 */
@RunWith(RobolectricTestRunner::class)
class SearchFilterRailTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun lastChipRevealsByScrolling() {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 480.dp, height = 120.dp)) {
                    FilterRail(state = SearchUiState(), onSelectType = {}, onOpenFilters = {})
                }
            }
        }
        // Mockup (Nov 2026): K-Pop is the last rail chip — "Complete series"
        // lives in the sheet now, so the rail must not carry it at all.
        compose.onNodeWithText("Complete series").assertDoesNotExist()
        // "K-Pop" is only a peek at the right edge at rest (Robo #7) — scroll it whole.
        val chip = compose.onNodeWithText("K-Pop")
        chip.performScrollTo()
        chip.assertIsDisplayed()
        val chipBounds = chip.fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue(
                "K-Pop still past the right edge after scrolling",
                chipBounds.right.toDp() <= 480.dp,
            )
        }
    }

    @Test
    fun filtersButtonIsFixedAtRest() {
        // Search review (Nov 2026): the Filters button is the sheet's only
        // entry — it used to be the 6th item in the scrolling row and sat
        // fully off-screen on a 360dp phone. Fixed at the row's left edge,
        // it is displayed at rest with no scroll, and its left edge is the
        // row's left edge. The chip itself carries the "Filters"
        // contentDescription; the Text inside it is inset by the icon and
        // the chip padding, so the bounds are read off the chip node.
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 360.dp, height = 120.dp)) {
                    FilterRail(state = SearchUiState(), onSelectType = {}, onOpenFilters = {})
                }
            }
        }
        val filters = compose.onNodeWithContentDescription("Filters")
        filters.assertIsDisplayed()
        val filtersBounds = filters.fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue(
                "Filters button not at the row's left edge",
                filtersBounds.left.toDp() == 0.dp,
            )
            assertTrue(
                "Filters button past the right edge at rest",
                filtersBounds.right.toDp() <= 360.dp,
            )
        }
    }

    @Test
    fun selectedChipScrollsFullyIntoView() {
        // Robo #7, shot 34: "Complete series" selected, yet only "Comp" showed — the rail
        // never scrolled to its own selection. Selecting it must bring it fully into view.
        // Mockup (Nov 2026): the last chip is K-Pop now — same mechanism, new last chip.
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 360.dp, height = 120.dp)) {
                    FilterRail(
                        state = SearchUiState(filters = SearchFilters(type = "kpop")),
                        onSelectType = {},
                        onOpenFilters = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val chip = compose.onNodeWithText("K-Pop")
        chip.assertIsDisplayed()
        val bounds = chip.fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue("K-Pop clipped at the left edge", bounds.left.toDp() >= 0.dp)
            assertTrue("K-Pop clipped at the right edge", bounds.right.toDp() <= 360.dp)
        }
    }

    @Test
    fun selectingVisibleChipDoesNotMoveTheRow() {
        // Revealing a selection must not shift the row when the chip is already fully
        // visible — tapping "Movies" at rest leaves "All" exactly where it was.
        var type by mutableStateOf<String?>(null)
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 360.dp, height = 120.dp)) {
                    FilterRail(
                        state = SearchUiState(filters = SearchFilters(type = type)),
                        onSelectType = { type = it },
                        onOpenFilters = {},
                    )
                }
            }
        }
        type = "movie"
        compose.waitForIdle()
        compose.onNodeWithText("All").assertIsDisplayed()
        compose.onNodeWithText("Movies").assertIsDisplayed()
    }

    @Test
    fun singleSectionAlignsItsChip() {
        // A4-F4 (Robo OR-05): "All" filled while only one section showed —
        // the chip claimed a breadth the list did not have. With no type
        // filter and a lone Series section, Series reads as the active chip
        // and "All" does not.
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 360.dp, height = 120.dp)) {
                    FilterRail(
                        state = SearchUiState(),
                        // S6: the groups arrive as a parameter now — the
                        // ViewModel no longer owns the list, so the screen
                        // derives them from the PagingData's snapshot.
                        groups = listOf(TypeGroup(label = "Series", typeKey = "series", items = emptyList())),
                        onSelectType = {},
                        onOpenFilters = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Series").assertIsSelected()
        compose.onNodeWithText("All").assertIsNotSelected()
    }

    @Test
    fun twoSectionsKeepAllActive() {
        // A4-F4: alignment only fires on a lone section — two sections
        // showing is genuinely "All", so the filter is not narrowed.
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 360.dp, height = 120.dp)) {
                    FilterRail(
                        state = SearchUiState(),
                        groups = listOf(
                            TypeGroup(label = "Movies", typeKey = "movie", items = emptyList()),
                            TypeGroup(label = "Series", typeKey = "series", items = emptyList()),
                        ),
                        onSelectType = {},
                        onOpenFilters = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("All").assertIsSelected()
        compose.onNodeWithText("Movies").assertIsNotSelected()
        compose.onNodeWithText("Series").assertIsNotSelected()
    }

    @Test
    fun rightEdgeFadeMaskExists() {
        // Search review (Nov 2026): the last chip hard-clipped at the right
        // edge ("KPO") — a fade mask over the row's right edge replaces the
        // hard clip. The mask is the shared ScrollingChipRow fade now (the local
        // 32dp fade painted, but chip fill → background is ~10 RGB steps
        // on this palette, so it advertised nothing); it is still a plain
        // overlay with no click handling, so the test only pins its
        // presence — the chips beneath stay tappable through it.
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 360.dp, height = 120.dp)) {
                    FilterRail(state = SearchUiState(), onSelectType = {}, onOpenFilters = {})
                }
            }
        }
        compose.onNodeWithTag(FilterRailFadeTag).assertExists()
    }
}
