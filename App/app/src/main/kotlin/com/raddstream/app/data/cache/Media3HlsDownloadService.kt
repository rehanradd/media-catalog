package com.raddstream.app.data.cache

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.raddstream.app.R

/**
 * The foreground service behind Media3 HLS keeps (R3).
 *
 * Media3's [DownloadService] is the standard shell around a
 * [DownloadManager]: it keeps HLS keeps downloading while the app is
 * backgrounded, shows one progress notification, and owns the manager's
 * lifecycle for the process. It is instantiated by the system (no-arg
 * constructor), so it reads the manager from the process singleton
 * ([MediaDownloadCache]) — the Media3-endorsed pattern — rather than Hilt.
 *
 * [getScheduler] returns null on purpose: downloads resume when the app
 * next runs, because [CacheCoordinator] re-drives every unfinished keep on
 * its own tick. No JobScheduler restart is scheduled, so the service never
 * wakes the app on its own.
 */
@OptIn(UnstableApi::class)
class Media3HlsDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DOWNLOAD_NOTIFICATION_CHANNEL_ID,
    R.string.media_download_channel_name,
) {

    override fun getDownloadManager(): DownloadManager =
        MediaDownloadCache.get(this).downloadManager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: List<Download>,
        @Requirements.RequirementFlags notMetRequirements: Int,
    ): Notification {
        val helper = DownloadNotificationHelper(this, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
        return helper.buildProgressNotification(
            this,
            android.R.drawable.stat_sys_download,
            /* contentIntent = */ null,
            /* message = */ null,
            downloads,
            notMetRequirements,
        )
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 7103
        const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "radd_media_downloads"
    }
}
