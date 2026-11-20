package com.raddstream.app.viewmodel

import android.content.Context
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.data.cache.CacheCoordinator
import com.raddstream.app.data.cache.CacheOrigin
import com.raddstream.app.data.cache.CacheTaskEntity
import com.raddstream.app.data.cache.CacheTaskState
import com.raddstream.app.data.cache.KeepTtlHolder
import com.raddstream.app.data.cache.OfflineCacheEngine
import com.raddstream.app.data.downloads.DeviceDownload
import com.raddstream.app.data.downloads.DeviceDownloadsRepository
import com.raddstream.app.data.downloads.DownloadSpeed
import com.raddstream.app.data.downloads.SmartDownloadsRunner
import com.raddstream.app.data.local.WatchHistoryDao
import com.raddstream.app.data.local.WatchHistoryEntity
import com.raddstream.app.repository.CacheRepository
import com.raddstream.app.repository.CatalogRepository
import com.raddstream.app.repository.KeepOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class KeepState { QUEUED, DOWNLOADING, PAUSED, READY, EXPIRING, NO_SPACE, FAILED, GATED }

/**
 * One offline keep as the Downloads screen should render it, projected from the cache engine's
 * task rows. Bytes progress is real; the expiry is the engine's retention window applied, never a
 * locally invented timer. These files are playable **only inside this app**.
 */
data class KeptItem(
    val id: String,
    val title: String,
    val printLabel: String,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val state: KeepState,
    val expiresAtMillis: Long?,
    /**
     * The opaque redirector handle this keep plays through — the same handle a stream route
     * takes, and the only address the player may be handed. Null when the engine never got one
     * (a task created before the keep was resolved, or one the server refused to name), which
     * is why [playable] checks it rather than trusting the state alone.
     */
    val streamHandle: String? = null,
    /**
     * The engine's opaque task key, so a tap can ask for this keep's own bytes without the screen
     * ever holding a URL or a host. No provenance and no locator — an identity inside this app.
     */
    val sourceKey: String = "",
    /**
     * Live transfer rate in bytes/second (0 while calculating or stalled, never a guess) — the
     * same rolling rate the device rows carry, so the keep row can show speed and ETA too.
     */
    val bytesPerSec: Long = 0L,
    /**
     * The catalog post id this keep belongs to (0 = unknown). The auto-delete sweep matches on
     * it, never on the title — two works sharing a title must never cross-delete each other.
     */
    val postId: Int = 0,
    /**
     * Why this keep failed, in viewer words (null when there is nothing specific to say). The
     * engine stores stable tokens; this is the mapped sentence, never the raw token.
     */
    val failureReason: String? = null,
) {
    val progress: Float
        get() = if (bytesTotal > 0) (bytesDownloaded.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f) else 0f

    /** Seconds until this keep finishes at the live rate, or null while calculating. */
    val etaSeconds: Long?
        get() = if (bytesPerSec > 0) {
            DownloadSpeed.etaSeconds((bytesTotal - bytesDownloaded).coerceAtLeast(0L), bytesPerSec)
        } else {
            null
        }

    /**
     * Robo run 9: a keep whose engine task has a blank title and a "Rank #1"
     * print label (displayLabel's fallback when a rendition carries no quality
     * words) renders "Rank #1" twice on one card — once as the title (the
     * title-to-print-label fallback in toKeptItem) and once as the print
     * label — and two such keeps speak identically. The row paints the label
     * only when it says something the title does not already say.
     */
    internal fun shownPrintLabel(): String? =
        printLabel.takeIf { it.isNotBlank() && it != title }

    /**
     * The row's spoken print label. The title rides along so two keeps that
     * share a rank label ("Rank #1") never speak identically — the painted
     * text stays the short label, the spoken line names the title too (the
     * run-6 printSpokenLabel pattern).
     */
    internal fun printLabelDescription(): String = "$title — $printLabel"

    /** Whole-file percent for the downloading line; null when the size was never learned. */
    val percent: Int?
        get() = if (bytesTotal > 0) (progress * 100).toInt().coerceIn(0, 100) else null

    /**
     * A row may be tapped to play only while bytes are on the device AND the engine has a
     * handle to play them through. An expiring keep is included deliberately: it is still
     * here, and the screen already says out loud how long it will be.
     */
    val playable: Boolean
        get() = !streamHandle.isNullOrBlank() &&
            (state == KeepState.READY || state == KeepState.EXPIRING)

    fun remainingMillis(now: Long): Long? = expiresAtMillis?.let { (it - now).coerceAtLeast(0) }
}

