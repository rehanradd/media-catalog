package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * H11 (2027 Home fix, round 2): the Continue Watching row a saved post
 * is already in. The match is by server handle first, title second — and
 * never invented: no row means no progress on the strip tile.
 */
class HomeMyListStripTest {

    private fun post(
        id: Int = 1,
        title: String = "Jawan",
        year: Int? = 2023,
        workRef: String? = "work:jawan",
    ) = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = year,
        contentType = "movie",
        workRef = workRef,
    )

    private fun tile(
        title: String = "Jawan",
        workRef: String = "work:jawan",
        positionMs: Long = 1_200_000L,
        durationMs: Long = 3_600_000L,
    ) = ResumeTile(
        // ResumeTile.of builds this Post from a local row: no year, no
        // workRef of its own — the handle lives on the tile, not the post.
        post = Post(id = 1, title = title, cleanTitle = title),
        season = 1,
        episode = 3,
        positionMs = positionMs,
        durationMs = durationMs,
        workRef = workRef,
    )

    @Test
    fun matchesByServerHandle() {
        val row = tile(workRef = "work:jawan")
        assertEquals(row, continueWatchingMatchFor(post(workRef = "work:jawan"), listOf(row)))
    }

    @Test
    fun noRowMeansNoMatch() {
        assertNull(continueWatchingMatchFor(post(), emptyList()))
        assertNull(
            continueWatchingMatchFor(
                post(workRef = "work:salaar"),
                listOf(tile(workRef = "work:jawan")),
            ),
        )
    }

    @Test
    fun fallsBackToTheTitleWhenTheSavedPostHasNoHandle() {
        // A v1 saved row carries no handle — the title is the only match,
        // year-blind, because the tile's Post carries no year either.
        val row = tile(title = "Jawan", workRef = "work:jawan")
        assertEquals(
            row,
            continueWatchingMatchFor(post(workRef = null, year = 2023), listOf(row)),
        )
    }

    @Test
    fun titleFallbackIgnoresTheYear() {
        // The saved post has a year; the tile's Post has none. A
        // year-strict key would never match — the title alone must.
        val row = tile(title = "Jawan", workRef = "work:other")
        assertEquals(
            row,
            continueWatchingMatchFor(post(workRef = null, year = 2023), listOf(row)),
        )
    }

    @Test
    fun titleFallbackDoesNotMatchADifferentTitle() {
        assertNull(
            continueWatchingMatchFor(
                post(title = "Salaar", workRef = null),
                listOf(tile(title = "Jawan")),
            ),
        )
    }

    @Test
    fun handleMatchWinsOverATitleMatch() {
        // Two rows share a title (a remake); the handle names the right one.
        val wrong = tile(title = "Jawan", workRef = "work:jawan-2019")
        val right = tile(title = "Jawan", workRef = "work:jawan")
        assertEquals(
            right,
            continueWatchingMatchFor(post(workRef = "work:jawan"), listOf(wrong, right)),
        )
    }
}
