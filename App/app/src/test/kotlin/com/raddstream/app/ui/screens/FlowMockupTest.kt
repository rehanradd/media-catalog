package com.raddstream.app.ui.screens

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Stitch flow mockups (Nov 2026): Browse, Dead Link, Maintenance and Setup
 * restyled to the "Radd Stream App Screens" mockups — same dark canvas and
 * jade accent, no invented data, no dead buttons. Each test drives the
 * extracted, ViewModel-free composable (or the plain screen) with real
 * callbacks, so a restyle that breaks the wiring fails here, not on device.
 */
@RunWith(RobolectricTestRunner::class)
class FlowMockupTest {

    @get:Rule
    val compose = createComposeRule()

    private fun post(id: Int, title: String, year: Int, type: String) = Post(
        id = id,
        title = title,
        year = year,
        contentType = type,
    )

    private fun setupContent(
        state: SetupUiState,
        onServerUrlChanged: (String) -> Unit = {},
        onTestConnection: () -> Unit = {},
        onRetryDefault: () -> Unit = {},
        onRevealUrlField: () -> Unit = {},
        onConnected: () -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                SetupScreenContent(
                    state = state,
                    onServerUrlChanged = onServerUrlChanged,
                    onTestConnection = onTestConnection,
                    onRetryDefault = onRetryDefault,
                    onRevealUrlField = onRevealUrlField,
                    onConnected = onConnected,
                )
            }
        }
    }

    @Test
    fun browsePosterGridShowsTitleAndMeta() {
        compose.setContent {
            RaddStreamTheme {
                BrowsePosterGrid(
                    items = listOf(
                        post(1, "Apex Horizon", 2024, "movie"),
                        post(2, "Shadow Protocol", 2023, "series"),
                    ),
                    loadingMore = false,
                    error = null,
                    gridState = rememberLazyGridState(),
                    onPostClick = {},
                    onRetryLoadMore = {},
                )
            }
        }
        // The canonical tile paints the bold title and the meta line, but the
        // text row is paint-only (clearAndSetSemantics — one spoken sentence
        // per tile), so the meta is pinned through that sentence, which
        // carries the same year + type. The title is findable as text only
        // because the art-less fallback frame prints it, not the text row.
        compose.onNodeWithText("Apex Horizon").assertIsDisplayed()
        compose.onNodeWithText("Shadow Protocol").assertIsDisplayed()
        // And each tile still speaks one sentence, title + meta.
        compose.onNode(hasContentDescription("Apex Horizon, 2024 · Movie")).assertExists()
        compose.onNode(hasContentDescription("Shadow Protocol, 2023 · Series")).assertExists()
    }

    @Test
    fun browsePosterGridTapReportsThePost() {
        var tapped: Post? = null
        compose.setContent {
            RaddStreamTheme {
                BrowsePosterGrid(
                    items = listOf(post(1, "Apex Horizon", 2024, "movie")),
                    loadingMore = false,
                    error = null,
                    gridState = rememberLazyGridState(),
                    onPostClick = { tapped = it },
                    onRetryLoadMore = {},
                )
            }
        }
        compose.onNode(hasContentDescription("Apex Horizon, 2024 · Movie")).performClick()
        assertEquals(1, tapped?.id)
    }

    @Test
    fun browsePosterGridLoadFailureRetries() {
        var retried = 0
        compose.setContent {
            RaddStreamTheme {
                BrowsePosterGrid(
                    items = listOf(post(1, "Apex Horizon", 2024, "movie")),
                    loadingMore = false,
                    error = "Next page failed",
                    gridState = rememberLazyGridState(),
                    onPostClick = {},
                    onRetryLoadMore = { retried++ },
                )
            }
        }
        compose.onNodeWithText("Next page failed").assertIsDisplayed()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(1, retried)
    }

    @Test
    fun deadLinkShowsReasonAndButtonGoesHome() {
        var homeClicks = 0
        compose.setContent {
            RaddStreamTheme { DeadLinkScreen(onHomeClick = { homeClicks++ }) }
        }
        compose.onNodeWithText("This title isn’t available").assertIsDisplayed()
        compose.onNodeWithText("No working sources right now. Try a similar title instead.")
            .assertIsDisplayed()
        compose.onNodeWithText("Browse similar titles").performClick()
        assertEquals(1, homeClicks)
        // The back arrow is the same way home — there is no title to go back to.
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(2, homeClicks)
    }

    @Test
    fun maintenanceShowsRetryAndChangeServer() {
        var retries = 0
        var changes = 0
        compose.setContent {
            RaddStreamTheme {
                MaintenanceScreen(
                    onChangeServer = { changes++ },
                    onRetry = { retries++ },
                )
            }
        }
        compose.onNode(hasText("is updating", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("We'll be back soon.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("Use a different catalog").performClick()
        assertEquals(1, changes)
    }

    @Test
    fun maintenanceHidesBothExitsWhenNeitherIsProvided() {
        compose.setContent {
            RaddStreamTheme { MaintenanceScreen() }
        }
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Use a different catalog").assertDoesNotExist()
    }

    @Test
    fun setupContentShowsFieldAndTestConnection() {
        // The content is stateless — the test owns the state, so typing into
        // the field really updates it and the Connect button enables. A fixed
        // blank state would leave the button disabled and the click a no-op.
        var state by mutableStateOf(SetupUiState(serverUrl = "", showUrlField = true))
        var tested = 0
        compose.setContent {
            RaddStreamTheme {
                SetupScreenContent(
                    state = state,
                    onServerUrlChanged = { state = state.copy(serverUrl = it) },
                    onTestConnection = { tested++ },
                    onRetryDefault = {},
                    onRevealUrlField = {},
                    onConnected = {},
                )
            }
        }
        compose.onNode(hasText("Welcome to", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Watch movies, shows and live TV.").assertIsDisplayed()
        compose.onNodeWithText("Catalog address").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("https://example.com:8080")
        assertEquals("https://example.com:8080", state.serverUrl)
        // One button only: Connect tests the address when the field is open.
        compose.onNodeWithText("Connect").performClick()
        assertEquals(1, tested)
        // Not connected yet: Continue stays disabled, offline stays offered.
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Continue offline — saved titles only").assertIsDisplayed()
    }

    @Test
    fun setupContentHidesFieldUntilAsked() {
        var retried = 0
        var revealed = 0
        setupContent(
            state = SetupUiState(showUrlField = false),
            onRetryDefault = { retried++ },
            onRevealUrlField = { revealed++ },
        )
        // The address is never printed on first sight: no field, and the
        // one Connect button retries the default catalog.
        compose.onNodeWithText("Catalog address").assertDoesNotExist()
        compose.onNodeWithText("Connect").assertIsDisplayed()
        compose.onNodeWithText("Connect").performClick()
        assertEquals(1, retried)
        compose.onNodeWithText("Use your own catalog").performClick()
        assertEquals(1, revealed)
    }

    @Test
    fun setupContentContinueNeedsConnection() {
        var connected = 0
        setupContent(
            state = SetupUiState(isConnected = true),
            onConnected = { connected++ },
        )
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        assertEquals(1, connected)
        compose.onNodeWithText("Continue offline — saved titles only").assertDoesNotExist()
    }
}
