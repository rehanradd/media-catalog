package com.raddstream.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raddstream.app.R
import com.raddstream.app.player.EpisodeCoverage
import com.raddstream.app.player.chapterTicks
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.ui.theme.RaddCurrent500
import com.raddstream.app.ui.theme.RaddInk950
import com.raddstream.app.ui.theme.PlayerChromeTheme
import com.raddstream.app.viewmodel.formatTime


/** Android's minimum touch target. Every press in this file is at least this wide and tall. */
internal val MinControlTarget = 48.dp

/**
 * The one press a player control uses: a [MinControlTarget] circle, [label] as the words a
 * screen reader says, and [Role.Button] so TalkBack offers the press itself as an action.
 *
 * Both halves were missing before. The control lane forced a 42 dp slot and the cinema chips
 * 38 dp — both under the minimum a fingertip is entitled to — and each drew an Icon whose own
 * description is deliberately null, so the sixteen named lane controls announced nothing at
 * all. A preference a viewer cannot hear is a preference only sighted users can set.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ControlTarget(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    // D-43: a selected control shows a filled state — a jade disc behind the
    // icon — so the viewer can see at a glance which control is active. A
    // disabled control dims and does not fire.
    selected: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val spoken = label
    // Long-press is opt-in per control (the Volume button's level slider). A control
    // without one keeps the plain clickable below — the press the semantics test pins.
    val base = modifier
        .size(MinControlTarget)
        .clip(CircleShape)
        .background(
            if (selected) RaddCurrent500.copy(alpha = 0.25f) else Color.Transparent,
            CircleShape,
        )
        .alpha(if (enabled) 1f else 0.4f)
    val pressModifier = if (onLongClick != null) {
        base
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button, enabled = enabled)
            .semantics { contentDescription = spoken }
    } else {
        base
            .clickable(onClick = onClick, role = Role.Button, enabled = enabled)
            .semantics { contentDescription = spoken }
    }
    Box(
        pressModifier,
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
internal fun PlayerControlButton(
    onClick: () -> Unit,
    label: String,
    onLongClick: (() -> Unit)? = null,
    // The redesign removed the visible label under the icon (D-43): the bar is
    // one thin icon row now, and the spoken label on the button still says
    // what each glyph does. The icon box grows for the play/pause primary
    // (36dp) while the 48dp press target stays.
    selected: Boolean = false,
    enabled: Boolean = true,
    // Cinema (Nov 2026): the glyph is 22dp; the 48dp press target in
    // ControlTarget stays.
    iconSize: Dp = 22.dp,
    content: @Composable () -> Unit,
) {
    ControlTarget(label = label, onClick = onClick, onLongClick = onLongClick, selected = selected, enabled = enabled) {
        Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * Mockup: quality is a text pill, not an icon — the rung itself ("1080P",
 * "Auto") is the label, so the viewer reads the quality without opening
 * the sheet. Same press contract as the other primaries (48dp minimum,
 * Button role, spoken label), only the shape is a rounded rectangle, so it
 * cannot ride [ControlTarget]'s circle clip. Single rung: the pill stays in
 * FULL white and tapping it says why instead of opening a sheet with one
 * row — the 25c rule, unchanged. Review fix (16 Nov): the old pill painted
 * the rung at 40% opacity, which reads as a disabled control on a tappable
 * one — a tappable control never dims. A hand-picked rung prints jade, Auto
 * white.
 */
