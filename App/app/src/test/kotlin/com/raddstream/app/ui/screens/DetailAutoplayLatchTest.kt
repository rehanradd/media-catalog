package com.raddstream.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Home tab does not switch after failed playback (Nov 2026): after a
 * failed autoplay playback — the hero's Watch Now is the entry — every
 * exit from the player was undone within a frame and the viewer could
 * never reach a tab, so tapping Home read as doing nothing.
 *
 * The cause was the autoplay once-per-title latch in DetailScreen:
 * `autoPlayedFor` was a plain `remember`, which Navigation Compose
 * discards when Detail leaves the composition for the Player. Coming
 * back from the failed player, the latch was null again — and the post
 * was already loaded and the resume lookup already landed in the
 * surviving ViewModel — so the autoplay effect re-fired at once and
 * `playBest()` re-opened the failed player. PlayerScreen already
 * documents the mechanism (its "Back to details" comment: the Detail
 * underneath "re-pushes the player on return (its autoplay effect
 * re-fires on a fresh composition)").
 *
 * The latch must survive the screen leaving the composition, so it is
 * `rememberSaveable` — kept in the back-stack entry's saved state. A
 * different title is a different entry, so its own autoplay is
 * untouched. Drift tests over the source in the `NavTabHomeTest`
 * pattern: DetailScreen needs a Hilt ViewModel to compose, so the
 * wiring is pinned here.
 */
class DetailAutoplayLatchTest {

    private val detailScreen: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) {
                return@lazy File(
                    dir,
                    "App/app/src/main/kotlin/com/raddstream/app/ui/screens/DetailScreen.kt",
                )
            }
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    private val source: String by lazy {
        assertTrue("DetailScreen.kt is missing at ${detailScreen.path}", detailScreen.isFile)
        detailScreen.readText()
    }

    @Test
    fun `autoplay latch survives leaving the screen`() {
        assertTrue(
            "autoPlayedFor must be rememberSaveable, not plain remember — " +
                "a plain remember is discarded when Detail leaves the composition " +
                "for the Player, so returning from a failed playback resets the " +
                "latch and the autoplay effect re-opens the failed player at once",
            source.contains("var autoPlayedFor by rememberSaveable { mutableStateOf<Int?>(null) }"),
        )
        assertFalse(
            "autoPlayedFor must not be a plain remember — that is the bug",
            source.contains("var autoPlayedFor by remember { mutableStateOf<Int?>(null) }"),
        )
    }

    @Test
    fun `autoplay still fires once per title`() {
        // The latch is the only thing that changed: the effect must still
        // guard on it and still set it, or autoplay would fire on every
        // recomposition instead of once per title.
        assertTrue(
            "The autoplay effect must still guard on autoPlayedFor != post.id",
            source.contains("autoPlayedFor != post.id"),
        )
        assertTrue(
            "The autoplay effect must still set autoPlayedFor = post.id",
            source.contains("autoPlayedFor = post.id"),
        )
        assertTrue(
            "DetailScreen must import rememberSaveable from androidx.compose.runtime.saveable",
            source.contains("import androidx.compose.runtime.saveable.rememberSaveable"),
        )
    }
}
