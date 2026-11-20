package com.raddstream.app.player

import com.raddstream.app.network.models.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REA #1 (Netflix, Nov 2026): the post-play preview rules — the merge
 * (server order, de-duplicated, capped at three) and the in-playback
 * Next Episode pill window (last 30 s, successor prepared, neither
 * ended nor locked). No Android, no player: the rules are the whole
 * behaviour, and the ViewModel only publishes what these functions
 * return.
 */
class PostPlayPreviewsTest {

    private fun post(id: Int, title: String, year: Int = 2026, type: String = "series") = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = year,
        contentType = type,
        posterUrl = "https://example.test/p$id.jpg",
    )

    @Test
    fun `merge keeps server order and caps at three`() {
        val similar = (1..5).map { post(it, "Title $it") }
        val previews = mergePostPlayPreviews(similar)
        assertEquals(listOf(1, 2, 3), previews.map { it.postId })
        assertEquals("Title 1", previews[0].title)
        assertEquals("2026 · Series", previews[0].subtitle)
        assertEquals("https://example.test/p1.jpg", previews[0].posterUrl)
    }

    @Test
    fun `merge de-duplicates by catalog id`() {
        val similar = listOf(post(1, "One"), post(2, "Two"), post(1, "One again"), post(3, "Three"))
        assertEquals(listOf(1, 2, 3), mergePostPlayPreviews(similar).map { it.postId })
    }

    @Test
    fun `merge of nothing is nothing`() {
        assertTrue(mergePostPlayPreviews(emptyList()).isEmpty())
    }

    @Test
    fun `a tile without a poster still carries its title`() {
        val preview = post(7, "No Poster", type = "movie").copy(posterUrl = null).toPostPlayPreview()
        assertEquals(7, preview.postId)
        assertEquals("No Poster", preview.title)
        assertEquals("2026 · Movie", preview.subtitle)
        assertEquals(null, preview.posterUrl)
    }

    @Test
    fun `the pill shows in the last thirty seconds with a prepared successor`() {
        assertTrue(showInPlaybackNext(90_000L, 120_000L, true, false, false))
        assertTrue(showInPlaybackNext(120_000L, 120_000L, true, false, false))
    }

    @Test
    fun `the pill stays hidden before the window and without a successor`() {
        assertFalse(showInPlaybackNext(89_999L, 120_000L, true, false, false))
        assertFalse(showInPlaybackNext(110_000L, 120_000L, false, false, false))
        assertFalse(showInPlaybackNext(110_000L, 0L, true, false, false))
    }

    @Test
    fun `the pill stays hidden when ended or locked`() {
        assertFalse(showInPlaybackNext(110_000L, 120_000L, true, true, false))
        assertFalse(showInPlaybackNext(110_000L, 120_000L, true, false, true))
    }
}
