package com.raddstream.app.data.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.raddstream.app.MainActivity
import com.raddstream.app.brand.Brand
import com.raddstream.app.data.cache.ConnectivityProbe
import com.raddstream.app.diagnostics.DiagnosticsBus
import com.raddstream.app.diagnostics.DiagnosticsSeverity
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Phase 4 (Downloads #5): a non-2xx answer from our own download endpoint,
 * typed so the failure kind rides with the failure instead of a bare
 * `IOException("HTTP ${code}")` whose message would end up painted on the
 * Downloads row. The message stays as the stored diagnostic; the screen
 * never sees it.
 */
internal class DownloadHttpException(val code: Int) : IOException("HTTP $code")

/**
 * The one writer of public downloads. A foreground service is used instead of
 * `DownloadManager` because the platform's public API has no pause/resume (only remove),
 * while the requirement is an honest pause that keeps its bytes.
 *
 * Every request goes to our own `/api/v1/download?id=<handle>` endpoint; OkHttp follows the
 * 302, so the upstream location never reaches this process. Bytes land in
 * `Movies/RaddStream/` — through MediaStore on Android 10+, through the public directory on
 * older devices. The notification carries real progress and Pause / Resume / Cancel.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var repo: DeviceDownloadsRepository

    @Inject lateinit var connectivityProbe: ConnectivityProbe

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ArrayDeque<String>()
    private var activeId: String? = null
    private var job: Job? = null
    private var foreground = false

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val notifications by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress for videos saved to Movies/RaddStream."
                setShowBadge(false)
            }
            notifications.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID).orEmpty()
        when (intent?.action) {
            ACTION_START -> if (id.isNotBlank()) {
                if (activeId != id && !queue.contains(id)) queue.addLast(id)
                ensureForeground(id)
                pump()
            }

            ACTION_PAUSE -> if (id.isNotBlank()) {
                queue.remove(id)
                if (activeId == id) job?.cancel()
                val current = repo.entity(id)
                if (current != null) {
                    repo.markPaused(id, current.bytesDownloaded)
                    notifyPaused(current)
                }
                finishIfIdle()
            }

            ACTION_CANCEL -> if (id.isNotBlank()) {
                queue.remove(id)
                if (activeId == id) job?.cancel()
                repo.entity(id)?.let { repo.deleteOutput(it) }
                deleteSegmentTemp(id)
                repo.removeEntry(id)
                finishIfIdle()
            }

            null -> {
                // A restart by the system with redelivered state: recover unfinished tasks and pump.
                repo.autoResumeInterrupted()
                repo.nextQueued()?.let { queue.addLast(it.id) }
                pump()
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun pump() {
        if (activeId != null) return
        val next = queue.firstOrNull { repo.entity(it)?.state == DeviceDownloadState.QUEUED }
            ?: run {
                finishIfIdle()
                return
            }
        queue.remove(next)
        activeId = next
        job = scope.launch {
            try {
                download(next)
            } finally {
                activeId = null
                job = null
                pump()
            }
        }
    }

    private suspend fun download(id: String): Unit = withContext(Dispatchers.IO) {
        val entry = repo.entity(id) ?: return@withContext
        if (entry.state != DeviceDownloadState.QUEUED) return@withContext

        // R3: a file big enough to split, on unmetered Wi-Fi, goes segmented —
        // parallel Range segments with a per-segment resume journal. A host
        // that will not do Range falls back to the single connection below.
        // A single-connection resume offset (bytes already in the public
        // file, no journal) stays single: segmenting would throw the
        // head-start away and re-download from zero into a temp file.
        val hasJournal = repo.loadSegmentJournal(id) != null
        val segments = segmentCountFor(
            ConnectivityProbe.current(this@DownloadService),
            connectivityProbe.isMetered(),
            entry.bytesTotal,
        )
        if (segments >= 2 && (entry.bytesDownloaded == 0L || hasJournal)) {
            when (downloadSegmented(id, entry, segments)) {
                SegmentedOutcome.FallbackToSingle -> {
                    clearSegmentState(id)
                    // Fall through to the single connection below.
                }
                else -> return@withContext
            }
        }
        downloadSingle(id, entry)
    }

    /**
     * R3: segmented parallel download into a pre-allocated temp file, merged
     * into the MediaStore destination only when every segment is done. The
     * MediaStore entry is created at merge time, never upfront — a PARTIAL
     * download must not leave a pending row in Movies/RaddStream. Returns the
     * outcome so the caller knows whether to fall back to a single connection.
     */
    private suspend fun downloadSegmented(
        id: String,
        entry: DeviceDownloadEntity,
        segmentCount: Int,
    ): SegmentedOutcome {
        val total = entry.bytesTotal
        val tempFile = segmentTempFile(id)
        tempFile.parentFile?.mkdirs()
        var latestJournal = repo.loadSegmentJournal(id)?.takeIf { it.totalBytes == total }
            ?: SegmentJournal.fresh(total, planSegments(total, segmentCount)).also {
                repo.saveSegmentJournal(id, it)
            }
        var downloaded = latestJournal.bytesDownloaded()
        var lastPersist = 0L
        repo.markRunning(id, downloaded, total)
        notifyProgress(id, entry, downloaded, total)
        val engine = SegmentedDownloader(OkHttpSegmentFetcher(client))
        val outcome = try {
            engine.download(entry.url, tempFile, latestJournal) { done, snapshot ->
                downloaded = done
                latestJournal = snapshot
                val now = SystemClock.elapsedRealtime()
                if (now - lastPersist >= NOTIFY_INTERVAL_MS) {
                    lastPersist = now
                    repo.saveSegmentJournal(id, snapshot)
                    repo.markProgress(id, done, total)
                    notifyProgress(id, entry, done, total)
                }
            }
        } catch (cancelled: CancellationException) {
            // Pause keeps everything: the temp file and the journal are the
            // resume proof. The repo's byte count is display-only; the journal
            // is what the next run trusts.
            repo.saveSegmentJournal(id, latestJournal)
            repo.entity(id)?.let { repo.markPaused(id, downloaded) }
            throw cancelled
        }
        return when (outcome) {
            is SegmentedOutcome.Complete -> {
                repo.saveSegmentJournal(id, outcome.journal)
                mergeSegmented(id, entry, tempFile, total, outcome.contentType)
                outcome
            }
            is SegmentedOutcome.Partial -> {
                repo.saveSegmentJournal(id, outcome.journal)
                val reason = "${outcome.missingSegments.size} of " +
                    "${outcome.journal.segments.size} parts did not finish."
                repo.markPartial(id, outcome.journal.bytesDownloaded(), total, reason)
                repo.entity(id)?.let { notifyPartial(it, outcome.journal.bytesDownloaded(), total) }
                outcome
            }
            is SegmentedOutcome.Failed -> {
                clearSegmentState(id)
                repo.markFailed(id, outcome.journal.bytesDownloaded(), total, outcome.reason, errorKind = outcome.kind)
                repo.entity(id)?.let { notifyFailed(it, outcome.journal.bytesDownloaded(), total) }
                outcome
            }
            SegmentedOutcome.FallbackToSingle -> outcome
        }
    }

    /**
     * Merge a fully-segmented temp file into the public destination, through
     * the same completion gate as the single connection: a partial or
     * wrong-type file is NEVER marked READY. A merge interrupted by a pause
     * keeps the temp file and the (complete) journal, so a Resume re-merges
     * instead of re-downloading.
     */
    private suspend fun mergeSegmented(
        id: String,
        entry: DeviceDownloadEntity,
        tempFile: File,
        total: Long,
        contentType: String,
    ) {
        val defect = completionDefect(total, total, contentType)
        if (defect != null) {
            clearSegmentState(id)
            repo.markFailed(id, total, total, defect, errorKind = DeviceDownloadErrorKind.INCOMPLETE_FILE)
            repo.entity(id)?.let { repo.deleteOutput(it) }
            repo.entity(id)?.let { notifyFailed(it, total, total) }
            return
        }
        val target = prepareOutput(entry)
        repo.setOutputUri(id, target.uri.toString())
        try {
            openOutput(target.uri, false).use { out ->
                tempFile.inputStream().use { input ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
                out.flush()
            }
        } catch (cancelled: CancellationException) {
            repo.markPaused(id, total)
            throw cancelled
        } catch (failure: Throwable) {
            clearSegmentState(id)
            repo.markFailed(
                id, total, total,
                failure.message?.takeIf { it.isNotBlank() }
                    ?: "The download could not be saved.",
                errorKind = DeviceDownloadErrorKind.SAVE_FAILED,
            )
            repo.entity(id)?.let { notifyFailed(it, total, total) }
            return
        }
        finalizeOutput(target.uri, entry.mimeType)
        repo.markReady(id, total, total, target.uri.toString())
        tempFile.delete()
        repo.clearSegmentJournal(id)
        notifyCompleted(entry.title)
    }

    /** The app-private temp file one segmented download assembles into. */
    private fun segmentTempFile(id: String): File =
        File(File(cacheDir, SEGMENT_DIR), "$id.tmp")

    /** Drop a segmented download's temp file and journal (finished, failed hard, or removed). */
    private fun clearSegmentState(id: String) {
        deleteSegmentTemp(id)
        repo.clearSegmentJournal(id)
    }

    private fun deleteSegmentTemp(id: String) {
        runCatching { segmentTempFile(id).delete() }
    }

    private suspend fun downloadSingle(id: String, entry: DeviceDownloadEntity): Unit = withContext(Dispatchers.IO) {
        var downloaded = entry.bytesDownloaded.coerceAtLeast(0L)
        var total = entry.bytesTotal
        var target: OutputTarget? = null
        var output: OutputStream? = null
        var contentType = ""
        try {
            target = prepareOutput(entry)
            repo.setOutputUri(id, target.uri.toString())

            // A resume is only safe when the stored offset is the destination's EXACT length.
            // A kill mid-write can leave the file ahead of the last persisted figure (append
            // would then duplicate the difference inside the video) or behind it (a gap). The
            // same rule as a provider's append refusal: the only correct restart is a fresh
            // request from zero. An unmeasurable length is trusted and proceeds.
            if (downloaded > 0) {
                val onDisk = outputLength(target)
                if (onDisk >= 0 && onDisk != downloaded) {
                    repo.resetResume(id)
                    downloaded = 0L
                }
            }

            val builder = Request.Builder()
                .url(entry.url)
                .header("User-Agent", Brand.userAgent)
            if (downloaded > 0) builder.header("Range", "bytes=$downloaded-")
            val response = client.newCall(builder.build()).execute()
            response.use { answer ->
                if (!answer.isSuccessful) throw DownloadHttpException(answer.code)
                val body = answer.body ?: throw IOException("empty body")
                contentType = answer.header("Content-Type").orEmpty()
                if (downloaded > 0 && answer.code != 206) downloaded = 0L
                total = totalBytes(answer.header("Content-Range"), body.contentLength(), downloaded)
                if (total <= 0L) total = entry.bytesTotal
                // Some providers refuse append mode. A Range response starts at the old
                // offset, so the only correct restart is a fresh request from zero.
                val stream = try {
                    openOutput(target.uri, downloaded > 0)
                } catch (refusal: IOException) {
                    if (downloaded == 0L) throw refusal
                    repo.resetResume(id)
                    val fresh = repo.entity(id) ?: return@withContext
                    downloadSingle(id, fresh)
                    return@withContext
                }
                output = stream
                repo.markRunning(id, downloaded, total)
                notifyProgress(id, entry, downloaded, total)

                body.byteStream().use { input ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    var lastNotify = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        stream.write(buffer, 0, read)
                        downloaded += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNotify >= NOTIFY_INTERVAL_MS) {
                            lastNotify = now
                            repo.markProgress(id, downloaded, total)
                            notifyProgress(id, entry, downloaded, total)
                        }
                    }
                }
            }
            output?.flush()
            output?.close()
            output = null
            // Audit critical 9: never mark a partial or empty file READY. OkHttp's final
            // response carries the Content-Type after redirects, so this judges what actually
            // arrived, not what the catalog claimed. An unknown total cannot prove coverage,
            // so it fails too.
            val defect = completionDefect(downloaded, total, contentType)
            if (defect != null) {
                repo.markFailed(id, downloaded, total, defect, errorKind = DeviceDownloadErrorKind.INCOMPLETE_FILE)
                repo.entity(id)?.let { repo.deleteOutput(it) }
                repo.resetResume(id)
                repo.entity(id)?.let { notifyFailed(it, downloaded, total) }
                return@withContext
            }
            finalizeOutput(target.uri, entry.mimeType)
            repo.markReady(id, downloaded, total, target.uri.toString())
            notifyCompleted(entry.title)
        } catch (cancelled: CancellationException) {
            closeQuietly(output)
            // The coroutine owns the exact byte count it wrote, so its pause offset is the only
            // authoritative one. The service's pause handler already stamped PAUSED with a value
            // from the throttled snapshot (up to NOTIFY_INTERVAL_MS + one chunk behind this
            // local); overwriting it here is what keeps a resumed Range request aligned with the
            // file's real end. A resumed download opens in append mode, so an offset SHORTER
            // than the file duplicates the gap between the two inside the video — which is why
            // this write must not be skipped just because the state already reads PAUSED.
            // A deleted row (cancel) is gone from the store, so it is not resurrected here.
            if (repo.entity(id) != null) {
                repo.markPaused(id, downloaded)
            }
            throw cancelled
        } catch (failure: Throwable) {
            closeQuietly(output)
            val now = repo.entity(id)
            if (now != null && now.state != DeviceDownloadState.PAUSED) {
                // The real exception, not the default sentence: a disk-full
                // failure and a network failure must not store the same words.
                // The message stays the stored diagnostic; the row paints the
                // mapped sentence for the kind, never this string.
                val reason = failure.message?.takeIf { it.isNotBlank() }
                    ?: "Download stopped before it finished."
                repo.markFailed(
                    id, downloaded, total, reason,
                    errorKind = downloadFailureKind(failure),
                )
                notifyFailed(now, downloaded, total, reason)
            }
        }
    }

    private data class OutputTarget(val uri: Uri, val isMediaStore: Boolean)

    private fun prepareOutput(entry: DeviceDownloadEntity): OutputTarget {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val existing = entry.localUri
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?.takeIf { it.scheme == "content" }
            if (existing != null) return OutputTarget(existing, true)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, entry.fileName)
                put(MediaStore.Video.Media.MIME_TYPE, entry.mimeType)
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$SUBDIR",
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore refused the destination")
            return OutputTarget(uri, true)
        }
        val file = publicFile(entry.fileName)
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
        return OutputTarget(Uri.fromFile(file), false)
    }

    /** The destination's current byte length, or -1 when the provider will not say. */
    private fun outputLength(target: OutputTarget): Long = when {
        !target.isMediaStore -> runCatching { File(requireNotNull(target.uri.path)).length() }.getOrDefault(-1L)
        else -> runCatching {
            contentResolver.openAssetFileDescriptor(target.uri, "r")?.use { afd ->
                if (afd.length >= 0) afd.length else -1L
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun openOutput(uri: Uri, append: Boolean): OutputStream {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
            val mode = if (append) "wa" else "w"
            return contentResolver.openOutputStream(uri, mode)
                ?: throw IOException("could not open the download destination")
        }
        val file = File(requireNotNull(uri.path))
        return FileOutputStream(file, append)
    }

    private fun finalizeOutput(uri: Uri, mime: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            runCatching { contentResolver.update(uri, values, null, null) }
        } else {
            uri.path?.let { path ->
                runCatching {
                    MediaScannerConnection.scanFile(this, arrayOf(path), arrayOf(mime), null)
                }
            }
        }
    }

    private fun publicFile(fileName: String): File = File(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), SUBDIR),
        fileName,
    )

    private fun totalBytes(contentRange: String?, contentLength: Long, already: Long): Long {
        contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            ?.let { return it }
        if (contentLength > 0) return already + contentLength
        return 0L
    }

    private fun closeQuietly(output: OutputStream?) {
        runCatching { output?.close() }
    }

    // ── notification ────────────────────────────────────────────────────────────

    private fun ensureForeground(id: String) {
        val entry = repo.entity(id) ?: return
        if (foreground) return
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(entry, entry.state, entry.bytesDownloaded, entry.bytesTotal),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        foreground = true
    }

    private fun notifyProgress(
        id: String,
        entry: DeviceDownloadEntity,
        downloaded: Long,
        total: Long,
    ) {
        val current = repo.entity(id) ?: entry
        if (!foreground || current.state == DeviceDownloadState.PAUSED) return
        notifications.notify(
            NOTIFICATION_ID,
            buildNotification(current, DeviceDownloadState.DOWNLOADING, downloaded, total),
        )
    }

    private fun notifyPaused(entry: DeviceDownloadEntity) {
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            foreground = false
        }
        notifications.notify(
            NOTIFICATION_ID,
            buildNotification(entry, DeviceDownloadState.PAUSED, entry.bytesDownloaded, entry.bytesTotal),
        )
    }

    private fun notifyPartial(entry: DeviceDownloadEntity, downloaded: Long, total: Long) {
        notifications.notify(
            NOTIFICATION_ID,
            buildNotification(entry, DeviceDownloadState.PARTIAL, downloaded, total),
        )
    }

    private fun notifyFailed(
        entry: DeviceDownloadEntity,
        downloaded: Long,
        total: Long,
        reason: String? = null,
    ) {
        // APP-13: the failed download joins the diagnostics timeline with
        // its title, print label (quality language), bytes, and the failure
        // reason — never the raw exception, never the URL. The reason is
        // passed explicitly because one call site notifies with the
        // pre-failure entity snapshot, whose stored error is stale.
        DiagnosticsBus.recordDownload(
            severity = DiagnosticsSeverity.ERROR,
            summary = "Download failed: ${entry.title}",
            title = entry.title,
            quality = entry.printLabel.takeIf { it.isNotBlank() },
            detail = "$downloaded of $total bytes",
            error = reason ?: entry.error,
        )
        notifications.notify(
            NOTIFICATION_ID,
            buildNotification(entry, DeviceDownloadState.FAILED, downloaded, total),
        )
    }

    private fun notifyCompleted(title: String) {
        // APP-13: the completed download joins the timeline (INFO — the
        // Problems tab never shows it, the Timeline does).
        DiagnosticsBus.recordDownload(
            severity = DiagnosticsSeverity.INFO,
            summary = "Download complete: $title",
            title = title,
        )
        notifications.notify(COMPLETE_NOTIFICATION_ID, completedNotification(title))
    }

    private fun buildNotification(
        entry: DeviceDownloadEntity,
        state: DeviceDownloadState,
        downloaded: Long,
        total: Long,
    ): Notification {
        val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
        val text = when (state) {
            DeviceDownloadState.QUEUED -> "Queued"
            // Phase 4 (Downloads #6): the same percent + bytes + speed +
            // ETA as the row — never percent alone.
            DeviceDownloadState.DOWNLOADING -> {
                val (rate, eta) = repo.liveRate(entry.id) ?: (0L to null)
                downloadNotificationText(downloaded, total, rate, eta)
            }
            DeviceDownloadState.PAUSED -> "Paused — ${formatBytes(downloaded)} saved"
            DeviceDownloadState.READY -> "Saved to Movies/$SUBDIR"
            DeviceDownloadState.FAILED -> "Stopped — open Downloads to resume"
            DeviceDownloadState.PARTIAL -> "Partially saved — retry to finish the missing parts"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(entry.title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .setOngoing(state == DeviceDownloadState.DOWNLOADING ||
                state == DeviceDownloadState.QUEUED)
        if (state == DeviceDownloadState.DOWNLOADING && total > 0) {
            builder.setProgress(100, percent, false)
        } else if (state == DeviceDownloadState.DOWNLOADING) {
            builder.setProgress(0, 0, true)
        }
        when (state) {
            DeviceDownloadState.DOWNLOADING, DeviceDownloadState.QUEUED ->
                builder.addAction(0, "Pause", serviceIntent(ACTION_PAUSE, entry.id))
            DeviceDownloadState.PAUSED, DeviceDownloadState.FAILED, DeviceDownloadState.PARTIAL ->
                builder.addAction(0, "Resume", resumeIntent(entry.id))
            else -> Unit
        }
        builder.addAction(0, "Cancel", serviceIntent(ACTION_CANCEL, entry.id))
        return builder.build()
    }

    private fun completedNotification(title: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("Download complete · Movies/$SUBDIR")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun serviceIntent(action: String, id: String): PendingIntent =
        PendingIntent.getService(
            this,
            (action + id).hashCode(),
            Intent(this, DownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun resumeIntent(id: String): PendingIntent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                (ACTION_START + id).hashCode(),
                Intent(this, DownloadService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            serviceIntent(ACTION_START, id)
        }

    private fun openAppIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun finishIfIdle() {
        if (activeId != null) return
        val pending = repo.nextQueued()
        if (pending != null) {
            queue.addLast(pending.id)
            pump()
            return
        }
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "radd_downloads"

        /** "450 MB", "1.0 GB" — the decimal units the rows use. */
        internal fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }

        /**
         * Phase 4 (Downloads #5): a download failure → its stable kind.
         * A 404/410 means the banked link is dead (retry must re-resolve);
         * 403 is a refusal, not a retry; 429 is honoured, never hammered;
         * anything else is a dropped transfer. Pure, so a unit test pins it.
         */
        internal fun downloadFailureKind(failure: Throwable): String = when (failure) {
            is DownloadHttpException -> when (failure.code) {
                404, 410 -> DeviceDownloadErrorKind.LINK_EXPIRED
                403 -> DeviceDownloadErrorKind.HOST_REFUSED
                429 -> DeviceDownloadErrorKind.RATE_LIMITED
                else -> DeviceDownloadErrorKind.TRANSFER_FAILED
            }
            else -> DeviceDownloadErrorKind.TRANSFER_FAILED
        }

        /**
         * Phase 4 (Downloads #6): the foreground notification carries the
         * same percent + bytes + speed + ETA as the row — "Downloading 45% ·
         * 450 MB / 1.0 GB · 12.0 MB/s · about 1 min left" — never percent
         * alone. Pure, so a unit test pins it without a service.
         */
        internal fun downloadNotificationText(
            downloaded: Long,
            total: Long,
            bytesPerSec: Long,
            etaSeconds: Long?,
        ): String {
            if (downloaded <= 0L && total <= 0L) return "Downloading — starting…"
            val percent = if (total > 0) {
                "${((downloaded * 100) / total).toInt().coerceIn(0, 100)}% · "
            } else {
                ""
            }
            val bytes = if (total > 0) {
                "${formatBytes(downloaded)} / ${formatBytes(total)}"
            } else {
                formatBytes(downloaded)
            }
            val speed = if (bytesPerSec > 0) {
                DownloadSpeed.formatSpeed(bytesPerSec) +
                    (DownloadSpeed.formatEta(etaSeconds)?.let { " · $it" } ?: "")
            } else {
                "calculating…"
            }
            return "Downloading $percent$bytes · $speed"
        }
        private const val NOTIFICATION_ID = 7101
        private const val COMPLETE_NOTIFICATION_ID = 7102
        private const val EXTRA_ID = "download_id"
        private const val ACTION_START = "com.raddstream.app.downloads.START"
        private const val ACTION_PAUSE = "com.raddstream.app.downloads.PAUSE"
        private const val ACTION_CANCEL = "com.raddstream.app.downloads.CANCEL"
        private const val SUBDIR = "RaddStream"
        private const val SEGMENT_DIR = "segmented"
        private const val CHUNK_BYTES = 64 * 1024
        private const val NOTIFY_INTERVAL_MS = 750L

        /** Null when the transfer provably completed as video; otherwise the failure reason. */
        internal fun completionDefect(downloaded: Long, total: Long, contentType: String): String? =
            when {
                downloaded <= 0L -> "Downloaded nothing."
                total <= 0L -> "Server did not report a size, so completeness cannot be verified."
                downloaded < total -> "Incomplete: $downloaded of $total bytes."
                !contentType.startsWith("video/", ignoreCase = true) ->
                    "Unexpected content type: ${contentType.ifBlank { "none" }}."
                else -> null
            }

        fun start(context: Context, id: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context, id: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_PAUSE)
                .putExtra(EXTRA_ID, id)
            runCatching { context.startService(intent) }
        }

        fun cancel(context: Context, id: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_ID, id)
            runCatching { context.startService(intent) }
        }
    }
}
