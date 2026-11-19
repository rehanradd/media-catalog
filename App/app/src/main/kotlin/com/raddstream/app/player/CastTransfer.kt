package com.raddstream.app.player

/**
 * MX #9 — Cast: the transfer, as pure rules. No Cast SDK here, so every
 * branch is a JVM test ([CastTransferTest]): who is playing where, what
 * position survives a hop, and what refuses a transfer outright.
 *
 * The shape mirrors the pre-1.9 Media3 pattern (see
 * RESEARCH-cast-20261119.md): one local player, one remote player, and a
 * manual swap on session availability — the app swaps the MediaSession
 * player itself, so the rules for *when* a swap may happen and *where*
 * playback resumes live here, not in the service.
 */

/** Which player is carrying playback right now. */
sealed interface CastTransferState {
    /** The phone's ExoPlayer. */
    data object Local : CastTransferState

    /** The receiver, reached through the session's CastPlayer. */
    data class Casting(val deviceName: String?) : CastTransferState
}

/** Why a transfer to the receiver was refused. */
enum class CastRefusalReason {
    /** Kids Lock (or a locked player) forbids casting right now. */
    NOT_ALLOWED,

    /** No Cast session is live — nothing to hand playback to. */
    NO_SESSION,

    /** The rendition carries no play URL at all. */
    NO_PLAY_URL,

    /** The link expired while it sat in the bundle — a rotted URL must never reach the receiver. */
    EXPIRED_LINK,
}

/** The answer to "may this rendition move to the TV now". */
sealed interface CastTransferDecision {
    /** Go: hand the receiver this URL at the caller's position. */
    data class Proceed(val playUrl: String) : CastTransferDecision

    /** Stay local, and say why. */
    data class Refuse(val reason: CastRefusalReason) : CastTransferDecision
}

/**
 * The transfer gate, in order: allowed → session live → URL present →
 * URL fresh. The caller passes the rendition that would actually be
 * cast — when the phone is playing a kept (loopback) copy, that is the
 * rendition with its LIVE redirector URL substituted, because a loopback
 * address can never play on a receiver.
 */
fun decideCastStart(
    playback: PlaybackInfo,
    allowed: Boolean,
    sessionAvailable: Boolean,
    nowEpochMillis: Long,
): CastTransferDecision {
    if (!allowed) return CastTransferDecision.Refuse(CastRefusalReason.NOT_ALLOWED)
    if (!sessionAvailable) return CastTransferDecision.Refuse(CastRefusalReason.NO_SESSION)
    val url = playback.playUrl?.trim()?.takeIf { it.isNotEmpty() }
        ?: return CastTransferDecision.Refuse(CastRefusalReason.NO_PLAY_URL)
    if (playback.isExpired(nowEpochMillis)) {
        return CastTransferDecision.Refuse(CastRefusalReason.EXPIRED_LINK)
    }
    return CastTransferDecision.Proceed(url)
}

/**
 * Where playback resumes after a hop, local → remote or remote → local.
 * An ended player reports its duration as its position; resuming there
 * would end again at once, so the end restarts (the same rule the MX #3
 * decoder rebuild uses). A position past a known duration clamps to the
 * end minus one second, never past it; an unknown duration keeps the
 * position as-is, floored at zero.
 */
fun castResumePosition(positionMs: Long, durationMs: Long): Long {
    val pos = positionMs.coerceAtLeast(0L)
    if (durationMs <= 0L) return pos
    if (pos >= durationMs) return 0L
    return pos.coerceAtMost(durationMs - 1_000L).coerceAtLeast(0L)
}
