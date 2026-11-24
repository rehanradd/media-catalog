package com.raddstream.app.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HomeMotionSchemeTest — the S7 gate for the 2027 Home + Shared fix.
 *
 * Plain JUnit4, no Robolectric: like [CardGeometryTest] it reads the
 * sources from disk, so it runs anywhere a JDK + junit4 exist.
 *
 * Rule: the Home scope animates on the system [androidx.compose.material3.MotionScheme]
 * (wired as `MotionScheme.expressive()` in `RaddStreamTheme`), never on the
 * hand-rolled [com.raddstream.app.ui.theme.RaddMotion] spring functions.
 * The spring functions stay — PlayerScreen, QualityRungCard and
 * PressFeedback still use them (out of this run's scope; they migrate when
 * the Player split lands) — but the three migrated sites must not.
 *
 * Scope: the whole of HeroCarousel and HomeScreen, and the BottomNavTab
 * function body in NavGraph (the rest of NavGraph keeps RaddMotion for
 * its screen enter/exit transitions — durations, not springs — so the
 * file-level scan is scoped to the tab function on purpose).
 */
class HomeMotionSchemeTest {

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "App/app/src/main").isDirectory) return@lazy dir
            dir = dir.parentFile
        }
        error("App/app/src/main not found above ${File("").absolutePath}")
    }

    private val wholeFiles = listOf(
        "App/app/src/main/kotlin/com/raddstream/app/ui/components/HeroCarousel.kt",
        "App/app/src/main/kotlin/com/raddstream/app/ui/screens/HomeScreen.kt",
    )

    private val navGraphPath = "App/app/src/main/kotlin/com/raddstream/app/ui/navigation/NavGraph.kt"

    private fun String.withoutComments(): String =
        Regex("""/\*[\s\S]*?\*/""").replace(Regex("""//[^\n]*""").replace(this, ""), "")

    /** The body of `<name>(...) { ... }`, braces counted, strings skipped.
     * Searched by name + "(" (not "fun " + name) because BottomNavTab is
     * declared with a receiver (`RowScope.BottomNavTab`); the definition
     * is the first occurrence, ahead of its call site. */
    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("$name(")
        assertTrue("function $name not found", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        var i = open
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
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return source.substring(open, i + 1)
                    }
                }
            }
            i++
        }
        error("unbalanced braces in $name")
    }

    @Test
    fun homeScopeUsesNoRaddMotionSprings() {
        val offenders = mutableListOf<String>()
        for (path in wholeFiles) {
            val file = File(repoRoot, path)
            assertTrue("scope file missing: $path", file.isFile)
            if (file.readText().withoutComments().contains("RaddMotion.")) {
                offenders += path
            }
        }
        val navGraph = File(repoRoot, navGraphPath)
        assertTrue("scope file missing: $navGraphPath", navGraph.isFile)
        if (functionBody(navGraph.readText(), "BottomNavTab").contains("RaddMotion.")) {
            offenders += "$navGraphPath::BottomNavTab"
        }
        assertEquals(
            "a migrated Home-scope site still calls a RaddMotion spring — use MaterialTheme.motionScheme instead",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun homeScopeActuallyReadsTheMotionScheme() {
        // The negative test above would pass on a file that deleted its
        // motion entirely; this one proves the migration landed.
        for (path in wholeFiles) {
            val file = File(repoRoot, path)
            assertTrue("scope file missing: $path", file.isFile)
            assertTrue(
                "$path never reads MaterialTheme.motionScheme",
                file.readText().withoutComments().contains("MaterialTheme.motionScheme"),
            )
        }
        val navGraph = File(repoRoot, navGraphPath)
        assertTrue(
            "$navGraphPath::BottomNavTab never reads MaterialTheme.motionScheme",
            functionBody(navGraph.readText(), "BottomNavTab").contains("MaterialTheme.motionScheme"),
        )
    }
}