@Composable
internal fun QualityPill(
    state: PlayerUiState,
    onShowQualitySheet: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val label = stringResource(R.string.player_quality_label, state.qualityLabel)
    val singleQualityMessage = stringResource(R.string.player_quality_single)
    val textColor = if (!state.qualityAuto) RaddCurrent500 else Color.White
    Box(
        modifier = Modifier
            .heightIn(min = MinControlTarget)
            .clip(RoundedCornerShape(8.dp))
            // Cinema (Nov 2026): no outline — the rung is plain bold text on a
            // translucent solid fill; the border was the outline this slice removes.
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clickable(
                onClick = {
                    if (state.canSwitchQuality) onShowQualitySheet()
                    else onMessage(singleQualityMessage)
                },
                role = Role.Button,
            )
            .semantics { contentDescription = label }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = state.qualityLabel,
            color = textColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** Thin MX scrubber: grey track, brighter buffered segment, theme played segment + thumb. */
@Composable
internal fun Scrubber(
    modifier: Modifier = Modifier,
    chrome: PlayerChromeTheme,
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    onScrubPreview: (Long) -> Unit = {},
    onScrubCommit: (Long) -> Unit = onSeek,
    onActivity: () -> Unit,
    episodes: List<EpisodeCoverage> = emptyList(),
) {
    var draggingTargetMs by remember { mutableStateOf<Long?>(null) }
    val displayPositionMs = draggingTargetMs ?: positionMs
    // Chapter ticks (plan §5 step 11): the pack's episode boundaries, from the same
    // pure function the print sheet's chapter list uses, so the two can never disagree.
    val ticks = remember(episodes, durationMs) { chapterTicks(episodes, durationMs) }
    // PL-8: the scrubber is a progress bar to a screen reader — its description
    // names the position and the chapter the position is inside, and setProgress
    // lets a switch or voice user seek without ever touching the track.
    val currentChapter = ticks.lastOrNull { it.offsetMs <= displayPositionMs }?.title
    val seekDescription = stringResource(
        R.string.player_seek,
        displayPositionMs.formatTime(),
        durationMs.formatTime(),
    ) + (currentChapter?.let { stringResource(R.string.player_seek_chapter, it) } ?: "")
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            // Hate-audit H6: 24dp of track + 12dp of padding each side = a
            // 48dp touch height, the floor the 44dp row missed. The thumb
            // stays 14dp at rest (18dp dragging); only the grab area grows.
            // Review fix (16 Nov): no horizontal padding of its own — the
            // column's shared rail is the left/right edge now.
            .padding(vertical = 12.dp)
            .height(24.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = seekDescription
                if (enabled && durationMs > 0) {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = displayPositionMs.toFloat() / durationMs.coerceAtLeast(1L),
                        range = 0f..1f,
                    )
                    setProgress { value ->
                        onSeek((value.coerceIn(0f, 1f) * durationMs).toLong())
                        true
                    }
                }
            },
    ) {
        val trackWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val duration = durationMs.coerceAtLeast(1L)
        val posFraction = (displayPositionMs.toFloat() / duration).coerceIn(0f, 1f)
        val bufFraction = (bufferedMs.toFloat() / duration).coerceIn(0f, 1f)

        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                // Cinema (Nov 2026): a 3dp track, not 4dp.
                .height(3.dp)
                .background(Color.White.copy(alpha = 0.28f)),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(bufFraction)
                .height(3.dp)
                .background(Color.White.copy(alpha = 0.5f)),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(posFraction)
                .height(3.dp)
                .background(chrome.played),
        )
        // One short white mark per chapter, straddling the track. Tapping one seeks —
        // the track's own tap-to-seek already does that, so a tick needs no gesture
        // of its own. The thumb passes over them; that is the point of a mark.
        ticks.forEach { tick ->
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (maxWidth * tick.fraction) - 1.dp)
                    .width(2.dp)
                    .height(9.dp)
                    .background(Color.White.copy(alpha = 0.85f)),
            )
        }
        val isDragging = draggingTargetMs != null
        val thumbSize = if (isDragging) 18.dp else 14.dp
        // Cinema (Nov 2026): the thumb is a solid white dot — the old 2dp
        // white ring separated a jade dot from the jade track, and white on
        // jade reads without it. No outline, like every other glyph now.
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = (maxWidth * posFraction.coerceIn(0f, 0.98f)) - (thumbSize - 14.dp) / 2)
                .size(thumbSize)
                .background(Color.White, shape = CircleShape),
        )
        if (enabled && durationMs > 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(durationMs) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            fun targetFor(x: Float): Long =
                                ((x / trackWidthPx).coerceIn(0f, 1f) * durationMs).toLong().coerceAtLeast(0)
                            var latestTarget = targetFor(down.position.x)
                            draggingTargetMs = latestTarget
                            onActivity()
                            onScrubPreview(latestTarget)
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    latestTarget = targetFor(change.position.x)
                                    if (!change.pressed) {
                                        break
                                    }
                                    draggingTargetMs = latestTarget
                                    onActivity()
                                    onScrubPreview(latestTarget)
                                    change.consume()
                                }
                            } finally {
                                draggingTargetMs = null
                                onScrubCommit(latestTarget)
                            }
                        }
                    },
            )
        }
    }
}

/**
 * Review fix (16 Nov): the locked state is a pill, not a floating button —
 * an outlined padlock + "Screen locked • Tap to unlock", centred at the
 * bottom, always visible while locked. The old button was a small lock-open
 * icon at the right edge that said nothing about what to do. The 2.5s
 * flash-on-tap from the spec is not implemented: the pill stays up the
 * whole time the screen is locked, so a viewer who locks by accident can
 * always see how to get out.
 */
@Composable
internal fun UnlockFab(onUnlock: () -> Unit) {
    val hint = stringResource(R.string.player_locked_hint)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            color = RaddInk950,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier
                .padding(bottom = 48.dp)
                // Cinema (Nov 2026): no outline — the solid fill is the pill.
                .clickable(onClick = onUnlock, role = Role.Button)
                .semantics { contentDescription = hint },
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_player_lock_open),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    hint,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}
