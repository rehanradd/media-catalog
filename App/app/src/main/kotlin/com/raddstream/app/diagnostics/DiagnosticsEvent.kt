package com.raddstream.app.diagnostics

/**
 * One recorded moment in the app, for the debug-only diagnostics screen
 * (APP-13). Every field is already redacted when the event is built — see
 * the debug implementation's redactor and recorder: a token,
 * key, auth header or signed URL must never reach an event, because events
 * are shown on screen and can be exported and shared.
 *
 * Severity criteria (explicit, so call sites cannot drift):
 * - ERROR: an operation failed (exception, HTTP 5xx, failed play, failed
 *   download, player give-up).
 * - WARNING: degraded but continuing (slow call over [SLOW_API_MS], player
 *   failover to a sibling, buffer failure, download retry, resolver fallback).
 * - INFO: everything else (navigation, successful calls, successful
 *   plays/downloads, resolver choice).
 *
 * Taxonomy (review N-1): `timestampNs` is the monotonic capture time
 * (`System.nanoTime()` at the record site); `sessionId` is the per-process
 * session UUID stamped by the recorder; `id` is the event id. The typed
 * fields below are the payload — no untyped map, so a typo cannot silently
 * invent a field.
 */
data class DiagnosticsEvent(
    val id: Long,
    val timestampMs: Long,
    val type: DiagnosticsType,
    val severity: DiagnosticsSeverity,
    /** One line: what happened. Redacted at record time. */
    val summary: String,
    /** Extra lines, or null. Redacted at record time. */
    val detail: String? = null,
    /** Route or screen name, e.g. "detail/123". Never a URL with a query. */
    val screen: String? = null,
    /** Viewer-visible title, or null. Never an account identifier. */
    val title: String? = null,
    /** Quality language only, e.g. "1080p · Fast direct". */
    val quality: String? = null,
    /**
     * Host or service name, when the call site actually knows one. Null in
     * most places: stream handles are opaque by design, so the player and the
     * download path know no hoster name. API events put the catalog server
     * host here.
     */
    val host: String? = null,
    /** The error in viewer words, or null. Never a raw exception message. */
    val error: String? = null,
    /** How long the operation took, when measured. */
    val durationMs: Long? = null,
    /** Monotonic capture time, `System.nanoTime()` at the record site. */
    val timestampNs: Long = 0L,
    /** The per-process session UUID, stamped by the recorder. */
    val sessionId: String = "",
) {
    /** A problem is anything that is not plain information. */
    val isProblem: Boolean get() = severity != DiagnosticsSeverity.INFO
}

enum class DiagnosticsType {
    NAVIGATION,
    API,
    RESOLVER,
    PLAYBACK,
    DOWNLOAD,
    APP,
}

enum class DiagnosticsSeverity {
    INFO,
    WARNING,
    ERROR,
}

/** An API call slower than this is a WARNING, not an INFO (see [DiagnosticsEvent]). */
const val SLOW_API_MS: Long = 3_000L
