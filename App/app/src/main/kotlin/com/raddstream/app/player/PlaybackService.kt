package com.raddstream.app.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.raddstream.app.brand.Brand
import com.raddstream.app.data.PlaybackBeaconErrors
import com.raddstream.app.data.PlaybackBeacons
import com.raddstream.app.data.Telemetry
import com.raddstream.app.data.cache.ConnectivityProbe
import com.raddstream.app.data.cache.MediaDownloadCache
import com.raddstream.app.player.audio.DelayAudioProcessor
import com.raddstream.app.player.audio.EnhancementAudioProcessor
import com.raddstream.app.player.subtitles.OffsetSubtitleDecoderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Media3 `MediaSessionService` owning the single app-wide [ExoPlayer].
 *
 * Responsibilities (mirrors API_V2.md §5 "App behaviour the contract mandates" + MX parity):
 *  - **Failover**: on a player error, consult [FailoverStateMachine] (retry once → next rank,
 *    max_ranks cap) while preserving the playback position across the switch, and record
 *    *which print settled and why each previous candidate died* in [PlayerSessionStore] so
 *    the overlay can tell the user.
 *  - **In-file episode advance**: when a packed print (season_pack / episode_range) ends and
 *    the next episode's offset is known (learned `cat_offsets` semantics or server chapter
 *    row), seek instead of stopping; otherwise hand a [NextEpisodeAction] to the UI.
 *  - **Media session**: notifications, lock screen, headset buttons and `PlaybackService`
 *    background survival come free from `MediaSession` — the screen controls it via
 *    `MediaController` (PlayerViewModel), never a second player.
 *  - **Auto quality**: when the viewer left the ladder on Auto, Media3's own load
 *    statistics (bytes and wall time per finished load) feed
 *    [AdaptiveQualitySwitcher] through the shared [PlayerSessionStore.autoQuality]
 *    director, and a rung change is applied as a same-position handle swap on our own
 *    redirector — see [evaluateAutoQuality].
 *  - **Cast (MX #9)**: when a Cast session opens, the [MediaSession] player swaps to the
 *    receiver's [CastPlayer] ([CastTransferManager]) at the local position, and swaps
 *    back at the remote position when the session ends — the controller, the
 *    notification and the lock screen never rebind. While casting, Auto quality, the
 *    playhead pump and the start watchdog are paused (the phone measures nothing),
 *    and a decoder-mode change is deferred to the return ([pendingDecoderMode]).
 *
 * The UI-side commands arrive through the shared [PlayerSessionStore] (same process) — no
 * Intent serialization. Which container a link is gets declared per item by
 * [PlaybackMediaItemFactory] (our play addresses carry no file extension, so Media3 cannot
 * infer one); everything below that stays Media3's own — the default media source factory picks
 * the HLS, DASH and progressive readers from the declared type, and `media3-exoplayer-hls` plus
 * `media3-exoplayer-dash` are already dependencies.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private val store: PlayerSessionStore = PlayerSessionStore.Instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var failover: FailoverStateMachine? = null

    /**
     * MX idea #3: the decoder mode the current player was built with. The
     * store is the truth the ViewModel and the failover machine write; this
     * is the mirror the service reads so a rebuild only ever happens when
     * the requested mode differs from the running player.
     */
    private var currentDecoderMode: DecoderMode = DecoderMode.HARDWARE

    /**
     * MX idea #8: the one enhancement processor for the life of the
     * service. It is injected into every player build (see the
     * `buildAudioSink` override in [buildPlayer]), so a live rebuild for
     * a decoder switch reuses the same instance — the mix survives the
     * rebuild, and a slider move applies live through the store
     * collection in [onCreate] with no rebuild at all.
     */
    private val enhancementProcessor = EnhancementAudioProcessor()

    /**
     * REA #3 (VLC, Nov 2026): the one delay processor for the life of
     * the service, same lifetime as the enhancement processor above.
     * It sits behind the enhancement processor in the sink chain, so it
     * always sees the enhanced PCM — a delay move applies live through
     * the store collection in [onCreate], and a rebuild reuses the same
     * instance, so the offset survives a decoder switch.
     */
    private val delayProcessor = DelayAudioProcessor()

    /** The beacon recorder: one finished session's measurements, per load. */
    private val beaconRecorder = PlaybackBeaconRecorder()

    /**
     * The stall watchdog (2026-11-12 rework, audit #1): one job per load,
     * armed by [loadRenditionOf], disarmed by the first frame. The
     * generation stops a stale watchdog from failing a load that already
     * replaced it.
     */
    private var watchdogJob: Job? = null
    private var watchdogGeneration = 0

    /** Resume target for the rendition currently being loaded (seek once READY). */
    private var pendingResumeMs: Long? = null
    private var lastKnownPositionMs: Long = 0
    private var titleHint: String = Brand.name

    /**
     * Session 159 (S5): whether the load now in the player is the loopback
     * kept copy rather than the rendition's own redirector address. Set by
     * [loadRenditionOf] from its [isLocalCopy] argument — [handleLoad] is the
     * only caller that ever passes true — and read by the two failover
     * entries ([onPlayerError], the start watchdog) so the failover machine
     * knows the failed attempt was the kept copy, not the live link. Every
     * load rewrites it, so a hop or a retry can never inherit a stale true.
     */
    private var lastLoadedWasLocalCopy = false

    /**
     * MX #9 — the Cast transfer manager, or null when this device cannot
     * initialise Cast (no Play Services). Built in [onCreate]; the session
     * callbacks below are the only entries that touch it.
     */
    private var castTransferManager: CastTransferManager? = null

    /**
     * MX #9 — a decoder-mode change asked for while casting. Rebuilding
     * the local player mid-cast would reload the rendition on the
     * receiver for no reason, so the request waits here and lands in
     * [onCastSessionUnavailable], on the player that will use it.
     */
    private var pendingDecoderMode: DecoderMode? = null

    /**
     * MX #9 — the return from Cast was caused by a remote error, not a
     * clean disconnect. The return path reads this so the banner says the
     * TV failed instead of the plain "back on this device".
     */
    private var castReturnFailed = false

    /** Thermal listener (API 29+), removed in onDestroy; null on older phones. */
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        // MX idea #3: the player starts in the mode the store holds — the
        // viewer's persisted choice when the ViewModel already loaded it, or
        // hardware when the service starts cold. The ViewModel's load lands a
        // moment later and the collection below rebuilds before the first
        // play, which is cheap: the player holds no media yet.
        currentDecoderMode = store.decoderMode.value
        val exo = buildPlayer(currentDecoderMode)
        exo.addListener(playerListener)
        exo.addAnalyticsListener(autoQualityListener)
        player = exo

        mediaSession = MediaSession.Builder(this, exo).build()

        scope.launch {
            store.decoderMode.collect { mode -> rebuildPlayer(mode) }
        }
        // MX #9 — the Cast transfer manager. The callbacks are the service's
        // own transfer methods: the manager owns the remote player, the
        // service owns what plays on it and where it resumes.
        castTransferManager = CastTransferManager(
            this,
            onSessionAvailable = { onCastSessionAvailable() },
            onSessionUnavailable = { onCastSessionUnavailable() },
        )
        // MX #9 — Kids Lock, enforced from the service side too: the
        // ViewModel clears castAllowed when the player locks, and a
        // session live at that moment ends at once — the return path
        // below brings playback home, then the lock applies.
        scope.launch {
            store.castAllowed.collect { allowed ->
                if (!allowed && store.castIsCasting.value) {
                    castTransferManager?.endSession()
                }
            }
        }
        // MX idea #8: the mix, applied live. The processor reads these
        // volatiles on its next queued buffer — a slider move, a preset
        // tap or a persisted level landing needs no rebuild.
        scope.launch {
            store.audioEnhancement.collect { settings ->
                enhancementProcessor.setSettings(settings)
            }
        }
        // REA #3: the audio sync offset, applied live the same way. The
        // delay processor sits behind the enhancement processor in the
        // sink chain, so it always sees the enhanced PCM.
        scope.launch {
            store.audioDelayMs.collect { delayMs ->
                delayProcessor.setDelayMs(delayMs)
            }
        }
        scope.launch {
            store.loadRequest.collect { request ->
                if (request != null && request.nonce != lastHandledNonce) {
                    lastHandledNonce = request.nonce
                    handleLoad(request)
                }
            }
        }
        startAutoQualityTicker()
        startPlayheadPump()
        startThermalListener()
    }

    /**
     * One player build, in the given decoder mode — the initial build and
     * every live rebuild ([rebuildPlayer]) come through here, so the two
     * players can never drift apart in factories, timeouts, or policies.
     * The factories are cheap to recreate per build: the HTTP and data
     * factories hold config only, and the playback cache behind them is
     * the shared singleton ([MediaDownloadCache.playbackFactory]).
     */
    private fun buildPlayer(mode: DecoderMode): ExoPlayer {
        // Cross-protocol redirects ON: resolver 302s are the normal path on this backend.
        // Cleartext: debug builds allow http (app/src/debug/AndroidManifest.xml); the release
        // HTTP-only gap is documented in PLAYER_SPEC.md §3.4 + HANDOFF_appplayer.md.
        // Explicit timeouts on the fallback 302 path (2026-11-12 rework, raised
        // Session 153): the client-ladder fallback plays through the same slow
        // resolve, so the HTTP layer itself must bound the wait — 15 s to
        // connect, 25 s between bytes — instead of leaving it to Media3's
        // defaults and the 30 s start card. The redirector's live resolve is
        // 6–12 s measured, so a 10 s read timeout fired while the 302 was
        // still being minted. The 20 s stall watchdog fires first on a
        // silent stall; these timeouts are the backstop that turns a hung
        // socket into a player error the failover machine can act on.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Brand.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(MEDIA_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(MEDIA_READ_TIMEOUT_MS)
        // Local playback: a finished download is a `content://` (MediaStore) or
        // `file://` URI, not an HTTP address. Handing DefaultHttpDataSource a
        // content URI makes ExoPlayer fail before the first frame, so all reads
        // go through DefaultDataSource — it opens local schemes directly (no
        // network, no connectivity check) and delegates http(s) to the factory
        // above. This is what makes a saved file play in airplane mode.
        // R3/R5: the http(s) leg runs through the shared Media3 cache
        // (read-only — playback never writes to the download store), so a
        // completed HLS keep plays offline from the same bytes its download
        // wrote, with no second fetch of the same segments.
        val dataFactory = DefaultDataSource.Factory(
            this,
            MediaDownloadCache.playbackFactory(this, httpFactory),
        )

        // TextRenderer is the one renderer we replace: it is final in 1.3.1 and knows no
        // subtitle offset, so its decoder gets a wrapper that holds each cue block back by
        // the live sync value (see OffsetSubtitleDecoderFactory). Every other renderer stays
        // exactly as DefaultRenderersFactory builds it.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>,
            ) {
                out.add(
                    TextRenderer(
                        output,
                        outputLooper,
                        OffsetSubtitleDecoderFactory { store.subtitleOffsetMs },
                    ),
                )
            }

            /**
             * MX idea #8 + REA #3: the audio sink carries the enhancement
             * processor — the volume boost past 100% and the equalizer —
             * and behind it the delay processor, applied to the decoded
             * PCM in Media3's own chain. This is the documented
             * injection point (`DefaultAudioSink.Builder.setAudioProcessors`):
             * there is no `ExoPlayer.Builder.setAudioProcessors` in Media3.
             * Everything else is the default sink, built exactly as the
             * factory builds it. The processor instances are the service's
             * shared ones, so a rebuild re-injects the same mix and the
             * same offset.
             */
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(enhancementProcessor, delayProcessor))
                    .build()
            }
        }
        // MX idea #1 (2026-11-18): the FFmpeg software audio decoder. PREFER
        // puts FfmpegAudioRenderer ahead of MediaCodecAudioRenderer. The
        // Jellyfin FFmpeg build claims MORE than the exotic formats — AAC,
        // MP3, FLAC, Opus included (Robo v11, Discover Pakistan, 2026-11-20:
        // it decoded the AAC stream) — so most audio goes through FFmpeg
        // and comes out as float PCM. That is safe ONLY because the sink
        // chain stays 16-bit end to end: ToInt16PcmAudioProcessor converts
        // the float before EnhancementAudioProcessor, which preserves its
        // input encoding (see that class — it used to force float output,
        // which made the 16-bit-only SilenceSkippingAudioProcessor throw
        // and broke every audio-bearing title). Without the AAR the factory
        // loads nothing extra (the extension is reflection-loaded and
        // silently skipped when absent), so a build without FFmpeg plays
        // exactly as today.
        renderersFactory.setExtensionRendererMode(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
        )
        // MX idea #3: the decoder mode is a factory choice. Hardware is
        // Media3's own default selector; software only offers the platform
        // software decoders (see SoftwareMediaCodecSelector). The FFmpeg
        // audio extension stays PREFER in both modes (see the comment
        // above): this selector moves the VIDEO decoder path only.
        renderersFactory.setMediaCodecSelector(mediaCodecSelectorFor(mode))

        val exo = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            // Session 156: the extractors factory carries FLAG_DISABLE_SEEK_FOR_CUES
            // (see PlayerExtractors.kt) — without it a tail-cued MKV makes the
            // extractor seek ~973 MB in while preparing, which a Range-ignoring
            // host answers by making the player download the whole file first.
            // Session 158 (S5): the factory also carries a load-error policy
            // that never retries a final answer from our own redirector (see
            // RedirectorLoadErrorPolicy) — Media3's default retries the media
            // item, which IS the redirector URL, on 404/429, and the S5 log
            // shows those retries pressing ~1/sec until the server's
            // per-minute bucket 429'd the session. A dead handle is the
            // failover machine's job, not a loader retry's.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataFactory, buildPlayerExtractorsFactory())
                    .setLoadErrorHandlingPolicy(RedirectorLoadErrorPolicy()),
            )
            .setLoadControl(loadControl())
            .setSeekBackIncrementMs(MX_SKIP_MS)
            .setSeekForwardIncrementMs(MX_SKIP_MS)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
            .build()
        // Screen-off playback (MX "keep playing with screen off") — no sensor needed.
        exo.setWakeMode(C.WAKE_MODE_LOCAL)
        // Seeking snaps to the nearest sync frame instead of decoding forward from the previous
        // one, which is what makes dragging the scrub bar feel immediate. Costs frame-exact
        // landing; nobody scrubs for that. A `val` reassignment would not compile here — ExoPlayer
        // exposes this as a setter whose getter returns a different type.
        exo.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        return exo
    }

    /**
     * MX idea #3: swap the player for one built in [mode], keeping the
     * position and the play/pause state — this is what a tap on the top-bar
     * chip does mid-playback, and what the decode failover does when the
     * other decoder gets its one shot. Media3 has no API to swap a
     * RenderersFactory on a live player, so the player is rebuilt; the
     * MediaSession is re-pointed at the new player ([MediaSession.setPlayer]
     * — the new player must use the same application looper, and both builds
     * happen here on the main thread), so the MediaController the screen
     * holds keeps working through the session and never rebinds.
     *
     * The old player is released before the new one is built, so only one
     * decoder set is ever alive. The reload is the active rendition from
     * the bundle (the live link — a kept copy that was playing reloads from
     * the server instead; the bytes are the same, only the source changes).
     * Nothing is reloaded before the first load: a rebuild with no bundle
     * just swaps the idle player.
     */
    private fun rebuildPlayer(mode: DecoderMode, resumeOverrideMs: Long? = null) {
        if (mode == currentDecoderMode) return
        val old = player ?: return
        // MX #9 — a rebuild asked for while casting waits: the local player
        // is paused and unused, and rebuilding it would reload the
        // rendition on the receiver for no reason. The request lands in
        // [onCastSessionUnavailable], on the player that will use it.
        if (store.castIsCasting.value) {
            pendingDecoderMode = mode
            return
        }
        // An ended player reports the duration as its position; reloading
        // there would end again at once, so a toggle at the end restarts.
        // A return from Cast hands its own resume in: the remote position,
        // which the paused local player cannot know.
        val resumeMs = resumeOverrideMs ?: if (old.playbackState == Player.STATE_ENDED) {
            0L
        } else {
            runCatching { old.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
        }
        val wasPlaying = old.playWhenReady
        disarmStartWatchdog()
        old.removeListener(playerListener)
        old.removeAnalyticsListener(autoQualityListener)
        old.release()
        val exo = buildPlayer(mode)
        currentDecoderMode = mode
        exo.addListener(playerListener)
        exo.addAnalyticsListener(autoQualityListener)
        player = exo
        // The session survives the rebuild: the controller never rebinds.
        mediaSession?.setPlayer(exo)
        val bundle = store.bundle.value
        val index = store.activeRenditionIndex.value
        val rendition = bundle?.renditions?.getOrNull(index) ?: return
        lastKnownPositionMs = resumeMs
        pendingResumeMs = resumeMs.takeIf { it > 0 }
        loadRenditionOf(bundle, rendition, countAsAttempt = false)
        // loadRenditionOf always starts playing; a paused viewer stays paused.
        exo.playWhenReady = wasPlaying
    }

    // ── Cast (MX #9) ─────────────────────────────────────────────────────

    /**
     * The player carrying playback right now: the receiver's CastPlayer
     * while casting, else the local ExoPlayer. Every position, seek and
     * state read that must follow a hop goes through here — never through
     * the `player` field directly, which is the local player even while
     * the TV is playing.
     */
    private fun activePlayer(): Player? =
        if (store.castIsCasting.value) castTransferManager?.castPlayer ?: player else player

    /**
     * Detail-first Cast (Nov 2026): hand this rendition to the receiver at
     * [positionMs]. The gate is [decideCastStart] — allowed, session live,
     * URL present and fresh — and the caller decides what a refusal means:
     * the mid-play path ends the session (an idle TV beside a playing
     * phone is the confusion the spike showed), the load-time path just
     * plays locally. Returns the decision, so both callers read the same
     * answer the service acted on.
     */
    private fun tryTransferToCast(
        bundle: PlaybackBundle,
        rendition: PlaybackRendition,
        positionMs: Long,
        playWhenReady: Boolean,
    ): CastTransferDecision {
        val manager = castTransferManager ?: return CastTransferDecision.Refuse(CastRefusalReason.NO_SESSION)
        if (!manager.isSessionAvailable()) return CastTransferDecision.Refuse(CastRefusalReason.NO_SESSION)
        val decision = decideCastStart(
            playback = rendition.playback,
            allowed = store.castAllowed.value,
            sessionAvailable = true,
            nowEpochMillis = System.currentTimeMillis(),
        )
        if (decision !is CastTransferDecision.Proceed) return decision
        val castPlayer = manager.castPlayer ?: return CastTransferDecision.Refuse(CastRefusalReason.NO_SESSION)
        val local = player
        val safePositionMs = castResumePosition(
            positionMs,
            runCatching { local?.duration ?: 0L }.getOrDefault(0L),
        )
        local?.pause()
        // The listener moves to the remote player: its errors, its end and
        // its ready are the ones that matter now.
        local?.removeListener(playerListener)
        castPlayer.addListener(playerListener)
        lastKnownPositionMs = safePositionMs
        pendingResumeMs = null
        store.setCastState(casting = true, deviceName = manager.deviceName())
        mediaSession?.setPlayer(castPlayer)
        disarmStartWatchdog()
        val item = PlaybackMediaItemFactory.build(
            rendition, bundle.target, titleHint,
            externalSubtitles = PlaybackMediaItemFactory.externalsFromTracks(rendition.tracks),
        )
        manager.transferToCast(item, safePositionMs, playWhenReady)
        store.announceCastStarted(manager.deviceName())
        return decision
    }

    /**
     * A Cast session just opened — the viewer picked a receiver in the
     * picker's dialog. The local player pauses and the receiver takes over
     * the active rendition at the local position, through the same
     * MediaSession the controller already talks to: play, pause, seek and
     * volume keep working from our controls, the notification and the lock
     * screen follow the session, and nothing rebinds.
     *
     * A refusal never leaves the session open: an idle TV beside a phone
     * still playing is the confusion the spike's silent failure mode
     * already showed. Ending the session fires
     * [onCastSessionUnavailable], which resumes locally at the same
     * position — a no-op hop, not a restart.
     */
    private fun onCastSessionAvailable() {
        val manager = castTransferManager ?: return
        if (!manager.isSessionAvailable()) return
        val bundle = store.bundle.value ?: return
        val rendition = store.activeRendition() ?: return
        val local = player ?: return
        if (local.mediaItemCount == 0) return
        when (val decision = tryTransferToCast(
            bundle = bundle,
            rendition = rendition,
            positionMs = runCatching { local.currentPosition }.getOrDefault(0L),
            playWhenReady = local.playWhenReady,
        )) {
            is CastTransferDecision.Refuse -> {
                store.announceCastRefused(decision.reason)
                manager.endSession()
                return
            }
            is CastTransferDecision.Proceed -> Unit
        }
    }

    /**
     * The session went away — the viewer disconnected, the receiver was
     * lost, or the service ended it (a refusal, a remote error, Kids Lock).
     * Playback comes home at the receiver's last known position, on the
     * local player, with no restart of the stream where the player
     * supports it: the rendition reloads at the remote position, and a
     * decoder-mode change deferred during the cast lands first.
     */
    private fun onCastSessionUnavailable() {
        val manager = castTransferManager ?: return
        val local = player ?: return
        val remotePositionMs = manager.remotePositionMs()
        manager.stopRemote()
        manager.castPlayer?.removeListener(playerListener)
        local.addListener(playerListener)
        store.setCastState(casting = false, deviceName = null)
        mediaSession?.setPlayer(local)
        if (castReturnFailed) {
            castReturnFailed = false
            store.announceCastFailed()
        } else {
            store.announceCastEnded()
        }
        val bundle = store.bundle.value ?: return
        val rendition = store.activeRendition() ?: return
        lastKnownPositionMs = remotePositionMs
        pendingResumeMs = remotePositionMs.takeIf { it > 0 }
        val pendingMode = pendingDecoderMode
        pendingDecoderMode = null
        if (pendingMode != null && pendingMode != currentDecoderMode) {
            rebuildPlayer(pendingMode, resumeOverrideMs = remotePositionMs)
        } else {
            loadRenditionOf(bundle, rendition, countAsAttempt = false)
        }
    }

    /**
     * Thermal-aware ABR (Session 126, research item 15): the phone's own thermal
     * state caps the director's climbs (MODERATE → 720p, SEVERE/CRITICAL → 480p)
     * and lifts again when it cools. API 29+ only — older phones simply never cap.
     * The listener only writes the director's thermal status; the cap itself is
     * pure policy in [ThermalCap], applied in `targetBand`.
     */
    private fun startThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            store.autoQuality.thermalStatus = when (status) {
                PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
                else -> ThermalStatus.NONE
            }
        }
        thermalListener = listener
        powerManager.addThermalStatusListener(mainExecutor, listener)
    }

    private var lastHandledNonce: Long = -1L

    /**
     * The buffering policy, in one call. [PlayerBuffering] owns the four numbers and the reason
     * for each; this is the only place they are handed to Media3, in the order
     * `setBufferDurationsMs` takes them — minimum, maximum, to start, to start again after a
     * stall — because the method is positional and a swap of two of them compiles happily.
     */
    private fun loadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setPrioritizeTimeOverSizeThresholds(PlayerBuffering.PRIORITIZE_TIME_OVER_SIZE)
            .setBufferDurationsMs(
                PlayerBuffering.MIN_BUFFER_MS,
                PlayerBuffering.MAX_BUFFER_MS,
                PlayerBuffering.BUFFER_FOR_PLAYBACK_MS,
                PlayerBuffering.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    private suspend fun handleLoad(request: PlayerSessionStore.LoadRequest) {
        val bundle = store.bundle.value ?: return
        val exo = player ?: return
        exo.pause()
        if (bundle.isStale(System.currentTimeMillis())) {
            // NAV-4: a stale bundle is an expired shared link, not a dead print — the
            // card says the link expired and offers Home, so the viewer fetches a fresh
            // link instead of tapping "try another print" at a rotted handle.
            disarmStartWatchdog()
            store.markGaveUp(
                "Play bundle expired — fetch a fresh link",
                HandoffWhy.EXPIRED_LINK,
            )
            return
        }
        titleHint = request.title
        store.clearGaveUp()
        store.resetFailoverLog()
        val machine = FailoverStateMachine(bundle.renditions, bundle.failurePolicy)
        failover = machine
        val start = machine.selectInitial()
        store.appendFailover(machine.log) // why any lower-ranked candidate was skipped
        if (start == null) {
            // Nothing our player can present. Say so plainly, and offer the keep-only link when
            // one exists. A page link is never in this bundle — `DetailViewModel` opens the
            // provider-page screen for it before playback is launched — so these two exits are
            // the whole answer here, and there is no in-player escape hatch to fall back to.
            val keepOnly = machine.keepOnlyFallback()
            disarmStartWatchdog()
            store.markGaveUp(
                if (keepOnly != null) {
                    "$NOT_PLAYABLE_HERE — this rank can only be kept on the device"
                } else {
                    "No directly playable link in this bundle"
                },
                if (keepOnly != null) HandoffWhy.DOWNLOAD_ONLY else HandoffWhy.NO_CANDIDATE_LEFT,
            )
            return
        }
        pendingResumeMs = request.startOverrideMs ?: request.resumePositionMs.takeIf { it > 0 }
        lastKnownPositionMs = pendingResumeMs ?: 0L
        store.setActiveIndex(start)
        armAutoQuality(bundle, start)
        // Detail-first Cast (Nov 2026): the viewer may have picked a TV on
        // the detail page before pressing Play — the session is already live
        // and no bundle was loaded when it opened, so the session callback
        // below had nothing to transfer. Check here, at load time, on the
        // live link (never a kept loopback copy — a loopback address can
        // never play on a receiver). The flag the gate reads lands from the
        // ViewModel's settings collect, which the load request can beat by
        // milliseconds, so wait up to a second for it before deciding; a
        // refusal here only plays locally, it never ends the session the
        // viewer just picked.
        val initialRendition = bundle.renditions.getOrNull(start)
        if (initialRendition != null && castTransferManager?.isSessionAvailable() == true) {
            withTimeoutOrNull(1_000L) { store.castAllowed.first { it } }
            val decision = tryTransferToCast(
                bundle = bundle,
                rendition = initialRendition,
                positionMs = pendingResumeMs ?: 0L,
                playWhenReady = true,
            )
            if (decision is CastTransferDecision.Proceed) return
        }
        // A kept copy is always the better source, offline or not: no CDN, no redirect, and the
        // bytes are already on this device. Ask before touching the network, and never let a
        // local read failure block the remote one — the failover machine still holds the bundle's
        // own rung and will advance to it if the local copy turns out to be unusable.
        val rendition = bundle.renditions.getOrNull(start)
        val localUrl = localCopyUrlFor(rendition)
        if (rendition != null && localUrl != null) {
            store.appendFailover(
                listOf(
                    FailoverRecord(
                        rendition.rank, rendition.releaseId, rendition.displayLabel(),
                        "saved on this device", "playing the kept copy",
                    ),
                ),
            )
            loadRenditionOf(
                bundle,
                rendition.copy(
                    playback = rendition.playback.copy(
                        playUrl = localUrl,
                        // The kept bytes do not rot: the redirector's expiry no longer applies.
                        expiresAt = null,
                        ephemeral = false,
                    ),
                ),
                // Session 159 (S5): this load is the kept copy, not the
                // rendition's own address — a pre-frame failure of it must
                // fall back to the live link (see the failover machine),
                // not hand off as if the live link had been tried.
                isLocalCopy = true,
            )
        } else {
            // Session 126 start-low, part 3 of 3: the director already armed on the
            // cheapest rung, but the bundle still points at the rung the server called
            // best — open the cheap one instead, so the first frame arrives fast and
            // the step-up climbs from evidence. A local copy above keeps precedence.
            val startLow = startLowBundle(bundle, start)
            loadRendition(startLow ?: bundle, start)
        }
    }

    /**
     * Start-low, part 3 of 3 (Session 126): rewrite the initial rendition onto the
     * cheapest rung of the staged ladder, mirroring [switchRungTo] exactly — same
     * handle swap on our own redirector, same failover-plan rebuild with the same
     * `selectInitial == index` guard, same store writes — but at load time, before
     * the first byte is requested. Null when there is nothing to swap: Auto off,
     * no ladder, already on the cheapest rung, or no address of ours on it.
     *
     * Parts 1 and 2 are the policy: `AdaptiveQualitySwitcher.pickBand` cold-picks the
     * lowest rung, and `AutoQualityDirector.configure` arms Auto there. This part is
     * needed because `evaluateAutoQuality` re-syncs the director to the rung actually
     * playing (`markPlaying`) first — a director-only change would be overwritten by
     * the top rung the bundle still points at.
     */
    private fun startLowBundle(bundle: PlaybackBundle, index: Int): PlaybackBundle? {
        val director = store.autoQuality
        if (!director.isEnabled()) return null
        val active = bundle.renditions.getOrNull(index) ?: return null
        val lowest = director.currentBand ?: return null
        if (lowest == bandOfRendition(active)) return null
        val rung = director.rungFor(lowest) ?: return null
        if (rung.streamId == active.playback.streamId) return null
        val newUrl = streamUrlForHandle(active.playback.playUrl, rung.streamId) ?: return null
        val swapped = active.copy(
            playback = active.playback.copy(
                playUrl = newUrl,
                streamId = rung.streamId,
                backupStreamIds = rung.backupHandles,
            ),
            print = active.print.copy(resolution = lowest),
        )
        val updated = bundle.copy(renditions = bundle.renditions.toMutableList().also { it[index] = swapped })
        val machine = FailoverStateMachine(updated.renditions, updated.failurePolicy)
        if (machine.selectInitial() != index) return null
        failover = machine
        store.replaceRendition(index, swapped)
        store.setAutoBand(lowest)
        store.appendFailover(
            listOf(
                FailoverRecord(
                    swapped.rank, swapped.releaseId, swapped.displayLabel(),
                    "start-low (no measurement yet)",
                    "auto opened on $lowest",
                ),
            ),
        )
        return updated
    }

    /**
     * The loopback address of a complete kept copy of this rendition, or null when there is none.
     *
     * The cache identifies a file by the opaque handle embedded in OUR OWN redirector address, so
     * only such an address can name one. A `content://`/`file://` download and a deep link have no
     * such handle and are played exactly as handed over. An engine failure is silence, not an
     * error: the caller then streams from the server as it always did.
     */
    private suspend fun localCopyUrlFor(rendition: PlaybackRendition?): String? {
        val playback = rendition?.playback ?: return null
        val url = playback.playUrl?.takeIf { it.isNotBlank() } ?: return null
        val handle = playback.streamId?.takeIf { it.isNotBlank() } ?: return null
        val engine = CacheEngineHolder.engine ?: return null
        return try {
            engine.playableUri(CacheSessionKey(renditionKey = handle, url = url))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun loadRendition(bundle: PlaybackBundle, index: Int) =
        loadRenditionOf(bundle, bundle.renditions.getOrNull(index))

    /**
     * Start one rendition exactly as given. Taking the rendition rather than an index is what
     * lets a silent hop play a *different handle of the same rung*: the copy the caller hands
     * here is the active rendition with our own redirector's `id=` swapped, so nothing about
     * the quality, the print or the origin changes.
     */
    private fun loadRenditionOf(
        bundle: PlaybackBundle,
        rendition: PlaybackRendition?,
        countAsAttempt: Boolean = true,
        isLocalCopy: Boolean = false,
    ) {
        disarmStartWatchdog()
        val exo = player ?: return
        rendition ?: return
        if (rendition.playback.isExpired(System.currentTimeMillis())) {
            // Never play a rotted URL silently — §5: re-request instead. An expired
            // handle is not a failure to retry: the machine hands off as EXPIRED_LINK
            // (NAV-4) so the card names the expired link, not a dead print.
            failover?.let { machine ->
                val decision = machine.onFailure("link expired before load", isExpiry = true)
                applyDecision(bundle, decision, preservePositionMs = lastKnownPositionMs)
            }
            return
        }
        // Tracking A: one load that actually proceeds is one playback
        // attempt. A failover hop counts — it is a new try at a different
        // copy — and an auto-quality swap of the same title does not (its
        // caller passes false). An expired link counts nothing, because the
        // early return above already left before any play was attempted.
        if (countAsAttempt) Telemetry.recordPlaybackAttempt()
        // Gap A3: the bundle's subtitle tracks ride the rendition, so a
        // failover hop rebuilds with the same tracks — they are not
        // ViewModel state, which a hop would lose.
        val item = PlaybackMediaItemFactory.build(
            rendition, bundle.target, titleHint,
            externalSubtitles = PlaybackMediaItemFactory.externalsFromTracks(rendition.tracks),
        )
        // Session 159 (S5): remember what this load actually is — the
        // failover entries below need to know whether the attempt that
        // failed was the kept copy or the live link. Set here, after the
        // early returns, so a load that never reached Media3 leaves the
        // previous load's answer untouched.
        lastLoadedWasLocalCopy = isLocalCopy
        if (store.castIsCasting.value) {
            // MX #9 — playback is on the receiver, so the load goes there:
            // a next episode, a manual rung change, or a hop lands on the
            // TV at the pending position, not on the paused local player.
            // A kept copy is a loopback address the receiver can never
            // fetch: cast the rendition's live redirector URL instead (the
            // store's rendition still holds it — the copy only ever
            // rewrote the load's own address). No preview is seeded and no
            // watchdog is armed: the phone downloads nothing while casting,
            // and the local player's state says nothing about the remote
            // load. The beacon keeps the session: the title is being
            // watched, on the TV.
            val manager = castTransferManager ?: return
            val castRendition = if (isLocalCopy) store.activeRendition() ?: rendition else rendition
            val castItem = if (isLocalCopy) {
                PlaybackMediaItemFactory.build(
                    castRendition, bundle.target, titleHint,
                    externalSubtitles = PlaybackMediaItemFactory.externalsFromTracks(castRendition.tracks),
                )
            } else {
                item
            }
            val positionMs = pendingResumeMs ?: lastKnownPositionMs
            pendingResumeMs = null
            lastKnownPositionMs = positionMs
            lastLoadedWasLocalCopy = false
            manager.transferToCast(castItem, positionMs, playWhenReady = true)
            beginBeaconFor(castRendition, isLocalCopy = false)
            return
        }
        exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = true
        beginBeaconFor(rendition, isLocalCopy)
        // Every load of a real address is a title being watched, which is the whole trigger a
        // preview has. A hop to a backup of the same print seeds again through the same line, and
        // the engine's own idempotence turns that second ask into no second row. A kept copy played
        // from disk is not: its address is our loopback proxy, which names no handle of the
        // redirector's, so the ask falls through to nothing.
        seedPreviewFor(rendition)
        // The stall watchdog: a load that never errors and never renders
        // must fail over on its own at twenty seconds, not sit on the
        // "Warming the link" notice until the thirty-second card.
        armStartWatchdog(bundle, rendition)
    }

    /**
     * The stall watchdog (2026-11-12 rework, audit #1): a load that never
     * errors and never renders — a hung resolve, a stalled CDN, a playlist
     * that never arrives — used to sit on the "Warming the link" notice
     * until the thirty-second card, and nothing ever advanced it. After
     * [StartDeadline.START_WATCHDOG_MS] with no first frame, the attempt
     * is failed over exactly as a player error before the first frame
     * would be: a silent hop to a backup, then the next rank, then the
     * honest give-up — never a retry of the same address (see
     * [FailoverStateMachine.onFailure]'s S2 rule). A first frame disarms
     * it; a new load re-arms it.
     *
     * Batch C (2026-11-14, Robo APK 499): the watchdog used to leave
     * `beforeFirstFrame` at its false default, so its first failover was
     * RetrySame — re-pressing the same stalled address (and re-seeding a
     * preview press-pair) while the 30 s clock ran out. That is how a
     * stalled McLintock! play showed the timeout card instead of the
     * honest rate-limited handoff: the re-press re-paid the refused
     * bucket, and no 429 ever surfaced as a player error for the
     * 429-as-pause path to catch.
     */
    private fun armStartWatchdog(bundle: PlaybackBundle, rendition: PlaybackRendition) {
        // MX #9 — the watchdog watches the local player, whose state says
        // nothing about a remote load. While casting there is no local
        // start to time out.
        if (store.castIsCasting.value) return
        watchdogJob?.cancel()
        val generation = ++watchdogGeneration
        watchdogJob = scope.launch {
            delay(StartDeadline.START_WATCHDOG_MS)
            if (generation != watchdogGeneration) return@launch
            if (store.castIsCasting.value) return@launch
            val exo = player ?: return@launch
            if (StartDeadline.hasFirstFrame(exo.playbackState)) return@launch
            val machine = failover ?: return@launch
            val decision = machine.onFailure(
                "start timed out after ${StartDeadline.START_WATCHDOG_MS / 1_000} s with no first frame",
                // Batch C (2026-11-14): a stalled start IS a start failure —
                // the machine must hop to a different file, never retry the
                // same address (S2). The one exception stays: a stalled
                // kept copy falls back to the live link through
                // isLocalCopyAttempt, exactly as a failed one does.
                beforeFirstFrame = true,
                isLocalCopyAttempt = lastLoadedWasLocalCopy,
            )
            // The beacon counts a hung start as a failure of this load; the
            // session finalises when the hop replaces it.
            beaconRecorder.noteWatchdogTimeout()
            applyDecision(bundle, decision, preservePositionMs = lastKnownPositionMs)
        }
    }

    private fun disarmStartWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun applyDecision(
        bundle: PlaybackBundle,
        decision: FailoverDecision,
        preservePositionMs: Long,
    ) {
        when (decision) {
            is FailoverDecision.RetrySame -> {
                store.appendFailover(listOf(decision.record))
                scope.launch {
                    delay(decision.backoffMs)
                    loadRendition(bundle, decision.index)
                }
            }
            is FailoverDecision.RetryWithSoftwareDecoder -> {
                // MX idea #1: same rendition, same position, fresh prepare —
                // no backoff, the decoder is the problem, not the network.
                // The player was built with EXTENSION_RENDERER_MODE_PREFER,
                // so the fresh prepare re-runs renderer selection with the
                // FFmpeg audio renderer first. (It claims AAC and the other
                // common formats too, not only AC-3/DTS — see the MX #1
                // comment at buildPlayer.)
                store.appendFailover(listOf(decision.record))
                pendingResumeMs = preservePositionMs.takeIf { it > 0 }
                loadRendition(bundle, decision.index)
            }
            is FailoverDecision.RetryWithOtherDecoderMode -> {
                // MX idea #3: same rendition, same position, rebuilt player —
                // no backoff, the decoder is the problem, not the network.
                // The mode is set in the store and the decoderMode collection
                // rebuilds the player in it and reloads this rendition at the
                // saved position. The persisted preference is NOT overwritten:
                // the next title starts in the mode the viewer chose.
                store.appendFailover(listOf(decision.record))
                store.setDecoderMode(decision.targetMode)
                store.announceDecoderFallback(decision.targetMode)
            }

            is FailoverDecision.AdvanceBackup -> {
                // Silent by design: no banner, no "settled on" chip, same rung. Only the
                // auditable trail records the hop, for the diagnostics sheet.
                store.appendFailover(listOf(decision.record))
                val active = bundle.renditions.getOrNull(decision.index) ?: return
                val hopped = active.copy(
                    playback = active.playback.copy(playUrl = decision.playUrl, streamId = decision.streamId),
                )
                pendingResumeMs = preservePositionMs.takeIf { it > 0 }
                loadRenditionOf(bundle, hopped)
            }

            is FailoverDecision.Advance -> {
                store.appendFailover(listOf(decision.record))
                pendingResumeMs = preservePositionMs.takeIf { it > 0 }
                store.setActiveIndex(decision.index, announceSettled = true)
                loadRendition(bundle, decision.index)
            }

            is FailoverDecision.Handoff -> {
                decision.record?.let { store.appendFailover(listOf(it)) }
                store.markGaveUp(decision.detail ?: "Playback stopped", decision.why)
            }
        }
    }

    /**
     * Band the loaded rung plays at, in the switcher's vocabulary. The print's own
     * resolution string is used when the ladder knows that band, so a hand-picked rung and
     * an Auto rung are described the same way; otherwise the staged start band.
     */
    private fun bandOfRendition(rendition: PlaybackRendition?): String? {
        val resolution = rendition?.print?.resolution?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return if (resolution != null && store.autoQuality.rungFor(resolution) != null) {
            resolution
        } else {
            store.autoQuality.currentBand
        }
    }

    /**
     * Begin measuring this load for the beacon — or finalise the previous
     * load and measure nothing, when there is no host to rank.
     *
     * A kept copy played from disk names no handle of the redirector's, and
     * a deep-linked load with no handle staged is the same: neither starts
     * a session. The previous session finalises here either way, so a
     * failover hop or a rung change records the load it replaces instead of
     * dropping it.
     */
    private fun beginBeaconFor(rendition: PlaybackRendition, isLocalCopy: Boolean) {
        val streamId = if (isLocalCopy) null
            else rendition.playback.streamId?.takeIf { it.isNotBlank() }
        val band = streamId?.let { bandOfRendition(rendition) }
        val finished = if (streamId != null && band != null) {
            beaconRecorder.start(
                streamId,
                band,
                AdaptiveQualitySwitcher.nominalBitrateFor(band),
                beaconNetFor(ConnectivityProbe.current(applicationContext)),
            )
        } else {
            beaconRecorder.finishCurrent()
        }
        finished?.let { PlaybackBeacons.record(it) }
    }

    /**
     * The beacon's closed error class for a player failure. Only the two IO
     * codes are mapped — a network failure and a network timeout are the
     * ones the host owns. Everything else reads "unknown": the failover
     * machine already translates the exact code into human words for the
     * viewer, and the beacon must not invent a decode/drm split it cannot
     * prove from the code alone. The server counts "unknown" failures
     * outside the host's column, so an unmapped code never blames a host it
     * cannot name.
     */
    private fun beaconErrorCode(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> PlaybackBeaconErrors.NETWORK
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackBeaconErrors.TIMEOUT
        else -> PlaybackBeaconErrors.UNKNOWN
    }

    // ── Auto quality: measured throughput → the next rung of the staged ladder ────

    /**
     * Arm the shared director for one playback. Called on every load, so a deep-linked
     * title (no ladder staged) gets an unarmed director instead of inheriting the previous
     * title's rung list — Auto then has nowhere to move and says nothing.
     */
    private fun armAutoQuality(bundle: PlaybackBundle, index: Int) {
        val plan = store.peekAutoLadder(bundle.renditions.getOrNull(index)?.playback?.streamId)
        val rungs = plan?.rungs.orEmpty()
        val startBand = plan?.startBand
            ?: bundle.renditions.getOrNull(index)?.print?.resolution?.trim()?.lowercase()
        store.autoQuality.configure(rungs, startBand, plan?.auto == true)
        store.setAutoBand(if (store.autoQuality.isEnabled()) store.autoQuality.currentBand else null)
    }

    /**
     * Media3's own load statistics are the sample source: every finished load reports the
     * bytes it moved and the wall time it took. No hand-rolled counting data source is
     * needed (and 1.3.1 ships no `CounterDataSource` to wrap with), which also means the
     * numbers are exactly what the player's bandwidth meter saw.
     */
    private val autoQualityListener = object : AnalyticsListener {
        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            feedSample(loadEventInfo, mediaLoadData)
        }

        override fun onLoadCanceled(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            // A cancelled load moved real bytes in real time (a seek, a rung change); the
            // sample is valid. Load ERRORS are not fed — a stalled connection reports a
            // tiny duration and would look like a spectacular speed.
            feedSample(loadEventInfo, mediaLoadData)
        }

        private fun feedSample(loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
            if (mediaLoadData.trackType !in MEASURABLE_TRACK_TYPES) return
            store.autoQuality.push(loadEventInfo.bytesLoaded, loadEventInfo.loadDurationMs)
            beaconRecorder.noteLoadSample(loadEventInfo.bytesLoaded, loadEventInfo.loadDurationMs)
        }
    }

    private fun startAutoQualityTicker() {
        scope.launch {
            while (true) {
                delay(AUTO_QUALITY_TICK_MS)
                evaluateAutoQuality()
            }
        }
    }

    /**
     * Feed the offline cache the playhead, from the one place that is alive for the whole of
     * playback.
     *
     * This is the pump the brief's `onPlayheadAdvanced` hook runs on. The engine's entry point looks
     * a task up by the key of the address being watched and returns when nothing answers, so a
     * progressing playhead only extends retention if SOMETHING is publishing it — and until now the
     * only publisher was the player screen's 200 ms UI ticker, which stops the moment the viewer
     * leaves the screen. Backgrounding a title to keep listening is the normal way this app is used,
     * so the cache was being fed while the poster was on screen and never again. The service owns the
     * player, so the service owns the position.
     *
     * Five seconds, and only on a move: [CacheCoordinator.onPlayheadAdvanced] answers a single
     * bounded pass and then parks until the byte it is shown advances further, so a faster feed
     * buys nothing but [com.raddstream.app.data.cache.CacheTaskDao] lookups — five a second at the
     * old cadence, for a window measured in tens of megabytes.
     */
    private fun startPlayheadPump() {
        scope.launch {
            var lastReportedMs = -1L
            while (true) {
                delay(PLAYHEAD_PUMP_MS)
                // MX #9 — while casting the phone downloads nothing, so
                // there is no ahead-pass to extend; the local position is
                // stale besides. The return path reads the remote position
                // from the CastPlayer instead.
                if (store.castIsCasting.value) continue
                val exo = player ?: continue
                // A paused player has no forward edge to anticipate; a seek still moves the
                // position, and that is reported on the next tick exactly like playback does.
                if (!exo.isPlaying) continue
                val duration = if (exo.duration > 0L) exo.duration else exo.contentDuration
                if (duration <= 0L) continue
                val position = exo.currentPosition.coerceAtLeast(0L)
                if (position == lastReportedMs) continue
                lastReportedMs = position
                store.reportPlayhead(store.activeRendition()?.playback?.playUrl, position, duration)
            }
        }
    }

    /**
     * Watching is not keeping, and this says nothing either way. It creates the one opportunistic
     * row the playhead pump above needs in order to have anything to extend: the engine's playhead
     * entry point resolves a task by the watched address and no-ops when there is none, so a
     * preview that is never seeded leaves the whole ahead-of-playback path dead on arrival.
     *
     * Silent by contract. A preview nobody asked for must not be able to produce a gate, a spinner,
     * a toast, a Downloads row it cannot fill, or an evicted copy it did not want — [CacheEngineHolder]
     * gives the engine every one of those refusals, and none of them belongs on screen.
     */
    private fun seedPreviewFor(rendition: PlaybackRendition?) {
        val playback = rendition?.playback ?: return
        val url = playback.playUrl?.takeIf { it.isNotBlank() } ?: return
        val handle = playback.streamId?.takeIf { it.isNotBlank() } ?: return
        val engine = CacheEngineHolder.engine ?: return
        scope.launch {
            try {
                engine.seedPreview(
                    PreviewRequest(
                        key = CacheSessionKey(renditionKey = handle, url = url),
                        url = url,
                        expectedTotalBytes = rendition.print.sizeBytes.takeIf { it > 0L },
                        resumable = playback.resumable || playback.acceptsRanges == true,
                        printLabel = rendition.displayLabel(),
                    ),
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The stream plays exactly as it would have. A preview is the only kind of cache
                // work that can fail without the viewer ever having a right to know.
            }
        }
    }

    /**
     * One Auto decision per [AUTO_QUALITY_TICK_MS]. Only ever while the viewer is actually
     * watching: while paused or buffering nothing is being fed, so nothing is known, and a
     * switch would be a guess.
     */
    private fun evaluateAutoQuality() {
        // MX #9 — while casting the receiver fetches the bytes, so the
        // phone measures nothing and the director must not move a rung on
        // a guess. A manual rung change still goes through [switchRungTo]
        // and lands on the receiver through [loadRenditionOf].
        if (store.castIsCasting.value) return
        val director = store.autoQuality
        if (!director.isEnabled()) return
        val exo = player ?: return
        if (exo.playbackState != Player.STATE_READY || !exo.isPlaying) return
        val bundle = store.bundle.value ?: return
        val index = store.activeRenditionIndex.value
        val active = bundle.renditions.getOrNull(index) ?: return
        lastKnownPositionMs = maxOf(lastKnownPositionMs, runCatching { exo.currentPosition }.getOrDefault(0L))
        // Re-sync with reality first: a silent hop or a rank failover may have moved the rung.
        director.markPlaying(bandOfRendition(active))
        val from = director.currentBand
        val target = director.targetBand() ?: return
        if (target == from) return
        switchRungTo(bundle, index, active, target, from)
    }

    /**
     * Move playback to another rung of the SAME staged ladder, keeping the position. This is
     * the rung-level sibling of [FailoverDecision.AdvanceBackup]: one handle of ours is
     * swapped for another on our own redirector by [streamUrlForHandle], so the address the
     * player gets is still ours, only now addressed to the band Auto chose.
     *
     * The failover plan is rebuilt from the updated rung list because its runner-ups belong
     * to the rung, not to the slot in the list — leaving the old plan in place would hop a
     * later error back to the rung we just left. If that rebuild does not land on the index
     * we are playing (a multi-rank bundle), the switch is refused rather than guessed.
     */
    private fun switchRungTo(
        bundle: PlaybackBundle,
        index: Int,
        active: PlaybackRendition,
        targetBand: String,
        fromBand: String?,
        reason: String? = null,
    ) {
        val director = store.autoQuality
        val rung = director.rungFor(targetBand) ?: return
        if (rung.streamId == active.playback.streamId) return
        val newUrl = streamUrlForHandle(active.playback.playUrl, rung.streamId)
        if (newUrl == null) {
            director.markPlaying(fromBand)
            store.appendFailover(
                listOf(
                    FailoverRecord(
                        active.rank, active.releaseId, active.displayLabel(),
                        "auto wanted $targetBand", "skipped (no address of ours on that rung)",
                    ),
                ),
            )
            return
        }
        val swapped = active.copy(
            playback = active.playback.copy(
                playUrl = newUrl,
                streamId = rung.streamId,
                backupStreamIds = rung.backupHandles,
            ),
            print = active.print.copy(resolution = targetBand),
        )
        val updated = bundle.copy(renditions = bundle.renditions.toMutableList().also { it[index] = swapped })
        val machine = FailoverStateMachine(updated.renditions, updated.failurePolicy)
        if (machine.selectInitial() != index) {
            director.markPlaying(fromBand)
            store.appendFailover(
                listOf(
                    FailoverRecord(
                        active.rank, active.releaseId, active.displayLabel(),
                        "auto wanted $targetBand", "skipped (failover plan is multi-rank)",
                    ),
                ),
            )
            return
        }
        failover = machine
        store.replaceRendition(index, swapped)
        store.setAutoBand(targetBand)
        // Hate-audit H9: Auto moved the band mid-title — say so once, briefly,
        // in the settled banner's own 5s transient. A hand-picked switch never
        // comes through here, so this only ever fires for Auto's own moves.
        store.announceQualityChange(targetBand)
        store.appendFailover(
            listOf(
                FailoverRecord(
                    swapped.rank, swapped.releaseId, swapped.displayLabel(),
                    reason ?: "measured ${formatBitrate(director.estimateBps)}",
                    reason?.let { "stepped down to $targetBand" }
                        ?: "auto moved ${fromBand ?: "the current rung"} → $targetBand",
                ),
            ),
        )
        pendingResumeMs = (activePlayer()?.let {
            runCatching { it.currentPosition }.getOrDefault(lastKnownPositionMs)
        } ?: lastKnownPositionMs).takeIf { it > 0 }
        loadRenditionOf(updated, swapped, countAsAttempt = false)
    }

    private fun formatBitrate(bps: Long): String = when {
        bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
        bps >= 1_000 -> "%d kbps".format(bps / 1_000)
        else -> "$bps bps"
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    // A first frame disarms the stall watchdog: the load
                    // landed, and a later rebuffer is the buffering spinner's
                    // job, not a failed start.
                    disarmStartWatchdog()
                    // The beacon's first frame: the start is over, and any
                    // buffering from here is a stall. The call also closes a
                    // stall that was open, so a rebuffer that ends in READY
                    // is counted even if it ends the session.
                    beaconRecorder.noteFirstFrame()
                    beaconRecorder.noteBufferingEnded()
                    // Session 155: the start error (if any) is spent — a
                    // later give-up must not report a stale start failure.
                    store.clearStartError()
                    pendingResumeMs?.let { pos ->
                        // MX #9 — the player that just became ready, local
                        // or remote: a resume belongs to the load that
                        // landed, not to the field that happens to hold the
                        // local player.
                        activePlayer()?.seekTo(pos)
                        pendingResumeMs = null
                    }
                }

                Player.STATE_BUFFERING -> {
                    // Buffering after the first frame is a stall; buffering
                    // before it is the start, and the recorder ignores that.
                    beaconRecorder.noteBufferingStarted()
                }

                Player.STATE_ENDED -> handleEnded()

                else -> Unit
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                lastKnownPositionMs = newPosition.positionMs
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // The beacon records the failure class now; the session itself
            // finalises as "failed" when the next load replaces it, or when
            // the service is destroyed — the failover machine may still hop,
            // and a hop is a new session, not the end of this one.
            beaconRecorder.noteError(beaconErrorCode(error))
            // Tracking A: a network failure while buffering is a buffer
            // failure; anything else (decode, renderer) is a playback error.
            // The split is the one the server's per-class report reads, and
            // the error itself — code, message, title — is never sent, only
            // the count. Counted before the early return below, because an
            // error with no bundle left is still an error the viewer saw.
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            ) {
                Telemetry.recordPlaybackBufferFailure()
            } else {
                Telemetry.recordPlaybackError()
            }
            val bundle = store.bundle.value ?: return
            lastKnownPositionMs = maxOf(lastKnownPositionMs, errorGetMs())
            // MX #9 — an error on the receiver is not a local failover:
            // the failover machine's hops are local loads, and running one
            // would start the phone playing under a TV showing an error.
            // The receiver failed — come home, reload locally at the remote
            // position, and let the local player's own error path judge
            // the link there. The banner says the TV failed; the viewer
            // keeps watching, on this phone.
            if (store.castIsCasting.value) {
                castReturnFailed = true
                castTransferManager?.endSession()
                return
            }
            val machine = failover ?: return
            // Session 121 (P-6): the raw ExoPlayer code is written to logcat here and
            // nowhere else — the failover machine translates it to human words before
            // any user-visible surface shows it, so diagnosis loses nothing.
            val rawSummary = summarize(error)
            Log.d("PlaybackService", "player error: $rawSummary")
            // Session 155 (S2): whether a first frame has landed decides
            // the failover shape (see FailoverStateMachine.onFailure) and
            // whether this error is a start error. The exact Media3 error
            // is kept for the give-up report, because the device test
            // returns no logcat — without it the report only says the
            // start timed out, which is the symptom, not the cause.
            val beforeFirstFrame = player?.let { !StartDeadline.hasFirstFrame(it.playbackState) } ?: true
            if (beforeFirstFrame) store.recordStartError(rawSummary)
            val gateRefusal = isGateRefusalError(error)
            // Session 158 (S5): a dead handle (404/403/410 from our own
            // redirector) is not a blip — the failover machine must hop,
            // never retry the same address.
            val deadHandle = isDeadHandleError(error)
            // Batch C (2026-11-14, 429-storm audit): a 429 from our own
            // redirector means the per-IP play bucket is full — the machine
            // must pause, never hop or retry into the same refused bucket.
            val rateLimited = isRateLimitedError(error)
            if (gateRefusal) {
                // Salaar (2026-11-12): the gate refused the rung now opening, and
                // every copy of that rung shares the refusal. Before the first
                // frame has landed, step DOWN the staged ladder at once — the
                // next lower rung is the picture this viewer may actually play —
                // instead of letting the failover plan burn its budget on copies
                // the server will refuse again. Only at start: a refusal after
                // the first frame is a different failure (the rung played once).
                val exo = player
                if (exo != null && !StartDeadline.hasFirstFrame(exo.playbackState)) {
                    val index = store.activeRenditionIndex.value
                    val active = bundle.renditions.getOrNull(index)
                    val fromBand = active?.let { bandOfRendition(it) }
                    val lower = fromBand?.let { store.autoQuality.lowerRungThan(it) }
                    if (active != null && lower != null) {
                        switchRungTo(
                            bundle, index, active, lower.band, fromBand,
                            reason = "gate refused ${fromBand ?: "this rung"}",
                        )
                        return
                    }
                }
            }
            // Session 159 (S5): the attempt that just failed may have been
            // the kept copy, not the live link — the machine's RetrySame
            // then loads the redirector, not the failed copy.
            // MX idea #1 (2026-11-18): a decode failure is a decoder problem,
            // not a dead copy. The machine retries the same rendition once
            // with the software decoder before hopping — but only when the
            // FFmpeg library is actually there (FfmpegSupport answers false
            // on a build without the AAR and on an ABI it did not ship).
            val isDecodingFailed =
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
            val decision = machine.onFailure(
                rawSummary,
                isGateRefusal = gateRefusal,
                beforeFirstFrame = beforeFirstFrame,
                isDeadHandle = deadHandle,
                isRateLimited = rateLimited,
                isLocalCopyAttempt = lastLoadedWasLocalCopy,
                isDecodingFailed = isDecodingFailed,
                isSoftwareDecoderAvailable = FfmpegSupport.isSoftwareAudioAvailable,
                currentDecoderMode = currentDecoderMode,
            )
            // H4 R7: a rendition abandoned AFTER the first frame died mid-play — the server
            // can never see a segment fetch (the 302 hands the player straight to the host),
            // so this record is the only way it learns the link is dead. The ViewModel
            // reports it once (best-effort) from the store slot; the hop itself still
            // proceeds below. Before the first frame this is a start failure, which the
            // ViewModel's deadline path already reports — never report the same death twice.
            if (!beforeFirstFrame) {
                val reportClass = midPlaybackReportClass(
                    decision,
                    isDeadHandle = deadHandle,
                    isGateRefusal = gateRefusal,
                    isRateLimited = rateLimited,
                    isDecodingFailed = isDecodingFailed,
                )
                if (reportClass != null) {
                    store.activeRendition()?.playback?.streamId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { handle ->
                            store.recordMidPlaybackFailure(handle, reportClass, rawSummary)
                        }
                }
            }
            applyDecision(bundle, decision, preservePositionMs = lastKnownPositionMs)
        }

        private fun errorGetMs(): Long = runCatching { activePlayer()?.currentPosition ?: 0L }.getOrDefault(0L)

        private fun summarize(e: PlaybackException): String {
            val causeName = e.errorCodeName
            val detail = e.message?.substringBefore('\n')?.take(90).orEmpty()
            return "$causeName $detail".trim()
        }
    }

    /** MX-style auto-advance across coverage types (in-file part 1; cross-file via store). */
    private fun handleEnded() {
        val bundle = store.bundle.value ?: return
        val active = store.activeRendition() ?: return
        // MX #9 — the player that ended, local or remote: an in-file seek
        // belongs to the player carrying playback, and a next-episode load
        // lands on it through [loadRenditionOf]'s cast branch.
        val exo = activePlayer() ?: return
        val action = EpisodeProgression.computeNext(
            bundle = bundle,
            rendition = active,
            learned = store.learnedOffsets,
            positionMs = exo.currentPosition,
            durationMs = maxOf(exo.duration, 0L),
        )
        when (action) {
            is NextEpisodeAction.InFileSeek -> {
                pendingResumeMs = action.positionMs
                exo.seekTo(action.positionMs)
                exo.playWhenReady = true
            }

            is NextEpisodeAction.NextBundleRequest,
            is NextEpisodeAction.CalibrationNeeded,
            -> {
                // The bundle ends here; the next episode is a new load with
                // a new beacon. This session played: a first frame landed.
                beaconRecorder.finishCurrent()?.let { PlaybackBeacons.record(it) }
                store.awaitNextEpisode(action)
            }

            NextEpisodeAction.StopHere, NextEpisodeAction.Unknown -> {
                // Nothing further loads: the session ends here.
                beaconRecorder.finishCurrent()?.let { PlaybackBeacons.record(it) }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // MX #9 — swiping the app away while casting must not kill the TV:
        // the receiver keeps playing after the phone app dies, which is
        // the normal Cast behaviour. The service stays alive while the
        // session is live and ends with it.
        if (store.castIsCasting.value) {
            super.onTaskRemoved(rootIntent)
            return
        }
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // The session ends with the service: played if a frame landed,
        // failed if an error was recorded, abandoned otherwise. Anything
        // still pending rides the next progress write from the store.
        beaconRecorder.finishCurrent()?.let { PlaybackBeacons.record(it) }
        scope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let { listener ->
                getSystemService(PowerManager::class.java)?.removeThermalStatusListener(listener)
            }
            thermalListener = null
        }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }

    companion object {
        /** MX default double-tap / seek button increment. */
        const val MX_SKIP_MS = 10_000L

        /**
         * How often Auto is allowed to move a rung. Comfortably longer than the switcher's
         * own smoothing window matters (a step down still needs five confirmed samples), and
         * short enough that a viewer on a dying link is off the too-high rung within a
         * re-buffer rather than within a scene.
         */
        const val AUTO_QUALITY_TICK_MS = 2_000L

        /**
         * How often the service offers the playhead to the offline cache. Longer than the UI's own
         * 200 ms clock on purpose: the engine answers one bounded pass per advance and then parks,
         * so anything faster is a database lookup per frame for a window that is tens of megabytes
         * wide. Five seconds is short enough that a viewer who seeks onto unbuffered ground has an
         * ahead-pass running before the player has re-buffered twice.
         */
        const val PLAYHEAD_PUMP_MS = 5_000L

        /** Loads worth measuring: media bytes. Subtitles and metadata would dilute the rate. */
        val MEASURABLE_TRACK_TYPES = setOf(C.TRACK_TYPE_DEFAULT, C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
    }
}
