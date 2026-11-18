package com.raddstream.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.raddstream.app.network.models.Post
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Feel-instant S8: the S3c Phase 2 delta sync — the "search offline" half
 * of preload. Once a day, in the overnight window, it pages the v2
 * trending list (popularity order — the same ranking the FTS index orders
 * by) and lets each page upsert itself into the index through the
 * repository's own `cachePosts`, until the per-run cap, a short page, or a
 * page the server cannot serve.
 *
 * The dedicated catalog-index endpoint (§5.2) does not exist yet — when it
 * lands, this loop's fetcher swaps to it and the 20k tier opens; the cap,
 * the stop rules and the tests stay. Until then the browse route is the
 * sync: top-N by popularity first, 5k per run, inside the ≤ 2 MB/day
 * budget (A11).
 *
 * A failure never retries: the next day covers it — the same rule
 * [com.raddstream.app.data.cache.CacheSweepWorker] follows, because a
 * sync that arrives a day late is still true, and a retry storm on a
 * flaky evening helps nobody. Partial progress is banked, never lost: a
 * run that stops halfway leaves its pages in the index.
 */
@HiltWorker
class CatalogIndexSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val catalog: CatalogPreloadCatalog,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = runCatching {
        syncCatalogIndex(catalog)
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.success() },
    )

    companion object {
        const val UNIQUE_NAME = "catalog-index-sync"

        /** Daily, in the overnight window — see [catalogPreloadConstraints]. */
        fun request() = PeriodicWorkRequestBuilder<CatalogIndexSyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(catalogPreloadConstraints())
            .build()
    }
}

/**
 * The sync itself, free of the worker so a test can run it against a fake
 * catalog: page the trending list until [maxTitles] titles are banked, a
 * short page, or a page the server cannot serve. Returns the titles banked
 * this run.
 *
 * A v2 that answers null on the first page means an old server: the run
 * falls back to the v1 trending list, page-numbered, for the whole run. A
 * v2 that dies mid-run stops the run instead of switching mid-stream — a
 * switch would re-seed from the top and duplicate the pages already
 * banked, and tomorrow's run covers the rest. A short page is the last one
 * on both paths: v2 says so through `hasMore`/cursor, v1 through the page
 * coming back short.
 */
suspend fun syncCatalogIndex(
    catalog: CatalogPreloadCatalog,
    maxTitles: Int = SYNC_MAX_TITLES_PER_RUN,
): Int {
    var banked = 0
    var cursor: String? = null
    var v1Page = 1
    var useV1 = false
    while (banked < maxTitles) {
        currentCoroutineContext().ensureActive()
        val posts: List<Post> = runCatching {
            if (useV1) {
                catalog.trendingPostsPage(v1Page, SYNC_PAGE_LIMIT).also { v1Page++ }
            } else {
                val page = catalog.trendingPage(cursor)
                when {
                    page == null && banked == 0 -> {
                        // Old server: the whole run goes v1, page-numbered.
                        useV1 = true
                        catalog.trendingPostsPage(v1Page, SYNC_PAGE_LIMIT).also { v1Page++ }
                    }
                    page == null -> emptyList()
                    else -> {
                        // A null cursor with hasMore true means the server
                        // withheld the cursor: stop after this page.
                        cursor = if (page.hasMore) page.nextCursor else null
                        page.posts
                    }
                }
            }
        }.getOrNull() ?: break
        if (posts.isEmpty()) break
        banked += posts.size
        val lastPage = if (useV1) posts.size < SYNC_PAGE_LIMIT else cursor == null
        if (lastPage) break
    }
    return banked
}
