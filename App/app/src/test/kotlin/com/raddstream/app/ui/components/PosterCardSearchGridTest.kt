package com.raddstream.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Screen audit #7 (Nov 2026): the search results grid's tiles get two
 * title lines — a long title reads whole under the "Series" header.
 * The tile's painted texts are cleared from semantics (one spoken
 * sentence per tile), so these tests pin geometry, not words: the title
 * slot is a fixed height per line, and a missing meta line removes a
 * whole line from the card. Two cards with the same shape but different
 * titles are rendered side by side so the heights compare under one
 * density and one theme; the titles differ only so each card's spoken
 * sentence finds its own node.
 *
 * Video audit (Nov 2026), refined direction 21:51 PKT 27 Nov: the meta
 * line is the language only — the year and rating are off the card, so
 * a tile with no language paints no meta line at all. (The two-line
 * title-slot test is gone with the old search design: the refined
 * direction takes PosterCard out of search entirely — YouTube-style
 * rich rows, the search batch's job — so a search-grid slot pin has no
 * subject any more. The grid still passes titleMaxLines = 2 until that
 * batch lands, and long titles still wrap to two lines.)
 *
 * Runs on the JVM under Robolectric. The tile is given the 100dp cell
 * the catalog grid actually hands it (see PosterCardProvenanceTest for why
 * an unconstrained tile lands below the fold in Robolectric's 320dp window).
 */
@RunWith(RobolectricTestRunner::class)
class PosterCardSearchGridTest {

    @get:Rule
    val compose = createComposeRule()

    private fun post(
        title: String,
        year: Int? = null,
        contentType: String? = null,
        imdbRating: String? = null,
        language: String? = null,
    ) = Post(
        // Distinct ids: two tiles in one composition must not share one.
        id = title.hashCode(),
        title = title,
        cleanTitle = title,
        year = year,
        contentType = contentType,
        imdbRating = imdbRating,
        language = language,
        // No poster URL: the card renders the branded fallback, so no image
        // load runs in the test.
        posterUrl = null,
    )

    /**
     * Renders two tiles side by side in 100dp cells (the width the
     * catalog grid hands a tile) and returns their heights in pixels, in
     * composition order. The two titles must differ so each tile's spoken
     * sentence matches exactly one node.
     */
    private fun twoCardHeights(
        first: Post,
        second: Post,
    ): Pair<Float, Float> {
        compose.setContent {
            RaddStreamTheme(darkTheme = true) {
                Row {
                    PosterCard(
                        post = first,
                        modifier = Modifier.width(100.dp),
                    )
                    PosterCard(
                        post = second,
                        modifier = Modifier.width(100.dp),
                    )
                }
            }
        }
        fun heightFor(title: String): Float =
            compose.onNode(hasContentDescription(title, substring = true))
                .fetchSemanticsNode().boundsInRoot.height
        return heightFor(first.title) to heightFor(second.title)
    }

    @Test
    fun tileWithNoLanguagePaintsNoMetaLine() {
        // No language: the meta line is empty, so the tile comes out
        // shorter by that line than a tile carrying one.
        val (noMeta, withLanguage) = twoCardHeights(
            first = post("Alpha Title", year = 2023, contentType = "series", imdbRating = "8.6"),
            second = post("Beta Title", year = 2023, contentType = "series", imdbRating = "8.6", language = "Hindi"),
        )
        assertTrue("no-meta tile ($noMeta px) is not shorter than language tile ($withLanguage px)", noMeta < withLanguage)
    }

    @Test
    fun languageMetaIsOneLineLikeAnyOtherLanguage() {
        // A language meta line is one line whatever the language —
        // "Hindi" and "English" are the same height to the pixel, and a
        // year or a rating beside it changes nothing (neither paints).
        val (hindi, english) = twoCardHeights(
            first = post("Alpha Title", year = 2023, contentType = "series", imdbRating = "8.6", language = "Hindi"),
            second = post("Beta Title", year = 2023, contentType = "series", language = "English"),
        )
        assertEquals("language meta changed height", hindi, english, 0.01f)
    }
}
