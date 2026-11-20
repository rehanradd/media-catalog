package com.raddstream.app.viewmodel

import com.raddstream.app.data.local.DownloadsForYouEntry
import com.raddstream.app.network.models.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * REA #5: the "Downloads For You" home row — one tile per fetched
 * series, newest fetch first, only while its episode is still on the
 * device. Pure, so the whole rule is pinned here without a repository.
 */
class DownloadsForYouSectionTest {

    private fun post(id: Int, title: String) = Post(
        id = id,
        title = title,
    )

    private fun entry(postId: Int, handle: String, fetchedAt: Long, episode: Int = 1) =
        DownloadsForYouEntry(
            postId = postId,
            season = 1,
            episode = episode,
            title = "Episode $episode",
            handle = handle,
            sizeBytes = 100_000_000L,
            fetchedAt = fetchedAt,
        )

    @Test
    fun `the row shows fetched series newest first`() {
        val pool = listOf(post(7, "Seven"), post(8, "Eight"), post(9, "Nine"))
        val entries = listOf(
            entry(postId = 7, handle = "h-1", fetchedAt = 100L),
            entry(postId = 8, handle = "h-2", fetchedAt = 300L),
            entry(postId = 9, handle = "h-3", fetchedAt = 200L),
        )
        val row = buildDownloadsForYouSection(entries, pool, setOf("h-1", "h-2", "h-3"))
        assertEquals(listOf(8, 9, 7), row?.items?.map { it.id })
        assertEquals(DOWNLOADS_FOR_YOU_KEY, row?.key)
        assertEquals(DOWNLOADS_FOR_YOU_TITLE, row?.title)
    }

    @Test
    fun `one tile per series however many episodes were fetched`() {
        val pool = listOf(post(7, "Seven"))
        val entries = listOf(
            entry(postId = 7, handle = "h-1", fetchedAt = 100L, episode = 1),
            entry(postId = 7, handle = "h-2", fetchedAt = 200L, episode = 2),
        )
        val row = buildDownloadsForYouSection(entries, pool, setOf("h-1", "h-2"))
        assertEquals(listOf(7), row?.items?.map { it.id })
    }

    @Test
    fun `an episode no longer kept is invisible`() {
        val pool = listOf(post(7, "Seven"), post(8, "Eight"))
        val entries = listOf(
            entry(postId = 7, handle = "h-1", fetchedAt = 100L),
            entry(postId = 8, handle = "h-2", fetchedAt = 200L),
        )
        // h-1's keep is gone (watched and auto-deleted): only Eight shows.
        val row = buildDownloadsForYouSection(entries, pool, setOf("h-2"))
        assertEquals(listOf(8), row?.items?.map { it.id })
    }

    @Test
    fun `a fetched series no rail carries has no poster to paint`() {
        val pool = listOf(post(8, "Eight"))
        val entries = listOf(entry(postId = 7, handle = "h-1", fetchedAt = 100L))
        val row = buildDownloadsForYouSection(entries, pool, setOf("h-1"))
        assertNull(row)
    }

    @Test
    fun `no live keeps means no row at all`() {
        val pool = listOf(post(7, "Seven"))
        val entries = listOf(entry(postId = 7, handle = "h-1", fetchedAt = 100L))
        assertNull(buildDownloadsForYouSection(entries, pool, emptySet()))
        assertNull(buildDownloadsForYouSection(emptyList(), pool, setOf("h-1")))
    }

    @Test
    fun `the row sits right after For you, else second`() {
        val forYou = HomeSection(key = FOR_YOU_KEY, title = FOR_YOU_TITLE, type = null)
        val movies = HomeSection(key = "movies", title = "Movies", type = null)
        val row = HomeSection(key = DOWNLOADS_FOR_YOU_KEY, title = DOWNLOADS_FOR_YOU_TITLE, type = null)
        assertEquals(
            listOf("movies", "for_you", "downloads_for_you"),
            insertDownloadsForYouSection(listOf(movies, forYou), row).map { it.key },
        )
        assertEquals(
            listOf("movies", "downloads_for_you"),
            insertDownloadsForYouSection(listOf(movies), row).map { it.key },
        )
        assertEquals(
            listOf("movies"),
            insertDownloadsForYouSection(listOf(movies, row), null).map { it.key },
        )
    }
}
