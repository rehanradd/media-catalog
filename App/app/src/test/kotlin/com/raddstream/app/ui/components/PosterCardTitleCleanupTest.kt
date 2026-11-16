package com.raddstream.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
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
 * Runs on the JVM under Robolectric. The tile is given the 120dp grid cell
 * the catalog grid actually hands it (see PosterCardNewBadgeTest for why
 * an unconstrained tile lands below the fold in Robolectric's 320dp window).
 * The years here are all safely old, so no case earns the NEW pill and its
 * ", new release" tail.
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
                    PosterCard(post = post, modifier = Modifier.width(120.dp))
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
