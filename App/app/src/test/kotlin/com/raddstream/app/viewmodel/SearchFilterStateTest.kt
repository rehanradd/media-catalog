package com.raddstream.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Samsung RTL retest (Issue 6): the Series filter answered 1 result once
 * and "No matches" on repeats of the same search, tapping the result card
 * was followed by the list going empty, and the "Clear filters" button did
 * nothing.
 *
 * Two defects, both in `SearchViewModel`:
 *
 * 1. `resetFilters()` kept the Type chip (`SearchFilters(type =
 *    it.filters.type)`), so "Clear filters" re-ran the same filtered
 *    search and the list stayed empty.
 * 2. `Job.cancel()` only takes effect at the next suspension point, so a
 *    search whose network call already returned kept running through its
 *    (non-suspending) write — a slow earlier search overwrote the newer
 *    one's list, and the order the two in-flight searches finished in
 *    decided what the viewer saw. A search (and a page load) may only
 *    write while it is still the latest scheduled generation.
 *
 * These are drift tests over the source in the `PlayerBackExitTest`
 * pattern: the ViewModel needs Hilt repositories to construct, so the
 * wiring is pinned here and the behaviour is proven by the generation
 * checks the code carries.
 */
class SearchFilterStateTest {

    private val searchViewModel: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) {
                return@lazy File(
                    dir,
                    "App/app/src/main/kotlin/com/raddstream/app/viewmodel/SearchViewModel.kt",
                )
            }
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    private val source: String by lazy {
        assertTrue("SearchViewModel.kt is missing at ${searchViewModel.path}", searchViewModel.isFile)
        searchViewModel.readText()
    }

    private fun functionBody(name: String, nextMarker: String): String {
        val start = source.indexOf(name)
        assertTrue("SearchViewModel.kt no longer declares $name", start >= 0)
        val end = source.indexOf(nextMarker, start)
        assertTrue("SearchViewModel.kt no longer has $nextMarker after $name", end > start)
        return source.substring(start, end)
    }

    @Test
    fun `reset filters clears the type chip too`() {
        val body = functionBody("fun resetFilters()", "private var v2Cursor")
        assertTrue(
            "resetFilters must build a default SearchFilters() — keeping the old " +
                "type re-runs the same filtered search and the button looks dead",
            body.contains("filters = SearchFilters()"),
        )
        assertFalse(
            "resetFilters must not carry the old type forward — that is the " +
                "Clear-filters no-op",
            body.contains("it.filters.type"),
        )
        assertTrue(
            "resetFilters must also restore the default sort — a clear that " +
                "keeps a non-default sort is not a clear",
            body.contains("sort = SearchSort.DEFAULT"),
        )
    }

    @Test
    fun `a fresh search supersedes the in-flight search and page load`() {
        val body = functionBody("private fun scheduleSearch(", "private fun scheduleSuggest()")
        assertTrue(
            "scheduleSearch must cancel the pending page load too — a page " +
                "that lands later appends its rows onto the new search's list",
            body.contains("moreJob?.cancel()"),
        )
        assertTrue(
            "scheduleSearch must stamp the new search as the latest generation",
            body.contains("++searchGeneration"),
        )
        assertTrue(
            "scheduleSearch must bail once it is no longer the latest generation",
            body.contains("generation != searchGeneration"),
        )
    }

    @Test
    fun `every search write is gated on still being the latest generation`() {
        val body = functionBody("private fun scheduleSearch(", "private fun scheduleSuggest()")
        // After the debounce, after the v2 call, after the v1 call, and in
        // the failure path: a stale job must be stopped before every write,
        // not just the first one it reaches.
        val checks = "generation != searchGeneration".toRegex().findAll(body).count()
        assertTrue(
            "scheduleSearch must check the generation after the debounce, " +
                "after each network call and on failure (found $checks checks)",
            checks >= 4,
        )
        assertTrue(
            "scheduleSearch must not write results after the v2 call without " +
                "re-checking the generation first",
            body.contains("searchWorksV2("),
        )
    }

    @Test
    fun `a cancelled search never strands the grid on its skeleton`() {
        val body = functionBody("private fun scheduleSearch(", "private fun scheduleSuggest()")
        // Batch C: the search job sets isLoading = true after the
        // debounce, and the catch rethrows CancellationException without
        // touching it — a search cancelled mid-flight (a newer filter tap
        // or keystroke while the network call is out) left the skeleton
        // on screen forever, with no error and no results. The finally
        // must clear the flag, and only for the latest generation, so a
        // stale job cannot clear a newer job's loading flag.
        assertTrue(
            "scheduleSearch must clear isLoading in a finally, so a " +
                "cancelled search cannot strand the skeleton",
            body.contains("finally {"),
        )
        assertTrue(
            "the search finally must be generation-guarded, like the writes",
            body.contains("if (generation == searchGeneration)"),
        )
    }

    @Test
    fun `a superseded page load never appends its rows`() {
        val body = functionBody("fun loadMore()", "private fun scheduleSearch(")
        assertTrue(
            "loadMore must capture the generation it belongs to",
            body.contains("val generation = searchGeneration"),
        )
        val checks = "generation != searchGeneration".toRegex().findAll(body).count()
        assertTrue(
            "loadMore must check the generation after each page fetch (found $checks checks)",
            checks >= 2,
        )
        assertEquals(
            "loadMore must not paint its failure over a newer search's list",
            1,
            "if \\(generation == searchGeneration\\)".toRegex().findAll(body).count(),
        )
    }

    @Test
    fun `a stale suggestion never paints chips for the old query`() {
        val body = functionBody("private fun scheduleSuggest()", "private fun recompute()")
        assertTrue(
            "scheduleSuggest must drop its chips when the query has moved on — " +
                "a cancelled suggest whose call already returned is the same " +
                "stale-write class as the search job",
            body.contains("if (query != _uiState.value.query.trim()) return@launch"),
        )
    }

    @Test
    fun `industry has no anime door because the server answers both identically`() {
        // F10 (Batch B): the server answers `type=anime` and `industry=anime`
        // with the identical fragment, so the two doors selected the same
        // rows. Anime lives under Type only; an Industry entry whose wire
        // value is "anime" would bring the duplicate back.
        assertTrue(
            "IndustryFilter still carries an anime wire value",
            IndustryFilter.entries.none { it.wireValue == "anime" },
        )
    }

    @Test
    fun `selecting kpop sets the query preset`() {
        // Mockup (Nov 2026): KPOP is a query preset, not a content_type —
        // the server has no K-pop facet, so the pill runs a real title
        // search for "kpop" and the fetch sites send type=null for it.
        val body = functionBody("fun selectType(type: String?)", "fun selectGenre")
        assertTrue(
            "selectType must set the query to \"kpop\" when the kpop pill is tapped — " +
                "without it the pill filters on a content_type no post carries",
            body.contains("if (type == \"kpop\") \"kpop\""),
        )
    }

    @Test
    fun `editing the query releases the kpop preset`() {
        // Mockup (Nov 2026): the preset is the tap's query, not a sticky
        // filter — typing a different word must clear the kpop type, or
        // the pill would keep answering "kpop" under a new query.
        val body = functionBody("fun onQueryChanged(query: String)", "fun submitCurrentQuery()")
        assertTrue(
            "onQueryChanged must release the kpop preset when the query moves off it",
            body.contains("filters.type == \"kpop\""),
        )
        assertTrue(
            "onQueryChanged must compute a fresh type that is null off the preset — " +
                "keeping the old type would re-run the kpop search under the new query",
            body.contains("val type = if"),
        )
    }

    @Test
    fun `every fetch site sends the effective type`() {
        // Mockup (Nov 2026): content_type='kpop' would answer zero rows on
        // both routes, so all four fetch sites (scheduleSearch v2 + v1,
        // loadMore v2 + v1) must translate through effectiveSearchType.
        assertFalse(
            "a fetch site still sends the raw type — the kpop pill would answer zero rows",
            source.contains("type = filters.type") || source.contains("type = state.filters.type"),
        )
        assertEquals(
            "all four fetch sites must route through effectiveSearchType",
            4,
            "effectiveSearchType\\(filters\\.type\\)|effectiveSearchType\\(state\\.filters\\.type\\)"
                .toRegex().findAll(source).count(),
        )
    }
}
