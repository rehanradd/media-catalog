package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.ui.screens.detailErrorMetadataLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies pure Detail screen/viewmodel helpers added for F18 (Similar titles
 * filtering) and Item 1 (humanized Detail error metadata line).
 */
class DetailSimilarTitlesTest {

    @Test
    fun `filterSimilarTitles excludes current title by id, workRef, or normalized title+year and deduplicates`() {
        val current = PostDetail(
            id = 42,
            title = "Jawan (2023)",
            cleanTitle = "Jawan",
            year = 2023,
            workRef = "work:jawan:2023",
        )
        val candidates = listOf(
            Post(id = 42, title = "Jawan", cleanTitle = "Jawan", year = 2023),
            Post(id = 100, title = "Jawan Dubbed", cleanTitle = "Jawan", year = 2023, workRef = "work:jawan:2023"),
            Post(id = 101, title = "Jawan!", cleanTitle = "Jawan!", year = 2023),
            Post(id = 200, title = "Pathaan", cleanTitle = "Pathaan", year = 2023, workRef = "work:pathaan"),
            Post(id = 201, title = "Pathaan Mirror", cleanTitle = "Pathaan", year = 2023, workRef = "work:pathaan"),
            Post(id = 300, title = "Dunki", cleanTitle = "Dunki", year = 2023),
        )

        val filtered = filterSimilarTitles(candidates, current)

        assertEquals(listOf(200, 300), filtered.map { it.id })
    }

    @Test
    fun `detailErrorMetadataLine formats known year, type, and drops Unknown or N_A languages`() {
        val post = Post(
            id = 10,
            title = "Sample",
            year = 2024,
            contentType = "movie",
            language = "Hindi + Unknown + English + N/A + Tamil",
        )

        assertEquals("2024 · Movie · Hindi, English", detailErrorMetadataLine(post))
    }

    @Test
    fun `detailErrorMetadataLine returns null when no metadata fields exist`() {
        val empty = Post(id = 11, title = "Bare", year = null, contentType = null, language = "Unknown")

        assertNull(detailErrorMetadataLine(empty))
    }
}
