package com.raddstream.app.resolve

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.raddstream.app.R
import com.raddstream.app.data.downloads.DeviceDownloadResult
import com.raddstream.app.data.downloads.DeviceDownloadState
import com.raddstream.app.data.downloads.DeviceDownloadsRepository
import com.raddstream.app.network.ApiProvider
import com.raddstream.app.network.streamRedirectUrl
import com.raddstream.app.network.withVipPass
import com.raddstream.app.repository.CatalogRepository
import com.raddstream.app.sync.SyncAccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The G-10 press/download test: the second phone, never opted in, never
 * resolved, on home Wi-Fi, presses Play once per banked link through the
 * real path and taps Download once per banked link through the real
 * download entry point. Debug-only, manual, sequential, no background
 * work, no scheduler. Results live in memory until the viewer exports
 * them; nothing is persisted and no bridge URL leaves the phone except
 * in the export the viewer shares.
 *
 * THE REAL PATHS, reused exactly — nothing here re-implements the mint:
 * - Play: Detail's `playBest`/`playPrint`/`playLadder` emit
 *   `PlayStream(streamId, title)` and `PlayerViewModel.startStreamHandle`
 *   builds the play URL with [streamRedirectUrl] (+ the VIP pass, as the
 *   player does) and hands it to ExoPlayer, which follows the 302. This
 *   harness does the same: the handle comes from the post detail
 *   ([CatalogRepository.getPostDetail], warm off — warming would
 *   pre-resolve the link and defeat the test), the URL is built with the
 *   same two helpers, and a bare ExoPlayer plays it. The v2 bundle call
 *   ([CatalogRepository.playV2]) is NOT on this path: it picks the
 *   server's winner for a print, while this test presses one sampled
 *   link by its own handle — the v1 fallback the detail takes when the
 *   bundle is unavailable.
 * - Download: Detail's confirmKeep calls
 *   [DeviceDownloadsRepository.enqueue] — this harness calls the same
 *   [DeviceDownloadsRepository.enqueue] with the same handle.
 *
 * MINT-TO-302 MEASUREMENT POINT, and its limits: the app has no in-app
 * suspend call at press time that returns the 302 — `startStreamHandle`
 * builds the redirector URL locally and the 302 happens inside the
 * player. So the measurement is a no-follow GET to the exact play URL
 * the player is about to load, issued once per press just before the
 * player starts. It IS the same request the player makes first, so it
 * measures the redirector's banked-302 latency directly — but it is a
 * second stream hit per press (the log will show two banked stream
 * hits per press: probe + player), and it measures the redirector's
 * 302, not the player's internal redirect-follow time. A banked probe
 * 302 means the player's 302 comes from the same cache. The parent
 * verifies "no resolve log line for this press" from the recorded
 * epoch timestamps, on the VM — this phone cannot see the server log.
 */
