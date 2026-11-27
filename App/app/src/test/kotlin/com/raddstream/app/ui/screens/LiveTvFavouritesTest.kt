package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.LiveTvChannel
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.LiveTvViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * E2 slice 1 — Live TV favourites: the star on the card, the Favourites
 * chip at the head of the row, and the starred-only grid. The persistence
 * half lives in [com.raddstream.app.repository.LiveTvFavouritesRepositoryTest];
 * this file pins the state the screen reads and the taps the viewer makes.
 */
@RunWith(RobolectricTestRunner::class)
class LiveTvFavouritesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun channel(id: Int, name: String, category: String = "news") = LiveTvChannel(
        id = id,
        name = name,
        category = category,
        country = "PK",
        language = "urdu",
        logoUrl = "",
        streamId = "stream-$id",
    )

    private fun stateWith(
        favorites: Set<Int> = emptySet(),
        showFavoritesOnly: Boolean = false,
        query: String = "",
        category: String? = null,
    ) = LiveTvViewModel.UiState(
        channels = listOf(
            channel(1, "Geo News"),
            channel(2, "ARY Digital", "entertainment"),
            channel(3, "Dunya News"),
        ),
        categories = listOf("news", "entertainment"),
        selectedCategory = category,
        query = query,
        favoriteChannelIds = favorites,
        showFavoritesOnly = showFavoritesOnly,
    )

    // ── UiState: the starred-only grid ──────────────────────────────────────

    @Test
    fun favouritesOnlyShowsOnlyStarredChannels() {
        val visible = stateWith(favorites = setOf(1, 3), showFavoritesOnly = true).visibleChannels
        assertEquals(listOf(1, 3), visible.map { it.id })
    }

    @Test
    fun favouritesOnlyWithNothingStarredShowsNothing() {
        assertTrue(stateWith(showFavoritesOnly = true).visibleChannels.isEmpty())
    }

    @Test
    fun starredIdsThatLeftTheRegistryShowNothing() {
        // A star outlives the channel it names; when the registry drops
        // that channel the Favourites view is empty, not a stale card.
        assertTrue(stateWith(favorites = setOf(99), showFavoritesOnly = true).visibleChannels.isEmpty())
    }

    @Test
    fun favouritesOffShowsTheWholeList() {
        val visible = stateWith(favorites = setOf(1)).visibleChannels
        assertEquals(listOf(1, 2, 3), visible.map { it.id })
    }

    @Test
    fun searchStillNarrowsInsideFavourites() {
        val visible = stateWith(
            favorites = setOf(1, 3),
            showFavoritesOnly = true,
            query = "dunya",
        ).visibleChannels
        assertEquals(listOf(3), visible.map { it.id })
    }

    @Test
    fun categoryStillNarrowsInsideFavourites() {
        // The ViewModel clears the category when Favourites turns on, but
        // the state itself must not let a stale category leak starred
        // channels from another category back in either way: favourites
        // first, then the category, so a starred news channel never shows
        // under entertainment.
        val visible = stateWith(
            favorites = setOf(1, 2),
            showFavoritesOnly = true,
            category = "entertainment",
        ).visibleChannels
        assertEquals(listOf(2), visible.map { it.id })
    }

    // ── Category row: the Favourites chip ───────────────────────────────────

    @Test
    fun favouritesChipIsSelectedOnlyWhileFavouritesOwnTheGrid() {
        compose.setContent {
            RaddStreamTheme {
                LiveTvCategoryRow(
                    categories = listOf("news", "sports"),
                    selectedCategory = null,
                    onCategorySelected = {},
                    showFavoritesOnly = true,
                    onFavouritesSelected = {},
                )
            }
        }
        compose.onNodeWithText("Favourites").assertIsSelected()
        compose.onNodeWithText("All").assertIsNotSelected()
    }

    @Test
    fun favouritesChipTapReportsTheToggle() {
        var reported: Boolean? = null
        compose.setContent {
            RaddStreamTheme {
                LiveTvCategoryRow(
                    categories = listOf("news"),
                    selectedCategory = null,
                    onCategorySelected = {},
                    showFavoritesOnly = false,
                    onFavouritesSelected = { reported = it },
                )
            }
        }
        compose.onNodeWithTag("liveTv_chip_favourites").performClick()
        assertEquals(true, reported)
    }

    @Test
    fun favouritesChipTapWhileSelectedReportsOff() {
        var reported: Boolean? = null
        compose.setContent {
            RaddStreamTheme {
                LiveTvCategoryRow(
                    categories = listOf("news"),
                    selectedCategory = null,
                    onCategorySelected = {},
                    showFavoritesOnly = true,
                    onFavouritesSelected = { reported = it },
                )
            }
        }
        compose.onNodeWithTag("liveTv_chip_favourites").performClick()
        assertEquals(false, reported)
    }

    @Test
    fun favouritesChipIs36dpTallLikeAll() {
        compose.setContent {
            RaddStreamTheme {
                LiveTvCategoryRow(
                    categories = listOf("news"),
                    selectedCategory = null,
                    onCategorySelected = {},
                )
            }
        }
        val expectedPx = with(compose.density) { 36.dp.toPx() }
        val height = compose.onNodeWithTag("liveTv_chip_favourites")
            .fetchSemanticsNode().boundsInRoot.height
        assertEquals(expectedPx, height, 1f)
    }

    // ── Card: the star toggle ───────────────────────────────────────────────

    @Test
    fun starTapStarsWithoutPlayingTheChannel() {
        var starred = false
        var played = false
        compose.setContent {
            RaddStreamTheme {
                LiveTvChannelCard(
                    channel = channel(1, "Geo News"),
                    onClick = { played = true },
                    isFavorite = false,
                    onFavoriteClick = { starred = true },
                    modifier = Modifier.width(180.dp),
                )
            }
        }
        compose.onNodeWithContentDescription("Star Geo News").assertIsDisplayed()
        compose.onNodeWithTag("liveTv_favorite_1").performClick()
        assertTrue("Tapping the star did not star the channel", starred)
        assertFalse("Tapping the star played the channel", played)
    }

    @Test
    fun starredCardOffersUnstarAndSaysSo() {
        compose.setContent {
            RaddStreamTheme {
                LiveTvChannelCard(
                    channel = channel(1, "Geo News"),
                    onClick = {},
                    isFavorite = true,
                    onFavoriteClick = {},
                    modifier = Modifier.width(180.dp),
                )
            }
        }
        compose.onNodeWithContentDescription("Unstar Geo News").assertIsDisplayed()
        compose.onNode(
            hasContentDescription("Geo News, live · starred · News · Pakistan · Urdu"),
        ).assertExists()
    }

    @Test
    fun unstarredCardSentenceHasNoStarredWord() {
        compose.setContent {
            RaddStreamTheme {
                LiveTvChannelCard(
                    channel = channel(1, "Geo News"),
                    onClick = {},
                    isFavorite = false,
                    onFavoriteClick = {},
                    modifier = Modifier.width(180.dp),
                )
            }
        }
        compose.onNode(
            hasContentDescription("Geo News, live · News · Pakistan · Urdu"),
        ).assertExists()
    }

    // ── Grid: the star state rides with the card ────────────────────────────

    @Test
    fun gridStarsOnlyTheStarredCardAndRoutesItsTap() {
        var starredId: Int? = null
        var playedId: Int? = null
        val channels = listOf(channel(1, "Geo News"), channel(2, "ARY Digital", "entertainment"))
        compose.setContent {
            RaddStreamTheme {
                LiveTvChannelGrid(
                    channels = channels,
                    onChannelClick = { playedId = it.id },
                    favoriteChannelIds = setOf(2),
                    onFavoriteClick = { starredId = it.id },
                )
            }
        }
        compose.onNodeWithContentDescription("Star Geo News").assertIsDisplayed()
        compose.onNodeWithContentDescription("Unstar ARY Digital").assertIsDisplayed()
        compose.onNodeWithTag("liveTv_favorite_2").performClick()
        assertEquals(2, starredId)
        assertEquals("Starring from the grid played the channel", null, playedId)
    }
}
