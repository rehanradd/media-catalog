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
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
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
        onConnected: (askRegion: Boolean) -> Unit = {},
        onContinue: () -> Unit = {},
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
                    onContinue = onContinue,
                )
            }
        }
    }

    // S6: BrowsePosterGrid reads the PagingData now — the mockup tests
    // feed it a static PagingData through a StateFlow, the way the Paging
    // test guide drives load states in isolation.
    private fun browsePagingDataFlow(
        posts: List<Post>,
        append: LoadState = LoadState.NotLoading(endOfPaginationReached = false),
    ): MutableStateFlow<PagingData<Post>> {
        val idle = LoadState.NotLoading(endOfPaginationReached = false)
        return MutableStateFlow(
            PagingData.from(
                data = posts,
                sourceLoadStates = LoadStates(refresh = idle, prepend = idle, append = append),
            ),
        )
    }

    @Test
    fun browsePosterGridShowsTitleAndMeta() {
        val pagingDataFlow = browsePagingDataFlow(
            listOf(
                post(1, "Apex Horizon", 2024, "movie"),
                post(2, "Shadow Protocol", 2023, "series"),
            ),
        )
        compose.setContent {
            RaddStreamTheme {
                val pagingItems = pagingDataFlow.collectAsLazyPagingItems()
                BrowsePosterGrid(
                    pagingItems = pagingItems,
                    gridState = rememberLazyGridState(),
                    onPostClick = {},
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
        val pagingDataFlow = browsePagingDataFlow(listOf(post(1, "Apex Horizon", 2024, "movie")))
        compose.setContent {
            RaddStreamTheme {
                val pagingItems = pagingDataFlow.collectAsLazyPagingItems()
                BrowsePosterGrid(
                    pagingItems = pagingItems,
                    gridState = rememberLazyGridState(),
                    onPostClick = { tapped = it },
                )
            }
        }
        compose.onNode(hasContentDescription("Apex Horizon, 2024 · Movie")).performClick()
        assertEquals(1, tapped?.id)
    }

    @Test
    fun browsePosterGridLoadFailureRetries() {
        // S6: the retry is Paging's now — there is no callback left to
        // count. What this pins is the row itself: the friendly message
        // and a Retry button that re-collects the page without crashing
        // the grid (the static flow re-emits the same failed page, so
        // the row is still there after the tap).
        val pagingDataFlow = browsePagingDataFlow(
            listOf(post(1, "Apex Horizon", 2024, "movie")),
            append = LoadState.Error(IOException("Next page failed")),
        )
        compose.setContent {
            RaddStreamTheme {
                val pagingItems = pagingDataFlow.collectAsLazyPagingItems()
                BrowsePosterGrid(
                    pagingItems = pagingItems,
                    gridState = rememberLazyGridState(),
                    onPostClick = {},
                )
            }
        }
        compose.onNodeWithText("Could not load more.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.onNodeWithText("Apex Horizon").assertIsDisplayed()
        compose.onNodeWithText("Could not load more.").assertIsDisplayed()
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
                    onContinue = {},
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
            onContinue = { connected++ },
        )
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        assertEquals(1, connected)
        compose.onNodeWithText("Continue offline — saved titles only").assertDoesNotExist()
    }
}
