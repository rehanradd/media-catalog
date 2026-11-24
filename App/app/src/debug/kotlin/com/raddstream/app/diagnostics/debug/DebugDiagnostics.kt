package com.raddstream.app.diagnostics.debug

import android.content.Context
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.raddstream.app.diagnostics.Diagnostics
import com.raddstream.app.diagnostics.DiagnosticsEvent
import com.raddstream.app.diagnostics.DiagnosticsSeverity
import com.raddstream.app.diagnostics.DiagnosticsType
import com.raddstream.app.diagnostics.SLOW_API_MS
import com.raddstream.app.ui.navigation.Screen
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The debug implementation of [Diagnostics] (APP-13, review B-2). Lives in
 * `src/debug`, so no release build compiles it — the release build gets
 * [com.raddstream.app.diagnostics.NoOpDiagnostics] instead.
 *
 * Why an object and not an injected repository: the record sites are places
 * Hilt cannot reach cheaply — an OkHttp interceptor lambda, a plain
 * `MediaSessionService`, a `DisposableEffect` listener — and the store must
 * exist before any screen is opened. One ring buffer, read everywhere,
 * written only through [record]; reached from `src/main` only through the
 * [com.raddstream.app.diagnostics.DiagnosticsBus] holder.
 *
 * What it holds: redacted structured events, newest last, at most
 * [MAX_EVENTS] (review R-1: 5,000). The buffer is persisted ONLY on
 * graceful shutdown ([persist], called when the diagnostics screen leaves)
 * to a private app-internal file (JSONL + GZIP); a process death clears
 * the in-memory buffer, and the only thing that ever leaves the phone is
 * the explicit export the viewer shares from the screen. No titles beyond
 * what the viewer is already looking at, no account identifiers, no install
 * id, no sync key, no URL query string, no playback handle.
 *
 * Redaction (review B-1): [record] sanitizes every free-text field FIRST
 * ([DiagnosticsRedactor.redactText] + truncate to 200 chars), then runs the
 * second-pass safety check: a field that still holds a credential value
 * becomes `***REDACTED-UNSAFE***` instead of being stored. Never throws and
 * never blocks the caller longer than a buffer append — recording must never
 * slow a hot path (notably the OkHttp interceptor, whose duration is
 * measured with `System.nanoTime()` BEFORE any recording happens).
 */
object DebugDiagnostics : Diagnostics {

    /** Ring-buffer cap (review R-1: ~5,000 events ≈ 2 MB). */
    const val MAX_EVENTS: Int = 5_000

    /** Private persisted buffer, written only on graceful shutdown (R-1). */
    const val PERSIST_FILE_NAME: String = "diagnostics.log.gz"

    private val lock = Any()
    private val buffer = ArrayDeque<DiagnosticsEvent>()
    private var nextId = 1L
    private val countersMap = mutableMapOf<String, Long>()
    private var dropped = 0L

    /**
     * The per-session UUID (review R-3): generated once per process, stamped
     * on every event, shown in the Session tab and the export header, so a
     * diagnostics export can be matched to a Crashlytics report by time and
     * version manually. It is NOT an install id and NOT an account id.
     */
    override val sessionId: String = UUID.randomUUID().toString()

    /**
     * Process start as the recorder observes it: the first [record] call.
     * Labelled exactly that way in the export header — it is NOT login time
     * and NOT install time.
     */
    @Volatile
    override var sessionStartedMs: Long = 0L
        private set

    /**
     * Server-clock offset in ms (review R-4): `Date` header minus device
     * time, from the most recent API response that carried one. Shown in the
     * Session tab; displayed event times are NOT adjusted (durations are
     * differences, so clock skew cannot affect slow-call detection).
     */
    @Volatile
    override var clockOffsetMs: Long = 0L
        private set

    private val _events = MutableStateFlow<List<DiagnosticsEvent>>(emptyList())
    override val events: StateFlow<List<DiagnosticsEvent>> = _events.asStateFlow()

    private val _counters = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val counters: StateFlow<Map<String, Long>> = _counters.asStateFlow()

    private val _droppedCount = MutableStateFlow(0L)
    override val droppedCount: StateFlow<Long> = _droppedCount.asStateFlow()

    override fun isEnabled(): Boolean = true

