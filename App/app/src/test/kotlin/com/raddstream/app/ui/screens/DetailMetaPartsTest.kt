package com.raddstream.app.ui.screens

import com.raddstream.app.network.models.PostDetail
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Detail review (Nov 2026, P1): the hero's meta line is built once
 * ([detailMetaParts]) and shared by the full-bleed overlay and the
 * no-backdrop poster header. "Movie" adds nothing — the page IS a
 * movie — while a series keeps its type word and its season count.
 */
class DetailMetaPartsTest {

    private fun post(
        contentType: String? = null,
        year: Int? = null,
        language: String? = null,
        runtimeMinutes: Int? = null,
    ) = PostDetail(
        id = 1,
        title = "Jawan",
        year = year,
        runtimeMinutes = runtimeMinutes,
        contentType = contentType,
        language = language,
    )

    @Test
    fun movieDropsTheTypeWord() {
        assertEquals(
            listOf("2023", "Hindi", "2 h 49 m"),
            detailMetaParts(
                post(contentType = "movie", year = 2023, language = "Hindi", runtimeMinutes = 169),
                seasonCount = 0,
            ),
        )
    }

    @Test
    fun seriesKeepsTypeAndSeasonCount() {
        assertEquals(
            listOf("2021", "Series", "Spanish", "2 seasons"),
            detailMetaParts(
                post(contentType = "series", year = 2021, language = "Spanish"),
                seasonCount = 2,
            ),
        )
    }

    @Test
    fun unknownLanguageIsDropped() {
        assertEquals(
            listOf("2023"),
            detailMetaParts(
                post(contentType = "movie", year = 2023, language = "Unknown"),
                seasonCount = 0,
            ),
        )
    }

    @Test
    fun multiLanguageIsDropped() {
        // Dev-cleanup (Nov 2026): "Multi" is the server's multi-audio
        // marker, not a language — the One Piece detail page read
        // "Series · Multi · 1 season". The segment drops; "Series" stays.
        assertEquals(
            listOf("Series", "1 season"),
            detailMetaParts(
                post(contentType = "series", language = "Multi"),
                seasonCount = 1,
            ),
        )
    }

    @Test
    fun emptyPostHasNoMeta() {
        assertEquals(emptyList<String>(), detailMetaParts(post(), seasonCount = 0))
    }
}
