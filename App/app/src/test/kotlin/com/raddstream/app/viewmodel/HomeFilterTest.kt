package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.Post
import org.junit.Assert.assertEquals
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

    // Video audit #31 (Nov 2026): the ANIME chip showed "No anime here
    // yet" while the hero and rails were full of anime. Anime is a
    // post-level flag, not a rail type — no built-in rail matches it —
    // so the chip narrows a rail to its anime titles instead of hiding
    // every rail. The server's own definition is the fragment
    // `(is_animated = 1 OR industry LIKE '%anime%')` (10,739 rows live).
    private fun post(
        id: Int,
        title: String,
        contentType: String = "movie",
        isAnimated: Boolean? = null,
        industry: String? = null,
        genres: String? = null,
    ) = Post(
        id = id,
        title = title,
        cleanTitle = title,
        contentType = contentType,
        isAnimated = isAnimated,
        industry = industry,
        genres = genres,
    )

    private val animeSeries = post(1, "Ranma 1/2", contentType = "series", isAnimated = true)
    private val animeMovie = post(2, "Blue Box", isAnimated = true)
    private val animeByIndustry = post(3, "Still Wants to Fight", contentType = "series", industry = "Anime")
    private val plainMovie = post(4, "Jawan")
    private val kpopTitle = post(5, "KPop Demon Hunters")

    @Test
    fun animeNarrowsMixedRailsToAnimePosts() {
        val mixed = HomeSection(key = "recent", title = "Recently added", type = null, items = listOf(animeSeries, plainMovie, animeMovie))
        val kept = filteredHomeSections(listOf(mixed), HomeFilter.Anime)
        assertEquals(1, kept.size)
        assertEquals(listOf(animeSeries, animeMovie), kept[0].items)
    }

    @Test
    fun animeCountsIndustryWhenTheFlagIsAbsent() {
        val mixed = HomeSection(key = "recent", title = "Recently added", type = null, items = listOf(animeByIndustry, plainMovie))
        val kept = filteredHomeSections(listOf(mixed), HomeFilter.Anime)
        assertEquals(1, kept.size)
        assertEquals(listOf(animeByIndustry), kept[0].items)
    }

    @Test
    fun animeDropsRailsWithNoAnimePosts() {
        val mixed = HomeSection(key = "recent", title = "Recently added", type = null, items = listOf(plainMovie))
        assertTrue(filteredHomeSections(listOf(mixed), HomeFilter.Anime).isEmpty())
        // And a rail with nothing loaded yet still hides — there is no
        // anime to show, so the empty message stays honest.
        assertTrue(filteredHomeSections(listOf(recent), HomeFilter.Anime).isEmpty())
    }

    @Test
    fun animeKeepsPanelAnimeRailWhole() {
        val panel = HomeSection(key = "panel:anime", title = "Anime", type = null, items = listOf(animeSeries, plainMovie))
        val kept = filteredHomeSections(listOf(panel), HomeFilter.Anime)
        assertEquals(1, kept.size)
        // A rail that already IS the anime rail passes through whole —
        // its rows are anime by construction, never narrowed.
        assertEquals(listOf(animeSeries, plainMovie), kept[0].items)
    }

    @Test
    fun kpopNarrowsToKpopTitles() {
        val mixed = HomeSection(key = "recent", title = "Recently added", type = null, items = listOf(kpopTitle, plainMovie))
        val kept = filteredHomeSections(listOf(mixed), HomeFilter.Kpop)
        assertEquals(1, kept.size)
        assertEquals(listOf(kpopTitle), kept[0].items)
        // No K-pop taxonomy exists on the server (`type=kpop` answers
        // zero rows live) — a rail with no K-pop title still hides, and
        // the empty message is honest for what is loaded.
        val plain = HomeSection(key = "movie", title = "Movies", type = "movie", items = listOf(plainMovie))
        assertTrue(filteredHomeSections(listOf(plain), HomeFilter.Kpop).isEmpty())
    }

    @Test
    fun moviesAndSeriesFilteringIsUnchanged() {
        // The post-level fallback is Anime/K-pop only — Movies and Series
        // keep the rail-level match, narrowed rails never appear there.
        val mixed = HomeSection(key = "recent", title = "Recently added", type = null, items = listOf(animeSeries, plainMovie))
        assertEquals(listOf(movie), filteredHomeSections(listOf(movie, series, mixed), HomeFilter.Movies))
        assertEquals(listOf(series), filteredHomeSections(listOf(movie, series, mixed), HomeFilter.Series))
    }
}
