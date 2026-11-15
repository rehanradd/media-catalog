package com.raddstream.app.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home filter chips (mockup, Nov 2026): ALL / MOVIES / SERIES / ANIME /
 * KPOP filter the content rails in place. The match is the rail's own
 * identity — its [HomeSection.type] first, then its key, so a panel rail
 * (whose type is null — the panel owns the rows, not the type) still matches
 * by key. A mixed rail ("Recently added", "Most copies", the regional rails
 * with no single type) shows only under ALL: the filter never invents a type
 * for a rail that has none. The hero, Continue Watching, Pick 3 and the
 * library are not rails and are never filtered — only these rows.
 */
class HomeFilterTest {

    private fun section(key: String, type: String?) =
        HomeSection(key = key, title = key, type = type)

    private val movie = section("movie", "movie")
    private val series = section("series", "series")
    private val completeSeason = section("season", "complete_season")
    private val panelMovie = section("panel:movie", null)
    private val panelSeries = section("panel:series", null)
    private val recent = section("recent", null)
    private val mostCopies = section("most_sources", null)
    private val anime = section("anime", "anime")
    private val panelAnime = section("panel:anime", null)
    private val kpop = section("kpop", "kpop")

    private val all = listOf(
        movie, series, completeSeason, panelMovie, panelSeries,
        recent, mostCopies, anime, panelAnime, kpop,
    )

    @Test
    fun allShowsEveryRail() {
        assertTrue(all.all { homeFilterMatches(it, HomeFilter.All) })
    }

    @Test
    fun moviesShowsMovieRailsOnly() {
        assertTrue(homeFilterMatches(movie, HomeFilter.Movies))
        assertTrue(homeFilterMatches(panelMovie, HomeFilter.Movies))
        assertFalse(homeFilterMatches(series, HomeFilter.Movies))
        assertFalse(homeFilterMatches(completeSeason, HomeFilter.Movies))
        assertFalse(homeFilterMatches(recent, HomeFilter.Movies))
        assertFalse(homeFilterMatches(mostCopies, HomeFilter.Movies))
        assertFalse(homeFilterMatches(anime, HomeFilter.Movies))
        assertFalse(homeFilterMatches(kpop, HomeFilter.Movies))
    }

    @Test
    fun seriesShowsSeriesAndCompleteSeasons() {
        assertTrue(homeFilterMatches(series, HomeFilter.Series))
        assertTrue(homeFilterMatches(completeSeason, HomeFilter.Series))
        assertTrue(homeFilterMatches(panelSeries, HomeFilter.Series))
        assertFalse(homeFilterMatches(movie, HomeFilter.Series))
        assertFalse(homeFilterMatches(recent, HomeFilter.Series))
        assertFalse(homeFilterMatches(mostCopies, HomeFilter.Series))
        assertFalse(homeFilterMatches(anime, HomeFilter.Series))
    }

    @Test
    fun animeMatchesByTypeOrPanelKey() {
        assertTrue(homeFilterMatches(anime, HomeFilter.Anime))
        assertTrue(homeFilterMatches(panelAnime, HomeFilter.Anime))
        assertFalse(homeFilterMatches(movie, HomeFilter.Anime))
        assertFalse(homeFilterMatches(series, HomeFilter.Anime))
        assertFalse(homeFilterMatches(recent, HomeFilter.Anime))
    }

    @Test
    fun kpopMatchesByTypeOrKey() {
        assertTrue(homeFilterMatches(kpop, HomeFilter.Kpop))
        assertTrue(homeFilterMatches(section("panel:kpop", null), HomeFilter.Kpop))
        assertFalse(homeFilterMatches(movie, HomeFilter.Kpop))
        assertFalse(homeFilterMatches(recent, HomeFilter.Kpop))
    }

    @Test
    fun mixedRailsHideUnderASpecificChip() {
        // "Recently added" and "Most copies" carry every kind of title, so
        // no chip may claim them — they show under ALL and nowhere else.
        HomeFilter.entries.filter { it != HomeFilter.All }.forEach { filter ->
            assertFalse(homeFilterMatches(recent, filter))
            assertFalse(homeFilterMatches(mostCopies, filter))
        }
    }
}
