package com.raddstream.app.diagnostics

import android.content.Context
import androidx.navigation.NavGraphBuilder
import kotlinx.coroutines.flow.StateFlow

/**
 * The diagnostics contract (APP-13, review B-2). This interface is the ONLY
 * diagnostics surface `src/main` knows: every record site, the navigation
 * graph, the Settings entry and `Telemetry` talk to a [Diagnostics], never
 * to an implementation.
 *
 * Two implementations, one per build type:
 * - release: [NoOpDiagnostics] — every call is a no-op, `isEnabled()` is
 *   false, no screen is ever registered.
 * - debug: the debug implementation (in `src/debug`) — the ring buffer, the
 *   redactor, the screen, the export. A single debug-only Hilt module
 *   provides it; release has no binding at all, because the only
 *   `@Inject` consumer is the debug-only ViewModel. The Application
 *   installs it into [DiagnosticsBus] reflectively, debug builds only.
 *
 * Redaction contract (review B-1): `record*` NEVER stores raw input. The
 * debug implementation sanitizes inside `record()` — sanitize-inside is the
 * only shape possible under B-2, because `src/main` cannot call the
 * debug-only redactor — and it is strictly stronger than sanitize-at-site,
 * because a write-site cannot forget it.
 */
interface Diagnostics {

    /** False in release (NoOp); true in debug. The navigation gate. */
    fun isEnabled(): Boolean

    /** The live event stream, newest last. Empty in release. */
    val events: StateFlow<List<DiagnosticsEvent>>

    /** The per-process session UUID (review R-3). Empty in release. */
    val sessionId: String

    /** Process start as the recorder observes it (its first record). */
    val sessionStartedMs: Long

    /**
     * Server-clock offset in ms (`Date` header minus device time, review
     * R-4). 0 when no API response has carried a `Date` header yet.
     */
    val clockOffsetMs: Long

    /** Aggregate counters fed by [recordCounter] (review N-6). */
    val counters: StateFlow<Map<String, Long>>

    /** How many oldest events were evicted once the buffer filled (R-1). */
    val droppedCount: StateFlow<Long>

    /** Record one event. Sanitized inside; never throws. */
    fun record(event: DiagnosticsEvent)

    /**
     * Record one API call. The URL is redacted inside (media-pattern and
     * token-bearing URLs drop in full); `dateHeader` is the response `Date`
     * header when one arrived, for the R-4 clock offset.
     */
    fun recordApi(
        method: String,
        url: String,
        status: Int?,
        durationMs: Long,
        failure: String? = null,
        dateHeader: String? = null,
    )

    /** Record a navigation: the destination route, query stripped. */
    fun recordNavigation(route: String?)

    /** Record a playback attempt, error, or failover hop. */
    fun recordPlayback(
        severity: DiagnosticsSeverity,
        summary: String,
        title: String? = null,
        quality: String? = null,
        detail: String? = null,
        error: String? = null,
        durationMs: Long? = null,
    )

    /** Record a download outcome (completion or failure). */
    fun recordDownload(
        severity: DiagnosticsSeverity,
        summary: String,
        title: String? = null,
        quality: String? = null,
        detail: String? = null,
        error: String? = null,
    )

    /**
     * Feed one aggregate-counter increment (review N-6 — `Telemetry`
     * counters). The debug implementation keeps a totals map for the Session
     * tab and records one low-frequency APP INFO event per increment.
     */
    fun recordCounter(name: String, value: Long = 1L)

    /**
     * Register the diagnostics destination on [builder], or do nothing
     * (release). Called by the navigation graph unconditionally — the
     * implementation decides.
     */
    fun addDestination(builder: NavGraphBuilder, onBackClick: () -> Unit)

    /** Empty the buffer. */
    fun clear()

    /** A copy of the buffer, newest last. */
    fun snapshot(): List<DiagnosticsEvent>

    /**
     * Persist the buffer to a private app-internal file (review R-1).
     * Called only on graceful shutdown (the screen leaving); a no-op in
     * release.
     */
    fun persist(context: Context)

    /** Rehydrate a persisted buffer, if one exists. No-op in release. */
    fun loadPersisted(context: Context)
}
