package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.SearchFilters
import com.raddstream.app.viewmodel.SearchUiState
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
 * Mockup (Nov 2026): the rail is ALL / MOVIES / SERIES / ANIME / KPOP — "Complete series"
 * lives in the sheet's Type group, not the rail, so the last chip is KPOP now.
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
    fun lastChipAndFiltersButtonRevealByScrolling() {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 480.dp, height = 120.dp)) {
                    FilterRail(state = SearchUiState(), onSelectType = {}, onOpenFilters = {})
                }
            }
        }
        // Mockup (Nov 2026): KPOP is the last rail chip — "Complete series"
        // lives in the sheet now, so the rail must not carry it at all.
        compose.onNodeWithText("Complete series").assertDoesNotExist()
        // "KPOP" is only a peek at the right edge at rest (Robo #7) — scroll it whole.
        val chip = compose.onNodeWithText("KPOP")
        chip.performScrollTo()
        chip.assertIsDisplayed()
        val chipBounds = chip.fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue(
                "KPOP still past the right edge after scrolling",
                chipBounds.right.toDp() <= 480.dp,
            )
        }
        // The Filters button after it is reachable the same way.
        val filters = compose.onNodeWithText("Filters")
        filters.performScrollTo()
        filters.assertIsDisplayed()
        val filtersBounds = filters.fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue(
                "Filters button still past the right edge after scrolling",
                filtersBounds.right.toDp() <= 480.dp,
            )
        }
    }

    @Test
    fun selectedChipScrollsFullyIntoView() {
        // Robo #7, shot 34: "Complete series" selected, yet only "Comp" showed — the rail
        // never scrolled to its own selection. Selecting it must bring it fully into view.
        // Mockup (Nov 2026): the last chip is KPOP now — same mechanism, new last chip.
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
        val chip = compose.onNodeWithText("KPOP")
        chip.assertIsDisplayed()
        val bounds = chip.fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue("KPOP clipped at the left edge", bounds.left.toDp() >= 0.dp)
            assertTrue("KPOP clipped at the right edge", bounds.right.toDp() <= 360.dp)
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
        compose.onNodeWithText("ALL").assertIsDisplayed()
        compose.onNodeWithText("MOVIES").assertIsDisplayed()
    }
}
