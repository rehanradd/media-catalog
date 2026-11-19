package com.raddstream.app.player.subtitle

/**
 * What the custom overlay draws for one cue, whatever file it came from. SRT/VTT cues arrive
 * here as a single unstyled run in the viewer's own look; ASS cues arrive with per-run colours
 * and weights, a placement from their style or `\an`/`\pos`, and a size relative to their
 * style. The overlay never re-parses a file format — it draws these runs.
 *
 * Placement follows the ASS numpad (1 = bottom-left … 9 = top-right) because that is the only
 * placement language any subtitle format in this app speaks; SRT/VTT default to bottom-centre.
 */
data class OverlayTextRun(
    val text: String,
    val colorArgb: Int? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    /** Font size relative to the cue style's size (1.0 = the style's size). */
    val fontSizeScale: Float? = null,
)

data class OverlayCue(
    val startMs: Long,
    val endMs: Long,
    val runs: List<OverlayTextRun>,
    val alignment: AssAlignment = AssAlignment.BOTTOM_CENTER,
    val position: AssPosition? = null,
    /** The style's vertical margin, as a fraction of the video height (ASS MarginV). */
    val marginVFraction: Float = 0f,
    val styleFontSizePt: Float = 20f,
    /**
     * The style's primary colour — null when the file carries no colour (SRT/VTT), in which
     * case the viewer's own colour is the text colour. A non-null file colour that is too
     * dark to read is replaced by the viewer's colour at draw time (the Robo run-4 rule).
     */
    val stylePrimaryArgb: Int? = null,
    /**
     * The style's own weight/decoration — null when the file carries none (SRT/VTT), in
     * which case the viewer's bold is the weight. For ASS the file's style is the look:
     * a non-bold style stays non-bold even when the viewer asked for bold, exactly as an
     * embedded track's own styling does when the sheet toggle is on.
     */
    val styleBold: Boolean? = null,
    val styleItalic: Boolean? = null,
    val styleUnderline: Boolean? = null,
    val styleStrike: Boolean? = null,
) {
    val text: String get() = runs.joinToString("") { it.text }
}

/**
 * One externally parsed subtitle file, in the shape the overlay needs. Both file kinds
 * implement it: [SubtitleFile] (SRT/VTT, one plain run per cue) and [AssFile] (styled runs).
 * The delay convention is the same everywhere in the player: positive shows text later.
 */
interface OverlaySubtitleFile {
    fun activeOverlayCues(positionMs: Long, delayMs: Long): List<OverlayCue>
    fun cueBoundaries(): List<Pair<Long, Long>>
}

/** Next/previous subtitle line, over cue (start, end) boundaries. Pure, JVM-testable. */
object SubtitleLineJump {

    /**
     * The start of the next line after [positionMs] (delay already applied by the caller).
     * Strictly greater than the virtual position: the line showing now is never re-selected,
     * and a line about to start in a gap IS the next line — no grace window, which would skip
     * it. Pressing next twice in a row lands on the following line both times.
     */
    fun nextStart(boundaries: List<Pair<Long, Long>>, virtualMs: Long): Long? =
        boundaries.map { it.first }.filter { it > virtualMs }.minOrNull()

    /**
     * The start of the previous line. Media-player convention: more than a second into a line,
     * "previous" restarts the line we are on; otherwise it goes to the line before it. In a
     * gap between lines, the line just shown is the previous one. Null when there is no
     * earlier line to go to.
     */
    fun prevStart(boundaries: List<Pair<Long, Long>>, virtualMs: Long): Long? {
        val sorted = boundaries.sortedBy { it.first }
        val current = sorted.lastOrNull { it.first <= virtualMs && virtualMs < it.second }
        if (current != null) {
            if (virtualMs - current.first > 1_000) return current.first
            return sorted.getOrNull(sorted.indexOf(current) - 1)?.first
        }
        // In a gap (or before the first line): the most recent line that started before us.
        return sorted.lastOrNull { it.first <= virtualMs }?.first
    }
}
