package com.raddstream.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P5 (player fixes) — the grep gate, rewritten after the CI failure on
 * 095e1c46 (Android run 45467907976): the first version of this gate asserted
 * the player had migrated OFF [RaddMotion] onto MaterialTheme.motionScheme.
 * That migration is BLOCKED on the pinned BOM — `MaterialTheme.motionScheme`
 * is internal in material3 1.4.0 (Compose BOM 2025.11.01) and only becomes
 * public in material3 1.5.0, which needs a BOM bump plus
 * MaterialExpressiveTheme: a separate decision, not this branch. Attempting
 * the read breaks the build (the exact failure that killed 45467907976).
 *
 * So this gate pins the workaround instead: the player chrome fades, the
 * double-tap ripple, the Ghost edge strip and the scrubber expansion all
 * ride the same M3 Expressive token values through [RaddMotion], which
 * carries them directly (fastSpatial 0.6/800, defaultSpatial 0.8/380,
 * fastEffects 1.0/3800, defaultEffects 1.0/1600) — same springs, same
 * pixels; only the source differs until the BOM bump. The reduced-motion
 * fallback stays one constant ([REDUCED_MOTION_FADE_MS]) owned by the
 * player. When the BOM bump lands, this gate is the file to flip: delete
 * the `no MaterialTheme.motionScheme` test and restore the migration
 * assertions.
 *
 * The negative assertions run on comment-stripped code: the block itself is
 * documented in the source comments, so a raw text search would trip on
 * its own explanation.
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

    /** Source with block comments and `//` line comments removed. */
    private fun strippedCode(): String {
        val noBlock = source().replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
        return noBlock.lines().joinToString("\n") { it.substringBefore("//") }
    }

    @Test
    fun `no player file reads the internal motion scheme`() {
        // MaterialTheme.motionScheme is internal in the pinned material3 1.4.0
        // — reading it is a compile error (Android run 45467907976). The
        // BOM bump to material3 1.5.0 is what unblocks it.
        val code = strippedCode()
        assertFalse(
            "a player file reads MaterialTheme.motionScheme, which is internal " +
                "in the pinned material3 1.4.0 — use the RaddMotion token " +
                "functions until the BOM bump to 1.5.0",
            code.contains("MaterialTheme.motionScheme"),
        )
        assertFalse(
            "a player file references MotionScheme directly — same internal-API " +
                "block as MaterialTheme.motionScheme",
            code.contains("MotionScheme"),
        )
    }

    @Test
    fun `the player fades and scrubber ride the RaddMotion token springs`() {
        val code = strippedCode()
        assertTrue(code.contains("RaddMotion.fastEffects()"))
        assertTrue(code.contains("RaddMotion.defaultEffects()"))
        assertTrue(code.contains("RaddMotion.defaultSpatial()"))
        assertTrue(code.contains("RaddMotion.fastSpatial()"))
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
