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
 * starting is immediate — a viewer sees the picture, not a spinner. Thirty seconds is what
 * the player then tries to hold, and two minutes is the ceiling a fast link may run to before
 * it stops fetching to save battery and cache — the RAM buffer is the shock absorber for a
 * stall, and the disk-ahead preview (see AheadFetcher) is what actually carries a dropped
 * connection, so the RAM target is generous but not the whole story. The rebuffer threshold
 * is deliberately four times the startup threshold: getting going is cheap, and recovering
 * from a stall should not start again into the same stall one frame later. (Player resilience,
 * 2026-11-25: MIN 15 s -> 30 s, MAX 50 s -> 120 s, after-rebuffer 1 s -> 2 s. Worst-case RAM
 * ~75 MB at 1080p/5 Mbps, ~22 MB at 480p — time wins over size, as before.)
 *
 * [PRIORITIZE_TIME_OVER_SIZE] decides what happens when the two budgets disagree. A
 * progressive MKV at a high bitrate reaches the default 50 MB byte cap well before it holds
 * fifteen seconds, and a player that stops on the byte cap is a player that re-buffers while
 * it still has bandwidth — which is the exact stutter this whole file exists to remove. Time
 * wins.
 */
object PlayerBuffering {
    const val MIN_BUFFER_MS = 30_000
    const val MAX_BUFFER_MS = 120_000
    const val BUFFER_FOR_PLAYBACK_MS = 500
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000
    const val PRIORITIZE_TIME_OVER_SIZE = true
    /**
     * P12 (round 2): a mid-playback rebuffer counts as stuck once it has
     * lasted 5 s. The bare "Buffering… 42%" plate is honest for a hiccup;
     * past 5 s the viewer needs to know the connection looks slow, and
     * needs two ways out (retry the rung, or drop to 480p). The start
     * path is excluded — it has its own deadline card (StartDeadline).
     */
    const val STUCK_AFTER_MS = 5_000
    /**
     * Player resilience (2026-11-25): a mid-playback stall that lasts this long with no
     * buffered-position advance is a dead loader, not a hiccup — the service's mid-play
     * stall watchdog drives the failover machine so it hops to a backup instead of waiting
     * for Media3's 25 s read timeout to error. Twice the stuck-card threshold on purpose:
     * the card shows first at 5 s, and a brief hiccup never pays for a hop.
     */
    const val MID_PLAY_STALL_HOP_MS = 10_000
}

/** P12 (round 2): true once a stall has lasted [PlayerBuffering.STUCK_AFTER_MS]. */
fun isStuckBuffering(elapsedMs: Long): Boolean = elapsedMs >= PlayerBuffering.STUCK_AFTER_MS
