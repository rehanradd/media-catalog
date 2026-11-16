package com.raddstream.app.resolve

import android.content.Context
import com.raddstream.app.R

/** The results file: run header + every attempt row, minted URLs in full. */
object ResolveTestExport {

    fun buildJson(header: RunHeader, results: List<AttemptResult>): String {
        val out = StringBuilder()
        out.append("{\n  \"header\": {")
        out.append("\n    \"device_model\": \"${jsonEscape(header.deviceModel)}\",")
        out.append("\n    \"android_version\": \"${jsonEscape(header.androidVersion)}\",")
        out.append("\n    \"app_version\": \"${jsonEscape(header.appVersion)}\",")
        out.append("\n    \"run_started_epoch_ms\": ${header.runStartedEpochMs},")
        out.append("\n    \"network_type\": \"${jsonEscape(header.networkType)}\",")
        out.append("\n    \"webview_suffix_applied\": ${header.webviewSuffixApplied}")
        out.append("\n  },\n  \"attempts\": [")
        for ((index, result) in results.withIndex()) {
            if (index > 0) out.append(",")
            out.append("\n    {")
            out.append("\n      \"link_id\": ${result.linkId},")
            out.append("\n      \"bridge_url\": \"${jsonEscape(result.bridgeUrl)}\",")
            out.append("\n      \"service\": \"${jsonEscape(result.service)}\",")
            out.append("\n      \"engine\": \"${result.engine.name.lowercase()}\",")
            out.append("\n      \"blocklist\": ${result.blocklist},")
            out.append("\n      \"attempt_kind\": \"${result.attemptKind.name.lowercase()}\",")
            out.append("\n      \"started_at_ms\": ${result.startedAtMs},")
            out.append("\n      \"finished_at_ms\": ${result.finishedAtMs},")
            out.append("\n      \"time_to_resolve_ms\": ${result.timeToResolveMs},")
            out.append("\n      \"success\": ${result.success},")
            out.append("\n      \"minted_url\": ${result.mintedUrl?.let { "\"${jsonEscape(it)}\"" } ?: "null"},")
            out.append("\n      \"probe_content_type\": ${result.probeContentType?.let { "\"${jsonEscape(it)}\"" } ?: "null"},")
            out.append("\n      \"probe_content_length\": ${result.probeContentLength ?: "null"},")
            out.append("\n      \"probe_http_status\": ${result.probeHttpStatus ?: "null"},")
            out.append("\n      \"probe_verdict\": ${result.probeVerdict?.let { "\"${it.name.lowercase()}\"" } ?: "null"},")
            out.append("\n      \"probe_note\": ${result.probeNote?.let { "\"${jsonEscape(it)}\"" } ?: "null"},")
            out.append("\n      \"data_used_bytes\": ${result.dataUsedBytes ?: "null"},")
            out.append("\n      \"battery_pct_before\": ${result.batteryPctBefore ?: "null"},")
            out.append("\n      \"battery_pct_after\": ${result.batteryPctAfter ?: "null"},")
            out.append("\n      \"webview_pss_kb_before\": ${result.webviewPssKbBefore ?: "null"},")
            out.append("\n      \"webview_pss_kb_after\": ${result.webviewPssKbAfter ?: "null"},")
            out.append("\n      \"challenge\": \"${result.challenge.name.lowercase()}\",")
            out.append("\n      \"error\": ${result.error?.let { "\"${jsonEscape(it)}\"" } ?: "null"}")
            out.append("\n    }")
        }
        out.append("\n  ]\n}\n")
        return out.toString()
    }

    /**
     * The human-readable summary: counts per group and the G-1/G-3 mint
     * rates, battery and data totals. Session-check re-attempts are excluded
     * from every count — they only test whether the mint differs per run.
     */
    fun buildSummary(context: Context, header: RunHeader, results: List<AttemptResult>): String {
        val normal = results.filter { it.attemptKind == AttemptKind.NORMAL }
        val lines = ArrayList<String>()
        lines.add(context.getString(R.string.resolve_test_summary_header, header.deviceModel, header.androidVersion))
        lines.add(context.getString(R.string.resolve_test_summary_suffix, header.webviewSuffixApplied.toString()))
        for ((group, service) in listOf("filepress" to "filepress", "1fichier" to "1fichier", "control" to "control")) {
            val groupResults = normal.filter {
                if (group == "control") it.service != "filepress" && it.service != "1fichier" else it.service == service
            }
            val minted = groupResults.count { it.success }
            lines.add(context.getString(R.string.resolve_test_summary_group, group, minted, groupResults.size))
        }
        val batteryDelta = normal
            .filter { it.batteryPctBefore != null && it.batteryPctAfter != null }
            .sumOf { it.batteryPctBefore!! - it.batteryPctAfter!! }
        val batteryKnown = normal.any { it.batteryPctBefore != null && it.batteryPctAfter != null }
        val dataTotal = normal.mapNotNull { it.dataUsedBytes }.sum()
        val dataKnown = normal.any { it.dataUsedBytes != null }
        lines.add(
            context.getString(
                R.string.resolve_test_summary_totals,
                if (batteryKnown) batteryDelta.toString() else "?",
                if (dataKnown) dataTotal.toString() else "?",
            ),
        )
        val sessionChecks = results.filter { it.attemptKind == AttemptKind.SESSION_CHECK }
        if (sessionChecks.isNotEmpty()) {
            lines.add(context.getString(R.string.resolve_test_summary_session_checks, sessionChecks.size))
        }
        return lines.joinToString("\n")
    }
}
