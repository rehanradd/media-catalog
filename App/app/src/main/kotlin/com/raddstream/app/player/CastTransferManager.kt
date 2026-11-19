package com.raddstream.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.common.C
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
 * Owns the [CastPlayer] (the pre-1.9 remote player — see
 * RESEARCH-cast-20261119.md for why the 1.9.0 Builder rewrite is out of
 * reach until the Media3 upgrade) and watches session availability on it.
 * Everything else — which rendition to cast, where playback resumes, what
 * the screen shows — is decided by the service, the store, and the
 * ViewModel from the events this class emits. When the Media3 upgrade
 * lands, this class is the one file that changes to the Builder pattern.
 *
 * The player is built lazily inside [runCatching]: a device without Play
 * Services, or a CastContext that refuses to initialise, yields no
 * player and no crash — casting is simply unavailable, and the button
 * never appears.
 */
@OptIn(UnstableApi::class)
class CastTransferManager(
    context: Context,
    /** The service's answer to "a receiver just became available". */
    private val onSessionAvailable: () -> Unit,
    /** The service's answer to "the receiver just went away". */
    private val onSessionUnavailable: () -> Unit,
) {

    private val appContext = context.applicationContext

    /** The remote player, or null when Cast cannot initialise on this device. */
    val castPlayer: CastPlayer? = runCatching {
        val castContext = CastContext.getSharedInstance(appContext)
        CastPlayer(castContext, CastMediaItemConverter()).apply {
            setSessionAvailabilityListener(
                object : androidx.media3.cast.SessionAvailabilityListener {
                    override fun onCastSessionAvailable() {
                        _deviceName.value = deviceName()
                        _isCasting.value = true
                        onSessionAvailable()
                    }

                    override fun onCastSessionUnavailable() {
                        _isCasting.value = false
                        _deviceName.value = null
                        onSessionUnavailable()
                    }
                }
            )
        }
    }.getOrNull()

    private val _isCasting = MutableStateFlow(false)

    /** True while a Cast session is live. The service is the one that flips playback over. */
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)

    /** The receiver's friendly name while casting, null otherwise. */
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    /** The live session's receiver name, or null when no session is open. */
    fun deviceName(): String? = runCatching {
        CastContext.getSharedInstance(appContext)
            .sessionManager.currentCastSession?.castDevice?.friendlyName
    }.getOrNull()

    /** True when a Cast session is live and the remote player can take a load. */
    fun isSessionAvailable(): Boolean = castPlayer?.isCastSessionAvailable == true

    /**
     * Hand the receiver [item] at [positionMs]. The caller has already
     * decided the rendition may cast ([decideCastStart]) and paused the
     * local player; the remote player takes over from the given position.
     */
    fun transferToCast(item: MediaItem, positionMs: Long, playWhenReady: Boolean) {
        val player = castPlayer ?: return
        player.setMediaItems(listOf(item), 0, castResumePosition(positionMs, player.duration))
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    /**
     * The receiver's last known position before the session went away — the
     * position local playback resumes from. Read before anything stops the
     * remote player, because a stopped remote player reports nothing.
     */
    fun remotePositionMs(): Long {
        val player = castPlayer ?: return 0L
        val pos = player.currentPosition
        if (pos == C.TIME_UNSET) return 0L
        return castResumePosition(pos, player.duration)
    }

    /** Stop the remote player after a session ends. Safe to call when no session is live. */
    fun stopRemote() {
        castPlayer?.stop()
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
