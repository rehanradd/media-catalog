package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.SuggestItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feel-instant S3b: the merged chip row — local rows first, the server
 * batch underneath, deduped by title, capped. Pure, so it is pinned here
 * without a ViewModel.
 */
class SearchLocalSuggestTest {

    private fun server(title: String, id: Int = title.hashCode()) =
        SuggestItem(id = id, title = title)

    @Test
    fun `local rows lead and the server batch follows`() {
        val local = listOf(LocalSearchResult("Avengers", 2012, "movie", null).toSuggestItem())
        val merged = mergeSuggestions(local, listOf(server("Avatar"), server("Avengers: Endgame")))
        assertEquals(
            listOf("Avengers", "Avatar", "Avengers: Endgame"),
            merged.map { it.title },
        )
    }

    @Test
    fun `the same title from both sides is one chip`() {
        val local = listOf(LocalSearchResult("Dune", 2021, "movie", null).toSuggestItem())
        val merged = mergeSuggestions(local, listOf(server("dune"), server("Dunkirk")))
        assertEquals(listOf("Dune", "Dunkirk"), merged.map { it.title })
        assertTrue("the local row wins the duplicate", merged.first().id < 0)
    }

    @Test
    fun `the merged row never runs longer than eight`() {
        val local = (1..5).map {
            LocalSearchResult("Local $it", 2024, "movie", null).toSuggestItem()
        }
        val server = (1..5).map { server("Server $it") }
        assertEquals(8, mergeSuggestions(local, server).size)
    }

    @Test
    fun `no locals means the server batch passes through unchanged`() {
        val server = listOf(server("Inception"), server("Interstellar"))
        assertEquals(server, mergeSuggestions(emptyList(), server))
    }

    @Test
    fun `a local row maps to a negative-id suggestion carrying its year`() {
        val item = LocalSearchResult("Jawan", 2023, "movie", null).toSuggestItem()
        assertTrue(item.id < 0)
        assertEquals("Jawan", item.title)
        assertEquals(2023, item.year)
    }
}
