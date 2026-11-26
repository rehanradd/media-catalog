package com.raddstream.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.raddstream.app.R
import com.raddstream.app.brand.Brand
import com.raddstream.app.player.AspectMode
import com.raddstream.app.player.AudioBoostUi
import com.raddstream.app.player.CacheAffordance
import com.raddstream.app.player.GestureSettings
import com.raddstream.app.player.KeepUiState
import com.raddstream.app.player.PlayerStatusWord
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.player.playerStatusWord
import com.raddstream.app.player.SleepMode
import com.raddstream.app.player.StartDeadlineStage
import com.raddstream.app.player.StartPhase
import com.raddstream.app.ui.components.RaddDialogTitle
import com.raddstream.app.ui.components.raddFilterChipColors
import com.raddstream.app.ui.components.RaddChip
import com.raddstream.app.ui.components.ScrollingChipRow
import com.raddstream.app.ui.components.rememberReduceMotion
import com.raddstream.app.ui.theme.PlayerChromeTheme
import com.raddstream.app.ui.theme.PlayerOnVideo
import com.raddstream.app.ui.theme.PressHaptics
import com.raddstream.app.ui.theme.RaddCurrent500
import com.raddstream.app.ui.theme.RaddDanger
import com.raddstream.app.ui.theme.RaddMotion
import com.raddstream.app.ui.theme.RaddSuccess
import com.raddstream.app.ui.theme.RaddTideIndigo
import com.raddstream.app.ui.theme.brandSp
import com.raddstream.app.ui.theme.paint
import com.raddstream.app.ui.theme.pressScale
import com.raddstream.app.viewmodel.PlayerViewModel
import com.raddstream.app.viewmodel.formatTime


// ── 5. Chrome bars ─────────────────────────────────────────────────────────────

/**
 * Review fix (16 Nov): one shared left/right rail for the whole chrome — top
 * bar, live mark, control row, scrubber and time rows all sit on it. 48dp,
 * or the display cutout + 16dp when the cutout is wider. The old chrome used
 * three different rails (4dp / 4dp / 12dp), so the edges never lined up.
 */
internal fun playerSafeRailDp(cutoutLeft: Dp, cutoutRight: Dp): Dp =
    maxOf(48.dp, cutoutLeft + 16.dp, cutoutRight + 16.dp)

@Composable
private fun playerChromeRail(): Dp {
    val cutout = WindowInsets.displayCutout.asPaddingValues()
    val direction = LocalLayoutDirection.current
    return playerSafeRailDp(
        cutoutLeft = cutout.calculateLeftPadding(direction),
        cutoutRight = cutout.calculateRightPadding(direction),
    )
}

