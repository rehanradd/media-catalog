package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.Post
import com.raddstream.app.player.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MX #7 — Kids Lock: the mode's read-through views. The raw lists stay
 * whole underneath the flag, so turning the mode off restores the full
 * screen with no re-fetch — these tests pin that the filtered view is
 * what the screens render, in both directions.
 *
 * The Pager filter (Search, Browse) and the Detail refuse path need the
 * full ViewModel harness, so they are proven by the CI run and the device
 * check instead; the pure state below is what a unit test can own.
 */
class KidsModeStateTest {

    private fun post(id: Int, genres: String) = Post(id = id, title = "Title $id", genres = genres)

    private fun section(key: String, vararg posts: Post) = HomeSection(
        key = key,
        title = key,
        type = null,
        items = posts.toList(),
    )

    private fun tile(post: Post) = ResumeTile(
        post = post,
        season = 0,
        episode = 0,
        positionMs = 0L,
        durationMs = 0L,
        workRef = "work-${post.id}",
    )

    // Home

    @Test
    fun `home sections filter to kid-appropriate rows in kids mode`() {
        val state = HomeUiState(
            kidsMode = true,
            sections = listOf(
                section("mixed", post(1, "Animation, Family"), post(2, "Horror"), post(3, "Comedy, Horror")),
                section("adult-only", post(4, "Horror, Thriller")),
            ),
        )

        val visible = state.visibleSections
        assertEquals(listOf("mixed"), visible.map { it.key })
        assertEquals(listOf(1, 3), visible.single().items.map { it.id })
    }

    @Test
    fun `home sections pass through unchanged when kids mode is off`() {
        val sections = listOf(
            section("mixed", post(1, "Animation, Family"), post(2, "Horror")),
        )
        val state = HomeUiState(kidsMode = false, sections = sections)

        assertEquals(sections, state.visibleSections)
    }

    @Test
    fun `home keeps a loading rail in kids mode even with no rows yet`() {
        val state = HomeUiState(
            kidsMode = true,
            sections = listOf(section("loading").copy(loading = true)),
        )

        assertEquals(listOf("loading"), state.visibleSections.map { it.key })
    }

    @Test
    fun `home keeps an errored rail in kids mode`() {
        val state = HomeUiState(
            kidsMode = true,
            sections = listOf(section("failed").copy(error = "No connection")),
        )

        assertEquals(listOf("failed"), state.visibleSections.map { it.key })
    }

    @Test
    fun `continue watching filters to kid-appropriate tiles in kids mode`() {
        val state = HomeUiState(
            kidsMode = true,
            continueWatching = listOf(
                tile(post(1, "Animation")),
                tile(post(2, "Action, Thriller")),
            ),
        )

        assertEquals(listOf(1), state.visibleContinueWatching.map { it.post.id })
    }

    @Test
    fun `pick three never shows in kids mode`() {
        // The latch alone would show the card in adult mode.
        val adult = HomeUiState(kidsMode = false, pickThreeShownThisVisit = true)
        assertTrue(adult.showPickThree)

        val kids = adult.copy(kidsMode = true)
        assertFalse(kids.showPickThree)
    }

    // Search

    @Test
    fun `search hides recents suggestions locals and discovery in kids mode`() {
        val state = SearchUiState(
            kidsMode = true,
            recents = listOf("frozen"),
            suggestions = listOf(com.raddstream.app.network.models.SuggestItem(id = -1, title = "frozen", year = null)),
            localResults = listOf(LocalSearchResult(title = "frozen", year = null, type = null, posterUrl = null)),
            localResultsQuery = "frozen",
            trending = listOf(post(1, "Animation")),
            recommended = listOf(post(2, "Family")),
        )

        assertTrue(state.visibleRecents.isEmpty())
        assertTrue(state.visibleSuggestions.isEmpty())
        assertTrue(state.visibleLocalResults.isEmpty())
        assertTrue(state.visibleTrending.isEmpty())
        assertTrue(state.visibleRecommended.isEmpty())
        assertFalse(state.hasLocalResults)
    }

    @Test
    fun `search passes everything through when kids mode is off`() {
        val state = SearchUiState(
            kidsMode = false,
            recents = listOf("frozen"),
            trending = listOf(post(1, "Animation")),
            recommended = listOf(post(2, "Family")),
            localResults = listOf(LocalSearchResult(title = "frozen", year = null, type = null, posterUrl = null)),
            localResultsQuery = "frozen",
            query = "frozen",
        )

        assertEquals(listOf("frozen"), state.visibleRecents)
        assertEquals(listOf(1), state.visibleTrending.map { it.id })
        assertEquals(listOf(2), state.visibleRecommended.map { it.id })
        assertTrue(state.hasLocalResults)
    }

    // Detail + Player defaults

    @Test
    fun `detail and player default to adult mode unblocked`() {
        assertFalse(DetailUiState().kidsMode)
        assertFalse(DetailUiState().kidsBlocked)
        assertFalse(PlayerUiState().kidsMode)
        assertFalse(PlayerUiState().locked)
    }
}
