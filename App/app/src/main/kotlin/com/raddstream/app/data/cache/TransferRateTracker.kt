package com.raddstream.app.data.cache

import com.raddstream.app.data.downloads.DownloadSpeed

/**
 * Rolling live transfer rate per cache task, from progress samples.
 *
 * The Downloads list and the player's keep chip both need a speed, and
 * neither may invent one: the rate is bytes moved between samples, over
 * the same last-five-samples window [DeviceDownloadsRepository] runs for
 * device files ([DownloadSpeed.estimateBytesPerSec]), so the two download
 * systems never disagree about what "fast" means. Fewer than two samples,
 * or no forward movement, reads as 0 — "calculating", never a guessed
 * number — exactly like the device row.
 *
 * Samples arrive from [CacheCoordinator.tick], which reads every running
 * task's committed bytes every 3 seconds: a stalled task keeps sampling
 * the same byte count, so its rate falls to 0 instead of freezing on an
 * old speed. Pure and clock-free except for the caller's monotonic
 * timestamps, so it is unit-testable without Room or the engine.
 */
class TransferRateTracker {

    private val samples = HashMap<Long, ArrayDeque<Pair<Long, Long>>>()

    /** One progress observation: [downloadedBytes] at monotonic [nowElapsedMs]. */
    fun sample(taskId: Long, downloadedBytes: Long, nowElapsedMs: Long) {
        val queue = samples.getOrPut(taskId) { ArrayDeque() }
        queue.addLast(downloadedBytes to nowElapsedMs)
        while (queue.size > MAX_SAMPLES) queue.removeFirst()
        if (samples.size > MAX_TRACKED_TASKS) {
            // Drop the stalest task wholesale; a live download re-samples on
            // the next tick, so nothing a viewer is watching loses its rate.
            val stalest = samples.entries
                .filter { it.key != taskId }
                .minByOrNull { it.value.lastOrNull()?.second ?: 0L }
                ?.key
            if (stalest != null) samples.remove(stalest)
        }
    }

    /** Live bytes/second for [taskId], or 0 while calculating or stalled. */
    fun bytesPerSec(taskId: Long): Long {
        val queue = samples[taskId] ?: return 0L
        return DownloadSpeed.estimateBytesPerSec(queue.toList())
    }

    /** Drop a task's samples when it finishes or is cancelled. */
    fun forget(taskId: Long) {
        samples.remove(taskId)
    }

    companion object {
        private const val MAX_SAMPLES = 5
        private const val MAX_TRACKED_TASKS = 64
    }
}
