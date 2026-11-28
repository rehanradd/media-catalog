package com.raddstream.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Search-crash fix (2026-11-28): typing a query in Search crashed the app
 * 2 of 2 times on Appetize ("Radd keeps stopping"). The typed-query path
 * itself has no throwing operation — every layer is guarded — but two
 * unguarded spots sat on it, and the crash left no trace to read:
 *
 * 1. `recordRecent` fires from `onPageMeta` on every first page, ~0.5s
 *    after the first keystrokes, while the viewer is still typing. Its
 *    `viewModelScope.launch { settingsRepository.saveSearchRecents(next) }`
 *    had no try/catch, so a DataStore write failure escaped the launch
 *    uncaught and killed the process. The same unguarded one-liner existed
 *    in `removeRecent`, `saveCurrentFilters` and `removeSavedFilter`.
 * 2. `searchWorksV2` / `browseWorksV2` caught only `HttpException` /
 *    `IOException`, so a Gson `JsonSyntaxException` (a RuntimeException)
 *    from a malformed row escaped to callers that are not inside a catch.
 * 3. `Telemetry.installCrashHandler` only counted crashes; it never kept
 *    the stack trace, so the Appetize crash was undiagnosable.
 *
 * The ViewModel needs Hilt repositories to construct, so — like
 * `SearchFilterStateTest` in the PlayerBackExitTest pattern — these are
 * drift tests over the source: the guards are pinned here, and the
 * behaviour is proven by the guards the code carries.
 */
class SearchCrashGuardTest {

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) return@lazy dir
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    private val viewModelSource: String by lazy {
        val file = File(
            repoRoot,
            "App/app/src/main/kotlin/com/raddstream/app/viewmodel/SearchViewModel.kt",
        )
        assertTrue("SearchViewModel.kt is missing at ${file.path}", file.isFile)
        file.readText()
    }

    private val repositorySource: String by lazy {
        val file = File(
            repoRoot,
            "App/app/src/main/kotlin/com/raddstream/app/repository/CatalogRepository.kt",
        )
        assertTrue("CatalogRepository.kt is missing at ${file.path}", file.isFile)
        file.readText()
    }

    private val telemetrySource: String by lazy {
        val file = File(
            repoRoot,
            "App/app/src/main/kotlin/com/raddstream/app/data/Telemetry.kt",
        )
        assertTrue("Telemetry.kt is missing at ${file.path}", file.isFile)
        file.readText()
    }

    private fun functionBody(source: String, name: String, nextMarker: String): String {
        val start = source.indexOf(name)
        assertTrue("source no longer declares $name", start >= 0)
        val end = source.indexOf(nextMarker, start)
        assertTrue("source no longer has $nextMarker after $name", end > start)
        return source.substring(start, end)
    }

    @Test
    fun `recordRecent never crashes on a failed persist`() {
        val body = functionBody(
            viewModelSource,
            "private fun recordRecent(query: String)",
            "private companion object",
        )
        assertTrue(
            "recordRecent must guard its DataStore write with runCatching — " +
                "an uncaught throw in this launch kills the process while " +
                "the viewer is still typing",
            body.contains("runCatching { settingsRepository.saveSearchRecents(next) }"),
        )
    }

    @Test
    fun `every settings persist launch in SearchViewModel is guarded`() {
        assertFalse(
            "no unguarded one-liner persist launch may remain — " +
                "viewModelScope.launch { settingsRepository.save... } " +
                "lets a DataStore failure kill the process",
            viewModelSource.contains("viewModelScope.launch { settingsRepository.save"),
        )
        val guarded = viewModelSource
            .split("runCatching { settingsRepository.save").size - 1
        assertEquals(
            "all four persist launches (recordRecent, removeRecent, " +
                "saveCurrentFilters, removeSavedFilter) must be runCatching-guarded",
            4, guarded,
        )
    }

    @Test
    fun `searchWorksV2 falls back on a parse failure but still propagates cancellation`() {
        val body = functionBody(
            repositorySource,
            "suspend fun searchWorksV2(",
            "suspend fun getWorkDetailV2(",
        )
        assertTrue(
            "searchWorksV2 must catch Exception — a Gson JsonSyntaxException " +
                "from a malformed row is a RuntimeException, not an IOException",
            body.contains("catch (_: Exception)"),
        )
        assertTrue(
            "searchWorksV2 must rethrow CancellationException before the " +
                "generic catch, or a cancelled search would silently return null",
            body.contains("catch (ex: CancellationException)") &&
                body.indexOf("catch (ex: CancellationException)") <
                body.indexOf("catch (_: Exception)") &&
                body.contains("throw ex"),
        )
    }

    @Test
    fun `browseWorksV2 falls back on a parse failure but still propagates cancellation`() {
        val body = functionBody(
            repositorySource,
            "suspend fun browseWorksV2(",
            "suspend fun searchWorksV2(",
        )
        assertTrue(
            "browseWorksV2 must catch Exception for the same reason as searchWorksV2",
            body.contains("catch (_: Exception)"),
        )
        assertTrue(
            "browseWorksV2 must rethrow CancellationException before the generic catch",
            body.contains("catch (ex: CancellationException)") &&
                body.indexOf("catch (ex: CancellationException)") <
                body.indexOf("catch (_: Exception)") &&
                body.contains("throw ex"),
        )
    }

    @Test
    fun `the crash handler persists the stack trace for the next crash`() {
        assertTrue(
            "installCrashHandler must persist the trace — the counter alone " +
                "never says what crashed",
            telemetrySource.contains("runCatching { persistCrashTrace(thread, error) }"),
        )
        assertTrue(
            "the trace file name must be a named constant, not a literal buried " +
                "in the handler",
            telemetrySource.contains("CRASH_TRACE_FILE"),
        )
    }
}
