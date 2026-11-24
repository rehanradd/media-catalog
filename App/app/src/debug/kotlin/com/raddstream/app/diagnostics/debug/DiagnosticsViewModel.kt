package com.raddstream.app.diagnostics.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raddstream.app.BuildConfig
import com.raddstream.app.R
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.diagnostics.Diagnostics
import com.raddstream.app.diagnostics.DiagnosticsBus
import com.raddstream.app.diagnostics.DiagnosticsEvent
import com.raddstream.app.diagnostics.DiagnosticsSeverity
import com.raddstream.app.diagnostics.DiagnosticsType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The diagnostics screen's ViewModel (APP-13, debug-only). Lives in
 * `src/debug`; Hilt injects the debug [Diagnostics] implementation.
 *
 * The event stream itself lives in the injected [diagnostics] and is exposed
 * here as [events] so the screen reads everything through the ViewModel
 * (App/AGENTS.md §13.6: state via StateFlow/ViewModel). Filter state lives
 * here; the actual filtering is the pure [filterEvents] function, computed
 * in the screen with `remember` so it stays testable without Compose
 * (review N-5, background filtering: a filter pass over at most 5,000 short
 * strings is sub-millisecond and cannot block a frame — the added async
 * complexity would make the filtered flows untestable deterministically
 * under `runTest`, so the screen keeps the `remember` computation and the
 * `LazyColumn` does the heavy lifting).
 *
 * Export mirrors [com.raddstream.app.resolve.ResolveTestViewModel] with one
 * deliberate difference (review N-4): the Markdown summary is shared as a
 * FILE through the debug-only FileProvider, not as pasted text — the JSONL
 * file rides in the cache dir next to it, and Copy puts the JSONL on the
 * clipboard.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: ServerSettingsRepository,
    private val diagnostics: Diagnostics = DiagnosticsBus.instance,
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val selectedTypes: Set<DiagnosticsType> = emptySet(),
        val selectedSeverities: Set<DiagnosticsSeverity> = emptySet(),
        val sessionInfo: DiagnosticsSessionInfo,
        val notice: String? = null,
        /** How many oldest events were evicted once the buffer filled (R-1). */
        val droppedCount: Long = 0L,
    )

    /** The live event stream, newest last. */
    val events: StateFlow<List<DiagnosticsEvent>> = diagnostics.events

    private val _uiState = MutableStateFlow(
        UiState(
            sessionInfo = buildSessionInfo(
                appContext,
                null,
                diagnostics.sessionStartedMs,
                sessionId = diagnostics.sessionId,
                clockOffsetMs = diagnostics.clockOffsetMs,
            ),
        ),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            diagnostics.loadPersisted(appContext)
            val serverUrl = settings.serverUrl.first()
            val remoteConfig = readRemoteConfig()
            _uiState.update { state ->
                state.copy(
                    sessionInfo = buildSessionInfo(
                        appContext,
                        serverUrl,
                        diagnostics.sessionStartedMs,
                        sessionId = diagnostics.sessionId,
                        clockOffsetMs = diagnostics.clockOffsetMs,
                        counters = diagnostics.counters.value,
                        remoteConfig = remoteConfig,
                    ),
                )
            }
            // The session start flips 0 → real the first time anything is
            // recorded; keep the header honest without rebuilding the
            // (ConnectivityManager-reading) info on every event.
            var lastSessionStart = diagnostics.sessionStartedMs
            diagnostics.events.collect {
                val started = diagnostics.sessionStartedMs
                if (started != lastSessionStart) {
                    lastSessionStart = started
                    _uiState.update { state ->
                        state.copy(
                            sessionInfo = state.sessionInfo.copy(
                                sessionStartedMs = started,
                            ),
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            diagnostics.counters.collect { counters ->
                _uiState.update { state ->
                    state.copy(sessionInfo = state.sessionInfo.copy(counters = counters))
                }
            }
        }
        viewModelScope.launch {
            diagnostics.droppedCount.collect { dropped ->
                _uiState.update { it.copy(droppedCount = dropped) }
            }
        }
    }

    /** The active remote-config switches (review N-7), read from the repository. */
    private suspend fun readRemoteConfig(): Map<String, Boolean> {
        return mapOf(
            "maintenance" to settings.maintenanceMode.first(),
            "downloads" to settings.downloadsFeatureEnabled.first(),
            "sharing" to settings.sharingFeatureEnabled.first(),
            "search" to settings.searchFeatureEnabled.first(),
            "pure_cinema" to settings.pureCinemaFeatureEnabled.first(),
            "cast" to settings.castFeatureEnabled.first(),
            "quality_gate" to settings.qualityGateEnabled.first(),
            "ads" to settings.adsEnabled.first(),
        )
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun toggleType(type: DiagnosticsType) {
        _uiState.update { state ->
            val next = state.selectedTypes.toMutableSet()
            if (!next.add(type)) next.remove(type)
            state.copy(selectedTypes = next)
        }
    }

    fun toggleSeverity(severity: DiagnosticsSeverity) {
        _uiState.update { state ->
            val next = state.selectedSeverities.toMutableSet()
            if (!next.add(severity)) next.remove(severity)
            state.copy(selectedSeverities = next)
        }
    }

    fun clearFilters() {
        _uiState.update {
            it.copy(query = "", selectedTypes = emptySet(), selectedSeverities = emptySet())
        }
    }

    /** Empty the recorder. The data is ephemeral by design; this is one tap. */
    fun clearEvents() {
        diagnostics.clear()
    }

    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    /** Persist the buffer to the private file (review R-1). */
    fun persist() {
        diagnostics.persist(appContext)
    }

    /** The shareable Markdown form for the current events. Pure. */
    fun buildExportText(events: List<DiagnosticsEvent>): String {
        return DiagnosticsExport.buildText(_uiState.value.sessionInfo, events)
    }

    /** The machine-readable JSONL form for the current events. Pure. */
    fun buildExportJsonLines(events: List<DiagnosticsEvent>): String {
        return DiagnosticsExport.buildJsonLines(_uiState.value.sessionInfo, events)
    }

    /**
     * Writes the Markdown summary and the JSONL file to the app cache, then
     * shares the Markdown FILE through the debug-only FileProvider (review
     * N-4) with `FLAG_GRANT_READ_URI_PERMISSION`. The Markdown text rides
     * as `EXTRA_TEXT` as well, so a target that ignores the stream still
     * gets the summary.
     */
    fun exportResults(events: List<DiagnosticsEvent>) {
        if (events.isEmpty()) return
        val text = buildExportText(events)
        val jsonLines = buildExportJsonLines(events)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(Date(_uiState.value.sessionInfo.sessionStartedMs.takeIf { it > 0 }
                ?: System.currentTimeMillis()))
        val markdownFile: File
        try {
            File(appContext.cacheDir, "diagnostics_$stamp.jsonl").writeText(jsonLines)
            markdownFile = File(appContext.cacheDir, "diagnostics_${stamp}_summary.md")
            markdownFile.writeText(text)
        } catch (_: Exception) {
            _uiState.update { it.copy(notice = appContext.getString(R.string.diagnostics_export_failed)) }
            return
        }
        val uri = try {
            FileProvider.getUriForFile(
                appContext,
                "${BuildConfig.APPLICATION_ID}.diagnostics",
                markdownFile,
            )
        } catch (_: Exception) {
            _uiState.update { it.copy(notice = appContext.getString(R.string.diagnostics_export_failed)) }
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_SUBJECT, appContext.getString(R.string.diagnostics_share_subject))
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            val chooser = Intent.createChooser(intent, appContext.getString(R.string.diagnostics_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(chooser)
        } catch (_: Exception) {
            _uiState.update { it.copy(notice = appContext.getString(R.string.diagnostics_export_failed)) }
        }
    }

    fun copyResultsJsonl(events: List<DiagnosticsEvent>) {
        if (events.isEmpty()) return
        val jsonLines = buildExportJsonLines(events)
        try {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("diagnostics", jsonLines))
            _uiState.update { it.copy(notice = appContext.getString(R.string.diagnostics_copied)) }
        } catch (_: Exception) {
            _uiState.update { it.copy(notice = appContext.getString(R.string.diagnostics_export_failed)) }
        }
    }
}
