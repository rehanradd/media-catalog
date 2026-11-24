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

    /** The 11 files the PlayerScreen split (APP-10) produced, in split order. */
    private val playerScreenFiles: List<String> = listOf(
        "PlayerScreen.kt", "PlayerGestures.kt", "PlayerHud.kt",
        "PlayerChrome.kt", "PlayerControls.kt", "PlayerCards.kt",
        "PlayerNotices.kt", "PlayerSubtitles.kt", "PlayerDialogs.kt",
        "PlayerSheets.kt", "PlayerScreenUtils.kt",
    )

    private val source: String by lazy {
        // The drift asserts read the split files concatenated, in split
        // order, so a toast's neighbours are the same as when the screen
        // was one file.
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) {
                val screensDir = File(
                    dir,
                    "App/app/src/main/kotlin/com/raddstream/app/ui/screens",
                )
                return@lazy playerScreenFiles.joinToString("\n") { name ->
                    val file = File(screensDir, name)
                    assertTrue("Player split file $name is missing at ${file.path}", file.isFile)
                    file.readText()
                }
            }
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

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
        // getOrElse, not getOrNull: this Compose version's
        // SemanticsConfiguration has no getOrNull (compile error in
        // record runs 2–3), and LiveRegionMode has no None to default
        // to (compile error in run 4) — absence is expressed by the
        // property not being in the config at all, so assert presence
        // first, then the value.
        assertTrue(node.config.contains(SemanticsProperties.LiveRegion))
        assertEquals(
            LiveRegionMode.Polite,
            node.config[SemanticsProperties.LiveRegion],
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
        assertTrue(node.config.contains(SemanticsProperties.LiveRegion))
        assertEquals(
            LiveRegionMode.Polite,
            node.config[SemanticsProperties.LiveRegion],
        )
    }

    @Test
    fun `the scrub toast carries a live region`() {
        assertTrue(
            "ScrubToast lost its live region",
            bodyAfter("fun ScrubToast(").contains("liveRegion = LiveRegionMode.Polite"),
        )
    }

    @Test
    fun `the subtitle toast carries a live region`() {
        assertTrue(
            "SubtitleToast lost its live region",
            bodyAfter("fun SubtitleToast(").contains("liveRegion = LiveRegionMode.Polite"),
        )
    }

    @Test
    fun `the boost badge carries a live region`() {
        assertTrue(
            "BoostBadge lost its live region",
            bodyAfter("fun BoostBadge(").contains("liveRegion = LiveRegionMode.Polite"),
        )
    }
}
