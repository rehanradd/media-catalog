package com.raddstream.app.player

/**
 * Pure gesture decision table for the MX Player gesture surface. The Compose layer
 * (`player/ui/GestureLayer.kt`) captures pointers and delegates every *meaning* here, so the
 * exact behavior researched in PLAYER_SPEC.md §2.1 is unit-testable without Android:
 *
 *  - double-tap LEFT third  → rewind [GestureSettings.skipMs] (default 10 000 — MX default),
 *  - double-tap RIGHT third → forward,
 *  - double-tap MIDDLE third → play/pause (MX option, on by default),
 *  - single tap → toggle controls,
 *  - vertical drag LEFT half → brightness · RIGHT half → volume,
 *  - horizontal drag → scrub (full width == 10% of the duration, clamped),
 *  - long press → speed boost to [GestureSettings.boostSpeed], release restores,
 *  - pinch out/in → aspect ZOOM / FIT (mode-based: PlayerSurfaceView absent in Media3 1.3.1),
 *  - two-finger tap → the [GestureSettings.cinemaRewindMs] jump back, and only in
 *    [ViewMode.PURE_CINEMA], where there is no rewind button to press (see `PlayerUiState`).
 */

enum class ScreenZone { LEFT, CENTER, RIGHT }

data class GestureSettings(
    val skipMs: Long = 10_000L,
    val boostSpeed: Float = 2.0f,
    val doubleTapCenterPlays: Boolean = true,
    val verticalGesturesEnabled: Boolean = true,
    val horizontalScrubEnabled: Boolean = true,
    val longPressBoostEnabled: Boolean = true,
    val lockGestures: Boolean = false, // the on-screen LOCK button sets this
    /**
     * Ghost mode: the chrome is gone, so the two-finger rewind is the only seek affordance
     * that does not need a screen tap to become visible. Off by default — in the normal
     * chrome a second rewind gesture over the ±10s buttons would be a surprise.
     */
    val pureCinema: Boolean = false,
    /** What a two-finger tap buys. Three seconds: enough to catch a line of dialogue, too
     *  short to be a substitute for the scrubber. */
    val cinemaRewindMs: Long = 3_000L,
)

sealed class TapCommand {
    object None : TapCommand()
    object ToggleControls : TapCommand()
    object TogglePlayPause : TapCommand()
    data class Seek(val deltaMs: Long) : TapCommand()
    /** Ripple direction for the on-screen double-tap feedback. */
    data class SeekWithRipple(val deltaMs: Long, val zone: ScreenZone) : TapCommand()
}

sealed class DragCommand {
    object None : DragCommand()
    data class BrightnessDelta(val fractionPerSwipe: Float) : DragCommand() // >0 brighter
    data class VolumeDelta(val fractionPerSwipe: Float) : DragCommand()
    data class ScrubTarget(val targetMs: Long, val startedNewScrub: Boolean) : DragCommand()
    data class SpeedStep(val delta: Float) : DragCommand()
}

sealed class PinchCommand {
    object None : PinchCommand()
    object ZoomFill : PinchCommand() // pinch out → expand/crop to fill
    object ZoomFit : PinchCommand()  // pinch in → fit inside
}

/** Which third of the width a point falls in (MX uses halves for vertical, thirds for taps). */
fun tapZone(x: Float, widthPx: Float): ScreenZone {
    require(widthPx > 0f)
    val third = widthPx / 3f
    return when {
        x < third -> ScreenZone.LEFT
        x > 2 * third -> ScreenZone.RIGHT
        else -> ScreenZone.CENTER
    }
}

/**
 * PL-9: the brightness drag's starting point. The old code assumed 0.5f on first use,
 * so a viewer whose screen was already at 20% (or 90%) felt the brightness JUMP the
 * moment they touched the left half. The drag must start where the screen already is:
 * the window's own `screenBrightness` first (what the player last set, -1f when the
 * window never set one), then the system brightness in 0..1 (Settings.System
 * SCREEN_BRIGHTNESS / 255), and only when neither answers does the drag fall back to
 * the middle. Both inputs are clamped — a driver that reports out of range must not
 * hand the drag a base it cannot hold.
 */
fun initialBrightnessBase(windowBrightness: Float, systemBrightness01: Float?): Float =
    when {
        windowBrightness >= 0f -> windowBrightness.coerceIn(0f, 1f)
        systemBrightness01 != null && systemBrightness01.isFinite() ->
            systemBrightness01.coerceIn(0f, 1f)
        else -> 0.5f
    }

/**
 * The screen edges belong to Android's back gesture: a vertical
 * brightness/volume drag that starts within [EDGE_DEAD_ZONE_DP] of the left
 * or right edge is the system gesture, not ours — the viewer gets back
 * navigation, not a brightness jump. The gesture layer checks the drag's
 * start point against this before it applies any vertical command.
 */
