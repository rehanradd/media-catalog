package com.raddstream.app.ui.screens

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.raddstream.app.R
import com.raddstream.app.network.models.Post
import com.raddstream.app.player.AspectMode
import com.raddstream.app.player.CacheAffordance
import com.raddstream.app.player.initialBrightnessBase
import com.raddstream.app.player.GestureMapper
import com.raddstream.app.player.HandoffWhy
import com.raddstream.app.player.looksLikeQualityLabel
import com.raddstream.app.player.PlayerOrientation
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.player.showInPlaybackNext
import com.raddstream.app.player.bufferingPercent
import com.raddstream.app.player.ScrubThumbnails
import com.raddstream.app.player.SpriteThumbnailCache
import com.raddstream.app.player.requestSpriteCapture
import com.raddstream.app.player.StartDeadline
import com.raddstream.app.player.StartDeadlineStage
import com.raddstream.app.player.StartPhase
import com.raddstream.app.player.SubtitleColor
import com.raddstream.app.player.SubtitleEdge
import com.raddstream.app.player.SubtitleFont
import com.raddstream.app.player.SubtitleUiSettings
import com.raddstream.app.player.subtitles.SyncOffsetSheet
import com.raddstream.app.ui.components.CatalogImage
import com.raddstream.app.ui.components.PinPadDialog
import com.raddstream.app.ui.components.rememberReduceMotion
import com.raddstream.app.ui.theme.RaddCaptionPlateArgb
import com.raddstream.app.ui.theme.RaddCurrent500
import com.raddstream.app.ui.theme.RaddMotion
import com.raddstream.app.ui.theme.PlayerChromeTheme
import com.raddstream.app.ui.theme.RaddNarrationCheck
import com.raddstream.app.ui.theme.RaddNarrationPlate
import com.raddstream.app.ui.theme.RaddPlayerSnackbarPlate
import com.raddstream.app.ui.theme.RaddPlayerStage
import com.raddstream.app.ui.theme.brandSp
import com.raddstream.app.ui.theme.rememberPosterLuminance
import com.raddstream.app.ui.theme.scrimScaleFor
import com.raddstream.app.viewmodel.KidsLockViewModel
import com.raddstream.app.viewmodel.KidsPinOutcome
import com.raddstream.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The MX-style player screen (PLAYER_SPEC.md §2). Decomposed into small composables so every
 * zone is readable in isolation:
 *
 *  - [PlayerSurface] — Media3 `PlayerView` with `useController=false`; aspect + subtitle
 *    styling only (all chrome is Compose).
 *  - [GestureOverlay] — the researched gesture table (PLAYER_SPEC §2.1), decisions delegated
 *    to the pure `GestureMapper`; tap / boost-release / drag / pinch live in independent
 *    `pointerInput` lanes so they never fight over events.
 *  - [TopBar] / [CenterSpinner] / [BottomControls] — three-zone fading chrome with the MX
 *    bottom button row (subtitles, audio, speed, lock, ±10s, frame-step, next episode,
 *    sleep, aspect, rotate, audio-only, stereo-select, cinema, PiP).
 *  - [EdgeHud] — what the same three zones collapse to in Ghost mode
 *    ([com.raddstream.app.player.ViewMode.PURE_CINEMA]): no pause scrim, no centre button,
 *    only edge chips and a 2dp progress line.
 *  - Sheets/dialogs — speed, sleep timer (fade-out), subtitle settings (sync applies to
 *    both render paths), tracks, prints + failover log, resume prompt, pack calibration,
 *    play-from, give-up card with the four §5 handoff reasons.
 *  - [SubtitleOverlay] — custom-rendered external SRT/VTT (delay read straight from the
 *    settings); embedded and Media3-parsed tracks get the same delay from the service's
 *    decoder wrapper.
 *  - Keep status — the signature "watching → keeping" affordance, fed by
 *    `ProgressiveCacheEngine` snapshots through [CacheAffordance], rides
 *    the scrubber/time row ([KeepTimeRowStatus]) instead of its own row
 *    (P1-16, audit B-21).
 *
 * Entry points: the `streamId`/`title` route the NavGraph uses (an opaque handle of ours,
 * never an upstream address), plus a title-only overload for bundle-driven navigation.
 */
@Composable
fun PlayerScreen(
    streamId: String,
    title: String,
    onBackClick: () -> Unit,
    onPlayNextEpisode: (workKey: String?, postId: Int?, season: Int, episode: Int) -> Unit = { _, _, _, _ -> },
    /** REA #1: a post-play preview tile was tapped — open that title's Detail. */
    onOpenPost: (Int) -> Unit = {},
    localUri: String? = null,
    // SC §3 (Session 126): tapping an actor on the pause strip searches them, the
    // same route the detail screen's cast chips take. NAV-4: the expired-link
    // card's Home button.
    onActorClick: (String) -> Unit = {},
    onHomeClick: (() -> Unit)? = null,
    /**
     * REA #4 (Netflix, Nov 2026): the give-up card's "Play Something" —
     * a weighted random pick from the "For you" pool, opened and started
     * at once. The screen picks through the ViewModel at tap time; a null
     * pick (no row yet) means the card never offers the button.
     */
    onPlaySomethingClick: (Post) -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(streamId, localUri) {
        if (!localUri.isNullOrBlank()) {
            viewModel.startLocalUri(localUri, title)
        } else if (streamId.isNotBlank()) {
            viewModel.startStreamHandle(streamId, title)
        }
    }
    PlayerScreenContent(state, viewModel, onBackClick, onPlayNextEpisode, onActorClick, onHomeClick, onOpenPost = onOpenPost, onPlaySomethingClick = onPlaySomethingClick)
}

/** Bundle-driven entry (PlayBundle already installed into the session store by the caller). */
@Composable
fun PlayerScreen(
    title: String,
    onBackClick: () -> Unit,
    onPlayNextEpisode: (workKey: String?, postId: Int?, season: Int, episode: Int) -> Unit = { _, _, _, _ -> },
    /** REA #1: a post-play preview tile was tapped — open that title's Detail. */
    onOpenPost: (Int) -> Unit = {},
    onActorClick: (String) -> Unit = {},
    onHomeClick: (() -> Unit)? = null,
    /** REA #4: the give-up card's "Play Something" — see the other overload. */
    onPlaySomethingClick: (Post) -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    PlayerScreenContent(state, viewModel, onBackClick, onPlayNextEpisode, onActorClick, onHomeClick, onOpenPost = onOpenPost, onPlaySomethingClick = onPlaySomethingClick)
}

