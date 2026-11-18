package com.raddstream.app.network.models

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/*
 * Helpers for the three v1 columns that are TEXT but hold a LIST.
 *
 * The truth they have to survive (verified against the live ledger, 2026-10-22):
 *  - `posts.genres`        → "Action, Adventure, Drama, Fantasy"        (CSV)
 *  - `posts.language`      → "Hindi + English + Japanese"               (not JSON either)
 *  - `posts.backdrop_urls` → '["https://a.jpg", "https://b.jpg"]'       (a JSON array
 *                            stored as a string), and sometimes one bare URL.
 * Gson cannot decode any of these into `List<String>` because the wire type is a String,
 * so [listOf] is the only sanctioned way to read them. It never throws: a malformed
 * "JSON array" falls back to CSV splitting, and a null stays an empty list.
 */
object WireText {

    /** Splits a CSV / " / "-joined / JSON-array-string column into trimmed, non-blank items. */
    fun listOf(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        if (text.startsWith("[")) {
            val parsed = try {
                val element = JsonParser.parseString(text)
                if (element.isJsonArray) {
                    element.asJsonArray.mapNotNull { item ->
                        if (item.isJsonNull) null else item.asString.trim().takeIf { it.isNotEmpty() }
                    }
                } else null
            } catch (e: JsonSyntaxException) {
                null
            } catch (e: IllegalStateException) {
                null
            }
            if (parsed != null) return parsed
        }
        // Strip stray brackets/quotes so a half-written JSON array still reads as items.
        val cleaned = text.trim('[', ']', '(', ')').replace("\"", " ")
        return splitOnAny(cleaned, ",", " / ", "+", ";", "|")
    }

    /** Splits on several separators at once, then trims and drops blanks and dups. */
    fun splitOnAny(text: String?, vararg separators: String): List<String> {
        val source = text?.trim().orEmpty()
        if (source.isEmpty()) return emptyList()
        // Qualified on purpose: inside this object an unqualified `listOf` is THIS member
        // function, so the short form would call itself forever on any non-JSON value.
        var parts: List<String> = kotlin.collections.listOf(source)
        for (separator in separators) {
            parts = parts.flatMap { it.split(separator) }
        }
        return parts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** True when a v1 URL string is worth handing to any client at all. */
    fun isHttpUrl(raw: String?): Boolean {
        val value = raw?.trim()?.lowercase().orEmpty()
        return value.startsWith("http://") || value.startsWith("https://")
    }

    /**
     * True when a wire value means "nothing was recorded" — blank, "Unknown"
     * or "N/A", case-insensitive. The catalog serves these literally on
     * ~60–100k works (language 62,654 / country 102,877 / industry 103,608
     * rows, Nov 2026), and the app must never paint them: a missing value
     * renders as nothing, never as the word "Unknown".
     */
    fun isPlaceholder(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return true
        val lower = text.lowercase()
        return lower == "unknown" || lower == "n/a"
    }

    /** The inverse of [isPlaceholder]: a value a screen may show the user. */
    fun isKnownValue(value: String?): Boolean = !isPlaceholder(value)

    /**
     * A language name a screen may show: the same placeholder rule as
     * [isKnownValue] — "Unknown" and "N/A" languages are dropped at render.
     * "Multi" is dropped too: the server writes it on multi-audio works
     * (One Piece post 1459 carries language "Multi"), and it is a
     * multi-audio marker, not a language — it painted as "Series · Multi ·
     * 1 season" on the detail meta line. A multi-audio print still says so
     * through its dual-audio badge, never through a language word.
     * (The hero's [com.raddstream.app.ui.components.isKnownLanguage] is the
     * same rule; it delegates here so the two can never drift apart.)
     */
    fun isKnownLanguage(value: String): Boolean {
        val text = value.trim()
        if (text.isEmpty()) return false
        if (text.lowercase() == "multi") return false
        return isKnownValue(value)
    }

    /** A genre name a screen may show: the same placeholder rule. */
    fun isKnownGenre(value: String): Boolean = isKnownValue(value)

    /**
     * A print label a screen may show. Besides the generic placeholders,
     * the server's own fallback label "Unknown print" (catalog_api.py:1043 —
     * a print with no describable parts) is a placeholder too: the episode
     * title falls back to "Episode N", and the quality pill is hidden — the
     * print carries no quality label at all, so there is no "Print" fallback.
     */
    fun isKnownPrintLabel(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return false
        val lower = text.lowercase()
        return lower != "unknown" && lower != "n/a" && lower != "unknown print"
    }
}
