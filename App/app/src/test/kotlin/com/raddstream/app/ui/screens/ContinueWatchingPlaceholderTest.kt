package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * B3 (home review, Nov 2026): the Continue Watching rows are pinned under
 * the hero and always present. With resume rows they are the real rows
 * (thumb, title, remaining label, progress bar); without rows — but with
 * watch history on the device — the slot is ONE quiet line ("No active
 * shows — start exploring picks" + Browse), never a big card and never a
 * "Continue watching" heading — the heading belongs to the real rail, with
 * rows under it. A4-F2: a fresh install gets no line at all — it read as an
 * error on a populated Home, and Pick 3 onboards a new viewer. It only
 * shows once the catalog has landed, so a blank or loading load still
 * gets the empty/loading state, not a placeholder for titles that may not
 * exist.
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

    private fun setHome(state: HomeUiState, onBrowseType: (String?, String?) -> Unit = { _, _ -> }) {
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
    fun quietLineShowsWhenHistoryExistsButNothingIsResumable() {
        // A4-F2: the line is for a viewer who watched before and has
        // nothing resumable now (finished everything, or removed every
        // row) — for them "no active shows" is true. A first run gets no
        // line at all (see noPlaceholderOnFirstRun... below): it read as
        // an error on a populated Home, and Pick 3 onboards a new viewer.
        setHome(HomeUiState(sections = listOf(loadedSection()), hasWatchHistory = true))

        // Home fix (Nov 2026): the slot sits above the hero now, so the
        // quiet line is on first paint — scroll it into view first anyway,
        // the same way the other tests do.
        compose.onNodeWithText("No active shows — start exploring picks").performScrollTo()
        compose.onNodeWithText("No active shows — start exploring picks").assertIsDisplayed()
        // No heading, no big card: the old "Start watching" card is gone.
        compose.onNodeWithText("Start watching").assertDoesNotExist()
        compose.onNodeWithText("Continue watching").assertDoesNotExist()
    }

    @Test
    fun noPlaceholderOnFirstRunEvenWhenCatalogIsLoaded() {
        // A4-F2: the Robo capture (matrix-12l8h97dvq326) showed the line
        // under a populated hero on a fresh install — no history at all.
        // The line is already gated on an empty Continue Watching list;
        // the missing gate was "has this device ever watched anything".
        setHome(HomeUiState(sections = listOf(loadedSection()), hasWatchHistory = false))

        compose.onNodeWithText("No active shows — start exploring picks").assertDoesNotExist()
        compose.onNodeWithText("Continue watching").assertDoesNotExist()
    }

    @Test
    fun browseCtaOpensBrowseWithNoType() {
        var browsedType: String? = "untouched"
        setHome(
            HomeUiState(sections = listOf(loadedSection()), hasWatchHistory = true),
            onBrowseType = { type, _ -> browsedType = type },
        )

        // A click on a node below the fold lands off-viewport and never
        // fires — scroll the CTA into view first. "Browse" also names the
        // Browse section heading, so match the one that acts.
        compose.onNode(hasText("Browse") and hasClickAction()).performScrollTo().performClick()

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

        compose.onNodeWithText("Continue watching").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Start watching").assertDoesNotExist()
        compose.onNodeWithText("No active shows — start exploring picks").assertDoesNotExist()
    }

    @Test
    fun noPlaceholderOnAnEmptyLoad() {
        setHome(HomeUiState())

        compose.onNodeWithText("No active shows — start exploring picks").assertDoesNotExist()
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

        compose.onNodeWithText("No active shows — start exploring picks").assertDoesNotExist()
    }
}
