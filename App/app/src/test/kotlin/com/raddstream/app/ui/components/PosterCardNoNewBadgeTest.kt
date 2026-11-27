package com.raddstream.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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
 * Video audit issue 30 (Nov 2026): the NEW pill is REMOVED. The old
 * year-only rule put it on everything, including a 1997 title, so the
 * badge meant nothing — and the list wire carries no added date to build
 * a true recency badge from. This test pins the removal: a current-year
 * title says nothing about "new release", and no NEW pill paints.
 *
 * Runs on the JVM under Robolectric. The tile is given the 100dp cell
 * the catalog grid actually hands it (see PosterCardProvenanceTest for why
 * an unconstrained tile lands below the fold in Robolectric's 320dp window).
 */
@RunWith(RobolectricTestRunner::class)
class PosterCardNoNewBadgeTest {

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
                    PosterCard(post = post, modifier = Modifier.width(100.dp))
                }
            }
        }
    }

    // The year is read off the clock, not hardcoded: the "current year"
    // case must stay current when this test runs in 2027 or later, and the
    // "older" case is three years back so it is old in any year.
    private val thisYear = currentCalendarYear()

    @Test
    fun `a current-year title says nothing about new`() {
        showTile(post(year = thisYear))
        compose.onNode(
            hasContentDescription("Inception, $thisYear · Movie", substring = false)
        ).assertIsDisplayed()
        compose.onNode(hasText("NEW")).assertDoesNotExist()
    }

    @Test
    fun `an older title says the same shape`() {
        val oldYear = thisYear - 3
        showTile(post(year = oldYear))
        compose.onNode(
            hasContentDescription("Inception, $oldYear · Movie", substring = false)
        ).assertIsDisplayed()
        compose.onNode(hasText("NEW")).assertDoesNotExist()
    }
}
