package com.raddstream.app.player

/**
 * Chapter ticks (plan §5 step 11, P14): the pack episode boundaries the server
 * already sends as [Coverage.episodes] — an offset in seconds and an optional
 * chapter title — turned into the marks the scrubber draws and the rows the print
 * sheet lists. Pure, so a JVM test can prove the fractions without a player.
 *
 * There are no in-video chapter markers anywhere in this app: the only chapter
 * data is the pack's episode boundaries. A single-episode file has no episodes
 * list, so it has no ticks — and that is honest, not a gap to fill by guessing.
 */
data class ChapterTick(
    /** Where the chapter starts, in milliseconds. */
    val offsetMs: Long,
    /** The server's chapter title, or "Episode N" when it sent none. */
    val title: String,
    /** Where the tick sits on the scrubber: 0 = start, 1 = end. */
    val fraction: Float,
)

/**
 * Episodes → ticks. A tick needs a known offset strictly inside the file: the
 * first episode starts at 0 (no tick at the very start), an offset at or past the
 * end is not a chapter, and a missing offset is not one either. Duplicates collapse
 * to one tick, and the list comes back in play order, not server order.
 */
fun chapterTicks(episodes: List<EpisodeCoverage>, durationMs: Long): List<ChapterTick> {
    if (durationMs <= 0) return emptyList()
    return episodes.mapNotNull { episode ->
        val offsetMs = episode.offsetSeconds?.let { it * 1_000L } ?: return@mapNotNull null
        if (offsetMs <= 0 || offsetMs >= durationMs) return@mapNotNull null
        ChapterTick(
            offsetMs = offsetMs,
            title = episode.chapterTitle?.takeIf { it.isNotBlank() } ?: "Episode ${episode.number}",
            fraction = (offsetMs.toFloat() / durationMs).coerceIn(0f, 1f),
        )
    }.distinctBy { it.offsetMs }.sortedBy { it.offsetMs }
}

/**
 * P2 (player fixes): the chapter the position is inside, as (number, start).
 * Ticks are the boundaries BETWEEN chapters, so the first tick's passing
 * means Chapter 2: before the first tick the viewer is in Chapter 1,
 * starting at 0. The number is what the scrubber's TalkBack label speaks
 * ("Chapter 3 — 12:00") and the start is the tick the marker sits on.
 * Pure, so a JVM test can prove the numbering without a player.
 */
fun chapterAt(ticks: List<ChapterTick>, positionMs: Long): Pair<Int, Long> {
    val passed = ticks.indexOfLast { it.offsetMs <= positionMs }
    return if (passed < 0) 1 to 0L else (passed + 2) to ticks[passed].offsetMs
}