const val EDGE_DEAD_ZONE_DP = 24f

fun isInEdgeDeadZone(xPx: Float, widthPx: Float, deadZonePx: Float): Boolean =
    xPx < deadZonePx || xPx > widthPx - deadZonePx

/** Which half of the width a vertical-drag point falls in (LEFT=brightness, RIGHT=volume). */
fun dragHalfZone(x: Float, widthPx: Float): ScreenZone =
    if (x < widthPx / 2f) ScreenZone.LEFT else ScreenZone.RIGHT

/** Sensible dead-zone: gestures that are mostly horizontal must not trigger vertical HUD. */
enum class DominantAxis { NONE, HORIZONTAL, VERTICAL }

fun dominantAxis(dxPx: Float, dyPx: Float, slopPx: Float = 16f): DominantAxis = when {
    kotlin.math.abs(dxPx) < slopPx && kotlin.math.abs(dyPx) < slopPx -> DominantAxis.NONE
    kotlin.math.abs(dxPx) >= kotlin.math.abs(dyPx) -> DominantAxis.HORIZONTAL
    else -> DominantAxis.VERTICAL
}

object GestureMapper {

    fun onSingleTap(settings: GestureSettings): TapCommand =
        if (settings.lockGestures) TapCommand.None else TapCommand.ToggleControls

    fun onDoubleTap(x: Float, widthPx: Float, settings: GestureSettings): TapCommand {
        if (settings.lockGestures) return TapCommand.None
        return when (tapZone(x, widthPx)) {
            ScreenZone.LEFT -> TapCommand.SeekWithRipple(-settings.skipMs, ScreenZone.LEFT)
            ScreenZone.RIGHT -> TapCommand.SeekWithRipple(+settings.skipMs, ScreenZone.RIGHT)
            ScreenZone.CENTER ->
                if (settings.doubleTapCenterPlays) TapCommand.TogglePlayPause
                else TapCommand.ToggleControls
        }
    }

    fun onLongPress(settings: GestureSettings): Boolean =
        settings.longPressBoostEnabled && !settings.lockGestures

    /**
     * Two-finger tap → the ghost-mode rewind. Silent outside [ViewMode.PURE_CINEMA] and
     * silent under the lock, which is the same rule every other seek path obeys: a locked
     * player must not be seekable by a gesture no one can see. The feedback is reported on
     * the LEFT edge — a rewind is a backward motion, and Ghost mode keeps its acknowledgements
     * out of the middle of the picture.
     */
    fun onTwoFingerTap(settings: GestureSettings): TapCommand {
        if (!settings.pureCinema || settings.lockGestures || settings.cinemaRewindMs <= 0L) {
            return TapCommand.None
        }
        return TapCommand.SeekWithRipple(-settings.cinemaRewindMs, ScreenZone.LEFT)
    }

    /**
     * Vertical drag → HUD delta. Convention: dragging UP (negative dy in screen coords)
     * increases, matching MX. Returns fraction of a full top-to-bottom swipe.
     */
    fun onVerticalDrag(
        xZone: ScreenZone,
        dyPx: Float,
        heightPx: Float,
        settings: GestureSettings,
    ): DragCommand {
        if (!settings.verticalGesturesEnabled || settings.lockGestures || heightPx <= 0f) {
            return DragCommand.None
        }
        val fraction = (-dyPx / heightPx)
        return when (xZone) {
            ScreenZone.LEFT -> DragCommand.BrightnessDelta(fraction)
            ScreenZone.RIGHT -> DragCommand.VolumeDelta(fraction)
            // MX: middle vertical drag does nothing special (center keeps play/pause semantics)
            ScreenZone.CENTER -> DragCommand.None
        }
    }

    /**
     * Horizontal scrub: a full-width swipe moves 10% of the duration, clamped to
     * [0, durationMs]. A quarter of the film per swipe (~42 min on Salaar) was
     * an accident waiting for a thumb — 10% is a deliberate seek, and the
     * scrubber is there for the big jumps.
     */
    fun onHorizontalScrub(
        basePositionMs: Long,
        dxPx: Float,
        widthPx: Float,
        durationMs: Long,
        settings: GestureSettings,
    ): DragCommand {
        if (!settings.horizontalScrubEnabled || settings.lockGestures ||
            widthPx <= 0f || durationMs <= 0
        ) {
            return DragCommand.None
        }
        val deltaMs = (dxPx / widthPx * durationMs / 10f).toLong()
        val target = (basePositionMs + deltaMs).coerceIn(0, durationMs)
        return DragCommand.ScrubTarget(target, startedNewScrub = true)
    }

