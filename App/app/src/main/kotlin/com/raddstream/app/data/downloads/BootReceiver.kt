package com.raddstream.app.data.downloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Listens for system boot completion and package update events to automatically
 * resume any public downloads that were interrupted by a shutdown.
 *
 * Keeps are deliberately NOT resumed here: a keep transfer resumes only when
 * the app opens and the cache engine runner starts ticking. Resuming keeps from
 * this receiver would need a foreground service (or WorkManager) to survive
 * the broadcast — a single tick launched from `onReceive` dies with the
 * process the moment the broadcast returns, so it would promise a resume it
 * cannot keep. The old comment claimed keeps too; that was the promise, not
 * the behaviour.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repo: DeviceDownloadsRepository

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            repo.autoResumeInterrupted()
        }
    }
}
