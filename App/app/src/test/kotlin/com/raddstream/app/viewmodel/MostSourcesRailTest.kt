package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.PanelSection
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.VipRemoteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2.2 Shape C: the "Most copies" rail is `sort=trending` on the live
 * `/api/v1/posts` route — no new endpoint, no nginx allow, no Browse call.
 * These pin the rail itself, not the network: five rails in order, only
 * "Most copies" asks for a sort. H10 (2027 Home fix, round 2): Browse
 * takes a sort now, so the trending rail KEEPS its "See all" — the sort
 * rides through to Browse instead of silently re-sorting. The label is
 * pinned too: "Trending" would promise popularity data that does not exist
 * — the ledger has no play-count column, so the order is prints + mirrors.
 */
class MostSourcesRailTest {

    @Test
    fun `five rails in order with most sources last`() {
        val sections = initialHomeSections()

        assertEquals(
            listOf("recent", "movie", "series", "season", "most_sources"),
            sections.map { it.key },
        )
        assertEquals(
            listOf("Recently added", "Movies", "Series", "Complete series", "Most copies"),
            sections.map { it.title },
        )
        assertTrue(sections.all { it.loading })
    }

    @Test
    fun `only most sources asks the server for a sort`() {
        val sections = initialHomeSections()

        assertEquals("trending", sections.last().sort)
        assertNull(sections.last().type)
        assertTrue(sections.dropLast(1).all { it.sort == null })
    }

    @Test
    fun `the rail is named for its input not for popularity`() {
        val rail = initialHomeSections().last()

        assertEquals("Most copies", rail.title)
        assertFalse(rail.title.contains("Trending", ignoreCase = true))
    }

    @Test
    fun `the trending rail keeps see all now that browse takes a sort`() {
        // H10: the sort used to hide "See all" (Browse took a type but no
        // sort, so it could not reproduce that order). Browse takes a sort
        // now, so the trending rail keeps its — the sort rides through.
        val sections = initialHomeSections()

        assertTrue(sections.dropLast(1).all { it.browseEquivalent })
        assertTrue(sections.last().browseEquivalent)
    }

    @Test
    fun `a plain rail has no sort and keeps see all`() {
        val section = HomeSection(key = "movie", title = "Movies", type = "movie")

        assertNull(section.sort)
        assertTrue(section.browseEquivalent)
    }

    @Test
    fun `merge keeps most sources when the panel owns no such key`() {
        val (sections, _) = mergePanelFeed(
            initialHomeSections(),
            VipRemoteConfig(
                sections = listOf(
                    PanelSection(key = "picks", title = "Picks", items = listOf(post(9))),
                ),
            ),
            dismissedBroadcastIds = emptySet(),
        )

        assertEquals(
            listOf("panel:picks", "recent", "movie", "series", "season", "most_sources"),
            sections.map { it.key },
        )
        assertEquals("trending", sections.last().sort)
    }

    @Test
    fun `a panel section keyed most sources replaces the built-in rail`() {
        val (sections, _) = mergePanelFeed(
            initialHomeSections(),
            VipRemoteConfig(
                sections = listOf(
                    PanelSection(key = "most_sources", title = "Most Sources", items = listOf(post(7))),
                ),
            ),
            dismissedBroadcastIds = emptySet(),
        )

        assertEquals(
            listOf("panel:most_sources", "recent", "movie", "series", "season"),
            sections.map { it.key },
        )
        assertTrue(sections.first().panelManaged)
    }

    private fun post(id: Int) = Post(id = id, title = "Title $id", cleanTitle = "Title $id")

    @Test
    fun `regional rails carry server industry filters and keep see all`() {
        val regional = regionalHomeSections()
        assertEquals(listOf("bollywood", "south_indian"), regional.map { it.key })
        assertEquals(listOf("bollywood", "south_indian"), regional.map { it.industry })
        assertTrue(regional.all { it.browseEquivalent })
    }

    @Test
    fun `south indian expands to the four southern industries the catalog carries`() {
        // "south_indian" is not a catalog value — v2 exact-matches the
        // industry column and answers it with 0 rows (measured live
        // 2026-11-15). The rail must send the real values instead.
        assertEquals(
            listOf("Tollywood", "Kollywood", "Mollywood", "Sandalwood"),
            industryWireValues("south_indian"),
        )
        assertEquals(listOf("bollywood"), industryWireValues("bollywood"))
        assertNull(industryWireValues(null))
    }
}
