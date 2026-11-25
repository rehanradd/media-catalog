package com.raddstream.app.data.downloads

import com.raddstream.app.data.cache.CacheCoordinator
import com.raddstream.app.data.cache.ConnectivityProbe
import com.raddstream.app.data.cache.KeepIdentity
import com.raddstream.app.data.cache.NetworkClass
import com.raddstream.app.data.cache.StorageGate
import com.raddstream.app.data.local.DownloadsForYouDao
import com.raddstream.app.data.local.DownloadsForYouEntry
import com.raddstream.app.data.local.WatchHistoryDao
import com.raddstream.app.data.local.WatchHistoryEntity
import com.raddstream.app.viewmodel.episodeProgressFor
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.repository.CacheRepository
import com.raddstream.app.repository.CatalogRepository
import com.raddstream.app.repository.KeepOutcome
import com.raddstream.app.sync.SyncAccountRepository
import com.raddstream.app.viewmodel.ReleaseLayoutBuilder
import com.raddstream.app.viewmodel.StorageInfo
import com.raddstream.app.viewmodel.VipQualityGate
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What one Smart Downloads pass did, so the caller can say one honest
 * line to the viewer. Silent when nothing happened: a pass that found
 * nothing to download is not an event, and a pass that never ran (the
 * switch is off) is not either.
 */
data class SmartDownloadsResult(
    /** Titles of the episodes this pass started downloading. */
    val enqueuedTitles: List<String> = emptyList(),
    /** The pass stopped because the device is not on Wi-Fi. */
    val skippedNoWifi: Boolean = false,
    /** The pass stopped because the device is low on free space. */
    val skippedLowSpace: Boolean = false,
    /**
     * REA #5: at least one episode did not fit in the Downloads For You
     * budget — the pass skipped it, not the whole pass: a later series
     * with a smaller (or unstated) size may still fit.
     */
    val skippedOverCap: Boolean = false,
)

/**
 * Smart Downloads, plan E2 (feature-gap audit #1): when the viewer
 * flips the switch on, the next unwatched episode of each series they
 * follow downloads itself, on the existing keep engine — no new
 * dependency, no backend work, no FCM.
 *
 * The gates, in order, before any byte is asked for:
 *  - the switch is ON (default OFF until the viewer flips it);
 *  - Wi-Fi only, always — independent of the separate
 *    `wifiOnlyDownloads` toggle, because an automatic download must
 *    never spend metered data the viewer did not ask to spend;
 *  - free space above the 500 MB floor the Downloads screen already
 *    uses ([StorageInfo.LOW_SPACE_BYTES]) — and the engine's own
 *    [KeepOutcome.NoSpace] stops the pass the same way if admission
 *    refuses mid-pass.
 *
 * The candidates are the series the viewer is actually watching
 * (watch-history series rows, most recent first) plus the series they
 * already keep — never a guess from a title. One episode per series
 * per pass, at most [MAX_SERIES_PER_PASS] series fetched and at most
 * [MAX_ENQUEUES_PER_PASS] episodes started, so a catch-up pass can
 * never turn into a bulk download. A partial file is never marked
 * READY — that is the engine's own rule (R3), and this runner never
 * touches task state directly.
 *
 * An automatic keep is [CacheRepository.keepAuto], not [keep]: it
 * downloads eagerly like an explicit keep, but it never evicts
 * something the viewer asked to keep in order to make room.
 */