@Composable
internal fun TopBar(state: PlayerUiState, onBack: () -> Unit, onLock: () -> Unit, scrimScale: Float = 1f) {
    val print = state.activeRendition
    val rail = playerChromeRail()
    // P3 (round 2): the scrim scales with the staged poster's luminance —
    // bright art gets a stronger scrim, dark art a lighter one. The stops
    // clamp at 1.0, and 1.0x is the long-standing scrim (no poster = 1.0x).
    val topGradient = Brush.verticalGradient(
        colors = listOf(
            Color.Black.copy(alpha = (0.82f * scrimScale).coerceAtMost(1f)),
            Color.Black.copy(alpha = (0.35f * scrimScale).coerceAtMost(1f)),
            Color.Transparent,
        )
    )
    // P4 (player fixes): the bar follows the title — 56dp for a one-line
    // title, 72dp for a two-line one. The height is a floor; content taller
    // than the floor grows the box on its own, so a wrapping title is never
    // clipped by the bar, at 1.3x font or any other size.
    var titleLines by remember { mutableStateOf(1) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Hate-audit H21 (Robo P8): when the system bars are transiently
            // revealed (edge swipe) the title must clear the status bar —
            // statusBarsPadding is a no-op while the bars are hidden, so
            // the immersive frame is untouched.
            .statusBarsPadding()
            .background(topGradient)
            // Cinema (Nov 2026): 56dp — the 48dp icon row + 6dp of padding.
            // The press targets stay 48dp; only the bar gets shorter.
            .heightIn(min = if (titleLines > 1) CinemaChrome.TOP_BAR_TWO_LINE_DP.dp else CinemaChrome.TOP_BAR_MIN_DP.dp)
            .padding(start = rail, end = rail, top = 2.dp, bottom = 4.dp),
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_player_back),
                contentDescription = stringResource(R.string.player_back),
                tint = PlayerOnVideo.text,
                // Cinema (Nov 2026): the glyph shrinks to 20dp; the IconButton
                // press target stays 48dp.
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            // Round 3 (P4 award lift): the title crossfades + slides 200ms
            // when it changes (next episode, print switch) — the bar never
            // cuts from one title to the next. Under reduced motion the
            // swap is a 30ms crossfade with no slide, same as the chrome
            // fade. Only the title animates; the subtitle is static text
            // under it, so the bar height never jumps mid-fade.
            val titleText = state.pendingTitle ?: print?.displayLabel() ?: Brand.name
            val reduceMotion = rememberReduceMotion()
            AnimatedContent(
                targetState = titleText,
                transitionSpec = {
                    if (reduceMotion) {
                        fadeIn(tween(REDUCED_MOTION_FADE_MS)) togetherWith
                            fadeOut(tween(REDUCED_MOTION_FADE_MS))
                    } else {
                        (fadeIn(tween(200, easing = RaddMotion.EmphasizedDecelerate)) +
                            slideInVertically(tween(200, easing = RaddMotion.EmphasizedDecelerate)) { it / 4 }) togetherWith
                            (fadeOut(tween(200, easing = RaddMotion.EmphasizedAccelerate)) +
                                slideOutVertically(tween(200, easing = RaddMotion.EmphasizedAccelerate)) { -it / 4 })
                    }
                },
                label = "player-title",
            ) { title ->
                Text(
                    title,
                    color = PlayerOnVideo.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    // P4 (player fixes): the full title, up to two lines — static
                    // text, never a marquee. A marquee is a motion affordance, and
                    // under reduced motion it must never run; two-line static text
                    // is the same title on every device. Ellipsis only if it still
                    // overflows at two lines.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { titleLines = it.lineCount },
                )
            }
            if (print != null) {
                val autoTag = if (state.autoQualityBand != null) stringResource(R.string.player_tag_auto_plain) else null
                // P4 (player fixes): the subtitle is year · Auto — quality
                // moves OUT of it. Quality is owned by the persistent HUD +
                // the QualityPill (single-owner rule), so the subtitle never
                // repeats it. Round 2: the old tag said "Auto: 1080p" — the
                // rung in a third place, after the HUD and the pill. The
                // bare "Auto" is the Auto indicator; the rung is the pill's
                // and the HUD's alone. A quality that moves on its own is
                // still never mistaken for a hand-picked one: Auto says so.
                // Review fix (16 Nov): a live channel has no subtitle at all — the
                // single live mark rides the bottom row, and a live picture has
                // no year or rung to print. The title centres vertically on its own.
                // Hate-audit H17: the "• HLS" half was engineer jargon; a viewer
                // does not need the transport, on live or on VOD.
                val subtitle = if (state.isLive) {
                    ""
                } else {
                    buildString {
                        state.titleYear?.let { append(it.toString()) }
                        autoTag?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it)
                        }
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        // E-C2: 11sp white at 72% over the 53%-black top bar was
                        // ~3.13:1 over a bright frame. Full white now.
                        color = PlayerOnVideo.text,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (state.sleep.mode == SleepMode.COUNTDOWN) {
            Icon(
                painter = painterResource(R.drawable.ic_player_bedtime),
                contentDescription = stringResource(R.string.player_sleep_remaining, state.sleep.remainingMs.formatTime()),
                tint = RaddCurrent500,
                modifier = Modifier
                    .size(16.dp)
                    .padding(end = 6.dp),
            )
        }
        // P1 (player fixes): the speed pill is GONE from this bar — the
        // bottom-row [SpeedPill] is the single owner and the single control
        // for speed now. A second pill here would be a second owner.
        // Session 121 (P-35): the keep chip lived here and squeezed the title to a
        // stub ("Ali Baba …") on a narrow phone. It now rides its own row above
        // the scrubber in BottomControls, where it cannot crowd anything.
        IconButton(onClick = onLock) {
            Icon(
                painter = painterResource(R.drawable.ic_player_lock),
                contentDescription = stringResource(R.string.player_lock),
                tint = PlayerOnVideo.text,
                // Cinema (Nov 2026): the glyph shrinks to 20dp; the IconButton
                // press target stays 48dp.
                modifier = Modifier.size(20.dp),
            )
        }
        // MX #6 (chrome diet): the print-switch button is gone from this bar —
        // it opened the print sheet, and the More sheet already has the
        // identical "Prints" row, so a source switch is one extra tap via
        // More instead of a permanent 48dp slot crowding the title.
        // P0-10 (road to 100/100, audit B-16): the decoder switch is gone
        // from this bar - plain jade "HW"/"SW" text was engineer-facing
        // diagnostic chrome in a prime slot. The switch lives in More ->
        // "Playback issue?" -> Decoder (PlayerSheets.kt, error-gated - shown
        // only after a playback error); the auto-fallback HW->SW with its
        // settled banner already lives in PlaybackService, so nothing is
        // lost.
        // P0-10 (audit B-23): Cast is back in the top bar, far right -
        // removing it was a regression. The same standard route-picker
        // Detail uses, composed only when a receiver is actually there
        // (state.castAvailable already folds the server flag and the lock
        // state), so the bar stays clean for everyone else. Bare 48dp like
        // back and lock - the bar's own gradient scrim is the contrast
        // plate, no extra circle.
        if (state.castAvailable) {
            PlayerCastButton()
        }
        }
    }
}

/**
 * P0-10 (road to 100/100, audit B-23): the player top bar's Cast button.
 * A `MediaRouteButton` is a View, not a composable, and the framework owns
 * the picker dialog plus the button's own connected / connecting states
 * ([CastButtonFactory.setUpMediaRouteButton]) - the same standard
 * route-picker Detail's cast button uses. No prose of its own: the
 * description comes from `detail_cast`, the same word Detail announces.
 */
@Composable
private fun PlayerCastButton() {
    val description = stringResource(R.string.detail_cast)
    AndroidView(
        factory = { context ->
            MediaRouteButton(context).apply {
                CastButtonFactory.setUpMediaRouteButton(context, this)
                contentDescription = description
            }
        },
        modifier = Modifier.size(48.dp),
    )
}

