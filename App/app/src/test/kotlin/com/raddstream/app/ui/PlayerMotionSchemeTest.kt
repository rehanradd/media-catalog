package com.raddstream.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P5 (player fixes) — the grep gate: no player call site references the
 * duplicated [RaddMotion] token set anymore. The player chrome fades, the
 * double-tap ripple, the Ghost edge strip and the scrubber expansion all
 * read MaterialTheme.motionScheme now; the reduced-motion fallback is one
 * constant ([REDUCED_MOTION_FADE_MS]) owned by the player, not a token.
 *
 * Scope is deliberate: [RaddMotion] itself is NOT deleted here, because
 * call sites outside the player (HeroCarousel, NavGraph, HomeScreen,
 * QualityRungCard, RegionSelectionScreen, PressFeedback) still use it —
 * the full S7 migration owns those, and deleting the object before they
 * migrate would break the build. This gate pins the player half of that
 * migration, so a later edit cannot quietly reach back for the old tokens.
 */
class PlayerMotionSchemeTest {

    /** The 11 files the PlayerScreen split (APP-10) produced, in split order. */
    private val playerScreenFiles: List<String> = listOf(
        "PlayerScreen.kt", "PlayerGestures.kt", "PlayerHud.kt",
        "PlayerChrome.kt", "PlayerControls.kt", "PlayerCards.kt",
        "PlayerNotices.kt", "PlayerSubtitles.kt", "PlayerDialogs.kt",
        "PlayerSheets.kt", "PlayerScreenUtils.kt",
    )

    private val screensDir: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) {
                return@lazy File(
                    dir,
                    "App/app/src/main/kotlin/com/raddstream/app/ui/screens",
                )
            }
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    private fun source(): String = playerScreenFiles.joinToString("\n") { name ->
        val file = File(screensDir, name)
        assertTrue("Player split file $name is missing at ${file.path}", file.isFile)
        file.readText()
    }

    @Test
    fun `no player file references the duplicated motion tokens`() {
        val code = source()
        assertFalse(
            "a player call site still references RaddMotion — migrate it to " +
                "MaterialTheme.motionScheme",
            code.contains("RaddMotion"),
        )
    }

    @Test
    fun `the player chrome fades read the motion scheme`() {
        val code = source()
        assertTrue(code.contains("MaterialTheme.motionScheme.fastEffects"))
        assertTrue(code.contains("MaterialTheme.motionScheme.defaultEffects"))
        assertTrue(code.contains("REDUCED_MOTION_FADE_MS"))
    }

    @Test
    fun `the reduced-motion fallback is an instant crossfade`() {
        // P5/S7: reduced motion means instant or a 100ms fade — the player's
        // 30ms crossfade is the instant end of that range.
        val utils = File(screensDir, "PlayerScreenUtils.kt").readText()
        val match = Regex("REDUCED_MOTION_FADE_MS = (\\d+)").find(utils)
        assertTrue("REDUCED_MOTION_FADE_MS is not defined", match != null)
        val ms = match!!.groupValues[1].toInt()
        assertTrue("reduced-motion fade must be instant or <= 100ms, was $ms", ms <= 100)
    }
}
