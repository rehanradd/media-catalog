package com.raddstream.app.diagnostics

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug-only guarantee, pinned as a source test (APP-13, review B-2).
 *
 * What it pins (the same list as the `checkDebugOnly` Gradle task, which
 * runs in Android CI — this test is the in-suite mirror):
 * 1. No `src/main` Kotlin file references the debug-only diagnostics
 *    implementation (the debug package or any debug-only class name) in
 *    CODE — string literals are stripped first, because the single
 *    sanctioned reflective install names the debug class in a string.
 * 2. The bus defaults to the NoOp implementation.
 * 3. There is exactly ONE Hilt module, and it is debug-only: `src/main`
 *    has no `DiagnosticsModule` at all (AGP merges the main and debug
 *    source sets instead of shadowing same-path files, so two modules at
 *    the same path are a duplicate-class compile error — CI round 3);
 *    the debug module provides the debug implementation.
 * 4. The navigation graph registers the diagnostics destination ONLY
 *    through `DiagnosticsBus.addDestination` — never a direct composable
 *    with the debug screen — and the Settings navigation is gated on
 *    `isEnabled()`.
 * 5. The Settings diagnostics button renders only when `showDiagnostics`
 *    is true.
 * 6. `ApiProvider` adds the timing interceptor only inside an
 *    `if (BuildConfig.DEBUG)` block.
 * 7. `Telemetry` feeds its counters into the bus (review N-6).
 * 8. The debug source set actually contains the implementation.
 * 9. The Application installs the debug implementation reflectively,
 *    inside an `if (BuildConfig.DEBUG)` block, and does NOT inject it
 *    (no `@Inject lateinit var diagnostics`).
 *
 * Plain JUnit4 on the JVM: it reads the repo from disk (walking up from
 * the Gradle working directory until it finds the App module), the same
 * shape as `BrandDriftTest`.
 */
class DiagnosticsReleaseGuardTest {

