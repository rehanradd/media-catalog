package com.raddstream.app.ui.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Home tab does not switch after failed playback (Nov 2026), second
 * half: the player card's way home and the dead-link screen's way home
 * both navigated to Home with `launchSingleTop` only and no `popUpTo`.
 * That stacks a second Home on top of the dead Player (or the dead-link
 * screen), so Back from that Home drops the viewer straight back into
 * the failed player — which reads as Home not working at all. Going
 * home must clear the stack above Home, the same block the bottom-nav
 * Home tab already uses.
 *
 * Drift tests over the source in the `NavTabHomeTest` pattern: the graph
 * needs Hilt screens to compose, so the wiring is pinned here.
 */
class PlayerHomeNavigationTest {

    private val navGraph: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) {
                return@lazy File(
                    dir,
                    "App/app/src/main/kotlin/com/raddstream/app/ui/navigation/NavGraph.kt",
                )
            }
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    private val source: String by lazy {
        assertTrue("NavGraph.kt is missing at ${navGraph.path}", navGraph.isFile)
        navGraph.readText()
    }

    /** The text from a marker to the end of its enclosing navigate block. */
    private fun navigateBlock(marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("NavGraph.kt no longer contains $marker", start >= 0)
        val end = source.indexOf("},", start)
        assertTrue("NavGraph.kt no longer closes the navigate block after $marker", end > start)
        return source.substring(start, end)
    }

    @Test
    fun `player way home clears the dead player`() {
        val block = navigateBlock("// NAV-4: the expired-link card's way home.")
        assertTrue(
            "The player card's way home must pop up to Home, or it stacks a " +
                "second Home over the dead player and Back returns to it",
            block.contains("popUpTo(Screen.Home.route)"),
        )
        assertTrue(
            "The pop must save the popped entries' state",
            block.contains("saveState = true"),
        )
        assertTrue(
            "Going home must restore Home's saved state",
            block.contains("restoreState = true"),
        )
        assertTrue(
            "A same-Home navigation must stay a no-op at the controller level",
            block.contains("launchSingleTop = true"),
        )
    }

    @Test
    fun `dead-link way home clears the dead-link screen`() {
        val block = navigateBlock("// NAV-5: the catch-all for a deep link that parsed to nothing.")
        assertTrue(
            "The dead-link screen's way home must pop up to Home, or it stacks " +
                "a second Home over the dead link and Back returns to it",
            block.contains("popUpTo(Screen.Home.route)"),
        )
        assertTrue(
            "The pop must save the popped entries' state",
            block.contains("saveState = true"),
        )
        assertTrue(
            "Going home must restore Home's saved state",
            block.contains("restoreState = true"),
        )
    }
}
