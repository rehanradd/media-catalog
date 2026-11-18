package com.raddstream.app.ui.screens

import com.raddstream.app.network.models.FacetEntry
import com.raddstream.app.network.models.FacetsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Browse row's facet chips, built by [browseFacetChips] from
 * `GET /api/v1/facets`. Pure, so the placeholder rule is pinned here
 * without a composition: a facet the server labels "Unknown" is dropped,
 * not drawn — the live facets feed carries a language facet literally
 * labelled "Unknown" with 60,226 posts behind it, and a chip that
 * promises "Unknown · 60226" is a broken promise to browse for a
 * language nobody recorded.
 */
class BrowseFacetChipsTest {

    private fun facets(
        genres: List<FacetEntry> = emptyList(),
        industries: List<FacetEntry> = emptyList(),
        languages: List<FacetEntry> = emptyList(),
    ) = FacetsResponse(genres = genres, industries = industries, languages = languages)

    @Test
    fun unknownLanguageFacetIsDropped() {
        val chips = browseFacetChips(
            facets(
                languages = listOf(
                    FacetEntry(key = "unknown", label = "Unknown", count = 60226),
                    FacetEntry(key = "hindi", label = "Hindi", count = 1200),
                ),
            ),
        )
        assertEquals(listOf("Hindi · 1200"), chips.map { it.label })
        assertEquals(listOf("hindi"), chips.map { it.language })
    }

    @Test
    fun unknownGenreAndIndustryFacetsAreDroppedToo() {
        val chips = browseFacetChips(
            facets(
                genres = listOf(
                    FacetEntry(key = "unknown", label = "Unknown", count = 13),
                    FacetEntry(key = "action", label = "Action", count = 900),
                ),
                industries = listOf(
                    FacetEntry(key = "N/A", label = "N/A", count = 50),
                    FacetEntry(key = "bollywood", label = "Bollywood", count = 300),
                ),
            ),
        )
        assertEquals(listOf("Action · 900", "Bollywood · 300"), chips.map { it.label })
    }

    @Test
    fun zeroCountFacetsAreStillDropped() {
        val chips = browseFacetChips(
            facets(languages = listOf(FacetEntry(key = "hindi", label = "Hindi", count = 0))),
        )
        assertTrue(chips.isEmpty())
    }

    @Test
    fun nullFacetsMeansNoChips() {
        assertTrue(browseFacetChips(null).isEmpty())
    }
}
