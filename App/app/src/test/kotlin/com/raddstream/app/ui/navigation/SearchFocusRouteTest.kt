package com.raddstream.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-2 (audit C-2): the Home header glyph deep-links into a focused
 * search field, while the tab tap keeps the plain, unfocused route.
 * The focus flag rides the route as `focus=1` — a plain query value,
 * never a second route — so every existing search link keeps working.
 */
class SearchFocusRouteTest {

    @Test
    fun `the plain tab route is unchanged and carries no focus`() {
        assertEquals("search", Screen.Search.PLAIN_ROUTE)
        assertEquals("search", Screen.Search.createRoute())
    }

    @Test
    fun `the home glyph route carries focus equals 1`() {
        assertEquals("search?focus=1", Screen.Search.createRoute(focusOnEntry = true))
    }

    @Test
    fun `focus composes with a query and with a facet`() {
        assertEquals(
            "search?query=Inception&focus=1",
            Screen.Search.createRoute(query = "Inception", focusOnEntry = true),
        )
        assertEquals(
            "search?genre=action&focus=1",
            Screen.Search.createRoute(genre = "action", focusOnEntry = true),
        )
    }

    @Test
    fun `the route template names the focus argument NavGraph reads`() {
        assertEquals(
            "search?query={query}&genre={genre}&industry={industry}&language={language}&focus={focus}",
            Screen.Search.route,
        )
        assertEquals("focus", Screen.Search.ARG_FOCUS)
        assertEquals("1", Screen.Search.FOCUS_ON_ENTRY)
    }
}
