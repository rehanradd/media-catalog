package com.raddstream.app.data.cache

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.raddstream.app.player.GATE_REFUSED_HTTP_STATUS
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** What one HLS keep's Media3 download is doing, in our own words. */
enum class HlsKeepDownloadState {
    NOT_STARTED, QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED,
}

/** Terminal outcome of driving one HLS keep to completion (or giving up on it). */
sealed interface HlsKeepDriveOutcome {
    data class Completed(val bytesDownloaded: Long) : HlsKeepDriveOutcome
    /** Transient failure — the coordinator retries with backoff. */
    data class Failed(val reason: String) : HlsKeepDriveOutcome
    /** The manifest URL rotted — the coordinator re-resolves and resumes. */
    data object SourceDead : HlsKeepDriveOutcome
    /** Our own redirector refused the rung (quality gate 402) — not retryable. */
    data object Gated : HlsKeepDriveOutcome
}

/**
 * The bridge between [CacheCoordinator] and Media3's [DownloadManager] for
 * HLS keeps (R3 — the MochiTV model).
 *
 * One Media3 download per keep, id = the keep's [CacheTaskEntity.sourceKey]:
 * the manager fetches the playlist + segments, retries each segment with
 * backoff, persists its own resume state in its database (the aria2
 * control-file lesson, built in), and reassembles the segments — a completed
 * download plays offline through the shared cache ([MediaDownloadCache]).
 *
 * What this deliberately does NOT replace: the auto-preview HLS path
 * ([HlsAheadBuffer]) still drives playhead-ahead buffering, and progressive
 * files still go through [AheadFetcher]. Only a user-requested FULL_DOWNLOAD
 * HLS keep goes through here — the row the viewer tapped "keep" on.
 *
 * Pause is a stop reason, not a delete: [pause] sets
 * [STOP_REASON_USER_PAUSED] on the download (bytes stay in the cache) and
 * [drive] clears it on resume, so a paused keep resumes from its segments.
 * Cancel removes the download — and with it, its cached bytes.
 */
@OptIn(UnstableApi::class)
class HlsKeepDownloader(private val context: Context) {

    private val appContext: Context = context.applicationContext

    /** Last failure exception per download id, for classifying 402/404/410. */
    private val lastErrors = ConcurrentHashMap<String, Exception?>()

    private val manager: DownloadManager
        get() = MediaDownloadCache.get(appContext).downloadManager

