package com.raddstream.app.diagnostics

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.brand.Brand
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.diagnostics.debug.DebugDiagnostics
import com.raddstream.app.diagnostics.debug.DiagnosticsViewModel
import com.raddstream.app.diagnostics.debug.serverHostOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The diagnostics ViewModel: filter state, export builders, clipboard copy,
 * and the cache-dir export files. The recorder is a process-wide singleton,
 * so every test clears it before and after.
 *
 * Robolectric: the ViewModel reads the stored server URL from DataStore in
 * `init` (the same construction `ApiProviderBaseUrlTest` uses), and the
 * clipboard + cache-dir writes need the Android runtime.
 *
 * Two disciplines the session-info tests depend on. (1) The DataStore file
 * is SHARED across the test methods in this class, so a URL one test saves
 * is still stored for the next: setUp clears it (saving "" reads back as
 * "no URL" — a blank value falls through to the brand default). The clear
 * also warms the DataStore: the first read in the JVM pays the cold disk
 * load, and after it the ViewModel's `first()` calls answer from memory.
 * NOTE the cleared state is NOT "unknown": with no saved URL the
 * repository returns `Brand.defaultServerUrl`
 * (`https://raddstream.duckdns.org` in this build), so a test that starts
 * from the cleared state sees the default host — "unknown" only when even
 * the brand ships no default (see the brand-default test).
 * (2) The ViewModel's `init` reads DataStore through `first()` calls, and
 * DataStore's reads run on REAL IO threads — no virtual scheduler can
 * drive them. `runTest`'s `advanceUntilIdle()` drives only the runTest
 * scheduler, and pumping the Main test dispatcher does not wait for real
 * threads either, so a session-info test that pumps and asserts reads the
 * pre-init session info whenever the runner is slow (CI round 5: all 3
 * session-info tests; round 7: 2 of 3 — the third only passed because
 * "unknown" is also the pre-init value, i.e. vacuously). The session-info
 * tests therefore run on REAL dispatchers — the house pattern in
 * `ApiProviderBaseUrlTest` — setting Main to `Dispatchers.Default` and
 * waiting, event-driven, for the session-info update to land
 * (`uiState.first { ... }` with a 10 s timeout), never by pumping a
 * virtual scheduler and hoping the IO finished.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DiagnosticsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var settings: ServerSettingsRepository
    private lateinit var viewModel: DiagnosticsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        DebugDiagnostics.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        settings = ServerSettingsRepository(context)
        // Clear any URL a previous test saved (shared DataStore file) and
        // warm the DataStore, so the ViewModel's init reads answer from
        // memory instead of racing the cold disk load.
        runBlocking { settings.saveServerUrl("") }
        viewModel = DiagnosticsViewModel(context, settings, DebugDiagnostics)
    }

    @After
    fun tearDown() {
        DebugDiagnostics.resetForTests()
        Dispatchers.resetMain()
    }

    private fun sampleEvents() = listOf(
        DiagnosticsEvent(
            id = 1L, timestampMs = 1_700_000_000_000L,
            type = DiagnosticsType.API, severity = DiagnosticsSeverity.INFO,
            summary = "GET /api/v1/posts → 200 in 90ms",
        ),
        DiagnosticsEvent(
            id = 2L, timestampMs = 1_700_000_001_000L,
            type = DiagnosticsType.PLAYBACK, severity = DiagnosticsSeverity.ERROR,
            summary = "Playback failed: ERROR_CODE_IO",
            title = "Salaar", error = "network failed",
        ),
    )

    @Test
    fun `filter state starts empty and toggles`() {
        val initial = viewModel.uiState.value
        assertEquals("", initial.query)
        assertTrue(initial.selectedTypes.isEmpty())
        assertTrue(initial.selectedSeverities.isEmpty())

        viewModel.setQuery("salaar")
        assertEquals("salaar", viewModel.uiState.value.query)

        viewModel.toggleType(DiagnosticsType.PLAYBACK)
        assertEquals(setOf(DiagnosticsType.PLAYBACK), viewModel.uiState.value.selectedTypes)
        viewModel.toggleType(DiagnosticsType.PLAYBACK)
        assertTrue(viewModel.uiState.value.selectedTypes.isEmpty())

        viewModel.toggleSeverity(DiagnosticsSeverity.ERROR)
        assertEquals(setOf(DiagnosticsSeverity.ERROR), viewModel.uiState.value.selectedSeverities)

        viewModel.clearFilters()
        assertEquals("", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.selectedTypes.isEmpty())
        assertTrue(viewModel.uiState.value.selectedSeverities.isEmpty())
    }

    @Test
    fun `session info loads the stored server host`() {
        // Real dispatchers (see the class KDoc, discipline 2): the save
        // completes for real, the fresh ViewModel's init reads DataStore
        // on real IO threads, and the wait below lands event-driven.
        runBlocking { settings.saveServerUrl("https://catalog.test") }
        Dispatchers.setMain(Dispatchers.Default)
        val fresh = DiagnosticsViewModel(context, settings, DebugDiagnostics)
        val info = runBlocking {
            withTimeout(10_000) {
                fresh.uiState.first { it.sessionInfo.serverHost == "catalog.test" }
            }.sessionInfo
        }
        assertEquals("catalog.test", info.serverHost)
    }

    @Test
    fun `session info without a stored URL reads the brand default host`() {
        // Wait for init to finish (remoteConfig populated) before
        // asserting — otherwise "unknown" would pass vacuously, since it
        // is also the pre-init value. The expectation is the brand
        // default, NOT "unknown": with no saved URL the repository
        // returns Brand.defaultServerUrl (ServerSettingsRepository
        // KDoc), so the screen shows its host. Deriving it from Brand
        // keeps this true whatever the brand ships — if the brand ever
        // ships no default, serverHostOf yields "unknown" and this
        // still passes. (Round 8 proved the old "unknown" expectation
        // wrong: init ran and the host was raddstream.duckdns.org.)
        Dispatchers.setMain(Dispatchers.Default)
        val fresh = DiagnosticsViewModel(context, settings, DebugDiagnostics)
        val info = runBlocking {
            withTimeout(10_000) {
                fresh.uiState.first { it.sessionInfo.remoteConfig.size == 8 }
            }.sessionInfo
        }
        assertEquals(serverHostOf(Brand.defaultServerUrl), info.serverHost)
    }

    @Test
    fun `session info carries the session id and the remote-config keys`() {
        Dispatchers.setMain(Dispatchers.Default)
        val fresh = DiagnosticsViewModel(context, settings, DebugDiagnostics)
        val info = runBlocking {
            withTimeout(10_000) {
                fresh.uiState.first { it.sessionInfo.remoteConfig.size == 8 }
            }.sessionInfo
        }
        assertEquals(DebugDiagnostics.sessionId, info.sessionId)
        assertTrue(info.sessionId.isNotBlank())
        assertEquals(
            setOf("maintenance", "downloads", "sharing", "search", "pure_cinema", "cast", "quality_gate", "ads"),
            info.remoteConfig.keys,
        )
    }

    @Test
    fun `buildExportText is Markdown with the header and the events`() {
        val text = viewModel.buildExportText(sampleEvents())
        // Brand.name is identity.name ("RADD Stream"), not the short name.
        assertTrue(text.contains("# RADD Stream diagnostics"))
        assertTrue(text.contains("- Events: 2 total · 1 errors · 0 warnings"))
        assertTrue(text.contains("Playback failed: ERROR_CODE_IO"))
    }

    @Test
    fun `buildExportJsonLines carries one JSON object per event`() {
        val jsonl = viewModel.buildExportJsonLines(sampleEvents())
        assertTrue(jsonl.contains("\"summary\": \"Playback failed: ERROR_CODE_IO\""))
        assertTrue(jsonl.contains("\"severity\": \"error\""))
        assertEquals(3, jsonl.trim().split("\n").size)
    }

    @Test
    fun `copyResultsJsonl puts the JSONL on the clipboard`() {
        viewModel.copyResultsJsonl(sampleEvents())
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        assertTrue(clip != null)
        val text = clip!!.getItemAt(0).text.toString()
        assertTrue(text.contains("\"summary\": \"Playback failed: ERROR_CODE_IO\""))
        assertEquals("Diagnostics JSONL copied.", viewModel.uiState.value.notice)
    }

    @Test
    fun `exportResults writes the JSONL and Markdown files to the cache dir`() {
        viewModel.exportResults(sampleEvents())
        val files = context.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("diagnostics_") }
        assertTrue(files.any { it.name.endsWith(".jsonl") })
        assertTrue(files.any { it.name.endsWith("_summary.md") })
        val jsonl = files.first { it.name.endsWith(".jsonl") }.readText()
        assertTrue(jsonl.contains("\"summary\": \"Playback failed: ERROR_CODE_IO\""))
        val markdown = files.first { it.name.endsWith("_summary.md") }.readText()
        assertTrue(markdown.contains("# RADD Stream diagnostics"))
    }

    @Test
    fun `exportResults and copyResultsJsonl do nothing when there are no events`() {
        viewModel.exportResults(emptyList())
        viewModel.copyResultsJsonl(emptyList())
        val files = context.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("diagnostics_") }
        assertTrue(files.isEmpty())
        assertEquals(null, viewModel.uiState.value.notice)
    }

    @Test
    fun `clearEvents empties the recorder`() {
        DebugDiagnostics.record(
            DiagnosticsType.API, DiagnosticsSeverity.INFO, "GET /api/v1/posts → 200 in 90ms",
        )
        assertFalse(DebugDiagnostics.snapshot().isEmpty())
        viewModel.clearEvents()
        assertTrue(DebugDiagnostics.snapshot().isEmpty())
    }

    @Test
    fun `events flow reflects the recorder`() = runTest {
        // The recorder is process-wide and shared with the whole suite, so
        // this test asserts PRESENCE, not an exact count: the event recorded
        // here must show up in the ViewModel's flow. (An exact-count assert
        // failed in CI 45322860981 — expected 1, was 2 — because another
        // test's event was in the shared buffer; the count itself is pinned
        // in DebugDiagnosticsTest, which owns the recorder's discipline.)
        DebugDiagnostics.resetForTests()
        DebugDiagnostics.record(
            DiagnosticsType.API, DiagnosticsSeverity.INFO, "GET /api/v1/posts → 200 in 90ms",
        )
        advanceUntilIdle()
        val events = viewModel.events.value
        assertTrue(
            "recorded event missing from the flow: $events",
            events.any { it.summary == "GET /api/v1/posts → 200 in 90ms" },
        )
    }
}
