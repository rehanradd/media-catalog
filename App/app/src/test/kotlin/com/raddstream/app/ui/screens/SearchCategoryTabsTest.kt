package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.GenreFilter
import com.raddstream.app.viewmodel.LanguageFilter
import com.raddstream.app.viewmodel.SearchFilters
import com.raddstream.app.viewmodel.SearchUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SE-1 (round 2): the category tabs above the results — Genre / Language /
 * Year, with quick chips under them, so the viewer scopes the list without
 * opening the sheet. A tap applies at once; tapping the selected chip again
 * clears it back to All.
 *
 * Runs on the JVM under Robolectric because this project has no emulator
 * image. Requires an x86_64 runner (robolectric/robolectric#9166).
 */
@RunWith(RobolectricTestRunner::class)
class SearchCategoryTabsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setTabs(
        state: SearchUiState = SearchUiState(),
        onSelectGenre: (GenreFilter) -> Unit = {},
        onSelectLanguage: (LanguageFilter) -> Unit = {},
        onSetYearRange: (Int?, Int?) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            RaddStreamTheme {
                CategoryTabs(
                    state = state,
                    onSelectGenre = onSelectGenre,
                    onSelectLanguage = onSelectLanguage,
                    onSetYearRange = onSetYearRange,
                )
            }
        }
    }

    @Test
    fun genreTabIsSelectedFirstWithQuickChips() {
        setTabs()
        compose.onNodeWithTag(CategoryTabGenreTag).assertIsDisplayed()
        compose.onNodeWithText("Action").assertIsDisplayed()
        compose.onNodeWithText("Comedy").assertIsDisplayed()
    }

    @Test
    fun tappingAGenreChipSelectsIt() {
        var selected: GenreFilter? = null
        setTabs(onSelectGenre = { selected = it })
        compose.onNodeWithText("Action").performClick()
        assertEquals(GenreFilter.ACTION, selected)
    }

    @Test
    fun tappingTheSelectedGenreChipClearsIt() {
        var selected: GenreFilter? = null
        setTabs(
            state = SearchUiState(filters = SearchFilters(genre = GenreFilter.ACTION)),
            onSelectGenre = { selected = it },
        )
        compose.onNodeWithText("Action").performClick()
        assertEquals(GenreFilter.ALL, selected)
    }

    @Test
    fun languageTabShowsLanguageChips() {
        var selected: LanguageFilter? = null
        setTabs(onSelectLanguage = { selected = it })
        compose.onNodeWithTag(CategoryTabLanguageTag).performClick()
        compose.onNodeWithText("Hindi · हिन्दी").assertIsDisplayed()
        compose.onNodeWithText("English").performClick()
        assertEquals(LanguageFilter.ENGLISH, selected)
    }

    @Test
    fun yearTabShowsYearChipsAndSelectsOneYear() {
        var range: Pair<Int?, Int?>? = null
        setTabs(onSetYearRange = { from, to -> range = from to to })
        compose.onNodeWithTag(CategoryTabYearTag).performClick()
        compose.onNodeWithText("2025").assertIsDisplayed()
        compose.onNodeWithText("2020s").assertIsDisplayed()
        compose.onNodeWithText("2025").performClick()
        assertEquals(2025 to 2025, range)
    }

    @Test
    fun tappingTheSelectedYearChipClearsTheRange() {
        var range: Pair<Int?, Int?>? = null
        setTabs(
            state = SearchUiState(filters = SearchFilters(yearFrom = 2025, yearTo = 2025)),
            onSetYearRange = { from, to -> range = from to to },
        )
        compose.onNodeWithTag(CategoryTabYearTag).performClick()
        compose.onNodeWithText("2025").performClick()
        assertEquals(null to null, range)
    }

    @Test
    fun quickChipsRightEdgeFadeMaskExists() {
        // S3 (round 5): the last quick chip hard-clipped at the right edge
        // ("Thrill…") — a fade mask over the row's right edge replaces the
        // hard clip. The mask is the shared ScrollingChipRow fade
        // (unconditional), a plain overlay with no click handling, so the
        // test only pins its presence — the chips beneath stay tappable
        // through it.
        setTabs()
        compose.onNodeWithTag(QuickChipsFadeTag).assertExists()
    }
}
