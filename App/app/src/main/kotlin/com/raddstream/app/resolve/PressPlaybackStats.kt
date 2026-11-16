package com.raddstream.app.resolve

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData

/**
 * The harness player's event→counter mapping, written once and used for
 * every press. A plain [AnalyticsListener] counts what the press did; a
 * Media3 [PlaybackStatsListener] keeps the same playback's own totals, so
 * the two can be cross-checked instead of trusting either alone.
 *
 * The overrides delegate to the small `record*` methods, which take plain
 * numbers — that is what the unit tests feed, because building a real
 * [AnalyticsListener.EventTime] needs a player and a timeline.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PressStatsCollector(
    /** [SystemClock.elapsedRealtime] at the press, so durations are monotonic. */
    private val pressStartedElapsedMs: Long,
) : AnalyticsListener {

    var firstFrameElapsedMs: Long? = null
        private set
    var rebufferCount: Int = 0
        private set
    var droppedFrames: Int = 0
        private set
    var bytesRead: Long = 0L
        private set
    var statsTotalPlayTimeMs: Long? = null
        private set
    var statsRebufferCount: Int? = null
        private set

    private var sawReady = false

    fun recordFirstFrame(nowElapsedMs: Long) {
        if (firstFrameElapsedMs == null) {
            firstFrameElapsedMs = nowElapsedMs - pressStartedElapsedMs
        }
    }

    fun recordPlaybackState(state: Int) {
        if (state == Player.STATE_READY) sawReady = true
        // Initial buffering is startup, not a rebuffer: only a BUFFERING
        // entry after playback first reached READY counts.
        if (state == Player.STATE_BUFFERING && sawReady) rebufferCount++
    }

    fun recordDroppedFrames(count: Int) {
        droppedFrames += count
    }

    fun recordBytesLoaded(bytes: Long) {
        bytesRead += bytes
    }

    fun applyPlaybackStats(totalPlayTimeMs: Long, totalRebufferCount: Int) {
        statsTotalPlayTimeMs = totalPlayTimeMs
        statsRebufferCount = totalRebufferCount
    }

    fun snapshot(): PressPlaybackSnapshot = PressPlaybackSnapshot(
        firstFrameElapsedMs = firstFrameElapsedMs,
        rebufferCount = rebufferCount,
        droppedFrames = droppedFrames,
        bytesRead = bytesRead,
        statsTotalPlayTimeMs = statsTotalPlayTimeMs,
        statsRebufferCount = statsRebufferCount,
    )

    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        framePresentationTimeUs: Long,
    ) {
        recordFirstFrame(SystemClock.elapsedRealtime())
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        recordPlaybackState(state)
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        recordDroppedFrames(droppedFrames)
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        recordBytesLoaded(loadEventInfo.bytesLoaded)
    }
}

/**
 * The [PlaybackStatsListener] half: keepHistory stays false (one press is
 * short; the history lists would only cost memory) and the callback hands
 * the finished session's totals to the same collector.
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun pressStatsListener(collector: PressStatsCollector): PlaybackStatsListener {
    return PlaybackStatsListener(false) { _, playbackStats ->
        playbackStats?.let { collector.applyPlaybackStats(it.totalPlayTimeMs, it.totalRebufferCount) }
    }
}