    /**
     * Record one event. Redacts every free-text field first, truncates to
     * 200 chars, then runs the second-pass safety check. Stamps the
     * recorder's own id, the session UUID, and a monotonic capture time.
     */
    override fun record(event: DiagnosticsEvent) {
        val safe = event.copy(
            id = 0L, // Replaced with the recorder's own sequence below.
            timestampNs = if (event.timestampNs != 0L) event.timestampNs else System.nanoTime(),
            sessionId = sessionId,
            summary = sanitize(event.summary),
            detail = event.detail?.let { sanitize(it) },
            title = event.title?.let { sanitize(it) },
            error = event.error?.let { sanitize(it) },
        )
        synchronized(lock) {
            if (sessionStartedMs == 0L) sessionStartedMs = event.timestampMs
            buffer.addLast(safe.copy(id = nextId++))
            while (buffer.size > MAX_EVENTS) {
                buffer.removeFirst()
                dropped++
            }
            _events.value = buffer.toList()
            _droppedCount.value = dropped
        }
    }

    /** Convenience record: timestamps with [System.currentTimeMillis]. */
    fun record(
        type: DiagnosticsType,
        severity: DiagnosticsSeverity,
        summary: String,
        detail: String? = null,
        screen: String? = null,
        title: String? = null,
        quality: String? = null,
        host: String? = null,
        error: String? = null,
        durationMs: Long? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        record(
            DiagnosticsEvent(
                id = 0L,
                timestampMs = timestampMs,
                type = type,
                severity = severity,
                summary = summary,
                detail = detail,
                screen = screen,
                title = title,
                quality = quality,
                host = host,
                error = error,
                durationMs = durationMs,
            ),
        )
    }

    /**
     * Record one API call from the OkHttp timing interceptor. The path is
     * shown without scheme/host/query credentials; the host goes in [host].
     * A media-pattern or token-bearing URL drops in full
     * (`***REDACTED***` as the path, no host). Resolve-path calls
     * (`/api/v2/play`, `/api/v2/resolve*`, `/api/v1/stream`) are type
     * RESOLVER — they are the client-visible half of every resolver
     * decision — everything else is type API. `dateHeader` is the response
     * `Date` header, for the R-4 clock offset.
     */
    override fun recordApi(
        method: String,
        url: String,
        status: Int?,
        durationMs: Long,
        failure: String?,
        dateHeader: String?,
    ) {
        if (dateHeader != null) noteDateHeader(dateHeader)
        val redacted = DiagnosticsRedactor.redactUrl(url)
        val host = if (redacted == DiagnosticsRedactor.REDACTED) null
        else runCatching { URI(redacted).host }.getOrNull()
        val path = displayPathOf(redacted)
        val isResolveCall = path.startsWith("/api/v2/play") ||
            path.startsWith("/api/v2/resolve") ||
            path.startsWith("/api/v1/stream")
        val type = if (isResolveCall) DiagnosticsType.RESOLVER else DiagnosticsType.API
        val severity = when {
            failure != null || (status != null && status >= 500) -> DiagnosticsSeverity.ERROR
            durationMs > SLOW_API_MS -> DiagnosticsSeverity.WARNING
            else -> DiagnosticsSeverity.INFO
        }
        val summary = buildString {
            append(method)
            append(' ')
            append(path)
            append(" → ")
            append(status?.toString() ?: "no response")
            append(" in ")
            append(durationMs)
            append("ms")
            if (durationMs > SLOW_API_MS) append(" (slow)")
        }
        record(
            type = type,
            severity = severity,
            summary = summary,
            detail = failure,
            host = host,
            durationMs = durationMs,
        )
    }

    /** Record a navigation: the destination route, query stripped. */
    override fun recordNavigation(route: String?) {
        val clean = route?.substringBefore('?')?.takeIf { it.isNotBlank() } ?: return
        record(
            type = DiagnosticsType.NAVIGATION,
            severity = DiagnosticsSeverity.INFO,
            summary = "Opened $clean",
            screen = clean,
        )
    }

    /** Record a playback attempt, error, or failover hop. */
    override fun recordPlayback(
        severity: DiagnosticsSeverity,
        summary: String,
        title: String?,
        quality: String?,
        detail: String?,
        error: String?,
        durationMs: Long?,
    ) {
        record(
            type = DiagnosticsType.PLAYBACK,
            severity = severity,
            summary = summary,
            detail = detail,
            screen = "player",
            title = title,
            quality = quality,
            error = error,
            durationMs = durationMs,
        )
    }

