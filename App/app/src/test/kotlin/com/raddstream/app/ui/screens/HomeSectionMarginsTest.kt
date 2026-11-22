package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.FacetEntry
import com.raddstream.app.network.models.FacetsResponse
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * App-design audit (2026-11-12, issue #15): "Your library" and "Browse by
 * type" started at x=0 with their first letter cut off. Every Home section
 * heading sits 16 dp from the screen edge (M3 Expressive phone margin).
 *
 * UI-fix P1-H3: the two browse rows merged into one "Browse" section, so the
 * two heading cases below are now one — the single heading still carries
 * the 16 dp margin.
 *
 * Home fix (Nov 2026): the Library strip is gone (item 8) — its
 * "Your Library" heading test went with it. The Browse heading is the
 * only section heading left on Home.
 *
 * Runs on the JVM under Robolectric because this project has no emulator
 * image. Requires an x86_64 runner (robolectric/robolectric#9166).
 */
@RunWith(RobolectricTestRunner::class)
class HomeSectionMarginsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun browseSectionHeadingHasThePhoneMargin() {
        val facets = FacetsResponse(
            genres = listOf(FacetEntry(key = "action", label = "Action", count = 12)),
        )
        compose.setContent {
            RaddStreamTheme {
                BrowseSection(facets = facets, onFacetSelected = { _, _, _ -> })
            }
        }
        compose.onNodeWithText("Browse").assertLeftPositionInRootIsEqualTo(16.dp)
    }

    @Test
    fun browseSectionKeepsOnlyFacetChips() {
        // A5 (user decision 04:42 PKT 20 Nov): the Browse row is a
        // navigation row, not a second filter system — the type
        // jump-offs are gone (they duplicated the filter row above) and
        // only the server's facet chips remain.
        val facets = FacetsResponse(
            genres = listOf(FacetEntry(key = "action", label = "Action", count = 12)),
        )
        compose.setContent {
            RaddStreamTheme {
                BrowseSection(facets = facets, onFacetSelected = { _, _, _ -> })
            }
        }
        compose.onNodeWithText("Browse").assertExists()
        compose.onNodeWithText("Action · 12").assertExists()
        // assertDoesNotExist() exists only on node COLLECTIONS, not on the
        // single node onNodeWithText returns (it never resolves there) —
        // count the matches instead: zero means the jump-offs are gone.
        compose.onAllNodesWithText("All").assertCountEquals(0)
        compose.onAllNodesWithText("Movies").assertCountEquals(0)
    }
}
