package com.raddstream.app.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UI-layer half of the Robo run-4 and run-5 a11y fixes (Sessions 132–133):
 * the bottom-nav items still measured 32dp tall in Robo runs 4 and 5 — under
 * the 48dp touch-target floor — even after run 3 added heightIn(min = 48.dp)
 * to the item and run 4 set the bar to exactly 80dp. The M3 source (1.3.0,
 * the version this app's compose-bom pins) shows why: the item's Box already
 * defaults to the 80dp bar height, and 32dp is the active-indicator pill
 * inside it — so the heightIn never reached the measured node, and Robo kept
 * measuring the pill in run 5. The bar no longer uses NavigationBarItem at
 * all: BottomNavTab owns its own clickable (the full bar height, 80dp
 * then) and its own indicator pill, raised to 48dp (then) so no node in
 * the bar is under the floor.
 * This test pins the rendered result: it builds the bar exactly the way
 * NavGraph does (NavigationBar, BottomNavTab, icon + label) and
 * asserts every item's clickable node is at least 48dp tall and is the full
 * 80dp bar height, every indicator pill is 48dp tall, and every tab speaks
 * its own label. Runs on the JVM under Robolectric because this project has
 * no emulator image.
 *
 * Robo run 6 (Session 134): the tabs STILL measured 32dp on the device, and
 * the pill is 48dp — so the pill was never the measured node. The measured
 * node is the tab, and the cause is the bar's own height: M3's NavigationBar
 * applies the system navigation-bars inset inside its 80dp row, and the
 * run-4 fix forced the bar to exactly 80dp total, so a 48dp system nav bar
 * (3-button navigation, the Robo device) left 32dp for the tabs. The bar no
 * longer forces a height — it sizes to its content plus the inset — and this
 * test now injects the same 48dp bottom inset the device has, so a regression
 * fails here instead of in Robo.
 *
 * fillMaxHeight() on the item was tried first in run 4 and is WRONG:
 * it makes the item fill the available height instead of the bar, so the bar
 * expands to fill the screen (the test measured 470dp — the test surface
 * height). It must never come back.
 *
 * Density (Nov 2026): the bar is 64dp and the pill is M3 Expressive's
 * 56×32dp — the pill is visual only, the tab is the touch target at
 * 64dp. M3 1.3.0's NavigationBar floors its Row at minHeight 80dp, so
 * the 64dp bar cannot be built on it; production now uses RaddBottomBar
 * (the same shape minus the floor) and this test builds it too. The
 * pill test now pins the 56×32 visual instead of the 48dp floor, and
 * the bar-height tests expect 64dp. A Robo re-run on a 3-button-nav
 * device is the verification owed for this change.
 *
 * Requires an x86_64 runner. Robolectric's native runtime has no Linux/aarch64
 * build (robolectric/robolectric#9166) and it fails while the runner boots, so
 * the exclusion for ARM hosts lives in app/build.gradle.kts rather than here.
 */
@RunWith(RobolectricTestRunner::class)
class BottomNavTouchTargetTest {

    @get:Rule
    val compose = createComposeRule()

    private val items: List<Pair<String, ImageVector>> = listOf(
        "Home" to Icons.Default.Home,
        "Search" to Icons.Default.Search,
        "Live TV" to Icons.Default.Tv,
        "My List" to Icons.Default.Favorite,
        "Settings" to Icons.Default.Settings,
    )

    private fun showBar(bottomInset: Dp = 0.dp) {
        compose.setContent {
            RaddStreamTheme {
                // No forced height — production lets the bar size to its
                // content plus the system nav inset (run 6). The inset is
                // injected here as a Modifier so the test can reproduce the
                // device: the no-inset tests use the default 0dp, the inset
                // test passes the Robo device's 48dp. (RaddBottomBar takes
                // the inset as a Modifier, not a WindowInsets value —
                // WindowInsets.navigationBars does not resolve as a default
                // value in this module — so production's default is
                // navigationBarsPadding() and the test passes
                // windowInsetsPadding(WindowInsets(...)) instead.)
                RaddBottomBar(modifier = Modifier.windowInsetsPadding(WindowInsets(bottom = bottomInset))) {
                    items.forEach { (label, icon) ->
                        BottomNavTab(
                            label = label,
                            icon = icon,
                            selected = label == "Home",
                            onClick = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun bottomNavItemsAreAtLeast48dpTall() {
        showBar()
        // The bar's only clickable nodes are its five tabs — one per tab.
        val clickable = compose.onAllNodes(hasClickAction())
        clickable.assertCountEquals(items.size)
        for (node in clickable.fetchSemanticsNodes()) {
            val height = with(compose.density) { node.boundsInRoot.height.toDp() }
            assertTrue(
                "bottom-nav item is only $height tall, under the 48dp floor",
                height >= 48.dp,
            )
        }
    }

    @Test
    fun bottomNavItemsAreTheFullBarHeight() {
        showBar()
        // The bar is 64dp by the M3 Expressive spec, and the tab fills
        // it — so the clickable is the whole bar, not the 32dp indicator
        // pill inside it. This is the number Robo runs 4 and 5 measured
        // (32dp); if it ever drops back to 32dp here, the indicator is
        // the measured node again.
        val clickable = compose.onAllNodes(hasClickAction())
        clickable.assertCountEquals(items.size)
        for (node in clickable.fetchSemanticsNodes()) {
            val height = with(compose.density) { node.boundsInRoot.height.toDp() }
            assertTrue(
                "bottom-nav item is $height tall, expected the full 64dp bar height",
                height in 63.dp..65.dp,
            )
        }
    }

    @Test
    fun bottomNavItemsStay64dpTallWithSystemNavBarInset() {
        // Robo run 6: the tabs measured 32dp on the device even though the
        // pill is 48dp and the no-inset tests above measure 80dp. M3's
        // NavigationBar applies the system navigation-bars inset inside its
        // 80dp row; a 48dp inset (3-button navigation, the Robo device) left
        // 32dp for the tabs when the bar was forced to exactly 80dp total.
        // The bar now sizes to its content plus the inset, so the tabs stay
        // the full 64dp even with the inset applied.
        showBar(bottomInset = 48.dp)
        val clickable = compose.onAllNodes(hasClickAction())
        clickable.assertCountEquals(items.size)
        for (node in clickable.fetchSemanticsNodes()) {
            val height = with(compose.density) { node.boundsInRoot.height.toDp() }
            assertTrue(
                "bottom-nav item is $height tall with a 48dp nav inset, expected the full 64dp bar height",
                height in 63.dp..65.dp,
            )
        }
    }

    @Test
    fun bottomNavIndicatorPillsAreTheExpressivePill() {
        showBar()
        // Density (Nov 2026): the pill is M3 Expressive's 56×32dp VISUAL
        // — the tab is the touch target (pinned ≥48dp by
        // bottomNavItemsAreAtLeast48dpTall), so the pill no longer needs
        // to clear the floor on its own. Robo runs 4–6 measured the 32dp
        // pill under the floor, but run 6 proved the measured node is the
        // tab, not the pill — and the pill carries no click action. The
        // pill carries the test tag, but the tab's own semantics node hides
        // it in the merged tree, so the query uses the unmerged tree.
        // Height is the asserted dimension: the pill's width is capped at
        // 56dp by the token and fills the tab below it.
        val pills = compose.onAllNodesWithTag(BottomNavIndicatorTag, useUnmergedTree = true)
        pills.assertCountEquals(items.size)
        for (node in pills.fetchSemanticsNodes()) {
            val height = with(compose.density) { node.boundsInRoot.height.toDp() }
            assertTrue(
                "bottom-nav indicator pill is $height tall, expected the 32dp Expressive pill",
                height in 31.dp..33.dp,
            )
        }
    }

    @Test
    fun bottomNavTabsSpeakTheirOwnLabels() {
        showBar()
        // The tab itself carries the spoken name (the SwitchRow pattern), so
        // a screen reader hears each tab once, by its own label.
        for ((label, _) in items) {
            compose.onNode(hasContentDescription(label)).assertExists()
        }
    }
}
