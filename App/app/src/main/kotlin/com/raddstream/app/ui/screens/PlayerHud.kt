package com.raddstream.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raddstream.app.R
import com.raddstream.app.player.MediaSegment
import com.raddstream.app.player.MediaSegmentType
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.player.showInPlaybackNext
import com.raddstream.app.player.ScreenZone
import com.raddstream.app.player.ScrubThumbnails
import com.raddstream.app.player.subtitles.SubtitleOffset
import com.raddstream.app.ui.components.rememberReduceMotion
import com.raddstream.app.ui.theme.RaddCurrent500
import com.raddstream.app.ui.theme.RaddMotion
import com.raddstream.app.ui.theme.PlayerChromeTheme
import com.raddstream.app.ui.theme.RaddHudPillIdle
import com.raddstream.app.ui.theme.RaddHudToast
import com.raddstream.app.ui.theme.brandSp
import com.raddstream.app.ui.theme.paint
import com.raddstream.app.viewmodel.formatTime
import kotlinx.coroutines.delay


// ── 4. Transient feedback ──────────────────────────────────────────────────────

@Composable
internal fun DoubleTapRipple(state: PlayerUiState) {
    // A held copy, not the live state: the 900 ms lifetime ends by setting the ripple to null,
    // and the pill that is fading out still has to name the side and the seconds it was showing.
    // Reading the live value first means the frame a tap lands on is never the previous pill's.
    var held by remember { mutableStateOf(state.ripple) }
    val ripple = state.ripple ?: held ?: return
    state.ripple?.let { held = it }
    val forward = ripple.deltaMs >= 0
    val chrome = PlayerChromeTheme.Default
    val label = (if (forward) "+" else "−") + (kotlin.math.abs(ripple.deltaMs) / 1000L) + "s"
    val alignment = when (ripple.zone) {
        ScreenZone.LEFT -> Alignment.CenterStart
        ScreenZone.RIGHT -> Alignment.CenterEnd
        ScreenZone.CENTER -> Alignment.Center
    }
    Box(Modifier.fillMaxSize(), contentAlignment = alignment) {
        // P-3 (emulator audit): the HUD itself is a plain conditional in
        // main now, so this ripple pill is the last fade in the player —
        // and it ignored Reduce Motion. Under reduced motion it crossfades
        // in 30 ms (the NavGraph pattern) instead of riding the effects
        // springs. The acknowledgement still appears rather than arrives:
        // quick in, slow out, so a double-tap over a dark scene is never
        // a flash.
        // P5 (player fixes) — BLOCKED on the pinned BOM: MaterialTheme.motionScheme
        // is internal in material3 1.4.0, so the fades ride the same M3 Expressive
        // effects token values via RaddMotion (fastEffects in, defaultEffects out),
        // with the same 30ms crossfade under reduced motion ([REDUCED_MOTION_FADE_MS]).
        val reduceMotion = rememberReduceMotion()
        AnimatedVisibility(
            visible = state.ripple != null,
            enter = fadeIn(if (reduceMotion) tween(REDUCED_MOTION_FADE_MS) else RaddMotion.fastEffects()),
            exit = fadeOut(if (reduceMotion) tween(REDUCED_MOTION_FADE_MS) else RaddMotion.defaultEffects()),
        ) {
            Surface(
                color = chrome.card,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(horizontal = 34.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = if (forward) painterResource(R.drawable.ic_player_forward_10) else painterResource(R.drawable.ic_player_replay_10),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

internal sealed class Hud {
    data class Brightness(val value: Float) : Hud()
    data class Volume(val value: Float) : Hud()
    object Boost : Hud()
    /** A subtitle-gesture readout, shown as a pill. The value travels; the label is
     *  formatted where it is drawn, because the gesture lanes cannot call stringResource. */
    data class Subtitle(val value: SubtitleHudValue) : Hud()
}

/**
 * Cinema player (Nov 2026): the chrome budget, in one place, so the 95/5 rule is
 * a number, not a feeling. TopBar 56dp + BottomControls 96dp = 152dp of chrome
 * on a 411dp-tall landscape phone — 63% of the picture stays video while the
 * chrome is up, 100% once the 3.0s auto-hide fires. The 48dp press target never
 * shrinks; only the visible glyphs do.
 */
internal object CinemaChrome {
    const val TOP_BAR_MIN_DP = 56
    /** P4 (player fixes): the top bar for a two-line title — 2×20sp title + 12sp subtitle + padding. */
    const val TOP_BAR_TWO_LINE_DP = 72
    /** P1 (player fixes): 104 → 96dp — scrubber 48 + controls 48, with the 8dp of
     * bottom padding gone (the bar sits flush to the immersive frame's bottom
     * edge). The press targets stay 48dp; the bar shrinks by tightening the
     * gap, never by shrinking a target. */
    const val BOTTOM_BAR_MIN_DP = 96
    const val HUD_LIFETIME_MS = 800L
    const val MIN_TOUCH_TARGET_DP = 48

    /** Share of the picture that stays video while the chrome is up, on a screen
     *  [screenHeightDp] tall in landscape. */
    fun videoSharePercent(screenHeightDp: Int): Float {
        if (screenHeightDp <= 0) return 0f
        val chrome = TOP_BAR_MIN_DP + BOTTOM_BAR_MIN_DP
        return (screenHeightDp - chrome).coerceAtLeast(0) / screenHeightDp.toFloat()
    }
}

/** Which screen edge a gesture readout rides. Brightness is the left half's
 *  gesture, volume the right half's — the indicator sits on the same side. */
internal enum class EdgeSide { LEFT, RIGHT }

internal enum class EdgeKind { BRIGHTNESS, VOLUME, BOOST }

/** The paint-only shape of the edge indicator: side, level, kind. The subtitle
 *  readout is a centred pill, never an edge line, so it maps to null. */
internal data class EdgeIndicatorSpec(
    val side: EdgeSide,
    val fraction: Float,
    val kind: EdgeKind,
)

internal fun edgeIndicatorSpec(hud: Hud): EdgeIndicatorSpec? = when (hud) {
    is Hud.Brightness -> EdgeIndicatorSpec(EdgeSide.LEFT, hud.value.coerceIn(0f, 1f), EdgeKind.BRIGHTNESS)
    is Hud.Volume -> EdgeIndicatorSpec(EdgeSide.RIGHT, hud.value.coerceIn(0f, 1f), EdgeKind.VOLUME)
    is Hud.Boost -> EdgeIndicatorSpec(EdgeSide.RIGHT, 1f, EdgeKind.BOOST)
    is Hud.Subtitle -> null
}

/**
 * What a subtitle gesture changed — a value, not a sentence. The lanes report these and the
 * pill formats them, the same split the brightness/volume edge lines already use.
 */
internal sealed interface SubtitleHudValue {
    data class Delay(val delayMs: Long) : SubtitleHudValue
    data class Size(val percent: Int) : SubtitleHudValue
    data class Position(val percent: Int) : SubtitleHudValue
    data class LineJump(val next: Boolean) : SubtitleHudValue
}

/**
 * Gesture feedback as a slim line on the screen edge — never a card in the middle of the
 * picture. Brightness rides the LEFT edge, volume the RIGHT (the gesture halves); the
 * long-press speed boost rides the right edge in jade. The 800 ms lifetime lives in the
 * caller's `LaunchedEffect(hud)`, so this is paint only: a tiny 16dp icon over a 3dp
 * vertical track, nothing else to read while a movie is playing. Cinema (Nov 2026):
 * the old 12sp text label is gone — the bar is the level, the icon says which one.
 */
@Composable
internal fun EdgeFeedbackLine(hud: Hud) {
    // The subtitle readout is a centred pill (SubtitleToast), not an edge line.
    val spec = edgeIndicatorSpec(hud) ?: return
    val label = when (hud) {
        is Hud.Brightness -> stringResource(R.string.player_brightness, (hud.value * 100).toInt())
        is Hud.Volume -> stringResource(R.string.player_volume, (hud.value * 100).toInt())
        is Hud.Boost -> "2×"
        is Hud.Subtitle -> "" // unreachable: the spec is null above; the when must stay exhaustive
    }
    val icon = when (spec.kind) {
        EdgeKind.BRIGHTNESS -> painterResource(R.drawable.ic_player_brightness_medium)
        EdgeKind.VOLUME -> painterResource(R.drawable.ic_player_volume_up)
        EdgeKind.BOOST -> painterResource(R.drawable.ic_player_fast_forward)
    }
    val trackColor = if (spec.kind == EdgeKind.BOOST) RaddCurrent500 else Color.White
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = if (spec.side == EdgeSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Column(
            // F-04: the edge readout is transient paint — without a live
            // region TalkBack never announces a brightness/volume/boost
            // change as it happens. Polite, merged so the icon's spoken
            // label ("Volume 80%") is the announced text.
            Modifier
                .padding(horizontal = 12.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The spoken label stays as the content description so the string is
            // still used and a screen reader can name the indicator.
            Icon(painter = icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .width(3.dp)
                    .height(96.dp)
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(spec.fraction)
                        .align(Alignment.BottomCenter)
                        .background(trackColor),
                )
            }
        }
    }
}

@Composable
internal fun ScrubToast(
    target: Long,
    durationMs: Long,
    /** The sprite-sheet frame for the target's bucket, or null when that stretch was never watched. */
    thumbnail: Bitmap?,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Cinema (Nov 2026): the seat clears the slimmer bottom bar (96dp)
            // with the same 12dp of air the old 130dp seat left over the 136dp bar.
            modifier = Modifier.padding(bottom = 108.dp),
        ) {
            // The thumbnail: a captured cell of the sprite sheet, shown only
            // when the target's bucket has a frame (P2-5, B-23 — no second
            // player any more). Until then, or when the stretch was never
            // watched, only the time shows — never a frame from another scene.
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(
                            ScrubThumbnails.THUMBNAIL_WIDTH_DP.dp,
                            ScrubThumbnails.THUMBNAIL_HEIGHT_DP.dp,
                        )
                        .padding(bottom = 8.dp),
                )
            }
            Surface(
                color = RaddHudToast,
                shape = MaterialTheme.shapes.small,
                // F-04: the scrub readout is a transient toast — a polite
                // live region so TalkBack announces the target time as it
                // moves. Merged so the time text is the announced text.
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
            ) {
                Text(
                    stringResource(R.string.player_time_of, target.formatTime(), durationMs.formatTime()),
                    color = Color.White,
                    fontSize = brandSp(12),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * The subtitle-gesture readout: the same pill shape as the scrub toast, same seat above the
 * subtitles, one value and nothing else — the delay, the size, the seat, or the line jumped
 * to. It shares the 800 ms lifetime of the other HUDs (`LaunchedEffect(hud)`).
 */
@Composable
internal fun SubtitleToast(value: SubtitleHudValue) {
    val text = when (value) {
        is SubtitleHudValue.Delay -> stringResource(
            R.string.player_subtitle_sync,
            SubtitleOffset.format(value.delayMs),
        )
        is SubtitleHudValue.Size -> stringResource(R.string.player_subtitle_size, value.percent)
        is SubtitleHudValue.Position -> stringResource(R.string.player_subtitle_position, value.percent)
        is SubtitleHudValue.LineJump -> stringResource(
            if (value.next) R.string.player_subtitle_next_line else R.string.player_subtitle_previous_line,
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            color = RaddHudToast,
            shape = MaterialTheme.shapes.small,
            // Cinema (Nov 2026): same 116dp seat as the scrub toast — above the
            // slimmer bottom bar, not floating mid-picture.
            // F-04: polite live region, merged so the pill's own text is
            // what TalkBack announces when a subtitle gesture lands.
            modifier = Modifier
                .padding(bottom = 116.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                text,
                color = Color.White,
                fontSize = brandSp(12),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
internal fun BoostBadge() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Surface(
            color = RaddCurrent500,
            shape = MaterialTheme.shapes.small,
            // Cinema (Nov 2026): the seat clears the slimmer top bar (56dp).
            // F-04: polite live region, merged so the badge text is what
            // TalkBack announces when the speed boost starts.
            modifier = Modifier
                .padding(top = 64.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(R.drawable.ic_player_fast_forward), contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.player_boost_badge), color = Color.Black, fontSize = brandSp(12), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * MX #4 — the Skip Intro / Skip Recap pill. Bottom-right above the
 * controls, jade like the boost badge so it reads as a primary action,
 * with the segment's own label ("Skip Intro", never a generic "Skip").
 * A tap seeks to the window's end — or to the end of the file for an
 * open-ended credits window — through the ViewModel's [skipActiveSegment].
 * Internal (not private) so PlayerMockupTest can compose it directly.
 */
@Composable
internal fun SkipSegmentButton(segment: MediaSegment, onSkip: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Surface(
            color = RaddCurrent500,
            shape = MaterialTheme.shapes.small,
            // Cinema (Nov 2026): same 116dp seat as the toasts — above the
            // slimmer bottom bar.
            modifier = Modifier.padding(end = 16.dp, bottom = 116.dp),
        ) {
            Row(
                Modifier
                    .clickableNoRipple { onSkip() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painter = painterResource(R.drawable.ic_player_fast_forward), contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        when (segment.type) {
                            MediaSegmentType.INTRO -> R.string.player_skip_intro
                            MediaSegmentType.RECAP -> R.string.player_skip_recap
                            MediaSegmentType.CREDITS -> R.string.player_skip_credits
                            MediaSegmentType.PREVIEW -> R.string.player_skip_preview
                        },
                    ),
                    color = Color.Black, fontSize = brandSp(12), fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * REA #1: the in-playback Next Episode pill — the same bottom-right seat
 * as [SkipSegmentButton] (116 dp above the controls), dark instead of
 * jade so the two never read as the same action. Shown only in the last
 * 30 s with a prepared successor (see [showInPlaybackNext]); the tap is
 * the same path as the end card's Next button. [bottomPadding] is 152 dp
 * when the Skip button is showing (116 + ~28 pill + 8 gap) and 116 dp
 * when it is not, so the pill never covers the Skip button.
 */
@Composable
internal fun NextEpisodePill(bottomPadding: Dp, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Surface(
            color = RaddHudPillIdle,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.padding(end = 16.dp, bottom = bottomPadding),
        ) {
            Row(
                Modifier
                    .clickableNoRipple { onClick() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painter = painterResource(R.drawable.ic_player_skip_next), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.player_next_episode), color = Color.White, fontSize = brandSp(12), fontWeight = FontWeight.Bold)
            }
        }
    }
}
