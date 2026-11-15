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
import com.raddstream.app.network.models.currentCalendarYear
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M11 (home review, Nov 2026): the NEW pill on a recent release. The pill
 * itself is paint-only (its semantics are cleared, like the rank badge), so
 * the test pins the tile's one spoken sentence: a 2026 title says
 * "new release", a 2024 title does not. The year rule itself is pure and
 * pinned in IsNewReleaseYearTest; this pins the wiring.
 *
 * Runs on the JVM under Robolectric. The tile is given the 120dp grid cell
 * the catalog grid actually hands it (see PosterCardProvenanceTest for why
 * an unconstrained tile lands below the fold in Robolectric's 320dp window).
 */
@RunWith(RobolectricTestRunner::class)
class PosterCardNewBadgeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun post(year: Int) = Post(
        id = 1,
        title = "Inception",
        cleanTitle = "Inception",
        year = year,
        contentType = "movie",
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

    // The year is read off the clock, not hardcoded: the "current year"
    // case must stay current when this test runs in 2027 or later, and the
    // "older" case is three years back so it is old in any year.
    private val thisYear = currentCalendarYear()

    @Test
    fun `a current-year title says new release`() {
        showTile(post(year = thisYear))
        compose.onNode(
            hasContentDescription("Inception, $thisYear · Movie, new release", substring = false)
        ).assertIsDisplayed()
    }

    @Test
    fun `an older title says nothing about new`() {
        val oldYear = thisYear - 3
        showTile(post(year = oldYear))
        compose.onNode(
            hasContentDescription("Inception, $oldYear · Movie", substring = false)
        ).assertIsDisplayed()
    }
}