    /** The same forbidden list as `checkDebugOnly` in build.gradle.kts. */
    private val forbidden = listOf(
        "diagnostics.debug",
        "DebugDiagnostics",
        "DiagnosticsRedactor",
        "DiagnosticsScreen",
        "DiagnosticsViewModel",
        "DiagnosticsExport",
        "DiagnosticsFilters",
        "DiagnosticsSessionInfo",
    )

    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "App/app/src/main").isDirectory) return@lazy dir
            if (File(dir, "app/src/main").isDirectory) return@lazy dir.parentFile
            dir = dir.parentFile
        }
        error("repo root not found from ${System.getProperty("user.dir")}")
    }

    private fun mainFile(relative: String): File {
        val file = File(repoRoot, "App/app/src/main/$relative")
        check(file.isFile) { "missing source file: $relative" }
        return file
    }

    private fun debugFile(relative: String): File {
        val file = File(repoRoot, "App/app/src/debug/$relative")
        check(file.isFile) { "missing debug source file: $relative" }
        return file
    }

    private fun mainText(relative: String): String = mainFile(relative).readText()

    /** True when [markerIndex] lies inside one of the `if (BuildConfig.DEBUG)` blocks in [lines]. */
    private fun isInsideDebugBlock(lines: List<String>, markerIndex: Int): Boolean {
        val debugIndices = lines.indices.filter { lines[it].contains("if (BuildConfig.DEBUG)") }
        return debugIndices.any { debugIndex ->
            var depth = 0
            var closeIndex = -1
            for (i in debugIndex until lines.size) {
                depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
                if (i > debugIndex && depth == 0) {
                    closeIndex = i
                    break
                }
            }
            closeIndex >= 0 && markerIndex in debugIndex..closeIndex
        }
    }

    /**
     * The code of a file with string/char contents blanked (mirror of
     * checkDebugOnly). A hand-rolled linear scanner, NOT a regex: the
     * regex version blew the stack in CI round 4 (StackOverflowError —
     * Java's regex matcher recurses per character and a stray quote
     * makes it consume a huge span).
     */
    private fun codeWithoutStrings(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\"")
                    i += 3
                    while (i < text.length && !text.startsWith("\"\"\"", i)) {
                        out.append(' ')
                        i++
                    }
                    if (text.startsWith("\"\"\"", i)) {
                        out.append("\"\"\"")
                        i += 3
                    }
                }
                text[i] == '"' -> {
                    out.append('"')
                    i++
                    while (i < text.length && text[i] != '"' && text[i] != '\n') {
                        if (text[i] == '\\' && i + 1 < text.length) {
                            out.append("  ")
                            i += 2
                        } else {
                            out.append(' ')
                            i++
                        }
                    }
                    if (i < text.length && text[i] == '"') {
                        out.append('"')
                        i++
                    }
                }
                text[i] == '\'' -> {
                    val simple = i + 2 < text.length &&
                        text[i + 1] != '\'' && text[i + 1] != '\\' &&
                        text[i + 1] != '\n' && text[i + 2] == '\''
                    val escaped = i + 3 < text.length &&
                        text[i + 1] == '\\' && text[i + 3] == '\''
                    if (simple || escaped) {
                        val end = if (escaped) i + 4 else i + 3
                        out.append(text.substring(i, end))
                        i = end
                    } else {
                        out.append(text[i])
                        i++
                    }
                }
                else -> {
                    out.append(text[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    @Test
    fun `no src-main file references debug-only diagnostics code`() {
        val mainRoot = File(repoRoot, "App/app/src/main/kotlin/com/raddstream/app")
        val offenders = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> forbidden.any { codeWithoutStrings(file.readText()).contains(it) } }
            .map { it.relativeTo(File(repoRoot, "App/app/src/main")).path.replace(File.separatorChar, '/') }
            .toList()
        assertTrue(
            "src/main references debug-only diagnostics code: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the string stripper blanks strings but keeps code references`() {
        val sample = "val a = \"com.raddstream.app.diagnostics.debug.DebugDiagnostics\"\n" +
            "val b = DebugDiagnostics\n" +
            "val q = '\"'\n"
        val stripped = codeWithoutStrings(sample)
        assertFalse(
            "a forbidden name inside a string literal must be blanked",
            stripped.contains("diagnostics.debug"),
        )
        assertFalse(
            "a forbidden class name inside a string literal must be blanked",
            stripped.contains("DebugDiagnostics\""),
        )
        assertTrue(
            "a code reference to the forbidden name must survive stripping",
            stripped.contains("val b = DebugDiagnostics"),
        )
        assertTrue(
            "a '\"' char literal must survive stripping whole",
            stripped.contains("val q = '\"'"),
        )
    }

    @Test
    fun `DiagnosticsBus defaults to the NoOp implementation`() {
        val bus = mainText("kotlin/com/raddstream/app/diagnostics/DiagnosticsBus.kt")
        assertTrue(
            "DiagnosticsBus must default to NoOpDiagnostics",
            bus.contains("var instance: Diagnostics = NoOpDiagnostics"),
        )
        assertFalse(
            "NoOpDiagnostics is an object, not a class — it must not be invoked",
            bus.contains("NoOpDiagnostics()"),
        )
    }

    @Test
    fun `exactly one Hilt module, debug-only`() {
        assertFalse(
            "src/main must NOT have a DiagnosticsModule — two same-path modules " +
                "are a duplicate-class compile error (AGP merges source sets, it " +
                "does not shadow them)",
            File(repoRoot, "App/app/src/main/kotlin/com/raddstream/app/di/DiagnosticsModule.kt").isFile,
        )
        val debugModule = debugFile(
            "kotlin/com/raddstream/app/di/DiagnosticsModule.kt",
        ).readText()
        assertTrue(
            "debug DiagnosticsModule must provide the debug implementation",
            debugModule.contains("fun provideDiagnostics(): Diagnostics = DebugDiagnostics"),
        )
    }

    @Test
    fun `Application installs the debug implementation reflectively, DEBUG-gated`() {
        val app = mainText("kotlin/com/raddstream/app/RaddStreamApplication.kt")
        assertFalse(
            "the Application must not inject Diagnostics (release has no binding)",
            app.contains("lateinit var diagnostics"),
        )
        assertTrue(
            "the Application must load the debug implementation reflectively",
            app.contains("Class.forName(\"com.raddstream.app.diagnostics.debug.DebugDiagnostics\")"),
        )
        val lines = app.lines()
        val markerIndex = lines.indexOfFirst { it.contains("Class.forName(") }
        assertTrue("reflective install must exist", markerIndex >= 0)
        assertTrue(
            "the reflective install must sit inside an if (BuildConfig.DEBUG) block",
            isInsideDebugBlock(lines, markerIndex),
        )
    }

    @Test
    fun `NavGraph registers the diagnostics destination only through the bus`() {
        val navGraph = mainText("kotlin/com/raddstream/app/ui/navigation/NavGraph.kt")
        assertTrue(
            "NavGraph must register the route via DiagnosticsBus.addDestination",
            navGraph.contains("DiagnosticsBus.addDestination"),
        )
        assertFalse(
            "NavGraph must not register the diagnostics composable directly",
            navGraph.contains("composable(Screen.Diagnostics.route)"),
        )
        assertFalse(
            "NavGraph must not import the debug screen",
            navGraph.contains("DiagnosticsScreen"),
        )
        assertTrue(
            "Settings navigation must be gated on isEnabled()",
            navGraph.contains("if (DiagnosticsBus.isEnabled())"),
        )
    }

    @Test
    fun `SettingsScreen diagnostics button renders only when showDiagnostics is true`() {
        val settings = mainText("kotlin/com/raddstream/app/ui/screens/SettingsScreen.kt")
        assertTrue(
            "SettingsScreen must have the showDiagnostics parameter",
            settings.contains("showDiagnostics: Boolean = false"),
        )
        assertTrue(
            "SettingsScreen must gate the button on showDiagnostics",
            settings.contains("if (showDiagnostics)"),
        )
        assertTrue(
            "SettingsScreen must still reference the diagnostics string",
            settings.contains("R.string.diagnostics_open"),
        )
    }

    @Test
    fun `ApiProvider adds the timing interceptor only inside a DEBUG block`() {
        val lines = mainText("kotlin/com/raddstream/app/network/ApiProvider.kt").lines()
        val markerIndex = lines.indexOfFirst { it.contains("clientBuilder.addInterceptor") }
        assertTrue("ApiProvider must add the diagnostics interceptor", markerIndex >= 0)
        assertTrue(
            "the diagnostics interceptor must sit inside an if (BuildConfig.DEBUG) block",
            isInsideDebugBlock(lines, markerIndex),
        )
    }

    @Test
    fun `Telemetry feeds its counters into the diagnostics bus`() {
        val telemetry = mainText("kotlin/com/raddstream/app/data/Telemetry.kt")
        assertTrue(
            "Telemetry must call DiagnosticsBus.recordCounter",
            telemetry.contains("DiagnosticsBus.recordCounter"),
        )
    }

    @Test
    fun `debug source set contains the implementation`() {
        val expected = listOf(
            "kotlin/com/raddstream/app/diagnostics/debug/DebugDiagnostics.kt",
            "kotlin/com/raddstream/app/diagnostics/debug/DiagnosticsRedactor.kt",
            "kotlin/com/raddstream/app/diagnostics/debug/DiagnosticsFilters.kt",
            "kotlin/com/raddstream/app/diagnostics/debug/DiagnosticsExport.kt",
            "kotlin/com/raddstream/app/diagnostics/debug/DiagnosticsSessionInfo.kt",
            "kotlin/com/raddstream/app/diagnostics/debug/DiagnosticsViewModel.kt",
            "kotlin/com/raddstream/app/diagnostics/debug/DiagnosticsScreen.kt",
        )
        for (relative in expected) {
            assertTrue("missing debug implementation file: $relative", debugFile(relative).isFile)
        }
        // The debug-only FileProvider (review N-4) lives in the debug manifest.
        val manifest = File(repoRoot, "App/app/src/debug/AndroidManifest.xml").readText()
        assertTrue(
            "debug manifest must declare the diagnostics FileProvider",
            manifest.contains("androidx.core.content.FileProvider") &&
                manifest.contains("\${applicationId}.diagnostics"),
        )
    }

    @Test
    fun `the diagnostics route object still exists in main`() {
        val screen = mainText("kotlin/com/raddstream/app/ui/navigation/Screen.kt")
        assertTrue(
            "Screen.Diagnostics route must exist in main (a route string is not implementation)",
            screen.contains("object Diagnostics : Screen(\"diagnostics\")"),
        )
    }
}
