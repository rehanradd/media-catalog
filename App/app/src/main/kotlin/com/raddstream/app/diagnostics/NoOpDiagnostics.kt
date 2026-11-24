package com.raddstream.app.diagnostics

import android.content.Context
import androidx.navigation.NavGraphBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The release implementation of [Diagnostics] (review B-2): every call is a
 * no-op, `isEnabled()` is false, no destination is ever registered, nothing
 * is stored. This is the object Hilt provides in release builds, and the
 * object [DiagnosticsBus] holds before the Application installs the real
 * one — so a record call that runs before install, or in a build without
 * the debug source set, costs one dead call and records nothing.
 */
object NoOpDiagnostics : Diagnostics {

    override fun isEnabled(): Boolean = false

    override val events: StateFlow<List<DiagnosticsEvent>> =
        MutableStateFlow(emptyList<DiagnosticsEvent>()).asStateFlow()

    override val sessionId: String = ""

    override val sessionStartedMs: Long = 0L

    override val clockOffsetMs: Long = 0L

    override val counters: StateFlow<Map<String, Long>> =
        MutableStateFlow(emptyMap<String, Long>()).asStateFlow()

    override val droppedCount: StateFlow<Long> =
        MutableStateFlow(0L).asStateFlow()

    override fun record(event: DiagnosticsEvent) = Unit

    override fun recordApi(
        method: String,
        url: String,
        status: Int?,
        durationMs: Long,
        failure: String?,
        dateHeader: String?,
    ) = Unit

    override fun recordNavigation(route: String?) = Unit

    override fun recordPlayback(
        severity: DiagnosticsSeverity,
        summary: String,
        title: String?,
        quality: String?,
        detail: String?,
        error: String?,
        durationMs: Long?,
    ) = Unit

    override fun recordDownload(
        severity: DiagnosticsSeverity,
        summary: String,
        title: String?,
        quality: String?,
        detail: String?,
        error: String?,
    ) = Unit

    override fun recordCounter(name: String, value: Long) = Unit

    override fun addDestination(builder: NavGraphBuilder, onBackClick: () -> Unit) = Unit

    override fun clear() = Unit

    override fun snapshot(): List<DiagnosticsEvent> = emptyList()

    override fun persist(context: Context) = Unit

    override fun loadPersisted(context: Context) = Unit
}