    init {
        // One listener per process (this class is a Hilt @Singleton): it only
        // records the failure exception, so the drive loop can tell a gated
        // rung from a rotted URL from a transient network failure.
        runCatching {
            manager.addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?,
                ) {
                    if (finalException != null) {
                        lastErrors[download.request.id] = finalException
                    }
                }
            })
        }
    }

    /** Enqueue (or resume) the HLS keep for [sourceKey] from [manifestUrl]. */
    fun enqueue(sourceKey: String, manifestUrl: String): Boolean {
        val request = DownloadRequest.Builder(sourceKey, android.net.Uri.parse(manifestUrl))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        return try {
            DownloadService.sendAddDownload(
                appContext,
                Media3HlsDownloadService::class.java,
                request,
                /* foreground = */ true,
            )
            true
        } catch (_: Exception) {
            // The foreground service could not start (background limits) —
            // queue it in the manager directly; it runs while the app lives.
            runCatching { manager.addDownload(request); true }.getOrDefault(false)
        }
    }

    /** Current state of one keep's Media3 download, or NOT_STARTED when there is none. */
    fun stateFor(sourceKey: String): HlsKeepDownloadState {
        val download = manager.downloadIndex.getDownload(sourceKey) ?: return HlsKeepDownloadState.NOT_STARTED
        return mapHlsKeepState(download.state, download.stopReason)
    }

    /** True when the Media3 download for [sourceKey] is complete — playable offline. */
    fun isComplete(sourceKey: String): Boolean =
        manager.downloadIndex.getDownload(sourceKey)?.state == Download.STATE_COMPLETED

    /** Bytes downloaded so far for one keep, or 0 when there is no download. */
    fun bytesDownloaded(sourceKey: String): Long =
        manager.downloadIndex.getDownload(sourceKey)?.bytesDownloaded ?: 0L

    /**
     * The manifest URL the manager actually fetched for [sourceKey] — the
     * address the player must open for the cache to hit. The task row's
     * `currentUrl` may have been re-resolved since the download was
     * enqueued; the request URI is the one the cache keyed on.
     */
    fun manifestUrlFor(sourceKey: String): String? =
        manager.downloadIndex.getDownload(sourceKey)?.request?.uri?.toString()

    /** Stop one keep's download, keeping its bytes for a later resume. */
    fun pause(sourceKey: String) {
        runCatching {
            DownloadService.sendSetStopReason(
                appContext,
                Media3HlsDownloadService::class.java,
                sourceKey,
                STOP_REASON_USER_PAUSED,
                /* foreground = */ false,
            )
        }
    }

    /** Remove one keep's download and its cached bytes. */
    fun remove(sourceKey: String) {
        lastErrors.remove(sourceKey)
        runCatching {
            DownloadService.sendRemoveDownload(
                appContext,
                Media3HlsDownloadService::class.java,
                sourceKey,
                /* foreground = */ false,
            )
        }.onFailure {
            runCatching { manager.removeDownload(sourceKey) }
        }
    }

    /**
     * Drive one HLS keep until it completes or fails, polling the manager.
     * Progress reports (downloaded, total) to [onProgress] for the task row.
     * Cancellation stops the Media3 download (bytes kept) and rethrows, so
     * the coordinator's pause/pre-empt keeps its meaning.
     */
    suspend fun drive(
        task: CacheTaskEntity,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): HlsKeepDriveOutcome {
        val id = task.sourceKey
        val existing = manager.downloadIndex.getDownload(id)
        when {
            existing == null -> {
                // Absent from the index — never added, or removed entirely
                // (Media3 has no STATE_REMOVED; a removed download is gone).
                if (!enqueue(id, task.currentUrl)) {
                    return HlsKeepDriveOutcome.Failed("hls_enqueue_failed")
                }
            }
            existing.state == Download.STATE_FAILED -> {
                // Re-adding the same id resumes from the segments already in
                // the cache — a failed download is not a lost download.
                if (!enqueue(id, task.currentUrl)) {
                    return HlsKeepDriveOutcome.Failed("hls_enqueue_failed")
                }
            }
            existing.stopReason != Download.STOP_REASON_NONE -> {
                runCatching {
                    DownloadService.sendSetStopReason(
                        appContext,
                        Media3HlsDownloadService::class.java,
                        id,
                        Download.STOP_REASON_NONE,
                        /* foreground = */ true,
                    )
                }
            }
            existing.state == Download.STATE_COMPLETED -> {
                return HlsKeepDriveOutcome.Completed(existing.bytesDownloaded)
            }
        }
        var notStartedPolls = 0
        try {
            while (true) {
                coroutineContext.ensureActive()
                val download = manager.downloadIndex.getDownload(id)
                val state = download?.let { mapHlsKeepState(it.state, it.stopReason) }
                    ?: HlsKeepDownloadState.NOT_STARTED
                when (state) {
                    HlsKeepDownloadState.COMPLETED -> {
                        return HlsKeepDriveOutcome.Completed(download!!.bytesDownloaded)
                    }
                    HlsKeepDownloadState.FAILED -> {
                        return classifyFailure(id, download!!)
                    }
                    HlsKeepDownloadState.NOT_STARTED -> {
                        if (++notStartedPolls > MAX_NOT_STARTED_POLLS) {
                            return HlsKeepDriveOutcome.Failed("hls_enqueue_failed")
                        }
                    }
                    else -> {
                        onProgress(
                            download!!.bytesDownloaded,
                            download.contentLength.coerceAtLeast(0L),
                        )
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        } catch (cancelled: CancellationException) {
            // A pause or pre-empt stops the download but keeps its bytes —
            // the next drive clears the stop reason and resumes from them.
            pause(id)
            throw cancelled
        }
    }

    /**
     * Classify a failed download from the exception the listener recorded.
     * A 402 with no bytes yet is OUR gate refusing the manifest (the
     * manifest is the first fetch — a segment 402 arrives after bytes
     * exist); 404/410/403 is a rotted URL; anything else is transient.
     */
    private fun classifyFailure(id: String, download: Download): HlsKeepDriveOutcome {
        val code = (lastErrors[id] as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return when {
            code == GATE_REFUSED_HTTP_STATUS && download.bytesDownloaded == 0L ->
                HlsKeepDriveOutcome.Gated
            code == 404 || code == 410 || code == 403 -> HlsKeepDriveOutcome.SourceDead
            else -> HlsKeepDriveOutcome.Failed("hls_failed")
        }
    }

    companion object {
        /** Stop reason for a user-paused keep — any non-zero app reason pauses the download. */
        const val STOP_REASON_USER_PAUSED = 1

        private const val POLL_INTERVAL_MS = 1_000L

        /** How long the drive waits for the manager to register a new download (~30 s). */
        private const val MAX_NOT_STARTED_POLLS = 30

        /**
         * Pure Media3-state mapping, kept testable without a [DownloadManager]:
         * a stopped download reads PAUSED (not DOWNLOADING), and anything
         * that is not in flight reads NOT_STARTED so the drive re-adds it.
         */
        internal fun mapHlsKeepState(state: Int, stopReason: Int): HlsKeepDownloadState = when (state) {
            Download.STATE_COMPLETED -> HlsKeepDownloadState.COMPLETED
            Download.STATE_FAILED -> HlsKeepDownloadState.FAILED
            Download.STATE_QUEUED, Download.STATE_RESTARTING -> HlsKeepDownloadState.QUEUED
            Download.STATE_DOWNLOADING ->
                if (stopReason != Download.STOP_REASON_NONE) HlsKeepDownloadState.PAUSED
                else HlsKeepDownloadState.DOWNLOADING
            // STOPPED, REMOVING, and anything else: no download is in
            // flight. (Media3 1.3.1 has no STATE_REMOVED — a removed
            // download is simply absent from the index, and the caller
            // reads NOT_STARTED for it through the null path.)
            else -> HlsKeepDownloadState.NOT_STARTED
        }
    }
}
