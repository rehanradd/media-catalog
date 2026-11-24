package com.raddstream.app.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.raddstream.app.player.AspectMode
import com.raddstream.app.player.EDGE_DEAD_ZONE_DP
import com.raddstream.app.player.DominantAxis
import com.raddstream.app.player.DragCommand
import com.raddstream.app.player.GestureMapper
import com.raddstream.app.player.GestureSettings
import com.raddstream.app.player.PinchCommand
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.player.TapCommand
import com.raddstream.app.player.isInSubtitleZone
import com.raddstream.app.player.isSubtitleFling
import com.raddstream.app.player.subtitles.SubtitleOffset
import com.raddstream.app.player.dragHalfZone
import com.raddstream.app.player.dominantAxis
import com.raddstream.app.player.isInEdgeDeadZone
import com.raddstream.app.player.subtitleMoveFraction
import com.raddstream.app.player.subtitleRetimeSteps
import com.raddstream.app.player.subtitleSizeDelta
import com.raddstream.app.viewmodel.PlayerViewModel


/**
 * How long after a two-finger rewind the single-tap chrome toggle stays suppressed. The
 * two lanes see the same up event in different passes, so this is a safety margin for the
 * one that wins by a hair — not a debounce a user can feel.
 */
private const val TWO_FINGER_TAP_GUARD_NS = 250_000_000L

// ── 3. Gesture plane ───────────────────────────────────────────────────────────

