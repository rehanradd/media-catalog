package com.raddstream.app.player

import com.raddstream.app.R
import com.raddstream.app.network.models.WireMediaSegment

/**
 * Skip Intro / Skip Recap (MX #4): one community-verified intro / recap /
 * credits / preview window, served by the v2 play bundle (`play_bundle`
 * `segments[]`) and stored server-side by `backfill_segments.py` from
 * TheIntroDB v3. Timings arrive already in file coordinates — the server
 * shifts them by the episode's offset inside a pack file and omits them
 * entirely when that offset is unknown — so the player never re-shifts
 * and never guesses: no segments, no button.
 *
 * The wire model lives in `network/models` (WireMediaSegment); this file
 * is the player's own shape plus the two pure rules, so both stay
 * JVM-unit-testable without Android.
 */
enum class MediaSegmentType(val wireName: String) {
    INTRO("intro"),
    RECAP("recap"),
    CREDITS("credits"),
    PREVIEW("preview");

    companion object {
        fun fromWire(wire: String?): MediaSegmentType? =
            entries.firstOrNull { it.wireName == wire }
    }
}

/** One Skip window: its type plus its window in the file, milliseconds. */
data class MediaSegment(
    val type: MediaSegmentType,
    /** Start of the window in the file, milliseconds. */
    val startMs: Long,
    /** End of the window, milliseconds; null = to the end of the file (credits). */
    val endMs: Long?,
)

/**
 * The wire segment as the player's own [MediaSegment]. Null when the wire
 * type is one this app does not know: an unknown segment can neither be
 * labelled nor acted on, so it is dropped at the edge instead of carried
 * as an UNKNOWN the button would have to defend against. A negative start
 * or an end at/before its start is bad data from the wire and is dropped
 * the same way — the server's parser already rejects those, so this is
 * defence in depth, not a second parser.
 */
fun WireMediaSegment.toMediaSegment(): MediaSegment? {
    val type = MediaSegmentType.fromWire(type) ?: return null
    val start = startMs ?: return null
    if (start < 0) return null
    if (endMs != null && endMs <= start) return null
    return MediaSegment(type = type, startMs = start, endMs = endMs)
}

/**
 * The segment whose window contains [positionMs] — the one the Skip
 * button offers right now. A null end means "to the end of the file", so
 * a credits window stays active from its start on. At most one segment
 * is active: windows for one episode do not overlap in the stored data,
 * and if they ever did the earliest start wins.
 */
fun activeSegment(segments: List<MediaSegment>, positionMs: Long): MediaSegment? =
    segments.filter { positionMs >= it.startMs && (it.endMs == null || positionMs < it.endMs) }
        .minByOrNull { it.startMs }

/**
 * Where a tap on the Skip button for [segment] seeks to: the window's
 * end, or the end of the file when the window runs to it (a credits
 * segment with no end). Null when neither is known — a file of unknown
 * duration with an open-ended window has nowhere honest to jump to, so
 * the button stays hidden instead of seeking nowhere.
 */
fun skipTargetMs(segment: MediaSegment, durationMs: Long): Long? =
    segment.endMs ?: durationMs.takeIf { it > 0 }
