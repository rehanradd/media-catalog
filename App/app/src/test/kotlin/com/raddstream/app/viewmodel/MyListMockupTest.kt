package com.raddstream.app.viewmodel

import com.raddstream.app.data.local.FavoritePost
import com.raddstream.app.data.local.WatchHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mockup (Nov 2026): My List paints clean titles, a progress bar only
 * where a real watch row says the viewer stopped halfway, and no sync
 * sentence at all. These tests pin the three pure rules behind that:
 * the title strip, the display mapping, and the progress map.
 */
class MyListMockupTest {

    // Titles

    @Test
    fun `movie suffix stack strips to the bare title`() {
        assertEquals(
            "No Hard Feelings",
            stripMyListTitleSuffixes("No Hard Feelings (2023) English Movie", 2023, "movie"),
        )
    }

    @Test
    fun `series suffix stack strips to the bare title`() {
        assertEquals(
            "Dune: Part Two",
            stripMyListTitleSuffixes("Dune: Part Two (2024) Hindi Series", 2024, "series"),
        )
        assertEquals(
            "Gen V",
            stripMyListTitleSuffixes("Gen V (2023) English Series", 2023, "series"),
        )
    }

    @Test
    fun `year strips even when the post carries none`() {
        assertEquals(
            "No Hard Feelings",
            stripMyListTitleSuffixes("No Hard Feelings (2023) English Movie", null, "movie"),
        )
    }

    @Test
    fun `a title that merely ends in a language word keeps it`() {
        assertEquals(
            "Broken English",
            stripMyListTitleSuffixes("Broken English", 2023, "movie"),
        )
        assertEquals(
            "The English Patient",
            stripMyListTitleSuffixes("The English Patient", 1996, "movie"),
        )
    }

    @Test
    fun `a bare year keeps its digits`() {
        assertEquals("1917", stripMyListTitleSuffixes("1917", 2019, "movie"))
    }

    @Test
    fun `a title with no type suffix is left alone`() {
        // No "Movie"/"Series" tail, so the language rule never fires —
        // this shape is the server display_title's job, not the strip's.
        assertEquals(
            "No Hard Feelings (2023) v2 – HDCAMRip",
            stripMyListTitleSuffixes("No Hard Feelings (2023) v2 – HDCAMRip", 2023, "movie"),
        )
        assertEquals(
            "Kesariya@100",
            stripMyListTitleSuffixes("Kesariya@100", 2025, "series"),
        )
    }

    @Test
    fun `display post paints the stripped title`() {
        val favorite = FavoritePost(
            id = 1,
            title = "No Hard Feelings (2023) English Movie",
            cleanTitle = "No Hard Feelings (2023) English Movie",
            year = "2023",
            contentType = "movie",
        )
        val post = myListDisplayPost(favorite)
        assertEquals("No Hard Feelings", post.cleanTitle)
        assertEquals("No Hard Feelings", post.displayTitle)
        assertEquals("No Hard Feelings (2023) English Movie", post.title)
    }

    @Test
    fun `display post falls back to the raw title when clean is blank`() {
        val favorite = FavoritePost(
            id = 2,
            title = "No Hard Feelings (2023) English Movie",
            cleanTitle = null,
            year = "2023",
            contentType = "movie",
        )
        assertEquals("No Hard Feelings", myListDisplayPost(favorite).cleanTitle)
    }

    @Test
    fun `display post never paints an empty title`() {
        // A strip that would leave nothing keeps the row as saved.
        val favorite = FavoritePost(
            id = 3,
            title = "(2023) English Movie",
            cleanTitle = "(2023) English Movie",
            year = "2023",
            contentType = "movie",
        )
        assertEquals("(2023) English Movie", myListDisplayPost(favorite).cleanTitle)
    }

    // Progress

    private fun row(
        postId: Int,
        positionMs: Long,
        durationMs: Long,
        updatedAt: Long = 1_000L,
    ) = WatchHistoryEntity(
        workRef = "work-$postId",
        postId = postId,
        title = "Title $postId",
        posterUrl = null,
        seasonNumber = 0,
        episodeNumber = 0,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt,
    )

    @Test
    fun `a resumable row paints its fraction`() {
        assertEquals(
            mapOf(1 to 0.5f),
            myListProgressByPostId(listOf(row(1, positionMs = 60_000L, durationMs = 120_000L))),
        )
    }

    @Test
    fun `a finished title paints no bar`() {
        assertTrue(
            myListProgressByPostId(
                listOf(row(1, positionMs = 115_000L, durationMs = 120_000L)),
            ).isEmpty(),
        )
    }

    @Test
    fun `a row with no duration paints no bar`() {
        assertTrue(
            myListProgressByPostId(
                listOf(row(1, positionMs = 60_000L, durationMs = 0L)),
            ).isEmpty(),
        )
    }

    @Test
    fun `a barely-started row paints no bar`() {
        assertTrue(
            myListProgressByPostId(
                listOf(row(1, positionMs = 500L, durationMs = 120_000L)),
            ).isEmpty(),
        )
    }

    @Test
    fun `the latest row per title wins`() {
        val history = listOf(
            row(1, positionMs = 30_000L, durationMs = 120_000L, updatedAt = 1_000L),
            row(1, positionMs = 90_000L, durationMs = 120_000L, updatedAt = 2_000L),
            row(2, positionMs = 60_000L, durationMs = 120_000L, updatedAt = 1_500L),
        )
        assertEquals(mapOf(1 to 0.75f, 2 to 0.5f), myListProgressByPostId(history))
    }

    @Test
    fun `a finished latest row hides an older resumable one`() {
        val history = listOf(
            row(1, positionMs = 60_000L, durationMs = 120_000L, updatedAt = 1_000L),
            row(1, positionMs = 115_000L, durationMs = 120_000L, updatedAt = 2_000L),
        )
        assertTrue(myListProgressByPostId(history).isEmpty())
    }

    @Test
    fun `empty history paints nothing`() {
        assertEquals(emptyMap<Int, Float>(), myListProgressByPostId(emptyList()))
        assertNull(myListProgressByPostId(emptyList())[1])
    }
}
