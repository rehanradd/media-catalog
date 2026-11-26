package com.raddstream.app.tooling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P0-11 (26 Nov 2026 audit, B-15): the app ships one icon language — the house
 * stroke set in res/drawable (ic_tab_*, ic_action_*, ic_player_*). Material
 * default icons (Icons.Default) are heavier filled glyphs; the 55 call sites
 * that still used them were migrated to the house set, and this guard fails CI
 * on any new usage in production ui/ code, so the two languages never mix again.
 *
 * The scan is raw text on purpose: a mention inside a comment trips it too, so
 * a commented-out Material icon — or a note saying "use Icons.Default here" —
 * cannot smuggle the old language back in. The S8 comments that used to name
 * the old icons were reworded for exactly this reason.
 *
 * Scope is production ui/ only (src/main/kotlin/com/raddstream/app/ui), as the
 * audit specified. Test code is out of scope — the nav tests still build
 * throwaway vectors from Icons.Default — and the DEBUG-only resolve screens
 * live outside ui/ too.
 */
class HouseIconsGuardTest {

    private val uiDir: File get() = File("src/main/kotlin/com/raddstream/app/ui")

    private fun uiKotlinFiles(): List<File> =
        uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun containsBannedIcon(text: String): Boolean = "Icons.Default" in text

    @Test
    fun `no Icons Default usage in production ui code`() {
        val files = uiKotlinFiles()
        assertTrue("the guard must actually be reading ui sources", files.isNotEmpty())

        val offenders = files.filter { containsBannedIcon(it.readText()) }
            .map { it.path }

        assertEquals(
            "a Material default icon in ui/ is a second icon language — use the house " +
                "stroke set (R.drawable.ic_action_* / ic_player_*) instead",
            emptyList<String>(), offenders,
        )
    }

    /** The guard needs a guard: the rule must tell a banned usage from a house one. */
    @Test
    fun `the banned-icon rule itself separates a Material icon from a house glyph`() {
        assertTrue(
            "a direct Material icon is banned",
            containsBannedIcon("Icon(Icons.Default.ArrowBack, contentDescription = \"Back\")"),
        )
        assertTrue(
            "a Material icon behind imageVector is banned",
            containsBannedIcon("imageVector = Icons.Default.Refresh,"),
        )
        assertFalse(
            "a house drawable is not banned",
            containsBannedIcon(
                "Icon(painter = painterResource(R.drawable.ic_action_arrow_left), " +
                    "contentDescription = \"Back\")",
            ),
        )
        assertFalse(
            "a house vectorResource is not banned",
            containsBannedIcon(
                "icon = ImageVector.vectorResource(R.drawable.ic_action_help_outline),",
            ),
        )
    }
}