@OptIn(UnstableApi::class)
@HiltViewModel
class PressTestViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val catalogRepository: CatalogRepository,
    private val apiProvider: ApiProvider,
    private val syncAccountRepository: SyncAccountRepository,
    private val deviceDownloads: DeviceDownloadsRepository,
) : ViewModel() {

    data class UiState(
        val samples: List<ResolveSample> = emptyList(),
        val results: List<PressResult> = emptyList(),
        val running: Boolean = false,
        val runningLinkId: Long? = null,
        val runHeader: PressRunHeader? = null,
        val loadError: String? = null,
        val notice: String? = null,
        val repeatedGroups: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /** No-follow client for the 302 probe only — the player follows redirects itself. */
    private val probeClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private var runJob: Job? = null
    private var currentPlayer: ExoPlayer? = null

    init {
        viewModelScope.launch {
            val set = withContext(Dispatchers.IO) { ResolveSamples.loadFromAssets(appContext) }
            if (set == null) {
                _state.update { it.copy(loadError = appContext.getString(R.string.press_test_load_failed)) }
            } else {
                // Control links have no banked direct link, so this phase
                // covers filepress + 1fichier only (40 links).
                _state.update { it.copy(samples = set.filepress + set.fichier) }
            }
        }
    }

    override fun onCleared() {
        runJob?.cancel()
        releasePlayer()
        probeClient.dispatcher.executorService.shutdown()
        probeClient.connectionPool.evictAll()
        super.onCleared()
    }

    /** Stops an in-flight run and releases the player — the screen calls this when it leaves. */
    fun stopRun() {
        runJob?.cancel()
        runJob = null
        releasePlayer()
        _state.update { it.copy(running = false, runningLinkId = null) }
    }

    /** Runs the 40 banked links in order, one at a time. A tap while running is ignored. */
    fun runAll() {
        if (_state.value.running) return
        runJob = viewModelScope.launch {
            val header = newRunHeader("initial")
            _state.update { it.copy(running = true, runHeader = header, results = emptyList()) }
            for (sample in _state.value.samples) {
                _state.update { it.copy(runningLinkId = sample.linkId) }
                val result = runPress(sample, AttemptKind.NORMAL)
                _state.update { it.copy(results = it.results + result) }
            }
            _state.update { it.copy(running = false, runningLinkId = null) }
        }
    }

    /**
     * The one repeat run of a group that scored 15–17/20 on a press bar.
     * Logged as a separate run with its own header, kind "repeat".
     */
    fun repeatGroup(service: String) {
        if (_state.value.running) return
        if (service !in repeatEligibleGroups(_state.value.results, _state.value.repeatedGroups)) return
        runJob = viewModelScope.launch {
            val header = newRunHeader("repeat")
            val group = _state.value.samples.filter { it.service == service }
            _state.update {
                it.copy(
                    running = true,
                    runHeader = header,
                    repeatedGroups = it.repeatedGroups + service,
                )
            }
            for (sample in group) {
                _state.update { it.copy(runningLinkId = sample.linkId) }
                val result = runPress(sample, AttemptKind.REPEAT)
                _state.update { it.copy(results = it.results + result) }
            }
            _state.update { it.copy(running = false, runningLinkId = null) }
        }
    }

    fun clearResults() {
        _state.update { it.copy(results = emptyList(), repeatedGroups = emptySet()) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** Same export shape as the mint harness: cache files + share sheet + copy. */
    fun exportResults() {
        val current = _state.value
        val header = current.runHeader ?: return
        if (current.results.isEmpty()) return
        val json = PressTestExport.buildJson(header, current.results)
        val summary = PressTestExport.buildSummary(appContext, header, current.results)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(header.runStartedEpochMs))
        try {
            File(appContext.cacheDir, "press_test_$stamp.json").writeText(json)
            File(appContext.cacheDir, "press_test_${stamp}_summary.txt").writeText(summary)
        } catch (_: Exception) {
            _state.update { it.copy(notice = appContext.getString(R.string.press_test_export_failed)) }
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, appContext.getString(R.string.press_test_share_subject))
            putExtra(Intent.EXTRA_TEXT, summary + "\n\n" + json)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            val chooser = Intent.createChooser(intent, appContext.getString(R.string.press_test_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(chooser)
        } catch (_: Exception) {
            _state.update { it.copy(notice = appContext.getString(R.string.press_test_export_failed)) }
        }
    }

    fun copyResultsJson() {
        val current = _state.value
        val header = current.runHeader ?: return
        if (current.results.isEmpty()) return
        val json = PressTestExport.buildJson(header, current.results)
        try {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("press_test", json))
            _state.update { it.copy(notice = appContext.getString(R.string.press_test_copied)) }
        } catch (_: Exception) {
            _state.update { it.copy(notice = appContext.getString(R.string.press_test_export_failed)) }
        }
    }

    private fun newRunHeader(runKind: String): PressRunHeader {
        return PressRunHeader(
            deviceModel = Build.MODEL ?: "unknown",
            androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            appVersion = appVersion(),
            runStartedEpochMs = System.currentTimeMillis(),
            networkType = networkType(),
            runKind = runKind,
        )
    }

    private suspend fun runPress(sample: ResolveSample, kind: AttemptKind): PressResult {
        val pressStartedAtMs = System.currentTimeMillis()
        val pressStartedElapsedMs = SystemClock.elapsedRealtime()
        var playError: String? = null
        var snapshot: PressPlaybackSnapshot? = null

        // The handle for this sampled link, from the post detail — the same
        // metadata Detail loads, with warm off so nothing is pre-resolved.
        val handle = lookupHandle(sample)
        var mintStartedAtMs = pressStartedAtMs
        var mintTo302Ms: Long? = null
        var mintHttpStatus: Int? = null
        var mintFinishedAtMs: Long? = null
        var mintedUrl: String? = null
        var playUrl: String? = null
        if (handle == null) {
            playError = "no_handle"
        } else {
            mintStartedAtMs = System.currentTimeMillis()
            val probe = probe302(handle)
            mintTo302Ms = probe.first
            mintHttpStatus = probe.second
            mintedUrl = probe.third
            mintFinishedAtMs = System.currentTimeMillis()
            playUrl = probe.fourth
            if (mintHttpStatus != 302) {
                // The redirector refused: the player would fail the same
                // way, so there is nothing to press. The download below
                // still runs — it is a separate path with its own bar.
                playError = "redirector_$mintHttpStatus"
            } else {
                val played = pressPlay(playUrl!!, pressStartedElapsedMs)
                snapshot = played.first
                playError = played.second
            }
        }
        val download = if (handle == null) {
            Quad(null, null, "no_handle", null)
        } else {
            runDownload(handle, sample)
        }
        return PressResult(
            linkId = sample.linkId,
            postId = sample.postId,
            service = sample.service,
            attemptKind = kind,
            pressStartedAtMs = pressStartedAtMs,
            mintStartedAtMs = mintStartedAtMs,
            mintFinishedAtMs = mintFinishedAtMs,
            mintTo302Ms = mintTo302Ms,
            mintHttpStatus = mintHttpStatus,
            mintedUrl = mintedUrl,
            pressToFirstFrameMs = snapshot?.firstFrameElapsedMs,
            rebufferCount = snapshot?.rebufferCount ?: 0,
            droppedFrames = snapshot?.droppedFrames ?: 0,
            bytesRead = snapshot?.bytesRead ?: 0L,
            statsTotalPlayTimeMs = snapshot?.statsTotalPlayTimeMs,
            statsRebufferCount = snapshot?.statsRebufferCount,
            playError = playError,
            downloadTapStartedAtMs = download.first,
            tapToFirstByteMs = download.second,
            downloadCancelled = download.fourth,
            downloadError = download.third,
        )
    }

    /**
     * The sampled link's opaque handle: the post detail's source whose
     * link id matches the sample. Null when the detail cannot be read or
     * names no such source — the press cannot start without a handle.
     */
    private suspend fun lookupHandle(sample: ResolveSample): String? {
        val detail = try {
            catalogRepository.getPostDetail(sample.postId.toInt(), warm = false)
        } catch (_: Exception) {
            null
        } ?: return null
        return detail.sources.firstOrNull { it.linkId?.toLong() == sample.linkId }
            ?.streamId?.takeIf { it.isNotBlank() }
    }

    /**
     * The 302 probe: a no-follow GET to the exact play URL the player will
     * load. Returns (duration ms, HTTP status, 302 Location, play URL).
     * The duration IS mint_to_302_ms — see the class KDoc for its limits.
     */
    private suspend fun probe302(
        handle: String,
    ): Quad<Long?, Int?, String?, String?> {
        val playUrl = try {
            withVipPass(
                streamRedirectUrl(apiProvider.currentBaseUrl(), handle),
                syncAccountRepository.currentVipPass(),
            )
        } catch (_: Exception) {
            return Quad(null, null, null, null)
        }
        val startedElapsed = SystemClock.elapsedRealtime()
        val outcome = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(playUrl).get().build()
                probeClient.newCall(request).execute().use { response ->
                    Triple(response.code, response.header("Location"), SystemClock.elapsedRealtime())
                }
            } catch (_: Exception) {
                null
            }
        } ?: return Quad(null, null, null, playUrl)
        val (status, location, finishedElapsed) = outcome
        return Quad(
            finishedElapsed - startedElapsed,
            status,
            location?.takeIf { status == 302 },
            playUrl,
        )
    }

    /**
     * Presses Play once through the real path: the harness's own minimal
     * ExoPlayer loads the redirector URL and follows the 302, exactly as
     * the production player does. Returns (snapshot, error). After the
     * first frame it keeps playing for a short observation window so the
     * counters have something to count, then stops and releases.
     */
    private suspend fun pressPlay(playUrl: String, pressStartedElapsedMs: Long): Pair<PressPlaybackSnapshot?, String?> {
        val collector = PressStatsCollector(pressStartedElapsedMs)
        val statsListener = pressStatsListener(collector)
        val player = ExoPlayer.Builder(appContext).build()
        currentPlayer = player
        try {
            player.addAnalyticsListener(collector)
            player.addAnalyticsListener(statsListener)
            player.setMediaItem(MediaItem.fromUri(playUrl))
            player.prepare()
            player.play()
            val deadlineElapsed = pressStartedElapsedMs + FIRST_FRAME_TIMEOUT_MS
            while (collector.firstFrameElapsedMs == null && SystemClock.elapsedRealtime() < deadlineElapsed) {
                delay(50)
            }
            if (collector.firstFrameElapsedMs == null) {
                return collector.snapshot() to "no_first_frame"
            }
            delay(OBSERVE_AFTER_FIRST_FRAME_MS)
            // The finished-session callback only fires when playback ends,
            // which is after this snapshot — so read the live totals from
            // the listener itself before stopping.
            statsListener.playbackStats?.let {
                collector.applyPlaybackStats(it.totalPlayTimeMs, it.totalRebufferCount)
            }
            return collector.snapshot() to null
        } catch (_: Exception) {
            return collector.snapshot() to "player_error"
        } finally {
            releasePlayer()
        }
    }

    /**
     * Taps Download once through the real entry point and records
     * tap-to-FIRST-BYTE — never time-to-finish. Cancels right after the
     * first byte is observed, so no full file is ever downloaded.
     * Returns (tap epoch ms, first-byte ms, error, cancel succeeded).
     *
     * MEASUREMENT LIMIT: the first byte is observed through the
     * repository's downloads flow, whose progress is flushed about once
     * a second — so a sub-second first byte can read up to ~1 s late.
     * The bar is 2 s and the reading is an upper bound, never an
     * understatement.
     */
    private suspend fun runDownload(
        handle: String,
        sample: ResolveSample,
    ): Quad<Long?, Long?, String?, Boolean?> {
        val tapStartedAtMs = System.currentTimeMillis()
        val tapStartedElapsedMs = SystemClock.elapsedRealtime()
        // A row left by an earlier run would already hold bytes, so the
        // tap-to-first-byte would measure nothing: clear it first.
        deviceDownloads.downloads.value.firstOrNull { it.handle == handle }?.let { existing ->
            deviceDownloads.requestRemove(existing.id)
            val deadline = SystemClock.elapsedRealtime() + REMOVE_WAIT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (deviceDownloads.downloads.value.none { it.id == existing.id }) break
                delay(100)
            }
        }
        val title = sample.title
        val outcome = try {
            deviceDownloads.enqueue(handle, title, sample.quality)
        } catch (_: Exception) {
            return Quad(tapStartedAtMs, null, "enqueue_failed", null)
        }
        val enqueued = when (outcome) {
            is DeviceDownloadResult.Enqueued -> outcome.download
            is DeviceDownloadResult.NeedsPermission -> {
                return Quad(tapStartedAtMs, null, "needs_permission", null)
            }
            is DeviceDownloadResult.Refused -> {
                return Quad(tapStartedAtMs, null, "refused:${outcome.message}", null)
            }
        }
        val deadlineElapsed = tapStartedElapsedMs + DOWNLOAD_FIRST_BYTE_TIMEOUT_MS
        var firstByteMs: Long? = null
        while (SystemClock.elapsedRealtime() < deadlineElapsed) {
            val row = deviceDownloads.downloads.value.firstOrNull { it.id == enqueued.id }
            if (row != null && row.bytesDownloaded > 0) {
                firstByteMs = SystemClock.elapsedRealtime() - tapStartedElapsedMs
                break
            }
            if (row == null) break
            delay(100)
        }
        if (firstByteMs == null) {
            deviceDownloads.requestRemove(enqueued.id)
            return Quad(tapStartedAtMs, null, "no_first_byte", null)
        }
        // Cancel right after the first byte: never download full files.
        deviceDownloads.requestRemove(enqueued.id)
        val cancelDeadline = SystemClock.elapsedRealtime() + REMOVE_WAIT_MS
        var cancelled = false
        while (SystemClock.elapsedRealtime() < cancelDeadline) {
            val row = deviceDownloads.downloads.value.firstOrNull { it.id == enqueued.id }
            if (row == null || (row.state != DeviceDownloadState.QUEUED && row.state != DeviceDownloadState.DOWNLOADING)) {
                cancelled = true
                break
            }
            delay(100)
        }
        return Quad(tapStartedAtMs, firstByteMs, null, cancelled)
    }

    private fun releasePlayer() {
        currentPlayer?.let { player ->
            try {
                player.stop()
            } catch (_: Exception) {
            }
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
        currentPlayer = null
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

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    companion object {
        const val PROBE_TIMEOUT_MS = 10_000L
        const val FIRST_FRAME_TIMEOUT_MS = 30_000L
        /** Observation window after the first frame, so the counters have something to count. */
        const val OBSERVE_AFTER_FIRST_FRAME_MS = 5_000L
        const val DOWNLOAD_FIRST_BYTE_TIMEOUT_MS = 30_000L
        const val REMOVE_WAIT_MS = 2_000L
    }
}
