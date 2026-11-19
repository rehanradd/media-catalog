package com.raddstream.app.player.subtitle

/**
 * A focused ASS/SSA (Advanced SubStation Alpha) parser for external subtitle files, written
 * in pure Kotlin so it runs on the JVM in tests with no Android dependency.
 *
 * Why in-app instead of libass: libass is the reference renderer (VLC, mpv, MX ship it), but
 * there is no prebuilt Maven Central AAR for it — it is a C library (FreeType + FriBidi +
 * HarfBuzz) that has to be built with the NDK, shipped per ABI, and licensed under LGPL. That
 * is a native-build project, not a subtitle feature. Media3 1.3.1 already parses ASS for
 * embedded tracks (its `SsaParser`), so the app does not need a second renderer there either.
 * What was missing was the *external* file path: an `.ass` picked by the viewer went to Media3
 * natively, its styles discarded by `setApplyEmbeddedStyles(false)`. This parser gives external
 * ASS the same styled treatment the overlay already gives SRT/VTT — colours, bold/italic,
 * sizes, and `\an`/`\pos` placement — as Android spans, with no native code.
 *
 * What it deliberately does NOT do (documented, not silently dropped):
 * - karaoke (`\k`, `\K`, `\kf`, `\ko`): timing is ignored, the words still render;
 * - fades, movement, rotation, clipping, vector drawing (`\fad`, `\move`, `\fr`, `\clip`, `\p`);
 * - embedded fonts (`\fn` and `[Fonts]`): the file's font names are not installed, and MKV
 *   attachments are not reachable from a loose file — the viewer's chosen font renders instead;
 * - secondary/outline/back colours as text colours (`\2c`, `\3c`, `\4c`): only the primary
 *   colour is the text; the outline colour becomes the shadow/plate the overlay draws.
 *
 * Colours are ASS `&HAABBGGRR&` (alpha FIRST, blue-green-red order, and alpha inverted:
 * `00` is opaque, `FF` is transparent) — the opposite of Android ARGB on both counts, so the
 * conversion is done here, once, and tested.
 */
class AssParser private constructor() {

    companion object {
        fun parse(content: String): AssFile = AssParserImpl(content).parse()
    }
}

/** Numpad alignment 1–9 from the ASS spec: 1 = bottom-left … 9 = top-right. */
enum class AssAlignment(val numpad: Int) {
    BOTTOM_LEFT(1),
    BOTTOM_CENTER(2),
    BOTTOM_RIGHT(3),
    MIDDLE_LEFT(4),
    MIDDLE_CENTER(5),
    MIDDLE_RIGHT(6),
    TOP_LEFT(7),
    TOP_CENTER(8),
    TOP_RIGHT(9);

    companion object {
        fun fromNumpad(numpad: Int): AssAlignment =
            entries.firstOrNull { it.numpad == numpad } ?: BOTTOM_CENTER
    }
}

/** Absolute position from a `\pos(x,y)` tag, as fractions of the PlayRes canvas. */
data class AssPosition(val xFraction: Float, val yFraction: Float)

/** One run of text inside a cue, after override tags have been applied. */
data class AssSegment(
    val text: String,
    val colorArgb: Int? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    /** Font size relative to the cue style's size (1.0 = the style's size). */
    val fontSizeScale: Float? = null,
)

/** A resolved style from `[V4+ Styles]` (or `[V4 Styles]` for SSA v4). */
data class AssStyle(
    val name: String,
    val fontName: String = "Arial",
    val fontSizePt: Float = 20f,
    val primaryArgb: Int = 0xFFFFFFFF.toInt(),
    val outlineArgb: Int = 0xFF000000.toInt(),
    val backArgb: Int = 0xFF000000.toInt(),
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val alignment: AssAlignment = AssAlignment.BOTTOM_CENTER,
    val marginL: Int = 0,
    val marginR: Int = 0,
    val marginV: Int = 0,
    val outlineWidth: Float = 0f,
    val shadowDepth: Float = 0f,
    val borderStyle: Int = 1,
)

