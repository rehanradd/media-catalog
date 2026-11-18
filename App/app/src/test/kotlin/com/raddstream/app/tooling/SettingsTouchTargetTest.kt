package com.raddstream.app.tooling

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings review (Nov 2026, D5): every tappable Button, OutlinedButton
 * and TextButton on the Settings screen — and the Help screen's About
 * Close button — carries the 48dp touch-target floor. A Material button's
 * own min height is below the floor, so the floor is a modifier, not a
 * default — and a new button added without it would silently ship short.
 *
 * A source scan, like [DetailTouchTargetTest], but exact instead of a
 * character window: each call's own argument list (paren-balanced, string
 * literals stripped) must contain heightIn(min = 48.dp). A window would
 * lie here — the Save button's modifier sits 801 characters past
 * its open, while the Dashboard/Profile pair is only 153 characters
 * apart. SettingsScreen carries no preview-only mock buttons, so the
 * whole file is shipped UI and the whole file is scanned.
 */
class SettingsTouchTargetTest {

    private fun source(name: String): String =
        File("src/main/kotlin/com/raddstream/app/ui/screens/$name").readText()

    /** The argument list of every button call in [source], paren-balanced. */
    private fun buttonArgLists(source: String): List<String> {
        val blocks = mutableListOf<String>()
        // The lookbehind keeps TextButton from matching twice — once as
        // itself and once as the Button inside it.
        val call = Regex("""(?<![A-Za-z])(?:Button|OutlinedButton|TextButton)\(""")
        for (match in call.findAll(source)) {
            var depth = 0
            var i = match.range.first
            var inString = false
            var escaped = false
            while (i < source.length) {
                val c = source[i]
                if (inString) {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') inString = false
                } else {
                    when (c) {
                        '"' -> inString = true
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) {
                                blocks.add(source.substring(match.range.first, i + 1))
                                break
                            }
                        }
                    }
                }
                i++
            }
        }
        return blocks
    }

    @Test
    fun `every settings button carries the 48dp floor`() {
        val settings = source("SettingsScreen.kt")
        val calls = buttonArgLists(settings)
        assertTrue("SettingsScreen must still have its buttons", calls.isNotEmpty())
        val short = calls.filter { !it.contains("heightIn(min = 48.dp)") }
        assertTrue(
            "every settings button must carry heightIn(min = 48.dp), missing at: " +
                short.joinToString { it.take(80).replace("\n", " ") },
            short.isEmpty(),
        )
    }

    @Test
    fun `help about close carries the 48dp floor`() {
        val help = source("HelpScreen.kt")
        val calls = buttonArgLists(help)
        assertTrue("HelpScreen must still have its Close button", calls.isNotEmpty())
        val short = calls.filter { !it.contains("heightIn(min = 48.dp)") }
        assertTrue(
            "every help button must carry heightIn(min = 48.dp), missing at: " +
                short.joinToString { it.take(80).replace("\n", " ") },
            short.isEmpty(),
        )
    }
}
