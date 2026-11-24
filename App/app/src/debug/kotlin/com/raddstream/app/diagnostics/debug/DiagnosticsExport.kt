package com.raddstream.app.diagnostics.debug

import com.raddstream.app.brand.Brand
import com.raddstream.app.diagnostics.DiagnosticsEvent
import com.raddstream.app.diagnostics.DiagnosticsSeverity
import com.raddstream.app.diagnostics.DiagnosticsType
import com.raddstream.app.resolve.jsonEscape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export builders (APP-13, review N-3 + R-5). Pure functions over
 * already-redacted events — and, as a defensive second pass, the finished
 * markdown/JSONL is run through the redactor once more before it is
 * returned, so a bug in the capture-time redactor cannot leak a signed
 * media URL into an export.
 *
 * Two forms (N-3): a human-readable Markdown summary (problems first, then
 * the full timeline) and machine-readable JSONL (one event per line, each
 * line a self-contained JSON object carrying the session id). The UI shares
 * the Markdown file and copies the JSONL text. The same JSONL line shape is
 * the persist format ([DebugDiagnostics.persist]).
 *
 * Mirrors the `ResolveTestExport` StringBuilder style (manual JSON, same
 * `jsonEscape`).
 */
object DiagnosticsExport {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun eventTime(timestampMs: Long): String = timeFormat.format(Date(timestampMs))

    fun sessionTime(timestampMs: Long): String = dateFormat.format(Date(timestampMs))

    fun buildText(header: DiagnosticsSessionInfo, events: List<DiagnosticsEvent>): String {
        val counts = countEvents(events)
        val problems = rankProblems(events)
        val out = StringBuilder()
        // Brand.name, never a literal: BrandDriftTest forbids hardcoded
        // brand names in main sources (identity.name is "RADD Stream").
        out.append("# ${Brand.name} diagnostics\n\n")
        out.append("- App: ${header.appVersion} (${header.versionCode}) · ${header.applicationId}\n")
        out.append("- Device: ${header.deviceModel} · ${header.androidVersion}\n")
        out.append("- Network: ${header.networkType} · Server: ${header.serverHost}\n")
        out.append("- Session ID: ${header.sessionId}\n")
        out.append("- Session started: ${sessionTime(header.sessionStartedMs)}\n")
        if (header.clockOffsetMs != 0L) {
            out.append("- Server clock offset: ${header.clockOffsetMs}ms\n")
        }
        out.append(
            "- Events: ${counts.total} total · ${counts.errors} errors · " +
                "${counts.warnings} warnings\n",
        )
        out.append("\n## Problems (${problems.size})\n\n")
        if (problems.isEmpty()) {
            out.append("None.\n")
        } else {
            for (event in problems) {
                out.append("- ${eventLine(event)}\n")
            }
        }
        out.append("\n## Timeline (${events.size})\n\n")
        if (events.isEmpty()) {
            out.append("No events recorded yet.\n")
        } else {
            for (event in events.asReversed()) {
                out.append("- ${eventLine(event)}\n")
            }
        }
        // Defensive second pass (R-5): the builders never see a credential
        // because the recorder never stored one — and if one ever slipped
        // through, the export still does not carry it out.
        return DiagnosticsRedactor.redactText(out.toString())
    }

    fun eventLine(event: DiagnosticsEvent): String {
        val context = listOfNotNull(
            event.screen,
            event.title,
            event.quality,
            event.host,
            event.error,
        ).joinToString(" · ")
        val detail = event.detail?.takeIf { it.isNotBlank() }
        return buildString {
            append("[${eventTime(event.timestampMs)}]")
            append(" ${event.severity.name} ${event.type.name}: ${event.summary}")
            if (context.isNotEmpty()) append(" ($context)")
            if (detail != null) append(" — $detail")
            event.durationMs?.let { append(" [${it}ms]") }
        }
    }

