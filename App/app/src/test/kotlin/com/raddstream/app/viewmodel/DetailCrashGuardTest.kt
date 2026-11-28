package com.raddstream.app.viewmodel

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detail-crash hardening (2026-11-28): opening a Detail killed the app
 * about 3 times in 5 on Appetize (build app-debug-2322) — two silent
 * vanishes, one "Radd keeps stopping" dialog, one "Couldn't load this
 * title" that Retry fixed. The investigation found the whole Detail-open
 * path guarded except ONE spot, and found no named throw in code: the
 * failures cluster on first opens in a cold process, not on a title or an
 * entry point. The decisive evidence is the crash trace itself, so this
 * test pins two things in source (the ViewModel needs Hilt repositories
 * to construct, like SearchCrashGuardTest):
 *
 * 1. The one unguarded suspend call — `settingsRepository.kidsModeActive
 *    .first()` in `loadDetail` — is wrapped in `runCatching` with a
 *    `false` default, so a DataStore read failure degrades to "kids mode
 *    off" instead of escaping the launch uncaught and killing the process.
 * 2. The trace reader the diagnosis depends on exists: Telemetry exposes
 *    `readLastCrashTrace`, the debug Diagnostics ViewModel loads it into
 *    its state, and the Diagnostics screen shows it under a Crash tab —
 *    so the next crash on a device the owner holds is readable from
 *    Settings > Diagnostics without adb. Its absence after a silent
 *    vanish is itself the answer (native crash, OOM, or a system kill).
 */
class DetailCrashGuardTest {

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) return@lazy dir
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    private fun source(path: String): String {
        val file = File(repoRoot, path)
        assertTrue("missing source file: ${file.path}", file.isFile)
        return file.readText()
    }

    @Test
    fun `the kids-mode read on the Detail-open path never escapes uncaught`() {
        val detail = source(
            "App/app/src/main/kotlin/com/raddstream/app/viewmodel/DetailViewModel.kt",
        )
        assertTrue(
            "kidsModeActive.first() must be wrapped in runCatching",
            "runCatching { settingsRepository.kidsModeActive.first() }" in detail,
        )
        // Exactly one kids-mode read exists on the open path, and it is the
        // wrapped one above — no bare call may remain anywhere in the file.
        assertEquals(
            "one kidsModeActive.first() call in total",
            1,
            Regex("kidsModeActive\\.first\\(\\)").findAll(detail).count(),
        )
    }

    @Test
    fun `the crash trace is readable from the debug Diagnostics screen`() {
        val telemetry = source(
            "App/app/src/main/kotlin/com/raddstream/app/data/Telemetry.kt",
        )
        assertTrue(
            "Telemetry must expose a last-crash reader",
            "fun readLastCrashTrace" in telemetry,
        )
        val viewModel = source(
            "App/app/src/debug/kotlin/com/raddstream/app/diagnostics/debug/DiagnosticsViewModel.kt",
        )
        assertTrue(
            "the Diagnostics ViewModel must load the trace into state",
            "Telemetry.readLastCrashTrace(appContext)" in viewModel,
        )
        assertTrue(
            "the Diagnostics ViewModel must offer a copy action for the trace",
            "fun copyCrashTrace()" in viewModel,
        )
        val screen = source(
            "App/app/src/debug/kotlin/com/raddstream/app/diagnostics/debug/DiagnosticsScreen.kt",
        )
        assertTrue(
            "the Diagnostics screen must have a Crash tab",
            "diagnostics_tab_crash" in screen,
        )
        assertTrue(
            "the Crash tab must render the trace from state",
            "lastCrashTrace" in screen,
        )
    }
}
