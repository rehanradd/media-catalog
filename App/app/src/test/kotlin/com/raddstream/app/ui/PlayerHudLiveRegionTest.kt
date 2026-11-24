package com.raddstream.app.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.ui.screens.CenterSpinner
import com.raddstream.app.ui.screens.EdgeFeedbackLine
import com.raddstream.app.ui.screens.Hud
import com.raddstream.app.ui.theme.RaddStreamTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * F-04 (APP-10): the transient player feedback — brightness/volume edge
 * line, scrub + subtitle toasts, boost badge, centre spinner — was
 * visual-only, so TalkBack never announced a gesture change. Each one is
 * a polite live region now. The two composables a test can compose
 * ([EdgeFeedbackLine], [CenterSpinner]) are asserted through the
 * semantics tree; the three private toasts are pinned by a source-drift
 * assert in the `PlayerControlSemanticsTest` pattern (their bodies must
 * carry the same live-region modifier).
 */
@RunWith(RobolectricTestRunner::class)
class PlayerHudLiveRegionTest {

    @get:Rule
    val compose = createComposeRule()

    private val playerScreen: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) {
                return@lazy File(
                    dir,
                    "App/app/src/main/kotlin/com/raddstream/app/ui/screens/PlayerScreen.kt",
                )
            }
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    private val source: String by lazy { playerScreen.readText() }

    /** The segment of [source] starting at [marker], up to the next top-level function. */
    private fun bodyAfter(marker: String): String =
        source.substringAfter(marker).substringBefore("\nprivate fun ")
            .substringBefore("\ninternal fun ").substringBefore("\nfun ")

    @Test
    fun `the volume edge line is a polite live region`() {
        compose.setContent {
            RaddStreamTheme {
                EdgeFeedbackLine(Hud.Volume(0.8f))
            }
        }

        val node = compose.onNodeWithContentDescription("Volume 80%").fetchSemanticsNode()
        assertEquals(
            LiveRegionMode.Polite,
            // getOrElse, not getOrNull: this Compose version's
            // SemanticsConfiguration has no getOrNull (compile error in
            // record runs 2–3). Absent reads as None, which fails the
            // assert exactly like a missing live region should.
            node.config.getOrElse(SemanticsProperties.LiveRegion) { LiveRegionMode.None },
        )
    }

    @Test
    fun `the buffering spinner is a polite live region that says buffering`() {
        compose.setContent {
            RaddStreamTheme {
                CenterSpinner(PlayerUiState(isBuffering = true))
            }
        }

        val node = compose.onNodeWithContentDescription("Buffering…").fetchSemanticsNode()
        assertEquals(
            LiveRegionMode.Polite,
            node.config.getOrElse(SemanticsProperties.LiveRegion) { LiveRegionMode.None },
        )
    }

    @Test
    fun `the scrub toast carries a live region`() {
        assertTrue(
            "ScrubToast lost its live region",
            bodyAfter("private fun ScrubToast(").contains("liveRegion = LiveRegionMode.Polite"),
        )
    }

    @Test
    fun `the subtitle toast carries a live region`() {
        assertTrue(
            "SubtitleToast lost its live region",
            bodyAfter("private fun SubtitleToast(").contains("liveRegion = LiveRegionMode.Polite"),
        )
    }

    @Test
    fun `the boost badge carries a live region`() {
        assertTrue(
            "BoostBadge lost its live region",
            bodyAfter("private fun BoostBadge(").contains("liveRegion = LiveRegionMode.Polite"),
        )
    }
}