    /** One event as a single JSON object (one line of the JSONL export). */
    fun eventJsonLine(event: DiagnosticsEvent): String {
        return buildString {
            append("{")
            append("\"id\": ${event.id},")
            append(" \"timestamp_ms\": ${event.timestampMs},")
            append(" \"timestamp_ns\": ${event.timestampNs},")
            append(" \"session_id\": \"${jsonEscape(event.sessionId)}\",")
            append(" \"type\": \"${event.type.name.lowercase()}\",")
            append(" \"severity\": \"${event.severity.name.lowercase()}\",")
            append(" \"summary\": \"${jsonEscape(event.summary)}\",")
            append(" \"detail\": ${event.detail?.let { "\"${jsonEscape(it)}\"" } ?: "null"},")
            append(" \"screen\": ${event.screen?.let { "\"${jsonEscape(it)}\"" } ?: "null"},")
            append(" \"title\": ${event.title?.let { "\"${jsonEscape(it)}\"" } ?: "null"},")
            append(" \"quality\": ${event.quality?.let { "\"${jsonEscape(it)}\"" } ?: "null"},")
            append(" \"host\": ${event.host?.let { "\"${jsonEscape(it)}\"" } ?: "null"},")
            append(" \"error\": ${event.error?.let { "\"${jsonEscape(it)}\"" } ?: "null"},")
            append(" \"duration_ms\": ${event.durationMs ?: "null"}")
            append("}")
        }
    }

    /**
     * The machine-readable form (N-3): a header line, then one JSON object
     * per event. Defensive second pass (R-5) over the whole text, same as
     * [buildText].
     */
    fun buildJsonLines(header: DiagnosticsSessionInfo, events: List<DiagnosticsEvent>): String {
        val out = StringBuilder()
        out.append("{")
        out.append("\"type\": \"header\",")
        out.append(" \"app_version\": \"${jsonEscape(header.appVersion)}\",")
        out.append(" \"version_code\": ${header.versionCode},")
        out.append(" \"application_id\": \"${jsonEscape(header.applicationId)}\",")
        out.append(" \"device_model\": \"${jsonEscape(header.deviceModel)}\",")
        out.append(" \"android_version\": \"${jsonEscape(header.androidVersion)}\",")
        out.append(" \"network_type\": \"${jsonEscape(header.networkType)}\",")
        out.append(" \"server_host\": \"${jsonEscape(header.serverHost)}\",")
        out.append(" \"session_id\": \"${jsonEscape(header.sessionId)}\",")
        out.append(" \"session_started_ms\": ${header.sessionStartedMs},")
        out.append(" \"clock_offset_ms\": ${header.clockOffsetMs}")
        out.append("}\n")
        for (event in events) {
            out.append(eventJsonLine(event))
            out.append("\n")
        }
        return DiagnosticsRedactor.redactText(out.toString())
    }

    /**
     * Parse one JSONL event line back (the persist round-trip). A hand-rolled
     * parser for the exact shape [eventJsonLine] writes — the app has no JSON
     * object parser in this module, and the shape is fixed. Returns null for
     * anything malformed (a corrupt persisted line reads as absent).
     */
    fun parseEventJsonLine(line: String): DiagnosticsEvent? {
        return runCatching {
            fun stringField(name: String): String? {
                val key = "\"$name\": \""
                val start = line.indexOf(key)
                if (start < 0) return null
                var i = start + key.length
                val sb = StringBuilder()
                while (i < line.length) {
                    val c = line[i]
                    if (c == '\\' && i + 1 < line.length) {
                        val next = line[i + 1]
                        when (next) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'u' -> {
                                val hex = line.substring(i + 2, (i + 6).coerceAtMost(line.length))
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                i += 4
                            }
                            else -> sb.append(next)
                        }
                        i += 2
                    } else if (c == '"') {
                        return sb.toString()
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                return null
            }
            fun longField(name: String): Long? {
                val key = "\"$name\": "
                val start = line.indexOf(key)
                if (start < 0) return null
                val rest = line.substring(start + key.length)
                val end = rest.indexOfFirst { !it.isDigit() && it != '-' }
                val token = if (end < 0) rest else rest.substring(0, end)
                return token.toLongOrNull()
            }
            fun nullableStringField(name: String): String? {
                val key = "\"$name\": "
                val start = line.indexOf(key)
                if (start < 0) return null
                return if (line.startsWith("null", start + key.length)) null
                else stringField(name)
            }
            val id = longField("id") ?: return null
            val timestampMs = longField("timestamp_ms") ?: return null
            val typeName = stringField("type") ?: return null
            val severityName = stringField("severity") ?: return null
            val summary = stringField("summary") ?: return null
            DiagnosticsEvent(
                id = id,
                timestampMs = timestampMs,
                type = DiagnosticsType.valueOf(typeName.uppercase()),
                severity = DiagnosticsSeverity.valueOf(severityName.uppercase()),
                summary = summary,
                detail = nullableStringField("detail"),
                screen = nullableStringField("screen"),
                title = nullableStringField("title"),
                quality = nullableStringField("quality"),
                host = nullableStringField("host"),
                error = nullableStringField("error"),
                durationMs = longField("duration_ms"),
                timestampNs = longField("timestamp_ns") ?: 0L,
                sessionId = stringField("session_id").orEmpty(),
            )
        }.getOrNull()
    }
}
