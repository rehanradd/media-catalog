package com.raddstream.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.screens.SearchContent
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.SearchUiState
import com.raddstream.app.viewmodel.TypeGroup
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/**
 * Round 2 (Search): the search results list at 1.3x font scale — the
 * golden that proves the tabs, the summary line, the fuzzy banner and the
 * exact-only button still fit without truncation when the viewer enlarges
 * text.
 *
 * A SEPARATE class from PaparazziPreviewTest on purpose: two Paparazzi
 * rules in one class break every test in it — JUnit applies both rules to
 * each test, and the second rule's prepare fails with
 * "Acquiring different scenes from same thread without releases" because
 * the first rule still holds the LayoutLib lock (record 45555447495,
 * attempts 1+2: all 37 PaparazziPreviewTest shots failed that way, while
 * the single-rule PlayerLandscape class passed all 7 of its shots).
 * Same pattern as BottomNavLargeFontPaparazziTest: one class, one rule.
 * Registered in build.gradle.kts (Paparazzi-only include + plain-run
 * exclude) and in both workflows' hard-coded --tests, or the record and
 * verify runs never execute it.
 */
class SearchLargeFontPaparazziTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 1.3f),
    )

    private fun fakePost(
        id: Int,
        title: String,
        year: Int,
        contentType: String,
        rating: String,
        genres: String,
        industry: String,
        language: String,
    ) = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = year,
        contentType = contentType,
        posterUrl = null,
        imdbRating = rating,
        genres = genres,
        industry = industry,
        language = language,
    )

    private val jawan = fakePost(
        id = 1,
        title = "Jawan",
        year = 2023,
        contentType = "movie",
        rating = "7.0",
        genres = "Action, Thriller",
        industry = "Bollywood",
        language = "Hindi",
    )
    private val salaars = fakePost(
        id = 2,
        title = "Salaar: Part 1 – Ceasefire",
        year = 2023,
        contentType = "movie",
        rating = "6.5",
        genres = "Action",
        industry = "Tollywood",
        language = "Telugu",
    )
    private val moneyHeist = fakePost(
        id = 5,
        title = "Money Heist",
        year = 2017,
        contentType = "series",
        rating = "8.2",
        genres = "Crime, Drama",
        industry = "Hollywood",
        language = "English",
    )

    /** Dark-first, like the shipped app: RaddStreamTheme defaults to the brand scheme. */
    @Composable
    private fun themed(content: @Composable () -> Unit) {
        RaddStreamTheme(darkTheme = true) {
            Surface(color = MaterialTheme.colorScheme.background) { content() }
        }
    }

    private val searchResultGroups = listOf(
        TypeGroup(label = "Movies", typeKey = "movie", items = listOf(jawan, salaars)),
        TypeGroup(label = "Series", typeKey = "series", items = listOf(moneyHeist)),
    )

    private val searchResults = SearchUiState(
        query = "jwan",
        serverTotal = 3,
        hasSearched = true,
        // The query is one edit away from a real title, so both the
        // did-you-mean line and the fuzzy banner render in this shot.
        didYouMean = "jawan",
        isFuzzy = true,
    )

    @Test
    fun searchResultsLargeFont() {
        // The same results list as PaparazziPreviewTest.searchResults, at
        // 1.3x font scale — the tabs, the summary line, the fuzzy banner
        // and the exact-only button must still fit without truncation or
        // overlap.
        val idle = LoadState.NotLoading(endOfPaginationReached = false)
        val pagingDataFlow = MutableStateFlow(
            PagingData.from(
                data = listOf(jawan, salaars, moneyHeist),
                sourceLoadStates = LoadStates(refresh = idle, prepend = idle, append = idle),
            ),
        )
        paparazzi.snapshot {
            themed {
                // The result rows stagger in on the phone
                // (SearchStaggeredFade) — under inspection mode they start
                // visible, so the golden shows what the phone shows after
                // the entrance, not the pre-entrance empty list. Same
                // provision as the Home goldens (see HomeScreen's rail).
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    val pagingItems = pagingDataFlow.collectAsLazyPagingItems()
                    SearchContent(
                        state = searchResults,
                        pagingItems = pagingItems,
                        groups = searchResultGroups,
                        onRunQuery = {},
                        onRemoveRecent = {},
                        onResetFilters = {},
                        onQueryChanged = {},
                        onShowCloseMatches = {},
                        onShowExactOnly = {},
                        onRunSuggestion = {},
                        onPostClick = {},
                    )
                }
            }
        }
    }
}
