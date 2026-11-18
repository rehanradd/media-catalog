package com.raddstream.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TypeScaleTest — guards the M3 type scale on the JVM (A4-F7).
 *
 * Plain JUnit4, no Robolectric, no Android runtime: like
 * [com.raddstream.app.ui.layout.CardGeometryTest] it reads the sources from
 * disk and inspects them, so it runs anywhere a JDK + junit4 exist and it
 * fails in CI rather than on a phone nobody has here.
 *
 * Why a source scan and not a layout assertion: a 13sp label and a 14sp
 * label look identical in a screenshot diff at a glance, and the drift
 * re-enters one `brandSp(15)` at a time. The only honest gate is the one
 * that refuses the off-scale value at the call site.
 *
 * It fails when:
 *  1. a `brandSp(13)`, `brandSp(15)` or `brandSp(18)` call survives anywhere
 *     in `ui/` — those sizes are off the M3 scale (Type.kt: "off-scale sizes
 *     such as 13, 15, 18 are non-conformant");
 *  2. the Downloads empty state goes back to a fixed-height box — Robo v7
 *     (v7-11.png) caught the 240dp box half-cutting the "Find something to
 *     download" pill, because EmptyState's fillScreen padding left 176dp of
 *     room for ~206dp of content.
 */
class TypeScaleTest {

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "App/app/src/main").isDirectory) return@lazy dir
            dir = dir.parentFile
        }
        error("App/app/src/main not found above ${File("").absolutePath}")
    }

    private val uiDir: File get() = File(repoRoot, "App/app/src/main/kotlin/com/raddstream/app/ui")

    private val downloadsPath =
        "App/app/src/main/kotlin/com/raddstream/app/ui/screens/DownloadsScreen.kt"

    /** Every UI source, comments already stripped so prose about a number is not read as the number. */
    private fun sourcesByPath(): Map<String, String> =
        uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate {
                val prefix = repoRoot.absolutePath.replace(File.separatorChar, '/') + "/"
                val path = it.absolutePath.replace(File.separatorChar, '/')
                val rel = if (path.startsWith(prefix)) path.removePrefix(prefix) else path
                rel to it.readText().withoutComments()
            }

    // ── 1. no off-scale type size ────────────────────────────────────────────
    @Test
    fun noOffScaleBrandSpSizes() {
        val offenders = sourcesByPath()
            .filter { (_, body) ->
                Regex("""brandSp\(\s*(13|15|18)\s*\)""").containsMatchIn(body)
            }
            .keys
            .sorted()
        assertEquals(
            "an off-scale type size survived — 13, 15 and 18 are not on the M3 scale " +
                "(Type.kt). Move to 12, 14 or 16 and keep one size per role",
            emptyList<String>(),
            offenders,
        )
        // Show the scan reads what it claims to read: brandSp call sites exist,
        // so a future refactor that drops brandSp entirely fails loudly here
        // instead of passing over nothing.
        val calls = sourcesByPath().values.sumOf { Regex("""brandSp\(""").findAll(it).count() }
        assertTrue(
            "the brandSp scan found $calls call sites; if that ever drops to 0 this test " +
                "passes over nothing",
            calls >= 50,
        )
    }

    // ── 2. the Downloads empty state wraps its content ──────────────────────
    @Test
    fun downloadsEmptyStateWrapsItsContent() {
        val body = sourcesByPath().getValue(downloadsPath)
        assertTrue(
            "the Downloads empty state must not sit in a fixed-height box again — " +
                "the 240dp box half-cut the pill in Robo v7 (v7-11.png)",
            !Regex("""height\(\s*240\s*\.dp""").containsMatchIn(body),
        )
        assertTrue(
            "the Downloads empty state must wrap its own content (fillScreen = false, " +
                "the F5 My List pattern), or the pill clips again on a short viewport",
            Regex("""fillScreen\s*=\s*false""").containsMatchIn(body),
        )
        assertTrue(
            "the Downloads empty-state copy is gone — the pins above would pass over nothing",
            body.contains("Nothing downloaded yet"),
        )
    }

    private fun String.withoutComments(): String =
        Regex("""/\*[\s\S]*?\*/""").replace(Regex("""//[^\n]*""").replace(this, ""), "")
}
