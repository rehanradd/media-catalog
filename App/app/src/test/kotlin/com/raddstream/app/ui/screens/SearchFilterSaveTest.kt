package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.GenreFilter
import com.raddstream.app.viewmodel.MaxSavedFilters
import com.raddstream.app.viewmodel.SavedSearchFilter
import com.raddstream.app.viewmodel.SearchFilters
import com.raddstream.app.viewmodel.SearchUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SE-2 (round 2): the filter sheet's footer carries "Save this filter" —
 * enabled only while a filter is active and the cap is not reached. Saving
 * keeps the current combination as a one-tap row above the results.
 *
 * Runs on the JVM under Robolectric because this project has no emulator
 * image. Requires an x86_64 runner (robolectric/robolectric#9166).
 */
@RunWith(RobolectricTestRunner::class)
class SearchFilterSaveTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setSheet(
        state: SearchUiState,
        savedFilters: List<SavedSearchFilter> = emptyList(),
        onSaveFilter: () -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 360.dp, height = 800.dp)) {
                    FilterSheetBody(
                        state = state,
                        onSelectType = {},
                        onSelectIndustry = {},
                        onSelectGenre = {},
                        onSelectLanguage = {},
                        onSelectQuality = {},
                        onSetIncludedOnly = {},
                        onSelectSort = {},
                        onSetYearRange = { _, _ -> },
                        onResetFilters = {},
                        onDone = {},
                        savedFilters = savedFilters,
                        onSaveFilter = onSaveFilter,
                    )
                }
            }
        }
    }

    @Test
    fun saveButtonShowsAndWorksWhileFiltersAreActive() {
        var saved = false
        setSheet(
            state = SearchUiState(filters = SearchFilters(genre = GenreFilter.ACTION)),
            onSaveFilter = { saved = true },
        )
        compose.onNodeWithText("Save this filter").assertIsDisplayed()
        compose.onNodeWithText("Save this filter").assertIsEnabled()
        compose.onNodeWithText("Save this filter").performClick()
        assertTrue("Save tap did not call onSaveFilter", saved)
    }

    @Test
    fun saveButtonIsDisabledWithNoActiveFilters() {
        setSheet(state = SearchUiState())
        compose.onNodeWithText("Save this filter").assertIsDisplayed()
        compose.onNodeWithText("Save this filter").assertIsNotEnabled()
        // Video audit #37 (Nov 2026): a greyed-out Save with no reason
        // reads as broken. The reason rides under the button.
        compose.onNodeWithText("Choose a filter first — there is nothing to save yet.").assertIsDisplayed()
    }

    @Test
    fun saveButtonIsDisabledAtTheCap() {
        val atCap = List(MaxSavedFilters) { i ->
            SavedSearchFilter(filters = SearchFilters(yearFrom = 2000 + i, yearTo = 2000 + i))
        }
        setSheet(
            state = SearchUiState(filters = SearchFilters(genre = GenreFilter.ACTION)),
            savedFilters = atCap,
        )
        compose.onNodeWithText("Save this filter").assertIsNotEnabled()
        // Video audit #37: the cap gets its own reason — "nothing to
        // save" would be the wrong words here.
        compose.onNodeWithText("You already saved $MaxSavedFilters filters. Remove one to save another.").assertIsDisplayed()
    }

    @Test
    fun saveButtonShowsNoReasonWhileEnabled() {
        setSheet(state = SearchUiState(filters = SearchFilters(genre = GenreFilter.ACTION)))
        compose.onNodeWithText("Save this filter").assertIsEnabled()
        compose.onNodeWithText("Choose a filter first — there is nothing to save yet.").assertDoesNotExist()
        compose.onNodeWithText("You already saved $MaxSavedFilters filters. Remove one to save another.").assertDoesNotExist()
    }
}
