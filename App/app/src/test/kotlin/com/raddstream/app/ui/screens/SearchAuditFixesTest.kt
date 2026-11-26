package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.components.PosterCard
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.GenreFilter
import com.raddstream.app.viewmodel.IndustryFilter
import com.raddstream.app.viewmodel.SearchFilters
import com.raddstream.app.viewmodel.SearchSort
import com.raddstream.app.viewmodel.SearchUiState
import com.raddstream.app.viewmodel.TypeGroup
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UI/UX audit fixes (Nov 2026), Search screen — the behaviours the audit
 * ranked #1–#11, pinned where a test can say something real. Pixels are
 * the Paparazzi goldens' job, not this file's: these tests pin the
 * decisions (which actions the error state offers, that the suggestion is
 * a chip, that the Filters action never wears a selection fill, that
 * "Complete series" is a switch, that Language shows chips, that Reset is
 * a button) and the touch targets.
 *
 * Audit #1, #5 and #6 are APP-07's (`SearchNoResultsContent` on branch
 * muse/phase4-app07-search-noresults-20261124) and are pinned by its
 * SearchNoResultsTest, not here.
 */
@RunWith(RobolectricTestRunner::class)
class SearchAuditFixesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun post(id: Int, title: String, contentType: String = "movie") = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = 2023,
        contentType = contentType,
        posterUrl = null,
        imdbRating = "7.0",
        genres = "Action",
        industry = "bollywood",
        language = "Hindi",
    )

    private val idle = LoadState.NotLoading(endOfPaginationReached = false)

    private fun setSearchContent(
        state: SearchUiState,
        groups: List<TypeGroup> = emptyList(),
        pagingData: PagingData<Post> = PagingData.empty(),
        onRunSuggestion: (String) -> Unit = {},
        onShowExactOnly: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
    ) {
        val pagingDataFlow = MutableStateFlow(pagingData)
        compose.setContent {
            RaddStreamTheme {
                val pagingItems = pagingDataFlow.collectAsLazyPagingItems()
                SearchContent(
                    state = state,
                    pagingItems = pagingItems,
                    groups = groups,
                    onRunQuery = {},
                    onRemoveRecent = {},
                    onResetFilters = {},
                    onQueryChanged = {},
                    onShowCloseMatches = {},
                    onShowExactOnly = onShowExactOnly,
                    onRunSuggestion = onRunSuggestion,
                    onPostClick = {},
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }

    private fun setFilterSheet(
        state: SearchUiState = SearchUiState(),
        onSelectType: (String?) -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 360.dp, height = 800.dp)) {
                    FilterSheetBody(
                        state = state,
                        onSelectType = onSelectType,
                        onSelectIndustry = {},
                        onSelectGenre = {},
                        onSelectLanguage = {},
                        onSelectQuality = {},
                        onSetIncludedOnly = {},
                        onSelectSort = {},
                        onSetYearRange = { _, _ -> },
                        onResetFilters = {},
                        onDone = {},
                    )
                }
            }
        }
    }

    // ── Audit #2: the fallback poster shows initials, the caption the title ──

    @Test
    fun fallbackPosterShowsMonogramOnceCaptionCarriesTitle() {
        compose.setContent {
            RaddStreamTheme {
                PosterCard(
                    post = post(1, "Jawan"),
                    modifier = Modifier.width(180.dp),
                )
            }
        }
        // The title appears exactly once — the caption below the poster.
        // Before the fix the fallback painted it a second time in-card.
        val titleNodes = compose.onAllNodesWithText("Jawan", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals("Title must appear once (caption only)", 1, titleNodes.size)
        // The fallback itself paints the monogram, not the title.
        compose.onNodeWithText("JA", useUnmergedTree = true).assertExists()
    }

    // ── Audit #3: the error state names Settings in plain words and opens it ──

    @Test
    fun errorStateOffersOpenSettingsAndNoAddressJargon() {
        var settingsOpened = false
        setSearchContent(
            state = SearchUiState(query = "jwan", hasSearched = true),
            pagingData = PagingData.empty(
                sourceLoadStates = LoadStates(
                    refresh = LoadState.Error(IOException("Unable to resolve host")),
                    prepend = idle,
                    append = idle,
                ),
            ),
            onOpenSettings = { settingsOpened = true },
        )
        compose.onNodeWithText("We couldn't reach the catalog. Check the connection in Settings.")
            .assertIsDisplayed()
        compose.onNodeWithText("address", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("Open Settings").performClick()
        assertTrue("Open Settings must call onOpenSettings", settingsOpened)
    }

    // ── Audit #4 (P1-11): one inline fuzzy banner, not three recovery paths ──

    @Test
    fun fuzzyAnswerShowsOneBannerWithAnInlineExactLink() {
        var exactOnly = false
        val groups = listOf(
            TypeGroup("Movies", "movie", listOf(post(1, "Jawan"), post(2, "Salaar"))),
            TypeGroup("Series", "series", listOf(post(5, "Money Heist", "series"))),
        )
        setSearchContent(
            state = SearchUiState(
                query = "jwan",
                serverTotal = 3,
                hasSearched = true,
                didYouMean = "jawan",
                isFuzzy = true,
            ),
            groups = groups,
            pagingData = PagingData.from(
                data = listOf(post(1, "Jawan"), post(2, "Salaar"), post(5, "Money Heist", "series")),
                sourceLoadStates = LoadStates(refresh = idle, prepend = idle, append = idle),
            ),
            onRunSuggestion = { },
            onShowExactOnly = { exactOnly = true },
        )
        // The one banner names the query and the recovery, in one place.
        val banner = compose.onNodeWithTag(FuzzyBannerTag)
        banner.performScrollTo()
        banner.assertIsDisplayed()
        compose.onNodeWithText("No exact match for \"jwan\". Showing close titles — ", substring = true)
            .assertIsDisplayed()
        // The two retired paths are gone: no did-you-mean chip, no
        // "Show exact matches only" button, and no result count line.
        compose.onNodeWithText("Did you mean jawan?").assertDoesNotExist()
        compose.onNodeWithText("Show exact matches only").assertDoesNotExist()
        compose.onNodeWithText("Showing 3 of 3 results").assertDoesNotExist()
        // The inline link is the exact-only action — and it is a text
        // link, not an OutlinedButton.
        compose.onNodeWithTag(FuzzyBannerExactLinkTag).performClick()
        assertTrue("The banner's inline link must show exact matches only", exactOnly)
    }

    // ── Audit #7: the Filters action never wears the selected fill ──

    @Test
    fun filtersActionIsNeverSelectedWhileTypeChipsAre() {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 360.dp, height = 120.dp)) {
                    FilterRail(
                        state = SearchUiState(
                            filters = SearchFilters(
                                type = "movie",
                                genre = GenreFilter.ACTION,
                                industry = IndustryFilter.BOLLYWOOD,
                            ),
                            sort = SearchSort.HIGHEST_IMDB,
                        ),
                        onSelectType = {},
                        onOpenFilters = {},
                    )
                }
            }
        }
        // Three filters active — and the action still reads as an action.
        compose.onNodeWithContentDescription("Filters, 3 active").assertIsNotSelected()
        // The selected type chip keeps its fill; the contrast is the point.
        compose.onNodeWithText("Movies").assertIsSelected()
    }

    // ── Audit #8: the rail's right-edge fade is wide enough to read ──
    // Re-rate 8 (Nov 2026): 48dp → 64dp — the 48dp fade still left the
    // cut chip legible at rest, so the strip widened and the ramp
    // steepened (see RowEndFade).

    @Test
    fun railFadeIs64dpWide() {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.size(width = 360.dp, height = 120.dp)) {
                    FilterRail(state = SearchUiState(), onSelectType = {}, onOpenFilters = {})
                }
            }
        }
        val fade = compose.onNodeWithTag(FilterRailFadeTag)
            .fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertEquals("Fade must be 64dp wide to dim the cut chip", 64.dp, fade.width.toDp())
        }
    }

    // ── Audit #9: "Complete series" is a switch refining Series ──

    @Test
    fun completeSeriesSwitchSelectsCompleteSeason() {
        var selected: String? = null
        setFilterSheet(onSelectType = { selected = it })
        val switch = compose.onNodeWithContentDescription("Complete series only")
        switch.assertIsOff()
        switch.performClick()
        assertEquals("complete_season", selected)
    }

    @Test
    fun completeSeriesOnKeepsSeriesChipSelected() {
        setFilterSheet(state = SearchUiState(filters = SearchFilters(type = "complete_season")))
        compose.onNodeWithContentDescription("Complete series only").assertIsOn()
        compose.onNodeWithText("Series").assertIsSelected()
        // No fifth chip in the Type grid any more — the lone wrapped row is gone.
        compose.onNodeWithText("Complete series").assertDoesNotExist()
    }

    // ── Audit #10: Language shows chips, like every other section ──

    @Test
    fun languageChipsAreVisibleWithoutExpanding() {
        setFilterSheet()
        compose.onNodeWithText("Language").assertExists()
        compose.onNodeWithText("Hindi · हिन्दी").assertExists()
        // The collapsed dropdown header is gone.
        compose.onNodeWithContentDescription("Language: All").assertDoesNotExist()
    }

    // ── Audit #11: Reset is a real button on the 48dp floor ──

    @Test
    fun resetIsAButtonOnTheTouchFloor() {
        setFilterSheet()
        val reset = compose.onNodeWithText("Reset")
        reset.assertIsDisplayed()
        val resetBounds = reset.fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue("Reset below 48dp touch height", resetBounds.height.toDp() >= 48.dp)
        }
        compose.onNodeWithText("Apply").assertIsDisplayed()
    }
}
