package com.raddstream.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MX #9 — Cast: the one place that touches the Cast SDK's player.
 *
 * Owns the [CastPlayer] (Media3 1.11's Builder form — see
 * RESEARCH-cast-20261119.md for the pre-1.9 history) and watches
 * session availability on it. Everything else — which rendition to
 * cast, where playback resumes, what the screen shows — is decided by
 * the service, the store, and the ViewModel from the events this class
 * emits.
 *
 * The CastPlayer IS the session player: the service builds it around
 * the local ExoPlayer and hands the same object to the MediaSession
 * and the player listener, so a Cast hop never moves a listener or
 * re-points a session. When a session opens the CastPlayer stops the
 * local player and delegates to the [RemoteCastPlayer]; when it ends
 * it stops the remote and delegates back. The custom
 * [CastPlayer.TransferCallback] below is what makes the hop resume
 * instead of restart: it runs immediately before the active player
 * changes, while the outgoing player is still readable, and records
 * its position — the incoming player cannot be read after the switch,
 * and the outgoing one reports nothing once stopped.
 *
 * The player is built lazily inside [runCatching]: a device without
 * Play Services, or a CastContext that refuses to initialise, yields
 * no player and no crash — casting is simply unavailable, and the
 * button never appears.
 *
 * Release ownership: [CastPlayer.release] releases the local player
 * it wraps too (CastPlayerImpl.handleRelease releases both players),
 * so this class owns the local player's release whenever a CastPlayer
 * was built around it — [release] and [rebuild] are the only places
 * the wrapped local player is released, and the service must not
 * release it separately afterwards. When no CastPlayer exists (Cast
 * unavailable) the local player stays the service's own to release.
 */
