package com.raddstream.app.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robo run 6: a gated Detail poster showed as a flat dark surfaceVariant
 * rectangle (image contrast 1.33) — the null model left the box blank behind
 * the shimmer, because a request that was never made still rendered the
 * loading state. A request that was never made now renders the branded
 * fallback (mark + title) instead, so the poster area always has visible
 * content. The shimmer is a loading state only, and nothing is loading.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogImageFallbackTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nullUrlShowsFallbackNotShimmer() {
        compose.setContent {
            RaddStreamTheme {
                CatalogImage(url = null, contentDescription = "Poster", fallbackLabel = "TS")
            }
        }
        // The fallback's initials are visible — the box is not a blank
        // shimmer. (A shimmer renders no text, so this fails on the old
        // code, which showed the shimmer for a null model.)
        compose.onNodeWithText("TS").assertExists()
    }

    @Test
    fun blankUrlShowsFallbackNotShimmer() {
        compose.setContent {
            RaddStreamTheme {
                CatalogImage(url = "   ", contentDescription = "Poster", fallbackLabel = "TS")
            }
        }
        compose.onNodeWithText("TS").assertExists()
    }

    // F6 (Batch A): the fallback carries the full title, not initials — the
    // tile must say which title failed to load.
    @Test
    fun fullTitleShowsInFallback() {
        compose.setContent {
            RaddStreamTheme {
                CatalogImage(url = null, contentDescription = "Poster", fallbackLabel = "The Three Stooges")
            }
        }
        compose.onNodeWithText("The Three Stooges").assertExists()
    }

    // A4-F1: on a large plate the monogram replaces the full label — the
    // name under a 48dp mark overflowed the plate and never painted, so
    // the Row shows mark + two letters and the full name stays out.
    @Test
    fun monogramReplacesFullLabelOnLargePlate() {
        compose.setContent {
            RaddStreamTheme {
                CatalogImage(
                    url = null,
                    contentDescription = "Logo",
                    fallbackLabel = "Discover Pakistan",
                    fallbackLarge = true,
                    monogram = "DP",
                )
            }
        }
        compose.onNodeWithText("DP").assertExists()
        compose.onNodeWithText("Discover Pakistan").assertDoesNotExist()
    }

    // A4-F1: without a monogram the large plate keeps the old mark +
    // full-label column — only the logo plate passes a monogram, so a
    // poster tile that happens to be large still names its title.
    @Test
    fun largePlateWithoutMonogramKeepsFullLabel() {
        compose.setContent {
            RaddStreamTheme {
                CatalogImage(
                    url = null,
                    contentDescription = "Poster",
                    fallbackLabel = "The Three Stooges",
                    fallbackLarge = true,
                )
            }
        }
        compose.onNodeWithText("The Three Stooges").assertExists()
    }
}
