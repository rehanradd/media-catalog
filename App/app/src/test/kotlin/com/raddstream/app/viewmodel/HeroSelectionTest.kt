package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hero (Nov 2026): which rail the banner rides. The panel's hero list is
 * curation, and curation goes stale — the panel once published McLintock!
 * (1963) as the six-slide hero while the newest feed walked 2027s. A stale
 * panel hero loses the slot to "Recently added"; a fresh one is used as-is.
 * These pin the decision, not the network: the year is passed in, so no
 * clock is read.
 */
class HeroSelectionTest {

    private val currentYear = 2026

    private fun post(id: Int, year: Int?) = Post(id = id, title = "Title $id", cleanTitle = "Title $id", year = year)

    private fun panelHero(vararg years: Int?) = HomeSection(
        key = "panel:hero",
        title = "Hero",
        type = null,
        items = years.mapIndexed { index, year -> post(index + 1, year) },
        panelManaged = true,
    )

    private fun recent(vararg years: Int?, loading: Boolean = false, error: String? = null) = HomeSection(
        key = "recent",
        title = "Recently added",
        type = null,
        items = years.mapIndexed { index, year -> post(100 + index, year) },
        loading = loading,
        error = error,
    )

    @Test
    fun `stale 1963 panel hero falls back to Recently added`() {
        val hero = selectHeroSection(
            listOf(panelHero(1963, 1954, 1946), recent(2027, 2026)),
            currentYear,
        )

        assertEquals("recent", hero?.key)
    }

    @Test
    fun `fresh 2026 panel hero is used as-is`() {
        val hero = selectHeroSection(
            listOf(panelHero(2026, 1963), recent(2027, 2026)),
            currentYear,
        )

        assertEquals("panel:hero", hero?.key)
    }

    @Test
    fun `a 2027 title makes the panel hero fresh`() {
        val hero = selectHeroSection(
            listOf(panelHero(2027), recent(2027)),
            currentYear,
        )

        assertEquals("panel:hero", hero?.key)
    }

    @Test
    fun `a 2025 title is still fresh in 2026`() {
        val hero = selectHeroSection(
            listOf(panelHero(2025), recent(2027)),
            currentYear,
        )

        assertEquals("panel:hero", hero?.key)
    }

    @Test
    fun `a 2024 list is stale in 2026`() {
        val hero = selectHeroSection(
            listOf(panelHero(2024), recent(2027)),
            currentYear,
        )

        assertEquals("recent", hero?.key)
    }

    @Test
    fun `all-null years are stale`() {
        val hero = selectHeroSection(
            listOf(panelHero(null, null), recent(2027)),
            currentYear,
        )

        assertEquals("recent", hero?.key)
    }

    @Test
    fun `an impossible year cannot make the list fresh`() {
        val hero = selectHeroSection(
            listOf(panelHero(20215), recent(2027)),
            currentYear,
        )

        assertEquals("recent", hero?.key)
    }

    @Test
    fun `a stale hero holds the skeleton while Recently added is still loading`() {
        val hero = selectHeroSection(
            listOf(panelHero(1963), recent(loading = true)),
            currentYear,
        )

        assertNull(hero)
    }

    @Test
    fun `a stale hero shows when Recently added failed - stale beats nothing`() {
        val hero = selectHeroSection(
            listOf(panelHero(1963), recent(error = "Couldn't reach the catalog.")),
            currentYear,
        )

        assertEquals("panel:hero", hero?.key)
    }

    @Test
    fun `a built-in first rail is never stale-checked`() {
        val hero = selectHeroSection(
            listOf(
                HomeSection(key = "most_sources", title = "Most copies", type = null, items = listOf(post(1, 1963))),
                recent(2027),
            ),
            currentYear,
        )

        assertEquals("most_sources", hero?.key)
    }

    @Test
    fun `no visible section means no hero`() {
        assertNull(selectHeroSection(listOf(recent(loading = true)), currentYear))
    }

    @Test
    fun `a panel-owned Recently added rail is the fallback`() {
        val hero = selectHeroSection(
            listOf(
                panelHero(1963),
                HomeSection(key = "panel:recent", title = "Recently added", type = null, items = listOf(post(9, 2027)), panelManaged = true),
            ),
            currentYear,
        )

        assertEquals("panel:recent", hero?.key)
    }
}
