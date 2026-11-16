package com.raddstream.app.resolve

import android.content.Context
import com.raddstream.app.R
import java.util.Locale

/** The press results file: run header + every press row, banked URLs in full. */
object PressTestExport {

    fun buildJson(header: PressRunHeader, results: List<PressResult>): String {
        val out = StringBuilder()
        out.append("{\n  \"header\": {")
        out.append("\n    \"device_model\": \"${jsonEscape(header.deviceModel)}\",")
        out.append("\n    \"android_version\": \"${jsonEscape(header.androidVersion)}\",")
        out.append("\n    \"app_version\": \"${jsonEscape(header.appVersion)}\",")
        out.append("\n    \"run_started_epoch_ms\": ${header.runStartedEpochMs},")
        out.append("\n    \"network_type\": \"${jsonEscape(header.networkType)}\",")
        out.append("\n    \"phase\": \"${jsonEscape(header.phase)}\",")
        out.append("\n    \"run_kind\": \"${jsonEscape(header.runKind)}\"")
        out.append("\n  },\n  \"presses\": [")
        for ((index, result) in results.withIndex()) {
            if (index > 0) out.append(",")
            out.append("\n    {")
            out.append("\n      \"link_id\": ${result.linkId},")
            out.append("\n      \"post_id\": ${result.postId},")
            out.append("\n      \"service\": \"${jsonEscape(result.service)}\",")
            out.append("\n      \"attempt_kind\": \"${result.attemptKind.name.lowercase()}\",")
            out.append("\n      \"press_started_at_ms\": ${result.pressStartedAtMs},")
            out.append("\n      \"mint_started_at_ms\": ${result.mintStartedAtMs},")
            out.append("\n      \"mint_finished_at_ms\": ${result.mintFinishedAtMs ?: "null"},")
            out.append("\n      \"mint_to_302_ms\": ${result.mintTo302Ms ?: "null"},")
            out.append("\n      \"mint_http_status\": ${result.mintHttpStatus ?: "null"},")
            out.append("\n      \"minted_url\": ${result.mintedUrl?.let { "\"${jsonEscape(it)}\"" } ?: "null"},")
            out.append("\n      \"press_to_first_frame_ms\": ${result.pressToFirstFrameMs ?: "null"},")
            out.append("\n      \"rebuffer_count\": ${result.rebufferCount},")
            out.append("\n      \"dropped_frames\": ${result.droppedFrames},")
            out.append("\n      \"bytes_read\": ${result.bytesRead},")
            out.append("\n      \"stats_total_play_time_ms\": ${result.statsTotalPlayTimeMs ?: "null"},")
            out.append("\n      \"stats_rebuffer_count\": ${result.statsRebufferCount ?: "null"},")
            out.append("\n      \"play_error\": ${result.playError?.let { "\"${jsonEscape(it)}\"" } ?: "null"},")
            out.append("\n      \"download_tap_started_at_ms\": ${result.downloadTapStartedAtMs ?: "null"},")
            out.append("\n      \"tap_to_first_byte_ms\": ${result.tapToFirstByteMs ?: "null"},")
            out.append("\n      \"download_cancelled\": ${result.downloadCancelled ?: "null"},")
            out.append("\n      \"download_error\": ${result.downloadError?.let { "\"${jsonEscape(it)}\"" } ?: "null"}")
            out.append("\n    }")
        }
        out.append("\n  ]\n}\n")
        return out.toString()
    }

    /**
     * The human-readable summary: per-group and overall T-1/T-2/T-3 pass
     * counts with the Wilson 95% interval for each press-bar pass rate.
     * Repeat runs score separately — a repeat never rewrites the initial
     * run's numbers. The "no resolve log line" bar is NOT claimed here:
     * this phone cannot see the server log, so the parent checks it on
     * the VM from the recorded press/mint timestamps.
     */
    fun buildSummary(context: Context, header: PressRunHeader, results: List<PressResult>): String {
        val lines = ArrayList<String>()
        lines.add(context.getString(R.string.press_test_summary_header, header.deviceModel, header.androidVersion))
        lines.add(context.getString(R.string.press_test_summary_kind, header.runKind))
        lines.add(context.getString(R.string.press_test_summary_probe_note))
        lines.add(context.getString(R.string.press_test_summary_log_note))
        for (kind in listOf(AttemptKind.NORMAL, AttemptKind.REPEAT)) {
            val kindResults = results.filter { it.attemptKind == kind }
            if (kindResults.isEmpty()) continue
            if (kind == AttemptKind.REPEAT) {
                lines.add(context.getString(R.string.press_test_summary_repeat_heading))
            }
            for (score in scorePressResults(results, kind)) {
                lines.add(context.getString(R.string.press_test_summary_group, score.group))
                lines.add(barLine(context, "T-1", score.t1))
                lines.add(barLine(context, "T-2", score.t2))
                lines.add(barLine(context, "T-3", score.t3))
            }
        }
        return lines.joinToString("\n")
    }

    private fun barLine(context: Context, bar: String, score: BarScore): String {
        return context.getString(
            R.string.press_test_summary_bar,
            bar,
            score.passes,
            score.trials,
            formatPct(score.wilsonLow),
            formatPct(score.wilsonHigh),
        )
    }

    private fun formatPct(fraction: Double): String =
        String.format(Locale.US, "%.1f%%", fraction * 100.0)
}