/**
 * The centre transport (mockup): a large frosted play button on the picture,
 * flanked by visible ±10s skip buttons — every rival shows them, and the
 * hidden double-tap alone was both undiscoverable and accident-prone
 * (hate-audit H5/H13). The skips are VOD-only: a live channel has no
 * position to move, so on live the centre button stands alone.
 *
 * Review fix (16 Nov) — a deliberate-decision reversal: the centre button
 * now shows ONLY while paused. During playback it is gone: it duplicated the
 * bottom row's play/pause, and a big frosted circle over a moving picture
 * reads as a second transport, not a shortcut. The paused picture already
 * keeps its HUD up, so the button is the affordance that says "tap to
 * resume" — the bottom row stays the transport while playing.
 *
 * Never over a start card (the card narrates the wait) and never over the
 * buffering spinner (the two would stack). A tap here toggles play/pause
 * only: the button sits in the chrome layer above the gesture plane, so the
 * tap is consumed here and never also toggles the chrome. When the chrome
 * auto-hides, the button goes with it and the picture is clean. An ended
 * title restarts from the beginning, the same rule the bottom bar's play
 * button follows. The centre button and double-tap-centre share one toggle
 * path ([com.raddstream.app.viewmodel.PlayerViewModel.togglePlayPause]); a
 * double-tap on the button is one toggle, not two — the second tap inside
 * the platform double-tap window is the double-tap's second half, not a
 * new press, so it is dropped.
 */
@Composable
internal fun CenterPlayPause(
    state: PlayerUiState,
    onTogglePlayPause: () -> Unit,
    onPlayFromStart: () -> Unit,
    onSeekBy: (Long) -> Unit,
) {
    if (state.startPhase != StartPhase.NONE || state.isBuffering) return
    // Review fix (16 Nov): paused-only. While playing the bottom row is the
    // transport; the centre button would duplicate it over the picture.
    if (state.isPlaying) return
    val label = stringResource(R.string.player_play)
    var lastCentreToggleNs by remember { mutableLongStateOf(0L) }
    // Round 3: press spring + haptic tick on the centre transport
    // (PressFeedback) — the paused-state play button is a primary control,
    // and until now it pressed with no scale and no tick.
    val playSource = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (!state.isLive) {
                CentreSkipButton(
                    icon = painterResource(R.drawable.ic_player_replay_10),
                    label = stringResource(R.string.player_rewind_10),
                    onClick = { onSeekBy(-10_000L) },
                )
            }
            Surface(
                color = PlayerOnVideo.chipFill,
                shape = CircleShape,
                modifier = Modifier
                    // Cinema (Nov 2026): 88 → 72dp — the frosted circle is an
                    // affordance, not a poster; the 72dp press target still
                    // clears the 48dp floor.
                    .size(72.dp)
                    .pressScale(playSource)
                    .clickable(
                        onClick = {
                            val now = System.nanoTime()
                            if (now - lastCentreToggleNs > 300_000_000L) {
                                lastCentreToggleNs = now
                                if (state.isEnded) onPlayFromStart() else onTogglePlayPause()
                            }
                        },
                        role = Role.Button,
                        interactionSource = playSource,
                    )
                    .semantics { contentDescription = label },
            ) {
                PressHaptics(playSource)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_player_play),
                        contentDescription = null,
                        tint = PlayerOnVideo.text,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            if (!state.isLive) {
                CentreSkipButton(
                    icon = painterResource(R.drawable.ic_player_forward_10),
                    label = stringResource(R.string.player_forward_10),
                    onClick = { onSeekBy(10_000L) },
                )
            }
        }
    }
}

/** One ±10s flank of the centre transport: a 48dp frosted circle, same chrome. */
@Composable
private fun CentreSkipButton(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
) {
    // Round 3: the flanks get the same press spring + haptic tick as the
    // centre button (PressFeedback) — a ±10s skip is a primary control.
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        color = PlayerOnVideo.chipFill,
        shape = CircleShape,
        modifier = Modifier
            .size(48.dp)
            .pressScale(interactionSource)
            .clickable(onClick = onClick, role = Role.Button, interactionSource = interactionSource)
            .semantics { contentDescription = label },
    ) {
        PressHaptics(interactionSource)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(painter = icon, contentDescription = null, tint = PlayerOnVideo.text, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * The centre of the picture holds exactly one thing while the picture is not
 * there yet: the buffering spinner. Once playback is under way the centre
 * belongs to [CenterPlayPause] — the frosted play/pause button — and this
 * spinner stands down, so the two never overlap. (Ghost mode already proved
 * the spinner-only shape; the mockup redesign puts transport on the picture
 * too, where the thumb already is.)
 */
@Composable
internal fun CenterSpinner(state: PlayerUiState) {
    if (state.startPhase == StartPhase.NONE && !state.isBuffering) return
    // Player card fix (Nov 2026): while the start-notice card is up
    // (HINT/ACTIONS) its own spinner is the one — a second centre
    // spinner reads as something broken. During SILENT the card is
    // hidden, so this spinner is the only one; after start it is the
    // buffering spinner.
    if (state.startPhase != StartPhase.NONE &&
        state.startDeadlineStage != StartDeadlineStage.SILENT
    ) return
    val chrome = PlayerChromeTheme.forId(state.playerTheme)
    // F-04: resolved here, at composition time — stringResource() is a
    // @Composable call and cannot run inside the semantics {} lambda below
    // (compile error: "@Composable invocations can only happen from the
    // context of a @Composable function").
    val bufferingLabel = stringResource(R.string.player_buffering)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = chrome.chip,
            shape = CircleShape,
            // F-04: the spinner is paint only — without a live region
            // TalkBack never says the player is buffering. Polite, with
            // the buffering sentence as the spoken text.
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = bufferingLabel
            },
        ) {
            // F4 (Batch B): the centre spinner was 44dp in a 72dp chip —
            // the round-2 re-review read it as very small against the
            // playback area, with no visible buffering progress. The ring
            // is 56dp in an 88dp chip now, and the buffering notice below
            // carries the buffered percent.
            Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = PlayerOnVideo.text,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
    }
}

