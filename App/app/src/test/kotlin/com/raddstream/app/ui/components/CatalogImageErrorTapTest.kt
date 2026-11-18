package com.raddstream.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * A3 Proof 3 (Nov 2026): Discover Pakistan's logo 429s, so its tile sat in
 * CatalogImage's error slot — and the error slot's full-size tap-to-retry
 * clickable swallowed the card's own tap. Robo tapped the card centre twice
 * (both SUCCESS) and nothing navigated; each tap only re-fetched the failing
 * logo. In Compose the innermost clickable consumes the tap, so a tile whose
 * image fails was un-openable by a centre tap — the same error slot serves
 * every poster, hero and detail image, so any failed image made its card
 * dead. The fix removes the full-size clickable from the error slot, so the
 * tap falls through to the parent card.
 *
 * The tap below is a real touch event at the tile centre (performTouchInput),
 * not performClick on the parent node: performClick invokes the parent's own
 * click action directly and would pass even with the bug, while a touch
 * event goes through hit-testing and lands on the innermost clickable —
 * exactly the Robo tap.
 *
 * The load is failed deterministically, in-process, by a fake Coil loader
 * whose every fetch throws at once (no network involved). Rounds 1–3 of
 * this test waited on a real fetch instead: round 1 tapped after a bare
 * waitForIdle() and passed on unfixed code in 0.087 s (the tap landed on
 * the loading shimmer, which has no clickable); rounds 2–3 waited with
 * waitUntil for the error slot's click action and died in the wait at
 * exactly 30.092 s and 90.089 s — under Robolectric the real fetch never
 * fails at all, so the error slot never appears however long the test
 * waits. The fake loader is what makes the error slot observable: the
 * bounded auto-retries re-issue against it and settle in milliseconds,
 * and the 30 s bound below is only a backstop.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogImageErrorTapTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun centreTapOnFailedImageReachesParentOnClick() {
        compose.mainClock.autoAdvance = false
        var clicked = false
        // Built once, outside the composition: a fresh loader per
        // recomposition would restart the fetch each time.
        val loader = ImageLoader.Builder(compose.activity.applicationContext)
            .components { add(FailingFetcherFactory()) }
            .build()
        compose.setContent {
            RaddStreamTheme {
                CompositionLocalProvider(LocalImageLoader provides loader) {
                    Box(
                        Modifier
                            .size(200.dp)
                            .testTag("tile")
                            .clickable { clicked = true },
                    ) {
                        CatalogImage(
                            url = "https://image.tmdb.org/t/p/w500/abc123.jpg",
                            contentDescription = "Poster",
                            fallbackLabel = "DP",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        // Pin the error slot before tapping: wait until the node carrying
        // the "Retry loading image" click action exists. That label lives
        // only on the error slot's full-size retry clickable — the loading
        // slot (fallback card + shimmer) has no clickable at all, and the
        // parent tile's own click carries no label. With the fake loader
        // the fetch fails at once and the bounded auto-retries settle in
        // milliseconds, so this returns almost immediately.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodes(hasClickActionLabel("Retry loading image"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNode(hasClickActionLabel("Retry loading image")).assertExists()
        compose.onNodeWithTag("tile").performTouchInput { click() }
        assertTrue(
            "centre tap on a failed-image tile did not reach the parent onClick",
            clicked,
        )
    }

    /** A fetcher factory whose every fetch fails at once, in-process. */
    private class FailingFetcherFactory : Fetcher.Factory {
        override fun create(
            data: Any,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? = FailingFetcher()
    }

    private class FailingFetcher : Fetcher {
        override suspend fun fetch(): FetchResult =
            throw IOException("test fetch always fails")
    }
}

/**
 * Matches a node whose click action carries the given onClickLabel. The
 * label is not a content description, so onNodeWithContentDescription
 * cannot find it — the match reads the OnClick action straight off the
 * node's semantics (the AccountLabelFieldTest pattern).
 */
private fun hasClickActionLabel(label: String) =
    SemanticsMatcher("click action labelled \"$label\"") { node ->
        node.config.contains(SemanticsActions.OnClick) &&
            node.config[SemanticsActions.OnClick].label == label
    }
