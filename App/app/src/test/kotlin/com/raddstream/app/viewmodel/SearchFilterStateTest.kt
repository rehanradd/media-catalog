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
 * Feel-instant S6: the page loop is a Pager now — there is no `loadMore()`
 * to guard, and the first page is fetched by a PagingSource the ViewModel
 * builds per search. The invariants are the same, and these tests pin the
 * new mechanism that carries them: a new search replaces the Pager params
 * (`flatMapLatest` cancels the old source's loads), and the source's
 * meta/error callbacks refuse a superseded generation's write. The
 * `finally` that used to clear `isLoading` is gone with the flag itself:
 * the screen reads the PagingData's `loadState`, and Paging owns that
 * state, so a cancelled search leaves no skeleton behind.
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

    private val searchScreen: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) {
                return@lazy File(
                    dir,
                    "App/app/src/main/kotlin/com/raddstream/app/ui/screens/SearchScreen.kt",
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

    private val screenSource: String by lazy {
        assertTrue("SearchScreen.kt is missing at ${searchScreen.path}", searchScreen.isFile)
        searchScreen.readText()
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
        val body = functionBody("fun resetFilters()", "private var searchGeneration")
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
    fun `a fresh search supersedes the in-flight search and its Pager`() {
        val body = functionBody("private fun scheduleSearch(", "private fun scheduleSuggest()")
        assertTrue(
            "scheduleSearch must cancel the pending search job — a debounced " +
                "job that fires later would build a Pager for the old query",
            body.contains("searchJob?.cancel()"),
        )
        assertTrue(
            "scheduleSearch must stamp the new search as the latest generation",
            body.contains("++searchGeneration"),
        )
        assertTrue(
            "scheduleSearch must bail once it is no longer the latest generation",
            body.contains("generation != searchGeneration"),
        )
        assertTrue(
            "scheduleSearch must build the new search as Pager params — the " +
                "params value is what builds (and cancels) the PagingSource",
            body.contains("searchParams.value = SearchParams("),
        )
        // The old source's loads are cancelled by flatMapLatest when the
        // params change — that is the page-load supersede now, in place of
        // the old moreJob?.cancel().
        assertTrue(
            "the Pager flow must switch sources per search (flatMapLatest), " +
                "or a superseded source keeps loading into the new list",
            source.contains("flatMapLatest"),
        )
    }

    @Test
    fun `every search write is gated on still being the latest generation`() {
        // The writes are the meta callback (totals, fuzzy, suggestion,
        // recent, dedupe key), the first-page-error callback, and the
        // schedule itself after the debounce. A stale source whose network
        // call already returned must be stopped before every one of them,
        // not just the first one it reaches.
        val metaBody = functionBody("private fun onPageMeta(", "private fun onFirstPageError(")
        assertTrue(
            "onPageMeta must refuse a superseded generation's totals before writing",
            metaBody.contains("if (generation != searchGeneration) return"),
        )
        val errorBody = functionBody("private fun onFirstPageError(", "private fun scheduleSearch(")
        assertTrue(
            "onFirstPageError must refuse a superseded generation's failure before writing",
            errorBody.contains("if (generation != searchGeneration) return"),
        )
        val scheduleBody = functionBody("private fun scheduleSearch(", "private fun scheduleSuggest()")
        assertTrue(
            "scheduleSearch must check the generation after the debounce, " +
                "before building the Pager params",
            scheduleBody.contains("if (generation != searchGeneration) return@launch"),
        )
    }

    @Test
    fun `a cancelled search never strands the grid on its skeleton`() {
        // Batch C, S6 form: there is no isLoading flag in the ViewModel to
        // strand any more — the screen reads the PagingData's loadState,
        // and Paging owns that state, so a search cancelled mid-flight (a
        // newer filter tap or keystroke while the network call is still
        // out) cannot leave the skeleton on screen forever. The flag must
        // not come back, and the screen must read the loadState.
        assertFalse(
            "SearchUiState must not carry an isLoading flag — the skeleton " +
                "is driven by the PagingData's loadState, and a ViewModel " +
                "flag is exactly what used to strand it",
            source.contains("val isLoading"),
        )
        assertTrue(
            "the screen must read the refresh loadState for its skeleton",
            screenSource.contains("loadState.refresh is LoadState.Loading"),
        )
    }

    @Test
    fun `the manual page loop is gone and no superseded source reports meta`() {
        // S6: there is no loadMore/showAll/fetchNextPage any more — the
        // Pager owns paging. The supersede guard that used to live in
        // loadMore lives in the meta callback now, and the Pager flow
        // cancels the old source when the params change.
        assertFalse(
            "loadMore must be gone — the Pager owns paging now",
            source.contains("fun loadMore()"),
        )
        assertFalse(
            "showAll must be gone — infinite scroll replaces it",
            source.contains("fun showAll()"),
        )
        assertFalse(
            "fetchNextPage must be gone — the PagingSource owns page fetches",
            source.contains("fun fetchNextPage("),
        )
        val metaBody = functionBody("private fun onPageMeta(", "private fun onFirstPageError(")
        assertTrue(
            "onPageMeta must capture the generation it belongs to and check " +
                "it before writing, or a superseded source's first page " +
                "paints its totals over the newer search's list",
            metaBody.contains("if (generation != searchGeneration) return"),
        )
    }

    @Test
    fun `a stale suggestion never paints chips for the old query`() {
        val body = functionBody("private fun scheduleSuggest()", "private fun scheduleLocalSearch()")
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
        // both routes, so both fetch sites (the PagingSource's v2 + v1
        // lambdas) must translate through effectiveSearchType. S6: the old
        // four sites (scheduleSearch v2 + v1, loadMore v2 + v1) are two —
        // one per route, in the Pager factory.
        assertFalse(
            "a fetch site still sends the raw type — the kpop pill would answer zero rows",
            source.contains("type = filters.type") || source.contains("type = state.filters.type"),
        )
        assertEquals(
            "both fetch sites must route through effectiveSearchType",
            2,
            "effectiveSearchType\\(params\\.filters\\.type\\)"
                .toRegex().findAll(source).count(),
        )
    }
}
