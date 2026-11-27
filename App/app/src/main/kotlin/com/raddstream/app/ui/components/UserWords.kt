package com.raddstream.app.ui.components

import com.raddstream.app.network.models.Post
import java.util.Locale

/**
 * App-design audit (2026-11-12): the app spoke in server words and rendered
 * raw numbers it had never validated. These pure helpers are the one place
 * that translation happens, so a screen never invents its own wording for
 * the same fact — and every rule here is unit-tested, because a rendering
 * bug ("★ 100.0", a literal "/PLOT" line) is only ever caught by a test
 * that feeds the bad input in.
 *
 * Rules:
 * - Ratings are 0–10. A 0–100 value (the catalog's old scale) is divided
 *   by 10; anything else unparseable or out of range renders nothing.
 * - Synopsis text never shows a section marker: lines that are only a
 *   "/WORD" marker ("/PLOT", "/SYNOPSIS", ...) are dropped.
 * - Quality bands carry an honest per-hour data estimate, in the same
 *   words the player quality sheet already uses.
 * - A title shown twice on one screen is the same title twice: rails
 *   dedupe on the work identity, not the row id.
 * - A displayed title never repeats what the meta line already states:
 *   a trailing year, language or type suffix is stripped ([detailTitle]),
 *   so "No Hard Feelings (2023) English Movie" paints as "No Hard
 *   Feelings" above a meta line that already reads "2023 · Movie ·
 *   English".
 */
object UserWords {

    /**
     * The rating to paint for a raw catalog value, or null when nothing
     * honest can be shown. "100.0" becomes "10.0" (the old 0–100 scale);
     * "8.6" stays "8.6"; "abc", "-1", "101" and blanks show no badge.
     * A 0.0 means "no rating recorded", not "rated zero" — it paints
     * nothing, so a tile never reads "★ 0.0" for an unrated film.
     */
    fun displayRating(raw: String?): String? {
        val value = raw?.trim()?.toDoubleOrNull() ?: return null
        val onTen = when {
            value <= 0 -> return null
            value <= 10.0 -> value
            value <= 100.0 -> value / 10.0
            else -> return null
        }
        return String.format(Locale.US, "%.1f", onTen)
    }

    /**
     * Drop section-marker lines ("/PLOT", "/SYNOPSIS", ...) from synopsis
     * text. A marker is a line whose first non-blank characters are "/"
     * followed by letters only. Ordinary text — including a "/" inside a
     * sentence — is untouched.
     */
    fun stripSectionMarkers(text: String): String {
        val marker = Regex("""^/[A-Za-z][A-Za-z ]*$""")
        return text.lineSequence()
            .filterNot { marker.matches(it.trim()) }
            .joinToString("\n")
            .trim()
    }

    /**
     * Honest data use for one quality band, or null when the band names
     * no known quality. The numbers match the player quality sheet
     * ([com.raddstream.app.player.qualityCostPerHour]) — one table, two
     * readers, so the detail screen can never quote a different cost
     * than the picker the viewer is about to tap.
     */
    fun dataEstimateForQuality(band: String): String? = when (band.trim().lowercase()) {
        "2160p", "4k", "uhd" -> "≈ 7–11 GB/hour"
        "1080p", "fullhd", "fhd" -> "≈ 3 GB/hour"
        "720p", "hd" -> "≈ 1.5 GB/hour"
        "480p" -> "≈ 700 MB/hour"
        "360p", "sd" -> "≈ 300 MB/hour"
        else -> null
    }

    /**
     * Make a long unbroken title breakable on word boundaries only.
     * Compose breaks a token like "Kesariya@100" mid-token when the card
     * is too narrow ("Kesariya@1 / 00"). A zero-width space before "@"
     * and after "-", "/" and "_" gives the layout a boundary to use —
     * "@100" itself is never split — and the characters are invisible
     * and unheard (the spoken title is the raw string, elsewhere).
     */
    fun breakableTitle(title: String): String = title
        .replace("@", "\u200B@")
        .replace("-", "-\u200B")
        .replace("/", "/\u200B")
        .replace("_", "_\u200B")

