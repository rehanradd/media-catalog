package com.raddstream.app.viewmodel

import com.raddstream.app.data.local.FavoritePost
import com.raddstream.app.data.local.WatchHistoryEntity
import com.raddstream.app.network.models.Post
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

    // Progress in words (My List review, Nov 2026): the same rows say
    // what remains, in the same words Home's Continue Watching rail
    // speaks — and stay silent exactly where the bar stays absent.

    private fun episodeRow(
        postId: Int,
        season: Int,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
        updatedAt: Long = 1_000L,
    ) = WatchHistoryEntity(
        workRef = "work-$postId-s$season-e$episode",
        postId = postId,
        title = "Title $postId",
        posterUrl = null,
        seasonNumber = season,
        episodeNumber = episode,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt,
    )

    @Test
    fun `a resumable movie says how much is left`() {
        assertEquals(
            mapOf(1 to "1 min left"),
            myListProgressLabelByPostId(
                listOf(row(1, positionMs = 60_000L, durationMs = 120_000L)),
            ),
        )
    }

    @Test
    fun `a resumable episode names its scope and what remains`() {
        assertEquals(
            mapOf(1 to "S2:E5 · 23 min left"),
            myListProgressLabelByPostId(
                listOf(
                    episodeRow(
                        1, season = 2, episode = 5,
                        positionMs = 2_220_000L, durationMs = 3_600_000L,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a finished title says nothing`() {
        assertTrue(
            myListProgressLabelByPostId(
                listOf(row(1, positionMs = 115_000L, durationMs = 120_000L)),
            ).isEmpty(),
        )
    }

    @Test
    fun `a row with no duration says nothing`() {
        assertTrue(
            myListProgressLabelByPostId(
                listOf(row(1, positionMs = 60_000L, durationMs = 0L)),
            ).isEmpty(),
        )
    }

    @Test
    fun `labels agree with bars on which titles have anything to say`() {
        val history = listOf(
            row(1, positionMs = 60_000L, durationMs = 120_000L, updatedAt = 1_000L),
            row(2, positionMs = 115_000L, durationMs = 120_000L, updatedAt = 1_000L),
            row(3, positionMs = 60_000L, durationMs = 0L, updatedAt = 1_000L),
        )
        assertEquals(
            myListProgressByPostId(history).keys,
            myListProgressLabelByPostId(history).keys,
        )
    }

    // Starter shelf (My List review, Nov 2026)

    private fun shelfPost(id: Int, title: String, posterUrl: String?) = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = 2024,
        contentType = "movie",
        posterUrl = posterUrl,
    )

    @Test
    fun `starter shelf drops a posterless first title and backfills`() {
        // The review's screenshot: Kesariya@100 sat first with a grey tile.
        // The shelf drops it and pulls the next posterful candidate up, so
        // the rail still shows three real posters.
        val shelf = starterShelf(
            listOf(
                shelfPost(1, "Kesariya@100", null),
                shelfPost(2, "The Penguin", "https://cdn.example/penguin.jpg"),
                shelfPost(3, "Gen V", "https://cdn.example/genv.jpg"),
                shelfPost(4, "Dune", "https://cdn.example/dune.jpg"),
            ),
        )
        assertEquals(listOf("The Penguin", "Gen V", "Dune"), shelf.map { it.title })
    }

    @Test
    fun `starter shelf drops a blank poster url too`() {
        val shelf = starterShelf(
            listOf(
                shelfPost(1, "No Art", ""),
                shelfPost(2, "Has Art", "https://cdn.example/art.jpg"),
            ),
        )
        assertEquals(listOf("Has Art"), shelf.map { it.title })
    }

    @Test
    fun `starter shelf caps at three posterful titles`() {
        val shelf = starterShelf(
            (1..6).map { shelfPost(it, "Title $it", "https://cdn.example/$it.jpg") },
        )
        assertEquals(listOf("Title 1", "Title 2", "Title 3"), shelf.map { it.title })
    }

    @Test
    fun `starter shelf with no posters is empty`() {
        assertTrue(starterShelf(listOf(shelfPost(1, "No Art", null))).isEmpty())
    }
}
