package com.raddstream.app.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HomeSpacingTokenTest — the S6 gate for the 2027 Home + Shared fix.
 *
 * Plain JUnit4, no Robolectric: like [CardGeometryTest] it reads the
 * sources from disk and inspects them, so it runs anywhere a JDK + junit4
 * exist and fails in CI rather than on a phone nobody has here.
 *
 * Rule: in the Home scope, spacing is a [com.raddstream.app.ui.theme.RaddTokens]
 * value, never a literal. A `.dp` literal on a code line is allowed only
 * when it is `0.dp` or `48.dp` (the touch-target floor, pinned by value)
 * or when the same line carries `// token-exempt:` — geometry that is not
 * spacing (icon sizes, dot geometry, skeleton bars, hairlines, dividers,
 * typography fallbacks), with the reason in the comment.
 *
 * Scope: the whole of HomeScreen, HeroCarousel, PosterCard, ScrollingChipRow
 * and NavGraph — plus the three Home regions of CommonUi
 * ([raddFilterChipColors], the chip-states preview, [ErrorState],
 * [InlineLoadFailure]). The rest of CommonUi (Detail skeletons, MediaRail)
 * is out of this run's scope and is NOT scanned: a second run owns it.
 */
class HomeSpacingTokenTest {

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "App/app/src/main").isDirectory) return@lazy dir
            dir = dir.parentFile
        }
        error("App/app/src/main not found above ${File("").absolutePath}")
    }

    private val uiDir: File get() = File(repoRoot, "App/app/src/main/kotlin/com/raddstream/app/ui")

    private val wholeFiles = listOf(
        "App/app/src/main/kotlin/com/raddstream/app/ui/screens/HomeScreen.kt",
        "App/app/src/main/kotlin/com/raddstream/app/ui/components/HeroCarousel.kt",
        "App/app/src/main/kotlin/com/raddstream/app/ui/components/PosterCard.kt",
        "App/app/src/main/kotlin/com/raddstream/app/ui/components/ScrollingChipRow.kt",
        "App/app/src/main/kotlin/com/raddstream/app/ui/navigation/NavGraph.kt",
    )

    private val commonUiPath = "App/app/src/main/kotlin/com/raddstream/app/ui/components/CommonUi.kt"
    private val commonUiRegions = listOf(
        "raddFilterChipColors",
        "FilterChipStatesPreview",
        "ErrorState",
        "InlineLoadFailure",
    )

    private val dpLiteral = Regex("""[0-9]+(?:\.[0-9]+)?\.dp""")
    private val allowedByValue = setOf("0.dp", "48.dp")

    /** A code line (comments stripped) and whether its raw line is exempt. */
    private fun codeLines(source: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        var inBlock = false
        for (raw in source.lines()) {
            var line = raw
            var code = StringBuilder()
            var i = 0
            while (i < line.length) {
                if (inBlock) {
                    val end = line.indexOf("*/", i)
                    if (end == -1) {
                        i = line.length
                    } else {
                        inBlock = false
                        i = end + 2
                    }
                } else if (line.startsWith("/*", i)) {
                    inBlock = true
                    i += 2
                } else if (line.startsWith("//", i)) {
                    break
                } else {
                    code.append(line[i])
                    i++
                }
            }
            out += code.toString() to raw.contains("token-exempt:")
        }
        return out
    }

    /** The body of `fun <name>(...) { ... }`, braces counted, strings skipped. */
    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
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

    private fun offendersFor(path: String, source: String): List<String> {
        val bad = mutableListOf<String>()
        codeLines(source).forEachIndexed { index, (code, exempt) ->
            val literals = dpLiteral.findAll(code).map { it.value }.toList()
            val unallowed = literals.filter { it !in allowedByValue }
            if (unallowed.isNotEmpty() && !exempt) {
                bad += "$path:${index + 1}: ${unallowed.joinToString()} — use a RaddTokens value or mark token-exempt with a reason"
            }
        }
        return bad
    }

    @Test
    fun homeScopeHasNoRawSpacingLiterals() {
        val offenders = mutableListOf<String>()
        for (path in wholeFiles) {
            val file = File(repoRoot, path)
            assertTrue("scope file missing: $path", file.isFile)
            offenders += offendersFor(path, file.readText())
        }
        val commonUi = File(repoRoot, commonUiPath)
        assertTrue("scope file missing: $commonUiPath", commonUi.isFile)
        val commonUiSource = commonUi.readText()
        for (region in commonUiRegions) {
            offenders += offendersFor("$commonUiPath::$region", functionBody(commonUiSource, region))
        }
        assertEquals(
            "a Home-scope line spaces with a raw .dp literal",
            emptyList<String>(),
            offenders,
        )
    }
}
