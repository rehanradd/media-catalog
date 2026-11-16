package com.raddstream.app.resolve

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raddstream.app.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The harness state. Debug-only, manual, sequential: one attempt at a time,
 * each link once per run, never parallel, no background work, no scheduler.
 * Results live in memory until the viewer exports them; nothing is persisted
 * and no bridge URL leaves the phone except in the export the viewer shares.
 */
@HiltViewModel
class ResolveTestViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    data class UiState(
        val samples: List<ResolveSample> = emptyList(),
        val blocklist: List<String> = emptyList(),
        val results: List<AttemptResult> = emptyList(),
        val running: Boolean = false,
        val runningLinkId: Long? = null,
        val runHeader: RunHeader? = null,
        val loadError: String? = null,
        val notice: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val transport: ResolveHttpTransport = OkHttpResolveTransport()
    private val httpChain = HttpChainEngine(transport)
    private val webViewEngine = WebViewEngine(appContext, transport)
    private var sampleSet: ResolveSampleSet? = null

    init {
        viewModelScope.launch {
            val set = withContext(Dispatchers.IO) { ResolveSamples.loadFromAssets(appContext) }
            if (set == null) {
                _state.update { it.copy(loadError = appContext.getString(R.string.resolve_test_load_failed)) }
            } else {
                sampleSet = set
                _state.update { it.copy(samples = set.ordered(), blocklist = set.blocklist) }
            }
        }
    }

    override fun onCleared() {
        transport.close()
        super.onCleared()
    }

    /** Runs the 50 samples in order, one at a time. A tap while running is ignored. */
    fun runAll() {
        if (_state.value.running) return
        viewModelScope.launch {
            val header = newRunHeader()
            _state.update { it.copy(running = true, runHeader = header, results = emptyList()) }
            for (sample in _state.value.samples) {
                _state.update { it.copy(runningLinkId = sample.linkId) }
                val result = runAttempt(sample, AttemptKind.NORMAL)
                _state.update { it.copy(results = it.results + result) }
            }
            logSummary()
            _state.update { it.copy(running = false, runningLinkId = null) }
        }
    }

    /**
     * Runs only the 10 control samples (filemoon + rpmplay — hosts that
     * should mint). Short enough for a Firebase Robo run: the full 50 takes
     * ~13 minutes, the control set ~1–5. Same logging as a full run.
     */
    fun runControlOnly() {
        if (_state.value.running) return
        viewModelScope.launch {
            val control = sampleSet?.control ?: emptyList()
            val header = newRunHeader()
            _state.update { it.copy(running = true, runHeader = header, results = emptyList()) }
            for (sample in control) {
                _state.update { it.copy(runningLinkId = sample.linkId) }
                val result = runAttempt(sample, AttemptKind.NORMAL)
                _state.update { it.copy(results = it.results + result) }
            }
            logSummary()
            _state.update { it.copy(running = false, runningLinkId = null) }
        }
    }

    /**
     * One logcat line per attempt plus a summary line at the end of a run.
     * The export leaves the phone only through the share sheet, so logcat is
     * how an emulator run (Firebase Robo) reports its mint counts back.
     */
    private fun logAttempt(result: AttemptResult) {
        Log.i(
            LOG_TAG,
            "RESOLVE_ATTEMPT linkId=${result.linkId} service=${result.service} " +
                "success=${result.success} error=${result.error ?: "-"} " +
                "timeMs=${result.timeToResolveMs}",
        )
    }

    private fun logSummary() {
        val results = _state.value.results
        fun count(service: String): String {
            val group = results.filter { it.service == service }
            return "${group.count { it.success }}/${group.size}"
        }
        Log.i(
            LOG_TAG,
            "RESOLVE_SUMMARY total=${results.count { it.success }}/${results.size} " +
                "filepress=${count("filepress")} 1fichier=${count("1fichier")} " +
                "filemoon=${count("filemoon")} rpmplay=${count("rpmplay")}",
        )
    }

    /** Re-attempts one link, logged as a session check and excluded from the summary counts. */
    fun mintAgain(sample: ResolveSample) {
        if (_state.value.running) return
        viewModelScope.launch {
            if (_state.value.runHeader == null) {
                _state.update { it.copy(runHeader = newRunHeader()) }
            }
            _state.update { it.copy(running = true, runningLinkId = sample.linkId) }
            val result = runAttempt(sample, AttemptKind.SESSION_CHECK)
            _state.update { it.copy(results = it.results + result, running = false, runningLinkId = null) }
        }
    }

    fun clearResults() {
        _state.update { it.copy(results = emptyList()) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    /**
     * Writes the JSON file and the text summary to the app cache, then shares
     * the summary + JSON text through the share sheet. There is no
     * FileProvider in the app, so the file itself is not attached — the full
     * text is what leaves the phone, and the file stays in the cache for
     * retrieval on the device.
     */
    fun exportResults() {
        val current = _state.value
        val header = current.runHeader ?: return
        if (current.results.isEmpty()) return
        val json = ResolveTestExport.buildJson(header, current.results)
        val summary = ResolveTestExport.buildSummary(appContext, header, current.results)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(header.runStartedEpochMs))
        try {
            File(appContext.cacheDir, "resolve_test_$stamp.json").writeText(json)
            File(appContext.cacheDir, "resolve_test_${stamp}_summary.txt").writeText(summary)
        } catch (_: Exception) {
            _state.update { it.copy(notice = appContext.getString(R.string.resolve_test_export_failed)) }
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, appContext.getString(R.string.resolve_test_share_subject))
            putExtra(Intent.EXTRA_TEXT, summary + "\n\n" + json)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            val chooser = Intent.createChooser(intent, appContext.getString(R.string.resolve_test_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(chooser)
        } catch (_: Exception) {
            _state.update { it.copy(notice = appContext.getString(R.string.resolve_test_export_failed)) }
        }
    }

    fun copyResultsJson() {
        val current = _state.value
        val header = current.runHeader ?: return
        if (current.results.isEmpty()) return
        val json = ResolveTestExport.buildJson(header, current.results)
        try {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("resolve_test", json))
            _state.update { it.copy(notice = appContext.getString(R.string.resolve_test_copied)) }
        } catch (_: Exception) {
            _state.update { it.copy(notice = appContext.getString(R.string.resolve_test_export_failed)) }
        }
    }

    private fun newRunHeader(): RunHeader {
        return RunHeader(
            deviceModel = Build.MODEL ?: "unknown",
            androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            appVersion = appVersion(),
            runStartedEpochMs = System.currentTimeMillis(),
            networkType = networkType(),
            webviewSuffixApplied = WebViewEngine.ensureDataDirectorySuffix(),
        )
    }

    companion object {
        /** Logcat tag for the per-attempt and summary lines (Robo reads these). */
        const val LOG_TAG = "ResolveTest"
    }

    private suspend fun runAttempt(sample: ResolveSample, kind: AttemptKind): AttemptResult {
        val startedAt = System.currentTimeMillis()
        val batteryBefore = batteryPct()
        val trafficBefore = uidTrafficBytes()
        val isWebView = sample.service != "filepress"
        val pssBefore = if (isWebView) processPssKb() else null
        val engine = if (isWebView) ResolveEngine.WEBVIEW else ResolveEngine.HTTP_CHAIN
        var success = false
        var mintedUrl: String? = null
        var probe: ProbeResult? = null
        var challenge = Challenge.NONE
        var error: String? = null
        if (isWebView) {
            when (val result = webViewEngine.resolve(sample, _state.value.blocklist)) {
                is WebViewEngine.Result.Minted -> {
                    success = true
                    mintedUrl = result.mintedUrl
                    probe = result.probe
                    challenge = result.challenge
                }
                is WebViewEngine.Result.Failed -> {
                    probe = result.probe
                    challenge = result.challenge
                    error = result.note
                }
            }
        } else {
            when (val result = httpChain.resolve(sample.bridgeUrl)) {
                is ChainResult.Minted -> {
                    success = true
                    mintedUrl = result.mintedUrl
                    probe = result.probe
                }
                is ChainResult.Failed -> {
                    probe = result.probe
                    error = result.note
                }
            }
        }
        val finishedAt = System.currentTimeMillis()
        val trafficAfter = uidTrafficBytes()
        val dataUsed = if (trafficBefore != null && trafficAfter != null) trafficAfter - trafficBefore else null
        return AttemptResult(
            linkId = sample.linkId,
            bridgeUrl = sample.bridgeUrl,
            service = sample.service,
            engine = engine,
            blocklist = sample.blocklist,
            attemptKind = kind,
            startedAtMs = startedAt,
            finishedAtMs = finishedAt,
            timeToResolveMs = finishedAt - startedAt,
            success = success,
            mintedUrl = mintedUrl,
            probeContentType = probe?.contentType,
            probeContentLength = probe?.contentLength,
            probeHttpStatus = probe?.httpStatus,
            probeVerdict = probe?.verdict,
            probeNote = probe?.note,
            dataUsedBytes = dataUsed,
            batteryPctBefore = batteryBefore,
            batteryPctAfter = batteryPct(),
            webviewPssKbBefore = pssBefore,
            webviewPssKbAfter = if (isWebView) processPssKb() else null,
            challenge = challenge,
            error = error,
        ).also { logAttempt(it) }
    }

    private fun batteryPct(): Int? {
        return try {
            val battery = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct < 0) null else pct
        } catch (_: Exception) {
            null
        }
    }

    private fun processPssKb(): Long? {
        return try {
            val activity = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activity.getProcessMemoryInfo(intArrayOf(Process.myPid()))
                .firstOrNull()?.totalPss?.toLong()
        } catch (_: Exception) {
            null
        }
    }

    private fun uidTrafficBytes(): Long? {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return null
        return rx + tx
    }

    /** wifi / mobile / other / none — never an SSID. */
    private fun networkType(): String {
        return try {
            val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return "none"
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

    @Suppress("DEPRECATION")
    private fun appVersion(): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    appContext.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            }
            info.versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }
}