/** One timed, styled subtitle event. */
data class AssCue(
    val startMs: Long,
    val endMs: Long,
    val segments: List<AssSegment>,
    val alignment: AssAlignment,
    val position: AssPosition? = null,
    val marginV: Int = 0,
    val styleFontSizePt: Float = 20f,
    val stylePrimaryArgb: Int = 0xFFFFFFFF.toInt(),
    /** The style's own weight/decoration — the base the override tags modify. */
    val styleBold: Boolean = false,
    val styleItalic: Boolean = false,
    val styleUnderline: Boolean = false,
    val styleStrike: Boolean = false,
) {
    /** Plain text, for callers that only need the words (logs, search, fallback). */
    val text: String get() = segments.joinToString("") { it.text }
}

class AssFile(
    val cues: List<AssCue>,
    val playResX: Int = 384,
    val playResY: Int = 288,
) : OverlaySubtitleFile {

    /** Cues visible at [positionMs] with [delayMs] applied (positive = later, as everywhere). */
    fun activeCues(positionMs: Long, delayMs: Long = 0): List<AssCue> {
        val virtual = positionMs - delayMs
        return cues.filter { it.startMs <= virtual && virtual < it.endMs }
    }

    override fun activeOverlayCues(positionMs: Long, delayMs: Long): List<OverlayCue> =
        activeCues(positionMs, delayMs).map { it.toOverlayCue(playResY) }

    override fun cueBoundaries(): List<Pair<Long, Long>> = cues.map { it.startMs to it.endMs }
}

private fun AssCue.toOverlayCue(playResY: Int): OverlayCue = OverlayCue(
    startMs = startMs,
    endMs = endMs,
    runs = segments.map {
        OverlayTextRun(it.text, it.colorArgb, it.bold, it.italic, it.underline, it.strike, it.fontSizeScale)
    },
    alignment = alignment,
    position = position,
    marginVFraction = if (playResY > 0) (marginV.toFloat() / playResY).coerceIn(0f, 0.5f) else 0f,
    styleFontSizePt = styleFontSizePt,
    stylePrimaryArgb = stylePrimaryArgb,
    styleBold = styleBold,
    styleItalic = styleItalic,
    styleUnderline = styleUnderline,
    styleStrike = styleStrike,
)

/**
 * `&HAABBGGRR&` (ASS) or `&HBBGGRR&` (SSA v4, no alpha) → Android ARGB.
 * ASS alpha is inverted (00 = opaque, FF = transparent) and the channels are BGR.
 * A colour that cannot be read is null, never a guess — the caller falls back to the style.
 */
