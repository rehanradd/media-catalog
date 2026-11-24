package com.raddstream.app.diagnostics.debug

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.raddstream.app.BuildConfig
import com.raddstream.app.brand.Brand
import java.net.URI

/**
 * Device + session info for the diagnostics screen's Session tab and for
 * every export header — the same fields the resolve/press test run headers
 * carry (`Build.MODEL`, Android version, app version, network type), plus
 * the server host, the session id (review R-3), the server-clock offset
 * (review R-4), the aggregate counters (review N-6) and the remote-config
 * snapshot (review N-7).
 *
 * Privacy: the server host is the host part of the stored server URL only —
 * never the full URL, never a query string. The network type is wifi /
 * mobile / other / none — never an SSID. No install id, no account
 * identifier, no sync key. The session id is a random per-process UUID, not
 * a device or account identifier. Remote-config values are booleans the
 * operator publishes — no secrets ride in them.
 */
data class DiagnosticsSessionInfo(
    val appVersion: String,
    val versionCode: Int,
    val applicationId: String,
    val deviceModel: String,
    val androidVersion: String,
    val networkType: String,
    val serverHost: String,
    /**
     * Process start as the recorder observes it (its first record) — NOT
     * login time, NOT install time. 0 when nothing has been recorded yet.
     */
    val sessionStartedMs: Long,
    /** The per-process session UUID (review R-3). Empty when unknown. */
    val sessionId: String = "",
    /** Server-clock offset in ms (review R-4). 0 when never measured. */
    val clockOffsetMs: Long = 0L,
    /** Aggregate counters fed by Telemetry (review N-6). */
    val counters: Map<String, Long> = emptyMap(),
    /** The active remote-config switches (review N-7). */
    val remoteConfig: Map<String, Boolean> = emptyMap(),
)

/**
 * Build the session info. Pure except for the ConnectivityManager read;
 * the server host is parsed from the stored URL, and any failure reads as
 * "unknown" rather than throwing.
 */
fun buildSessionInfo(
    context: Context,
    serverUrl: String?,
    sessionStartedMs: Long,
    sessionId: String = "",
    clockOffsetMs: Long = 0L,
    counters: Map<String, Long> = emptyMap(),
    remoteConfig: Map<String, Boolean> = emptyMap(),
): DiagnosticsSessionInfo {
    return DiagnosticsSessionInfo(
        appVersion = Brand.versionName,
        versionCode = BuildConfig.VERSION_CODE,
        applicationId = BuildConfig.APPLICATION_ID,
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        networkType = networkTypeOf(context),
        serverHost = serverHostOf(serverUrl),
        sessionStartedMs = sessionStartedMs,
        sessionId = sessionId,
        clockOffsetMs = clockOffsetMs,
        counters = counters,
        remoteConfig = remoteConfig,
    )
}

/** Host part of a server URL, or "unknown" — never the full URL. */
fun serverHostOf(serverUrl: String?): String {
    if (serverUrl.isNullOrBlank()) return "unknown"
    return runCatching { URI(serverUrl.trim()).host }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: "unknown"
}

/** wifi / mobile / other / none — never an SSID. Same shape as the resolve harness. */
fun networkTypeOf(context: Context): String {
    return try {
        val connectivity =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val capabilities =
                connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                    ?: return "none"
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                else -> "other"
            }
        } else {
            @Suppress("DEPRECATION")
            val info = connectivity.activeNetworkInfo
            when {
                info == null || !info.isConnected -> "none"
                info.type == ConnectivityManager.TYPE_WIFI -> "wifi"
                info.type == ConnectivityManager.TYPE_MOBILE -> "mobile"
                else -> "other"
            }
        }
    } catch (_: Exception) {
        "other"
    }
}
