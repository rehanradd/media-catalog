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
 * Video audit (Nov 2026): the meta line is year · rating only — the
 * type word never paints, so there is no bare-type line to suppress any
 * more; a tile with no year and no rating paints no meta line at all.
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
    ) = Post(
        // Distinct ids: two tiles in one composition must not share one.
        id = title.hashCode(),
        title = title,
        cleanTitle = title,
        year = year,
        contentType = contentType,
        imdbRating = imdbRating,
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
        firstTitleMaxLines: Int = 1,
        second: Post,
        secondTitleMaxLines: Int = 1,
    ): Pair<Float, Float> {
        compose.setContent {
            RaddStreamTheme(darkTheme = true) {
                Row {
                    PosterCard(
                        post = first,
                        modifier = Modifier.width(100.dp),
                        titleMaxLines = firstTitleMaxLines,
                    )
                    PosterCard(
                        post = second,
                        modifier = Modifier.width(100.dp),
                        titleMaxLines = secondTitleMaxLines,
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
    fun twoLineTitleSlotIsTallerThanOneLineSlot() {
        // The slot is a fixed height per line, so a two-line tile is
        // taller than the same tile with one line — by exactly one title
        // line — even for a title short enough to fit on one line.
        val (oneLine, twoLines) = twoCardHeights(
            first = post("Alpha Title", year = 2023, contentType = "movie"),
            second = post("Beta Title", year = 2023, contentType = "movie"),
            secondTitleMaxLines = 2,
        )
        assertTrue("two-line slot ($twoLines px) is not taller than one-line ($oneLine px)", twoLines > oneLine)
    }

    @Test
    fun tileWithNoYearAndNoRatingPaintsNoMetaLine() {
        // No rating, no year: the meta line is empty, so the tile comes
        // out shorter by that line than a tile carrying a year.
        val (noMeta, withYear) = twoCardHeights(
            first = post("Alpha Title", contentType = "series"),
            second = post("Beta Title", year = 2023, contentType = "series"),
        )
        assertTrue("no-meta tile ($noMeta px) is not shorter than year tile ($withYear px)", noMeta < withYear)
    }

    @Test
    fun richMetaIsOneLineLikeAYearOnlyMeta() {
        // A rating and a year mean the meta line carries "2023 · ★ 8.6" —
        // still one line, so the two cards are the same height to the pixel.
        val (yearOnly, rich) = twoCardHeights(
            first = post("Alpha Title", year = 2023, contentType = "series"),
            second = post("Beta Title", year = 2023, contentType = "series", imdbRating = "8.6"),
        )
        assertEquals("rich meta changed height", yearOnly, rich, 0.01f)
    }
}