@Composable
internal fun GestureOverlay(
    state: PlayerUiState,
    vm: PlayerViewModel,
    onToggleControls: () -> Unit,
    onBrightness: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onScrubPreview: (Long) -> Unit,
    onScrubCommit: (Long) -> Unit,
    onBoostStart: () -> Unit,
    onBoostEnd: () -> Unit,
    onSubtitleHud: (SubtitleHudValue) -> Unit,
) {
    val settings = GestureSettings(
        lockGestures = state.locked,
        pureCinema = state.pureCinema,
    )
    // Values that must be fresh inside long-lived pointer loops:
    val latestSettings by rememberUpdatedState(settings)
    val latestDuration by rememberUpdatedState(state.durationMs)
    val latestPosition by rememberUpdatedState(state.positionMs)
    val latestSubtitle by rememberUpdatedState(state.subtitle)
    val latestCustomSubtitleLoaded by rememberUpdatedState(state.customSubtitleLoaded)
    // Volume-gesture fix (Nov 2026): onVolume reads state.userVolume, a
    // snapshot — inside Lane C's never-restarted pointerInput(Unit) loop
    // the first composition's closure kept adding each frame's tiny delta
    // to the ORIGINAL volume, so a full swipe moved it ~1% and the
    // volume flickered instead of following the finger. latestOnVolume
    // keeps the base fresh; latestOnBrightness the same for safety
    // (brightness only worked by accident — it reads a live MutableState).
    val latestOnVolume by rememberUpdatedState(onVolume)
    val latestOnBrightness by rememberUpdatedState(onBrightness)
    val latestOnSubtitleHud by rememberUpdatedState(onSubtitleHud)
    /**
     * Stamp of the last confirmed two-finger tap. The two-finger lane below watches the
     * Initial pass, which every frame delivers before the Main pass the tap lane reads, so
     * by the time `detectTapGestures` decides an up was a tap this stamp is already set —
     * one two-finger tap rewinds, it does not also toggle the chrome. A stamp rather than
     * consumption: consuming the pointers here would silently kill pinch-to-zoom, which
     * needs the same two fingers.
     */
    val twoFingerStamp = remember { LongArray(1) }

    Box(
        Modifier
            .fillMaxSize()
            // Lane E — two fingers down, still, together → the Ghost rewind. Reads only, never
            // consumes, so lanes A–D keep working exactly as they did.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val origins = hashMapOf(down.id.value to down.position)
                    val slop = viewConfiguration.touchSlop
                    var sawSecondPointer = false
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // Both fingers in one frame counts, even if only one of them is a
                        // pointer we happened to see go down: index-then-thumb and a true
                        // simultaneous tap must behave the same.
                        if (event.changes.size >= 2) sawSecondPointer = true
                        event.changes.filter { it.pressed }.forEach { change ->
                            val at = origins[change.id.value]
                            if (at == null) {
                                origins[change.id.value] = change.position
                                if (origins.size >= 2) sawSecondPointer = true
                            } else if (kotlin.math.abs(change.position.x - at.x) > slop ||
                                kotlin.math.abs(change.position.y - at.y) > slop
                            ) {
                                moved = true
                            }
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                    if (sawSecondPointer && !moved) {
                        when (val cmd = GestureMapper.onTwoFingerTap(latestSettings)) {
                            is TapCommand.SeekWithRipple -> {
                                twoFingerStamp[0] = System.nanoTime()
                                vm.seekBy(cmd.deltaMs)
                                vm.showRipple(cmd.zone, cmd.deltaMs)
                            }
                            else -> Unit
                        }
                    }
                }
            }
            // Lane A — taps: single → controls, double → ±10s ripple / play-pause, long → boost
            .pointerInput(Unit) {
                // PointerInputScope.size is an IntSize (width/height), not an Offset.
                val paneSize: androidx.compose.ui.unit.IntSize = this.size
                detectTapGestures(
                    onTap = {
                        val justRewound = System.nanoTime() - twoFingerStamp[0] < TWO_FINGER_TAP_GUARD_NS
                        if (!justRewound &&
                            GestureMapper.onSingleTap(latestSettings) == TapCommand.ToggleControls
                        ) {
                            onToggleControls()
                        }
                    },
                    onDoubleTap = { offset ->
                        when (val cmd = GestureMapper.onDoubleTap(offset.x, paneSize.width.toFloat(), latestSettings)) {
                            is TapCommand.SeekWithRipple -> {
                                vm.seekBy(cmd.deltaMs)
                                vm.showRipple(cmd.zone, cmd.deltaMs)
                            }
                            TapCommand.TogglePlayPause -> vm.togglePlayPause()
                            TapCommand.ToggleControls -> onToggleControls()
                            else -> Unit
                        }
                    },
                    onLongPress = {
                        if (GestureMapper.onLongPress(latestSettings)) onBoostStart()
                    },
                )
            }
            // Lane B — any gesture *release* restores boosted speed (MX boost semantics)
            .pointerInput(Unit) {
                awaitEachGesture {
                    try {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        onBoostEnd()
                    }
                }
            }
            // Lane C — drags: vertical = brightness (left half) / volume (right half);
            //           horizontal = scrub preview+commit. A vertical drag that starts on
            //           the subtitle strip moves the subtitle seat instead (MX idea #5),
            //           and a quick flick there jumps a subtitle line.
            .pointerInput(Unit) {
                val paneSize: androidx.compose.ui.unit.IntSize = this.size
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    val startY = down.position.y
                    var lastY = startY
                    var axis: DominantAxis = DominantAxis.NONE
                    val zone = dragHalfZone(startX, paneSize.width.toFloat())
                    val inSubtitleZone = isInSubtitleZone(
                        startX,
                        startY,
                        paneSize.width.toFloat(),
                        paneSize.height.toFloat(),
                    )
                    // The flick classifier needs the drag's own clock and travel, and the
                    // seat it started from — the live move is undone on a flick, so the
                    // seat never drifts from a gesture that meant "next line".
                    val subtitleDragStartMs = System.currentTimeMillis()
                    val subtitleStartFraction = latestSubtitle.bottomFraction
                    var subtitleTotalDy = 0f
                    // The seat accumulates locally per event: the fresh-state read can lag
                    // recomposition by a frame, and adding each delta to a stale base would
                    // drop most of the drag. The local sum is the truth; the VM is told it.
                    var subtitleSeat = subtitleStartFraction
                    // Hate-audit H5: the screen edges belong to Android's back
                    // gesture — a vertical drag that starts inside the edge
                    // dead zone is the system gesture, not a brightness/volume
                    // change, so it applies nothing here.
                    val edgeDeadZonePx = with(density) { EDGE_DEAD_ZONE_DP.dp.toPx() }
                    val inEdgeDeadZone = isInEdgeDeadZone(
                        startX,
                        paneSize.width.toFloat(),
                        edgeDeadZonePx,
                    )
                    val basePosition = latestPosition
                    var scrubTarget: Long? = null
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val totalDx = change.position.x - startX
                            val dy = change.position.y - lastY
                            lastY = change.position.y
                            val totalDy = lastY - startY
                            if (axis == DominantAxis.NONE) {
                                axis = dominantAxis(totalDx, totalDy, viewConfiguration.touchSlop)
                            }
                            when (axis) {
                                DominantAxis.VERTICAL ->
                                    if (inSubtitleZone && !latestSettings.lockGestures) {
                                        subtitleTotalDy = totalDy
                                        val fraction = subtitleMoveFraction(dy, paneSize.height.toFloat())
                                        subtitleSeat = (subtitleSeat + fraction).coerceIn(0f, 0.4f)
                                        vm.setSubtitleBottomFraction(subtitleSeat)
                                        latestOnSubtitleHud(SubtitleHudValue.Position((subtitleSeat * 100).toInt()))
                                    } else if (!inEdgeDeadZone) {
                                        when (val cmd = GestureMapper.onVerticalDrag(zone, dy, paneSize.height.toFloat(), latestSettings)) {
                                            is DragCommand.BrightnessDelta -> latestOnBrightness(cmd.fractionPerSwipe)
                                            is DragCommand.VolumeDelta -> latestOnVolume(cmd.fractionPerSwipe)
                                            else -> Unit
                                        }
                                    }
                                DominantAxis.HORIZONTAL ->
                                    when (val cmd = GestureMapper.onHorizontalScrub(basePosition, totalDx, paneSize.width.toFloat(), latestDuration, latestSettings)) {
                                        is DragCommand.ScrubTarget -> {
                                            scrubTarget = cmd.targetMs
                                            onScrubPreview(cmd.targetMs)
                                        }
                                        else -> Unit
                                    }
                                DominantAxis.NONE -> Unit
                            }
                            change.consumed.positionChange = true
                        }
                    } finally {
                        if (axis == DominantAxis.HORIZONTAL) {
                            scrubTarget?.let { onScrubCommit(it) }
                        }
                        if (axis == DominantAxis.VERTICAL && inSubtitleZone && !latestSettings.lockGestures) {
                            val durationMs = System.currentTimeMillis() - subtitleDragStartMs
                            if (isSubtitleFling(durationMs, subtitleTotalDy)) {
                                // Undo the live move first, then jump: up = next line, down =
                                // previous. With no overlay file the jump itself says so out
                                // loud (the VM message); the pill only shows when a line was
                                // actually jumped to.
                                vm.setSubtitleBottomFraction(subtitleStartFraction)
                                val next = subtitleTotalDy < 0f
                                vm.jumpSubtitleLine(next)
                                if (latestCustomSubtitleLoaded != null) {
                                    latestOnSubtitleHud(SubtitleHudValue.LineJump(next))
                                }
                            }
                        }
                    }
                }
            }
            // Lane D — pinch: expand → ZOOM, squeeze → FIT (mode mapping per PLAYER_SPEC §2.1).
            //           On the subtitle strip the same two fingers work on the subtitles
            //           instead (MX idea #5): a pinch resizes them live, a mostly-horizontal
            //           two-finger swipe re-times them a coarse step per 10% of width.
            .pointerInput(Unit) {
                val paneSize: androidx.compose.ui.unit.IntSize = this.size
                // The re-time banks sub-step travel across transform callbacks; a gap over
                // half a second means a new gesture, and the bank starts over. The size
                // banks the same way, for the same frame-lag reason as the Lane C seat.
                var retimeAccumPx = 0f
                var lastTransformMs = 0L
                var subtitleSize = 0
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (isInSubtitleZone(centroid.x, centroid.y, paneSize.width.toFloat(), paneSize.height.toFloat()) &&
                        !latestSettings.lockGestures
                    ) {
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastTransformMs > 500L) {
                            retimeAccumPx = 0f
                            subtitleSize = latestSubtitle.sizePercent
                        }
                        lastTransformMs = nowMs
                        val sizeDelta = subtitleSizeDelta(zoom)
                        if (sizeDelta != 0) {
                            subtitleSize = (subtitleSize + sizeDelta).coerceIn(50, 200)
                            vm.setSubtitleSettings(latestSubtitle.copy(sizePercent = subtitleSize))
                            latestOnSubtitleHud(SubtitleHudValue.Size(subtitleSize))
                        }
                        if (kotlin.math.abs(zoom - 1f) <= 0.02f) {
                            retimeAccumPx += pan.x
                            val steps = subtitleRetimeSteps(retimeAccumPx, paneSize.width.toFloat())
                            if (steps != 0) {
                                val nextDelay = SubtitleOffset.clamp(
                                    latestSubtitle.delayMs + steps * SubtitleOffset.COARSE_STEP_MS,
                                )
                                vm.setSubtitleOffset(nextDelay)
                                latestOnSubtitleHud(SubtitleHudValue.Delay(nextDelay))
                                retimeAccumPx -= steps * 0.10f * paneSize.width
                            }
                        }
                        return@detectTransformGestures
                    }
                    val cause = when {
                        zoom > 1.02f -> "out"
                        zoom < 0.98f -> "in"
                        else -> ""
                    }
                    when (GestureMapper.onPinch(cause, latestSettings)) {
                        PinchCommand.ZoomFill -> vm.setAspect(AspectMode.ZOOM)
                        PinchCommand.ZoomFit -> vm.setAspect(AspectMode.FIT)
                        else -> Unit
                    }
                }
            },
    )
}
