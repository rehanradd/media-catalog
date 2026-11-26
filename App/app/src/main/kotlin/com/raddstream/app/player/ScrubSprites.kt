package com.raddstream.app.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Scrub thumbnails — the preview picture above the seek bar while the viewer
 * drags to a new position (YouTube-style), plus the time the toast already
 * printed. P2-5 (B-23): the second, muted ExoPlayer that used to render
 * these frames is gone — it decoded the same stream twice, which is a
 * battery/thermal/jank risk on a phone. The thumbnails are now a sprite
 * sheet built from the frames the MAIN player already decoded.
 *
 * How it works: while a VOD title plays, the player screen captures the
 * main surface's current frame ([PixelCopy] off the PlayerView's
 * SurfaceView surface, API 24+) into one cell of a single sheet bitmap —
 * one cell per bucket of the title, 48 buckets across the duration. On a
 * scrub the toast shows the cell for the target's bucket, as a plain
 * [Bitmap] in an [androidx.compose.foundation.Image] — no player, no
 * TextureView, no extra network fetch, no new service. The backend serves
 * no sprite, and MediaMetadataRetriever cannot open an HLS playlist, so
 * the main player's own decoded frames are the only thumbnail source that
 * works for everything this app plays (HLS and progressive alike).
 *
 * Honest degradation, by design: a bucket only has a frame once playback
 * has passed through it (or a seek has landed in it). Scrubbing to a
 * position nobody has watched yet shows the time only — the same time-only
 * toast Android 5 used to get — never a frame from another scene. Live
 * has no scrubber and no toast, so nothing is ever captured for it.
 * Audio-only has no surface, so nothing is captured either.
 *
 * Cost: one 160x90 [PixelCopy] per bucket (at most 48 per title), one
 * sheet bitmap of 1280x540 (~2.8 MB ARGB_8888), recycled when the screen
 * leaves or the rendition changes. Captures run only while playing, at
 * most one in flight.
 */
object ScrubThumbnails {

    /**
     * Scrub thumbnails exist on API 24+ (Android 7.0) and above only —
     * [PixelCopy] needs API 24. The [sdkInt] parameter is the device's API
     * level (defaults to the running device) — it exists so the gate is a
     * pure function a JVM test can call with 21, 23 and 24 directly.
     * Below 24 the toast stays time-only, exactly as Android 5 did when
     * the preview player was the mechanism.
     */
    fun isSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.N

    /**
     * Which bucket [positionMs] falls in, for a title of [durationMs].
     * Returns -1 when there is no usable duration (live, unknown) or the
     * position is negative. A position past the end clamps to the last
     * bucket — the frame there is the closest one that exists.
     */
    fun bucketIndex(positionMs: Long, durationMs: Long, cellCount: Int = CELL_COUNT): Int {
        if (durationMs <= 0L || positionMs < 0L || cellCount <= 0) return -1
        return (positionMs * cellCount / durationMs).toInt().coerceIn(0, cellCount - 1)
    }

    /**
     * The cell's top-left corner inside the sheet, in pixels, for a bucket
     * index. Cells fill row-major: index 8 is the first cell of row 2.
     */
    fun cellOrigin(index: Int, columns: Int = COLUMNS): Pair<Int, Int> =
        Pair((index % columns) * CELL_WIDTH_PX, (index / columns) * CELL_HEIGHT_PX)

    /**
     * Whether the toast shows a thumbnail: exactly when the target's
     * bucket has a captured frame. No settle delay any more — a captured
     * frame is already the frame at that position, so it can show the
     * moment the finger lands on it.
     */
    fun shouldShowThumbnail(frameAvailable: Boolean): Boolean = frameAvailable

    /** Thumbnail size in the toast: 16:9, small enough to sit over the chrome. */
    const val THUMBNAIL_WIDTH_DP: Int = 160
    const val THUMBNAIL_HEIGHT_DP: Int = 90

    /** The sheet is 8 columns x 6 rows of 160x90 cells: 48 buckets a title. */
    const val COLUMNS: Int = 8
    const val ROWS: Int = 6
    const val CELL_COUNT: Int = COLUMNS * ROWS
    const val CELL_WIDTH_PX: Int = 160
    const val CELL_HEIGHT_PX: Int = 90
}

