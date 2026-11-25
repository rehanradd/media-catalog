package com.raddstream.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S8 (round 3): the player UI draws every glyph from the custom icon
 * set (res/drawable ic_player_* / ic_action_*), never from
 * androidx.compose.material.icons. The drawables carry the exact Material
 * glyph paths, so the render is pixel-identical — but the source must not
 * reach for Icons.Default again, or the set drifts back to two icon
 * systems. This is a source test in the BrandDriftTest pattern: it reads
 * the 11 player split files and fails on any material-icons reference.
 */
class PlayerIconSystemTest {

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

    @Test
    fun `player UI never uses Icons Default`() {
        for (name in playerScreenFiles) {
            val file = File(screensDir, name)
            assertTrue("Player split file $name is missing at ${file.path}", file.isFile)
            val text = file.readText()
            assertFalse(
                "$name still uses Icons.Default — draw the glyph from res/drawable (ic_player_* / ic_action_*) instead",
                text.contains("Icons.Default"),
            )
            assertFalse(
                "$name still imports androidx.compose.material.icons — the player icon set lives in res/drawable",
                text.contains("androidx.compose.material.icons"),
            )
        }
    }
}
