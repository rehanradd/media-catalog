package com.raddstream.app.data.downloads

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.raddstream.app.data.cache.MegaProxyHolder
import com.raddstream.app.data.cache.MegaStartError
import com.raddstream.app.network.ApiProvider
import com.raddstream.app.network.buildRaddGson
import com.raddstream.app.network.downloadRedirectUrl
import com.raddstream.app.network.withVipPass
import com.raddstream.app.sync.SyncAccountRepository
import com.raddstream.app.viewmodel.deviceFailureReasonFor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The six states a public download can be in. PARTIAL is a download that
 * ended with some segments done and some missing after all retries (R3):
 * the bytes and the segment journal are kept, the file is NEVER marked
 * READY, and a Resume retries only the missing segments.
 */
enum class DeviceDownloadState { QUEUED, DOWNLOADING, PAUSED, READY, FAILED, PARTIAL }

/**
 * Stable failure kinds for device downloads (Phase 4, Downloads #5). The
 * kind is what the row's viewer sentence is mapped from; the stored
 * free-text `error` is a diagnostic only and is never displayed. One
 * spelling, in one place, so a typo cannot silently unmap a failure.
 */
object DeviceDownloadErrorKind {
    const val LINK_EXPIRED = "link_expired"
    const val HOST_REFUSED = "host_refused"
    const val RATE_LIMITED = "rate_limited"
    const val TRANSFER_FAILED = "transfer_failed"
    const val INCOMPLETE_FILE = "incomplete_file"
    const val SAVE_FAILED = "save_failed"
}

/**
 * D11: a download whose byte count has not moved for longer than this
 * reads as stalled, not "calculating…" — on both the device rows (tracked
 * in [DeviceDownloadsRepository.markProgress]) and the keep rows (tracked
 * in the ViewModel's per-task marks). Thirty seconds: long enough that a
 * slow start or a single slow chunk never trips it.
 */
internal const val DOWNLOAD_STALL_AFTER_MS = 30_000L

