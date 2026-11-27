package com.raddstream.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Rail-title cleanup (Nov 2026): the tile paints the same clean title
 * Detail and the hero paint — the server's display title first, then the
 * clean title, then the raw title, each stripped of the year/language/type/
 * season suffixes the meta line already states. The painted text row is
 * cleared from semantics (one spoken sentence per tile), so the test pins
 * that sentence: it starts with the painted title, so a suffixed title
 * can never hide behind a matching sentence.
 *
 * Runs on the JVM under Robolectric. The tile is given the 100dp grid cell
 * the catalog grid actually hands it (see PosterCardNoNewBadgeTest for why
 * an unconstrained tile lands below the fold in Robolectric's 320dp window).
 * The years here are all safely old, and the NEW pill is gone outright
 * (video audit issue 30), so no case earns a ", new release" tail.
 */
@RunWith(RobolectricTestRunner::class)
class PosterCardTitleCleanupTest {

    @get:Rule
    val compose = createComposeRule()

    private fun post(
        title: String,
        cleanTitle: String? = null,
        displayTitleWire: String? = null,
        year: Int? = null,
        contentType: String? = null,
        language: String? = null,
    ) = Post(
        id = 1,
        title = title,
        cleanTitle = cleanTitle,
        displayTitleWire = displayTitleWire,
        year = year,
        contentType = contentType,
        language = language,
    )

    private fun showTile(post: Post) {
        compose.setContent {
            RaddStreamTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    PosterCard(post = post, modifier = Modifier.width(100.dp))
                }
            }
        }
    }

    private fun assertTileSays(sentence: String) {
        compose.onNode(hasContentDescription(sentence, substring = false)).assertIsDisplayed()
    }

    @Test
    fun `a year language and type suffix stack is stripped`() {
        showTile(
            post(
                title = "No Hard Feelings (2023) English Movie",
                cleanTitle = "No Hard Feelings (2023) English Movie",
                year = 2023,
                contentType = "movie",
                language = "English",
            )
        )
        assertTileSays("No Hard Feelings, 2023 · Movie")
    }

    @Test
    fun `the server display title wins over a suffixed clean title`() {
        showTile(
            post(
                title = "No Hard Feelings (2023) English Movie",
                cleanTitle = "No Hard Feelings (2023) English Movie",
                displayTitleWire = "No Hard Feelings",
                year = 2023,
                contentType = "movie",
                language = "English",
            )
        )
        assertTileSays("No Hard Feelings, 2023 · Movie")
    }

    @Test
    fun `a series type word is stripped`() {
        showTile(
            post(
                title = "Breaking Bad TV Series",
                cleanTitle = "Breaking Bad TV Series",
                contentType = "series",
            )
        )
        assertTileSays("Breaking Bad, Series")
    }

    @Test
    fun `a season range suffix is stripped`() {
        showTile(
            post(
                title = "Show (2023) S1-S2",
                cleanTitle = "Show (2023) S1-S2",
                year = 2023,
                contentType = "series",
            )
        )
        assertTileSays("Show, 2023 · Series")
    }

    // UI/UX audit Library #1 / Home #9 (Nov 2026): the fallback poster
    // printed the title twice — in the fallback card and in the caption
    // below it. Video audit issue 32 (Nov 2026): the fallback paints no
    // initials at all any more — the branded placeholder (mark only),
    // never a two-letter box that reads as content that is not there.
    // The full title appears exactly once on the tile (the caption
    // carries it). channelMonogram("Jawan") is "JA" — the first two
    // letters of a one-word title — so the tile must NOT have a "JA"
    // node any more. (An earlier draft of this test asserted "JW" and
    // failed on both the tile and the card: "JW" is not the monogram —
    // it was the test's error, not the code's.)
    @Test
    fun `the fallback paints no initials, the caption carries the title`() {
        showTile(post(title = "Jawan", cleanTitle = "Jawan", year = 2023, contentType = "movie"))
        compose.onNodeWithText("JA", useUnmergedTree = true).assertDoesNotExist()
        val titleNodes = compose.onAllNodesWithText("Jawan", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals("the title is painted twice on the tile", 1, titleNodes.size)
    }

    // Same audit item, pinned where the fallback is addressable on its
    // own: the card paints the monogram and never the full title.
    @Test
    fun `the fallback card on its own paints the monogram, not the title`() {
        compose.setContent {
            RaddStreamTheme(darkTheme = true) {
                ImageFallbackCard(label = channelMonogram("Jawan"))
            }
        }
        compose.onNodeWithText("JA").assertExists()
        compose.onNodeWithText("Jawan").assertDoesNotExist()
    }

    @Test
    fun `The English Patient keeps its English`() {
        showTile(
            post(
                title = "The English Patient",
                cleanTitle = "The English Patient",
                year = 1996,
                contentType = "movie",
                language = "English",
            )
        )
        assertTileSays("The English Patient, 1996 · Movie")
    }

    @Test
    fun `1917 keeps its year`() {
        showTile(
            post(
                title = "1917",
                cleanTitle = "1917",
                year = 2019,
                contentType = "movie",
                language = "English",
            )
        )
        assertTileSays("1917, 2019 · Movie")
    }
}