/**
 * The sprite sheet: one bitmap holding up to [ScrubThumbnails.CELL_COUNT]
 * captured frames, plus which buckets are filled. The sheet is the only
 * storage — a cell is drawn into it on capture and cropped back out of it
 * on display, so a title never holds more than one sheet (~2.8 MB).
 *
 * [version] is Compose state, bumped on every store/reset: a composition
 * that reads it re-runs when a new cell lands, which is how a toast that
 * is already showing time-only gains its picture mid-scrub.
 */
class SpriteThumbnailCache {

    private var sheet: Bitmap? = null
    private val filled = BooleanArray(ScrubThumbnails.CELL_COUNT)

    var version by mutableIntStateOf(0)
        private set

    /** True once a frame has been captured for this bucket. */
    fun isFilled(bucket: Int): Boolean =
        bucket in filled.indices && filled[bucket]

    /**
     * Draw [frame] into [bucket]'s cell, scaling to the cell. The frame is
     * recycled here — the caller must not reuse it after this call. A
     * bucket outside the sheet is dropped, never stored.
     */
    fun store(bucket: Int, frame: Bitmap) {
        if (bucket !in filled.indices) {
            frame.recycle()
            return
        }
        var target = sheet?.takeUnless { it.isRecycled }
        if (target == null) {
            target = Bitmap.createBitmap(
                ScrubThumbnails.COLUMNS * ScrubThumbnails.CELL_WIDTH_PX,
                ScrubThumbnails.ROWS * ScrubThumbnails.CELL_HEIGHT_PX,
                Bitmap.Config.ARGB_8888,
            )
            sheet = target
        }
        val (x, y) = ScrubThumbnails.cellOrigin(bucket)
        Canvas(target).drawBitmap(
            frame,
            null,
            Rect(
                x,
                y,
                x + ScrubThumbnails.CELL_WIDTH_PX,
                y + ScrubThumbnails.CELL_HEIGHT_PX,
            ),
            null,
        )
        frame.recycle()
        filled[bucket] = true
        version++
    }

    /**
     * The captured frame for [positionMs]'s bucket, as a fresh 160x90
     * bitmap, or null when that bucket was never captured. The caller owns
     * the returned bitmap.
     */
    fun frameFor(positionMs: Long, durationMs: Long): Bitmap? {
        val bucket = ScrubThumbnails.bucketIndex(positionMs, durationMs)
        if (bucket < 0 || !filled[bucket]) return null
        val sheet = sheet?.takeUnless { it.isRecycled } ?: return null
        val (x, y) = ScrubThumbnails.cellOrigin(bucket)
        return Bitmap.createBitmap(
            sheet,
            x,
            y,
            ScrubThumbnails.CELL_WIDTH_PX,
            ScrubThumbnails.CELL_HEIGHT_PX,
        )
    }

    /** Drop the sheet (rendition changed, or the screen is leaving). */
    fun reset() {
        sheet?.recycle()
        sheet = null
        filled.fill(false)
        version++
    }

    /** Release the sheet. Call when the player screen leaves. */
    fun release() = reset()
}

/**
 * Capture the main player's current frame off [surface] into a 160x90
 * bitmap. [onFrame] runs on the main thread with the captured bitmap when
 * PixelCopy succeeds; the bitmap is recycled here on failure, so the
 * caller only ever receives a usable frame. A no-op below API 24, where
 * PixelCopy does not exist.
 */
internal fun requestSpriteCapture(surface: Surface, onFrame: (Bitmap) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
    val bitmap = Bitmap.createBitmap(
        ScrubThumbnails.CELL_WIDTH_PX,
        ScrubThumbnails.CELL_HEIGHT_PX,
        Bitmap.Config.ARGB_8888,
    )
    PixelCopy.request(
        surface,
        bitmap,
        { result ->
            if (result == PixelCopy.SUCCESS) onFrame(bitmap) else bitmap.recycle()
        },
        Handler(Looper.getMainLooper()),
    )
}