fun assColorToArgb(raw: String?): Int? {
    val cleaned = raw?.trim()?.removePrefix("&H")?.removePrefix("&h")?.trimEnd('&')?.trim().orEmpty()
    if (cleaned.isEmpty() || cleaned.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    return try {
        when (cleaned.length) {
            8 -> {
                val alpha = 0xFF - cleaned.substring(0, 2).toInt(16)
                val blue = cleaned.substring(2, 4).toInt(16)
                val green = cleaned.substring(4, 6).toInt(16)
                val red = cleaned.substring(6, 8).toInt(16)
                (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
            6 -> {
                val blue = cleaned.substring(0, 2).toInt(16)
                val green = cleaned.substring(2, 4).toInt(16)
                val red = cleaned.substring(4, 6).toInt(16)
                (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }
}

/** Rec. 709 relative luminance, 0 (black) .. 1 (white), from an Android ARGB colour. */
fun argbLuminance(argb: Int): Float {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

/**
 * The Robo run-4 lesson, applied to ASS colours: a fansub style that draws dark text is
 * invisible on a dark frame. A segment colour darker than this is not the author's intent
 * surviving — it is unreadable — so the caller substitutes the viewer's own colour.
 */
const val ASS_MIN_READABLE_LUMINANCE = 0.35f

fun assColorIsReadable(argb: Int): Boolean = argbLuminance(argb) >= ASS_MIN_READABLE_LUMINANCE

private class AssParserImpl(private val content: String) {

    fun parse(): AssFile {
        var playResX = 384
        var playResY = 288
        val styles = LinkedHashMap<String, AssStyle>()
        val cues = ArrayList<AssCue>()
        var section = ""
        var styleFormat: List<String>? = null
        var eventFormat: List<String>? = null
        for (rawLine in content.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.lowercase()
                styleFormat = null
                eventFormat = null
                continue
            }
            when {
                section == "[script info]" -> {
                    val key = line.substringBefore(":").trim().lowercase()
                    val value = line.substringAfter(":", "").trim()
                    when (key) {
                        "playresx" -> value.toIntOrNull()?.takeIf { it > 0 }?.let { playResX = it }
                        "playresy" -> value.toIntOrNull()?.takeIf { it > 0 }?.let { playResY = it }
                    }
                }
                section == "[v4+ styles]" || section == "[v4 styles]" -> {
                    if (line.startsWith("Format:", ignoreCase = true)) {
                        styleFormat = line.substringAfter(":").split(",").map { it.trim().lowercase() }
                    } else if (line.startsWith("Style:", ignoreCase = true)) {
                        parseStyle(line.substringAfter(":", ""), styleFormat)?.let { styles[it.name] = it }
                    }
                }
                section == "[events]" -> {
                    if (line.startsWith("Format:", ignoreCase = true)) {
                        eventFormat = line.substringAfter(":").split(",").map { it.trim().lowercase() }
                    } else if (line.startsWith("Dialogue:", ignoreCase = true)) {
                        parseDialogue(line.substringAfter(":", ""), eventFormat, styles, playResX, playResY)
                            ?.let { cues.add(it) }
                    }
                    // Comment: lines are skipped by design — they are author notes, not subtitles.
                }
                // [Fonts], [Graphics] and anything else: not subtitle text, skipped.
            }
        }
        return AssFile(cues.sortedBy { it.startMs }, playResX, playResY)
    }

    private fun parseStyle(raw: String, format: List<String>?): AssStyle? {
        val columns = format ?: DEFAULT_STYLE_FORMAT
        val values = splitCsv(raw, columns.size)
        if (values.size < columns.size) return null
        val row = columns.zip(values).toMap()
        val name = row["name"]?.trim().orEmpty()
        if (name.isEmpty()) return null
        val alignment = row["alignment"]?.trim()?.toIntOrNull()?.let { AssAlignment.fromNumpad(it) }
            ?: AssAlignment.BOTTOM_CENTER
        return AssStyle(
            name = name,
            fontName = row["fontname"]?.trim().orEmpty().ifEmpty { "Arial" },
            fontSizePt = row["fontsize"]?.trim()?.toFloatOrNull()?.takeIf { it > 0f } ?: 20f,
            primaryArgb = assColorToArgb(row["primarycolour"]) ?: 0xFFFFFFFF.toInt(),
            outlineArgb = assColorToArgb(row["outlinecolour"]) ?: 0xFF000000.toInt(),
            backArgb = assColorToArgb(row["backcolour"]) ?: 0xFF000000.toInt(),
            bold = row["bold"]?.trim() == "-1",
            italic = row["italic"]?.trim() == "-1",
            underline = row["underline"]?.trim() == "-1",
            strike = row["strikeout"]?.trim() == "-1",
            alignment = alignment,
            marginL = row["marginl"]?.trim()?.toIntOrNull() ?: 0,
            marginR = row["marginr"]?.trim()?.toIntOrNull() ?: 0,
            marginV = row["marginv"]?.trim()?.toIntOrNull() ?: 0,
            outlineWidth = row["outline"]?.trim()?.toFloatOrNull() ?: 0f,
            shadowDepth = row["shadow"]?.trim()?.toFloatOrNull() ?: 0f,
            borderStyle = row["borderstyle"]?.trim()?.toIntOrNull() ?: 1,
        )
    }

    private fun parseDialogue(
        raw: String,
        format: List<String>?,
        styles: Map<String, AssStyle>,
        playResX: Int,
        playResY: Int,
    ): AssCue? {
        val columns = format ?: DEFAULT_EVENT_FORMAT
        val values = splitCsv(raw, columns.size)
        if (values.size < columns.size) return null
        val row = columns.zip(values).toMap()
        val start = SubtitleParser.parseTimestamp(row["start"]?.trim().orEmpty()) ?: return null
        val end = SubtitleParser.parseTimestamp(row["end"]?.trim().orEmpty()) ?: return null
        if (end <= start) return null
        val styleName = row["style"]?.trim().orEmpty()
        val style = styles[styleName] ?: styles["Default"] ?: AssStyle(name = styleName)
        val marginV = row["marginv"]?.trim()?.toIntOrNull() ?: style.marginV
        val text = values.last()
        if (text.isBlank()) return null
        val (segments, alignment, position) = parseStyledText(text, style, styles, playResX, playResY)
        if (segments.none { it.text.isNotBlank() }) return null
        return AssCue(
            startMs = start,
            endMs = end,
            segments = segments,
            alignment = alignment,
            position = position,
            marginV = marginV,
            styleFontSizePt = style.fontSizePt,
            stylePrimaryArgb = style.primaryArgb,
            styleBold = style.bold,
            styleItalic = style.italic,
            styleUnderline = style.underline,
            styleStrike = style.strike,
        )
    }

    /**
     * Splits dialogue text at override blocks. Style-affecting tags (`\b`, `\i`, underline,
     * `\s`, `\c`, `\fs`) close the current run and open a new one; cue-level tags (`\an`, `\pos`)
     * set the cue's placement; everything the renderer cannot honour (karaoke, fades, movement,
     * rotation, drawing) is dropped with its text kept.
     */
    private fun parseStyledText(
        raw: String,
        base: AssStyle,
        styles: Map<String, AssStyle>,
        playResX: Int,
        playResY: Int,
    ): Triple<List<AssSegment>, AssAlignment, AssPosition?> {
        var alignment = base.alignment
        var position: AssPosition? = null
        val segments = ArrayList<AssSegment>()
        val current = StringBuilder()
        // The run style is one mutable holder, not six variables: an override block must only
        // change the fields it actually names — a later `{\b1}` must not clear the colour an
        // earlier `{\c...}` set.
        val run = RunStyle()

        fun flush() {
            if (current.isEmpty()) return
            segments.add(
                AssSegment(
                    text = current.toString(),
                    colorArgb = run.colorArgb,
                    bold = run.bold,
                    italic = run.italic,
                    underline = run.underline,
                    strike = run.strike,
                    fontSizeScale = run.fontSizeScale,
                ),
            )
            current.clear()
        }

        fun resetTo(style: AssStyle) {
            run.colorArgb = null
            run.bold = null
            run.italic = null
            run.underline = null
            run.strike = null
            run.fontSizeScale = null
            alignment = style.alignment
        }

        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '{' -> {
                    val close = raw.indexOf('}', i)
                    if (close < 0) {
                        // An unclosed brace is a broken tag, not text — drop it and keep reading.
                        i = raw.length
                    } else {
                        flush()
                        applyOverrideBlock(
                            raw.substring(i + 1, close), base, styles, playResX, playResY, run,
                            onReset = { resetTo(it) },
                            onAlignment = { alignment = it },
                            onPosition = { position = it },
                        )
                        i = close + 1
                    }
                }
                c == '\\' && i + 1 < raw.length && (raw[i + 1] == 'N' || raw[i + 1] == 'n') -> {
                    current.append('\n')
                    i += 2
                }
                c == '\\' && i + 1 < raw.length && raw[i + 1] == 'h' -> {
                    current.append(' ')
                    i += 2
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        flush()
        return Triple(segments, alignment, position)
    }

    /** The effective run style: null on a field means "the cue style's value". */
    private class RunStyle(
        var colorArgb: Int? = null,
        var bold: Boolean? = null,
        var italic: Boolean? = null,
        var underline: Boolean? = null,
        var strike: Boolean? = null,
        var fontSizeScale: Float? = null,
    )

    private fun applyOverrideBlock(
        block: String,
        base: AssStyle,
        styles: Map<String, AssStyle>,
        playResX: Int,
        playResY: Int,
        run: RunStyle,
        onReset: (AssStyle) -> Unit,
        onAlignment: (AssAlignment) -> Unit,
        onPosition: (AssPosition) -> Unit,
    ) {
        // The block is a run of backslash commands; each is `\` + letters + an optional value
        // up to the next backslash. Unknown commands are skipped, never misread as text.
        var i = 0
        while (i < block.length) {
            if (block[i] != '\\') {
                i++
                continue
            }
            val match = OVERRIDE_COMMAND.find(block, i) ?: break
            val name = match.groupValues[1]
            val value = match.groupValues[2]
            when (name) {
                "b" -> run.bold = value != "0"
                "i" -> run.italic = value != "0"
                "u" -> run.underline = value != "0"
                "s" -> run.strike = value != "0"
                "c", "1c" -> assColorToArgb(value)?.let { run.colorArgb = it }
                "fs" -> value.toFloatOrNull()?.takeIf { it > 0f }?.let { run.fontSizeScale = it / base.fontSizePt }
                "an" -> value.toIntOrNull()?.let { onAlignment(AssAlignment.fromNumpad(it)) }
                "pos" -> parsePos(value, playResX, playResY)?.let { onPosition(it) }
                "r" -> {
                    val target = if (value.isBlank()) base else styles[value.trim()] ?: base
                    onReset(target)
                }
                // \fn (font name — not installed), \2c/\3c/\4c (karaoke/outline/back),
                // \k/\K/\kf/\ko (karaoke), \fad/\fade, \move, \fr, \fsp, \clip, \t, \p
                // and anything else: dropped, text kept. Karaoke timing is the one loss
                // a viewer could notice; the words still render in order.
                else -> Unit
            }
            i = match.range.last + 1
        }
    }

    private fun parsePos(value: String, playResX: Int, playResY: Int): AssPosition? {
        val parts = value.trim().removePrefix("(").removeSuffix(")").split(",")
        if (parts.size != 2) return null
        val x = parts[0].trim().toFloatOrNull() ?: return null
        val y = parts[1].trim().toFloatOrNull() ?: return null
        if (playResX <= 0 || playResY <= 0) return null
        return AssPosition(
            xFraction = (x / playResX).coerceIn(0f, 1f),
            yFraction = (y / playResY).coerceIn(0f, 1f),
        )
    }

    /**
     * Split a `Format:`-ordered line on commas, keeping the last column whole — dialogue text
     * and style names may contain commas, so a plain split would shred them.
     */
    private fun splitCsv(raw: String, columns: Int): List<String> {
        if (columns <= 1) return listOf(raw)
        val parts = raw.split(",", limit = columns)
        return parts.map { it.trim() }
    }

    companion object {
        private val OVERRIDE_COMMAND = Regex("""\\([a-zA-Z]+)([^\\]*)""")

        private val DEFAULT_STYLE_FORMAT = listOf(
            "name", "fontname", "fontsize", "primarycolour", "secondarycolour",
            "outlinecolour", "backcolour", "bold", "italic", "underline", "strikeout",
            "scalex", "scaley", "spacing", "angle", "borderstyle", "outline", "shadow",
            "alignment", "marginl", "marginr", "marginv", "encoding",
        )

        private val DEFAULT_EVENT_FORMAT = listOf(
            "layer", "start", "end", "style", "name", "marginl", "marginr", "marginv",
            "effect", "text",
        )
    }
}