    /**
     * M2 (home review, Nov 2026): Compose's Ellipsis cuts at the last
     * fitting character, not the last word ("Strange New W…"). Given the
     * index where the layout cut the text (the first character not shown),
     * back off to the previous word boundary and append "…" — so the cut
     * lands between words. Returns the input unchanged when the cut is
     * already at a boundary, or when there is no earlier boundary to back
     * off to (one long word fills the line). Video audit #11: also returns
     * the input unchanged when backing off would leave a bare stub — a
     * single short word ("The…", "A…") — because a stub says less than
     * the mid-word cut it replaced ("The Icebl…"). A kept fragment of two
     * or more words, or one word of eight or more letters, is a real
     * fragment and still trims. Pure string work, so the boundary rule is
     * assertable without a layout.
     *
     * The stub guard is a TITLE rule. A meta line's segments are not
     * title words — "97%" is a complete match-percent segment, not a
     * stub — so [com.raddstream.app.ui.components.trimMetaLineToWordBoundary]
     * passes `stubGuard = false` and keeps trimming to it.
     */
    fun trimToWordBoundary(text: String, cutIndex: Int, stubGuard: Boolean = true): String {
        if (cutIndex <= 0 || cutIndex >= text.length) return text
        if (text[cutIndex].isWhitespace() || text[cutIndex - 1].isWhitespace()) return text
        val boundary = text.lastIndexOf(' ', cutIndex - 1)
        if (boundary <= 0) return text
        val kept = text.substring(0, boundary)
        // A stub is a lone short word: "The", "A", "It" — the cut backed
        // off past the only word that fit. Keep the layout's own cut
        // instead; the mid-word ellipsis shows strictly more of the title.
        if (stubGuard && !kept.contains(' ') && kept.length < 8) return text
        return "$kept…"
    }

    /**
     * The work identity for cross-rail dedupe: the catalog's work ref when
     * it has one, else the normalised title + year. Two rows of one film —
     * two prints, two mirrors — share one key, so the same title can never
     * appear twice on one Home screen.
     */
    fun workKey(workRef: String?, title: String, year: Int?): String {
        val ref = workRef?.trim()?.lowercase()
        if (!ref.isNullOrBlank()) return "work:$ref"
        return "title:${normalizeDedupTitle(title)}:${year ?: ""}"
    }

    /**
     * The title to paint for a detail page: the server's own display
     * title when it sent one, else the clean title, else the raw title —
     * each stripped of any year/language/type suffix the meta line
     * already states. A blank display title is no title at all, never a
     * reason to paint nothing.
     */
    fun detailTitle(
        displayTitle: String?,
        cleanTitle: String?,
        title: String,
        year: Int?,
        contentType: String?,
        languages: List<String>,
    ): String {
        val base = displayTitle?.takeIf { it.isNotBlank() }
            ?: cleanTitle?.takeIf { it.isNotBlank() }
            ?: title
        return stripTitleSuffixes(base, year, contentType, languages)
    }

    /**
     * Strip the suffixes a catalog title carries that the meta line
     * already states, applied until the title stops changing: a trailing
     * "(YYYY)" that is the post's year (or any year when the post has
     * none), a trailing language word that is one of the post's own
     * languages, a trailing season range ("S1-S2", "S01–S03", "Season 2")
     * the meta line (hero) and the episode section (Detail) already
     * state, and a trailing "Movie"/"Full Movie" for a movie or
     * "Series"/"TV Series" for a series. Only the tail is ever touched —
     * "The English Patient" keeps its English, "1917" keeps its year,
     * and a mid-title "(2023)" (as in "No Hard Feelings (2023) v2 – …")
     * survives, because the server's display title is the answer for
     * that shape, not a strip. Pure string work, so the rule is
     * assertable without a layout.
     */
    fun stripTitleSuffixes(
        title: String,
        year: Int?,
        contentType: String?,
        languages: List<String>,
    ): String {
        var current = title.trim()
        if (current.isEmpty()) return current
        val type = contentType?.trim()?.lowercase().orEmpty()
        val isMovie = type == "movie"
        val isSeries = "series" in type || "season" in type
        val knownLanguages = languages
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        val yearSuffix = Regex("""\(\s*(\d{4})\s*\)\s*$""")
        // Trailing season range, three shapes: "S1-S2" / "S01–S03"
        // (dash or en dash, optional spaces), "Season 2" / "Season 1-2",
        // and a bare "S1". The leading whitespace is required, so a title
        // that IS a season token keeps it — the remainder guard below is
        // the second half of that rule.
        val seasonSuffixes = listOf(
            Regex("""\s+S\d{1,2}\s*[-–—]\s*S?\d{1,2}\s*$""", RegexOption.IGNORE_CASE),
            Regex("""\s+Season\s+\d{1,2}(?:\s*[-–—]\s*\d{1,2})?\s*$""", RegexOption.IGNORE_CASE),
            Regex("""\s+S\d{1,2}\s*$""", RegexOption.IGNORE_CASE),
        )
        // Four passes are enough for every real suffix stack
        // ("(2023) English Movie" needs three: type, language, year);
        // the loop stops the moment a pass changes nothing.
        var changed: Boolean
        var passes = 0
        do {
            changed = false
            var next = current
            // Trailing "(YYYY)" — the post's year, or any year when the
            // post carries none. The parens must be the last thing on
            // the line, so "Apollo 13 (1995)" keeps its 13.
            yearSuffix.find(next)?.let { match ->
                val suffixYear = match.groupValues[1].toIntOrNull()
                if (suffixYear != null && (year == null || suffixYear == year)) {
                    next = next.substring(0, match.range.first).trim()
                }
            }
            // Trailing language word — only a word the post itself
            // lists, so a title that happens to end in a language name
            // the catalog never recorded keeps it.
            if (knownLanguages.isNotEmpty()) {
                val lastWord = next.substringAfterLast(' ', "").lowercase()
                if (lastWord in knownLanguages) {
                    next = next.substring(0, next.length - lastWord.length).trim()
                }
            }
            // Trailing type word — only the type the post itself is.
            next = when {
                isMovie && next.endsWith("Full Movie", ignoreCase = true) ->
                    next.dropLast("Full Movie".length).trim()
                isMovie && next.endsWith("Movie", ignoreCase = true) ->
                    next.dropLast("Movie".length).trim()
                isSeries && next.endsWith("TV Series", ignoreCase = true) ->
                    next.dropLast("TV Series".length).trim()
                isSeries && next.endsWith("Series", ignoreCase = true) ->
                    next.dropLast("Series".length).trim()
                else -> next
            }
            // Trailing season range — the seasons live in the meta line,
            // not the title. Runs inside the same loop, so a year the
            // range was hiding ("Show (2026) S1-S2") falls to the year
            // pass on the next round.
            for (seasonPattern in seasonSuffixes) {
                seasonPattern.find(next)?.let { match ->
                    val remainder = next.substring(0, match.range.first).trim()
                    if (remainder.isNotBlank()) next = remainder
                }
            }
            if (next != current) {
                current = next
                changed = true
            }
            passes++
        } while (changed && passes < 4)
        return current
    }