/**
 * What a tapped keep should do: play the bytes already on this device, play through the
 * redirector handle, or explain that neither is possible yet.
 */
sealed class KeepRoute {
    data class Local(val uri: String) : KeepRoute()
    data class Stream(val handle: String) : KeepRoute()
    object NothingPlayable : KeepRoute()
}

object KeepPlayback {
    /**
     * Local bytes win whenever the engine could serve them: a complete keep needs no network, no
     * resolver round-trip, and no live upstream, and the loopback server answers `Range` so seeking
     * stays cheap. [streamHandle] is the fallback for a keep whose file the engine cannot name —
     * a task written before the resolver ever saw it, or one whose bytes were swept since.
     */
    fun route(localUri: String?, streamHandle: String?, playable: Boolean): KeepRoute = when {
        !localUri.isNullOrBlank() -> KeepRoute.Local(localUri)
        playable && !streamHandle.isNullOrBlank() -> KeepRoute.Stream(streamHandle)
        else -> KeepRoute.NothingPlayable
    }
}

data class StorageInfo(val freeBytes: Long, val totalBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction: Float get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes.toFloat() else 0f
    val lowSpace: Boolean get() = freeBytes in 1 until LOW_SPACE_BYTES

    companion object {
        const val LOW_SPACE_BYTES = 500_000_000L // warn under ~500 MB free
    }
}

data class DownloadsUiState(
    /** Public files in `Movies/RaddStream/`, owned by Android's DownloadManager. */
    val deviceDownloads: List<DeviceDownload> = emptyList(),
    /** In-app keeps from the cache engine — private storage, expiry-bound. */
    val items: List<KeptItem> = emptyList(),
    val storage: StorageInfo? = null,
    val isLoading: Boolean = true,
    val statusMessage: String? = null,
    /** The notice names a sweep deletion that can still be undone (re-downloaded). */
    val canUndoSweep: Boolean = false,
) {
    val hasAnything: Boolean get() = deviceDownloads.isNotEmpty() || items.isNotEmpty()

    /**
     * ONE list, one word: every download on this screen, whatever system
     * owns the copy. Files first — they are the foreground downloads the
     * viewer is watching — then the in-app keeps. Each row still carries
     * its own actions and its own one state word; the split the viewer
     * used to see ("Saved to this device" vs "In-app keeps") was two
     * systems' names, not two things the viewer asked for.
     */
    val allDownloads: List<DownloadRow>
        get() = deviceDownloads.map { DownloadRow.Device(it) } + items.map { DownloadRow.Keep(it) }
}

/** One row of the single Downloads list — the system that owns the copy is its detail, not its kind. */
sealed interface DownloadRow {
    data class Device(val download: DeviceDownload) : DownloadRow
    data class Keep(val item: KeptItem) : DownloadRow
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheRepository: CacheRepository,
    private val catalogRepository: CatalogRepository,
    private val keepTtl: KeepTtlHolder,
    private val deviceDownloads: DeviceDownloadsRepository,
    private val cacheEngine: OfflineCacheEngine,
    private val watchHistoryDao: WatchHistoryDao,
    private val settingsRepository: ServerSettingsRepository,
    private val smartDownloads: SmartDownloadsRunner,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState = _uiState.asStateFlow()

    private var autoDeleteWatched: Boolean = false

    /** The auto-delete sweep runs once per ViewModel lifetime, not on every list emission. */
    private var sweepDone = false