    /** Record a download outcome (completion or failure). */
    override fun recordDownload(
        severity: DiagnosticsSeverity,
        summary: String,
        title: String?,
        quality: String?,
        detail: String?,
        error: String?,
    ) {
        record(
            type = DiagnosticsType.DOWNLOAD,
            severity = severity,
            summary = summary,
            detail = detail,
            screen = "downloads",
            title = title,
            quality = quality,
            error = error,
        )
    }

    /**
     * Feed one aggregate-counter increment (review N-6 — `Telemetry`
     * counters). Keeps a totals map for the Session tab and records one
     * low-frequency APP INFO event per increment, so the timeline can show
     * them. Counters fire once per attempt/error/completion, never per tick.
     */
    override fun recordCounter(name: String, value: Long) {
        synchronized(lock) {
            countersMap[name] = (countersMap[name] ?: 0L) + value
            _counters.value = countersMap.toMap()
        }
        record(
            type = DiagnosticsType.APP,
            severity = DiagnosticsSeverity.INFO,
            summary = "Counter $name +$value",
        )
    }

    override fun addDestination(builder: NavGraphBuilder, onBackClick: () -> Unit) {
        builder.composable(Screen.Diagnostics.route) {
            DiagnosticsScreen(onBackClick = onBackClick)
        }
    }

    /** Empty the buffer. The data is ephemeral by design; this is one tap. */
    override fun clear() {
        synchronized(lock) {
            buffer.clear()
            _events.value = emptyList()
        }
    }

    /** A copy of the buffer, newest last. Mutating it affects nothing. */
    override fun snapshot(): List<DiagnosticsEvent> = synchronized(lock) { buffer.toList() }

    /**
     * Persist the buffer to a private app-internal file (review R-1), JSONL
     * + GZIP, one event per line. Called ONLY on graceful shutdown (the
     * diagnostics screen leaving) — never on a timer, never on a crash.
     * A failure to write is swallowed: diagnostics must never crash the app.
     */
    override fun persist(context: Context) {
        runCatching {
            val events = snapshot()
            val file = File(context.filesDir, PERSIST_FILE_NAME)
            GZIPOutputStream(file.outputStream().buffered()).use { gzip ->
                for (event in events) {
                    gzip.write((DiagnosticsExport.eventJsonLine(event) + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    /**
     * Rehydrate a persisted buffer, if one exists. Loaded events keep their
     * stored session id; new events get this process's [sessionId]. A
     * corrupt or missing file reads as empty — never a crash, never a
     * partial buffer.
     */
    override fun loadPersisted(context: Context) {
        runCatching {
            val file = File(context.filesDir, PERSIST_FILE_NAME)
            if (!file.isFile) return
            val loaded = mutableListOf<DiagnosticsEvent>()
            GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    DiagnosticsExport.parseEventJsonLine(line)?.let { loaded.add(it) }
                    if (loaded.size >= MAX_EVENTS) break
                }
            }
            if (loaded.isEmpty()) return
            synchronized(lock) {
                buffer.clear()
                buffer.addAll(loaded)
                nextId = (loaded.maxOfOrNull { it.id } ?: 0L) + 1L
                if (sessionStartedMs == 0L) {
                    sessionStartedMs = loaded.first().timestampMs
                }
                _events.value = buffer.toList()
            }
        }
    }

    /** Test-only reset: empties the buffer and the counters. */
    fun resetForTests() {
        synchronized(lock) {
            buffer.clear()
            countersMap.clear()
            dropped = 0L
            _events.value = emptyList()
            _counters.value = emptyMap()
            _droppedCount.value = 0L
        }
    }

    private fun noteDateHeader(dateHeader: String) {
        val serverMs = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                .parse(dateHeader)?.time
        }.getOrNull() ?: return
        clockOffsetMs = serverMs - System.currentTimeMillis()
    }

    private fun sanitize(field: String): String {
        val truncated = DiagnosticsRedactor.truncate(field)
        val redacted = DiagnosticsRedactor.redactText(truncated)
        return if (DiagnosticsRedactor.isSafe(redacted)) redacted
        else DiagnosticsRedactor.REDACTED_UNSAFE
    }

    private fun displayPathOf(redactedUrl: String): String {
        if (redactedUrl == DiagnosticsRedactor.REDACTED) return redactedUrl
        return try {
            val uri = URI(redactedUrl)
            val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
            val query = uri.rawQuery?.takeIf { it.isNotBlank() }
            if (query != null) "$path?$query" else path
        } catch (_: Exception) {
            redactedUrl
        }
    }
}
