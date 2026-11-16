package com.raddstream.app.player

import com.raddstream.app.data.PlaybackBeacon
import com.raddstream.app.data.PlaybackBeaconErrors
import com.raddstream.app.data.PlaybackBeaconNetworks
import com.raddstream.app.data.PlaybackBeaconOutcomes
import java.util.UUID

/**
 * One playback session's measurements, accumulated from the player's own
 * callbacks and finished into a [PlaybackBeacon] when the load ends.
 *
 * The sources are the ones the player already has, nothing new invented:
 * Media3's load statistics (bytes + wall time per finished/cancelled media
 * load — the same samples the auto-quality director eats), the playback
 * state machine (first frame, buffering, end), and the player error. No
 * per-segment tracking exists to wire: the player cannot supply it, so the
 * beacon does not ask for it.
 *
 * Outcome is decided at finish, from what the load did:
 * - an error (or a start timeout) was recorded → "failed";
 * - a first frame landed and nothing failed → "played" — including a load
 *   superseded by a rung change after it played, which delivered video and
 *   was not abandoned by the viewer;
 * - replaced before any frame, with no error → "abandoned".
 *
 * A stall is buffering *after* the first frame: the initial buffering
 * before anything renders is the start, not a stall, and is never counted.
 * A kept copy played from disk has no host to rank, so the service never
 * starts a session for one — this class does not need to know that.
 *
 * Threading: every call comes from the player's main-thread callbacks and
 * the service's own scope, which is the only writer. [start] finalises the
 * previous session first and returns it, so a failover hop or a rung change
 * never silently drops the load it replaces.
 */
class PlaybackBeaconRecorder(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val newSid: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Session(
        val sid: String,
        val streamId: String,
        val band: String,
        val brBps: Long,
        val net: String,
        var firstLoadMs: Long? = null,
        var bytesTotal: Long = 0,
        var loadMsTotal: Long = 0,
        var stalls: Int = 0,
        var rebufferMs: Long = 0,
        var bufferingSinceMs: Long? = null,
        var firstFrameSeen: Boolean = false,
        var errorCode: String? = null,
    )

    private var current: Session? = null

    /**
     * Begin measuring a load, finalising the previous one first. Returns the
     * previous session's beacon, if it had one — the caller records it.
     */
    fun start(streamId: String, band: String, brBps: Long, net: String): PlaybackBeacon? {
        val finished = finishCurrent()
        current = Session(
            sid = newSid(),
            streamId = streamId,
            band = band,
            brBps = brBps,
            net = net,
        )
        return finished
    }

    /** One finished or cancelled media load: bytes moved in wall time. */
    fun noteLoadSample(bytesLoaded: Long, loadDurationMs: Long) {
        val session = current ?: return
        if (bytesLoaded < 0 || loadDurationMs < 0) return
        if (session.firstLoadMs == null) session.firstLoadMs = loadDurationMs
        session.bytesTotal += bytesLoaded
        session.loadMsTotal += loadDurationMs
    }

    /** The first frame landed: the start is over, and stalls count from here. */
    fun noteFirstFrame() {
        current?.firstFrameSeen = true
    }

    /** Buffering began. Only counts when a frame has already landed. */
    fun noteBufferingStarted() {
        val session = current ?: return
        if (!session.firstFrameSeen) return
        if (session.bufferingSinceMs == null) session.bufferingSinceMs = nowMs()
    }

    /** Buffering ended: close the stall, if one was open. */
    fun noteBufferingEnded() {
        val session = current ?: return
        val since = session.bufferingSinceMs ?: return
        session.bufferingSinceMs = null
        session.rebufferMs += (nowMs() - since).coerceAtLeast(0L)
        session.stalls += 1
    }

    /** A player error killed or hurt this load. The first error wins. */
    fun noteError(code: String) {
        val session = current ?: return
        if (session.errorCode == null) session.errorCode = code
    }

    /** A start timeout: the load hung with no first frame. Counts as a failure. */
    fun noteWatchdogTimeout() {
        noteError(PlaybackBeaconErrors.TIMEOUT)
    }

    /** Finish the current session now, with the outcome it earned. */
    fun finishCurrent(): PlaybackBeacon? {
        val session = current ?: return null
        current = null
        // A stall still open at finish counts: the session ended mid-buffer.
        session.bufferingSinceMs?.let { since ->
            session.bufferingSinceMs = null
            session.rebufferMs += (nowMs() - since).coerceAtLeast(0L)
            session.stalls += 1
        }
        val outcome = when {
            session.errorCode != null -> PlaybackBeaconOutcomes.FAILED
            session.firstFrameSeen -> PlaybackBeaconOutcomes.PLAYED
            else -> PlaybackBeaconOutcomes.ABANDONED
        }
        return PlaybackBeacon(
            sid = session.sid,
            streamId = session.streamId,
            band = session.band,
            brBps = session.brBps,
            bytesTotal = session.bytesTotal,
            loadMsTotal = session.loadMsTotal,
            ttfbMs = session.firstLoadMs ?: 0L,
            stalls = session.stalls,
            rebufferMs = session.rebufferMs,
            outcome = outcome,
            error = session.errorCode,
            net = session.net,
        )
    }
}

/** The beacon's network class for a probe answer. Metered roaming is cellular. */
fun beaconNetFor(networkClass: com.raddstream.app.data.cache.NetworkClass): String = when (networkClass) {
    com.raddstream.app.data.cache.NetworkClass.WIFI -> PlaybackBeaconNetworks.WIFI
    com.raddstream.app.data.cache.NetworkClass.MOBILE,
    com.raddstream.app.data.cache.NetworkClass.METERED_ROAMING -> PlaybackBeaconNetworks.MOBILE
    com.raddstream.app.data.cache.NetworkClass.NONE -> PlaybackBeaconNetworks.NONE
}
