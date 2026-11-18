package com.raddstream.app.data.follow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.raddstream.app.MainActivity
import com.raddstream.app.data.local.FollowedSeries
import com.raddstream.app.data.local.FollowedSeriesDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * The daily new-episode check. One pass over every followed series, once
 * a day, on any network — Wi-Fi is NOT required (a viewer on mobile data
 * still wants to know), but a low battery is a reason to wait: the check
 * is a nicety, never urgent enough to spend the last of the battery on.
 *
 * A failure never retries: the next day, or the next launch, covers it —
 * the same rule [com.raddstream.app.data.cache.CacheSweepWorker] follows,
 * because a notification that arrives a day late is still true, and a
 * retry storm on a flaky evening helps nobody. A series whose own check
 * throws never stops the others: one dead title must not silence the
 * rest of the list.
 */
@HiltWorker
class FollowSeriesWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val followedDao: FollowedSeriesDao,
    private val catalog: FollowSeriesCatalog,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = runCatching {
        checkFollowedSeries(
            dao = followedDao,
            catalog = catalog,
            onNewEpisode = { series, scope -> notifyNewEpisode(series, scope) },
        )
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.success() },
    )

    private fun notifyNewEpisode(series: FollowedSeries, scope: EpisodeScope) {
        val notifications =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "New episodes",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "New seasons and episodes for series you follow."
            }
            notifications.createNotificationChannel(channel)
        }
        val openApp = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?: Intent(applicationContext, MainActivity::class.java)
        openApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(series.title)
            .setContentText(scope.label())
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        // One notification per series, replaced when a newer episode lands:
        // a follow is one bell, not a growing stack of them.
        notifications.notify(("followed:" + series.workRef).hashCode(), notification)
    }

    companion object {
        const val UNIQUE_NAME = "followed-series-check"
        const val CHANNEL_ID = "new-episodes"

        /** Daily, any network, never on a low battery — the spec's constraints. */
        fun request() = PeriodicWorkRequestBuilder<FollowSeriesWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder().setRequiresBatteryNotLow(true).build(),
            )
            .build()
    }
}

/**
 * The check itself, free of the worker so a test can run it against a
 * fake catalog and a real in-memory DAO: for each followed series, ask
 * the catalog for the work's newest post, read its releases, and compare
 * the newest scope with the last one the viewer saw.
 *
 * A first check with no last-seen seeds silently — the episodes already
 * on the page when the bell was tapped are not news, and the follow
 * already seeded them when it could. A work the catalog cannot name any
 * more (empty page, unparseable releases) leaves the row untouched: an
 * unanswered question must not move the stamp, or the next answer would
 * be judged against a scope nobody ever saw.
 */
suspend fun checkFollowedSeries(
    dao: FollowedSeriesDao,
    catalog: FollowSeriesCatalog,
    onNewEpisode: (FollowedSeries, EpisodeScope) -> Unit,
) {
    for (series in dao.followedOnce()) {
        // One series' failure never stops the others — and the per-series
        // body lives in its own function because `continue` inside
        // `runCatching`'s inline lambda is an experimental feature: a
        // plain `return` here is the same shape without the flag.
        runCatching { checkOneSeries(series, dao, catalog, onNewEpisode) }
    }
}

private suspend fun checkOneSeries(
    series: FollowedSeries,
    dao: FollowedSeriesDao,
    catalog: FollowSeriesCatalog,
    onNewEpisode: (FollowedSeries, EpisodeScope) -> Unit,
) {
    val posts = catalog.postsForWork(series.workRef, series.cleanTitle)
    var newest: EpisodeScope? = null
    for (post in posts.take(MAX_DETAIL_CALLS_PER_SERIES)) {
        val detail = catalog.detailFor(post.id) ?: continue
        val scope = NewEpisodeCheck.newestScope(detail.releases)
        if (scope != null && (newest == null || scope > newest)) newest = scope
    }
    val seen = newest ?: return
    val lastSeen = series.lastSeenSeason?.let { EpisodeScope(it, series.lastSeenEpisode) }
    if (lastSeen == null) {
        // Never checked: seed, do not announce.
        dao.updateLastSeen(series.workRef, seen.season, seen.episode)
    } else if (seen > lastSeen) {
        onNewEpisode(series, seen)
        dao.updateLastSeen(series.workRef, seen.season, seen.episode)
    }
}

/** Detail calls per followed series per day — a follow list is short, and the catalog is not a walk. */
private const val MAX_DETAIL_CALLS_PER_SERIES = 5
