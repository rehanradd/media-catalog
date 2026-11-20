package com.raddstream.app.viewmodel

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.raddstream.app.brand.Brand
import com.raddstream.app.player.AbRepeat
import com.raddstream.app.player.AspectMode
import com.raddstream.app.player.AudioBoostUi
import com.raddstream.app.player.audio.AudioEnhancementSettings
import com.raddstream.app.player.audio.EqPresets
import com.raddstream.app.player.audio.EqState
import com.raddstream.app.player.activeSegment
import com.raddstream.app.player.skipTargetMs
import com.raddstream.app.player.AudioTrackMatcher
import com.raddstream.app.player.AudioTrackPolicy
import com.raddstream.app.player.AudioTrackRow
import com.raddstream.app.player.AutoplayCountdown
import com.raddstream.app.player.BingePrefetchPlanner
import com.raddstream.app.player.BingePreloadDecision
import com.raddstream.app.player.CacheAffordance
import com.raddstream.app.player.CacheSessionKey
import com.raddstream.app.player.PreviewRequest
import com.raddstream.app.player.CacheEngineHolder
import com.raddstream.app.player.CacheSnapshot
import com.raddstream.app.player.CacheState
import com.raddstream.app.player.CalibrationState
import com.raddstream.app.player.CastAvailabilityTracker
import com.raddstream.app.player.DataSaver
import com.raddstream.app.player.DecoderMode
import com.raddstream.app.player.DeferredOffsetReporter
import com.raddstream.app.player.directVideoIsHls
import com.raddstream.app.player.directVideoMime
import com.raddstream.app.player.directVideoTitle
import com.raddstream.app.player.EpisodeOffsets
import com.raddstream.app.player.EpisodeProgression
import com.raddstream.app.player.ExternalSubtitle
import com.raddstream.app.player.KeepResult
import com.raddstream.app.player.KeepUiState
import com.raddstream.app.player.LadderForScope
import com.raddstream.app.player.LadderRung
import com.raddstream.app.player.LanguageDefaults
import com.raddstream.app.player.NextEpisodeAction
import com.raddstream.app.player.PlaybackBundle
import com.raddstream.app.player.PlaybackInfo
import com.raddstream.app.player.PlaybackMediaItemFactory
import com.raddstream.app.player.PlaybackRendition
import com.raddstream.app.player.PlayTarget
import com.raddstream.app.player.PlayerOrientation
import com.raddstream.app.player.PlayerSessionStore
import com.raddstream.app.player.PrintInfo
import com.raddstream.app.player.SessionInfo
import com.raddstream.app.player.ShareMoment
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.player.QualityLadder
import com.raddstream.app.player.RippleState
import com.raddstream.app.player.ScreenZone
import com.raddstream.app.player.SleepMode
import com.raddstream.app.player.SleepTick
import com.raddstream.app.player.SleepTimer
import com.raddstream.app.player.StartDeadline
import com.raddstream.app.player.StartPhase
import com.raddstream.app.player.SubtitleTrackDecision
import com.raddstream.app.player.SubtitleTrackPolicy
import com.raddstream.app.player.SubtitleUiSettings
import com.raddstream.app.player.TrackUiOption
import com.raddstream.app.player.TracksInfo
import com.raddstream.app.player.ViewMode
import com.raddstream.app.player.abRepeatWithA
import com.raddstream.app.player.abRepeatWithB
import com.raddstream.app.player.loopTargetMs
import com.raddstream.app.player.mergePostPlayPreviews
import com.raddstream.app.player.resolveStartBand
import com.raddstream.app.player.shouldAutoAdvance
import com.raddstream.app.player.subtitle.OverlayCue
import com.raddstream.app.player.subtitle.OverlaySubtitleFile
import com.raddstream.app.player.subtitle.SubtitleFormat
import com.raddstream.app.player.subtitle.SubtitleLineJump
import com.raddstream.app.player.subtitle.SubtitleParser
import com.raddstream.app.player.subtitles.AudioOffset
import com.raddstream.app.player.subtitles.SubtitleOffset
import com.raddstream.app.data.LocalResumeRepository
import com.raddstream.app.data.isLocalResumeUri
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.data.cache.ConnectivityProbe
import com.raddstream.app.data.cache.NetworkClass
import com.raddstream.app.data.downloads.SmartDownloadsRunner
import com.raddstream.app.data.local.TitleSyncPrefs
import com.raddstream.app.data.local.TitleSyncPrefsDao
import com.raddstream.app.data.local.WatchHistoryDao
import com.raddstream.app.network.ApiProvider
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.parseCastNames
import com.raddstream.app.network.streamRedirectUrl
import com.raddstream.app.network.withVipPass
import com.raddstream.app.repository.CacheRepository
import com.raddstream.app.repository.CatalogRepository
import com.raddstream.app.repository.FavoritesRepository
import com.raddstream.app.repository.KeepOutcome
import com.raddstream.app.sync.SyncAccountRepository
import com.raddstream.app.ui.navigation.DeepLinks
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.sign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException

/**
 * Dev-cleanup (Nov 2026, E1): the service-bind failure shows this one fixed sentence.
 * A literal, not a player_* resource — the ChromeCopy ratchet requires every player_*
 * string to be painted from PlayerScreen.kt, and this card is set here, in the ViewModel,
 * before the screen ever sees it. The ViewModel's other viewer copy is literals too.
 */
internal const val PLAYER_SERVICE_UNAVAILABLE_MESSAGE = "Couldn't open the player. Try again."

/**
 * UI brain of the player. It does NOT own an ExoPlayer — [com.raddstream.app.player.PlaybackService]
 * does. This ViewModel drives that player through a Media3 [MediaController] (the same object
 * that backs notifications / lock screen / headsets) and keeps every MX-style control surface
 * state in one immutable [PlayerUiState].
 *
 * Created with `hiltViewModel()` from `PlayerScreen`.
 */