@OptIn(UnstableApi::class)
class CastTransferManager(
    context: Context,
    localPlayer: Player,
    /** The service's answer to "a receiver just became available". */
    private val onSessionAvailable: () -> Unit,
    /** The service's answer to "the receiver just went away". */
    private val onSessionUnavailable: () -> Unit,
) {

    private val appContext = context.applicationContext

    /**
     * Where the phone was when playback hopped to the receiver — the
     * resume position when the receiver never played (a refused session
     * ends with the remote empty, and resuming at its 0 would throw
     * the phone's position away). Recorded by the transfer callback,
     * before the local player stops.
     */
    @Volatile
    private var lastLocalPositionMs = 0L

    @Volatile
    private var lastLocalDurationMs = C.TIME_UNSET

    /**
     * Where the receiver was when playback came home. Recorded by the
     * transfer callback, before the remote player stops — and only
     * when the remote actually carried a load (see the callback).
     */
    @Volatile
    private var lastRemotePositionMs = 0L

    @Volatile
    private var lastRemoteDurationMs = C.TIME_UNSET

    /** The remote carried a load on this cast leg — see [remotePositionMs]. */
    @Volatile
    private var remoteEverPlayed = false

    private var remotePlayer: RemoteCastPlayer? = null

    // Declared before the CastPlayer below: the build's initial-sync
    // (a session already live) writes these flows during construction,
    // and a property read before its own initializer runs is null.
    private val _isCasting = MutableStateFlow(false)

    /** True while a Cast session is live. The service is the one that flips playback over. */
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)

    /** The receiver's friendly name while casting, null otherwise. */
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    /**
     * Records the outgoing player's position before the switch. The
     * direction is read off the players themselves: `to` is the
     * [RemoteCastPlayer] on a local → remote hop, `from` is it on the
     * way back. A remote that never carried a load (a refused session)
     * is not a resume source — its position is 0 and its duration
     * unset, so it must not overwrite the phone's position.
     */
    private val transferCallback = CastPlayer.TransferCallback { from, to ->
        val pos = runCatching { from.currentPosition }.getOrDefault(0L)
        val dur = runCatching { from.duration }.getOrDefault(C.TIME_UNSET)
        when {
            to === remotePlayer -> {
                lastLocalPositionMs = pos
                lastLocalDurationMs = dur
                remoteEverPlayed = false
                lastRemotePositionMs = 0L
                lastRemoteDurationMs = C.TIME_UNSET
            }
            from === remotePlayer &&
                runCatching { from.mediaItemCount }.getOrDefault(0) > 0 -> {
                lastRemotePositionMs = pos
                lastRemoteDurationMs = dur
                remoteEverPlayed = true
            }
        }
    }

    /**
     * Session availability, read off the CastPlayer's own device info:
     * the active player is remote exactly while a session is live.
     * The guard against a repeated event is the [_isCasting] flag —
     * a device-info change for another reason (a renamed receiver)
     * must not re-fire the service's transfer.
     */
    private val sessionListener = object : Player.Listener {
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
            val remote = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
            if (remote && !_isCasting.value) {
                _deviceName.value = deviceName()
                _isCasting.value = true
                onSessionAvailable()
            } else if (!remote && _isCasting.value) {
                _isCasting.value = false
                _deviceName.value = null
                onSessionUnavailable()
            }
        }
    }

    private fun buildCastPlayer(local: Player): CastPlayer? = runCatching {
        // Media3 1.11's Cast entry point: initialise the framework
        // before building the remote player (build() throws when Cast
        // was never initialised). needsInitialization() keeps a second
        // build — a decoder-mode rebuild — from re-initialising.
        val cast = Cast.getSingletonInstance(appContext)
        if (cast.needsInitialization()) cast.initialize()
        val remote = RemoteCastPlayer.Builder(appContext)
            .setMediaItemConverter(CastMediaItemConverter())
            .build()
        remotePlayer = remote
        CastPlayer.Builder(appContext)
            .setLocalPlayer(local)
            .setRemotePlayer(remote)
            .setTransferCallback(transferCallback)
            .build()
            .also { player ->
                player.addListener(sessionListener)
                // A session already live when the player is built (a
                // service restart mid-cast): no device-info change will
                // fire to announce it, so sync the flag here. The
                // service's own availability callback runs from its
                // post-construction check, not from here — this class
                // is still being constructed and the service has not
                // assigned its manager field yet.
                if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                    _deviceName.value = deviceName()
                    _isCasting.value = true
                }
            }
    }.getOrNull()

    /** The session player, or null when Cast cannot initialise on this device. */
    var castPlayer: CastPlayer? = buildCastPlayer(localPlayer)
        private set

    /** The live session's receiver name, or null when no session is open. */
    fun deviceName(): String? = runCatching {
        CastContext.getSharedInstance(appContext)
            .sessionManager.currentCastSession?.castDevice?.friendlyName
    }.getOrNull()

    /** True when a Cast session is live and the remote player can take a load. */
    fun isSessionAvailable(): Boolean =
        castPlayer?.deviceInfo?.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

    /**
     * Hand the receiver [item] at [positionMs]. The caller has already
     * decided the rendition may cast ([decideCastStart]); the load goes
     * to the session player, which delegates to the remote player while
     * a session is live — no listener or session re-pointing is needed,
     * because both already point at the session player.
     */
    fun transferToCast(item: MediaItem, positionMs: Long, playWhenReady: Boolean) {
        val player = castPlayer ?: return
        player.setMediaItems(listOf(item), 0, castResumePosition(positionMs, player.duration))
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    /**
     * The position local playback resumes from after the session ends.
     * The receiver's last known position when it played, else the
     * phone's position from before the hop — a refused cast is a
     * no-op hop, not a restart from 0. Read after the session ended,
     * from the positions the transfer callback recorded: a stopped
     * remote player reports nothing.
     */
    fun remotePositionMs(): Long = if (remoteEverPlayed) {
        castResumePosition(lastRemotePositionMs, lastRemoteDurationMs)
    } else {
        castResumePosition(lastLocalPositionMs, lastLocalDurationMs)
    }

    /**
     * Rebuild the session player around a new local player (a
     * decoder-mode rebuild). Releasing the old CastPlayer releases
     * the old local player it wrapped — the caller must have built
     * the new local player first and must not release the old one
     * separately. The recorded positions reset: the new player has
     * no hop history yet.
     */
    fun rebuild(local: Player): CastPlayer? {
        castPlayer?.release()
        remotePlayer = null
        resetTransferState()
        castPlayer = buildCastPlayer(local)
        return castPlayer
    }

    /**
     * Release the session player. This also releases the local player
     * it wrapped — the service must not release that player separately
     * afterwards. A no-op when no CastPlayer was ever built.
     */
    fun release() {
        castPlayer?.release()
        castPlayer = null
        remotePlayer = null
        resetTransferState()
    }

    private fun resetTransferState() {
        lastLocalPositionMs = 0L
        lastLocalDurationMs = C.TIME_UNSET
        lastRemotePositionMs = 0L
        lastRemoteDurationMs = C.TIME_UNSET
        remoteEverPlayed = false
        _isCasting.value = false
        _deviceName.value = null
    }

    /**
     * End the live Cast session — a refusal, a remote error, Kids Lock, or
     * the viewer disconnecting. The session-unavailable callback runs the
     * return-to-local path, so ending here is the one call every exit
     * funnels through.
     */
    fun endSession() {
        runCatching {
            CastContext.getSharedInstance(appContext)
                .sessionManager.endCurrentSession(true)
        }
    }
}

/**
 * MX #9 — Cast: whether any receiver is discoverable on this network, as
 * the Compose layer needs it. [CastState] is the framework's own answer —
 * `NO_DEVICES_AVAILABLE` vs anything else — so the button is composed only
 * when a receiver actually exists, and the MX #6 slim bar stays slim when
 * there is nothing to cast to. Built lazily in [runCatching] for the same
 * reason as the manager: no Play Services, no crash, no button.
 */
class CastAvailabilityTracker(context: Context) {

    private val appContext = context.applicationContext

    private val _devicesAvailable = MutableStateFlow(false)

    /** True when at least one Cast receiver is discoverable. */
    val devicesAvailable: StateFlow<Boolean> = _devicesAvailable.asStateFlow()

    private val listener = CastStateListener { state ->
        _devicesAvailable.value = state != CastState.NO_DEVICES_AVAILABLE
    }

    init {
        runCatching {
            val castContext = CastContext.getSharedInstance(appContext)
            _devicesAvailable.value =
                castContext.castState != CastState.NO_DEVICES_AVAILABLE
            castContext.addCastStateListener(listener)
        }
    }
}
