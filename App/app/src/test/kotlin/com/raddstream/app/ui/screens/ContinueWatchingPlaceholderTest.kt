package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.HomeSection
import com.raddstream.app.viewmodel.HomeUiState
import com.raddstream.app.viewmodel.ResumeTile
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B3: the Continue Watching rail is pinned under the hero and always
 * present. With resume rows it is the real rail; without rows — a fresh
 * install — it is a "Start watching" placeholder whose CTA opens Browse.
 * It only shows once the catalog has landed, so a blank or loading load
 * still gets the empty/loading state, not a placeholder for titles that
 * may not exist.
 */
@RunWith(RobolectricTestRunner::class)
class ContinueWatchingPlaceholderTest {

    @get:Rule
    val compose = createComposeRule()

    private val jawan = Post(id = 1, title = "Jawan", year = 2023, contentType = "movie")

    private fun loadedSection() = HomeSection(
        key = "trending",
        title = "Most copies",
        type = null,
        items = listOf(jawan),
        sort = "trending",
    )

    private fun resumeTile() = ResumeTile(
        post = jawan,
        season = 1,
        episode = 3,
        positionMs = 1_500_000L,
        durationMs = 2_700_000L,
        workRef = "work:jawan",
    )

    private fun setHome(state: HomeUiState, onBrowseType: (String?) -> Unit = {}) {
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = state,
                    heroAutoplay = false,
                    onBrowseType = onBrowseType,
                )
            }
        }
    }

    @Test
    fun placeholderShowsWhenCatalogLoadedButNothingWatchedYet() {
        setHome(HomeUiState(sections = listOf(loadedSection())))

        compose.onNodeWithText("Continue watching").assertIsDisplayed()
        compose.onNodeWithText("Start watching").assertIsDisplayed()
        compose.onNodeWithText("Browse titles").assertIsDisplayed()
    }

    @Test
    fun browseCtaOpensBrowseWithNoType() {
        var browsedType: String? = "untouched"
        setHome(
            HomeUiState(sections = listOf(loadedSection())),
            onBrowseType = { browsedType = it },
        )

        compose.onNodeWithText("Browse titles").performClick()

        assertNull(browsedType)
    }

    @Test
    fun realRailShowsInsteadOfPlaceholderWhenResumeDataExists() {
        setHome(
            HomeUiState(
                sections = listOf(loadedSection()),
                continueWatching = listOf(resumeTile()),
            ),
        )

        compose.onNodeWithText("Continue watching").assertIsDisplayed()
        compose.onNodeWithText("Start watching").assertDoesNotExist()
    }

    @Test
    fun noPlaceholderOnAnEmptyLoad() {
        setHome(HomeUiState())

        compose.onNodeWithText("Start watching").assertDoesNotExist()
    }

    @Test
    fun noPlaceholderWhileTheCatalogIsStillLoading() {
        setHome(
            HomeUiState(
                sections = listOf(
                    HomeSection(key = "trending", title = "Most copies", type = null, loading = true),
                ),
            ),
        )

        compose.onNodeWithText("Start watching").assertDoesNotExist()
    }
}
