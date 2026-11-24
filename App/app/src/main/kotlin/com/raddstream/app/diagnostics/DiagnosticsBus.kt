package com.raddstream.app.diagnostics

import android.content.Context
import androidx.navigation.NavGraphBuilder
import kotlinx.coroutines.flow.StateFlow

/**
 * The process-wide diagnostics holder (APP-13, review B-2).
 *
 * Why a holder and not constructor injection everywhere: the record sites
 * are places Hilt cannot reach — `PlaybackService` is a plain
 * `MediaSessionService`, the OkHttp interceptor is a lambda built once per
 * client, and `Telemetry` is a static object. `Telemetry`'s own KDoc says
 * exactly this about its counters. One volatile instance, read everywhere,
 * written in exactly one place: `RaddStreamApplication.onCreate`, which
 * installs the debug implementation reflectively in debug builds; in
 * release the bus keeps [NoOpDiagnostics].
 *
 * Before install — and in any build without the debug source set — the
 * instance is [NoOpDiagnostics], so every record call is a no-op and
 * nothing is ever stored. The review's call shape is literal:
 * `DiagnosticsBus.record(...)`.
 */
object DiagnosticsBus {

    /** Set once by the Application; tests swap it and swap it back. */
    @Volatile
    var instance: Diagnostics = NoOpDiagnostics
        private set

    /** The Application's install step. Not for call sites. */
    fun install(diagnostics: Diagnostics) {
        instance = diagnostics
    }

    fun isEnabled(): Boolean = instance.isEnabled()

    val events: StateFlow<List<DiagnosticsEvent>> get() = instance.events

    val sessionId: String get() = instance.sessionId

    val sessionStartedMs: Long get() = instance.sessionStartedMs

    val clockOffsetMs: Long get() = instance.clockOffsetMs

    val counters: StateFlow<Map<String, Long>> get() = instance.counters

    val droppedCount: StateFlow<Long> get() = instance.droppedCount

    fun record(event: DiagnosticsEvent) = instance.record(event)

    fun recordApi(
        method: String,
        url: String,
        status: Int?,
        durationMs: Long,
        failure: String? = null,
        dateHeader: String? = null,
    ) = instance.recordApi(method, url, status, durationMs, failure, dateHeader)

    fun recordNavigation(route: String?) = instance.recordNavigation(route)

    fun recordPlayback(
        severity: DiagnosticsSeverity,
        summary: String,
        title: String? = null,
        quality: String? = null,
        detail: String? = null,
        error: String? = null,
        durationMs: Long? = null,
    ) = instance.recordPlayback(severity, summary, title, quality, detail, error, durationMs)

    fun recordDownload(
        severity: DiagnosticsSeverity,
        summary: String,
        title: String? = null,
        quality: String? = null,
        detail: String? = null,
        error: String? = null,
    ) = instance.recordDownload(severity, summary, title, quality, detail, error)

    fun recordCounter(name: String, value: Long = 1L) = instance.recordCounter(name, value)

    fun addDestination(builder: NavGraphBuilder, onBackClick: () -> Unit) =
        instance.addDestination(builder, onBackClick)

    fun clear() = instance.clear()

    fun snapshot(): List<DiagnosticsEvent> = instance.snapshot()

    fun persist(context: Context) = instance.persist(context)

    fun loadPersisted(context: Context) = instance.loadPersisted(context)
}