/** One public download as the Downloads screen and the service both see it. */
data class DeviceDownload(
    val id: String,
    val title: String,
    val printLabel: String,
    val state: DeviceDownloadState,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val localUri: String?,
    /**
     * The opaque handle this download was enqueued by — the same
     * stream id a detail print or quality rung keeps by. The detail
     * screen matches episode rows against it so a row can say it is
     * already on the device; the id above is a UUID and names nothing
     * outside this store.
     */
    val handle: String = "",
    val mimeType: String,
    val fileName: String,
    /**
     * App-design audit #12: the rolling speed (bytes/second) and the ETA
     * derived from it — 0 / null while calculating, never a guessed number.
     * Live only: a persisted row reloads with no samples, so it reads as
     * calculating until the next progress tick.
     */
    val bytesPerSec: Long = 0L,
    val etaSeconds: Long? = null,
    /**
     * Why this download failed, in viewer words — the stored
     * [DeviceDownloadEntity.error] (the completion gate's specific reason,
     * or the exception that stopped the transfer). Null when the download
     * never failed, or when there is nothing specific to say.
     *
     * Phase 4 (Downloads #5): the stored string is a diagnostic only — it
     * can hold raw backend words ("HTTP 404", byte counts, content types)
     * and must never be painted. What the row paints comes from
     * [errorKind] through [deviceFailureReasonFor] instead; see [toUi].
     */
    val failureReason: String? = null,
    /**
     * The stable failure kind behind [failureReason] — `link_expired`,
     * `host_refused`, `rate_limited`, `transfer_failed`, `incomplete_file`,
     * `save_failed`, or null when the failure was never classified. Old
     * persisted rows carry null and fall back to the generic status line.
     */
    val errorKind: String? = null,
    /**
     * D12: how many times this download has been (re)started — the row
     * names its attempt when it fails. 0 when the count was never recorded
     * (an old persisted row); the row normalizes it to at least 1.
     */
    val attempts: Int = 0,
    /**
     * D11: true while this download is DOWNLOADING and its byte count has
     * not moved for longer than [DOWNLOAD_STALL_AFTER_MS] — the row says
     * "Stalled", not "calculating…". Computed in [toUi] from the
     * last-movement marks; false for every other state.
     */
    val stalled: Boolean = false,
    /**
     * D10: the catalog poster's URL for this file — the server's own poster
     * for the title, carried at enqueue time by the caller that knew it
     * (the Detail screen's post, or Smart Downloads' fetched detail).
     * Null when the enqueue site knew no poster (an old persisted row, or
     * a download started before this field existed): the row paints the
     * initials tile, never a fetched or invented poster.
     */
    val posterUrl: String? = null,
) {
    val progress: Float
        get() = if (bytesTotal > 0) {
            (bytesDownloaded.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val percent: Int?
        get() = if (bytesTotal > 0) (progress * 100).toInt().coerceIn(0, 100) else null

    val playing: Boolean get() = state == DeviceDownloadState.READY && !localUri.isNullOrBlank()
    /**
     * D19: a queued row is not downloading yet — offering it Pause is a
     * contradiction. Only a row actually moving can be paused.
     */
    val canPause: Boolean
        get() = state == DeviceDownloadState.DOWNLOADING
    val canResume: Boolean
        get() = state == DeviceDownloadState.PAUSED ||
            state == DeviceDownloadState.FAILED ||
            state == DeviceDownloadState.PARTIAL

    /**
     * Robo run 9: the same shape as the keeps — a print label identical to
     * the title would paint twice on one card, and two files sharing one
     * quality label would speak identically. The row paints the label only
     * when it says something the title does not already say.
     */
    internal fun shownPrintLabel(): String? =
        printLabel.takeIf { it.isNotBlank() && it != title }

    /**
     * The row's spoken print label. The title rides along so two files that
     * share a quality label never speak identically — the painted text stays
     * the short label, the spoken line names the title too (the run-6
     * printSpokenLabel pattern).
     */
    internal fun printLabelDescription(): String = "$title — $printLabel"
}

/** What one enqueue attempt did, so the caller can say something true to the viewer. */
sealed interface DeviceDownloadResult {
    data class Enqueued(val download: DeviceDownload) : DeviceDownloadResult
    data object NeedsPermission : DeviceDownloadResult
    data class Refused(val message: String) : DeviceDownloadResult
}

/**
 * The persisted half of one public download. [url] is always our own redirector — the server's
 * resolve answer is used only to verify the handle and name the container, never to learn an
 * upstream location. The service owns the bytes; this class owns the durable state and the
 * observable list both screens read.
 */
data class DeviceDownloadEntity(
    val id: String = "",
    val handle: String = "",
    val url: String = "",
    val title: String = "",
    val printLabel: String = "",
    val mimeType: String = "video/mp4",
    val fileName: String = "",
    val bytesDownloaded: Long = 0L,
    val bytesTotal: Long = 0L,
    val state: DeviceDownloadState = DeviceDownloadState.QUEUED,
    val localUri: String? = null,
    val enqueuedAt: Long = 0L,
    val error: String? = null,
    /**
     * Phase 4 (Downloads #5): the stable failure kind behind [error] —
     * see [DeviceDownload.errorKind]. Gson tolerates the missing field on
     * old persisted rows (null). The stored [error] string stays as the
     * diagnostic; it stops being displayed.
     */
    val errorKind: String? = null,
    /**
     * D12: how many times this download has been (re)started — 1 on the
     * first enqueue, +1 on every resume. Old persisted rows carry no
     * field and read 0 (never recorded), never 1. The row names its
     * attempt when it fails, so a retry is a counted try.
     */
    val attempts: Int = 0,
    /**
     * D10: the catalog poster's URL, carried at enqueue time by the
     * caller that knew it — the Detail screen's post, or Smart
     * Downloads' fetched detail. Gson tolerates the missing field on
     * old persisted rows (null): those rows keep the initials tile.
     * Never fetched here and never invented: null means "no poster
     * known", and the row paints the tile.
     */
    val posterUrl: String? = null,
)

/**
 * Public, user-visible downloads written to `Movies/RaddStream/` by [DownloadService].
 * Kept separate from the in-app cache engine: these bytes survive the app and play in
 * any video player.
 *
 * The repository is also the service's durable memory. Progress is held in memory and
 * flushed at a human pace (about once a second) so a long download does not rewrite
 * SharedPreferences for every network chunk, while a pause, failure or completion is
 * always persisted before the UI hears about it.
 */
@Singleton
class DeviceDownloadsRepository @Inject constructor(
    @ApplicationContext val context: Context,
    private val apiProvider: ApiProvider,
    private val syncAccountRepository: SyncAccountRepository,
) {
    private val gson: Gson = buildRaddGson()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _downloads = MutableStateFlow<List<DeviceDownload>>(emptyList())
    val downloads: StateFlow<List<DeviceDownload>> = _downloads.asStateFlow()

    private var persistedAt = HashMap<String, Long>()

    init {
        // A row left mid-flight by a dead process is restored to QUEUED so it
        // can auto-continue without requiring a manual Resume tap.
        val (entries, _) = recoverInterruptedEntries(load())
        persist(entries)
        emit(entries)
    }

    /**
     * Resumes any downloads that are queued or were interrupted mid-flight.
     * Transitions interrupted tasks back to QUEUED and triggers DownloadService.
     */
    fun autoResumeInterrupted() {
        val (entries, changed) = recoverInterruptedEntries(load())
        if (changed) {
            persist(entries)
            emit(entries)
        }
        val next = entries.firstOrNull { it.state == DeviceDownloadState.QUEUED }
        if (next != null) {
            runCatching { DownloadService.start(context, next.id) }
        }
    }

    /** API 28 and older need the legacy write permission; API 29+ writes through MediaStore. */
    fun hasWriteAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * D10: [posterUrl] is the catalog poster's URL when the caller already
     * knows it — the Detail screen's post, or Smart Downloads' fetched
     * detail. It is stored on the row as-is, never fetched or validated
     * here: a blank or unknown poster stays null and the row paints the
     * initials tile. Old callers pass nothing and old rows carry nothing.
     */
    suspend fun enqueue(
        handle: String,
        title: String,
        printLabel: String,
        posterUrl: String? = null,
    ): DeviceDownloadResult = withContext(Dispatchers.IO) {
        if (handle.isBlank()) return@withContext DeviceDownloadResult.Refused(
            "This copy has no address to download.",
        )
        if (!hasWriteAccess()) return@withContext DeviceDownloadResult.NeedsPermission

        val entries = load()
        entries.firstOrNull { it.handle == handle && it.state != DeviceDownloadState.FAILED }
            ?.let { existing ->
                when (existing.state) {
                    DeviceDownloadState.QUEUED ->
                        runCatching { DownloadService.start(context, existing.id) }
                    DeviceDownloadState.PAUSED, DeviceDownloadState.PARTIAL -> requestResume(existing.id)
                    else -> Unit
                }
                return@withContext DeviceDownloadResult.Enqueued(existing.toUi())
            }

        val baseUrl = runCatching { apiProvider.currentBaseUrl() }.getOrNull()
            ?: return@withContext DeviceDownloadResult.Refused(
                "Connect to a catalog server before downloading.",
            )
        val meta = runCatching { apiProvider.current().resolveStream(handle, "0") }.getOrNull()
        if (meta == null || !meta.success || meta.streamId == null) {
            return@withContext DeviceDownloadResult.Refused(
                "The server could not verify this copy just now. Try another version.",
            )
        }
        val mime = videoMime(meta.mimeType, meta.kind)
            ?: return@withContext DeviceDownloadResult.Refused(
                "This copy is a web page or a segmented playlist, not one downloadable video file.",
            )

        val cleanTitle = title.trim().ifBlank { "Video" }
        val cleanLabel = printLabel.trim()
        // Client-side decrypt: this copy's bytes are ciphertext, and the
        // download endpoint answers the descriptor for it, not a 302. Start
        // the decrypting proxy and download clear bytes from its loopback
        // URL. A start failure is a plain refusal — never a download of
        // ciphertext. One proxy serves the whole process: starting a
        // DIFFERENT decrypting copy later stops this one, so a mega download
        // interrupted that way fails and can be resumed, not silently saved
        // as garbage.
        val downloadUrl = if (meta.decrypt?.lowercase() == "aes-ctr") {
            MegaProxyHolder.start(meta) ?: return@withContext DeviceDownloadResult.Refused(
                when (MegaProxyHolder.lastError) {
                    MegaStartError.Quota -> "MEGA's free limit is used up for now — this copy needs a few hours. Try another version."
                    MegaStartError.Dead -> "This copy is gone from MEGA. Try another version."
                    MegaStartError.Network -> "MEGA did not answer right now. Check your connection and try again."
                    else -> "This copy could not be opened. Try another version."
                },
            )
        } else {
            withVipPass(
                // 2026-11-12 rework: in-app downloads fetch through our own
                // `/api/v1/download` endpoint, not the play redirector and
                // never a browser — the server 302s to the file and the
                // download service follows it with Range resume.
                downloadRedirectUrl(baseUrl, handle),
                // Session 123: the same VIP pass rides the download URL, so
                // a VIP download of a premium print saves instead of 402ing.
                syncAccountRepository.currentVipPass(),
            )
        }
        val entry = DeviceDownloadEntity(
            id = UUID.randomUUID().toString(),
            handle = handle,
            url = downloadUrl,
            title = cleanTitle,
            printLabel = cleanLabel,
            mimeType = mime,
            fileName = uniqueFileName(cleanTitle, cleanLabel, extensionFor(mime), entries),
            bytesTotal = meta.fileSizeBytes ?: 0L,
            state = DeviceDownloadState.QUEUED,
            enqueuedAt = System.currentTimeMillis(),
            // D12: the first start is attempt 1 — every resume after it counts up from here.
            attempts = 1,
            // D10: the caller's poster, stored as-is — blank means unknown, never a tile URL.
            posterUrl = posterUrl?.takeIf { it.isNotBlank() },
        )
        val updated = entries + entry
        persist(updated)
        emit(updated)
        val started = runCatching { DownloadService.start(context, entry.id) }.isSuccess
        if (!started) {
            update(entry.id) {
                it.copy(state = DeviceDownloadState.FAILED, error = "The system refused to start the download.")
            }
            return@withContext DeviceDownloadResult.Refused(
                "The system would not start the download. Try again from the app.",
            )
        }
        DeviceDownloadResult.Enqueued(entry.toUi())
    }

    fun requestPause(id: String) {
        val current = load().firstOrNull { it.id == id } ?: return
        if (current.state != DeviceDownloadState.QUEUED &&
            current.state != DeviceDownloadState.DOWNLOADING
        ) {
            return
        }
        update(id) { it.copy(state = DeviceDownloadState.PAUSED) }
        DownloadService.pause(context, id)
    }

    /**
     * Phase 4 (Downloads #5 + APP-05 §3.1): a 404/410 retry must re-resolve
     * the handle first — re-GETting the same redirector URL can only 404
     * again unless the server's bank is refreshed. One user tap = at most
     * one `resolveStream` call; no automatic retry loop, no retry on
     * 429/403. A re-resolved retry restarts from zero by default: the fresh
     * resolve may name a different file than the dead one, and resuming at
     * the old byte offset into a different file corrupts it.
     */
    suspend fun requestResume(id: String) {
        val current = load().firstOrNull { it.id == id } ?: return
        if (current.state != DeviceDownloadState.PAUSED &&
            current.state != DeviceDownloadState.FAILED &&
            current.state != DeviceDownloadState.PARTIAL
        ) {
            return
        }
        if (current.errorKind == DeviceDownloadErrorKind.LINK_EXPIRED) {
            val handle = current.handle
            val meta = runCatching { apiProvider.current().resolveStream(handle, "0") }.getOrNull()
            if (meta == null || !meta.success || meta.streamId == null) {
                // The fresh link could not be minted: stay FAILED with the
                // link_expired kind — the row already says so in viewer
                // words. Do not start the service, do not loop.
                return
            }
            val baseUrl = runCatching { apiProvider.currentBaseUrl() }.getOrNull() ?: return
            val downloadUrl = if (meta.decrypt?.lowercase() == "aes-ctr") {
                // Mega AES-CTR entries take the proxy branch: their URL is a
                // loopback proxy URL, not the redirector. Detect via the
                // fresh resolve metadata, never by sniffing the URL string.
                MegaProxyHolder.start(meta) ?: return
            } else {
                withVipPass(
                    downloadRedirectUrl(baseUrl, handle),
                    syncAccountRepository.currentVipPass(),
                )
            }
            // Restart from zero: the fresh resolve may name a different
            // file, and resetResume also clears the segment journal, whose
            // ranges describe bytes of a download that no longer exists.
            resetResume(id)
            update(id) {
                it.copy(
                    url = downloadUrl,
                    state = DeviceDownloadState.QUEUED,
                    bytesTotal = meta.fileSizeBytes?.takeIf { size -> size > 0 } ?: it.bytesTotal,
                    error = null,
                    errorKind = null,
                    // D12: a re-resolved retry is a fresh start — and a counted one.
                    attempts = it.attempts + 1,
                )
            }
            runCatching { DownloadService.start(context, id) }
            return
        }
        // D12: a plain resume counts as an attempt too.
        update(id) { it.copy(state = DeviceDownloadState.QUEUED, error = null, errorKind = null, attempts = it.attempts + 1) }
        runCatching { DownloadService.start(context, id) }
    }

    fun requestRemove(id: String) {
        val current = load().firstOrNull { it.id == id } ?: return
        if (current.state == DeviceDownloadState.READY ||
            current.state == DeviceDownloadState.FAILED ||
            current.state == DeviceDownloadState.PAUSED
        ) {
            deleteOutput(current)
            removeEntry(id)
        } else {
            // An active writer must close its stream before the file is deleted.
            DownloadService.cancel(context, id)
        }
    }

    internal fun entity(id: String): DeviceDownloadEntity? =
        load().firstOrNull { it.id == id }

    internal fun markRunning(id: String, downloaded: Long, total: Long) {
        updateInMemory(id) {
            if (it.state == DeviceDownloadState.PAUSED ||
                it.state == DeviceDownloadState.FAILED ||
                it.state == DeviceDownloadState.READY
            ) {
                it
            } else {
                it.copy(
                    state = DeviceDownloadState.DOWNLOADING,
                    bytesDownloaded = downloaded,
                    bytesTotal = if (total > 0) total else it.bytesTotal,
                )
            }
        }
    }

    internal fun markProgress(id: String, downloaded: Long, total: Long) {
        // Rolling speed over the last five progress ticks (~250 ms apart):
        // the Downloads row and the notification read the same rate, and a
        // stalled transfer reads as "calculating" instead of a frozen ETA.
        // Computed before markRunning emits, so the row never lags a tick.
        val samples = speedSamples.getOrPut(id) { ArrayDeque() }
        samples.addLast(downloaded to SystemClock.elapsedRealtime())
        while (samples.size > 5) samples.removeFirst()
        val speed = DownloadSpeed.estimateBytesPerSec(samples.toList())
        liveRates[id] = speed to DownloadSpeed.etaSeconds(
            (total - downloaded).coerceAtLeast(0L), speed,
        )
        // D11: the stall clock — the last instant this download's byte count
        // actually moved. A tick that arrives with the same count does not
        // reset it; only real movement does.
        val nowMs = System.currentTimeMillis()
        val prev = lastBytesChange[id]
        if (prev == null || prev.first != downloaded) {
            lastBytesChange[id] = downloaded to nowMs
        }
        markRunning(id, downloaded, total)
        val now = System.currentTimeMillis()
        if (now - (persistedAt[id] ?: 0L) >= PROGRESS_FLUSH_MS) {
            persistedAt[id] = now
            persist(currentEntities())
        }
    }

    /** Drop a download's live rate when it stops moving, so a stale speed never shows. */
    private fun dropLiveRate(id: String) {
        speedSamples.remove(id)
        liveRates.remove(id)
        lastBytesChange.remove(id)
    }

    internal fun markPaused(id: String, downloaded: Long) {
        dropLiveRate(id)
        update(id) {
            it.copy(
                state = DeviceDownloadState.PAUSED,
                bytesDownloaded = downloaded,
                error = null,
                errorKind = null,
            )
        }
    }

    internal fun markFailed(
        id: String,
        downloaded: Long,
        total: Long,
        reason: String = "Download stopped before it finished.",
        errorKind: String? = null,
    ) {
        dropLiveRate(id)
        update(id) {
            it.copy(
                state = DeviceDownloadState.FAILED,
                bytesDownloaded = downloaded,
                bytesTotal = if (total > 0) total else it.bytesTotal,
                error = reason,
                errorKind = errorKind,
            )
        }
    }

    /**
     * R3: a download that ended with some segments done and some missing.
     * The bytes and the segment journal stay exactly where they are — a
     * Resume retries only the missing segments — and the file is NEVER
     * marked READY. The reason names how many parts are missing, so the row
     * can say it instead of hiding it.
     */
    internal fun markPartial(
        id: String,
        downloaded: Long,
        total: Long,
        reason: String,
        errorKind: String? = null,
    ) {
        dropLiveRate(id)
        update(id) {
            it.copy(
                state = DeviceDownloadState.PARTIAL,
                bytesDownloaded = downloaded,
                bytesTotal = if (total > 0) total else it.bytesTotal,
                error = reason,
                errorKind = errorKind,
            )
        }
    }

    /** Persist a segment journal (R3) — the resume proof for one download id. */
    internal fun saveSegmentJournal(id: String, journal: SegmentJournal) {
        prefs.edit().putString(KEY_SEGMENTS + id, journal.encode()).apply()
    }

    /** The stored segment journal for one download id, or null when absent or corrupt. */
    internal fun loadSegmentJournal(id: String): SegmentJournal? =
        SegmentJournal.decode(prefs.getString(KEY_SEGMENTS + id, null))

    /** Drop a segment journal — the download finished, failed hard, or was removed. */
    internal fun clearSegmentJournal(id: String) {
        prefs.edit().remove(KEY_SEGMENTS + id).apply()
    }

    internal fun markReady(id: String, downloaded: Long, total: Long, localUri: String) {
        dropLiveRate(id)
        update(id) {
            it.copy(
                state = DeviceDownloadState.READY,
                bytesDownloaded = downloaded,
                bytesTotal = if (total > 0) total else it.bytesTotal,
                localUri = localUri,
                error = null,
                errorKind = null,
            )
        }
        persistedAt.remove(id)
    }

    internal fun setOutputUri(id: String, uri: String) {
        update(id) { it.copy(localUri = uri) }
    }

    /** Drops the resume offset when the destination cannot append; bytes restart from zero. */
    internal fun resetResume(id: String) {
        // A restart from zero invalidates the segment journal too: its ranges
        // describe bytes of a download that no longer exists.
        clearSegmentJournal(id)
        update(id) { it.copy(bytesDownloaded = 0L) }
    }

    internal fun nextQueued(): DeviceDownloadEntity? =
        load().firstOrNull { it.state == DeviceDownloadState.QUEUED }

    internal fun removeEntry(id: String) {
        val remaining = load().filterNot { it.id == id }
        persistedAt.remove(id)
        dropLiveRate(id)
        clearSegmentJournal(id)
        persist(remaining)
        emit(remaining)
    }

    internal fun deleteOutput(entity: DeviceDownloadEntity) {
        val raw = entity.localUri?.takeIf { it.isNotBlank() } ?: return
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "content" -> runCatching { context.contentResolver.delete(uri, null, null) }
            "file" -> uri.path?.let { runCatching { File(it).delete() } }
        }
    }

    private fun update(id: String, transform: (DeviceDownloadEntity) -> DeviceDownloadEntity) {
        val updated = load().map { if (it.id == id) transform(it) else it }
        persist(updated)
        emit(updated)
    }

    /**
     * Progress is held in memory and flushed at a human pace, so durability must not be a lie:
     * the memory snapshot itself has to advance, or every throttled `persist(currentEntities())`
     * rewrites the stale pre-download state and a resumed download restarts from zero bytes.
     * That made this the ONLY writer that keeps a running byte count durable — every state an
     * entity can carry (DOWNLOADING included) is therefore what the boot normalization and the
     * pause handler will actually find in the store.
     */
    @Synchronized
    private fun updateInMemory(id: String, transform: (DeviceDownloadEntity) -> DeviceDownloadEntity) {
        val updated = currentEntities().map { if (it.id == id) transform(it) else it }
        memory = updated
        emit(updated)
    }

    private var memory: List<DeviceDownloadEntity>? = null

    @Synchronized
    private fun currentEntities(): List<DeviceDownloadEntity> {
        memory?.let { return it }
        return load().also { memory = it }
    }

    @Synchronized
    private fun persist(entries: List<DeviceDownloadEntity>) {
        memory = entries
        prefs.edit().putString(KEY_ENTRIES, gson.toJson(entries)).apply()
    }

    @Synchronized
    private fun load(): List<DeviceDownloadEntity> {
        memory?.let { return it }
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        val entries = runCatching {
            gson.fromJson(raw, Array<DeviceDownloadEntity>::class.java)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        memory = entries
        return entries
    }

    private fun emit(entries: List<DeviceDownloadEntity>) {
        _downloads.value = entries
            .sortedByDescending { it.enqueuedAt }
            .map { it.toUi() }
    }

    private fun DeviceDownloadEntity.toUi(): DeviceDownload = DeviceDownload(
        id = id,
        title = title.ifBlank { "Video" },
        printLabel = printLabel,
        state = state,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        localUri = localUri,
        handle = handle,
        mimeType = mimeType,
        fileName = fileName,
        bytesPerSec = liveRates[id]?.first ?: 0L,
        etaSeconds = liveRates[id]?.second,
        // Phase 4 (Downloads #5): the failure reason is mapped from the
        // stable kind, never the stored string — the stored string can hold
        // raw backend words ("HTTP 404", byte counts, content types) and
        // must never reach the screen. A PARTIAL row keeps its stored
        // sentence when no kind maps: it is constructed ("3 of 4 parts did
        // not finish."), never an exception message, and it is the one thing
        // the viewer needs to decide on a retry.
        failureReason = when (state) {
            DeviceDownloadState.FAILED -> deviceFailureReasonFor(errorKind)
            DeviceDownloadState.PARTIAL -> deviceFailureReasonFor(errorKind) ?: error
            else -> null
        },
        errorKind = errorKind,
        attempts = attempts,
        // D11: stalled only while actually downloading — a paused row keeps
        // its last mark out of the picture (dropLiveRate cleared it), and a
        // row that never moved reads as starting, not stalled.
        stalled = state == DeviceDownloadState.DOWNLOADING &&
            lastBytesChange[id]?.let { (movedBytes, movedAt) ->
                movedBytes == bytesDownloaded &&
                    System.currentTimeMillis() - movedAt > DOWNLOAD_STALL_AFTER_MS
            } ?: false,
        // D10: the poster the enqueue site knew — null for old rows and
        // poster-less enqueues, which keep the initials tile.
        posterUrl = posterUrl,
    )

    /** Live (speed, ETA) per download, for the foreground notification. */
    internal fun liveRate(id: String): Pair<Long, Long?>? = liveRates[id]

    /** Rolling progress samples per download (bytes, monotonic ms), last five only. */
    private val speedSamples = HashMap<String, ArrayDeque<Pair<Long, Long>>>()

    /** Live (speed, ETA) per download, derived from [speedSamples]. */
    private val liveRates = HashMap<String, Pair<Long, Long?>>()

    /** D11: last (bytes, wall-clock ms) at which each download's byte count actually moved. */
    private val lastBytesChange = HashMap<String, Pair<Long, Long>>()

    private fun videoMime(declared: String?, kind: String?): String? {
        val mime = declared?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if ("mpegurl" in mime || "mp2t" in mime) return null
        if (mime.startsWith("video/")) {
            return when {
                "matroska" in mime -> "video/x-matroska"
                "webm" in mime -> "video/webm"
                "x-msvideo" in mime -> "video/x-msvideo"
                else -> "video/mp4"
            }
        }
        if ("matroska" in mime) return "video/x-matroska"
        if (mime.isBlank() || mime == "application/octet-stream") {
            // A direct/resolve/download handle is a byte endpoint; "external" without a video
            // mime is a landing page, and saving HTML under a video name helps nobody.
            return if (kind in setOf("direct", "resolve", "download")) "video/mp4" else null
        }
        return null
    }

    private fun extensionFor(mime: String): String = when (mime) {
        "video/x-matroska" -> "mkv"
        "video/webm" -> "webm"
        "video/x-msvideo" -> "avi"
        else -> "mp4"
    }

    private fun uniqueFileName(
        title: String,
        printLabel: String,
        extension: String,
        existing: List<DeviceDownloadEntity>,
    ): String {
        val base = sanitize("$title${if (printLabel.isNotBlank()) " [$printLabel]" else ""}")
            .ifBlank { "video" }
            .take(MAX_NAME_CHARS)
        val used = existing.map { it.fileName }.toSet()
        var candidate = "$base.$extension"
        var suffix = 2
        while (candidate in used) {
            candidate = "$base ($suffix).$extension"
            suffix++
        }
        return candidate
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .trim('.')

    private companion object {
        const val PREFS_NAME = "radd_device_downloads"
        const val KEY_ENTRIES = "entries"
        const val KEY_SEGMENTS = "segments:"
        const val MAX_NAME_CHARS = 80
        const val PROGRESS_FLUSH_MS = 1_000L
    }
}

/**
 * Recovers interrupted transfers after a crash, kill, or device restart.
 * Any task in DOWNLOADING is moved to QUEUED so the service can continue it.
 */
internal fun recoverInterruptedEntries(
    entries: List<DeviceDownloadEntity>,
): Pair<List<DeviceDownloadEntity>, Boolean> {
    var changed = false
    val updated = entries.map {
        if (it.state == DeviceDownloadState.DOWNLOADING) {
            changed = true
            it.copy(state = DeviceDownloadState.QUEUED)
        } else {
            it
        }
    }
    return updated to changed
}
