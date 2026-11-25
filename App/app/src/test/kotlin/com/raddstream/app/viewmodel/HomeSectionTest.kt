package com.raddstream.app.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H10 (2027 Home fix, round 2): which rails get a "See all" — Browse
 * reproduces the rail's order, or the rail gets none.
 */
class HomeSectionTest {

    private fun section(
        sort: String? = null,
        relatedWorkId: Int? = null,
        forYou: Boolean = false,
    ) = HomeSection(
        key = "k",
        title = "T",
        type = null,
        sort = sort,
        relatedWorkId = relatedWorkId,
        forYou = forYou,
    )

    @Test
    fun unsortedRailHasABrowseEquivalent() {
        assertTrue(section().browseEquivalent)
    }

    @Test
    fun trendingRailHasABrowseEquivalent() {
        assertTrue(section(sort = "trending").browseEquivalent)
    }

    @Test
    fun otherSortHasNoBrowseEquivalent() {
        assertFalse(section(sort = "rating").browseEquivalent)
    }

    @Test
    fun relatedRailHasNoBrowseEquivalent() {
        assertFalse(section(relatedWorkId = 42).browseEquivalent)
        assertFalse(section(sort = "trending", relatedWorkId = 42).browseEquivalent)
    }

    @Test
    fun forYouRailHasNoBrowseEquivalent() {
        assertFalse(section(forYou = true).browseEquivalent)
        assertFalse(section(sort = "trending", forYou = true).browseEquivalent)
    }
}