    fun onPinch(cause: String, settings: GestureSettings): PinchCommand {
        if (settings.lockGestures) return PinchCommand.None
        return when (cause) {
            "out" -> PinchCommand.ZoomFill
            "in" -> PinchCommand.ZoomFit
            else -> PinchCommand.None
        }
    }

    /** Speed menu values — MX exposes 0.25× … 4× (confirmed in feature reel). */
    val SPEED_PRESETS: List<Float> =
        listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f)

    /**
     * The speeds the sheet offers (Stitch playback-speed sheet): the six a
     * viewer actually picks, 0.5×–2×. The full [SPEED_PRESETS] range stays
     * for the long-press 2× boost and the tests; the sheet reads this
     * subset — filtered from the one list, never a second copy — so the
     * two can never drift apart. 1.75× is deliberately not in it: the
     * mockup's six rows skip it.
     */
    val SHEET_PRESETS: List<Float> =
        SPEED_PRESETS.filter { it in listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f) }

    fun clampedSpeed(speed: Float): Float = speed.coerceIn(0.25f, 4.0f)
}

/**
 * Subtitle gestures (MX idea #5). All four live inside the subtitle strip — the bottom 30% of
 * the pane, centre third — where the text sits and where a viewer's thumb already is when they
 * are looking at the subtitles. A vertical drag anywhere else keeps its old meaning
 * (brightness/volume), a pinch anywhere else keeps zoom/fit, and a horizontal drag anywhere
 * else keeps scrubbing. Gated on `lockGestures` only — they work in Ghost mode too, where
 * there is no sheet to reach for.
 *
 *  - two-finger horizontal swipe on the strip → subtitle delay, one coarse step (500 ms)
 *    per 10% of pane width travelled (the sync sheet's coarse step, same readout);
 *  - pinch with its centroid on the strip → subtitle size, live, ±10 points per callback;
 *  - vertical drag starting on the strip → the seat moves with the finger (up = higher);
 *  - a quick vertical FLICK on the strip → previous/next subtitle line instead (the live
 *    move is undone first, so the seat never drifts from a flick).
 */
const val SUBTITLE_ZONE_BOTTOM_FRACTION = 0.30f

/** The strip: bottom 30% of the pane, centre third. */
fun isInSubtitleZone(x: Float, y: Float, widthPx: Float, heightPx: Float): Boolean {
    if (widthPx <= 0f || heightPx <= 0f) return false
    val third = widthPx / 3f
    return x in third..(2 * third) && y >= heightPx * (1f - SUBTITLE_ZONE_BOTTOM_FRACTION)
}

/**
 * Two-finger re-time: whole 500 ms steps from the travelled distance. The caller accumulates
 * raw pixels across transform callbacks and keeps the remainder (`travelled - steps * 10% of
 * width`), so a slow swipe still banks its steps instead of losing the sub-step travel.
 * Partial travel is no step — truncating toward zero, so a 15%-width swipe is one step.
 */
fun subtitleRetimeSteps(dxPx: Float, widthPx: Float): Int {
    if (widthPx <= 0f) return 0
    return (dxPx / widthPx / 0.10f).toInt()
}

/**
 * Pinch on the strip: size change per transform callback, from the zoom factor. A real pinch
 * reports ~0.95–1.05 per frame, so ±10 points per callback is a generous clamp, not a limit —
 * the size itself is clamped to 50..200 by [SubtitleUiSettings.clamped].
 */
fun subtitleSizeDelta(zoom: Float): Int {
    if (!zoom.isFinite() || zoom <= 0f) return 0
    return ((zoom - 1f) * 100f).toInt().coerceIn(-10, 10)
}

/** Vertical drag on the strip: fraction of pane height the seat moves (up = higher). */
fun subtitleMoveFraction(dyPx: Float, heightPx: Float): Float {
    if (heightPx <= 0f) return 0f
    return -dyPx / heightPx
}

/** A flick is short, far, and fast — a slow deliberate drag is none of those. */
const val SUBTITLE_FLING_MAX_DURATION_MS = 300L
const val SUBTITLE_FLING_MIN_DISTANCE_PX = 48f
const val SUBTITLE_FLING_MIN_VELOCITY_PX_PER_S = 500f

fun isSubtitleFling(durationMs: Long, totalDyPx: Float): Boolean {
    if (durationMs <= 0L || durationMs > SUBTITLE_FLING_MAX_DURATION_MS) return false
    if (kotlin.math.abs(totalDyPx) < SUBTITLE_FLING_MIN_DISTANCE_PX) return false
    val averageVelocityPxPerSec = kotlin.math.abs(totalDyPx) / durationMs * 1000f
    return averageVelocityPxPerSec >= SUBTITLE_FLING_MIN_VELOCITY_PX_PER_S
}