/**
 * The whole chrome of Ghost mode ([com.raddstream.app.player.ViewMode.PURE_CINEMA]) — and
 * the only thing left when the frame is deliberately furniture-free:
 *
 *  - no scrim across the picture and no scrim behind a pause: the frame keeps its own
 *    brightness whether you are playing or paused, which is the entire point of the mode;
 *  - no giant centre play button. Transport lives at the bottom-left edge;
 *  - three small chips hugging the edges (back + title, transport + time, exit + lock), each
 *    with its own rounded backing so it stays readable over a white frame without any of
 *    them dimming the image;
 *  - a 2dp played-progress line along the very top edge, so position is legible without a
 *    bar sitting on top of the picture.
 *
 * It fades on the same timer as the normal bars, a single tap brings it back, and a
 * two-finger tap rewinds [com.raddstream.app.player.GestureSettings.cinemaRewindMs] — the
 * affordance that makes a chrome-less frame still usable. The exit chip is why leaving is
 * never a hunt: one tap returns the full MX chrome.
 */
@Composable
internal fun EdgeHud(
    state: PlayerUiState,
    onBack: () -> Unit,
    onPlayFromStart: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleViewMode: () -> Unit,
    onLock: () -> Unit,
) {
    val played = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val chrome = PlayerChromeTheme.forId(state.playerTheme)
    Box(Modifier.fillMaxSize()) {
        // Progress, painted on the edge of the frame rather than across the image.
        // F29 (Batch B): no line on a live stream — position over a DVR-window
        // duration is not progress, and the bar would imply a scrub that does
        // not exist.
        if (!state.isLive) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .height(2.dp)
                    .background(chrome.track),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(played)
                        .height(2.dp)
                        .background(chrome.played),
                )
            }
        }

        // Top-left: leave, and what is playing.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 6.dp, top = 6.dp)
                .background(chrome.chip, CircleShape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlTarget(label = stringResource(R.string.player_back), onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_player_back),
                    contentDescription = null,
                    tint = PlayerOnVideo.text,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = state.pendingTitle ?: state.activeRendition?.displayLabel() ?: Brand.name,
                color = PlayerOnVideo.mutedText,
                fontSize = brandSp(12),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .padding(end = 8.dp),
            )
        }

        // Bottom-left: transport. Tapping it at the end of a file replays from the top,
        // because this mode has no separate "play again" furniture to fall back on.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 10.dp)
                .background(chrome.chip, CircleShape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlTarget(
                // CL-5: the Ghost-mode edge control is the centre control by
                // another name — it says "Pause video"/"Play video", not bare
                // "Pause"/"Play". Robo run 4 flagged two "Play" controls; the
                // centre button now says "Play video", and this one is the
                // centre button in Ghost mode, so it says the same.
                label = if (state.isPlaying) stringResource(R.string.player_pause_centre) else stringResource(R.string.player_play_centre),
                onClick = { if (state.isEnded) onPlayFromStart() else onTogglePlayPause() },
            ) {
                Icon(
                    painter = if (state.isPlaying && !state.isBuffering) painterResource(R.drawable.ic_player_pause) else painterResource(R.drawable.ic_player_play),
                    contentDescription = null,
                    tint = PlayerOnVideo.text,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                // Session 121 (P-11): same rule as the bottom bar — "Loading…" while
                // the start is in flight, position alone once playing with an
                // unknown total, never a "--:--" total that was never measured.
                text = if (state.startPhase != StartPhase.NONE) {
                    stringResource(R.string.app_loading)
                } else if (!state.isLive && state.durationMs > 0) {
                    stringResource(
                        R.string.player_time_of,
                        state.positionMs.formatTime(),
                        state.durationMs.formatTime(),
                    )
                } else {
                    state.positionMs.formatTime()
                },
                color = PlayerOnVideo.mutedText,
                fontSize = brandSp(11),
                modifier = Modifier.padding(end = 8.dp),
            )
        }

        // Bottom-right: the way back to the full chrome, plus the lock.
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 6.dp, bottom = 10.dp)
                .background(chrome.chip, CircleShape)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlTarget(label = stringResource(R.string.player_leave_cinema), onClick = onToggleViewMode) {
                Icon(
                    painter = painterResource(R.drawable.ic_player_local_movies),
                    contentDescription = null,
                    tint = RaddCurrent500,
                    modifier = Modifier.size(20.dp),
                )
            }
            ControlTarget(label = stringResource(R.string.player_lock), onClick = onLock) {
                Icon(
                    painter = painterResource(R.drawable.ic_player_lock),
                    contentDescription = null,
                    tint = PlayerOnVideo.text,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
internal fun BottomControls(
    state: PlayerUiState,
    onPlayFromStart: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleMute: () -> Unit,
    onShowSubtitleSheet: () -> Unit,
    onShowQualitySheet: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onShowMoreSheet: () -> Unit,
    // P1 (player fixes): the flexible slots' owners — the speed sheet, the
    // audio-tracks sheet and the next-episode hop. They arrive as callbacks
    // (never a ViewModel call) so the row stays a pure view of the state.
    onShowSpeedSheet: () -> Unit,
    onShowAudioSheet: () -> Unit,
    onRequestNextEpisode: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSetUserVolume: (Float) -> Unit,
    onSetAudioBoostLevel: (Float) -> Unit,
    onKeepCurrent: () -> Unit,
    onEnterPip: () -> Unit,
    onActivity: () -> Unit,
    onScrubPreview: (Long) -> Unit = {},
    onScrubCommit: (Long) -> Unit,
    // SC §3: tapping an actor on the pause strip searches them, the same
    // route the detail screen's cast chips take.
    onActorClick: (String) -> Unit = {},
    // 25c: the single-rung Quality button explains itself through the
    // screen's snackbar, which lives in the content scope, not here.
    onMessage: (String) -> Unit = {},
    // P3 (round 2): the scrim multiplier from the staged poster's
    // luminance (1.0x = the long-standing scrim; no poster = 1.0x).
    scrimScale: Float = 1f,
) {
    var showVolumeDialog by remember { mutableStateOf(false) }
    // P1-16 (road to 100/100, audit B-21): the pause X-Ray is one line
    // that expands on tap. A fresh pause always starts collapsed, and
    // resuming collapses it again — the cast is never advertised on
    // every pause, only when the viewer asks for it.
    var xrayExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) xrayExpanded = false
    }
    val chrome = PlayerChromeTheme.forId(state.playerTheme)
    val rail = playerChromeRail()
    // Review fix (16 Nov): the bottom scrim is a full-height gradient — the
    // old one went transparent exactly where the live mark sits, so the mark
    // read over a bright ticker instead of over the dark.
    val bottomGradient = Brush.verticalGradient(
        colors = listOf(
            Color.Transparent,
            Color.Black.copy(alpha = (0.48f * scrimScale).coerceAtMost(1f)),
            Color.Black.copy(alpha = (0.92f * scrimScale).coerceAtMost(1f)),
        )
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(bottomGradient)
                // Cinema (Nov 2026): 136 → 104dp — scrubber 48 + controls 48 +
                // 8dp of bottom padding. The press targets stay 48dp; only the
                // bar gets shorter.
                // P1 (player fixes): 104 → 96dp. The 8dp comes off the bottom
                // padding alone (8dp → 0dp): the bar sits flush to the
                // immersive frame's bottom edge, and every press target stays
                // 48dp — the bar shrinks by tightening the gap, never by
                // shrinking a target. See [CinemaChrome.BOTTOM_BAR_MIN_DP].
                .heightIn(min = CinemaChrome.BOTTOM_BAR_MIN_DP.dp)
                .padding(start = rail, end = rail, bottom = 0.dp),
        ) {
            // P1-16 (road to 100/100, audit B-21): pause is discovery,
            // but the X-Ray is ONE line now — the context line (episode ·
            // quality · audio · subtitles, P9 round 2) with a chevron.
            // The cast chips only appear when the viewer taps the line;
            // a pause no longer advertises the whole cast on every
            // pause, and the strip never appears mid-play (it would
            // steal the picture). The cast list itself is the loaded
            // signal — it is empty until the title stages it. The line
            // shows whenever paused (the rung alone is worth naming);
            // the chips only when cast is known AND the line is open.
            if (!state.isPlaying) {
                // The episode label is formatted from the string resource
                // here (series only, both numbers > 0) — the pure line
                // below only joins parts that already exist.
                val episodeLabel = if (state.isSeriesScope) {
                    state.activeRendition?.coverage?.let { cov ->
                        if (cov.season > 0 && cov.ordinal > 0) {
                            stringResource(R.string.player_episode_label, cov.season, cov.ordinal)
                        } else {
                            null
                        }
                    }
                } else {
                    null
                }
                val xrayLine = xrayContextLine(
                    episodeLabel = episodeLabel,
                    quality = state.qualityLabel,
                    audio = state.audioTracks.firstOrNull { it.selected }?.label?.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.player_audio_label, it) },
                    subtitle = stringResource(
                        R.string.player_subtitles_label,
                        subtitleStatusLabel(state, stringResource(R.string.player_off_label)),
                    ),
                )
                if (xrayLine.isNotBlank()) {
                    val hasCast = state.castNames.isNotEmpty()
                    val expandLabel = stringResource(
                        if (xrayExpanded) R.string.player_xray_hide_cast else R.string.player_xray_show_cast,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .then(
                                if (hasCast) {
                                    Modifier
                                        .clickable(
                                            role = Role.Button,
                                            onClick = { xrayExpanded = !xrayExpanded },
                                        )
                                        .semantics { contentDescription = expandLabel }
                                } else {
                                    Modifier
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            xrayLine,
                            color = PlayerOnVideo.text.copy(alpha = 0.75f),
                            fontSize = brandSp(12),
                            maxLines = if (xrayExpanded) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (hasCast) {
                            Icon(
                                painter = painterResource(
                                    if (xrayExpanded) R.drawable.ic_action_keyboard_arrow_up
                                    else R.drawable.ic_action_keyboard_arrow_down,
                                ),
                                contentDescription = null,
                                tint = PlayerOnVideo.mutedText,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    if (xrayExpanded && hasCast) {
                        // A4-F3: rides ScrollingChipRow + RaddChip now — the fade
                        // and the row are the shared component, not a hand-rolled
                        // copy. The cast strip gains the fade the other rows
                        // already had.
                        ScrollingChipRow(
                            modifier = Modifier.padding(top = 8.dp),
                            contentPadding = PaddingValues(start = 0.dp, end = 16.dp),
                        ) {
                            // F-06: keyed by index + name, not by name alone —
                            // staged cast data can list the same actor twice, and
                            // two items sharing a Lazy key throw at composition.
                            // The duplicate chip still renders (same label, same
                            // search target); only the crash is gone.
                            itemsIndexed(state.castNames, key = { index, actor -> "$index:$actor" }) { _, actor ->
                                RaddChip(
                                    selected = false,
                                    onClick = { onActorClick(actor) },
                                    label = { Text(actor, maxLines = 1) },
                                    colors = raddFilterChipColors(),
                                )
                            }
                        }
                    }
                }
            }
            // P1-16 (road to 100/100, audit B-21): the KeepChip row is
            // gone — its own 48dp row above the scrubber made the paused
            // stack three rows tall. The keep status rides the
            // scrubber/time row now ([KeepTimeRowStatus], below); nothing
            // about the keep is deleted, only moved.
            // F19b (Batch A): the seek bar and its time row only exist once the
            // player knows a duration. A live stream never reports one
            // (ExoPlayer's duration stays TIME_UNSET, so durationMs stays 0),
            // which used to leave a dead scrubber reading "0:00 / --:--" under a
            // live picture. A VOD's duration is known at prepare, so the bar
            // appears as soon as playback can start; while the start card is up
            // the card itself narrates the phase, so nothing is lost.
            // F29 (Batch B): the duration gate was not enough — a live HLS
            // playlist can report a finite DVR-window duration (Discover
            // Pakistan: 2:00:12), and the scrubber came back with it. The
            // gate is the explicit live flag now: a live stream gets no
            // scrubber and no time row at all, only a LIVE mark in their
            // place, whatever duration the playlist claims.
            if (!state.isLive && state.durationMs > 0) {
                // MX #6 (chrome diet): the time row is merged into the
                // scrubber row — position (+ status word) flanks the track
                // on the left, duration on the right, the YouTube pattern.
                // One full text row leaves the bottom bar; every value
                // stays, including the status word (audit #3/#4: the viewer
                // must tell loading, paused and buffering apart at a
                // glance). The "--:--" fallback stays even though the guard
                // above already proves a duration — a duration the player
                // has not learned yet is not a time to show.
                val statusWord = when (
                    playerStatusWord(state.startPhase, state.isBuffering, state.isPlaying)
                ) {
                    PlayerStatusWord.LOADING -> stringResource(R.string.app_loading)
                    PlayerStatusWord.BUFFERING -> stringResource(R.string.player_buffering)
                    PlayerStatusWord.PAUSED -> stringResource(R.string.player_paused)
                    PlayerStatusWord.NONE -> null
                }
                Row(
                    Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.positionMs.formatTime(),
                        color = PlayerOnVideo.text,
                        // Cinema (Nov 2026): the time reads at 11sp, not 12sp.
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (statusWord != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(statusWord, color = PlayerOnVideo.mutedText, style = MaterialTheme.typography.labelSmall)
                    }
                    Scrubber(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        chrome = chrome,
                        positionMs = state.positionMs,
                        bufferedMs = state.bufferedMs,
                        durationMs = state.durationMs,
                        enabled = state.seekEnabled,
                        onSeek = { onSeekTo(it) },
                        onScrubPreview = onScrubPreview,
                        onScrubCommit = onScrubCommit,
                        onActivity = onActivity,
                        episodes = state.activeRendition?.coverage?.episodes.orEmpty(),
                    )
                    Text(
                        state.durationMs.takeIf { it > 0 }?.formatTime() ?: "--:--",
                        color = PlayerOnVideo.text,
                        // Cinema (Nov 2026): the time reads at 11sp, not 12sp.
                        style = MaterialTheme.typography.labelSmall,
                    )
                    // P1-16 (road to 100/100, audit B-21): the keep status
                    // rides this row now — the KeepChip row above the
                    // scrubber is gone. Same gate, same words, same live
                    // ring as the chip had; the tap still keeps the title.
                    if (keepStatusVisible(state)) {
                        Spacer(Modifier.width(8.dp))
                        KeepTimeRowStatus(state, onKeepCurrent)
                    }
                }
            } else if (state.isLive) {
                // Review fix (16 Nov): the single live mark in the whole
                // chrome — jade while playing, muted grey reading PAUSED
                // while paused, so a frozen live picture never reads live.
                // P1-16: a live title with a keep rides the same row as
                // the mark — live has no time row to carry the status.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    LiveMark(paused = !state.isPlaying)
                    if (keepStatusVisible(state)) {
                        Spacer(Modifier.width(8.dp))
                        KeepTimeRowStatus(state, onKeepCurrent)
                    }
                }
            }
            // Review fix (16 Nov): a D-43 reversal. SpaceEvenly spread the six
            // primaries into a row of identical islands with no relation between
            // them; the groups now read as two clusters — transport on the
            // left, tools on the right — the way every rival lays them out.
            Row(
                Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val subtitleWord = subtitleStatusLabel(state, stringResource(R.string.player_off))
                val volumePercent = (state.userVolume * 100).toInt()
                // P1 (player fixes): exactly six primaries, in two clusters —
                // transport on the left (Play/Pause, Volume), tools on the
                // right (2 flexible slots, Fullscreen, More). The flexible
                // slots resolve by priority ([resolveFlexiblePlayerControls]),
                // so the row is always six and a hidden seventh button would
                // still be a button that lies. Everything not in the row lives
                // in the More sheet — nothing was deleted, only moved.
                Row(
                    modifier = Modifier.testTag("player-controls-left"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlayerControlButton(
                        { if (state.isEnded) onPlayFromStart() else onTogglePlayPause() },
                        if (state.isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                        // Cinema (Nov 2026): the primary glyph is 36dp; the
                        // 48dp press target stays.
                        iconSize = 36.dp,
                    ) {
                        Icon(
                            painter = if (state.isPlaying) painterResource(R.drawable.ic_player_pause) else painterResource(R.drawable.ic_player_play),
                            contentDescription = null,
                            tint = PlayerOnVideo.text,
                        )
                    }
                    PlayerControlButton(
                        { onToggleMute() },
                        if (state.userVolume > 0f) stringResource(R.string.player_volume, volumePercent)
                        else stringResource(R.string.player_volume_muted),
                        onLongClick = { showVolumeDialog = true },
                    ) {
                        if (state.userVolume > 0f) {
                            Icon(
                                painter = painterResource(R.drawable.ic_player_volume),
                                contentDescription = null,
                                tint = PlayerOnVideo.text,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_player_volume_muted),
                                contentDescription = null,
                                tint = PlayerOnVideo.text,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.testTag("player-controls-right"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // P1 (player fixes): the right cluster is 2 flexible slots
                    // + Fullscreen + More — 4 permanent + 2 flexible = 6,
                    // always, by construction ([resolveFlexiblePlayerControls]).
                    // The default title (no next episode, 1x speed, one audio
                    // track, no cast target) resolves to Subtitles + Quality,
                    // exactly the row this replaced — so the common case does
                    // not move, and the row only changes when the context does.
                    val flexible = resolveFlexiblePlayerControls(
                        hasNextEpisode = state.nextEpisodeTitle != null || state.endedHasNextEpisode,
                        castAvailable = state.castAvailable,
                        speed = state.speed,
                        hasMultipleAudioTracks = state.canCycleAudio,
                    )
                    flexible.forEach { slot ->
                        when (slot) {
                            FlexiblePlayerControl.NEXT_EPISODE -> PlayerControlButton(
                                { onRequestNextEpisode() },
                                stringResource(R.string.player_next_episode),
                            ) {
                                Icon(painter = painterResource(R.drawable.ic_player_skip_next), contentDescription = null, tint = PlayerOnVideo.text)
                            }
                            // Cast never renders while CAST_PATH_PROVEN is
                            // false (the resolver already excludes it) — the
                            // branch exists so the `when` stays exhaustive
                            // when the spike is proven and the flag flips.
                            FlexiblePlayerControl.CAST -> Unit
                            FlexiblePlayerControl.SPEED -> SpeedPill(
                                speed = state.speed,
                                onShowSpeedSheet = onShowSpeedSheet,
                            )
                            FlexiblePlayerControl.AUDIO -> {
                                // P6: the audio button names the track in play
                                // — the "current state is visible" pattern, the
                                // same one the speed pill and the subtitles
                                // label already follow.
                                val audioLabel = state.audioTracks
                                    .firstOrNull { it.selected }?.label?.takeIf { it.isNotBlank() }
                                PlayerControlButton(
                                    { onShowAudioSheet() },
                                    audioLabel?.let { stringResource(R.string.player_audio_label, it) }
                                        ?: stringResource(R.string.player_audio_tracks),
                                ) {
                                    Icon(painter = painterResource(R.drawable.ic_player_audiotrack), contentDescription = null, tint = PlayerOnVideo.text)
                                }
                            }
                            FlexiblePlayerControl.SUBTITLES -> PlayerControlButton(
                                { onShowSubtitleSheet() },
                                stringResource(R.string.player_subtitles_label, subtitleWord),
                                selected = subtitleActive(state),
                            ) {
                                Icon(painterResource(R.drawable.ic_player_subtitles), null, tint = if (subtitleActive(state)) RaddCurrent500 else PlayerOnVideo.text)
                            }
                            FlexiblePlayerControl.QUALITY -> QualityPill(
                                state = state,
                                onShowQualitySheet = onShowQualitySheet,
                                onMessage = onMessage,
                            )
                        }
                    }
                    PlayerControlButton(
                        { onToggleFullscreen() },
                        if (state.fullscreen) stringResource(R.string.player_exit_fullscreen)
                        else stringResource(R.string.player_fullscreen),
                    ) {
                        if (state.fullscreen) {
                            Icon(
                                painter = painterResource(R.drawable.ic_player_fullscreen_exit),
                                contentDescription = null,
                                tint = PlayerOnVideo.text,
                            )
                        } else {
                            Icon(painterResource(R.drawable.ic_player_fullscreen), null, tint = PlayerOnVideo.text)
                        }
                    }
                    PlayerControlButton(
                        { onShowMoreSheet() },
                        stringResource(R.string.player_more),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_player_more),
                            contentDescription = null,
                            tint = PlayerOnVideo.text,
                        )
                    }
                }
            }
        }
        // Volume long-press: a level slider with the % in words, so the exact level
        // is reachable without the gesture. Dragging writes straight to the same
        // setUserVolume the gesture uses, so mute remembers the restore point.
        if (showVolumeDialog) {
            AlertDialog(
                onDismissRequest = { showVolumeDialog = false },
                title = { RaddDialogTitle(stringResource(R.string.player_volume_title)) },
                text = {
                    Column {
                        // Robo run 4: this level line says "Volume 100%" — and so
                        // does the HUD toast — so the two read identically. The
                        // dialog is the level control, so it announces itself as
                        // one ("Volume level, 100 percent") while the paint stays
                        // the short "Volume 100%".
                        val volumeLevelSpoken = stringResource(
                            R.string.player_volume_level,
                            (state.userVolume * 100).toInt(),
                        )
                        Text(
                            stringResource(R.string.player_volume, (state.userVolume * 100).toInt()),
                            modifier = Modifier.semantics { contentDescription = volumeLevelSpoken },
                        )
                        Slider(
                            value = state.userVolume,
                            onValueChange = { onSetUserVolume(it) },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Volume boost (MX idea #8, verified 2026-11-24):
                        // the slider runs the full 0..200%
                        // (AudioBoostUi.MAX_OFFERED_LEVEL) because the
                        // software gain stage is real — the part of the
                        // level above unity goes to the enhancement
                        // processor in the service's audio sink chain
                        // (PlaybackService collects the store's
                        // audioEnhancement and applies it live), so
                        // every stop on it is applied. The old comment
                        // here said this build had no gain stage and the
                        // slider stopped at the 100% player ceiling; both
                        // were wrong — the slider never stopped at 100%,
                        // and the processor has been wired since MX #8.
                        // The summary below says what was requested and
                        // what was actually applied. The requested level lives
                        // in this dialog only — the stored level is the
                        // clamped one, and a request that was never applied is
                        // not a setting to persist.
                        var requestedBoost by remember { mutableStateOf(state.audioBoostLevel) }
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.player_volume_boost))
                        Slider(
                            value = requestedBoost,
                            onValueChange = {
                                requestedBoost = it
                                onSetAudioBoostLevel(it)
                            },
                            valueRange = 0f..AudioBoostUi.MAX_OFFERED_LEVEL,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            AudioBoostUi.honestSummary(requestedBoost, state.userVolume),
                            color = PlayerOnVideo.faintText,
                            fontSize = brandSp(12),
                        )
                    }
                },
                confirmButton = {
                    TextButton({ showVolumeDialog = false }) {
                        Text(stringResource(R.string.player_close))
                    }
                },
            )
        }
    }
}

private fun subtitleActive(state: PlayerUiState) =
    state.selectedSubtitleId != null || state.customSubtitleLoaded != null ||
        state.nativeExternals.isNotEmpty()

/**
 * The word the subtitles control speaks: which text is loaded, or an honest "off". Internal
 * so `PlayerControlSemanticsTest` can assert a named control is never rendered nameless.
 */
internal fun subtitleStatusLabel(state: PlayerUiState, offWord: String): String =
    state.customSubtitleLoaded
        ?: state.selectedSubtitleId?.let { id -> state.subtitleTracks.firstOrNull { it.id == id }?.label }
        ?: offWord

/**
 * P9 (round 2) — the X-Ray context line: episode, rung, audio track,
 * subtitle track, joined with middots. Any part that is blank is
 * omitted — a missing track is never invented, and a movie (no episode
 * label) never prints an episode. The episode label arrives already
 * formatted from the player_episode_label string resource —
 * user-visible words live in strings.xml, never in code.
 */
internal fun xrayContextLine(
    episodeLabel: String?,
    quality: String?,
    audio: String?,
    subtitle: String?,
): String = listOfNotNull(
    episodeLabel?.takeIf { it.isNotBlank() },
    quality?.takeIf { it.isNotBlank() },
    audio?.takeIf { it.isNotBlank() },
    subtitle?.takeIf { it.isNotBlank() },
).joinToString(" · ")

/**
 * P1-16 (road to 100/100, audit B-21): whether the keep status shows in
 * the scrubber/time row. The exact gate the old KeepChip row used, moved
 * here with the status: App-design audit #7 — an automatic ahead-window
 * copy never announces itself when the viewer just pressed Play (its
 * progress lives in Downloads); Player review D3 — a keep the viewer
 * TAPPED is theirs to watch, so [KeepUiState.Keeping] shows only while
 * [PlayerUiState.keepRequested] is true, and `refreshKeepState` resets
 * that flag on every switch so a stale request cannot un-silence the
 * next title's automatic copy.
 */
internal fun keepStatusVisible(state: PlayerUiState): Boolean {
    val keep = state.keep
    if (keep is KeepUiState.Unavailable) return false
    if (keep is KeepUiState.Keeping && !state.keepRequested) return false
    return true
}

/**
 * P1-16 (road to 100/100, audit B-21): the keep status as one small
 * element of the scrubber/time row, after the duration (beside the LIVE
 * mark on a live title, which has no time row). The KeepChip row above
 * the scrubber is gone; this is the same status in the row the audit
 * names — same words ([CacheAffordance.chipText]), same live ring
 * ([CacheAffordance.keepingProgress], only while the total is known —
 * a ring at 0% with an unknown total is the Session 120 lie in a
 * circle), same state tint the chip used, and the tap still runs
 * [onKeepCurrent]. The 12dp vertical padding gives the tap a ~40dp
 * target inside the row's 48dp height without growing the row.
 */
@Composable
internal fun KeepTimeRowStatus(
    state: PlayerUiState,
    onKeepCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!keepStatusVisible(state)) return
    val keep = state.keep
    val stateTint = when (keep) {
        is KeepUiState.Stored -> RaddSuccess
        is KeepUiState.Blocked -> RaddDanger
        is KeepUiState.Keeping -> RaddTideIndigo
        else -> Color.White
    }
    Row(
        modifier = modifier
            .padding(vertical = 12.dp)
            .clickableNoRipple(onClick = onKeepCurrent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val progress = CacheAffordance.keepingProgress(keep)
        if (progress != null) {
            CircularProgressIndicator(
                progress = progress,
                modifier = Modifier.size(14.dp),
                color = stateTint,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_action_download),
                contentDescription = null,
                tint = stateTint,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            CacheAffordance.chipText(keep),
            color = PlayerOnVideo.mutedText,
            fontSize = brandSp(11),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 110.dp),
        )
    }
}

/**
 * P10 (round 2) — the zoom reset chip. Pinch is the only on-screen path
 * out of ZOOM today, and it is not discoverable; while zoomed, this chip
 * is the one visible tap back to Fit. The screen composes it only while
 * the aspect is not FIT, the player is not locked, and Ghost mode is
 * off — the chip itself never decides visibility.
 */
@Composable
internal fun ZoomResetChip(
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // stringResource is a @Composable call — it cannot run inside the
    // semantics block below (not a @Composable context), so the
    // description is read here, in the composable body.
    val resetDescription = stringResource(R.string.player_zoom_reset)
    // Round 6: the chip presses with the same default-effects spring +
    // haptic tick as the other controls — until now it pressed with no
    // scale and no tick. The clickable keeps no ripple (borderless
    // chip), but shares its interaction source with pressScale so the
    // scale tracks the real press state.
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .semantics { contentDescription = resetDescription }
            .pressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onReset,
            ),
    ) {
        PressHaptics(interactionSource)
        Text(
            text = AspectMode.FIT.label,
            color = Color.White,
            fontSize = brandSp(12),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
