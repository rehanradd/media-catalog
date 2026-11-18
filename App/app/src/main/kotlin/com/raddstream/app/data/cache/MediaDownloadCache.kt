package com.raddstream.app.data.cache

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DownloadManager
import com.raddstream.app.brand.Brand
import java.io.File
import java.util.concurrent.Executors

/**
 * The ONE Media3 cache in the app (R5 — the shared streaming cache).
 *
 * One [SimpleCache] instance serves both halves of the offline story:
 *  - the [DownloadManager] writes HLS keeps into it (R3), and
 *  - the player reads through [playbackFactory], so a completed keep plays
 *    offline from the same bytes — no second download of the same segments.
 *
 * Eviction is deliberately [NoOpCacheEvictor]: the cache never evicts on its
 * own, because an LRU evictor cannot tell a user's keep from an opportunistic
 * preview — and a user's downloaded keep must NEVER be evicted. Deletion is
 * owned by the existing sweeper/TTL logic ([CacheCoordinator.cancelTask] and
 * friends remove the Media3 download when the task row goes), and the
 * [StorageGate] ceiling bounds the store at enqueue time. A plain object
 * (not a Hilt binding) because [com.raddstream.app.player.PlaybackService]
 * is a plain MediaSessionService Hilt cannot reach — it reads the same
 * instance through [get], which is exactly the Media3-endorsed pattern.
 *
 * The download cache lives in app-private `filesDir/media_downloads`, never
 * in the public Movies directory: keeps play inside this app only, and the
 * [SimpleCache] folder lock means exactly one owner — this object — ever
 * opens the directory.
 */
@OptIn(UnstableApi::class)
object MediaDownloadCache {

    private const val CACHE_DIR_NAME = "media_downloads"

    /** At most two HLS keeps downloading at once — the coordinator's own cap. */
    private const val MAX_PARALLEL_DOWNLOADS = 2

    @Volatile
    private var holder: Holder? = null

    data class Holder(
        val cache: SimpleCache,
        val downloadManager: DownloadManager,
    )

    /** The single holder for this process, created once from the application context. */
    fun get(context: Context): Holder {
        holder?.let { return it }
        return synchronized(this) {
            holder ?: create(context.applicationContext).also { holder = it }
        }
    }

    /**
     * A read-only [CacheDataSource.Factory] over the shared cache, for the
     * player: cache hits serve offline, misses pass through to [upstream],
     * and playback NEVER writes — streaming must not silently populate the
     * download store (R5 rule). Only bytes the [DownloadManager] wrote are
     * ever served from the cache.
     */
    fun playbackFactory(context: Context, upstream: DataSource.Factory): CacheDataSource.Factory {
        val cache = get(context).cache
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null)
    }

    private fun create(appContext: Context): Holder {
        val cacheDir = File(appContext.filesDir, CACHE_DIR_NAME)
        val databaseProvider = StandaloneDatabaseProvider(appContext)
        val cache = SimpleCache(cacheDir, NoOpCacheEvictor(), databaseProvider)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Brand.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(MEDIA_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(MEDIA_READ_TIMEOUT_MS)
        val downloadManager = DownloadManager(
            appContext,
            databaseProvider,
            cache,
            httpFactory,
            Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS),
        )
        // 1.3.1 has no max-parallel constructor argument — the cap is a
        // setter (the default is 3). Two keeps at once is the R3 cap: a
        // third keep waits its turn instead of opening a third set of
        // connections against a host that may already be throttling us.
        downloadManager.setMaxParallelDownloads(MAX_PARALLEL_DOWNLOADS)
        return Holder(cache, downloadManager)
    }

    private const val MEDIA_CONNECT_TIMEOUT_MS = 15_000
    private const val MEDIA_READ_TIMEOUT_MS = 30_000
}