@HiltViewModel
@OptIn(UnstableApi::class)
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val apiProvider: ApiProvider,
    private val settingsRepository: ServerSettingsRepository,
    private val catalogRepository: CatalogRepository,
    private val favoritesRepository: FavoritesRepository,
    private val syncAccountRepository: SyncAccountRepository,
    private val watchHistoryDao: WatchHistoryDao,
    private val titleSyncPrefsDao: TitleSyncPrefsDao,
    private val localResumeRepository: LocalResumeRepository,
    private val connectivityProbe: ConnectivityProbe,
    private val smartDownloads: SmartDownloadsRunner,
    private val cacheRepository: CacheRepository,
) : ViewModel() {

    private val store: PlayerSessionStore = PlayerSessionStore.Instance

    // MX #9 — Cast: the server's chromecast switch and the network's
    // discoverable receivers, as the ViewModel last saw them. The button
    // needs both plus an unlocked player; the store's gate needs the
    // switch plus the lock only (a session cannot exist without a device).
    private var castFeatureEnabled: Boolean = true
    private var castDevicesAvailable: Boolean = false
    private val castAvailabilityTracker = CastAvailabilityTracker(appContext)

    /**
     * MX #9 — recompute the Cast gate after any input moves: the server
     * switch, a receiver appearing or vanishing, or the lock changing.
     * The store's answer is what the service enforces (it ends a live
     * session the moment the lock lands); the UI's answer additionally
     * needs a receiver to exist at all.
     */
    private fun recomputeCastAllowed() {
        val allowed = castFeatureEnabled && !_ui.value.locked
        store.setCastAllowed(allowed)
        _ui.update { it.copy(castAvailable = castDevicesAvailable && allowed) }
    }

    private var controller: MediaController? = null
    private var tickerJob: Job? = null

    /**
     * Monotonic instant the current load's start clock armed
     * (`elapsedRealtime`, so a sleeping phone does not gift the deadline
     * free seconds). Salaar (2026-11-13): this is NOT stamped at Play press
     * — it arms in the ticker, on the first tick where the player is no
     * longer IDLE, i.e. the moment Media3 holds a URL and is loading an
     * address. The seconds between the tap and the load are not the load's:
     * a slow server resolve (measured 5.7–10.4 s cold) must not spend the
     * give-up budget before the player has anything to play. Null once a
     * first frame has rendered, so no stale attempt can fire.
     */
    private var startAttemptAtMs: Long? = null

    /**
     * Monotonic instant the current load was requested, for the one case
     * the start clock never arms: a load that never reaches Media3 at all.
     * The ticker backdates [startAttemptAtMs] to this instant once thirty
     * seconds have passed without the player leaving IDLE, so the give-up
     * still fires on the normal path instead of waiting forever.
     */
    private var loadRequestedAtMs: Long? = null

    /** True while the start clock is still waiting for Media3 to hold a URL. */
    private var startClockPending: Boolean = false

    /**
     * Link-report bookkeeping (Session 123): the handle a `played` report was
     * already sent for, and the attempt a `failed` report was already sent for.
     * One report per handle / per attempt — the ticker visits every 200 ms and
     * must never turn one playback into dozens of rows. Both reset naturally:
     * a new bundle carries a new handle, a new attempt a new timestamp.
     */
    private var playedReportHandle: String? = null
    private var failedReportAttemptAtMs: Long? = null

    private var keepJob: Job? = null
    private var settingsJob: Job? = null
    private var bingeResolveJob: Job? = null
    private var bingePrefetchJob: Job? = null

    /**
     * The operator's `feature_flags.downloads`, on until the board says otherwise. It decides
     * whether this player offers a keep at all — see [refreshKeepState] and [keepCurrent].
     */
    private var downloadsAllowed: Boolean = true

    /**
     * The viewer's own player defaults (plan §5 step 10, P36): autoplay-next, on until
     * the viewer turns it off (plan D4), and the standing default quality band ("auto"
     * = no default). [defaultQualitySet] tells a picked "Auto" apart from a never
     * picked one: only the second starts playback at 480p. Collected, not sampled,
     * so a change in Settings reaches a player that is already running before the
     * next episode ends.
     */
    private var autoplayNext: Boolean = true
    private var defaultQualityBand: String = LadderForScope.AUTO
    private var defaultQualitySet: Boolean = false

    private val sleepTimer = SleepTimer()

    /**
     * The binge policy plus the fire-once set it makes its caller own. Lives here because
     * the ViewModel is the only place that knows both the progress tick and the session
     * lifecycle; the planner itself never touches the player.
     */
    private val bingePlanner = BingePrefetchPlanner()

    /** The next episode's handle, once looked up; null means "not addressable yet". */
    private var nextEpisodeHandle: String? = null

    /**
     * Everything needed to open the successor without touching the catalog again: its
     * handle, rungs and the scope it belongs to. [resolved] flips when the 85% pass has
     * asked our redirector about the handle, which is what makes the open instant.
     */
    private data class PreparedNextEpisode(
        val season: Int,
        val episode: Int,
        val handle: String,
        val title: String,
        val backupHandles: List<String>,
        val rungs: List<LadderRung>,
        val startBand: String?,
        val auto: Boolean,
        val resolved: Boolean = false,
        /** PL-12: the successor's still, year and cast — the end card's "Up next" preview. */
        val posterUrl: String? = null,
        val year: Int? = null,
        val castNames: List<String> = emptyList(),
    )

    private var preparedNextEpisode: PreparedNextEpisode? = null

    /**
     * PL-12: publish the prepared successor's title, number and still into the state —
     * the end card's "Up next" preview and the countdown card read it from here — or
     * clear all four when nothing is prepared. Called wherever `preparedNextEpisode`
     * is assigned or cleared, so the state can never name a successor that is gone.
     */
    private fun publishNextEpisode() {
        val prepared = preparedNextEpisode
        _ui.update {
            it.copy(
                nextEpisodeTitle = prepared?.title,
                nextEpisodeSeason = prepared?.season,
                nextEpisodeEpisode = prepared?.episode,
                nextEpisodePosterUrl = prepared?.posterUrl,
            )
        }
    }

    /**
     * The autoplay-next countdown (Session 123, PL-1): the job ticking the ten
     * seconds between an episode's end and the advance, and the action it will
     * fire when it reaches zero. Cancelling the job is cancelling the advance —
     * the viewer chose to stay — so every path that opens something else
     * (a manual Next, a new bundle) clears both without firing.
     */
    private var autoplayCountdownJob: Job? = null
    private var pendingAutoplayAction: NextEpisodeAction.NextBundleRequest? = null

    /** In-run resume memory; cross-launch persistence is a data-agent request (handoff). */
    private val resumeByTarget = HashMap<String, Long>()

    /**
     * The key the current bundle was opened under — the stream-id handle (or the local file
     * address for an on-device print). Every read of [resumeByTarget] uses it, and
     * [seedResumeFromStoredPositions] resolves the work scope through it (`peekWorkRef` maps
     * handle → scope), so positions measured during playback must be written under it too.
     * Sweep #10: `rememberResume` used to key by `target.workKey` instead, orphaning every
     * pause/destroy position under a key no reader consults while the stale handle-keyed
     * seed kept offering "Resume from X".
     */
    private var activeResumeKey: String? = null

    /**
     * Whether the bundle now playing is a staged live channel. Set in
     * [beginBundle] from the store's per-handle flag — the Live TV tap
     * staged it — and cleared for every other bundle, so a movie opened
     * after a channel in the same session is VOD again. The ticker ORs
     * it with Media3's `isCurrentMediaItemLive`, which reads false for a
     * channel whose HLS playlist carries an end tag (Discover Pakistan's
     * 2:00:12 DVR window). A quality switch re-begins the bundle with the
     * same handle, so the flag survives it.
     */
    private var stagedLiveChannel = false

    /**
     * Whether this device has a sync key, mirrored so a twice-a-second tick can read a boolean
     * instead of suspending on a Flow. False is the ordinary case, and then every account call
     * below returns before it builds a request — the player is exactly what it was before the
     * optional account existed.
     */
    private var syncSignedIn: Boolean = false

    /**
     * Whether the server has confirmed paid VIP for this account. Mirrored rather than
     * sampled for the same reason as [syncSignedIn]: a break decision is made on a tap, not
     * in a suspending context. It is the ledger's answer and nothing else — a rewarded
     * device pass is a separate fact and is read where it is stored.
     */
    private var syncEntitled: Boolean = false

    /**
     * The operator's 1080p/4K switch, mirrored from remote config. On is the shipped default,
     * the fail-closed mirror of the server gate (seeded and live ON): until remote config
     * says otherwise, a free viewer is never offered a rung the server will 402.
     * [premiumAccess] is what the gate is actually handed at every site below.
     */
    private var qualityGateEnabled: Boolean = true
    private var freeQualityCap: String = "720p"
    /** Data Saver — on by default, so mobile-data titles start at 480p. */
    private var dataSaver: Boolean = true
    /** MX idea #3: the decoder mode in force, mirrored from the store so a tap reads the live value, not a stale echo. */
    private var decoderMode: DecoderMode = DecoderMode.HARDWARE
    /** Wi-Fi-only downloads — on by default; the Keep button honours it (Session 123). */
    private var wifiOnlyDownloads: Boolean = true
    /** Delete a watched download to free the space — off by default (user decision 04:42 PKT 20 Nov; Session 123: series episodes too). */
    private var autoDeleteWatched: Boolean = false
    /**
     * MX #7 — Kids Lock: the mode in force, mirrored from DataStore.
     * [kidsUnlockGranted] is the PIN's grant for THIS title only — a new
     * episode re-locks, and turning the mode off clears it.
     */
    private var kidsMode: Boolean = false
    private var kidsUnlockGranted: Boolean = false

    /** A confirmed member, or everyone while the operator has not turned gating on. */
    private val premiumAccess: Boolean get() = VipQualityGate.premiumAccess(qualityGateEnabled, syncEntitled)

    private val _ui = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    /** The service-side player, exposed read-only so `PlayerView` can attach to it. */
    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    /** Externally parsed subtitle (custom-render path). Null → native rendering only. */
    private var customSubtitleFile: OverlaySubtitleFile? = null
    /** True when the loaded overlay file came from the bundle (Gap A3), not from the viewer. */
    private var customSubtitleFromBundle: Boolean = false

    init {
        connectController()
        observeStore()
        observeSettings()
        observeSyncAccount()
    }

    /**
     * The two account facts this player cares about, plus the one device fact: is there a key,
     * what does the ledger say about it, and is a rewarded pass still running. Collected rather
     * than sampled, so signing in or out mid-session is felt by a player that is already
     * running, and so the tick loop never has to suspend to ask.
     */
    private fun observeSyncAccount() {
        viewModelScope.launch {
            syncAccountRepository.signedIn.collect { signIn -> syncSignedIn = signIn }
        }
        viewModelScope.launch {
            syncAccountRepository.entitlement.collect { earned ->
                syncEntitled = earned?.vip == true
                // A membership approved mid-playback should make its rungs selectable now,
                // not after the next episode.
                armQualityChoices()
            }
        }
        viewModelScope.launch {
            settingsRepository.qualityGateEnabled.collect { enabled ->
                qualityGateEnabled = enabled
                armQualityChoices()
            }
        }
        viewModelScope.launch {
            settingsRepository.freeQualityCap.collect { cap ->
                freeQualityCap = cap
                armQualityChoices()
            }
        }
        viewModelScope.launch {
            settingsRepository.dataSaver.collect { enabled ->
                dataSaver = enabled
                _ui.update { it.copy(dataSaver = enabled) }
                armQualityChoices()
            }
        }
        viewModelScope.launch {
            settingsRepository.autoPip.collect { enabled ->
                _ui.update { it.copy(autoPip = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.wifiOnlyDownloads.collect { enabled ->
                wifiOnlyDownloads = enabled
            }
        }
        viewModelScope.launch {
            settingsRepository.autoDeleteWatched.collect { enabled ->
                autoDeleteWatched = enabled
            }
        }
    }

    /**
     * The preferences the policy classes read. Collected, not sampled, so the settings
     * screen changing a value is felt by a player that is already running.
     */
    private fun observeSettings() {
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            settingsRepository.audioBoostLevel.collect { level ->
                _ui.update { it.copy(audioBoostLevel = AudioBoostUi.offeredLevel(level)) }
                applyPlayerVolume()
            }
        }
        // MX idea #8: the persisted equalizer state lands in the UI state
        // and is pushed to the store, so a mix made in an earlier session
        // is the mix the next episode starts with.
        viewModelScope.launch {
            settingsRepository.eqState.collect { eq ->
                _ui.update { it.copy(eqState = eq) }
                pushAudioEnhancement(AudioBoostUi.splitGain(_ui.value.audioBoostLevel, _ui.value.userVolume).dspGain)
            }
        }
        viewModelScope.launch {
            combine(
                settingsRepository.userRegion,
                settingsRepository.preferredAudioLanguage,
                settingsRepository.preferredSubtitleLanguage,
            ) { region, audio, subtitle ->
                Triple(
                    LanguageDefaults.effectiveAudioLanguage(audio, region),
                    LanguageDefaults.effectiveAudioFallback(region),
                    LanguageDefaults.effectiveSubtitleLanguage(subtitle, region),
                )
            }.collect { (audio, fallback, subtitle) ->
                _ui.update {
                    it.copy(
                        preferredAudioLanguage = audio,
                        preferredAudioFallback = fallback,
                        preferredSubtitleLanguage = subtitle,
                    )
                }
                applyPreferredAudioTrack()
                applyPreferredSubtitleTrack()
            }
        }
        // …plus the one display preference the player owns: Ghost mode or the full chrome.
        viewModelScope.launch {
            settingsRepository.viewModeChoice.collect { name ->
                _ui.update { it.copy(viewMode = ViewMode.fromName(name)) }
            }
        }
        // MX idea #3: the persisted decoder mode — the store is the
        // in-process truth the service builds its player from, so the
        // stored choice lands there (the service rebuilds if it already
        // built the other mode, before the first play).
        viewModelScope.launch {
            settingsRepository.decoderMode.collect { name ->
                store.setDecoderMode(DecoderMode.fromName(name))
            }
        }
        // The stored subtitle look. Nothing is written back: the sync offset belongs to this
        // viewing, so the live value rides through `withDelay` and a store answer — including
        // the echo of the viewer's own write — can never reset a delay they dialled in.
        viewModelScope.launch {
            settingsRepository.subtitleStyle.collect { style ->
                _ui.update { it.copy(subtitle = style.withDelay(it.subtitle.delayMs)) }
            }
        }
        // …and the operator's answer for that same preference. Nothing is written back: the
        // viewer's stored choice survives, and PlayerUiState.pureCinema is the one place that
        // refuses to honour it while `pure_cinema` is off, so the frame returns on its own.
        viewModelScope.launch {
            settingsRepository.pureCinemaFeatureEnabled.collect { allowed ->
                _ui.update { it.copy(pureCinemaAllowed = allowed) }
            }
        }
        // MX #9 — Cast: the server's chromecast switch, a receiver
        // appearing or vanishing on this network, and the session state
        // the service publishes. Collected, not sampled, so a receiver
        // appearing mid-play offers the button, and a switch flipped off
        // takes it away without a relaunch.
        viewModelScope.launch {
            settingsRepository.castFeatureEnabled.collect { enabled ->
                castFeatureEnabled = enabled
                recomputeCastAllowed()
            }
        }
        viewModelScope.launch {
            castAvailabilityTracker.devicesAvailable.collect { available ->
                castDevicesAvailable = available
                recomputeCastAllowed()
            }
        }
        viewModelScope.launch {
            store.castIsCasting.collect { casting ->
                _ui.update { it.copy(isCasting = casting) }
            }
        }
        viewModelScope.launch {
            store.castDeviceName.collect { name ->
                _ui.update { it.copy(castDeviceName = name) }
            }
        }
        // The board's downloads switch, which decides whether the keep chip is offered at all.
        // Re-asking the keep state is what makes a refresh take the chip away mid-play instead
        // of leaving a button that can no longer write a file.
        viewModelScope.launch {
            settingsRepository.downloadsFeatureEnabled.collect { allowed ->
                downloadsAllowed = allowed
                refreshKeepState()
            }
        }
        // The viewer's own player defaults (plan §5 step 10): the autoplay-next switch
        // gates the auto-advance, and the default band is the rung a new title starts
        // on when Play was Auto. Collected, not sampled, so a change in Settings reaches
        // a player that is already running.
        viewModelScope.launch {
            settingsRepository.autoplayNext.collect { enabled -> autoplayNext = enabled }
        }
        viewModelScope.launch {
            settingsRepository.defaultQuality.collect { band -> defaultQualityBand = band }
        }
        viewModelScope.launch {
            settingsRepository.defaultQualitySet.collect { set -> defaultQualitySet = set }
        }
        // The player chrome theme this device wears: collected, not sampled, so
        // a change on the VIP screen reaches a player that is already running.
        viewModelScope.launch {
            settingsRepository.playerTheme.collect { theme ->
                _ui.update { it.copy(playerTheme = theme) }
            }
        }
        // MX #7 — Kids Lock: the mode, collected not sampled, so a parent
        // turning it on reaches a player that is already running — the next
        // episode locks, and the state flag tells the screen the unlock
        // needs the PIN. Turning it off clears the grant with the mode.
        // Turning it on also locks a bundle already playing: the DataStore
        // read can land after the first bundle, and a player opened in
        // kids mode must never start unlocked on that race.
        viewModelScope.launch {
            settingsRepository.kidsModeActive.collect { active ->
                kidsMode = active
                if (!active) kidsUnlockGranted = false
                _ui.update { it.copy(kidsMode = active) }
                if (active && store.bundle.value != null) {
                    kidsUnlockGranted = false
                    _ui.update { it.copy(locked = true) }
                    recomputeCastAllowed()
                }
            }
        }
    }

    // ── Service connection ───────────────────────────────────────────────────────

    private fun connectController() {
        val token = SessionToken(appContext, ComponentName(appContext, com.raddstream.app.player.PlaybackService::class.java))
        // MediaController.Builder in 1.3.1 has only buildAsync() (verified in source).
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                _player.value = c
                // The service is a process singleton and may still hold a previous screen's
                // sync; a fresh ViewModel starts at 0, so publish that before playback starts.
                store.subtitleOffsetMs = _ui.value.subtitle.delayMs
                c.addListener(playerListener)
                // P-1: the service is a process singleton and never resets
                // speed, so a fresh ViewModel can inherit a previous
                // screen's 2x from the service player. A new player session
                // always starts at 1.0 (the ui default too) — the speed
                // sheet never shows a speed the player is not using.
                if (c.playbackParameters.speed != 1f) c.playbackParameters = PlaybackParameters(1f)
                _ui.update { it.copy(connected = true) }
                startTicker()
            } catch (t: Throwable) {
                // Dev-cleanup (Nov 2026, E1): the card shows one fixed sentence — the
                // exception's own words are logcat-only, never viewer copy.
                Log.e("PlayerViewModel", "MediaController bind failed", t)
                _ui.update {
                    it.copy(
                        connectionError = PLAYER_SERVICE_UNAVAILABLE_MESSAGE,
                        // PL-3: a bind failure with no network is the offline card, not the
                        // generic service error — the viewer can act on the first, not the second.
                        isOffline = connectivityProbe.current() == NetworkClass.NONE,
                    )
                }
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // PL-3: a first frame playing is the proof the network is back — the
            // offline card clears itself, no retry tap needed.
            _ui.update { it.copy(isPlaying = isPlaying, isOffline = if (isPlaying) false else it.isOffline) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // PL-10: the video's own aspect ratio, for the PiP params. A zero height
            // is a size that says nothing — keep the previous ratio, never a NaN.
            if (videoSize.height > 0) {
                _ui.update { it.copy(videoAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _ui.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    isEnded = playbackState == Player.STATE_ENDED,
                    // The ended successor offer dies the moment playback leaves the ended
                    // state — a replay or a new title must not inherit the last episode's
                    // Next Episode button.
                    endedHasNextEpisode = if (playbackState == Player.STATE_ENDED) it.endedHasNextEpisode else false,
                )
            }
            if (playbackState == Player.STATE_ENDED) {
                rememberResume()
                handleSleepEndOfVideo()
                maybeAutoDeleteWatchedEpisode()
                onEpisodeEndedSmartDownloads()
                loadPostPlayPreviews()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            _ui.update { state ->
                state.copy(
                    audioTracks = TrackMapper.options(tracks, C.TRACK_TYPE_AUDIO),
                    subtitleTracks = TrackMapper.options(tracks, C.TRACK_TYPE_TEXT),
                    frameRate = TrackMapper.selectedVideoFrameRate(tracks) ?: state.frameRate,
                )
            }
            // Tracks only become decidable now: the media item is prepared, so the audio
            // language the user set in Settings can be applied to the list the player just
            // reported. Also the first chance a fresh playback gets — a preference set before
            // the stream existed could not be honoured anywhere else. The subtitle row applies
            // at the same moment, for the same reason, through its own fire-once gate.
            applyPreferredAudioTrack()
            applyPreferredSubtitleTrack()
            applyBundleCustomSubtitle()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _ui.update {
                it.copy(
                    speed = playbackParameters.speed,
                    // While boosting, the boost value is "the" speed; keep the flag honest.
                    boosting = it.boosting && playbackParameters.speed != it.speed,
                )
            }
        }
    }

    private fun observeStore() {
        viewModelScope.launch {
            store.bundle.collect { b ->
                // A new bundle is a new episode: any countdown belonging to the
                // ended one is dropped, never carried across.
                clearAutoplayCountdown()
                _ui.update { it.copy(bundle = b, activeRendition = store.activeRendition(), endedHasNextEpisode = false, activeSkipSegment = null, postPlayPreviews = emptyList()) }
                // REA #3: a new bundle is a new title — its saved sync
                // offsets land here, before the first frame the viewer
                // judges. A rung switch is the same bundle, so the guard
                // inside applySavedSyncPrefs keeps it from re-reading.
                applySavedSyncPrefs(b?.target?.workKey?.takeIf { it.isNotBlank() })
                armQualityChoices()
                // A rung Auto moved to is the SAME episode in a different address, so the
                // binge policy must not be re-armed by it; only a new file or a new episode
                // is a new lifecycle. The cache session does change with the address.
                refreshKeepState()
                val key = bingeBundleKey(b)
                if (key != watchedBingeKey) {
                    watchedBingeKey = key
                    bingePlanner.reset()
                    nextEpisodeHandle = null
                    preparedNextEpisode = null
                    publishNextEpisode()
                    bingeResolveJob?.cancel()
                    bingePrefetchJob?.cancel()
                    if (b != null) prepareBingePrefetch(b)
                    // MX #7 — Kids Lock: a new episode starts locked — the
                    // kid watches, the controls stay put until a parent
                    // unlocks them with the PIN. A rung switch is not a new
                    // episode (same key), so it never re-locks mid-play.
                    if (b != null && kidsMode) {
                        kidsUnlockGranted = false
                        _ui.update { it.copy(locked = true) }
                        recomputeCastAllowed()
                    }
                }
            }
        }
        viewModelScope.launch {
            store.activeRenditionIndex.collect {
                _ui.update { it.copy(activeRendition = store.activeRendition()) }
                armQualityChoices()
                refreshKeepState()
            }
        }
        viewModelScope.launch {
            store.autoBand.collect { band -> _ui.update { it.copy(autoQualityBand = band) } }
        }
        viewModelScope.launch {
            // MX idea #3: the store is the in-process truth for the decoder
            // mode — a tap, a persisted load, or a failover all land here —
            // so the chip reads it, never a second copy.
            store.decoderMode.collect { mode ->
                decoderMode = mode
                _ui.update { it.copy(decoderMode = mode) }
            }
        }
        viewModelScope.launch {
            store.failoverLog.collect { log -> _ui.update { it.copy(failoverLog = log) } }
        }
        viewModelScope.launch {
            store.settledBanner.collect { banner -> _ui.update { it.copy(settledBanner = banner) } }
        }
        viewModelScope.launch {
            store.giveUp.collect { pair -> _ui.update { it.copy(giveUp = pair) } }
        }
        viewModelScope.launch {
            store.nextEpisode.collect { action ->
                if (action != null) handleCrossEpisodeAction(action)
            }
        }
    }

    private var watchedBingeKey: String? = null

    /** The last A-B loop-back, on the monotonic clock: the re-arm guard in the policy. */
    private var lastAbLoopAtMs: Long? = null

    /** Which file the staged A-B segment belongs to (target + title); a new file clears it. */
    private var abRepeatScopeKey: String? = null

    private fun startTicker() {
        tickerJob?.cancel()
        var lastSleepTick = System.currentTimeMillis()
        tickerJob = viewModelScope.launch {
            while (true) {
                val c = controller
                if (c != null && c.isConnected) {
                    val now = System.currentTimeMillis()
                    val wasStarting = _ui.value.startPhase != StartPhase.NONE
                    // The live flag is the staged channel flag OR Media3's
                    // own read: a channel whose playlist carries an end tag
                    // reports false to Media3 and a DVR window as its
                    // duration (Discover Pakistan: 2:00:12). Live never
                    // adopts that window as a duration — the UI gates the
                    // seek bar and the time row on isLive, and a stale VOD
                    // duration must not ride along if the flag flips.
                    val live = stagedLiveChannel || c.isCurrentMediaItemLive
                    _ui.update {
                        val position = c.currentPosition
                        val duration = if (live) it.durationMs
                            else if (c.duration > 0) c.duration else it.durationMs
                        it.copy(
                            positionMs = position,
                            bufferedMs = bufferedOf(c),
                            durationMs = duration,
                            // F29 (Batch B): the live flag rides the same tick
                            // as the position — a live window stays live for
                            // the whole stream, and a VOD never flips to it.
                            isLive = live,
                            // P-3: the notice card's elapsed line counts from
                            // the request (Play press), so the resolve wait is
                            // counted too — published on every tick, whatever
                            // the start clock (which arms only once Media3
                            // holds a URL) says. Zero until a load is
                            // requested, and zero again after a first frame.
                            startRequestElapsedMs = loadRequestedAtMs?.let { requestedAt ->
                                (SystemClock.elapsedRealtime() - requestedAt).coerceAtLeast(0)
                            } ?: 0,
                            // MX #4: the Skip button follows the playhead —
                            // the segment whose window holds this position,
                            // and only when a jump target is known (an
                            // open-ended window in a file of unknown
                            // duration has nowhere to jump to, so the
                            // button stays hidden instead of seeking
                            // nowhere). The ticker visits every 200 ms,
                            // and every window is seconds long, so no
                            // window is ever missed between ticks.
                            activeSkipSegment = activeSegment(
                                it.bundle?.segments.orEmpty(), position,
                            )?.takeIf { skipTargetMs(it, duration) != null },
                        )
                    }
                    // A-B repeat (§5 P14): the ticker already visits every 200 ms, so
                    // it is the clock — when the playhead reaches B, seek back to A.
                    // The re-arm window inside the policy keeps a seek still in flight
                    // from being re-issued on every visit.
                    val loopTo = loopTargetMs(
                        _ui.value.positionMs, _ui.value.abRepeat, lastAbLoopAtMs,
                        SystemClock.elapsedRealtime(),
                    )
                    if (loopTo != null) {
                        lastAbLoopAtMs = SystemClock.elapsedRealtime()
                        seekTo(loopTo)
                    }
                    // The start deadline (§5 P5): the ticker already visits every 200 ms, so
                    // it is the clock — no second thread. A first frame disarms the attempt
                    // and records how long the start took, but only when the player really
                    // was starting: a quality switch mid-scene must not overwrite the
                    // route-open measurement with a near-zero one. The frame test is
                    // READY only, never a known duration: a stalled copy can know its
                    // duration from the playlist or file header while the picture is
                    // still black, and the old `isPlaying || duration > 0` test disarmed
                    // the deadline on that alone — the Pushpa 2 retest buffered forever
                    // with no error and no give-up card.
                    // Salaar (2026-11-13): the start clock arms here, when
                    // Media3 has a URL — not at Play press. The first
                    // connected tick where the player is no longer IDLE is
                    // the first moment it is loading an address; before
                    // that the service is still starting and the redirector
                    // has not been handed a media item, so the seconds
                    // between the tap and the load are not the load's. A
                    // slow server resolve (measured 5.7–10.4 s cold,
                    // ceiling ~20 s) must not spend the give-up budget
                    // before the player has anything to play. If the load
                    // never reaches Media3 at all, the clock backdates to
                    // the request once thirty seconds have passed, so the
                    // give-up still fires on the normal path instead of
                    // waiting forever.
                    if (startClockPending) {
                        if (c.playbackState != Player.STATE_IDLE) {
                            startAttemptAtMs = SystemClock.elapsedRealtime()
                            startClockPending = false
                        } else {
                            // A mutable property cannot be smart-cast, so
                            // the request instant is read once, locally.
                            val requestedAt = loadRequestedAtMs
                            if (requestedAt != null &&
                                SystemClock.elapsedRealtime() - requestedAt >=
                                StartDeadline.GIVE_UP_AFTER_MS
                            ) {
                                startAttemptAtMs = requestedAt
                                startClockPending = false
                            }
                        }
                    }
                    val attemptAt = startAttemptAtMs
                    if (attemptAt != null) {
                        val elapsed = SystemClock.elapsedRealtime() - attemptAt
                        val firstFrame = StartDeadline.hasFirstFrame(c.playbackState)
                        if (firstFrame) {
                            startAttemptAtMs = null
                            // P-3: the request instant is spent — a stale one
                            // would keep the notice's elapsed line growing
                            // after playback starts, and the backdate fallback
                            // only reads it while the clock is still pending.
                            loadRequestedAtMs = null
                            _ui.update {
                                it.copy(
                                    startElapsedMs = 0,
                                    startRequestElapsedMs = 0,
                                    lastFirstFrameMs = if (wasStarting) elapsed else it.lastFirstFrameMs,
                                    deadlineGiveUp = false,
                                )
                            }
                            // The link played: one `played` report per handle (Session
                            // 123). The first frame is the proof — a stalled copy can
                            // know its duration from the header while the picture is
                            // still black, so duration alone never counts. Best-effort
                            // and silent; a failed report is dropped, never shown.
                            reportLinkPlayedOnce(elapsed)
                        } else {
                            _ui.update {
                                it.copy(
                                    startElapsedMs = elapsed,
                                    deadlineGiveUp = elapsed >= StartDeadline.GIVE_UP_AFTER_MS,
                                )
                            }
                            // The link never produced a first frame before the
                            // deadline: one `failed` report per attempt (Session 123).
                            // This is the dead-link signal the server's link-health
                            // axis is built on — the give-up card the viewer sees is
                            // the same event, reported once, at the same moment.
                            if (elapsed >= StartDeadline.GIVE_UP_AFTER_MS &&
                                failedReportAttemptAtMs != attemptAt
                            ) {
                                failedReportAttemptAtMs = attemptAt
                                reportLinkFailedOnce(elapsed)
                            }
                        }
                    }
                    // H4 R7: a rendition abandoned mid-playback (after the first frame). The
                    // service recorded it in the store slot when the failover machine hopped
                    // or handed off; consume it once here and report it — best-effort and
                    // silent, like every other link report — so the server banks the dead
                    // winner and the next press does not re-serve it. This sits outside the
                    // start-attempt block above on purpose: after the first frame lands,
                    // `startAttemptAtMs` is null and that block never runs again.
                    store.midPlaybackFailure.value?.let { failure ->
                        store.clearMidPlaybackFailure()
                        viewModelScope.launch {
                            catalogRepository.reportLink(
                                handle = failure.handle,
                                event = "failed",
                                error = failure.summary,
                                failureClass = failure.failureClass,
                            )
                        }
                    }
                    // Sleep timer advance + fade (player volume = user volume × fade factor)
                    if (_ui.value.sleep.mode == SleepMode.COUNTDOWN) {
                        val tick = sleepTimer.onTick(now - lastSleepTick)
                        applySleep(tick)
                    }
                    tickBingePreload(_ui.value.positionMs, _ui.value.durationMs)
                    // The playhead is NOT reported from here. This ticker only runs while the player
                    // screen is composed, so pumping the cache from it stops the moment the viewer
                    // backgrounds the app — and at this clock it would ask the engine for a task
                    // lookup five times a second. PlaybackService owns the feed: it lives as long as
                    // the audio does, and it ticks the cache on the cache's own cadence.
                    // The account's own heartbeat: one report per fifteen seconds at most, and
                    // the gate is a boolean read, so this tick costs nothing when it is not due.
                    if (c.isPlaying) reportResume(c, c.currentPosition, forced = false)
                    lastSleepTick = now
                }
                delay(200)
            }
        }
    }

    private fun bufferedOf(c: MediaController): Long =
        if (c.bufferedPosition > 0) c.bufferedPosition else c.currentPosition

    // ── Link reports (Session 123) ────────────────────────────────────────────

    /** The handle this playback is streaming through, or null for local files. */
    private fun currentReportHandle(): String? =
        store.activeRendition()?.playback?.streamId?.takeIf { it.isNotBlank() }

    /**
     * One `played` report per handle: the first frame is the proof the link
     * works. Runs in the background and never touches the UI — a failed report
     * is dropped by the repository, never shown and never retried.
     */
    private fun reportLinkPlayedOnce(firstFrameMs: Long) {
        val handle = currentReportHandle() ?: return
        if (playedReportHandle == handle) return
        playedReportHandle = handle
        viewModelScope.launch {
            catalogRepository.reportLink(
                handle = handle,
                event = "played",
                latencyMs = firstFrameMs.toInt().coerceAtLeast(0),
            )
        }
    }

    /**
     * One `failed` report per attempt: the give-up deadline passed with no
     * first frame. Same best-effort rule as [reportLinkPlayedOnce].
     */
    private fun reportLinkFailedOnce(elapsedMs: Long) {
        val handle = currentReportHandle() ?: return
        // Session 155 (S2): the give-up report carries the exact Media3
        // start error when the service recorded one, so the server names
        // why the file never produced a frame. Without it the report only
        // says the start timed out, which is the symptom, not the cause.
        val startError = store.lastStartError.value?.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            catalogRepository.reportLink(
                handle = handle,
                event = "failed",
                latencyMs = elapsedMs.toInt().coerceAtLeast(0),
                error = startError ?: "Playback did not start",
            )
        }
    }

    // ── Play entry points ────────────────────────────────────────────────────────

    /**
     * Route path `player/{streamId}/{title}`: one opaque handle of ours. The address the
     * player loads is our own redirector for that handle, built here — never a URL that
     * came from a scraper, and never one that names where the file lives.
     */
    fun startStreamHandle(streamId: String, title: String, startOverrideMs: Long? = null) {
        viewModelScope.launch {
            // Session 126 (audit bug d): re-seed BEFORE the early-return guard — the
            // position may have moved on another device since this handle was opened,
            // and the old code returned without ever asking. The seed only writes
            // when the fresh row is strictly later (see ResumeSeed.newerSeed).
            // A hung resume lookup must never hold back Play: the seed is a
            // nicety, and five seconds without an answer means playing from
            // the start instead of waiting on a row.
            withTimeoutOrNull(5_000) { seedResumeFromStoredPositions(streamId, startOverrideMs) }
            if (_ui.value.bundle?.renditions?.firstOrNull()?.playback?.streamId == streamId) return@launch
            // A pasted direct link, staged under its opaque key by the search
            // screen: no redirector, no server round-trip, no resume history —
            // the address itself is the whole ladder, one rung.
            store.peekDirectUrl(streamId)?.let { directUrl ->
                beginBundle(
                    directPlaybackBundle(streamId, directUrl),
                    title,
                    resumeKey = streamId,
                    overrideMs = startOverrideMs,
                )
                return@launch
            }
            val playUrl = withVipPass(
                streamRedirectUrl(apiProvider.currentBaseUrl(), streamId),
                // Session 123: the VIP pass rides the URL because the player
                // follows the 302 outside this process — a header cannot ride
                // along, and the sync key must never be handed to a file host
                // on a redirect. A guest or free account sends no pass.
                syncAccountRepository.currentVipPass(),
            )
            // Runner-ups of this rung, staged by the detail screen: handles only, so a
            // 403 mid-play costs one silent hop instead of a dialog and a quality drop.
            // The manifest MIME, staged by the Live TV screen for a channel: without
            // it a bare handle classifies as progressive and an HLS playlist meets
            // the progressive reader (Robo #4). A handle with no entry — a movie,
            // a deep link — passes null and builds exactly as before.
            val bundle = PlaybackBundle.forStreamHandle(streamId = streamId, playUrl = playUrl,
                                                       title = title,
                                                       mimeType = store.peekManifestMime(streamId),
                                                       backupHandles = store.peekFailover(streamId))
                .withStagedTarget(streamId)
                .withStagedCoverage(streamId)
                .withStagedTracks(streamId)
                .withStagedSegments(streamId)
            beginBundle(bundle, title, resumeKey = streamId, overrideMs = startOverrideMs)
        }
    }

    /**
     * The route carries one opaque handle, but the detail screen staged which work and which
     * episode that handle is. Stamping that onto the bundle is what lets the service decide
     * that an END has a successor at all, and what gives binge pre-caching a post id to look
     * the successor's ladder up by. A handle with no staged scope (a deep link) keeps the
     * route bundle's empty target, so it behaves exactly as before.
     */
    private fun PlaybackBundle.withStagedTarget(streamId: String): PlaybackBundle {
        val scope = store.peekWorkRef(streamId) ?: return this
        if (scope.postId <= 0) return this
        return copy(
            target = PlayTarget(
                kind = if (scope.episode > 0) "episode" else "work",
                id = scope.postId,
                workKey = scope.workRef.takeIf { it.isNotBlank() },
                workType = scope.workType,
                season = scope.season,
                episode = scope.episode,
            ),
        )
    }

    /**
     * The coverage the detail screen staged for this handle (plan §5 step 13): the
     * print's episode boundaries and chapter titles, from the v2 bundle. A handle
     * launched the old way has none staged, so the rendition keeps the default empty
     * coverage — the scrubber draws no ticks, exactly as before.
     */
    private fun PlaybackBundle.withStagedCoverage(streamId: String): PlaybackBundle {
        val coverage = store.peekCoverage(streamId) ?: return this
        val first = renditions.firstOrNull() ?: return this
        return copy(renditions = listOf(first.copy(coverage = coverage)) + renditions.drop(1))
    }

    /**
     * The subtitle tracks the detail screen staged for this handle (Gap A3).
     * A handle launched the old way has none staged, so the rendition keeps
     * the default empty tracks — the subtitle sheet simply offers nothing
     * extra, exactly as before.
     */
    private fun PlaybackBundle.withStagedTracks(streamId: String): PlaybackBundle {
        val tracks = store.peekTracks(streamId)
        if (tracks.isEmpty()) return this
        val first = renditions.firstOrNull() ?: return this
        return copy(renditions = listOf(first.copy(tracks = TracksInfo(subtitles = tracks))) + renditions.drop(1))
    }

    /**
     * The Skip Intro / Skip Recap windows (MX #4) the detail screen staged
     * for this handle, stamped onto the bundle the same way its coverage
     * is. A handle launched the old way has none staged, so the bundle
     * keeps the default empty list — no button, exactly as before.
     */
    private fun PlaybackBundle.withStagedSegments(streamId: String): PlaybackBundle {
        val segments = store.peekSegments(streamId)
        if (segments.isEmpty()) return this
        return copy(segments = segments)
    }

    /**
     * Route path `player_local/{uri}/{title}`: a finished public download. The bytes are on
     * this device, so no redirector and no upstream origin is involved — the bundle carries
     * the local content/file URI and every other control surface behaves exactly as usual.
     */
    fun startLocalUri(uri: String, title: String) {
        val clean = uri.trim()
        if (clean.isEmpty()) return
        if (_ui.value.bundle?.renditions?.firstOrNull()?.playback?.playUrl == clean) return
        val extension = Uri.parse(clean).lastPathSegment?.substringAfterLast('.')?.lowercase()
        val mime = extension?.let {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
        }
        val bundle = PlaybackBundle(
            session = SessionInfo(id = "local-${clean.hashCode().toString(16)}"),
            target = PlayTarget(kind = "local", id = 0),
            renditions = listOf(
                PlaybackRendition(
                    rank = 1,
                    print = PrintInfo(label = "Downloaded"),
                    playback = PlaybackInfo(
                        playUrl = clean,
                        streamId = "local",
                        label = "Saved to device",
                        protocol = "progressive",
                        mimeType = mime,
                        resumable = true,
                        kind = "direct",
                        playabilityWire = "stream",
                    ),
                ),
            ),
        )
        beginBundle(bundle, title, resumeKey = clean)
    }

    /**
     * One rung, no server: a pasted .mp4/.m3u8 address staged under its opaque
     * key. The shape mirrors [startLocalUri] — the player already knows how to
     * play a bundle whose only rendition is a direct file — with two honest
     * differences: `resumable` is false, because the address is unprobed and
     * the player must not claim a Range resume it never measured; and the
     * protocol and mime come from the address itself, so Media3 picks the
     * right reader for a playlist versus a single file.
     */
    private fun directPlaybackBundle(key: String, url: String): PlaybackBundle {
        val isHls = directVideoIsHls(url)
        return PlaybackBundle(
            session = SessionInfo(id = key),
            target = PlayTarget(kind = "direct", id = 0),
            renditions = listOf(
                PlaybackRendition(
                    rank = 1,
                    print = PrintInfo(label = "Direct link"),
                    playback = PlaybackInfo(
                        playUrl = url,
                        streamId = key,
                        label = directVideoTitle(url),
                        protocol = if (isHls) "hls" else "progressive",
                        mimeType = directVideoMime(url),
                        resumable = false,
                        kind = "direct",
                        playabilityWire = "stream",
                    ),
                ),
            ),
        )
    }

    /**
     * The stored position for the scope this handle is about to play, in the one place this
     * player already looks. [ResumeSeed] owns the precedence; what lives here is the two reads
     * and the write into [resumeByTarget] — the same map a position on THIS device writes.
     *
     * Nothing is re-ordered and nothing blocks the play button on it: both lookups are local
     * Room reads that only run when this process has no live position to offer, so a slow
     * account row cannot hold back playback, and a playback the detail screen could not name
     * has nothing to look up at all.
     */
    private suspend fun seedResumeFromStoredPositions(resumeKey: String, explicitStartMs: Long?) {
        if (explicitStartMs != null) return
        val existing = resumeByTarget[resumeKey]
        val scope = store.peekWorkRef(resumeKey)
        if (scope == null) {
            // A local file has no work ref for Room or the sync account to hang a
            // position on — its resume lives in the local store instead, and only
            // a device address may read it (a staged direct-URL key never does).
            if (!isLocalResumeUri(resumeKey)) return
            val localMs = localResumeRepository.positionFor(resumeKey) ?: return
            val seed = if (existing == null) {
                ResumeSeed.seedFor(explicitStartMs, existing, null, localMs)
            } else {
                // Session 126 (audit bug d): the position moved elsewhere — take the
                // newer row, never move the seed backwards.
                ResumeSeed.newerSeed(existing, null, localMs)
            } ?: return
            // Re-checked: write only while the map still holds the value this decision
            // was based on — a position measured while this lookup was running outranks it.
            if (resumeByTarget[resumeKey] == existing) resumeByTarget[resumeKey] = seed
            return
        }
        val syncedMs = if (syncSignedIn) {
            syncAccountRepository.resumePositionMs(scope.workRef, scope.season, scope.episode)
        } else {
            null
        }
        val localMs = watchHistoryDao.findScope(scope.workRef, scope.season, scope.episode)
            ?.positionMs
        val seed = if (existing == null) {
            ResumeSeed.seedFor(explicitStartMs, existing, syncedMs, localMs)
        } else {
            ResumeSeed.newerSeed(existing, syncedMs, localMs)
        } ?: return
        // Re-checked: a position measured while these lookups were running outranks either row.
        if (resumeByTarget[resumeKey] == existing) resumeByTarget[resumeKey] = seed
    }

    // ── The visible quality switch (Auto + one row per rung of this scope) ─────────

    /**
     * The ladder of the scope being watched. Held here rather than re-read from the store on
     * every tap because Auto moves playback onto a rung whose handle the detail screen never
     * staged a ladder under — the plan belongs to the scope, not to the launched address.
     */
    private var ladderPlan: PlayerSessionStore.AutoLadderPlan? = null

    /** The rung the server called best-and-playable, remembered so "Auto" can return to it. */
    private var autoRungHandle: String? = null

    /** Read the staged ladder for the address now playing, if this scope has one. */
    private fun armQualityChoices() {
        val handle = _ui.value.activeRendition?.playback?.streamId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return
        val staged = store.peekAutoLadder(handle) ?: return
        // The detail screen already gates what it stages, but a deep link or a membership
        // that lapsed mid-session could still hand us a premium rung. The player applies the
        // same rule at the point the switch is built, so no premium choice is ever offered.
        val rungs = staged.rungs.filter { VipQualityGate.allowsBand(it.band, premiumAccess, freeQualityCap) }
        if (rungs.isEmpty()) return
        // The standing default band (plan §5 step 10): an explicit tap outranks it, it
        // outranks Auto's pick, and a band missing from this ladder is ignored.
        val (startBand, auto) = resolveStartBand(
            stagedStartBand = staged.startBand,
            stagedAuto = staged.auto,
            defaultBand = defaultQualityBand,
            rungBands = rungs.map { it.band },
            defaultBandSet = defaultQualitySet,
        )
        // Data Saver caps the start on mobile data — 480p when the ladder has it —
        // and the auto pointer with it, so Auto cannot quietly start above the cap.
        // The rungs themselves are untouched: the viewer can still switch up.
        val onWifi = connectivityProbe.current() == NetworkClass.WIFI
        val cappedStartBand = DataSaver.capBand(startBand, rungs.map { it.band }, dataSaver, onWifi)
        val cappedAutoBand = staged.autoBand
            ?.takeIf { band -> rungs.any { it.band == band } }
            ?.let { DataSaver.capBand(it, rungs.map { it.band }, dataSaver, onWifi) }
        val plan = staged.copy(
            rungs = rungs,
            startBand = cappedStartBand,
            auto = auto,
            autoBand = cappedAutoBand,
        )
        ladderPlan = plan
        if (plan.auto) autoRungHandle = staged.startBand?.let { band ->
            // The same cap as the start band above: Auto's first rung is the
            // capped one on mobile data, not the staged one above it.
            val capped = DataSaver.capBand(band, rungs.map { it.band }, dataSaver, onWifi)
            plan.rungs.firstOrNull { it.band == capped }?.streamId
        } ?: handle
        _ui.update {
            it.copy(
                qualityRungs = plan.rungs,
                qualityBand = plan.startBand ?: it.qualityBand,
                qualityAuto = plan.auto,
            )
        }
    }

    /**
     * Choose a rung, or give the choice back to Auto ([band] = `auto`).
     *
     * The position is carried over, which is the whole point of a quality switch: a viewer
     * who asks for Full HD mid-scene must not be sent back to the beginning. Switching to a
     * rung means starting playback on that rung's own handle — the address is still only ever
     * ours, and the ladder plus that rung's runner-ups are re-staged under it first so a CDN
     * that refuses still hops silently instead of showing an error.
     */
    fun selectQualityBand(band: String?) {
        val plan = ladderPlan ?: return
        val wantedAuto = band.isNullOrBlank() || band.equals(LadderForScope.AUTO, ignoreCase = true)
        val targetBand = if (wantedAuto) {
            autoRungHandle?.let { handle -> plan.rungs.firstOrNull { it.streamId == handle }?.band }
                ?: plan.startBand
        } else {
            band
        }
        val rung = plan.rungs.firstOrNull { it.band == targetBand }
        if (rung == null) {
            // Audit bug (b): the old code returned silently, so a tap on a band the
            // scope does not offer did nothing at all — no switch, no message. The
            // viewer is told, in the same snackbar every other player message uses.
            viewModelScope.launch {
                _events.emit(PlayerEvent.Message("That quality is not offered for this title"))
            }
            return
        }
        if (!VipQualityGate.allowsBand(rung.band, premiumAccess, freeQualityCap)) {
            viewModelScope.launch {
                _events.emit(PlayerEvent.Message(VipQualityGate.lockedMessage(freeQualityCap)))
            }
            return
        }
        val active = _ui.value.activeRendition
        val auto = wantedAuto
        store.autoQuality.configure(plan.rungs, rung.band, auto)
        store.setAutoBand(if (auto) store.autoQuality.currentBand else null)
        _ui.update { it.copy(qualitySheetVisible = false, qualityBand = rung.band, qualityAuto = auto) }
        if (rung.streamId == active?.playback?.streamId) return

        val title = _ui.value.pendingTitle ?: active?.displayLabel() ?: Brand.name
        val positionMs = controller?.takeIf { it.isConnected }?.currentPosition ?: 0L
        ladderPlan = PlayerSessionStore.AutoLadderPlan(plan.rungs, rung.band, auto)
        store.stageFailover(rung.streamId, rung.backupHandles)
        store.stageAutoLadder(rung.streamId, plan.rungs, rung.band, auto)
        // The work did not change, only its address: carry the staged handle over, so a
        // quality switch (or a silent hop onto this rung's runner-up) keeps reporting resume
        // points against the same scope the detail screen named.
        store.peekWorkRef(active?.playback?.streamId)?.let { scope ->
            store.stageWorkRef(rung.streamId, scope)
            rung.backupHandles.forEach { store.stageWorkRef(it, scope) }
        }
        // D-35: carry the display metadata with the scope, so the loading state after a
        // quality switch still shows the poster instead of a black void.
        store.peekTitleMeta(active?.playback?.streamId)?.let { meta ->
            store.stageTitleMeta(rung.streamId, meta)
            rung.backupHandles.forEach { store.stageTitleMeta(it, meta) }
        }
        startStreamHandle(rung.streamId, title, startOverrideMs = positionMs.takeIf { it > 1_000 })
    }

    fun showQualitySheet() {
        if (_ui.value.qualityRungs.isEmpty()) return
        _ui.update { it.copy(qualitySheetVisible = true) }
    }

    fun hideQualitySheet() {
        _ui.update { it.copy(qualitySheetVisible = false) }
    }

    /** The Quality sheet's Data Saver row (Session 126): one tap, persisted, felt at once. */
    fun toggleDataSaver() {
        viewModelScope.launch { settingsRepository.saveDataSaver(!dataSaver) }
    }

    /**
     * MX idea #3: one tap on the top-bar HW/SW chip. The store is the
     * in-process truth — the service rebuilds the player in the new mode
     * at once, keeping the position — and the choice is persisted for the
     * next play. The mirror is written synchronously so a double-tap
     * reads the live value, not the collection's echo.
     */
    fun toggleDecoderMode() {
        val next = decoderMode.other()
        decoderMode = next
        store.setDecoderMode(next)
        viewModelScope.launch { settingsRepository.saveDecoderMode(next.name) }
    }

    /**
     * SC.24: share a moment — the title, the timestamp, and the app's own deep link,
     * handed to the share sheet by the screen. A local file has no stream handle to
     * link to, so there is nothing to share: the row is hidden for local bundles and
     * this guard is the second door.
     */
    fun shareMoment() {
        val streamId = _ui.value.activeRendition?.playback?.streamId?.trim()?.takeIf { it.isNotBlank() }
            ?: return
        if (streamId == "local") return
        val title = _ui.value.pendingTitle ?: _ui.value.activeRendition?.displayLabel() ?: Brand.name
        val text = ShareMoment.shareText(title, _ui.value.positionMs, DeepLinks.streamUri(streamId, title))
        viewModelScope.launch { _events.emit(PlayerEvent.ShareMoment(text)) }
    }

    private fun beginBundle(
        bundle: PlaybackBundle,
        title: String,
        resumeKey: String,
        overrideMs: Long? = null,
    ) {
        // B5: starting any playback is the first play — the first-run Pick 3
        // card waits for it, so a first play from Live TV or a detail page
        // unlocks it too. Fire-and-forget; the write is idempotent.
        viewModelScope.launch {
            runCatching { settingsRepository.setFirstBrowseDone(true) }
        }
        // A new file starts with no segment: the old A-B points are positions in a
        // different file. A quality switch keeps the same work, season, episode and
        // title, so the segment survives it — the range is in the file, not the
        // address. The scope key is the target plus the title, because a deep link
        // carries the empty target and only its title tells it apart.
        val scopeKey = "${bundle.target.kind}:${bundle.target.id}:${bundle.target.season}:${bundle.target.episode}:$title"
        if (abRepeatScopeKey != scopeKey) {
            _ui.update { it.copy(abRepeat = AbRepeat()) }
            lastAbLoopAtMs = null
            abRepeatScopeKey = scopeKey
        }
        store.installBundle(bundle)
        activeResumeKey = resumeKey
        // The live flag for this bundle: the Live TV tap staged the handle,
        // every other launch path staged nothing. Read here, once per
        // bundle, not in the ticker — the store is in-memory and keyed by
        // handle, and the ticker must not re-decide it 5 times a second.
        stagedLiveChannel = bundle.renditions.firstOrNull()?.playback?.streamId
            ?.let { store.isLiveChannel(it) } == true
        // Gap A3: the bundle's own SRT/VTT subtitle loads into the overlay
        // here (and again when the tracks report, below) — the preference
        // may not be in state yet at install time, and the fire-once gate
        // makes the second call a no-op when the first already ran.
        applyBundleCustomSubtitle()
        // D-35 / PL-11: publish the title's display metadata and the series scope for
        // this bundle — the loading state shows the staged poster, the More sheet
        // shows its series rows only for a series scope. A deep link or a local file
        // has nothing staged, so the poster clears and the rows hide, honestly.
        val meta = store.peekTitleMeta(resumeKey)
        _ui.update {
            it.copy(
                posterUrl = meta?.posterUrl,
                titleYear = meta?.year,
                castNames = meta?.castNames.orEmpty(),
                isSeriesScope = EpisodeProgression.seriesLikeWorkType(bundle.target.workType) ||
                    bundle.target.episode > 0,
            )
        }
        val resume = resumeByTarget[resumeKey] ?: 0L
        when {
            // A quality switch lands on a new address mid-scene: the position is the one
            // thing it must not lose, so it is an override, never a "resume from?" prompt.
            overrideMs != null -> loadNow(title, resumeKey, resumePosition = 0, override = overrideMs)

            resume > RESUME_PROMPT_THRESHOLD_MS ->
                _ui.update { it.copy(resumeOfferMs = resume, pendingResumeKey = resumeKey, pendingTitle = title) }

            // Below the ask threshold the position is still real — a 4 s stop
            // resumes silently at 4 s instead of being thrown away. The
            // threshold only decides whether to ASK, never whether to keep.
            else -> loadNow(title, resumeKey, resumePosition = resume, override = null)
        }
    }

    private fun loadNow(title: String, resumeKey: String, resumePosition: Long, override: Long?) {
        // P-1 (emulator audit): speed is per-title, not per-session. The
        // controller outlives the title, so a 2x choice on one title rode
        // into the next with no indication. A new title resets to 1.0; a
        // retry or a quality switch (same title) keeps the viewer's choice.
        // onPlaybackParametersChanged mirrors the reset into ui.speed.
        if (title != _ui.value.pendingTitle) {
            controller?.let { c ->
                if (c.playbackParameters.speed != 1f) c.playbackParameters = PlaybackParameters(1f)
            }
        }
        store.requestLoad(title = title, resumePositionMs = resumePosition, startOverrideMs = override)
        // Salaar (2026-11-13): the start clock arms in the ticker when
        // Media3 holds a URL, not here — the tap is not the load. Record
        // the request time only, so a load that never reaches Media3 still
        // gives up at thirty seconds (see the ticker's fallback).
        loadRequestedAtMs = SystemClock.elapsedRealtime()
        startAttemptAtMs = null
        startClockPending = true
        playedReportHandle = null // a new attempt may play a handle that already failed
        // Session 155: a new attempt starts with no start error — a stale
        // one from the last attempt must not ride into its give-up report.
        store.clearStartError()
        // PL-3: ask the probe at the moment of the load — offline at start shows the
        // offline card with Retry at once, not the 30-second deadline card. A retry
        // re-asks, so the card clears the moment the network is back.
        val offlineNow = connectivityProbe.current() == NetworkClass.NONE
        _ui.update {
            it.copy(
                resumeOfferMs = null,
                pendingResumeKey = null,
                giveUp = null,
                pendingTitle = title,
                startElapsedMs = 0,
                // P-3: same reset as the stage clock — the notice's elapsed
                // line counts from the new request, not the last attempt.
                startRequestElapsedMs = 0,
                deadlineGiveUp = false,
                isOffline = offlineNow,
            )
        }
        activeResumeKey = resumeKey
        resumeByTarget[resumeKey] = resumePosition.coerceAtLeast(resumeByTarget[resumeKey] ?: 0L)
    }

    /** Answer to the "Resume from 12:34?" dialog (MX "resume playback" prompt). */
    fun resolveResume(resume: Boolean) {
        val ms = _ui.value.resumeOfferMs ?: return
        val key = _ui.value.pendingResumeKey ?: return
        val title = _ui.value.pendingTitle ?: Brand.name
        if (resume) {
            loadNow(title, key, resumePosition = ms, override = null)
        } else {
            resumeByTarget.remove(key)
            loadNow(title, key, resumePosition = 0, override = null)
        }
    }

    /** "Play again from" — MX dialog to start at an arbitrary position. */
    fun playFrom(positionMs: Long) {
        val title = _ui.value.activeRendition?.displayLabel() ?: Brand.name
        val key = activeResumeKey ?: store.bundle.value?.target?.workKey ?: title
        loadNow(title, key, resumePosition = 0, override = positionMs.coerceAtLeast(0))
    }

    private fun rememberResume(forced: Boolean = true) {
        val c = controller ?: return
        val bundle = store.bundle.value ?: return
        // Write under the key every reader consults — the opened handle, not the work key.
        val key = activeResumeKey ?: bundle.target.workKey ?: c.currentMediaItem?.mediaId ?: return
        val pos = c.currentPosition
        // Resume is exact: any position past 0 is kept (the old 5 s floor threw
        // away a real stop 4 s in). A finished file still clears it.
        if (pos > 0 && pos < c.duration) resumeByTarget[key] = pos else resumeByTarget.remove(key)
        // A local file's position must survive the process: the in-memory map dies
        // with it, so a device address is also written to the local resume store
        // under the same guards (a finished file clears it).
        if (isLocalResumeUri(key)) {
            viewModelScope.launch { localResumeRepository.savePosition(key, pos, c.duration) }
        }
        reportResume(c, pos, forced)
    }

    /**
     * Tell the optional sync account where viewing is, at the same moment this class already
     * remembers it for itself. Three rules decide everything here, and all three live in
     * [SyncAccountRepository] rather than in this player:
     *  - the work is named by the handle the detail screen staged, never by a title, a row id
     *    or a work key. Nothing staged means nothing reported: a deep-linked playback has no
     *    provenable identity on the account surface, and guessing is not an option;
     *  - [forced] is a stop, a pause or a teardown — positions worth a request. Anything else
     *    goes through the fifteen-second gate, which is checked WITHOUT suspending so a
     *    twice-a-second tick costs one boolean and no coroutine at all;
     *  - a refusal is silent and cannot touch playback. Every failure path in the sync layer
     *    returns a value this function throws away, because a viewer mid-scene must never lose
     *    their picture to a resume-point upload.
     */
    private fun reportResume(c: MediaController, positionMs: Long, forced: Boolean) {
        val scope = store.currentSyncScope() ?: return
        if (positionMs <= 0L) return
        val durationMs = if (c.duration > 0) c.duration else _ui.value.durationMs
        rememberLocally(scope, positionMs, durationMs, forced)
        if (!syncSignedIn) return
        if (!forced && !syncAccountRepository.progressReportDue()) return
        viewModelScope.launch {
            // The progress report still rides to the server (resume depends
            // on it), but the finish-watch coin grant is gone with coins:
            // the server answers coins_earned 0 and no toast is shown.
            syncAccountRepository.reportProgress(
                workRef = scope.workRef,
                season = scope.season,
                episode = scope.episode,
                positionMs = positionMs,
                durationMs = durationMs,
                forced = forced,
            )
        }
    }

    /**
     * Continue watching is a local fact, not an account feature: a guest who never signed in
     * still gets a rail. Writes ride the same forced-or-fifteen-seconds gate as the account
     * report (the tick calls this five times a second, so an ungated write would be a database
     * hammer), and a scope with no history row — a deep link this app never opened — updates
     * nothing rather than inventing a title.
     */
    private fun rememberLocally(
        scope: PlayerSessionStore.SyncScope,
        positionMs: Long,
        durationMs: Long,
        forced: Boolean,
    ) {
        val now = System.currentTimeMillis()
        if (!forced && now - lastHistoryWriteAt < HISTORY_WRITE_INTERVAL_MS) return
        lastHistoryWriteAt = now
        viewModelScope.launch {
            watchHistoryDao.recordPosition(
                workRef = scope.workRef,
                season = scope.season,
                episode = scope.episode,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = now,
            )
        }
    }

    /** Epoch millis of the last local history write; see [rememberLocally]. */
    private var lastHistoryWriteAt: Long = 0L

    // ── Transport & value controls ───────────────────────────────────────────────

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            rememberResume()
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        if (!_ui.value.seekEnabled) return
        // Media3 1.3.1's Player has no seekBy(); an absolute relative-position seek is equivalent.
        c.seekTo((c.currentPosition + deltaMs).coerceAtLeast(0L))
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        if (!_ui.value.seekEnabled) return
        c.seekTo(positionMs.coerceAtLeast(0))
    }

    /**
     * MX #4: jump past the segment the Skip button is offering — to the
     * window's end, or to the end of the file for an open-ended credits
     * window. A tap with no active segment, or no known target, is a
     * no-op: the button is only ever shown when both exist, so this is
     * defence, not a path the UI takes. The seek itself is the same
     * [seekTo] the scrubber uses, so it honours the same enabled gate.
     */
    fun skipActiveSegment() {
        val segment = _ui.value.activeSkipSegment ?: return
        val target = skipTargetMs(segment, _ui.value.durationMs) ?: return
        seekTo(target)
    }

    /** Double-tap ripple feedback for the screen (side + seconds, animated by nonce). */
    fun showRipple(zone: ScreenZone, deltaMs: Long) {
        _ui.update { it.copy(ripple = RippleState(zone, deltaMs, System.nanoTime())) }
    }

    fun clearRipple() {
        _ui.update { it.copy(ripple = null) }
    }

    /** Frame-step: 1/fps seek (Media3 1.3.1 has no frame API — PLAYER_SPEC §2.3). */
    fun stepFrame(direction: Int) {
        val fps = _ui.value.frameRate?.takeIf { it > 0f } ?: 24f
        val delta = (1000f / fps) * direction.sign
        seekTo(_ui.value.positionMs + delta.toLong())
    }

    /** A-B repeat (§5 P14): set point A at the current playhead. */
    fun setAbPointA() {
        _ui.update { it.copy(abRepeat = abRepeatWithA(it.abRepeat, it.positionMs)) }
    }

    /** A-B repeat (§5 P14): set point B at the current playhead. */
    fun setAbPointB() {
        _ui.update { it.copy(abRepeat = abRepeatWithB(it.abRepeat, it.positionMs)) }
    }

    /** A-B repeat (§5 P14): clear the segment; playback continues normally. */
    fun clearAbRepeat() {
        _ui.update { it.copy(abRepeat = AbRepeat()) }
    }

    fun setSpeed(speed: Float) {
        val c = controller ?: return
        val clamped = speed.coerceIn(0.25f, 4f)
        _ui.update { it.copy(boosting = false) }
        c.playbackParameters = PlaybackParameters(clamped)
    }

    /** Long-press speed boost (MX): the screen MUST call [endBoost] on release. */
    fun beginBoost(boostSpeed: Float = 2f) {
        val c = controller ?: return
        if (_ui.value.boosting) return
        _ui.update { it.copy(boosting = true) }
        c.playbackParameters = PlaybackParameters(boostSpeed.coerceIn(0.25f, 4f))
    }

    fun endBoost() {
        val c = controller ?: return
        if (!_ui.value.boosting) return
        val restore = _ui.value.speed.coerceIn(0.25f, 4f)
        _ui.update { it.copy(boosting = false) }
        c.playbackParameters = PlaybackParameters(restore)
    }

    /**
     * The user's own volume 0..1, as the right-half gesture sets it. The value handed to
     * the player is this times the persisted boost, clamped into the 0..1 contract by
     * [AudioBoostUi.effectivePlayerGain] — see [applyPlayerVolume].
     */
    fun setUserVolume(volume: Float) {
        val clamped = if (volume.isFinite()) volume.coerceIn(0f, 1f) else 1f
        if (clamped > 0f) lastAudibleVolume = clamped
        _ui.update { it.copy(userVolume = clamped) }
        applyPlayerVolume()
    }

    /** The last audible level, so un-muting restores what the viewer had, not full blast. */
    private var lastAudibleVolume: Float = 1f

    /**
     * Mute / un-mute from the Volume button. Muting keeps the last audible level aside;
     * un-muting restores it. A gesture drag to silence is the same act, so [setUserVolume]
     * is what records the restore point, not this function.
     */
    fun toggleMute() {
        if (_ui.value.userVolume > 0f) setUserVolume(0f)
        else setUserVolume(lastAudibleVolume)
    }

    /** Fullscreen (immersive, system bars hidden) ↔ normal (system bars shown). */
    fun toggleFullscreen() {
        _ui.update { it.copy(fullscreen = !it.fullscreen) }
    }

    /**
     * The audio level control. 1.0 is the untouched baseline and 2.0 is the
     * MX Player ceiling: the part at or below unity goes to the player, the
     * excess goes to the enhancement processor as software gain (see
     * [AudioBoostUi.splitGain] and [applyPlayerVolume]). Stored through the
     * same DataStore the settings screen writes, so a mix made in the player
     * is the level the next episode starts with and the one the settings
     * screen shows.
     */
    fun setAudioBoostLevel(level: Float) {
        val clamped = AudioBoostUi.offeredLevel(level)
        _ui.update { it.copy(audioBoostLevel = clamped) }
        applyPlayerVolume()
        viewModelScope.launch { settingsRepository.saveAudioBoostLevel(clamped) }
    }

    /**
     * The one place that writes `Player.setVolume` — and the one place that
     * writes the processor's gain. Three inputs multiply into one requested
     * total: the persisted boost and the user's own volume ([AudioBoostUi.splitGain]);
     * the sleep fade multiplies only the player half. The boost can push the
     * total past 1.0, and the excess is real now: it is the processor's gain,
     * pushed to the store for the service to apply live. The caption comes
     * from [AudioBoostUi.honestSummary] and not from the requested number.
     */
    private fun applyPlayerVolume() {
        val state = _ui.value
        val fade = if (state.sleep.mode == SleepMode.COUNTDOWN) {
            SleepTimer.volumeFactorFor(state.sleep.remainingMs, state.sleep.fadeMs)
        } else {
            1f
        }
        val safeFade = if (fade.isFinite()) fade.coerceIn(0f, 1f) else 1f
        val split = AudioBoostUi.splitGain(state.audioBoostLevel, state.userVolume)
        controller?.volume = split.playerGain * safeFade
        pushAudioEnhancement(split.dspGain)
    }

    /**
     * Pushes the current processor mix to the store: the boost excess from
     * [applyPlayerVolume] (or the caller-supplied [dspGain]) plus the
     * equalizer state. The service collects this and applies it live.
     */
    private fun pushAudioEnhancement(dspGain: Float) {
        store.setAudioEnhancement(AudioEnhancementSettings(dspGain, _ui.value.eqState))
    }

    /** Equalizer on/off, from the EQ sheet. Persisted, applied live. */
    fun setEqEnabled(enabled: Boolean) {
        updateEqState(_ui.value.eqState.copy(enabled = enabled))
    }

    /** One of the named presets, from the EQ sheet. Enables the EQ. */
    fun setEqPreset(presetId: String) {
        val preset = EqPresets.byId(presetId) ?: return
        updateEqState(_ui.value.eqState.withPreset(preset))
    }

    /** One band slider, from the EQ sheet. Moves the state to Custom unless the gains match a preset. */
    fun setEqBandGain(index: Int, gainDb: Float) {
        updateEqState(_ui.value.eqState.withBandGain(index, gainDb))
    }

    private fun updateEqState(next: EqState) {
        _ui.update { it.copy(eqState = next) }
        pushAudioEnhancement(AudioBoostUi.splitGain(_ui.value.audioBoostLevel, _ui.value.userVolume).dspGain)
        viewModelScope.launch { settingsRepository.saveEqState(next) }
    }

    /** The EQ sheet, opened from the More sheet's Audio row. */
    fun showEqSheet() {
        _ui.update { it.copy(eqSheetVisible = true) }
    }

    fun hideEqSheet() {
        _ui.update { it.copy(eqSheetVisible = false) }
    }

    fun retryCurrentCandidates() {
        val title = _ui.value.activeRendition?.displayLabel() ?: Brand.name
        store.clearGaveUp()
        // Salaar (2026-11-13): same clock rule as [loadNow] — the retry's
        // clock arms when Media3 holds the retried URL, not at the tap.
        loadRequestedAtMs = SystemClock.elapsedRealtime()
        startAttemptAtMs = null
        startClockPending = true
        playedReportHandle = null // a new attempt may play a handle that already failed
        // Session 155: a retry is a new attempt — the old start error must
        // not ride into this attempt's give-up report.
        store.clearStartError()
        // P-3: same reset as the stage clock — the notice's elapsed line
        // counts from the new request, not the attempt being retried.
        _ui.update { it.copy(deadlineGiveUp = false, startElapsedMs = 0, startRequestElapsedMs = 0) }
        store.requestLoad(title = title, resumePositionMs = _ui.value.positionMs)
    }

    /**
     * Manual failover (§5 P5, the 8 s action): jump to the next print now instead of
     * waiting for the staged failover to exhaust the one being tried. The next print's own
     * runner-ups are staged under it first, and its work scope is carried over, exactly
     * as a quality switch does — so resume points and binge context survive the hop.
     * A no-op when [PlayerUiState.canTryAnotherPrint] is false; the button is never
     * offered then, so this is a guard, not a path the viewer can reach.
     */
    fun tryNextPrint() {
        val handle = _ui.value.nextPrintHandle ?: return
        val active = _ui.value.activeRendition
        val title = _ui.value.pendingTitle ?: active?.displayLabel() ?: Brand.name
        val positionMs = controller?.takeIf { it.isConnected }?.currentPosition ?: 0L
        val next = _ui.value.bundle?.renditions?.firstOrNull { it.playback.streamId == handle }
        val backups = (next?.playback?.backupStreamIds
            ?: active?.playback?.backupStreamIds.orEmpty())
            .filter { it != handle }
        store.stageFailover(handle, backups)
        store.peekWorkRef(active?.playback?.streamId)?.let { scope ->
            store.stageWorkRef(handle, scope)
            backups.forEach { store.stageWorkRef(it, scope) }
        }
        startStreamHandle(handle, title, startOverrideMs = positionMs.takeIf { it > 1_000 })
    }

    /**
     * Hate-audit H12: manual source switch. The print sheet's rows are
     * tappable now — tapping a rendition loads it now, at the current
     * position, instead of waiting for the staged failover. The same
     * staging as [tryNextPrint]: the new print's runner-ups and the work
     * scope carry over, so resume points and binge context survive the
     * hop. A tap on the print already playing just closes the sheet.
     */
    fun switchRendition(rendition: PlaybackRendition) {
        val active = _ui.value.activeRendition
        // The handle is nullable by design (PlayBundle: a rank can carry
        // no address of ours at all) — a tap on such a rendition loads
        // nothing, so it is a no-op and the sheet stays open for another
        // pick, instead of crashing the compile's non-null contract.
        val handle = rendition.playback.streamId ?: return
        if (handle == active?.playback?.streamId) {
            hidePrintSheet()
            return
        }
        val title = _ui.value.pendingTitle ?: active?.displayLabel() ?: Brand.name
        val positionMs = controller?.takeIf { it.isConnected }?.currentPosition ?: 0L
        val backups = rendition.playback.backupStreamIds.filter { it != handle }
        store.stageFailover(handle, backups)
        store.peekWorkRef(active?.playback?.streamId)?.let { scope ->
            store.stageWorkRef(handle, scope)
            backups.forEach { store.stageWorkRef(it, scope) }
        }
        hidePrintSheet()
        startStreamHandle(handle, title, startOverrideMs = positionMs.takeIf { it > 1_000 })
    }

    /**
     * The viewer dismissed the 30 s give-up card: keep waiting, with a fresh thirty
     * seconds. A dismiss is not a fix — if the copy is truly dead the card comes back —
     * but a viewer who is still willing to wait should not be nagged every tick.
     */
    fun dismissDeadlineGiveUp() {
        // Salaar (2026-11-13): same clock rule as [loadNow] — the fresh
        // thirty seconds arm when Media3 holds a URL, not at the dismiss.
        loadRequestedAtMs = SystemClock.elapsedRealtime()
        startAttemptAtMs = null
        startClockPending = true
        playedReportHandle = null // a new attempt may play a handle that already failed
        // P-3: same reset as the stage clock — the notice's elapsed line
        // counts from the new request, not the attempt being dismissed.
        _ui.update { it.copy(deadlineGiveUp = false, startElapsedMs = 0, startRequestElapsedMs = 0) }
    }

    /** Manual "next episode" button — same event path as auto-advance at media end. */
    fun requestNextEpisode() {
        // A manual Next while the countdown is running replaces it: the viewer
        // already chose, so the held advance is dropped, never fired twice.
        clearAutoplayCountdown()
        _ui.update { it.copy(endedHasNextEpisode = false) }
        val bundle = store.bundle.value
        val target = bundle?.target
        if (target == null || (target.episode <= 0 && target.season <= 0)) {
            viewModelScope.launch {
                _events.emit(PlayerEvent.Message("No episode context on this link"))
            }
            return
        }
        val season = target.season
        val nextEpisode = (target.episode.coerceAtLeast(1)) + 1
        viewModelScope.launch {
            if (openNextEpisode(season, nextEpisode, target.id.takeIf { it > 0 })) return@launch
            _events.emit(
                PlayerEvent.PlayNextEpisode(
                    workKey = target.workKey,
                    postId = target.id.takeIf { it > 0 },
                    season = season,
                    episode = nextEpisode,
                ),
            )
        }
    }

    /**
     * Whether the END that just happened leads anywhere. A packed file's in-file successor
     * counts, so the advance is never offered before a dead end.
     */
    private fun hasSuccessorAtEnd(state: PlayerUiState): Boolean {
        val bundle = store.bundle.value ?: return false
        return when (
            EpisodeProgression.computeNext(
                bundle = bundle,
                rendition = store.activeRendition(),
                learned = store.learnedOffsets,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
            )
        ) {
            is NextEpisodeAction.InFileSeek,
            is NextEpisodeAction.NextBundleRequest,
            is NextEpisodeAction.CalibrationNeeded,
            -> true
            else -> false
        }
    }

    // ── Smart binge pre-caching ──────────────────────────────────────────────────

    /**
     * Start looking up the next episode's handle while the current one is still playing, so
     * the policy has something to ask for at the threshold instead of discovering at 85% that
     * nobody ever resolved the successor.
     *
     * The episode context comes from the bundle when the server supplied one, and from the
     * scope the detail screen staged otherwise — a route launch carries one opaque handle,
     * so without that stamp there would be no post id to look a successor's ladder up by.
     *
     * Skipped when the next episode lives in THIS file (a packed season whose offset the
     * server or the learned ledger already knows): there is no second address to warm, and
     * the in-file advance the service performs on END is the whole feature there.
     */
    private fun prepareBingePrefetch(bundle: PlaybackBundle) {
        val target = bundle.target
        val rendition = store.activeRendition() ?: bundle.renditions.firstOrNull()
        val scope = store.currentSyncScope()
        val episode = target.episode.takeIf { it > 0 } ?: scope?.episode ?: 0
        val season = target.season.takeIf { it > 0 } ?: scope?.season ?: 0
        val postId = target.id.takeIf { it > 0 } ?: scope?.postId ?: 0
        val isSeries = EpisodeProgression.seriesLikeWorkType(target.workType ?: scope?.workType) ||
            episode > 0
        if (!isSeries || postId <= 0 || episode <= 0 || rendition == null) return
        if (nextEpisodeIsInThisFile(bundle, rendition)) return
        val nextEpisode = episode + 1
        val expectedKey = bingeBundleKey(bundle)
        bingeResolveJob?.cancel()
        bingeResolveJob = viewModelScope.launch {
            val prepared = lookupNextEpisode(postId, season, nextEpisode) ?: return@launch
            // A bundle switch while the lookup ran means this successor belongs to a
            // viewing the viewer already left; never arm it against the new episode.
            if (bingeBundleKey(store.bundle.value) != expectedKey) return@launch
            nextEpisodeHandle = prepared.handle
            preparedNextEpisode = prepared
            publishNextEpisode()
        }
    }

    /** The successor's own ladder, reduced to what a hand-off needs. */
    private suspend fun lookupNextEpisode(postId: Int, season: Int, episode: Int): PreparedNextEpisode? {
        val detail = runCatching { catalogRepository.getPostDetail(postId) }.getOrNull() ?: return null
        val ladder = VipQualityGate.gate(
            QualityLadder.forNumberedScope(
                detail.optionGroups,
                season.takeIf { it > 0 },
                episode,
            ),
            premiumAccess,
            freeQualityCap,
        )
        val option = ladder.resolvedFor(LadderForScope.AUTO) ?: return null
        val handle = option.streamId.takeIf { it.isNotBlank() } ?: return null
        val rungs = ladder.options
            .filter { it.playsHere && it.streamId.isNotBlank() && it.band.isNotBlank() }
            .map {
                LadderRung(
                    band = it.band,
                    streamId = it.streamId,
                    backupHandles = it.backupHandles,
                    label = it.title,
                )
            }
        val title = detail.cleanTitle?.takeIf { it.isNotBlank() } ?: detail.title
        return PreparedNextEpisode(
            season = season,
            episode = episode,
            handle = handle,
            title = title,
            backupHandles = option.backupHandles,
            rungs = rungs,
            startBand = option.band,
            auto = ladder.autoBand != null && option.band == ladder.autoBand,
            posterUrl = detail.posterUrl,
            year = detail.year,
            castNames = parseCastNames(detail.castNames),
        )
    }

    /** True when the successor's start position in this same file is already known. */
    private fun nextEpisodeIsInThisFile(bundle: PlaybackBundle, rendition: PlaybackRendition): Boolean {
        val coverage = rendition.coverage
        if (!coverage.isPacked()) return false
        val target = bundle.target
        val next = target.episode.coerceAtLeast(1) + 1
        val season = coverage.season.takeIf { it > 0 } ?: target.season
        val known = coverage.episodes.any { it.number == next } ||
            store.learnedOffsets.lookup(target.workKey, season, next, rendition.print.label) != null
        // A pack that simply runs on (no chapter row) still needs no second address, so it
        // counts as in-file: the service seeks at END and there is nothing to pre-fetch.
        return known || coverage.lastEpisode == 0 || next <= coverage.lastEpisode
    }

    /**
     * One binge tick: ask the policy, and act on a real request exactly once.
     *
     * Session 123: the act used to be only a probe — the redirector was asked
     * about the handle and the successor marked resolved, but zero bytes were
     * fetched, so the advance still waited on the first bytes. The head-pull
     * entry point the old comment said did not exist is the engine's own
     * `seedPreview` (the same bounded ahead-window the player seeds while
     * watching), so the tick now pulls the next episode's head through it
     * after a usable probe. The budget (`mayPreview`) and the Wi-Fi-only
     * switch still apply: on mobile data with Wi-Fi-only on, the link stays
     * warm and the bytes wait for Wi-Fi.
     */
    private fun tickBingePreload(positionMs: Long, durationMs: Long) {
        val bundle = store.bundle.value ?: return
        val target = bundle.target
        val episode = target.episode.takeIf { it > 0 } ?: store.currentSyncScope()?.episode ?: 0
        val isSeries = EpisodeProgression.seriesLikeWorkType(target.workType) || episode > 0
        val decision = bingePlanner.tick(positionMs, durationMs, isSeries, nextEpisodeHandle)
        if (decision is BingePreloadDecision.PrefetchNextHead) prefetchNextEpisodeHead(decision.handle)
    }

    /**
     * Ask the redirector about one handle and report what the answer *was*, as primitives —
     * same reasoning as `CatalogRepository.probePost`: the refusal/silence distinction is what
     * decides whether a viewer's saved row gets hidden, and a `runCatching` that flattens a 404
     * and a dead socket into `null` is exactly the bug that rule exists to avoid.
     */
    private suspend fun probeHandle(handle: String): HandleProbe = try {
        HandleProbe(catalogRepository.resolveStream(handle), null, null, noAnswer = false)
    } catch (e: HttpException) {
        HandleProbe(null, e.code(), null, noAnswer = false)
    } catch (e: Exception) {
        HandleProbe(null, null, null, noAnswer = true)
    }

    private fun prefetchNextEpisodeHead(handle: String) {
        bingePrefetchJob?.cancel()
        bingePrefetchJob = viewModelScope.launch {
            val probe = probeHandle(handle)
            // A redirector that refuses this handle by name is not a hiccup: the successor is
            // gone, and a saved row pointing at it must stop looking playable. Silence, by
            // contrast, says nothing and is left as one.
            if (probe.answer == AdultPolicy.ServerAnswer.GONE) {
                store.currentSyncScope()?.postId
                    // 0 is the scope's "no post" default, never a row to mark.
                    ?.takeIf { it > 0 }
                    ?.let { savedId -> favoritesRepository.recordStreamRefusal(savedId) }
                _events.emit(PlayerEvent.Message(NEXT_UNAVAILABLE))
                return@launch
            }
            val answer = probe.response
            // `streamId` is the model's own "this answer is usable" test: a successful lookup
            // that produced no handle of ours is a false success and says nothing to the user.
            if (answer?.streamId == null) return@launch
            val prepared = preparedNextEpisode
            if (prepared != null && prepared.handle == handle) {
                preparedNextEpisode = prepared.copy(resolved = true)
            }
            // Session 123: the probe alone never fetched a byte. Pull the next
            // episode's head through the engine's preview pass now — the same
            // bounded ahead-window the player seeds while watching — so the
            // advance starts on bytes already on disk. The engine's own budget
            // (`mayPreview`) decides whether the bytes are affordable, and the
            // Wi-Fi-only switch decides whether mobile data may buy them: with
            // Wi-Fi-only on and no Wi-Fi, the link stays warm and the bytes
            // wait. A preview that fails is silent — the advance still works,
            // it just waits on the first bytes again.
            val engine = CacheEngineHolder.engine
            val onWifi = connectivityProbe.current() == NetworkClass.WIFI
            if (engine != null && !DataSaver.wifiOnlyBlocks(wifiOnlyDownloads, onWifi)) {
                val url = withVipPass(
                    streamRedirectUrl(apiProvider.currentBaseUrl(), handle),
                    // Session 123: the preview fetches bytes through the same
                    // redirector the player uses, so the same pass rides it —
                    // a VIP next episode prefetches instead of 402ing.
                    syncAccountRepository.currentVipPass(),
                )
                val label = prepared
                    ?.takeIf { it.handle == handle }
                    ?.let { "Episode ${it.episode}" }
                    ?: "Next episode"
                runCatching {
                    engine.seedPreview(
                        PreviewRequest(
                            key = CacheSessionKey(renditionKey = handle, url = url),
                            url = url,
                            expectedTotalBytes = answer.fileSizeBytes?.takeIf { it > 0L },
                            resumable = answer.resumable ?: false,
                            printLabel = label,
                        ),
                    )
                }
            }
            _events.emit(
                PlayerEvent.Message("Next episode ready — its link is live, so advancing will not wait on a lookup"),
            )
        }
    }

    /**
     * Open the successor requested by the END signal or the manual next button.
     *
     * [preparedNextEpisode] is the fast path: the ladder was already resolved and the
     * redirector was already asked at 85%, so this is an install and a load. When nothing
     * was prepared (a very short episode, a seek past the threshold, a just-opened link)
     * the ladder is looked up now, which is still the same hand-off minus the warming.
     * False means no playable rung exists, and the caller falls back to the app's event
     * route rather than pretending the episode advanced.
     */
    private suspend fun openNextEpisode(season: Int, episode: Int, postId: Int?): Boolean {
        val prepared = preparedNextEpisode
            ?.takeIf { it.season == season && it.episode == episode }
            ?: lookupNextEpisode(
                postId = postId?.takeIf { it > 0 } ?: store.currentSyncScope()?.postId ?: return false,
                season = season,
                episode = episode,
            )
            ?: return false
        openPrepared(prepared)
        return true
    }

    private fun openPrepared(prepared: PreparedNextEpisode) {
        // Carry the work's scope onto every address of the successor, exactly as the detail
        // screen does when it launches a rung: a resume point belongs to the episode, not to
        // whichever handle happens to serve it.
        store.currentSyncScope()?.let { current ->
            val scope = current.copy(season = prepared.season, episode = prepared.episode)
            (listOf(prepared.handle) + prepared.backupHandles +
                prepared.rungs.flatMap { listOf(it.streamId) + it.backupHandles })
                .distinct()
                .forEach { store.stageWorkRef(it, scope) }
        }
        // D-35: carry the successor's display metadata with its scope, so the loading
        // state of the next episode shows its poster instead of a black void.
        val nextMeta = PlayerSessionStore.TitleMeta(
            posterUrl = prepared.posterUrl,
            year = prepared.year,
            castNames = prepared.castNames,
        )
        (listOf(prepared.handle) + prepared.backupHandles +
            prepared.rungs.flatMap { listOf(it.streamId) + it.backupHandles })
            .distinct()
            .forEach { store.stageTitleMeta(it, nextMeta) }
        store.stageFailover(prepared.handle, prepared.backupHandles)
        if (prepared.rungs.isNotEmpty()) {
            store.stageAutoLadder(prepared.handle, prepared.rungs, prepared.startBand, prepared.auto)
        }
        preparedNextEpisode = null
        nextEpisodeHandle = null
        publishNextEpisode()
        startStreamHandle(prepared.handle, prepared.title)
    }

    private fun bingeBundleKey(bundle: PlaybackBundle?): String? = bundle?.let {
        "${it.session.id}-${it.target.workKey}-${it.target.season}-${it.target.episode}-${it.target.id}"
    }

    // ── Sheet visibility ─────────────────────────────────────────────────────────

    fun showSpeedSheet() {
        _ui.update { it.copy(speedSheetVisible = true) }
    }

    fun hideSpeedSheet() {
        _ui.update { it.copy(speedSheetVisible = false) }
    }

    fun showTracksSheet() {
        _ui.update { it.copy(tracksSheetVisible = true) }
    }

    fun hideTracksSheet() {
        _ui.update { it.copy(tracksSheetVisible = false) }
    }

    fun showSubtitleSheet() {
        _ui.update { it.copy(subtitleSheetVisible = true) }
    }

    fun hideSubtitleSheet() {
        _ui.update { it.copy(subtitleSheetVisible = false) }
    }

    /** Opens the sync sheet alone: the subtitle list and the offset control never stack. */
    fun showSubtitleOffsetSheet() {
        _ui.update { it.copy(subtitleSheetVisible = false, subtitleOffsetSheetVisible = true) }
    }

    fun hideSubtitleOffsetSheet() {
        _ui.update { it.copy(subtitleOffsetSheetVisible = false) }
    }

    /**
     * Live subtitle sync. Called on every slider frame and on each quick button, so the
     * custom SRT/VTT overlay re-renders on the next frame AND the service's decoder wrapper
     * retimes the native track from the same number while playback continues.
     *
     * A positive value shows the text later, matching both render paths. The value is
     * remembered for this title (see [scheduleSyncPrefsSave]) — the sheet is where a
     * per-file correction is made, so the next episode of the same work opens with it.
     */
    fun setSubtitleOffset(delayMs: Long) {
        val clamped = SubtitleOffset.clamp(delayMs)
        _ui.update { it.copy(subtitle = it.subtitle.copy(delayMs = clamped)) }
        store.subtitleOffsetMs = clamped
        scheduleSyncPrefsSave()
    }

    /**
     * Live audio sync. Called on every slider frame and on each quick button; the delay
     * processor in the service's audio sink retimes the sound from the same number while
     * playback continues, with no rebuild and no seek.
     *
     * A positive value plays the sound later, matching the subtitle convention. The
     * value is remembered for this title (see [scheduleSyncPrefsSave]).
     */
    fun setAudioDelay(delayMs: Long) {
        val clamped = AudioOffset.clamp(delayMs)
        _ui.update { it.copy(audioDelayMs = clamped) }
        store.setAudioDelayMs(clamped)
        scheduleSyncPrefsSave()
    }

    /**
     * REA #3: the sync offsets for the work now playing, loaded once per title and
     * applied to the UI, the subtitle paths and the audio sink. A work with no saved
     * row opens in sync (both offsets 0) — the correction belongs to the file that
     * needed it, never to the next title. Called from the bundle collector, which
     * is where a new episode's identity first lands.
     */
    private var lastSyncPrefsWorkRef: String? = null
    private var syncPrefsSaveJob: Job? = null

    private fun applySavedSyncPrefs(workRef: String?) {
        if (workRef == lastSyncPrefsWorkRef) return
        lastSyncPrefsWorkRef = workRef
        syncPrefsSaveJob?.cancel()
        if (workRef.isNullOrBlank()) {
            applySyncOffsets(audioDelayMs = 0L, subtitleDelayMs = 0L)
            return
        }
        viewModelScope.launch {
            val saved = runCatching { titleSyncPrefsDao.findByWorkRef(workRef) }.getOrNull()
            applySyncOffsets(
                audioDelayMs = saved?.audioDelayMs ?: 0L,
                subtitleDelayMs = saved?.subtitleDelayMs ?: 0L,
            )
        }
    }

    /** One place that puts both offsets into the UI and the two live paths. */
    private fun applySyncOffsets(audioDelayMs: Long, subtitleDelayMs: Long) {
        val audio = AudioOffset.clamp(audioDelayMs)
        val subtitle = SubtitleOffset.clamp(subtitleDelayMs)
        _ui.update { it.copy(audioDelayMs = audio, subtitle = it.subtitle.copy(delayMs = subtitle)) }
        store.setAudioDelayMs(audio)
        store.subtitleOffsetMs = subtitle
    }

    /**
     * Persists the live offsets for the work now playing, debounced so a slider drag
     * writes once instead of once per frame. A row with both offsets 0 is deleted —
     * "in sync" and "never asked" are the same absence, exactly like a cleared thumb.
     * A Room write never touches playback: a failure here is swallowed, because a
     * viewer mid-scene must never lose their picture to a preference save.
     */
    private fun scheduleSyncPrefsSave() {
        syncPrefsSaveJob?.cancel()
        syncPrefsSaveJob = viewModelScope.launch {
            delay(SYNC_PREFS_SAVE_DEBOUNCE_MS)
            val workRef = lastSyncPrefsWorkRef?.takeIf { it.isNotBlank() } ?: return@launch
            val audio = _ui.value.audioDelayMs
            val subtitle = _ui.value.subtitle.delayMs
            runCatching {
                if (audio == 0L && subtitle == 0L) {
                    titleSyncPrefsDao.deleteByWorkRef(workRef)
                } else {
                    titleSyncPrefsDao.insert(
                        TitleSyncPrefs(
                            workRef = workRef,
                            audioDelayMs = audio,
                            subtitleDelayMs = subtitle,
                        ),
                    )
                }
            }
        }
    }

    fun showPrintSheet() {
        _ui.update { it.copy(printSheetVisible = true) }
    }

    fun hidePrintSheet() {
        _ui.update { it.copy(printSheetVisible = false) }
    }

    fun showMoreSheet() {
        _ui.update { it.copy(moreSheetVisible = true) }
    }

    fun hideMoreSheet() {
        _ui.update { it.copy(moreSheetVisible = false) }
    }

    fun showSleepSheet() {
        _ui.update { it.copy(sleepSheetVisible = true) }
    }

    fun hideSleepSheet() {
        _ui.update { it.copy(sleepSheetVisible = false) }
    }

    fun showPlayFromDialog() {
        _ui.update { it.copy(playFromDialogVisible = true) }
    }

    fun hidePlayFromDialog() {
        _ui.update { it.copy(playFromDialogVisible = false) }
    }

    // ── Aspect / rotation / lock / audio-only ────────────────────────────────────

    fun cycleAspect() {
        _ui.update { it.copy(aspect = AspectMode.nextOf(it.aspect)) }
    }

    fun setAspect(mode: AspectMode) {
        _ui.update { it.copy(aspect = mode) }
    }

    fun toggleOrientation() {
        _ui.update { it.copy(orientation = it.orientation.toggled()) }
    }

    /**
     * H18 (user decision 04:42 PKT 20 Nov): set the orientation to the
     * phone's held orientation at player entry, so the entry effect's
     * sensor request is not overwritten by the stale LANDSCAPE default.
     */
    fun setOrientation(orientation: PlayerOrientation) {
        _ui.update { it.copy(orientation = orientation) }
    }

    /**
     * REA #4 (Netflix, Nov 2026): the "Play Something" shuffle for the
     * give-up card — a weighted random pick from the "For you" pool
     * ([ForYouPoolStore]), so a title that will not play still ends in
     * something playing. [canPlaySomething] is false when the ranker has
     * no row (yet), so the card never offers a shuffle with nothing
     * behind it; the pick itself is taken at tap time, never at
     * composition, so two taps draw two titles.
     */
    val canPlaySomething: Boolean get() = ForYouPoolStore.hasPicks()

    fun playSomething(): Post? = ForYouPoolStore.pick()

    fun setLocked(locked: Boolean) {
        // MX #7 — Kids Lock: unlocking in kids mode asks for the PIN first.
        // The screen raises the pad on the event this emits, and the PIN's
        // success calls [unlockWithKidsPin] instead of coming back here —
        // so a kid hammering the unlock button only ever gets the pad.
        // Locking always works: a kid may lock, never unlock.
        if (!locked && kidsMode && !kidsUnlockGranted) {
            viewModelScope.launch { _events.emit(PlayerEvent.RequestKidsPin) }
            return
        }
        _ui.update { it.copy(locked = locked) }
        // MX #9 — the lock is a Cast input: locking takes the button away
        // and ends a live session (the service enforces the store's
        // answer); unlocking offers it again.
        recomputeCastAllowed()
    }

    /**
     * MX #7 — Kids Lock: the PIN's success path — unlocks the player and
     * records the grant for this title only. A new episode re-locks (the
     * bundle collector below), and turning the mode off clears the grant.
     */
    fun unlockWithKidsPin() {
        kidsUnlockGranted = true
        _ui.update { it.copy(locked = false) }
        // MX #9 — the PIN's success offers Cast again (see setLocked).
        recomputeCastAllowed()
    }

    /**
     * Full chrome ↔ Ghost mode. Persisted through the same DataStore the settings screen
     * writes, so the next episode opens in the frame the viewer last asked for, and so the
     * two entry points (this button and the settings row) can never disagree.
     *
     * The operator's `pure_cinema` switch is the one thing that can refuse this: the screen
     * already hides both buttons while it is off, and this guard is what stops any other caller
     * from smuggling chrome away behind that switch. The stored choice is not rewritten, so the
     * viewer gets the mode back the moment the board allows it again.
     */
    fun toggleViewMode() {
        val to = ViewMode.nextOf(_ui.value.viewMode)
        if (to == ViewMode.PURE_CINEMA && !_ui.value.pureCinemaAllowed) return
        _ui.update { it.copy(viewMode = to) }
        viewModelScope.launch { settingsRepository.saveViewModeChoice(to.name) }
    }

    fun toggleAudioOnly() {
        val c = controller ?: return
        val to = !_ui.value.audioOnly
        // Background/audio-only playback: video renderer off, audio keeps running via session.
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, to)
            .build()
        _ui.update { it.copy(audioOnly = to) }
    }

    /**
     * MX "downmix to stereo" analogue: 1.3.1 cannot downmix, but can restrict SELECTION to
     * stereo tracks (setMaxAudioChannelCount verified). Documented in PLAYER_SPEC §2.3.
     */
    fun toggleStereoOnly() {
        val c = controller ?: return
        val to = !_ui.value.stereoOnly
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setMaxAudioChannelCount(if (to) 2 else Integer.MAX_VALUE)
            .build()
        _ui.update { it.copy(stereoOnly = to) }
    }

    // ── Tracks: audio & subtitle selection ───────────────────────────────────────

    fun selectAudioTrack(option: TrackUiOption?) = selectTrack(C.TRACK_TYPE_AUDIO, option)

    /** null → subtitles off. */
    fun selectSubtitleTrack(option: TrackUiOption?) = selectTrack(C.TRACK_TYPE_TEXT, option)

    /**
     * Apply the user's audio language to the tracks the player just reported.
     *
     * [AudioTrackPolicy.pickPreferred] returns null in the two cases where forcing a
     * selection would make things worse — the stream has nothing in that language, and the
     * language is already playing — so a null answer is honoured as "change nothing" rather
     * than second-guessed here. Media3's own locale heuristic is left in charge exactly when
     * neither the viewer nor their region expressed a preference (East Asian audio); a
     * viewer who never chose still gets their region's default, Hindi for Desi.
     */
    private fun applyPreferredAudioTrack() {
        val state = _ui.value
        val preferred = state.preferredAudioLanguage?.takeIf { it.isNotBlank() } ?: return
        if (controller == null) return
        val picked = AudioTrackPolicy.pickPreferred(
            state.audioTrackRows(),
            preferred,
            state.preferredAudioFallback,
        ) ?: return
        if (picked == state.selectedAudioId) return
        val option = state.audioTracks.firstOrNull { it.id == picked } ?: return
        selectAudioTrack(option)
    }

    /**
     * The (playback, preference) pair the subtitle row has already honoured.
     *
     * WHY TEXT NEEDS A GATE AUDIO DOES NOT: audio can only ever be asked to select a track or
     * to say nothing, and a hand-picked track simply re-reports itself. The subtitle row also
     * says "turn off", and `onTracksChanged` fires after every selection made from the
     * Subtitles sheet — re-running an Off on that report would silence what the viewer just
     * chose, mid-episode. The pair is keyed on the binge lifecycle rather than the media item,
     * for the same reason the planner is: a silent failover or an Auto move is a new address
     * for the same viewing, and dropping the subtitles under either would be a second bug.
     * A new file, a new episode, or a new preference value changes the pair and re-arms it.
     */
    private var subtitleHonouredFor: Pair<String, String?>? = null

    /**
     * The (playback, preference) pair the bundle's own custom subtitle
     * already honoured — the same fire-once discipline as
     * [subtitleHonouredFor], on its own gate, so loading the overlay file
     * never spends the native row's gate and a sheet selection never
     * re-fetches under the viewer.
     */
    private var bundleCustomSubtitleHonouredFor: Pair<String, String?>? = null

    /**
     * Apply the Settings subtitle language to the text tracks the player just reported.
     *
     * [SubtitleTrackPolicy.decide] makes the three-way call and goes through the same
     * matcher the audio row uses, so a preference typed as "Hindi" finds a track tagged "hi"
     * here exactly as it does for audio, and an unmatched preference leaves the player's own
     * default alone instead of guessing. Off is the same act as the sheet's "Turn subtitles
     * off": it disables the text renderer, which also quietens a native external file.
     */
    private fun applyPreferredSubtitleTrack() {
        if (controller == null) return
        val state = _ui.value
        // Before the gate: a file with no text tracks has nothing to pick and nothing to
        // silence, and it must not spend the pair a file that has tracks will need.
        if (state.subtitleTracks.isEmpty()) return
        val key = bingeBundleKey(store.bundle.value).orEmpty() to state.preferredSubtitleLanguage
        if (key == subtitleHonouredFor) return
        subtitleHonouredFor = key
        when (val decision = SubtitleTrackPolicy.decide(state.subtitleTrackRows(), state.preferredSubtitleLanguage)) {
            SubtitleTrackDecision.TurnOff -> selectSubtitleTrack(null)
            SubtitleTrackDecision.LeaveAlone -> Unit
            is SubtitleTrackDecision.Select -> {
                val option = state.subtitleTracks.firstOrNull { it.id == decision.id } ?: return
                selectSubtitleTrack(option)
            }
        }
    }

    /**
     * Load the bundle's own custom-rendered (SRT/VTT) subtitle track into
     * the overlay (Gap A3). Native tracks reach the Media3 picker through
     * the factory; SRT/VTT are filtered out there by design — the overlay
     * renders them — so without this the bundle's most common subtitle
     * format would arrive and then never display. The pick follows the
     * same policy as the native row ([SubtitleTrackPolicy.decide]): the
     * viewer's language, and nothing when it matches none — never a
     * guess, and Off loads nothing. Fire-once per bundle + preference on
     * its own gate, so a failover hop or a sheet selection never
     * re-fetches under the viewer. A new bundle retires the previous
     * bundle's file; a viewer-picked SAF file is theirs and stays.
     */
    private fun applyBundleCustomSubtitle() {
        val bundle = store.bundle.value ?: return
        val rendition = store.activeRendition() ?: bundle.renditions.firstOrNull() ?: return
        val custom = rendition.tracks.subtitles.filter {
            PlaybackMediaItemFactory.isCustomRenderable(it.format) && !it.url.isNullOrBlank()
        }
        val state = _ui.value
        val key = bingeBundleKey(bundle).orEmpty() to state.preferredSubtitleLanguage
        if (key == bundleCustomSubtitleHonouredFor) return
        bundleCustomSubtitleHonouredFor = key
        if (customSubtitleFromBundle) {
            customSubtitleFile = null
            customSubtitleFromBundle = false
            _ui.update { it.copy(customSubtitleLoaded = null) }
        }
        if (custom.isEmpty()) return
        val rows = custom.mapIndexed { index, track ->
            AudioTrackRow(
                id = "bundle-sub-$index",
                language = track.language,
                label = AudioTrackMatcher.displayableLanguageTag(track.language) ?: "Subtitles",
                selected = false,
                supported = true,
            )
        }
        val picked = when (val decision = SubtitleTrackPolicy.decide(rows, state.preferredSubtitleLanguage)) {
            is SubtitleTrackDecision.Select -> custom.getOrNull(rows.indexOfFirst { it.id == decision.id })
            else -> null
        } ?: return
        val url = picked.url ?: return
        viewModelScope.launch {
            val text = fetchSubtitleText(url)
            if (text == null) return@launch
            customSubtitleFile = SubtitleParser.parseOverlay(text, SubtitleFormat.fromFileNameOrMime(picked.format ?: ""))
            customSubtitleFromBundle = true
            val label = AudioTrackPolicy.nameFor(picked.language)
                ?: AudioTrackMatcher.displayableLanguageTag(picked.language) ?: "Subtitles"
            _ui.update { it.copy(customSubtitleLoaded = label) }
        }
    }

    /**
     * The audio-track cycle affordance: the next playable track, wrapping. Refused out loud
     * when there is nothing to cycle between, because a button that silently does nothing
     * reads as a broken button.
     */
    fun cycleAudioTrack() {
        val state = _ui.value
        val next = AudioTrackPolicy.nextInCycle(state.audioTrackRows(), state.selectedAudioId)
        if (next == null) {
            viewModelScope.launch {
                _events.emit(PlayerEvent.Message("Only one audio track on this print — nothing to switch to"))
            }
            return
        }
        val option = state.audioTracks.firstOrNull { it.id == next } ?: return
        selectAudioTrack(option)
        val name = AudioTrackPolicy.nameFor(option.language)
        viewModelScope.launch {
            _events.emit(PlayerEvent.Message("Audio: ${name ?: option.label}"))
        }
    }

    private fun selectTrack(trackType: Int, option: TrackUiOption?) {
        val c = controller ?: return
        var builder = c.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(trackType)
        if (trackType == C.TRACK_TYPE_TEXT) {
            builder = builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, option == null)
        }
        if (option != null) {
            builder = builder.setOverrideForType(
                TrackSelectionOverride(option.group, listOf(option.trackIndex)),
            )
        }
        c.trackSelectionParameters = builder.build()
        _ui.update {
            if (trackType == C.TRACK_TYPE_AUDIO) it.copy(selectedAudioId = option?.id)
            else it.copy(selectedSubtitleId = option?.id)
        }
        // Remember the pick: the next episode (and the next visit) auto-applies this
        // language, not the old Settings choice. The sync delay is deliberately never
        // saved — it belongs to this viewing. Turning subtitles off is a pick too: it
        // saves the literal "Off", because saving nothing would let the region default
        // (English) come back on the next episode and undo the viewer in front of us.
        val language = persistedLanguageFor(option)
        if (language != null) {
            viewModelScope.launch {
                if (trackType == C.TRACK_TYPE_AUDIO) settingsRepository.savePreferredAudioLanguage(language)
                else settingsRepository.savePreferredSubtitleLanguage(language)
            }
        } else if (trackType == C.TRACK_TYPE_TEXT) {
            viewModelScope.launch {
                settingsRepository.savePreferredSubtitleLanguage(ServerSettingsRepository.SUBTITLE_OFF)
            }
        }
    }

    // ── Subtitles: settings + external files ─────────────────────────────────────

    /**
     * Change how subtitles look, and remember it. Size, seat, weight, plate and colour are the
     * viewer's own style and survive a restart, a process kill and the next title; the sync
     * offset is the one field this store is never told, because it belongs to one title's
     * file — it is remembered per work in [TitleSyncPrefs] instead, and applied when that
     * title opens.
     */
    fun setSubtitleSettings(settings: SubtitleUiSettings) {
        val style = settings.clamped()
        _ui.update { it.copy(subtitle = style) }
        viewModelScope.launch { settingsRepository.saveSubtitleStyle(style) }
    }

    /** Bundle-provided or SAF-picked external subtitle. SRT/VTT/ASS → our overlay (delay read
     *  directly, styles kept for ASS); exotic formats → Media3 SubtitleConfiguration (delay via the decoder wrapper). */
    fun addExternalSubtitle(url: String, label: String, formatHint: String) {
        viewModelScope.launch {
            val ext = ExternalSubtitle(
                url = url,
                label = label,
                formatHint = formatHint,
                customRendered = PlaybackMediaItemFactory.isCustomRenderable(formatHint),
            )
            if (ext.customRendered) {
                val text = fetchSubtitleText(url)
                if (text == null) {
                    _events.emit(PlayerEvent.Message("Could not load subtitle file: $label"))
                    return@launch
                }
                customSubtitleFile = SubtitleParser.parseOverlay(text, SubtitleFormat.fromFileNameOrMime(formatHint))
                customSubtitleFromBundle = false
                _ui.update { it.copy(customSubtitleLoaded = label) }
            } else {
                val bundle = store.bundle.value
                val index = store.activeRenditionIndex.value
                val rendition = bundle?.renditions?.getOrNull(index)
                val c = controller
                if (bundle == null || rendition == null || c == null || c.mediaItemCount == 0) return@launch
                val natives = _ui.value.nativeExternals + ext
                val item = PlaybackMediaItemFactory.build(
                    rendition = rendition,
                    target = bundle.target,
                    title = _ui.value.pendingTitle ?: rendition.displayLabel(),
                    externalSubtitles = natives,
                )
                c.replaceMediaItem(index, item)
                c.seekTo(_ui.value.positionMs)
                c.prepare()
                _ui.update { it.copy(nativeExternals = natives) }
            }
        }
    }

    fun removeCustomSubtitle() {
        customSubtitleFile = null
        customSubtitleFromBundle = false
        _ui.update { it.copy(customSubtitleLoaded = null) }
    }

    private suspend fun fetchSubtitleText(url: String): String? = withContext(Dispatchers.IO) {
        try {
            when {
                url.startsWith("content://") ->
                    appContext.contentResolver.openInputStream(Uri.parse(url))?.use { stream ->
                        stream.bufferedReader().readText().takeIf { it.length < MAX_SUBTITLE_BYTES }
                    }
                url.startsWith("http://") || url.startsWith("https://") -> {
                    val request = okhttp3.Request.Builder().url(url).build()
                    okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                        if (!response.isSuccessful) null
                        else response.body?.string()?.takeIf { it.length < MAX_SUBTITLE_BYTES }
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Active subtitle lines for the custom overlay (delay applied here — MX subtitle sync). */
    fun overlayCues(positionMs: Long): List<OverlayCue> {
        val file = customSubtitleFile ?: return emptyList()
        return file.activeOverlayCues(positionMs, _ui.value.subtitle.delayMs)
    }

    /**
     * Move the subtitle seat (the strip-drag gesture). Persisted like the other style
     * fields — a viewer who watches with the text higher expects it higher next time too.
     */
    fun setSubtitleBottomFraction(fraction: Float) {
        setSubtitleSettings(_ui.value.subtitle.copy(bottomFraction = fraction))
    }

    /**
     * Jump to the previous/next subtitle line — the strip flick. Overlay files only: the
     * native path has no cue list (`Player.Listener.onCues` gives the current cues, never
     * the neighbours), so a native track says so out loud instead of pretending to jump.
     * The seek honours `seekEnabled`, like every other seek path.
     */
    fun jumpSubtitleLine(next: Boolean) {
        val file = customSubtitleFile
        if (file == null) {
            viewModelScope.launch {
                _events.emit(PlayerEvent.Message("Line jump needs an external subtitle file"))
            }
            return
        }
        val virtual = _ui.value.positionMs - _ui.value.subtitle.delayMs
        val target = if (next) SubtitleLineJump.nextStart(file.cueBoundaries(), virtual)
        else SubtitleLineJump.prevStart(file.cueBoundaries(), virtual)
        if (target == null) {
            viewModelScope.launch {
                _events.emit(
                    PlayerEvent.Message(if (next) "No next subtitle line" else "No previous subtitle line"),
                )
            }
            return
        }
        seekTo(target)
    }

    // ── Sleep timer (fade-out per MX behavior) ───────────────────────────────────

    fun startSleepCountdown(ms: Long) {
        sleepTimer.start(ms)
        _ui.update { it.copy(sleep = sleepTimer.snapshot()) }
    }

    fun sleepAtVideoEnd() {
        sleepTimer.armEndOfVideo()
        _ui.update { it.copy(sleep = sleepTimer.snapshot()) }
    }

    fun cancelSleep() {
        sleepTimer.cancel()
        _ui.update { it.copy(sleep = sleepTimer.snapshot()) }
        applyPlayerVolume() // restore the full mix immediately
    }

    private fun applySleep(tick: SleepTick) {
        _ui.update { it.copy(sleep = tick.state) }
        // The fade multiplies the SAME product as the boost (see [applyPlayerVolume]); two
        // writers of `volume` would each overwrite the other's factors.
        applyPlayerVolume()
        if (tick.shouldPause) {
            controller?.pause()
            viewModelScope.launch { _events.emit(PlayerEvent.Message("Sleep timer — playback paused")) }
            cancelSleep()
        }
    }

    /** END_OF_VIDEO sleep is enforced on the ended signal. */
    private fun handleSleepEndOfVideo() {
        if (_ui.value.sleep.mode == SleepMode.END_OF_VIDEO) {
            cancelSleep()
            viewModelScope.launch { _events.emit(PlayerEvent.Message("Sleep timer — video finished")) }
        }
    }

    /**
     * Series auto-delete (Session 123): a series episode the viewer just
     * finished, whose keep is complete, deletes itself — the same promise the
     * Downloads sweep already keeps for movies, extended to the one case the
     * sweep cannot identify. The sweep matches by title against movie rows
     * only, because one watched episode must never delete the whole season;
     * here the identity is exact instead — this player holds the episode's
     * own cache key, and only a complete keep (`KeepUiState.Stored`) is
     * touched, so a keep still fetching is left alone. Movies keep their
     * sweep and are never touched here; the message names what went, so the
     * deletion is never silent.
     */
    /**
     * What the player just auto-deleted, held so Undo can re-queue it:
     * the handle is the only way back, and the bytes are gone — Undo
     * downloads the episode again from scratch, it does not restore it.
     */
    private data class AutoDeletedEpisode(
        val handle: String,
        val title: String,
        val printLabel: String,
        val resumable: Boolean,
        val postId: Int,
        val episode: Int,
    )

    private var lastAutoDeletedEpisode: AutoDeletedEpisode? = null

    private fun maybeAutoDeleteWatchedEpisode() {
        if (!autoDeleteWatched) return
        val bundle = store.bundle.value ?: return
        val target = bundle.target
        val episode = target.episode.takeIf { it > 0 } ?: store.currentSyncScope()?.episode ?: 0
        if (episode <= 0) return
        if (_ui.value.keep !is KeepUiState.Stored) return
        val engine = CacheEngineHolder.engine ?: return
        val key = store.cacheKey() ?: return
        // Capture the re-keep data BEFORE the delete: the rendition is
        // the episode's own handle, print label and resumable flag, and
        // the title is the title the player is showing for it.
        val rendition = store.activeRendition()
        val handle = rendition?.playback?.streamId?.takeIf { it.isNotBlank() }
        val deleted = handle?.let {
            AutoDeletedEpisode(
                handle = it,
                title = _ui.value.pendingTitle ?: "Episode $episode",
                printLabel = rendition.displayLabel(),
                resumable = rendition.playback.resumable,
                postId = target.id,
                episode = episode,
            )
        }
        viewModelScope.launch {
            runCatching { engine.cancelKeep(key) }
            if (deleted != null) {
                lastAutoDeletedEpisode = deleted
                _events.emit(PlayerEvent.MessageWithUndo("Deleted Episode $episode — you finished watching it."))
            } else {
                _events.emit(PlayerEvent.Message("Deleted Episode $episode — you finished watching it."))
            }
        }
    }

    /**
     * Undo the player's auto-delete of a finished episode: re-queue it
     * from the held handle, so it downloads again from scratch. The
     * notice says what happened; a keep whose handle is gone cannot come
     * back, and there is nothing to undo twice.
     */
    fun undoAutoDeletedEpisode() {
        val deleted = lastAutoDeletedEpisode ?: return
        lastAutoDeletedEpisode = null
        viewModelScope.launch {
            val outcome = runCatching {
                cacheRepository.keep(
                    handle = deleted.handle,
                    title = deleted.title,
                    sizeBytes = null,
                    resumable = deleted.resumable,
                    printLabel = deleted.printLabel,
                    postId = deleted.postId,
                )
            }.getOrNull()
            val message = when (outcome) {
                is KeepOutcome.Enqueued, is KeepOutcome.AlreadyKept ->
                    "Downloading Episode ${deleted.episode} again."
                is KeepOutcome.NoSpace ->
                    "Not enough free space to download Episode ${deleted.episode} again."
                else -> "Could not re-queue Episode ${deleted.episode} — its copy is gone."
            }
            _events.emit(PlayerEvent.Message(message))
        }
    }

    /**
     * Smart Downloads (plan E2): an episode just ended, so the next
     * unwatched episode of the same series can download itself on Wi-Fi.
     * Fire-and-forget and silent on failure — the end card is not the
     * place to report a download that did not start, and the Downloads
     * screen's catch-up pass is the visible one.
     */
    private fun onEpisodeEndedSmartDownloads() {
        val target = store.bundle.value?.target ?: return
        if (target.episode <= 0 || target.id <= 0) return
        viewModelScope.launch {
            runCatching {
                smartDownloads.onEpisodeFinished(
                    postId = target.id,
                    season = target.season,
                    episode = target.episode,
                    workRef = target.workKey,
                )
            }
        }
    }

    // ── Episode progression + offsets ────────────────────────────────────────────

    private fun handleCrossEpisodeAction(action: NextEpisodeAction) {
        store.clearNextEpisode()
        when (action) {
            is NextEpisodeAction.NextBundleRequest -> viewModelScope.launch {
                // The autoplay-next switch (plan §5 step 10, P36) is the promise: with it
                // off, the episode ends on the Next Episode button instead of advancing.
                // The action is already consumed above, so the button re-derives the next
                // episode through requestNextEpisode — nothing here fires twice.
                if (!shouldAutoAdvance(autoplayNext, action)) {
                    _ui.update { it.copy(endedHasNextEpisode = true) }
                    return@launch
                }
                // Session 123 (PL-1): the episode no longer advances the instant it
                // ends. The viewer gets ten seconds and a Cancel first — the advance
                // waits for the countdown to finish.
                startAutoplayCountdown(action)
            }

            is NextEpisodeAction.CalibrationNeeded -> _ui.update {
                it.copy(
                    calibration = CalibrationState(
                        targetEpisode = action.nextEpisode,
                        warning = action.packWarning
                            ?: "Packed file — where does Episode ${action.nextEpisode} start?",
                    ),
                )
            }

            else -> Unit
        }
    }

    /**
     * REA #1: the post-play previews for the title that just ended — the
     * server's related titles (GET /api/v1/related, the same answer Home's
     * "Because you watch" rail reads), merged and capped by
     * [mergePostPlayPreviews]. A failed fetch is an empty row, never a
     * placeholder: the load is best-effort, the end card is not. Cancelled
     * and re-run on every end, so a replay never wears the last title's tiles.
     */
    private var postPlayPreviewsJob: Job? = null

    private fun loadPostPlayPreviews() {
        postPlayPreviewsJob?.cancel()
        postPlayPreviewsJob = viewModelScope.launch {
            val seedPostId = store.bundle.value?.target?.id?.takeIf { it > 0 }
                ?: store.currentSyncScope()?.postId?.takeIf { it > 0 }
            val similar = if (seedPostId != null) {
                runCatching {
                    catalogRepository.getRelated(workId = seedPostId, page = 1, limit = 6)
                }.getOrNull()?.results.orEmpty()
            } else {
                emptyList()
            }
            _ui.update { it.copy(postPlayPreviews = mergePostPlayPreviews(similar)) }
        }
    }

    /** REA #1: a post-play preview tile was tapped — open that title's Detail. */
    fun openPost(postId: Int) {
        viewModelScope.launch { _events.emit(PlayerEvent.OpenPost(postId)) }
    }

    /**
     * The autoplay-next countdown (Session 123, PL-1).
     *
     * The state is the pure [AutoplayCountdown] ticking once a second; the
     * advance it fires is exactly the path that used to run the instant the
     * episode ended — the between-episodes break decision, then the advance —
     * only delayed by ten seconds. Cancelling the job (the viewer's Cancel, a
     * manual Next, a new bundle, or the ViewModel dying) drops the held action
     * without firing it: a countdown that never reaches zero advances nothing.
     */
    private fun startAutoplayCountdown(action: NextEpisodeAction.NextBundleRequest) {
        clearAutoplayCountdown()
        pendingAutoplayAction = action
        autoplayCountdownJob = viewModelScope.launch {
            var countdown = AutoplayCountdown.start()
            while (true) {
                _ui.update { it.copy(autoplayCountdownSeconds = countdown.secondsLeft) }
                delay(1_000L)
                countdown = countdown.tick() ?: break
            }
            _ui.update { it.copy(autoplayCountdownSeconds = null) }
            val held = pendingAutoplayAction
            pendingAutoplayAction = null
            if (held == null) return@launch
            // Session 126 (PL-2): the countdown used to ask the sponsor seam here and
            // hold the advance behind a fake ad. There is no ad to show and no network
            // to fill one, so the advance runs now, exactly as the viewer expected.
            advanceCrossEpisode(held)
        }
    }

    /** The countdown's Cancel: stay on the ended episode and offer the buttons instead. */
    fun cancelAutoplayCountdown() {
        clearAutoplayCountdown()
        _ui.update { it.copy(autoplayCountdownSeconds = null, endedHasNextEpisode = true) }
    }

    /** Drops the countdown and its held action without firing either. */
    private fun clearAutoplayCountdown() {
        autoplayCountdownJob?.cancel()
        autoplayCountdownJob = null
        pendingAutoplayAction = null
        _ui.update { it.copy(autoplayCountdownSeconds = null) }
    }

    /**
     * The successor hand-off itself: the prepared bundle opened at 85% wins, then a cold resolve,
     * and only then the app route.
     */
    private suspend fun advanceCrossEpisode(action: NextEpisodeAction.NextBundleRequest) {
        // The prepared successor (resolved at 85%) opens without a catalog lookup;
        // a cold END still resolves on demand before falling back to the app route.
        if (openNextEpisode(action.season, action.nextEpisode, action.postId)) return
        _events.emit(
            PlayerEvent.PlayNextEpisode(
                workKey = action.workKey,
                postId = action.postId,
                season = action.season,
                episode = action.nextEpisode,
            ),
        )
    }

    /** "Mark current position as Episode N start" — calibrates the pack offset. */
    fun calibrateEpisodeStart(episodeNumber: Int) {
        val bundle = store.bundle.value ?: return
        val rendition = store.activeRendition() ?: return
        val position = controller?.currentPosition ?: _ui.value.positionMs
        val report = EpisodeOffsets.buildReport(
            target = bundle.target,
            rendition = rendition,
            episodeNumber = episodeNumber,
            offsetMs = position,
            chapterTitle = null,
        )
        if (report == null) {
            viewModelScope.launch {
                _events.emit(PlayerEvent.Message("This link has no stable work identity — offset not reported"))
            }
            return
        }
        store.learnedOffsets.learn(report, store.offsetReporter)
        _ui.update { it.copy(calibration = null) }
        // The server post (Session 123): the catalog's own episode id rides on
        // the coverage's enumerated episodes. When it resolves, the offset is
        // shared with every later viewer at once; when it does not (an older
        // bundle without episode ids), the local learned offset still stands
        // and the deferred reporter keeps the report queued for the server.
        val episodeId = rendition.coverage.episodes
            .firstOrNull { it.number == episodeNumber }
            ?.episodeId
        viewModelScope.launch {
            val shared = episodeId?.let {
                catalogRepository.reportEpisodeOffset(
                    episodeId = it,
                    offsetSeconds = report.offsetSeconds,
                    chapterTitle = report.chapterTitle,
                    releaseLabel = report.releaseLabel,
                )
            } == true
            val queued = !shared && store.offsetReporter is DeferredOffsetReporter
            _events.emit(
                PlayerEvent.Message(
                    "Episode $episodeNumber marked at ${(position / 1000L).formatTime()}" +
                        (if (shared) " (shared with the server)" else if (queued) " (queued for server)" else ""),
                ),
            )
        }
    }

    /** Open the calibration dialog manually (menu path) for the current target episode. */
    fun openCalibration() {
        val bundle = store.bundle.value
        val current = bundle?.target?.episode?.takeIf { it > 0 } ?: 1
        _ui.update {
            it.copy(
                calibration = CalibrationState(
                    targetEpisode = current,
                    warning = "Scrub/pause exactly where this episode starts in the packed " +
                        "file, then mark it. Offsets feed the shared cat_offsets ledger.",
                ),
            )
        }
    }

    fun dismissCalibration() {
        _ui.update { it.copy(calibration = null) }
    }

    // ── Signature feature: watching → keeping ────────────────────────────────────

    /**
     * Re-attaches the cache observer to the CURRENT rendition (called on every switch).
     *
     * With the operator's downloads switch off the chip is removed rather than left clickable:
     * [KeepUiState.Unavailable] is the state this screen already renders as "no keep here".
     */
    fun refreshKeepState() {
        keepJob?.cancel()
        latestSnapshot = null
        // Player review D3: keepRequested is the user-tap flag KeepChip gates
        // on. A new title, episode or rendition has had no tap yet — a stale
        // true from the previous title would un-silence its automatic
        // ahead-window keep. (A rendition Auto switches to mid-keep returns
        // to today's behaviour for that rendition: the adopt continues
        // engine-side and Downloads shows it.)
        _ui.update { it.copy(keepRequested = false) }
        val engine = CacheEngineHolder.engine
        val key = store.cacheKey()
        if (engine == null || key == null || !downloadsAllowed ||
            store.bundle.value?.target?.kind == "local"
        ) {
            _ui.update { it.copy(keep = KeepUiState.Unavailable, keepRequested = false) }
            return
        }
        keepJob = viewModelScope.launch {
            engine.observe(key).collect { snapshot ->
                latestSnapshot = snapshot
                _ui.update { it.copy(keep = CacheAffordance.uiStateFor(snapshot, it.keepRequested)) }
            }
        }
    }

    private var latestSnapshot: CacheSnapshot? = null

    /** The *Keep this* button. Hands engine position + already-fetched bytes, never restarts. */
    fun keepCurrent() {
        // PL-5: a local file is already on this device — there is nothing to keep, and
        // the tap says so instead of offering a download of a file that is already here.
        if (store.bundle.value?.target?.kind == "local") {
            viewModelScope.launch {
                _events.emit(PlayerEvent.Message("Already on this device"))
            }
            return
        }
        if (!downloadsAllowed) {
            viewModelScope.launch {
                _events.emit(PlayerEvent.Message("Keeping files for offline is switched off for this server"))
            }
            return
        }
        // Session 123: the same Wi-Fi-only gate the detail screen's Keep
        // honours. A hundreds-of-megabytes download starting on mobile data
        // because the viewer tapped Keep mid-playback is the expensive
        // mistake the switch exists to prevent — the message says where the
        // switch lives, so the tap is never left guessing.
        if (DataSaver.wifiOnlyBlocks(wifiOnlyDownloads, connectivityProbe.current() == NetworkClass.WIFI)) {
            viewModelScope.launch {
                _events.emit(PlayerEvent.Message("Wi-Fi only is on — connect to Wi-Fi to download this title. You can change it in Settings."))
            }
            return
        }
        val engine = CacheEngineHolder.engine
        val key = store.cacheKey()
        val c = controller
        if (engine == null || key == null) {
            viewModelScope.launch {
                _events.emit(PlayerEvent.Message("Download engine not wired into this build yet"))
            }
            return
        }
        val rendition = store.activeRendition() ?: return
        _ui.update { it.copy(keepRequested = true) }
        viewModelScope.launch {
            val request = CacheAffordance.buildAdoptRequest(
                key = key,
                snapshot = latestSnapshot,
                positionMs = c?.currentPosition ?: _ui.value.positionMs,
                bufferedMs = c?.bufferedPosition ?: _ui.value.positionMs,
                durationMs = _ui.value.durationMs,
                expectedTotalBytes = rendition.print.sizeBytes.takeIf { it > 0 },
                resumable = rendition.playback.resumable || rendition.playback.acceptsRanges == true,
                printLabel = rendition.displayLabel(),
                title = _ui.value.pendingTitle ?: "",
                // The staged sync scope carries this work's post id (Detail
                // staged it at play time); without it the keep can never be
                // matched by the Downloads auto-delete sweep, so it is kept.
                postId = store.currentSyncScope()?.postId ?: 0,
            )
            val result = try {
                engine.adopt(request)
            } catch (t: Throwable) {
                KeepResult.Failed(t.message ?: "engine error")
            }
            _ui.update { it.copy(keep = keepUiFor(result), keepRequested = result is KeepResult.Stored) }
            _events.emit(PlayerEvent.Message(keepMessage(result)))
        }
    }

    private fun keepUiFor(result: KeepResult): KeepUiState = when (result) {
        is KeepResult.Stored -> KeepUiState.Stored(result.playableOffline, null)
        is KeepResult.OnlyPlayableInApp -> KeepUiState.Blocked(
            CacheState.APP_PRIVATE_ONLY,
            // A null window is the pinned answer the tap above produced — no auto-delete — so it
            // gets the words that are true for it. "TTL unknown" would describe a missing number
            // with the vocabulary of a promise kept forever.
            "Kept — playable in ${Brand.shortName} only" +
                (result.ttlSeconds?.let { " (${it / 3600} h)" } ?: " until you delete it"),
        )
        is KeepResult.StorageFull -> KeepUiState.Blocked(
            CacheState.STORAGE_FULL,
            "Storage full: need ${CacheAffordance.formatBytes(result.requiredBytes)}, " +
                "${CacheAffordance.formatBytes(result.availableBytes)} free",
        )
        is KeepResult.Interrupted -> KeepUiState.Blocked(
            CacheState.INTERRUPTED,
            "Interrupted at ${CacheAffordance.formatBytes(result.fetchedBytes)}" +
                (if (result.willAutoResume) " — resuming" else ""),
        )
        is KeepResult.Failed -> KeepUiState.Blocked(CacheState.FAILED, result.message)
        KeepResult.EngineNotWired -> KeepUiState.Unavailable
    }

    private fun keepMessage(result: KeepResult): String = when (result) {
        is KeepResult.Stored -> "Kept for offline"
        is KeepResult.OnlyPlayableInApp -> "Kept — file is app-private (playable here only)"
        is KeepResult.StorageFull -> "Storage full — free up space and keep again"
        is KeepResult.Interrupted -> "Keep interrupted — will resume"
        is KeepResult.Failed -> "Keep failed: ${result.message}"
        KeepResult.EngineNotWired -> "Keep engine not available in this build"
    }

    // ── GiveUp / external handoff helpers for the screen ────────────────────────

    fun openWithExternalPlayer() {
        val url = store.activeRendition()?.playback?.playUrl ?: return
        viewModelScope.launch { _events.emit(PlayerEvent.OpenExternalPlayer(url)) }
    }

    /** User dismissed the giveUp card. */
    fun dismissGiveUp() {
        store.clearGaveUp()
    }

    fun dismissSettledBanner() {
        store.clearSettledBanner()
        _ui.update { it.copy(settledBanner = null) }
    }

    override fun onCleared() {
        rememberResume()
        tickerJob?.cancel()
        keepJob?.cancel()
        settingsJob?.cancel()
        bingeResolveJob?.cancel()
        bingePrefetchJob?.cancel()
        _player.value = null
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        super.onCleared()
    }

    companion object {
        const val RESUME_PROMPT_THRESHOLD_MS = 10_000L
        const val MAX_SUBTITLE_BYTES = 4_000_000

        /** At most one local history write per this interval; a forced stop always writes. */
        const val HISTORY_WRITE_INTERVAL_MS = 15_000L

        /** REA #3: a slider drag saves its sync offsets once, not once per frame. */
        const val SYNC_PREFS_SAVE_DEBOUNCE_MS = 500L
    }
}

/** Long → "h:mm:ss" / "m:ss" — top-level so both ViewModel and UI can reuse it. */
fun Long.formatTime(): String {
    val totalSeconds = this / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/** What the screen must route elsewhere (next-episode bundle, external hand-off). */
sealed class PlayerEvent {
    data class Message(val text: String) : PlayerEvent()
    /**
     * Like [Message], but the screen offers Undo: the viewer just lost a
     * download they may still want. Smart Downloads' auto-delete of a
     * finished episode is the only sender today.
     */
    data class MessageWithUndo(val text: String) : PlayerEvent()
    data class PlayNextEpisode(val workKey: String?, val postId: Int?, val season: Int, val episode: Int) : PlayerEvent()
    data class OpenExternalPlayer(val url: String) : PlayerEvent()
    /** SC.24: share-a-moment text for the system share sheet. */
    /**
     * REA #1: a post-play preview tile was tapped — open that title's
     * Detail. The screen owns the navigation; the VM only names the post.
     */
    data class OpenPost(val postId: Int) : PlayerEvent()
    data class ShareMoment(val text: String) : PlayerEvent()
    /**
     * MX #7 — Kids Lock: the unlock FAB was tapped while kids mode is on.
     * The screen raises the PIN pad on this; the PIN's success unlocks
     * through [PlayerViewModel.unlockWithKidsPin].
     */
    data object RequestKidsPin : PlayerEvent()
}

@OptIn(UnstableApi::class)
internal object TrackMapper {

    /** Rows for the audio/text selection sheets, rebuilt from the controller's [Tracks]. */
    fun options(tracks: Tracks, trackType: Int): List<TrackUiOption> {
        val out = ArrayList<TrackUiOption>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != trackType) return@forEachIndexed
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val fallback = if (trackType == C.TRACK_TYPE_AUDIO) "Audio ${i + 1}" else "Subtitle ${i + 1}"
                out.add(
                    TrackUiOption(
                        id = "g$groupIndex-t$i",
                        group = group.mediaTrackGroup,
                        trackIndex = i,
                        // A track's own label, else its language, else the
                        // numbered fallback — but never a placeholder: a muxer
                        // writes "und" for an unrecorded language, and a bare
                        // "und" as the row's label is the same leak as the
                        // catalog's "Unknown".
                        label = AudioTrackMatcher.displayableLanguageTag(format.label?.toString())
                            ?: AudioTrackMatcher.displayableLanguageTag(format.language)
                            ?: fallback,
                        language = format.language,
                        supported = group.isTrackSupported(i),
                        selected = group.isTrackSelected(i),
                    ),
                )
            }
        }
        return out
    }

    /** Selected video fps for frame-stepping math; null when unknown (Format.frameRate field). */
    fun selectedVideoFrameRate(tracks: Tracks): Float? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    val rate = group.getTrackFormat(i).frameRate
                    if (rate > 0f) return rate
                }
            }
        }
        return null
    }
}

