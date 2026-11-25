package com.raddstream.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SE-2 (round 2): a kept filter combination must survive a DataStore
 * round-trip exactly — the filters, the years, the plan flag and the sort —
 * and a stored string this build cannot read must decode to null, never to
 * a guessed filter. Pure, so these run on plain JUnit with no Robolectric.
 */
class SavedSearchFilterTest {

    private fun full() = SavedSearchFilter(
        filters = SearchFilters(
            type = "series",
            industry = IndustryFilter.BOLLYWOOD,
            genre = GenreFilter.ACTION,
            quality = QualityFilter.FULL_HD,
            language = LanguageFilter.HINDI,
            yearFrom = 2025,
            yearTo = 2025,
            includedOnly = true,
        ),
        sort = SearchSort.TITLE,
    )

    @Test
    fun `a full combination round-trips exactly`() {
        val saved = full()
        assertEquals(saved, decodeSavedSearchFilter(saved.encoded))
    }

    @Test
    fun `an empty combination round-trips to all-defaults`() {
        val saved = SavedSearchFilter(SearchFilters())
        assertEquals(saved, decodeSavedSearchFilter(saved.encoded))
    }

    @Test
    fun `the label names the facets in sheet order`() {
        assertEquals("Series · Bollywood · Action · 1080p · Hindi · 2025 · Included in my plan · Title A–Z", full().label)
    }

    @Test
    fun `the label of an empty combination is All titles`() {
        assertEquals("All titles", SavedSearchFilter(SearchFilters()).label)
    }

    @Test
    fun `the default sort is not named in the label`() {
        val saved = SavedSearchFilter(
            filters = SearchFilters(genre = GenreFilter.COMEDY),
            sort = SearchSort.DEFAULT,
        )
        assertEquals("Comedy", saved.label)
    }

    @Test
    fun `a single year reads as one year, not a range`() {
        val saved = SavedSearchFilter(
            filters = SearchFilters(yearFrom = 2025, yearTo = 2025),
        )
        assertEquals("2025", saved.label)
    }

    @Test
    fun `an unknown enum name decodes to null, never a guess`() {
        val saved = full()
        val corrupt = saved.encoded.replace("ACTION", "NO_SUCH_GENRE")
        assertNull(decodeSavedSearchFilter(corrupt))
    }

    @Test
    fun `a wrong field count decodes to null`() {
        assertNull(decodeSavedSearchFilter("series\u001EALL"))
        assertNull(decodeSavedSearchFilter(""))
    }

    @Test
    fun `a non-numeric year decodes to null, not a crash`() {
        val saved = full()
        val corrupt = saved.encoded.replace("2025", "yyyy")
        val decoded = decodeSavedSearchFilter(corrupt)
        // The years fall out; the rest of the combination survives.
        assertEquals(full().copy(filters = full().filters.copy(yearFrom = null, yearTo = null)), decoded)
    }
}
