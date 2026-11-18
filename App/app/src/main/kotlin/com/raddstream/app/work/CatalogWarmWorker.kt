package com.raddstream.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.raddstream.app.ui.components.homeRailPrefetchUrls
import com.raddstream.app.ui.components.prefetchPosterArt
import com.raddstream.app.viewmodel.HomeSection
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Feel-instant S8: the overnight catalog warm — the client-side twin of
 * the server's 21:00 top-100 pre-warm. Once a day, in the overnight
 * window, it fetches every warmable Home rail's first page. Each fetch
 * warms the OkHttp disk cache (S1) simply by being made, and upserts its
 * titles into the FTS index (S3c) through the repository's own
 * `cachePosts` — so the morning's first Home open answers from disk and
 * the index already knows the top titles. The first two posters per rail
 * are prefetched through the S2 helper (capped at 20, metered gate
 * included).
 *
 * A failure never retries: the next day, or the next launch, covers it —
 * the same rule [com.raddstream.app.data.cache.CacheSweepWorker] follows,
 * because a warm that arrives a day late is still true, and a retry storm
 * on a flaky evening helps nobody.
 */
@HiltWorker
class CatalogWarmWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val catalog: CatalogPreloadCatalog,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = runCatching {
        warmCatalog(catalog) { urls -> prefetchPosterArt(applicationContext, urls) }
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.success() },
    )

    companion object {
        const val UNIQUE_NAME = "catalog-warm"

        /** Daily, in the overnight window — see [catalogPreloadConstraints]. */
        fun request() = PeriodicWorkRequestBuilder<CatalogWarmWorker>(1, TimeUnit.DAYS)
            .setConstraints(catalogPreloadConstraints())
            .build()
    }
}

/**
 * The warm itself, free of the worker so a test can run it against a fake
 * catalog and a recording prefetch: fetch every warmable rail's first page,
 * then prefetch the first two posters per rail. Returns the titles warmed.
 *
 * One dead rail never stops the others: a rail that throws contributes
 * nothing and the warm goes on — the next Home load fetches it anyway.
 * Sequential, not parallel like Home's `loadAll`: nobody is waiting on
 * this run, and seven parallel first pages would spike the server for a
 * warm. A stop mid-run (the worker's window ending) banks what it fetched
 * and reports success — the next day covers the rest.
 */
suspend fun warmCatalog(
    catalog: CatalogPreloadCatalog,
    prefetchPosters: suspend (List<String>) -> Unit,
): Int {
    var warmed = 0
    val warmedSections = mutableListOf<HomeSection>()
    for (section in warmableSections()) {
        currentCoroutineContext().ensureActive()
        val items = runCatching { catalog.railFirstPage(section) }.getOrNull() ?: continue
        if (items.isEmpty()) continue
        warmed += items.size
        warmedSections += section.copy(items = items)
    }
    if (warmedSections.isNotEmpty()) {
        prefetchPosters(homeRailPrefetchUrls(warmedSections))
    }
    return warmed
}