    /** Lowercase, punctuation to spaces: the title half of a dedupe key. */
    fun normalizeDedupTitle(title: String): String =
        title.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /**
     * Drop items whose work key was already shown on this screen. [keyOf]
     * is the work identity (work ref, or normalised title + year) — never
     * the row id, because the same film filed twice has two row ids.
     * Returns the kept items and the grown seen set, so rails filter in
     * order, first rail wins.
     *
     * [prefer] (video audit issue 39, Nov 2026): when a later row in the
     * same rail carries the same key, the winner of
     * `prefer(kept, candidate)` takes the slot — the position stays the
     * first row's, the content may be the better row's. A key already in
     * [seen] from an earlier rail still drops outright: the preference
     * only ever chooses between rows of this rail.
     */
    fun <T> dedupeAcrossRails(
        items: List<T>,
        seen: Set<String>,
        keyOf: (T) -> String,
        prefer: ((existing: T, candidate: T) -> T)? = null,
    ): Pair<List<T>, Set<String>> {
        val kept = ArrayList<T>(items.size)
        val keptIndexByKey = HashMap<String, Int>()
        val next = seen.toMutableSet()
        for (item in items) {
            val key = keyOf(item)
            if (key.isBlank()) {
                kept.add(item)
                continue
            }
            val existingIndex = keptIndexByKey[key]
            if (existingIndex == null && next.add(key)) {
                keptIndexByKey[key] = kept.size
                kept.add(item)
            } else if (existingIndex != null && prefer != null) {
                kept[existingIndex] = prefer(kept[existingIndex], item)
            }
        }
        return kept to next
    }

    /**
     * Video audit issue 39 (Nov 2026): of two rows for one work, prefer
     * the metadata-complete one — a poster and a rating beat a bare row,
     * so a tile never reads "2026" with no star while its twin carries
     * "2026 · ★ 8.3". The winner keeps the first row's position (the
     * dedupe owns the order); a tie keeps the existing row, so a complete
     * first row is never swapped for an identical twin. Pure, so the
     * choice is assertable without a layout. A rating of "0.0" or an
     * unparseable one counts as no rating ([displayRating]) — and no
     * rating is ever invented here: a work with no rating on ANY row
     * still paints none, and that gap belongs to the catalog data.
     */
    fun preferCompletePost(existing: Post, candidate: Post): Post {
        fun score(post: Post): Int {
            var score = 0
            if (!post.posterUrl.isNullOrBlank()) score++
            if (displayRating(post.imdbRating) != null) score++
            return score
        }
        return if (score(candidate) > score(existing)) candidate else existing
    }
}