/**
 * The language a live track pick writes back to Settings, or null when the pick
 * says nothing about the standing preference. A picked track's language is the
 * viewer's choice for this show and the next visit; turning subtitles off is
 * viewing-scoped — it must not delete the stored language, or the next episode
 * would start silent for a viewer who wants Hindi. A track with no language tag
 * has nothing to persist either.
 *
 * Top-level (not a ViewModel member) so the unit test can call it directly — the
 * ViewModel's eight injected deps have no fakes anywhere in the repo.
 */
internal fun persistedLanguageFor(option: TrackUiOption?): String? =
    option?.language?.trim()?.takeIf { it.isNotEmpty() }

/**
 * What one `/api/v1/stream` lookup established: the body if there was one, the status if the
 * transport answered, and [noAnswer] when nothing came back at all. See
 * `viewmodel/AdultPolicy.serverAnswerFor` for what each combination is allowed to conclude.
 */
data class HandleProbe(
    val response: com.raddstream.app.network.models.StreamResponse?,
    val httpStatus: Int?,
    val bodyClaimsSuccess: Boolean?,
    val noAnswer: Boolean,
) {
    val answer: AdultPolicy.ServerAnswer
        get() = AdultPolicy.serverAnswerFor(httpStatus, response?.success ?: bodyClaimsSuccess, noAnswer)
}

/** The only wording a refused successor may get. No title, no handle, no upstream address. */
private const val NEXT_UNAVAILABLE = "The next episode is no longer offered by this server."