// ── Root ───────────────────────────────────────────────────────────────────────

@androidx.annotation.OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreenContent(
    state: PlayerUiState,
    vm: PlayerViewModel,
    onBackClick: () -> Unit,
    onPlayNextEpisode: (workKey: String?, postId: Int?, season: Int, episode: Int) -> Unit,
    onActorClick: (String) -> Unit = {},
    onHomeClick: (() -> Unit)? = null,
    /** REA #1: a post-play preview tile was tapped — open that title's Detail. */
    onOpenPost: (Int) -> Unit = {},
    /** REA #4: the give-up card's "Play Something" — see [PlayerScreen]. */
    onPlaySomethingClick: (Post) -> Unit = {},
    /** MX #7 — Kids Lock: the PIN's owner for the unlock pad below. */
    kidsLockViewModel: KidsLockViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    // Library bump 2026-11-23 (Compose 1.9 lint LocalContextGetResourceValueCall):
    // these two strings are read inside the PIN-pad callback below, where
    // stringResource() cannot run — resolve them here, at composition time.
    val kidsPinWrong = stringResource(R.string.kids_pin_wrong)
    val kidsPinIncomplete = stringResource(R.string.kids_pin_incomplete)
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findActivity() }
    // MX #7 — Kids Lock: the unlock pad, raised when the VM answers an
    // unlock tap in kids mode with RequestKidsPin. The error and lockout
    // lines ride the dialog's own params, as the Settings gate does.
    var kidsPinRequested by remember { mutableStateOf(false) }
    var kidsPinError by remember { mutableStateOf<String?>(null) }
    val kidsScope = rememberCoroutineScope()
    // The chrome theme this device wears: resolved once here and handed to the
    // transient overlays that take no state of their own; the stateful chrome
    // pieces resolve it from `state.playerTheme` themselves.
    val chrome = PlayerChromeTheme.forId(state.playerTheme)
    // P3 (round 2): the staged poster's luminance drives the chrome scrims —
    // bright art gets a stronger scrim, dark art a lighter one. Null (no
    // poster, or not loaded yet) is 1.0x: the long-standing scrims.
    val scrimScale = scrimScaleFor(rememberPosterLuminance(state.posterUrl))

    // BM rule 9: low-end devices get solid chrome, not frosted glass — the
    // blur is a cost they pay for nothing, and a solid plate reads the same.
    val lowRam = remember(context) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    }

    // System Back always leaves the player (Session 117): the give-up card's
    // AlertDialog declines Back while it is showing (PL-13: an outside tap is
    // keep-waiting, Back is exit), and the top-left arrow always worked, but
    // nothing handled Back during playback — the 114 device retest pressed it
    // three times on a stuck player and once while the card was up, and the
    // player never exited. This handler is the fallback underneath the dialog's
    // own: it is registered first, so a dialog or sheet that is showing wins
    // the press (its handler was added later and both end in the same place —
    // back to details), and with
    // nothing showing, Back pops the player instead of doing nothing. The
    // lock does not gate it: a locked player must still let its viewer out.
    BackHandler { onBackClick() }

    var controlsVisible by remember { mutableStateOf(true) }
    var interactTick by remember { mutableStateOf(0L) }
    var brightness by remember { mutableFloatStateOf(-1f) } // -1 → follow system
    var hud by remember { mutableStateOf<Hud?>(null) }
    var snackbar by remember { mutableStateOf<PlayerSnackbar?>(null) }
    var scrubPreviewMs by remember { mutableStateOf<Long?>(null) }

    // Scrub thumbnails (P2-5, B-23): a sprite sheet built from the frames
    // the main player already decoded — no second ExoPlayer. The sheet
    // fills one bucket per capture while the title plays; the toast shows
    // the target bucket's cell, or time only when it was never captured.
    // Released when this screen leaves; reset when the rendition changes
    // (see the LaunchedEffect below).
    val spriteCache = remember { SpriteThumbnailCache() }
    DisposableEffect(spriteCache) {
        onDispose { spriteCache.release() }
    }
    // The PlayerView behind the surface the captures copy from. Set by
    // PlayerSurface's factory; null until the view exists (audio-only
    // never sets it, so nothing is ever captured there).
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var spriteCaptureInFlight by remember { mutableStateOf(false) }
    // The sprite frame for the scrub target, if its bucket was captured.
    // Keyed on the cache version so a toast already showing time-only
    // gains its picture the moment the cell lands.
    val spriteFrame = remember(scrubPreviewMs, spriteCache.version) {
        scrubPreviewMs?.let { spriteCache.frameFor(it, state.durationMs) }
    }

    // PL-10: the PiP window follows the video's own shape, and the source rect is
    // the surface's own bounds — the window grows out of the picture, not the corner.
    var pipSourceRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val launchPip = rememberPipLauncher(aspectRatio = state.videoAspectRatio, sourceRect = pipSourceRect)
    PlayerEventWatcher(
        vm, context, onPlayNextEpisode,
        onMessage = { snackbar = PlayerSnackbar(it) },
        onMessageWithUndo = { snackbar = PlayerSnackbar(it, showUndo = true) },
        onOpenPost = onOpenPost,
        // MX #7 — Kids Lock: the VM's answer to an unlock tap in kids mode.
        onRequestKidsPin = { kidsPinRequested = true },
    )

    // P2-5 (B-23): auto-enter PiP is ON by default for VOD — when the
    // switch is on and the title is playing, leaving the player (home,
    // recents, another app) keeps it playing in a window. The viewer can
    // turn it off in Settings → Player, and the system setting still wins:
    // if PiP is off for this app in the system settings, the auto-enter
    // silently skips — no toast on the way out the door (the manual PiP
    // button still attempts and toasts on refusal). Live never auto-enters:
    // a live window is not a title the viewer is mid-way through. Already
    // in a window: entering PiP pauses the activity, which would re-fire
    // this observer — skip when already in PiP.
    // The screen is always hosted by the Activity, so its context is the owner;
    // lifecycle-runtime-compose is not a dependency, so no LocalLifecycleOwner.
    val lifecycleOwner = context as LifecycleOwner
    DisposableEffect(lifecycleOwner, state.autoPip, state.isPlaying, state.isLive) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && state.autoPip && state.isPlaying && !state.isLive &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                isPipAllowedBySystem(context) && activity?.isInPictureInPictureMode != true
            ) {
                launchPip()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Immersive fullscreen + keep-screen-on while the player exists (MX behaviour).
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val insets = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        insets?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        val originalBrightness = window?.attributes?.screenBrightness
        // H18 (user decision 04:42 PKT 20 Nov): playback entry respects
        // the phone's held orientation instead of forcing landscape — a
        // viewer holding the phone portrait opens portrait; landscape
        // stays available through the rotate control. The sensor request
        // follows how the device is held right now, and the state is
        // synced to it here so the orientation effect below applies the
        // held orientation on its first run instead of overwriting the
        // sensor request with the stale LANDSCAPE default. (Session 157
        // S4 forced landscape because a sensor on a fixed/flat/slow
        // device could hold the player portrait while a landscape picture
        // played sideways; the owner decided the held orientation wins.)
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        vm.setOrientation(
            if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                PlayerOrientation.PORTRAIT
            } else {
                PlayerOrientation.LANDSCAPE
            },
        )
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insets?.show(WindowInsetsCompat.Type.systemBars())
            window?.let {
                val attrs = it.attributes
                attrs.screenBrightness =
                    originalBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                it.attributes = attrs
            }
            // Session 158 (S5): UNSPECIFIED alone does not snap the app
            // back on a device whose rotation is locked (a cloud device,
            // or a phone with auto-rotate off) — after exiting the Salaar
            // player the detail stayed landscape while the device frame
            // was portrait, until the tester rotated the device. Ask the
            // sensor once, so the app follows how the device is actually
            // held right now, then release back to neutral a second later
            // so the rest of the app is not sensor-driven forever. The
            // delayed post survives the composition (it is tied to the
            // decor view, not the composable) and is a no-op on a
            // finishing activity.
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            activity?.window?.decorView?.postDelayed(
                { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED },
                1_000L,
            )
        }
    }

    // Fullscreen toggle: immersive (bars hidden) ↔ normal (bars shown). Entry hides
    // them in the DisposableEffect above; this effect applies every later flip, and
    // the dispose step still restores the bars when the player leaves.
    LaunchedEffect(state.fullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        if (state.fullscreen) insets.hide(WindowInsetsCompat.Type.systemBars())
        else insets.show(WindowInsetsCompat.Type.systemBars())
    }

    // Brightness override from the left-half vertical gesture.
    LaunchedEffect(brightness) {
        activity?.let { a ->
            val attrs = a.window.attributes
            attrs.screenBrightness = brightness.coerceIn(-1f, 1f)
            a.window.attributes = attrs
        }
    }

    // Manual orientation (rotate button). configChanges on MainActivity keeps playback alive.
    // H18: the entry effect above already synced the state to the held
    // orientation, so the first composition applies it; later flips are
    // the viewer's explicit choice. The landscape branch is forced, the
    // portrait branch keeps the sensor, so an explicit flip to portrait
    // still follows how the viewer holds the device.
    LaunchedEffect(state.orientation) {
        activity?.requestedOrientation = when (state.orientation) {
            PlayerOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            PlayerOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    // Auto-hide the chrome 3.0 s into uninterrupted playback.
    LaunchedEffect(controlsVisible, state.isPlaying, interactTick, state.dialogBlocking) {
        if (controlsVisible && state.isPlaying && !state.dialogBlocking) {
            delay(3000)
            if (state.isPlaying) controlsVisible = false
        }
    }

    // Double-tap ripple lifetime (nonce-keyed).
    LaunchedEffect(state.ripple?.nonce) {
        if (state.ripple != null) {
            delay(900)
            vm.clearRipple()
        }
    }

    // HUD + settled banner fades.
    LaunchedEffect(hud) {
        if (hud != null) {
            delay(CinemaChrome.HUD_LIFETIME_MS)
            hud = null
        }
    }
    // Resume chip (redesign): the offer auto-resolves to Resume after 3 s —
    // playback already waits on the answer, so a viewer who does nothing is
    // a viewer who wants to keep watching from where they stopped.
    // resolveResume is idempotent, so a tap and the timer cannot double-fire.
    LaunchedEffect(state.resumeOfferMs) {
        if (state.resumeOfferMs != null) {
            delay(3000)
            vm.resolveResume(true)
        }
    }
    LaunchedEffect(snackbar) {
        if (snackbar != null) {
            // An Undoable notice stays up longer — three seconds is not
            // enough to read it, find Undo, and decide.
            delay(if (snackbar?.showUndo == true) 8000 else 3000)
            snackbar = null
        }
    }
    LaunchedEffect(state.settledBanner) {
        if (state.settledBanner != null) {
            delay(5000)
            vm.dismissSettledBanner()
        }
    }

    // Scrub thumbnails (P2-5, B-23): the sheet fills while the title plays.
    // Every position tick that lands in an unfilled bucket copies the main
    // surface's current frame into that bucket — one capture per bucket, at
    // most one in flight, only while playing a VOD title with a known
    // duration. The Scrubber (bottom bar) does not come through here — it
    // seeks the main player directly, so the picture itself is already the
    // preview and no thumbnail is owed. A rendition change (quality switch,
    // next episode) drops the sheet: the frames belong to the old picture.
    LaunchedEffect(state.activeRendition) {
        spriteCache.reset()
    }
    LaunchedEffect(state.positionMs, state.isPlaying, state.isLive, state.durationMs, playerView) {
        if (!state.isPlaying || state.isLive || state.durationMs <= 0L) return@LaunchedEffect
        if (!ScrubThumbnails.isSupported()) return@LaunchedEffect
        val bucket = ScrubThumbnails.bucketIndex(state.positionMs, state.durationMs)
        if (bucket < 0 || spriteCache.isFilled(bucket) || spriteCaptureInFlight) return@LaunchedEffect
        // getVideoSurfaceView() returns a plain View in current Media3 —
        // the surface view is a SurfaceView by default, so cast; any other
        // surface type silently skips the capture.
        val surface = (playerView?.videoSurfaceView as? SurfaceView)?.holder?.surface?.takeIf { it.isValid }
            ?: return@LaunchedEffect
        spriteCaptureInFlight = true
        requestSpriteCapture(surface) { frame ->
            spriteCache.store(bucket, frame)
            spriteCaptureInFlight = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(chrome.background),
    ) {
        // 1 — video surface or audio-only placeholder
        if (state.audioOnly) {
            AudioOnlySurface(
                title = state.pendingTitle ?: state.activeRendition?.displayLabel() ?: stringResource(R.string.player_audio_title),
                isPlaying = state.isPlaying,
            )
        } else {
            PlayerSurface(vm, state, onBounds = { pipSourceRect = it }, onPlayerView = { playerView = it })
        }

        // D-35: the poster's blurred shadow behind the loading state. The window before
        // the first frame is the one a viewer cannot tell apart from a crash, and a black
        // void is the worst thing to show in it — so the staged poster sits dimmed and
        // blurred behind the notice card, with the real progress ring on top. (Blur is a
        // no-op below API 31; the scrim carries the dimming there.) It stands down the
        // moment the picture starts, and a deep link with no staged poster shows none.
        if (state.startPhase != StartPhase.NONE && !state.posterUrl.isNullOrBlank()) {
            Box(Modifier.fillMaxSize()) {
                CatalogImage(
                    url = state.posterUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(24.dp),
                    contentScale = ContentScale.Crop,
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            }
        }

        // 2 — our own subtitle overlay (external SRT/VTT/ASS with delay)
        SubtitleOverlay(state, vm)

        // Review fix (16 Nov): a paused picture sits behind a 30% scrim so the
        // frozen frame reads as paused, not stuck. Above the surface but below
        // the gesture plane, so taps still reach the gestures and the chrome
        // above it stays bright. Not during a start, while buffering, or
        // after the end card — those states narrate themselves.
        if (!state.isPlaying && state.startPhase == StartPhase.NONE && !state.isBuffering && !state.isEnded) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
        }

        // 3 — gesture plane (single full-screen layer, lanes split by pointerInput)
        GestureOverlay(
            state = state,
            vm = vm,
            onToggleControls = {
                controlsVisible = !controlsVisible
                interactTick = System.nanoTime()
            },
            onBrightness = { frac ->
                if (brightness < 0f) {
                    // PL-9: the drag starts where the screen already is — the window's
                    // own brightness first, then the system brightness — never an
                    // assumed 0.5 that jumps a dim screen to half brightness on touch.
                    val windowBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
                    val system01 = runCatching {
                        Settings.System.getInt(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            -1,
                        ).takeIf { it >= 0 }?.div(255f)
                    }.getOrNull()
                    brightness = initialBrightnessBase(windowBrightness, system01)
                }
                brightness = (brightness + frac).coerceIn(0f, 1f)
                hud = Hud.Brightness(brightness)
                // Player review D6: a brightness drag is activity — the chrome
                // must not hide mid-gesture, exactly like a scrub or a tap does.
                interactTick = System.nanoTime()
            },
            onVolume = { frac ->
                val v = (state.userVolume + frac).coerceIn(0f, 1f)
                vm.setUserVolume(v)
                hud = Hud.Volume(v)
                // Player review D6: same as brightness — a volume drag is
                // activity, so it resets the 3-second auto-hide timer.
                interactTick = System.nanoTime()
            },
            onScrubPreview = { scrubPreviewMs = it; interactTick = System.nanoTime() },
            onScrubCommit = {
                vm.seekTo(it)
                scrubPreviewMs = null
                interactTick = System.nanoTime()
            },
            onBoostStart = {
                vm.beginBoost()
                hud = Hud.Boost
            },
            onBoostEnd = vm::endBoost,
            onSubtitleHud = {
                hud = Hud.Subtitle(it)
                interactTick = System.nanoTime()
            },
        )

        // 4 — transient feedback layers
        DoubleTapRipple(state)
        hud?.let { EdgeFeedbackLine(it) }
        (hud as? Hud.Subtitle)?.let { SubtitleToast(it.value) }
        scrubPreviewMs?.let { target ->
            ScrubToast(
                target = target,
                durationMs = state.durationMs,
                // The sprite frame for the target's bucket, or null when
                // that stretch was never watched — then the toast is time-only.
                thumbnail = spriteFrame,
            )
        }
        if (state.boosting) BoostBadge()

        // MX #4 — the Skip button. It lives with the transient layers, not
        // in the chrome: the chrome auto-hides mid-intro, and a button that
        // hides with it would take two taps (one to reveal chrome, one to
        // skip). It sits bottom-right above the controls, the same 130 dp
        // clearance ScrubToast uses, and only while a segment is active —
        // the ViewModel only ever sets one when a jump target is known.
        // Locked or ended: no transport jumps from either state.
        state.activeSkipSegment?.let { segment ->
            if (!state.locked && !state.isEnded) {
                SkipSegmentButton(segment = segment, onSkip = { vm.skipActiveSegment() })
            }
        }

        // REA #1: the in-playback Next Episode pill — the last 30 s of an
        // episode whose successor is already prepared. It takes the same
        // bottom-right seat as the Skip button (116 dp), sitting above it
        // when both show (152 dp = 116 + ~28 pill + 8 gap), so the two
        // never overlap and neither hides with the chrome.
        if (showInPlaybackNext(state.positionMs, state.durationMs, state.nextEpisodeTitle != null, state.isEnded, state.locked)) {
            NextEpisodePill(
                bottomPadding = if (state.activeSkipSegment != null) 152.dp else 116.dp,
                onClick = { vm.requestNextEpisode() },
            )
        }

        // 5 — chrome. Ghost mode paints only the edge strip; everything else keeps the
        // three-zone MX bars, with no centre button and no scrim on the picture.
        // Session 121 (P-17): while the player is still starting there is no picture
        // to protect and no back button anywhere — the top bar (or the edge strip
        // in Ghost mode) stays up until the start phase ends, even if the viewer
        // tapped the chrome away. The bottom bar still obeys the tap; only the
        // way out is forced visible.
        // Player review D1: the chrome fades, never cuts. The AnimatedVisibility
        // below IS the gate now — a plain `if` would remove the bars in one
        // frame before the exit fade could play. Same fade the double-tap
        // ripple uses (RaddMotion effects springs; 30 ms crossfade under reduced
        // motion). The lock still cuts: locking is a mode change, not an
        // auto-hide, and the UnlockFab replaces the chrome at once.
        // P5 (player fixes): the chrome fades ride the M3 Expressive effects
        // springs via RaddMotion — fastEffects in, defaultEffects out — and
        // Ghost mode's edge strip fades with them instead of cutting in/out
        // on a plain `if`. (MaterialTheme.motionScheme itself is internal in
        // the pinned material3 1.4.0; wiring it is the BOM-bump follow-up.)
        val chromeVisible = controlsVisible || state.startPhase != StartPhase.NONE
        val reduceMotion = rememberReduceMotion()
        // Round 6: the chrome fades AND scales (expressive effects spec) —
        // the bars settle in from 0.97 instead of only fading. Under
        // reduced motion the scale is dropped (30ms crossfade only). The
        // settled state is unchanged (alpha 1, scale 1), so the Paparazzi
        // goldens do not move.
        val chromeEnter = if (reduceMotion) fadeIn(tween(REDUCED_MOTION_FADE_MS)) else fadeIn(RaddMotion.fastEffects()) + scaleIn(RaddMotion.fastEffects(), initialScale = 0.97f)
        val chromeExit = if (reduceMotion) fadeOut(tween(REDUCED_MOTION_FADE_MS)) else fadeOut(RaddMotion.defaultEffects()) + scaleOut(RaddMotion.defaultEffects(), targetScale = 0.97f)
        // The zoom chip pops a little harder (0.9, anchored top-end) so its
        // appear/disappear reads on a small target.
        val chipEnter = if (reduceMotion) fadeIn(tween(REDUCED_MOTION_FADE_MS)) else fadeIn(RaddMotion.fastEffects()) + scaleIn(RaddMotion.fastEffects(), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f))
        val chipExit = if (reduceMotion) fadeOut(tween(REDUCED_MOTION_FADE_MS)) else fadeOut(RaddMotion.defaultEffects()) + scaleOut(RaddMotion.defaultEffects(), targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f))
        if (!state.locked) {
            if (state.pureCinema) {
                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = chromeEnter,
                    exit = chromeExit,
                ) {
                    EdgeHud(state = state, onBack = onBackClick, onPlayFromStart = { vm.playFrom(0) }, onTogglePlayPause = { vm.togglePlayPause() }, onToggleViewMode = { vm.toggleViewMode() }, onLock = { vm.setLocked(true) })
                }
            } else {
                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = chromeEnter,
                    exit = chromeExit,
                ) {
                    TopBar(state = state, onBack = onBackClick, onLock = { vm.setLocked(true) }, scrimScale = scrimScale)
                }
                // P10 (round 2): the zoom-reset chip — a visible way back
                // to Fit while zoomed. It rides the chrome visibility, so
                // it hides with the bars; it never shows at FIT, locked,
                // or in Ghost mode (that branch has no chip at all). The
                // 80dp top clearance sits it just below the top bar.
                AnimatedVisibility(
                    visible = chromeVisible && state.aspect != AspectMode.FIT,
                    enter = chipEnter,
                    exit = chipExit,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                        ZoomResetChip(
                            onReset = { vm.setAspect(AspectMode.FIT) },
                            modifier = Modifier.padding(top = 80.dp, end = 16.dp),
                        )
                    }
                }
                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = chromeEnter,
                    exit = chromeExit,
                ) {
                    CenterSpinner(state)
                    CenterPlayPause(
                        state,
                        onTogglePlayPause = { vm.togglePlayPause() },
                        onPlayFromStart = { vm.playFrom(0) },
                        onSeekBy = { vm.seekBy(it) },
                    )
                    BottomControls(
                        state = state,
                        onPlayFromStart = { vm.playFrom(0) },
                        onTogglePlayPause = { vm.togglePlayPause() },
                        onToggleMute = { vm.toggleMute() },
                        onShowSubtitleSheet = { vm.showSubtitleSheet() },
                        onToggleFullscreen = { vm.toggleFullscreen() },
                        onShowMoreSheet = { vm.showMoreSheet() },
                        onSeekTo = { vm.seekTo(it) },
                        onSeekBy = { vm.seekBy(it) },
                        onSetUserVolume = { vm.setUserVolume(it) },
                        onKeepCurrent = { vm.keepCurrent() },
                        onEnterPip = launchPip,
                        onActivity = { interactTick = System.nanoTime() },
                        onScrubPreview = { scrubPreviewMs = it },
                        onScrubCommit = {
                            vm.seekTo(it)
                            scrubPreviewMs = null
                            interactTick = System.nanoTime()
                        },
                        onActorClick = onActorClick,
                        scrimScale = scrimScale,
                    )
                }
            }
        }
        if (state.locked) {
            UnlockFab(onUnlock = { vm.setLocked(false) })
        }

        // MX #7 — Kids Lock: the unlock pad. A right PIN unlocks through
        // the VM's grant (this title only — the next episode re-locks); a
        // wrong one leaves the pad open with its message. The pad sits
        // above the chrome, so it opens even with the controls hidden.
        if (kidsPinRequested) {
            PinPadDialog(
                title = stringResource(R.string.kids_pin_title),
                subtitle = stringResource(R.string.kids_pin_player_subtitle),
                error = kidsPinError,
                lockedOutSeconds = (kidsLockViewModel.lockoutRemainingMs() / 1_000).toInt(),
                onPinComplete = { pin ->
                    kidsScope.launch {
                        when (kidsLockViewModel.verifyPin(pin)) {
                            KidsPinOutcome.Success -> {
                                vm.unlockWithKidsPin()
                                kidsPinRequested = false
                            }
                            KidsPinOutcome.WrongPin -> kidsPinError = kidsPinWrong
                            KidsPinOutcome.InvalidFormat -> kidsPinError = kidsPinIncomplete
                            KidsPinOutcome.Mismatch -> kidsPinError = kidsPinWrong
                            // LockedOut clears the error — the dialog's own
                            // countdown line says it.
                            KidsPinOutcome.LockedOut -> kidsPinError = null
                        }
                    }
                },
                onDismiss = { kidsPinRequested = false },
            )
        }

        // 6 — narration cards
        state.settledBanner?.let { banner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Surface(
                    color = RaddNarrationPlate,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = 60.dp)
                        .clickableNoRipple { vm.dismissSettledBanner() },
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painter = painterResource(R.drawable.ic_action_check), contentDescription = null, tint = RaddNarrationCheck, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(banner, color = Color.White, fontSize = brandSp(14), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // Samsung RTL retest (Issue 7): "Back to details" must dismiss the
        // card even when the pop itself cannot run yet. Clearing the card's
        // own state first means the tap is visibly answered at once — the
        // card is gone whatever the pop does — and the pop then leaves the
        // player. A tap that only pops leaves the same card on screen
        // whenever the pop is swallowed, or when the Detail underneath
        // re-pushes the player on return (its autoplay effect re-fires on
        // a fresh composition), which reads exactly as "the button did
        // nothing". Both give-up sources clear here: the handoff card is
        // store-driven, the deadline card is state-driven.
        val backToDetails = { vm.dismissGiveUp(); vm.dismissDeadlineGiveUp(); onBackClick() }

        state.giveUp?.let { (message, why) ->
            GiveUpCard(
                message = message,
                why = why,
                onRetry = { vm.retryCurrentCandidates() },
                onExternal = { vm.openWithExternalPlayer() },
                onDismiss = { vm.dismissGiveUp() },
                onBackToDetails = backToDetails,
                // NAV-4: an expired shared link gets its own way home.
                onHomeClick = onHomeClick,
                // Session 120: the deadline card offered this and the handoff
                // card did not, so after every candidate failed the viewer
                // could only retry the same dead ladder. A further print past
                // max_ranks is still an honest manual hop — tryNextPrint
                // moves to a different print, never the same URL again.
                onTryAnother = if (state.canTryAnotherPrint) {
                    { vm.tryNextPrint() }
                } else {
                    null
                },
                // REA #4: this title will not play — offer a different one
                // from the "For you" pool instead of only retrying the dead
                // ladder. The pick is taken at tap time, never at
                // composition, so two taps draw two titles.
                onPlaySomething = if (vm.canPlaySomething) {
                    { vm.playSomething()?.let(onPlaySomethingClick) }
                } else {
                    null
                },
            )
        } ?: if (state.deadlineGiveUp) {
            // The 30 s hard deadline (§5 P5): no candidate failed, but none produced a
            // frame in time. The card is the same one the service shows, with the timeout
            // reason and the third action the plan promises — try another print now.
            // Dismissing it keeps waiting with a fresh thirty seconds.
            GiveUpCard(
                message = stringResource(R.string.player_start_giveup_message),
                why = HandoffWhy.START_TIMEOUT,
                onRetry = { vm.retryCurrentCandidates() },
                onExternal = { vm.openWithExternalPlayer() },
                onDismiss = { vm.dismissDeadlineGiveUp() },
                onBackToDetails = backToDetails,
                onHomeClick = onHomeClick,
                onTryAnother = if (state.canTryAnotherPrint) {
                    { vm.tryNextPrint() }
                } else {
                    null
                },
                // REA #4: same shuffle as the handoff card above — a title
                // that never produced a frame still ends in something
                // playing. The pick is taken at tap time.
                onPlaySomething = if (vm.canPlaySomething) {
                    { vm.playSomething()?.let(onPlaySomethingClick) }
                } else {
                    null
                },
            )
        } else {
            // Neither a give-up nor a deadline expiry is showing.
        }

        // PL-14: the connection-error card and the give-up card are mutually
        // exclusive — they used to stack on the same failure, two cards saying
        // the same thing in different words. The give-up card is the one that
        // stays; the connection error owns only the failures it does not cover.
        if (state.connectionError != null && state.giveUp == null && !state.deadlineGiveUp) {
            state.connectionError?.let { err ->
                ConnectionErrorCard(err, onRetry = { vm.retryCurrentCandidates() })
            }
        }

        // PL-3: an offline start or an offline failure shows an offline card
        // immediately, with Retry — not just the 30-second deadline card. Waiting
        // half a minute to be told there is no network is not a wait, it is a lie.
        if (state.isOffline && state.giveUp == null && !state.deadlineGiveUp) {
            OfflineCard(onRetry = { vm.retryCurrentCandidates() })
        }

        // PL-4: a locked player keeps a small time chip visible — position and
        // remaining, without unlocking. A locked screen that says nothing about
        // where you are is a screen that says nothing at all. A duration the
        // player has not learned yet is not a time to show. F29 (Batch B): a
        // live stream has no remaining to show, so the chip stays down.
        if (state.locked && !state.isLive && state.durationMs > 0) {
            LockedTimeChip(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                lowRam = lowRam,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
            )
        }

        if (state.isBuffering && !state.isPlaying && state.giveUp == null &&
            state.startPhase == StartPhase.NONE
        ) {
            // P12 (round 2): a stall past 5 s gets the stuck card — the
            // bare plate stops being honest then. Otherwise the plate as
            // before. The percent is computed once for both branches; a
            // live stream never shows one (see [bufferingPercent]).
            val bufferedPct = bufferingPercent(state.bufferedMs, state.durationMs, state.isLive)
            if (state.stuckBuffering) {
                StuckBufferCard(
                    bufferedPct = bufferedPct,
                    onRetry = { vm.retryCurrentCandidates() },
                    // The 480p button only exists when the title offers
                    // the rung — a button that shows a snackbar and does
                    // nothing is a button that lies.
                    onPlay480p = if (state.qualityRungs.any { it.band == "480p" }) {
                        { vm.selectQualityBand("480p") }
                    } else {
                        null
                    },
                )
            } else {
                // Robo run 7: the loading notice's title was unreadable over the
                // frame on a translucent plate — the same rule holds here. The
                // bare "Buffering…" sat directly on the video frame; it gets the
                // notice's solid plate, so the wait is readable over any frame.
                Surface(
                    color = RaddPlayerStage,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    // F4 (Batch B): the notice names how much is buffered ahead
                    // ("Buffering… 42%") on a VOD, so a stall shows progress, not
                    // just a spinner. A live stream never shows a percent — see
                    // [bufferingPercent].
                    PlayerLoadingIndicator(
                        label = if (bufferedPct != null) {
                            stringResource(R.string.player_buffering_percent, bufferedPct)
                        } else {
                            stringResource(R.string.player_buffering)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }

        // The window before the first frame is the one a viewer cannot tell apart from a
        // crash, so it is the one window the player narrates out loud. It survives the lock
        // and Ghost mode for the same reason the buffering spinner does: feedback is not
        // furniture. Past two seconds the card gains the slow line (§5 P5); past eight it
        // gains something to press. At thirty seconds the deadline card owns the screen
        // instead, so this notice stands down.
        // Player card fix (Nov 2026): while the deadline stage is SILENT
        // (the first 2 s) no card at all — the centre spinner below is the
        // only sign of work. The card is a bordered 420 dp plate with a
        // title, a step line and, from ACTIONS, real buttons: that shape
        // reads as an error dialog, and ordinary users pressed Retry on a
        // press that was still succeeding. The full card earns its place
        // only from HINT, when the wait is genuinely long.
        if (state.startPhase != StartPhase.NONE &&
            state.startDeadlineStage != StartDeadlineStage.GIVE_UP &&
            state.startDeadlineStage != StartDeadlineStage.SILENT
        ) {
            val actions = state.startDeadlineStage == StartDeadlineStage.ACTIONS
            // Session 121 (P-36): the 30-second wait showed no progress at all. When
            // the bundle has more than one version, say another one is being tried —
            // no counter, no internal vocabulary (dev-cleanup, Nov 2026, D3).
            // One version needs no label at all.
            val attemptLabel = run {
                val renditions = state.bundle?.renditions.orEmpty()
                if (renditions.size <= 1) {
                    null
                } else {
                    stringResource(R.string.player_attempt_counter)
                }
            }
            StartNoticeCard(
                phase = state.startPhase,
                title = state.pendingTitle,
                attemptLabel = attemptLabel,
                slowHint = state.startDeadlineStage != StartDeadlineStage.SILENT,
                // 26: the three-step progress story — the label names the step
                // being done, not the generic phase: finding the best print,
                // then warming the link, then buffering.
                stepLabel = when (state.startPhase) {
                    StartPhase.CONNECTING_PLAYER -> stringResource(R.string.player_step_finding)
                    StartPhase.STARTING -> stringResource(R.string.player_step_warming)
                    else -> stringResource(R.string.player_step_buffering)
                },
                // Session 121 (P-7): the rung label must name a rung. The display label
                // used to fall back to the print's title, so the notice read
                // "Quality: Ali Baba aur 40 Bhoot"; a non-quality label falls through
                // to the band label instead, and the line is dropped when neither
                // names a rung.
                rungLabel = if (state.startDeadlineStage != StartDeadlineStage.SILENT) {
                    state.activeRendition?.displayLabel()?.takeIf { looksLikeQualityLabel(it) }
                        ?: state.qualityLabel.takeIf { it.isNotBlank() && looksLikeQualityLabel(it) }
                } else {
                    null
                },
                // P-3: the elapsed line counts from the request, so the
                // resolve wait is counted too (see StartDeadline).
                waitElapsedMs = state.startRequestElapsedMs,
                onRetry = if (actions) {
                    { vm.retryCurrentCandidates() }
                } else {
                    null
                },
                onTryAnother = if (actions && state.canTryAnotherPrint) {
                    { vm.tryNextPrint() }
                } else {
                    null
                },
                onCancel = onBackClick,
            )
        }

        if (state.isEnded && controlsVisible && !state.locked && !state.pureCinema) {
            // Session 123 (PL-1): while the countdown runs the ended overlay is
            // the countdown, not the buttons — one clear action, Cancel.
            val countdown = state.autoplayCountdownSeconds
            if (countdown != null) {
                AutoplayCountdownCard(
                    chrome,
                    secondsLeft = countdown,
                    onCancel = { vm.cancelAutoplayCountdown() },
                    nextEpisodeTitle = state.nextEpisodeTitle,
                    nextEpisodeSeason = state.nextEpisodeSeason,
                    nextEpisodeEpisode = state.nextEpisodeEpisode,
                    onPlayNow = { vm.requestNextEpisode() },
                    previews = state.postPlayPreviews,
                    onPreviewClick = { preview -> vm.openPost(preview.postId) },
                )
            } else {
                EndedButtons(
                    chrome,
                    showNextEpisode = state.endedHasNextEpisode,
                    onPlayAgain = { vm.playFrom(0) },
                    onNextEpisode = { vm.requestNextEpisode() },
                    nextEpisodeTitle = state.nextEpisodeTitle,
                    nextEpisodeSeason = state.nextEpisodeSeason,
                    nextEpisodeEpisode = state.nextEpisodeEpisode,
                    nextEpisodePosterUrl = state.nextEpisodePosterUrl,
                    previews = state.postPlayPreviews,
                    onPreviewClick = { preview -> vm.openPost(preview.postId) },
                )
            }
        }

        // 7 — dialogs & sheets (VM-flag driven so every entry point is centralized)
        state.resumeOfferMs?.let { offer ->
            ResumeChip(
                offerMs = offer,
                onResume = { vm.resolveResume(true) },
                onStartOver = { vm.resolveResume(false) },
            )
        }
        state.calibration?.let { cal ->
            CalibrationDialog(
                state = cal,
                onConfirm = { episode -> vm.calibrateEpisodeStart(episode) },
                onDismiss = { vm.dismissCalibration() },
            )
        }
        if (state.playFromDialogVisible) {
            PlayFromDialog(
                onSubmit = { ms ->
                    vm.playFrom(ms)
                    vm.hidePlayFromDialog()
                },
                onDismiss = { vm.hidePlayFromDialog() },
            )
        }
        if (state.speedSheetVisible) SpeedSheet(state, vm)
        if (state.sleepSheetVisible) SleepSheet(state, vm)
        if (state.subtitleSheetVisible) SubtitleSettingsSheet(state, vm)
        if (state.subtitleOffsetSheetVisible) {
            SyncOffsetSheet(
                audioOffsetMs = state.audioDelayMs,
                onAudioOffsetChange = { vm.setAudioDelay(it) },
                offsetMs = state.subtitle.delayMs,
                hasCustomOverlay = state.customSubtitleLoaded != null,
                onOffsetChange = { vm.setSubtitleOffset(it) },
                onDismiss = { vm.hideSubtitleOffsetSheet() },
            )
        }
        if (state.tracksSheetVisible) TracksSheet(state, vm)
        if (state.printSheetVisible) PrintLogSheet(state, vm)
        if (state.qualitySheetVisible) QualitySheet(state, vm)
        if (state.moreSheetVisible) MoreSheet(state, vm, onEnterPip = launchPip)
        if (state.eqSheetVisible) EqSheet(state, vm)

        // 8 — lightweight snackbar. F4 (Batch A): suppressed while a start card is
        // showing — in landscape (~360dp tall) the card's bottom edge sits where
        // this bar lands, so the two overlapped and neither was readable. The
        // message is not lost: it reappears on the next start-phase-free frame
        // because the snackbar state lives in the ViewModel, not in this Box.
        if (state.startPhase == StartPhase.NONE) {
            snackbar?.let { bar ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Surface(
                        color = RaddPlayerSnackbarPlate,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            // Cinema (Nov 2026): 100 → 104dp — the seat clears
                            // the slimmer bottom bar (96dp) instead of sitting
                            // 4dp into the scrubber row.
                            .padding(bottom = 104.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(bar.text, color = Color.White, fontSize = brandSp(14), modifier = Modifier.weight(1f))
                            if (bar.showUndo) {
                                TextButton(
                                    onClick = {
                                        vm.undoAutoDeletedEpisode()
                                        snackbar = null
                                    },
                                ) {
                                    Text("Undo", color = Color.White, fontSize = brandSp(14))
                                }
                            }
                        }
                    }
                }
            }
        }

    }
}

/**
 * True when a sheet the viewer opened is up — pauses the controls auto-hide.
 *
 * Session 126 (audit bug a): the More sheet was missing from this list, so the
 * chrome auto-hid three seconds into a More-sheet browse and the viewer had to
 * tap back in to read the rows they had just opened. Every sheet that can hold
 * the viewer's attention belongs here — including More, which is a sheet like
 * any other. (The old paragraph about the sponsor slot is gone with the slot
 * itself: PL-2 removed the placeholder ad entirely, and there is no ad state
 * left in this file to exclude.)
 */
private val PlayerUiState.dialogBlocking: Boolean
    get() = resumeOfferMs != null || calibration != null || giveUp != null ||
        speedSheetVisible || tracksSheetVisible || subtitleSheetVisible ||
        subtitleOffsetSheetVisible || printSheetVisible || sleepSheetVisible ||
        playFromDialogVisible || qualitySheetVisible || moreSheetVisible || eqSheetVisible

// ── 1. Surface ─────────────────────────────────────────────────────────────────

// Fully qualified on purpose: this file also uses Kotlin's `@OptIn` for
// ExperimentalMaterial3Api, and the two annotations answer different checkers. Media3's
// `UnsafeOptInUsageError` is a *lint* check that reads `androidx.annotation.OptIn` only, so the
// Kotlin form here compiled clean and left 20 lint errors.
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(
    vm: PlayerViewModel,
    state: PlayerUiState,
    // PL-10: the surface reports its own bounds so the PiP window can grow
    // out of the picture.
    onBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    // P2-5: the sprite captures copy frames off this view's surface, so the
    // screen keeps the view itself.
    onPlayerView: ((PlayerView) -> Unit)? = null,
) {
    val player by vm.player.collectAsState()
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            // P10 (round 2): the two-finger pan while zoomed. The pan is
            // a fraction of the surface, clamped in the ViewModel to
            // ±0.25 — the picture can be dragged, never lost off-screen.
            // Zero unless zoomed; leaving ZOOM resets it.
            .graphicsLayer {
                translationX = state.videoPan.x * size.width
                translationY = state.videoPan.y * size.height
            }
            .then(
                if (onBounds != null) {
                    Modifier.onGloballyPositioned { onBounds(it.boundsInWindow()) }
                } else {
                    Modifier
                },
            ),
        factory = { ctx ->
            PlayerView(ctx).apply {
                onPlayerView?.invoke(this)
                useController = false // all chrome is Compose
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.resizeMode = state.aspect.resizeMode
            styleNativeSubtitles(view, state)
        },
    )
}

/**
 * Native embedded-subtitle rendering (size/position/colors) — every call below was verified
 * public in media3-ui 1.3.1. Sync is applied below this layer by the service's decoder
 * wrapper, so this styling and the custom overlay ([SubtitleOverlay]) agree.
 *
 * Both paths read the ONE [PlayerUiState.subtitle] snapshot, which is why the viewer's picked
 * colour and weight land on an embedded track exactly as they land on an external file: the
 * sheet never has to say which renderer a file happens to use. The one exception is
 * [SubtitleUiSettings.useEmbeddedStyles]: when the viewer turns it on, the native view hands
 * styling back to Media3's parsed ASS styles (colours, sizes, placement) instead of the
 * viewer's look — the overlay keeps the file's colours for external ASS either way.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun styleNativeSubtitles(view: PlayerView, state: PlayerUiState) {
    val sv: SubtitleView = view.subtitleView ?: return
    val s = state.subtitle
    sv.setFractionalTextSize(
        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s.sizePercent / 100f,
        false,
    )
    sv.setBottomPaddingFraction(s.bottomFraction)
    val fontTypeface = when (s.font) {
        SubtitleFont.SERIF -> Typeface.SERIF
        SubtitleFont.MONOSPACE -> Typeface.MONOSPACE
        SubtitleFont.DEFAULT -> Typeface.DEFAULT
    }
    sv.setStyle(
        CaptionStyleCompat(
            s.colour.argb,
            if (s.textBackground) RaddCaptionPlateArgb else android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            when (s.edge) {
                SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                SubtitleEdge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
            },
            android.graphics.Color.BLACK,
            // Bold was overlay-only until the sheet persisted the style; the native path has
            // always had a typeface slot, it was simply never filled.
            if (s.bold) Typeface.create(fontTypeface, Typeface.BOLD) else fontTypeface,
        ),
    )
    // Robo run 4: embedded styles were applied on top of the viewer's own
    // colour, so a track that styles its cues dark renders black text on a
    // black frame — invisible subtitles, and a 1.03:1 contrast flag. The
    // viewer's picked colour (bright by the SubtitleColor rule, white by
    // default) is the readable one, so it wins over whatever the file says.
    // Position/size still come from the viewer's snapshot below — unless the
    // viewer explicitly asks for the file's own styling in the sheet, in
    // which case both switches hand the native view back to Media3's parsed
    // styles. Font sizes only take effect while styles are on (Media3).
    sv.setApplyEmbeddedStyles(s.useEmbeddedStyles)
    sv.setApplyEmbeddedFontSizes(s.useEmbeddedStyles)
}

@Composable
private fun AudioOnlySurface(title: String, isPlaying: Boolean) {
    Box(Modifier.fillMaxSize().background(RaddPlayerStage), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painter = painterResource(R.drawable.ic_player_music_note), contentDescription = null, tint = RaddCurrent500, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (isPlaying) stringResource(R.string.player_audio_only_active)
                else stringResource(R.string.player_audio_only_paused),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = brandSp(12),
            )
        }
    }
}