    /** The Smart Downloads catch-up pass runs once per ViewModel lifetime, like the sweep. */
    private var smartPassDone = false

    /** Keeps the last sweep deleted, so Undo can re-queue them (fresh download, bytes are gone). */
    private var lastSwept: List<KeptItem> = emptyList()

    init {
        viewModelScope.launch {
            settingsRepository.autoDeleteWatched.collect { autoDeleteWatched = it }
        }
        viewModelScope.launch {
            settingsRepository.smartDownloadsEnabled.collect { enabled ->
                if (enabled) runSmartDownloadsPass()
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(storage = readStorage()) }
        }
        viewModelScope.launch {
            cacheRepository.tasksFlow().collect { rows ->
                val items = rows.mapNotNull(::toKeptItem)
                _uiState.update { it.copy(items = items, isLoading = false) }
                sweepWatched(items)
            }
        }
        viewModelScope.launch {
            deviceDownloads.downloads.collect { rows ->
                _uiState.update { it.copy(deviceDownloads = rows, isLoading = false) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { _uiState.update { it.copy(storage = readStorage()) } }
    }

    /**
     * "Free up space", from the storage card: re-read storage and say, in
     * one honest line, how much space this screen's downloads hold and how
     * to free it. There is no bulk-delete behind the button — a finished
     * title is removed with its own ×, never silently in bulk — so the
     * button explains instead of deleting.
     */
    fun freeUpSpace() {
        viewModelScope.launch {
            val downloadsBytes = _uiState.value.deviceDownloads.sumOf { it.bytesDownloaded } +
                _uiState.value.items.sumOf { it.bytesDownloaded }
            _uiState.update { it.copy(storage = readStorage(), statusMessage = freeUpSpaceMessage(downloadsBytes)) }
        }
    }

    fun clearStatus() {
        lastSwept = emptyList()
        _uiState.update { it.copy(statusMessage = null, canUndoSweep = false) }
    }

    /**
     * Real, cheap, and always meaningful: how much space is left on the app's primary storage.
     * Used for the storage warning + the "playable only in this app" explanation.
     */
    private fun readStorage(): StorageInfo? = try {
        val stat = StatFs(context.filesDir.absolutePath)
        StorageInfo(freeBytes = stat.availableBytes, totalBytes = stat.totalBytes)
    } catch (_: Exception) {
        null
    }

    // --- engine actions ---

    fun cancel(item: KeptItem) {
        val taskId = item.id.toLongOrNull() ?: return
        viewModelScope.launch {
            runCatching { cacheRepository.cancel(taskId) }
                .onFailure { _uiState.update { s -> s.copy(statusMessage = "Could not cancel that download.") } }
        }
    }

    /**
     * Delete finished movies on their own, to free the space. Only a fully
     * downloaded keep (READY/EXPIRING) whose post id is in the watched set is
     * touched — a download still in flight is never deleted, and a series
     * keep never matches, because the watched set holds movie rows only.
     * Series episodes are deleted at the player instead, when the episode
     * ends (`PlayerViewModel.maybeAutoDeleteWatchedEpisode`): there the
     * identity is the episode's own cache key, so one watched episode can
     * never delete the rest of the season. The notice names what went, and
     * offers Undo — so the deletion is never silent and never final.
     *
     * Matched by post id, never by title: two works sharing a title (a
     * remake, a same-name series) must never cross-delete each other, and a
     * keep whose post id was never plumbed (0, legacy rows) is never swept.
     * Runs once per ViewModel lifetime: the old every-emission sweep could
     * delete a finished movie the moment Downloads opened, before the viewer
     * could replay it.
     */
    private fun sweepWatched(items: List<KeptItem>) {
        if (sweepDone || !autoDeleteWatched) return
        sweepDone = true
        viewModelScope.launch {
            val watched = runCatching { watchedPostIds(watchHistoryDao.findMovieRows()) }.getOrNull()
                ?: return@launch
            if (watched.isEmpty()) return@launch
            val finished = items.filter {
                (it.state == KeepState.READY || it.state == KeepState.EXPIRING) &&
                    it.postId > 0 && it.postId in watched
            }
            if (finished.isEmpty()) return@launch
            lastSwept = finished
            finished.forEach { item ->
                item.id.toLongOrNull()?.let { runCatching { cacheRepository.cancel(it) } }
            }
            _uiState.update { s ->
                s.copy(
                    statusMessage = if (finished.size == 1) {
                        "Deleted ${finished.first().title} — you finished watching it."
                    } else {
                        "Deleted ${finished.size} finished downloads."
                    },
                    canUndoSweep = true,
                )
            }
        }
    }

    /**
     * Smart Downloads (plan E2): the catch-up pass — once per ViewModel
     * lifetime, when the switch is on, fetch the next unwatched episode
     * of each followed series. One honest line when it starts something
     * or when a gate stops it; silent when there is nothing to download,
     * like the warm path. A pass that never ran (the switch is off) says
     * nothing at all.
     */
    private fun runSmartDownloadsPass() {
        if (smartPassDone) return
        smartPassDone = true
        viewModelScope.launch {
            val result = runCatching { smartDownloads.runCatchUp() }.getOrNull() ?: return@launch
            val message = when {
                result.enqueuedTitles.size == 1 ->
                    "Smart Downloads started downloading ${result.enqueuedTitles.first()}."
                result.enqueuedTitles.size > 1 ->
                    "Smart Downloads started downloading ${result.enqueuedTitles.size} episodes."
                result.skippedNoWifi ->
                    "Smart Downloads needs Wi-Fi — the next episode will download when you're back on Wi-Fi."
                result.skippedLowSpace ->
                    "Smart Downloads paused — not enough free space on this phone."
                // REA #5: the budget stopped at least one episode — the
                // pass still fetched what fit, so this line only shows
                // when nothing was enqueued at all.
                result.skippedOverCap ->
                    "Downloads For You is full. Delete a downloaded episode or raise the limit in Settings."
                else -> null
            } ?: return@launch
            _uiState.update { it.copy(statusMessage = message) }
        }
    }

    /**
     * Undo the last auto-delete sweep: re-queue each deleted keep from its
     * stored handle, so it downloads again from scratch — the bytes are
     * gone, and no Undo can pretend otherwise. A keep whose handle is gone
     * cannot come back, so it is skipped and the notice says what happened.
     */
    fun undoSweep() {
        val swept = lastSwept
        lastSwept = emptyList()
        _uiState.update { it.copy(statusMessage = null, canUndoSweep = false) }
        if (swept.isEmpty()) return
        viewModelScope.launch {
            var restored = 0
            swept.forEach { item ->
                val handle = item.streamHandle?.takeIf { it.isNotBlank() } ?: return@forEach
                val ok = runCatching {
                    cacheRepository.keep(
                        handle = handle,
                        title = item.title,
                        sizeBytes = item.bytesTotal.takeIf { it > 0 },
                        resumable = true,
                        printLabel = item.printLabel,
                        postId = item.postId,
                    )
                }.getOrNull()
                if (ok is KeepOutcome.Enqueued ||
                    ok is KeepOutcome.AlreadyKept
                ) {
                    restored++
                }
            }
            _uiState.update { s ->
                s.copy(
                    statusMessage = if (restored == swept.size) {
                        if (restored == 1) "Downloading ${swept.first().title} again." else "Downloading ${restored} titles again."
                    } else if (restored > 0) {
                        "Downloading $restored of ${swept.size} titles again — the rest could not be re-queued."
                    } else {
                        "Could not re-queue those downloads."
                    },
                )
            }
        }
    }

    fun retry(item: KeptItem) {
        val taskId = item.id.toLongOrNull() ?: return
        viewModelScope.launch {
            runCatching { cacheRepository.resume(taskId) }
                .onFailure { _uiState.update { s -> s.copy(statusMessage = "Could not resume that download.") } }
        }
    }

    /** Pause a keep: its bytes stay on disk and the engine leaves it alone until Resume. */
    fun pause(item: KeptItem) {
        val taskId = item.id.toLongOrNull() ?: return
        viewModelScope.launch {
            runCatching { cacheRepository.pause(taskId) }
                .onFailure { _uiState.update { s -> s.copy(statusMessage = "Could not pause that download.") } }
        }
    }

    // --- public download service actions ---

    fun pauseDownload(item: DeviceDownload) {
        deviceDownloads.requestPause(item.id)
    }

    fun resumeDownload(item: DeviceDownload) {
        deviceDownloads.requestResume(item.id)
    }

    fun removeDownload(item: DeviceDownload) {
        deviceDownloads.requestRemove(item.id)
    }

    /**
     * Project one engine task onto a list row. Every field is honest: the state comes straight from
     * the state machine, a pause is only labelled "no space" when the engine parked it for that
     * reason, and a finished ephemeral keep gets the retention window's own expiry stamp.
     *
     * Returns null for a row that must never render: DELETED is already filtered by the query,
     * and EXPIRED is never written by any code (the sweeper retires expired tasks straight to
     * DELETED), so a row in either state is a stale read, not a list entry.
     */
    private fun toKeptItem(task: CacheTaskEntity): KeptItem? {
        val now = System.currentTimeMillis()
        val expiresAt = expiryFor(task)
        val state = when (task.stateEnum) {
            CacheTaskState.QUEUED -> KeepState.QUEUED
            CacheTaskState.LOADING -> KeepState.DOWNLOADING
            CacheTaskState.PAUSED ->
                when (task.lastError) {
                    CacheCoordinator.ERR_NO_SPACE -> KeepState.NO_SPACE
                    CacheCoordinator.ERR_GATED -> KeepState.GATED
                    else -> KeepState.PAUSED
                }
            CacheTaskState.COMPLETED ->
                if (expiresAt != null && expiresAt - now <= EXPIRING_SOON_MILLIS) KeepState.EXPIRING else KeepState.READY
            CacheTaskState.FAILED -> KeepState.FAILED
            CacheTaskState.EXPIRED, CacheTaskState.DELETED -> return null
        }
        return KeptItem(
            id = task.id.toString(),
            // A title is a title: the print label is a quality, not a name,
            // and "Saved file" is a status, not a name either. Old rows whose
            // title was never stored fall back to the print label, then to
            // an honest untitled.
            title = task.title.ifBlank { task.printLabel.ifBlank { "Untitled download" } },
            printLabel = task.printLabel,
            bytesDownloaded = task.downloadedBytes,
            bytesTotal = task.totalSizeBytes,
            state = state,
            expiresAtMillis = expiresAt,
            streamHandle = task.resourceId?.takeIf { it.isNotBlank() },
            sourceKey = task.sourceKey,
            bytesPerSec = cacheRepository.liveRateBytesPerSec(task.id),
            // The task row's releaseId carries the catalog post id for a
            // keep (0 = unknown, never auto-deleted); see CacheSeeds.
            postId = task.releaseId.toInt().takeIf { it > 0 } ?: 0,
            failureReason = if (state == KeepState.FAILED) failureReasonFor(task.lastError) else null,
        )
    }

    /**
     * Resolve a tap. The engine answers from the file manifest, so a keep the sweep already
     * truncated — or one that never finished landing — has no local address and falls back to the
     * handle rather than opening a player on bytes that are not there.
     */
    suspend fun playRoute(item: KeptItem): KeepRoute {
        val complete = item.state == KeepState.READY || item.state == KeepState.EXPIRING
        val local = if (complete) {
            runCatching { cacheEngine.playableUriForSourceKey(item.sourceKey) }.getOrNull()
        } else {
            null
        }
        return KeepPlayback.route(local, item.streamHandle, item.playable)
    }

    private var warmJob: Job? = null
    private var lastWarmedHandle: String? = null

    /**
     * Batch C — warm a keep's stream handle in the background, on tap.
     *
     * Only the redirector path (`KeepRoute.Stream`) warms: a keep with
     * local bytes plays them with no network at all, and a local tap never
     * reaches this. Same shape and guard as
     * [LiveTvViewModel.warmChannel] — a metadata-only
     * `GET /api/v1/stream?redirect=0` that makes the server live-resolve
     * and bank the answer, so the player's redirector 302s from cache
     * instead of paying the resolve inside "Finding your video". It runs
     * in parallel with the navigation, never gates it, and a warm that
     * fails is silent.
     */
    fun warmStreamHandle(handle: String) {
        if (!shouldWarmHandle(handle, lastWarmedHandle)) return
        warmJob?.cancel()
        warmJob = viewModelScope.launch {
            val warmed = runCatching {
                withTimeout(30_000) { catalogRepository.resolveStream(handle) }
            }.isSuccess
            if (warmed) lastWarmedHandle = handle
        }
    }

    /** The row said Play and nothing behind it is playable any more — say so instead of idling. */
    fun reportUnplayable() {
        _uiState.update { it.copy(statusMessage = "That keep is no longer stored on this device.") }
    }

    /** The retention window's own expiry for a finished ephemeral keep; null for a permanent one. */
    private fun expiryFor(task: CacheTaskEntity): Long? {
        if (task.originEnum == CacheOrigin.USER_KEEP) return null
        if (task.stateEnum != CacheTaskState.COMPLETED) return null
        // Read live from [KeepTtlHolder] — the same source the sweep deletes against — so this row
        // cannot promise a minute the engine has no intention of honouring. A settings change lands
        // on the next task emission; nothing here owns a timer of its own.
        return task.createdAtWallClock + keepTtl.ttlHours().millis
    }

    companion object {
        const val EXPIRING_SOON_MILLIS = 60 * 60_000L // last hour before auto-delete reads "Expiring"
    }
}

/**
 * The ids of finished works, from watch-history rows — the same
 * end-guard as the Continue Watching rail, matched by identity instead of by title
 * string. My List's "Unwatched first" sort uses this: two works sharing
 * a clean title (a remake, a same-name series) must never cross-mark
 * each other, and a series counts as watched when any episode of it is
 * finished, because every episode row carries the series' own post id.
 * Rows that cannot be routed to a work (`postId <= 0`) are not finished
 * works and drop out here. The Downloads auto-delete sweep uses this too:
 * a finished movie's keep is deleted by post id, never by title.
 */
fun watchedPostIds(rows: List<WatchHistoryEntity>): Set<Int> =
    rows.filter { it.durationMs > 0 && it.positionMs >= it.durationMs - WatchHistoryEntity.END_GUARD_MS }
        .map { it.postId }
        .filter { it > 0 }
        .toSet()

/**
 * The engine's stable failure token, in viewer words. A token with no
 * mapping renders nothing — the generic line stands alone rather than
 * leaking an internal token onto the screen.
 */
internal fun failureReasonFor(lastError: String?): String? = when (lastError) {
    "ephemeral_dead" -> "The source stopped responding."
    "no_fresh_url" -> "The link expired and could not be refreshed."
    "transient" -> "A network error interrupted the download."
    "sequential_failed" -> "The download could not be completed."
    else -> null
}

/** The "Free up space" line: the real bytes this screen's downloads hold, and the honest way to free them. */
internal fun freeUpSpaceMessage(downloadsBytes: Long): String =
    if (downloadsBytes > 0) {
        "Downloads here use ${formatDownloadSize(downloadsBytes)}. Remove a finished title with its × to free space."
    } else {
        "Nothing downloaded here yet — there is nothing to free up."
    }

/** Decimal size words, the same units the rows use (1 GB = 1,000,000,000 bytes). */
internal fun formatDownloadSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "%.0f KB".format(bytes / 1_000.0)
}
