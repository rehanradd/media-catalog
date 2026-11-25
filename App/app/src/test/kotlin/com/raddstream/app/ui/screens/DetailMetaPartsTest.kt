package com.raddstream.app.ui.screens

import com.raddstream.app.network.models.PostDetail
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Detail review (Nov 2026, P1): the hero's meta line is built once
 * ([detailMetaParts]) and shared by the full-bleed overlay and the
 * no-backdrop poster header. "Movie" adds nothing — the page IS a
 * movie — while a series keeps its type word and its season count.
 *
 * S2 (round 2, Nov 2026): the brief's meta convention — year ·
 * duration · quality. Language is NOT in this line any more: it lives
 * in the Audio row below ("Audio: Hindi ›"), where a viewer goes to
 * change it. The quality is the picked band ("1080p") or what Auto
 * reaches ("Auto · up to 4K").
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
            listOf("2023", "2 h 49 m"),
            detailMetaParts(
                post(contentType = "movie", year = 2023, language = "Hindi", runtimeMinutes = 169),
                seasonCount = 0,
            ),
        )
    }

    @Test
    fun seriesKeepsTypeAndSeasonCount() {
        assertEquals(
            listOf("2021", "Series", "2 seasons"),
            detailMetaParts(
                post(contentType = "series", year = 2021, language = "Spanish"),
                seasonCount = 2,
            ),
        )
    }

    @Test
    fun knownLanguageNeverAppearsInTheMetaLine() {
        // S2 (round 2): language left the meta line entirely — it lives
        // in the Audio row. Even a known language never paints here.
        assertEquals(
            listOf("2023", "2 h 49 m"),
            detailMetaParts(
                post(contentType = "movie", year = 2023, language = "Hindi", runtimeMinutes = 169),
                seasonCount = 0,
            ),
        )
    }

    @Test
    fun multiLanguageIsDropped() {
        // Dev-cleanup (Nov 2026): "Multi" is the server's multi-audio
        // marker, not a language — the One Piece detail page read
        // "Series · Multi · 1 season". S2 dropped language from the
        // line outright, so the marker cannot paint here either.
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

    @Test
    fun matchPercentLeadsTheMetaLine() {
        assertEquals(
            listOf("97% Match", "2023", "2 h 49 m"),
            detailMetaParts(
                post(contentType = "movie", year = 2023, language = "Hindi", runtimeMinutes = 169),
                seasonCount = 0,
                matchPercent = 97,
            ),
        )
    }

    @Test
    fun noMatchPercentMeansTheMetaLineIsExactlyAsBefore() {
        assertEquals(
            listOf("2023", "2 h 49 m"),
            detailMetaParts(
                post(contentType = "movie", year = 2023, language = "Hindi", runtimeMinutes = 169),
                seasonCount = 0,
                matchPercent = null,
            ),
        )
    }

    // ── S2 (round 2): quality in the meta line ──

    @Test
    fun qualityFollowsDurationInTheMetaLine() {
        assertEquals(
            listOf("2023", "2 h 49 m", "1080p"),
            detailMetaParts(
                post(contentType = "movie", year = 2023, runtimeMinutes = 169),
                seasonCount = 0,
                quality = "1080p",
            ),
        )
    }

    @Test
    fun qualityShowsAutoUpToTheBestBand() {
        assertEquals(
            listOf("2023", "2 h 49 m", "Auto · up to 4K"),
            detailMetaParts(
                post(contentType = "movie", year = 2023, runtimeMinutes = 169),
                seasonCount = 0,
                quality = "Auto · up to 4K",
            ),
        )
    }

    @Test
    fun blankQualityIsDropped() {
        assertEquals(
            listOf("2023", "2 h 49 m"),
            detailMetaParts(
                post(contentType = "movie", year = 2023, runtimeMinutes = 169),
                seasonCount = 0,
                quality = " ",
            ),
        )
        assertEquals(
            listOf("2023", "2 h 49 m"),
            detailMetaParts(
                post(contentType = "movie", year = 2023, runtimeMinutes = 169),
                seasonCount = 0,
                quality = null,
            ),
        )
    }

    @Test
    fun fullMetaLineOrderIsMatchYearTypeDurationQualitySeasons() {
        assertEquals(
            listOf("97% Match", "2021", "Series", "45 min", "720p", "2 seasons"),
            detailMetaParts(
                post(contentType = "series", year = 2021, runtimeMinutes = 45),
                seasonCount = 2,
                matchPercent = 97,
                quality = "720p",
            ),
        )
    }
}
