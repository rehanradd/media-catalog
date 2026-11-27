package com.raddstream.app.player

/**
 * How much media the player keeps ahead of the playhead, and how little it needs before it
 * shows a picture.
 *
 * These four numbers are one owner because [androidx.media3.exoplayer.DefaultLoadControl]
 * checks their relationship against each other when the load control is built, and a wrong
 * pair is a crash at playback start rather than a compile error: the startup buffer must not
 * exceed either the minimum buffer or the rebuffer threshold. Stating the numbers here lets a
 * unit test on a JVM hold that relationship, instead of a phone finding it on the first tap of
 * a poster.
 *
 * The shape of the choice: 500 ms of buffered media is enough to fill the first frame, so
 * starting is immediate — a viewer sees the picture, not a spinner. Fifteen seconds is what
 * the player then tries to hold, and fifty is the ceiling a fast link may run to before it
 * stops fetching to save battery and cache. The rebuffer threshold is deliberately six
 * times the startup threshold: getting going is cheap, and recovering from a stall should
 * not start again into the same stall one frame later. A live HLS segment is 2–6 s, so
 * resuming on the old 1 s meant playing ~2 s, stalling, and resuming into the same stall —
 * the exact 2-second rebuffer cycle in the 27 Nov video audit (issue 3, Madani Channel).
 * Three seconds is a whole segment's worth of cushion before the picture restarts.
 *
 * [PRIORITIZE_TIME_OVER_SIZE] decides what happens when the two budgets disagree. A
 * progressive MKV at a high bitrate reaches the default 50 MB byte cap well before it holds
 * fifteen seconds, and a player that stops on the byte cap is a player that re-buffers while
 * it still has bandwidth — which is the exact stutter this whole file exists to remove. Time
 * wins.
 */
object PlayerBuffering {
    const val MIN_BUFFER_MS = 15_000
    const val MAX_BUFFER_MS = 50_000
    const val BUFFER_FOR_PLAYBACK_MS = 500
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
    const val PRIORITIZE_TIME_OVER_SIZE = true
    /**
     * P12 (round 2): a mid-playback rebuffer counts as stuck once it has
     * lasted 5 s. The bare "Buffering… 42%" plate is honest for a hiccup;
     * past 5 s the viewer needs to know the connection looks slow, and
     * needs two ways out (retry the rung, or drop to 480p). The start
     * path is excluded — it has its own deadline card (StartDeadline).
     *
     * Video-audit #6 (27 Nov 2026): the 5 s clock is wrong for a live
     * start. A live channel legitimately buffers 6–12 s while the
     * redirector mints the 302 (measured), so the "Still loading — your
     * connection looks slow" card fired on a start that was succeeding —
     * the audit's Madani card at 0:49, with the picture arriving a second
     * later. A live stall counts as stuck only past 12 s: beyond the
     * measured resolve ceiling, still well before the 20 s start watchdog
     * fails the attempt over.
     */
    const val STUCK_AFTER_MS = 5_000
    const val STUCK_AFTER_MS_LIVE = 12_000
}

/** P12 (round 2): true once a stall has lasted [PlayerBuffering.STUCK_AFTER_MS]. */
fun isStuckBuffering(elapsedMs: Long, isLive: Boolean = false): Boolean =
    elapsedMs >= if (isLive) PlayerBuffering.STUCK_AFTER_MS_LIVE else PlayerBuffering.STUCK_AFTER_MS