@Singleton
class SmartDownloadsRunner @Inject constructor(
    private val settingsRepository: ServerSettingsRepository,
    private val syncAccountRepository: SyncAccountRepository,
    private val catalogRepository: CatalogRepository,
    private val cacheRepository: CacheRepository,
    private val deviceDownloads: DeviceDownloadsRepository,
    private val watchHistoryDao: WatchHistoryDao,
    private val connectivityProbe: ConnectivityProbe,
    private val storage: StorageGate,
    /** REA #5: the "Downloads For You" ledger — what this runner fetched. */
    private val downloadsForYouDao: DownloadsForYouDao,
) {

    companion object {
        /** Series fetched per catch-up pass (detail fetch is the cost). */
        const val MAX_SERIES_PER_PASS = 5

        /** Episodes started per pass — one per series, at most this many. */
        const val MAX_ENQUEUES_PER_PASS = 3
    }

    /**
     * The Downloads screen's catch-up pass: once per ViewModel lifetime,
     * fetch the next unwatched episode of each followed series.
     */
    suspend fun runCatchUp(): SmartDownloadsResult {
        blockedReason()?.let { return it }
        // REA #5: drop ledger rows whose keep is gone before counting the
        // budget — a watched episode the player auto-deleted must not keep
        // occupying the cap, or the budget would shrink with every finish.
        cleanupStaleEntries()
        val seriesRows = runCatching { watchHistoryDao.findSeriesRows() }.getOrDefault(emptyList())
        val byPostId = seriesRows.groupBy { it.postId }
        val keepPostIds = runCatching { cacheRepository.list() }
            .getOrDefault(emptyList())
            .map { it.releaseId.toInt() }
            .filter { it > 0 }
        val candidates = (byPostId.keys.filter { it > 0 } + keepPostIds)
            .distinct()
            .take(MAX_SERIES_PER_PASS)
        val enqueued = mutableListOf<String>()
        var overCap = false
        for (postId in candidates) {
            if (enqueued.size >= MAX_ENQUEUES_PER_PASS) break
            val outcome = runForSeries(postId, byPostId[postId].orEmpty(), after = null)
            when (outcome) {
                is SeriesOutcome.Enqueued -> enqueued += outcome.title
                is SeriesOutcome.OverCap -> overCap = true
                is SeriesOutcome.LowSpace -> return SmartDownloadsResult(
                    enqueuedTitles = enqueued,
                    skippedLowSpace = true,
                )
                is SeriesOutcome.Skipped -> Unit
            }
        }
        return SmartDownloadsResult(enqueuedTitles = enqueued, skippedOverCap = overCap)
    }

    /**
     * The player calls this when one episode ends: fetch the next
     * unwatched episode of the same series, so it is ready offline.
     * [workRef] is the series' work key when the player knows it;
     * without it the watched set is empty and only the [after] cursor
     * keeps the finished episode from being re-fetched.
     */
    suspend fun onEpisodeFinished(
        postId: Int,
        season: Int,
        episode: Int,
        workRef: String?,
    ): SmartDownloadsResult {
        blockedReason()?.let { return it }
        if (postId <= 0 || episode <= 0) return SmartDownloadsResult()
        val rows = if (workRef.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { watchHistoryDao.findWork(workRef) }.getOrDefault(emptyList())
        }
        return when (val outcome = runForSeries(postId, rows, after = season to episode)) {
            is SeriesOutcome.Enqueued -> SmartDownloadsResult(enqueuedTitles = listOf(outcome.title))
            is SeriesOutcome.OverCap -> SmartDownloadsResult(skippedOverCap = true)
            is SeriesOutcome.LowSpace -> SmartDownloadsResult(skippedLowSpace = true)
            is SeriesOutcome.Skipped -> SmartDownloadsResult()
        }
    }

    private sealed interface SeriesOutcome {
        data class Enqueued(val title: String) : SeriesOutcome
        /** REA #5: the candidate did not fit in the Downloads For You budget. */
        data object OverCap : SeriesOutcome
        data object LowSpace : SeriesOutcome
        data object Skipped : SeriesOutcome
    }

    /**
     * The three gates, in order. Returns null when the pass may run;
     * otherwise the result the caller should report (an empty one when
     * the switch is off — a pass that never ran says nothing).
     */
    private suspend fun blockedReason(): SmartDownloadsResult? {
        if (!settingsRepository.smartDownloadsEnabled.first()) return SmartDownloadsResult()
        if (connectivityProbe.current() != NetworkClass.WIFI) {
            return SmartDownloadsResult(skippedNoWifi = true)
        }
        if (runCatching { storage.osFreeBytes() }.getOrDefault(Long.MAX_VALUE) < StorageInfo.LOW_SPACE_BYTES) {
            return SmartDownloadsResult(skippedLowSpace = true)
        }
        return null
    }

    private suspend fun runForSeries(
        postId: Int,
        watchedRows: List<WatchHistoryEntity>,
        after: Pair<Int, Int>?,
    ): SeriesOutcome {
        val detail = runCatching { catalogRepository.getPostDetail(postId) }.getOrNull() ?: return SeriesOutcome.Skipped
        val layout = ReleaseLayoutBuilder.build(
            detail.contentType,
            detail.releases,
            detail.backdropList,
            detail.seasonArtwork,
        )
        if (!layout.isSeriesLike) return SeriesOutcome.Skipped
        val watched = episodeProgressFor(watchedRows)
            .filterValues { it.watched }.keys
        val kept = keptHandles()
        val candidate = SmartDownloadsPlanner.nextEpisode(
            seasons = layout.seasons,
            watched = watched,
            keptHandles = kept,
            premiumAccess = premiumAccess(),
            freeQualityCap = settingsRepository.freeQualityCap.first(),
            after = after,
        ) ?: return SeriesOutcome.Skipped
        // REA #5: the budget is checked per candidate, not per pass — a
        // candidate that does not fit is skipped, and a later series with
        // a smaller (or unstated) size may still fit.
        val capGb = settingsRepository.downloadsForYouCapGb.first()
        if (!downloadsForYouFits(liveUsedBytes(kept), candidate.sizeBytes, capGb)) {
            return SeriesOutcome.OverCap
        }
        return enqueue(candidate, postId, detail.posterUrl)
    }

    private suspend fun premiumAccess(): Boolean =
        VipQualityGate.premiumAccess(
            settingsRepository.qualityGateEnabled.first(),
            syncAccountRepository.entitlement.value?.vip == true,
        )

    /** Handles already kept or downloading, by either keep route. */
    private suspend fun keptHandles(): Set<String> {
        val engine = runCatching { cacheRepository.list() }
            .getOrDefault(emptyList())
            .mapNotNull { it.resourceId }
        val device = deviceDownloads.downloads.value.map { it.handle }
        return (engine + device).filter { it.isNotBlank() }.toSet()
    }

    /**
     * One candidate, through the same routing the season-keep flow
     * uses: resolve the handle, HLS goes to the engine, anything else
     * goes to the device file downloader.
     *
     * D10: [posterUrl] is the series' own poster from the detail this pass
     * already fetched — it rides to the device-file row so Smart Downloads
     * paint real art. Null when the detail carried none.
     */
    private suspend fun enqueue(
        candidate: SmartDownloadCandidate,
        postId: Int,
        posterUrl: String? = null,
    ): SeriesOutcome {
        val resolved = runCatching { catalogRepository.resolveStream(candidate.handle) }.getOrNull()
            ?: return SeriesOutcome.Skipped
        if (!resolved.success) return SeriesOutcome.Skipped
        return if (KeepIdentity.linkKindFor(resolved.mimeType, null) == CacheCoordinator.KIND_HLS) {
            when (val outcome = cacheRepository.keepAuto(
                handle = candidate.handle,
                title = candidate.title,
                sizeBytes = candidate.sizeBytes,
                resumable = resolved.resumable ?: false,
                printLabel = candidate.printLabel,
                postId = postId,
            )) {
                is KeepOutcome.Enqueued -> {
                    // REA #5: a fresh fetch is a ledger row — the Home row
                    // and the budget both read it. An AlreadyKept outcome
                    // is NOT recorded: the episode was already on the
                    // device (kept by hand, or an earlier pass), so the
                    // row would claim a fetch that never happened.
                    recordFetch(candidate, postId)
                    SeriesOutcome.Enqueued(candidate.title)
                }
                is KeepOutcome.AlreadyKept ->
                    SeriesOutcome.Enqueued(candidate.title)
                is KeepOutcome.NoSpace -> SeriesOutcome.LowSpace
                KeepOutcome.Unavailable -> SeriesOutcome.Skipped
            }
        } else {
            // D10: the series poster rides along, so the device-file row
            // paints real art instead of the initials tile.
            when (deviceDownloads.enqueue(candidate.handle, candidate.title, candidate.printLabel, posterUrl)) {
                is DeviceDownloadResult.Enqueued -> {
                    recordFetch(candidate, postId)
                    SeriesOutcome.Enqueued(candidate.title)
                }
                DeviceDownloadResult.NeedsPermission,
                is DeviceDownloadResult.Refused -> SeriesOutcome.Skipped
            }
        }
    }

    /**
     * REA #5: bytes still on the device for episodes Smart Downloads
     * fetched — the ledger rows whose handle is still kept, summed by
     * their stated size. A row whose keep is gone (watched and
     * auto-deleted, or deleted by hand) counts nothing: the budget is
     * about bytes on the device, not rows in a table.
     */
    private suspend fun liveUsedBytes(keptHandles: Set<String>): Long {
        return runCatching { downloadsForYouDao.listAll() }.getOrDefault(emptyList())
            .filter { it.handle in keptHandles }
            .sumOf { it.sizeBytes ?: 0L }
    }

    /**
     * REA #5: drop ledger rows whose handle is no longer kept, so a
     * watched episode the player auto-deleted stops occupying the budget
     * and stops appearing in the row. Runs at the start of a catch-up
     * pass; a row that is dropped here was already invisible to both.
     */
    private suspend fun cleanupStaleEntries() {
        val kept = keptHandles()
        runCatching { downloadsForYouDao.listAll() }.getOrDefault(emptyList())
            .filter { it.handle !in kept }
            .forEach { entry ->
                runCatching { downloadsForYouDao.deleteByHandle(entry.handle) }
            }
    }

    /** REA #5: one fetched episode is one ledger row (see [enqueue]). */
    private suspend fun recordFetch(candidate: SmartDownloadCandidate, postId: Int) {
        runCatching {
            downloadsForYouDao.insert(
                DownloadsForYouEntry(
                    postId = postId,
                    season = candidate.season,
                    episode = candidate.episode,
                    title = candidate.title,
                    handle = candidate.handle,
                    sizeBytes = candidate.sizeBytes,
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}
