package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.screens.nextSort
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L1 (round 2): the filter chips scope the list — All / Movies / Series /
 * Downloads. Movies is the MOVIE work type; Series is the whole series
 * family (series + seasons + anime); Downloads is titles with a verified
 * copy on the device, by post id (the same id rule the sort's watched set
 * uses, so a same-name remake never cross-matches). Anything the wire
 * calls documentary, sport or music video fits neither Movies nor Series
 * and shows only under All.
 */
class MyListFilterTest {

    private fun post(id: Int, contentType: String, isAnimated: Boolean? = null) = Post(
        id = id,
        title = "Title $id",
        cleanTitle = "Title $id",
        year = 2024,
        contentType = contentType,
        posterUrl = null,
        isAnimated = isAnimated,
    )

    private val rows = listOf(
        post(1, "movie"),
        post(2, "series"),
        post(3, "complete_season"),
        post(4, "documentary"),
        post(5, "unknown", isAnimated = true),
        post(6, "movie", isAnimated = true),
    )

    @Test
    fun `all returns every title`() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6), filterMyList(rows, MyListFilter.ALL, emptySet()).map { it.id })
    }

    @Test
    fun `movies keeps only movies`() {
        // An animated movie is still a movie — the anime flag only
        // promotes a series-family or unknown row.
        assertEquals(listOf(1, 6), filterMyList(rows, MyListFilter.MOVIES, emptySet()).map { it.id })
    }

    @Test
    fun `series keeps the whole series family`() {
        // Series + complete season + the anime-flagged unknown row; the
        // documentary fits neither chip and shows only under All.
        assertEquals(listOf(2, 3, 5), filterMyList(rows, MyListFilter.SERIES, emptySet()).map { it.id })
    }

    @Test
    fun `downloads keeps only titles with a verified copy`() {
        assertEquals(listOf(2, 5), filterMyList(rows, MyListFilter.DOWNLOADS, setOf(2, 5)).map { it.id })
    }

    @Test
    fun `same-title twins never cross-match on downloads`() {
        // Identity is the post id — downloading one "Dune" must not badge
        // its same-name remake.
        val twins = listOf(post(1, "movie"), post(2, "movie"))
        assertEquals(listOf(1), filterMyList(twins, MyListFilter.DOWNLOADS, setOf(1)).map { it.id })
    }

    @Test
    fun `sort button cycles recently added, title, unwatched first`() {
        assertEquals(ListSort.TITLE, nextSort(ListSort.RECENT))
        assertEquals(ListSort.UNWATCHED, nextSort(ListSort.TITLE))
        assertEquals(ListSort.RECENT, nextSort(ListSort.UNWATCHED))
    }
}
