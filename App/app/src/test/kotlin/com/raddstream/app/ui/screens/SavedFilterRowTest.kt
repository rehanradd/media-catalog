package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.GenreFilter
import com.raddstream.app.viewmodel.LanguageFilter
import com.raddstream.app.viewmodel.SavedSearchFilter
import com.raddstream.app.viewmodel.SearchFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SE-2 (round 2): the saved-filter row above the rail — one chip per kept
 * combination. A tap re-applies it; the × removes it. The row's spoken
 * name is the apply action, and the × stays its own node.
 *
 * Runs on the JVM under Robolectric because this project has no emulator
 * image. Requires an x86_64 runner (robolectric/robolectric#9166).
 */
@RunWith(RobolectricTestRunner::class)
class SavedFilterRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val actionHindi = SavedSearchFilter(
        filters = SearchFilters(genre = GenreFilter.ACTION, language = LanguageFilter.HINDI),
    )

    private fun setRow(
        saved: List<SavedSearchFilter> = listOf(actionHindi),
        onApply: (SavedSearchFilter) -> Unit = {},
        onRemove: (SavedSearchFilter) -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                SavedFilterRow(
                    savedFilters = saved,
                    onApply = onApply,
                    onRemove = onRemove,
                )
            }
        }
    }

    @Test
    fun savedFilterLabelShows() {
        setRow()
        compose.onNodeWithText("Action · Hindi").assertIsDisplayed()
    }

    @Test
    fun tappingTheChipAppliesTheFilter() {
        var applied: SavedSearchFilter? = null
        setRow(onApply = { applied = it })
        compose.onNodeWithContentDescription("Apply saved filter Action · Hindi").performClick()
        assertEquals(actionHindi, applied)
    }

    @Test
    fun tappingTheCrossRemovesTheFilter() {
        var removed: SavedSearchFilter? = null
        setRow(onRemove = { removed = it })
        compose.onNodeWithContentDescription("Remove saved filter Action · Hindi").performClick()
        assertEquals(actionHindi, removed)
    }

    @Test
    fun tappingTheChipDoesNotRemoveIt() {
        var removed: SavedSearchFilter? = null
        setRow(onRemove = { removed = it })
        compose.onNodeWithContentDescription("Apply saved filter Action · Hindi").performClick()
        assertNull("Tapping the chip must apply, not remove", removed)
    }
}
