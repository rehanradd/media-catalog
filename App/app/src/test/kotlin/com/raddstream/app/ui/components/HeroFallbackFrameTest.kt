package com.raddstream.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Hero (Nov 2026): the branded frame the hero paints while its artwork is
 * still in flight — the hero_fallback vector, dark surface with the jade
 * mark, so the hero is never a black void. The transient loading state
 * itself is not observable under Robolectric (the CatalogImageLoadingTest
 * pattern: every node query waits for idle first), so this pins the frame
 * composable directly: it renders, it carries its tag, it does not crash
 * on the vector. A Paparazzi shot of the frame through the real slide was
 * tried and dropped: any real art URL reaches CatalogImage, whose Hilt
 * entry-point lookup throws under Layoutlib ("Could not find an Application
 * in the given context") — the same reason every Paparazzi fake carries a
 * null art URL. The wiring — the slide passes the frame as CatalogImage's
 * loading slot — is a one-line param pass, reviewed in the diff.
 *
 * Requires an x86_64 runner (robolectric/robolectric#9166); see the
 * exclusion in app/build.gradle.kts.
 */
@RunWith(RobolectricTestRunner::class)
class HeroFallbackFrameTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun frameRenders() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RaddStreamTheme {
                HeroFallbackFrame()
            }
        }

        compose.onNodeWithTag("heroFallbackFrame").assertIsDisplayed()
    }
}
